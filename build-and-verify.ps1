#Requires -Version 5.1
<#
.SYNOPSIS
    构建 EhViewer 并验证签名 / 清单，专门用来验证本次改动。

.DESCRIPTION
    做四件事：
      1. 检查 JDK / Android SDK / NDK / CMake
      2. 准备签名密钥（没有就自动生成一个本地验证用的）
      3. 构建 APK
      4. 用 apksigner + aapt2 验证：v1/v2/v3 签名、包名、versionCode、targetSdk、危险权限

.EXAMPLE
    # 只验证能编译（debug，不涉及签名）
    .\build-and-verify.ps1 -DebugBuild

.EXAMPLE
    # 完整验证：自动生成验证密钥并出 release 包
    .\build-and-verify.ps1

.EXAMPLE
    # 用自己的密钥
    .\build-and-verify.ps1 -KeystorePath D:\keys\release.jks -StorePassword *** -KeyAlias ehviewer -KeyPassword ***
#>
[CmdletBinding()]
param(
    [string]$SdkPath,
    [string]$KeystorePath,
    [string]$StorePassword,
    [string]$KeyAlias,
    [string]$KeyPassword,
    [switch]$DebugBuild,
    [switch]$Offline,
    [switch]$SkipChecks
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $RepoRoot

function Write-Step($m) { Write-Host "`n==> $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "    [OK]   $m" -ForegroundColor Green }
function Write-Warn($m) { Write-Host "    [WARN] $m" -ForegroundColor Yellow }
function Stop-Fail($m)  { Write-Host "`n[FAIL] $m" -ForegroundColor Red; Pop-Location; exit 1 }

# ---------------------------------------------------------------- 1. 工具链
Write-Step '检查工具链'

if (-not $env:JAVA_HOME) {
    Write-Warn 'JAVA_HOME 未设置，将尝试使用 PATH 里的 java'
}
try {
    $javaRaw = (& java -version 2>&1) -join ' '
} catch {
    Stop-Fail '找不到 java。请安装 JDK 17 或 21，并设置 JAVA_HOME。'
}
if ($javaRaw -match 'version "(\d+)') {
    $javaMajor = [int]$Matches[1]
    Write-Ok "Java $javaMajor  ($javaRaw)"
    if ($javaMajor -gt 24) {
        Write-Warn "Gradle 9.5 + AGP 9.3 在 JDK $javaMajor 上可能报 'Unsupported class file major version'。"
        Write-Warn '若构建失败，请换 JDK 21（本项目 sourceCompatibility 就是 21）。'
    }
    if ($javaMajor -lt 17) {
        Stop-Fail "JDK 太旧（$javaMajor），AGP 9 需要 17+。"
    }
} else {
    Write-Warn "无法识别 Java 版本：$javaRaw"
}

if (-not $SdkPath) {
    $candidates = @()
    if ($env:ANDROID_HOME)      { $candidates += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT)  { $candidates += $env:ANDROID_SDK_ROOT }
    $lp = Join-Path $RepoRoot 'local.properties'
    if (Test-Path $lp) {
        $sdkLine = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($sdkLine) { $candidates += ($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '').Trim() }
    }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    $candidates += 'C:\Android\Sdk'
    $candidates += 'D:\Android\Sdk'
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'platforms'))) { $SdkPath = (Resolve-Path $c).Path; break }
    }
}

if (-not $SdkPath) {
    Stop-Fail @'
找不到 Android SDK。二选一：
  A) 装 Android Studio，打开一次 SDK Manager 让它下载 SDK；
  B) 命令行装 cmdline-tools 后执行：
       sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" "ndk;27.0.12077973" "cmake;3.22.1"
然后设置环境变量 ANDROID_HOME 指向 SDK 目录，或传 -SdkPath。
'@
}
Write-Ok "Android SDK: $SdkPath"

# 写 local.properties，保证 Gradle 找得到 SDK
$localProps = Join-Path $RepoRoot 'local.properties'
$sdkEscaped = $SdkPath -replace '\\', '/'
"sdk.dir=$sdkEscaped" | Set-Content -Path $localProps -Encoding ascii
Write-Ok "已写入 local.properties"

$btRoot = Join-Path $SdkPath 'build-tools'
if (-not (Test-Path $btRoot)) { Stop-Fail "缺少 build-tools：$btRoot。请用 sdkmanager 安装 build-tools;35.0.0" }
$buildTools = Get-ChildItem $btRoot -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { try { [version]($_.Name -replace '[^0-9.].*$', '') } catch { [version]'0.0.0' } } -Descending |
    Select-Object -First 1
