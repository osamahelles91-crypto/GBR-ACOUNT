package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object HostingerStorageManager {
    private const val TAG = "HostingerStorageManager"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    suspend fun checkConnection(baseUrl: String): ConnectionResult = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        if (normalized.isEmpty()) {
            return@withContext ConnectionResult(false, "رابط بوابة الاستضافة فارغ")
        }
        val healthUrl = normalized + "health.php"
        try {
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext ConnectionResult(false, "فشل الاتصال بالملف البرمجي health.php. رمز الاستجابة: ${response.code}")
                }
                val bodyText = response.body?.string() ?: ""
                try {
                    val json = JSONObject(bodyText)
                    val status = json.optString("status")
                    val directories = json.optJSONObject("directories")
                    
                    val tdsWritable = directories?.optJSONObject("tds")?.optBoolean("writable") ?: false
                    val tdsExists = directories?.optJSONObject("tds")?.optBoolean("exists") ?: false
                    
                    val imagesWritable = directories?.optJSONObject("images")?.optBoolean("writable") ?: false
                    val imagesExists = directories?.optJSONObject("images")?.optBoolean("exists") ?: false

                    if (tdsWritable && imagesWritable) {
                        return@withContext ConnectionResult(
                            true, 
                            "تم التحقق بنجاح! الاتصال بالملف health.php سليم، والمجلدات المخصصة قابلة للكتابة والعمل."
                        )
                    } else {
                        val errorList = mutableListOf<String>()
                        if (!tdsExists) errorList.add("مجلد tds غير موجود")
                        else if (!tdsWritable) errorList.add("مجلد tds غير قابل للكتابة (يرجى مراجعة صلاحيات المجلد Chmod 755 أو 777)")
                        
                        if (!imagesExists) errorList.add("مجلد images غير موجود")
                        else if (!imagesWritable) errorList.add("مجلد images غير قابل للكتابة (يرجى مراجعة صلاحيات المجلد Chmod 755 أو 777)")
                        
                        return@withContext ConnectionResult(
                            false, 
                            "فشل التحقق: " + errorList.joinToString("، ")
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing health.php json response", e)
                    return@withContext ConnectionResult(false, "تم تلقي استجابة غير صالحة من الملف health.php: ${e.localizedMessage}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection check failed", e)
            return@withContext ConnectionResult(false, "تعذر الوصول إلى $healthUrl. تحقق من اتصال الخادم والشبكة وصحة الرابط. التفاصيل: ${e.localizedMessage}")
        }
    }

    suspend fun uploadFile(
        context: Context,
        baseUrl: String,
        localUri: android.net.Uri,
        type: String, // "tds" or "image"
        customFileName: String
    ): String? = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        if (normalized.isEmpty()) {
            Log.e(TAG, "Cannot upload: hostinger gateway URL is empty")
            return@withContext null
        }
        val uploadUrl = normalized + "upload.php"
        try {
            val bytes = if (localUri.scheme == "file") {
                val fObj = java.io.File(localUri.path ?: "")
                if (fObj.exists()) fObj.readBytes() else null
            } else {
                val inputStream = context.contentResolver.openInputStream(localUri)
                inputStream?.use { it.readBytes() }
            }

            if (bytes == null || bytes.isEmpty()) {
                Log.e(TAG, "Failed to read bytes for Hostinger upload from: $localUri")
                return@withContext null
            }

            // Detect media type
            val mimeType = context.contentResolver.getType(localUri) ?: "application/octet-stream"
            val mediaType = mimeType.toMediaTypeOrNull()
            
            val filePart = bytes.toRequestBody(mediaType)
            
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("type", type)
                .addFormDataPart("filename", customFileName)
                .addFormDataPart("file", customFileName, filePart)
                .build()

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Hostinger upload failed with HTTP error: ${response.code}")
                    return@withContext null
                }
                val bodyText = response.body?.string() ?: ""
                try {
                    val json = JSONObject(bodyText)
                    if (json.optBoolean("success", false)) {
                        val fileUrl = json.optString("url")
                        Log.i(TAG, "File successfully uploaded to Hostinger: $fileUrl")
                        return@withContext fileUrl
                    } else {
                        val errorMsg = json.optString("error", "Unknown error from hostinger node")
                        Log.e(TAG, "Hostinger backend reported failure: $errorMsg")
                        return@withContext null
                    }
                } catch (pe: Exception) {
                    Log.e(TAG, "Could not parse JSON response from Hostinger upload: $bodyText", pe)
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Hostinger file upload", e)
            return@withContext null
        }
    }

    suspend fun deleteFile(
        baseUrl: String,
        filename: String,
        type: String // "tds", "image", or "lab"
    ): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(baseUrl)
        if (normalized.isEmpty()) {
            Log.e(TAG, "Cannot delete: hostinger gateway URL is empty")
            return@withContext false
        }
        val deleteUrl = normalized + "delete.php"
        try {
            val jsonPayload = JSONObject().apply {
                put("type", type)
                put("filename", filename)
            }
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val requestBody = jsonPayload.toString().toRequestBody(mediaType)
            
            val request = Request.Builder()
                .url(deleteUrl)
                .post(requestBody)
                .build()
                
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Hostinger delete failed with HTTP error: ${response.code}")
                    return@withContext false
                }
                val bodyText = response.body?.string() ?: ""
                try {
                    val json = JSONObject(bodyText)
                    if (json.optBoolean("success", false)) {
                        Log.i(TAG, "File successfully deleted from Hostinger: $filename")
                        return@withContext true
                    } else {
                        val errorMsg = json.optString("error", "Unknown error from hostinger node")
                        Log.e(TAG, "Hostinger delete backend reported failure: $errorMsg")
                        return@withContext false
                    }
                } catch (pe: Exception) {
                    Log.e(TAG, "Could not parse JSON response from Hostinger delete: $bodyText", pe)
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in Hostinger file deletion", e)
            return@withContext false
        }
    }
}

data class ConnectionResult(val success: Boolean, val message: String)
