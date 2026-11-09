# 改动说明：本地相册零复制导入 / 签名修复 / 权限收敛

改的是本仓库根目录下的这份源码树。
三件事：导入本地相册不再复制、正式包签名补齐 v1+v2+v3、权限声明收敛并把「所有文件访问」改成按需申请。

> ⚠️ 本机没有 Android SDK / NDK / Gradle 缓存，**这些改动没有经过编译验证**。
> 代码是按现有 API 逐一核对写的，但仍需你在有 SDK 的机器上 `./gradlew app:assembleRelease` 过一遍。
> 文末有验证清单。

---

## 一、本地相册导入不再复制

### 原来的行为

`DownloadAlbumImporter.processAlbumFolder()` 会：

1. 在下载目录下新建一个画廊文件夹（`SpiderDen.prepareDownloadStorage()`）
2. 把源目录里每张图 `copyFile()` 进去，并重命名成 `00000001.jpg`
3. 再把第一张复制一份成 `.thumb`
4. 写 `spiderInfo`，登记为「已完成」

源码注释写得很直白：`将本地图片文件夹复制为已完成画廊`。所以磁盘占用必然翻倍，且没有开关。

### 现在的行为

只写一条数据库记录，`archiveUri` 存 `local-album:` + SAF 目录树 URI，**一个字节都不复制**。
阅读时由新增的 `LocalAlbumGalleryProvider` 直接从原目录按需读取、解码到内存。

```
导入：选中目录 → 递归枚举（只读） → 写 DownloadInfo(total/finished) → 结束
阅读：点条目 → GalleryActivity(ACTION_LOCAL_ALBUM) → LocalAlbumGalleryProvider
       → 后台线程列目录 → 按请求逐个 openInputStream() → 解码
```

### 新增 / 修改的文件

| 文件 | 改动 |
|---|---|
| `gallery/LocalAlbumGalleryProvider.java` | **新增**。SAF 目录版的画廊 provider，流式解码，不落盘 |
| `ui/.../part/DownloadAlbumImporter.kt` | 删掉复制 / 重命名 / `.thumb` / spiderInfo 逻辑；新增 `getTreeUri()`、`isTreeAccessible()` |
| `ui/GalleryActivity.java` | 新增 `ACTION_LOCAL_ALBUM`，在 `buildProvider()` 里构造新 provider |
| `ui/.../DownloadsScene.java` | 点击本地相册时校验授权并走新 action |
| `ui/.../part/DownloadAdapter.kt` | 封面改为从原目录解第一张图（原来读 `.thumb`） |
| `ui/.../part/DownloadPaginationController.kt` | 本地相册也跳过 `SpiderInfo` 读取 |
| `download/DownloadManager.java` | 重启后为 `local-album:` 条目恢复 SAF 持久授权 |

### 为什么不能直接复用 `DirGalleryProvider`

它解码时写的是 `Image.decode((FileInputStream) is, false)`。SAF 返回的是
`ParcelFileDescriptor` 包装流，强转会 `ClassCastException`。
所以新 provider 改用 `BitmapDrawable.createFromStream()` 这条路（和 `ArchiveGalleryProvider` 一致）。

### 行为差异（重要）

- ✅ 空间不再翻倍；导入几百张图瞬间完成（只枚举不复制）
- ✅ 保留原始文件名和目录结构，排序按相对路径自然序
- ⚠️ **原目录被删除/移动/改名后，画廊就打不开了**（会提示「无法访问」）。这是「不复制」的必然代价。
- ⚠️ 卸载重装后 SAF 授权失效，需要重新导入（和压缩包导入一样）
- ⚠️ 在下载列表里删除该条目只删数据库记录，**不会删你的原始文件**（这是刻意的）
- ⚠️ 不再需要先设置下载目录
- ✅ **旧条目兼容**：老版本导入的条目 `archiveUri` 也带 `local-album:` 前缀，但文件是真复制过的。
  新增 `hasLocalCopy()` 判断——只要下载目录里还能找到图，就继续走原来的 `ACTION_EH` 本地阅读路径，
  封面也优先用旧副本。所以升级不会让已有条目失效。

---

## 二、签名修复

### 根因（两条，都会导致「装不上」）

