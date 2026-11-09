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
package com.hippo.ehviewer.ui.scene.download.part

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.gallery.GalleryProvider2
import com.hippo.ehviewer.spider.SpiderDen
import com.hippo.lib.yorozuya.StringUtils
import com.hippo.unifile.UniFile
import com.hippo.util.NaturalComparator
import java.util.Collections

/**
 * 将本地图片文件夹登记为已完成画廊。
 *
 * <p>注意：这里<b>不复制任何文件</b>。只在数据库里写入一条 DownloadInfo，把 SAF 目录树的
 * URI 存进 archiveUri；阅读时由 [com.hippo.ehviewer.gallery.LocalAlbumGalleryProvider]
 * 直接从原目录按需读取。所以导入后不会产生第二份图片，删除原目录后画廊才会失效。
 *
 * <p>旧实现是复制到下载目录并重命名为 00000001.jpg，磁盘占用翻倍，已废弃。
 */
class DownloadAlbumImporter(private val mHost: Host) {
    interface Host {
        val eHContext: Context?

        fun getString(resId: Int): String?

        fun runOnUiThread(runnable: Runnable?)

        fun updateForLabel()

        fun updateView()

        val downloadManager: DownloadManager?

        val label: String?
    }

    fun importLocalAlbum(folderPickerLauncher: ActivityResultLauncher<Intent?>) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

