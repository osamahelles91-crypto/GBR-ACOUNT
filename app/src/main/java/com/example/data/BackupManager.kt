package com.example.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Robust Password-Based Encryption and Decryption using AES-GCM-256 and PBKDF2.
 */
object BackupEncryption {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    val MAGIC_HEADER = byteArrayOf('G'.toByte(), 'B'.toByte(), 'R'.toByte(), 'B'.toByte()) // GBR Backup Signature

    fun isEncryptedBackup(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC_HEADER.size) return false
        for (i in MAGIC_HEADER.indices) {
            if (bytes[i] != MAGIC_HEADER[i]) return false
        }
        return true
    }

    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)

        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)

        // Derive key from password using PBKDF2
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val tmp = factory.generateSecret(spec)
        val secretKey = SecretKeySpec(tmp.encoded, ALGORITHM)

        // Initialize AES-GCM Cipher
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val encryptedData = cipher.doFinal(data)

        // Format: MAGIC_HEADER (4 bytes) + SALT (16 bytes) + IV (12 bytes) + ENCRYPTED_DATA
        val combined = ByteArray(MAGIC_HEADER.size + salt.size + iv.size + encryptedData.size)
        System.arraycopy(MAGIC_HEADER, 0, combined, 0, MAGIC_HEADER.size)
        System.arraycopy(salt, 0, combined, MAGIC_HEADER.size, salt.size)
        System.arraycopy(iv, 0, combined, MAGIC_HEADER.size + salt.size, iv.size)
        System.arraycopy(encryptedData, 0, combined, MAGIC_HEADER.size + salt.size + iv.size, encryptedData.size)

        return combined
    }

    fun decrypt(encryptedBytes: ByteArray, password: CharArray): ByteArray {
        if (encryptedBytes.size < MAGIC_HEADER.size + SALT_LENGTH + IV_LENGTH) {
            throw IllegalArgumentException("الملف صغير جداً أو غير صالح!")
        }

        // Validate GBRB header
        for (i in MAGIC_HEADER.indices) {
            if (encryptedBytes[i] != MAGIC_HEADER[i]) {
                throw IllegalArgumentException("الملف ليس نسخة احتياطية مشفرة صالحة لنظام GBR!")
            }
        }

        val salt = ByteArray(SALT_LENGTH)
        System.arraycopy(encryptedBytes, MAGIC_HEADER.size, salt, 0, SALT_LENGTH)

        val iv = ByteArray(IV_LENGTH)
        System.arraycopy(encryptedBytes, MAGIC_HEADER.size + SALT_LENGTH, iv, 0, IV_LENGTH)

        val encryptedDataSize = encryptedBytes.size - MAGIC_HEADER.size - SALT_LENGTH - IV_LENGTH
        val encryptedData = ByteArray(encryptedDataSize)
        System.arraycopy(encryptedBytes, MAGIC_HEADER.size + SALT_LENGTH + IV_LENGTH, encryptedData, 0, encryptedDataSize)

        // Derive key from password using PBKDF2
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val tmp = factory.generateSecret(spec)
        val secretKey = SecretKeySpec(tmp.encoded, ALGORITHM)

        // Decrypt using AES-GCM
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(encryptedData)
    }
}

/**
 * Unified structural definition for a backup category/section.
 * This makes the backup pipeline future-proof to automatically include newly registered sections.
 */
data class BackupSection(
    val key: String,                  // English key identifier (e.g. "formulations")
    val titleAr: String,              // Arabic title
    val titleEn: String,              // English title
    val tables: List<String>          // Database table names included in this section
)

/**
 * BackupManager is responsible for handling DB import and export strategies.
 * High scalability for backup segments with dynamic category registration.
 */
object BackupManager {

