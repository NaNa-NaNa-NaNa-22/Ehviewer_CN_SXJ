package com.hippo.ehviewer.ui.scene.download.part

import android.app.Activity
import android.app.ProgressDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.hippo.ehviewer.R
import com.hippo.ehviewer.client.EhUtils
import com.hippo.ehviewer.dao.DownloadInfo
import com.hippo.ehviewer.download.DownloadManager
import com.hippo.ehviewer.ui.GalleryActivity
import com.hippo.unifile.UniFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * App 私有 PDF 库。
 *
 * - 移入：从系统文件选择器选 PDF，复制到 filesDir/private_pdfs，成功后删除原文件。
 * - 移出：选一个 SAF 目录，复制出去，成功后删除私有副本。
 *
 * 私有目录是 App 内部存储，系统文档/文件管理器/相册都看不到。
 * 大文件用 64KB 缓冲流式复制，不一次性读入内存。
 */
class PrivatePdfController(private val mHost: Host) {

    interface Host {
        val eHContext: Context?
        val activity2: Activity?
        val downloadManager: DownloadManager?
        val label: String?
        fun runOnUiThread(runnable: Runnable?)
        fun updateForLabel()
        fun updateView()
    }

    private enum class MoveResult {
        SUCCESS, SOURCE_KEPT, FAILED, NO_SPACE
    }

    private var mainDialog: AlertDialog? = null
    private var progressDialog: ProgressDialog? = null
    private var importLauncher: ActivityResultLauncher<Intent>? = null
    private var exportLauncher: ActivityResultLauncher<Intent>? = null
    private var exportingFiles: List<File> = emptyList()
    private var busy = false

    fun show(
        importLauncher: ActivityResultLauncher<Intent>,
        exportLauncher: ActivityResultLauncher<Intent>
    ) {
        this.importLauncher = importLauncher
        this.exportLauncher = exportLauncher
        if (sync(mHost.label)) {
            mHost.updateForLabel()
            mHost.updateView()
        }
        showMainDialog()
    }

    fun handleImportResult(result: ActivityResult) {
        if (busy) {
            return
        }
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            showMainDialog()
            return
        }

