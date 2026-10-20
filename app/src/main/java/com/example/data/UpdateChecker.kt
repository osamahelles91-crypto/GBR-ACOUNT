package com.example.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * مقارنة الإصدار المثبّت بأحدث إصدار معروف. أحدث إصدار يُحفظ في Firestore (app_release/latest)
 * وينشره تلقائياً أول جهاز يثبّت إصداراً أحدث (الأعلى رقماً يفوز)، فتراه باقي الأجهزة.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val DAY_MS = 24L * 3600L * 1000L

    val latest = MutableStateFlow<String?>(null)
    val checking = MutableStateFlow(false)
    val lastCheckFailed = MutableStateFlow(false)

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").split(".").map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** true إن كان a أحدث من b */
    fun isNewer(a: String, b: String): Boolean {
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun updateAvailable(): Boolean = latest.value?.let { isNewer(it, AppVersion.name) } == true

    suspend fun refresh(context: Context) {
        checking.value = true
        try {
            if (!SyncManager.initializeFirebase(context)) { lastCheckFailed.value = true; return }
            val ref = FirebaseFirestore.getInstance().collection("app_release").document("latest")
            val snap = ref.get().awaitTask()
            val key = "${AppVersion.PLATFORM}Name"
            val cloud = snap.getString(key)
            if (cloud == null || isNewer(AppVersion.name, cloud)) {
                ref.set(
                    hashMapOf<String, Any>(
                        key to AppVersion.name,
                        "${AppVersion.PLATFORM}Updated" to System.currentTimeMillis(),
                        "${AppVersion.PLATFORM}By" to ActivityLog.deviceName(context)
                    ),
                    SetOptions.merge()
                ).awaitTask()
                latest.value = AppVersion.name
            } else {
                latest.value = cloud
            }
            lastCheckFailed.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            lastCheckFailed.value = true
        } finally {
            checking.value = false
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("gbr_update_check", Context.MODE_PRIVATE)

    fun alertEnabled(context: Context): Boolean = prefs(context).getBoolean("alert_enabled", true)
    fun setAlertEnabled(context: Context, on: Boolean) { prefs(context).edit().putBoolean("alert_enabled", on).apply() }

    /** يظهر التنبيه إن كان مفعّلاً وهناك إصدار أحدث ولم يُغلق خلال آخر 24 ساعة. */
    fun shouldNotify(context: Context): Boolean {
        if (!alertEnabled(context) || !updateAvailable()) return false
        val last = prefs(context).getLong("last_dismissed", 0L)
        return System.currentTimeMillis() - last >= DAY_MS
    }

    fun dismiss(context: Context) { prefs(context).edit().putLong("last_dismissed", System.currentTimeMillis()).apply() }
}
