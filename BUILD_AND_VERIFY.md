# 打包与验证指南

环境已经装好并通过实测，**可以离线构建**。

| | 位置 |
|---|---|
| JDK 21 | `E:\DevEnv\Java\jdk-21.0.2` |
| Android SDK | `E:\DevEnv\Android\Sdk` |
| Gradle 缓存（含 9.5.0 发行版 + 全部依赖） | `E:\DevEnv\Caches\gradle` |
| 安装包归档 | `E:\DevEnv\Installers` |

环境变量已是用户级，**新开终端**生效：

```
JAVA_HOME         = E:\DevEnv\Java\jdk-21.0.2
ANDROID_HOME      = E:\DevEnv\Android\Sdk
ANDROID_SDK_ROOT  = E:\DevEnv\Android\Sdk
ANDROID_USER_HOME = E:\DevEnv\Caches\android-home
GRADLE_USER_HOME  = E:\DevEnv\Caches\gradle
```

详见 [E:\DevEnv\README.md](E:\DevEnv\README.md)。

---

## 已经验证过的

| 项目 | 结果 |
|---|---|
| JDK | `openjdk version "21.0.2"` |
| Gradle | `9.5.0`（从 `GRADLE_USER_HOME` 里加载，不联网） |
| NDK | `27.0.12077973` |
| **离线构建** | `BUILD SUCCESSFUL`，产物 `app-appRelease-debug.apk` **24.43 MB** |
| 签名 | `apksigner verify` → v1 **true** / v2 **true** |
| 权限清单 | **15 条**（14 自有 + 1 个 androidx 动态接收器权限），**0 个广告类权限**，无 `ACCESS_DOWNLOAD_MANAGER` |

## 构建（三选一）

```powershell
# 在仓库根目录执行（与本文档同级）

# A. 离线调试包（最快，13 秒，验证改动能否编译）
powershell -ExecutionPolicy Bypass -File .\build-and-verify.ps1 -DebugBuild -Offline

# B. 正式包（需要 keystore.properties；没有就自动生成验证用的）
powershell -ExecutionPolicy Bypass -File .\build-and-verify.ps1 -Offline

# C. 直接 gradle
.\gradlew.bat app:assembleDebug --offline --no-daemon
```

产物：

```
app/build/outputs/apk/appRelease/debug/app-appRelease-debug.apk          ← 正常
app/build/intermediates/apk/appRelease/debug/app-appRelease-debug.apk    ← 用 -Pandroid.injected.build.abi 时在这
```

> 本机 PowerShell 执行策略会拦脚本，务必带 `-ExecutionPolicy Bypass`。

## 正式签名

```powershell
keytool -genkeypair -v -keystore keystore\release.jks -alias ehviewer `
        -keyalg RSA -keysize 2048 -validity 10000

copy keystore.properties.example keystore.properties
# 填 storeFile / storePassword / keyAlias / keyPassword

.\gradlew.bat app:assembleRelease --offline --no-daemon
```

---

## 验签输出怎么读

```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

| 现象 | 含义 |
|---|---|
| `v2 = false` | **要修的签名 bug 没修好**，Android 11+ 判「没有签名」 |
| `v1 = false` | Android 6（API 23）装不上（v2 是 API 24 才有的） |
| `DOES NOT VERIFY` | 签名被破坏，通常签完又跑了 zipalign |

> 注意：**debug 包只有 v1+v2**（AGP 用 debug keystore），这是正常的。
> release 包才是 v1+v2+v3。

## 权限核对

`aapt2 dump badging` 里应满足：

- `uses-permission` 里 **没有** `ACCESS_DOWNLOAD_MANAGER`
- `android.permission.INTERNET` 只出现 **1 次**
- `WRITE_EXTERNAL_STORAGE` 带 `maxSdkVersion='28'`
- `READ_EXTERNAL_STORAGE` 带 `maxSdkVersion='32'`

实测产物里除了这 14 条自有权限，还有 1 条 androidx 自动生成的
`<包名>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`（用于 API 33+ 的注册接收器，属正常现象）。

**Firebase 已整条移除**，所以不再有 `AD_ID`、`ACCESS_ADSERVICES_ATTRIBUTION`、
`ACCESS_ADSERVICES_AD_ID`、`BIND_GET_INSTALL_REFERRER_SERVICE`。
崩溃改为写本地日志：

```bash
adb shell run-as com.ecv.customview ls files/crash
adb shell run-as com.ecv.customview cat files/crash/crash-20261108-233000-123.txt > crash.txt
```

---

## 常见报错对照

| 报错 | 原因 | 处理 |
|---|---|---|
| `SDK location not found` | 没 `local.properties` 也没 `ANDROID_HOME` | 脚本会自动写；或手动 `sdk.dir=E:/DevEnv/Android/Sdk` |
| `Failed to find Build Tools revision 36.0.0` | AGP 9.3.1 默认要 36 | 已装 `build-tools;36.0.0` |
| `NDK not configured. Preferred NDK version is '28.2.13676358'` | 没钉版本 | 项目已加 `ndkVersion "27.0.12077973"` |
| `AccessDeniedException: ...\Temp\kotlin-compiler-in-...` | TEMP 不可写 | 把 `TEMP`/`TMP` 指到可写目录；脚本会自动兜底 |
| `clang: error: unable to make temporary file: Permission denied` | 同上 | 同上 |
| `AccessDeniedException: ...\.android\debug.keystore.lock` | 用户目录只读 | 设 `ANDROID_USER_HOME` 到可写目录 |
| `Could not create an instance of type AndroidLocationsBuildService` | 误设了旧的 `ANDROID_SDK_HOME` | 删掉这个变量 |
| `Unsupported class file major version` | 用 JDK 25 跑 | 换 JDK 21 |
| `Could not set unknown property 'enableV1Signing'` | AGP 9 DSL 改名 | 删掉那 3 行，AGP 默认在 minSdk<24 时就开 v1+v2+v3 |
| 产物是 `-unsigned.apk` | 没读到 `keystore.properties` | 文件要放**仓库根目录** |

---

## 构建验证不了的部分：运行时行为

「导入不再复制」只能跑起来看：

```powershell
adb install -r app\build\outputs\apk\appRelease\debug\app-appRelease-debug.apk

adb shell du -sm /sdcard/EhViewer        # 导入前
# App 里：下载 → 菜单 → 导入本地相册 → 选一个目录
adb shell du -sm /sdcard/EhViewer        # 数字不应变大
```

再补两个边界：

1. 原目录改名后再点该条目 → 应提示「无法访问」，不能崩溃
2. 杀进程重开 → 仍能读（验证 SAF 持久授权）

---

## 验证清单

- [x] 离线构建通过
- [x] APK 27.8 MB，v1+v2 验签通过
- [x] 权限清单符合预期（14 个自有权限，无 `ACCESS_DOWNLOAD_MANAGER`）
- [x] arm64-v8a 原生库都在（`libehviewer.so` / `libimage.so` / …）
- [ ] 装到真机（Android 12+ 和 Android 6 各一台）
- [ ] 导入相册后 `/sdcard/EhViewer` 占用不变
- [ ] 原目录改名后提示「无法访问」而非崩溃
- [ ] 首次冷启动不再自动跳「所有文件访问」设置页
