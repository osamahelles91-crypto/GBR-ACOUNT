package com.example.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

object DeviceSecurityManager {
    private const val TAG = "DeviceSecurity"
    private const val PREFS_NAME = "gbr_device_security_prefs"
    
    // Status states
    const val STATUS_CHECKING = "CHECKING"
    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_PENDING = "PENDING"
    const val STATUS_SUSPENDED = "SUSPENDED"
    const val STATUS_BLOCKED = "BLOCKED"
    const val STATUS_OFFLINE_EXPIRED = "OFFLINE_EXPIRED"

    private val _deviceStatus = MutableStateFlow(STATUS_CHECKING)
    val deviceStatus = _deviceStatus.asStateFlow()

    private val _isPrimary = MutableStateFlow(false)
    val isPrimary = _isPrimary.asStateFlow()

    // Admin codes are never stored in clear text nor displayed: only SHA-256 digests are kept in the app.
    private const val MASTER_CODE_HASH = "3fedc522064fbe97dc563df0ae9dd1ad1b10b075138cb9e1746c046e2a7df529"
    private const val OFFLINE_CODE_HASH = "02a0f7c919c092ddefb1cbf9142246702fc8cb20071f210a521d2444aafc34db"

    fun isMasterCode(input: String): Boolean = hashString(input.trim().uppercase()) == MASTER_CODE_HASH
    fun isOfflineExtensionCode(input: String): Boolean = hashString(input.trim().uppercase()) == OFFLINE_CODE_HASH

    data class PendingDeviceRegistration(
        val deviceId: String,
        val deviceName: String,
        val registeredAt: String,
        val model: String = "",
        val manufacturer: String = ""
    )

    private val _pendingDevices = MutableStateFlow<List<PendingDeviceRegistration>>(emptyList())
    val pendingDevices = _pendingDevices.asStateFlow()

    private var adminListenerJob: kotlinx.coroutines.Job? = null
    private var snapshotRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var onPendingDeviceCallback: ((PendingDeviceRegistration) -> Unit)? = null
    private val notifiedPendingDeviceIds = Collections.synchronizedSet(mutableSetOf<String>())

    private var appContext: Context? = null
    private var currentDeviceListenerReg: com.google.firebase.firestore.ListenerRegistration? = null
    private var backgroundStatusJob: kotlinx.coroutines.Job? = null
    private var statusChangeCallback: ((String) -> Unit)? = null

    private var deviceId: String = ""
    private var deviceName: String = ""

    val TRUSTED_RECOVERY_EMAILS = listOf("osama_helles@hotmail.com", "osamahelles1991@gmail.com", "osama.helles91@gmail.com")

    suspend fun generateAndSendSecurityCode(context: Context, targetEmail: String, operationType: String): String? {
        val isConnected = SyncManager.isNetworkAvailable(context)
        if (!isConnected) return null
        if (!SyncManager.initializeFirebase(context)) return null

        return try {
            val chars = "0123456789"
            val rand = Random()
            val codeSb = java.lang.StringBuilder()
            for (i in 0 until 6) {
                codeSb.append(chars[rand.nextInt(chars.length)])
            }
            val rawCode = codeSb.toString()
            val hashedCode = hashString(rawCode)
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val db = FirebaseFirestore.getInstance()
            val batch = db.batch()

            val securityRef = db.collection("settings").document("device_security")
            val securityUpdates = hashMapOf<String, Any>(
                "currentCodeHash" to hashedCode,
                "currentCodeTargetEmail" to targetEmail,
                "currentCodeCreatedAt" to nowStr,
                "currentCodeType" to operationType
            )
            batch.set(securityRef, securityUpdates, SetOptions.merge())

            val operationAr = when (operationType) {
                "RECOVER_ADMIN" -> "استرداد صلاحيات المسؤول الأول للنظام"
                "REGENERATE_KEY" -> "إعادة إنشاء مفتاح الاسترداد الآمن (Master Key)"
                "CHANGE_EMAIL" -> "تغيير البريد الإلكتروني الأمني المعتمد"
                "TRANSFER_ADMIN" -> "نقل صلاحيات المسؤول الرئيسي إلى جهاز جديد"
                else -> "عملية أمنية حساسة"
            }

            // Writing to Firestore "mail" collection disabled completely per user request to remove all mail/email functionalities.
            // The system logs and local UI will be used instead.
            /*
            val mailRef = db.collection("mail").document()
            val mailPayload = hashMapOf(
                "to" to targetEmail,
                "message" to hashMapOf(
                    "subject" to "🔑 دهانات GBR - رمز الأمان والاسترداد الحساس",
                    "text" to """
                        مرحباً مسؤول نظام دهانات GBR،
                        
                        تم طلب رمز أمان لتنفيذ العملية الحساسة التالية:
                        🏷️ العمليّة: $operationAr
                        📱 الجهاز: ${getDeviceName()} (ID: ${getDeviceId()})
                        ⏰ الوقت: $nowStr
                        
                        🔑 رمز الأمان الخاص بك هو:
                        [$rawCode]
                        
                        يرجى إدخال هذا الرمز في التطبيق لإتمام العملية الأمنية. يرجى حفظ هذا الرمز وأرشفته لأغراض التدقيق الفني.
                        
                        حماية وأمن دهانات GBR.
                    """.trimIndent(),
                    "html" to """
                        <div style="direction: rtl; text-align: right; font-family: sans-serif; padding: 20px; border: 1px solid #e5e7eb; border-radius: 12px; background-color: #f9fafb;">
                            <h2 style="color: #1e3a8a; border-bottom: 2px solid #3b82f6; padding-bottom: 8px;">🔑 دهانات GBR - رمز الأمان والاسترداد الحساس</h2>
                            <p style="font-size: 14px; color: #374151;">مرحباً مسؤول نظام دهانات GBR،</p>
                            <p style="font-size: 14px; color: #374151;">تم طلب رمز أمان لتنفيذ العملية الحساسة التالية:</p>
                            <div style="background-color: #f3f4f6; padding: 12px; border-radius: 8px; margin: 15px 0;">
                                <p style="margin: 4px 0; font-size: 14px;"><strong>🏷️ العمليّة:</strong> <span style="color: #2563eb;">$operationAr</span></p>
                                <p style="margin: 4px 0; font-size: 14px;"><strong>📱 الجهاز:</strong> ${getDeviceName()}</p>
                                <p style="margin: 4px 0; font-size: 12px; color: #6b7280;"><strong>🆔 معرف الجهاز:</strong> ${getDeviceId()}</p>
                                <p style="margin: 4px 0; font-size: 14px;"><strong>⏰ الوقت:</strong> $nowStr</p>
                            </div>
                            <p style="font-size: 14px; color: #374151;">🔑 رمز الأمان الخاص بك هو:</p>
                            <div style="text-align: center; margin: 20px 0;">
                                <span style="font-size: 32px; font-weight: bold; letter-spacing: 4px; color: #1e3a8a; background-color: #eff6ff; padding: 10px 30px; border: 1.5px dashed #2563eb; border-radius: 8px; display: inline-block;">$rawCode</span>
                            </div>
                            <p style="font-size: 13px; color: #ef4444; font-weight: bold;">⚠️ تنبيه أمني: لا تشارك هذا الرمز مع أي شخص للمحافظة على أمن المصنع والتركيبات.</p>
                            <p style="font-size: 12px; color: #6b7280; margin-top: 20px; border-top: 1px solid #e5e7eb; padding-top: 8px;">إدارة أمن دهانات GBR.</p>
                        </div>
                    """.trimIndent()
                )
            )
            batch.set(mailRef, mailPayload)
            */

            batch.commit().awaitTask()
            rawCode
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate security code", e)
            null
        }
    }