1. **`app/build.gradle` 里完全没有 `signingConfigs`**
   → `assembleRelease` 产出 `app-release-unsigned.apk`，未签名包系统直接拒绝解析，
   表现就是「解析包错误 / 应用未安装」。

2. **CI 用 `jarsigner` 签名，只产生 v1(JAR) 签名**
   而 `targetSdkVersion 30` 的应用在 **Android 11+ 被强制要求 v2 及以上**签名方案，
   只有 v1 会被判定为「没有签名」，对应 issue 里的
   `安装包没有签名` / `packageInfo is none`（小米 HyperOS、OPPO Android 17 等）。

   雪上加霜的是 `azure-pipelines.yml` 本身还是坏的：
   - 只跑 `assembleDebug`
   - `apkFiles: 'build/Ehviewer.apk'` 路径不存在
   - `jarsignerKeystoreFile: 'text.key'` 拼错，仓库里叫 `test.key`
   - 签完再 `zipalign`，会破坏 v2 签名块

### 改了什么

`app/build.gradle`：

```gradle
signingConfigs {
    release {
        storeFile ...
        enableV1Signing true   // API 23 需要（v2 是 API 24 才有的）
        enableV2Signing true   // Android 11+ 强制
        enableV3Signing true
    }
}
```

密钥来源：`环境变量 > keystore.properties > 无`。
没配置时仍然能出未签名包（仅本地调试），配置后 `release` 自动挂上签名。
AGP 的 `signingConfig` 底层用的就是 `apksigner`，不会再出现「只有 v1」的问题。

配套文件：

| 文件 | 说明 |
|---|---|
| `keystore.properties.example` | 复制成 `keystore.properties` 填密钥路径和口令 |
| `.gitignore` | 增加 `keystore.properties` / `*.jks` / `*.keystore` |
| `.github/workflows/build.yml` | 改成出 **release 签名包**，并跑 `apksigner verify`；验签失败直接让 CI 红 |
| `azure-pipelines.yml` | 重写：先解密钥 → `assembleRelease` → `apksigner verify` |

**关键防线**：两个 CI 都加了 `apksigner verify --print-certs`，
以后再出现「没签名 / 只有 v1」的包，流水线会直接失败，发不出去。

### 出包步骤

```bash
# 1. 生成自己的发布密钥（不要用仓库里公开的 test.key）
keytool -genkeypair -v -keystore keystore/release.jks -alias ehviewer \
        -keyalg RSA -keysize 2048 -validity 10000

# 2. 配置
cp keystore.properties.example keystore.properties
#   编辑 storeFile / storePassword / keyAlias / keyPassword

# 3. 构建
./gradlew app:assembleRelease
# 产物：app/build/outputs/apk/appRelease/release/app-appRelease-release.apk

# 4. 自己验一遍（重要）
$ANDROID_HOME/build-tools/<ver>/apksigner verify --verbose --print-certs <apk>
# 期望看到：Verified using v1 scheme: true / v2 scheme: true / v3 scheme: true
```

---

## 三、权限收敛

### 删掉 / 收窄的

| 权限 | 处理 | 原因 |
|---|---|---|
| `INTERNET` | 删掉重复的一行 | 原来声明了两次 |
| `ACCESS_DOWNLOAD_MANAGER` | **删除** | 空声明，代码从不使用；何况它本身也不是第三方可申请的权限，只会增加扫描器噪音 |
| `WRITE_EXTERNAL_STORAGE` | 加 `maxSdkVersion="28"` | Android 10+ 已无用 |
| `READ_EXTERNAL_STORAGE` | 加 `maxSdkVersion="32"` | Android 13 起用分区媒体权限 |
| `CAMERA` / `ACCESS_WIFI_STATE` | 保留，修正注释 | 注释原来写反了（CAMERA 被标成「获取 wifi 状态」）。实际 CAMERA 用于改头像拍照 |

### 「所有文件访问」不再开机就要

原来 `MainActivity.onInit()` 里无条件判断：没有 `MANAGE_EXTERNAL_STORAGE` 就
**每次冷启动**都往系统设置页跳一次，紧接着 `checkDownloadLocation()` 又因为写不进目录
弹一个「下载目录无效」。