if (-not $buildTools) { Stop-Fail "build-tools 里找不到 apksigner.bat" }
$BuildToolsPath = $buildTools.FullName
Write-Ok "build-tools: $($buildTools.Name)"

$ndkRoot = Join-Path $SdkPath 'ndk'
if (-not (Test-Path $ndkRoot)) {
    Write-Warn '没装 NDK。本项目含 C/C++（libwebp/libpng/giflib 等），必须装：'
    Write-Warn "  sdkmanager `"ndk;27.0.12077973`" `"cmake;3.22.1`""
} else {
    Write-Ok "NDK: $((Get-ChildItem $ndkRoot -Directory | Select-Object -First 1).Name)"
}
if (-not (Test-Path (Join-Path $SdkPath 'cmake'))) {
    Write-Warn '没装 CMake（externalNativeBuild 需要）：sdkmanager "cmake;3.22.1"'
}

# ---------------------------------------------------------------- 2. 签名
if (-not $DebugBuild) {
    Write-Step '准备签名密钥'
    if (-not $KeystorePath) { $KeystorePath = Join-Path $RepoRoot 'keystore\verify.jks' }
    if (-not $StorePassword) { $StorePassword = 'verify123456' }
    if (-not $KeyAlias)      { $KeyAlias      = 'ehviewer' }
    if (-not $KeyPassword)   { $KeyPassword   = $StorePassword }

    if (-not (Test-Path $KeystorePath)) {
        $dir = Split-Path -Parent $KeystorePath
        if ($dir) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Write-Warn "密钥不存在，生成一个【仅供本地验证】的：$KeystorePath"
        & keytool -genkeypair -keystore $KeystorePath -alias $KeyAlias `
            -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $StorePassword -keypass $KeyPassword `
            -dname "CN=EhViewer Local Verify, O=Local, C=CN" 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { Stop-Fail 'keytool 生成密钥失败' }
        Write-Warn '这个密钥只能用来验证流程，不要拿去发版。'
    } else {
        Write-Ok "使用已有密钥: $KeystorePath"
    }

    $kp = Join-Path $RepoRoot 'keystore.properties'
    @(
        "# 由 build-and-verify.ps1 生成，仅用于本地验证"
        "storeFile=$($KeystorePath -replace '\\','/')"
        "storePassword=$StorePassword"
        "keyAlias=$KeyAlias"
        "keyPassword=$KeyPassword"
    ) | Set-Content -Path $kp -Encoding utf8
    Write-Ok '已写入 keystore.properties'
}

# ---------------------------------------------------------------- 3. 构建
Write-Step '开始构建（首次会下载依赖，可能很久）'
$task = if ($DebugBuild) { 'app:assembleDebug' } else { 'app:assembleRelease' }
$gradleArgs = @($task, '--no-daemon')
if ($Offline) { $gradleArgs += '--offline' }