    suspend fun verifySecurityCode(context: Context, inputCode: String, operationType: String): Boolean {
        val isConnected = SyncManager.isNetworkAvailable(context)
        if (!isConnected) return false
        if (!SyncManager.initializeFirebase(context)) return false

        return try {
            val db = FirebaseFirestore.getInstance()
            val securityDoc = db.collection("settings").document("device_security").get().awaitTask()
            if (!securityDoc.exists()) return false

            val savedHash = securityDoc.getString("currentCodeHash") ?: ""
            val savedType = securityDoc.getString("currentCodeType") ?: ""

            if (savedHash.isEmpty() || savedType != operationType) {
                return false
            }

            val inputHash = hashString(inputCode.trim())
            if (savedHash == inputHash) {
                db.collection("settings").document("device_security")
                    .update(
                        "currentCodeHash", "",
                        "currentCodeType", ""
                    ).awaitTask()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify security code", e)
            false
        }
    }

    // A new Primary takes over: every other primary device goes back to PENDING (awaiting approval by the new Primary)
    private suspend fun demoteOtherPrimariesToPending(db: FirebaseFirestore, myId: String) {
        try {
            val primaries = db.collection("device_registrations").whereEqualTo("isPrimary", true).get().awaitTask()
            for (doc in primaries.documents) {
                if (doc.id != myId) {
                    db.collection("device_registrations").document(doc.id)
                        .update("isPrimary", false, "status", STATUS_PENDING).awaitTask()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to demote previous primary devices", e)
        }
    }

    suspend fun recoverAdminWithEmailCode(context: Context, inputCode: String): Boolean {
        val isConnected = SyncManager.isNetworkAvailable(context)
        if (!isConnected) return false
        if (!SyncManager.initializeFirebase(context)) return false

        val verified = verifySecurityCode(context, inputCode, "RECOVER_ADMIN")
        if (!verified) return false

        return try {
            val db = FirebaseFirestore.getInstance()
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            // 1. Demote any other primary devices
            val allDevices = db.collection("device_registrations").get().awaitTask()
            for (doc in allDevices.documents) {
                val docId = doc.id
                val wasPrimary = doc.getBoolean("isPrimary") ?: false
                if (wasPrimary && docId != deviceId) {
                    db.collection("device_registrations").document(docId)
                        .update(
                            "isPrimary", false,
                            "status", STATUS_PENDING
                        ).awaitTask()
                }
            }

            // 2. Write/Update this device payload as approved & primary
            val devicePayload = hashMapOf(
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "registeredAt" to nowStr,
                "lastActive" to nowStr,
                "status" to STATUS_APPROVED,
                "isPrimary" to true
            )
            db.collection("device_registrations").document(deviceId).set(devicePayload, SetOptions.merge()).awaitTask()

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("cached_status", STATUS_APPROVED)
                .putLong("last_verified_timestamp", System.currentTimeMillis())
                .putBoolean("is_primary", true)
                .apply()

            _isPrimary.value = true
            _deviceStatus.value = STATUS_APPROVED
            Log.i(TAG, "Device successfully recovered system and promoted to Primary Admin via email code!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Promotion failed", e)
            false
        }
    }

    fun isCloudConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences("gbr_sync_prefs", Context.MODE_PRIVATE)
        val userProjectId = prefs.getString("fb_project_id", null)?.trim()
        val userApiKey = prefs.getString("fb_api_key", null)?.trim()
        val userAppId = prefs.getString("fb_app_id", null)?.trim()
        return !userProjectId.isNullOrBlank() && !userApiKey.isNullOrBlank() && !userAppId.isNullOrBlank()
    }

    fun initialize(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // 1. Get or generate persistent Device ID
        var savedId = prefs.getString("device_id", null)
        if (savedId.isNullOrEmpty()) {
            savedId = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", savedId).apply()
        }
        deviceId = savedId

        // 2. Determine device name
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        deviceName = if (model.isNotBlank() || manufacturer.isNotBlank()) {
            "$manufacturer $model".trim()
        } else {
            "هاتف أندرويد"
        }
        
        // 3. Read cached security state for offline work
        val cachedStatus = prefs.getString("cached_status", STATUS_CHECKING) ?: STATUS_CHECKING
        val lastVerified = prefs.getLong("last_verified_timestamp", 0L)
        val isPrimaryVal = prefs.getBoolean("is_primary", false)
        _isPrimary.value = isPrimaryVal
        
        Log.d(TAG, "Initialized. ID: $deviceId, Name: $deviceName, CachedStatus: $cachedStatus, LastVerified: $lastVerified, IsPrimary: $isPrimaryVal")
        
        // Let's decide current status
        if (!isCloudConfigured(ctx)) {
            _deviceStatus.value = STATUS_APPROVED
        } else if (cachedStatus == STATUS_APPROVED) {
            // Strict 48-hour offline limit: applies to ALL devices without exception (including Primary Admin)
            val currentTime = System.currentTimeMillis()
            val timeDiff = currentTime - lastVerified
            val gracePeriodMs = 48 * 60 * 60 * 1000L // 48 hours
            
            if (timeDiff <= gracePeriodMs) {
                _deviceStatus.value = STATUS_APPROVED
            } else {
                _deviceStatus.value = STATUS_OFFLINE_EXPIRED
            }
        } else {
            _deviceStatus.value = cachedStatus
        }
    }

    fun updateDeviceStatusLocally(context: Context, status: String, isPrimaryVal: Boolean = false) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("cached_status", status)
            .putLong("last_verified_timestamp", System.currentTimeMillis())
            .putBoolean("is_primary", isPrimaryVal)
            .apply()

        _isPrimary.value = isPrimaryVal
        _deviceStatus.value = status

        if (status == STATUS_SUSPENDED) {
            SyncManager.stopRealtimeListeners()
        } else if (status == STATUS_BLOCKED) {
            SyncManager.stopRealtimeListeners()
            wipeDeviceData(ctx)
        }
    }

    fun getDeviceId(context: Context? = null): String {
        if (deviceId.isNotEmpty()) return deviceId
        val ctx = (context ?: appContext)?.applicationContext
        if (ctx != null) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var savedId = prefs.getString("device_id", null)
            if (savedId.isNullOrEmpty()) {
                savedId = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", savedId).apply()
            }
            deviceId = savedId
            return deviceId
        }
        return deviceId.ifEmpty { "device_default" }
    }

