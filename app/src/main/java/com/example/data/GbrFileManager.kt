package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GbrFileManager {
    private const val TAG = "GBR_FileManager"
    const val CACHE_DIR_NAME = "gbr_files_cache"

    // Get the local cache directory for GBR files
    fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // Get the local persistent files directory for GBR files (stored in context.filesDir so Android OS never clears it automatically)
    fun getPersistentDir(context: Context): File {
        val dir = File(context.filesDir, "gbr_persistent_files")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // Copy a selected picker URI to the stable internal persistent files directory (never cleared by OS)
    fun copyUriToPersistentStorage(context: Context, sourceUri: Uri, prefix: String): Uri? {
        try {
            val resolver = context.contentResolver
            val originalName = getFileNameFromUri(context, sourceUri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(sourceUri)) ?: "jpg"
            val persistentDir = getPersistentDir(context)
            val targetFile = File(persistentDir, "gbr_saved_${prefix}_${System.currentTimeMillis()}.$extension")

            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Successfully copied Uri to persistent storage file: ${targetFile.absolutePath}")
            return Uri.fromFile(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy Uri to persistent storage", e)
            return null
        }
    }

    // Copy a selected picker URI (content:// or file://) immediately to the stable internal cache directory
    fun copyUriToCache(context: Context, sourceUri: Uri, prefix: String): Uri? {
        try {
            val resolver = context.contentResolver
            val originalName = getFileNameFromUri(context, sourceUri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(sourceUri)) ?: "pdf"
            val cacheDir = getCacheDir(context)
            val targetFile = File(cacheDir, "gbr_picked_${prefix}_${System.currentTimeMillis()}.$extension")

            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Save initial diagnostics for this picked file
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val formattedDate = sdf.format(java.util.Date())
            val fileId = targetFile.nameWithoutExtension
            saveFileDiagnostic(context, fileId, mapOf(
                "original_name" to originalName,
                "size" to targetFile.length(),
                "last_updated" to targetFile.lastModified(),
                "status" to "pending_sync",
                "last_sync" to "بانتظار الحفظ والرفع"
            ))

            Log.i(TAG, "Successfully copied picked Uri to local cache file: ${targetFile.absolutePath}")
            return Uri.fromFile(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy picked Uri to local cache", e)
            return null
        }
    }

    // Extract filename from a local Uri
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "unknown_file"
        try {
            if (uri.scheme == "content") {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = it.getString(nameIndex)
                        }
                    }
                }
            } else {
                name = uri.lastPathSegment ?: "unknown_file"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving filename from URI: $uri", e)
        }
        return name
    }

    // Extract filename from a Firebase Storage HTTP URL
    fun getFileNameFromUrl(urlStr: String): String {
        return try {
            val uri = Uri.parse(urlStr)
            val path = uri.path ?: return "document.pdf"
            val lastSegment = path.substringAfterLast("/")
            val decoded = java.net.URLDecoder.decode(lastSegment, "UTF-8")
            if (decoded.contains("/")) {
                decoded.substringAfterLast("/")
            } else {
                decoded
            }
        } catch (e: Exception) {
            "document.pdf"
        }
    }

    // Download file from url to target cache directory with progress reporting
    suspend fun downloadAndCacheFile(
        context: Context,
        urlStr: String,
        targetId: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = getCacheDir(context)
            val isPdf = urlStr.lowercase().contains(".pdf") || !urlStr.contains(".png") && !urlStr.contains(".jpg") && !urlStr.contains(".jpeg")
            val fileSuffix = if (isPdf) ".pdf" else if (urlStr.lowercase().contains(".png")) ".png" else ".jpg"
            val targetFile = File(cacheDir, "gbr_cache_${targetId}$fileSuffix")

            // Create directories if missing
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("فشل الاتصال بالملف السحابي: رمز الاستجابة ${connection.responseCode}")
            }

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(targetFile)

            val data = ByteArray(4096)
            var total: Long = 0
            var count: Int
            while (inputStream.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    onProgress(total.toFloat() / fileLength.toFloat())
                }
                outputStream.write(data, 0, count)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            // Update local file metadata & diagnostics
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val formattedDate = sdf.format(java.util.Date())
            saveFileDiagnostic(context, targetId, mapOf(
                "original_name" to getFileNameFromUrl(urlStr),
                "size" to targetFile.length(),
                "last_updated" to targetFile.lastModified(),
                "last_download" to formattedDate,
                "status" to "synchronized",
                "last_sync" to formattedDate,
                "failure_reason" to "" // Clear previous failures
            ))

            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading and caching file for target $targetId", e)
            saveFileDiagnostic(context, targetId, mapOf(
                "failure_reason" to (e.localizedMessage ?: "حدث عطل غير متوقع أثناء الاتصال بالخادم وتنزيل الملف.")
            ))
            null
        }
    }

    // Get cached or newly updated file. Performs a metadata check if online.
    suspend fun getFileWithAutoUpdate(
        context: Context,
        urlStr: String?,
        targetId: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        if (urlStr.isNullOrBlank()) {
            return@withContext null
        }

        // If it starts with file:// or is a direct local file system path, return it directly if it exists
        if (urlStr.startsWith("file://") || urlStr.startsWith("/")) {
            try {
                val cleanPath = if (urlStr.startsWith("file://")) urlStr.substring(7) else urlStr
                val localFileObj = File(cleanPath)
                if (localFileObj.exists()) {
                    return@withContext localFileObj
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed resolving direct local file path in getFileWithAutoUpdate", e)
            }
        }

        val cacheDir = getCacheDir(context)
        val isPdf = urlStr.lowercase().contains(".pdf") || !urlStr.contains(".png") && !urlStr.contains(".jpg") && !urlStr.contains(".jpeg")
        val fileSuffix = if (isPdf) ".pdf" else if (urlStr.lowercase().contains(".png")) ".png" else ".jpg"
        val localFile = File(cacheDir, "gbr_cache_${targetId}$fileSuffix")

        val hasInternet = SyncManager.isNetworkAvailable(context)

        // Offline mode: Always return local cached copy if available
        if (!hasInternet) {
            if (localFile.exists()) {
                return@withContext localFile
            } else {
                saveFileDiagnostic(context, targetId, mapOf(
                    "failure_reason" to "الهاتف غير متصل بالإنترنت ولم يتم تخزين نسخة محلية مؤقتة من الملف مسبقاً."
                ))
                return@withContext null
            }
        }

        // Online mode: Check if cached file exists and check for newer updates via quick HEAD query
        try {
            if (localFile.exists()) {
                val url = URL(urlStr)
                val headConn = url.openConnection() as HttpURLConnection
                headConn.connectTimeout = 4000
                headConn.readTimeout = 4000
                headConn.requestMethod = "HEAD"
                val remoteLength = headConn.contentLengthLong
                val remoteLastModified = headConn.lastModified
                headConn.disconnect()

                // If remote file has same size and has not been modified after local file's creation, return cached file!
                if (localFile.length() == remoteLength && (remoteLastModified == 0L || localFile.lastModified() >= remoteLastModified)) {
                    // Update diagnostics for access
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    val formattedDate = sdf.format(java.util.Date())
                    saveFileDiagnostic(context, targetId, mapOf(
                        "status" to "synchronized",
                        "last_download" to formattedDate
                    ))
                    return@withContext localFile
                }
            }

            // Otherwise, download fresh copy or update existing one
            return@withContext downloadAndCacheFile(context, urlStr, targetId, onProgress)
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic check for updates failed for $targetId, calling local file fallback", e)
            if (localFile.exists()) {
                return@withContext localFile
            } else {
                return@withContext downloadAndCacheFile(context, urlStr, targetId, onProgress)
            }
        }
    }

    // Open file inside external applications safely using FileProvider
    fun openCachedFile(context: Context, file: File): Boolean {
        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val mimeType = if (file.name.endsWith(".png", true)) {
                "image/png"
            } else if (file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true)) {
                "image/jpeg"
            } else {
                "application/pdf"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving or opening cached file: ${file.absolutePath}", e)
            false
        }
    }

    // Save and retrieve file diagnostics metadata values
    fun saveFileDiagnostic(context: Context, id: String, values: Map<String, Any>) {
        val prefs = context.getSharedPreferences("gbr_file_diagnostics", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for ((key, value) in values) {
            val prefKey = "${id}_${key}"
            when (value) {
                is String -> editor.putString(prefKey, value)
                is Long -> editor.putLong(prefKey, value)
                is Int -> editor.putInt(prefKey, value)
                is Boolean -> editor.putBoolean(prefKey, value)
                is Float -> editor.putFloat(prefKey, value)
            }
        }
        editor.apply()
    }

    fun getFileDiagnostic(context: Context, id: String): FileDiagnosticInfo {
        val prefs = context.getSharedPreferences("gbr_file_diagnostics", Context.MODE_PRIVATE)
        val cacheDir = getCacheDir(context)
        
        // Find if local file under this ID actually exists physically
        var localCopyExists = false
        val files = cacheDir.listFiles()
        if (files != null) {
            for (f in files) {
                if (f.name.contains("gbr_cache_$id") || f.name.contains("gbr_picked_$id")) {
                    localCopyExists = true
                    break
                }
            }
        }

        return FileDiagnosticInfo(
            originalName = prefs.getString("${id}_original_name", null),
            size = prefs.getLong("${id}_size", 0L),
            lastUpdated = prefs.getLong("${id}_last_updated", 0L),
            lastDownload = prefs.getString("${id}_last_download", "لم يتم التنزيل بعد") ?: "لم يتم التنزيل بعد",
            lastSync = prefs.getString("${id}_last_sync", "لم يتم المزامنة بعد") ?: "لم يتم المزامنة بعد",
            failureReason = prefs.getString("${id}_failure_reason", null),
            status = prefs.getString("${id}_status", "unknown") ?: "unknown",
            localCopyExists = localCopyExists
        )
    }

    fun getLocalCachedFileIfExists(context: Context, id: String, isPdf: Boolean = false): File? {
        val cacheDir = getCacheDir(context)
        val suffixes = if (isPdf) listOf(".pdf") else listOf(".jpg", ".png", ".jpeg")
        for (suffix in suffixes) {
            val file = File(cacheDir, "gbr_cache_$id$suffix")
            if (file.exists()) return file
        }
        return null
    }

    data class SavedFileResult(
        val success: Boolean,
        val fileName: String,
        val savedLocationDesc: String,
        val uri: Uri? = null,
        val errorMessage: String? = null
    )

    suspend fun saveFileToDeviceStorage(
        context: Context,
        sourcePathOrUrl: String,
        suggestedTitle: String = ""
    ): SavedFileResult = withContext(Dispatchers.IO) {
        try {
            if (sourcePathOrUrl.isBlank()) {
                return@withContext SavedFileResult(false, "", "", null, "مسار الملف غير متاح أو فارغ")
            }

            val isHttp = sourcePathOrUrl.startsWith("http://", ignoreCase = true) || sourcePathOrUrl.startsWith("https://", ignoreCase = true)
            val isContent = sourcePathOrUrl.startsWith("content://", ignoreCase = true)
            val isLocalFile = sourcePathOrUrl.startsWith("file://", ignoreCase = true) || sourcePathOrUrl.startsWith("/")

            // Determine mime type and clean name
            val urlOrPathClean = sourcePathOrUrl.lowercase()
            val isPng = urlOrPathClean.contains(".png")
            val isPdf = urlOrPathClean.contains(".pdf")
            val isImage = isPng || urlOrPathClean.contains(".jpg") || urlOrPathClean.contains(".jpeg") || urlOrPathClean.contains(".webp") || (!isPdf && !urlOrPathClean.contains(".doc"))

            val ext = if (isPdf) ".pdf" else if (isPng) ".png" else if (isImage) ".jpg" else ".bin"
            val mimeType = if (isPdf) "application/pdf" else if (isPng) "image/png" else if (isImage) "image/jpeg" else "application/octet-stream"

            val sanitizedTitle = suggestedTitle.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                .ifBlank { "GBR_Lab_Attachment" }

            val finalFileName = "${sanitizedTitle}_${System.currentTimeMillis()}$ext"

            // Open input stream from appropriate source
            var inputStream: java.io.InputStream? = null
            var httpConn: HttpURLConnection? = null

            if (isHttp) {
                val url = URL(sourcePathOrUrl)
                httpConn = url.openConnection() as HttpURLConnection
                httpConn.connectTimeout = 15000
                httpConn.readTimeout = 15000
                httpConn.requestMethod = "GET"
                httpConn.connect()
                if (httpConn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("فشل تنزيل الملف السحابي (كود ${httpConn.responseCode})")
                }
                inputStream = httpConn.inputStream
            } else if (isContent) {
                inputStream = context.contentResolver.openInputStream(Uri.parse(sourcePathOrUrl))
            } else if (isLocalFile) {
                val path = if (sourcePathOrUrl.startsWith("file://")) sourcePathOrUrl.substring(7) else sourcePathOrUrl
                val f = File(path)
                if (f.exists()) {
                    inputStream = java.io.FileInputStream(f)
                } else {
                    throw Exception("الملف المحلي غير موجود")
                }
            }

            if (inputStream == null) {
                throw Exception("تعذر قراءة بيانات الملف المرفق")
            }

            // Save to MediaStore (Pictures for Images, Downloads for others)
            val resolver = context.contentResolver
            var savedUri: Uri? = null
            val locationDesc: String

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collectionUri = if (isImage) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val relativeDir = if (isImage) {
                    Environment.DIRECTORY_PICTURES + "/GBR_Laboratory"
                } else {
                    Environment.DIRECTORY_DOWNLOADS + "/GBR_Laboratory"
                }

                locationDesc = if (isImage) "معرض الصور (Pictures/GBR_Laboratory)" else "مجلد التنزيلات (Downloads/GBR_Laboratory)"

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, finalFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                savedUri = resolver.insert(collectionUri, values)
                if (savedUri != null) {
                    resolver.openOutputStream(savedUri)?.use { outputStream ->
                        inputStream.use { it.copyTo(outputStream) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(savedUri, values, null, null)
                } else {
                    throw Exception("فشل إنشاء ملف جديد في وسائط الهاتف")
                }
            } else {
                val targetDir = if (isImage) {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GBR_Laboratory")
                } else {
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GBR_Laboratory")
                }
                if (!targetDir.exists()) targetDir.mkdirs()

                locationDesc = if (isImage) "معرض الصور (Pictures/GBR_Laboratory)" else "مجلد التنزيلات (Downloads/GBR_Laboratory)"

                val destinationFile = File(targetDir, finalFileName)
                destinationFile.outputStream().use { outputStream ->
                    inputStream.use { it.copyTo(outputStream) }
                }
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destinationFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )
                savedUri = Uri.fromFile(destinationFile)
            }

            httpConn?.disconnect()
            SavedFileResult(
                success = true,
                fileName = finalFileName,
                savedLocationDesc = locationDesc,
                uri = savedUri
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file to device: ${e.message}", e)
            SavedFileResult(
                success = false,
                fileName = "",
                savedLocationDesc = "",
                errorMessage = e.localizedMessage ?: "حدث خطأ أثناء تنزيل وحفظ الملف"
            )
        }
    }

    data class FileDiagnosticInfo(
        val originalName: String?,
        val size: Long,
        val lastUpdated: Long,
        val lastDownload: String?,
        val lastSync: String?,
        val failureReason: String?,
        val status: String,
        val localCopyExists: Boolean
    )
}