    /**
     * Dynamic registry for all sections. Adding a new section here automatically updates:
     * 1. The custom checkboxes shown in the UI.
     * 2. The full backup.
     * 3. The custom select/import filters.
     */
    val sections = listOf(
        BackupSection(
            key = "formulations",
            titleAr = "التركيبات والمكونات والوصفات والمواصفات المرجعية",
            titleEn = "Formulations, Recipes & Specs",
            tables = listOf(
                "formulations",
                "formulation_items",
                "formulation_revisions",
                "recipe_phases",
                "recipe_items",
                "recipe_statuses",
                "formulation_quality_tests",
                "formulation_reference_specs"
            )
        ),
        BackupSection(
            key = "raw_materials",
            titleAr = "المواد الخام وسجل تعديل الأسعار",
            titleEn = "Raw Materials & Price History",
            tables = listOf(
                "raw_materials",
                "price_history"
            )
        ),
        BackupSection(
            key = "production",
            titleAr = "الإنتاج وسجل التشغيل وتعديل الكميات وفحوصات الوجبات واللوحات",
            titleEn = "Production, Batches & Run Logs",
            tables = listOf(
                "production_orders",
                "production_order_items",
                "production_order_events",
                "production_order_phases",
                "production_order_recipe_items",
                "production_adjustments",
                "production_order_test_records",
                "production_logs"
            )
        ),
        BackupSection(
            key = "laboratory",
            titleAr = "جلسات المختبر والتحاليل المعملية وفحوصات الجودة والملحقات والوثائق",
            titleEn = "Laboratory Sessions, Quality Tests & Attachments",
            tables = listOf(
                "laboratory_sessions",
                "laboratory_tests",
                "quality_tests",
                "laboratory_attachments"
            )
        ),
        BackupSection(
            key = "research_development",
            titleAr = "مشاريع البحوث وعينات البحث والتطوير (R&D)",
            titleEn = "Research & Development Projects",
            tables = listOf(
                "development_projects",
                "development_samples"
            )
        ),
        BackupSection(
            key = "settings_and_equipment",
            titleAr = "إعدادات العبوات والمعدات والمزامنة المحلية والتنبيهات وسلة المهملات",
            titleEn = "Settings, Packagings, Alerts, Recycle Bin & Equipment Sync",
            tables = listOf(
                "custom_packagings",
                "sync_metadata",
                "operational_alerts",
                "recycle_bin"
            )
        )
    )

    /**
     * Structure metadata block to make the JSON payload backward-compatible.
     */
    fun createBackupMetadata(context: Context): JSONObject {
        val meta = JSONObject()
        meta.put("format_version", 3) // Version 3 supporting registry-level dynamic backups
        meta.put("platform", "Android")
        meta.put("package_name", context.packageName)
        meta.put("exported_at_ms", System.currentTimeMillis())
        meta.put("has_binary_attachments", false)
        return meta
    }

    /**
     * Scalability helper: Pack local files into a single ZIP archive.
     */
    fun exportToCompressedArchive(
        context: Context,
        jsonPayload: String,
        attachedFiles: List<File>,
        outputStream: OutputStream
    ) {
        ZipOutputStream(outputStream).use { zos ->
            val jsonEntry = ZipEntry("database_backup.json")
            zos.putNextEntry(jsonEntry)
            zos.write(jsonPayload.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            attachedFiles.forEach { file ->
                if (file.exists() && file.isFile) {
                    val fileEntry = ZipEntry("attachments/${file.name}")
                    zos.putNextEntry(fileEntry)
                    file.inputStream().use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    /**
     * Scalability helper: Restore image/PDF attachments back into app private storage.
     */
    fun importFromCompressedArchive(
        context: Context,
        zipInputStream: InputStream,
        onJsonExtracted: (String) -> Unit
    ) {
        ZipInputStream(zipInputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "database_backup.json") {
                    val content = zis.bufferedReader().readText()
                    onJsonExtracted(content)
                } else if (entry.name.startsWith("attachments/")) {
                    val fileName = entry.name.substringAfter("attachments/")
                    val destFile = File(context.filesDir, fileName)
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
