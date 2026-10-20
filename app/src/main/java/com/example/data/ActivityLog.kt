package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** سجل النشاط: من فعل ماذا ومن أي جهاز ومتى. يُحفظ في السحابة (activity_log) ويظهر على كل الأجهزة. */
data class ActivityEntry(
    val id: String,
    val timestamp: Long,
    val username: String,
    val deviceName: String,
    val category: String, // formulation, order, material, lab
    val message: String
)

object ActivityLog {
    private const val TAG = "ActivityLog"
    private const val COLLECTION = "activity_log"
    private const val RETENTION_DAYS = 30L
    private const val MAX_ENTRIES = 500

    val entries = MutableStateFlow<List<ActivityEntry>>(emptyList())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var registration: ListenerRegistration? = null

    private fun prefs(context: Context) = context.getSharedPreferences("gbr_activity_log", Context.MODE_PRIVATE)

    fun defaultDeviceName(): String = try {
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    } catch (e: Exception) { "جهاز" }

    /** اسم الجهاز الذي حدده المستخدم من الإعدادات (أو اسم الجهاز الافتراضي). */
    fun deviceName(context: Context): String =
        prefs(context).getString("device_name", null)?.takeIf { it.isNotBlank() } ?: defaultDeviceName()

    fun customDeviceName(context: Context): String =
        prefs(context).getString("device_name", "") ?: ""

    fun setDeviceName(context: Context, name: String) {
        prefs(context).edit().putString("device_name", name.trim()).apply()
    }

    fun record(context: Context, username: String, category: String, message: String) {
        val entry = ActivityEntry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            username = username,
            deviceName = deviceName(context),
            category = category,
            message = message
        )
        // يظهر فوراً محلياً ثم يُرفع للسحابة (المستمع سيستبدل القائمة بنسخة السحابة)
        entries.value = (listOf(entry) + entries.value.filter { it.id != entry.id }).take(MAX_ENTRIES)
        scope.launch {
            try {
                if (!SyncManager.initializeFirebase(context)) return@launch
                val payload = hashMapOf<String, Any>(
                    "id" to entry.id,
                    "timestamp" to entry.timestamp,
                    "username" to entry.username,
                    "deviceName" to entry.deviceName,
                    "category" to entry.category,
                    "message" to entry.message
                )
                FirebaseFirestore.getInstance().collection(COLLECTION).document(entry.id).set(payload).awaitTask()
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading activity entry", e)
            }
        }
    }

    /** يُستدعى مرة عند بدء المزامنة: يستمع لآخر الأحداث ويحذف ما مضى عليه أكثر من 30 يوماً. */
    fun startListening(db: FirebaseFirestore): ListenerRegistration {
        registration?.remove()
        val reg = db.collection(COLLECTION)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(MAX_ENTRIES.toLong())
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Listen failed for activity_log", error)
                    return@addSnapshotListener
                }
                if (snapshots != null) {
                    val list = snapshots.documents.mapNotNull { doc ->
                        try {
                            ActivityEntry(
                                id = doc.getString("id") ?: doc.id,
                                timestamp = doc.getLong("timestamp") ?: return@mapNotNull null,
                                username = doc.getString("username") ?: "غير معروف",
                                deviceName = doc.getString("deviceName") ?: "جهاز مجهول",
                                category = doc.getString("category") ?: "",
                                message = doc.getString("message") ?: ""
                            )
                        } catch (e: Exception) { null }
                    }
                    entries.value = list
                }
            }
        registration = reg
        scope.launch {
            try {
                val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600L * 1000L
                val old = db.collection(COLLECTION).whereLessThan("timestamp", cutoff).limit(400).get().awaitTask()
                if (!old.isEmpty) {
                    val batch = db.batch()
                    old.documents.forEach { batch.delete(it.reference) }
                    batch.commit().awaitTask()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Activity log cleanup failed", e)
            }
        }
        return reg
    }
}
