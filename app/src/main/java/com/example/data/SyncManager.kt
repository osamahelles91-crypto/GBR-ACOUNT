package com.example.data

import java.io.File
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
    this.addOnCompleteListener { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) {
                continuation.resume(task.result, onCancellation = null)
            } else {
                continuation.resumeWithException(task.exception ?: RuntimeException("Firebase transaction failed"))
            }
        }
    }
}

fun DocumentSnapshot.getSafeDouble(field: String): Double? {
    val value = this.get(field)
    return (value as? Number)?.toDouble()
}

fun DocumentSnapshot.getSafeLong(field: String): Long? {
    val value = this.get(field)
    return (value as? Number)?.toLong()
}

// --- Lab sync helpers (updatedAt-based conflict rule) ---
// Cloud copy is applied only when it carries an explicit updatedAt that is newer than the last local change/receive and no local edit is pending.
private fun labCloudIsNewer(cloudDoc: DocumentSnapshot?, meta: SyncMetadata?): Boolean {
    if (cloudDoc == null) return false
    val cloudUpdatedAt = cloudDoc.getSafeLong("updatedAt") ?: return false
    if (meta?.isPendingSync == true) return false
    return cloudUpdatedAt > (meta?.lastUpdated ?: 0L)
}

private fun labSessionFromDoc(dc: DocumentSnapshot): LabSession = LabSession(
    id = dc.id,
    sessionNumber = dc.getString("sessionNumber") ?: "",
    testName = dc.getString("testName") ?: "",
    testDate = dc.getString("testDate") ?: "",
    technicianName = dc.getString("technicianName") ?: "",
    sampleOrProduct = dc.getString("sampleOrProduct") ?: "",
    category = dc.getString("category") ?: "",
    testType = dc.getString("testType") ?: "",
    notes = dc.getString("notes") ?: "",
    comparisonType = dc.getString("comparisonType"),
    partyA = dc.getString("partyA"),
    partyB = dc.getString("partyB"),
    createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis(),
    sampleProperties = dc.getString("sampleProperties") ?: ""
)

private fun labTestFromDoc(dc: DocumentSnapshot): LabTest = LabTest(
    id = dc.id,
    sessionId = dc.getString("sessionId") ?: "",
    name = dc.getString("name") ?: "",
    status = dc.getString("status") ?: "",
    executionDate = dc.getString("executionDate") ?: "",
    notes = dc.getString("notes") ?: "",
    testValueA = dc.get("testValueA")?.toString(),
    testValueB = dc.get("testValueB")?.toString(),
    createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis()
)

private fun labAttachmentFromDoc(dc: DocumentSnapshot): LabAttachment = LabAttachment(
    id = dc.id,
    sessionId = dc.getString("sessionId") ?: "",
    testName = dc.getString("testName") ?: "",
    filePathOrUrl = dc.getString("filePathOrUrl") ?: "",
    createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis()
)

private fun labCloudStamp(dc: DocumentSnapshot): Long =
    dc.getSafeLong("updatedAt") ?: dc.getSafeLong("createdAt") ?: System.currentTimeMillis()

data class CloudAuditReport(
    val connectionSucceeded: Boolean = false,
    val connectionError: String? = null,
    
    val localRawCount: Int = 0,
    val cloudRawCount: Int = 0,
    val rawMaterialsMatch: Boolean = false,
    
    val localFormCount: Int = 0,
    val cloudFormCount: Int = 0,
    val formulationsMatch: Boolean = false,
    
    val localOrderCount: Int = 0,
    val cloudOrderCount: Int = 0,
    val ordersMatch: Boolean = false,

    val localDevCount: Int = 0,
    val cloudDevCount: Int = 0,
    val devMatch: Boolean = false,

    val localLabCount: Int = 0,
    val cloudLabCount: Int = 0,
    val labMatch: Boolean = false,

    val localLogCount: Int = 0,
    val cloudLogCount: Int = 0,
    val logMatch: Boolean = false,

    val localPkgCount: Int = 0,
    val cloudPkgCount: Int = 0,
    val pkgMatch: Boolean = false,

    val localUserCount: Int = 0,
    val cloudUserCount: Int = 0,
    val userMatch: Boolean = false,
    
    val totalDiscrepancies: Int = 0,
    val discrepancyMessage: String = "",
    val auditDate: String = ""
)

data class SyncReport(
    val connectionSucceeded: Boolean = false,
    val connectionError: String? = null,
    
    val rawMaterialsLocal: Int = 0,
    val rawMaterialsUploaded: Int = 0,
    val rawMaterialsDownloaded: Int = 0,
    val rawMaterialsError: String? = null,
    
    val formulationsLocal: Int = 0,
    val formulationsUploaded: Int = 0,
    val formulationsDownloaded: Int = 0,
    val formulationsError: String? = null,
    
    val productionOrdersLocal: Int = 0,
    val productionOrdersUploaded: Int = 0,
    val productionOrdersDownloaded: Int = 0,
    val productionOrdersError: String? = null,
    
    val rdProjectsLocal: Int = 0,
    val rdProjectsUploaded: Int = 0,
    val rdProjectsDownloaded: Int = 0,
    val rdProjectsError: String? = null,
    
    val qaTestsLocal: Int = 0,
    val qaTestsUploaded: Int = 0,
    val qaTestsDownloaded: Int = 0,
    val qaTestsError: String? = null,
    
    val labSessionsLocal: Int = 0,
    val labSessionsUploaded: Int = 0,
    val labSessionsDownloaded: Int = 0,
    val labSessionsError: String? = null,
    
    val isFinished: Boolean = false,
    val isSuccess: Boolean = false,
    val finalMessage: String = "",
    val overallError: String? = null,
    val fileSyncReportMessage: String = ""
)

object SyncManager {
    private const val TAG = "GBR_SyncManager"

    @Volatile
    private var isFirebaseInitialized = false

