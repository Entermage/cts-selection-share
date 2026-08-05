param(
    [string]$NdkPath = ""
)

$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($NdkPath)) {
    $NdkPath = Join-Path (Split-Path -Parent $project) "ndk-r29\android-ndk-r29"
}
$sdk = $env:ANDROID_SDK_ROOT
if ([string]::IsNullOrWhiteSpace($sdk)) {
    $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $javaHome = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
}
$java = Join-Path $javaHome "bin\java.exe"
$javac = Join-Path $javaHome "bin\javac.exe"
$jar = Join-Path $javaHome "bin\jar.exe"
$androidJarSource = Join-Path $sdk "platforms\android-36.1\android.jar"
$d8 = Join-Path $sdk "build-tools\37.0.0\d8.bat"
$ndkBuild = Join-Path $NdkPath "ndk-build.cmd"
$build = Join-Path $project "build"
$classes = Join-Path $build "classes"
$dexOut = Join-Path $build "dex"
$nativeOut = Join-Path $build "native"
$stage = Join-Path $build "stage"
$moduleProp = Join-Path $project "module\module.prop"
$versionLine = Get-Content -LiteralPath $moduleProp |
    Where-Object { $_ -match '^version=' } | Select-Object -First 1
if (-not $versionLine) { throw "Missing version in module.prop" }
$version = $versionLine.Substring("version=".Length).Trim()
$zipPath = Join-Path $build "cts-share-zygisk-v$version.zip"

foreach ($required in @($java, $javac, $jar, $androidJarSource, $d8, $ndkBuild)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build dependency: $required" }
}

foreach ($directory in @($classes, $dexOut, $nativeOut, $stage)) {
    if (Test-Path -LiteralPath $directory) { Remove-Item -LiteralPath $directory -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

$androidJar = $androidJarSource

$sources = Get-ChildItem -LiteralPath (Join-Path $project "java") -Filter *.java -Recurse |
    Select-Object -ExpandProperty FullName
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Push-Location $classes
try { & $jar cf (Join-Path $build "helper.jar") . } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

$env:JAVA_HOME = $javaHome
& $d8 --min-api 30 --output $dexOut (Join-Path $build "helper.jar")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$ndkArgs = @(
    "NDK_PROJECT_PATH=$project",
    "APP_BUILD_SCRIPT=$(Join-Path $project 'jni\Android.mk')",
    "NDK_APPLICATION_MK=$(Join-Path $project 'jni\Application.mk')",
    "NDK_OUT=$(Join-Path $nativeOut 'obj')",
    "NDK_LIBS_OUT=$(Join-Path $nativeOut 'libs')"
)
& $ndkBuild $ndkArgs
if ($LASTEXITCODE -ne 0) { throw "ndk-build failed" }

Copy-Item -LiteralPath $moduleProp -Destination $stage
Copy-Item -LiteralPath (Join-Path $project "module\customize.sh") -Destination $stage
Copy-Item -LiteralPath (Join-Path $dexOut "classes.dex") -Destination (Join-Path $stage "helper.dex")
New-Item -ItemType File -Force -Path (Join-Path $stage "skip_mount") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $stage "zygisk") | Out-Null
Copy-Item -LiteralPath (Join-Path $nativeOut "libs\arm64-v8a\libctsshare.so") `
    -Destination (Join-Path $stage "zygisk\arm64-v8a.so")

& tar.exe -a -c -f $zipPath -C $stage zygisk customize.sh helper.dex module.prop skip_mount
if ($LASTEXITCODE -ne 0) { throw "zip packaging failed" }
Write-Host "Built: $zipPath"
