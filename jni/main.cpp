#include <android/log.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <unistd.h>

#include <stdlib.h>
#include <string.h>

#include "zygisk.hpp"

#define LOG_TAG "CTSShareZygisk"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char *kTargetProcess =
        "com.google.android.googlequicksearchbox:googleapp";
constexpr const char *kHelperDex = "helper.dex";

JavaVM *g_vm = nullptr;
jclass g_bootstrap_class = nullptr;

void clear_exception(JNIEnv *env, const char *where) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("JNI exception at %s", where);
    }
}

void *bootstrap_thread(void *) {
    JNIEnv *env = nullptr;
    if (g_vm == nullptr ||
        g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("Unable to attach bootstrap thread");
        return nullptr;
    }

    jclass activity_thread = env->FindClass("android/app/ActivityThread");
    if (activity_thread == nullptr) {
        clear_exception(env, "FindClass(ActivityThread)");
        g_vm->DetachCurrentThread();
        return nullptr;
    }
    jmethodID current_application = env->GetStaticMethodID(
            activity_thread, "currentApplication", "()Landroid/app/Application;");
    if (current_application == nullptr) {
        clear_exception(env, "ActivityThread.currentApplication");
        env->DeleteLocalRef(activity_thread);
        g_vm->DetachCurrentThread();
        return nullptr;
    }

    jobject application = nullptr;
    for (int attempt = 0; attempt < 120 && application == nullptr; ++attempt) {
        application = env->CallStaticObjectMethod(activity_thread, current_application);
        if (env->ExceptionCheck()) {
            clear_exception(env, "currentApplication invoke");
            application = nullptr;
        }
        if (application == nullptr) {
            usleep(50000);
        }
    }

    if (application != nullptr && g_bootstrap_class != nullptr) {
        jmethodID init = env->GetStaticMethodID(
                g_bootstrap_class, "init", "(Landroid/app/Application;)V");
        if (init != nullptr) {
            env->CallStaticVoidMethod(g_bootstrap_class, init, application);
            clear_exception(env, "ShareBootstrap.init");
            LOGI("Share helper initialized");
        } else {
            clear_exception(env, "GetStaticMethodID(ShareBootstrap.init)");
        }
    } else {
        LOGE("Application was not available before timeout");
    }

    if (application != nullptr) env->DeleteLocalRef(application);
    env->DeleteLocalRef(activity_thread);
    g_vm->DetachCurrentThread();
    return nullptr;
}

bool read_all(int fd, unsigned char *buffer, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t count = read(fd, buffer + offset, size - offset);
        if (count <= 0) return false;
        offset += static_cast<size_t>(count);
    }
    return true;
}

}  // namespace

class CTSShareModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        env_->GetJavaVM(&g_vm);
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        const char *process = env_->GetStringUTFChars(args->nice_name, nullptr);
        target_ = process != nullptr && strcmp(process, kTargetProcess) == 0;
        if (process != nullptr) env_->ReleaseStringUTFChars(args->nice_name, process);

        if (!target_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        int module_dir = api_->getModuleDir();
        if (module_dir < 0) {
            LOGE("getModuleDir failed");
            return;
        }
        int dex_fd = openat(module_dir, kHelperDex, O_RDONLY | O_CLOEXEC);
        close(module_dir);
        if (dex_fd < 0) {
            LOGE("Unable to open %s", kHelperDex);
            return;
        }

        struct stat st{};
        if (fstat(dex_fd, &st) != 0 || st.st_size <= 0) {
            LOGE("Invalid helper dex");
            close(dex_fd);
            return;
        }
        dex_size_ = static_cast<size_t>(st.st_size);
        dex_data_ = static_cast<unsigned char *>(malloc(dex_size_));
        if (dex_data_ == nullptr || !read_all(dex_fd, dex_data_, dex_size_)) {
            LOGE("Unable to read helper dex");
            free(dex_data_);
            dex_data_ = nullptr;
            dex_size_ = 0;
        }
        close(dex_fd);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!target_ || dex_data_ == nullptr || dex_size_ == 0) return;

        jobject buffer = env_->NewDirectByteBuffer(dex_data_, dex_size_);
        if (buffer == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "NewDirectByteBuffer");
            return;
        }
        jclass class_loader_class = env_->FindClass("java/lang/ClassLoader");
        if (class_loader_class == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "FindClass(ClassLoader)");
            env_->DeleteLocalRef(buffer);
            return;
        }
        jmethodID get_system = env_->GetStaticMethodID(
                class_loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        if (get_system == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "ClassLoader.getSystemClassLoader");
            env_->DeleteLocalRef(class_loader_class);
            env_->DeleteLocalRef(buffer);
            return;
        }
        jobject parent = env_->CallStaticObjectMethod(class_loader_class, get_system);
        if (parent == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "getSystemClassLoader invoke");
            env_->DeleteLocalRef(class_loader_class);
            env_->DeleteLocalRef(buffer);
            return;
        }

        jclass dex_loader_class = env_->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (dex_loader_class == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "FindClass(InMemoryDexClassLoader)");
            env_->DeleteLocalRef(parent);
            env_->DeleteLocalRef(class_loader_class);
            env_->DeleteLocalRef(buffer);
            return;
        }
        jmethodID ctor = env_->GetMethodID(
                dex_loader_class, "<init>",
                "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        if (ctor == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "InMemoryDexClassLoader constructor");
            env_->DeleteLocalRef(dex_loader_class);
            env_->DeleteLocalRef(parent);
            env_->DeleteLocalRef(class_loader_class);
            env_->DeleteLocalRef(buffer);
            return;
        }
        jobject loader = env_->NewObject(dex_loader_class, ctor, buffer, parent);
        if (loader == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "InMemoryDexClassLoader create");
            env_->DeleteLocalRef(dex_loader_class);
            env_->DeleteLocalRef(parent);
            env_->DeleteLocalRef(class_loader_class);
            env_->DeleteLocalRef(buffer);
            return;
        }

        jmethodID load_class = env_->GetMethodID(
                class_loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (load_class == nullptr || env_->ExceptionCheck()) {
            clear_exception(env_, "ClassLoader.loadClass");
            env_->DeleteLocalRef(loader);
            env_->DeleteLocalRef(dex_loader_class);
            env_->DeleteLocalRef(parent);
            env_->DeleteLocalRef(class_loader_class);
            env_->DeleteLocalRef(buffer);
            return;
        }
        jstring class_name = env_->NewStringUTF("dev.ctsshare.ShareBootstrap");
        jobject loaded_class = env_->CallObjectMethod(loader, load_class, class_name);
        if (env_->ExceptionCheck() || loaded_class == nullptr) {
            clear_exception(env_, "loadClass(ShareBootstrap)");
            return;
        }
        g_bootstrap_class = static_cast<jclass>(env_->NewGlobalRef(loaded_class));

        env_->DeleteLocalRef(loaded_class);
        env_->DeleteLocalRef(class_name);
        env_->DeleteLocalRef(loader);
        env_->DeleteLocalRef(dex_loader_class);
        env_->DeleteLocalRef(parent);
        env_->DeleteLocalRef(class_loader_class);
        env_->DeleteLocalRef(buffer);

        pthread_t thread;
        if (pthread_create(&thread, nullptr, bootstrap_thread, nullptr) == 0) {
            pthread_detach(thread);
        } else {
            LOGE("Unable to create bootstrap thread");
        }
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool target_ = false;
    unsigned char *dex_data_ = nullptr;
    size_t dex_size_ = 0;
};

REGISTER_ZYGISK_MODULE(CTSShareModule)