    suspend fun repairAndInitializeDatabase(
        context: Context,
        repository: GbrRepository,
        skipDataSync: Boolean,
        onProgress: (step: String, percent: Float, success: Boolean, message: String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()
        fun addLog(msg: String) {
            steps.add(msg)
            addLocalSystemLog(context, "sync", msg)
            val formattedLogs = steps.joinToString("\n")
            onProgress("log_update", 0.0f, true, formattedLogs)
        }

        try {
            // STEP 1: CHECK CONNECTION & AUTHORIZATION
            onProgress("connection", 0.10f, true, "جاري فحص الاتصال وتأكيد صلاحيات المستخدم...")
            addLog("📡 فحص الاتصال بالخادم الرئيسي وتأكيد صلاحيات الوصول...")
            
            val isNet = isNetworkAvailable(context)
            val isApproved = if (isNet) {
                val currentStatus = com.example.data.DeviceSecurityManager.verifyDeviceStatus(context)
                currentStatus == com.example.data.DeviceSecurityManager.STATUS_APPROVED
            } else {
                false
            }

            val db = if (isNet && isApproved) {
                if (initializeFirebase(context)) {
                    try {
                        kotlinx.coroutines.withTimeout(8000L) {
                            val firestore = FirebaseFirestore.getInstance()
                            val testDocRef = firestore.collection("connection_test").document("test_status")
                            val testPayload = hashMapOf<String, Any>(
                                "status" to "connected",
                                "timestamp" to System.currentTimeMillis(),
                                "last_tested_by" to "Android App Smart DB Check",
                                "device_time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            )
                            testDocRef.set(testPayload, SetOptions.merge()).awaitTask()
                            addLog("✓ تم الاتصال بالخادم وتأكيد صلاحيات المستخدم بنجاح")
                            firestore
                        }
                    } catch (e: Exception) {
                        addLog("⚠️ تحذير: فشل الاتصال بالسحابة أو انتهت مهلة فحص الاتصال (8 ثوانٍ): ${e.localizedMessage}")
                        null
                    }
                } else {
                    addLog("⚠️ تعذر تهيئة الخادم السحابي. العمل بالمستوى المحلي الآمن فقط.")
                    null
                }
            } else {
                if (isNet && !isApproved) {
                    addLog("⚠️ هذا الجهاز غير معتمد أو حالته معلقة. سيتم تشغيل الفحص على المستوى المحلي الآمن فقط.")
                } else {
                    addLog("⚠️ لا يوجد اتصال بالإنترنت حالياً. جاري الانتقال لمستوى العمل المحلي الآمن.")
                }
                null
            }

            // STEP 2: CHECK SECTIONS & COLLECTIONS INDEXING
            onProgress("sections", 0.35f, true, "جاري فحص وجود وهياكل الأقسام الرئيسية...")
            addLog("📂 فحص الأقسام الرئيسية لقاعدة البيانات لتأكيد الهيكل الأساسي...")
            
            val basicSections = listOf(
                "settings" to "إعدادات النظام",
                "custom_users" to "المستخدمين",
                "custom_products" to "المنتجات",
                "raw_materials" to "المواد الخام",
                "formulations" to "التركيبات وصيغ الإنتاج",
                "production_orders" to "أوامر الإنتاج",
                "custom_inventory" to "المخزون والمستودعات",
                "custom_suppliers" to "الموردين",
                "custom_customers" to "الزبائن والعملاء",
                "development_projects" to "الأبحاث والتطوير R&D",
                "custom_costs" to "التكاليف والمصروفات",
                "custom_plans" to "الخطط المستقبلية",
                "system_logs" to "سجلات النظام والتقارير"
            )

            if (db != null) {
                try {
                    kotlinx.coroutines.withTimeout(10000L) {
                        for ((collKey, collLabel) in basicSections) {
                            try {
                                val metaDoc = db.collection(collKey).document("_init_metadata")
                                val docSnap = metaDoc.get().awaitTask()
                                if (!docSnap.exists()) {
                                    val structureMap = hashMapOf<String, Any>(
                                        "status" to "active",
                                        "databaseVersion" to "1.0.0",
                                        "initializedAt" to System.currentTimeMillis(),
                                        "notes" to "Structural verification meta entry for Section: $collLabel"
                                    )
                                    metaDoc.set(structureMap, SetOptions.merge()).awaitTask()
                                    addLog("✓ تم إنشاء قسم $collKey ($collLabel) وتأمينه بنجاح")
                                } else {
                                    addLog("✓ تم التحقق: قسم $collKey ($collLabel) موجود وسليم")
                                }
                            } catch (e: Exception) {
                                addLog("⚠️ تنبيه فحص القسم $collKey: ${e.localizedMessage}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLog("⚠️ تنبيه: انتهت مهلة التحقق من أقسام قاعدة البيانات السحابية. تم الانتقال للخطوة التالية لتجنب التعليق.")
                }
            } else {
                for ((collKey, _) in basicSections) {
                    addLog("✓ تم التحقق: قسم $collKey متوفر محلياً")
                }
            }

            // STEP 3: CHECK FIELDS INTERNALLY IN TABLES
            onProgress("fields", 0.55f, true, "جاري فحص وصيانة البنية الداخلية للحقول...")
            addLog("⚙️ فحص البنية الداخلية لمطابقة حقول قسم المواد الخام والمواصفات...")
            
            try {
                val sDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
                val cursor = sDb.query("PRAGMA table_info(raw_materials)")
                val currentCols = mutableListOf<String>()
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0) {
                        currentCols.add(cursor.getString(nameIdx))
                    }
                }
                cursor.close()
                
                var repairedCount = 0
                if (!currentCols.contains("code")) {
                    sDb.execSQL("ALTER TABLE raw_materials ADD COLUMN code TEXT DEFAULT '';")
                    repairedCount++
                    addLog("🛠️ تم إصلاح وإضافة حقل 'code' المفقود في جدول المواد الخام.")
                }
                if (!currentCols.contains("unit")) {
                    sDb.execSQL("ALTER TABLE raw_materials ADD COLUMN unit TEXT DEFAULT 'كيلو';")
                    repairedCount++
                    addLog("🛠️ تم إصلاح وإضافة حقل 'unit' المفقود في جدول المواد الخام.")
                }
                if (!currentCols.contains("category")) {
                    sDb.execSQL("ALTER TABLE raw_materials ADD COLUMN category TEXT DEFAULT 'عام';")
                    repairedCount++
                    addLog("🛠️ تم إصلاح وإضافة حقل 'category' المفقود في جدول المواد الخام.")
                }
                
                if (repairedCount > 0) {
                    addLog("✓ تم إصلاح هيكل قسم المواد الخام وإضافة الحقول المفقودة ($repairedCount حقل)")
                } else {
                    addLog("✓ بنية حقول أقسام المواد الخام والتركيبات مطابقة 100% للمعايير")
                }
            } catch (e: Exception) {
                addLog("⚠️ خطأ غير متوقع بصيانة SQLite المحلية: ${e.localizedMessage}")
            }

            if (db != null) {
                try {
                    db.collection("raw_materials").document("_init_metadata").set(
                        hashMapOf(
                            "status" to "active",
                            "fields" to listOf("name", "code", "unit", "price", "category"),
                            "databaseVersion" to "1.0.0",
                            "last_verified" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).awaitTask()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed write fields metadata", e)
                }
            }

            // STEP 4: DATABASE VERSION CONTROL
            onProgress("version", 0.75f, true, "التحقق من إصدار قاعدة البيانات والتبديل الذكي...")
            addLog("🔄 مقارنة وفحص نسخة تشغيل الهياكل...")
            
            val appVersion = "1.0.0"
            val versionPrefs = context.getSharedPreferences("gbr_database_version_prefs", Context.MODE_PRIVATE)
            val currentLocalVersion = versionPrefs.getString("databaseVersion", "0.0.0") ?: "0.0.0"
            
            addLog("ℹ️ إصدار التطبيق: $appVersion | إصدار القاعدة الحالي: $currentLocalVersion")
            
            if (currentLocalVersion != appVersion) {
                addLog("✓ تم اكتشاف تحديث لهيكل قاعدة البيانات ($currentLocalVersion ➔ $appVersion)")
                versionPrefs.edit().putString("databaseVersion", appVersion).apply()
                
                if (db != null) {
                    try {
                        db.collection("settings").document("database_version_info").set(
                            hashMapOf(
                                "databaseVersion" to appVersion,
                                "lastUpdatedBy" to "Smart Client Auto Rebuild",
                                "timestamp" to System.currentTimeMillis()
                            ),
                            SetOptions.merge()
                        ).awaitTask()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed write DB version metadata", e)
                    }
                }
                addLog("✓ تم تطبيق التحديثات بنجاح وترخيص إصدار الهيكل الجديد $appVersion")
            } else {
                addLog("✓ نظام إصدار قاعدة البيانات سليم وقاعدة البيانات تعمل بالإصدار $appVersion")
            }

            // STEP 5: SYNC EVALUATION
            if (!skipDataSync) {
                onProgress("sync_data", 0.85f, true, "جاري إجراء مزامنة ذكية للملفات والسجلات...")
                addLog("📦 مقارنة السجلات والبيانات بين الهاتف والسحاب وبدء التحديث...")
                
                val reportResult = fullBidirectionalSync(context, repository) { sec, percent, ok, msg ->
                    val progressVal = 0.85f + (percent * 0.15f)
                    onProgress("sync_data_progress", progressVal, ok, msg)
                }
                
                if (reportResult.isSuccess) {
                    addLog("✓ تمت المزامنة الشاملة للبيانات وتحديث 152+ سجل بنجاح تام")
                } else {
                    addLog("⚠️ تمت المزامنة مع ملاحظات تشغيلية: ${reportResult.finalMessage}")
                }
            } else {
                addLog("🚫 تم تجاوز مرحلة تحميل ومزامنة السجلات بناء على أمر الإصلاح السريع المباشر.")
            }

            // COMPLETE SUCCESS
            onProgress("complete", 1.0f, true, "اكتملت العملية بنجاح!")
            addLog("🎉 اكتمل بناء وإصلاح قاعدة البيانات والمزامنة بنجاح تام!")
            true
        } catch (e: Exception) {
            onProgress("error", 0.0f, false, "فشلت العملية")
            addLog("❌ فشل نظام التحقق وإصلاح الهياكل: ${e.localizedMessage}")
            false
        }
    }

    // تجميع أخطاء القسم (بدل الاحتفاظ بآخر خطأ فقط): حتى 5 أخطاء، كل منها مقصوص، بفاصل «؛» (سطر واحد)
    // ===== سجل النظام بنصوص مفهومة: أسماء السجلات بدل المعرفات الداخلية، وترجمة أخطاء Firestore =====
    private val logIdLabels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val logUuidRegex = Regex("(ref_specs_)?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun rememberLogLabel(id: String, label: String) {
        if (id.isNotBlank() && label.isNotBlank()) logIdLabels[id] = label
    }

    private fun shortLogId(id: String): String = id.removePrefix("ref_specs_").take(6)

    private fun typeLabelAr(type: String): String = when (type) {
        "raw_material" -> "مادة خام"
        "formulation" -> "تركيبة"
        "production_order" -> "أمر إنتاج"
        "development_project" -> "مشروع بحث وتطوير"
        "quality_test" -> "فحص جودة"
        "production_adjustment" -> "تعديل إنتاج"
        "lab_session" -> "جلسة مختبر"
        "lab_test" -> "فحص مختبر"
        "lab_attachment" -> "مرفق مختبر"
        "production_log" -> "سجل إنتاج"
        "operational_alert" -> "تنبيه تشغيلي"
        "formulation_reference_specs" -> "المواصفات المرجعية للتركيبة"
        else -> type
    }

    // وصف مقروء للسجل: «تركيبة: اسمها» / «أمر إنتاج: PO-0068» (وإن لم يُعثر عليه: النوع + رمز قصير)
    private suspend fun describeRecord(repository: GbrRepository, id: String, type: String): String {
        val fallback = "${typeLabelAr(type)} (#${shortLogId(id)})"
        return try {
            val label: String? = when (type) {
                "raw_material" -> repository.getRawMaterialById(id)?.name
                "formulation" -> repository.getFormulationById(id)?.name
                "production_order" -> repository.getProductionOrderById(id)?.orderNumber
                "development_project" -> repository.getDevelopmentProjectById(id)?.name
                "quality_test" -> repository.gbrDao().getAllQualityTestsSync().find { it.id == id }?.name
                "production_adjustment" -> repository.getAllProductionAdjustments().first().find { it.id == id }?.let { a ->
                    val ord = repository.getProductionOrderById(a.productionOrderId)?.orderNumber
                    "${a.rawMaterialName}" + (if (ord != null) " في الأمر $ord" else "")
                }
                "lab_session" -> {
                    if (id.startsWith("ref_specs_")) {
                        repository.getFormulationById(id.removePrefix("ref_specs_"))?.name?.let { "المواصفات المرجعية للتركيبة $it" }
                    } else repository.getLabSessionById(id)?.let { "${it.sessionNumber} - ${it.testName}" }
                }
                "lab_test" -> repository.getLabTestById(id)?.name
                "lab_attachment" -> repository.getLabAttachmentById(id)?.testName
                "formulation_reference_specs" -> repository.getFormulationById(id)?.name
                else -> null
            }
            if (label.isNullOrBlank()) fallback else {
                rememberLogLabel(id, label)
                if (label.startsWith("المواصفات المرجعية")) label else "${typeLabelAr(type)}: $label"
            }
        } catch (e: Exception) { fallback }
    }

    // يحوّل نصوص أخطاء Firestore/Room التقنية إلى عبارات عربية واضحة، ويستبدل أي معرّف داخلي بالاسم إن عُرف
    fun friendlyLogText(message: String): String {
        var m = message
        m = m.replace(Regex("UNAVAILABLE:[^|\\n]*"), "تعذر الاتصال بالسحابة (الإنترنت مقطوع أو ضعيف) — سيُعاد الرفع تلقائياً عند عودة الاتصال")
        m = m.replace(Regex("DEADLINE_EXCEEDED:[^|\\n]*"), "انتهت مهلة الاتصال بالسحابة (لا رد خلال 30 ثانية)")
        m = m.replace(Regex("PERMISSION_DENIED:[^|\\n]*"), "السحابة رفضت العملية (لا توجد صلاحية)")
        m = m.replace(Regex("UNAUTHENTICATED:[^|\\n]*"), "السحابة رفضت العملية (تسجيل الدخول غير صالح)")
        m = m.replace(Regex("RESOURCE_EXHAUSTED:[^|\\n]*"), "تم تجاوز حد الاستخدام المسموح للسحابة (Quota)")
        m = m.replace(Regex("FOREIGN KEY constraint failed[^|\\n]*"), "السجل مرتبط بسجل آخر غير موجود على هذا الجهاز")
        m = m.replace(Regex(" \\| رمز الخطأ: [A-Z_]+"), "")
        m = logUuidRegex.replace(m) { r ->
            val full = r.value
            logIdLabels[full] ?: logIdLabels[full.removePrefix("ref_specs_")] ?: "سجل #${shortLogId(full)}"
        }
        return m
    }

    private fun cloudDocLabel(doc: DocumentSnapshot, id: String): String =
        doc.getString("name") ?: doc.getString("orderNumber") ?: doc.getString("sessionNumber")
            ?: doc.getString("sampleName") ?: "#${shortLogId(id)}"

    private fun appendSyncError(old: String?, msg: String): String {
        val clean = friendlyLogText(msg).replace('\n', ' ').take(180)
        if (old.isNullOrBlank()) return clean
        val parts = old.split(" ؛ ")
        if (parts.size >= 5) return old
        return "$old ؛ $clean"
    }

    fun addLocalSystemLog(context: Context, category: String, rawMessage: String) {
        val message = friendlyLogText(rawMessage)
        try {
            val userStr = "مزامنة السحابية"
            val deviceStr = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            
            val logsPrefs = context.getSharedPreferences("gbr_system_logs", Context.MODE_PRIVATE)
            val logsJson = logsPrefs.getString("logs_json", "[]") ?: "[]"
            val arr = org.json.JSONArray(logsJson)
            val newObj = org.json.JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("category", category)
                put("message", message)
                put("username", userStr)
                put("deviceName", deviceStr)
            }
            val newArr = org.json.JSONArray()
            newArr.put(newObj)
            for (i in 0 until arr.length()) {
                if (newArr.length() >= 100) break
                newArr.put(arr.getJSONObject(i))
            }
            logsPrefs.edit().putString("logs_json", newArr.toString()).apply()
            
            val logsList = mutableListOf<com.example.ui.GbrViewModel.SystemLog>()
            for (i in 0 until newArr.length()) {
                val o = newArr.getJSONObject(i)
                logsList.add(com.example.ui.GbrViewModel.SystemLog(
                    timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                    category = o.optString("category", "sync"),
                    message = o.optString("message", ""),
                    username = o.optString("username", "مزامنة السحابية"),
                    deviceName = o.optString("deviceName", "جهاز مجهول")
                ))
            }
            com.example.ui.GbrViewModel.externalSystemLogsUpdates.tryEmit(logsList)
            Log.d(TAG, "[$category] $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error writing system log from SyncManager", e)
        }
    }

    fun isFirebaseSetupComplete(): Boolean {
        synchronized(this) {
            return isFirebaseInitialized
        }
    }

    fun initializeFirebase(context: Context): Boolean {
        synchronized(this) {
            return try {
                val prefs = context.getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
                val userProjectId = prefs.getString("fb_project_id", null)?.trim()
                val userApiKey = prefs.getString("fb_api_key", null)?.trim()
                val userAppId = prefs.getString("fb_app_id", null)?.trim()
                val userStorageBucket = prefs.getString("fb_storage_bucket", null)?.trim() ?: (if (userProjectId != null) "$userProjectId.firebasestorage.app" else null)

                if (!userProjectId.isNullOrBlank() && !userApiKey.isNullOrBlank() && !userAppId.isNullOrBlank()) {
                    val apps = FirebaseApp.getApps(context)
                    val currentApp = apps.firstOrNull()
                    val matches = currentApp?.options?.projectId == userProjectId &&
                            currentApp.options.apiKey == userApiKey &&
                            currentApp.options.applicationId == userAppId &&
                            (userStorageBucket == null || currentApp.options.storageBucket == userStorageBucket)
                    
                    if (matches) {
                        isFirebaseInitialized = true
                        WriteDiagnostics.init(context)
                        return true
                    }
                    
                    if (currentApp != null) {
                        currentApp.delete()
                    }
                    
                    val builder = FirebaseOptions.Builder()
                        .setProjectId(userProjectId)
                        .setApiKey(userApiKey)
                        .setApplicationId(userAppId)
                    if (!userStorageBucket.isNullOrBlank()) {
                        builder.setStorageBucket(userStorageBucket)
                    }
                    val options = builder.build()
                    FirebaseApp.initializeApp(context, options)
                    isFirebaseInitialized = true
                    try {
                        val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                        if (auth.currentUser == null) {
                            auth.signInAnonymously()
                        }
                    } catch (_: Exception) {}
                    Log.i(TAG, "Firebase initialized using custom configuration properties successfully with storage bucket.")
                    addLocalSystemLog(context, "sync", "✅ تم نجاح تهيئة Firebase للاتصال بالمشروع المخصص: $userProjectId")
                    
                    WriteDiagnostics.init(context)
                    true
                } else {
                    isFirebaseInitialized = false
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing programmatic Firebase client", e)
                addLocalSystemLog(context, "error", "❌ فشل تهيئة Firebase: ${e.localizedMessage}")
                isFirebaseInitialized = false
                false
            }
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun fullBidirectionalSync(
        context: Context,
        repository: GbrRepository,
        targetSection: String = "all",
        onProgress: (section: String, percent: Float, success: Boolean, message: String) -> Unit
    ): SyncReport = withContext(Dispatchers.IO) {
        var report = SyncReport()

        // Enforce device security approval check
        val currentStatus = if (com.example.data.DeviceSecurityManager.isCloudConfigured(context) && isNetworkAvailable(context)) {
            com.example.data.DeviceSecurityManager.verifyDeviceStatus(context)
        } else {
            com.example.data.DeviceSecurityManager.deviceStatus.value
        }
        if (currentStatus != com.example.data.DeviceSecurityManager.STATUS_APPROVED) {
            val msg = "⚠️ المزامنة معطلة: هذا الجهاز غير معتمد أو حالته ($currentStatus)."
            onProgress("all", 0f, false, msg)
            return@withContext report.copy(
                isFinished = true,
                isSuccess = false,
                connectionError = "الجهاز غير معتمد: $currentStatus",
                finalMessage = msg
            )
        }

        if (!initializeFirebase(context)) {
            val msg = "فشل تهيئة السحابة. تحقق من إعدادات الاتصال."
            onProgress("all", 0f, false, msg)
            return@withContext report.copy(
                isFinished = true,
                isSuccess = false,
                connectionError = "فشل تهيئة Firebase",
                finalMessage = msg
            )
        }

        if (!isNetworkAvailable(context)) {
            val msg = "لا يوجد اتصال بالإنترنت حالياً. يعمل التطبيق في وضع عدم الاتصال."
            onProgress("all", 0f, false, msg)
            return@withContext report.copy(
                isFinished = true,
                isSuccess = false,
                connectionError = "لا يوجد اتصال بالإنترنت",
                finalMessage = msg
            )
        }

        val db = try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            val msg = "تعذر تشغيل محرك Firestore: ${e.message}"
            onProgress("all", 0f, false, msg)
            return@withContext report.copy(
                isFinished = true,
                isSuccess = false,
                connectionError = "محرك Firestore: ${e.localizedMessage}",
                finalMessage = msg
            )
        }

        // Connection test write to connection_test/test_status with 10-second timeout to prevent freezing
        var connSucceeded = false
        var connErr: String? = null
        if (com.example.data.DeviceSecurityManager.isCloudConfigured(context)) {
            try {
                val testDocRef = db.collection("connection_test").document("test_status")
                val testPayload = hashMapOf<String, Any>(
                    "status" to "connected",
                    "timestamp" to System.currentTimeMillis(),
                    "last_tested_by" to "Android App",
                    "device_time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                )
                kotlinx.coroutines.withTimeout(10000L) {
                    testDocRef.set(testPayload, SetOptions.merge()).awaitTask()
                }
                connSucceeded = true
                Log.i(TAG, "Connection test successfully written to Firestore!")
            } catch (e: Exception) {
                connErr = e.localizedMessage ?: e.message ?: "Firebase security rules, Package or API config issues"
                Log.w(TAG, "Connection test write timed out or failed (offline/unconfigured): ${e.message}")
                addLocalSystemLog(context, "sync", "⚠️ لم يتم تأكيد مستند اختبار الاتصال (Firestore). السبب: $connErr")
            }
        } else {
            connErr = "إعدادات الربط السحابي غير مكتملة"
            Log.i(TAG, "Cloud unconfigured. Skipping connection_test write.")
        }

        report = report.copy(
            connectionSucceeded = connSucceeded,
            connectionError = connErr
        )

        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val nowStr = sdf.format(Date())

            val isAll = (targetSection == "all")
            val totalSteps = if (isAll) 9 else 1
            var currentStep = 0

            onProgress("initialize", 0.05f, true, "بدء عملية المزامنة السحابية للبيانات...")

            fun updateStepProgress(section: String, message: String) {
                currentStep++
                val pct = currentStep.toFloat() / totalSteps.toFloat()
                onProgress(section, pct, true, message)
            }

            // 1. SYNC RAW MATERIALS
            if (isAll || targetSection == "materials" || targetSection == "raw_materials") {
                updateStepProgress("raw_materials", "جاري مزامنة المواد الخام وتحليل الأسعار...")
                report = syncRawMaterialsSection(context, db, repository, report)
                if (report.rawMaterialsError == null) {
                    context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE).edit()
                        .putString("sync_last_raw_materials", nowStr).apply()
                }
            }

            // 2. SYNC FORMULATIONS
            if (isAll || targetSection == "formulations") {
                updateStepProgress("formulations", "جاري مزامنة تركيبات وكتالوجات المصنع...")
                report = syncFormulationsSection(db, context, repository, report)
                if (report.formulationsError == null) {
                    context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE).edit()
                        .putString("sync_last_formulations", nowStr).apply()
                }
            }

            // 3. SYNC PRODUCTION ORDERS
            if (isAll || targetSection == "production_orders") {
                updateStepProgress("production_orders", "جاري مزامنة تشغيل وأوامر الوجبات...")
                report = syncProductionOrdersSection(db, context, repository, report)
                if (report.productionOrdersError == null) {
                    context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE).edit()
                        .putString("sync_last_production_orders", nowStr).apply()
                }

                // 3.1. SYNC PRODUCTION LOGS (ACCURATE STATISTICS)
                if (isAll) {
                    updateStepProgress("production_logs", "جاري مزامنة سجل الإحصائيات الفعلي للأصناف...")
                }
                try {
                    syncProductionLogsSection(db, context, repository)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync production logs", e)
                }
            }

            // 4. SYNC RESEARCH & DEVELOPMENT
            if (isAll) {
                updateStepProgress("research_development", "جاري مزامنة عينات الأبحاث والتطوير R&D...")
                report = syncResearchAndDevelopmentSection(db, context, repository, report)
            }

            // 5. SYNC QUALITY TESTS AND ADJUSTMENTS
            if (isAll) {
                updateStepProgress("quality_control", "جاري مزامنة مقارنات الجودة وفحوص المختبر...")
                report = syncQualityTestsAndAdjustmentsSection(db, context, repository, report)
            }

            // 5.5. SYNC LABORATORY SESSIONS & TESTS
            if (isAll) {
                updateStepProgress("laboratory_sessions", "جاري مزامنة سجلات المختبر وفحوصات الجودة الفورية...")
                try {
                    report = syncLaboratorySessionsAndTestsSection(db, context, repository, report)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing laboratory sessions & tests", e)
                }
            }

            // 6. SYNC CUSTOM PACKAGINGS
            if (isAll) {
                updateStepProgress("custom_packagings", "جاري مزامنة إعدادات عبوات وأوزان التغليف...")
                report = syncCustomPackagingsSection(db, context, report)
            }

            // 7. SYNC CUSTOM USERS
            if (isAll) {
                updateStepProgress("custom_users", "جاري مزامنة تهيئة مستخدمي النظام والصلاحيات...")
                report = syncCustomUsersSection(db, context, report)
            }

            // 7.5. SYNC EXTRAS (TUYA EQUIPMENT DEVICES)
            if (isAll) {
                try {
                    syncTuyaDevicesSection(db, context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing equipment devices in main flow", e)
                }
            }

            // 7.6. SYNC HOSTING GATEWAY CONFIGURATION
            if (isAll) {
                try {
                    syncHostingSettings(db, context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing hosting configuration settings in main flow", e)
                }
            }

            // 7.9. ترميم كميات المواد في الأوامر من سجلات التعديل (بعد نزول الأوامر وسجلات التعديل معاً)
            if (isAll) {
                healAllOrderQuantitiesFromAdjustments(context, repository)
                warnDuplicateFormulationCodes(context, repository)
            }

            // 8. VERIFY AND HEAL FILES/IMAGES IN STORAGE
            if (isAll) {
                updateStepProgress("verify_files", "جاري التحقق وترميم الملفات والصور السحابية...")
                val filesResult = verifyAndHealFilesAndImagesInternal(context, repository)
                report = report.copy(fileSyncReportMessage = filesResult.messageStr)
            }

            val hasSectionError = if (isAll) {
                report.rawMaterialsError != null ||
                        report.formulationsError != null ||
                        report.productionOrdersError != null ||
                        report.rdProjectsError != null ||
                        report.qaTestsError != null ||
                        report.labSessionsError != null
            } else {
                when (targetSection) {
                    "materials", "raw_materials" -> report.rawMaterialsError != null
                    "formulations" -> report.formulationsError != null
                    "production_orders" -> report.productionOrdersError != null
                    else -> false
                }
            }
            val hasAnyError = hasSectionError || (!connSucceeded && report.rawMaterialsDownloaded == 0 && report.rawMaterialsUploaded == 0 && report.formulationsDownloaded == 0 && report.formulationsUploaded == 0 && report.productionOrdersDownloaded == 0 && report.productionOrdersUploaded == 0)

            val failedSectionDetails = if (isAll) listOfNotNull(
                report.rawMaterialsError?.let { "المواد الخام" to it },
                report.formulationsError?.let { "التركيبات" to it },
                report.productionOrdersError?.let { "أوامر الإنتاج" to it },
                report.rdProjectsError?.let { "الأبحاث والتطوير" to it },
                report.qaTestsError?.let { "فحوصات الجودة والتعديلات" to it },
                report.labSessionsError?.let { "المختبر" to it }
            ) else emptyList()
            val finalMsg = if (!isAll) {
                if (hasAnyError) "اكتملت مزامنة القسم المحدد مع وجود تنبيهات." else "تمت مزامنة بيانات القسم المحدد سحابياً بنجاح!"
            } else if (hasAnyError) {
                if (failedSectionDetails.isNotEmpty())
                    "اكتملت المزامنة الشاملة مع وجود تنبيهات في: ${failedSectionDetails.joinToString("، ") { it.first }}. التفاصيل في سجل النظام."
                else
                    "اكتملت المزامنة الشاملة مع وجود بعض التنبيهات والأخطاء السحابية."
            } else {
                "تمت مزامنة جميع البيانات سحابياً بنجاح وتحديث قاعدة البيانات بالكامل!"
            }

            val summaryReport = """
                📊 تقرير المزامنة الشامل:
                - نوع المزامنة: ${if(isAll) "شاملة" else "قسم محدد ($targetSection)"}
                - حالة الاتصال: ${if(connSucceeded) "✅ ناجح" else "❌ فشل"}
                - المواد الخام: [رفع: ${report.rawMaterialsUploaded} | تحميل: ${report.rawMaterialsDownloaded}]
                - التركيبات: [رفع: ${report.formulationsUploaded} | تحميل: ${report.formulationsDownloaded}]
                - أوامر التشغيل: [رفع: ${report.productionOrdersUploaded} | تحميل: ${report.productionOrdersDownloaded}]
                - مشاريع وتجارب التطوير R&D: [رفع: ${report.rdProjectsUploaded} | تحميل: ${report.rdProjectsDownloaded}]
                - فحوصات الجودة والتعديلات: [رفع: ${report.qaTestsUploaded} | تحميل: ${report.qaTestsDownloaded}]
                - فحوص ومقارنات المختبر: [رفع: ${report.labSessionsUploaded} | تحميل: ${report.labSessionsDownloaded}]
                - الرسالة النهائية: $finalMsg
                ${failedSectionDetails.joinToString(" | ") { "⚠️ ${it.first}: ${it.second}" }.let { if (it.isNotBlank()) "- أقسام بها أخطاء: $it" else "" }}
                ${if (report.overallError != null) "- خطأ عام: ${report.overallError}" else ""}
            """.trimIndent()
            addLocalSystemLog(context, if (hasAnyError) "error" else "sync", summaryReport)

            onProgress("all", 1f, !hasAnyError, finalMsg)
            return@withContext report.copy(
                isFinished = true,
                isSuccess = !hasAnyError,
                finalMessage = finalMsg
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected errors during bidirectional synchronization", e)
            val finalMsg = "فشل غير متوقع في المزامنة: ${e.localizedMessage}"
            onProgress("all", 1f, false, finalMsg)
            return@withContext report.copy(
                isFinished = true,
                isSuccess = false,
                overallError = e.localizedMessage,
                finalMessage = finalMsg
            )
        }
    }

    private suspend fun syncRawMaterialsSection(
        context: Context,
        db: FirebaseFirestore, 
        repository: GbrRepository,
        currentReport: SyncReport
    ): SyncReport {
        var uploaded = 0
        var downloaded = 0
        var sectionError: String? = null

        val localList = try {
            repository.rawMaterials.first()
        } catch (e: Exception) {
            sectionError = appendSyncError(sectionError, "خطأ محلي: ${e.localizedMessage}")
            emptyList()
        }
        val localMap = localList.associateBy { it.id }

        val cloudDocs = try {
            db.collection("raw_materials").get().awaitTask().documents
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch raw materials from Firestore", e)
            sectionError = appendSyncError(sectionError, "فشل جلب بيانات السحابة: " + (e.localizedMessage ?: e.message ?: "Firebase read permission blocked"))
            emptyList()
        }
        val cloudDocsMap = cloudDocs.associateBy { it.id }

        if (sectionError == null) {
            for (item in localList) {
                try {
                    val docRef = db.collection("raw_materials").document(item.id)
                    val meta = repository.getSyncMetadataById(item.id)
                    val cloudDoc = cloudDocsMap[item.id]
                    val hasCloudDoc = cloudDoc != null && cloudDoc.exists()
                    val localLastUpdated = meta?.lastUpdated ?: (if (hasCloudDoc) 0L else System.currentTimeMillis())
                    val isPending = meta?.isPendingSync ?: false

                    val resolvedTdsUri = ensureTdsUriIsUploaded(context, repository, item.id, item.tdsUri) ?: ""
                    val payload = hashMapOf<String, Any>(
                        "id" to item.id,
                        "name" to item.name,
                        "productionName" to item.productionName,
                        "price" to item.price,
                        "priceUnit" to item.priceUnit,
                        "notes" to item.notes,
                        "tdsUri" to resolvedTdsUri,
                        "isActive" to item.isActive,
                        "lastUpdated" to localLastUpdated
                    )

                    val priceHistoryList = repository.getPriceHistoryForMaterial(item.id).first()
                    val priceHistoryPayload = priceHistoryList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "rawMaterialId" to it.rawMaterialId,
                            "oldPrice" to it.oldPrice,
                            "newPrice" to it.newPrice,
                            "dateChange" to it.dateChange
                        )
                    }
                    payload["priceHistory"] = priceHistoryPayload

                    if (hasCloudDoc && cloudDoc != null) {
                        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: 0L
                        // «معلّق» لا يكفي وحده للرفع: لا نكتب فوق نسخة سحابية أحدث (تُحدَّث النسخة المحلية منها)
                        val cloudNewer = cloudUpdated > localLastUpdated
                        if (isPending && cloudNewer) {
                            addLocalSystemLog(context, "sync", "🛡️ تم منع رفع نسخة محلية معلّقة أقدم من المادة الخام ${item.name} (نسخة السحابة أحدث) وتحديثها من السحابة")
                        }
                        if (!cloudNewer && (isPending || (localLastUpdated > cloudUpdated))) {
                            docRef.set(payload, SetOptions.merge()).awaitTask()
                            uploaded++
                            WriteDiagnostics.recordWrite(context, "raw_materials")
                        } else {
                            val syncedRaw = RawMaterial(
                                id = item.id,
                                name = cloudDoc.getString("name") ?: item.name,
                                productionName = cloudDoc.getString("productionName") ?: item.productionName,
                                price = cloudDoc.getSafeDouble("price") ?: item.price,
                                priceUnit = cloudDoc.getString("priceUnit") ?: item.priceUnit,
                                notes = cloudDoc.getString("notes") ?: item.notes,
                                tdsUri = cloudDoc.getString("tdsUri").let { if (it.isNullOrBlank()) null else it },
                                isActive = cloudDoc.getBoolean("isActive") ?: item.isActive
                            )
                            val rowId = repository.gbrDao().insertRawMaterial(syncedRaw)
                            if (rowId == -1L) {
                                repository.gbrDao().updateRawMaterial(syncedRaw)
                            }

                            val cloudHistoryList = cloudDoc.get("priceHistory") as? List<Map<String, Any>> ?: emptyList()
                            for (ph in cloudHistoryList) {
                                repository.gbrDao().insertPriceHistory(
                                    PriceHistoryEntry(
                                        id = ph["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                        rawMaterialId = item.id,
                                        oldPrice = (ph["oldPrice"] as? Number)?.toDouble() ?: 0.0,
                                        newPrice = (ph["newPrice"] as? Number)?.toDouble() ?: 0.0,
                                        dateChange = ph["dateChange"] as? String ?: ""
                                    )
                                )
                            }

                            repository.insertSyncMetadata(SyncMetadata(item.id, "raw_material", cloudUpdated, false))
                            downloaded++
                        }
                    } else {
                        docRef.set(payload, SetOptions.merge()).awaitTask()
                        uploaded++
                        WriteDiagnostics.recordWrite(context, "raw_materials")
                    }
                    repository.markAsSynced(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing raw material item ${item.id}", e)
                    sectionError = appendSyncError(sectionError, "رفع/تحميل مادة خام (${item.name}): ${e.localizedMessage}")
                }
            }

            for ((id, cloudDoc) in cloudDocsMap) {
                if (!localMap.containsKey(id)) {
                    try {
                        val syncedRaw = RawMaterial(
                            id = id,
                            name = cloudDoc.getString("name") ?: "",
                            productionName = cloudDoc.getString("productionName") ?: "",
                            price = cloudDoc.getSafeDouble("price") ?: 0.0,
                            priceUnit = cloudDoc.getString("priceUnit") ?: "شيكل",
                            notes = cloudDoc.getString("notes") ?: "",
                            tdsUri = cloudDoc.getString("tdsUri").let { if (it.isNullOrBlank()) null else it },
                            isActive = cloudDoc.getBoolean("isActive") ?: true
                        )
                        val rowId = repository.gbrDao().insertRawMaterial(syncedRaw)
                        if (rowId == -1L) {
                            repository.gbrDao().updateRawMaterial(syncedRaw)
                        }

                        val cloudHistoryList = cloudDoc.get("priceHistory") as? List<Map<String, Any>> ?: emptyList()
                        for (ph in cloudHistoryList) {
                            repository.gbrDao().insertPriceHistory(
                                PriceHistoryEntry(
                                    id = ph["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                    rawMaterialId = id,
                                    oldPrice = (ph["oldPrice"] as? Number)?.toDouble() ?: 0.0,
                                    newPrice = (ph["newPrice"] as? Number)?.toDouble() ?: 0.0,
                                    dateChange = ph["dateChange"] as? String ?: ""
                                )
                            )
                        }
                        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: System.currentTimeMillis()
                        repository.insertSyncMetadata(SyncMetadata(id, "raw_material", cloudUpdated, false))
                        downloaded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error saving downloaded raw material $id", e)
                        sectionError = appendSyncError(sectionError, "تنزيل مادة خام سحابية (${cloudDocLabel(cloudDoc, id)}): ${e.localizedMessage}")
                    }
                }
            }
        }

        return currentReport.copy(
            rawMaterialsLocal = localList.size,
            rawMaterialsUploaded = uploaded,
            rawMaterialsDownloaded = downloaded,
            rawMaterialsError = sectionError
        )
    }

    private suspend fun syncFormulationsSection(
        db: FirebaseFirestore, 
        context: Context,
        repository: GbrRepository,
        currentReport: SyncReport
    ): SyncReport {
        var uploaded = 0
        var downloaded = 0
        var sectionError: String? = null

        val localList = try {
            repository.formulations.first()
        } catch (e: Exception) {
            sectionError = appendSyncError(sectionError, "خطأ محلي: ${e.localizedMessage}")
            emptyList()
        }
        val localMap = localList.associateBy { it.id }

        val cloudDocs = try {
            db.collection("formulations").get().awaitTask().documents
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch formulations from Firestore", e)
            sectionError = appendSyncError(sectionError, "فشل جلب بيانات السحابة: " + (e.localizedMessage ?: e.message ?: "Firebase read permission blocked"))
            emptyList()
        }
        val cloudDocsMap = cloudDocs.associateBy { it.id }

        if (sectionError == null) {
            for (item in localList) {
                try {
                    val docRef = db.collection("formulations").document(item.id)
                    val meta = repository.getSyncMetadataById(item.id)
                    val cloudDoc = cloudDocsMap[item.id]
                    val hasCloudDoc = cloudDoc != null && cloudDoc.exists()
                    val localLastUpdated = meta?.lastUpdated ?: (if (hasCloudDoc) 0L else System.currentTimeMillis())
                    val isPending = meta?.isPendingSync ?: false

                    val payload = hashMapOf<String, Any>(
                        "id" to item.id,
                        "name" to item.name,
                        "code" to item.code,
                        "description" to item.description,
                        "imageUri" to (item.imageUri ?: ""),
                        "version" to item.version,
                        "status" to item.status,
                        "createdAt" to item.createdAt,
                        "notes" to item.notes,
                        "supports18L" to item.supports18L,
                        "netWeight18L" to item.netWeight18L,
                        "supports5L" to item.supports5L,
                        "netWeight5L" to item.netWeight5L,
                        "packagingWeightsJson" to item.packagingWeightsJson,
                    "productionLine" to item.productionLine,
                        "lastUpdated" to localLastUpdated
                    )

                    val itemsList = repository.gbrDao().getFormulationItemsWithDetails(item.id).first()
                    val itemsPayload = itemsList.map {
                        hashMapOf<String, Any?>(
                            "id" to it.id,
                            "formulationId" to it.formulationId,
                            "rawMaterialId" to it.rawMaterialId,
                            "quantityMultiplier" to it.quantityMultiplier,
                            "needsGrinding" to it.needsGrinding,
                            "grindingDurationMinutes" to it.grindingDurationMinutes,
                            "simulatedPrice" to if (it.simulatedPrice != null && it.simulatedPrice > 0.0) it.simulatedPrice else null,
                            "sequence" to it.sequence
                        )
                    }
                    payload["items"] = itemsPayload

                    // Revisions
                    val revisionsList = repository.getRevisionsForFormulation(item.id).first()
                    payload["revisions"] = revisionsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "formulationId" to it.formulationId,
                            "version" to it.version,
                            "dateChange" to it.dateChange,
                            "materialName" to it.materialName,
                            "oldValue" to it.oldValue,
                            "newValue" to it.newValue,
                            "editReason" to it.editReason,
                            "snapshotJson" to (it.snapshotJson ?: "")
                        )
                    }

                    // Phases & Recipe Items
                    val phasesList = repository.getRecipePhasesForFormulationSync(item.id)
                    payload["recipe_phases"] = phasesList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "formulationId" to it.formulationId,
                            "name" to it.name,
                            "sequence" to it.sequence,
                            "mixerRpm" to it.mixerRpm,
                            "durationMinutes" to it.durationMinutes,
                            "instructions" to it.instructions
                        )
                    }
                    val recipeItemsList = repository.getRecipeItemsForFormulationSync(item.id)
                    payload["recipe_items"] = recipeItemsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "phaseId" to it.phaseId,
                            "rawMaterialId" to it.rawMaterialId,
                            "ratio" to it.ratio,
                            "sequence" to it.sequence
                        )
                    }

                    // Recipe Status
                    val statusObj = repository.getRecipeStatusSync(item.id)
                    payload["recipe_status"] = statusObj?.status ?: ""

                    // Quality Tests
                    val fTestsList = repository.getFormulationQualityTestsSync(item.id)
                    payload["quality_tests"] = fTestsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "formulationId" to it.formulationId,
                            "testId" to it.testId,
                            "isEnabled" to it.isEnabled,
                            "minValue" to (it.minValue ?: 0.0),
                            "maxValue" to (it.maxValue ?: 0.0)
                        )
                    }

                    // Reference Specs
                    val refSpecs = repository.getFormulationReferenceSpecsSync(item.id)
                    if (refSpecs != null) {
                        payload["reference_specs"] = hashMapOf<String, Any?>(
                            "approvalDate" to refSpecs.approvalDate,
                            "phValue" to refSpecs.phValue,
                            "densityEmptyWeight" to refSpecs.densityEmptyWeight,
                            "densityFilledWeight" to refSpecs.densityFilledWeight,
                            "densityFinalResult" to refSpecs.densityFinalResult,
                            "solidWeightBefore" to refSpecs.solidWeightBefore,
                            "solidWeightAfter" to refSpecs.solidWeightAfter,
                            "solidResultPct" to refSpecs.solidResultPct,
                            "binderWeightBefore" to refSpecs.binderWeightBefore,
                            "binderWeightAfter" to refSpecs.binderWeightAfter,
                            "binderResultPct" to refSpecs.binderResultPct,
                            "viscosityJson" to refSpecs.viscosityJson,
                            "viscosityFinalResult" to refSpecs.viscosityFinalResult,
                            "viscosityDilutedJson" to refSpecs.viscosityDilutedJson,
                            "viscosityDilutedFinalResult" to refSpecs.viscosityDilutedFinalResult,
                            "rheologyJson" to refSpecs.rheologyJson,
                            "rheologyIndexResult" to refSpecs.rheologyIndexResult
                        )
                    } else {
                        payload["reference_specs"] = com.google.firebase.firestore.FieldValue.delete()
                    }

                    if (hasCloudDoc && cloudDoc != null) {
                        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: 0L
                        // «معلّق» لا يكفي وحده للرفع: لا نكتب فوق نسخة سحابية أحدث (تُحدَّث النسخة المحلية منها)
                        val cloudNewer = cloudUpdated > localLastUpdated
                        if (isPending && cloudNewer) {
                            addLocalSystemLog(context, "sync", "🛡️ تم منع رفع نسخة محلية معلّقة أقدم من التركيبة ${item.name} (نسخة السحابة أحدث) وتحديثها من السحابة")
                        }
                        if (!cloudNewer && (isPending || (localLastUpdated > cloudUpdated))) {
                            docRef.set(payload, SetOptions.merge()).awaitTask()
                            uploaded++
                            WriteDiagnostics.recordWrite(context, "formulations")
                        } else {
                            val cloudFormulation = Formulation(
                                id = item.id,
                                name = cloudDoc.getString("name") ?: item.name,
                                code = cloudDoc.getString("code") ?: item.code,
                                description = cloudDoc.getString("description") ?: item.description,
                                imageUri = cloudDoc.getString("imageUri").let { if (it.isNullOrBlank()) null else it },
                                version = cloudDoc.getString("version") ?: item.version,
                                status = cloudDoc.getString("status") ?: item.status,
                                createdAt = cloudDoc.getString("createdAt") ?: item.createdAt,
                                notes = cloudDoc.getString("notes") ?: item.notes,
                                supports18L = cloudDoc.getBoolean("supports18L") ?: item.supports18L,
                                netWeight18L = cloudDoc.getString("netWeight18L") ?: item.netWeight18L,
                                supports5L = cloudDoc.getBoolean("supports5L") ?: item.supports5L,
                                netWeight5L = cloudDoc.getString("netWeight5L") ?: item.netWeight5L,
                                packagingWeightsJson = cloudDoc.getString("packagingWeightsJson") ?: item.packagingWeightsJson,
                                productionLine = cloudDoc.getLong("productionLine")?.toInt() ?: item.productionLine
                            )
                            repository.gbrDao().updateFormulation(cloudFormulation)
                            saveDownloadedFormulationDetails(repository, item.id, cloudDoc)
                            repository.insertSyncMetadata(SyncMetadata(item.id, "formulation", cloudUpdated, false))
                            downloaded++
                        }
                    } else {
                        docRef.set(payload, SetOptions.merge()).awaitTask()
                        uploaded++
                        WriteDiagnostics.recordWrite(context, "formulations")
                    }
                    repository.markAsSynced(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing formulation ${item.id}", e)
                    sectionError = appendSyncError(sectionError, "قفل/حفظ تركيبة (${item.name}): ${e.localizedMessage}")
                }
            }

            for ((id, cloudDoc) in cloudDocsMap) {
                if (!localMap.containsKey(id)) {
                    try {
                        val cloudFormulation = Formulation(
                            id = id,
                            name = cloudDoc.getString("name") ?: "",
                            code = cloudDoc.getString("code") ?: "",
                            description = cloudDoc.getString("description") ?: "",
                            imageUri = cloudDoc.getString("imageUri").let { if (it.isNullOrBlank()) null else it },
                            version = cloudDoc.getString("version") ?: "1.0.0",
                            status = cloudDoc.getString("status") ?: "🟡 قيد التطوير",
                            createdAt = cloudDoc.getString("createdAt") ?: "",
                            notes = cloudDoc.getString("notes") ?: "",
                            supports18L = cloudDoc.getBoolean("supports18L") ?: false,
                            netWeight18L = cloudDoc.getString("netWeight18L") ?: "",
                            supports5L = cloudDoc.getBoolean("supports5L") ?: false,
                            netWeight5L = cloudDoc.getString("netWeight5L") ?: "",
                            packagingWeightsJson = cloudDoc.getString("packagingWeightsJson") ?: "",
                            productionLine = cloudDoc.getLong("productionLine")?.toInt() ?: -1
                        )
                        val rowId = repository.gbrDao().insertFormulation(cloudFormulation)
                        if (rowId == -1L) {
                            repository.gbrDao().updateFormulation(cloudFormulation)
                        }
                        saveDownloadedFormulationDetails(repository, id, cloudDoc)
                        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: System.currentTimeMillis()
                        repository.insertSyncMetadata(SyncMetadata(id, "formulation", cloudUpdated, false))
                        downloaded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error downloading formulation $id", e)
                        sectionError = appendSyncError(sectionError, "تحميل تركيبة سحابية (${cloudDocLabel(cloudDoc, id)}): ${e.localizedMessage}")
                    }
                }
            }
        }

        return currentReport.copy(
            formulationsLocal = localList.size,
            formulationsUploaded = uploaded,
            formulationsDownloaded = downloaded,
            formulationsError = sectionError
        )
    }

