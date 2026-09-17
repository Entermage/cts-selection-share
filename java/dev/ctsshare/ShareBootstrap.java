package dev.ctsshare;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShareBootstrap {
    private static final String TAG = "CTSShareZygisk";
    private static final String BUTTON_TAG = "cts_share_zygisk_button";
    private static final long MAX_IMAGE_AGE_MS = 120_000L;
    private static final long POLL_MS = 100L;
    private static final long FALLBACK_DELAY_MS = 48L;
    private static final long CACHE_FILE_TTL_MS = 10 * 60_000L;
    private static final long DEBUG_LOG_FILE_BYTES = 32 * 1024L;
    private static final long DEBUG_STATE_MIN_INTERVAL_MS = 250L;
    private static final Object DEBUG_LOG_LOCK = new Object();

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private static Bitmap currentSelection;
    private static long actionRowMissingSince;
    private static WeakReference<View> observedRoot = new WeakReference<>(null);
    private static ViewTreeObserver.OnPreDrawListener preDrawListener;
    private static Class<?> cachedRegionViewClass;
    private static Method cachedPeerMethod;
    private static Field cachedPeerField;
    private static Class<?> cachedPeerClass;
    private static Method cachedNormalizedRegionMethod;
    private static Field cachedActiveRegionField;
    private static final ArrayList<File> pendingShareFiles = new ArrayList<>();
    private static long lastPreDrawProbe;
    private static int sourceTaskId = -1;
    private static int currentCtsTaskId = -1;
    private static WeakReference<Activity> newlyCreatedCtsActivity = new WeakReference<>(null);
    private static WeakReference<Activity> backCallbackActivity = new WeakReference<>(null);
    private static OnBackInvokedCallback backCallback;
    private static WeakReference<Application> debugApplication = new WeakReference<>(null);
    private static String regionProbeState = "not-probed";
    private static String lastDebugState = "";
    private static long lastDebugStateAt;
    private static long recoveryUntilUptime;
    private static long lastRecoveryProbeAt;

    private ShareBootstrap() {}

    public static void init(final Application application) {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        debugApplication = new WeakReference<>(application);
        debugLog(application, "helper init pid=" + android.os.Process.myPid()
                + " sdk=" + Build.VERSION.SDK_INT);
        MAIN.post(() -> {
            cleanupCacheDirectory(application);
            application.registerActivityLifecycleCallbacks(new Callbacks());
            recoveryUntilUptime = SystemClock.uptimeMillis() + 30_000L;
            Activity recovered = findResumedCtsActivity();
            if (recovered != null) handleCtsActivityResumed(recovered, true);
            MAIN.post(POLLER);
            Log.i(TAG, "Lifecycle monitor registered");
        });
    }

    private static final Runnable POLLER = new Runnable() {
        @Override public void run() {
            try {
                Activity activity = currentActivity.get();
                long now = SystemClock.uptimeMillis();
                if (activity == null && now < recoveryUntilUptime &&
                        now - lastRecoveryProbeAt >= 500L) {
                    lastRecoveryProbeAt = now;
                    activity = findResumedCtsActivity();
                    if (activity != null) handleCtsActivityResumed(activity, true);
                }
                if (activity != null && isCtsActivity(activity)) {
                    Bitmap candidate = selectedBitmap(activity);
                    if (candidate != null && !candidate.isRecycled()) currentSelection = candidate;
                    File image = latestLensImage(activity);
                    boolean freshFile = image != null &&
                            System.currentTimeMillis() - image.lastModified() <= MAX_IMAGE_AGE_MS;
                    boolean rememberedSelection = currentSelection != null &&
                            !currentSelection.isRecycled();
                    // The selected crop can be fully visible before Google exposes
                    // its Bitmap or writes LensImages. Treat the RegionView state as
                    // independent evidence so the Share action is not skipped in
                    // that window (or for selections that never expose a Bitmap).
                    Rect activeRegion = selectedRegionOnScreen(activity);
                    logProbeState(activity, candidate, image, freshFile, activeRegion);
                    if (activeRegion != null || rememberedSelection || freshFile) {
                        ensureButton(activity, activeRegion);
                    }
                    else removeButton(activity);
                }
            } catch (Throwable error) {
                Log.e(TAG, "Poll failed", error);
            } finally {
                MAIN.postDelayed(this, POLL_MS);
            }
        }
    };

    private static boolean isCtsActivity(Activity activity) {
        String name = activity.getClass().getName().toLowerCase(Locale.US);
        return name.contains("omnient") ||
                name.contains("contextualsearch") ||
                name.contains("lensient");
    }

    private static Activity findResumedCtsActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentMethod = activityThreadClass.getDeclaredMethod(
                    "currentActivityThread");
            currentMethod.setAccessible(true);
            Object activityThread = currentMethod.invoke(null);
            if (activityThread == null) return null;

            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activities = activitiesField.get(activityThread);
            if (!(activities instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) activities).values()) {
                if (record == null) continue;
                Field activityField = findField(record.getClass(), "activity");
                if (activityField == null) continue;
                Object value = activityField.get(record);
                if (!(value instanceof Activity)) continue;
                Activity activity = (Activity) value;
                Field pausedField = findField(record.getClass(), "paused");
                boolean resumed = pausedField != null ?
                        !pausedField.getBoolean(record) :
                        activity.getWindow().getDecorView().hasWindowFocus();
                if (isCtsActivity(activity) && resumed &&
                        !activity.isFinishing() && !activity.isDestroyed()) {
                    debugLog("recovered already-resumed CTS class="
                            + activity.getClass().getName() + " task="
                            + activity.getTaskId());
                    return activity;
                }
            }
        } catch (Throwable error) {
            debugLog("CTS recovery error " + Log.getStackTraceString(error));
        }
        return null;
    }

    private static Field findField(Class<?> sourceClass, String name) {
        for (Class<?> type = sourceClass; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static File latestLensImage(Activity activity) {
        File directory = new File(activity.getFilesDir(), "LensImages");
        File[] files = directory.listFiles();
        if (files == null) return null;
        File latest = null;
        for (File file : files) {
            if (!file.isFile() || file.length() <= 0) continue;
            if (latest == null || file.lastModified() > latest.lastModified()) latest = file;
        }
        return latest;
    }

    private static Bitmap selectedBitmap(Activity activity) {
        return findSelectedBitmap(activity.getWindow().getDecorView(), null);
    }

    private static Bitmap findSelectedBitmap(View view, Bitmap best) {
        try {
            if (view instanceof ImageView) {
                ImageView imageView = (ImageView) view;
                Drawable drawable = imageView.getDrawable();
                Rect bounds = new Rect();
                boolean visible = imageView.getGlobalVisibleRect(bounds);
                String className = imageView.getClass().getName();
                String idName = "";
                if (imageView.getId() != View.NO_ID) {
                    try {
                        idName = imageView.getResources().getResourceEntryName(imageView.getId());
                    } catch (Throwable ignored) {}
                }
                boolean searchThumbnail = "lensient_searchbox_thumbnail".equals(idName);
                boolean selectionImageClass = className.contains("ShapeableImageView");
                if (visible && bounds.width() > 0 && bounds.height() > 0 &&
                        !className.contains("FrozenImageView") &&
                        (searchThumbnail || selectionImageClass) &&
                        drawable instanceof BitmapDrawable) {
                    Bitmap value = ((BitmapDrawable) drawable).getBitmap();
                    int minimum = searchThumbnail ? 32 : 200;
                    if (value != null && !value.isRecycled() &&
                            value.getWidth() >= minimum && value.getHeight() >= minimum) {
                        long area = (long) value.getWidth() * value.getHeight();
                        long bestArea = best == null ? 0L :
                                (long) best.getWidth() * best.getHeight();
                        if (area > bestArea) best = value;
                    }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    best = findSelectedBitmap(group.getChildAt(i), best);
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "Bitmap lookup failed", error);
        }
        return best;
    }

    private static void ensureButton(Activity activity, Rect activeRegion) {
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        View existingInjected = root.findViewWithTag(BUTTON_TAG);

        int actionRowId = activity.getResources().getIdentifier(
                "lens_action_menu_buttons", "id", activity.getPackageName());
        View actionRowView = actionRowId == 0 ? null : root.findViewById(actionRowId);
        ViewGroup actionRow = actionRowView instanceof ViewGroup ?
                (ViewGroup) actionRowView : null;
        TextView reference = findNativeActionReference(actionRow);
        if (reference != null && activeRegion == null) {
            actionRowMissingSince = 0L;
            currentSelection = null;
            if (existingInjected != null &&
                    existingInjected.getParent() instanceof ViewGroup) {
                ((ViewGroup) existingInjected.getParent()).removeView(existingInjected);
            }
            return;
        }
        if (actionRow != null && reference != null) {
            actionRowMissingSince = 0L;
            if (existingInjected != null && existingInjected.getParent() == actionRow) {
                keepNativeActionMenuOnScreen(activity, actionRow);
                return;
            }
            if (existingInjected != null && existingInjected.getParent() instanceof ViewGroup) {
                ((ViewGroup) existingInjected.getParent()).removeView(existingInjected);
            }
            Button button = new Button(activity);
            copyButtonAppearance(reference, button);
            button.setTag(BUTTON_TAG);
            button.setText(shareLabel());
            button.setContentDescription(shareLabel());
            button.setOnClickListener(view -> shareLatest(activity, button));

            ViewGroup.LayoutParams source = reference.getLayoutParams();
            LinearLayout.LayoutParams params;
            if (source instanceof LinearLayout.LayoutParams) {
                params = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) source);
            } else {
                params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            actionRow.addView(button, params);
            actionRow.post(() -> keepNativeActionMenuOnScreen(activity, actionRow));
            Log.i(TAG, "Share button joined lens_action_menu_buttons");
            debugLog("button joined native row region=" + rectText(activeRegion));
            return;
        }

        if (existingInjected != null && existingInjected.getParent() == actionRow &&
                actionRow != null) {
            actionRow.removeView(existingInjected);
            existingInjected = null;
        }

        if (existingInjected != null) {
            positionStandaloneShare(activity, existingInjected);
            return;
        }

        // Google may omit the entire action row when text extraction is not an
        // available action. It also removes the row briefly while rebinding it,
        // so wait for a stable absence before showing a one-button fallback.
        long now = System.currentTimeMillis();
        if (actionRowMissingSince == 0L) actionRowMissingSince = now;
        if (now - actionRowMissingSince < FALLBACK_DELAY_MS) return;
        TextView button = new TextView(activity);
        button.setTag(BUTTON_TAG);
        button.setText(shareLabel());
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(activity, 8));
        button.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);
        button.setMinHeight(dp(activity, 44));
        button.setContentDescription(shareLabel());

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(235, 43, 43, 43));
        background.setCornerRadius(dp(activity, 24));
        button.setBackground(background);
        button.setOnClickListener(view -> shareLatest(activity, button));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 44));
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = statusBarHeight(activity) + dp(activity, 92);
        root.addView(button, params);
        positionStandaloneShare(activity, button);
        button.post(() -> positionStandaloneShare(activity, button));
        Log.i(TAG, "Share button added to " + activity.getClass().getName());
        debugLog("standalone button added region=" + rectText(activeRegion));
    }

    private static TextView findNativeActionReference(ViewGroup actionRow) {
        if (actionRow == null) return null;
        for (int i = 0; i < actionRow.getChildCount(); i++) {
            View child = actionRow.getChildAt(i);
            if (child instanceof TextView && !BUTTON_TAG.equals(child.getTag())) {
                return (TextView) child;
            }
        }
        return null;
    }

    private static void positionStandaloneShare(Activity activity, View button) {
        try {
            View root = activity.getWindow().getDecorView();
            if (!(button.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            Rect selection = selectedRegionOnScreen(activity);

            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            int width = button.getWidth();
            int height = button.getHeight();
            if (width <= 0 || height <= 0) {
                int widthSpec = View.MeasureSpec.makeMeasureSpec(
                        root.getWidth(), View.MeasureSpec.AT_MOST);
                int heightSpec = View.MeasureSpec.makeMeasureSpec(
                        dp(activity, 44), View.MeasureSpec.EXACTLY);
                button.measure(widthSpec, heightSpec);
                width = button.getMeasuredWidth();
                height = button.getMeasuredHeight();
            }
            if (width <= 0 || height <= 0) return;

            int margin = dp(activity, 8);
            int gap = dp(activity, 10);
            int safeLeft = rootLocation[0] + margin;
            int safeRight = rootLocation[0] + root.getWidth() - margin;
            int safeTop = rootLocation[1] + statusBarHeight(activity) + dp(activity, 64);
            int safeBottom = rootLocation[1] + root.getHeight() - margin;

            int headerId = activity.getResources().getIdentifier(
                    "lens_overlay_buttons_container", "id", activity.getPackageName());
            View header = headerId == 0 ? null : root.findViewById(headerId);
            Rect visible = new Rect();
            if (header != null && header.getGlobalVisibleRect(visible)) {
                safeTop = Math.max(safeTop, visible.bottom + margin);
            }

            int panelId = activity.getResources().getIdentifier(
                    "lens_info_panel", "id", activity.getPackageName());
            View panel = panelId == 0 ? null : root.findViewById(panelId);
            if (panel != null && panel.getGlobalVisibleRect(visible) &&
                    visible.top > safeTop) {
                safeBottom = Math.min(safeBottom, visible.top - margin);
            }

            int x;
            int y;
            if (selection != null && !selection.isEmpty()) {
                x = selection.centerX() - width / 2;
                int above = selection.top - gap - height;
                int below = selection.bottom + gap;
                if (above >= safeTop) {
                    y = above;
                } else if (below + height <= safeBottom) {
                    y = below;
                } else {
                    y = Math.max(safeTop, Math.min(above, safeBottom - height));
                }
            } else {
                x = rootLocation[0] + (root.getWidth() - width) / 2;
                y = rootLocation[1] + statusBarHeight(activity) + dp(activity, 92);
            }
            x = Math.max(safeLeft, Math.min(x, safeRight - width));
            y = Math.max(safeTop, Math.min(y, safeBottom - height));

            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) button.getLayoutParams();
            int leftMargin = x - rootLocation[0];
            int topMargin = y - rootLocation[1];
            if (params.gravity != (Gravity.TOP | Gravity.LEFT) ||
                    params.leftMargin != leftMargin || params.topMargin != topMargin) {
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.leftMargin = leftMargin;
                params.topMargin = topMargin;
                button.setLayoutParams(params);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to position standalone share", error);
        }
    }

    private static Rect selectedRegionOnScreen(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            int id = activity.getResources().getIdentifier(
                    "region_view", "id", activity.getPackageName());
            View regionView = id == 0 ? null : root.findViewById(id);
            if (regionView == null) {
                regionProbeState = "region-view-missing id=" + id;
                return null;
            }
            if (regionView.getWidth() <= 0 || regionView.getHeight() <= 0) {
                regionProbeState = "region-view-not-laid-out size="
                        + regionView.getWidth() + "x" + regionView.getHeight();
                return null;
            }

            Object peer = null;
            if (cachedRegionViewClass != regionView.getClass()) {
                cachedRegionViewClass = regionView.getClass();
                cachedPeerMethod = null;
                cachedPeerField = null;
                try {
                    cachedPeerMethod = regionView.getClass().getDeclaredMethod("a");
                    cachedPeerMethod.setAccessible(true);
                } catch (Throwable ignored) {}
                try {
                    cachedPeerField = regionView.getClass().getDeclaredField("a");
                    cachedPeerField.setAccessible(true);
                } catch (Throwable ignored) {}
            }
            try {
                if (cachedPeerMethod != null) peer = cachedPeerMethod.invoke(regionView);
            } catch (Throwable ignored) {}
            try {
                if (peer == null && cachedPeerField != null) {
                    peer = cachedPeerField.get(regionView);
                }
            } catch (Throwable ignored) {}
            if (peer == null) peer = findRegionPeer(regionView);
            if (peer == null) {
                regionProbeState = "peer-missing view=" + regionView.getClass().getName();
                return null;
            }

            if (cachedPeerClass != peer.getClass()) {
                cachePeerReflection(peer.getClass());
                debugLog("region peer=" + peer.getClass().getName()
                        + " activeField=" + memberName(cachedActiveRegionField)
                        + " normalizedMethod=" + memberName(cachedNormalizedRegionMethod));
            }
            boolean hasActiveRegion = false;
            try {
                hasActiveRegion = cachedActiveRegionField != null &&
                        cachedActiveRegionField.get(peer) != null;
            } catch (Throwable ignored) {}
            if (!hasActiveRegion) {
                regionProbeState = "active-region-missing peer=" + peer.getClass().getName();
                return null;
            }

            RectF normalized = null;
            try {
                Object value = cachedNormalizedRegionMethod == null ? null :
                        cachedNormalizedRegionMethod.invoke(peer);
                if (value instanceof RectF) normalized = new RectF((RectF) value);
            } catch (Throwable ignored) {}
            if (!validNormalizedRegion(normalized)) {
                normalized = null;
                for (Method method : allDeclaredMethods(peer.getClass())) {
                    if (method.getParameterTypes().length != 0 ||
                            method.getReturnType() != RectF.class) continue;
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(peer);
                        if (value instanceof RectF && validNormalizedRegion((RectF) value)) {
                            cachedNormalizedRegionMethod = method;
                            normalized = new RectF((RectF) value);
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (!validNormalizedRegion(normalized)) {
                regionProbeState = "normalized-region-invalid method="
                        + memberName(cachedNormalizedRegionMethod);
                return null;
            }

            int[] location = new int[2];
            regionView.getLocationOnScreen(location);
            Rect result = new Rect(
                    location[0] + Math.round(normalized.left * regionView.getWidth()),
                    location[1] + Math.round(normalized.top * regionView.getHeight()),
                    location[0] + Math.round(normalized.right * regionView.getWidth()),
                    location[1] + Math.round(normalized.bottom * regionView.getHeight()));
            Rect rootBounds = new Rect();
            root.getGlobalVisibleRect(rootBounds);
            if (!result.intersect(rootBounds) || result.isEmpty()) {
                regionProbeState = "region-outside-window raw=" + result;
                return null;
            }
            regionProbeState = "valid " + result.flattenToString();
            return result;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to read selected region", error);
            regionProbeState = "region-error " + error.getClass().getName();
            debugLog("region exception " + Log.getStackTraceString(error));
            return null;
        }
    }

    private static void logProbeState(Activity activity, Bitmap bitmap, File image,
            boolean freshFile, Rect activeRegion) {
        try {
            View root = activity.getWindow().getDecorView();
            int rowId = activity.getResources().getIdentifier(
                    "lens_action_menu_buttons", "id", activity.getPackageName());
            View row = rowId == 0 ? null : root.findViewById(rowId);
            View button = root.findViewWithTag(BUTTON_TAG);
            String state = "probe region={" + regionProbeState + "}"
                    + " bitmap=" + bitmapText(bitmap)
                    + " lens=" + fileText(image)
                    + " fresh=" + freshFile
                    + " row=" + viewText(row)
                    + " button=" + viewText(button)
                    + " active=" + rectText(activeRegion);
            long now = SystemClock.uptimeMillis();
            if (!state.equals(lastDebugState) &&
                    now - lastDebugStateAt >= DEBUG_STATE_MIN_INTERVAL_MS) {
                lastDebugState = state;
                lastDebugStateAt = now;
                debugLog(state);
            }
        } catch (Throwable error) {
            debugLog("probe logging error=" + error.getClass().getName());
        }
    }

    private static String bitmapText(Bitmap bitmap) {
        if (bitmap == null) return "none";
        if (bitmap.isRecycled()) return "recycled";
        return bitmap.getWidth() + "x" + bitmap.getHeight();
    }

    private static String fileText(File file) {
        if (file == null) return "none";
        return file.getName() + ":" + file.length() + ":" + file.lastModified();
    }

    private static String viewText(View view) {
        if (view == null) return "none";
        return view.getClass().getSimpleName() + ":vis=" + view.getVisibility()
                + ":shown=" + view.isShown() + ":size="
                + view.getWidth() + "x" + view.getHeight();
    }

    private static String rectText(Rect rect) {
        return rect == null ? "none" : rect.flattenToString();
    }

    private static String memberName(Object member) {
        if (member instanceof Field) return ((Field) member).getName();
        if (member instanceof Method) return ((Method) member).getName();
        return "none";
    }

    private static Object findRegionPeer(View regionView) {
        for (Class<?> type = regionView.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object candidate = field.get(regionView);
                    if (candidate != null && hasRegionField(candidate.getClass())) {
                        cachedPeerField = field;
                        return candidate;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean hasRegionField(Class<?> peerClass) {
        for (Class<?> type = peerClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().getName().endsWith(".lens.view.region.Region")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ArrayList<Method> allDeclaredMethods(Class<?> sourceClass) {
        ArrayList<Method> methods = new ArrayList<>();
        for (Class<?> type = sourceClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) methods.add(method);
        }
        return methods;
    }

    private static boolean validNormalizedRegion(RectF value) {
        return value != null && !value.isEmpty() &&
                value.left >= -0.05f && value.top >= -0.05f &&
                value.right <= 1.05f && value.bottom <= 1.05f &&
                value.width() >= 0.005f && value.height() >= 0.005f;
    }

    private static void cachePeerReflection(Class<?> peerClass) {
        cachedPeerClass = peerClass;
        cachedNormalizedRegionMethod = null;
        cachedActiveRegionField = null;
        for (Class<?> type = peerClass; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    String typeName = field.getType().getName();
                    if (typeName.endsWith(".lens.view.region.Region")) {
                        cachedActiveRegionField = field;
                    }
                } catch (Throwable ignored) {}
            }
        }
        for (Class<?> type = peerClass; type != null; type = type.getSuperclass()) {
            try {
                cachedNormalizedRegionMethod = type.getDeclaredMethod("c");
                cachedNormalizedRegionMethod.setAccessible(true);
                break;
            } catch (Throwable ignored) {}
        }
    }

    private static void keepNativeActionMenuOnScreen(Activity activity, View actionRow) {
        try {
            View root = activity.getWindow().getDecorView();
            int containerId = activity.getResources().getIdentifier(
                    "lens_action_menu_container", "id", activity.getPackageName());
            View container = containerId == 0 ? actionRow : root.findViewById(containerId);
            if (container == null || container.getWidth() <= 0) return;

            int[] rootLocation = new int[2];
            int[] containerLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            container.getLocationOnScreen(containerLocation);
            int safeRight = rootLocation[0] + root.getWidth() - dp(activity, 8);
            int safeLeft = rootLocation[0] + dp(activity, 8);
            int actualLeft = containerLocation[0];
            int actualRight = containerLocation[0] + container.getWidth();
            int overflow = actualRight - safeRight;
            if (overflow > 0) {
                container.setTranslationX(container.getTranslationX() - overflow);
            } else if (actualLeft < safeLeft) {
                container.setTranslationX(
                        container.getTranslationX() + (safeLeft - actualLeft));
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to keep action menu on screen", error);
        }
    }

    private static void installPreDrawGuard(Activity activity) {
        View root = activity.getWindow().getDecorView();
        if (observedRoot.get() == root && preDrawListener != null) return;
        clearPreDrawGuard();
        observedRoot = new WeakReference<>(root);
        preDrawListener = () -> {
            try {
                Activity current = currentActivity.get();
                Bitmap selection = currentSelection;
                boolean hasSelectionBitmap = selection != null && !selection.isRecycled();
                long now = SystemClock.uptimeMillis();
                if (current == activity &&
                        (!hasSelectionBitmap || root.findViewWithTag(BUTTON_TAG) == null) &&
                        now - lastPreDrawProbe >= 32L) {
                    lastPreDrawProbe = now;
                    Bitmap candidate = selectedBitmap(activity);
                    if (candidate != null && !candidate.isRecycled()) {
                        currentSelection = candidate;
                        selection = candidate;
                        hasSelectionBitmap = true;
                    }
                }
                Rect activeRegion = current == activity ?
                        selectedRegionOnScreen(activity) : null;
                if (current == activity && (hasSelectionBitmap || activeRegion != null)) {
                    View before = root.findViewWithTag(BUTTON_TAG);
                    Object beforeParent = before == null ? null : before.getParent();
                    ensureButton(activity, activeRegion);
                    // If Google rebuilt the action row, cancel this draw once so
                    // the user never sees a frame containing only Select text.
                    View after = root.findViewWithTag(BUTTON_TAG);
                    Object afterParent = after == null ? null : after.getParent();
                    if (after != before || afterParent != beforeParent) return false;
                }
            } catch (Throwable error) {
                Log.e(TAG, "Pre-draw button guard failed", error);
            }
            return true;
        };
        root.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    private static void clearPreDrawGuard() {
        View root = observedRoot.get();
        if (root != null && preDrawListener != null) {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(preDrawListener);
        }
        observedRoot = new WeakReference<>(null);
        preDrawListener = null;
    }

    private static void copyButtonAppearance(TextView source, Button target) {
        Drawable background = source.getBackground();
        if (background != null && background.getConstantState() != null) {
            target.setBackground(background.getConstantState().newDrawable().mutate());
        } else {
            target.setBackground(background);
        }
        target.setBackgroundTintList(source.getBackgroundTintList());
        target.setBackgroundTintMode(source.getBackgroundTintMode());
        target.setTextColor(source.getTextColors());
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        target.setTypeface(source.getTypeface());
        target.setGravity(source.getGravity());
        target.setIncludeFontPadding(source.getIncludeFontPadding());
        target.setPaddingRelative(source.getPaddingStart(), source.getPaddingTop(),
                source.getPaddingEnd(), source.getPaddingBottom());
        target.setMinWidth(0);
        target.setMinimumWidth(0);
        target.setMinHeight(source.getMinimumHeight());
        target.setMinimumHeight(source.getMinimumHeight());
        target.setElevation(source.getElevation());
        target.setStateListAnimator(source.getStateListAnimator());
        target.setAllCaps(false);
        target.setClickable(true);
        target.setFocusable(true);
    }

    private static void removeButton(Activity activity) {
        ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
        View button = root.findViewWithTag(BUTTON_TAG);
        if (button != null && button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
            debugLog("button removed region={" + regionProbeState + "}");
        }
    }

    private static void shareLatest(Activity activity, TextView button) {
        Bitmap selected = selectedBitmap(activity);
        if (selected == null && currentSelection != null && !currentSelection.isRecycled()) {
            selected = currentSelection;
        }
        File image = latestLensImage(activity);
        debugLog("share clicked bitmap=" + bitmapText(selected)
                + " lens=" + fileText(image) + " region={" + regionProbeState + "}");
        if (selected == null && image == null) {
            debugLog("share rejected: no image");
            Toast.makeText(activity, noImageLabel(), Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap stableBitmap = null;
        if (selected != null) {
            try {
                stableBitmap = selected.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to copy selected bitmap", error);
            }
        }
        final Bitmap bitmapToShare = stableBitmap;
        final File fileToShare = image;
        button.setEnabled(false);
        new Thread(() -> {
            Uri uri = bitmapToShare != null ?
                    copyBitmapToCache(activity, bitmapToShare) :
                    copyToCache(activity, fileToShare);
            if (bitmapToShare != null) bitmapToShare.recycle();
            MAIN.post(() -> {
                button.setEnabled(true);
                if (uri == null || activity.isFinishing()) {
                    debugLog("share prepare failed uri=" + uri
                            + " finishing=" + activity.isFinishing());
                    Toast.makeText(activity, shareFailedLabel(), Toast.LENGTH_SHORT).show();
                    return;
                }
                String mime = bitmapToShare != null ? "image/jpeg" :
                        mimeType(fileToShare.getName());
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(ClipData.newUri(activity.getContentResolver(), "CTS image", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    activity.startActivity(Intent.createChooser(send, shareLabel()));
                    debugLog("system sharesheet started mime=" + mime);
                } catch (Throwable error) {
                    Log.e(TAG, "Unable to open sharesheet", error);
                    Toast.makeText(activity, shareFailedLabel(), Toast.LENGTH_SHORT).show();
                    debugLog("sharesheet exception " + Log.getStackTraceString(error));
                }
            });
        }, "CTSShareCopy").start();
    }

    private static Uri copyBitmapToCache(Activity activity, Bitmap bitmap) {
        File file = newCacheFile(activity, ".jpg");
        if (file == null) return null;
        try {
            try (OutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    throw new IllegalStateException("Bitmap compression failed");
                }
            }
            Uri uri = cacheUri(activity, file);
            rememberTemporaryFile(file);
            Log.i(TAG, "Selected bitmap prepared: " + bitmap.getWidth() + "x" +
                    bitmap.getHeight());
            return uri;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to cache selected bitmap", error);
            file.delete();
            return null;
        }
    }

    private static Uri copyToCache(Activity activity, File source) {
        String extension = extension(source.getName());
        File file = newCacheFile(activity, "." + extension);
        if (file == null) return null;
        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            Uri uri = cacheUri(activity, file);
            rememberTemporaryFile(file);
            return uri;
        } catch (Throwable error) {
            Log.e(TAG, "Unable to cache image for sharing", error);
            file.delete();
            return null;
        }
    }

    private static File newCacheFile(Context context, String suffix) {
        try {
            File directory = new File(context.getCacheDir(), "cts-share");
            if (!directory.isDirectory() && !directory.mkdirs()) return null;
            return File.createTempFile("selection-", suffix, directory);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to create temporary share file", error);
            return null;
        }
    }

    private static Uri cacheUri(Context context, File file) throws Exception {
        Uri uri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.getPackageName() + ".provider.fileprovider")
                .appendPath("image_share")
                .appendPath("cts-share")
                .appendPath(file.getName())
                .build();
        try (InputStream verification =
                     context.getContentResolver().openInputStream(uri)) {
            if (verification == null) {
                throw new IllegalStateException("Cache URI is unreadable");
            }
        }
        return uri;
    }

    private static void rememberTemporaryFile(File file) {
        synchronized (pendingShareFiles) {
            pendingShareFiles.add(file);
        }
        MAIN.postDelayed(() -> deleteTemporaryFile(file), CACHE_FILE_TTL_MS);
    }

    private static void deleteTemporaryFile(File file) {
        synchronized (pendingShareFiles) {
            pendingShareFiles.remove(file);
        }
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete temporary share file: " + file.getName());
        }
    }

    private static void cleanupCacheDirectory(Context context) {
        File[] files = new File(context.getCacheDir(), "cts-share").listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) return name.substring(dot + 1).toLowerCase(Locale.US);
        return "jpg";
    }

    private static String mimeType(String name) {
        String extension = extension(name);
        if ("png".equals(extension)) return "image/png";
        if ("webp".equals(extension)) return "image/webp";
        return "image/jpeg";
    }

    private static String shareLabel() {
        String language = Locale.getDefault().getLanguage();
        if ("ja".equals(language)) return "共有";
        if ("zh".equals(language)) return "分享";
        return "Share";
    }

    private static String noImageLabel() {
        return "zh".equals(Locale.getDefault().getLanguage()) ? "没有可分享的选区图片" : "No selection image";
    }

    private static String shareFailedLabel() {
        return "zh".equals(Locale.getDefault().getLanguage()) ? "准备分享图片失败" : "Unable to share image";
    }

    private static int statusBarHeight(Activity activity) {
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? 0 : activity.getResources().getDimensionPixelSize(id);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void debugLog(String message) {
        Application application = debugApplication.get();
        if (application != null) debugLog(application, message);
    }

    private static void debugLog(Context context, String message) {
        synchronized (DEBUG_LOG_LOCK) {
            try {
                File current = new File(context.getFilesDir(), "cts-share-debug.log");
                File previous = new File(context.getFilesDir(), "cts-share-debug.log.1");
                if (current.length() >= DEBUG_LOG_FILE_BYTES) {
                    if (previous.exists() && !previous.delete()) {
                        Log.w(TAG, "Unable to delete old debug log");
                    }
                    if (!current.renameTo(previous)) {
                        try (FileOutputStream truncate = new FileOutputStream(current, false)) {
                            truncate.write(new byte[0]);
                        }
                    }
                }
                String timestamp = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = timestamp + " " + message.replace('\n', ' ') + "\n";
                try (FileOutputStream output = new FileOutputStream(current, true)) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable error) {
                Log.e(TAG, "Unable to write debug log", error);
            }
        }
    }

    private static void rememberSourceTask(Activity activity) {
        try {
            ActivityManager manager = (ActivityManager)
                    activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return;
            int ctsTaskId = activity.getTaskId();
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(12);
            for (ActivityManager.RunningTaskInfo task : tasks) {
                int taskId = task.taskId;
                if (taskId >= 0 && taskId != ctsTaskId && !isCtsTask(task)) {
                    sourceTaskId = taskId;
                    Log.i(TAG, "Remembered source task=" + sourceTaskId);
                    return;
                }
            }
            sourceTaskId = -1;
            Log.w(TAG, "No source task found behind CTS task=" + ctsTaskId);
        } catch (Throwable error) {
            sourceTaskId = -1;
            Log.e(TAG, "Unable to remember source task", error);
        }
    }

    private static boolean isCtsTask(ActivityManager.RunningTaskInfo task) {
        ComponentName component = task.topActivity != null ? task.topActivity : task.baseActivity;
        if (component == null) return false;
        String name = component.getClassName().toLowerCase(Locale.US);
        return name.contains("omnient") ||
                name.contains("contextualsearch") ||
                name.contains("lensient");
    }

    private static void returnToSource(Activity activity) {
        int targetTaskId = sourceTaskId;
        boolean restored = false;
        try {
            ActivityManager manager = (ActivityManager)
                    activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && targetTaskId >= 0) {
                manager.moveTaskToFront(targetTaskId, 0);
                restored = true;
            }
        } catch (Throwable error) {
            Log.e(TAG, "Unable to restore source task=" + targetTaskId, error);
        }
        if (!restored) {
            restored = activity.moveTaskToBack(true);
        }
        sourceTaskId = -1;
        currentCtsTaskId = -1;
        Log.i(TAG, "Returned to source task=" + targetTaskId
                + " restored=" + restored);
    }

    private static void installBackCallback(Activity activity) {
        if (Build.VERSION.SDK_INT < 33 || backCallbackActivity.get() == activity) return;
        clearBackCallback();
        try {
            OnBackInvokedCallback callback = () -> returnToSource(activity);
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            backCallback = callback;
            backCallbackActivity = new WeakReference<>(activity);
            Log.i(TAG, "CTS back callback registered");
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register CTS back callback", error);
        }
    }

    private static void clearBackCallback() {
        Activity activity = backCallbackActivity.get();
        OnBackInvokedCallback callback = backCallback;
        backCallbackActivity = new WeakReference<>(null);
        backCallback = null;
        if (Build.VERSION.SDK_INT < 33 || activity == null || callback == null) return;
        try {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        } catch (Throwable error) {
            Log.w(TAG, "Unable to unregister CTS back callback", error);
        }
    }

    private static void handleCtsActivityResumed(Activity activity, boolean recovered) {
        Activity previous = currentActivity.get();
        if (previous != activity) {
            currentSelection = null;
            actionRowMissingSince = 0L;
        }
        currentActivity = new WeakReference<>(activity);
        int taskId = activity.getTaskId();
        boolean newCtsLaunch = newlyCreatedCtsActivity.get() == activity;
        if (newCtsLaunch) newlyCreatedCtsActivity = new WeakReference<>(null);
        if (sourceTaskId < 0 || newCtsLaunch) {
            currentCtsTaskId = taskId;
            int previousSourceTaskId = sourceTaskId;
            rememberSourceTask(activity);
            if (newCtsLaunch && previousSourceTaskId >= 0) {
                Log.i(TAG, "New CTS launch replaced source task="
                        + previousSourceTaskId + " with task=" + sourceTaskId);
            }
        } else if (taskId != currentCtsTaskId) {
            currentCtsTaskId = taskId;
            Log.i(TAG, "CTS task changed; preserved source task=" + sourceTaskId);
        }
        installBackCallback(activity);
        installPreDrawGuard(activity);
        Log.i(TAG, "CTS candidate resumed: " + activity.getClass().getName());
        debugLog("activity bound class=" + activity.getClass().getName()
                + " task=" + taskId + " source=" + sourceTaskId
                + " newLaunch=" + newCtsLaunch + " recovered=" + recovered);
    }

    private static final class Callbacks implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            if (isCtsActivity(activity) && state == null) {
                newlyCreatedCtsActivity = new WeakReference<>(activity);
                debugLog("activity created class=" + activity.getClass().getName()
                        + " task=" + activity.getTaskId() + " fresh=true");
            } else if (isCtsActivity(activity)) {
                debugLog("activity created class=" + activity.getClass().getName()
                        + " task=" + activity.getTaskId() + " restored=true");
            }
        }
        @Override public void onActivityStarted(Activity activity) {}

        @Override public void onActivityResumed(Activity activity) {
            if (isCtsActivity(activity)) {
                handleCtsActivityResumed(activity, false);
            }
        }

        @Override public void onActivityPaused(Activity activity) {
            // Keep the injected child while the system sharesheet is on top.
            // Google's Select text action also remains attached during pause.
            if (isCtsActivity(activity)) {
                debugLog("activity paused class=" + activity.getClass().getName()
                        + " task=" + activity.getTaskId());
            }
        }

        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        @Override public void onActivityDestroyed(Activity activity) {
            if (isCtsActivity(activity)) {
                debugLog("activity destroyed class=" + activity.getClass().getName()
                        + " task=" + activity.getTaskId());
            }
            if (backCallbackActivity.get() == activity) clearBackCallback();
            Activity current = currentActivity.get();
            if (current == activity) {
                removeButton(activity);
                clearPreDrawGuard();
                currentSelection = null;
                actionRowMissingSince = 0L;
                currentActivity = new WeakReference<>(null);
            }
        }
    }
}
