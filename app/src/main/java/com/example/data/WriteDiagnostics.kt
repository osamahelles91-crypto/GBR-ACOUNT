package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object WriteDiagnostics {
    private val _writeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val writeCounts: StateFlow<Map<String, Int>> = _writeCounts

    private val _totalWrites = MutableStateFlow<Int>(0)
    val totalWrites: StateFlow<Int> = _totalWrites

    private val _currentCycleId = MutableStateFlow<String>("")
    val currentCycleId: StateFlow<String> = _currentCycleId

    private var listenerRegistration: ListenerRegistration? = null
    private var activeCycleId: String? = null

    fun getDiagnosticCycleId(): String {
        val tz = TimeZone.getTimeZone("Asia/Jerusalem")
        val cal = Calendar.getInstance(tz)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        // If the current hour in Palestine is before 10 AM, we subtract 1 day to belong to previous day's cycle
        if (hour < 10) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = tz
        return sdf.format(cal.time)
    }

    fun init(context: Context) {
        startListening(context)
    }

    @Synchronized
    fun startListening(context: Context) {
        val cycleId = getDiagnosticCycleId()
        _currentCycleId.value = cycleId

        if (listenerRegistration != null && activeCycleId == cycleId) {
            return
        }

        stopListening()

        if (!DeviceSecurityManager.isCloudConfigured(context) || !SyncManager.isFirebaseSetupComplete()) {
            return
        }

        try {
            val db = FirebaseFirestore.getInstance()
            activeCycleId = cycleId
            listenerRegistration = db.collection("write_diagnostics").document(cycleId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e("WriteDiagnostics", "Error listening to write diagnostics for cycle $cycleId", error)
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val total = snapshot.getLong("totalWrites")?.toInt() ?: 0
                        val colsRaw = snapshot.get("collections") as? Map<*, *>
                        val cols = mutableMapOf<String, Int>()
                        colsRaw?.forEach { (key, value) ->
                            if (key is String && value is Number) {
                                cols[key] = value.toInt()
                            }
                        }
                        _writeCounts.value = cols
                        _totalWrites.value = total
                        Log.i("WriteDiagnostics", "Loaded global diagnostics for $cycleId: Total=$total")
                    } else {
                        _writeCounts.value = emptyMap()
                        _totalWrites.value = 0
                    }
                }
        } catch (e: Exception) {
            Log.e("WriteDiagnostics", "Failed to start snapshot listener for $cycleId", e)
        }
    }

    @Synchronized
    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
        activeCycleId = null
    }

    fun recordWrite(context: Context, action: String, count: Int = 1) {
        // Optimistic local UI update
        val currentMap = _writeCounts.value.toMutableMap()
        val currentCount = currentMap[action] ?: 0
        currentMap[action] = currentCount + count
        _writeCounts.value = currentMap
        _totalWrites.value = _totalWrites.value + count

        val cycleId = getDiagnosticCycleId()
        _currentCycleId.value = cycleId

        // Ensure we are listening to the correct cycle
        startListening(context)

        CoroutineScope(Dispatchers.IO).launch {
            if (!DeviceSecurityManager.isCloudConfigured(context) || !SyncManager.isFirebaseSetupComplete()) {
                return@launch
            }
            try {
                val db = FirebaseFirestore.getInstance()
                val docRef = db.collection("write_diagnostics").document(cycleId)

                val updates = hashMapOf<String, Any>(
                    "totalWrites" to FieldValue.increment(count.toLong()),
                    "collections.$action" to FieldValue.increment(count.toLong())
                )

                docRef.update(updates).addOnFailureListener {
                    // Document doesn't exist yet, create it with initial data
                    val initial = hashMapOf<String, Any>(
                        "totalWrites" to count.toLong(),
                        "collections" to hashMapOf(action to count.toLong())
                    )
                    docRef.set(initial, SetOptions.merge())
                }
            } catch (e: Exception) {
                Log.e("WriteDiagnostics", "Failed to upload single write diagnostic", e)
            }
        }
    }

    fun clear(context: Context) {
        // No-op to maintain backward compatibility, since we reset automatically at 10 AM
    }
}