        val uris = ArrayList<Uri>()
        val clipData = result.data!!.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        } else {
            result.data!!.data?.let { uris.add(it) }
        }

        if (uris.isEmpty()) {
            showMainDialog()
            return
        }
        moveIn(uris)
    }

    fun handleExportResult(result: ActivityResult) {
        if (busy) {
            return
        }
        val treeUri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || treeUri == null) {
            exportingFiles = emptyList()
            showMainDialog()
            return
        }
        val files = exportingFiles
        exportingFiles = emptyList()
        if (files.isEmpty()) {
            showMainDialog()
            return
        }
        moveOut(treeUri, files)
    }

    private fun listPdfs(context: Context): Array<File> {
        val files = getPdfDir(context).listFiles { file ->
            file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
        } ?: emptyArray()
        files.sortBy { it.name.lowercase(Locale.US) }
        return files
    }

    /**
     * 让私有 PDF 文件和下载列表里的数据库条目保持一致。
     *
     * - 私有目录里有、列表里没有的，补一条 DownloadInfo。
     * - 列表里有、私有文件已经不在的，删掉对应条目。
     *
     * @return 是否发生了增删
     */
    fun sync(label: String?): Boolean {
        val context = mHost.eHContext ?: return false
        val manager = mHost.downloadManager ?: return false
        val files = listPdfs(context)
        val infos = manager.allDownloadInfoList.toList()
        val knownUris = HashSet<String>()

        for (info in infos) {
            if (isPrivatePdf(info)) {
                info.archiveUri?.let { knownUris.add(it) }
            }
        }

        var changed = false

        for (file in files) {
            val archiveUri = URI_PREFIX + file.name
            if (!knownUris.contains(archiveUri)) {
                val info = createDownloadInfo(manager, file, label)
                manager.addDownload(listOf(info))
                knownUris.add(archiveUri)
                changed = true
            }
        }

        for (info in infos) {
            if (!isPrivatePdf(info)) {
                continue
            }
            val file = getPrivatePdfFile(context, info)
            if (file == null || !file.isFile) {
                manager.deleteDownload(info.gid)
                changed = true
            }
        }

        return changed
    }

    private fun createDownloadInfo(
        manager: DownloadManager,
        file: File,
        label: String?
    ): DownloadInfo {
        var gid = System.currentTimeMillis()
        while (manager.getDownloadInfo(gid) != null) {
            gid++
        }

        val info = DownloadInfo()
        info.gid = gid
        info.token = TOKEN
        info.title = file.name
        info.titleJpn = null
        info.thumb = null
        info.category = EhUtils.UNKNOWN
        info.posted = null
        info.uploader = "Private PDF"
        info.rating = -1.0f
        info.state = DownloadInfo.STATE_FINISH
        info.legacy = 0
        info.time = gid
        info.label = label
        info.total = 1
        info.finished = 1
        info.archiveUri = URI_PREFIX + file.name
        return info
    }

    private fun showMainDialog() {
        if (busy) {
            return
        }
        val activity = mHost.activity2 ?: return
        val context = mHost.eHContext ?: return
        if (activity.isFinishing) {
            return
        }

        val files = listPdfs(context)
        mainDialog?.dismiss()

        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.private_pdf_title)
            .setPositiveButton(R.string.private_pdf_import) { _, _ -> startImport() }
            .setNegativeButton(android.R.string.cancel) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.private_pdf_export_all) { _, _ ->
                startExport(files.toList())
            }

        if (files.isEmpty()) {
            builder.setMessage(R.string.private_pdf_empty)
        } else {
            val labels = files.map { fileLabel(it) }.toTypedArray()
            builder.setItems(labels) { dialog, which ->
                dialog.dismiss()
                if (which in files.indices) {
                    openPdf(activity, files[which])
                }
            }
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            if (files.isNotEmpty()) {
                dialog.listView?.setOnItemLongClickListener { _, _, position, _ ->
                    if (position in files.indices) {
                        showFileMenu(files[position])
                    }
                    true
                }
            }
        }
        dialog.setOnDismissListener {
            if (mainDialog === dialog) {
                mainDialog = null
            }
        }
        mainDialog = dialog
        try {
            dialog.show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show private PDF dialog", e)
        }
    }

    private fun fileLabel(file: File): String {
        return "${file.name}  (${formatSize(file.length())})"
    }

    private fun showFileMenu(file: File) {
        val activity = mHost.activity2 ?: return
        val items = arrayOf(
            activity.getString(R.string.private_pdf_export),
            activity.getString(R.string.private_pdf_delete)
        )
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                if (which == 0) {
                    startExport(listOf(file))
                } else {
                    confirmDelete(file)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(file: File) {
        val activity = mHost.activity2 ?: return
        AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.private_pdf_delete_confirm, file.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                file.delete()
                sync(null)
                mHost.updateForLabel()
                mHost.updateView()
                showMainDialog()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showMainDialog() }
            .show()
    }

    private fun openPdf(activity: Activity, file: File) {
        try {
            val intent = Intent(activity, GalleryActivity::class.java)
            intent.action = GalleryActivity.ACTION_PDF
            intent.putExtra(GalleryActivity.KEY_PDF_PATH, file.absolutePath)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open private PDF " + file, e)
            Toast.makeText(activity, R.string.pdf_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startImport() {
        val launcher = importLauncher ?: return
        val activity = mHost.activity2 ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        mainDialog?.dismiss()
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch PDF import picker", e)
            Toast.makeText(activity, R.string.pdf_open_failed, Toast.LENGTH_SHORT).show()
            showMainDialog()
        }
    }

    private fun startExport(files: List<File>) {
        if (files.isEmpty()) {
            val context = mHost.eHContext ?: return
            Toast.makeText(
                context,
                R.string.private_pdf_empty,
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val launcher = exportLauncher ?: return
        val activity = mHost.activity2 ?: return
        exportingFiles = files
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        mainDialog?.dismiss()
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch PDF export picker", e)
            exportingFiles = emptyList()
            Toast.makeText(activity, R.string.private_pdf_export_failed, Toast.LENGTH_SHORT).show()
            showMainDialog()
        }
    }

    private fun moveIn(uris: List<Uri>) {
        val activity = mHost.activity2 ?: return
        val context = mHost.eHContext ?: return
        busy = true
        showProgress(activity, uris.size)

        Thread(Runnable {
            val dir = getPdfDir(context)
            var success = 0
            var failed = 0
            var sourceKept = 0

            try {
                for ((index, uri) in uris.withIndex()) {
                    mHost.runOnUiThread {
                        updateProgress(activity, index + 1, uris.size, queryDisplayName(context, uri))
                    }
                    when (moveOneIn(context, dir, uri)) {
                        MoveResult.SUCCESS -> success++
                        MoveResult.SOURCE_KEPT -> {
                            success++
                            sourceKept++
                        }
                        MoveResult.NO_SPACE -> failed++
                        MoveResult.FAILED -> failed++
                    }
                }
            } finally {
                mHost.runOnUiThread {
                    dismissProgress()
                    busy = false
                    sync(mHost.label)
                    mHost.updateForLabel()
                    mHost.updateView()
                    showMainDialog()
                    Toast.makeText(
                        context,
                        context.getString(R.string.private_pdf_moved, success, failed, sourceKept),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, "private-pdf-import").start()
    }

    private fun moveOneIn(context: Context, dir: File, uri: Uri): MoveResult {
        val displayName = sanitizeName(queryDisplayName(context, uri))
        val target = uniqueFile(dir, displayName)
        val temp = File(dir, target.name + ".part")
        if (temp.exists()) {
            temp.delete()
        }

        val sourceSize = querySize(context, uri)
        if (sourceSize > 0 && dir.usableSpace < sourceSize + 4L * 1024 * 1024) {
            return MoveResult.NO_SPACE
        }

        try {
            val input = context.contentResolver.openInputStream(uri) ?: return MoveResult.FAILED
            input.use { from ->
                FileOutputStream(temp).use { to ->
                    from.copyTo(to, 64 * 1024)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy PDF into private storage: " + uri, e)
            temp.delete()
            return MoveResult.FAILED
        }

        if (sourceSize > 0 && temp.length() != sourceSize) {
            temp.delete()
            return MoveResult.FAILED
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return MoveResult.FAILED
        }

        return if (deleteSource(context, uri)) {
            MoveResult.SUCCESS
        } else {
            MoveResult.SOURCE_KEPT
        }
    }

    private fun moveOut(treeUri: Uri, files: List<File>) {
        val activity = mHost.activity2 ?: return
        val context = mHost.eHContext ?: return
        busy = true
        showProgress(activity, files.size)

        Thread(Runnable {
            var success = 0
            var failed = 0
            var privateKept = 0

            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist export tree permission", e)
                }

                val dir = UniFile.fromTreeUri(context, treeUri)
                if (dir == null || !dir.isDirectory) {
                    failed = files.size
                } else {
                    for ((index, file) in files.withIndex()) {
                        mHost.runOnUiThread {
                            updateProgress(activity, index + 1, files.size, file.name)
                        }
                        when (moveOneOut(dir, file)) {
                            MoveResult.SUCCESS -> success++
                            MoveResult.SOURCE_KEPT -> {
                                success++
                                privateKept++
                            }
                            MoveResult.NO_SPACE,
                            MoveResult.FAILED -> failed++
                        }
                    }
                }
            } finally {
                mHost.runOnUiThread {
                    dismissProgress()
                    busy = false
                    sync(null)
                    mHost.updateForLabel()
                    mHost.updateView()
                    showMainDialog()
                    Toast.makeText(
                        context,
                        context.getString(R.string.private_pdf_exported, success, failed, privateKept),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, "private-pdf-export").start()
    }

    private fun moveOneOut(dir: UniFile, file: File): MoveResult {
        val name = uniqueOutName(dir, file.name)
        val out = dir.createFile(name) ?: return MoveResult.FAILED
        try {
            FileInputStream(file).use { from ->
                out.openOutputStream().use { to ->
                    from.copyTo(to, 64 * 1024)
                }
            }
            if (out.length() >= 0 && out.length() != file.length()) {
                out.delete()
                return MoveResult.FAILED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export private PDF " + file, e)
            out.delete()
            return MoveResult.FAILED
        }

        return if (file.delete()) {
            MoveResult.SUCCESS
        } else {
            MoveResult.SOURCE_KEPT
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) {
            return file
        }
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            file = File(dir, "$base ($index)$ext")
            if (!file.exists()) {
                return file
            }
            index++
        }
    }

    private fun uniqueOutName(dir: UniFile, name: String): String {
        if (dir.findFile(name) == null) {
            return name
        }
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base ($index)$ext"
            if (dir.findFile(candidate) == null) {
                return candidate
            }
            index++
        }
    }

    private fun sanitizeName(name: String?): String {
        var value = name
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            .orEmpty()
        if (value.isEmpty()) {
            value = "document.pdf"
        }
        value = value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        if (!value.endsWith(".pdf", ignoreCase = true)) {
            value += ".pdf"
        }
        return value
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun deleteSource(context: Context, uri: Uri): Boolean {
        return try {
            if (ContentResolver.SCHEME_FILE == uri.scheme) {
                val path = uri.path
                path != null && File(path).delete()
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete source PDF after moving: " + uri, e)
            false
        }
    }

    private fun showProgress(activity: Activity, total: Int) {
        try {
            progressDialog = ProgressDialog(activity).apply {
                isIndeterminate = true
                setCancelable(false)
                setMessage(activity.getString(R.string.private_pdf_moving, 0, total, ""))
                show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show private PDF progress", e)
        }
    }

    private fun updateProgress(activity: Activity, index: Int, total: Int, name: String?) {
        try {
            progressDialog?.setMessage(
                activity.getString(
                    R.string.private_pdf_moving,
                    index,
                    total,
                    name ?: ""
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update private PDF progress", e)
        }
    }

    private fun dismissProgress() {
        try {
            progressDialog?.dismiss()
        } catch (e: Exception) {
            // Ignore
        }
        progressDialog = null
    }

    private fun formatSize(size: Long): String {
        if (size < 1024) {
            return "$size B"
        }
        val kb = size / 1024.0
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb)
        }
        val mb = kb / 1024.0
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb)
        }
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    companion object {
        private const val TAG = "PrivatePdfController"

        const val URI_PREFIX = "private-pdf:"
        private const val TOKEN = "private-pdf"

        @JvmStatic
        fun getPdfDir(context: Context): File {
            return File(context.filesDir, "private_pdfs").apply { mkdirs() }
        }

        @JvmStatic
        fun isPrivatePdf(info: DownloadInfo?): Boolean {
            val uri = info?.archiveUri ?: return false
            return uri.startsWith(URI_PREFIX)
        }

        @JvmStatic
        fun getPrivatePdfFile(context: Context?, info: DownloadInfo?): File? {
            if (context == null) {
                return null
            }
            val raw = info?.archiveUri ?: return null
            if (!raw.startsWith(URI_PREFIX)) {
                return null
            }
            val name = raw.substring(URI_PREFIX.length)
            if (name.isEmpty() || name.contains('/') || name.contains('\\')) {
                return null
            }
            return File(getPdfDir(context), name)
        }
    }
}