现在：

- `onInit()` 不再申请
- `checkDownloadLocation()` 里只有 `ensureDir()` **真的失败**时才引导授权
- 用 `Settings.getAskedAllFilesAccess()` 记住「已引导过」，避免反复弹
- 下载目录本身不需要该权限的用户（例如选了 SAF 授权目录）完全不会被打扰

这是「权限误报」体感的主要来源：一个看图应用一启动就要「所有文件访问」，安全软件和用户都会觉得可疑。

### 仍然保留的两个高风险权限（无法既保功能又去掉）

| 权限 | 为什么留着 | 想去掉怎么办 |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | 默认下载目录 `/sdcard/EhViewer` 在 Android 11+ 必须靠它才能写 | 把下载目录改成应用私有目录或 SAF 目录 |
| `REQUEST_INSTALL_PACKAGES` | 应用内更新下载完 APK 要交给系统安装器 | 删掉这一行，更新改为手动下载安装 |

两者都在清单里加了注释，删 `REQUEST_INSTALL_PACKAGES` 只需删一行。

---

## 四、没有验证的部分 & 验证清单

### 没能验证

- **没有编译过**：本机没有 Android SDK、NDK、Gradle 缓存，`assembleRelease` 跑不起来
- **没有真机跑过**：SAF 授权、大相册列目录性能、`Image.decode` 的 BitmapDrawable 路径都没有实测
- **CI 配置没有实际触发过**

### 建议按这个顺序自测

1. `./gradlew app:assembleRelease` 能过（先确认 `enableV1Signing` 等属性在 AGP 9.3.1 上没被改名；
   若报 `Could not set unknown property`，删掉那三行即可——AGP 默认在 minSdk<24 时就会开 v1+v2+v3）
2. `apksigner verify` 三个 scheme 都是 true
3. 装到 Android 12+ 和 Android 6/7 各一台，确认都能安装
4. 导入一个 100 张左右的相册：
   - 看下载目录**没有**新增文件夹（关键验证点）
   - 打开能正常翻页、缩放
   - 杀掉进程重启，仍能打开（验证持久授权）
   - 把原目录改名，再打开应提示「无法访问」而不是崩溃
5. 相册页封面能显示
6. 卸载重装，旧条目的表现（预期：提示重新导入）
7. 首次冷启动：不应再自动跳到「所有文件访问」设置页；只有下载目录写失败时才跳

### 已知取舍

- 不复制 = 依赖原目录存活。如果你更希望「导入后原文件可以删」，那就还是得复制，
  两者不可兼得；可以后续加一个「导入时选择：引用 / 复制」的开关。
- `LocalAlbumGalleryProvider` 每次打开画廊都会重新枚举目录（几百张图约几十毫秒到几百毫秒）。
  条目多了以后可以加一层「目录快照」缓存，这次没做。

---

## 七、构建实测（2026-11-08）

环境：`E:\DevEnv`（JDK 21.0.2 + SDK 35/36 + NDK r27 + Gradle 9.5.0 全量缓存）

| 检查 | 结果 |
|---|---|
| `gradlew app:assembleDebug --offline` | **BUILD SUCCESSFUL**，APK **27.8 MB** |
| `apksigner verify` | v1 **true** / v2 **true**（debug 包无 v3，release 才有） |
| `aapt2 dump badging` | 权限 14 条，无 `ACCESS_DOWNLOAD_MANAGER`，`INTERNET` 1 条 |
| 原生库 | `libehviewer.so` / `libimage.so` 等 arm64-v8a 齐全 |

构建过程中额外需要的一处工程改动（已含在补丁里）：

1. `ndkVersion "27.0.12077973"` —— 原来是注释掉的，AGP 9.3.1 默认要 28.2.13676358，
   不钉版本换台机器就报 `NDK not configured`。

环境侧的额外要求（不在工程里）：Build-Tools 必须装 **36.0.0**。

**仍然没有验证的**：真机安装、运行时行为（导入不复制 / 原目录改名 / 冷启动不再索权）。
---

## 八、移除 Firebase，改为本地崩溃日志

### 为什么拆

`firebase-analytics` 会在合并清单时带进 4 个权限：