    fun getDeviceName(context: Context? = null): String {
        if (deviceName.isNotEmpty()) return deviceName
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        deviceName = if (model.isNotBlank() || manufacturer.isNotBlank()) {
            "$manufacturer $model".trim()
        } else {
            "هاتف أندرويد"
        }
        return deviceName
    }

    fun startRealtimeStatusListener(context: Context, onStatusChanged: ((String) -> Unit)? = null) {
        val ctx = context.applicationContext
        appContext = ctx
        if (onStatusChanged != null) {
            statusChangeCallback = onStatusChanged
        }

        if (!isCloudConfigured(ctx)) {
            Log.d(TAG, "startRealtimeStatusListener: Cloud not configured")
            return
        }

        if (!SyncManager.initializeFirebase(ctx)) {
            Log.w(TAG, "startRealtimeStatusListener: Firebase initialization failed")
            return
        }

        currentDeviceListenerReg?.remove()
        currentDeviceListenerReg = null
        backgroundStatusJob?.cancel()
        backgroundStatusJob = null

        val myId = getDeviceId(ctx)
        Log.i(TAG, "Starting realtime security listener on Firestore for device ID: $myId")
        val db = FirebaseFirestore.getInstance()

        try {
            currentDeviceListenerReg = db.collection("device_registrations").document(myId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Device status snapshot listener error", error)
                        return@addSnapshotListener
                    }
                    val sharedPrefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val cachedStatus = sharedPrefs.getString("cached_status", STATUS_CHECKING)
                    val cachedPrimary = sharedPrefs.getBoolean("is_primary", false)

                    if (snapshot != null && snapshot.exists()) {
                        val rawStatus = snapshot.getString("status") ?: STATUS_PENDING
                        val remoteIsPrimary = snapshot.getBoolean("isPrimary") ?: false

                        val normalizedStatus = when {
                            rawStatus.equals(STATUS_APPROVED, ignoreCase = true) || rawStatus == "معتمد" -> STATUS_APPROVED
                            rawStatus.equals(STATUS_BLOCKED, ignoreCase = true) || rawStatus == "محظور" -> STATUS_BLOCKED
                            rawStatus.equals(STATUS_SUSPENDED, ignoreCase = true) || rawStatus == "موقوف" -> STATUS_SUSPENDED
                            else -> STATUS_PENDING
                        }

                        if (normalizedStatus != cachedStatus || remoteIsPrimary != cachedPrimary || normalizedStatus != _deviceStatus.value) {
                            Log.i(TAG, "Device security status changed in real-time -> $normalizedStatus (was: $cachedStatus)")
                            updateDeviceStatusLocally(ctx, normalizedStatus, remoteIsPrimary)
                            statusChangeCallback?.invoke(normalizedStatus)
                        }
                    } else if (snapshot != null && !snapshot.exists()) {
                        if (!cachedPrimary && !_isPrimary.value) {
                            // Only block and wipe if device was actively APPROVED before deletion!
                            // If it's PENDING or re-registering, a missing doc means it's awaiting registration.
                            if (_deviceStatus.value == STATUS_APPROVED) {
                                Log.i(TAG, "Active device registration deleted by admin from Firestore. Setting BLOCKED status.")
                                updateDeviceStatusLocally(ctx, STATUS_BLOCKED, false)
                                statusChangeCallback?.invoke(STATUS_BLOCKED)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting Firestore snapshot listener", e)
        }

        // Periodic check: local offline check every 3s (0 network cost), cloud verification every 5 min (30s if not approved)
        backgroundStatusJob = CoroutineScope(Dispatchers.IO).launch {
            var lastCloudCheckTime = 0L
            while (isActive) {
                kotlinx.coroutines.delay(3000)
                if (isCloudConfigured(ctx)) {
                    try {
                        val prevStatus = _deviceStatus.value
                        val now = System.currentTimeMillis()
                        val isApproved = (prevStatus == STATUS_APPROVED)
                        val cloudInterval = if (isApproved) (5 * 60 * 1000L) else (30 * 1000L)
                        val shouldCheckCloud = SyncManager.isNetworkAvailable(ctx) && (now - lastCloudCheckTime >= cloudInterval)

                        val newStatus = if (shouldCheckCloud) {
                            lastCloudCheckTime = now
                            verifyDeviceStatus(ctx, forceOfflineCheck = false)
                        } else {
                            verifyDeviceStatus(ctx, forceOfflineCheck = true)
                        }
                        if (newStatus != prevStatus) {
                            statusChangeCallback?.invoke(newStatus)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stopRealtimeStatusListener() {
        currentDeviceListenerReg?.remove()
        currentDeviceListenerReg = null
        backgroundStatusJob?.cancel()
        backgroundStatusJob = null
    }

    fun isEmulator(): Boolean {
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""
        val fingerprint = Build.FINGERPRINT ?: ""
        val hardware = Build.HARDWARE ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val product = Build.PRODUCT ?: ""
        
        return (brand.startsWith("generic") && device.startsWith("generic"))
                || fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || manufacturer.contains("Genymotion")
                || product.contains("sdk_google")
                || product.contains("google_sdk")
                || product.contains("sdk")
                || product.contains("sdk_x86")
                || product.contains("vbox86p")
                || product.contains("emulator")
                || product.contains("simulator")
    }

    // Dedicated function to explicitly submit an authorization/approval request to the admin
    suspend fun sendRegistrationRequestToCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!SyncManager.initializeFirebase(ctx)) {
            Log.w(TAG, "Cannot send registration request: Firebase not initialized")
            return@withContext false
        }
        return@withContext try {
            withTimeout(10000L) {
                try {
                    val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    if (auth.currentUser == null) {
                        auth.signInAnonymously().awaitTask()
                    }
                } catch (authEx: Exception) {
                    Log.w(TAG, "Anonymous auth skipped/failed: ${authEx.message}")
                }

                val db = FirebaseFirestore.getInstance()
                val myId = getDeviceId(ctx)
                val myName = getDeviceName(ctx)
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val devicePayload = hashMapOf<String, Any>(
                    "deviceId" to myId,
                    "deviceName" to myName,
                    "registeredAt" to nowStr,
                    "lastActive" to nowStr,
                    "status" to STATUS_PENDING,
                    "isPrimary" to false,
                    "model" to (Build.MODEL ?: ""),
                    "manufacturer" to (Build.MANUFACTURER ?: "")
                )

                // Overwrite device registration to PENDING on cloud (replaces any previous blocked/deleted/suspended status)
                db.collection("device_registrations").document(myId)
                    .set(devicePayload).awaitTask()

                // Insert/activate operational alert for admin so admin phone gets immediate alert
                try {
                    val alertPayload = hashMapOf<String, Any>(
                        "id" to "device_alert_$myId",
                        "title" to "جهاز جديد يطلب المزامنة 🔌",
                        "description" to "تم رصد جهاز جديد يتصل بالنظام ويطلب الاعتماد: $myName",
                        "mainSection" to "عام",
                        "bindingScope" to "ALL",
                        "alertLevel" to "WARNING",
                        "status" to "ACTIVE",
                        "createdAt" to System.currentTimeMillis(),
                        "targetDeviceId" to myId
                    )
                    db.collection("operational_alerts").document("device_alert_$myId")
                        .set(alertPayload, SetOptions.merge()).awaitTask()
                } catch (ae: Exception) {
                    Log.e(TAG, "Failed inserting operational alert on Firestore", ae)
                }

                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("cached_status", STATUS_PENDING)
                    .putLong("last_verified_timestamp", System.currentTimeMillis())
                    .putBoolean("is_primary", false)
                    .apply()

                _isPrimary.value = false
                _deviceStatus.value = STATUS_PENDING
                notifiedPendingDeviceIds.remove(myId)
                Log.i(TAG, "sendRegistrationRequestToCloud successfully sent for $myId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in sendRegistrationRequestToCloud", e)
            false
        }
    }

    // Ensure device is written to Firestore and registered
    suspend fun ensureDeviceRegisteredOnCloud(context: Context, forceStatus: String? = null): Boolean = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        appContext = ctx
        if (!SyncManager.initializeFirebase(ctx)) {
            Log.w(TAG, "Cannot ensure registration: Firebase not initialized")
            return@withContext false
        }
        return@withContext try {
            withTimeout(10000L) {
                // Attempt anonymous auth if enabled in project rules
                try {
                    val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                    if (auth.currentUser == null) {
                        auth.signInAnonymously().awaitTask()
                    }
                } catch (authEx: Exception) {
                    Log.w(TAG, "Anonymous auth skipped/failed: ${authEx.message}")
                }

                val db = FirebaseFirestore.getInstance()
                val myId = getDeviceId(ctx)
                val myName = getDeviceName(ctx)
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val isLocalPrimary = prefs.getBoolean("is_primary", false)
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val docRef = db.collection("device_registrations").document(myId)
                val docSnap = try { docRef.get().awaitTask() } catch (e: Exception) { null }

                var targetStatus: String
                var targetIsPrimary: Boolean

                if (docSnap != null && docSnap.exists()) {
                    val remoteStatus = (docSnap.getString("status") ?: STATUS_PENDING).trim()
                    val remoteIsPrimary = docSnap.getBoolean("isPrimary") ?: false
                    val hasDeletedAt = docSnap.getString("deletedAt") != null

                    val isBlocked = remoteStatus.equals(STATUS_BLOCKED, ignoreCase = true) ||
                                    remoteStatus.equals("BLOCKED", ignoreCase = true) ||
                                    remoteStatus.equals("DELETED", ignoreCase = true) ||
                                    remoteStatus == "محظور" ||
                                    remoteStatus == "محذوف"
                    val isSuspended = remoteStatus.equals(STATUS_SUSPENDED, ignoreCase = true) ||
                                      remoteStatus.equals("SUSPENDED", ignoreCase = true) ||
                                      remoteStatus == "موقوف"
                    val isApproved = remoteStatus.equals(STATUS_APPROVED, ignoreCase = true) ||
                                     remoteStatus.equals("APPROVED", ignoreCase = true) ||
                                     remoteStatus == "معتمد"

                    val normalizedStatus = when {
                        // If device was previously deleted, treat connection as fresh PENDING request
                        hasDeletedAt || remoteStatus.equals("DELETED", ignoreCase = true) -> STATUS_PENDING
                        isApproved -> STATUS_APPROVED
                        isSuspended -> STATUS_SUSPENDED
                        isBlocked -> STATUS_BLOCKED
                        else -> STATUS_PENDING
                    }

                    targetStatus = forceStatus ?: normalizedStatus
                    // Cloud document is the source of truth: a stale local primary flag may only re-claim the role when no OTHER device is primary
                    targetIsPrimary = if (remoteIsPrimary) {
                        true
                    } else if (isLocalPrimary) {
                        val otherPrimary = try {
                            db.collection("device_registrations").whereEqualTo("isPrimary", true).get().awaitTask()
                                .documents.any { it.id != myId }
                        } catch (e: Exception) { false }
                        !otherPrimary
                    } else false
                } else {
                    if (isLocalPrimary) {
                        targetStatus = forceStatus ?: STATUS_APPROVED
                        targetIsPrimary = true
                    } else {
                        // Check if other devices exist on Firestore
                        val allDevicesSnap = try { db.collection("device_registrations").limit(5).get().awaitTask() } catch (e: Exception) { null }
                        val hasExistingOtherDevices = allDevicesSnap != null && allDevicesSnap.documents.any { it.id != myId }
                        val secDocSnap = try { db.collection("settings").document("device_security").get().awaitTask() } catch (e: Exception) { null }
                        val hasSystemSecurity = secDocSnap != null && secDocSnap.exists()

                        if (!hasExistingOtherDevices && !hasSystemSecurity) {
                            // Very first device setup: make primary admin
                            targetStatus = STATUS_APPROVED
                            targetIsPrimary = true
                            val secPayload = hashMapOf<String, Any>(
                                "recoveryKey" to MASTER_CODE_HASH,
                                "activeSecurityEmail" to "osama.helles91@gmail.com",
                                "setupAt" to nowStr
                            )
                            try {
                                db.collection("settings").document("device_security").set(secPayload, SetOptions.merge()).awaitTask()
                            } catch (_: Exception) {}
                        } else {
                            targetStatus = forceStatus ?: STATUS_PENDING
                            targetIsPrimary = false
                        }
                    }
                }

                val devicePayload = hashMapOf<String, Any>(
                    "deviceId" to myId,
                    "deviceName" to myName,
                    "registeredAt" to (docSnap?.getString("registeredAt") ?: nowStr),
                    "lastActive" to nowStr,
                    "status" to targetStatus,
                    "isPrimary" to targetIsPrimary,
                    "model" to (Build.MODEL ?: ""),
                    "manufacturer" to (Build.MANUFACTURER ?: "")
                )
                if (targetStatus == STATUS_APPROVED) {
                    devicePayload["approvedAt"] = docSnap?.getString("approvedAt") ?: nowStr
                }

                db.collection("device_registrations").document(myId).set(devicePayload, SetOptions.merge()).awaitTask()

                if (targetStatus == STATUS_PENDING) {
                    try {
                        val alertPayload = hashMapOf<String, Any>(
                            "id" to "device_alert_$myId",
                            "title" to "جهاز جديد يطلب المزامنة 🔌",
                            "description" to "تم رصد جهاز جديد يتصل بالنظام ويطلب الاعتماد: $myName",
                            "mainSection" to "عام",
                            "bindingScope" to "ALL",
                            "alertLevel" to "WARNING",
                            "status" to "ACTIVE",
                            "createdAt" to System.currentTimeMillis(),
                            "targetDeviceId" to myId
                        )
                        db.collection("operational_alerts").document("device_alert_$myId").set(alertPayload, SetOptions.merge()).awaitTask()
                    } catch (ae: Exception) {
                        Log.e(TAG, "Failed inserting operational alert on Firestore", ae)
                    }
                } else if (targetStatus == STATUS_APPROVED) {
                    try {
                        db.collection("operational_alerts").document("device_alert_$myId").delete().awaitTask()
                    } catch (_: Exception) {}
                }

                if (targetStatus == STATUS_BLOCKED) {
                    wipeDeviceData(ctx)
                }

                prefs.edit()
                    .putString("cached_status", targetStatus)
                    .putLong("last_verified_timestamp", System.currentTimeMillis())
                    .putBoolean("is_primary", targetIsPrimary)
                    .apply()

                _isPrimary.value = targetIsPrimary
                _deviceStatus.value = targetStatus
                Log.i(TAG, "ensureDeviceRegisteredOnCloud completed successfully for $myId with status $targetStatus")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in ensureDeviceRegisteredOnCloud", e)
            false
        }
    }

    // Refresh device status from Firestore
    suspend fun verifyDeviceStatus(context: Context, forceOfflineCheck: Boolean = false): String {
        if (deviceId.isEmpty() || deviceName.isEmpty()) {
            initialize(context)
        }
        if (deviceId.isEmpty()) {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var savedId = p.getString("device_id", null)
            if (savedId.isNullOrEmpty()) {
                savedId = UUID.randomUUID().toString()
                p.edit().putString("device_id", savedId).apply()
            }
            deviceId = savedId
        }
        if (deviceName.isEmpty()) {
            val model = Build.MODEL ?: ""
            val manufacturer = Build.MANUFACTURER ?: ""
            deviceName = if (model.isNotBlank() || manufacturer.isNotBlank()) {
                "$manufacturer $model".trim()
            } else {
                "هاتف أندرويد"
            }
        }

        if (!isCloudConfigured(context)) {
            _deviceStatus.value = STATUS_APPROVED
            return STATUS_APPROVED
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isConnected = SyncManager.isNetworkAvailable(context)
        
        if (!isConnected || forceOfflineCheck) {
            // Offline Mode logic - applies to ALL devices (including Primary Admin) to protect formulations if stolen offline
            val cachedStatus = prefs.getString("cached_status", STATUS_CHECKING) ?: STATUS_CHECKING
            val lastVerified = prefs.getLong("last_verified_timestamp", 0L)
            val isPrimaryVal = prefs.getBoolean("is_primary", false)
            _isPrimary.value = isPrimaryVal
            
            if (cachedStatus == STATUS_APPROVED) {
                val currentTime = System.currentTimeMillis()
                val gracePeriodMs = 48 * 60 * 60 * 1000L // 48 hours
                if (currentTime - lastVerified <= gracePeriodMs) {
                    _deviceStatus.value = STATUS_APPROVED
                } else {
                    _deviceStatus.value = STATUS_OFFLINE_EXPIRED
                }
            } else if (cachedStatus == STATUS_CHECKING) {
                _deviceStatus.value = STATUS_PENDING
                prefs.edit().putString("cached_status", STATUS_PENDING).apply()
            } else {
                _deviceStatus.value = cachedStatus
            }
            return _deviceStatus.value
        }

        // Online check
        if (!SyncManager.initializeFirebase(context)) {
            Log.w(TAG, "Could not initialize Firebase during device verification. Falling back to offline check.")
            return verifyDeviceStatus(context, forceOfflineCheck = true)
        }

        return try {
            kotlinx.coroutines.withTimeout(15000) {
                val db = FirebaseFirestore.getInstance()
                val docRef = db.collection("device_registrations").document(deviceId)
                val docSnap = docRef.get().awaitTask()

                val isLocalPrimary = prefs.getBoolean("is_primary", false)

                if (!docSnap.exists()) {
                    ensureDeviceRegisteredOnCloud(context)
                    _deviceStatus.value
                } else {
                    // Device exists in cloud
                    val rawStatus = docSnap.getString("status") ?: STATUS_PENDING
                    var isPrimary = docSnap.getBoolean("isPrimary") ?: false
                    
                    var status = when {
                        rawStatus.equals(STATUS_APPROVED, ignoreCase = true) || rawStatus == "معتمد" -> STATUS_APPROVED
                        rawStatus.equals(STATUS_BLOCKED, ignoreCase = true) || rawStatus == "محظور" || rawStatus.equals("DELETED", ignoreCase = true) -> STATUS_BLOCKED
                        rawStatus.equals(STATUS_SUSPENDED, ignoreCase = true) || rawStatus == "موقوف" -> STATUS_SUSPENDED
                        else -> STATUS_PENDING
                    }
                    
                    if (isLocalPrimary && (!isPrimary || status != STATUS_APPROVED)) {
                        if (status == STATUS_BLOCKED) {
                            isPrimary = false
                        } else {
                            status = STATUS_APPROVED
                            isPrimary = true
                            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                            db.collection("device_registrations").document(deviceId)
                                .set(hashMapOf(
                                    "status" to STATUS_APPROVED,
                                    "isPrimary" to true,
                                    "lastActive" to nowStr,
                                    "deviceName" to deviceName,
                                    "deviceId" to deviceId
                                ), SetOptions.merge()).awaitTask()
                        }
                    } else {
                        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        docRef.set(hashMapOf(
                            "lastActive" to nowStr,
                            "deviceName" to deviceName,
                            "deviceId" to deviceId
                        ), SetOptions.merge()).awaitTask()
                    }

                    prefs.edit()
                        .putString("cached_status", status)
                        .putLong("last_verified_timestamp", System.currentTimeMillis())
                        .putBoolean("is_primary", isPrimary)
                        .apply()

                    if (status == STATUS_BLOCKED) {
                        wipeDeviceData(context)
                    }

                    _isPrimary.value = isPrimary
                    _deviceStatus.value = status
                    status
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device status on cloud or timed out", e)
            verifyDeviceStatus(context, forceOfflineCheck = true)
        }
    }

    // Generate secure 16-char code
    private fun generateSecureRecoveryKey(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Easy to read, excludes ambiguous characters (I, O, 1, 0)
        val rand = Random()
        val sb = StringBuilder()
        for (i in 0 until 16) {
            if (i > 0 && i % 4 == 0) sb.append("-")
            sb.append(chars[rand.nextInt(chars.length)])
        }
        return "GBR-$sb"
    }

    // Recover device using Recovery Key
    suspend fun recoverSystem(context: Context, rawRecoveryKey: String): Boolean {
        val trimmedKey = rawRecoveryKey.trim().uppercase()
        val isFallbackMasterKey = isMasterCode(trimmedKey)

        val currentDevId = getDeviceId(context)
        val currentDevName = getDeviceName(context)

        // 1. If it is the fallback master recovery key, approve LOCALLY immediately to prevent any lockout!
        if (isFallbackMasterKey) {
            Log.i(TAG, "Fallback master key entered! Approving device locally first to guarantee immediate access.")
            
            // Save locally as Primary Admin
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("cached_status", STATUS_APPROVED)
                .putLong("last_verified_timestamp", System.currentTimeMillis())
                .putBoolean("is_primary", true)
                .apply()
            
            _isPrimary.value = true
            _deviceStatus.value = STATUS_APPROVED
            
            // 2. Try to sync to the cloud in the background asynchronously so we never block the UI or fail on bad connections
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    if (isCloudConfigured(context) && SyncManager.isNetworkAvailable(context) && SyncManager.initializeFirebase(context)) {
                        val db = FirebaseFirestore.getInstance()
                        val expectedHash = MASTER_CODE_HASH
                        
                        // Set/heal the settings/device_security document
                        db.collection("settings").document("device_security")
                            .set(hashMapOf("recoveryKey" to expectedHash), SetOptions.merge())
                            .awaitTask()
                        
                        demoteOtherPrimariesToPending(db, currentDevId)

                        // Update this device registration as APPROVED and PRIMARY safely
                        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        val devicePayload = hashMapOf(
                            "deviceId" to currentDevId,
                            "deviceName" to currentDevName,
                            "registeredAt" to nowStr,
                            "lastActive" to nowStr,
                            "status" to STATUS_APPROVED,
                            "isPrimary" to true,
                            "approvedAt" to nowStr
                        )
                        db.collection("device_registrations").document(currentDevId).set(devicePayload, SetOptions.merge()).awaitTask()
                        
                        try {
                            db.collection("operational_alerts").document("device_alert_$currentDevId").delete().awaitTask()
                        } catch (_: Exception) {}
                        
                        Log.i(TAG, "Asynchronously propagated recovery status to Cloud Firestore as Primary Admin.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background cloud registration failed during recovery, but local approval remains active", e)
                }
            }

            return true
        }

        // 3. Standard online flow for any other custom recovery key
        val isConnected = SyncManager.isNetworkAvailable(context)
        if (!isConnected) return false
        
        if (!SyncManager.initializeFirebase(context)) return false

        return try {
            val db = FirebaseFirestore.getInstance()
            val securityDoc = db.collection("settings").document("device_security").get().awaitTask()
            
            val savedHash = if (securityDoc.exists()) securityDoc.getString("recoveryKey") ?: "" else ""
            val inputRecoveryHash = hashString(trimmedKey)
            
            // Only an input whose digest equals the stored digest is accepted (the master code itself is handled above)
            val isRecoveryKeyMatch = savedHash.isNotEmpty() && savedHash == inputRecoveryHash
            
            if (isRecoveryKeyMatch) {
                val expectedHash = MASTER_CODE_HASH
                if (savedHash != expectedHash) {
                    try {
                        db.collection("settings").document("device_security")
                            .set(hashMapOf("recoveryKey" to expectedHash), SetOptions.merge())
                            .awaitTask()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Failed to update recoveryKey hash on Firestore", ex)
                    }
                }
                
                demoteOtherPrimariesToPending(db, currentDevId)

                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                
                // Write/Update this device payload as approved & Primary Admin
                val devicePayload = hashMapOf(
                    "deviceId" to currentDevId,
                    "deviceName" to currentDevName,
                    "registeredAt" to nowStr,
                    "lastActive" to nowStr,
                    "status" to STATUS_APPROVED,
                    "isPrimary" to true,
                    "approvedAt" to nowStr
                )
                db.collection("device_registrations").document(currentDevId).set(devicePayload, SetOptions.merge()).awaitTask()
                
                try {
                    db.collection("operational_alerts").document("device_alert_$currentDevId").delete().awaitTask()
                } catch (_: Exception) {}

                // Save locally as Primary Admin
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("cached_status", STATUS_APPROVED)
                    .putLong("last_verified_timestamp", System.currentTimeMillis())
                    .putBoolean("is_primary", true)
                    .apply()
                
                _isPrimary.value = true
                _deviceStatus.value = STATUS_APPROVED
                Log.i(TAG, "Device successfully recovered and approved via recovery key as Primary Admin!")
                true
            } else {
                Log.w(TAG, "Recovery Key mismatch!")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed", e)
            false
        }
    }

    // Secure wipe of device data (Room DB and standard shared preferences)
    fun wipeDeviceData(context: Context) {
        try {
            Log.w(TAG, "🔴 SECURE WIPE TRIGGERED! Clearing Room Database and Application Caches to protect industrial secrets!")
            
            // 0. Stop realtime synchronization immediately
            try {
                SyncManager.stopRealtimeListeners()
            } catch (se: Exception) {
                Log.e(TAG, "Error stopping realtime listeners during wipe", se)
            }

            // 1. Clear Room database
            val db = AppDatabase.getDatabase(context)
            Thread {
                try {
                    db.clearAllTables()
                    Log.i(TAG, "Room Database tables cleared completely.")
                } catch (de: Exception) {
                    Log.e(TAG, "Error clearing tables", de)
                }
            }.start()
            
            // 2. Clear main Shared Preferences
            val prefsToClear = listOf(
                "gbr_prefs",
                "gbr_sync_prefs",
                "gbr_system_logs",
                "gbr_equipment_prefs",
                "gbr_user_prefs",
                "gbr_app_properties",
                "gbr_print_prefs",
                "gbr_sounds_prefs"
            )
            for (p in prefsToClear) {
                try {
                    context.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().apply()
                } catch (pe: Exception) {
                    Log.e(TAG, "Error clearing pref $p", pe)
                }
            }

            // Mark device status locally as BLOCKED
            val secPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            secPrefs.edit()
                .putString("cached_status", STATUS_BLOCKED)
                .putLong("last_verified_timestamp", System.currentTimeMillis())
                .putBoolean("is_primary", false)
                .apply()

            _isPrimary.value = false
            _deviceStatus.value = STATUS_BLOCKED
            
            Log.i(TAG, "All cache, database tables, and credentials wiped successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during secure wipe", e)
        }
    }

    suspend fun deleteDeviceFromCloud(context: Context, targetDeviceId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isCloudConfigured(context) || !SyncManager.isNetworkAvailable(context) || !SyncManager.initializeFirebase(context)) {
            return@withContext false
        }
        val cleanId = targetDeviceId.trim()
        if (cleanId.isBlank()) return@withContext false

        return@withContext try {
            withTimeout(7000L) {
                val db = FirebaseFirestore.getInstance()
                // Delete associated operational alert if any
                try {
                    db.collection("operational_alerts").document("device_alert_$cleanId").delete().awaitTask()
                } catch (ae: Exception) {
                    Log.e(TAG, "Failed deleting operational alert for $cleanId", ae)
                }

                // Delete the registration document from Firestore
                try {
                    db.collection("device_registrations").document(cleanId).delete().awaitTask()
                } catch (de: Exception) {
                    Log.e(TAG, "Failed deleting registration doc on Firestore: $cleanId", de)
                }

                _pendingDevices.value = _pendingDevices.value.filter { it.deviceId != cleanId }
                notifiedPendingDeviceIds.remove(cleanId)
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete device from cloud: $cleanId", e)
            false
        }
    }

    // Helper for hashing
    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun applyOfflineExtension(context: Context, code: String): Boolean {
        val cleanCode = code.trim().uppercase()
        if (isOfflineExtensionCode(cleanCode)) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_verified_timestamp", System.currentTimeMillis())
                .putString("cached_status", STATUS_APPROVED)
                .apply()
            _deviceStatus.value = STATUS_APPROVED
            return true
        }
        return false
    }

    suspend fun updateDeviceStatusOnCloud(context: Context, targetDeviceId: String, newStatus: String, isPrimaryVal: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!SyncManager.initializeFirebase(context)) {
            return@withContext false
        }
        val cleanId = targetDeviceId.trim()
        if (cleanId.isBlank()) return@withContext false

        return@withContext try {
            withTimeout(7000L) {
                val db = FirebaseFirestore.getInstance()
                val updates = hashMapOf<String, Any>(
                    "status" to newStatus,
                    "isPrimary" to isPrimaryVal
                )
                val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                updates["lastActive"] = nowStr
                when (newStatus) {
                    STATUS_APPROVED -> updates["approvedAt"] = nowStr
                    STATUS_SUSPENDED -> updates["suspendedAt"] = nowStr
                    STATUS_BLOCKED -> updates["blockedAt"] = nowStr
                }
                db.collection("device_registrations").document(cleanId)
                    .set(updates, SetOptions.merge()).awaitTask()
                
                // Clean up operational alert if no longer pending
                if (newStatus != STATUS_PENDING) {
                    try {
                        db.collection("operational_alerts").document("device_alert_$cleanId").delete().awaitTask()
                    } catch (ae: Exception) {
                        Log.e(TAG, "Failed deleting operational alert for $cleanId", ae)
                    }
                }
                // Update local StateFlow
                _pendingDevices.value = _pendingDevices.value.filter { it.deviceId != cleanId }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update device status on cloud for $cleanId", e)
            false
        }
    }

    suspend fun approveAllPendingDevices(context: Context): Int {
        if (!SyncManager.initializeFirebase(context)) return 0
        val list = _pendingDevices.value
        var approvedCount = 0
        list.forEach { dev ->
            val success = updateDeviceStatusOnCloud(context, dev.deviceId, STATUS_APPROVED)
            if (success) approvedCount++
        }
        return approvedCount
    }

    suspend fun approveDeviceManuallyById(context: Context, targetDeviceId: String, customName: String = ""): Boolean {
        if (!SyncManager.initializeFirebase(context)) return false
        val rawInput = targetDeviceId.trim()
        if (rawInput.isEmpty()) return false
        val cleanId = rawInput.replace("...", "").trim()
        
        return try {
            val db = FirebaseFirestore.getInstance()
            val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            // 1. Check all existing documents for exact or partial ID/name match
            var matchedAny = false
            try {
                val allDocs = db.collection("device_registrations").get().awaitTask()
                for (doc in allDocs.documents) {
                    val docId = doc.id
                    val storedDevId = (doc.getString("deviceId") ?: "").trim()
                    val storedDevName = (doc.getString("deviceName") ?: "").trim()
                    
                    val isMatch = docId.equals(cleanId, ignoreCase = true) ||
                            storedDevId.equals(cleanId, ignoreCase = true) ||
                            (cleanId.length >= 6 && docId.contains(cleanId, ignoreCase = true)) ||
                            (cleanId.length >= 6 && storedDevId.contains(cleanId, ignoreCase = true)) ||
                            (cleanId.length >= 4 && storedDevName.contains(cleanId, ignoreCase = true))
                    
                    if (isMatch) {
                        matchedAny = true
                        val resolvedName = if (customName.isNotBlank()) customName else storedDevName.ifEmpty { "هاتف معتمد" }
                        val updatePayload = hashMapOf<String, Any>(
                            "deviceId" to (storedDevId.ifEmpty { docId }),
                            "deviceName" to resolvedName,
                            "status" to STATUS_APPROVED,
                            "isPrimary" to false,
                            "approvedAt" to nowStr,
                            "lastActive" to nowStr
                        )
                        db.collection("device_registrations").document(docId).set(updatePayload, SetOptions.merge()).awaitTask()
                        
                        try {
                            db.collection("operational_alerts").document("device_alert_$docId").delete().awaitTask()
                        } catch (_: Exception) {}
                        if (storedDevId.isNotEmpty()) {
                            try {
                                db.collection("operational_alerts").document("device_alert_$storedDevId").delete().awaitTask()
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search in existing registrations encountered issue", e)
            }
            
            // 2. Also write direct record for cleanId
            val payload = hashMapOf<String, Any>(
                "deviceId" to cleanId,
                "deviceName" to (if (customName.isNotBlank()) customName else "هاتف معتمد"),
                "status" to STATUS_APPROVED,
                "isPrimary" to false,
                "approvedAt" to nowStr,
                "lastActive" to nowStr
            )
            db.collection("device_registrations").document(cleanId).set(payload, SetOptions.merge()).awaitTask()
            
            try {
                db.collection("operational_alerts").document("device_alert_$cleanId").delete().awaitTask()
            } catch (_: Exception) {}
            
            _pendingDevices.value = _pendingDevices.value.filter { it.deviceId != cleanId }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed manual device approval for $cleanId", e)
            false
        }
    }

    fun startAdminPendingDevicesListener(context: Context, onNewPendingDeviceDetected: ((PendingDeviceRegistration) -> Unit)? = null) {
        val ctx = context.applicationContext
        appContext = ctx
        if (onNewPendingDeviceDetected != null) {
            onPendingDeviceCallback = onNewPendingDeviceDetected
            // If we already have pending devices loaded, trigger callback for each
            _pendingDevices.value.forEach { p ->
                if (!notifiedPendingDeviceIds.contains(p.deviceId)) {
                    notifiedPendingDeviceIds.add(p.deviceId)
                    onNewPendingDeviceDetected(p)
                }
            }
        }
        if (adminListenerJob?.isActive == true) return
        adminListenerJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                try {
                    if (SyncManager.initializeFirebase(ctx)) {
                        val db = FirebaseFirestore.getInstance()
                        val myId = getDeviceId(ctx)
                        
                        if (snapshotRegistration == null) {
                            snapshotRegistration = db.collection("device_registrations")
                                .addSnapshotListener { snapshot, error ->
                                    if (error != null) {
                                        Log.e(TAG, "Snapshot error in admin pending listener", error)
                                        return@addSnapshotListener
                                    }
                                    if (snapshot != null) {
                                        val pendingList = mutableListOf<PendingDeviceRegistration>()
                                        val currentIds = mutableSetOf<String>()
                                        for (doc in snapshot.documents) {
                                            val st = (doc.getString("status") ?: "").trim()
                                            val id = doc.id
                                            val isBlocked = st.equals(STATUS_BLOCKED, ignoreCase = true) || 
                                                            st.equals("BLOCKED", ignoreCase = true) || 
                                                            st.equals("DELETED", ignoreCase = true) || 
                                                            st.equals("SUSPENDED", ignoreCase = true) || 
                                                            st.equals("CANCELLED", ignoreCase = true) || 
                                                            st == "محظور" || st == "محذوف" || st == "موقوف" || st == "ملغي"
                                            val isApproved = st.equals(STATUS_APPROVED, ignoreCase = true) || 
                                                             st.equals("APPROVED", ignoreCase = true) || 
                                                             st == "معتمد"
                                            val isPending = !isBlocked && !isApproved && (
                                                st.equals(STATUS_PENDING, ignoreCase = true) || 
                                                st.equals("pending", ignoreCase = true) || 
                                                st == "معلق" || 
                                                st == "بانتظار الاعتماد" ||
                                                st.isEmpty()
                                            )
                                            if (isBlocked) {
                                                try {
                                                    db.collection("operational_alerts").document("device_alert_$id").delete()
                                                } catch (_: Exception) {}
                                            } else if (isPending && id != myId && id.isNotBlank()) {
                                                val name = doc.getString("deviceName") ?: "هاتف أندرويد"
                                                val regAt = doc.getString("registeredAt") ?: ""
                                                val model = doc.getString("model") ?: ""
                                                val mfg = doc.getString("manufacturer") ?: ""
                                                pendingList.add(PendingDeviceRegistration(id, name, regAt, model, mfg))
                                                currentIds.add(id)
                                            }
                                        }
                                        
                                        _pendingDevices.value = pendingList
                                        
                                        // Clean up removed devices from notified set
                                        notifiedPendingDeviceIds.retainAll(currentIds)
                                        
                                        // Trigger callback for pending devices
                                        pendingList.forEach { p ->
                                            if (!notifiedPendingDeviceIds.contains(p.deviceId)) {
                                                notifiedPendingDeviceIds.add(p.deviceId)
                                                onPendingDeviceCallback?.invoke(p)
                                            }
                                        }
                                    }
                                }
                        }
                        
                        // Periodic direct check (in case snapshot listener was dropped or slow)
                        try {
                            val querySnap = try {
                                db.collection("device_registrations").get(com.google.firebase.firestore.Source.SERVER).awaitTask()
                            } catch (_: Exception) {
                                db.collection("device_registrations").get().awaitTask()
                            }
                            val pendingList = mutableListOf<PendingDeviceRegistration>()
                            val currentIds = mutableSetOf<String>()
                            for (doc in querySnap.documents) {
                                val st = (doc.getString("status") ?: "").trim()
                                val id = doc.id
                                val isBlocked = st.equals(STATUS_BLOCKED, ignoreCase = true) || 
                                                st.equals("BLOCKED", ignoreCase = true) || 
                                                st.equals("DELETED", ignoreCase = true) || 
                                                st.equals("SUSPENDED", ignoreCase = true) || 
                                                st.equals("CANCELLED", ignoreCase = true) || 
                                                st == "محظور" || st == "محذوف" || st == "موقوف" || st == "ملغي"
                                val isApproved = st.equals(STATUS_APPROVED, ignoreCase = true) || 
                                                 st.equals("APPROVED", ignoreCase = true) || 
                                                 st == "معتمد"
                                val isPending = !isBlocked && !isApproved && (
                                    st.equals(STATUS_PENDING, ignoreCase = true) || 
                                    st.equals("pending", ignoreCase = true) || 
                                    st == "معلق" || 
                                    st == "بانتظار الاعتماد" ||
                                    st.isEmpty()
                                )
                                if (isBlocked) {
                                    try {
                                        db.collection("operational_alerts").document("device_alert_$id").delete()
                                    } catch (_: Exception) {}
                                } else if (isPending && id != myId && id.isNotBlank()) {
                                    val name = doc.getString("deviceName") ?: "هاتف أندرويد"
                                    val regAt = doc.getString("registeredAt") ?: ""
                                    val model = doc.getString("model") ?: ""
                                    val mfg = doc.getString("manufacturer") ?: ""
                                    pendingList.add(PendingDeviceRegistration(id, name, regAt, model, mfg))
                                    currentIds.add(id)
                                }
                            }
                            _pendingDevices.value = pendingList
                            notifiedPendingDeviceIds.retainAll(currentIds)
                            pendingList.forEach { p ->
                                if (!notifiedPendingDeviceIds.contains(p.deviceId)) {
                                    notifiedPendingDeviceIds.add(p.deviceId)
                                    onPendingDeviceCallback?.invoke(p)
                                }
                            }
                        } catch (qe: Exception) {
                            Log.w(TAG, "Direct pending poll query error", qe)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in admin pending devices monitor", e)
                }
                kotlinx.coroutines.delay(10 * 60 * 1000L) // Infrequent fallback check (real-time updates are driven by snapshot listener)
            }
        }
    }
}