        try {
            folderPickerLauncher.launch(intent)
        } catch (e: Exception) {
            val context: Context? = mHost.eHContext
            if (context != null) {
                Toast.makeText(context, R.string.import_album_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleSelectedFolder(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            return
        }

        val uri = result.data!!.data ?: return

        val context: Context = mHost.eHContext ?: return

        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d(TAG, "Successfully obtained persistent URI permission for: $uri")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to obtain persistent URI permission for: $uri", e)
            Toast.makeText(context, R.string.import_album_failed, Toast.LENGTH_LONG).show()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error when obtaining URI permission for: $uri", e)
            Toast.makeText(context, R.string.import_album_failed, Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, R.string.import_album_processing, Toast.LENGTH_LONG).show()
        Thread(Runnable { processAlbumFolder(uri) }).start()
    }

    private fun processAlbumFolder(uri: Uri) {
        val context: Context = mHost.eHContext ?: return

        try {
            val sourceDir = UniFile.fromTreeUri(context, uri)
            if (sourceDir == null || !sourceDir.isDirectory()) {
                showFailed()
                return
            }

            val archiveUri: String = URI_PREFIX + uri.toString()
            val downloadManager = mHost.downloadManager
            if (downloadManager != null && isAlreadyImported(downloadManager, archiveUri)) {
                showToast(R.string.import_album_already_imported)
                return
            }

            val importRoot = resolveImportRoot(sourceDir)
            if (importRoot == null) {
                showFailed()
                return
            }

            val images = collectImageFiles(importRoot)
            if (images.isEmpty()) {
                showToast(R.string.import_album_no_images)
                return
            }

            Collections.sort<UniFile?>(
                images,
                Comparator { left: UniFile?, right: UniFile? ->
                    NaturalComparator().compare(
                        left!!.getName(),
                        right!!.getName()
                    )
                })

            // 到这里为止只是枚举，没有任何写盘操作。
            val gid = System.currentTimeMillis()
            val downloadInfo = createAlbumDownloadInfo(gid, sourceDir, archiveUri, images.size)

            if (downloadManager == null) {
                showFailed()
                return
            }

            val downloadList: MutableList<DownloadInfo?> = ArrayList<DownloadInfo?>()
            downloadList.add(downloadInfo)
            downloadManager.addDownload(downloadList)
            mHost.runOnUiThread {
                Toast.makeText(
                    context,
                    context.getString(R.string.import_album_success, images.size),
                    Toast.LENGTH_SHORT
                ).show()
                mHost.updateForLabel()
                mHost.updateView()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process album folder", e)
            showFailed()
        }
    }

    private fun createAlbumDownloadInfo(
        gid: Long,
        sourceDir: UniFile,
        archiveUri: String?,
        imageCount: Int
    ): DownloadInfo {
        val downloadInfo = DownloadInfo()
        downloadInfo.gid = gid
        downloadInfo.token = LOCAL_TOKEN
        var folderName = sourceDir.getName()
        if (folderName == null || folderName.trim { it <= ' ' }.isEmpty()) {
            folderName = "imported_album_$gid"
        }
        downloadInfo.title = folderName
        downloadInfo.titleJpn = null
        // 没有缩略图文件，列表页会从原目录解第一张图作为封面
        downloadInfo.thumb = null
        downloadInfo.category = EhUtils.UNKNOWN
        downloadInfo.posted = null
        downloadInfo.uploader = LOCAL_UPLOADER
        downloadInfo.rating = -1.0f
        downloadInfo.state = DownloadInfo.STATE_FINISH
        downloadInfo.legacy = 0
        downloadInfo.time = gid
        downloadInfo.label = mHost.label
        downloadInfo.total = imageCount
        downloadInfo.finished = imageCount
        downloadInfo.archiveUri = archiveUri
        return downloadInfo
    }

    private fun resolveImportRoot(dir: UniFile?): UniFile? {
        if (dir == null || !dir.isDirectory()) {
            return null
        }
        val children = dir.listFiles() ?: return null
        var hasImageAtLevel = false
        var onlySubdir: UniFile? = null
        var subdirCount = 0
        for (child in children) {
            if (child.isFile() && isImageFile(child)) {
                hasImageAtLevel = true
            } else if (child.isDirectory()) {
                onlySubdir = child
                subdirCount++
            }
        }
        if (hasImageAtLevel) {
            return dir
        }
        if (subdirCount == 1 && onlySubdir != null) {
            return resolveImportRoot(onlySubdir)
        }
        return dir
    }

    private fun collectImageFiles(root: UniFile): MutableList<UniFile?> {
        val images: MutableList<UniFile?> = ArrayList<UniFile?>()
        collectImageFilesRecursive(root, images)
        return images
    }

    private fun collectImageFilesRecursive(dir: UniFile, images: MutableList<UniFile?>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory()) {
                collectImageFilesRecursive(child, images)
            } else if (isImageFile(child)) {
                images.add(child)
            }
        }
    }

    private fun isImageFile(file: UniFile?): Boolean {
        if (file == null || !file.isFile()) {
            return false
        }
        val name = file.getName() ?: return false
        return StringUtils.endsWith(name.lowercase(), GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS)
    }

    private fun isAlreadyImported(manager: DownloadManager, archiveUri: String): Boolean {
        val all = manager.allDownloadInfoList ?: return false
        for (info in all) {
            if (archiveUri == info.archiveUri) {
                return true
            }
        }
        return false
    }

    private fun showFailed() {
        showToast(R.string.import_album_failed)
    }

    private fun showToast(resId: Int) {
        val context: Context = mHost.eHContext ?: return
        mHost.runOnUiThread { Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        private val TAG: String = DownloadAlbumImporter::class.java.simpleName

        const val URI_PREFIX: String = "local-album:"
        private const val LOCAL_TOKEN = "local"
        private const val LOCAL_UPLOADER = "Local Album"

        @JvmStatic
        fun isLocalAlbum(info: DownloadInfo?): Boolean {
            return info != null && info.archiveUri != null && info.archiveUri.startsWith(URI_PREFIX)
        }

        /**
         * 从 archiveUri 里还原出 SAF 目录树的 URI。不是本地相册则返回 null。
         */
        @JvmStatic
        fun getTreeUri(info: DownloadInfo?): Uri? {
            val raw = info?.archiveUri ?: return null
            if (!raw.startsWith(URI_PREFIX)) {
                return null
            }
            return try {
                Uri.parse(raw.substring(URI_PREFIX.length))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * 旧版本（2.0.2.7 及以前）导入时会把图片<b>复制</b>到下载目录，但 archiveUri 也带
         * 同样的 `local-album:` 前缀。这类条目必须继续走原来的本地阅读路径，
         * 否则升级后突然读不到（原目录很可能早就被删了）。
         *
         * @return true 表示存在旧版本复制出来的副本
         */
        @JvmStatic
        fun hasLocalCopy(info: DownloadInfo?): Boolean {
            if (info == null) {
                return false
            }
            return try {
                val dir = SpiderDen.getGalleryDownloadDir(info) ?: return false
                dir.isDirectory && SpiderDen.findImageFile(dir, 0) != null
            } catch (e: Exception) {
                false
            }
        }

        /**
         * 目录树是否仍然可读。用于提示「原目录已删除/移动/授权失效」。
         */
        @JvmStatic
        fun isTreeAccessible(context: Context, treeUri: Uri): Boolean {
            return try {
                val dir = UniFile.fromTreeUri(context, treeUri)
                dir != null && dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
            } catch (e: Exception) {
                false
            }
        }
    }
}