`
AD_ID
ACCESS_ADSERVICES_ATTRIBUTION
ACCESS_ADSERVICES_AD_ID
BIND_GET_INSTALL_REFERRER_SERVICE
`

这几个是安全软件判「跟踪/广告 SDK」的典型特征，比 `MANAGE_EXTERNAL_STORAGE` 还敏感。
而且本工程 **没有 google-services.json**，`FirebaseAnalytics` 实际从未正确初始化——
等于白挂着这些权限。

### 拆了什么

| 位置 | 改动 |
|---|---|
| `build.gradle`（根） | 删掉 `com.google.gms.google-services`、`com.google.firebase.crashlytics` 两个插件 |
| `app/build.gradle` | 删掉 google-services 条件应用块、`firebase-crashlytics` 与 `firebase-analytics` 依赖 |
| `Analytics.kt` | 整体重写为**本地**崩溃日志 |
| `ui/EhActivity.java` | 删掉 `FirebaseAnalytics.getInstance()` |
| `client/EhUrlOpener.kt` | 直接调 Crashlytics 改为 `Analytics.recordException()` |
| `updater/AppUpdater.kt` | 连带修：`okio.source`/`okio.buffer` 扩展来自 Firebase 间接带入的 okio 3.x，拆掉后改用 okio 1.17 的 Java 门面 `Okio.source()` / `Okio.buffer()` |

### 替代方案：纯本地崩溃日志

`Analytics` 现在：

- **不联网、不申请权限、不收集设备标识**
- 异常写入 `filesDir/crash/crash-<时间戳>.txt`，单份截断 256 KB，滚动保留最近 20 份
- 额外挂 `UncaughtExceptionHandler`，把以前「直接闪退、什么都不留下」的崩溃也记下来
- 记录内容：时间、caught/uncaught、应用版本、Android 版本、厂商/机型、线程名、完整堆栈
- `isEnabled` 仍读原来的「数据分析」开关，语义变成「记录本地崩溃日志」

导出：

`ash
adb shell run-as com.ecv.customview ls files/crash
adb shell run-as com.ecv.customview cat files/crash/crash-20261108-233000-123.txt > crash.txt
`

### 想接远端的话

| 方案 | 好处 | 代价 |
|---|---|---|
| **ACRA**（自建后端） | 开源、可完全自控、能只传堆栈 | 要自己搭服务端；默认会收集设备信息，需关掉 |
| **Sentry self-hosted** | 功能强、有符号化 | 依赖重、默认行为也会带遥测，要逐项关 |
| **Bugsnag / Firebase** | 开箱即用 | 第三方托管，隐私与合规问题依旧 |

对这类应用，**本地日志 + 用户主动上报**通常是最合适的：零权限、零网络、零合规风险。

### 实测结果

| | 移除前 | 移除后 |
|---|---|---|
| APK 大小 | 27.77 MB | **24.43 MB**（−3.34 MB） |
| 权限条数 | 19 | **15** |
| 广告类权限 | 4 个 | **0 个** |
| 验签 | v1/v2 | v1/v2（不变） |
### Release 包实测（全 ABI）

| 项目 | 结果 |
|---|---|
| 构建 | `BUILD SUCCESSFUL`，`app-appRelease-release.apk` **24.26 MB** |
| 签名 | **v1 true / v2 true / v3 true**（debug 包只有 v1+v2） |
| 包名 | `com.ecv.customview`（无 debug 后缀），versionCode 111 / 2.0.2.7 |
| ABI | `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` 各 5 个 `.so` |
| 权限 | 15 条，广告类 **0** 条 |
| SHA-256 | `4B0AE18288EB879BB6E183EB4B44613F54CA3686047536233BD927DB8B0EAE81` |

签名证书：`CN=EhViewer Local Release, OU=Dev, O=DevEnv, L=Local, C=CN`
（指纹 `dfbf336b…3d339d`）。**这是本地生成的密钥，不是作者的发布密钥**，
所以装不上官方版（签名冲突），必须先卸载官方版。

密钥位置：`E:\DevEnv\Keys\ehviewer-release.jks`，口令在同目录 `keystore-password.txt`。
**务必自己备份**——这个 key 决定了应用身份，丢了就再也无法覆盖升级。