# Kotlin 编译器和 clang 都要在 TEMP 里建临时目录；TEMP 不可写会报
# AccessDeniedException / "unable to make temporary file"
$tempOk = $false
try {
    $probe = Join-Path $env:TEMP ("ehv_probe_" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType File -Path $probe -Force | Out-Null
    Remove-Item $probe -Force
    $tempOk = $true
} catch { $tempOk = $false }
if (-not $tempOk) {
    $localTemp = Join-Path $RepoRoot '.build-tmp'
    New-Item -ItemType Directory -Path $localTemp -Force | Out-Null
    $env:TEMP = $localTemp
    $env:TMP = $localTemp
    Write-Warn "TEMP 不可写，已临时改用 $localTemp"
}

Write-Host "    .\gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor DarkGray
& "$RepoRoot\gradlew.bat" @gradleArgs
if ($LASTEXITCODE -ne 0) {
    Stop-Fail @'
Gradle 构建失败。常见原因：
  - NDK/CMake 没装       -> sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
  - SDK 版本不对         -> sdkmanager "platforms;android-35" "build-tools;35.0.0"
  - JDK 版本不兼容       -> 换 JDK 21
  - 签名配置报 unknown property enableV1Signing
                          -> 删掉 app/build.gradle 里那三行 enableV*Signing，
                             用 AGP 默认（minSdk<24 自动启用 v1+v2+v3）
'@
}
Write-Ok '构建完成'

# ---------------------------------------------------------------- 4. 验证
Write-Step '定位 APK'
$apkDir = Join-Path $RepoRoot 'app\build\outputs\apk'
if (-not (Test-Path $apkDir)) { Stop-Fail "找不到输出目录 $apkDir" }
$apk = Get-ChildItem $apkDir -Recurse -Filter *.apk |
    Where-Object { $_.Name -notlike '*unsigned*' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $apk) { Stop-Fail '没有生成 APK' }
Write-Ok "$($apk.FullName)"
Write-Ok ("大小 {0:N2} MB" -f ($apk.Length / 1MB))
Write-Ok "SHA-256 $((Get-FileHash $apk.FullName -Algorithm SHA256).Hash)"

if ($SkipChecks) { Write-Warn '按 -SkipChecks 跳过验签'; Pop-Location; exit 0 }

Write-Step 'apksigner 验签'
$apksigner = Join-Path $BuildToolsPath 'apksigner.bat'
$signOut = (& $apksigner verify --verbose --print-certs $apk.FullName 2>&1) -join "`n"
Write-Host $signOut
if ($LASTEXITCODE -ne 0) { Stop-Fail 'apksigner verify 失败：签名无效（系统会拒绝安装）' }

$v1 = $signOut -match 'Verified using v1 scheme.*:\s*true'
$v2 = $signOut -match 'Verified using v2 scheme.*:\s*true'
$v3 = $signOut -match 'Verified using v3 scheme.*:\s*true'
if (-not $v2) { Stop-Fail '缺少 v2 签名！Android 11+ 会判定「没有签名」，这正是要修的 bug。' }
Write-Ok "v1=$v1  v2=$v2  v3=$v3"
if (-not $v1) { Write-Warn 'v1=false：Android 6（API 23）会装不上，检查 minSdk 和 enableV1Signing' }

Write-Step 'aapt2 检查清单'
$aapt2 = Join-Path $BuildToolsPath 'aapt2.exe'
$badging = (& $aapt2 dump badging $apk.FullName 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { Stop-Fail 'aapt2 dump badging 失败' }

$pkg      = if ($badging -match "package: name='([^']+)'")            { $Matches[1] } else { '?' }
$verCode  = if ($badging -match "versionCode='([^']+)'")              { $Matches[1] } else { '?' }
$verName  = if ($badging -match "versionName='([^']+)'")              { $Matches[1] } else { '?' }
$minSdk   = if ($badging -match "sdkVersion:'([^']+)'")               { $Matches[1] } else { '?' }
$tgtSdk   = if ($badging -match "targetSdkVersion:'([^']+)'")         { $Matches[1] } else { '?' }

Write-Ok "package      = $pkg"
Write-Ok "versionName   = $verName  (versionCode $verCode)"
Write-Ok "minSdk/target = $minSdk / $tgtSdk"

$perms = [regex]::Matches($badging, "uses-permission: name='([^']+)'") | ForEach-Object { $_.Groups[1].Value }
Write-Host "`n    权限清单（$($perms.Count) 个）:" -ForegroundColor DarkGray
$perms | ForEach-Object { Write-Host "      - $_" -ForegroundColor DarkGray }

$problems = @()
if ($pkg -ne 'com.ecv.customview') { $problems += "包名不对：$pkg（应为 com.ecv.customview）" }
if ($verCode -ne '111')          { $problems += "versionCode=$verCode（应为 111）" }
if ($perms -contains 'android.permission.ACCESS_DOWNLOAD_MANAGER') {
    $problems += 'ACCESS_DOWNLOAD_MANAGER 还在（本次已移除）'
}
$internetCount = ($perms | Where-Object { $_ -eq 'android.permission.INTERNET' }).Count
if ($internetCount -gt 1) { $problems += "INTERNET 重复声明 $internetCount 次" }

if ($problems.Count -gt 0) {
    Write-Host ''
    $problems | ForEach-Object { Write-Warn $_ }
} else {
    Write-Ok '清单检查通过'
}

Write-Step '结果'
Write-Host "    APK : $($apk.FullName)"
Write-Host "    SHA : $((Get-FileHash $apk.FullName -Algorithm SHA256).Hash)"
Write-Host ''
Write-Host '  下一步（行为验证，构建验证不了）：' -ForegroundColor Cyan
Write-Host '    1. adb install -r <apk>'
Write-Host '    2. 记录导入前占用： adb shell du -sh /sdcard/EhViewer'
Write-Host '    3. App 里「导入本地相册」选一个目录'
Write-Host '    4. 再看一次 du —— 数字不应变大（变大说明还在复制）'
Write-Host '    5. 打开该画廊翻几页，再杀进程重开，确认仍能读'

if ($problems.Count -gt 0) { Pop-Location; exit 1 }
Pop-Location
exit 0