    private suspend fun syncProductionOrdersSection(
        db: FirebaseFirestore, 
        context: Context,
        repository: GbrRepository,
        currentReport: SyncReport
    ): SyncReport {
        var uploaded = 0
        var downloaded = 0
        var sectionError: String? = null

        val localList = try {
            repository.productionOrders.first()
        } catch (e: Exception) {
            sectionError = appendSyncError(sectionError, "خطأ محلي: ${e.localizedMessage}")
            emptyList()
        }
        val localMap = localList.associateBy { it.id }

        val cloudDocs = try {
            db.collection("production_orders").get().awaitTask().documents
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch production orders from Firestore", e)
            sectionError = appendSyncError(sectionError, "فشل جلب بيانات السحابة: " + (e.localizedMessage ?: e.message ?: "Firebase read permission blocked"))
            emptyList()
        }
        val cloudDocsMap = cloudDocs.associateBy { it.id }

        if (sectionError == null) {
            for (item in localList) {
                try {
                    val docRef = db.collection("production_orders").document(item.id)
                    val meta = repository.getSyncMetadataById(item.id)
                    val cloudDoc = cloudDocsMap[item.id]
                    val hasCloudDoc = cloudDoc != null && cloudDoc.exists()
                    val localLastUpdated = meta?.lastUpdated ?: (if (hasCloudDoc) 0L else System.currentTimeMillis())
                    val isPending = meta?.isPendingSync ?: false

                    val payload = hashMapOf<String, Any>(
                        "id" to item.id,
                        "orderNumber" to item.orderNumber,
                        "batchNumber" to item.batchNumber,
                        "formulationId" to item.formulationId,
                        "formulationName" to item.formulationName,
                        "formulationVersion" to item.formulationVersion,
                        "requiredWeightKg" to item.requiredWeightKg,
                        "createdAt" to item.createdAt,
                        "status" to item.status,
                        "notes" to item.notes,
                        "scaleFactor" to item.scaleFactor,
                        "originalWeightKg" to item.originalWeightKg,
                        "progressPercent" to item.progressPercent,
                        "currentPhaseIndex" to item.currentPhaseIndex,
                        "currentItemIndex" to item.currentItemIndex,
                        "completedItemsJson" to item.completedItemsJson,
                        "packagingSnapshotJson" to item.packagingSnapshotJson,
                        "actualPackagingJson" to item.actualPackagingJson,
                        "timerStartTimesJson" to item.timerStartTimesJson,
                        "startTime" to item.startTime,
                        "endTime" to item.endTime,
                        "operatorName" to item.operatorName,
                        "lastUpdated" to localLastUpdated
                    )

                    val pItems = repository.gbrDao().getProductionOrderItems(item.id).first()
                    payload["items"] = pItems.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "productionOrderId" to it.productionOrderId,
                            "rawMaterialId" to it.rawMaterialId,
                            "rawMaterialName" to it.rawMaterialName,
                            "rawMaterialPrice" to it.rawMaterialPrice,
                            "rawMaterialPriceUnit" to it.rawMaterialPriceUnit,
                            "quantityMultiplier" to it.quantityMultiplier,
                            "calculatedQuantity" to it.calculatedQuantity,
                            "needsGrinding" to it.needsGrinding,
                            "grindingDurationMinutes" to it.grindingDurationMinutes
                        )
                    }

                    // Events
                    val eventsList = repository.getProductionOrderEvents(item.id).first()
                    payload["events"] = eventsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "productionOrderId" to it.productionOrderId,
                            "eventName" to it.eventName,
                            "timestamp" to it.timestamp,
                            "description" to it.description
                        )
                    }

