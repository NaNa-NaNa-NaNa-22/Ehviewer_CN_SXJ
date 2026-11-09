/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.ehviewer

import android.content.Context
import android.os.Build
import android.util.Log
import com.hippo.scene.SceneFragment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地崩溃日志。
 *
 * 原来这里接的是 Firebase Analytics / Crashlytics：
 *   - 会往构建里带进 AD_ID、ACCESS_ADSERVICES_AD_ID、BIND_GET_INSTALL_REFERRER_SERVICE 等权限
 *   - 会上报设备标识、页面浏览、异常堆栈到 Google
 *   - 而本工程根本没有 google-services.json，Analytics 实际也没被正确初始化
 *
 * 现在改为**完全本地**：
 *   - 不联网、不申请任何权限、不收集设备标识
 *   - 异常堆栈写到 应用私有目录 files/crash/ 下，滚动保留最近 [MAX_FILES] 份
 *   - 同时挂一个 UncaughtExceptionHandler，把以前会直接闪退、什么都不留下的崩溃也记下来
 *
 * 日志导出：
 *   adb shell run-as com.ecv.customview ls files/crash
 *   adb shell run-as com.ecv.customview cat files/crash/xxx.txt > crash.txt
 * 或者用系统「设置 → 应用 → EhViewer → 存储」里看不到也没关系，
 * 后续可以加一个「导出崩溃日志」的分享入口（见 [getCrashDir]）。
 */
object Analytics {
    private const val LOG_TAG = "Analytics"

    /** 崩溃日志目录（相对 filesDir） */
    private const val CRASH_DIR = "crash"

    /** 最多保留多少份 */
    private const val MAX_FILES = 20

    /** 单份最大字节数，超出截断堆栈 */
    private const val MAX_BYTES = 256 * 1024

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var crashHandlerInstalled = false

    @JvmStatic
    fun start(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (!crashHandlerInstalled) {
            installCrashHandler()
        }
    }

    /**
     * 是否记录崩溃日志。沿用原来的「数据分析」开关，语义变成纯本地的日志开关。
     */
    @JvmStatic
    val isEnabled: Boolean
        get() = Settings.getEnableAnalytics()

    /**
     * 原来会上报页面浏览。纯本地实现下没有意义，保留空实现以维持调用点不变。
     */
    @JvmStatic
    fun onSceneView(scene: SceneFragment) {
        // no-op
    }

    @JvmStatic
    fun recordException(e: Throwable) {
        Log.e(LOG_TAG, "Unexpected error raised", e)
        if (!isEnabled) {
            return
        }
        try {
            writeCrash(e, caught = true)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "Failed to write crash log", t)
        }
    }

    /**
     * 崩溃日志目录，供后续做「导出」入口时使用。
     */
    @JvmStatic
    fun getCrashDir(context: Context): File {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 无论开关如何，未捕获异常都要留一份，否则用户只会看到「闪退」
                writeCrash(throwable, caught = false)
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Failed to write uncaught crash log", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
        crashHandlerInstalled = true
    }

    private fun writeCrash(e: Throwable, caught: Boolean) {
        val context = appContext ?: return
        val dir = getCrashDir(context)

        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(now)
        val file = File(dir, "crash-$stamp.txt")

        val body = buildString {
            append("time      : ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(now)).append('\n')
            append("kind      : ").append(if (caught) "caught" else "uncaught").append('\n')
            append("app       : ").append(BuildConfig.APPLICATION_ID).append(' ')
            append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("android   : ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("device    : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("thread    : ").append(Thread.currentThread().name).append('\n')
            append('\n')
            append(Log.getStackTraceString(e))
        }

        val text = if (body.length > MAX_BYTES) body.substring(0, MAX_BYTES) + "\n...[truncated]" else body
        file.writeText(text, Charsets.UTF_8)

        prune(dir)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") } ?: return
        if (files.size <= MAX_FILES) {
            return
        }
        files.sortByDescending { it.lastModified() }
        for (i in MAX_FILES until files.size) {
            files[i].delete()
        }
    }
}
