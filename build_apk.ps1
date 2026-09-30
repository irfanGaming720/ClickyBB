# ClickyBB APK Build Script (Headless compilation for Android 4.3 / BB10)
$ErrorActionPreference = "Stop"

$BUILD_TOOLS = "C:\Users\LENOVO LOQ 15\AppData\Local\Android\Sdk\build-tools\28.0.3"
$ANDROID_JAR = "C:\Users\LENOVO LOQ 15\AppData\Local\Android\Sdk\platforms\android-28\android.jar"
$JAVA_BIN    = "C:\Program Files\Android\Android Studio\jbr\bin"
$KEYSTORE    = "C:\Users\LENOVO LOQ 15\.android\debug.keystore"

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Building ClickyBB.apk for BlackBerry Q10" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Clean build directory
$BUILD_DIR = "build"
if (Test-Path $BUILD_DIR) {
    Remove-Item -Recurse -Force $BUILD_DIR
}
New-Item -ItemType Directory -Path "$BUILD_DIR\gen", "$BUILD_DIR\obj", "$BUILD_DIR\bin" -Force | Out-Null

# 2. Generate R.java with AAPT
Write-Host "[1/6] Generating R.java via AAPT..." -ForegroundColor Yellow
& "$BUILD_TOOLS\aapt.exe" package -f -m -J "$BUILD_DIR\gen" -M "AndroidManifest.xml" -S "res" -I "$ANDROID_JAR"
if ($LASTEXITCODE -ne 0) { throw "AAPT failed to generate R.java" }

# 3. Compile Java source code
Write-Host "[2/6] Compiling Java classes with javac..." -ForegroundColor Yellow
$javaFiles = Get-ChildItem -Path "$BUILD_DIR\gen", "src" -Filter *.java -Recurse | Select-Object -ExpandProperty FullName
& "$JAVA_BIN\javac.exe" -source 8 -target 8 -g:none -bootclasspath "$ANDROID_JAR" -cp "$ANDROID_JAR" -d "$BUILD_DIR\obj" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

# 4. Generate classes.dex via D8 (min-api 18)
Write-Host "[3/6] Compiling DEX bytecode with D8 (min-api 18)..." -ForegroundColor Yellow
$classFiles = Get-ChildItem -Path "$BUILD_DIR\obj" -Filter *.class -Recurse | Select-Object -ExpandProperty FullName
& "$JAVA_BIN\java.exe" -jar "$BUILD_TOOLS\lib\d8.jar" --release --min-api 18 --lib "$ANDROID_JAR" --output "$BUILD_DIR\bin" $classFiles
if ($LASTEXITCODE -ne 0) { throw "D8 failed to generate DEX" }

# 5. Package resources into unaligned APK
Write-Host "[4/6] Packaging resources with AAPT..." -ForegroundColor Yellow
& "$BUILD_TOOLS\aapt.exe" package -f -M "AndroidManifest.xml" -S "res" -I "$ANDROID_JAR" -F "$BUILD_DIR\bin\unaligned.apk"
if ($LASTEXITCODE -ne 0) { throw "AAPT failed to package resources" }

# Add classes.dex into unaligned.apk
Push-Location "$BUILD_DIR\bin"
try {
    & "$BUILD_TOOLS\aapt.exe" add "unaligned.apk" "classes.dex"
    if ($LASTEXITCODE -ne 0) { throw "AAPT failed to add classes.dex" }
} finally {
    Pop-Location
}

# 6. Zipalign APK (4-byte alignment)
Write-Host "[5/6] Aligning APK with zipalign..." -ForegroundColor Yellow
& "$BUILD_TOOLS\zipalign.exe" -f -v 4 "$BUILD_DIR\bin\unaligned.apk" "$BUILD_DIR\bin\aligned.apk" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

# 7. Sign APK with v1 + v2 signature for Android 4.3 / BlackBerry 10 compatibility
Write-Host "[6/6] Signing APK with apksigner (v1 enabled for BB10)..." -ForegroundColor Yellow
& "$JAVA_BIN\java.exe" -jar "$BUILD_TOOLS\lib\apksigner.jar" sign `
    --ks "$KEYSTORE" `
    --ks-pass "pass:android" `
    --key-pass "pass:android" `
    --ks-key-alias "androiddebugkey" `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --out "ClickyBB.apk" `
    "$BUILD_DIR\bin\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

# Verify APK
Write-Host "Verifying signatures..." -ForegroundColor Yellow
& "$JAVA_BIN\java.exe" -jar "$BUILD_TOOLS\lib\apksigner.jar" verify --verbose "ClickyBB.apk"

$apk = Get-Item "ClickyBB.apk"
Write-Host "=========================================" -ForegroundColor Green
Write-Host " SUCCESS! Generated: $($apk.FullName)" -ForegroundColor Green
Write-Host " Size: $($apk.Length) bytes ($([math]::Round($apk.Length / 1024, 2)) KB)" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