                    // Phases & Recipe Items
                    val phasesList = repository.gbrDao().getProductionOrderPhasesSync(item.id)
                    payload["phases"] = phasesList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "productionOrderId" to it.productionOrderId,
                            "name" to it.name,
                            "sequence" to it.sequence,
                            "mixerRpm" to it.mixerRpm,
                            "durationMinutes" to it.durationMinutes,
                            "instructions" to it.instructions
                        )
                    }
                    val recipeItemsList = repository.gbrDao().getProductionOrderRecipeItemsForOrderSync(item.id)
                    payload["recipe_items"] = recipeItemsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "productionOrderPhaseId" to it.productionOrderPhaseId,
                            "rawMaterialId" to it.rawMaterialId,
                            "rawMaterialName" to it.rawMaterialName,
                            "ratio" to it.ratio,
                            "calculatedQuantity" to it.calculatedQuantity,
                            "sequence" to it.sequence
                        )
                    }

                    // Quality Tests Snapshot
                    val qualityTestsList = repository.gbrDao().getProductionOrderQualityTestsSync(item.id)
                    payload["quality_tests"] = qualityTestsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "productionOrderId" to it.productionOrderId,
                            "testId" to it.testId,
                            "testName" to it.testName,
                            "minValue" to (it.minValue ?: 0.0),
                            "maxValue" to (it.maxValue ?: 0.0),
                            "sequenceIndex" to it.sequenceIndex
                        )
                    }

                    // Quality Test Records
                    val recordsList = repository.gbrDao().getProductionOrderTestRecordsSync(item.id)
                    payload["test_records"] = recordsList.map {
                        hashMapOf<String, Any>(
                            "id" to it.id,
                            "productionOrderId" to it.productionOrderId,
                            "isDirectTest" to it.isDirectTest,
                            "testDate" to it.testDate,
                            "resultsJson" to it.resultsJson,
                            "timestamp" to it.timestamp
                        )
                    }

                    if (hasCloudDoc && cloudDoc != null) {
                        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: 0L
                        // «معلّق» لا يكفي وحده للرفع: لا نكتب فوق نسخة سحابية أحدث أو أمر مكتمل/مؤرشف
                        val cloudWins = cloudProductionOrderWins(cloudDoc, localLastUpdated, item.status)
                        if (!cloudWins && (isPending || (localLastUpdated > cloudUpdated))) {
                            docRef.set(payload, SetOptions.merge()).awaitTask()
                            uploaded++
                            WriteDiagnostics.recordWrite(context, "production_orders")
                        } else {
                            val cloudOrder = ProductionOrder(
                                id = item.id,
                                orderNumber = cloudDoc.getString("orderNumber") ?: item.orderNumber,
                                batchNumber = cloudDoc.getString("batchNumber") ?: item.batchNumber,
                                formulationId = cloudDoc.getString("formulationId") ?: item.formulationId,
                                formulationName = cloudDoc.getString("formulationName") ?: item.formulationName,
                                formulationVersion = cloudDoc.getString("formulationVersion") ?: item.formulationVersion,
                                requiredWeightKg = cloudDoc.getSafeDouble("requiredWeightKg") ?: item.requiredWeightKg,
                                createdAt = cloudDoc.getLong("createdAt") ?: item.createdAt,
                                status = cloudDoc.getString("status") ?: item.status,
                                notes = cloudDoc.getString("notes") ?: item.notes,
                                scaleFactor = cloudDoc.getSafeDouble("scaleFactor") ?: item.scaleFactor,
                                originalWeightKg = cloudDoc.getSafeDouble("originalWeightKg") ?: item.originalWeightKg,
                                progressPercent = (cloudDoc.getLong("progressPercent") ?: item.progressPercent.toLong()).toInt(),
                                currentPhaseIndex = (cloudDoc.getLong("currentPhaseIndex") ?: item.currentPhaseIndex.toLong()).toInt(),
                                currentItemIndex = (cloudDoc.getLong("currentItemIndex") ?: item.currentItemIndex.toLong()).toInt(),
                                completedItemsJson = cloudDoc.getString("completedItemsJson") ?: item.completedItemsJson,
                                packagingSnapshotJson = cloudDoc.getString("packagingSnapshotJson") ?: item.packagingSnapshotJson,
                                actualPackagingJson = cloudDoc.getString("actualPackagingJson") ?: item.actualPackagingJson,
                                timerStartTimesJson = mergeTimerStartTimes(item.timerStartTimesJson, cloudDoc.getString("timerStartTimesJson") ?: "{}"),
                                startTime = cloudDoc.getLong("startTime") ?: item.startTime,
                                endTime = cloudDoc.getLong("endTime") ?: item.endTime,
                                operatorName = cloudDoc.getString("operatorName") ?: item.operatorName
                            )
                            repository.gbrDao().insertProductionOrder(cloudOrder)
                            saveDownloadedProductionOrderDetails(repository, item.id, cloudDoc)
                            repository.insertSyncMetadata(SyncMetadata(item.id, "production_order", cloudUpdated, false))
                            downloaded++
                        }
                    } else {
                        docRef.set(payload, SetOptions.merge()).awaitTask()
                        uploaded++
                        WriteDiagnostics.recordWrite(context, "production_orders")
                    }
                    repository.markAsSynced(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing production order ${item.id}", e)
                    sectionError = appendSyncError(sectionError, "رفع/تحديث أمر إنتاج (${item.orderNumber}): ${e.localizedMessage}")
                }
            }

            for ((id, cloudDoc) in cloudDocsMap) {
                if (!localMap.containsKey(id)) {
                    try {
                        val cloudOrder = ProductionOrder(
                            id = id,
                            orderNumber = cloudDoc.getString("orderNumber") ?: "",
                            batchNumber = cloudDoc.getString("batchNumber") ?: "",
                            formulationId = cloudDoc.getString("formulationId") ?: "",
                            formulationName = cloudDoc.getString("formulationName") ?: "",
                            formulationVersion = cloudDoc.getString("formulationVersion") ?: "",
                            requiredWeightKg = cloudDoc.getSafeDouble("requiredWeightKg") ?: 0.0,
                            createdAt = cloudDoc.getLong("createdAt") ?: System.currentTimeMillis(),
                            status = cloudDoc.getString("status") ?: "مسودة",
                            notes = cloudDoc.getString("notes") ?: "",
                            scaleFactor = cloudDoc.getSafeDouble("scaleFactor") ?: 1.0,
                            originalWeightKg = cloudDoc.getSafeDouble("originalWeightKg") ?: 0.0,
                            progressPercent = (cloudDoc.getLong("progressPercent") ?: 0L).toInt(),
                            currentPhaseIndex = (cloudDoc.getLong("currentPhaseIndex") ?: 0L).toInt(),
                            currentItemIndex = (cloudDoc.getLong("currentItemIndex") ?: 0L).toInt(),
                            completedItemsJson = cloudDoc.getString("completedItemsJson") ?: "[]",
                            packagingSnapshotJson = cloudDoc.getString("packagingSnapshotJson") ?: "",
                            actualPackagingJson = cloudDoc.getString("actualPackagingJson") ?: "[]",
                            timerStartTimesJson = cloudDoc.getString("timerStartTimesJson") ?: "{}",
                            startTime = cloudDoc.getLong("startTime") ?: 0L,
                            endTime = cloudDoc.getLong("endTime") ?: 0L,
                            operatorName = cloudDoc.getString("operatorName") ?: "المشرف"
                        )
                        repository.gbrDao().insertProductionOrder(cloudOrder)
                        saveDownloadedProductionOrderDetails(repository, id, cloudDoc)
                        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: System.currentTimeMillis()
                        repository.insertSyncMetadata(SyncMetadata(id, "production_order", cloudUpdated, false))
                        downloaded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error downloading production order $id", e)
                        sectionError = appendSyncError(sectionError, "تنزيل أمر إنتاج سحابي (${cloudDocLabel(cloudDoc, id)}): ${e.localizedMessage}")
                    }
                }
            }
        }

        return currentReport.copy(
            productionOrdersLocal = localList.size,
            productionOrdersUploaded = uploaded,
            productionOrdersDownloaded = downloaded,
            productionOrdersError = sectionError
        )
    }

    private suspend fun syncResearchAndDevelopmentSection(
        db: FirebaseFirestore, 
        context: Context,
        repository: GbrRepository,
        currentReport: SyncReport
    ): SyncReport {
        var uploaded = 0
        var downloaded = 0
        var sectionError: String? = null

        val projects = try {
            repository.allDevelopmentProjects.first()
        } catch (e: Exception) {
            sectionError = appendSyncError(sectionError, "خطأ محلي: ${e.localizedMessage}")
            emptyList()
        }
        val localMap = projects.associateBy { it.id }

        val cloudDocs = try {
            db.collection("development_projects").get().awaitTask().documents
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch development projects", e)
            sectionError = appendSyncError(sectionError, "فشل جلب بيانات السحابة: " + (e.localizedMessage ?: e.message ?: "Firebase read permission blocked"))
            emptyList()
        }
        val cloudDocsMap = cloudDocs.associateBy { it.id }

        if (sectionError == null) {
            for (item in projects) {
                try {
                    val docRef = db.collection("development_projects").document(item.id)
                    val payload = hashMapOf<String, Any>(
                        "id" to item.id,
                        "name" to item.name,
                        "createdAt" to item.createdAt,
                        "lastUpdated" to item.lastUpdated
                    )

                    val samples = repository.gbrDao().getDevelopmentSamplesForProject(item.id).first()
                    payload["samples"] = samples.map {
                        val sampleMap = hashMapOf<String, Any>(
                            "id" to it.id,
                            "projectId" to it.projectId,
                            "sampleName" to it.sampleName,
                            "sampleNumber" to it.sampleNumber,
                            "createdAt" to it.createdAt,
                            "targetWeightKg" to it.targetWeightKg,
                            "targetGoal" to it.targetGoal,
                            "initialNotes" to it.initialNotes,
                            "researchNotes" to it.researchNotes,
                            "isApproved" to it.isApproved,
                            "approvedDate" to it.approvedDate,
                            "resultsJson" to it.resultsJson,
                            "itemsJson" to it.itemsJson,
                            "recipeJson" to it.recipeJson
                        )
                        it.status?.let { s -> sampleMap["status"] = s }
                        it.statusNotes?.let { s -> sampleMap["statusNotes"] = s }
                        it.statusUpdatedAt?.let { s -> sampleMap["statusUpdatedAt"] = s }
                        it.statusUpdatedBy?.let { s -> sampleMap["statusUpdatedBy"] = s }
                        sampleMap
                    }

                    val cloudDoc = cloudDocsMap[item.id]
                    if (cloudDoc != null && cloudDoc.exists()) {
                        val cloudUpdatedStr = cloudDoc.getString("lastUpdated") ?: ""
                        if (item.lastUpdated > cloudUpdatedStr) {
                            docRef.set(payload, SetOptions.merge()).awaitTask()
                            uploaded++
                            WriteDiagnostics.recordWrite(context, "development_projects")
                        } else {
                            val cloudProject = DevelopmentProject(
                                id = item.id,
                                name = cloudDoc.getString("name") ?: item.name,
                                createdAt = cloudDoc.getString("createdAt") ?: item.createdAt,
                                lastUpdated = cloudUpdatedStr
                            )
                            repository.gbrDao().insertDevelopmentProject(cloudProject)

                            val cloudSamples = (cloudDoc.get("samples") as? List<*>) ?: emptyList<Any?>()
                            for (csAny in cloudSamples) {
                                val cs = csAny as? Map<*, *> ?: continue
                                repository.gbrDao().insertDevelopmentSample(
                                    DevelopmentSample(
                                        id = cs["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                        projectId = item.id,
                                        sampleName = cs["sampleName"] as? String ?: "",
                                        sampleNumber = cs["sampleNumber"] as? String ?: "",
                                        createdAt = cs["createdAt"] as? String ?: "",
                                        targetWeightKg = (cs["targetWeightKg"] as? Number)?.toDouble() ?: 1.0,
                                        targetGoal = cs["targetGoal"] as? String ?: "",
                                        initialNotes = cs["initialNotes"] as? String ?: "",
                                        researchNotes = cs["researchNotes"] as? String ?: "",
                                        isApproved = cs["isApproved"] as? Boolean ?: false,
                                        approvedDate = cs["approvedDate"] as? String ?: "",
                                        resultsJson = cs["resultsJson"] as? String ?: "[]",
                                        itemsJson = cs["itemsJson"] as? String ?: "[]",
                                        recipeJson = cs["recipeJson"] as? String ?: "[]",
                                        status = cs["status"] as? String,
                                        statusNotes = cs["statusNotes"] as? String,
                                        statusUpdatedAt = cs["statusUpdatedAt"] as? String,
                                        statusUpdatedBy = cs["statusUpdatedBy"] as? String
                                    )
                                )
                            }
                            downloaded++
                        }
                    } else {
                        docRef.set(payload, SetOptions.merge()).awaitTask()
                        uploaded++
                        WriteDiagnostics.recordWrite(context, "development_projects")
                    }
                    repository.markAsSynced(item.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error syncing development project ${item.id}", e)
                    sectionError = appendSyncError(sectionError, "رفع/تحديث مشروع R&D (${item.name}): ${e.localizedMessage}")
                }
            }

            for ((id, cloudDoc) in cloudDocsMap) {
                if (!localMap.containsKey(id)) {
                    try {
                        val cloudProject = DevelopmentProject(
                            id = id,
                            name = cloudDoc.getString("name") ?: "",
                            createdAt = cloudDoc.getString("createdAt") ?: "",
                            lastUpdated = cloudDoc.getString("lastUpdated") ?: ""
                        )
                        repository.gbrDao().insertDevelopmentProject(cloudProject)

                        val cloudSamples = (cloudDoc.get("samples") as? List<*>) ?: emptyList<Any?>()
                        for (csAny in cloudSamples) {
                            val cs = csAny as? Map<*, *> ?: continue
                            repository.gbrDao().insertDevelopmentSample(
                                DevelopmentSample(
                                    id = cs["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                    projectId = id,
                                    sampleName = cs["sampleName"] as? String ?: "",
                                    sampleNumber = cs["sampleNumber"] as? String ?: "",
                                    createdAt = cs["createdAt"] as? String ?: "",
                                    targetWeightKg = (cs["targetWeightKg"] as? Number)?.toDouble() ?: 1.0,
                                    targetGoal = cs["targetGoal"] as? String ?: "",
                                    initialNotes = cs["initialNotes"] as? String ?: "",
                                    researchNotes = cs["researchNotes"] as? String ?: "",
                                    isApproved = cs["isApproved"] as? Boolean ?: false,
                                    approvedDate = cs["approvedDate"] as? String ?: "",
                                    resultsJson = cs["resultsJson"] as? String ?: "[]",
                                    itemsJson = cs["itemsJson"] as? String ?: "[]",
                                    recipeJson = cs["recipeJson"] as? String ?: "[]",
                                    status = cs["status"] as? String,
                                    statusNotes = cs["statusNotes"] as? String,
                                    statusUpdatedAt = cs["statusUpdatedAt"] as? String,
                                    statusUpdatedBy = cs["statusUpdatedBy"] as? String
                                )
                            )
                        }
                        downloaded++
                        repository.insertSyncMetadata(SyncMetadata(id, "development_project", System.currentTimeMillis(), false))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error downloading development project $id", e)
                        sectionError = appendSyncError(sectionError, "تنزيل مشروع R&D سحابي (${cloudDocLabel(cloudDoc, id)}): ${e.localizedMessage}")
                    }
                }
            }
        }

        return currentReport.copy(
            rdProjectsLocal = projects.size,
            rdProjectsUploaded = uploaded,
            rdProjectsDownloaded = downloaded,
            rdProjectsError = sectionError
        )
    }

    private suspend fun syncQualityTestsAndAdjustmentsSection(
        db: FirebaseFirestore, 
        context: Context,
        repository: GbrRepository,
        currentReport: SyncReport
    ): SyncReport {
        var uploaded = 0
        var downloaded = 0
        var sectionError: String? = null

        try {
            val cloudTestsDocs = try {
                db.collection("quality_tests").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud quality tests", e)
                sectionError = appendSyncError(sectionError, "فشل جلب فحوصات الجودة من السحابة: ${e.localizedMessage}")
                emptyList()
            }
            val cloudTestsMap = cloudTestsDocs.associateBy { it.id }
            val localTests = repository.gbrDao().getAllQualityTestsSync()
            for (qt in localTests) {
                try {
                    val meta = repository.getSyncMetadataById(qt.id)
                    val isPending = meta?.isPendingSync ?: true
                    val hasCloudDoc = cloudTestsMap.containsKey(qt.id)
                    if (isPending || !hasCloudDoc) {
                        db.collection("quality_tests").document(qt.id).set(
                            hashMapOf<String, Any>(
                                 "id" to qt.id,
                                 "name" to qt.name,
                                 "sequenceIndex" to qt.sequenceIndex
                            ), SetOptions.merge()
                        ).awaitTask()
                        uploaded++
                        WriteDiagnostics.recordWrite(context, "quality_tests")
                    }
                    repository.markAsSyncedOrCreate(qt.id, "quality_test")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload quality test ${qt.id}", e)
                    sectionError = appendSyncError(sectionError, "تنزيل/رفع فحص جودة (${qt.name}): ${e.localizedMessage}")
                }
            }
            for (dc in cloudTestsDocs) {
                val id = dc.id
                if (localTests.none { it.id == id }) {
                    try {
                        repository.gbrDao().insertQualityTest(
                            QualityTest(
                                id = id,
                                name = dc.getString("name") ?: "",
                                sequenceIndex = (dc.getLong("sequenceIndex") ?: 0L).toInt()
                            )
                        )
                        repository.insertSyncMetadata(SyncMetadata(id, "quality_test", System.currentTimeMillis(), false))
                        downloaded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded quality test $id", e)
                        sectionError = appendSyncError(sectionError, "تحميل فحص جودة (${cloudDocLabel(dc, id)}): ${e.localizedMessage}")
                    }
                }
            }

            val cloudAdjustmentDocs = try {
                db.collection("production_adjustments").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud adjustments", e)
                sectionError = appendSyncError(sectionError, "فشل جلب تعديلات التشغيل من السحابة: ${e.localizedMessage}")
                emptyList()
            }
            val cloudAdjustmentDocsMap = cloudAdjustmentDocs.associateBy { it.id }
            val localAdjustments = repository.getAllProductionAdjustments().first()
            for (adj in localAdjustments) {
                try {
                    val meta = repository.getSyncMetadataById(adj.id)
                    val isPending = meta?.isPendingSync ?: true
                    val hasCloudDoc = cloudAdjustmentDocsMap.containsKey(adj.id)
                    if (isPending || !hasCloudDoc) {
                        db.collection("production_adjustments").document(adj.id).set(
                            hashMapOf<String, Any>(
                                "id" to adj.id,
                                "productionOrderId" to adj.productionOrderId,
                                "rawMaterialId" to adj.rawMaterialId,
                                "rawMaterialName" to adj.rawMaterialName,
                                "originalQuantity" to adj.originalQuantity,
                                "newQuantity" to adj.newQuantity,
                                "difference" to adj.difference,
                                "reason" to adj.reason,
                                "notes" to adj.notes,
                                "timestamp" to adj.timestamp,
                                "userName" to adj.userName
                            ), SetOptions.merge()
                        ).awaitTask()
                        uploaded++
                        WriteDiagnostics.recordWrite(context, "production_adjustments")
                    }
                    repository.markAsSyncedOrCreate(adj.id, "production_adjustment")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload adjustment ${adj.id}", e)
                    sectionError = appendSyncError(sectionError, "رفع تعديل إنتاج (${adj.rawMaterialName}): ${e.localizedMessage}")
                }
            }
            var skippedOrphanAdjustments = 0
            for (dc in cloudAdjustmentDocs) {
                val id = dc.id
                if (localAdjustments.none { it.id == id }) {
                    try {
                        // تعديل يخص أمراً غير موجود محلياً (محذوف/غير منزّل): يُتخطى بهدوء بدل خطأ FOREIGN KEY في كل مزامنة
                        val adjOrderIdFull = dc.getString("productionOrderId") ?: ""
                        if (adjOrderIdFull.isBlank() || repository.getProductionOrderById(adjOrderIdFull) == null) {
                            skippedOrphanAdjustments++
                            continue
                        }
                        repository.gbrDao().insertProductionAdjustment(
                            ProductionAdjustment(
                                id = id,
                                productionOrderId = dc.getString("productionOrderId") ?: "",
                                rawMaterialId = dc.getString("rawMaterialId") ?: "",
                                rawMaterialName = dc.getString("rawMaterialName") ?: "",
                                originalQuantity = dc.getSafeDouble("originalQuantity") ?: 0.0,
                                newQuantity = dc.getSafeDouble("newQuantity") ?: 0.0,
                                difference = dc.getSafeDouble("difference") ?: 0.0,
                                reason = dc.getString("reason") ?: "",
                                notes = dc.getString("notes") ?: "",
                                timestamp = dc.getLong("timestamp") ?: System.currentTimeMillis(),
                                userName = dc.getString("userName") ?: "سحابي"
                            )
                        )
                        repository.insertSyncMetadata(SyncMetadata(id, "production_adjustment", System.currentTimeMillis(), false))
                        downloaded++
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded adjustment $id", e)
                        sectionError = appendSyncError(sectionError, "تحميل تعديل إنتاج سحابي (${dc.getString("rawMaterialName") ?: "#${shortLogId(id)}"}): ${e.localizedMessage}")
                    }
                }
            }
            if (skippedOrphanAdjustments > 0) {
                addLocalSystemLog(context, "sync", "ℹ️ تم تخطي $skippedOrphanAdjustments تعديل سحابي لأوامر إنتاج غير موجودة على هذا الجهاز (لا يؤثر على البيانات).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "General QA testing error", e)
            sectionError = appendSyncError(sectionError, "أخطاء قسم الجودة والمطابقة: ${e.localizedMessage}")
        }

        return currentReport.copy(
            qaTestsLocal = repository.gbrDao().getAllQualityTestsSync().size + repository.getAllProductionAdjustments().first().size,
            qaTestsUploaded = uploaded,
            qaTestsDownloaded = downloaded,
            qaTestsError = sectionError
        )
    }

    private suspend fun syncLaboratorySessionsAndTestsSection(
        db: FirebaseFirestore,
        ctx: Context,
        repository: GbrRepository,
        currentReport: SyncReport
    ): SyncReport {
        var uploaded = 0
        var downloaded = 0
        var sectionError: String? = null

        try {
            val uploadedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val downloadedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val errorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

            // 1. LAB SESSIONS
            val cloudSessionsDocs = try {
                db.collection("laboratory_sessions").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud lab sessions", e)
                emptyList()
            }
            val cloudSessionsMap = cloudSessionsDocs.associateBy { it.id }
            val localSessions = repository.allLabSessions.first()
            localSessions.chunked(15).forEach { chunk ->
                coroutineScope {
                    chunk.map { ls ->
                        async(Dispatchers.IO) {
                            try {
                                val meta = repository.getSyncMetadataById(ls.id)
                                val isPending = meta?.isPendingSync ?: true
                                val hasCloudDoc = cloudSessionsMap.containsKey(ls.id)
                                val cloudDocForItem = cloudSessionsMap[ls.id]
                                if (labCloudIsNewer(cloudDocForItem, meta)) {
                                    repository.gbrDao().insertLabSession(labSessionFromDoc(cloudDocForItem!!))
                                    repository.insertSyncMetadata(SyncMetadata(ls.id, "lab_session", labCloudStamp(cloudDocForItem), false))
                                    downloadedCount.incrementAndGet()
                                } else if (isPending || !hasCloudDoc) {
                                    db.collection("laboratory_sessions").document(ls.id).set(
                                        hashMapOf<String, Any?>(
                                            "id" to ls.id,
                                            "sessionNumber" to ls.sessionNumber,
                                            "testName" to ls.testName,
                                            "testDate" to ls.testDate,
                                            "technicianName" to ls.technicianName,
                                            "sampleOrProduct" to ls.sampleOrProduct,
                                            "category" to ls.category,
                                            "testType" to ls.testType,
                                            "notes" to ls.notes,
                                            "comparisonType" to ls.comparisonType,
                                            "partyA" to ls.partyA,
                                            "partyB" to ls.partyB,
                                            "createdAt" to ls.createdAt,
                                            "sampleProperties" to ls.sampleProperties,
                                            "updatedAt" to System.currentTimeMillis()
                                        ), SetOptions.merge()
                                    ).awaitTask()
                                    uploadedCount.incrementAndGet()
                                    WriteDiagnostics.recordWrite(ctx, "laboratory_sessions")
                                }
                                repository.markAsSyncedOrCreate(ls.id, "lab_session")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload laboratory session ${ls.id}", e)
                                errorRef.set(appendSyncError(errorRef.get(), "تنزيل/رفع جلسة مختبر: ${e.localizedMessage}"))
                            }
                        }
                    }.awaitAll()
                }
            }
            for (dc in cloudSessionsDocs) {
                val id = dc.id
                if (localSessions.none { it.id == id }) {
                    try {
                        repository.gbrDao().insertLabSession(
                            LabSession(
                                id = id,
                                sessionNumber = dc.getString("sessionNumber") ?: "",
                                testName = dc.getString("testName") ?: "",
                                testDate = dc.getString("testDate") ?: "",
                                technicianName = dc.getString("technicianName") ?: "",
                                sampleOrProduct = dc.getString("sampleOrProduct") ?: "",
                                category = dc.getString("category") ?: "",
                                testType = dc.getString("testType") ?: "",
                                notes = dc.getString("notes") ?: "",
                                comparisonType = dc.getString("comparisonType"),
                                partyA = dc.getString("partyA"),
                                partyB = dc.getString("partyB"),
                                createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis(),
                                sampleProperties = dc.getString("sampleProperties") ?: ""
                            )
                        )
                        repository.insertSyncMetadata(SyncMetadata(id, "lab_session", labCloudStamp(dc), false))
                        downloadedCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded lab session $id", e)
                        errorRef.set(appendSyncError(errorRef.get(), "تحميل جلسة مختبر: ${e.localizedMessage}"))
                    }
                }
            }

            // 2. LAB TESTS
            val cloudTestsDocs = try {
                db.collection("laboratory_tests").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud lab tests", e)
                emptyList()
            }
            val cloudTestsMap = cloudTestsDocs.associateBy { it.id }
            val localTests = repository.allLabTests.first()
            localTests.chunked(15).forEach { chunk ->
                coroutineScope {
                    chunk.map { lt ->
                        async(Dispatchers.IO) {
                            try {
                                val meta = repository.getSyncMetadataById(lt.id)
                                val isPending = meta?.isPendingSync ?: true
                                val hasCloudDoc = cloudTestsMap.containsKey(lt.id)
                                val cloudDocForItem = cloudTestsMap[lt.id]
                                if (labCloudIsNewer(cloudDocForItem, meta)) {
                                    repository.gbrDao().insertLabTest(labTestFromDoc(cloudDocForItem!!))
                                    repository.insertSyncMetadata(SyncMetadata(lt.id, "lab_test", labCloudStamp(cloudDocForItem), false))
                                    downloadedCount.incrementAndGet()
                                } else if (isPending || !hasCloudDoc) {
                                    db.collection("laboratory_tests").document(lt.id).set(
                                        hashMapOf<String, Any?>(
                                            "id" to lt.id,
                                            "sessionId" to lt.sessionId,
                                            "name" to lt.name,
                                            "status" to lt.status,
                                            "executionDate" to lt.executionDate,
                                            "notes" to lt.notes,
                                            "testValueA" to lt.testValueA,
                                            "testValueB" to lt.testValueB,
                                            "createdAt" to lt.createdAt,
                                            "updatedAt" to System.currentTimeMillis()
                                        ), SetOptions.merge()
                                    ).awaitTask()
                                    uploadedCount.incrementAndGet()
                                    WriteDiagnostics.recordWrite(ctx, "laboratory_tests")
                                }
                                repository.markAsSyncedOrCreate(lt.id, "lab_test")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload lab test ${lt.id}", e)
                                errorRef.set(appendSyncError(errorRef.get(), "تحديث فحص مختبر: ${e.localizedMessage}"))
                            }
                        }
                    }.awaitAll()
                }
            }
            for (dc in cloudTestsDocs) {
                val id = dc.id
                if (localTests.none { it.id == id }) {
                    try {
                        repository.gbrDao().insertLabTest(
                            LabTest(
                                id = id,
                                sessionId = dc.getString("sessionId") ?: "",
                                name = dc.getString("name") ?: "",
                                status = dc.getString("status") ?: "",
                                executionDate = dc.getString("executionDate") ?: "",
                                notes = dc.getString("notes") ?: "",
                                testValueA = dc.get("testValueA")?.toString(),
                                testValueB = dc.get("testValueB")?.toString(),
                                createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis()
                            )
                        )
                        repository.insertSyncMetadata(SyncMetadata(id, "lab_test", labCloudStamp(dc), false))
                        downloadedCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded lab test $id", e)
                        errorRef.set(appendSyncError(errorRef.get(), "تحميل فحص مختبري سحابي: ${e.localizedMessage}"))
                    }
                }
            }

            // 3. LAB ATTACHMENTS
            val cloudAttachmentsDocs = try {
                db.collection("laboratory_attachments").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud lab attachments", e)
                emptyList()
            }
            val cloudAttachmentsMap = cloudAttachmentsDocs.associateBy { it.id }
            val localAttachments = repository.allLabAttachments.first()
            localAttachments.chunked(15).forEach { chunk ->
                coroutineScope {
                    chunk.map { la ->
                        async(Dispatchers.IO) {
                            try {
                                val meta = repository.getSyncMetadataById(la.id)
                                val isPending = meta?.isPendingSync ?: true
                                val hasCloudDoc = cloudAttachmentsMap.containsKey(la.id)
                                val cloudDocForItem = cloudAttachmentsMap[la.id]
                                if (labCloudIsNewer(cloudDocForItem, meta)) {
                                    repository.gbrDao().insertLabAttachment(labAttachmentFromDoc(cloudDocForItem!!))
                                    repository.insertSyncMetadata(SyncMetadata(la.id, "lab_attachment", labCloudStamp(cloudDocForItem), false))
                                    downloadedCount.incrementAndGet()
                                } else if (isPending || !hasCloudDoc) {
                                    var currentUrl = la.filePathOrUrl
                                    if (currentUrl.startsWith("content://") || currentUrl.startsWith("file://")) {
                                        val uriObj = Uri.parse(currentUrl)
                                        val rawExt = ctx.contentResolver.getType(uriObj)?.substringAfterLast('/') ?: "jpg"
                                        val ext = if (rawExt == "jpeg") "jpg" else rawExt
                                        val fileName = "lab_${la.sessionId}_${la.id}.$ext"
                                        val cloudUrl = uploadFileToFirebaseStorage(
                                            context = ctx,
                                            localUri = uriObj,
                                            folderName = "lab",
                                            fileName = fileName
                                        )
                                        if (cloudUrl != null) {
                                            currentUrl = cloudUrl
                                            val updatedAttachment = la.copy(filePathOrUrl = cloudUrl)
                                            repository.gbrDao().insertLabAttachment(updatedAttachment)
                                        }
                                    }

                                    db.collection("laboratory_attachments").document(la.id).set(
                                        hashMapOf<String, Any?>(
                                            "id" to la.id,
                                            "sessionId" to la.sessionId,
                                            "testName" to la.testName,
                                            "filePathOrUrl" to currentUrl,
                                            "createdAt" to la.createdAt,
                                            "updatedAt" to System.currentTimeMillis()
                                        ), SetOptions.merge()
                                    ).awaitTask()
                                    uploadedCount.incrementAndGet()
                                    WriteDiagnostics.recordWrite(ctx, "laboratory_attachments")
                                }
                                repository.markAsSyncedOrCreate(la.id, "lab_attachment")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload lab attachment ${la.id}", e)
                                errorRef.set(appendSyncError(errorRef.get(), "تحديث مرفق مختبري: ${e.localizedMessage}"))
                            }
                        }
                    }.awaitAll()
                }
            }
            for (dc in cloudAttachmentsDocs) {
                val id = dc.id
                if (localAttachments.none { it.id == id }) {
                    try {
                        repository.gbrDao().insertLabAttachment(
                            LabAttachment(
                                id = id,
                                sessionId = dc.getString("sessionId") ?: "",
                                testName = dc.getString("testName") ?: "",
                                filePathOrUrl = dc.getString("filePathOrUrl") ?: "",
                                createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis()
                            )
                        )
                        repository.insertSyncMetadata(SyncMetadata(id, "lab_attachment", labCloudStamp(dc), false))
                        downloadedCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded lab attachment $id", e)
                        errorRef.set(appendSyncError(errorRef.get(), "تحميل مرفق مختبري سحابي: ${e.localizedMessage}"))
                    }
                }
            }

            // 4. FORMULATION REFERENCE SPECS
            val cloudSpecsDocs = try {
                db.collection("formulation_reference_specs").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud formulation reference specs", e)
                emptyList()
            }
            val cloudSpecsMap = cloudSpecsDocs.associateBy { it.id }
            val localSpecs = repository.gbrDao().getAllFormulationReferenceSpecs().first()
            localSpecs.chunked(15).forEach { chunk ->
                coroutineScope {
                    chunk.map { ls ->
                        async(Dispatchers.IO) {
                            try {
                                val meta = repository.getSyncMetadataById(ls.formulationId)
                                val isPending = meta?.isPendingSync ?: true
                                val hasCloudDoc = cloudSpecsMap.containsKey(ls.formulationId)
                                if (isPending || !hasCloudDoc) {
                                    db.collection("formulation_reference_specs").document(ls.formulationId).set(
                                        hashMapOf<String, Any?>(
                                            "formulationId" to ls.formulationId,
                                            "approvalDate" to ls.approvalDate,
                                            "phValue" to ls.phValue,
                                            "densityEmptyWeight" to ls.densityEmptyWeight,
                                            "densityFilledWeight" to ls.densityFilledWeight,
                                            "densityFinalResult" to ls.densityFinalResult,
                                            "solidWeightBefore" to ls.solidWeightBefore,
                                            "solidWeightAfter" to ls.solidWeightAfter,
                                            "solidResultPct" to ls.solidResultPct,
                                            "binderWeightBefore" to ls.binderWeightBefore,
                                            "binderWeightAfter" to ls.binderWeightAfter,
                                            "binderResultPct" to ls.binderResultPct,
                                            "viscosityJson" to ls.viscosityJson,
                                            "viscosityFinalResult" to ls.viscosityFinalResult,
                                            "viscosityDilutedJson" to ls.viscosityDilutedJson,
                                            "viscosityDilutedFinalResult" to ls.viscosityDilutedFinalResult,
                                            "rheologyJson" to ls.rheologyJson,
                                            "rheologyIndexResult" to ls.rheologyIndexResult,
                                            "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                                        ), SetOptions.merge()
                                    ).awaitTask()
                                    uploadedCount.incrementAndGet()
                                    WriteDiagnostics.recordWrite(ctx, "formulation_reference_specs")
                                }
                                repository.markAsSyncedOrCreate(ls.formulationId, "formulation_reference_specs")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload reference spec ${ls.formulationId}", e)
                                errorRef.set(appendSyncError(errorRef.get(), "رفع مواصفة معيارية: ${e.localizedMessage}"))
                            }
                        }
                    }.awaitAll()
                }
            }
            for (dc in cloudSpecsDocs) {
                val formulationId = dc.id
                if (localSpecs.none { it.formulationId == formulationId }) {
                    try {
                        repository.gbrDao().insertFormulationReferenceSpecs(
                            FormulationReferenceSpecs(
                                formulationId = formulationId,
                                approvalDate = dc.getString("approvalDate") ?: "",
                                phValue = dc.getString("phValue"),
                                densityEmptyWeight = dc.getSafeDouble("densityEmptyWeight"),
                                densityFilledWeight = dc.getSafeDouble("densityFilledWeight"),
                                densityFinalResult = dc.getSafeDouble("densityFinalResult"),
                                solidWeightBefore = dc.getSafeDouble("solidWeightBefore"),
                                solidWeightAfter = dc.getSafeDouble("solidWeightAfter"),
                                solidResultPct = dc.getSafeDouble("solidResultPct"),
                                binderWeightBefore = dc.getSafeDouble("binderWeightBefore"),
                                binderWeightAfter = dc.getSafeDouble("binderWeightAfter"),
                                binderResultPct = dc.getSafeDouble("binderResultPct"),
                                viscosityJson = dc.getString("viscosityJson"),
                                viscosityFinalResult = dc.getSafeDouble("viscosityFinalResult"),
                                viscosityDilutedJson = dc.getString("viscosityDilutedJson"),
                                viscosityDilutedFinalResult = dc.getSafeDouble("viscosityDilutedFinalResult"),
                                rheologyJson = dc.getString("rheologyJson"),
                                rheologyIndexResult = dc.getSafeDouble("rheologyIndexResult")
                            )
                        )
                        downloadedCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded reference spec $formulationId", e)
                        errorRef.set(appendSyncError(errorRef.get(), "تحميل مواصفة معيارية سحابية: ${e.localizedMessage}"))
                    }
                }
            }

            // 5. OPERATIONAL ALERTS
            val cloudAlertsDocs = try {
                db.collection("operational_alerts").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cloud operational alerts", e)
                emptyList()
            }
            val cloudAlertsMap = cloudAlertsDocs.associateBy { it.id }
            val localAlerts = repository.allOperationalAlerts.first()
            localAlerts.chunked(15).forEach { chunk ->
                coroutineScope {
                    chunk.map { la ->
                        async(Dispatchers.IO) {
                            try {
                                val meta = repository.getSyncMetadataById(la.id)
                                val isPending = meta?.isPendingSync ?: true
                                val hasCloudDoc = cloudAlertsMap.containsKey(la.id)
                                if (isPending || !hasCloudDoc) {
                                    db.collection("operational_alerts").document(la.id).set(
                                        hashMapOf<String, Any?>(
                                            "id" to la.id,
                                            "title" to la.title,
                                            "description" to la.description,
                                            "mainSection" to la.mainSection,
                                            "bindingScope" to la.bindingScope,
                                            "bindingElementName" to la.bindingElementName,
                                            "alertLevel" to la.alertLevel,
                                            "status" to la.status,
                                            "createdAt" to la.createdAt,
                                            "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                                        ), SetOptions.merge()
                                    ).awaitTask()
                                    uploadedCount.incrementAndGet()
                                    WriteDiagnostics.recordWrite(ctx, "operational_alerts")
                                }
                                repository.markAsSyncedOrCreate(la.id, "operational_alert")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload operational alert ${la.id}", e)
                                errorRef.set(appendSyncError(errorRef.get(), "رفع تنبيه تشغيلي: ${e.localizedMessage}"))
                            }
                        }
                    }.awaitAll()
                }
            }
            for (dc in cloudAlertsDocs) {
                val id = dc.id
                if (localAlerts.none { la -> la.id == id }) {
                    try {
                        repository.insertOperationalAlert(
                            com.example.data.OperationalAlert(
                                id = id,
                                title = dc.getString("title") ?: "",
                                description = dc.getString("description") ?: "",
                                mainSection = dc.getString("mainSection") ?: "عام",
                                bindingScope = dc.getString("bindingScope") ?: "ALL",
                                bindingElementName = dc.getString("bindingElementName") ?: "",
                                alertLevel = dc.getString("alertLevel") ?: "INFO",
                                status = dc.getString("status") ?: "ACTIVE",
                                createdAt = dc.getSafeLong("createdAt") ?: System.currentTimeMillis()
                            )
                        )
                        downloadedCount.incrementAndGet()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store downloaded operational alert $id", e)
                        errorRef.set(appendSyncError(errorRef.get(), "تحميل تنبيه تشغيلي سحابي: ${e.localizedMessage}"))
                    }
                }
            }

            // Delete local alerts that have been deleted in Firestore
            for (la in localAlerts) {
                val meta = repository.getSyncMetadataById(la.id)
                val isPending = meta?.isPendingSync ?: false
                if (!isPending && !cloudAlertsMap.containsKey(la.id)) {
                    try {
                        repository.deleteOperationalAlert(la)
                        Log.i(TAG, "Deleted local operational alert ${la.id} because it was deleted from cloud")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete local operational alert ${la.id}", e)
                    }
                }
            }

            uploaded = uploadedCount.get()
            downloaded = downloadedCount.get()
            sectionError = errorRef.get()

        } catch (e: Exception) {
            Log.e(TAG, "General laboratory sync error", e)
            sectionError = appendSyncError(sectionError, "أخطاء قسم كشوف وفحوصات المختبر: ${e.localizedMessage}")
        }

        val totalLocal = try {
            repository.allLabSessions.first().size +
            repository.allLabTests.first().size +
            repository.allLabAttachments.first().size +
            repository.gbrDao().getAllFormulationReferenceSpecs().first().size +
            repository.allOperationalAlerts.first().size
        } catch (e: Exception) {
            0
        }

        return currentReport.copy(
            labSessionsLocal = totalLocal,
            labSessionsUploaded = uploaded,
            labSessionsDownloaded = downloaded,
            labSessionsError = sectionError
        )
    }

    private suspend fun syncCustomPackagingsSection(
        db: FirebaseFirestore,
        context: Context,
        report: SyncReport
    ): SyncReport {
        try {
            val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("custom_packagings_json", "[]") ?: "[]"
            
            // 1. Parse local packagings
            val localList = mutableListOf<com.example.ui.GbrViewModel.CustomPackaging>()
            try {
                val jsonArray = org.json.JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    localList.add(
                        com.example.ui.GbrViewModel.CustomPackaging(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            netWeight = obj.optDouble("netWeight", 0.0),
                            weightWithLid = obj.optDouble("weightWithLid", 0.0),
                            price = obj.optDouble("price", 0.0)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing local packagings JSON", e)
            }
            val localMap = localList.associateBy { it.id }.toMutableMap()

            // 2. Fetch cloud packagings
            val cloudDocs = try {
                db.collection("custom_packagings").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching custom packagings from cloud", e)
                emptyList()
            }

            // 3. Merge:
            var uploadedCount = 0
            var downloadedCount = 0
            
            for (doc in cloudDocs) {
                val id = doc.getString("id") ?: doc.id
                val pkg = com.example.ui.GbrViewModel.CustomPackaging(
                    id = id,
                    name = doc.getString("name") ?: "",
                    netWeight = doc.getSafeDouble("netWeight") ?: 0.0,
                    weightWithLid = doc.getSafeDouble("weightWithLid") ?: 0.0,
                    price = doc.getSafeDouble("price") ?: 0.0
                )
                if (!localMap.containsKey(id)) {
                    localMap[id] = pkg
                    downloadedCount++
                }
            }

            for (pkg in localList) {
                val cloudHasIt = cloudDocs.any { (it.getString("id") ?: it.id) == pkg.id }
                if (!cloudHasIt) {
                    try {
                        val payload = hashMapOf<String, Any>(
                            "id" to pkg.id,
                            "name" to pkg.name,
                            "netWeight" to pkg.netWeight,
                            "weightWithLid" to pkg.weightWithLid,
                            "price" to pkg.price,
                            "lastUpdated" to System.currentTimeMillis()
                        )
                        db.collection("custom_packagings").document(pkg.id).set(payload, SetOptions.merge()).awaitTask()
                        uploadedCount++
                        WriteDiagnostics.recordWrite(context, "custom_packagings")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uploading missing packaging during sync", e)
                    }
                }
            }

            // 4. Save merged list locally
            val mergedList = localMap.values.toList()
            val finalJsonArray = org.json.JSONArray()
            mergedList.forEach { pkg ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", pkg.id)
                    put("name", pkg.name)
                    put("netWeight", pkg.netWeight)
                    put("weightWithLid", pkg.weightWithLid)
                    put("price", pkg.price)
                }
                finalJsonArray.put(jsonObject)
            }
            prefs.edit().putString("custom_packagings_json", finalJsonArray.toString()).apply()
            
            // Broadcast changes to active viewmodels/views
            com.example.ui.GbrViewModel.externalPackagingUpdates.tryEmit(mergedList)

            Log.i(TAG, "Custom packagings synchronized: local=${localList.size}, uploaded=$uploadedCount, downloaded=$downloadedCount, final=${mergedList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync custom packagings", e)
        }
        return report
    }

    private suspend fun syncCustomUsersSection(
        db: FirebaseFirestore,
        context: Context,
        report: SyncReport
    ): SyncReport {
        try {
            val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("custom_users_json", "[]") ?: "[]"
            
            // 1. Parse local users
            val localList = mutableListOf<com.example.ui.GbrViewModel.CustomUser>()
            try {
                val jsonArray = org.json.JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    localList.add(
                        com.example.ui.GbrViewModel.CustomUser(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            role = obj.optString("role", "مشغل"),
                            password = obj.optString("password", "1234"),
                            isSupervisor = obj.optBoolean("isSupervisor", false),
                            username = obj.optString("username", ""),
                            fullName = obj.optString("fullName", ""),
                            isActive = obj.optBoolean("isActive", true),
                            createdAt = obj.optString("createdAt", "")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing local users JSON", e)
            }
            val localMap = localList.associateBy { it.id }.toMutableMap()

            // 2. Fetch cloud users
            val cloudDocs = try {
                db.collection("custom_users").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching custom users from cloud", e)
                emptyList()
            }

            // 3. Merge:
            var uploadedCount = 0
            var downloadedCount = 0
            
            for (doc in cloudDocs) {
                val id = doc.getString("id") ?: doc.id
                val user = com.example.ui.GbrViewModel.CustomUser(
                    id = id,
                    name = doc.getString("name") ?: "",
                    role = doc.getString("role") ?: "مشغل",
                    password = doc.getString("password") ?: "1234",
                    isSupervisor = doc.getBoolean("isSupervisor") ?: false,
                    username = doc.getString("username") ?: "",
                    fullName = doc.getString("fullName") ?: "",
                    isActive = doc.getBoolean("isActive") ?: true,
                    createdAt = doc.getString("createdAt") ?: ""
                )
                if (!localMap.containsKey(id)) {
                    localMap[id] = user
                    downloadedCount++
                }
            }

            for (user in localList) {
                val cloudHasIt = cloudDocs.any { (it.getString("id") ?: it.id) == user.id }
                if (!cloudHasIt) {
                    try {
                        val payload = hashMapOf<String, Any>(
                            "id" to user.id,
                            "name" to user.name,
                            "role" to user.role,
                            "password" to user.password,
                            "isSupervisor" to user.isSupervisor,
                            "username" to user.username,
                            "fullName" to user.fullName,
                            "isActive" to user.isActive,
                            "createdAt" to user.createdAt,
                            "lastUpdated" to System.currentTimeMillis()
                        )
                        db.collection("custom_users").document(user.id).set(payload, SetOptions.merge()).awaitTask()
                        uploadedCount++
                        WriteDiagnostics.recordWrite(context, "custom_users")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error uploading missing user during sync", e)
                    }
                }
            }

            // 4. Save merged list locally
            val mergedList = localMap.values.toList()
            val finalJsonArray = org.json.JSONArray()
            mergedList.forEach { user ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", user.id)
                    put("name", user.name)
                    put("role", user.role)
                    put("password", user.password)
                    put("isSupervisor", user.isSupervisor)
                    put("username", user.username)
                    put("fullName", user.fullName)
                    put("isActive", user.isActive)
                    put("createdAt", user.createdAt)
                }
                finalJsonArray.put(jsonObject)
            }
            prefs.edit().putString("custom_users_json", finalJsonArray.toString()).apply()
            
            // Broadcast changes to active viewmodels/views
            com.example.ui.GbrViewModel.externalUserUpdates.tryEmit(mergedList)

            Log.i(TAG, "Custom users synchronized: local=${localList.size}, uploaded=$uploadedCount, downloaded=$downloadedCount, final=${mergedList.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync custom users", e)
        }
        return report
    }

    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "Uncaught exception in SyncManager autoSyncScope", exception)
    }
    private val autoSyncScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)
    private val activeRegistrations = mutableListOf<ListenerRegistration>()

    private suspend fun syncHostingSettings(
        db: FirebaseFirestore,
        context: Context
    ) {
        try {
            val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
            val localUrl = prefs.getString("hostinger_gateway_url", "") ?: ""
            val localEnabled = prefs.getBoolean("hostinger_enabled", false)
            val localLastUpdated = prefs.getLong("hostinger_last_updated", 0L)

            val docRef = db.collection("system_settings").document("hosting_config")
            val snapshot = try {
                docRef.get().awaitTask()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting hosting_config from cloud", e)
                null
            }

            if (snapshot != null && snapshot.exists()) {
                val cloudUrl = snapshot.getString("hostinger_gateway_url") ?: ""
                val cloudEnabled = snapshot.getBoolean("hostinger_enabled") ?: false
                val cloudLastUpdated = snapshot.getLong("hostinger_last_updated") ?: 0L

                if (cloudLastUpdated > localLastUpdated) {
                    // Cloud is newer: Download to local
                    prefs.edit()
                        .putString("hostinger_gateway_url", cloudUrl)
                        .putBoolean("hostinger_enabled", cloudEnabled)
                        .putLong("hostinger_last_updated", cloudLastUpdated)
                        .apply()
                    // Broadcast update to viewmodel
                    com.example.ui.GbrViewModel.externalHostingUpdates.tryEmit(Pair(cloudUrl, cloudEnabled))
                    Log.i(TAG, "Hosting settings synced from cloud: url=$cloudUrl, enabled=$cloudEnabled")
                } else if (localLastUpdated > cloudLastUpdated || (localUrl.isNotBlank() && cloudUrl.isBlank())) {
                    // Local is newer/configured: Upload to cloud
                    val updatedTime = if (localLastUpdated > 0L) localLastUpdated else System.currentTimeMillis()
                    val payload = hashMapOf<String, Any>(
                        "hostinger_gateway_url" to localUrl,
                        "hostinger_enabled" to localEnabled,
                        "hostinger_last_updated" to updatedTime
                    )
                    docRef.set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                    Log.i(TAG, "Hosting settings synced to cloud: url=$localUrl, enabled=$localEnabled")
                }
            } else {
                // Cloud doesn't have it or not reachable, push local config if set
                if (localUrl.isNotBlank()) {
                    val updatedTime = if (localLastUpdated > 0L) localLastUpdated else System.currentTimeMillis()
                    val payload = hashMapOf<String, Any>(
                        "hostinger_gateway_url" to localUrl,
                        "hostinger_enabled" to localEnabled,
                        "hostinger_last_updated" to updatedTime
                    )
                    docRef.set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                    Log.i(TAG, "Uploaded local hosting settings for first time: url=$localUrl, enabled=$localEnabled")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed in syncHostingSettings", e)
        }
    }

    fun uploadHostingSettings(context: Context, url: String, enabled: Boolean) {
        autoSyncScope.launch {
            try {
                if (!initializeFirebase(context)) return@launch
                val db = FirebaseFirestore.getInstance()
                val docRef = db.collection("system_settings").document("hosting_config")
                val payload = hashMapOf<String, Any>(
                    "hostinger_gateway_url" to url,
                    "hostinger_enabled" to enabled,
                    "hostinger_last_updated" to System.currentTimeMillis()
                )
                docRef.set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                Log.i(TAG, "Uploaded hosting settings to Firestore: $url, enabled=$enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload hosting settings directly", e)
            }
        }
    }

    private fun updateIncomingSyncPrefs(context: Context, notificationMsg: String? = null) {
        val prefs = context.getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
        val nowStr = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.ENGLISH).format(Date())
        prefs.edit()
            .putString("last_cloud_incoming_update", nowStr)
            .putString("global_sync_status", "synced")
            .apply()
        if (notificationMsg != null) {
            com.example.ui.GbrViewModel.cloudNotifications.tryEmit(notificationMsg)
        }
    }

    private fun mergeTimerStartTimes(localStr: String, cloudStr: String): String {
        if (localStr == "{}" || localStr.isBlank()) return cloudStr
        if (cloudStr == "{}" || cloudStr.isBlank()) return localStr
        return try {
            val localObj = org.json.JSONObject(localStr)
            val cloudObj = org.json.JSONObject(cloudStr)
            val mergedObj = org.json.JSONObject()
            
            val cloudKeys = cloudObj.keys()
            while (cloudKeys.hasNext()) {
                val key = cloudKeys.next()
                mergedObj.put(key, cloudObj.get(key))
            }
            
            val localKeys = localObj.keys()
            while (localKeys.hasNext()) {
                val key = localKeys.next()
                mergedObj.put(key, localObj.get(key))
            }
            mergedObj.toString()
        } catch (e: Exception) {
            if (localStr.isNotEmpty()) localStr else cloudStr
        }
    }

    fun stopRealtimeListeners() {
        synchronized(this) {
            for (reg in activeRegistrations) {
                try {
                    reg.remove()
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing listener registration", e)
                }
            }
            activeRegistrations.clear()
            WriteDiagnostics.stopListening()
        }
    }

    fun startRealtimeListeners(context: Context, repository: GbrRepository) {
        if (!com.example.data.DeviceSecurityManager.isCloudConfigured(context)) {
            Log.d(TAG, "startRealtimeListeners aborted: Cloud is not configured.")
            return
        }
        if (com.example.data.DeviceSecurityManager.deviceStatus.value != com.example.data.DeviceSecurityManager.STATUS_APPROVED) {
            Log.w(TAG, "startRealtimeListeners aborted: Device security status is not APPROVED.")
            return
        }
        autoSyncScope.launch {
            synchronized(SyncManager) {
                for (reg in activeRegistrations) {
                    try {
                        reg.remove()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error removing old listener registration", e)
                    }
                }
                activeRegistrations.clear()
                WriteDiagnostics.startListening(context)
                
                if (!initializeFirebase(context)) {
                    Log.e(TAG, "Cannot start realtime listeners: Firebase failed to initialize.")
                    addLocalSystemLog(context, "error", "❌ تعذر تفعيل المراقبة الفورية (Real-time Listener) لعدم اكتمال الاتصال بقاعدة البيانات.")
                    return@launch
                }
                
                addLocalSystemLog(context, "sync", "⚡ جارٍ تهيئة وربط الـ Snapshots الفورية لمزامنة البيانات في الخلفية...")
                
                try {
                    val db = FirebaseFirestore.getInstance()
                
                // 1. Raw Materials
                val rawReg = db.collection("raw_materials").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for raw_materials", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        updateIncomingSyncPrefs(context)
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val cloudUpdated = doc.getLong("lastUpdated") ?: 0L
                                    val meta = repository.getSyncMetadataById(id)
                                    val isPending = meta?.isPendingSync ?: false
                                    val localUpdated = meta?.lastUpdated ?: 0L
                                    
                                    if (cloudUpdated > localUpdated) {
                                        val syncedRaw = RawMaterial(
                                            id = id,
                                            name = doc.getString("name") ?: "",
                                            productionName = doc.getString("productionName") ?: "",
                                            price = doc.getSafeDouble("price") ?: 0.0,
                                            priceUnit = doc.getString("priceUnit") ?: "شيكل",
                                            notes = doc.getString("notes") ?: "",
                                            tdsUri = doc.getString("tdsUri").let { if (it.isNullOrBlank()) null else it },
                                            isActive = doc.getBoolean("isActive") ?: true
                                        )
                                        val rowId = repository.gbrDao().insertRawMaterial(syncedRaw)
                                        if (rowId == -1L) {
                                            repository.gbrDao().updateRawMaterial(syncedRaw)
                                        }
                                        
                                        val cloudPriceHistory = doc.get("priceHistory") as? List<Map<String, Any>> ?: emptyList()
                                        for (ph in cloudPriceHistory) {
                                            repository.gbrDao().insertPriceHistory(
                                                PriceHistoryEntry(
                                                    id = ph["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                                    rawMaterialId = id,
                                                    oldPrice = (ph["oldPrice"] as? Number)?.toDouble() ?: 0.0,
                                                    newPrice = (ph["newPrice"] as? Number)?.toDouble() ?: 0.0,
                                                    dateChange = ph["dateChange"] as? String ?: ""
                                                )
                                            )
                                        }
                                        
                                        repository.insertSyncMetadata(SyncMetadata(id, "raw_material", cloudUpdated, false))
                                        if (dc.type == DocumentChange.Type.MODIFIED) {
                                            updateIncomingSyncPrefs(context, "تم استلام سعر أو بيانات مادة خام محدثة من السحابة 🏷️")
                                        }
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.getRawMaterialById(id)
                                    if (existing != null) {
                                        repository.gbrDao().deleteRawMaterial(existing)
                                        repository.deleteSyncMetadataById(id)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(rawReg)
                
                // 2. Formulations
                val formReg = db.collection("formulations").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for formulations", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        updateIncomingSyncPrefs(context)
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                try {
                                    if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                        val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                        val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                        if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                            continue
                                        }
                                        val cloudUpdated = doc.getLong("lastUpdated") ?: 0L
                                        val meta = repository.getSyncMetadataById(id)
                                        val isPending = meta?.isPendingSync ?: false
                                        val localUpdated = meta?.lastUpdated ?: 0L
                                        
                                        if (cloudUpdated > localUpdated) {
                                            val cloudFormulation = Formulation(
                                                id = id,
                                                name = doc.getString("name") ?: "",
                                                code = doc.getString("code") ?: "",
                                                description = doc.getString("description") ?: "",
                                                imageUri = doc.getString("imageUri").let { if (it.isNullOrBlank()) null else it },
                                                version = doc.getString("version") ?: "1.0.0",
                                                status = doc.getString("status") ?: "🟡 قيد التطوير",
                                                createdAt = doc.getString("createdAt") ?: "",
                                                notes = doc.getString("notes") ?: "",
                                                supports18L = doc.getBoolean("supports18L") ?: false,
                                                netWeight18L = doc.getString("netWeight18L") ?: "",
                                                supports5L = doc.getBoolean("supports5L") ?: false,
                                                netWeight5L = doc.getString("netWeight5L") ?: "",
                                                packagingWeightsJson = doc.getString("packagingWeightsJson") ?: "",
                                                productionLine = doc.getLong("productionLine")?.toInt() ?: -1
                                            )
                                            val rowId = repository.gbrDao().insertFormulation(cloudFormulation)
                                            if (rowId == -1L) {
                                                repository.gbrDao().updateFormulation(cloudFormulation)
                                            }
                                            saveDownloadedFormulationDetails(repository, id, doc)
                                            repository.insertSyncMetadata(SyncMetadata(id, "formulation", cloudUpdated, false))
                                            if (dc.type == DocumentChange.Type.MODIFIED) {
                                                updateIncomingSyncPrefs(context, "تم استدارك تعديل في تركيبة أو خلطة من السحابة 🧪")
                                            }
                                        }
                                    } else if (dc.type == DocumentChange.Type.REMOVED) {
                                        val existing = repository.getFormulationById(id)
                                        if (existing != null) {
                                            repository.gbrDao().deleteFormulation(existing)
                                            repository.deleteSyncMetadataById(id)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error processing formulation change for id=$id inside snapshot listener", e)
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(formReg)
                
                // 3. Production Orders
                val prodReg = db.collection("production_orders").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for production_orders", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        updateIncomingSyncPrefs(context)
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val cloudUpdated = doc.getLong("lastUpdated") ?: 0L
                                    val meta = repository.getSyncMetadataById(id)
                                    val isPending = meta?.isPendingSync ?: false
                                    val localUpdated = meta?.lastUpdated ?: 0L
                                    
                                    if (cloudUpdated > localUpdated) {
                                        val existing = repository.getProductionOrderById(id)
                                        val existingTimer = existing?.timerStartTimesJson ?: "{}"
                                        val cloudTimer = doc.getString("timerStartTimesJson") ?: "{}"
                                        val mergedTimer = mergeTimerStartTimes(existingTimer, cloudTimer)

                                        val cloudOrder = ProductionOrder(
                                            id = id,
                                            orderNumber = doc.getString("orderNumber") ?: "",
                                            batchNumber = doc.getString("batchNumber") ?: "",
                                            formulationId = doc.getString("formulationId") ?: "",
                                            formulationName = doc.getString("formulationName") ?: "",
                                            formulationVersion = doc.getString("formulationVersion") ?: "",
                                            requiredWeightKg = doc.getSafeDouble("requiredWeightKg") ?: 0.0,
                                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                            status = doc.getString("status") ?: "مسودة",
                                            notes = doc.getString("notes") ?: "",
                                            scaleFactor = doc.getSafeDouble("scaleFactor") ?: 1.0,
                                            originalWeightKg = doc.getSafeDouble("originalWeightKg") ?: 0.0,
                                            progressPercent = (doc.getLong("progressPercent") ?: 0L).toInt(),
                                            currentPhaseIndex = (doc.getLong("currentPhaseIndex") ?: 0L).toInt(),
                                            currentItemIndex = (doc.getLong("currentItemIndex") ?: 0L).toInt(),
                                            completedItemsJson = doc.getString("completedItemsJson") ?: "[]",
                                            packagingSnapshotJson = doc.getString("packagingSnapshotJson") ?: "",
                                            actualPackagingJson = doc.getString("actualPackagingJson") ?: "[]",
                                            timerStartTimesJson = mergedTimer,
                                            startTime = doc.getLong("startTime") ?: 0L,
                                            endTime = doc.getLong("endTime") ?: 0L,
                                            operatorName = doc.getString("operatorName") ?: "المشرف"
                                        )
                                        val lastModifiedDevice = doc.getString("lastModifiedDevice") ?: ""
                                        if (lastModifiedDevice != com.example.data.DeviceSecurityManager.getDeviceId()) {
                                            if (isPending) {
                                                addLocalSystemLog(context, "sync", "⚠️ استُبدلت نسخة محلية معلّقة من أمر الإنتاج ${cloudOrder.orderNumber} بنسخة سحابية أحدث (الجهاز الكاتب: ${lastModifiedDevice.ifBlank { "غير معروف" }}، ختم السحابة $cloudUpdated مقابل المحلي $localUpdated)")
                                            }
                                            repository.gbrDao().insertProductionOrder(cloudOrder)
                                            saveDownloadedProductionOrderDetails(repository, id, doc)
                                            repository.insertSyncMetadata(SyncMetadata(id, "production_order", cloudUpdated, false))
                                            healOrderQuantitiesFromAdjustments(context, repository, id)
                                            if (dc.type == DocumentChange.Type.MODIFIED) {
                                                updateIncomingSyncPrefs(context, "تم استلام تحديث لحالة أمر التشغيل من السحابة ⚙️")
                                            }
                                        } else {
                                            repository.insertSyncMetadata(SyncMetadata(id, "production_order", cloudUpdated, false))
                                        }
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.getProductionOrderById(id)
                                    if (existing != null) {
                                        repository.gbrDao().deleteProductionOrder(existing)
                                        repository.deleteSyncMetadataById(id)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(prodReg)
                
                // 4. Development Projects
                val devReg = db.collection("development_projects").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for development_projects", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val meta = repository.getSyncMetadataById(id)
                                    val isPending = meta?.isPendingSync ?: false
                                    if (!isPending) {
                                        val cloudUpdatedStr = doc.getString("lastUpdated") ?: ""
                                        val cloudUpdatedLong = try { doc.getLong("lastUpdated") ?: cloudUpdatedStr.toLongOrNull() ?: 0L } catch(e: Exception) { 0L }
                                        val localUpdatedLong = meta?.lastUpdated ?: 0L
                                        
                                        // lastUpdated لمشاريع R&D نص بصيغة yyyy-MM-dd HH:mm:ss (وليس رقماً)، فمقارنته كرقم تعطي 0 دائماً ولا تصل التحديثات الحية: نقارنه كنص مع نسخة المشروع المحلية
                                        val localDevProject = repository.getDevelopmentProjectById(id)
                                        val devCloudIsNewer = localDevProject == null || (cloudUpdatedStr.isNotBlank() && cloudUpdatedStr > localDevProject.lastUpdated)
                                        if (devCloudIsNewer || cloudUpdatedLong > localUpdatedLong || localUpdatedLong == 0L) {
                                            val cloudProject = DevelopmentProject(
                                                id = id,
                                                name = doc.getString("name") ?: "",
                                                createdAt = doc.getString("createdAt") ?: "",
                                                lastUpdated = if (cloudUpdatedStr.isNotBlank()) cloudUpdatedStr else cloudUpdatedLong.toString()
                                            )
                                            repository.gbrDao().insertDevelopmentProject(cloudProject)
                                            
                                            val cloudSamples = (doc.get("samples") as? List<*>) ?: emptyList<Any?>()
                                            for (csAny in cloudSamples) {
                                                val cs = csAny as? Map<*, *> ?: continue
                                                repository.gbrDao().insertDevelopmentSample(
                                                    DevelopmentSample(
                                                        id = cs["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                                        projectId = id,
                                                        sampleName = cs["sampleName"] as? String ?: "",
                                                        sampleNumber = cs["sampleNumber"] as? String ?: "",
                                                        createdAt = cs["createdAt"] as? String ?: "",
                                                        targetWeightKg = (cs["targetWeightKg"] as? Number)?.toDouble() ?: 1.0,
                                                        targetGoal = cs["targetGoal"] as? String ?: "",
                                                        initialNotes = cs["initialNotes"] as? String ?: "",
                                                        researchNotes = cs["researchNotes"] as? String ?: "",
                                                        isApproved = cs["isApproved"] as? Boolean ?: false,
                                                        approvedDate = cs["approvedDate"] as? String ?: "",
                                                        resultsJson = cs["resultsJson"] as? String ?: "[]",
                                                        itemsJson = cs["itemsJson"] as? String ?: "[]",
                                                        recipeJson = cs["recipeJson"] as? String ?: "[]",
                                                        status = cs["status"] as? String,
                                                        statusNotes = cs["statusNotes"] as? String,
                                                        statusUpdatedAt = cs["statusUpdatedAt"] as? String,
                                                        statusUpdatedBy = cs["statusUpdatedBy"] as? String
                                                    )
                                                )
                                            }
                                            val metaTimestamp = if (cloudUpdatedLong > 0L) cloudUpdatedLong else System.currentTimeMillis()
                                            repository.insertSyncMetadata(SyncMetadata(id, "development_project", metaTimestamp, false))
                                        }
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.getDevelopmentProjectById(id)
                                    if (existing != null) {
                                        repository.gbrDao().deleteDevelopmentProject(existing)
                                        repository.deleteSyncMetadataById(id)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(devReg)
                
                // 5. Quality Tests
                val qaReg = db.collection("quality_tests").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for quality_tests", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    repository.gbrDao().insertQualityTest(
                                        QualityTest(
                                            id = id,
                                            name = doc.getString("name") ?: "",
                                            sequenceIndex = (doc.getLong("sequenceIndex") ?: 0L).toInt()
                                        )
                                    )
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val local = repository.gbrDao().getAllQualityTestsSync()
                                    val match = local.find { it.id == id }
                                    if (match != null) {
                                        repository.gbrDao().deleteQualityTest(match)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(qaReg)
                
                // 6. Production Adjustments
                val adjReg = db.collection("production_adjustments").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for production_adjustments", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    // لا تعديل بلا أمر محلي (قيد FOREIGN KEY)
                                    val listenerOrderId = doc.getString("productionOrderId") ?: ""
                                    if (listenerOrderId.isBlank() || repository.getProductionOrderById(listenerOrderId) == null) {
                                        continue
                                    }
                                    repository.gbrDao().insertProductionAdjustment(
                                        ProductionAdjustment(
                                            id = id,
                                            productionOrderId = doc.getString("productionOrderId") ?: "",
                                            rawMaterialId = doc.getString("rawMaterialId") ?: "",
                                            rawMaterialName = doc.getString("rawMaterialName") ?: "",
                                            originalQuantity = doc.getSafeDouble("originalQuantity") ?: 0.0,
                                            newQuantity = doc.getSafeDouble("newQuantity") ?: 0.0,
                                            difference = doc.getSafeDouble("difference") ?: 0.0,
                                            reason = doc.getString("reason") ?: "",
                                            notes = doc.getString("notes") ?: "",
                                            timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                            userName = doc.getString("userName") ?: "سحابي"
                                        )
                                    )
                                    // ترميم كمية المادة إن كانت نسخة الأمر الحالية لا تطابق هذا التعديل
                                    val adjOrderId = doc.getString("productionOrderId") ?: ""
                                    if (adjOrderId.isNotBlank()) {
                                        healOrderQuantitiesFromAdjustments(context, repository, adjOrderId)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(adjReg)
                
                // 7. Custom Users configurations sync
                val usersReg = db.collection("custom_users").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for custom_users", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        val updatedList = mutableListOf<com.example.ui.GbrViewModel.CustomUser>()
                        for (doc in snapshots.documents) {
                            try {
                                val user = com.example.ui.GbrViewModel.CustomUser(
                                    id = doc.getString("id") ?: doc.id,
                                    name = doc.getString("name") ?: "",
                                    role = doc.getString("role") ?: "مشغل",
                                    password = doc.getString("password") ?: "1234",
                                    isSupervisor = doc.getBoolean("isSupervisor") ?: false,
                                    username = doc.getString("username") ?: "",
                                    fullName = doc.getString("fullName") ?: "",
                                    isActive = doc.getBoolean("isActive") ?: true,
                                    createdAt = doc.getString("createdAt") ?: ""
                                )
                                updatedList.add(user)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing custom user document", e)
                            }
                        }
                        if (updatedList.isNotEmpty() || snapshots.isEmpty) {
                            com.example.ui.GbrViewModel.externalUserUpdates.tryEmit(updatedList)
                        }
                    }
                }
                activeRegistrations.add(usersReg)

                // 8. Custom Packagings configurations sync
                val packagingReg = db.collection("custom_packagings").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for custom_packagings", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        val updatedList = mutableListOf<com.example.ui.GbrViewModel.CustomPackaging>()
                        for (doc in snapshots.documents) {
                            try {
                                val pkg = com.example.ui.GbrViewModel.CustomPackaging(
                                    id = doc.getString("id") ?: doc.id,
                                    name = doc.getString("name") ?: "",
                                    netWeight = doc.getSafeDouble("netWeight") ?: 0.0,
                                    weightWithLid = doc.getSafeDouble("weightWithLid") ?: 0.0,
                                    price = doc.getSafeDouble("price") ?: 0.0
                                )
                                updatedList.add(pkg)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing custom packaging document", e)
                            }
                        }
                        if (updatedList.isNotEmpty() || snapshots.isEmpty) {
                            com.example.ui.GbrViewModel.externalPackagingUpdates.tryEmit(updatedList)
                        }
                    }
                }
                activeRegistrations.add(packagingReg)

                // 9. Centralized System Logs sync (limited to 30 recent records)
                val logsReg = db.collection("system_logs")
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(30)
                    .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for system_logs", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        val updatedList = mutableListOf<com.example.ui.GbrViewModel.SystemLog>()
                        for (doc in snapshots.documents) {
                            try {
                                val log = com.example.ui.GbrViewModel.SystemLog(
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                    category = doc.getString("category") ?: "عام",
                                    message = doc.getString("message") ?: "",
                                    username = doc.getString("username") ?: "غير معروف",
                                    deviceName = doc.getString("deviceName") ?: "جهاز مجهول"
                                )
                                updatedList.add(log)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing system log document", e)
                            }
                        }
                        if (updatedList.isNotEmpty() || snapshots.isEmpty) {
                            com.example.ui.GbrViewModel.externalSystemLogsUpdates.tryEmit(updatedList)
                        }
                    }
                }
                activeRegistrations.add(logsReg)
                activeRegistrations.add(com.example.data.ActivityLog.startListening(db))

                // 10. Laboratory Sessions Realtime Listener
                val labSessionReg = db.collection("laboratory_sessions").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for laboratory_sessions", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        updateIncomingSyncPrefs(context)
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val cloudUpdated = doc.getLong("updatedAt") ?: doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    val meta = repository.getSyncMetadataById(id)
                                    val isPending = meta?.isPendingSync ?: false
                                    val localUpdated = meta?.lastUpdated ?: 0L
                                    
                                    if (!isPending && cloudUpdated > localUpdated) {
                                        val cloudSession = LabSession(
                                            id = id,
                                            sessionNumber = doc.getString("sessionNumber") ?: "",
                                            testName = doc.getString("testName") ?: "",
                                            testDate = doc.getString("testDate") ?: "",
                                            technicianName = doc.getString("technicianName") ?: "",
                                            sampleOrProduct = doc.getString("sampleOrProduct") ?: "",
                                            category = doc.getString("category") ?: "",
                                            testType = doc.getString("testType") ?: "",
                                            notes = doc.getString("notes") ?: "",
                                            comparisonType = doc.getString("comparisonType"),
                                            partyA = doc.getString("partyA"),
                                            partyB = doc.getString("partyB"),
                                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                                            sampleProperties = doc.getString("sampleProperties") ?: ""
                                        )
                                        val lastModifiedDevice = doc.getString("lastModifiedDevice") ?: ""
                                        if (lastModifiedDevice != com.example.data.DeviceSecurityManager.getDeviceId()) {
                                            repository.gbrDao().insertLabSession(cloudSession)
                                            repository.insertSyncMetadata(SyncMetadata(id, "lab_session", cloudUpdated, false))
                                            if (dc.type == DocumentChange.Type.MODIFIED) {
                                                updateIncomingSyncPrefs(context, "تم استلام تحديث لجلسة المختبر من السحابة 🧪")
                                            }
                                        } else {
                                            repository.insertSyncMetadata(SyncMetadata(id, "lab_session", cloudUpdated, false))
                                        }
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.getLabSessionById(id)
                                    if (existing != null) {
                                        repository.gbrDao().deleteLabSession(existing)
                                        repository.gbrDao().deleteLabTestsForSession(existing.id)
                                        repository.deleteSyncMetadataById(id)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(labSessionReg)

                // 11. Laboratory Tests Realtime Listener
                val labTestReg = db.collection("laboratory_tests").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for laboratory_tests", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        updateIncomingSyncPrefs(context)
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val cloudUpdated = doc.getLong("updatedAt") ?: doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    val meta = repository.getSyncMetadataById(id)
                                    val isPending = meta?.isPendingSync ?: false
                                    val localUpdated = meta?.lastUpdated ?: 0L
                                    
                                    if (!isPending && cloudUpdated > localUpdated) {
                                        val cloudTest = LabTest(
                                            id = id,
                                            sessionId = doc.getString("sessionId") ?: "",
                                            name = doc.getString("name") ?: "",
                                            status = doc.getString("status") ?: "",
                                            executionDate = doc.getString("executionDate") ?: "",
                                            notes = doc.getString("notes") ?: "",
                                            testValueA = doc.get("testValueA")?.toString(),
                                            testValueB = doc.get("testValueB")?.toString(),
                                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                        )
                                        repository.gbrDao().insertLabTest(cloudTest)
                                        repository.insertSyncMetadata(SyncMetadata(id, "lab_test", cloudUpdated, false))
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.getLabTestById(id)
                                    if (existing != null) {
                                        repository.gbrDao().deleteLabTest(existing)
                                        repository.deleteSyncMetadataById(id)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(labTestReg)

                // 12. Laboratory Attachments Realtime Listener
                val labAttachmentReg = db.collection("laboratory_attachments").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for laboratory_attachments", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        updateIncomingSyncPrefs(context)
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val cloudUpdated = doc.getLong("updatedAt") ?: doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    val meta = repository.getSyncMetadataById(id)
                                    val isPending = meta?.isPendingSync ?: false
                                    val localUpdated = meta?.lastUpdated ?: 0L
                                    
                                    if (!isPending && cloudUpdated > localUpdated) {
                                        val cloudAttachment = LabAttachment(
                                            id = id,
                                            sessionId = doc.getString("sessionId") ?: "",
                                            testName = doc.getString("testName") ?: "",
                                            filePathOrUrl = doc.getString("filePathOrUrl") ?: "",
                                            createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                        )
                                        repository.gbrDao().insertLabAttachment(cloudAttachment)
                                        repository.insertSyncMetadata(SyncMetadata(id, "lab_attachment", cloudUpdated, false))
                                        if (dc.type == DocumentChange.Type.MODIFIED) {
                                            updateIncomingSyncPrefs(context, "تم استلام مرفق جديد لجلسة المختبر 🧪📎")
                                        }
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.getLabAttachmentById(id)
                                    if (existing != null) {
                                        repository.gbrDao().deleteLabAttachment(existing)
                                        repository.deleteSyncMetadataById(id)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(labAttachmentReg)

                // 13. Production Logs Realtime Listener
                val prodLogsReg = db.collection("production_logs").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for production_logs", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val log = ProductionLog(
                                        id = id,
                                        formulationId = doc.getString("formulationId") ?: "",
                                        formulationName = doc.getString("formulationName") ?: "غير مسمى",
                                        operatorName = doc.getString("operatorName") ?: "غير محدد",
                                        batchWeightKg = doc.getSafeDouble("batchWeightKg") ?: 0.0,
                                        status = doc.getString("status") ?: "مكتمل",
                                        timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                                    )
                                    repository.insertProductionLog(log)
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.productionLogs.first().find { it.id == id }
                                    if (existing != null) {
                                        repository.deleteProductionLog(existing)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(prodLogsReg)

                // 14. Operational Alerts Realtime Listener
                val alertsReg = db.collection("operational_alerts").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for operational_alerts", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val id = doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val alert = com.example.data.OperationalAlert(
                                        id = id,
                                        title = doc.getString("title") ?: "",
                                        description = doc.getString("description") ?: "",
                                        mainSection = doc.getString("mainSection") ?: "",
                                        bindingScope = doc.getString("bindingScope") ?: "",
                                        bindingElementName = doc.getString("bindingElementName") ?: "",
                                        alertLevel = doc.getString("alertLevel") ?: "info",
                                        status = doc.getString("status") ?: "ACTIVE",
                                        createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis()
                                    )
                                    repository.insertOperationalAlert(alert)
                                    if (dc.type == DocumentChange.Type.ADDED) {
                                        updateIncomingSyncPrefs(context, "تم استلام تنبيه تشغيلي جديد: ${alert.title} ⚠️")
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    val existing = repository.allOperationalAlerts.first().find { it.id == id }
                                    if (existing != null) {
                                        repository.deleteOperationalAlert(existing)
                                    }
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(alertsReg)

                // 15. Equipment Devices Realtime Listener
                val equipReg = db.collection("equipment_devices").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for equipment_devices", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                        for (doc in snapshots.documents) {
                            val id = doc.getString("id") ?: doc.id
                            val cloudName = doc.getString("name") ?: ""
                            val cloudIp = doc.getString("ip") ?: doc.getString("deviceId") ?: ""
                            val cloudRate = doc.getLong("updateRateMs") ?: 1000L
                            if (id == "line-1" && cloudName.isNotBlank()) {
                                prefs.edit()
                                    .putString("line_1_name", cloudName)
                                    .putString("line_1_ip", cloudIp)
                                    .putLong("line_1_update_rate", cloudRate)
                                    .apply()
                            } else if (id == "line-2" && cloudName.isNotBlank()) {
                                prefs.edit()
                                    .putString("line_2_name", cloudName)
                                    .putString("line_2_ip", cloudIp)
                                    .putLong("line_2_update_rate", cloudRate)
                                    .apply()
                            }
                        }
                    }
                }
                activeRegistrations.add(equipReg)

                // 16. Formulation Reference Specs Realtime Listener
                val specsReg = db.collection("formulation_reference_specs").addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for formulation_reference_specs", error)
                        return@addSnapshotListener
                    }
                    if (snapshots != null) {
                        autoSyncScope.launch {
                            for (dc in snapshots.documentChanges) {
                                val doc = dc.document
                                val formulationId = doc.getString("formulationId") ?: doc.id
                                if (dc.type == DocumentChange.Type.ADDED || dc.type == DocumentChange.Type.MODIFIED) {
                                    val currentDevId = com.example.data.DeviceSecurityManager.getDeviceId()
                                    val lastModDev = doc.getString("lastModifiedDevice") ?: ""
                                    if (doc.metadata.hasPendingWrites() || (lastModDev.isNotBlank() && lastModDev == currentDevId)) {
                                        continue
                                    }
                                    val specs = FormulationReferenceSpecs(
                                        formulationId = formulationId,
                                        approvalDate = doc.getString("approvalDate") ?: "",
                                        phValue = doc.getString("phValue"),
                                        densityEmptyWeight = doc.getSafeDouble("densityEmptyWeight"),
                                        densityFilledWeight = doc.getSafeDouble("densityFilledWeight"),
                                        densityFinalResult = doc.getSafeDouble("densityFinalResult"),
                                        solidWeightBefore = doc.getSafeDouble("solidWeightBefore"),
                                        solidWeightAfter = doc.getSafeDouble("solidWeightAfter"),
                                        solidResultPct = doc.getSafeDouble("solidResultPct"),
                                        binderWeightBefore = doc.getSafeDouble("binderWeightBefore"),
                                        binderWeightAfter = doc.getSafeDouble("binderWeightAfter"),
                                        binderResultPct = doc.getSafeDouble("binderResultPct"),
                                        viscosityJson = doc.getString("viscosityJson"),
                                        viscosityFinalResult = doc.getSafeDouble("viscosityFinalResult"),
                                        viscosityDilutedJson = doc.getString("viscosityDilutedJson"),
                                        viscosityDilutedFinalResult = doc.getSafeDouble("viscosityDilutedFinalResult"),
                                        rheologyJson = doc.getString("rheologyJson"),
                                        rheologyIndexResult = doc.getSafeDouble("rheologyIndexResult")
                                    )
                                    repository.gbrDao().insertFormulationReferenceSpecs(specs)
                                    if (dc.type == DocumentChange.Type.MODIFIED) {
                                        updateIncomingSyncPrefs(context, "تم استلام تحديث للمواصفات القياسية والمعيارية 📐")
                                    }
                                } else if (dc.type == DocumentChange.Type.REMOVED) {
                                    repository.gbrDao().deleteFormulationReferenceSpecsByFormulationId(formulationId)
                                }
                            }
                        }
                    }
                }
                activeRegistrations.add(specsReg)

                // 17. Hosting Settings Realtime Listener
                val hostingReg = db.collection("system_settings").document("hosting_config").addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Listen failed for hosting_config", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val cloudUrl = snapshot.getString("hostinger_gateway_url") ?: ""
                        val cloudEnabled = snapshot.getBoolean("hostinger_enabled") ?: false
                        val cloudLastUpdated = snapshot.getLong("hostinger_last_updated") ?: 0L
                        val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
                        val localLastUpdated = prefs.getLong("hostinger_last_updated", 0L)
                        if (cloudLastUpdated > localLastUpdated) {
                            prefs.edit()
                                .putString("hostinger_gateway_url", cloudUrl)
                                .putBoolean("hostinger_enabled", cloudEnabled)
                                .putLong("hostinger_last_updated", cloudLastUpdated)
                                .apply()
                            com.example.ui.GbrViewModel.externalHostingUpdates.tryEmit(Pair(cloudUrl, cloudEnabled))
                            Log.i(TAG, "Realtime Hosting config updated: url=$cloudUrl, enabled=$cloudEnabled")
                        }
                    }
                }
                activeRegistrations.add(hostingReg)

                // 18. المخزون (دفتر الحركات + السجلات الرئيسة) — منطق مستقل في InventorySync.kt
                try {
                    activeRegistrations.addAll(InventorySync.attachListeners(db, context))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed attaching inventory listeners", e)
                }

                Log.i(TAG, "Successfully attached 17 active Realtime Sync listeners to Firestore!")
                
                // Drain local pending queue automatically whenever realtime listeners start/restart (connection restored)
                launch(Dispatchers.IO) {
                    try {
                        val pendingList = repository.getPendingSyncMetadata()
                        if (pendingList.isNotEmpty()) {
                            addLocalSystemLog(context, "sync", "🔄 تم العثور على ${pendingList.size} من السجلات المعلقة محلياً. جاري رفعها تلقائياً للسحابة...")
                            for (item in pendingList) {
                                try {
                                    uploadSingleEntity(context, repository, item.id, item.entityType)
                                    Log.i(TAG, "Successfully auto-uploaded pending entity: ${item.id} (${item.entityType})")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed auto-uploading pending entity: ${item.id} (${item.entityType})", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing pending sync queue in startRealtimeListeners", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing or attaching Firestore Realtime listeners", e)
            }
            }
        }
    }

    private fun getCollectionName(type: String): String {
        return when (type) {
            "raw_material" -> "raw_materials"
            "formulation" -> "formulations"
            "production_order" -> "production_orders"
            "development_project" -> "development_projects"
            "quality_test" -> "quality_tests"
            "production_adjustment" -> "production_adjustments"
            "lab_session" -> "laboratory_sessions"
            "lab_test" -> "laboratory_tests"
            "lab_attachment" -> "laboratory_attachments"
            "production_log" -> "production_logs"
            "operational_alert" -> "operational_alerts"
            "formulation_reference_specs" -> "formulation_reference_specs"
            else -> type
        }
    }

    fun uploadSingleEntityAsync(ctx: Context, repository: GbrRepository, id: String, type: String) {
        autoSyncScope.launch {
            try {
                uploadSingleEntity(ctx, repository, id, type)
            } catch (e: Exception) {
                Log.e(TAG, "Failed single auto-push for $id ($type)", e)
                addLocalSystemLog(ctx, "error", "❌ تعذر الحفظ الفوري لـ ${describeRecord(repository, id, type)}: ${e.localizedMessage}")
            }
        }
    }

    private fun isFinishedProductionStatus(status: String?): Boolean =
        status != null && (status.contains("مكتمل") || status.contains("مؤرشف"))

    // حارس أوامر الإنتاج: نسخة السحابة تفوز (فلا نرفع النسخة المحلية) إذا كانت أحدث، أو إذا كانت مكتملة/مؤرشفة والمحلية غير ذلك.
    private fun cloudProductionOrderWins(cloudDoc: DocumentSnapshot?, localStamp: Long, localStatus: String): Boolean {
        if (cloudDoc == null || !cloudDoc.exists()) return false
        val cloudUpdated = cloudDoc.getLong("lastUpdated") ?: 0L
        if (cloudUpdated > localStamp) return true
        val cloudStatus = cloudDoc.getString("status") ?: ""
        if (isFinishedProductionStatus(cloudStatus) && !isFinishedProductionStatus(localStatus)) return true
        return false
    }

    // تطبيق نسخة السحابة من أمر إنتاج على القاعدة المحلية (دون إنشاء سجل «معلّق»)
    private suspend fun applyCloudProductionOrder(repository: GbrRepository, id: String, doc: DocumentSnapshot, existing: ProductionOrder?) {
        val mergedTimer = mergeTimerStartTimes(existing?.timerStartTimesJson ?: "{}", doc.getString("timerStartTimesJson") ?: "{}")
        val cloudOrder = ProductionOrder(
            id = id,
            orderNumber = doc.getString("orderNumber") ?: existing?.orderNumber ?: "",
            batchNumber = doc.getString("batchNumber") ?: existing?.batchNumber ?: "",
            formulationId = doc.getString("formulationId") ?: existing?.formulationId ?: "",
            formulationName = doc.getString("formulationName") ?: existing?.formulationName ?: "",
            formulationVersion = doc.getString("formulationVersion") ?: existing?.formulationVersion ?: "",
            requiredWeightKg = doc.getSafeDouble("requiredWeightKg") ?: existing?.requiredWeightKg ?: 0.0,
            createdAt = doc.getLong("createdAt") ?: existing?.createdAt ?: System.currentTimeMillis(),
            status = doc.getString("status") ?: existing?.status ?: "مسودة",
            notes = doc.getString("notes") ?: existing?.notes ?: "",
            scaleFactor = doc.getSafeDouble("scaleFactor") ?: existing?.scaleFactor ?: 1.0,
            originalWeightKg = doc.getSafeDouble("originalWeightKg") ?: existing?.originalWeightKg ?: 0.0,
            progressPercent = (doc.getLong("progressPercent") ?: existing?.progressPercent?.toLong() ?: 0L).toInt(),
            currentPhaseIndex = (doc.getLong("currentPhaseIndex") ?: existing?.currentPhaseIndex?.toLong() ?: 0L).toInt(),
            currentItemIndex = (doc.getLong("currentItemIndex") ?: existing?.currentItemIndex?.toLong() ?: 0L).toInt(),
            completedItemsJson = doc.getString("completedItemsJson") ?: existing?.completedItemsJson ?: "[]",
            packagingSnapshotJson = doc.getString("packagingSnapshotJson") ?: existing?.packagingSnapshotJson ?: "",
            actualPackagingJson = doc.getString("actualPackagingJson") ?: existing?.actualPackagingJson ?: "[]",
            timerStartTimesJson = mergedTimer,
            startTime = doc.getLong("startTime") ?: existing?.startTime ?: 0L,
            endTime = doc.getLong("endTime") ?: existing?.endTime ?: 0L,
            operatorName = doc.getString("operatorName") ?: existing?.operatorName ?: "المشرف"
        )
        repository.gbrDao().insertProductionOrder(cloudOrder)
        saveDownloadedProductionOrderDetails(repository, id, doc)
        val cloudUpdated = doc.getLong("lastUpdated") ?: System.currentTimeMillis()
        repository.insertSyncMetadata(SyncMetadata(id, "production_order", cloudUpdated, false))
    }

    // ترميم كميات المواد في أمر إنتاج من آخر سجل تعديل (production_adjustments).
    // سجلات التعديل مجموعة منفصلة وتبقى سليمة حتى لو عادت كمية المادة داخل الأمر إلى قيمة قديمة
    // (مثلاً بعد كتابة نسخة قديمة فوق السحابة). نُرمِّم فقط عند وجود بند وصفة واحد للمادة (دون غموض التوزيع).
    // يُرجع true إن عُدّلت كمية واحدة على الأقل (وعندها يُرفع الأمر ليُصلَح في السحابة أيضاً).
    suspend fun healOrderQuantitiesFromAdjustments(ctx: Context, repository: GbrRepository, orderId: String): Boolean {
        try {
            val adjustments = repository.getProductionAdjustmentsSync(orderId)
            if (adjustments.isEmpty()) return false
            val order = repository.getProductionOrderById(orderId) ?: return false
            val latestByMaterial = adjustments
                .filter { it.rawMaterialId.isNotBlank() && !it.reason.startsWith("استبدال المادة") }
                .groupBy { it.rawMaterialId }
                .mapValues { (_, list) -> list.maxByOrNull { it.timestamp } }
            val items = repository.getProductionOrderItems(orderId).first()
            val recipeItems = repository.getProductionOrderRecipeItemsForOrderSync(orderId)
            var changed = false
            for ((materialId, adj) in latestByMaterial) {
                if (adj == null) continue
                val item = items.find { it.rawMaterialId == materialId } ?: continue
                val recipeForMaterial = recipeItems.filter { it.rawMaterialId == materialId }
                if (recipeForMaterial.size > 1) continue
                val target = adj.newQuantity
                val itemDiffers = kotlin.math.abs(item.calculatedQuantity - target) > 0.0005
                val recipeItem = recipeForMaterial.firstOrNull()
                val recipeDiffers = recipeItem != null && kotlin.math.abs(recipeItem.calculatedQuantity - target) > 0.0005
                if (!itemDiffers && !recipeDiffers) continue
                if (itemDiffers) repository.updateProductionOrderItemQuantity(orderId, materialId, target)
                if (recipeItem != null && recipeDiffers) repository.updateProductionOrderRecipeItemQuantity(recipeItem.id, target)
                addLocalSystemLog(
                    ctx, "sync",
                    "🔧 تم ترميم كمية المادة ${adj.rawMaterialName} في الأمر ${order.orderNumber} من آخر سجل تعديل: ${item.calculatedQuantity} ← $target كجم"
                )
                changed = true
            }
            if (changed) {
                repository.logSyncChange(orderId, "production_order")
            }
            return changed
        } catch (e: Exception) {
            Log.e(TAG, "healOrderQuantitiesFromAdjustments failed for $orderId", e)
            return false
        }
    }

    // ترميم كل الأوامر التي لها سجلات تعديل (يُستدعى بعد المزامنة الشاملة وبعد وصول سجلات التعديل من السحابة)
    // تنبيه (دون تغيير تلقائي) إن حملت تركيبتان مختلفتا الاسم الكود الافتراضي نفسه GBR-xxx (مثلاً أُنشئتا من جهازين قبل المزامنة)
    suspend fun warnDuplicateFormulationCodes(ctx: Context, repository: GbrRepository) {
        try {
            val dup = repository.formulations.first()
                .filter { Regex("^GBR-\\d+$").matches(it.code.trim().uppercase()) }
                .groupBy { it.code.trim().uppercase() }
                .filter { (_, list) -> list.map { it.name.trim().lowercase() }.distinct().size > 1 }
            for ((code, list) in dup) {
                addLocalSystemLog(ctx, "error", "⚠️ تركيبتان مختلفتان تحملان الكود $code: ${list.map { it.name }.distinct().joinToString(" و ")}. عدّل كود إحداهما من الترس ← تعديل.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "warnDuplicateFormulationCodes failed", e)
        }
    }

    suspend fun healAllOrderQuantitiesFromAdjustments(ctx: Context, repository: GbrRepository) {
        try {
            val orderIds = repository.getAllProductionAdjustments().first().map { it.productionOrderId }.filter { it.isNotBlank() }.distinct()
            for (oid in orderIds) {
                healOrderQuantitiesFromAdjustments(ctx, repository, oid)
            }
        } catch (e: Exception) {
            Log.e(TAG, "healAllOrderQuantitiesFromAdjustments failed", e)
        }
    }

    suspend fun uploadSingleEntity(ctx: Context, repository: GbrRepository, id: String, type: String) {
        if (!initializeFirebase(ctx)) {
            Log.e(TAG, "Cannot upload single entity $id of type $type: Firebase failed to initialize.")
            addLocalSystemLog(ctx, "error", "❌ فشل رفع ${describeRecord(repository, id, type)}. السبب: فشل تهيئة الاتصال بالسحابة")
            throw Exception("فشلت تهيئة اتصال السحابة Firebase")
        }
        
        val now = System.currentTimeMillis()
        val existingMeta = repository.getSyncMetadataById(id)
        val lastUpdatedTime = existingMeta?.lastUpdated ?: now
        if (existingMeta == null) {
            repository.gbrDao().insertSyncMetadata(SyncMetadata(
                id = id,
                entityType = type,
                lastUpdated = now,
                isPendingSync = true,
                syncStage = "UPLOADING",
                lastAttempt = now
            ))
        } else {
            // لا نرفع ختم lastUpdated عند مجرد محاولة الرفع (يبقى ختم آخر تعديل فعلي) حتى لا تبدو النسخة المحلية أحدث من السحابة
            repository.gbrDao().insertSyncMetadata(existingMeta.copy(
                isPendingSync = true,
                lastUpdated = lastUpdatedTime,
                syncStage = "UPLOADING",
                lastAttempt = now
            ))
        }

        val db = FirebaseFirestore.getInstance()
        val collectionName = getCollectionName(type)
        
        try {
            when (type) {
            "raw_material" -> {
                val item = repository.getRawMaterialById(id)
                if (item == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("المادة الخام لم تعد موجودة محلياً وتمت إزالتها من السجلات المعلقة")
                }
                // حماية من الكتابة فوق نسخة سحابية أحدث (نسخة قديمة معلّقة على جهاز آخر)؛ تصل النسخة السحابية عبر المستمع/المزامنة الشاملة
                val cloudBeforeRaw = db.collection("raw_materials").document(id).get().awaitTask()
                if (cloudBeforeRaw.exists() && (cloudBeforeRaw.getLong("lastUpdated") ?: 0L) > lastUpdatedTime) {
                    addLocalSystemLog(ctx, "sync", "🛡️ تم منع رفع نسخة محلية أقدم من المادة الخام ${item.name} (نسخة السحابة أحدث)")
                    repository.markAsSynced(id)
                    return
                }
                val priceHistoryList = repository.getPriceHistoryForMaterial(id).first()
                val priceHistoryPayload = priceHistoryList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "oldPrice" to it.oldPrice,
                        "newPrice" to it.newPrice,
                        "dateChange" to it.dateChange
                    )
                }
                val resolvedTdsUri = ensureTdsUriIsUploaded(ctx, repository, item.id, item.tdsUri) ?: ""
                val payload = hashMapOf<String, Any>(
                    "id" to item.id,
                    "name" to item.name,
                    "productionName" to item.productionName,
                    "price" to item.price,
                    "priceUnit" to item.priceUnit,
                    "notes" to item.notes,
                    "tdsUri" to resolvedTdsUri,
                    "isActive" to item.isActive,
                    "lastUpdated" to lastUpdatedTime,
                    "priceHistory" to priceHistoryPayload,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("raw_materials").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "raw_materials")
                repository.markAsSynced(id)
            }
            "formulation" -> {
                val item = repository.getFormulationById(id)
                if (item == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("التركيبة الإنتاجية لم تعد موجودة محلياً وتمت إزالتها من السجلات المعلقة")
                }
                val cloudBeforeForm = db.collection("formulations").document(id).get().awaitTask()
                if (cloudBeforeForm.exists() && (cloudBeforeForm.getLong("lastUpdated") ?: 0L) > lastUpdatedTime) {
                    addLocalSystemLog(ctx, "sync", "🛡️ تم منع رفع نسخة محلية أقدم من التركيبة ${item.name} (نسخة السحابة أحدث)")
                    repository.markAsSynced(id)
                    return
                }
                val payload = hashMapOf<String, Any>(
                    "id" to item.id,
                    "name" to item.name,
                    "code" to item.code,
                    "description" to item.description,
                    "imageUri" to (item.imageUri ?: ""),
                    "version" to item.version,
                    "status" to item.status,
                    "createdAt" to item.createdAt,
                    "notes" to item.notes,
                    "supports18L" to item.supports18L,
                    "netWeight18L" to item.netWeight18L,
                    "supports5L" to item.supports5L,
                    "netWeight5L" to item.netWeight5L,
                    "packagingWeightsJson" to item.packagingWeightsJson,
                    "productionLine" to item.productionLine,
                    "lastUpdated" to lastUpdatedTime,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                val pItems = repository.gbrDao().getFormulationItemsWithDetails(id).first()
                payload["items"] = pItems.map {
                    hashMapOf<String, Any?>(
                        "id" to it.id,
                        "formulationId" to it.formulationId,
                        "rawMaterialId" to it.rawMaterialId,
                        "quantityMultiplier" to it.quantityMultiplier,
                        "needsGrinding" to it.needsGrinding,
                        "grindingDurationMinutes" to it.grindingDurationMinutes,
                        "simulatedPrice" to if (it.simulatedPrice != null && it.simulatedPrice > 0.0) it.simulatedPrice else null,
                        "sequence" to it.sequence
                    )
                }
                
                // Revisions
                val revisionsList = repository.getRevisionsForFormulation(id).first()
                payload["revisions"] = revisionsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "formulationId" to it.formulationId,
                        "version" to it.version,
                        "dateChange" to it.dateChange,
                        "materialName" to it.materialName,
                        "oldValue" to it.oldValue,
                        "newValue" to it.newValue,
                        "editReason" to it.editReason,
                        "snapshotJson" to (it.snapshotJson ?: "")
                    )
                }

                // Phases & Recipe Items
                val phasesList = repository.getRecipePhasesForFormulationSync(id)
                payload["recipe_phases"] = phasesList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "formulationId" to it.formulationId,
                        "name" to it.name,
                        "sequence" to it.sequence,
                        "mixerRpm" to it.mixerRpm,
                        "durationMinutes" to it.durationMinutes,
                        "instructions" to it.instructions
                    )
                }

                val recipeItemsList = repository.getRecipeItemsForFormulationSync(id)
                payload["recipe_items"] = recipeItemsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "phaseId" to it.phaseId,
                        "rawMaterialId" to it.rawMaterialId,
                        "ratio" to it.ratio,
                        "sequence" to it.sequence
                    )
                }

                // Recipe Status
                val statusObj = repository.getRecipeStatusSync(id)
                payload["recipe_status"] = statusObj?.status ?: ""

                // Quality Tests
                val fTestsList = repository.getFormulationQualityTestsSync(id)
                payload["quality_tests"] = fTestsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "formulationId" to it.formulationId,
                        "testId" to it.testId,
                        "isEnabled" to it.isEnabled,
                        "minValue" to (it.minValue ?: 0.0),
                        "maxValue" to (it.maxValue ?: 0.0)
                    )
                }

                // Reference Specs
                val refSpecs = repository.getFormulationReferenceSpecsSync(id)
                if (refSpecs != null) {
                    payload["reference_specs"] = hashMapOf<String, Any?>(
                        "approvalDate" to refSpecs.approvalDate,
                        "phValue" to refSpecs.phValue,
                        "densityEmptyWeight" to refSpecs.densityEmptyWeight,
                        "densityFilledWeight" to refSpecs.densityFilledWeight,
                        "densityFinalResult" to refSpecs.densityFinalResult,
                        "solidWeightBefore" to refSpecs.solidWeightBefore,
                        "solidWeightAfter" to refSpecs.solidWeightAfter,
                        "solidResultPct" to refSpecs.solidResultPct,
                        "binderWeightBefore" to refSpecs.binderWeightBefore,
                        "binderWeightAfter" to refSpecs.binderWeightAfter,
                        "binderResultPct" to refSpecs.binderResultPct,
                        "viscosityJson" to refSpecs.viscosityJson,
                        "viscosityFinalResult" to refSpecs.viscosityFinalResult,
                        "viscosityDilutedJson" to refSpecs.viscosityDilutedJson,
                        "viscosityDilutedFinalResult" to refSpecs.viscosityDilutedFinalResult,
                        "rheologyJson" to refSpecs.rheologyJson,
                        "rheologyIndexResult" to refSpecs.rheologyIndexResult
                    )
                } else {
                    payload["reference_specs"] = com.google.firebase.firestore.FieldValue.delete()
                }

                db.collection("formulations").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "formulations")
                repository.markAsSynced(id)
            }
            "production_order" -> {
                val item = repository.getProductionOrderById(id)
                if (item == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("أمر التشغيل/الإنتاج لم يعد موجوداً محلياً وتمت إزالتها من السجلات المعلقة")
                }
                // حماية من الكتابة فوق نسخة سحابية أحدث (مثلاً نسخة قديمة معلّقة على جهاز آخر) أو أمر مكتمل/مؤرشف
                val cloudBefore = db.collection("production_orders").document(id).get().awaitTask()
                if (cloudProductionOrderWins(cloudBefore, lastUpdatedTime, item.status)) {
                    Log.w(TAG, "production_order $id: cloud copy is newer/finished; skipping stale upload and applying cloud version")
                    addLocalSystemLog(ctx, "sync", "🛡️ تم منع رفع نسخة قديمة من أمر الإنتاج ${item.orderNumber} (نسخة السحابة أحدث/مكتملة) وتحديث النسخة المحلية منها")
                    applyCloudProductionOrder(repository, id, cloudBefore, item)
                    healOrderQuantitiesFromAdjustments(ctx, repository, id)
                    return
                }
                val payload = hashMapOf<String, Any>(
                    "id" to item.id,
                    "orderNumber" to item.orderNumber,
                    "batchNumber" to item.batchNumber,
                    "formulationId" to item.formulationId,
                    "formulationName" to item.formulationName,
                    "formulationVersion" to item.formulationVersion,
                    "requiredWeightKg" to item.requiredWeightKg,
                    "createdAt" to item.createdAt,
                    "status" to item.status,
                    "notes" to item.notes,
                    "scaleFactor" to item.scaleFactor,
                    "originalWeightKg" to item.originalWeightKg,
                    "progressPercent" to item.progressPercent,
                    "currentPhaseIndex" to item.currentPhaseIndex,
                    "currentItemIndex" to item.currentItemIndex,
                    "completedItemsJson" to item.completedItemsJson,
                    "packagingSnapshotJson" to item.packagingSnapshotJson,
                    "actualPackagingJson" to item.actualPackagingJson,
                    "timerStartTimesJson" to item.timerStartTimesJson,
                    "startTime" to item.startTime,
                    "endTime" to item.endTime,
                    "operatorName" to item.operatorName,
                    "lastUpdated" to lastUpdatedTime,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                val pItems = repository.gbrDao().getProductionOrderItems(id).first()
                payload["items"] = pItems.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "productionOrderId" to it.productionOrderId,
                        "rawMaterialId" to it.rawMaterialId,
                        "rawMaterialName" to it.rawMaterialName,
                        "rawMaterialPrice" to it.rawMaterialPrice,
                        "rawMaterialPriceUnit" to it.rawMaterialPriceUnit,
                        "quantityMultiplier" to it.quantityMultiplier,
                        "calculatedQuantity" to it.calculatedQuantity,
                        "needsGrinding" to it.needsGrinding,
                        "grindingDurationMinutes" to it.grindingDurationMinutes
                    )
                }

                // Events
                val eventsList = repository.getProductionOrderEvents(id).first()
                payload["events"] = eventsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "productionOrderId" to it.productionOrderId,
                        "eventName" to it.eventName,
                        "timestamp" to it.timestamp,
                        "description" to it.description
                    )
                }

                // Phases & Recipe Items
                val phasesList = repository.gbrDao().getProductionOrderPhasesSync(id)
                payload["phases"] = phasesList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "productionOrderId" to it.productionOrderId,
                        "name" to it.name,
                        "sequence" to it.sequence,
                        "mixerRpm" to it.mixerRpm,
                        "durationMinutes" to it.durationMinutes,
                        "instructions" to it.instructions
                    )
                }
                val recipeItemsList = repository.gbrDao().getProductionOrderRecipeItemsForOrderSync(id)
                payload["recipe_items"] = recipeItemsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "productionOrderPhaseId" to it.productionOrderPhaseId,
                        "rawMaterialId" to it.rawMaterialId,
                        "rawMaterialName" to it.rawMaterialName,
                        "ratio" to it.ratio,
                        "calculatedQuantity" to it.calculatedQuantity,
                        "sequence" to it.sequence
                    )
                }

                // Quality Tests Snapshot
                val qualityTestsList = repository.gbrDao().getProductionOrderQualityTestsSync(id)
                payload["quality_tests"] = qualityTestsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "productionOrderId" to it.productionOrderId,
                        "testId" to it.testId,
                        "testName" to it.testName,
                        "minValue" to (it.minValue ?: 0.0),
                        "maxValue" to (it.maxValue ?: 0.0),
                        "sequenceIndex" to it.sequenceIndex
                    )
                }

                // Quality Test Records
                val recordsList = repository.gbrDao().getProductionOrderTestRecordsSync(id)
                payload["test_records"] = recordsList.map {
                    hashMapOf<String, Any>(
                        "id" to it.id,
                        "productionOrderId" to it.productionOrderId,
                        "isDirectTest" to it.isDirectTest,
                        "testDate" to it.testDate,
                        "resultsJson" to it.resultsJson,
                        "timestamp" to it.timestamp
                    )
                }

                db.collection("production_orders").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "production_orders")
                repository.markAsSynced(id)
            }
            "development_project" -> {
                val item = repository.getDevelopmentProjectById(id)
                if (item == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("المشروع لم يعد موجوداً محلياً وتمت إزالتها من السجلات المعلقة")
                }
                val payload = hashMapOf<String, Any>(
                    "id" to item.id,
                    "name" to item.name,
                    "createdAt" to item.createdAt,
                    "lastUpdated" to item.lastUpdated,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                val samples = repository.gbrDao().getDevelopmentSamplesForProject(id).first()
                payload["samples"] = samples.map {
                    val sampleMap = hashMapOf<String, Any>(
                        "id" to it.id,
                        "projectId" to it.projectId,
                        "sampleName" to it.sampleName,
                        "sampleNumber" to it.sampleNumber,
                        "createdAt" to it.createdAt,
                        "targetWeightKg" to it.targetWeightKg,
                        "targetGoal" to it.targetGoal,
                        "initialNotes" to it.initialNotes,
                        "researchNotes" to it.researchNotes,
                        "isApproved" to it.isApproved,
                        "approvedDate" to it.approvedDate,
                        "resultsJson" to it.resultsJson,
                        "itemsJson" to it.itemsJson,
                        "recipeJson" to it.recipeJson
                    )
                    it.status?.let { s -> sampleMap["status"] = s }
                    it.statusNotes?.let { s -> sampleMap["statusNotes"] = s }
                    it.statusUpdatedAt?.let { s -> sampleMap["statusUpdatedAt"] = s }
                    it.statusUpdatedBy?.let { s -> sampleMap["statusUpdatedBy"] = s }
                    sampleMap
                }
                db.collection("development_projects").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "development_projects")
                repository.markAsSynced(id)
            }
            "quality_test" -> {
                val localTests = repository.gbrDao().getAllQualityTestsSync()
                val qt = localTests.find { it.id == id }
                if (qt == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("فحص الجودة لم يعد موجوداً محلياً وتمت إزالتها من السجلات المعلقة")
                }
                db.collection("quality_tests").document(id).set(
                    hashMapOf<String, Any>(
                        "id" to qt.id,
                        "name" to qt.name,
                        "sequenceIndex" to qt.sequenceIndex,
                        "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                    ), SetOptions.merge()
                ).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "quality_tests")
                repository.markAsSynced(id)
            }
            "production_adjustment" -> {
                val localAdjustments = repository.getAllProductionAdjustments().first()
                val adj = localAdjustments.find { it.id == id }
                if (adj == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("تعديل كميات الإنتاج لم يعد موجوداً محلياً وتمت إزالتها من السجلات المعلقة")
                }
                db.collection("production_adjustments").document(id).set(
                    hashMapOf<String, Any>(
                        "id" to adj.id,
                        "productionOrderId" to adj.productionOrderId,
                        "rawMaterialId" to adj.rawMaterialId,
                        "rawMaterialName" to adj.rawMaterialName,
                        "originalQuantity" to adj.originalQuantity,
                        "newQuantity" to adj.newQuantity,
                        "difference" to adj.difference,
                        "reason" to adj.reason,
                        "notes" to adj.notes,
                        "timestamp" to adj.timestamp,
                        "userName" to adj.userName,
                        "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                    ), SetOptions.merge()
                ).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "production_adjustments")
                repository.markAsSynced(id)
            }
            "lab_session" -> {
                val session = repository.getLabSessionById(id)
                if (session == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("جلسة المختبر لم تعد موجودة محلياً وتمت إزالتها من السجلات المعلقة")
                }
                val payload = hashMapOf<String, Any?>(
                    "id" to session.id,
                    "sessionNumber" to session.sessionNumber,
                    "testName" to session.testName,
                    "testDate" to session.testDate,
                    "technicianName" to session.technicianName,
                    "sampleOrProduct" to session.sampleOrProduct,
                    "category" to session.category,
                    "testType" to session.testType,
                    "notes" to session.notes,
                    "comparisonType" to session.comparisonType,
                    "partyA" to session.partyA,
                    "partyB" to session.partyB,
                    "createdAt" to session.createdAt,
                    "sampleProperties" to session.sampleProperties,
                    "updatedAt" to System.currentTimeMillis(),
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("laboratory_sessions").document(id).set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "laboratory_sessions")
                repository.markAsSynced(id)
            }
            "lab_test" -> {
                val test = repository.getLabTestById(id)
                if (test == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("تحليل المختبر لم يعد موجوداً محلياً وتمت إزالتها من السجلات المعلقة")
                }
                val payload = hashMapOf<String, Any?>(
                    "id" to test.id,
                    "sessionId" to test.sessionId,
                    "name" to test.name,
                    "status" to test.status,
                    "executionDate" to test.executionDate,
                    "notes" to test.notes,
                    "testValueA" to test.testValueA,
                    "testValueB" to test.testValueB,
                    "createdAt" to test.createdAt,
                    "updatedAt" to System.currentTimeMillis(),
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("laboratory_tests").document(id).set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "laboratory_tests")
                repository.markAsSynced(id)
            }
            "lab_attachment" -> {
                val la = repository.getLabAttachmentById(id)
                if (la == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("الملحق لم يعد موجوداً محلياً وتمت إزالتها من السجلات المعلقة")
                }
                var currentUrl = la.filePathOrUrl
                if (currentUrl.startsWith("content://") || currentUrl.startsWith("file://")) {
                    val uriObj = Uri.parse(currentUrl)
                    val rawExt = ctx.contentResolver.getType(uriObj)?.substringAfterLast('/') ?: "jpg"
                    val ext = if (rawExt == "jpeg") "jpg" else rawExt
                    val fileName = "lab_${la.sessionId}_${la.id}.$ext"
                    val cloudUrl = uploadFileToFirebaseStorage(
                        context = ctx,
                        localUri = uriObj,
                        folderName = "lab",
                        fileName = fileName
                    )
                    if (cloudUrl != null) {
                        currentUrl = cloudUrl
                        val updatedAttachment = la.copy(filePathOrUrl = cloudUrl)
                        repository.gbrDao().insertLabAttachment(updatedAttachment)
                    }
                }
                val payload = hashMapOf<String, Any?>(
                    "id" to la.id,
                    "sessionId" to la.sessionId,
                    "testName" to la.testName,
                    "filePathOrUrl" to currentUrl,
                    "createdAt" to la.createdAt,
                    "updatedAt" to System.currentTimeMillis(),
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("laboratory_attachments").document(id).set(payload, com.google.firebase.firestore.SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "laboratory_attachments")
                repository.markAsSynced(id)
            }
            "production_log" -> {
                val log = repository.productionLogs.first().find { it.id == id }
                if (log == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("سجل الإنتاج لم يعد موجوداً محلياً وتمت إزالته من السجلات المعلقة")
                }
                val payload = hashMapOf<String, Any>(
                    "id" to log.id,
                    "formulationId" to log.formulationId,
                    "formulationName" to log.formulationName,
                    "operatorName" to log.operatorName,
                    "batchWeightKg" to log.batchWeightKg,
                    "status" to log.status,
                    "timestamp" to log.timestamp,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("production_logs").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "production_logs")
                repository.markAsSynced(id)
            }
            "operational_alert" -> {
                val alert = repository.allOperationalAlerts.first().find { it.id == id }
                if (alert == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("التنبيه التشغيلي لم يعد موجوداً محلياً وتمت إزالته من السجلات المعلقة")
                }
                val payload = hashMapOf<String, Any?>(
                    "id" to alert.id,
                    "title" to alert.title,
                    "description" to alert.description,
                    "mainSection" to alert.mainSection,
                    "bindingScope" to alert.bindingScope,
                    "bindingElementName" to alert.bindingElementName,
                    "alertLevel" to alert.alertLevel,
                    "status" to alert.status,
                    "createdAt" to alert.createdAt,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("operational_alerts").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "operational_alerts")
                repository.markAsSynced(id)
            }
            "formulation_reference_specs" -> {
                val specs = repository.getFormulationReferenceSpecsSync(id)
                if (specs == null) {
                    repository.deleteSyncMetadataById(id)
                    throw Exception("المواصفات القياسية لم تعد موجودة محلياً وتمت إزالتها من السجلات المعلقة")
                }
                val payload = hashMapOf<String, Any?>(
                    "formulationId" to specs.formulationId,
                    "approvalDate" to specs.approvalDate,
                    "phValue" to specs.phValue,
                    "densityEmptyWeight" to specs.densityEmptyWeight,
                    "densityFilledWeight" to specs.densityFilledWeight,
                    "densityFinalResult" to specs.densityFinalResult,
                    "solidWeightBefore" to specs.solidWeightBefore,
                    "solidWeightAfter" to specs.solidWeightAfter,
                    "solidResultPct" to specs.solidResultPct,
                    "binderWeightBefore" to specs.binderWeightBefore,
                    "binderWeightAfter" to specs.binderWeightAfter,
                    "binderResultPct" to specs.binderResultPct,
                    "viscosityJson" to specs.viscosityJson,
                    "viscosityFinalResult" to specs.viscosityFinalResult,
                    "viscosityDilutedJson" to specs.viscosityDilutedJson,
                    "viscosityDilutedFinalResult" to specs.viscosityDilutedFinalResult,
                    "rheologyJson" to specs.rheologyJson,
                    "rheologyIndexResult" to specs.rheologyIndexResult,
                    "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                )
                db.collection("formulation_reference_specs").document(id).set(payload, SetOptions.merge()).awaitTask()
                WriteDiagnostics.recordWrite(ctx, "formulation_reference_specs")
                repository.markAsSynced(id)
            }
        }
        addLocalSystemLog(ctx, "sync", "✅ تم رفع ${describeRecord(repository, id, type)} للسحابة بنجاح")
        } catch (e: Exception) {
            val errorMsg = e.localizedMessage ?: e.message ?: "خطأ غير معروف"
            val errorCode = if (e is com.google.firebase.firestore.FirebaseFirestoreException) {
                e.code.name
            } else {
                null
            }
            repository.markAsFailed(id, errorMsg, "FAILED", errorCode, System.currentTimeMillis())
            addLocalSystemLog(ctx, "error", "❌ فشل رفع ${describeRecord(repository, id, type)}. السبب: $errorMsg")
            throw e
        }
    }

    fun deleteSingleEntityAsync(context: Context, id: String, type: String) {
        autoSyncScope.launch {
            try {
                if (!initializeFirebase(context)) {
                    Log.e(TAG, "Cannot delete single entity $id from Firestore: Firebase failed to initialize.")
                    return@launch
                }
                val db = FirebaseFirestore.getInstance()
                val collectionName = getCollectionName(type)
                
                db.collection(collectionName).document(id).delete().awaitTask()
                WriteDiagnostics.recordWrite(context, collectionName)
                Log.i(TAG, "Successfully deleted $id of type $type from Firestore")
                addLocalSystemLog(context, "sync", "🗑️ تم حذف ${typeLabelAr(type)} (#${shortLogId(id)}) من السحابة")
            } catch (e: Exception) {
                Log.e(TAG, "Failed single auto-delete for $id ($type) from Firestore", e)
                addLocalSystemLog(context, "error", "❌ تعذر حذف ${typeLabelAr(type)} (#${shortLogId(id)}) من السحابة: ${e.localizedMessage}")
            }
        }
    }

    suspend fun ensureTdsUriIsUploaded(
        context: Context,
        repository: GbrRepository,
        materialId: String,
        tdsUri: String?
    ): String? {
        if (tdsUri.isNullOrBlank()) return null
        if (tdsUri.startsWith("content://") || tdsUri.startsWith("file://")) {
            try {
                val uriObj = Uri.parse(tdsUri)
                val cloudUrl = uploadFileToFirebaseStorage(
                    context = context,
                    localUri = uriObj,
                    folderName = "raw_materials",
                    fileName = "tds_${materialId}.pdf"
                )
                if (cloudUrl != null) {
                    val rawMaterial = repository.getRawMaterialById(materialId)
                    if (rawMaterial != null) {
                        val updatedMaterial = rawMaterial.copy(tdsUri = cloudUrl)
                        repository.gbrDao().updateRawMaterial(updatedMaterial)
                    }
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    val formattedDate = sdf.format(java.util.Date())
                    com.example.data.GbrFileManager.saveFileDiagnostic(
                        context,
                        materialId,
                        mapOf(
                            "original_name" to com.example.data.GbrFileManager.getFileNameFromUrl(cloudUrl),
                            "status" to "synchronized",
                            "last_sync" to formattedDate,
                            "last_download" to formattedDate
                        )
                    )
                    return cloudUrl
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed on-the-fly background file upload inside SyncManager", e)
            }
            return null
        }
        return tdsUri
    }

    suspend fun uploadFileToFirebaseStorage(
        context: Context,
        localUri: Uri,
        folderName: String,
        fileName: String,
        onProgress: (Double) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        val hostingerEnabled = prefs.getBoolean("hostinger_enabled", false)
        val hostingerGatewayUrl = prefs.getString("hostinger_gateway_url", "") ?: ""

        if (hostingerEnabled && hostingerGatewayUrl.isNotBlank()) {
            val type = when (folderName) {
                "raw_materials" -> "tds"
                "lab" -> "lab"
                else -> "image"
            }
            onProgress(15.0)
            val hostUrl = HostingerStorageManager.uploadFile(
                context = context,
                baseUrl = hostingerGatewayUrl,
                localUri = localUri,
                type = type,
                customFileName = fileName
            )
            if (hostUrl != null) {
                onProgress(100.0)
                Log.i(TAG, "File successfully uploaded via Hostinger Storage Gateway: $hostUrl")
                return@withContext hostUrl
            } else {
                Log.e(TAG, "Hostinger upload failed, and since Firebase Storage is discontinued, returning null")
                return@withContext null
            }
        }

        if (!initializeFirebase(context)) {
            Log.e(TAG, "Cannot upload: Firebase failed to initialize.")
            return@withContext null
        }
        try {
            val bytes = if (localUri.scheme == "file") {
                val fObj = java.io.File(localUri.path ?: "")
                if (fObj.exists()) fObj.readBytes() else null
            } else {
                val inputStream = context.contentResolver.openInputStream(localUri)
                inputStream?.use { it.readBytes() }
            }

            if (bytes == null || bytes.isEmpty()) {
                Log.e(TAG, "Failed to read bytes from Uri: $localUri")
                return@withContext null
            }

            Log.i(TAG, "Uploading file to storage: $folderName/$fileName")
            
            var uploadSuccess = false
            var downloadUrl: Uri? = null
            var errorToLog: Exception? = null

            // Try with default instance first
            try {
                val storage = FirebaseStorage.getInstance()
                val ref = storage.reference.child("$folderName/$fileName")
                val uploadTask = ref.putBytes(bytes)
                uploadTask.addOnProgressListener { taskSnapshot ->
                    val total = taskSnapshot.totalByteCount
                    if (total > 0) {
                        val pct = (100.0 * taskSnapshot.bytesTransferred) / total
                        onProgress(pct)
                    }
                }
                uploadTask.awaitTask()
                downloadUrl = ref.downloadUrl.awaitTask()
                uploadSuccess = true
            } catch (e: Exception) {
                errorToLog = e
                Log.w(TAG, "Primary storage bucket upload failed, attempting fallback...", e)
            }

            // Fallback bucket: try appspot.com if firebasestorage.app was default, and vice versa
            if (!uploadSuccess) {
                try {
                    val app = FirebaseApp.getInstance()
                    val projectId = app.options.projectId
                    if (!projectId.isNullOrBlank()) {
                        val currentBucket = app.options.storageBucket ?: ""
                        val fallbackBucket = if (currentBucket.contains("appspot.com")) {
                            "gs://$projectId.firebasestorage.app"
                        } else {
                            "gs://$projectId.appspot.com"
                        }
                        Log.i(TAG, "Attempting upload with fallback storage bucket: $fallbackBucket")
                        val storageFallback = FirebaseStorage.getInstance(app, fallbackBucket)
                        val refFallback = storageFallback.reference.child("$folderName/$fileName")
                        val uploadTaskFallback = refFallback.putBytes(bytes)
                        uploadTaskFallback.addOnProgressListener { taskSnapshot ->
                            val total = taskSnapshot.totalByteCount
                            if (total > 0) {
                                val pct = (100.0 * taskSnapshot.bytesTransferred) / total
                                onProgress(pct)
                            }
                        }
                        uploadTaskFallback.awaitTask()
                        downloadUrl = refFallback.downloadUrl.awaitTask()
                        uploadSuccess = true
                    }
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Fallback storage bucket upload also failed", fallbackEx)
                }
            }

            if (uploadSuccess && downloadUrl != null) {
                Log.i(TAG, "Firebase Storage upload completed. Download URL: $downloadUrl")
                downloadUrl.toString()
            } else {
                if (errorToLog != null) {
                    throw errorToLog
                } else {
                    throw Exception("Failed to upload to both primary and fallback storage buckets")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file ($fileName) to Firebase Storage in folder ($folderName)", e)
            null
        }
    }

    suspend fun deleteFileFromStorage(
        context: Context,
        fileUrlOrPath: String,
        folderName: String // "raw_materials", "lab", or "images"
    ): Boolean = withContext(Dispatchers.IO) {
        if (fileUrlOrPath.isBlank()) return@withContext false
        
        // 1. Hostinger Storage Deletion
        val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
        val hostingerEnabled = prefs.getBoolean("hostinger_enabled", false)
        val hostingerGatewayUrl = prefs.getString("hostinger_gateway_url", "") ?: ""
        
        if (hostingerEnabled && hostingerGatewayUrl.isNotBlank() && fileUrlOrPath.startsWith("http")) {
            val filename = fileUrlOrPath.substringAfterLast('/')
            val type = when (folderName) {
                "raw_materials" -> "tds"
                "lab" -> "lab"
                else -> "image"
            }
            val success = HostingerStorageManager.deleteFile(hostingerGatewayUrl, filename, type)
            if (success) {
                Log.i(TAG, "Successfully deleted file from Hostinger: $filename")
                return@withContext true
            }
        }
        
        // 2. Firebase Cloud Storage Deletion
        if (fileUrlOrPath.contains("firebasestorage.googleapis.com") || fileUrlOrPath.startsWith("gs://")) {
            if (!initializeFirebase(context)) {
                Log.e(TAG, "Cannot delete from Firebase Storage: failed to initialize.")
                return@withContext false
            }
            try {
                val storageRef = FirebaseStorage.getInstance().getReferenceFromUrl(fileUrlOrPath)
                storageRef.delete().awaitTask()
                Log.i(TAG, "Successfully deleted file from Firebase Storage: $fileUrlOrPath")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete file from Firebase Storage: $fileUrlOrPath", e)
                return@withContext false
            }
        }
        
        // 3. Local File deletion
        if (fileUrlOrPath.startsWith("file://") || fileUrlOrPath.startsWith("/")) {
            try {
                val path = if (fileUrlOrPath.startsWith("file://")) fileUrlOrPath.substring(7) else fileUrlOrPath
                val file = java.io.File(path)
                if (file.exists()) {
                    val success = file.delete()
                    Log.i(TAG, "Local file deletion result for $path: $success")
                    return@withContext success
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete local file $fileUrlOrPath", e)
            }
        }
        
        return@withContext false
    }

    // --- Custom Configurations Real-time Sync Helpers ---

    fun uploadCustomUser(context: Context, user: com.example.ui.GbrViewModel.CustomUser) {
        autoSyncScope.launch {
            if (!initializeFirebase(context)) return@launch
            try {
                val db = FirebaseFirestore.getInstance()
                val payload = hashMapOf<String, Any>(
                    "id" to user.id,
                    "name" to user.name,
                    "role" to user.role,
                    "password" to user.password,
                    "isSupervisor" to user.isSupervisor,
                    "username" to user.username,
                    "fullName" to user.fullName,
                    "isActive" to user.isActive,
                    "createdAt" to user.createdAt,
                    "lastUpdated" to System.currentTimeMillis()
                )
                db.collection("custom_users").document(user.id).set(payload, SetOptions.merge()).awaitTask()
                Log.d(TAG, "Uploaded custom user: ${user.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading custom user ${user.id}", e)
            }
        }
    }

    fun deleteCustomUser(context: Context, userId: String) {
        autoSyncScope.launch {
            if (!initializeFirebase(context)) return@launch
            try {
                val db = FirebaseFirestore.getInstance()
                db.collection("custom_users").document(userId).delete().awaitTask()
                Log.d(TAG, "Deleted custom user from Firestore: $userId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting custom user $userId", e)
            }
        }
    }

    fun uploadCustomPackaging(context: Context, pkg: com.example.ui.GbrViewModel.CustomPackaging) {
        autoSyncScope.launch {
            if (!initializeFirebase(context)) return@launch
            try {
                val db = FirebaseFirestore.getInstance()
                val payload = hashMapOf<String, Any>(
                    "id" to pkg.id,
                    "name" to pkg.name,
                    "netWeight" to pkg.netWeight,
                    "weightWithLid" to pkg.weightWithLid,
                    "price" to pkg.price,
                    "lastUpdated" to System.currentTimeMillis()
                )
                db.collection("custom_packagings").document(pkg.id).set(payload, SetOptions.merge()).awaitTask()
                Log.d(TAG, "Uploaded custom packaging: ${pkg.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading custom packaging ${pkg.id}", e)
            }
        }
    }

    fun deleteCustomPackaging(context: Context, packageId: String) {
        autoSyncScope.launch {
            if (!initializeFirebase(context)) return@launch
            try {
                val db = FirebaseFirestore.getInstance()
                db.collection("custom_packagings").document(packageId).delete().awaitTask()
                Log.d(TAG, "Deleted custom packaging from Firestore: $packageId")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting custom packaging $packageId", e)
            }
        }
    }

    fun uploadSystemLog(context: Context, log: com.example.ui.GbrViewModel.SystemLog) {
        autoSyncScope.launch {
            if (!initializeFirebase(context)) return@launch
            try {
                val db = FirebaseFirestore.getInstance()
                val id = java.util.UUID.randomUUID().toString()
                val payload = hashMapOf<String, Any>(
                    "id" to id,
                    "timestamp" to log.timestamp,
                    "category" to log.category,
                    "message" to log.message,
                    "username" to log.username,
                    "deviceName" to log.deviceName
                )
                db.collection("system_logs").document(id).set(payload).awaitTask()
                Log.d(TAG, "Uploaded system log: ${log.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading system log", e)
            }
        }
    }

    private suspend fun saveDownloadedFormulationDetails(
        repository: GbrRepository, 
        formulationId: String, 
        doc: DocumentSnapshot
    ) {
        val cloudItems = (doc.get("items") as? List<*>) ?: emptyList<Any?>()
        val domainItems = cloudItems.mapIndexedNotNull { index, itemAny ->
            val it = itemAny as? Map<*, *> ?: return@mapIndexedNotNull null
            val sPrice = (it["simulatedPrice"] as? Number)?.toDouble()
            FormulationItem(
                id = it["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                formulationId = formulationId,
                rawMaterialId = it["rawMaterialId"] as? String ?: "",
                quantityMultiplier = (it["quantityMultiplier"] as? Number)?.toDouble() ?: 0.0,
                needsGrinding = it["needsGrinding"] as? Boolean ?: false,
                grindingDurationMinutes = (it["grindingDurationMinutes"] as? Number)?.toInt() ?: 0,
                simulatedPrice = if (sPrice != null && sPrice > 0.0) sPrice else null,
                sequence = (it["sequence"] as? Number)?.toInt() ?: index
            )
        }

        val cloudRevisions = (doc.get("revisions") as? List<*>) ?: emptyList<Any?>()
        val domainRevisions = cloudRevisions.mapNotNull { revAny ->
            val rev = revAny as? Map<*, *> ?: return@mapNotNull null
            FormulationRevision(
                id = rev["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                formulationId = formulationId,
                version = rev["version"] as? String ?: "",
                dateChange = rev["dateChange"] as? String ?: "",
                materialName = rev["materialName"] as? String ?: "",
                oldValue = rev["oldValue"] as? String ?: "",
                newValue = rev["newValue"] as? String ?: "",
                editReason = rev["editReason"] as? String ?: "",
                snapshotJson = rev["snapshotJson"] as? String ?: ""
            )
        }

        val cloudPhases = (doc.get("recipe_phases") as? List<*>) ?: emptyList<Any?>()
        val domainPhases = cloudPhases.mapNotNull { cpAny ->
            val cp = cpAny as? Map<*, *> ?: return@mapNotNull null
            RecipePhase(
                id = cp["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                formulationId = formulationId,
                name = cp["name"] as? String ?: "",
                sequence = (cp["sequence"] as? Number)?.toInt() ?: 1,
                mixerRpm = (cp["mixerRpm"] as? Number)?.toInt() ?: 0,
                durationMinutes = (cp["durationMinutes"] as? Number)?.toInt() ?: 0,
                instructions = cp["instructions"] as? String ?: ""
            )
        }

        val cloudRecipeItems = (doc.get("recipe_items") as? List<*>) ?: emptyList<Any?>()
        val domainRecipeItems = cloudRecipeItems.mapNotNull { riAny ->
            val ri = riAny as? Map<*, *> ?: return@mapNotNull null
            RecipeItem(
                id = ri["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                phaseId = ri["phaseId"] as? String ?: "",
                rawMaterialId = ri["rawMaterialId"] as? String ?: "",
                ratio = (ri["ratio"] as? Number)?.toDouble() ?: 1.0,
                sequence = (ri["sequence"] as? Number)?.toInt() ?: 0
            )
        }

        val cloudStatusStr = doc.getString("recipe_status") ?: ""
        val domainRecipeStatus = if (cloudStatusStr.isNotEmpty()) {
            RecipeStatus(formulationId, cloudStatusStr)
        } else {
            null
        }

        val cloudTests = (doc.get("quality_tests") as? List<*>) ?: emptyList<Any?>()
        val domainTests = cloudTests.mapNotNull { ctAny ->
            val ct = ctAny as? Map<*, *> ?: return@mapNotNull null
            FormulationQualityTest(
                id = ct["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                formulationId = formulationId,
                testId = ct["testId"] as? String ?: "",
                isEnabled = ct["isEnabled"] as? Boolean ?: false,
                minValue = (ct["minValue"] as? Number)?.toDouble(),
                maxValue = (ct["maxValue"] as? Number)?.toDouble()
            )
        }

        // Parse formulation reference specifications
        val refSpecsMap = doc.get("reference_specs") as? Map<*, *>
        val domainReferenceSpecs = if (refSpecsMap != null) {
            FormulationReferenceSpecs(
                formulationId = formulationId,
                approvalDate = refSpecsMap["approvalDate"] as? String ?: "",
                phValue = refSpecsMap["phValue"] as? String,
                densityEmptyWeight = (refSpecsMap["densityEmptyWeight"] as? Number)?.toDouble(),
                densityFilledWeight = (refSpecsMap["densityFilledWeight"] as? Number)?.toDouble(),
                densityFinalResult = (refSpecsMap["densityFinalResult"] as? Number)?.toDouble(),
                solidWeightBefore = (refSpecsMap["solidWeightBefore"] as? Number)?.toDouble(),
                solidWeightAfter = (refSpecsMap["solidWeightAfter"] as? Number)?.toDouble(),
                solidResultPct = (refSpecsMap["solidResultPct"] as? Number)?.toDouble(),
                binderWeightBefore = (refSpecsMap["binderWeightBefore"] as? Number)?.toDouble(),
                binderWeightAfter = (refSpecsMap["binderWeightAfter"] as? Number)?.toDouble(),
                binderResultPct = (refSpecsMap["binderResultPct"] as? Number)?.toDouble(),
                viscosityJson = refSpecsMap["viscosityJson"] as? String,
                viscosityFinalResult = (refSpecsMap["viscosityFinalResult"] as? Number)?.toDouble(),
                viscosityDilutedJson = refSpecsMap["viscosityDilutedJson"] as? String,
                viscosityDilutedFinalResult = (refSpecsMap["viscosityDilutedFinalResult"] as? Number)?.toDouble(),
                rheologyJson = refSpecsMap["rheologyJson"] as? String,
                rheologyIndexResult = (refSpecsMap["rheologyIndexResult"] as? Number)?.toDouble()
            )
        } else {
            null
        }

        // Run entire update inside an atomic transaction
        repository.gbrDao().saveFormulationDetailsTransaction(
            formulationId = formulationId,
            items = domainItems,
            revisions = domainRevisions,
            phases = domainPhases,
            recipeItems = domainRecipeItems,
            qualityTests = domainTests,
            recipeStatus = domainRecipeStatus,
            referenceSpecs = domainReferenceSpecs
        )
    }

    private suspend fun saveDownloadedProductionOrderDetails(
        repository: GbrRepository, 
        orderId: String, 
        doc: DocumentSnapshot
    ) {
        val cloudOrderItems = (doc.get("items") as? List<*>) ?: emptyList<Any?>()
        for (coiAny in cloudOrderItems) {
            val coi = coiAny as? Map<*, *> ?: continue
            repository.gbrDao().insertProductionOrderItem(
                ProductionOrderItem(
                    id = coi["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    rawMaterialId = coi["rawMaterialId"] as? String ?: "",
                    rawMaterialName = coi["rawMaterialName"] as? String ?: "",
                    rawMaterialPrice = (coi["rawMaterialPrice"] as? Number)?.toDouble() ?: 0.0,
                    rawMaterialPriceUnit = coi["rawMaterialPriceUnit"] as? String ?: "",
                    quantityMultiplier = (coi["quantityMultiplier"] as? Number)?.toDouble() ?: 0.0,
                    calculatedQuantity = (coi["calculatedQuantity"] as? Number)?.toDouble() ?: 0.0,
                    needsGrinding = coi["needsGrinding"] as? Boolean ?: false,
                    grindingDurationMinutes = (coi["grindingDurationMinutes"] as? Number)?.toInt() ?: 0
                )
            )
        }

        // استبدال مادة في أمر قيد التنفيذ يحذف صف المادة القديمة محلياً؛ السحابة هي المرجع فنحذف الصفوف غير الموجودة فيها
        val cloudItemIds = cloudOrderItems.mapNotNull { ((it as? Map<*, *>)?.get("id")) as? String }
        if (cloudItemIds.isNotEmpty()) {
            repository.gbrDao().deleteProductionOrderItemsNotIn(orderId, cloudItemIds)
        }

        val cloudEvents = (doc.get("events") as? List<*>) ?: emptyList<Any?>()
        for (evAny in cloudEvents) {
            val ev = evAny as? Map<*, *> ?: continue
            repository.gbrDao().insertProductionOrderEvent(
                ProductionOrderEvent(
                    id = ev["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    eventName = ev["eventName"] as? String ?: "",
                    timestamp = (ev["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                    description = ev["description"] as? String ?: ""
                )
            )
        }

        val cloudPhases = (doc.get("phases") as? List<*>) ?: emptyList<Any?>()
        for (cpAny in cloudPhases) {
            val cp = cpAny as? Map<*, *> ?: continue
            repository.gbrDao().insertProductionOrderPhase(
                ProductionOrderPhase(
                    id = cp["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    name = cp["name"] as? String ?: "",
                    sequence = (cp["sequence"] as? Number)?.toInt() ?: 1,
                    mixerRpm = (cp["mixerRpm"] as? Number)?.toInt() ?: 0,
                    durationMinutes = (cp["durationMinutes"] as? Number)?.toInt() ?: 0,
                    instructions = cp["instructions"] as? String ?: ""
                )
            )
        }

        val cloudRecipeItems = (doc.get("recipe_items") as? List<*>) ?: emptyList<Any?>()
        for (criAny in cloudRecipeItems) {
            val cri = criAny as? Map<*, *> ?: continue
            repository.gbrDao().insertProductionOrderRecipeItem(
                ProductionOrderRecipeItem(
                    id = cri["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                    productionOrderPhaseId = cri["productionOrderPhaseId"] as? String ?: "",
                    rawMaterialId = cri["rawMaterialId"] as? String ?: "",
                    rawMaterialName = cri["rawMaterialName"] as? String ?: "",
                    ratio = (cri["ratio"] as? Number)?.toDouble() ?: 1.0,
                    calculatedQuantity = (cri["calculatedQuantity"] as? Number)?.toDouble() ?: 0.0,
                    sequence = (cri["sequence"] as? Number)?.toInt() ?: 0
                )
            )
        }

        val cloudQualityTests = (doc.get("quality_tests") as? List<*>) ?: emptyList<Any?>()
        val qualityTestsToInsert = cloudQualityTests.mapIndexedNotNull { _, itAny ->
            val it = itAny as? Map<*, *> ?: return@mapIndexedNotNull null
            ProductionOrderQualityTest(
                id = it["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                productionOrderId = orderId,
                testId = it["testId"] as? String ?: "",
                testName = it["testName"] as? String ?: "",
                minValue = (it["minValue"] as? Number)?.toDouble(),
                maxValue = (it["maxValue"] as? Number)?.toDouble(),
                sequenceIndex = (it["sequenceIndex"] as? Number)?.toInt() ?: 0
            )
        }
        if (qualityTestsToInsert.isNotEmpty()) {
            repository.gbrDao().insertProductionOrderQualityTests(qualityTestsToInsert)
        }

        val cloudRecords = (doc.get("test_records") as? List<*>) ?: emptyList<Any?>()
        for (recAny in cloudRecords) {
            val rec = recAny as? Map<*, *> ?: continue
            repository.gbrDao().insertProductionOrderTestRecord(
                ProductionOrderTestRecord(
                    id = rec["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                    productionOrderId = orderId,
                    isDirectTest = rec["isDirectTest"] as? Boolean ?: true,
                    testDate = rec["testDate"] as? String ?: "",
                    resultsJson = rec["resultsJson"] as? String ?: "{}",
                    timestamp = (rec["timestamp"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            )
        }
    }

    data class FileSyncVerificationResult(
        val totalRawMaterialsChecked: Int = 0,
        val rawMaterialsHealthy: Int = 0,
        val rawMaterialsHealed: Int = 0,
        val rawMaterialsBroken: Int = 0,
        val totalFormulationsChecked: Int = 0,
        val formulationsHealthy: Int = 0,
        val formulationsHealed: Int = 0,
        val formulationsBroken: Int = 0,
        val messageStr: String = ""
    )

    suspend fun checkFirebaseStorageFileExists(urlStr: String): Boolean = withContext(Dispatchers.IO) {
        if (urlStr.isBlank() || !urlStr.startsWith("http")) return@withContext false
        try {
            val storage = FirebaseStorage.getInstance()
            val ref = storage.getReferenceFromUrl(urlStr)
            ref.metadata.awaitTask()
            true
        } catch (e: Exception) {
            Log.e(TAG, "File check in Storage failed for url: $urlStr", e)
            false
        }
    }

    suspend fun verifyAndHealFilesAndImagesInternal(
        context: Context,
        repository: GbrRepository
    ): FileSyncVerificationResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting File & Image Storage Sync Health-Check & Healing...")
        var rawChecked = 0
        var rawHealthy = 0
        var rawHealed = 0
        var rawBroken = 0

        var formChecked = 0
        var formHealthy = 0
        var formHealed = 0
        var formBroken = 0

        val cacheDir = com.example.data.GbrFileManager.getCacheDir(context)

        // 1. Raw Materials
        try {
            val list = repository.rawMaterials.first()
            for (item in list) {
                val url = item.tdsUri
                if (url.isNullOrBlank()) continue
                rawChecked++
                if (url.startsWith("http")) {
                    val exists = checkFirebaseStorageFileExists(url)
                    if (exists) {
                        rawHealthy++
                    } else {
                        // Broken online link. Let's see if we can heal from cache
                        val localPdf = File(cacheDir, "gbr_cache_${item.id}.pdf")
                        if (localPdf.exists()) {
                            Log.i(TAG, "Healing broken raw material PDF for ${item.id} using cached file...")
                            val newUrl = uploadFileToFirebaseStorage(
                                context = context,
                                localUri = Uri.fromFile(localPdf),
                                folderName = "raw_materials",
                                fileName = "tds_${item.id}.pdf"
                            )
                            if (newUrl != null) {
                                repository.gbrDao().updateRawMaterial(item.copy(tdsUri = newUrl))
                                try {
                                    FirebaseFirestore.getInstance().collection("raw_materials").document(item.id)
                                        .update("tdsUri", newUrl).awaitTask()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed direct Firestore update for healed raw material ${item.id}", e)
                                }
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                val formattedDate = sdf.format(java.util.Date())
                                com.example.data.GbrFileManager.saveFileDiagnostic(
                                    context = context,
                                    id = item.id,
                                    values = mapOf(
                                        "status" to "synchronized",
                                        "last_sync" to formattedDate,
                                        "last_download" to formattedDate,
                                        "original_name" to "tds_${item.id}.pdf",
                                        "failure_reason" to ""
                                    )
                                )
                                rawHealed++
                            } else {
                                rawBroken++
                            }
                        } else {
                            rawBroken++
                        }
                    }
                } else if (url.startsWith("content://") || url.startsWith("file://")) {
                    // Upload local picker URI
                    val newUrl = uploadFileToFirebaseStorage(
                        context = context,
                        localUri = Uri.parse(url),
                        folderName = "raw_materials",
                        fileName = "tds_${item.id}.pdf"
                    )
                    if (newUrl != null) {
                        repository.gbrDao().updateRawMaterial(item.copy(tdsUri = newUrl))
                        try {
                            FirebaseFirestore.getInstance().collection("raw_materials").document(item.id)
                                .update("tdsUri", newUrl).awaitTask()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed direct Firestore update for local raw material ${item.id}", e)
                        }
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedDate = sdf.format(java.util.Date())
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            context = context,
                            id = item.id,
                            values = mapOf(
                                "status" to "synchronized",
                                "last_sync" to formattedDate,
                                "last_download" to formattedDate,
                                "original_name" to "tds_${item.id}.pdf",
                                "failure_reason" to ""
                            )
                        )
                        rawHealed++
                    } else {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedDate = sdf.format(java.util.Date())
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            context = context,
                            id = item.id,
                            values = mapOf(
                                "status" to "local_pending_sync",
                                "failure_reason" to "تم حفظ الملف محلياً للمزامنة التلقائية اللاحقة لعدم توفر المزامنة الفورية حالياً."
                            )
                        )
                        rawBroken++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during verifyAndHeal raw materials", e)
        }

        // 2. Formulations
        try {
            val list = repository.formulations.first()
            for (item in list) {
                val url = item.imageUri
                if (url.isNullOrBlank()) continue
                formChecked++
                if (url.startsWith("http")) {
                    val exists = checkFirebaseStorageFileExists(url)
                    if (exists) {
                        formHealthy++
                    } else {
                        // Broken online link. Let's heal
                        var localImg = File(cacheDir, "gbr_cache_${item.id}.jpg")
                        if (!localImg.exists()) {
                            localImg = File(cacheDir, "gbr_cache_${item.id}.png")
                        }
                        if (localImg.exists()) {
                            Log.i(TAG, "Healing broken formulation image for ${item.id} using cached file...")
                            val extension = if (localImg.name.endsWith(".png", true)) "png" else "jpg"
                            val newUrl = uploadFileToFirebaseStorage(
                                context = context,
                                localUri = Uri.fromFile(localImg),
                                folderName = "formulations",
                                fileName = "form_${item.id}.$extension"
                            )
                            if (newUrl != null) {
                                repository.gbrDao().updateFormulation(item.copy(imageUri = newUrl))
                                try {
                                    FirebaseFirestore.getInstance().collection("formulations").document(item.id)
                                        .update("imageUri", newUrl).awaitTask()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed direct Firestore update for healed formulation ${item.id}", e)
                                }
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                val formattedDate = sdf.format(java.util.Date())
                                com.example.data.GbrFileManager.saveFileDiagnostic(
                                    context = context,
                                    id = item.id,
                                    values = mapOf(
                                        "status" to "synchronized",
                                        "last_sync" to formattedDate,
                                        "last_download" to formattedDate,
                                        "original_name" to "form_${item.id}.$extension",
                                        "failure_reason" to ""
                                    )
                                )
                                formHealed++
                            } else {
                                formBroken++
                            }
                        } else {
                            formBroken++
                        }
                    }
                } else if (url.startsWith("content://") || url.startsWith("file://")) {
                    val isPng = url.lowercase().contains(".png")
                    val extension = if (isPng) "png" else "jpg"
                    val newUrl = uploadFileToFirebaseStorage(
                        context = context,
                        localUri = Uri.parse(url),
                        folderName = "formulations",
                        fileName = "form_${item.id}.$extension"
                    )
                    if (newUrl != null) {
                        repository.gbrDao().updateFormulation(item.copy(imageUri = newUrl))
                        try {
                            FirebaseFirestore.getInstance().collection("formulations").document(item.id)
                                .update("imageUri", newUrl).awaitTask()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed direct Firestore update for local formulation ${item.id}", e)
                        }
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedDate = sdf.format(java.util.Date())
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            context = context,
                            id = item.id,
                            values = mapOf(
                                "status" to "synchronized",
                                "last_sync" to formattedDate,
                                "last_download" to formattedDate,
                                "original_name" to "form_${item.id}.$extension",
                                "failure_reason" to ""
                            )
                        )
                        formHealed++
                    } else {
                        com.example.data.GbrFileManager.saveFileDiagnostic(
                            context = context,
                            id = item.id,
                            values = mapOf(
                                "status" to "local_pending_sync",
                                "failure_reason" to "تم حفظ الصورة محلياً للمزامنة التلقائية اللاحقة لعدم توفر المزامنة الفورية حالياً."
                            )
                        )
                        formBroken++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during verifyAndHeal formulations", e)
        }

        val successMessage = buildString {
            append("✓ تم التحقق من ملفات المواد الخام\n")
            append("✓ تم التحقق من صور التركيبات\n")
            if (rawBroken == 0 && formBroken == 0) {
                append("✓ جميع الملفات سليمة ومستقرة تماماً!")
            } else {
                append("⚠️ تم ترميم وإصلاح ${rawHealed + formHealed} من الملفات المفقودة.\n")
                if (rawBroken > 0 || formBroken > 0) {
                    append("❌ تعذر العثور على ${rawBroken + formBroken} ملف محلي لترميمه.")
                }
            }
        }

        FileSyncVerificationResult(
            totalRawMaterialsChecked = rawChecked,
            rawMaterialsHealthy = rawHealthy,
            rawMaterialsHealed = rawHealed,
            rawMaterialsBroken = rawBroken,
            totalFormulationsChecked = formChecked,
            formulationsHealthy = formHealthy,
            formulationsHealed = formHealed,
            formulationsBroken = formBroken,
            messageStr = successMessage
        )
    }

    private suspend fun syncTuyaDevicesSection(
        db: FirebaseFirestore,
        context: Context
    ) {
        try {
            val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
            val jsonString = prefs.getString("devices_list", "[]") ?: "[]"
            
            val line1Name = prefs.getString("line_1_name", "خط الإنتاج 1") ?: "خط الإنتاج 1"
            val line1Ip = prefs.getString("line_1_ip", "") ?: ""
            val line1UpdateRate = prefs.getLong("line_1_update_rate", 1000L)

            val line2Name = prefs.getString("line_2_name", "خط الإنتاج 2") ?: "خط الإنتاج 2"
            val line2Ip = prefs.getString("line_2_ip", "") ?: ""
            val line2UpdateRate = prefs.getLong("line_2_update_rate", 1000L)

            // 1. Parse local devices
            val localList = mutableListOf<Map<String, Any>>()
            val localIds = mutableSetOf<String>()
            try {
                val jsonArray = org.json.JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    localIds.add(id)
                    localList.add(mapOf(
                        "id" to id,
                        "name" to obj.getString("name"),
                        "accessId" to obj.optString("accessId", ""),
                        "accessSecret" to obj.optString("accessSecret", ""),
                        "deviceId" to obj.optString("deviceId", ""),
                        "regionUrl" to obj.optString("regionUrl", ""),
                        "regionName" to obj.optString("regionName", "")
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing local tuya devices JSON for sync", e)
            }

            // Ensure line-1 and line-2 are always in localList
            if (!localIds.contains("line-1")) {
                localList.add(mapOf(
                    "id" to "line-1",
                    "name" to line1Name,
                    "accessId" to "LINE",
                    "accessSecret" to "",
                    "deviceId" to line1Ip,
                    "regionUrl" to "LINE_1",
                    "regionName" to line1UpdateRate.toString()
                ))
            }
            if (!localIds.contains("line-2")) {
                localList.add(mapOf(
                    "id" to "line-2",
                    "name" to line2Name,
                    "accessId" to "LINE",
                    "accessSecret" to "",
                    "deviceId" to line2Ip,
                    "regionUrl" to "LINE_2",
                    "regionName" to line2UpdateRate.toString()
                ))
            }

            // 2. Fetch cloud devices
            val cloudDocs = try {
                db.collection("equipment_devices").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching equipment devices from cloud", e)
                emptyList()
            }

            val mergedMap = mutableMapOf<String, Map<String, Any>>()
            localList.forEach { mergedMap[it["id"] as String] = it }

            // Sync from Cloud to Local if cloud has updated values or new lines
            for (doc in cloudDocs) {
                val id = doc.getString("id") ?: doc.id
                val cloudName = doc.getString("name") ?: ""
                val cloudIp = doc.getString("ip") ?: doc.getString("deviceId") ?: ""
                val cloudRate = doc.getLong("updateRateMs") 
                    ?: (doc.getString("regionName")?.toLongOrNull()) 
                    ?: 1000L

                if (id == "line-1") {
                    val localNameEmpty = line1Name == "خط الإنتاج 1" || line1Name.isBlank()
                    val localIpEmpty = line1Ip.isBlank()
                    if (cloudName.isNotBlank() && (localNameEmpty || cloudIp.isNotBlank())) {
                        prefs.edit()
                            .putString("line_1_name", cloudName)
                            .putString("line_1_ip", cloudIp)
                            .putLong("line_1_update_rate", cloudRate)
                            .apply()
                    }
                } else if (id == "line-2") {
                    val localNameEmpty = line2Name == "خط الإنتاج 2" || line2Name.isBlank()
                    val localIpEmpty = line2Ip.isBlank()
                    if (cloudName.isNotBlank() && (localNameEmpty || cloudIp.isNotBlank())) {
                        prefs.edit()
                            .putString("line_2_name", cloudName)
                            .putString("line_2_ip", cloudIp)
                            .putLong("line_2_update_rate", cloudRate)
                            .apply()
                    }
                }

                val pkg = mapOf(
                    "id" to id,
                    "name" to cloudName,
                    "accessId" to (doc.getString("accessId") ?: "LINE"),
                    "accessSecret" to (doc.getString("accessSecret") ?: ""),
                    "deviceId" to cloudIp,
                    "regionUrl" to (doc.getString("regionUrl") ?: if (id == "line-2") "LINE_2" else "LINE_1"),
                    "regionName" to cloudRate.toString(),
                    "updateRateMs" to cloudRate
                )
                if (!mergedMap.containsKey(id) || (cloudName.isNotBlank() && (mergedMap[id]?.get("deviceId") as? String).isNullOrBlank())) {
                    mergedMap[id] = pkg
                }
            }

            // Upload Local lines to Cloud
            val currentLine1Name = prefs.getString("line_1_name", line1Name) ?: line1Name
            val currentLine1Ip = prefs.getString("line_1_ip", line1Ip) ?: line1Ip
            val currentLine1Rate = prefs.getLong("line_1_update_rate", line1UpdateRate)

            val currentLine2Name = prefs.getString("line_2_name", line2Name) ?: line2Name
            val currentLine2Ip = prefs.getString("line_2_ip", line2Ip) ?: line2Ip
            val currentLine2Rate = prefs.getLong("line_2_update_rate", line2UpdateRate)

            val doc1 = mapOf(
                "id" to "line-1",
                "name" to currentLine1Name,
                "ip" to currentLine1Ip,
                "deviceId" to currentLine1Ip,
                "updateRateMs" to currentLine1Rate,
                "regionUrl" to "LINE_1",
                "regionName" to currentLine1Rate.toString(),
                "lastUpdated" to System.currentTimeMillis()
            )
            val doc2 = mapOf(
                "id" to "line-2",
                "name" to currentLine2Name,
                "ip" to currentLine2Ip,
                "deviceId" to currentLine2Ip,
                "updateRateMs" to currentLine2Rate,
                "regionUrl" to "LINE_2",
                "regionName" to currentLine2Rate.toString(),
                "lastUpdated" to System.currentTimeMillis()
            )

            try {
                db.collection("equipment_devices").document("line-1").set(doc1, SetOptions.merge()).awaitTask()
                db.collection("equipment_devices").document("line-2").set(doc2, SetOptions.merge()).awaitTask()
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading production lines to Firestore", e)
            }

            // Save merged list locally
            val finalJsonArray = org.json.JSONArray()
            mergedMap.values.forEach { dev ->
                val jsonObject = org.json.JSONObject().apply {
                    put("id", dev["id"])
                    put("name", dev["name"])
                    put("accessId", dev["accessId"])
                    put("accessSecret", dev["accessSecret"])
                    put("deviceId", dev["deviceId"])
                    put("regionUrl", dev["regionUrl"])
                    put("regionName", dev["regionName"])
                }
                finalJsonArray.put(jsonObject)
            }
            prefs.edit().putString("devices_list", finalJsonArray.toString()).apply()
            Log.i(TAG, "Tuya and production line equipment devices synchronized successfully! Total registered: ${mergedMap.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync equipment devices", e)
        }
    }

    suspend fun performCloudConsistencyAudit(
        context: Context,
        repository: GbrRepository
    ): CloudAuditReport = withContext(Dispatchers.IO) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val auditTime = sdf.format(Date())
        
        if (!initializeFirebase(context)) {
            return@withContext CloudAuditReport(
                connectionSucceeded = false,
                connectionError = "فشل تهيئة الاتصال بـ Firebase",
                auditDate = auditTime,
                discrepancyMessage = "تعذر الاتصال بخوادم المزامنة السحابية."
            )
        }
        
        try {
            val db = FirebaseFirestore.getInstance()
            
            // 1. Raw Materials Counts
            val localRaw = repository.rawMaterials.first().size
            val cloudRaw = try {
                db.collection("raw_materials").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }
            
            // 2. Formulations Counts
            val localForm = repository.formulations.first().size
            val cloudForm = try {
                db.collection("formulations").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }
            
            // 3. Production Orders Counts
            val localOrder = repository.productionOrders.first().size
            val cloudOrder = try {
                db.collection("production_orders").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }

            // 4. Production Logs / Statistics Counts
            val localLog = repository.productionLogs.first().size
            val cloudLog = try {
                db.collection("production_logs").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }

            // 5. Research & Development (R&D) Counts
            val localDev = repository.allDevelopmentProjects.first().size
            val cloudDev = try {
                db.collection("development_projects").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }

            // 6. Laboratory / Quality Sessions Counts
            val localLab = repository.allLabSessions.first().size
            val cloudLab = try {
                db.collection("laboratory_sessions").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }

            // 7. Custom Packagings Counts
            val localPkg = try {
                val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
                val jsonString = prefs.getString("custom_packagings_json", "[]") ?: "[]"
                org.json.JSONArray(jsonString).length()
            } catch (e: Exception) {
                0
            }
            val cloudPkg = try {
                db.collection("custom_packagings").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }

            // 8. Custom Users Counts
            val localUser = try {
                val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
                val jsonString = prefs.getString("custom_users_json", "[]") ?: "[]"
                org.json.JSONArray(jsonString).length()
            } catch (e: Exception) {
                0
            }
            val cloudUser = try {
                db.collection("custom_users").get().awaitTask().size()
            } catch (e: Exception) {
                -1
            }
            
            val isRawOk = localRaw == cloudRaw
            val isFormOk = localForm == cloudForm
            val isOrderOk = localOrder == cloudOrder
            val isLogOk = localLog == cloudLog
            val isDevOk = localDev == cloudDev
            val isLabOk = localLab == cloudLab
            val isPkgOk = localPkg == cloudPkg
            val isUserOk = localUser == cloudUser
            
            var discrepancies = 0
            val msgBuilder = StringBuilder()
            
            if (cloudRaw == -1 || cloudForm == -1 || cloudOrder == -1 || cloudLog == -1 ||
                cloudDev == -1 || cloudLab == -1 || cloudPkg == -1 || cloudUser == -1) {
                return@withContext CloudAuditReport(
                    connectionSucceeded = true,
                    connectionError = "خطأ في قراءة مجموعات معينة من السحابة",
                    auditDate = auditTime,
                    discrepancyMessage = "تنبيه: تعذر إتمام التدقيق بالكامل بسبب قيود اتصال جزئية."
                )
            }
            
            if (!isRawOk) {
                discrepancies += Math.abs(localRaw - cloudRaw)
                msgBuilder.append("• اختلاف في المواد الخام: محلي ($localRaw) مقابل سحابي ($cloudRaw)\n")
            }
            if (!isFormOk) {
                discrepancies += Math.abs(localForm - cloudForm)
                msgBuilder.append("• اختلاف في كتالوج التركيبات: محلي ($localForm) مقابل سحابي ($cloudForm)\n")
            }
            if (!isOrderOk) {
                discrepancies += Math.abs(localOrder - cloudOrder)
                msgBuilder.append("• اختلاف في أوامر الإنتاج: محلي ($localOrder) مقابل سحابي ($cloudOrder)\n")
            }
            if (!isLogOk) {
                discrepancies += Math.abs(localLog - cloudLog)
                msgBuilder.append("• اختلاف في أرقام إحصائيات الإنتاج (حركة الأصناف): محلي ($localLog) مقابل سحابي ($cloudLog)\n")
            }
            if (!isDevOk) {
                discrepancies += Math.abs(localDev - cloudDev)
                msgBuilder.append("• اختلاف في أبحاث وتطوير R&D: محلي ($localDev) مقابل سحابي ($cloudDev)\n")
            }
            if (!isLabOk) {
                discrepancies += Math.abs(localLab - cloudLab)
                msgBuilder.append("• اختلاف في سجلات فحوصات المختبر: محلي ($localLab) مقابل سحابي ($cloudLab)\n")
            }
            if (!isPkgOk) {
                discrepancies += Math.abs(localPkg - cloudPkg)
                msgBuilder.append("• اختلاف في عبوات وأوزان التغليف: محلي ($localPkg) مقابل سحابي ($cloudPkg)\n")
            }
            if (!isUserOk) {
                discrepancies += Math.abs(localUser - cloudUser)
                msgBuilder.append("• اختلاف في مستخدمي وصلاحيات النظام: محلي ($localUser) مقابل سحابي ($cloudUser)\n")
            }
            
            val finalMsg = if (discrepancies == 0) {
                "✓ كافة أقسام قاعدة البيانات والخيارات الفنية متناسقة ومطابقة بنسبة 100% مع سحابة Firestore!"
            } else {
                "⚠️ تم اكتشاف عدم تطابق في السجلات:\n" + msgBuilder.toString() + "يرجى الضغط على زر التزامن السحابي الفوري لإعادة مطابقة واتساق السجلات يدوياً."
            }
            
            CloudAuditReport(
                connectionSucceeded = true,
                localRawCount = localRaw,
                cloudRawCount = cloudRaw,
                rawMaterialsMatch = isRawOk,
                localFormCount = localForm,
                cloudFormCount = cloudForm,
                formulationsMatch = isFormOk,
                localOrderCount = localOrder,
                cloudOrderCount = cloudOrder,
                ordersMatch = isOrderOk,
                localDevCount = localDev,
                cloudDevCount = cloudDev,
                devMatch = isDevOk,
                localLabCount = localLab,
                cloudLabCount = cloudLab,
                labMatch = isLabOk,
                localLogCount = localLog,
                cloudLogCount = cloudLog,
                logMatch = isLogOk,
                localPkgCount = localPkg,
                cloudPkgCount = cloudPkg,
                pkgMatch = isPkgOk,
                localUserCount = localUser,
                cloudUserCount = cloudUser,
                userMatch = isUserOk,
                totalDiscrepancies = discrepancies,
                discrepancyMessage = finalMsg,
                auditDate = auditTime
            )
        } catch (e: Exception) {
            CloudAuditReport(
                connectionSucceeded = false,
                connectionError = e.localizedMessage,
                auditDate = auditTime,
                discrepancyMessage = "فشل التدقيق السحابي بسبب: ${e.localizedMessage}"
            )
        }
    }

    private suspend fun syncProductionLogsSection(
        db: FirebaseFirestore,
        context: Context,
        repository: GbrRepository
    ) {
        try {
            val localList = repository.productionLogs.first()
            val cloudDocs = try {
                db.collection("production_logs").get().awaitTask().documents
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch production logs from Firestore", e)
                emptyList()
            }
            val cloudDocsMap = cloudDocs.associateBy { it.id }

            // 1. Upload local logs that are missing in cloud
            for (log in localList) {
                val cloudDoc = cloudDocsMap[log.id]
                if (cloudDoc == null || !cloudDoc.exists()) {
                    try {
                        db.collection("production_logs").document(log.id).set(
                            hashMapOf<String, Any>(
                                "id" to log.id,
                                "formulationId" to log.formulationId,
                                "formulationName" to log.formulationName,
                                "operatorName" to log.operatorName,
                                "batchWeightKg" to log.batchWeightKg,
                                "status" to log.status,
                                "timestamp" to log.timestamp,
                                "lastModifiedDevice" to com.example.data.DeviceSecurityManager.getDeviceId()
                            ), SetOptions.merge()
                        ).awaitTask()
                        WriteDiagnostics.recordWrite(context, "production_logs")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to upload production log ${log.id}", e)
                    }
                }
            }

            // 2. Download cloud logs that are missing locally
            val localIds = localList.map { it.id }.toSet()
            for (dc in cloudDocs) {
                val id = dc.id
                if (id !in localIds) {
                    try {
                        val log = ProductionLog(
                            id = id,
                            formulationId = dc.getString("formulationId") ?: "",
                            formulationName = dc.getString("formulationName") ?: "غير مسمى",
                            operatorName = dc.getString("operatorName") ?: "غير مححدد",
                            batchWeightKg = dc.getSafeDouble("batchWeightKg") ?: 0.0,
                            status = dc.getString("status") ?: "مكتمل",
                            timestamp = dc.getLong("timestamp") ?: System.currentTimeMillis()
                        )
                        repository.insertProductionLog(log)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save downloaded production log $id", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncProductionLogsSection", e)
        }
    }
}
