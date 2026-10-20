@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import java.util.Locale
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.joinAll
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetAddress
import java.net.NetworkInterface

// ==========================================
// 1. DATA MODELS & NETWORKING
// ==========================================

data class DiscoveredGbrDevice(
    val ip: String,
    val deviceName: String,
    val hostname: String,
    val manufacturer: String
)

fun getLocalDeviceIpAddress(context: Context): String? {
    try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) {
            val ipStr = String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
            if (ipStr != "0.0.0.0") return ipStr
        }
    } catch (_: Exception) {}

    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        if (interfaces != null) {
            for (intf in Collections.list(interfaces)) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val host = addr.hostAddress
                        if (host != null && !host.contains(":")) {
                            if (host.startsWith("192.") || host.startsWith("10.") || host.startsWith("172.")) {
                                return host
                            }
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {}

    return null
}

fun extractSubnetPrefix(ip: String?): String {
    if (ip.isNullOrBlank()) return "192.168.1."
    val parts = ip.trim().split(".")
    if (parts.size == 4) {
        return "${parts[0]}.${parts[1]}.${parts[2]}."
    }
    return "192.168.1."
}

data class RelayStatus(
    val id: Int,
    val name: String,
    val state: Boolean
)

data class LineStatus(
    val device: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val firmware: String = "",
    val online: Boolean = false,
    val ip: String = "",
    val rssi: Int = 0,
    val relays: List<RelayStatus> = emptyList(),
    val weight: Double = 0.0,
    val scale_connected: Boolean = false,
    val fill_active: Boolean = false,
    val fill_target: Double = 0.0,
    val fault_reason: String = "",
    // Motor control fields (Line 2 / ESP32-S3)
    val motor_running: Boolean = false,
    val motor_runtime_seconds: Long = 0L,
    val motor_timed_run_active: Boolean = false,
    val motor_timed_run_remaining_seconds: Long = 0L,
    val motor_timed_run_paused: Boolean = false,
    val motor_timed_run_needs_confirm: Boolean = false,
    // حقول المستشعرات الفعلية (خط 2): null = لم يرسلها المتحكم (برمجية أقدم) ← نعود لحالة الريليه
    val hydraulic_up_active: Boolean? = null,
    val hydraulic_down_active: Boolean? = null,
    val speed_adjusting: Boolean? = null
)

data class SavedWifiItem(
    val ssid: String,
    val connected: Boolean = false
)

// Helper for WiFi RSSI description matching GBR Control Center
fun formatRssiDescription(rssi: Int): Pair<String, Color> {
    return when {
        rssi == 0 -> Pair("غير متاح", Color.Gray)
        rssi > -60 -> Pair("ممتازة ($rssi dBm)", SuccessGreen)
        rssi > -70 -> Pair("جيدة ($rssi dBm)", SuccessGreen)
        rssi > -80 -> Pair("متوسطة - قد تسبب تأخيرًا أحيانًا ($rssi dBm)", WarningOrange)
        else -> Pair("ضعيفة - يُنصح بتقريب الجهاز من الراوتر ($rssi dBm)", ErrorRed)
    }
}

// Helper for RAM usage description matching GBR Control Center
fun formatRamUsageDescription(pct: Double): Pair<String, Color> {
    val formatted = String.format(Locale.US, "%.1f", pct)
    return when {
        pct < 65.0 -> Pair("ممتازة ($formatted%)", SuccessGreen)
        pct <= 85.0 -> Pair("استخدام متوسط - طبيعي ($formatted%)", GBRBlueMain)
        else -> Pair("استخدام مرتفع جدًا ($formatted%)", ErrorRed)
    }
}

// Helper for Reset Reason translation matching GBR Control Center
fun formatResetReason(reason: String): Triple<String, String, Color> {
    val upper = reason.uppercase()
    return when {
        upper.contains("BROWNOUT") || upper.contains("POWER_DROP") -> Triple(
            "انخفاض مفاجئ في التيار الكهربائي (Brownout)",
            "تنبيه هاردوير: تم انخفاض الفولتية فجأة مما سبب إعادة التشغيل للحماية ⚠️",
            ErrorRed
        )
        upper.contains("POWERON") || upper.contains("POWER_ON") || upper.contains("POWER") -> Triple(
            "تشغيل عادي (Power On / Reset)",
            "تم تشغيل الجهاز أو إعادة وصل الكهرباء بشكل طبيعي 🟢",
            SuccessGreen
        )
        upper.contains("SW") || upper.contains("SOFTWARE") -> Triple(
            "إعادة تشغيل برمجية (Software Reset)",
            "إعادة تشغيل برمجية مطلوبة من المستخدم أو التطبيق 🟢",
            SuccessGreen
        )
        upper.contains("WDT") || upper.contains("WATCHDOG") -> Triple(
            "إعادة تشغيل تلقائية لحفظ الأداء (Watchdog)",
            "تمت إعادة تشغيل الجهاز تلقائياً لتفادي التعليق 🟡",
            WarningOrange
        )
        upper.contains("DEEPSLEEP") || upper.contains("SLEEP") -> Triple(
            "استيقاظ من النوم العميق (Deep Sleep)",
            "استيقاظ طبيعي من وضع توفير الطاقة 🟢",
            SuccessGreen
        )
        else -> Triple(
            reason.ifBlank { "تشغيل عادي (Power On)" },
            "حالة تشغيل النظام 🟢",
            SuccessGreen
        )
    }
}

private val client = OkHttpClient.Builder()
    .connectTimeout(2500, TimeUnit.MILLISECONDS)
    .readTimeout(2500, TimeUnit.MILLISECONDS)
    .writeTimeout(2500, TimeUnit.MILLISECONDS)
    .build()

// Safely parse double from JSON to prevent crashes
private fun safeParseDouble(obj: JSONObject, key: String, default: Double = 0.0): Double {
    if (!obj.has(key) || obj.isNull(key)) return default
    return try {
        obj.optDouble(key, default)
    } catch (e: Exception) {
        val str = obj.optString(key)
        str.toDoubleOrNull() ?: default
    }
}

// Safely parse long from JSON to support both numeric and string formats
private fun safeParseLong(obj: JSONObject, key: String, default: Long = 0L): Long {
    if (!obj.has(key) || obj.isNull(key)) return default
    return try {
        obj.optLong(key, default)
    } catch (e: Exception) {
        val str = obj.optString(key)
        str.toLongOrNull() ?: str.toDoubleOrNull()?.toLong() ?: default
    }
}

// Format motor runtime or countdown seconds to mm:ss format
fun formatMotorRuntime(totalSeconds: Long): String {
    val sec = totalSeconds.coerceAtLeast(0L)
    val mins = sec / 60
    val remSec = sec % 60
    return String.format(Locale.US, "%02d:%02d", mins, remSec)
}

data class FaultLogEntry(
    val time: String = "",
    val text: String = ""
)

data class ExecutionLogEntry(
    val time: String = "",
    val text: String = "",
    val category: String = "",
    val event: String = "",
    val details: String = ""
)

fun analyzeLogText(text: String): Triple<String, String, String> {
    val t = text.trim()
    val tLower = t.lowercase()
    var category = "عام"
    var event = t
    var details = ""

    // Specialized check for new translated terms
    when {
        tLower.contains("event log cleared") -> {
            category = "النظام 🖥️"
            event = "حذف السجل"
        }
        tLower.contains("hardware zero") -> {
            category = "متحكم"
            event = "تصفير الميزان"
        }
        tLower.contains("auto-fill complete") || tLower.contains("auto-fili complete") || t.contains("اكتملت التعبئة") || t.contains("تعبئة مكتملة") || t.contains("اكتمال التعبئة") -> {
            category = "صمام 💧"
            event = "اكتملت التعبئة 💧"
        }
        tLower.contains("auto-fill start - target") || tLower.contains("auto-fili start - target") || tLower.contains("auto-fill start") || tLower.contains("auto-fili start") || t.contains("بدء تعبئة تلقائية") || t.contains("بدء التعبئة") -> {
            category = "صمام 💧"
            event = "بدء تعبئة تلقائية 💧"
        }
        t.contains("بدء جرس التحذير") || t.contains("جرس التحذير") || t.contains("جرس تحذير") || t.contains("جرس") || t.contains("تحذير") || tLower.contains("alarm") || tLower.contains("warning") || t.contains("إنذار") -> {
            category = "تحذير ⚠️"
            event = "بدء جرس التحذير 🔔"
        }
    }

    // Specially extract completed weight and duration if water-fill
    if (tLower.contains("auto-fill") || tLower.contains("auto-fili") || tLower.contains("water") || tLower.contains("تعبئة") || tLower.contains("ماء")) {
        val weightMatch = Regex("""(\d+(\.\d+)?)\s*(كجم|كغم|kg|g|kilogram)""", RegexOption.IGNORE_CASE).find(t)
        val weightVal = weightMatch?.value ?: ""

        val durationMatch = Regex("""(\d+(\.\d+)?)\s*(ثانية|ثواني|s|sec|seconds)""", RegexOption.IGNORE_CASE).find(t)
        val durationVal = durationMatch?.value ?: ""

        if (weightVal.isNotBlank() && durationVal.isNotBlank()) {
            details = "$weightVal\nالمدة: $durationVal"
        } else if (weightVal.isNotBlank()) {
            details = weightVal
        } else if (durationVal.isNotBlank()) {
            details = durationVal
        }
    }

    // Default parsing if not handled above
    if (details.isBlank()) {
        val numberRegex = Regex("""(\d+(\.\d+)?)\s*(كجم|كغم|جرام|درجة|ثانية|ثواني|ساعة|دقائق|مل|أمبير|فولت|Hz|cP|%|C|kg|g|s|V|A|rpm|RPM|step|Hz)""", RegexOption.IGNORE_CASE)
        val numberMatch = numberRegex.find(t)
        if (numberMatch != null) {
            details = numberMatch.value
        } else {
            val genericNumberRegex = Regex("""\b\d+(\.\d+)?\b""")
            val genericMatch = genericNumberRegex.findAll(t).map { it.value }.toList()
            if (genericMatch.isNotEmpty()) {
                details = genericMatch.joinToString(", ")
            }
        }
    }

    if (category == "عام" || event == t) {
        // Fallback checks for classification
        when {
            t.contains("صمام") || t.contains("valve") || t.contains("ماء") || t.contains("water") || t.contains("ضخ") -> {
                category = "صمام 💧"
                event = when {
                    t.contains("فتح") || t.contains("open") -> "فتح الصمام"
                    t.contains("إغلاق") || t.contains("close") -> "إغلاق الصمام"
                    else -> "تحكم بالصمام"
                }
            }
            t.contains("موتور") || t.contains("محرك") || t.contains("motor") || t.contains("خلط") || t.contains("سرعة") || t.contains("speed") || t.contains("mix") || t.contains("حركة") -> {
                category = "المحرك ⚙️"
                event = when {
                    t.contains("تشغيل") || t.contains("start") || t.contains("run") -> "تشغيل المحرك"
                    t.contains("إيقاف") || t.contains("stop") -> "إيقاف المحرك"
                    t.contains("سرعة") || t.contains("speed") -> "ضبط السرعة"
                    else -> "تحكم بالمحرك"
                }
            }
            t.contains("ميزان") || t.contains("وزن") || t.contains("scale") || t.contains("weight") || t.contains("تصفير") || t.contains("zero") || t.contains("tare") -> {
                category = "الميزان ⚖️"
                event = when {
                    t.contains("تصفير") || t.contains("zero") || t.contains("tare") -> "تصفير الميزان"
                    t.contains("معايرة") || t.contains("calibrate") -> "معايرة الميزان"
                    else -> "قراءة الوزن"
                }
            }
            t.contains("هيدروليك") || t.contains("رفع") || t.contains("خفص") || t.contains("تنزيل") || t.contains("lift") || t.contains("hydraulic") -> {
                category = "الهيدروليك 🏗️"
                event = when {
                    t.contains("رفع") || t.contains("lift") || t.contains("up") -> "رفع الذراع"
                    t.contains("خفص") || t.contains("تنزيل") || t.contains("lower") || t.contains("down") -> "خفض الذراع"
                    else -> "تحكم بالهيدروليك"
                }
            }
            t.contains("أمان") || t.contains("طوارئ") || t.contains("خطأ") || t.contains("فصل") || t.contains("حرارة") || t.contains("interlock") || t.contains("safety") || t.contains("emergency") || t.contains("fault") || t.contains("error") -> {
                category = "أمان/تحذير ⚠️"
                event = when {
                    t.contains("طوارئ") || t.contains("emergency") -> "توقف طوارئ"
                    t.contains("خطأ") || t.contains("error") || t.contains("fault") -> "خطأ بالنظام"
                    else -> "تنبيه أمان"
                }
            }
            t.contains("نظام") || t.contains("اتصال") || t.contains("wifi") || t.contains("WiFi") || t.contains("تشغيل") || t.contains("reboot") || t.contains("reset") -> {
                category = "النظام 🖥️"
                event = when {
                    t.contains("بدء") || t.contains("تشغيل") || t.contains("start") || t.contains("boot") -> "بدء تشغيل النظام"
                    t.contains("اتصال") || t.contains("connect") -> "تحديث الاتصال"
                    else -> "حالة النظام"
                }
            }
        }
    }

    if (details.isBlank()) {
        details = "-"
    }

    return Triple(category, event, details)
}

fun parseExecutionLogItem(raw: Any): ExecutionLogEntry {
    var time = ""
    var text = ""
    
    if (raw is JSONObject) {
        time = raw.optString("time", raw.optString("timestamp", "")).trim()
        text = raw.optString("text", raw.optString("message", raw.optString("msg", ""))).trim()
    } else {
        val str = raw.toString().trim()
        if (str.startsWith("{")) {
            try {
                val obj = JSONObject(str)
                time = obj.optString("time", obj.optString("timestamp", "")).trim()
                text = obj.optString("text", obj.optString("message", obj.optString("msg", ""))).trim()
            } catch (e: Exception) {
                // Fallthrough to manual parsing
            }
        }
        if (time.isBlank() || text.isBlank()) {
            val timeMatch = Regex("""["'](?:time|timestamp)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(str)
            val textMatch = Regex("""["'](?:text|message|msg)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(str)
            time = timeMatch?.groupValues?.get(1)?.trim() ?: ""
            text = textMatch?.groupValues?.get(1)?.trim() ?: ""
            
            if (text.isBlank() && str.isNotBlank()) {
                val plainTextRegex = Regex("""^\[?([\d: \-\/A-Za-z]+)\]?[\s:-]+(.*)$""")
                val plainTextMatch = plainTextRegex.find(str)
                if (plainTextMatch != null) {
                    time = plainTextMatch.groupValues[1].trim()
                    text = plainTextMatch.groupValues[2].trim()
                } else {
                    time = "-"
                    text = str
                }
            }
        }
    }
    
    val (category, _, details) = analyzeLogText(text)
    return ExecutionLogEntry(
        time = if (time.isBlank()) "-" else time,
        text = text,
        category = category,
        event = text,
        details = details
    )
}

private fun translateResetReason(x: String): String {
    val upper = x.uppercase()
    return when {
        upper.contains("POWERON") || upper.contains("POWER_ON") -> "تشغيل عادي"
        upper.contains("BROWNOUT") -> "انخفاض مفاجئ في التيار الكهربائي"
        upper.contains("PANIC") || upper.contains("SW") -> "عطل برمجي (Crash)"
        upper.contains("WDT") || upper.contains("WATCHDOG") -> "توقف البرنامج عن الاستجابة (Watchdog)"
        else -> x
    }
}

private fun translateDeviceName(device: String): String {
    return when (device.trim()) {
        "WaterValve", "Water Valve", "Water_Valve", "Water valve" -> "صمام الماء"
        "Relay2" -> "محرك الخلاط"
        "Relay3" -> "رفع الهيدروليك"
        "Relay4" -> "خفض الهيدروليك"
        else -> device.trim()
    }
}

fun translateFaultLogText(rawText: String): String {
    val text = rawText.trim()
    if (text.isEmpty()) return ""

    if (text.startsWith("Device BOOTED - reset reason:", ignoreCase = true)) {
        val x = text.substringAfter("Device BOOTED - reset reason:").trim()
        val translatedX = translateResetReason(x)
        return "إقلاع الجهاز - السبب: $translatedX"
    }

    if (text.startsWith("Unexpected restart -", ignoreCase = true)) {
        val x = text.substringAfter("Unexpected restart -").trim()
        val translatedX = translateResetReason(x)
        return "⚠️ إعادة تشغيل غير متوقعة - $translatedX"
    }

    val onRegex = Regex("""^(WaterValve|Relay2|Relay3|Relay4)\s+ON$""", RegexOption.IGNORE_CASE)
    onRegex.find(text)?.let { match ->
        val dev = translateDeviceName(match.groupValues[1])
        return "$dev - تشغيل"
    }

    val offRegex = Regex("""^(WaterValve|Relay2|Relay3|Relay4|\w+)\s+OFF\s+\(duration:\s*(.+?)\)$""", RegexOption.IGNORE_CASE)
    offRegex.find(text)?.let { match ->
        val dev = translateDeviceName(match.groupValues[1])
        val dur = match.groupValues[2]
        return "$dev - إيقاف (استمر $dur)"
    }

    val scaleZeroRegex = Regex("""^Scale ZERO \(software\)\s*-\s*gross was\s*(.+)$""", RegexOption.IGNORE_CASE)
    scaleZeroRegex.find(text)?.let { match ->
        val valKg = match.groupValues[1]
        return "تصفير الميزان (برمجي) - كان الإجمالي $valKg"
    }

    val scaleTareRegex = Regex("""^Scale TARE \(software\)\s*-\s*gross was\s*(.+)$""", RegexOption.IGNORE_CASE)
    scaleTareRegex.find(text)?.let { match ->
        val valKg = match.groupValues[1]
        return "وزن الفارغ (Tare - برمجي) - كان الإجمالي $valKg"
    }

    if (text.startsWith("Zero after scale reconnect", ignoreCase = true)) {
        return "ℹ️ تصفير بعد إعادة اتصال الميزان - لم يُحتسب كمادة"
    }

    val matRegex = Regex("""^Material\s*#(\d+)\s+added:\s*(.+)$""", RegexOption.IGNORE_CASE)
    matRegex.find(text)?.let { match ->
        val num = match.groupValues[1]
        val kg = match.groupValues[2]
        return "✓ تمت إضافة مادة رقم $num - $kg"
    }

    if (text.startsWith("Auto NEW BATCH", ignoreCase = true)) {
        return "بدء دفعة جديدة تلقائيًا (مرت 24 ساعة على آخر مادة)"
    }

    val autoFillStartRegex = Regex("""^Auto-fill START\s*-\s*target\s*(.+)$""", RegexOption.IGNORE_CASE)
    autoFillStartRegex.find(text)?.let { match ->
        val targetKg = match.groupValues[1]
        return "بدء تعبئة تلقائية - الهدف $targetKg"
    }

    if (text.startsWith("Auto-fill COMPLETE", ignoreCase = true)) {
        return "✓ اكتملت التعبئة التلقائية"
    }

    if (text.startsWith("Auto-fill CANCELLED", ignoreCase = true)) {
        return "تم إلغاء التعبئة التلقائية"
    }

    val wifiDiscRegex = Regex("""^WiFi DISCONNECTED\s*\(RSSI was\s*(.+?)\)$""", RegexOption.IGNORE_CASE)
    wifiDiscRegex.find(text)?.let { match ->
        val rssi = match.groupValues[1]
        return "📡 انقطع اتصال الشبكة (كانت قوة الإشارة $rssi)"
    }

    val wifiReconnRegex = Regex("""^WiFi RECONNECTED\s*\(was down for\s*(.+?)\)$""", RegexOption.IGNORE_CASE)
    wifiReconnRegex.find(text)?.let { match ->
        val dur = match.groupValues[1]
        return "📡 عاد اتصال الشبكة (كان منقطعًا لمدة $dur)"
    }

    val manualZeroRegex = Regex("""^Possible manual zero on indicator\s*\(was\s*(.+?)\)$""", RegexOption.IGNORE_CASE)
    manualZeroRegex.find(text)?.let { match ->
        val valKg = match.groupValues[1]
        return "⚠️ تصفير مفاجئ محتمل من شاشة المؤشر (كانت القيمة $valKg)"
    }

    if (text.startsWith("Water valve open with no weight increase", ignoreCase = true)) {
        return "⚠️ الصمام مفتوح منذ أكثر من 30 ثانية بدون زيادة في الوزن - تحقق من الميزان أو تدفق المياه"
    }

    if (text.startsWith("Blocked: attempted to open water valve", ignoreCase = true)) {
        return "⛔ تم رفض محاولة فتح صمام الماء لأن الميزان غير متصل"
    }

    if (text.startsWith("Blocked: attempted to start auto-fill", ignoreCase = true)) {
        return "⛔ تم رفض محاولة بدء تعبئة تلقائية لأن الميزان غير متصل"
    }

    val baudRegex = Regex("""^Scale baud rate changed to\s*(.+)$""", RegexOption.IGNORE_CASE)
    baudRegex.find(text)?.let { match ->
        val rate = match.groupValues[1]
        return "تم تغيير سرعة اتصال الميزان إلى $rate"
    }

    val hwZeroOkRegex = Regex("""^Hardware ZERO via Z command\s*-\s*gross was\s*(.+?),\s*verified OK$""", RegexOption.IGNORE_CASE)
    hwZeroOkRegex.find(text)?.let { match ->
        val kg = match.groupValues[1]
        return "✓ تصفير المؤشر فعليًا (هاردوير) - كان الإجمالي $kg - تم التحقق بنجاح"
    }

    if (text.startsWith("Hardware ZERO via Z command", ignoreCase = true) && text.contains("FAILED", ignoreCase = true)) {
        return "⚠️ فشل التحقق من تصفير المؤشر - تأكد أن وضع الأوامر (P5=4) مفعّل"
    }

    return text
}

fun parseFaultItem(raw: Any): FaultLogEntry {
    if (raw is JSONObject) {
        val time = raw.optString("time", "").trim()
        val text = raw.optString("text", "").trim()
        return FaultLogEntry(time = time, text = text)
    }
    val str = raw.toString().trim()
    if (str.startsWith("{")) {
        try {
            val obj = JSONObject(str)
            val time = obj.optString("time", "").trim()
            val text = obj.optString("text", "").trim()
            if (time.isNotBlank() || text.isNotBlank()) {
                return FaultLogEntry(time = time, text = text)
            }
        } catch (e: Exception) {
            // Fallthrough to regex
        }
    }

    val timeMatch = Regex("""["']time["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(str)
    val textMatch = Regex("""["']text["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(str)
    
    val extractedTime = timeMatch?.groupValues?.get(1)?.trim() ?: ""
    var extractedText = textMatch?.groupValues?.get(1)?.trim() ?: ""

    if (extractedText.isBlank() && str.isNotBlank()) {
        extractedText = str.replace(Regex("""^\s*\{?\s*["']time["']\s*:\s*["'][^"']*["']\s*,\s*["']text["']\s*:\s*["']?"""), "")
            .replace(Regex("""["']?\s*\}?\s*$"""), "")
            .trim()
    }

    return FaultLogEntry(time = extractedTime, text = extractedText)
}

fun resolveUrl(base: String, endpoint: String): String {
    val cleanBase = base.trim()
    val hasScheme = cleanBase.startsWith("http://") || cleanBase.startsWith("https://")
    val url = if (hasScheme) cleanBase else "http://$cleanBase"
    val formattedBase = if (url.endsWith("/")) url.substring(0, url.length - 1) else url
    val formattedEndpoint = if (endpoint.startsWith("/")) endpoint else "/$endpoint"
    return "$formattedBase$formattedEndpoint"
}

// قراءة /status تُعتبر صالحة فقط إذا احتوت الحقول الأساسية (وإلا تُهمل ولا تُفسَّر كـ «وزن 0 وتوقف»)
fun isCompleteStatusJson(jsonStr: String): Boolean {
    return try {
        val obj = JSONObject(jsonStr)
        obj.has("weight") && obj.has("fill_active")
    } catch (e: Exception) {
        false
    }
}

// Parse status JSON from ESP32
fun parseLineStatus(jsonStr: String): LineStatus {
    try {
        val obj = JSONObject(jsonStr)
        val relaysList = mutableListOf<RelayStatus>()
        if (obj.has("relays")) {
            val arr = obj.getJSONArray("relays")
            for (i in 0 until arr.length()) {
                val rObj = arr.getJSONObject(i)
                relaysList.add(
                    RelayStatus(
                        id = rObj.optInt("id", i + 1),
                        name = rObj.optString("name", "Relay"),
                        state = rObj.optBoolean("state", false)
                    )
                )
            }
        } else {
            // Fill default relays
            relaysList.addAll(
                listOf(
                    RelayStatus(1, "WaterValve", false),
                    RelayStatus(2, "Relay2", false),
                    RelayStatus(3, "Relay3", false),
                    RelayStatus(4, "Relay4", false)
                )
            )
        }
        val faultReason = obj.optString("fault_reason", obj.optString("fault", "")).trim()
        val motorRunning = obj.optBoolean("motor_running", false)
        val motorRuntime = safeParseLong(obj, "motor_runtime_seconds", 0L)
        val motorTimedActive = obj.optBoolean("motor_timed_run_active", false)
        val motorTimedRemaining = safeParseLong(obj, "motor_timed_run_remaining_seconds", 0L)
        val motorTimedPaused = obj.optBoolean("motor_timed_run_paused", false)
        val motorTimedNeedsConfirm = obj.optBoolean("motor_timed_run_needs_confirm", false)

        return LineStatus(
            device = obj.optString("device", "GBR Smart Factory"),
            manufacturer = obj.optString("manufacturer", "GBR Paints"),
            model = obj.optString("model", "ESP32 Controller"),
            firmware = obj.optString("firmware", "1.0.0"),
            online = obj.optBoolean("online", true),
            ip = obj.optString("ip", ""),
            rssi = obj.optInt("rssi", 0),
            relays = relaysList,
            weight = safeParseDouble(obj, "weight", 0.0),
            scale_connected = obj.optBoolean("scale_connected", false),
            fill_active = obj.optBoolean("fill_active", false),
            fill_target = safeParseDouble(obj, "fill_target", 0.0),
            fault_reason = faultReason,
            motor_running = motorRunning,
            motor_runtime_seconds = motorRuntime,
            motor_timed_run_active = motorTimedActive,
            motor_timed_run_remaining_seconds = motorTimedRemaining,
            motor_timed_run_paused = motorTimedPaused,
            motor_timed_run_needs_confirm = motorTimedNeedsConfirm,
            hydraulic_up_active = if (obj.has("hydraulic_up_active")) obj.optBoolean("hydraulic_up_active", false) else null,
            hydraulic_down_active = if (obj.has("hydraulic_down_active")) obj.optBoolean("hydraulic_down_active", false) else null,
            speed_adjusting = if (obj.has("speed_adjusting")) obj.optBoolean("speed_adjusting", false) else null
        )
    } catch (e: Exception) {
        e.printStackTrace()
        return LineStatus(online = false)
    }
}

// Helper function to robustly parse scale update rate inputs in milliseconds or seconds (supporting Arabic numerals, decimals, units)
fun parseUpdateRateInput(input: String, defaultRate: Long = 1000L): Long {
    if (input.isBlank()) return defaultRate
    var normalized = input.trim()
        .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
        .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
        .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
        .replace(',', '.')

    val numberMatch = Regex("""\d+(\.\d+)?""").find(normalized) ?: return defaultRate
    val numStr = numberMatch.value
    val numDouble = numStr.toDoubleOrNull() ?: return defaultRate

    val finalMs = when {
        normalized.contains("s", ignoreCase = true) || normalized.contains("ثانية") || numDouble <= 10.0 -> {
            (numDouble * 1000.0).toLong()
        }
        else -> {
            numDouble.toLong()
        }
    }
    return finalMs.coerceIn(100L, 10000L)
}

// Persistent Storage for production lines matching SyncManager's expected "devices_list" structure & Cloud Firestore
fun saveLines(
    context: Context,
    line1Name: String,
    line1Ip: String,
    line1UpdateRate: Long,
    line2Name: String,
    line2Ip: String,
    line2UpdateRate: Long
) {
    val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
    val editor = prefs.edit()
    editor.putString("line_1_name", line1Name)
    editor.putString("line_1_ip", line1Ip)
    editor.putLong("line_1_update_rate", line1UpdateRate)
    editor.putString("line_2_name", line2Name)
    editor.putString("line_2_ip", line2Ip)
    editor.putLong("line_2_update_rate", line2UpdateRate)

    // Keep "devices_list" updated in SharedPreferences for SyncManager compatibility and cloud backup
    val arr = JSONArray()
    arr.put(JSONObject().apply {
        put("id", "line-1")
        put("name", line1Name)
        put("accessId", "LINE")
        put("accessSecret", "")
        put("deviceId", line1Ip)
        put("regionUrl", "LINE_1")
        put("regionName", line1UpdateRate.toString())
    })
    arr.put(JSONObject().apply {
        put("id", "line-2")
        put("name", line2Name)
        put("accessId", "LINE")
        put("accessSecret", "")
        put("deviceId", line2Ip)
        put("regionUrl", "LINE_2")
        put("regionName", line2UpdateRate.toString())
    })
    editor.putString("devices_list", arr.toString())
    editor.commit()

    // Immediately push lines data and connection speeds to Cloud Firestore in the background
    try {
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val doc1 = mapOf(
            "id" to "line-1",
            "name" to line1Name,
            "ip" to line1Ip,
            "deviceId" to line1Ip,
            "updateRateMs" to line1UpdateRate,
            "regionUrl" to "LINE_1",
            "regionName" to line1UpdateRate.toString(),
            "lastUpdated" to System.currentTimeMillis()
        )
        val doc2 = mapOf(
            "id" to "line-2",
            "name" to line2Name,
            "ip" to line2Ip,
            "deviceId" to line2Ip,
            "updateRateMs" to line2UpdateRate,
            "regionUrl" to "LINE_2",
            "regionName" to line2UpdateRate.toString(),
            "lastUpdated" to System.currentTimeMillis()
        )
        db.collection("equipment_devices").document("line-1")
            .set(doc1, com.google.firebase.firestore.SetOptions.merge())
        db.collection("equipment_devices").document("line-2")
            .set(doc2, com.google.firebase.firestore.SetOptions.merge())
    } catch (_: Exception) {}
}

// ==========================================
// 2. MAIN EQUIPMENT CONTROL PANEL
// ==========================================

@Composable
fun PhoneSignalBars(
    rssi: Int,
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val activeBars = when {
        !isOnline -> 0
        rssi == 0 -> 3
        rssi > -60 -> 4
        rssi > -70 -> 3
        rssi > -80 -> 2
        else -> 1
    }

    val activeColor = when {
        !isOnline -> ErrorRed.copy(alpha = 0.5f)
        activeBars >= 3 -> SuccessGreen
        activeBars == 2 -> WarningOrange
        else -> ErrorRed
    }

    val inactiveColor = Color(0xFFE5E7EB)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val barHeights = listOf(6.dp, 10.dp, 14.dp, 18.dp)
        for (i in 0 until 4) {
            val isActive = i < activeBars
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(barHeights[i])
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (isActive) activeColor else inactiveColor)
            )
        }
    }
}

// =========================================================================
// SENSORS STATUS & DIAGNOSTICS HELPERS (LINE 2)
// =========================================================================
fun formatSensorLastRun(lastTimestamp: Long, isCurrentlyActive: Boolean, runtimeSeconds: Long = 0L): String {
    if (isCurrentlyActive) {
        return if (runtimeSeconds > 0) {
            val m = runtimeSeconds / 60
            val s = runtimeSeconds % 60
            if (m > 0) "🟢 قيد العمل والتشغيل الآن (منذ ${m}د و ${s}ث)"
            else "🟢 قيد العمل والتشغيل الآن (منذ ${s} ثوانٍ)"
        } else {
            "🟢 قيد العمل والتشغيل الآن ⚡"
        }
    }
    if (lastTimestamp <= 0L) {
        return "لم يُرصد تشغيل في هذه الجلسة"
    }
    val diff = System.currentTimeMillis() - lastTimestamp
    return try {
        when {
            diff < 60_000L -> "منذ ثوانٍ معدودة (${SimpleDateFormat("hh:mm:ss a", Locale("ar")).format(Date(lastTimestamp))})"
            diff < 3_600_000L -> "منذ ${diff / 60_000L} دقيقة (${SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(lastTimestamp))})"
            diff < 86_400_000L -> "اليوم في ${SimpleDateFormat("hh:mm a", Locale("ar")).format(Date(lastTimestamp))}"
            else -> SimpleDateFormat("yyyy/MM/dd hh:mm a", Locale("ar")).format(Date(lastTimestamp))
        }
    } catch (e: Exception) {
        "مسجل مؤخراً"
    }
}

@Composable
fun SensorStatusDiagnosticCard(
    title: String,
    sensorRole: String,
    isActive: Boolean,
    activeText: String,
    inactiveText: String,
    lastRunText: String,
    wireArabic: String,
    wireEnglish: String,
    wireColors: List<Color>,
    diagnosticDetails: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = GBRBlueMain,
    gpioPort: String = ""
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFFDCFCE7) else Color(0xFFFEF2F2)
        ),
        border = BorderStroke(
            1.5.dp,
            if (isActive) SuccessGreen else Color(0xFFEF4444)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Icon + Title + Real-time State Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (isActive) SuccessGreen.copy(alpha = 0.15f) else iconTint.copy(alpha = 0.1f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isActive) SuccessGreen else iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = GBRDarkIndigo,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = sensorRole,
                            fontSize = 9.5.sp,
                            color = Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Active / Inactive State Badge
                Surface(
                    color = if (isActive) SuccessGreen else Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    if (isActive) Color.White else Color.Gray,
                                    CircleShape
                                )
                        )
                        Text(
                            text = if (isActive) activeText else inactiveText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) Color.White else Color(0xFF475569)
                        )
                    }
                }
            }

            // GPIO Controller Port Badge (منفذ لوحة المتحكم)
            if (gpioPort.isNotBlank()) {
                Surface(
                    color = Color(0xFFEFF6FF),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = GBRBlueMain,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "منفذ المتحكم:",
                                fontSize = 9.5.sp,
                                color = Color(0xFF1E40AF),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Surface(
                            color = Color(0xFFDBEAFE),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = gpioPort,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF1E3A8A),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Wire Data for Maintenance (بيانات الأسلاك لتسهيل الصيانة)
            Surface(
                color = Color(0xFFF8FAFC),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Visual Wire Color Stripes Preview
                        Row(
                            modifier = Modifier
                                .height(16.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .border(1.dp, Color(0xFF94A3B8), RoundedCornerShape(3.dp))
                        ) {
                            wireColors.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .fillMaxHeight()
                                        .background(c)
                                )
                            }
                        }
                        Text(
                            text = wireArabic,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = GBRDarkIndigo
                        )
                    }
                    Text(
                        text = wireEnglish,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64748B)
                    )
                }
            }

            // Last Run Row (آخر تشغيل)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (isActive) SuccessGreen else GBRBlueMain,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "آخر تشغيل:",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = GBRDarkIndigo
                    )
                }
                Text(
                    text = lastRunText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) SuccessGreen else Color(0xFF1E293B)
                )
            }

            // Diagnostics Details Row
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(11.dp).padding(top = 1.dp)
                )
                Text(
                    text = diagnosticDetails,
                    fontSize = 8.5.sp,
                    color = Color.Gray,
                    lineHeight = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * إرسال «إيقاف» لريليه لحظي بشكل موثوق (حادثة 2026-10-12: زر رفع الهيدروليك عالق في خط 2).
 * أمر الإيقاف الواحد قد يضيع (انقطاع شبكة لحظي أو إلغاء الضغطة)، لذلك: يُعاد حتى 4 مرات
 * إن فشل الطلب أو أبلغ /status أن الريليه (أو حقل المستشعر المقابل) ما زال فعّالاً،
 * ويتوقف فوراً إن بدأ المستخدم ضغطة جديدة (isStillWanted=false) كي لا يقطع ضغطته.
 */
private val relayOffScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

private fun sendRelayOffReliably(
    context: Context,
    relayId: Int,
    currentLineIp: String,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    isStillWanted: () -> Boolean,
    attempt: Int = 1
) {
    if (currentLineIp.isBlank()) return
    val maxAttempts = 4
    fun retryOrWarn() {
        if (attempt >= maxAttempts) {
            relayOffScope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    try {
                        Toast.makeText(context, "⚠️ تعذّر تأكيد إيقاف الريليه $relayId — تحقق من الجهاز أو استخدم زر الطوارئ", Toast.LENGTH_LONG).show()
                    } catch (_: Exception) {}
                }
            }
        } else {
            relayOffScope.launch {
                kotlinx.coroutines.delay(350L)
                if (isStillWanted()) {
                    sendRelayOffReliably(context, relayId, currentLineIp, sendCommand, onStatusUpdate, isStillWanted, attempt + 1)
                }
            }
        }
    }
    sendCommand(
        resolveUrl(currentLineIp, "/control?relay=$relayId&state=off"),
        { updated ->
            if (updated != null) onStatusUpdate(updated)
            val relayStillOn = updated?.relays?.find { it.id == relayId }?.state == true
            val sensorStillOn = when (relayId) {
                4 -> updated?.hydraulic_up_active == true
                5 -> updated?.hydraulic_down_active == true
                7, 8 -> updated?.speed_adjusting == true
                else -> false
            }
            if (updated != null && (relayStillOn || sensorStillOn) && isStillWanted()) retryOrWarn()
        },
        { _ -> if (isStillWanted()) retryOrWarn() }
    )
}

// =========================================================================
// نبضات الأمان (deadman) للريليهات اللحظية 4/5/7/8 فقط (هيدروليك + سرعة)
// المتحكم يقبل ttl بين 200 و3000ms ويوقف الريليه وحده إن انقطعت النبضات.
// ممنوع استخدامها للريليهين 2 و3 (تشغيل/إيقاف الماتور) أو أي ريليه آخر.
// =========================================================================
private const val HOLD_TTL_MS = 1000
private const val HOLD_PULSE_INTERVAL_MS = 250L
private const val HOLD_MAX_CONSECUTIVE_FAILS = 3

internal fun holdOnUrl(ip: String, relayId: Int): String =
    resolveUrl(ip, "/control?relay=$relayId&state=on&ttl=$HOLD_TTL_MS")

/** تكرر أمر التشغيل كل 250ms ما دام الضغط قائماً؛ 3 إخفاقات متتالية (نجاح يصفّر العدّاد) → توقف وتستدعي onLost. */
internal fun launchHoldPulses(
    scope: kotlinx.coroutines.CoroutineScope,
    relayId: Int,
    ip: String,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onLost: () -> Unit
): kotlinx.coroutines.Job = scope.launch {
    var fails = 0
    while (true) {
        kotlinx.coroutines.delay(HOLD_PULSE_INTERVAL_MS)
        val ok = kotlinx.coroutines.withTimeoutOrNull(900L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                sendCommand(
                    holdOnUrl(ip, relayId),
                    { updated -> if (cont.isActive) cont.resumeWith(Result.success(updated != null)) },
                    { _ -> if (cont.isActive) cont.resumeWith(Result.success(false)) }
                )
            }
        } ?: false
        fails = if (ok) 0 else fails + 1
        if (fails >= HOLD_MAX_CONSECUTIVE_FAILS) {
            onLost()
            break
        }
    }
}

/** للطيار الآلي: تبقي الريليه شغالاً durationSeconds ثانية بنبضات كل 250ms، وتبلّغ عن الثواني المتبقية. */
internal suspend fun holdWithPulses(
    ip: String,
    relayId: Int,
    durationSeconds: Int,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onRemaining: (Int) -> Unit = {}
) {
    val totalMs = durationSeconds.coerceAtLeast(1) * 1000L
    val start = System.currentTimeMillis()
    sendCommand(holdOnUrl(ip, relayId), {}, {})
    var lastRem = -1
    while (true) {
        val elapsed = System.currentTimeMillis() - start
        if (elapsed >= totalMs) break
        val rem = kotlin.math.ceil((totalMs - elapsed) / 1000.0).toInt()
        if (rem != lastRem) { lastRem = rem; onRemaining(rem) }
        kotlinx.coroutines.delay(minOf(HOLD_PULSE_INTERVAL_MS, totalMs - elapsed))
        if (System.currentTimeMillis() - start < totalMs) sendCommand(holdOnUrl(ip, relayId), {}, {})
    }
}

// =========================================================================
// LINE 2 DEDICATED MOTOR CONTROL CARD (ESP32-S3)
// =========================================================================
// =========================================================================
// LINE 2 MOMENTARY HOLD-TO-RUN BUTTON (PRESS-AND-HOLD)
// =========================================================================
@Composable
fun MomentaryHoldButton(
    relayId: Int,
    label: String,
    subLabel: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    activeColor: Color,
    defaultColor: Color,
    isRelayStateActive: Boolean,
    enabled: Boolean,
    disabledReason: String? = null,
    currentLineIp: String,
    testTag: String,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isLocallyPressed by remember { mutableStateOf(false) }
    val pulseScope = rememberCoroutineScope()

    // Industrial Safety: If composable leaves composition while pressed, send OFF immediately
    DisposableEffect(relayId, currentLineIp) {
        onDispose {
            if (isLocallyPressed && currentLineIp.isNotBlank()) {
                sendRelayOffReliably(context, relayId, currentLineIp, sendCommand, onStatusUpdate, { true })
            }
        }
    }

    val isActive = isLocallyPressed || isRelayStateActive

    val containerBg = when {
        !enabled -> Color(0xFFF0F2F5)
        isActive -> activeColor.copy(alpha = 0.15f)
        else -> Color.White
    }
    val borderColor = when {
        !enabled -> Color(0xFFE0E0E0)
        isActive -> activeColor
        else -> Color(0xFFCFD8DC)
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(if (isActive) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 0.dp else 2.dp),
        modifier = Modifier
            .testTag(testTag)
            .pointerInput(enabled, relayId, currentLineIp) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initialPos = down.position
                    val touchSlop = viewConfiguration.touchSlop

                    if (currentLineIp.isBlank()) {
                        return@awaitEachGesture
                    }

                    // Anti-accidental scroll filter:
                    // Wait up to 160ms while ensuring finger remains stationary within touch slop.
                    // If user is scrolling/swiping, the gesture moves past slop or is consumed by scroll container.
                    var cancelled = false
                    withTimeoutOrNull(160L) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed || pointer.isConsumed) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                            val diff = pointer.position - initialPos
                            if (diff.getDistance() > touchSlop) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                        }
                    }

                    if (cancelled) {
                        return@awaitEachGesture
                    }

                    // Confirmed intentional hold - consume event & activate
                    down.consume()
                    isLocallyPressed = true
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (_: Exception) {}

                    // On Confirmed Press / Down -> send ON immediately
                    sendCommand(
                        holdOnUrl(currentLineIp, relayId),
                        { updated -> if (updated != null) onStatusUpdate(updated) },
                        { /* Handled */ }
                    )

                    // نبضات أمان: تجدّد صلاحية الريليه كل 250ms؛ إن انقطعت الشبكة يوقفه المتحكم نفسه
                    val pulseJob = launchHoldPulses(pulseScope, relayId, currentLineIp, sendCommand) {
                        isLocallyPressed = false
                        try {
                            Toast.makeText(context, "انقطع الاتصال — توقفت الحركة. أعد الضغط للمتابعة", Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {}
                    }

                    // الإيقاف يُرسل دائماً (finally) حتى لو أُلغيت الضغطة أثناء الإمساك — مثلاً بتغيّر enabled عند انقطاع اتصال لحظي
                    try {
                        var pointerFinished = false
                        while (!pointerFinished) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed) {
                                pointerFinished = true
                            } else {
                                pointer.consume()
                                // On leave / drag outside button bounds -> release
                                val pos = pointer.position
                                val isOutside = pos.x < 0 || pos.x > size.width || pos.y < 0 || pos.y > size.height
                                if (isOutside) {
                                    pointerFinished = true
                                }
                            }
                        }

                    } finally {
                        // On Release / Leave / Cancel -> send OFF (مع إعادة محاولة وتحقق)
                        pulseJob.cancel()
                        isLocallyPressed = false
                        sendRelayOffReliably(context, relayId, currentLineIp, sendCommand, onStatusUpdate, { !isLocallyPressed })
                    }
                }
            }
            .then(
                if (!enabled && disabledReason != null) {
                    Modifier.clickable {
                        Toast.makeText(context, disabledReason, Toast.LENGTH_SHORT).show()
                    }
                } else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !enabled -> Color(0xFFB0BEC5)
                            isActive -> activeColor
                            else -> defaultColor
                        }
                    )
                    .border(
                        width = 2.dp,
                        color = if (isActive) Color.White else Color.Transparent,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) Color.White else Color(0xFF78909C),
                    modifier = Modifier.size(26.dp)
                )
            }

            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    !enabled -> Color.Gray
                    isActive -> activeColor
                    else -> GBRDarkIndigo
                },
                textAlign = TextAlign.Center
            )

            Text(
                text = when {
                    !enabled -> "مغلق"
                    isActive -> "● جاري العمل"
                    else -> "اضغط مع الاستمرار"
                },
                fontSize = 10.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    !enabled -> Color.Gray
                    isActive -> activeColor
                    else -> Color.DarkGray
                },
                textAlign = TextAlign.Center
            )
        }
    }
}

// =========================================================================
// LINE 2 HYDRAULIC LIFT / LOWER SYSTEM CARD (RELAY 4 & 5)
// =========================================================================
@Composable
fun Line2HydraulicCard(
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit
) {
    val relay4Active = lineStatus?.relays?.find { it.id == 4 }?.state == true
    val relay5Active = lineStatus?.relays?.find { it.id == 5 }?.state == true

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, Color(0xFFCFD8DC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("line2_hydraulic_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Title & Active Movement Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    relay4Active -> Color(0xFFE8F5E9)
                                    relay5Active -> Color(0xFFE3F2FD)
                                    else -> Color(0xFFECEFF1)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                relay4Active -> Icons.Default.KeyboardArrowUp
                                relay5Active -> Icons.Default.KeyboardArrowDown
                                else -> Icons.Default.Build
                            },
                            contentDescription = null,
                            tint = when {
                                relay4Active -> Color(0xFF2E7D32)
                                relay5Active -> GBRBlueMain
                                else -> Color(0xFF546E7A)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "نظام الرفع الهيدروليكي",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = GBRDarkIndigo
                        )
                        Text(
                            text = "ضغط مستمر لحظي (Momentary)",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                // Dynamic Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            when {
                                relay4Active -> Color(0xFFE8F5E9)
                                relay5Active -> Color(0xFFE3F2FD)
                                else -> Color(0xFFECEFF1)
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                relay4Active -> Color(0xFF81C784)
                                relay5Active -> Color(0xFF90CAF9)
                                else -> Color(0xFFCFD8DC)
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = when {
                            relay4Active -> "يرفع الآن ⬆️"
                            relay5Active -> "ينزل الآن ⬇️"
                            else -> "متوقف"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = when {
                            relay4Active -> Color(0xFF1B5E20)
                            relay5Active -> Color(0xFF1565C0)
                            else -> Color(0xFF546E7A)
                        }
                    )
                }
            }

            // Two Momentary Buttons Row: Relay 4 (Lift) & Relay 5 (Lower)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Relay 4: اضغط للرفع ↑
                Box(modifier = Modifier.weight(1f)) {
                    MomentaryHoldButton(
                        relayId = 4,
                        label = "اضغط للرفع ↑",
                        subLabel = "هيدروليك",
                        icon = Icons.Default.KeyboardArrowUp,
                        activeColor = Color(0xFF2E7D32),
                        defaultColor = Color(0xFF43A047),
                        isRelayStateActive = relay4Active,
                        enabled = isControllerOnline,
                        currentLineIp = currentLineIp,
                        testTag = "hydraulic_lift_btn",
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate
                    )
                }

                // Relay 5: اضغط للخفض ↓
                Box(modifier = Modifier.weight(1f)) {
                    MomentaryHoldButton(
                        relayId = 5,
                        label = "اضغط للخفض ↓",
                        subLabel = "هيدروليك",
                        icon = Icons.Default.KeyboardArrowDown,
                        activeColor = Color(0xFF1565C0),
                        defaultColor = Color(0xFF1E88E5),
                        isRelayStateActive = relay5Active,
                        enabled = isControllerOnline,
                        currentLineIp = currentLineIp,
                        testTag = "hydraulic_lower_btn",
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate
                    )
                }
            }

            Text(
                text = "⚡ التحكم مباشر ولحظي: يعمل الهيدروليك أثناء الضغط المستمر ويتوقف فور رفع الإصبع.",
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// =========================================================================
// LINE 2 SPEED CONTROL CARD (RELAY 7 & 8 - INTERLOCKED WITH MOTOR_RUNNING)
// =========================================================================
@Composable
fun Line2SpeedControlCard(
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit
) {
    val isMotorRunning = lineStatus?.motor_running == true
    val relay7Active = lineStatus?.relays?.find { it.id == 7 }?.state == true
    val relay8Active = lineStatus?.relays?.find { it.id == 8 }?.state == true

    // Safety Interlock: Speed adjustment is strictly forbidden unless motor is running
    val isSpeedControlEnabled = isControllerOnline && isMotorRunning

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMotorRunning) Color.White else Color(0xFFFAFAFA)
        ),
        border = BorderStroke(
            1.5.dp,
            if (isMotorRunning) Color(0xFFCFD8DC) else Color(0xFFFFCC80)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("line2_speed_control_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Title & Status Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (isMotorRunning) Color(0xFFFFF3E0) else Color(0xFFECEFF1)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = if (isMotorRunning) Color(0xFFE65100) else Color(0xFF78909C),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "التحكم بالسرعة",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = GBRDarkIndigo
                        )
                        Text(
                            text = "بكرات ميكانيكية متغيرة",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            when {
                                !isMotorRunning -> Color(0xFFFFEBEE)
                                relay7Active || relay8Active -> Color(0xFFFFF3E0)
                                else -> Color(0xFFE8F5E9)
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = when {
                                !isMotorRunning -> Color(0xFFFFCDD2)
                                relay7Active || relay8Active -> Color(0xFFFFB74D)
                                else -> Color(0xFFA5D6A7)
                            },
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = when {
                            !isMotorRunning -> "🔒 مقفل (المحرك متوقف)"
                            relay7Active -> "جاري الزيادة ⏩"
                            relay8Active -> "جاري الخفض ⏪"
                            else -> "جاهز للضبط 🟢"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = when {
                            !isMotorRunning -> Color(0xFFC62828)
                            relay7Active || relay8Active -> Color(0xFFE65100)
                            else -> Color(0xFF2E7D32)
                        }
                    )
                }
            }

            // Safety Warning Banner when motor is NOT running
            if (!isMotorRunning) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFF8E1),
                    border = BorderStroke(1.dp, Color(0xFFFFD54F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "⚠️ التحكم بالسرعة مغلق: يجب تشغيل المحرك أولاً. تغيير السرعة يتم ميكانيكيًا عبر انتقال بكرات لا تتحرك إلا والمحرك دائر فعليًا.",
                            fontSize = 11.sp,
                            color = Color(0xFFBF360C),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // Two Momentary Buttons Row: Relay 7 (Increase) & Relay 8 (Decrease)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Relay 7: اضغط للزيادة
                Box(modifier = Modifier.weight(1f)) {
                    MomentaryHoldButton(
                        relayId = 7,
                        label = "اضغط للزيادة",
                        subLabel = "السرعة",
                        icon = Icons.Default.Add,
                        activeColor = Color(0xFFE65100),
                        defaultColor = Color(0xFFF57C00),
                        isRelayStateActive = relay7Active,
                        enabled = isSpeedControlEnabled,
                        disabledReason = "لا يمكن ضبط السرعة والمحرك متوقف! شغّل المحرك أولاً.",
                        currentLineIp = currentLineIp,
                        testTag = "speed_increase_btn",
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate
                    )
                }

                // Relay 8: اضغط للخفض
                Box(modifier = Modifier.weight(1f)) {
                    MomentaryHoldButton(
                        relayId = 8,
                        label = "اضغط للخفض",
                        subLabel = "السرعة",
                        icon = Icons.Default.KeyboardArrowDown,
                        activeColor = Color(0xFF5D4037),
                        defaultColor = Color(0xFF795548),
                        isRelayStateActive = relay8Active,
                        enabled = isSpeedControlEnabled,
                        disabledReason = "لا يمكن ضبط السرعة والمحرك متوقف! شغّل المحرك أولاً.",
                        currentLineIp = currentLineIp,
                        testTag = "speed_decrease_btn",
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate
                    )
                }
            }

            Text(
                text = "⚙️ ضبط السرعة لحظي: يستمر تغيير السرعة بالضغط المستمر، ويتوقف فور ترك الزر.",
                fontSize = 11.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// =========================================================================
// LINE 2 DEDICATED WATER VALVE CARD (RELAY 1)
// =========================================================================
@Composable
fun Line2WaterValveCard(
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    onScaleDisconnected: () -> Unit,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit
) {
    val context = LocalContext.current
    val relay1State = lineStatus?.relays?.find { it.id == 1 }?.state ?: false

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, Color(0xFFCFD8DC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("line2_water_valve_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (relay1State) Color(0xFFE0F7FA) else Color(0xFFECEFF1)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = null,
                        tint = if (relay1State) Color(0xFF00838F) else Color(0xFF78909C),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Text(
                        text = "صمام تزويد المياه (المخرج 1)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = GBRDarkIndigo
                    )
                    Text(
                        text = if (relay1State) "الصمام مفتوح حاليًا 💧" else "الصمام مغلق",
                        fontSize = 11.sp,
                        color = if (relay1State) Color(0xFF00838F) else Color.Gray,
                        fontWeight = if (relay1State) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Button(
                onClick = {
                    if (currentLineIp.isBlank()) {
                        Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val nextState = if (relay1State) "off" else "on"
                    sendCommand(
                        resolveUrl(currentLineIp, "/control?relay=1&state=$nextState"),
                        { updated ->
                            if (updated != null) onStatusUpdate(updated)
                            Toast.makeText(context, if (nextState == "on") "تم فتح صمام الماء 💧" else "تم إغلاق صمام الماء 🛑", Toast.LENGTH_SHORT).show()
                        },
                        { err ->
                            val msg = err.message ?: ""
                            if (msg.contains("الميزان غير متصل") || msg.contains("409") || msg.contains("scale", ignoreCase = true)) {
                                onScaleDisconnected()
                            } else {
                                Toast.makeText(context, "فشل التحكم بالصمام: $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                },
                enabled = isControllerOnline,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (relay1State) Color(0xFFC62828) else Color(0xFF00838F)
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(44.dp)
                    .testTag("line2_water_valve_toggle_btn")
            ) {
                Icon(
                    imageVector = if (relay1State) Icons.Default.Close else Icons.Default.WaterDrop,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (relay1State) "إغلاق الصمام" else "فتح الصمام",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}


@Composable
fun Line2MotorCard(
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit
) {
    val context = LocalContext.current
    var minutesInput by remember { mutableStateOf("") }

    val isRunning = lineStatus?.motor_running == true
    val runtimeSeconds = lineStatus?.motor_runtime_seconds ?: 0L
    val isTimedActive = lineStatus?.motor_timed_run_active == true
    val remainingSeconds = lineStatus?.motor_timed_run_remaining_seconds ?: 0L
    val isTimedPaused = lineStatus?.motor_timed_run_paused == true
    val needsConfirm = lineStatus?.motor_timed_run_needs_confirm == true

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.5.dp, Color(0xFFCFD8DC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("line2_motor_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Row: Title & Colored Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isRunning) Color(0xFFE8F5E9) else Color(0xFFECEFF1)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = if (isRunning) Color(0xFF2E7D32) else Color(0xFF546E7A),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "المحرك",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = GBRDarkIndigo
                        )
                        Text(
                            text = "مستشعر الدوران الفعلي (ESP32-S3)",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                // Colored Status Badge based strictly on motor_running
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (isRunning) Color(0xFFE8F5E9) else Color(0xFFECEFF1)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isRunning) Color(0xFF81C784) else Color(0xFFCFD8DC),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF2E7D32) else Color(0xFF78909C))
                        )
                        Text(
                            text = if (isRunning) "يعمل (ON)" else "متوقف (OFF)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isRunning) Color(0xFF1B5E20) else Color(0xFF455A64)
                        )
                    }
                }
            }

            // Current Runtime when motor is running (motor_runtime_seconds)
            if (isRunning) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFE8F5E9).copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "مدة التشغيل الحالية:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF1B5E20)
                            )
                        }
                        Text(
                            text = "${formatMotorRuntime(runtimeSeconds)} دقيقة",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                    }
                }
            }

            // Manual Start & Stop Buttons (No conditional blocking - always enabled when online)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Start Button -> /control?relay=2&state=on
                Button(
                    onClick = {
                        if (currentLineIp.isBlank()) {
                            Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        sendCommand(
                            resolveUrl(currentLineIp, "/control?relay=2&state=on"),
                            { updated ->
                                if (updated != null) onStatusUpdate(updated)
                                Toast.makeText(context, "تم إرسال أمر تشغيل المحرك 🟢", Toast.LENGTH_SHORT).show()
                            },
                            { err ->
                                Toast.makeText(context, "فشل إرسال أمر التشغيل: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    enabled = isControllerOnline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        disabledContainerColor = Color(0xFFE0E0E0),
                        disabledContentColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("motor_start_pulse_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isControllerOnline) Color.White else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "تشغيل",
                        fontWeight = FontWeight.Bold,
                        color = if (isControllerOnline) Color.White else Color.Gray,
                        fontSize = 14.sp
                    )
                }

                // Stop Button -> /control?relay=3&state=on
                Button(
                    onClick = {
                        if (currentLineIp.isBlank()) {
                            Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        sendCommand(
                            resolveUrl(currentLineIp, "/control?relay=3&state=on"),
                            { updated ->
                                if (updated != null) onStatusUpdate(updated)
                                Toast.makeText(context, "تم إرسال أمر إيقاف المحرك 🛑", Toast.LENGTH_SHORT).show()
                            },
                            { err ->
                                Toast.makeText(context, "فشل إرسال أمر الإيقاف: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    enabled = isControllerOnline,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),
                        disabledContainerColor = Color(0xFFE0E0E0),
                        disabledContentColor = Color.Gray
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("motor_stop_pulse_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = if (isControllerOnline) Color.White else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "إيقاف",
                        fontWeight = FontWeight.Bold,
                        color = if (isControllerOnline) Color.White else Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }

            HorizontalDivider(color = Color(0xFFECEFF1), thickness = 1.dp)

            // Timed Run Section & Active Status Monitoring
            if (!isTimedActive) {
                // Input form for Timed Run
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "⏱️ تشغيل المحرك لمدة محددة (بالدقائق)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = GBRDarkIndigo
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = minutesInput,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() || it == '.' || it in '٠'..'٩' }) {
                                    minutesInput = input
                                }
                            },
                            label = { Text("عدد الدقائق", fontSize = 11.sp) },
                            placeholder = { Text("مثال: 15", fontSize = 11.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("motor_timed_minutes_input")
                        )

                        Button(
                            onClick = {
                                if (currentLineIp.isBlank()) {
                                    Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val normalizedInput = minutesInput.trim()
                                    .replace('٠', '0').replace('١', '1').replace('٢', '2')
                                    .replace('٣', '3').replace('٤', '4').replace('٥', '5')
                                    .replace('٦', '6').replace('٧', '7').replace('٨', '8')
                                    .replace('٩', '9')
                                val mins = normalizedInput.toDoubleOrNull()
                                if (mins == null || mins <= 0.0) {
                                    Toast.makeText(context, "الرجاء إدخال عدد دقائق صحيح أكبر من الصفر", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val minsParam = if (mins % 1.0 == 0.0) mins.toInt().toString() else mins.toString()
                                sendCommand(
                                    resolveUrl(currentLineIp, "/motor/start-timed?minutes=$minsParam"),
                                    { updated ->
                                        if (updated != null) onStatusUpdate(updated)
                                        Toast.makeText(context, "تم بدء تشغيل المحرك لمدة $minsParam دقيقة ⏱️", Toast.LENGTH_SHORT).show()
                                    },
                                    { err ->
                                        Toast.makeText(context, "فشل بدء التشغيل بمدة: ${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            enabled = isControllerOnline,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GBRBlueMain,
                                disabledContainerColor = Color(0xFFE0E0E0),
                                disabledContentColor = Color.Gray
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("motor_start_timed_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تشغيل بمدة محددة", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                // Timed Run Active: Display Countdown, Paused (Case A), or Sensor Stop Alert (Case B)
                when {
                    // Case B: Unexpected Stop Detected by Sensor (motor_timed_run_needs_confirm = true)
                    needsConfirm -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFEBEE),
                            border = BorderStroke(2.dp, Color(0xFFD32F2F)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("motor_timed_needs_confirm_alert")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFD32F2F),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "⚠️ تم إيقاف المحرك يدويًا أثناء تشغيل مستهدف",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFFB71C1C)
                                        )
                                        Text(
                                            text = "تأكد أن المحرك آمن للاستكمال قبل المتابعة.",
                                            fontSize = 11.sp,
                                            color = Color(0xFFC62828),
                                            lineHeight = 16.sp
                                        )
                                        Text(
                                            text = "الوقت المتبقي: ${formatMotorRuntime(remainingSeconds)} دقيقة",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFB71C1C)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Confirm & Resume Button -> /motor/resume
                                    Button(
                                        onClick = {
                                            if (currentLineIp.isBlank()) return@Button
                                            sendCommand(
                                                resolveUrl(currentLineIp, "/motor/resume"),
                                                { updated ->
                                                    if (updated != null) onStatusUpdate(updated)
                                                    Toast.makeText(context, "تم تأكيد الأمان واستكمال تشغيل المحرك ▶️", Toast.LENGTH_SHORT).show()
                                                },
                                                { err ->
                                                    Toast.makeText(context, "فشل استكمال التشغيل: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        },
                                        enabled = isControllerOnline,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1.2f)
                                            .height(44.dp)
                                            .testTag("motor_timed_confirm_resume_btn")
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("تأكيد والاستكمال", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 11.sp)
                                    }

                                    // Cancel Timed Run Button -> /motor/cancel-timed
                                    OutlinedButton(
                                        onClick = {
                                            if (currentLineIp.isBlank()) return@OutlinedButton
                                            sendCommand(
                                                resolveUrl(currentLineIp, "/motor/cancel-timed"),
                                                { updated ->
                                                    if (updated != null) onStatusUpdate(updated)
                                                    Toast.makeText(context, "تم إلغاء التشغيل المستهدف 🛑", Toast.LENGTH_SHORT).show()
                                                },
                                                { err ->
                                                    Toast.makeText(context, "فشل إلغاء التشغيل: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        },
                                        enabled = isControllerOnline,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                                        border = BorderStroke(1.dp, Color(0xFFD32F2F)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("motor_timed_cancel_b_btn")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إلغاء التشغيل", fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Case A: Paused from App/Computer (motor_timed_run_paused = true && !needs_confirm)
                    isTimedPaused -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFF3E0),
                            border = BorderStroke(1.5.dp, Color(0xFFFFB74D)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("motor_timed_paused_box")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = null,
                                        tint = Color(0xFFE65100),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "متوقف مؤقتًا - متبقي ${formatMotorRuntime(remainingSeconds)} دقيقة",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = Color(0xFFE65100)
                                        )
                                        Text(
                                            text = "تم الإيقاف من التطبيق أثناء التشغيل المستهدف.",
                                            fontSize = 10.sp,
                                            color = Color(0xFFBF360C)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Resume Button -> /motor/resume
                                    Button(
                                        onClick = {
                                            if (currentLineIp.isBlank()) return@Button
                                            sendCommand(
                                                resolveUrl(currentLineIp, "/motor/resume"),
                                                { updated ->
                                                    if (updated != null) onStatusUpdate(updated)
                                                    Toast.makeText(context, "تم استكمال تشغيل المحرك ▶️", Toast.LENGTH_SHORT).show()
                                                },
                                                { err ->
                                                    Toast.makeText(context, "فشل استكمال التشغيل: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        },
                                        enabled = isControllerOnline,
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("motor_timed_resume_btn")
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("استكمال", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                                    }

                                    // Cancel Button -> /motor/cancel-timed
                                    OutlinedButton(
                                        onClick = {
                                            if (currentLineIp.isBlank()) return@OutlinedButton
                                            sendCommand(
                                                resolveUrl(currentLineIp, "/motor/cancel-timed"),
                                                { updated ->
                                                    if (updated != null) onStatusUpdate(updated)
                                                    Toast.makeText(context, "تم إلغاء التشغيل المستهدف 🛑", Toast.LENGTH_SHORT).show()
                                                },
                                                { err ->
                                                    Toast.makeText(context, "فشل إلغاء التشغيل: ${err.message}", Toast.LENGTH_LONG).show()
                                                }
                                            )
                                        },
                                        enabled = isControllerOnline,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                                        border = BorderStroke(1.dp, Color(0xFFEF9A9A)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .testTag("motor_timed_cancel_a_btn")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("إلغاء", fontWeight = FontWeight.Bold, color = Color(0xFFC62828), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Active Countdown Running
                    else -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFE3F2FD),
                            border = BorderStroke(1.dp, Color(0xFF90CAF9)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("motor_timed_active_box")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = GBRBlueMain,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Text(
                                            text = "⏳ تشغيل مستهدف نشط",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = GBRDarkIndigo
                                        )
                                    }

                                    // Countdown Timer Value
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(GBRBlueMain)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "متبقي: ${formatMotorRuntime(remainingSeconds)} دقيقة",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                // Cancel Timed Run Button -> /motor/cancel-timed
                                Button(
                                    onClick = {
                                        if (currentLineIp.isBlank()) return@Button
                                        sendCommand(
                                            resolveUrl(currentLineIp, "/motor/cancel-timed"),
                                            { updated ->
                                                if (updated != null) onStatusUpdate(updated)
                                                Toast.makeText(context, "تم إلغاء التشغيل المستهدف 🛑", Toast.LENGTH_SHORT).show()
                                            },
                                            { err ->
                                                Toast.makeText(context, "فشل إلغاء التشغيل: ${err.message}", Toast.LENGTH_LONG).show()
                                            }
                                        )
                                    },
                                    enabled = isControllerOnline,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                        .testTag("motor_cancel_timed_active_btn")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("إلغاء التشغيل المستهدف", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


// =========================================================================
// HIGH-SPEED DISPERSER (مشتت تشتيت عالي السرعة) - EXACT INDUSTRIAL MODEL
// =========================================================================
@Composable
fun InteractiveMixerVisual(
    lineStatus: LineStatus?,
    isControllerOnline: Boolean,
    onWaterValveClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isMotorRunning = lineStatus?.motor_running == true
    val relay1Water = lineStatus?.relays?.find { it.id == 1 }?.state == true
    val relay4Lift = lineStatus?.relays?.find { it.id == 4 }?.state == true
    val relay5Lower = lineStatus?.relays?.find { it.id == 5 }?.state == true
    val relay7SpeedUp = lineStatus?.relays?.find { it.id == 7 }?.state == true
    val relay8SpeedDown = lineStatus?.relays?.find { it.id == 8 }?.state == true
    val currentWeight = (lineStatus?.weight ?: 0.0).coerceAtLeast(0.0)
    val targetFillWeight = lineStatus?.fill_target?.takeIf { it > 1.0 } ?: 25.0
    val fillFraction = (currentWeight / targetFillWeight).coerceIn(0.0, 1.0).toFloat()
    val fillPercent = (fillFraction * 100).toInt()

    val infiniteTransition = rememberInfiniteTransition(label = "disperser_anim")

    // High-speed Cowles disc rotation angle
    val bladeAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when {
                    relay7SpeedUp -> 240
                    relay8SpeedDown -> 750
                    else -> 420
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "cowles_spin"
    )

    // Water inflow cascade
    val waterCascade by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "water_cascade"
    )

    // Hydraulic motion pulse
    val hydraulicPulse by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hydraulic_pulse"
    )

    // High-shear vortex pulse & motor vibration
    val motorPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "motor_pulse"
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), // Deep industrial slate
        border = BorderStroke(1.5.dp, Color(0xFF334155)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(238.dp)
            .testTag("interactive_mixer_visual_card")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Floor / Factory Ground Line
                val groundY = h * 0.92f
                drawLine(
                    color = Color(0xFF334155),
                    start = Offset(w * 0.04f, groundY),
                    end = Offset(w * 0.96f, groundY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // High-Speed Disperser Colors (Matching user's machine photo)
                val disperserBlue = Color(0xFF1D4ED8) // Heavy Industrial Machine Blue
                val disperserBlueDark = Color(0xFF1E3A8A)
                val disperserBlueLight = Color(0xFF3B82F6)
                val chromeSteel = Color(0xFFE2E8F0)
                val steelStroke = Color(0xFF64748B)

                // Dynamic vertical lift offset when hydraulic lift/lower is engaged
                val liftOffset = when {
                    relay4Lift -> -14.dp.toPx() * hydraulicPulse
                    relay5Lower -> 5.dp.toPx() * hydraulicPulse
                    else -> 0f
                }

                // -------------------------------------------------------------
                // 1. CIRCULAR BASE FLANGE & STIFFENER GUSSETS (قاعدة المشتت السفلية)
                // -------------------------------------------------------------
                val columnCenterX = w * 0.43f
                val baseFlangeWidth = 90.dp.toPx()
                val baseFlangeHeight = 12.dp.toPx()

                // Circular Flange Plate Base on Floor
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(disperserBlueLight, disperserBlueDark),
                        startY = groundY - baseFlangeHeight,
                        endY = groundY
                    ),
                    topLeft = Offset(columnCenterX - baseFlangeWidth / 2f, groundY - baseFlangeHeight),
                    size = Size(baseFlangeWidth, baseFlangeHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )

                // Triangular Gussets (أعصاب التقوية الملحومة على القاعدة)
                val gussetLeft = Path().apply {
                    moveTo(columnCenterX - baseFlangeWidth * 0.44f, groundY - 2.dp.toPx())
                    lineTo(columnCenterX - 14.dp.toPx(), groundY - 2.dp.toPx())
                    lineTo(columnCenterX - 14.dp.toPx(), groundY - 32.dp.toPx())
                    close()
                }
                drawPath(gussetLeft, color = disperserBlue)

                val gussetRight = Path().apply {
                    moveTo(columnCenterX + baseFlangeWidth * 0.44f, groundY - 2.dp.toPx())
                    lineTo(columnCenterX + 14.dp.toPx(), groundY - 2.dp.toPx())
                    lineTo(columnCenterX + 14.dp.toPx(), groundY - 32.dp.toPx())
                    close()
                }
                drawPath(gussetRight, color = disperserBlueDark)

                // -------------------------------------------------------------
                // 2. HYDRAULIC POWER UNIT (HPU) ON THE LEFT BASE (وحدة الهيدروليك)
                // -------------------------------------------------------------
                val hpuLeft = columnCenterX - baseFlangeWidth * 0.50f - 44.dp.toPx()
                val hpuWidth = 38.dp.toPx()
                val hpuHeight = 32.dp.toPx()
                val hpuTop = groundY - hpuHeight

                // Hydraulic Oil Reservoir Box
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(disperserBlueLight, disperserBlueDark)),
                    topLeft = Offset(hpuLeft, hpuTop),
                    size = Size(hpuWidth, hpuHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                drawRoundRect(
                    color = steelStroke,
                    topLeft = Offset(hpuLeft, hpuTop),
                    size = Size(hpuWidth, hpuHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )

                // HPU Lifting Handle
                val handlePath = Path().apply {
                    moveTo(hpuLeft + 12.dp.toPx(), hpuTop + 14.dp.toPx())
                    lineTo(hpuLeft + 12.dp.toPx(), hpuTop + 22.dp.toPx())
                    lineTo(hpuLeft + 26.dp.toPx(), hpuTop + 22.dp.toPx())
                    lineTo(hpuLeft + 26.dp.toPx(), hpuTop + 14.dp.toPx())
                }
                drawPath(handlePath, color = Color(0xFF94A3B8), style = Stroke(width = 1.8.dp.toPx()))

                // Hydraulic Pump Motor (Mounted on top of reservoir)
                val pumpMotorWidth = 14.dp.toPx()
                val pumpMotorHeight = 18.dp.toPx()
                val pumpMotorLeft = hpuLeft + 18.dp.toPx()
                val pumpMotorTop = hpuTop - pumpMotorHeight

                drawRoundRect(
                    color = Color(0xFF1E3A8A),
                    topLeft = Offset(pumpMotorLeft, pumpMotorTop),
                    size = Size(pumpMotorWidth, pumpMotorHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Solenoid Valve Block on top of pump motor
                val valveBlockTop = pumpMotorTop - 9.dp.toPx()
                drawRoundRect(
                    color = if (relay4Lift || relay5Lower) Color(0xFF00E676) else Color(0xFF16A34A),
                    topLeft = Offset(pumpMotorLeft - 3.dp.toPx(), valveBlockTop),
                    size = Size(10.dp.toPx(), 9.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Flexible High-Pressure Hydraulic Hose to Column Base
                val hosePath = Path().apply {
                    moveTo(pumpMotorLeft + 4.dp.toPx(), valveBlockTop + 4.dp.toPx())
                    cubicTo(
                        hpuLeft + hpuWidth + 12.dp.toPx(), valveBlockTop + 20.dp.toPx(),
                        columnCenterX - 24.dp.toPx(), groundY - 14.dp.toPx(),
                        columnCenterX - 12.dp.toPx(), groundY - 16.dp.toPx()
                    )
                }
                drawPath(
                    path = hosePath,
                    color = if (relay4Lift || relay5Lower) Color(0xFF38BDF8) else Color(0xFF0F172A),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )

                // -------------------------------------------------------------
                // 3. MAIN VERTICAL COLUMN & CHROME HYDRAULIC PISTON (العمود والمكبس)
                // -------------------------------------------------------------
                val columnWidth = 26.dp.toPx()
                val columnTop = h * 0.38f + (liftOffset * 0.20f)
                val columnHeight = groundY - columnTop

                // Lower Blue Outer Column Sleeve
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(disperserBlueDark, disperserBlueLight, disperserBlueDark),
                        startX = columnCenterX - columnWidth / 2f,
                        endX = columnCenterX + columnWidth / 2f
                    ),
                    topLeft = Offset(columnCenterX - columnWidth / 2f, columnTop),
                    size = Size(columnWidth, columnHeight),
                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                )
                // Collar / Clamp Flange on top of lower column
                drawRoundRect(
                    color = disperserBlueDark,
                    topLeft = Offset(columnCenterX - (columnWidth + 8.dp.toPx()) / 2f, columnTop - 5.dp.toPx()),
                    size = Size(columnWidth + 8.dp.toPx(), 8.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Polished Chrome Hydraulic Piston Ram (المكبس الهيدروليكي الكروم الصاعد)
                val chromeRamWidth = 18.dp.toPx()
                val bridgeBottomY = h * 0.20f + liftOffset
                val chromeRamTop = bridgeBottomY + 4.dp.toPx()
                val chromeRamHeight = (columnTop - chromeRamTop).coerceAtLeast(10.dp.toPx())

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color(0xFFCBD5E1), Color(0xFFF8FAFC), Color(0xFF94A3B8)),
                        startX = columnCenterX - chromeRamWidth / 2f,
                        endX = columnCenterX + chromeRamWidth / 2f
                    ),
                    topLeft = Offset(columnCenterX - chromeRamWidth / 2f, chromeRamTop),
                    size = Size(chromeRamWidth, chromeRamHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Hydraulic Lift / Lower Motion Indicator Arrows on the Chrome Ram
                if (relay4Lift) {
                    for (k in 0..2) {
                        val arrY = chromeRamTop + chromeRamHeight * (0.25f + k * 0.28f)
                        val upArrow = Path().apply {
                            moveTo(columnCenterX, arrY - 4.dp.toPx())
                            lineTo(columnCenterX - 4.dp.toPx(), arrY + 3.dp.toPx())
                            lineTo(columnCenterX + 4.dp.toPx(), arrY + 3.dp.toPx())
                            close()
                        }
                        drawPath(upArrow, color = Color(0xFF00E676))
                    }
                } else if (relay5Lower) {
                    for (k in 0..2) {
                        val arrY = chromeRamTop + chromeRamHeight * (0.25f + k * 0.28f)
                        val downArrow = Path().apply {
                            moveTo(columnCenterX, arrY + 4.dp.toPx())
                            lineTo(columnCenterX - 4.dp.toPx(), arrY - 3.dp.toPx())
                            lineTo(columnCenterX + 4.dp.toPx(), arrY - 3.dp.toPx())
                            close()
                        }
                        drawPath(downArrow, color = Color(0xFF00B0FF))
                    }
                }

                // Parallel Chrome Guide Rod (عصا التثبيت والتوجيه الجانبية)
                val guideRodX = columnCenterX + columnWidth / 2f + 12.dp.toPx()
                drawLine(
                    color = Color(0xFFCBD5E1),
                    start = Offset(guideRodX, bridgeBottomY + 8.dp.toPx()),
                    end = Offset(guideRodX, groundY - 10.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // -------------------------------------------------------------
                // 4. OVERHEAD CANTILEVER ARM / BRIDGE (الجسر والذراع العلوي)
                // -------------------------------------------------------------
                val armLeft = w * 0.12f
                val armRight = w * 0.88f
                val armWidth = armRight - armLeft
                val armHeight = 18.dp.toPx()
                val armTop = bridgeBottomY - armHeight

                // Heavy Horizontal Beam Housing (Belt Guard & Pulley Enclosure)
                val armPath = Path().apply {
                    moveTo(armLeft + 4.dp.toPx(), armTop)
                    lineTo(armRight - 10.dp.toPx(), armTop)
                    quadraticTo(armRight, armTop, armRight, armTop + 8.dp.toPx())
                    lineTo(armRight, armTop + armHeight - 6.dp.toPx())
                    quadraticTo(armRight, armTop + armHeight, armRight - 8.dp.toPx(), armTop + armHeight)
                    lineTo(armLeft, armTop + armHeight)
                    lineTo(armLeft, armTop + 4.dp.toPx())
                    close()
                }

                drawPath(
                    path = armPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(disperserBlueLight, disperserBlueDark),
                        startY = armTop,
                        endY = armTop + armHeight
                    )
                )
                drawPath(
                    path = armPath,
                    color = Color(0xFF38BDF8).copy(alpha = 0.5f),
                    style = Stroke(width = 1.2.dp.toPx())
                )

                // -------------------------------------------------------------
                // 5. MAIN ELECTRIC DRIVE MOTOR (محرك الدوران الكهربائي الرئيسي)
                // -------------------------------------------------------------
                val motorCenterX = w * 0.24f
                val motorWidth = 32.dp.toPx()
                val motorHeight = 44.dp.toPx()
                val motorLeft = motorCenterX - motorWidth / 2f
                val motorTop = armTop + armHeight

                // Flanged Motor Housing
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(disperserBlueDark, disperserBlue, disperserBlueDark),
                        startX = motorLeft,
                        endX = motorLeft + motorWidth
                    ),
                    topLeft = Offset(motorLeft, motorTop),
                    size = Size(motorWidth, motorHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Horizontal Cooling Fins on Motor Body
                for (finIdx in 1..4) {
                    val finY = motorTop + (motorHeight * (finIdx / 5.5f))
                    drawLine(
                        color = Color(0xFF60A5FA),
                        start = Offset(motorLeft + 4.dp.toPx(), finY),
                        end = Offset(motorLeft + motorWidth - 4.dp.toPx(), finY),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }

                // Explosion-Proof Terminal Box on the Left of Motor
                drawRoundRect(
                    color = Color(0xFF1E3A8A),
                    topLeft = Offset(motorLeft - 8.dp.toPx(), motorTop + 8.dp.toPx()),
                    size = Size(8.dp.toPx(), 14.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Motor Running Beacon LED
                val beaconCenter = Offset(motorCenterX, motorTop - 2.dp.toPx())
                if (isMotorRunning) {
                    drawCircle(
                        color = Color(0xFF00E676).copy(alpha = 0.35f * motorPulse),
                        radius = 8.dp.toPx(),
                        center = beaconCenter
                    )
                    drawCircle(
                        color = Color(0xFF00E676),
                        radius = 4.dp.toPx(),
                        center = beaconCenter
                    )
                } else {
                    drawCircle(
                        color = Color(0xFF64748B),
                        radius = 3.dp.toPx(),
                        center = beaconCenter
                    )
                }

                // Speed Variation Chevrons on Belt Guard (Relays 7 & 8)
                if (relay7SpeedUp) {
                    val chevX = columnCenterX + 24.dp.toPx()
                    val chevY = armTop + armHeight / 2f
                    for (c in 0..1) {
                        val path = Path().apply {
                            moveTo(chevX + c * 8.dp.toPx(), chevY - 4.dp.toPx())
                            lineTo(chevX + c * 8.dp.toPx() + 5.dp.toPx(), chevY)
                            lineTo(chevX + c * 8.dp.toPx(), chevY + 4.dp.toPx())
                        }
                        drawPath(path, color = Color(0xFFFF9100), style = Stroke(width = 2.dp.toPx()))
                    }
                } else if (relay8SpeedDown) {
                    val chevX = columnCenterX + 24.dp.toPx()
                    val chevY = armTop + armHeight / 2f
                    for (c in 0..1) {
                        val path = Path().apply {
                            moveTo(chevX + c * 8.dp.toPx() + 5.dp.toPx(), chevY - 4.dp.toPx())
                            lineTo(chevX + c * 8.dp.toPx(), chevY)
                            lineTo(chevX + c * 8.dp.toPx() + 5.dp.toPx(), chevY + 4.dp.toPx())
                        }
                        drawPath(path, color = Color(0xFFFF9100), style = Stroke(width = 2.dp.toPx()))
                    }
                }

                // -------------------------------------------------------------
                // 6. RIGHT SPINDLE BEARING HOUSING (قاعدة عامود الدوران المخروطية)
                // -------------------------------------------------------------
                val shaftX = w * 0.76f
                val spindleTop = armTop + armHeight
                val spindleHeight = 24.dp.toPx()
                val spindleTopWidth = 24.dp.toPx()
                val spindleBottomWidth = 18.dp.toPx()

                // Tapered Spindle Housing (غلاف المحامل المخروطي)
                val spindlePath = Path().apply {
                    moveTo(shaftX - spindleTopWidth / 2f, spindleTop)
                    lineTo(shaftX + spindleTopWidth / 2f, spindleTop)
                    lineTo(shaftX + spindleBottomWidth / 2f, spindleTop + spindleHeight)
                    lineTo(shaftX - spindleBottomWidth / 2f, spindleTop + spindleHeight)
                    close()
                }
                drawPath(
                    path = spindlePath,
                    brush = Brush.verticalGradient(
                        colors = listOf(disperserBlueLight, disperserBlueDark),
                        startY = spindleTop,
                        endY = spindleTop + spindleHeight
                    )
                )

                // -------------------------------------------------------------
                // 7. CHEMICAL MIXING TANK WITH DYNAMIC WEIGHT-LINKED LEVEL (خزان الخلط)
                // -------------------------------------------------------------
                val tankWidth = 56.dp.toPx()
                val tankHeight = 65.dp.toPx()
                val tankLeft = shaftX - tankWidth / 2f
                val tankRight = tankLeft + tankWidth
                val tankBottom = groundY
                val tankTop = tankBottom - tankHeight

                // Tank Outer Body (Stainless Steel Vessel)
                val tankWallPath = Path().apply {
                    moveTo(tankLeft, tankTop)
                    lineTo(tankLeft, tankBottom - 6.dp.toPx())
                    quadraticTo(tankLeft, tankBottom, tankLeft + 6.dp.toPx(), tankBottom)
                    lineTo(tankRight - 6.dp.toPx(), tankBottom)
                    quadraticTo(tankRight, tankBottom, tankRight, tankBottom - 6.dp.toPx())
                    lineTo(tankRight, tankTop)
                }

                // Tank Background Inside
                drawPath(tankWallPath, color = Color(0xFF1E293B))

                // DYNAMIC LIQUID LEVEL LINKED DIRECTLY TO WEIGHT (المنسوب مرتبط بالوزن)
                val maxFluidDepth = tankHeight * 0.85f
                val currentFluidDepth = (maxFluidDepth * fillFraction).coerceAtLeast(if (currentWeight > 0.1) 6.dp.toPx() else 0f)
                val fluidSurfaceY = tankBottom - currentFluidDepth

                if (currentFluidDepth > 0f) {
                    val fluidPath = Path().apply {
                        moveTo(tankLeft + 2.dp.toPx(), fluidSurfaceY)

                        if (isMotorRunning && currentFluidDepth > 10.dp.toPx()) {
                            // High-Shear Vortex (دوامة التشتيت العميقة المميزة لمشتت High-Speed Disperser)
                            val vortexDepth = (currentFluidDepth * 0.45f * motorPulse).coerceAtMost(18.dp.toPx())
                            val halfW = tankWidth / 2f

                            // Curved vortex surface pulling down toward the central shaft
                            quadraticTo(
                                shaftX - halfW * 0.35f, fluidSurfaceY - 2.dp.toPx(),
                                shaftX - 6.dp.toPx(), fluidSurfaceY + vortexDepth * 0.6f
                            )
                            quadraticTo(
                                shaftX, fluidSurfaceY + vortexDepth,
                                shaftX + 6.dp.toPx(), fluidSurfaceY + vortexDepth * 0.6f
                            )
                            quadraticTo(
                                shaftX + halfW * 0.35f, fluidSurfaceY - 2.dp.toPx(),
                                tankRight - 2.dp.toPx(), fluidSurfaceY
                            )
                        } else {
                            lineTo(tankRight - 2.dp.toPx(), fluidSurfaceY)
                        }

                        lineTo(tankRight - 2.dp.toPx(), tankBottom - 6.dp.toPx())
                        quadraticTo(tankRight - 2.dp.toPx(), tankBottom, tankRight - 6.dp.toPx(), tankBottom)
                        lineTo(tankLeft + 6.dp.toPx(), tankBottom)
                        quadraticTo(tankLeft + 2.dp.toPx(), tankBottom, tankLeft + 2.dp.toPx(), tankBottom - 6.dp.toPx())
                        close()
                    }

                    // Liquid Gradient
                    drawPath(
                        path = fluidPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0284C7).copy(alpha = 0.55f),
                                Color(0xFF0369A1).copy(alpha = 0.85f)
                            ),
                            startY = fluidSurfaceY,
                            endY = tankBottom
                        )
                    )

                    // Vortex Cavitation Core rings when running
                    if (isMotorRunning && currentFluidDepth > 12.dp.toPx()) {
                        drawOval(
                            color = Color(0xFF7DD3FC).copy(alpha = 0.50f * motorPulse),
                            topLeft = Offset(shaftX - 12.dp.toPx(), fluidSurfaceY + 2.dp.toPx()),
                            size = Size(24.dp.toPx(), 6.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // Tank Metallic Wall Outline & Rim
                drawPath(
                    path = tankWallPath,
                    color = Color(0xFF94A3B8),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
                // Top Rolled Rim of Tank
                drawRoundRect(
                    color = Color(0xFFCBD5E1),
                    topLeft = Offset(tankLeft - 3.dp.toPx(), tankTop - 2.dp.toPx()),
                    size = Size(tankWidth + 6.dp.toPx(), 4.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Tank Side Handles
                val handleLeftPath = Path().apply {
                    moveTo(tankLeft, tankTop + 14.dp.toPx())
                    lineTo(tankLeft - 4.dp.toPx(), tankTop + 14.dp.toPx())
                    lineTo(tankLeft - 4.dp.toPx(), tankTop + 24.dp.toPx())
                    lineTo(tankLeft, tankTop + 24.dp.toPx())
                }
                drawPath(handleLeftPath, color = Color(0xFFCBD5E1), style = Stroke(width = 2.dp.toPx()))

                val handleRightPath = Path().apply {
                    moveTo(tankRight, tankTop + 14.dp.toPx())
                    lineTo(tankRight + 4.dp.toPx(), tankTop + 14.dp.toPx())
                    lineTo(tankRight + 4.dp.toPx(), tankTop + 24.dp.toPx())
                    lineTo(tankRight, tankTop + 24.dp.toPx())
                }
                drawPath(handleRightPath, color = Color(0xFFCBD5E1), style = Stroke(width = 2.dp.toPx()))

                // Tank Caster Wheels (عجلات أسفل الخزان)
                drawCircle(color = Color(0xFF475569), radius = 2.5.dp.toPx(), center = Offset(tankLeft + 8.dp.toPx(), groundY - 1.dp.toPx()))
                drawCircle(color = Color(0xFF475569), radius = 2.5.dp.toPx(), center = Offset(tankRight - 8.dp.toPx(), groundY - 1.dp.toPx()))

                // -------------------------------------------------------------
                // 8. STAINLESS DISPERSER SHAFT & COWLES SAWTOOTH DISC (القرص المسنن)
                // -------------------------------------------------------------
                val shaftTop = spindleTop + spindleHeight
                // Blade position moves with hydraulic lift
                val cowlesDiscY = (tankBottom - 14.dp.toPx() + liftOffset).coerceIn(shaftTop + 20.dp.toPx(), groundY - 6.dp.toPx())

                // Disperser Stainless Steel Shaft
                drawLine(
                    color = chromeSteel,
                    start = Offset(shaftX, shaftTop),
                    end = Offset(shaftX, cowlesDiscY),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                // Shaft Chrome Specular Shine Line
                drawLine(
                    color = Color.White.copy(alpha = 0.8f),
                    start = Offset(shaftX - 0.8.dp.toPx(), shaftTop),
                    end = Offset(shaftX - 0.8.dp.toPx(), cowlesDiscY),
                    strokeWidth = 1.dp.toPx()
                )

                // Cowles Sawtooth Blade (قرص التشتيت المسنن الدائري)
                val discDiameter = 32.dp.toPx()
                val discRadius = discDiameter / 2f
                val teethCount = 10
                val toothHeight = 3.dp.toPx()

                rotate(degrees = if (isMotorRunning) bladeAngle else 0f, pivot = Offset(shaftX, cowlesDiscY)) {
                    // Central Disc Body
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFF8FAFC), Color(0xFFCBD5E1), Color(0xFF94A3B8)),
                            center = Offset(shaftX, cowlesDiscY),
                            radius = discRadius
                        ),
                        radius = discRadius,
                        center = Offset(shaftX, cowlesDiscY)
                    )

                    // Sawtooth Edges (أسنان التشتيت الحادة للأعلى والأسفل بالتناوب)
                    for (t in 0 until teethCount) {
                        val angleRad = (t * (360f / teethCount)) * (Math.PI / 180.0)
                        val cosA = Math.cos(angleRad).toFloat()
                        val sinA = Math.sin(angleRad).toFloat()

                        val toothBaseX = shaftX + discRadius * cosA
                        val toothBaseY = cowlesDiscY + (discRadius * 0.40f) * sinA // perspective flat disc

                        val toothTipX = shaftX + (discRadius + toothHeight) * cosA
                        val toothTipY = cowlesDiscY + ((discRadius + toothHeight) * 0.40f) * sinA + (if (t % 2 == 0) -toothHeight else toothHeight)

                        drawLine(
                            color = Color(0xFFF8FAFC),
                            start = Offset(toothBaseX, toothBaseY),
                            end = Offset(toothTipX, toothTipY),
                            strokeWidth = 1.8.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                    }

                    // Central Disc Fastening Nut
                    drawCircle(
                        color = Color(0xFF334155),
                        radius = 2.5.dp.toPx(),
                        center = Offset(shaftX, cowlesDiscY)
                    )
                }

                // -------------------------------------------------------------
                // 9. WATER SUPPLY PIPE & SOLENOID VALVE (أنبوب وصمام التعبئة)
                // -------------------------------------------------------------
                val pipeColor = if (relay1Water) Color(0xFF00E5FF) else Color(0xFF64748B)
                val pipeStartX = tankRight - 2.dp.toPx()
                val pipeStartY = tankTop - 18.dp.toPx()
                val pipeNozzleX = tankRight - 8.dp.toPx()
                val pipeNozzleY = tankTop + 2.dp.toPx()

                // Water Pipe Routing into Tank Rim
                val pipePath = Path().apply {
                    moveTo(w * 0.94f, pipeStartY)
                    lineTo(pipeNozzleX, pipeStartY)
                    lineTo(pipeNozzleX, pipeNozzleY)
                }
                drawPath(
                    path = pipePath,
                    color = pipeColor,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                )

                // Solenoid Valve Body
                drawCircle(
                    color = if (relay1Water) Color(0xFF00E5FF) else Color(0xFF475569),
                    radius = 3.5.dp.toPx(),
                    center = Offset(pipeNozzleX + 10.dp.toPx(), pipeStartY)
                )

                // Animated Falling Water Droplets when Water Valve is Open
                if (relay1Water) {
                    val dropDist = (fluidSurfaceY - pipeNozzleY).coerceAtLeast(6.dp.toPx())
                    for (i in 0..2) {
                        val dropFrac = (waterCascade + i * 0.33f) % 1f
                        val dropY = pipeNozzleY + (dropDist * dropFrac)
                        drawCircle(
                            color = Color(0xFF00E5FF).copy(alpha = (1f - dropFrac * 0.2f).coerceIn(0.4f, 1f)),
                            radius = 2.dp.toPx(),
                            center = Offset(pipeNozzleX, dropY)
                        )
                    }
                }
            }

            // =================================================================
            // REAL-TIME OVERLAY INFORMATIONAL BADGES (بيانات الحالة الفورية)
            // =================================================================
            // Top Row Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Machine Identification Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1E293B).copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, Color(0xFF3B82F6))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(11.dp))
                        Text(
                            text = "High-Speed Disperser (مشتت سرعات)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF93C5FD)
                        )
                    }
                }

                // Motor State Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isMotorRunning) Color(0xFF1B5E20).copy(alpha = 0.90f) else Color(0xFF1E293B).copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, if (isMotorRunning) Color(0xFF00E676) else Color(0xFF475569))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isMotorRunning) Color(0xFF00E676) else Color(0xFF94A3B8))
                        )
                        Text(
                            text = if (isMotorRunning) "المحرك: دائر 🟢" else "المحرك: متوقف ⚪",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Bottom Row Badges (Water Valve, Hydraulic Status, Dynamic Weight & Tank Level)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Water Valve & Hydraulic Status Combo Badge
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Water Valve
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (relay1Water) Color(0xFF006064).copy(alpha = 0.92f) else Color(0xFF1E293B).copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, if (relay1Water) Color(0xFF00E5FF) else Color(0xFF475569)),
                        modifier = Modifier.clickable(enabled = isControllerOnline, onClick = onWaterValveClick)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WaterDrop,
                                contentDescription = null,
                                tint = if (relay1Water) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = if (relay1Water) "صمام الماء: مفتوح 💧" else "صمام الماء: مغلق",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (relay1Water) Color(0xFFE0F7FA) else Color(0xFFCBD5E1)
                            )
                        }
                    }

                    // Hydraulic Lift/Lower indicator
                    if (relay4Lift || relay5Lower) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (relay4Lift) Color(0xFF1B5E20).copy(alpha = 0.90f) else Color(0xFF0D47A1).copy(alpha = 0.90f),
                            border = BorderStroke(1.dp, if (relay4Lift) Color(0xFF00E676) else Color(0xFF00B0FF))
                        ) {
                            Text(
                                text = if (relay4Lift) "⬆️ رفع هيدروليك" else "⬇️ تنزيل هيدروليك",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                            )
                        }
                    }
                }

                // Dynamic Tank Liquid Level & Weight Readout Badge (المنسوب مرتبط بالوزن)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (fillPercent > 0) Color(0xFF38BDF8) else Color.Gray)
                        )
                        Text(
                            text = "منسوب الخزان: $fillPercent% (${String.format(Locale.US, "%.2f", currentWeight)} كجم)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// COCKPIT COMPACT WATER VALVE BUTTON
// =========================================================================
@Composable
fun CockpitWaterValveButton(
    isValveOpen: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isValveOpen) Color(0xFF00838F) else Color.White
    val contentColor = if (isValveOpen) Color.White else Color(0xFF37474F)
    val borderColor = if (isValveOpen) Color(0xFF00ACC1) else Color(0xFFCFD8DC)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = BorderStroke(1.5.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isValveOpen) 0.dp else 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("cockpit_water_valve_btn")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isValveOpen) Color.White.copy(alpha = 0.25f) else Color(0xFFE0F7FA)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WaterDrop,
                    contentDescription = null,
                    tint = if (isValveOpen) Color.White else Color(0xFF00838F),
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = if (isValveOpen) "إغلاق الصمام" else "فتح الصمام",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = contentColor,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (isValveOpen) "مفتوح 💧" else "صمام الماء",
                fontSize = 10.sp,
                color = if (isValveOpen) Color.White.copy(alpha = 0.9f) else Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =========================================================================
// COCKPIT TIMED RUN DIALOG WITH INSTANT PRESETS
// =========================================================================
@Composable
fun CockpitTimedRunDialog(
    onDismiss: () -> Unit,
    onStartTimed: (String) -> Unit
) {
    var minutesInput by remember { mutableStateOf("") }
    val presets = listOf("5", "10", "15", "30")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = GBRBlueMain)
                Text(
                    text = "تشغيل المحرك بمؤقت زمني",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = GBRDarkIndigo
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "اختر مدة سريعة أو أدخل عدد الدقائق المطلوبة لتشغيل المحرك تلقائياً:",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )

                // Quick Presets Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (minutesInput == preset) GBRBlueMain else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (minutesInput == preset) GBRBlueMain else Color(0xFFCBD5E1)),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { minutesInput = preset }
                        ) {
                            Text(
                                text = "$preset د",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                color = if (minutesInput == preset) Color.White else Color(0xFF334155),
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                // Custom Minutes Input
                OutlinedTextField(
                    value = minutesInput,
                    onValueChange = { input ->
                        if (input.all { it.isDigit() || it == '.' || it in '٠'..'٩' }) {
                            minutesInput = input
                        }
                    },
                    label = { Text("أو أدخل عدد الدقائق يدوياً", fontSize = 11.sp) },
                    placeholder = { Text("مثال: 20", fontSize = 11.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalized = minutesInput.trim()
                        .replace('٠', '0').replace('١', '1').replace('٢', '2')
                        .replace('٣', '3').replace('٤', '4').replace('٥', '5')
                        .replace('٦', '6').replace('٧', '7').replace('٨', '8')
                        .replace('٩', '9')
                    if (normalized.isNotBlank()) {
                        onStartTimed(normalized)
                    }
                },
                enabled = minutesInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("بدء التشغيل ⏱️", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.Gray)
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = Color.White
    )
}

// =========================================================================
// LINE 2 UNIFIED COCKPIT CONSOLE SCREEN (قمرة التحكم المباشر الموحدة في شاشة واحدة)
// =========================================================================
@Composable
fun Line2UnifiedCockpitScreen(
    currentLineName: String,
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    onBack: () -> Unit,
    onScaleDisconnected: () -> Unit,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    triggerEmergencyStop: () -> Unit,
    onOpenQuickRemote: () -> Unit = {},
    onOpenAutopilot: () -> Unit = {}
) {
    val context = LocalContext.current
    var showCockpitTimedDialog by remember { mutableStateOf(false) }

    val isMotorRunning = lineStatus?.motor_running == true
    val runtimeSeconds = lineStatus?.motor_runtime_seconds ?: 0L
    val isTimedActive = lineStatus?.motor_timed_run_active == true
    val remainingSeconds = lineStatus?.motor_timed_run_remaining_seconds ?: 0L
    val isTimedPaused = lineStatus?.motor_timed_run_paused == true
    val needsConfirm = lineStatus?.motor_timed_run_needs_confirm == true

    val relay1Water = lineStatus?.relays?.find { it.id == 1 }?.state == true
    val relay4Lift = lineStatus?.relays?.find { it.id == 4 }?.state == true
    val relay5Lower = lineStatus?.relays?.find { it.id == 5 }?.state == true
    val relay7SpeedUp = lineStatus?.relays?.find { it.id == 7 }?.state == true
    val relay8SpeedDown = lineStatus?.relays?.find { it.id == 8 }?.state == true

    val toggleWaterValve = {
        if (currentLineIp.isNotBlank()) {
            val nextState = if (relay1Water) "off" else "on"
            sendCommand(
                resolveUrl(currentLineIp, "/control?relay=1&state=$nextState"),
                { updated ->
                    if (updated != null) onStatusUpdate(updated)
                    Toast.makeText(context, if (nextState == "on") "تم فتح صمام الماء 💧" else "تم إغلاق صمام الماء 🛑", Toast.LENGTH_SHORT).show()
                },
                { err ->
                    val msg = err.message ?: ""
                    if (msg.contains("الميزان غير متصل") || msg.contains("409") || msg.contains("scale", ignoreCase = true)) {
                        onScaleDisconnected()
                    } else {
                        Toast.makeText(context, "فشل التحكم بالصمام: $msg", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "قمرة التحكم الموحدة - $currentLineName",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = GBRDarkIndigo,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "ESP32-S3 (تحكم مباشر شامل بدون تمرير)",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("cockpit_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GBRBlueMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    // Quick Remote Switcher Button
                    IconButton(
                        onClick = onOpenQuickRemote,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("cockpit_to_remote_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsRemote,
                            contentDescription = "Quick Remote",
                            tint = Color(0xFF0284C7),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Autopilot Switcher Button
                    IconButton(
                        onClick = onOpenAutopilot,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("cockpit_to_autopilot_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PrecisionManufacturing,
                            contentDescription = "Autopilot",
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Online Status Badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isControllerOnline) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        border = BorderStroke(1.dp, if (isControllerOnline) Color(0xFFA5D6A7) else Color(0xFFFFCDD2)),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = if (isControllerOnline) "متصل 🟢" else "غير متصل 🔴",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isControllerOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Emergency Stop Header Button
                    Button(
                        onClick = triggerEmergencyStop,
                        enabled = isControllerOnline,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .padding(end = 8.dp)
                            .testTag("cockpit_header_estop_btn")
                    ) {
                        Icon(Icons.Default.Dangerous, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("طوارئ 🚨", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF8FAFC))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // =================================================================
            // 1. INTERACTIVE CHEMICAL MIXER GRAPHIC MODEL
            // =================================================================
            InteractiveMixerVisual(
                lineStatus = lineStatus,
                isControllerOnline = isControllerOnline,
                onWaterValveClick = { toggleWaterValve() }
            )

            // =================================================================
            // 2. COMPACT CLUSTERS: HYDRAULICS (MOMENTARY) & WATER VALVE
            // =================================================================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Relay 4: Lift ▲ (Hold-to-run)
                Box(modifier = Modifier.weight(1f)) {
                    MomentaryHoldButton(
                        relayId = 4,
                        label = "رفع ▲",
                        subLabel = "هيدروليك",
                        icon = Icons.Default.KeyboardArrowUp,
                        activeColor = Color(0xFF2E7D32),
                        defaultColor = Color(0xFF43A047),
                        isRelayStateActive = relay4Lift,
                        enabled = isControllerOnline,
                        currentLineIp = currentLineIp,
                        testTag = "cockpit_hydraulic_lift_btn",
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate
                    )
                }

                // Relay 5: Lower ▼ (Hold-to-run)
                Box(modifier = Modifier.weight(1f)) {
                    MomentaryHoldButton(
                        relayId = 5,
                        label = "تنزيل ▼",
                        subLabel = "هيدروليك",
                        icon = Icons.Default.KeyboardArrowDown,
                        activeColor = Color(0xFF1565C0),
                        defaultColor = Color(0xFF1E88E5),
                        isRelayStateActive = relay5Lower,
                        enabled = isControllerOnline,
                        currentLineIp = currentLineIp,
                        testTag = "cockpit_hydraulic_lower_btn",
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate
                    )
                }

                // Relay 1: Water Valve (Toggle)
                Box(modifier = Modifier.weight(1f)) {
                    CockpitWaterValveButton(
                        isValveOpen = relay1Water,
                        enabled = isControllerOnline,
                        onClick = { toggleWaterValve() }
                    )
                }
            }

            // =================================================================
            // 3. MAIN MOTOR COMPACT CONTROLS
            // =================================================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFCFD8DC)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Title & Motor State Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                            Text("المحرك الرئيسي", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GBRDarkIndigo)
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isMotorRunning) Color(0xFFE8F5E9) else Color(0xFFECEFF1)
                        ) {
                            Text(
                                text = if (isMotorRunning) "يعمل (دوران فعلي) 🟢" else "متوقف ⚪",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMotorRunning) Color(0xFF1B5E20) else Color(0xFF546E7A),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Start, Stop, and Timed Run Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Start Button (Relay 2)
                        Button(
                            onClick = {
                                if (currentLineIp.isNotBlank()) {
                                    sendCommand(
                                        resolveUrl(currentLineIp, "/control?relay=2&state=on"),
                                        { updated ->
                                            if (updated != null) onStatusUpdate(updated)
                                            Toast.makeText(context, "تم إرسال أمر تشغيل المحرك 🟢", Toast.LENGTH_SHORT).show()
                                        },
                                        { err -> Toast.makeText(context, "فشل التشغيل: ${err.message}", Toast.LENGTH_LONG).show() }
                                    )
                                }
                            },
                            enabled = isControllerOnline,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(44.dp)
                                .testTag("cockpit_motor_start_btn")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تشغيل ▶", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.5.sp)
                        }

                        // Stop Button (Relay 3)
                        Button(
                            onClick = {
                                if (currentLineIp.isNotBlank()) {
                                    sendCommand(
                                        resolveUrl(currentLineIp, "/control?relay=3&state=on"),
                                        { updated ->
                                            if (updated != null) onStatusUpdate(updated)
                                            Toast.makeText(context, "تم إرسال أمر إيقاف المحرك 🛑", Toast.LENGTH_SHORT).show()
                                        },
                                        { err -> Toast.makeText(context, "فشل الإيقاف: ${err.message}", Toast.LENGTH_LONG).show() }
                                    )
                                }
                            },
                            enabled = isControllerOnline,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(44.dp)
                                .testTag("cockpit_motor_stop_btn")
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إيقاف ⏹", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.5.sp)
                        }

                        // Timed Run Launcher Button
                        OutlinedButton(
                            onClick = { showCockpitTimedDialog = true },
                            enabled = isControllerOnline,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GBRBlueMain),
                            border = BorderStroke(1.5.dp, GBRBlueMain),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("cockpit_motor_timed_btn")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("مؤقت ⏱", fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                        }
                    }

                    // Timed Run Active Controls / Alert Banner (if active)
                    if (isTimedActive) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                needsConfirm -> Color(0xFFFFEBEE)
                                isTimedPaused -> Color(0xFFFFF3E0)
                                else -> Color(0xFFE8F5E9)
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    needsConfirm -> Color(0xFFD32F2F)
                                    isTimedPaused -> Color(0xFFFFB74D)
                                    else -> Color(0xFFA5D6A7)
                                }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = when {
                                            needsConfirm -> "⚠️ تم إيقاف المحرك يدويًا أثناء تشغيل مستهدف"
                                            isTimedPaused -> "⏸️ مؤقت المحرك متوقف مؤقتًا"
                                            else -> "⏱️ تشغيل بمؤقت زمني نشط"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        color = when {
                                            needsConfirm -> Color(0xFFB71C1C)
                                            isTimedPaused -> Color(0xFFE65100)
                                            else -> Color(0xFF1B5E20)
                                        }
                                    )
                                    Text(
                                        text = "متبقي: ${formatMotorRuntime(remainingSeconds)} د",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        color = Color.DarkGray
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (needsConfirm || isTimedPaused) {
                                        Button(
                                            onClick = {
                                                if (currentLineIp.isNotBlank()) {
                                                    sendCommand(
                                                        resolveUrl(currentLineIp, "/motor/resume"),
                                                        { updated -> if (updated != null) onStatusUpdate(updated) },
                                                        { err -> Toast.makeText(context, "فشل الاستكمال: ${err.message}", Toast.LENGTH_SHORT).show() }
                                                    )
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f).height(36.dp)
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("استكمال ▶", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                if (currentLineIp.isNotBlank()) {
                                                    sendCommand(
                                                        resolveUrl(currentLineIp, "/motor/pause"),
                                                        { updated -> if (updated != null) onStatusUpdate(updated) },
                                                        { err -> Toast.makeText(context, "فشل الإيقاف المؤقت: ${err.message}", Toast.LENGTH_SHORT).show() }
                                                    )
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.weight(1f).height(36.dp)
                                        ) {
                                            Icon(Icons.Default.Pause, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("إيقاف مؤقت ⏸", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            if (currentLineIp.isNotBlank()) {
                                                sendCommand(
                                                    resolveUrl(currentLineIp, "/motor/cancel-timed"),
                                                    { updated -> if (updated != null) onStatusUpdate(updated) },
                                                    { err -> Toast.makeText(context, "فشل الإلغاء: ${err.message}", Toast.LENGTH_SHORT).show() }
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD32F2F)),
                                        border = BorderStroke(1.dp, Color(0xFFD32F2F)),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f).height(36.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("إلغاء المؤقت 🛑", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFD32F2F))
                                    }
                                }
                            }
                        }
                    } else if (isMotorRunning) {
                        // Continuous Runtime Pill
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("وقت الدوران الفعلي المستمر:", fontSize = 11.sp, color = Color(0xFF1B5E20))
                            Text("${formatMotorRuntime(runtimeSeconds)} دقيقة", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                        }
                    }
                }
            }

            // =================================================================
            // 4. MECHANICAL SPEED CONTROLS (MOMENTARY - INTERLOCKED)
            // =================================================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isMotorRunning) Color.White else Color(0xFFFAFAFA)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isMotorRunning) Color(0xFFCFD8DC) else Color(0xFFFFCC80)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = if (isMotorRunning) Color(0xFFE65100) else Color.Gray, modifier = Modifier.size(18.dp))
                            Text("التحكم بالسرعة (بكرات ميكانيكية)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GBRDarkIndigo)
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isMotorRunning) Color(0xFFFFF3E0) else Color(0xFFFFEBEE)
                        ) {
                            Text(
                                text = if (isMotorRunning) "جاهز للضبط 🟢" else "🔒 مقفل (المحرك متوقف)",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isMotorRunning) Color(0xFFE65100) else Color(0xFFC62828),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Warning note if motor is stopped
                    if (!isMotorRunning) {
                        Text(
                            text = "⚠️ أزرار السرعة مقفلة: يلزم تشغيل المحرك أولاً لحماية بكرات نقل الحركة الميكانيكية.",
                            fontSize = 10.5.sp,
                            color = Color(0xFFB71C1C),
                            lineHeight = 14.sp
                        )
                    }

                    // Two Speed Momentary Buttons Row: Relay 7 (Inc) & Relay 8 (Dec)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Relay 7: زيادة السرعة +
                        Box(modifier = Modifier.weight(1f)) {
                            MomentaryHoldButton(
                                relayId = 7,
                                label = "زيادة السرعة +",
                                subLabel = "بكرات",
                                icon = Icons.Default.Speed,
                                activeColor = Color(0xFFE65100),
                                defaultColor = Color(0xFFF57C00),
                                isRelayStateActive = relay7SpeedUp,
                                enabled = isControllerOnline && isMotorRunning,
                                disabledReason = if (!isMotorRunning) "يجب تشغيل المحرك أولاً لضبط السرعة ميكانيكياً" else null,
                                currentLineIp = currentLineIp,
                                testTag = "cockpit_speed_inc_btn",
                                sendCommand = sendCommand,
                                onStatusUpdate = onStatusUpdate
                            )
                        }

                        // Relay 8: خفض السرعة -
                        Box(modifier = Modifier.weight(1f)) {
                            MomentaryHoldButton(
                                relayId = 8,
                                label = "خفض السرعة -",
                                subLabel = "بكرات",
                                icon = Icons.Default.Speed,
                                activeColor = Color(0xFFE65100),
                                defaultColor = Color(0xFFF57C00),
                                isRelayStateActive = relay8SpeedDown,
                                enabled = isControllerOnline && isMotorRunning,
                                disabledReason = if (!isMotorRunning) "يجب تشغيل المحرك أولاً لضبط السرعة ميكانيكياً" else null,
                                currentLineIp = currentLineIp,
                                testTag = "cockpit_speed_dec_btn",
                                sendCommand = sendCommand,
                                onStatusUpdate = onStatusUpdate
                            )
                        }
                    }
                }
            }

            // =================================================================
            // 5. INDUSTRIAL EMERGENCY STOP BUTTON (E-STOP)
            // =================================================================
            Button(
                onClick = triggerEmergencyStop,
                enabled = isControllerOnline,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("cockpit_primary_estop_btn")
            ) {
                Icon(Icons.Default.Dangerous, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("🛑 إيقاف طوارئ فوري لكافة المعدات (E-STOP)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    // Timed Run Preset Dialog
    if (showCockpitTimedDialog) {
        CockpitTimedRunDialog(
            onDismiss = { showCockpitTimedDialog = false },
            onStartTimed = { minsParam ->
                showCockpitTimedDialog = false
                if (currentLineIp.isNotBlank()) {
                    sendCommand(
                        resolveUrl(currentLineIp, "/motor/start-timed?minutes=$minsParam"),
                        { updated ->
                            if (updated != null) onStatusUpdate(updated)
                            Toast.makeText(context, "تم بدء تشغيل المحرك لمدة $minsParam دقيقة ⏱️", Toast.LENGTH_SHORT).show()
                        },
                        { err -> Toast.makeText(context, "فشل بدء التشغيل: ${err.message}", Toast.LENGTH_LONG).show() }
                    )
                }
            }
        )
    }
}

// =========================================================================
// LINE 2 QUICK REMOTE SCREEN (صفحة التحكم السريع - نمط ريموت التلفاز)
// =========================================================================
@Composable
fun Line2QuickRemoteScreen(
    currentLineName: String,
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    onBack: () -> Unit,
    onScaleDisconnected: () -> Unit,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    triggerEmergencyStop: () -> Unit,
    onOpenAutopilot: () -> Unit = {}
) {
    val context = LocalContext.current
    var isTransmitting by remember { mutableStateOf(false) }

    val isMotorRunning = lineStatus?.motor_running == true
    val runtimeSeconds = lineStatus?.motor_runtime_seconds ?: 0L
    val relay4Lift = lineStatus?.relays?.find { it.id == 4 }?.state == true
    val relay5Lower = lineStatus?.relays?.find { it.id == 5 }?.state == true
    val relay7SpeedUp = lineStatus?.relays?.find { it.id == 7 }?.state == true
    val relay8SpeedDown = lineStatus?.relays?.find { it.id == 8 }?.state == true

    val currentWeight = (lineStatus?.weight ?: 0.0).coerceAtLeast(0.0)
    val targetFillWeight = lineStatus?.fill_target?.takeIf { it > 1.0 } ?: 25.0
    val fillFraction = (currentWeight / targetFillWeight).coerceIn(0.0, 1.0).toFloat()
    val fillPercent = (fillFraction * 100).toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "ريموت التحكم السريع - $currentLineName",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = GBRDarkIndigo,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "نمط ريموت التلفاز (أزرار اتجاهات ومحرك)",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("quick_remote_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GBRBlueMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    // Switch to Autopilot View
                    IconButton(
                        onClick = onOpenAutopilot,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("quick_remote_to_autopilot_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PrecisionManufacturing,
                            contentDescription = "Autopilot",
                            tint = Color(0xFF6366F1),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Online Status Badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isControllerOnline) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        border = BorderStroke(1.dp, if (isControllerOnline) Color(0xFFA5D6A7) else Color(0xFFFFCDD2)),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = if (isControllerOnline) "متصل 🟢" else "غير متصل 🔴",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isControllerOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Emergency Stop Button in Header
                    Button(
                        onClick = triggerEmergencyStop,
                        enabled = isControllerOnline,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .padding(end = 8.dp)
                            .testTag("quick_remote_header_estop_btn")
                    ) {
                        Icon(Icons.Default.Dangerous, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("طوارئ 🚨", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0A0E17))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // =================================================================
            // HANDHELD REMOTE CHASSIS (جسم ريموت التلفاز الصناعي المريح)
            // =================================================================
            Card(
                shape = RoundedCornerShape(36.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF131B2A)
                ),
                border = BorderStroke(2.dp, Color(0xFF334155)),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Remote Top Cap & Infrared LED Emitter Dome
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isTransmitting) Color(0xFF00E5FF) else Color(0xFF1E293B))
                            .border(
                                width = 2.dp,
                                color = if (isTransmitting) Color(0xFFE0F7FA) else Color(0xFF475569),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isTransmitting) Color.White else Color(0xFF334155))
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Upper Control Row (Emergency Button + Remote Label + Water Valve Button)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick E-Stop Round Button (Like TV Power Button)
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFDC2626),
                            border = BorderStroke(2.dp, Color(0xFFEF4444)),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .size(44.dp)
                                .clickable(enabled = isControllerOnline, onClick = triggerEmergencyStop)
                                .testTag("remote_power_estop_btn")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Dangerous,
                                    contentDescription = "E-STOP",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Industrial Remote Brand Badge
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "HIGH-SPEED DISPERSER",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "REMOTE CONTROL • ESP32-S3",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64748B)
                            )
                        }

                        // Spacer to balance the layout symmetrically with the emergency stop button
                        Spacer(modifier = Modifier.size(44.dp))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. Mini LCD Screen on Remote (شاشة عرض الوزن والحالة الرقمية)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF090E17),
                        border = BorderStroke(1.5.dp, Color(0xFF1E3A5F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "وزن الخزان: ${String.format(Locale.US, "%.2f", currentWeight)} كجم",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF38BDF8)
                                )
                                Text(
                                    text = "المنسوب: $fillPercent%",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (fillPercent > 0) Color(0xFF38BDF8) else Color(0xFF64748B)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isMotorRunning) "المحرك: دائر 🟢" else "المحرك: متوقف ⚪",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isMotorRunning) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                                )
                                if (isMotorRunning && runtimeSeconds > 0) {
                                    Text(
                                        text = "المدة: ${formatMotorRuntime(runtimeSeconds)} د",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFA7F3D0)
                                    )
                                } else {
                                    Text(
                                        text = if (isControllerOnline) "الإشارة: ممتازة ⚡" else "غير متصل ⚠️",
                                        fontSize = 10.5.sp,
                                        color = if (isControllerOnline) Color(0xFF6EE7B7) else Color(0xFFFCA5A5)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 4. THE CORE TV REMOTE D-PAD CONTROLLER (قرص ريموت التلفاز الدائري التفاعلي)
                    TvRemoteDpad(
                        isMotorRunning = isMotorRunning,
                        relay4Lift = relay4Lift,
                        relay5Lower = relay5Lower,
                        relay7SpeedUp = relay7SpeedUp,
                        relay8SpeedDown = relay8SpeedDown,
                        isControllerOnline = isControllerOnline,
                        currentLineIp = currentLineIp,
                        sendCommand = sendCommand,
                        onStatusUpdate = onStatusUpdate,
                        onTransmit = { transmitting -> isTransmitting = transmitting }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // 5. Tactile Operational Instructions Hint
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "👆 اضغط مع الاستمرار: للأزرار الحركية (رفع/تنزيل/سرعة)",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFCBD5E1),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "🔘 ضغطة واحدة بالمنتصف: لتشغيل أو إيقاف المحرك",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFCBD5E1),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 6. Bottom Emergency Stop Button (E-STOP)
                    Button(
                        onClick = triggerEmergencyStop,
                        enabled = isControllerOnline,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(14.dp),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("remote_bottom_estop_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dangerous,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🛑 إيقاف طوارئ فوري (E-STOP)",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

// =========================================================================
// TV REMOTE D-PAD COMPONENT (قرص أزرار ريموت التلفاز الدائري المتكامل)
// =========================================================================
@Composable
fun TvRemoteDpad(
    isMotorRunning: Boolean,
    relay4Lift: Boolean,
    relay5Lower: Boolean,
    relay7SpeedUp: Boolean,
    relay8SpeedDown: Boolean,
    isControllerOnline: Boolean,
    currentLineIp: String,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    onTransmit: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .size(280.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A)),
                    radius = 200f
                )
            )
            .border(2.5.dp, Color(0xFF334155), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // -----------------------------------------------------------------
        // 1. TOP: CH ▲ (أزرار قلب القنوات الرأسية - رفع الهيدروليك - Relay 4)
        // -----------------------------------------------------------------
        RemoteDirectionalButton(
            relayId = 4,
            label = "رفع هيدروليك",
            subLabel = "CH ▲",
            icon = Icons.Default.KeyboardArrowUp,
            activeBgColor = Color(0xFF064E3B),
            activeGlowColor = Color(0xFF00E676),
            idleBgColor = Color(0xFF1E293B),
            idleContentColor = Color(0xFF4ADE80),
            isRelayStateActive = relay4Lift,
            enabled = isControllerOnline,
            disabledReason = if (!isControllerOnline) "المتحكم غير متصل" else null,
            shape = RoundedCornerShape(topStart = 80.dp, topEnd = 80.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .size(width = 116.dp, height = 76.dp),
            currentLineIp = currentLineIp,
            testTag = "remote_btn_lift_up",
            sendCommand = sendCommand,
            onStatusUpdate = onStatusUpdate,
            onTransmit = onTransmit
        )

        // -----------------------------------------------------------------
        // 2. BOTTOM: CH ▼ (أزرار قلب القنوات الرأسية - تنزيل الهيدروليك - Relay 5)
        // -----------------------------------------------------------------
        RemoteDirectionalButton(
            relayId = 5,
            label = "تنزيل هيدروليك",
            subLabel = "CH ▼",
            icon = Icons.Default.KeyboardArrowDown,
            activeBgColor = Color(0xFF0C4A6E),
            activeGlowColor = Color(0xFF00B0FF),
            idleBgColor = Color(0xFF1E293B),
            idleContentColor = Color(0xFF38BDF8),
            isRelayStateActive = relay5Lower,
            enabled = isControllerOnline,
            disabledReason = if (!isControllerOnline) "المتحكم غير متصل" else null,
            shape = RoundedCornerShape(bottomStart = 80.dp, bottomEnd = 80.dp, topStart = 14.dp, topEnd = 14.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .size(width = 116.dp, height = 76.dp),
            currentLineIp = currentLineIp,
            testTag = "remote_btn_lower_down",
            sendCommand = sendCommand,
            onStatusUpdate = onStatusUpdate,
            onTransmit = onTransmit
        )

        // -----------------------------------------------------------------
        // 3. RIGHT: VOL + (أزرار رفع وخفض الصوت الأفقية - زيادة السرعة - Relay 7)
        // -----------------------------------------------------------------
        RemoteDirectionalButton(
            relayId = 7,
            label = "زيادة سرعة",
            subLabel = if (isMotorRunning) "VOL +" else "🔒 مقفل",
            icon = Icons.Default.Add,
            activeBgColor = Color(0xFF7C2D12),
            activeGlowColor = Color(0xFFFF9100),
            idleBgColor = Color(0xFF1E293B),
            idleContentColor = Color(0xFFFBBF24),
            isRelayStateActive = relay7SpeedUp,
            enabled = isControllerOnline && isMotorRunning,
            disabledReason = if (!isControllerOnline) "المتحكم غير متصل" else "تنبيه: يجب تشغيل المحرك أولاً لزيادة السرعة ⚠️",
            shape = RoundedCornerShape(topEnd = 80.dp, bottomEnd = 80.dp, topStart = 14.dp, bottomStart = 14.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(width = 76.dp, height = 116.dp),
            currentLineIp = currentLineIp,
            testTag = "remote_btn_speed_up",
            sendCommand = sendCommand,
            onStatusUpdate = onStatusUpdate,
            onTransmit = onTransmit
        )

        // -----------------------------------------------------------------
        // 4. LEFT: VOL - (أزرار رفع وخفض الصوت الأفقية - خفض السرعة - Relay 8)
        // -----------------------------------------------------------------
        RemoteDirectionalButton(
            relayId = 8,
            label = "خفض سرعة",
            subLabel = if (isMotorRunning) "VOL -" else "🔒 مقفل",
            icon = Icons.Default.Remove,
            activeBgColor = Color(0xFF7C2D12),
            activeGlowColor = Color(0xFFFF9100),
            idleBgColor = Color(0xFF1E293B),
            idleContentColor = Color(0xFFFBBF24),
            isRelayStateActive = relay8SpeedDown,
            enabled = isControllerOnline && isMotorRunning,
            disabledReason = if (!isControllerOnline) "المتحكم غير متصل" else "تنبيه: يجب تشغيل المحرك أولاً لخفض السرعة ⚠️",
            shape = RoundedCornerShape(topStart = 80.dp, bottomStart = 80.dp, topEnd = 14.dp, bottomEnd = 14.dp),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(width = 76.dp, height = 116.dp),
            currentLineIp = currentLineIp,
            testTag = "remote_btn_speed_down",
            sendCommand = sendCommand,
            onStatusUpdate = onStatusUpdate,
            onTransmit = onTransmit
        )

        // -----------------------------------------------------------------
        // 5. CENTER: OK / POWER (زر تشغيل المحرك وإيقافه في المنتصف)
        // -----------------------------------------------------------------
        RemoteCenterMotorButton(
            isMotorRunning = isMotorRunning,
            enabled = isControllerOnline,
            currentLineIp = currentLineIp,
            modifier = Modifier
                .align(Alignment.Center)
                .size(88.dp),
            sendCommand = sendCommand,
            onStatusUpdate = onStatusUpdate,
            onTransmit = onTransmit
        )
    }
}

// =========================================================================
// REMOTE DIRECTIONAL MOMENTARY BUTTON (أزرار ريموت التلفاز اللحظية مع استجابة باللمس)
// =========================================================================
@Composable
fun RemoteDirectionalButton(
    relayId: Int,
    label: String,
    subLabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    activeBgColor: Color,
    activeGlowColor: Color,
    idleBgColor: Color,
    idleContentColor: Color,
    isRelayStateActive: Boolean,
    enabled: Boolean,
    disabledReason: String? = null,
    shape: Shape,
    modifier: Modifier = Modifier,
    currentLineIp: String,
    testTag: String,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    onTransmit: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isLocallyPressed by remember { mutableStateOf(false) }
    val pulseScope = rememberCoroutineScope()

    DisposableEffect(relayId, currentLineIp) {
        onDispose {
            if (isLocallyPressed && currentLineIp.isNotBlank()) {
                sendRelayOffReliably(context, relayId, currentLineIp, sendCommand, onStatusUpdate, { true })
            }
        }
    }

    val isActive = isLocallyPressed || isRelayStateActive

    val bg = when {
        !enabled -> Color(0xFF111827).copy(alpha = 0.5f)
        isActive -> activeBgColor
        else -> idleBgColor
    }
    val borderCol = when {
        !enabled -> Color(0xFF1E293B)
        isActive -> activeGlowColor
        else -> Color(0xFF334155)
    }
    val contentCol = when {
        !enabled -> Color(0xFF64748B)
        isActive -> Color.White
        else -> idleContentColor
    }

    Box(
        modifier = modifier
            .testTag(testTag)
            .clip(shape)
            .background(bg)
            .border(if (isActive) 2.5.dp else 1.5.dp, borderCol, shape)
            .pointerInput(enabled, relayId, currentLineIp) {
                if (!enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (!disabledReason.isNullOrBlank()) {
                            Toast.makeText(context, disabledReason, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@pointerInput
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initialPos = down.position
                    val touchSlop = viewConfiguration.touchSlop

                    if (currentLineIp.isBlank()) return@awaitEachGesture

                    var cancelled = false
                    withTimeoutOrNull(160L) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed || pointer.isConsumed) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                            val diff = pointer.position - initialPos
                            if (diff.getDistance() > touchSlop) {
                                cancelled = true
                                return@withTimeoutOrNull
                            }
                        }
                    }

                    if (cancelled) return@awaitEachGesture

                    down.consume()
                    isLocallyPressed = true
                    onTransmit(true)
                    try {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    } catch (_: Exception) {}

                    // On Press -> send ON immediately
                    sendCommand(
                        holdOnUrl(currentLineIp, relayId),
                        { updated -> if (updated != null) onStatusUpdate(updated) },
                        { /* Handled */ }
                    )

                    // نبضات أمان: تجدّد صلاحية الريليه كل 250ms؛ إن انقطعت الشبكة يوقفه المتحكم نفسه
                    val pulseJob = launchHoldPulses(pulseScope, relayId, currentLineIp, sendCommand) {
                        isLocallyPressed = false
                        onTransmit(false)
                        try {
                            Toast.makeText(context, "انقطع الاتصال — توقفت الحركة. أعد الضغط للمتابعة", Toast.LENGTH_LONG).show()
                        } catch (_: Exception) {}
                    }

                    // الإيقاف يُرسل دائماً (finally) حتى لو أُلغيت الضغطة أثناء الإمساك — مثلاً بتغيّر enabled عند انقطاع اتصال لحظي
                    try {
                        var pointerFinished = false
                        while (!pointerFinished) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed) {
                                pointerFinished = true
                            } else {
                                pointer.consume()
                                val pos = pointer.position
                                val isOutside = pos.x < 0 || pos.x > size.width || pos.y < 0 || pos.y > size.height
                                if (isOutside) {
                                    pointerFinished = true
                                }
                            }
                        }

                    } finally {
                        // On Release / Leave / Cancel -> send OFF (مع إعادة محاولة وتحقق)
                        pulseJob.cancel()
                        isLocallyPressed = false
                        onTransmit(false)
                        sendRelayOffReliably(context, relayId, currentLineIp, sendCommand, onStatusUpdate, { !isLocallyPressed })
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentCol,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = contentCol,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            if (subLabel.isNotBlank()) {
                Text(
                    text = subLabel,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = contentCol.copy(alpha = 0.85f),
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// =========================================================================
// REMOTE CENTER MOTOR BUTTON (زر تشغيل/إيقاف المحرك بمنتصف ريموت التلفاز)
// =========================================================================
@Composable
fun RemoteCenterMotorButton(
    isMotorRunning: Boolean,
    enabled: Boolean,
    currentLineIp: String,
    modifier: Modifier = Modifier,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    onTransmit: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val infiniteTransition = rememberInfiniteTransition(label = "remote_motor_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isMotorRunning) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "motor_pulse"
    )

    val bgBrush = if (isMotorRunning) {
        Brush.radialGradient(listOf(Color(0xFF10B981), Color(0xFF047857)))
    } else {
        Brush.radialGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
    }

    val borderCol = if (isMotorRunning) Color(0xFF34D399) else Color(0xFFEF4444)
    val contentCol = if (isMotorRunning) Color.White else Color(0xFFFCA5A5)

    Box(
        modifier = modifier
            .testTag("remote_center_motor_btn")
            .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
            .clip(CircleShape)
            .background(bgBrush)
            .border(3.dp, borderCol, CircleShape)
            .clickable(enabled = enabled && currentLineIp.isNotBlank()) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onTransmit(true)
                if (isMotorRunning) {
                    // Send Stop (Relay 3)
                    sendCommand(
                        resolveUrl(currentLineIp, "/control?relay=3&state=on"),
                        { updated ->
                            onTransmit(false)
                            if (updated != null) onStatusUpdate(updated)
                            Toast.makeText(context, "تم إرسال أمر إيقاف المحرك ⏹", Toast.LENGTH_SHORT).show()
                        },
                        { err ->
                            onTransmit(false)
                            Toast.makeText(context, "فشل إيقاف المحرك: ${err.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    // Send Start (Relay 2)
                    sendCommand(
                        resolveUrl(currentLineIp, "/control?relay=2&state=on"),
                        { updated ->
                            onTransmit(false)
                            if (updated != null) onStatusUpdate(updated)
                            Toast.makeText(context, "تم إرسال أمر تشغيل المحرك ▶", Toast.LENGTH_SHORT).show()
                        },
                        { err ->
                            onTransmit(false)
                            Toast.makeText(context, "فشل تشغيل المحرك: ${err.message}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(
                imageVector = if (isMotorRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = "Motor Start/Stop",
                tint = contentCol,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = if (isMotorRunning) "إيقاف ⏹" else "تشغيل ▶",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = contentCol,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (isMotorRunning) "دائر 🟢" else "متوقف ⚪",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentCol.copy(alpha = 0.85f),
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

// =========================================================================
// LINE 2 QUICK REMOTE LAUNCH BANNER (زر فتح صفحة التحكم السريع - نمط ريموت التلفاز)
// =========================================================================
@Composable
fun Line2QuickRemoteLaunchBanner(
    onOpenQuickRemote: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
        border = BorderStroke(1.5.dp, Color(0xFF38BDF8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .clickable(onClick = onOpenQuickRemote)
            .testTag("line2_open_quick_remote_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF0284C7), Color(0xFF0369A1)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsRemote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "التحكم السريع",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =========================================================================
// LINE 2 SCALE ACTION RESULT MODEL
// =========================================================================
data class ScaleActionResultData(
    val actionLabel: String = "",
    val netWeightAfter: Double = 0.0,
    val grossWeightAtZero: Double = 0.0
)

// =========================================================================
// LINE 2 AUTOPILOT (الطيار الآلي لخط إنتاج 2 - برمجة وتنفيذ الوصفات والعمليات الآلية)
// =========================================================================

enum class AutopilotTimingMode(val titleAr: String, val subtitleAr: String) {
    AFTER_PREVIOUS("بعد اكتمال السابق", "يتم تنفيذ الأمر تسلسلياً بعد انتهاء الخطوة السابقة"),
    DURING_MOTOR("أثناء تنفيذ الأمر السابق", "يتم تنفيذ الأمر بالتزامن أثناء عمل الخطوة السابقة")
}

enum class AutopilotStepType(val titleAr: String, val iconName: String) {
    START_MOTOR("تشغيل الخلاط", "PlayArrow"),
    SPEED_UP("زيادة السرعة +", "Speed"),
    SPEED_DOWN("تخفيض السرعة -", "Speed"),
    ADD_WATER("إضافة كمية ماء", "WaterDrop"),
    LIFT_HYDRAULIC("رفع هيدروليك", "KeyboardArrowUp"),
    LOWER_HYDRAULIC("تنزيل هيدروليك", "KeyboardArrowDown"),
    WAIT_DURATION("مدة انتظار (توقف/تهدئة)", "Timer"),
    TARE_SCALE("تصفير المؤشر ⚖️", "Scale")
}

data class AutopilotStep(
    val id: String = UUID.randomUUID().toString(),
    val type: AutopilotStepType = AutopilotStepType.START_MOTOR,
    val timingMode: AutopilotTimingMode = AutopilotTimingMode.AFTER_PREVIOUS,
    val startImmediate: Boolean = true, // تشغيل فوري أو بعد مدة (عندما يكون بعد الأمر السابق)
    val delayBeforeSeconds: Int = 0,    // مدة الانتظار قبل البدء (بالثواني)
    val motorOffsetMinutes: Double = 0.0, // توقيت التنفيذ أثناء دوران الخلاط: بعد مضي X دقيقة من بدء الخلاط
    val durationMinutes: Double = 30.0, // مدة تشغيل الخلاط بالدقائق (خاص بـ START_MOTOR مع إيقاف آلي)
    val durationSeconds: Int = 5,       // مدة الحركة أو النبضة بالثواني (للهيدروليك، زيادة/خفض السرعة، أو الانتظار)
    val targetWaterKg: Double = 50.0,   // كمية الماء المستهدفة
    val note: String = ""
)

data class AutopilotProgram(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "برنامج آلي جديد",
    val description: String = "",
    val steps: List<AutopilotStep> = emptyList()
)

// SharedPreferences persistence for Autopilot Programs
fun saveAutopilotPrograms(context: Context, programs: List<AutopilotProgram>) {
    try {
        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()
        for (prog in programs) {
            val pObj = JSONObject()
            pObj.put("id", prog.id)
            pObj.put("name", prog.name)
            pObj.put("description", prog.description)
            val stepsArr = org.json.JSONArray()
            for (step in prog.steps) {
                val sObj = JSONObject()
                sObj.put("id", step.id)
                sObj.put("type", step.type.name)
                sObj.put("timingMode", step.timingMode.name)
                sObj.put("startImmediate", step.startImmediate)
                sObj.put("delayBeforeSeconds", step.delayBeforeSeconds)
                sObj.put("motorOffsetMinutes", step.motorOffsetMinutes)
                sObj.put("durationMinutes", step.durationMinutes)
                sObj.put("durationSeconds", step.durationSeconds)
                sObj.put("targetWaterKg", step.targetWaterKg)
                sObj.put("note", step.note)
                stepsArr.put(sObj)
            }
            pObj.put("steps", stepsArr)
            jsonArray.put(pObj)
        }
        prefs.edit().putString("line2_autopilot_programs", jsonArray.toString()).apply()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun loadAutopilotPrograms(context: Context): List<AutopilotProgram> {
    try {
        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("line2_autopilot_programs", null)
        if (!raw.isNullOrBlank()) {
            val jsonArray = org.json.JSONArray(raw)
            val list = mutableListOf<AutopilotProgram>()
            for (i in 0 until jsonArray.length()) {
                val pObj = jsonArray.getJSONObject(i)
                val id = pObj.optString("id", UUID.randomUUID().toString())
                val name = pObj.optString("name", "برنامج آلي")
                val desc = pObj.optString("description", "")
                val stepsArr = pObj.optJSONArray("steps") ?: org.json.JSONArray()
                val steps = mutableListOf<AutopilotStep>()
                for (j in 0 until stepsArr.length()) {
                    val sObj = stepsArr.getJSONObject(j)
                    val sId = sObj.optString("id", UUID.randomUUID().toString())
                    val typeStr = sObj.optString("type", AutopilotStepType.START_MOTOR.name)
                    // If old type was STOP_MOTOR, skip it since motor stops automatically by duration
                    if (typeStr == "STOP_MOTOR") continue
                    val type = try { AutopilotStepType.valueOf(typeStr) } catch (_: Exception) { AutopilotStepType.START_MOTOR }
                    val timingModeStr = sObj.optString("timingMode", AutopilotTimingMode.AFTER_PREVIOUS.name)
                    val timingMode = try { AutopilotTimingMode.valueOf(timingModeStr) } catch (_: Exception) { AutopilotTimingMode.AFTER_PREVIOUS }
                    val startImm = sObj.optBoolean("startImmediate", true)
                    val delaySec = sObj.optInt("delayBeforeSeconds", 0)
                    val motorOffsetMin = sObj.optDouble("motorOffsetMinutes", 0.0)
                    val durSec = sObj.optInt("durationSeconds", 5)
                    val durMin = if (sObj.has("durationMinutes")) {
                        sObj.optDouble("durationMinutes", 30.0)
                    } else {
                        if (type == AutopilotStepType.START_MOTOR) (durSec / 60.0).coerceAtLeast(1.0) else 30.0
                    }
                    val targetW = sObj.optDouble("targetWaterKg", 50.0)
                    val note = sObj.optString("note", "")
                    steps.add(
                        AutopilotStep(
                            id = sId,
                            type = type,
                            timingMode = timingMode,
                            startImmediate = startImm,
                            delayBeforeSeconds = delaySec,
                            motorOffsetMinutes = motorOffsetMin,
                            durationMinutes = durMin,
                            durationSeconds = durSec,
                            targetWaterKg = targetW,
                            note = note
                        )
                    )
                }
                list.add(AutopilotProgram(id = id, name = name, description = desc, steps = steps))
            }
            if (list.isNotEmpty()) return list
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Default built-in template program (matching realistic production workflow)
    return listOf(
        AutopilotProgram(
            name = "دورة خلط مع تعديل سرعة ورفع هيدروليك",
            description = "تصفير وتعبئة ماء، تشغيل الخلاط 30 دقيقة مع زيادة السرعة بعد 3 دقائق، ثم رفع الهيدروليك 5 ثواني",
            steps = listOf(
                AutopilotStep(
                    type = AutopilotStepType.ADD_WATER,
                    timingMode = AutopilotTimingMode.AFTER_PREVIOUS,
                    targetWaterKg = 50.0,
                    note = "تصفير المؤشر مسبقاً وتعبئة 50 كجم ماء"
                ),
                AutopilotStep(
                    type = AutopilotStepType.START_MOTOR,
                    timingMode = AutopilotTimingMode.AFTER_PREVIOUS,
                    startImmediate = true,
                    durationMinutes = 30.0,
                    note = "تشغيل الخلاط 30 دقيقة (إيقاف تلقائي بنهاية المدة)"
                ),
                AutopilotStep(
                    type = AutopilotStepType.SPEED_UP,
                    timingMode = AutopilotTimingMode.DURING_MOTOR,
                    motorOffsetMinutes = 3.0,
                    durationSeconds = 3,
                    note = "أثناء تنفيذ الأمر السابق: زيادة السرعة بعد 3 دقائق لمدة 3 ثوانٍ"
                ),
                AutopilotStep(
                    type = AutopilotStepType.LIFT_HYDRAULIC,
                    timingMode = AutopilotTimingMode.AFTER_PREVIOUS,
                    durationSeconds = 5,
                    note = "بعد انتهاء مدة تشغيل الخلاط: رفع الهيدروليك لمدة 5 ثوانٍ"
                )
            )
        )
    )
}

// =========================================================================
// LINE 2 AUTOPILOT LAUNCH BANNER (زر فتح صفحة الطيار الآلي)
// =========================================================================
@Composable
fun Line2AutopilotLaunchBanner(
    onOpenAutopilot: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
        border = BorderStroke(1.5.dp, Color(0xFF818CF8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .clickable(onClick = onOpenAutopilot)
            .testTag("line2_open_autopilot_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(Color(0xFF6366F1), Color(0xFF4338CA)))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PrecisionManufacturing,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = "البرمجة الآلية",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// =========================================================================
// SIMPLIFIED AUTOPILOT EXECUTION OVERLAY (نافذة مبسطة ومستقلة لمتابعة التشغيل الحي)
// =========================================================================
@Composable
fun AutopilotExecutionOverlay(
    currentLineName: String,
    programName: String,
    currentStepIndex: Int,
    totalSteps: Int,
    activeStep: AutopilotStep?,
    currentSubPhase: String,
    stepRemainingSeconds: Int,
    currentWeight: Double,
    isMotorRunning: Boolean,
    waterValveOpen: Boolean,
    fillActive: Boolean,
    onStop: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "overlay_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val progressFraction = if (totalSteps > 0 && currentStepIndex >= 0) {
        ((currentStepIndex + 1).toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Dialog(
        onDismissRequest = { /* Non-dismissable by clicking outside during active execution */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D16).copy(alpha = 0.96f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                border = BorderStroke(2.dp, Color(0xFF6366F1).copy(alpha = pulseAlpha)),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header: Status badge & Title
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF10B981).copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, Color(0xFF10B981))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Text(
                                    text = "الطيار الآلي قيد التشغيل ⚡",
                                    color = Color(0xFF34D399),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1F2937)
                        ) {
                            Text(
                                text = currentLineName,
                                color = Color(0xFF9CA3AF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Program Name
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = programName,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "المرحلة الحالية: خطوة رقم ${currentStepIndex + 1} من إجمالي $totalSteps",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    // Active Step Visual Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1E293B),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when (activeStep?.type) {
                                                AutopilotStepType.ADD_WATER -> Color(0xFF0284C7)
                                                AutopilotStepType.TARE_SCALE -> Color(0xFF0F766E)
                                                AutopilotStepType.START_MOTOR -> Color(0xFF16A34A)
                                                AutopilotStepType.SPEED_UP -> Color(0xFFD97706)
                                                AutopilotStepType.SPEED_DOWN -> Color(0xFF7C3AED)
                                                AutopilotStepType.LIFT_HYDRAULIC, AutopilotStepType.LOWER_HYDRAULIC -> Color(0xFFEA580C)
                                                else -> Color(0xFF475569)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when (activeStep?.type) {
                                            AutopilotStepType.ADD_WATER -> Icons.Default.WaterDrop
                                            AutopilotStepType.TARE_SCALE -> Icons.Default.Balance
                                            AutopilotStepType.START_MOTOR -> Icons.Default.PlayArrow
                                            AutopilotStepType.SPEED_UP -> Icons.Default.FastForward
                                            AutopilotStepType.SPEED_DOWN -> Icons.Default.FastRewind
                                            AutopilotStepType.LIFT_HYDRAULIC -> Icons.Default.ArrowUpward
                                            AutopilotStepType.LOWER_HYDRAULIC -> Icons.Default.ArrowDownward
                                            else -> Icons.Default.Timer
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeStep?.type?.titleAr ?: "تنفيذ الإجراء...",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp,
                                        color = Color.White
                                    )
                                    if (!activeStep?.note.isNullOrBlank()) {
                                        Text(
                                            text = activeStep?.note ?: "",
                                            fontSize = 11.5.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                            }

                            // Sub-phase detailed text
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0F172A),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = currentSubPhase.ifBlank { "جاري معالجة المرحلة..." },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF38BDF8),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp)
                                )
                            }
                        }
                    }

                    // Live Sensors Readout Grid (Scale Weight, Motor State, Water Valve)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Live Weight
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("وزن الميزان", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = String.format(Locale.US, "%.1f كجم", currentWeight),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (currentWeight > 0.1) Color(0xFF38BDF8) else Color(0xFFE2E8F0)
                                )
                            }
                        }

                        // 2. Motor State
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("حالة الخلاط", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = if (isMotorRunning) "يعمل ⚙️" else "متوقف ⏹",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMotorRunning) Color(0xFF34D399) else Color(0xFF94A3B8)
                                )
                            }
                        }

                        // 3. Water Valve
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF0F172A),
                            border = BorderStroke(1.dp, Color(0xFF1E293B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("صمام الماء", fontSize = 10.sp, color = Color(0xFF94A3B8))
                                Text(
                                    text = if (waterValveOpen || fillActive) "مفتوح 💧" else "مغلق 🔒",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (waterValveOpen || fillActive) Color(0xFF38BDF8) else Color(0xFF94A3B8)
                                )
                            }
                        }
                    }

                    // Execution Progress Bar
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = Color(0xFF10B981),
                            trackColor = Color(0xFF334155)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "التقدم العام: ${(progressFraction * 100).toInt()}%",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = "${currentStepIndex + 1} من $totalSteps خطوات",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }

                    // Emergency / Stop Button
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "إيقاف تشغيل الطيار الآلي 🛑",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.5.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// LINE 2 AUTOPILOT FULL SCREEN (صفحة الطيار الآلي الشاملة لخط إنتاج 2)
// =========================================================================
@Composable
fun Line2AutopilotScreen(
    currentLineName: String,
    currentLineIp: String,
    isControllerOnline: Boolean,
    lineStatus: LineStatus?,
    onBack: () -> Unit,
    onScaleDisconnected: () -> Unit,
    sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit,
    onStatusUpdate: (LineStatus) -> Unit,
    triggerEmergencyStop: () -> Unit,
    onOpenQuickRemote: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Enforce Screen Keep On constantly while on Autopilot page (تثبيت تشغيل الشاشة ولا تقفل)
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Programs management
    var programs by remember { mutableStateOf(loadAutopilotPrograms(context)) }
    var selectedProgramIndex by remember { mutableStateOf(0) }
    val currentProgram = programs.getOrNull(selectedProgramIndex) ?: AutopilotProgram()

    // Execution State
    var isRunning by remember { mutableStateOf(false) }
    var currentStepIndex by remember { mutableStateOf(-1) }
    var currentSubPhase by remember { mutableStateOf("") } // e.g. "تصفير الميزان...", "انتظار البدء...", "جاري ضخ الماء..."
    var stepRemainingSeconds by remember { mutableStateOf(0) }
    var executedStepsCount by remember { mutableStateOf(0) }
    var executionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Dialogs
    var showAddEditStepDialog by remember { mutableStateOf(false) }
    var editingStepIndex by remember { mutableStateOf<Int?>(null) }
    var showProgramNameDialog by remember { mutableStateOf(false) }
    var newProgramName by remember { mutableStateOf("") }
    var scaleActionResultNotification by remember { mutableStateOf<ScaleActionResultData?>(null) }
    var showTareWarningDialog by remember { mutableStateOf(false) }
    var pendingNewStep by remember { mutableStateOf<AutopilotStep?>(null) }
    var pendingStepIndex by remember { mutableStateOf<Int?>(null) }
    var showExecutionSuccessDialog by remember { mutableStateOf(false) }
    var completedStepsList by remember { mutableStateOf<List<AutopilotStep>>(emptyList()) }

    // Live measurements
    val currentWeight = (lineStatus?.weight ?: 0.0).coerceAtLeast(0.0)
    val isMotorRunning = lineStatus?.motor_running == true
    val fillActive = lineStatus?.fill_active == true
    val waterValveOpen = lineStatus?.relays?.find { it.id == 1 }?.state == true

    // Direct query helper to fetch immediate line status (bypasses poll delay)
    val queryLiveStatusDirect: suspend () -> LineStatus? = {
        if (currentLineIp.isBlank()) null
        else {
            try {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url(resolveUrl(currentLineIp, "/status")).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (body != null) {
                                val parsed = parseLineStatus(body)
                                withContext(Dispatchers.Main) {
                                    onStatusUpdate(parsed)
                                }
                                parsed
                            } else null
                        } else null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // Robust Hardware Zero Execution for Scale identical to the main line 2 screen logic (/scale/hardware-zero)
    val executeHardwareScaleZero: suspend (String) -> Boolean = { label ->
        var zeroSuccess = false
        try {
            val url = resolveUrl(currentLineIp, "/scale/hardware-zero")
            val jsonStr = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string()?.trim() ?: ""
                    if (response.isSuccessful) {
                        if (bodyStr.startsWith("{")) {
                            val obj = JSONObject(bodyStr)
                            val isSuccess = obj.optBoolean("success", true)
                            val errorMsg = obj.optString("error", obj.optString("message", "")).trim()
                            if (!isSuccess || errorMsg.isNotBlank()) {
                                val displayErr = if (errorMsg.isNotBlank()) errorMsg else "فشل عملية الـ $label"
                                throw Exception(displayErr)
                            }
                        }
                        bodyStr
                    } else {
                        var errorMsg = ""
                        if (bodyStr.startsWith("{")) {
                            try {
                                val obj = JSONObject(bodyStr)
                                errorMsg = obj.optString("error", obj.optString("message", "")).trim()
                            } catch (e: Exception) {}
                        }
                        if (errorMsg.isBlank()) {
                            errorMsg = "خطأ من السيرفر: ${response.code}"
                        }
                        throw Exception(errorMsg)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (jsonStr.isNotBlank() && jsonStr.startsWith("{")) {
                    val obj = JSONObject(jsonStr)
                    val netWeight = safeParseDouble(obj, "net_weight", safeParseDouble(obj, "gross_weight_after", 0.0))
                    val grossAtZero = if (obj.has("gross_weight_at_zero")) {
                        safeParseDouble(obj, "gross_weight_at_zero", 0.0)
                    } else if (obj.has("gross_weight_before")) {
                        safeParseDouble(obj, "gross_weight_before", 0.0)
                    } else if (obj.has("gross_weight")) {
                        safeParseDouble(obj, "gross_weight", 0.0)
                    } else {
                        lineStatus?.weight ?: 0.0
                    }

                    scaleActionResultNotification = ScaleActionResultData(
                        actionLabel = label,
                        netWeightAfter = netWeight,
                        grossWeightAtZero = grossAtZero
                    )
                } else {
                    scaleActionResultNotification = ScaleActionResultData(
                        actionLabel = label,
                        netWeightAfter = 0.0,
                        grossWeightAtZero = lineStatus?.weight ?: 0.0
                    )
                }
            }
            zeroSuccess = true
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("الميزان غير متصل") || msg.contains("409")) {
                withContext(Dispatchers.Main) {
                    onScaleDisconnected()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, e.message ?: "فشل تصفير الميزان الفعلي", Toast.LENGTH_SHORT).show()
                }
            }
            zeroSuccess = false
        }
        zeroSuccess
    }

    // Rule: Sound Alarm via /alarm/test, wait 2 seconds, then execute command
    val soundAlarmBeforeCommand: suspend (String) -> Unit = { commandLabel ->
        currentSubPhase = "🔔 تشغيل جرس الإنذار ($commandLabel) والانتظار 2 ثانية..."
        try {
            withContext(Dispatchers.IO) {
                val url = resolveUrl(currentLineIp, "/alarm/test")
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use {}
            }
        } catch (_: Exception) {
            // Non-blocking so connection hiccups to buzzer do not freeze the industrial sequence
        }
        delay(2000L)
    }

    // Stop and Reset execution function
    val stopExecution = {
        executionJob?.cancel()
        executionJob = null
        isRunning = false
        currentStepIndex = -1
        currentSubPhase = ""
        stepRemainingSeconds = 0
        // Safety: Stop motor, turn off hydraulic lift/lower, speed controls, water valve
        if (currentLineIp.isNotBlank()) {
            sendCommand(resolveUrl(currentLineIp, "/control?relay=3&state=on"), { updated -> if (updated != null) onStatusUpdate(updated) }, {})
            sendCommand(resolveUrl(currentLineIp, "/control?relay=4&state=off"), {}, {})
            sendCommand(resolveUrl(currentLineIp, "/control?relay=5&state=off"), {}, {})
            sendCommand(resolveUrl(currentLineIp, "/control?relay=7&state=off"), {}, {})
            sendCommand(resolveUrl(currentLineIp, "/control?relay=8&state=off"), {}, {})
            sendCommand(resolveUrl(currentLineIp, "/fill/stop"), {}, {})
        }
        Toast.makeText(context, "تم إيقاف تنفيذ برنامج الطيار الآلي 🛑", Toast.LENGTH_SHORT).show()
    }

    // Execute Autopilot Program Coroutine
    val startProgramExecution = {
        if (!isControllerOnline) {
            Toast.makeText(context, "لا يمكن البدء: المتحكم غير متصل! ⚠️", Toast.LENGTH_LONG).show()
        } else if (currentProgram.steps.isEmpty()) {
            Toast.makeText(context, "الرجاء إضافة خطوات للبرنامج أولاً ➕", Toast.LENGTH_SHORT).show()
        } else {
            isRunning = true
            executedStepsCount = 0
            executionJob = coroutineScope.launch {
                try {
                    var stepIdx = 0
                    while (stepIdx < currentProgram.steps.size) {
                        currentStepIndex = stepIdx
                        val step = currentProgram.steps[stepIdx]

                        if (step.type == AutopilotStepType.START_MOTOR) {
                            // Phase 1: Pre-delay if any
                            if (!step.startImmediate && step.delayBeforeSeconds > 0) {
                                currentSubPhase = "⏳ انتظار مؤقت قبل بدء تشغيل الخلاط (${step.delayBeforeSeconds} ثانية)..."
                                for (sec in step.delayBeforeSeconds downTo 1) {
                                    stepRemainingSeconds = sec
                                    delay(1000)
                                }
                            }

                            // Phase 2: Start the motor
                            soundAlarmBeforeCommand("تشغيل الخلاط")
                            currentSubPhase = "🚀 تشغيل الخلاط (إرسال أمر التشغيل)..."
                            sendCommand(
                                resolveUrl(currentLineIp, "/control?relay=2&state=on"),
                                { updated -> if (updated != null) onStatusUpdate(updated) },
                                {}
                            )
                            delay(1500)

                            // Collect subsequent steps that are configured to run DURING this motor operation
                            val concurrentSteps = mutableListOf<Pair<Int, AutopilotStep>>()
                            var lookahead = stepIdx + 1
                            while (lookahead < currentProgram.steps.size &&
                                currentProgram.steps[lookahead].timingMode == AutopilotTimingMode.DURING_MOTOR
                            ) {
                                concurrentSteps.add(lookahead to currentProgram.steps[lookahead])
                                lookahead++
                            }

                            val motorTotalSeconds = (step.durationMinutes * 60.0).toInt().coerceAtLeast(1)
                            val executedConcurrentIndices = mutableSetOf<Int>()

                            // Second-by-second countdown for the motor run duration
                            for (elapsedSec in 0 until motorTotalSeconds) {
                                val remainingSec = motorTotalSeconds - elapsedSec
                                stepRemainingSeconds = remainingSec

                                val elapsedMin = elapsedSec / 60
                                val elapsedS = elapsedSec % 60
                                val remMin = remainingSec / 60
                                val remS = remainingSec % 60
                                val elapsedFormatted = String.format(Locale.US, "%02d:%02d", elapsedMin, elapsedS)
                                val remFormatted = String.format(Locale.US, "%02d:%02d", remMin, remS)

                                currentSubPhase = "⚙️ الخلاط يعمل: مضى $elapsedFormatted / الإجمالي ${step.durationMinutes} دقيقة (المتبقي: $remFormatted)"

                                // Check if any concurrent step should trigger at this second
                                for ((cIdx, cStep) in concurrentSteps) {
                                    val triggerOffsetSec = (cStep.motorOffsetMinutes * 60.0).toInt()
                                    if (elapsedSec == triggerOffsetSec && cIdx !in executedConcurrentIndices) {
                                        executedConcurrentIndices.add(cIdx)
                                        // Trigger concurrent action in background without interrupting mixer rotation
                                        launch {
                                            when (cStep.type) {
                                                AutopilotStepType.SPEED_UP -> {
                                                    val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                    soundAlarmBeforeCommand("زيادة السرعة")
                                                    holdWithPulses(currentLineIp, 7, dur, sendCommand)
                                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=7&state=off"), {}, {})
                                                }
                                                AutopilotStepType.SPEED_DOWN -> {
                                                    val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                    soundAlarmBeforeCommand("تخفيض السرعة")
                                                    holdWithPulses(currentLineIp, 8, dur, sendCommand)
                                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=8&state=off"), {}, {})
                                                }
                                                AutopilotStepType.LIFT_HYDRAULIC -> {
                                                    val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                    soundAlarmBeforeCommand("رفع الهيدروليك")
                                                    holdWithPulses(currentLineIp, 4, dur, sendCommand)
                                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=4&state=off"), {}, {})
                                                }
                                                AutopilotStepType.LOWER_HYDRAULIC -> {
                                                    val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                    soundAlarmBeforeCommand("تنزيل الهيدروليك")
                                                    holdWithPulses(currentLineIp, 5, dur, sendCommand)
                                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=5&state=off"), {}, {})
                                                }
                                                AutopilotStepType.ADD_WATER -> {
                                                    // Alarm rule: sound alarm and wait 2 seconds before opening valve
                                                    val initWeight = queryLiveStatusDirect()?.weight ?: (lineStatus?.weight ?: 0.0)
                                                    val finalTgt = initWeight + cStep.targetWaterKg
                                                    soundAlarmBeforeCommand("فتح صمام الماء وضخ ${cStep.targetWaterKg} كجم")
                                                    sendCommand(resolveUrl(currentLineIp, "/fill/start?target=${String.format(Locale.US, "%.1f", finalTgt)}"), {}, {})
                                                }
                                                AutopilotStepType.TARE_SCALE -> {
                                                    currentSubPhase = "⚖️ جاري تصفير الميزان عبر /scale/hardware-zero..."
                                                    executeHardwareScaleZero("تصفير المؤشر الفعلي")
                                                    var tareWait = 0
                                                    while (tareWait < 10) {
                                                        delay(500)
                                                        val freshWeight = queryLiveStatusDirect()?.weight ?: (lineStatus?.weight ?: 0.0)
                                                        if (Math.abs(freshWeight) <= 0.15) break
                                                        tareWait++
                                                    }
                                                }
                                                else -> {}
                                            }
                                        }
                                    }
                                }

                                delay(1000)
                            }

                            // AUTOMATIC MOTOR STOP: Motor duration completed, stop motor immediately
                            soundAlarmBeforeCommand("إيقاف الخلاط آلياً")
                            currentSubPhase = "⏹ انقضت مدة تشغيل الخلاط (${step.durationMinutes} دقيقة) - إيقاف الخلاط آلياً..."
                            sendCommand(
                                resolveUrl(currentLineIp, "/control?relay=3&state=on"),
                                { updated -> if (updated != null) onStatusUpdate(updated) },
                                {}
                            )
                            delay(1500)

                            // Advance past all concurrent steps that ran during this motor session
                            stepIdx = lookahead
                            executedStepsCount = stepIdx
                        } else {
                            // Sequential execution (AFTER_PREVIOUS)
                            if (!step.startImmediate && step.delayBeforeSeconds > 0) {
                                currentSubPhase = "⏳ انتظار مؤقت قبل بدء الخطوة (${step.delayBeforeSeconds} ثانية)..."
                                for (sec in step.delayBeforeSeconds downTo 1) {
                                    stepRemainingSeconds = sec
                                    delay(1000)
                                }
                            }

                            // Collect subsequent steps that are configured to run DURING this operation (concurrently)
                            val concurrentSteps = mutableListOf<Pair<Int, AutopilotStep>>()
                            var lookahead = stepIdx + 1
                            while (lookahead < currentProgram.steps.size &&
                                currentProgram.steps[lookahead].timingMode == AutopilotTimingMode.DURING_MOTOR
                            ) {
                                concurrentSteps.add(lookahead to currentProgram.steps[lookahead])
                                lookahead++
                            }

                            // Trigger concurrent actions in background if any
                            if (concurrentSteps.isNotEmpty()) {
                                launch {
                                    for ((_, cStep) in concurrentSteps) {
                                        val delayMs = ((cStep.motorOffsetMinutes * 60.0) * 1000L).toLong().coerceAtLeast(0L)
                                        if (delayMs > 0) delay(delayMs)
                                        when (cStep.type) {
                                            AutopilotStepType.SPEED_UP -> {
                                                val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                soundAlarmBeforeCommand("زيادة السرعة")
                                                holdWithPulses(currentLineIp, 7, dur, sendCommand)
                                                sendCommand(resolveUrl(currentLineIp, "/control?relay=7&state=off"), {}, {})
                                            }
                                            AutopilotStepType.SPEED_DOWN -> {
                                                val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                soundAlarmBeforeCommand("تخفيض السرعة")
                                                holdWithPulses(currentLineIp, 8, dur, sendCommand)
                                                sendCommand(resolveUrl(currentLineIp, "/control?relay=8&state=off"), {}, {})
                                            }
                                            AutopilotStepType.LIFT_HYDRAULIC -> {
                                                val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                soundAlarmBeforeCommand("رفع الهيدروليك")
                                                holdWithPulses(currentLineIp, 4, dur, sendCommand)
                                                sendCommand(resolveUrl(currentLineIp, "/control?relay=4&state=off"), {}, {})
                                            }
                                            AutopilotStepType.LOWER_HYDRAULIC -> {
                                                val dur = cStep.durationSeconds.coerceAtLeast(1)
                                                soundAlarmBeforeCommand("تنزيل الهيدروليك")
                                                holdWithPulses(currentLineIp, 5, dur, sendCommand)
                                                sendCommand(resolveUrl(currentLineIp, "/control?relay=5&state=off"), {}, {})
                                            }
                                            AutopilotStepType.ADD_WATER -> {
                                                val initWeight = queryLiveStatusDirect()?.weight ?: (lineStatus?.weight ?: 0.0)
                                                val finalTgt = initWeight + cStep.targetWaterKg
                                                soundAlarmBeforeCommand("فتح صمام الماء وضخ ${cStep.targetWaterKg} كجم")
                                                sendCommand(resolveUrl(currentLineIp, "/fill/start?target=${String.format(Locale.US, "%.1f", finalTgt)}"), {}, {})
                                            }
                                            AutopilotStepType.TARE_SCALE -> {
                                                executeHardwareScaleZero("تصفير المؤشر الفعلي")
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            }

                            when (step.type) {
                                AutopilotStepType.SPEED_UP -> {
                                    val dur = step.durationSeconds.coerceAtLeast(1)
                                    soundAlarmBeforeCommand("زيادة السرعة")
                                    currentSubPhase = "⚡ زيادة السرعة قيد التفعيل ($dur ثانية)..."
                                    holdWithPulses(currentLineIp, 7, dur, sendCommand) { stepRemainingSeconds = it }
                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=7&state=off"), {}, {})
                                    delay(500)
                                }
                                AutopilotStepType.SPEED_DOWN -> {
                                    val dur = step.durationSeconds.coerceAtLeast(1)
                                    soundAlarmBeforeCommand("تخفيض السرعة")
                                    currentSubPhase = "🔉 تخفيض السرعة قيد التفعيل ($dur ثانية)..."
                                    holdWithPulses(currentLineIp, 8, dur, sendCommand) { stepRemainingSeconds = it }
                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=8&state=off"), {}, {})
                                    delay(500)
                                }
                                AutopilotStepType.LIFT_HYDRAULIC -> {
                                    val dur = step.durationSeconds.coerceAtLeast(1)
                                    soundAlarmBeforeCommand("رفع الهيدروليك")
                                    currentSubPhase = "🔼 رفع الهيدروليك قيد العمل ($dur ثانية)..."
                                    holdWithPulses(currentLineIp, 4, dur, sendCommand) { stepRemainingSeconds = it }
                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=4&state=off"), {}, {})
                                    delay(500)
                                }
                                AutopilotStepType.LOWER_HYDRAULIC -> {
                                    val dur = step.durationSeconds.coerceAtLeast(1)
                                    soundAlarmBeforeCommand("تنزيل الهيدروليك")
                                    currentSubPhase = "🔽 تنزيل الهيدروليك قيد العمل ($dur ثانية)..."
                                    holdWithPulses(currentLineIp, 5, dur, sendCommand) { stepRemainingSeconds = it }
                                    sendCommand(resolveUrl(currentLineIp, "/control?relay=5&state=off"), {}, {})
                                    delay(500)
                                }
                                AutopilotStepType.WAIT_DURATION -> {
                                    val dur = step.durationSeconds.coerceAtLeast(1)
                                    soundAlarmBeforeCommand("بدء فترة الانتظار")
                                    currentSubPhase = "⏱ مدة انتظار وتهدئة ($dur ثانية)..."
                                    for (sec in dur downTo 1) {
                                        stepRemainingSeconds = sec
                                        delay(1000)
                                    }
                                }
                                AutopilotStepType.ADD_WATER -> {
                                    val initialWeight = queryLiveStatusDirect()?.weight ?: (lineStatus?.weight ?: 0.0)
                                    val finalAbsoluteTarget = initialWeight + step.targetWaterKg
                                    // MANDATORY RULE 2: Sound alarm via /alarm/test, wait 2 seconds, then open water valve
                                    soundAlarmBeforeCommand("فتح صمام الماء وبدء ضخ ${String.format(Locale.US, "%.1f", step.targetWaterKg)} كجم")

                                    currentSubPhase = "💧 جاري فتح الصمام وبدء ضخ ${String.format(Locale.US, "%.1f", step.targetWaterKg)} كجم ماء (الوزن المستهدف على الميزان: ${String.format(Locale.US, "%.1f", finalAbsoluteTarget)} كجم)..."
                                    sendCommand(
                                        resolveUrl(currentLineIp, "/fill/start?target=${String.format(Locale.US, "%.1f", finalAbsoluteTarget)}"),
                                        { updated -> if (updated != null) onStatusUpdate(updated) },
                                        { err ->
                                            val msg = err.message ?: ""
                                            if (msg.contains("الميزان غير متصل") || msg.contains("409")) {
                                                onScaleDisconnected()
                                            }
                                        }
                                    )

                                    // Wait for fill controller to confirm start
                                    delay(2000)

                                    // Monitor filling until completion strictly before proceeding to next step
                                    var timeoutSeconds = 600
                                    var zeroFillActiveCount = 0
                                    while (timeoutSeconds > 0) {
                                        stepRemainingSeconds = timeoutSeconds
                                        val fresh = queryLiveStatusDirect() ?: lineStatus
                                        val isValveOpen = fresh?.relays?.find { it.id == 1 }?.state == true
                                        val isFillingActive = fresh?.fill_active == true || isValveOpen
                                        val currentKg = fresh?.weight ?: 0.0
                                        val waterPumpedKg = (currentKg - initialWeight).coerceAtLeast(0.0)

                                        currentSubPhase = "💧 ضخ الماء قيد التقدم: تم ضخ ${String.format(Locale.US, "%.1f", waterPumpedKg)} / ${String.format(Locale.US, "%.1f", step.targetWaterKg)} كجم (الميزان: ${String.format(Locale.US, "%.1f", currentKg)} كجم)"

                                        if (!isFillingActive) {
                                            zeroFillActiveCount++
                                            // Once fill_active is false and valve closed, check if target was actually reached
                                            if (zeroFillActiveCount >= 2) {
                                                if (waterPumpedKg >= (step.targetWaterKg * 0.90) || currentKg >= (finalAbsoluteTarget - 0.5)) {
                                                    break
                                                } else if (timeoutSeconds < 570) {
                                                    // Stopped prematurely due to water supply cut or safety shutdown
                                                    currentSubPhase = "⚠️ تنبيه: توقف ضخ الماء قبل الوصول للهدف! تم ضخ ${String.format(Locale.US, "%.1f", waterPumpedKg)} كجم فقط."
                                                    delay(2500)
                                                    break
                                                }
                                            }
                                        } else {
                                            zeroFillActiveCount = 0
                                            if (waterPumpedKg >= (step.targetWaterKg * 0.98) || currentKg >= (finalAbsoluteTarget - 0.2)) {
                                                // Target reached, wait for controller to turn off valve
                                                delay(1000)
                                                queryLiveStatusDirect()
                                                break
                                            }
                                        }

                                        delay(1000)
                                        timeoutSeconds--
                                    }

                                    val finalWaterKg = queryLiveStatusDirect()?.weight ?: (lineStatus?.weight ?: 0.0)
                                    val totalWaterAdded = (finalWaterKg - initialWeight).coerceAtLeast(0.0)
                                    if (totalWaterAdded >= (step.targetWaterKg * 0.85) || finalWaterKg >= (finalAbsoluteTarget - 0.5)) {
                                        currentSubPhase = "✅ تم اكتمال ضخ ${String.format(Locale.US, "%.1f", totalWaterAdded)} كجم ماء بنجاح! الانتقال للخطوة التالية..."
                                    } else {
                                        currentSubPhase = "⚠️ تم إيقاف ضخ الماء بوزن ${String.format(Locale.US, "%.1f", totalWaterAdded)} كجم فقط دون بلوغ الهدف الكامل (${step.targetWaterKg} كجم)!"
                                    }
                                    delay(1500)
                                }
                                AutopilotStepType.TARE_SCALE -> {
                                    currentSubPhase = "⚖️ جاري تصفير المؤشر فعلياً عبر /scale/hardware-zero..."
                                    executeHardwareScaleZero("تصفير المؤشر الفعلي")

                                    // Verify that scale actually becomes zero (reading <= 0.15 kg)
                                    var tareVerified = false
                                    var tareAttempts = 0
                                    while (tareAttempts < 14) {
                                        delay(500)
                                        tareAttempts++
                                        stepRemainingSeconds = (14 - tareAttempts) / 2
                                        val directStatus = queryLiveStatusDirect()
                                        val reading = directStatus?.weight ?: (lineStatus?.weight ?: 999.0)
                                        currentSubPhase = "⚖️ التحقق من تصفير الميزان (القراءة: ${String.format(Locale.US, "%.2f", reading)} كجم)..."
                                        if (Math.abs(reading) <= 0.15) {
                                            tareVerified = true
                                            break
                                        }
                                    }
                                }
                                else -> {}
                            }

                            stepIdx = lookahead
                            executedStepsCount = stepIdx
                        }
                    }

                    // Program Completed
                    isRunning = false
                    currentStepIndex = -1
                    currentSubPhase = "🎉 اكتمل تنفيذ كامل خطوات البرنامج الآلي بنجاح!"
                    completedStepsList = currentProgram.steps.toList()
                    showExecutionSuccessDialog = true
                    Toast.makeText(context, "اكتمل البرنامج الآلي بالكامل بنجاح 🎯", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    isRunning = false
                    currentSubPhase = "⚠️ تم إيقاف البرنامج أو حدوث خطأ: ${e.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "البرمجة الآلية",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                maxLines = 1
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFEEF2FF)
                            ) {
                                Text(
                                    text = "خط إنتاج 2 🤖",
                                    color = Color(0xFF4F46E5),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "برمجة متسلسلة للعمليات وتشغيل الخط آلياً",
                            fontSize = 10.5.sp,
                            color = Color.Gray
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (isRunning) {
                                Toast.makeText(context, "الرجاء إيقاف تنفيذ البرنامج أولاً قبل الخروج", Toast.LENGTH_SHORT).show()
                            } else {
                                onBack()
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .testTag("autopilot_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GBRBlueMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                actions = {
                    // Switch to Quick Remote
                    IconButton(
                        onClick = onOpenQuickRemote,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("autopilot_to_remote_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsRemote,
                            contentDescription = "Remote",
                            tint = Color(0xFF4F46E5),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Alarm Test Button (/alarm/test)
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                soundAlarmBeforeCommand("تجربة جرس الإنذار يدوياً")
                                Toast.makeText(context, "تم إرسال إشارة جرس الإنذار (/alarm/test) 🔔", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = isControllerOnline,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("autopilot_test_alarm_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "جرس الإنذار",
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Online Badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isControllerOnline) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                        border = BorderStroke(1.dp, if (isControllerOnline) Color(0xFFA5D6A7) else Color(0xFFFFCDD2)),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = if (isControllerOnline) "متصل 🟢" else "غير متصل 🔴",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isControllerOnline) Color(0xFF2E7D32) else Color(0xFFC62828),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    // Emergency Stop Header Button
                    Button(
                        onClick = {
                            stopExecution()
                            triggerEmergencyStop()
                        },
                        enabled = isControllerOnline,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .padding(end = 8.dp)
                            .testTag("autopilot_header_estop_btn")
                    ) {
                        Icon(Icons.Default.Dangerous, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("طوارئ 🚨", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        // Simplified Execution View (نافذة مبسطة ومستقلة لمتابعة التشغيل الحي)
        if (isRunning && currentStepIndex in currentProgram.steps.indices) {
            AutopilotExecutionOverlay(
                currentLineName = currentLineName,
                programName = currentProgram.name,
                currentStepIndex = currentStepIndex,
                totalSteps = currentProgram.steps.size,
                activeStep = currentProgram.steps.getOrNull(currentStepIndex),
                currentSubPhase = currentSubPhase,
                stepRemainingSeconds = stepRemainingSeconds,
                currentWeight = currentWeight,
                isMotorRunning = isMotorRunning,
                waterValveOpen = waterValveOpen,
                fillActive = fillActive,
                onStop = stopExecution
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF0F172A))
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // =================================================================
            // 1. LIVE SCALE READING DISPLAY (قراءة الميزان الحية)
            // =================================================================
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.5.dp, Color(0xFF38BDF8).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0284C7).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Scale,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "قراءة الميزان الحالية",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                text = if (isControllerOnline) "الميزان متصل وجاهز للوزن ⚖️" else "غير متصل بالمتحكم",
                                fontSize = 10.sp,
                                color = if (isControllerOnline) Color(0xFF34D399) else Color(0xFFF87171)
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.2f", currentWeight),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF38BDF8)
                            )
                            Text(
                                text = "كجم",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // =================================================================
            // 2. LIVE ACTIVE RUNNING STAGE BANNER (العرض المتحرك للمرحلة الحالية)
            // =================================================================
            if (isRunning && currentStepIndex in currentProgram.steps.indices) {
                val activeStep = currentProgram.steps[currentStepIndex]
                val infinitePulse = rememberInfiniteTransition(label = "autopilot_pulse")
                val pulseAlpha by infinitePulse.animateFloat(
                    initialValue = 0.85f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_alpha"
                )

                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                    border = BorderStroke(2.5.dp, Color(0xFF818CF8).copy(alpha = pulseAlpha)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF10B981)
                            ) {
                                Text(
                                    text = "جاري تنفيذ الخطوة ${currentStepIndex + 1} من ${currentProgram.steps.size}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                            if (stepRemainingSeconds > 0) {
                                Text(
                                    text = "الوقت المتبقي: $stepRemainingSeconds ثانية",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFBBF24)
                                )
                            }
                        }

                        // Large Display of the active step name
                        Text(
                            text = activeStep.type.titleAr,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        // Sub Phase details
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF312E81),
                            border = BorderStroke(1.dp, Color(0xFF6366F1)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = currentSubPhase,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFC7D2FE),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        // Execution Progress Bar
                        val progressFraction = ((currentStepIndex + 1).toFloat() / currentProgram.steps.size.toFloat()).coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = Color(0xFF10B981),
                                trackColor = Color(0xFF334155)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "التقدم العام للبرنامج: ${(progressFraction * 100).toInt()}%",
                                    fontSize = 10.5.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = "${currentStepIndex + 1} / ${currentProgram.steps.size}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        // Stop Button for the active execution
                        Button(
                            onClick = stopExecution,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إيقاف تشغيل الطيار الآلي 🛑", fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // =================================================================
            // 3. PROGRAM SELECTOR & HEADER (اختيار وتسمية البرنامج الآلي)
            // =================================================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.5.dp, Color(0xFF475569)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "البرنامج الآلي المختار:",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Rename Program
                            OutlinedButton(
                                onClick = {
                                    newProgramName = currentProgram.name
                                    showProgramNameDialog = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تسمية", fontSize = 11.sp, color = Color(0xFF38BDF8))
                            }

                            // Add New Program
                            Button(
                                onClick = {
                                    val newP = AutopilotProgram(name = "برنامج آلي جديد #${programs.size + 1}")
                                    val updated = programs + newP
                                    programs = updated
                                    selectedProgramIndex = updated.lastIndex
                                    saveAutopilotPrograms(context, updated)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("برنامج جديد", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }

                    // Program Selector Tabs
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(programs.indices.toList()) { idx ->
                            val p = programs[idx]
                            val isSelected = idx == selectedProgramIndex
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) Color(0xFF4F46E5) else Color(0xFF334155),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF818CF8) else Color(0xFF475569)),
                                modifier = Modifier.clickable {
                                    if (!isRunning) {
                                        selectedProgramIndex = idx
                                    } else {
                                        Toast.makeText(context, "لا يمكن تغيير البرنامج أثناء التشغيل", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = p.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else Color(0xFFCBD5E1)
                                    )
                                    if (programs.size > 1 && !isRunning) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete",
                                            tint = if (isSelected) Color.White.copy(alpha = 0.7f) else Color.Gray,
                                            modifier = Modifier
                                                .size(14.dp)
                                                .clickable {
                                                    val updated = programs.toMutableList()
                                                    updated.removeAt(idx)
                                                    programs = updated
                                                    selectedProgramIndex = 0
                                                    saveAutopilotPrograms(context, updated)
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // 4. PROGRAM STEPS LIST & BUILDER (قائمة خطوات البرنامج الآلي)
            // =================================================================
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(1.5.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تسلسل خطوات البرنامج (${currentProgram.steps.size} خطوة):",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        // Add Step Button
                        Button(
                            onClick = {
                                editingStepIndex = null
                                showAddEditStepDialog = true
                            },
                            enabled = !isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إضافة أمر جديد ➕", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }

                    if (currentProgram.steps.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0F172A),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = null,
                                    tint = Color(0xFF64748B),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "لم يتم إضافة أي خطوات في هذا البرنامج بعد",
                                    fontSize = 12.5.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = "اضغط على زر 'إضافة أمر جديد' بالأعلى للبدء",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    } else {
                        // Steps List
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            currentProgram.steps.forEachIndexed { idx, step ->
                                val isCurrentlyExecuting = isRunning && currentStepIndex == idx
                                val isPassed = isRunning && idx < currentStepIndex

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = when {
                                        isCurrentlyExecuting -> Color(0xFF312E81)
                                        isPassed -> Color(0xFF064E3B).copy(alpha = 0.6f)
                                        else -> Color(0xFF0F172A)
                                    },
                                    border = BorderStroke(
                                        width = if (isCurrentlyExecuting) 2.dp else 1.dp,
                                        color = when {
                                            isCurrentlyExecuting -> Color(0xFF818CF8)
                                            isPassed -> Color(0xFF10B981)
                                            else -> Color(0xFF334155)
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            // Step Number Badge
                                            Surface(
                                                shape = CircleShape,
                                                color = when {
                                                    isCurrentlyExecuting -> Color(0xFF818CF8)
                                                    isPassed -> Color(0xFF10B981)
                                                    else -> Color(0xFF334155)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = "${idx + 1}",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }

                                            // Step Icon & Description
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = step.type.titleAr,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                    if (isCurrentlyExecuting) {
                                                        Surface(
                                                            color = Color(0xFFF59E0B),
                                                            shape = RoundedCornerShape(6.dp)
                                                        ) {
                                                            Text(
                                                                text = "جاري العمل ⚡",
                                                                color = Color.Black,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                // Parameter details
                                                val detailText = when (step.type) {
                                                    AutopilotStepType.START_MOTOR -> {
                                                        val startMode = if (step.startImmediate) "تشغيل فوري" else "تأخير ${step.delayBeforeSeconds} ث"
                                                        "$startMode • المدة: ${step.durationMinutes} دقيقة (إيقاف آلي)"
                                                    }
                                                    AutopilotStepType.SPEED_UP -> {
                                                        if (step.timingMode == AutopilotTimingMode.DURING_MOTOR) {
                                                            val offsetStr = if (step.motorOffsetMinutes > 0) "بعد ${step.motorOffsetMinutes} دقيقة" else "مباشرة"
                                                            "⚙️ أثناء تنفيذ الأمر السابق: زيادة السرعة $offsetStr • المدة: ${step.durationSeconds} ث"
                                                        } else {
                                                            val startMode = if (step.startImmediate) "فوري" else "تأخير ${step.delayBeforeSeconds} ث"
                                                            "زيادة السرعة ($startMode) • المدة: ${step.durationSeconds} ثانية"
                                                        }
                                                    }
                                                    AutopilotStepType.SPEED_DOWN -> {
                                                        if (step.timingMode == AutopilotTimingMode.DURING_MOTOR) {
                                                            val offsetStr = if (step.motorOffsetMinutes > 0) "بعد ${step.motorOffsetMinutes} دقيقة" else "مباشرة"
                                                            "⚙️ أثناء تنفيذ الأمر السابق: تخفيض السرعة $offsetStr • المدة: ${step.durationSeconds} ث"
                                                        } else {
                                                            val startMode = if (step.startImmediate) "فوري" else "تأخير ${step.delayBeforeSeconds} ث"
                                                            "تخفيض السرعة ($startMode) • المدة: ${step.durationSeconds} ثانية"
                                                        }
                                                    }
                                                    AutopilotStepType.WAIT_DURATION -> "مدة انتظار وتهدئة: ${step.durationSeconds} ثانية"
                                                    AutopilotStepType.ADD_WATER -> {
                                                        if (step.timingMode == AutopilotTimingMode.DURING_MOTOR) {
                                                            val offsetStr = if (step.motorOffsetMinutes > 0) "بعد ${step.motorOffsetMinutes} دقيقة" else "مباشرة"
                                                            "⚙️ أثناء تنفيذ الأمر السابق: إضافة ${step.targetWaterKg} كجم ماء $offsetStr"
                                                        } else {
                                                            "إضافة كمية ماء: ${step.targetWaterKg} كجم"
                                                        }
                                                    }
                                                    AutopilotStepType.TARE_SCALE -> "تصفير المؤشر آلياً ⚖️"
                                                    AutopilotStepType.LIFT_HYDRAULIC -> {
                                                        if (step.timingMode == AutopilotTimingMode.DURING_MOTOR) {
                                                            val offsetStr = if (step.motorOffsetMinutes > 0) "بعد ${step.motorOffsetMinutes} دقيقة" else "مباشرة"
                                                            "⚙️ أثناء تنفيذ الأمر السابق: رفع هيدروليك $offsetStr • المدة: ${step.durationSeconds} ث"
                                                        } else {
                                                            "رفع هيدروليك لمدة: ${step.durationSeconds} ثانية"
                                                        }
                                                    }
                                                    AutopilotStepType.LOWER_HYDRAULIC -> {
                                                        if (step.timingMode == AutopilotTimingMode.DURING_MOTOR) {
                                                            val offsetStr = if (step.motorOffsetMinutes > 0) "بعد ${step.motorOffsetMinutes} دقيقة" else "مباشرة"
                                                            "⚙️ أثناء تنفيذ الأمر السابق: تنزيل هيدروليك $offsetStr • المدة: ${step.durationSeconds} ث"
                                                        } else {
                                                            "تنزيل هيدروليك لمدة: ${step.durationSeconds} ثانية"
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = detailText,
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF94A3B8)
                                                )
                                            }
                                        }

                                        // Step Actions (Reorder / Edit / Delete)
                                        if (!isRunning) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                IconButton(
                                                    onClick = {
                                                        editingStepIndex = idx
                                                        showAddEditStepDialog = true
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                                }
                                                IconButton(
                                                    onClick = {
                                                        val updatedSteps = currentProgram.steps.toMutableList()
                                                        updatedSteps.removeAt(idx)
                                                        val updatedP = currentProgram.copy(steps = updatedSteps)
                                                        val updatedProgs = programs.toMutableList()
                                                        updatedProgs[selectedProgramIndex] = updatedP
                                                        programs = updatedProgs
                                                        saveAutopilotPrograms(context, updatedProgs)
                                                    },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // START / STOP MASTER EXECUTION BUTTON
                    if (!isRunning) {
                        Button(
                            onClick = { startProgramExecution() },
                            enabled = isControllerOnline && currentProgram.steps.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4F46E5),
                                disabledContainerColor = Color(0xFF334155)
                            ),
                            shape = RoundedCornerShape(14.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("autopilot_start_program_btn")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "بدء تشغيل البرنامج الآلي الآن 🚀",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Button(
                            onClick = stopExecution,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            shape = RoundedCornerShape(14.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("autopilot_stop_program_btn")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "إيقاف تنفيذ البرنامج الآلي ⏹",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }

    // =========================================================================
    // DIALOG: ADD / EDIT AUTOPILOT STEP
    // =========================================================================
    if (showAddEditStepDialog) {
        val existingStep = editingStepIndex?.let { currentProgram.steps.getOrNull(it) }
        var stepType by remember { mutableStateOf(existingStep?.type ?: AutopilotStepType.START_MOTOR) }
        var timingMode by remember { mutableStateOf(existingStep?.timingMode ?: AutopilotTimingMode.AFTER_PREVIOUS) }
        var startImmediate by remember { mutableStateOf(existingStep?.startImmediate ?: true) }
        var delaySecondsStr by remember { mutableStateOf((existingStep?.delayBeforeSeconds ?: 0).toString()) }
        var motorOffsetMinutesStr by remember { mutableStateOf((existingStep?.motorOffsetMinutes ?: 3.0).toString()) }
        var durationMinutesStr by remember { mutableStateOf((existingStep?.durationMinutes ?: 30.0).toString()) }
        var durationSecondsStr by remember { mutableStateOf((existingStep?.durationSeconds ?: 5).toString()) }
        var targetWaterKgStr by remember { mutableStateOf((existingStep?.targetWaterKg ?: 50.0).toString()) }
        var noteStr by remember { mutableStateOf(existingStep?.note ?: "") }

        AlertDialog(
            onDismissRequest = { showAddEditStepDialog = false },
            title = {
                Text(
                    text = if (existingStep != null) "تعديل أمر في البرنامج الآلي" else "إضافة أمر جديد إلى البرنامج الآلي",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = GBRDarkIndigo
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 1. Step Type Selection
                    Text("اختر نوع الأمر:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
                    val types = listOf(
                        AutopilotStepType.START_MOTOR,
                        AutopilotStepType.SPEED_UP,
                        AutopilotStepType.SPEED_DOWN,
                        AutopilotStepType.ADD_WATER,
                        AutopilotStepType.TARE_SCALE,
                        AutopilotStepType.LIFT_HYDRAULIC,
                        AutopilotStepType.LOWER_HYDRAULIC,
                        AutopilotStepType.WAIT_DURATION
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        types.forEach { t ->
                            val isSel = t == stepType
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0xFFEEF2FF) else Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, if (isSel) Color(0xFF6366F1) else Color(0xFFE2E8F0)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { stepType = t }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        selected = isSel,
                                        onClick = { stepType = t }
                                    )
                                    Text(
                                        text = t.titleAr,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) Color(0xFF312E81) else Color(0xFF334155)
                                    )
                                }
                            }
                        }
                    }

                    // 2. Timing Mode (for steps other than START_MOTOR)
                    if (stepType != AutopilotStepType.START_MOTOR) {
                        Text("توقيت تنفيذ هذا الأمر:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = timingMode == AutopilotTimingMode.AFTER_PREVIOUS,
                                onClick = { timingMode = AutopilotTimingMode.AFTER_PREVIOUS },
                                label = { Text("بعد اكتمال السابق ➡️", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = timingMode == AutopilotTimingMode.DURING_MOTOR,
                                onClick = { timingMode = AutopilotTimingMode.DURING_MOTOR },
                                label = { Text("أثناء تنفيذ الأمر السابق ⚙️", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        if (timingMode == AutopilotTimingMode.DURING_MOTOR) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFFFF7ED),
                                border = BorderStroke(1.dp, Color(0xFFFDBA74)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = "💡 تشغيل متزامن أثناء تنفيذ الأمر السابق:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFC2410C)
                                    )
                                    Text(
                                        text = "سيتم تفعيل هذا الأمر بعد مضي الوقت المحدد أدناه من بدء تنفيذ الأمر السابق دون انتظار اكتماله بالكامل.",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF9A3412)
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = motorOffsetMinutesStr,
                                onValueChange = { motorOffsetMinutesStr = it },
                                label = { Text("يبدأ بعد مرور كم دقيقة من بدء الأمر السابق؟ (بالدقائق)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        } else {
                            // AFTER_PREVIOUS: Immediate vs Delayed Start
                            Text("توقيت البدء بعد الأمر السابق:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = startImmediate,
                                    onClick = { startImmediate = true },
                                    label = { Text("فوري ⚡", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = !startImmediate,
                                    onClick = { startImmediate = false },
                                    label = { Text("بعد انتظار ⏳", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (!startImmediate) {
                                OutlinedTextField(
                                    value = delaySecondsStr,
                                    onValueChange = { delaySecondsStr = it },
                                    label = { Text("مدة الانتظار قبل البدء (بالثواني)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    // 3. Specific Parameters based on Step Type
                    when (stepType) {
                        AutopilotStepType.START_MOTOR -> {
                            // Immediate vs Delayed Start
                            Text("توقيت بدء تشغيل الخلاط:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = startImmediate,
                                    onClick = { startImmediate = true },
                                    label = { Text("تشغيل فوري ⚡", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = !startImmediate,
                                    onClick = { startImmediate = false },
                                    label = { Text("تشغيل بعد انتظار ⏳", fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (!startImmediate) {
                                OutlinedTextField(
                                    value = delaySecondsStr,
                                    onValueChange = { delaySecondsStr = it },
                                    label = { Text("مدة الانتظار قبل البدء (بالثواني)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }

                            OutlinedTextField(
                                value = durationMinutesStr,
                                onValueChange = { durationMinutesStr = it },
                                label = { Text("مدة تشغيل الخلاط (بالدقائق) ⏱") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                text = "ℹ️ سيتوقف الخلاط آلياً وتلقائياً فور انقضاء هذه المدة بالدقائق.",
                                fontSize = 10.5.sp,
                                color = Color(0xFF059669)
                            )
                        }

                        AutopilotStepType.SPEED_UP, AutopilotStepType.SPEED_DOWN -> {
                            OutlinedTextField(
                                value = durationSecondsStr,
                                onValueChange = { durationSecondsStr = it },
                                label = { Text("مدة نبضة/تفعيل السرعة (بالثواني) ⏱") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Text(
                                text = "⚡ سيتم تفعيل ريليه ${if (stepType == AutopilotStepType.SPEED_UP) "زيادة" else "تخفيض"} السرعة لمدة ${durationSecondsStr} ثوانٍ ثم فصله آلياً.",
                                fontSize = 10.5.sp,
                                color = Color(0xFFE65100)
                            )
                        }

                        AutopilotStepType.ADD_WATER -> {
                            // ملاحظة: الضخ لا يشترط تصفير المؤشر قبله
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFE0F2FE),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "💧 نظام الضخ التلقائي يقوم بحساب وضخ كمية الماء المطلوبة بالكامل كوزن صافي يضاف فوق قراءة الميزان الحالية آلياً، دون اشتراط تصفير الميزان.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0369A1),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }

                            OutlinedTextField(
                                value = targetWaterKgStr,
                                onValueChange = { targetWaterKgStr = it },
                                label = { Text("كمية الماء المستهدفة (كجم)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        AutopilotStepType.TARE_SCALE -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFE0F2FE),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚖️ أمر تصفير المؤشر: سيقوم هذا الأمر بتصفير مؤشر الميزان الفعلي ليقرأ 0.0 كجم قبل الاستمرار.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0369A1),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        AutopilotStepType.LIFT_HYDRAULIC, AutopilotStepType.LOWER_HYDRAULIC -> {
                            OutlinedTextField(
                                value = durationSecondsStr,
                                onValueChange = { durationSecondsStr = it },
                                label = { Text("مدة حركة الهيدروليك (بالثواني)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }

                        AutopilotStepType.WAIT_DURATION -> {
                            OutlinedTextField(
                                value = durationSecondsStr,
                                onValueChange = { durationSecondsStr = it },
                                label = { Text("مدة التوقف/الانتظار (بالثواني)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }

                    // Optional Note
                    OutlinedTextField(
                        value = noteStr,
                        onValueChange = { noteStr = it },
                        label = { Text("ملاحظة توضيحية للخطوة (اختياري)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newStep = AutopilotStep(
                            id = existingStep?.id ?: UUID.randomUUID().toString(),
                            type = stepType,
                            timingMode = if (stepType == AutopilotStepType.START_MOTOR) AutopilotTimingMode.AFTER_PREVIOUS else timingMode,
                            startImmediate = startImmediate,
                            delayBeforeSeconds = delaySecondsStr.toIntOrNull() ?: 0,
                            motorOffsetMinutes = motorOffsetMinutesStr.toDoubleOrNull() ?: 0.0,
                            durationMinutes = durationMinutesStr.toDoubleOrNull() ?: 30.0,
                            durationSeconds = durationSecondsStr.toIntOrNull() ?: 5,
                            targetWaterKg = targetWaterKgStr.toDoubleOrNull() ?: 50.0,
                            note = noteStr
                        )

                        val indexToInsert = editingStepIndex ?: currentProgram.steps.size
                        val hasPrecedingTare = currentProgram.steps.take(indexToInsert).any { it.type == AutopilotStepType.TARE_SCALE }

                        if (stepType == AutopilotStepType.ADD_WATER && !hasPrecedingTare) {
                            pendingNewStep = newStep
                            pendingStepIndex = editingStepIndex
                            showTareWarningDialog = true
                            showAddEditStepDialog = false
                        } else {
                            val updatedSteps = currentProgram.steps.toMutableList()
                            if (editingStepIndex != null && editingStepIndex in updatedSteps.indices) {
                                updatedSteps[editingStepIndex!!] = newStep
                            } else {
                                updatedSteps.add(newStep)
                            }

                            val updatedP = currentProgram.copy(steps = updatedSteps)
                            val updatedProgs = programs.toMutableList()
                            updatedProgs[selectedProgramIndex] = updatedP
                            programs = updatedProgs
                            saveAutopilotPrograms(context, updatedProgs)
                            showAddEditStepDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                ) {
                    Text(if (existingStep != null) "حفظ التعديل" else "إضافة الأمر", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEditStepDialog = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    // =========================================================================
    // DIALOG: RENAME / SAVE PROGRAM
    // =========================================================================
    if (showProgramNameDialog) {
        AlertDialog(
            onDismissRequest = { showProgramNameDialog = false },
            title = { Text("تسمية البرنامج الآلي", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newProgramName,
                    onValueChange = { newProgramName = it },
                    label = { Text("اسم البرنامج الآلي") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProgramName.isNotBlank()) {
                            val updatedP = currentProgram.copy(name = newProgramName.trim())
                            val updatedProgs = programs.toMutableList()
                            updatedProgs[selectedProgramIndex] = updatedP
                            programs = updatedProgs
                            saveAutopilotPrograms(context, updatedProgs)
                        }
                        showProgramNameDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                ) {
                    Text("حفظ الاسم", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showProgramNameDialog = false }) {
                    Text("إلغاء", color = Color.Gray)
                }
            }
        )
    }

    // =========================================================================
    // DIALOG: SCALE ACTION RESULT NOTIFICATION (تأكيد نجاح تصفير الميزان وعرض الوزن)
    // =========================================================================
    scaleActionResultNotification?.let { data ->
        AlertDialog(
            onDismissRequest = { scaleActionResultNotification = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "تم ${data.actionLabel} بنجاح ⚖️",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = GBRDarkIndigo,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, IndustrialBorder),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "الموقع: ${String.format(Locale.US, "%.1f", data.netWeightAfter)} كجم  ·  شاشة الميزان: ${String.format(Locale.US, "%.1f", data.grossWeightAtZero)} كجم",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "توضيح: تم تصفير مؤشر الميزان بنجاح والتأكد من مطابقة ومزامنة القراءة.",
                                fontSize = 10.5.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { scaleActionResultNotification = null },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("dismiss_autopilot_scale_result_dialog_btn")
                ) {
                    Text("حسناً", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }

    // =========================================================================
    // DIALOG: TARE WARNING DIALOG
    // =========================================================================
    if (showTareWarningDialog && pendingNewStep != null) {
        AlertDialog(
            onDismissRequest = { showTareWarningDialog = false },
            title = {
                Text(
                    text = "💧 خيار تصفير الميزان قبل ضخ الماء",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = GBRDarkIndigo
                )
            },
            text = {
                Text(
                    text = "يقوم النظام آلياً بحساب كمية الماء الصافية المطلوبة وضخها بالكامل كوزن مضاف فوق الوزن الحالي للميزان بدقة دون فقد. هل ترغب أيضاً في إدراج خطوة تصفير المؤشر لتبدأ القراءة من الصفر، أم المتابعة وضخ كمية الماء مباشرة فوق الوزن الحالي؟",
                    fontSize = 13.sp,
                    color = Color(0xFF334155)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        // الخيار 1: إضافة خطوة تصفير المؤشر أولاً
                        val idx = pendingStepIndex ?: currentProgram.steps.size
                        val tareStep = AutopilotStep(
                            type = AutopilotStepType.TARE_SCALE,
                            timingMode = pendingNewStep!!.timingMode,
                            startImmediate = pendingNewStep!!.startImmediate,
                            note = "تصفير المؤشر مسبقاً قبل تعبئة المياه ⚖️"
                        )
                        val updatedSteps = currentProgram.steps.toMutableList()
                        if (pendingStepIndex != null && pendingStepIndex!! in updatedSteps.indices) {
                            // Insert Tare before the edited step, and update the edited step
                            updatedSteps[pendingStepIndex!!] = pendingNewStep!!
                            updatedSteps.add(pendingStepIndex!!, tareStep)
                        } else {
                            // Append Tare and then append Water
                            updatedSteps.add(tareStep)
                            updatedSteps.add(pendingNewStep!!)
                        }
                        val updatedP = currentProgram.copy(steps = updatedSteps)
                        val updatedProgs = programs.toMutableList()
                        updatedProgs[selectedProgramIndex] = updatedP
                        programs = updatedProgs
                        saveAutopilotPrograms(context, updatedProgs)
                        
                        showTareWarningDialog = false
                        pendingNewStep = null
                        pendingStepIndex = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)
                ) {
                    Text("إضافة خطوة تصفير أولاً", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            // Option 2: Execute as is without tare
                            val updatedSteps = currentProgram.steps.toMutableList()
                            if (pendingStepIndex != null && pendingStepIndex!! in updatedSteps.indices) {
                                updatedSteps[pendingStepIndex!!] = pendingNewStep!!
                            } else {
                                updatedSteps.add(pendingNewStep!!)
                            }
                            val updatedP = currentProgram.copy(steps = updatedSteps)
                            val updatedProgs = programs.toMutableList()
                            updatedProgs[selectedProgramIndex] = updatedP
                            programs = updatedProgs
                            saveAutopilotPrograms(context, updatedProgs)

                            showTareWarningDialog = false
                            pendingNewStep = null
                            pendingStepIndex = null
                        }
                    ) {
                        Text("الضخ المباشر فوق الوزن الحالي", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            showTareWarningDialog = false
                            pendingNewStep = null
                            pendingStepIndex = null
                        }
                    ) {
                        Text("إلغاء", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        )
    }

    // =========================================================================
    // DIALOG: EXECUTION SUCCESS SUMMARY
    // =========================================================================
    if (showExecutionSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showExecutionSuccessDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SuccessGreen,
                    modifier = Modifier.size(52.dp)
                )
            },
            title = {
                Text(
                    text = "🎉 تم اكتمال تنفيذ البرنامج الآلي بنجاح!",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = GBRDarkIndigo,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "تم تنفيذ الخطوات التالية تسلسلياً بالكامل:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                    
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        LazyColumn(
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(completedStepsList) { step ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White, RoundedCornerShape(6.dp))
                                        .border(0.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                when (step.type) {
                                                    AutopilotStepType.ADD_WATER -> Color(0xFF0284C7)
                                                    AutopilotStepType.TARE_SCALE -> Color(0xFF0F766E)
                                                    AutopilotStepType.START_MOTOR -> Color(0xFF16A34A)
                                                    AutopilotStepType.SPEED_UP -> Color(0xFFD97706)
                                                    AutopilotStepType.SPEED_DOWN -> Color(0xFF7C3AED)
                                                    AutopilotStepType.LIFT_HYDRAULIC, AutopilotStepType.LOWER_HYDRAULIC -> Color(0xFFEA580C)
                                                    else -> Color(0xFF475569)
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when (step.type) {
                                                AutopilotStepType.ADD_WATER -> Icons.Default.WaterDrop
                                                AutopilotStepType.TARE_SCALE -> Icons.Default.Balance
                                                AutopilotStepType.START_MOTOR -> Icons.Default.PlayArrow
                                                AutopilotStepType.SPEED_UP -> Icons.Default.FastForward
                                                AutopilotStepType.SPEED_DOWN -> Icons.Default.FastRewind
                                                AutopilotStepType.LIFT_HYDRAULIC -> Icons.Default.ArrowUpward
                                                AutopilotStepType.LOWER_HYDRAULIC -> Icons.Default.ArrowDownward
                                                else -> Icons.Default.Timer
                                            },
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = step.type.titleAr,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E293B)
                                        )
                                        val desc = when (step.type) {
                                            AutopilotStepType.ADD_WATER -> "كمية ماء: ${step.targetWaterKg} كجم"
                                            AutopilotStepType.TARE_SCALE -> "تصفير المؤشر ⚖️"
                                            AutopilotStepType.START_MOTOR -> "مدة: ${step.durationMinutes} دقيقة"
                                            AutopilotStepType.SPEED_UP, AutopilotStepType.SPEED_DOWN, AutopilotStepType.LIFT_HYDRAULIC, AutopilotStepType.LOWER_HYDRAULIC -> "مدة: ${step.durationSeconds} ثانية"
                                            else -> "انتظار: ${step.durationSeconds} ثانية"
                                        }
                                        Text(
                                            text = desc,
                                            fontSize = 10.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showExecutionSuccessDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) {
                    Text("حسناً", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}


// قراءة وزن صالحة للاعتماد كوزن خلاط فارغ: فئة الميزان فقط (ما عدا التصفير/المعايرة) وبها رقم موجب
private fun logWeightKgOf(entry: ExecutionLogEntry): Double? {
    if (!entry.category.contains("ميزان")) return null
    if (entry.event.contains("تصفير") || entry.event.contains("معايرة")) return null
    val src = if (entry.details.isNotBlank() && entry.details != "-") entry.details else entry.text
    val m = Regex("""(\d+(?:\.\d+)?)\s*(?:كجم|كغم|kg)?""", RegexOption.IGNORE_CASE).find(src) ?: return null
    return m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 }
}

@Composable
fun EquipmentControlPanel(viewModel: GbrViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lang by viewModel.appLanguage.collectAsState()
    fun t(ar: String, en: String): String = if (lang == "ar") ar else en

    // Keep screen awake while in Equipment Control Panel
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var showLine2QuickRemoteScreen by remember { mutableStateOf(false) }
    var showLine2AutopilotScreen by remember { mutableStateOf(false) }

    // Back Action Handler
    BackHandler(enabled = true) {
        if (showLine2AutopilotScreen) {
            showLine2AutopilotScreen = false
        } else if (showLine2QuickRemoteScreen) {
            showLine2QuickRemoteScreen = false
        } else if (viewModel.waterStepContext.value != null) {
            viewModel.returnFromEquipmentToWaterStep()
        } else {
            viewModel.showSegment(viewModel.previousSegmentForEquipmentControl)
        }
    }

    // Load initial values from SharedPreferences
    var line1Name by remember { mutableStateOf("خط الإنتاج 1") }
    var line1Ip by remember { mutableStateOf("") }
    var line1UpdateRate by remember { mutableStateOf(300L) }
    var line1WeightMode by remember { mutableStateOf("WEIGHT") }
    var line1PkgWeight by remember { mutableStateOf("24.35") }
    var line1TankTare by remember { mutableStateOf("0") }

    var line2Name by remember { mutableStateOf("خط الإنتاج 2") }
    var line2Ip by remember { mutableStateOf("") }
    var line2UpdateRate by remember { mutableStateOf(300L) }
    var line2WeightMode by remember { mutableStateOf("WEIGHT") }
    var line2PkgWeight by remember { mutableStateOf("24.35") }
    var line2TankTare by remember { mutableStateOf("0") }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
        line1Name = prefs.getString("line_1_name", "خط الإنتاج 1") ?: "خط الإنتاج 1"
        line1Ip = prefs.getString("line_1_ip", "") ?: ""
        line1UpdateRate = prefs.getLong("line_1_update_rate", 1000L)
        line1WeightMode = prefs.getString("line_1_weight_mode", "WEIGHT") ?: "WEIGHT"
        line1PkgWeight = prefs.getString("line_1_pkg_weight", "24.35") ?: "24.35"
        line1TankTare = prefs.getString("line_1_tank_tare", "0") ?: "0"

        line2Name = prefs.getString("line_2_name", "خط الإنتاج 2") ?: "خط الإنتاج 2"
        line2Ip = prefs.getString("line_2_ip", "") ?: ""
        line2UpdateRate = prefs.getLong("line_2_update_rate", 1000L)
        line2WeightMode = prefs.getString("line_2_weight_mode", "WEIGHT") ?: "WEIGHT"
        line2PkgWeight = prefs.getString("line_2_pkg_weight", "24.35") ?: "24.35"
        line2TankTare = prefs.getString("line_2_tank_tare", "0") ?: "0"
        
        // Ensure "devices_list" exists in SharedPreferences for SyncManager compatibility on first launch
        if (!prefs.contains("line_1_name")) {
            saveLines(context, "خط الإنتاج 1", "", 1000L, "خط الإنتاج 2", "", 1000L)
        }
    }

    // Tab switcher state: 0 for Line 1, 1 for Line 2
    var selectedTab by remember { mutableIntStateOf(viewModel.equipmentTargetLine?.coerceIn(0, 1) ?: 0) }
    LaunchedEffect(Unit) { viewModel.equipmentTargetLine = null }
    val waterStepCtx by viewModel.waterStepContext.collectAsState()

    // Selected Line Local values
    val currentLineName = if (selectedTab == 0) line1Name else line2Name
    val currentLineIp = if (selectedTab == 0) line1Ip else line2Ip

    var showEditDialog by remember { mutableStateOf(false) }

    // Inline edit form states synced to tab selection and dialog open
    var editName by remember(selectedTab, line1Name, line2Name, showEditDialog) {
        mutableStateOf(if (selectedTab == 0) line1Name else line2Name)
    }
    var editIp by remember(selectedTab, line1Ip, line2Ip, showEditDialog) {
        mutableStateOf(if (selectedTab == 0) line1Ip else line2Ip)
    }
    var editUpdateRateStr by remember(selectedTab, line1UpdateRate, line2UpdateRate, showEditDialog) {
        mutableStateOf((if (selectedTab == 0) line1UpdateRate else line2UpdateRate).toString())
    }

    // Device Scan States inside Edit Dialog
    var isScanningDevices by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableIntStateOf(0) }
    var scanSubnetPrefix by remember(showEditDialog) {
        val currentIp = getLocalDeviceIpAddress(context)
        mutableStateOf(extractSubnetPrefix(currentIp))
    }
    var detectedPhoneIp by remember(showEditDialog) {
        mutableStateOf(getLocalDeviceIpAddress(context) ?: "غير معروف")
    }
    val discoveredGbrDevices = remember { mutableStateListOf<DiscoveredGbrDevice>() }
    var scanStatusText by remember { mutableStateOf("") }

    val startDeviceScan: () -> Unit = {
        if (!isScanningDevices) {
            isScanningDevices = true
            scanProgress = 0
            discoveredGbrDevices.clear()

            val rawPrefix = scanSubnetPrefix.trim()
            val cleanPrefix = if (rawPrefix.endsWith(".")) rawPrefix else "$rawPrefix."
            scanStatusText = "جارٍ البحث على $cleanPrefix... (0 / 254)"

            coroutineScope.launch(Dispatchers.IO) {
                val scanClient = OkHttpClient.Builder()
                    .connectTimeout(1200, TimeUnit.MILLISECONDS)
                    .readTimeout(1200, TimeUnit.MILLISECONDS)
                    .writeTimeout(1200, TimeUnit.MILLISECONDS)
                    .build()

                val semaphore = Semaphore(24)
                val foundList = Collections.synchronizedList(mutableListOf<DiscoveredGbrDevice>())
                val completedCount = AtomicInteger(0)

                val scanJobs = (1..254).map { host ->
                    launch {
                        semaphore.withPermit {
                            val targetIp = "$cleanPrefix$host"
                            try {
                                val req = Request.Builder()
                                    .url("http://$targetIp/status")
                                    .get()
                                    .build()
                                scanClient.newCall(req).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val bodyStr = response.body?.string() ?: ""
                                        val json = JSONObject(bodyStr)
                                        val manufacturer = json.optString("manufacturer", "")

                                        // MANDATORY VERIFICATION: Must be "GBR Paints"
                                        if (manufacturer.equals("GBR Paints", ignoreCase = true)) {
                                            val deviceName = json.optString("device", "متحكم GBR")
                                            val hostname = json.optString("hostname", "")
                                            foundList.add(
                                                DiscoveredGbrDevice(
                                                    ip = targetIp,
                                                    deviceName = if (deviceName.isNotBlank()) deviceName else "متحكم GBR",
                                                    hostname = hostname,
                                                    manufacturer = manufacturer
                                                )
                                            )
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                // Ignore unreachable or non-HTTP endpoints
                            } finally {
                                val count = completedCount.incrementAndGet()
                                withContext(Dispatchers.Main) {
                                    scanProgress = count
                                    scanStatusText = "جارٍ البحث... ($count / 254)"
                                    discoveredGbrDevices.clear()
                                    discoveredGbrDevices.addAll(foundList)
                                }
                            }
                        }
                    }
                }

                scanJobs.joinAll()

                withContext(Dispatchers.Main) {
                    isScanningDevices = false
                    if (foundList.isEmpty()) {
                        scanStatusText = "اكتمل البحث: لم يتم العثور على أجهزة GBR Paints على النطاق $cleanPrefix ⚠️"
                        Toast.makeText(context, "لم يتم العثور على أجهزة GBR Paints على $cleanPrefix", Toast.LENGTH_SHORT).show()
                    } else {
                        scanStatusText = "تم العثور على ${foundList.size} جهاز GBR Paints 🟢"
                        Toast.makeText(context, "تم العثور على ${foundList.size} جهاز GBR Paints! 🟢", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Active Line Status connected to background polling in ViewModel
    val vmLine1Status by viewModel.line1Status.collectAsState()
    val vmLine2Status by viewModel.line2Status.collectAsState()
    val vmLine1Error by viewModel.line1PollingError.collectAsState()
    val vmLine2Error by viewModel.line2PollingError.collectAsState()

    // لا نُعيد تهيئة الحالة مع كل قراءة من الخدمة الخلفية (كان يسبب وميض «غير متصل»): الصفحة تتحكم بحالتها من قراءاتها هي
    var lineStatus by remember(selectedTab) {
        mutableStateOf<LineStatus?>(if (selectedTab == 0) vmLine1Status else vmLine2Status)
    }
    var isPollingError by remember(selectedTab) {
        mutableStateOf(if (selectedTab == 0) vmLine1Error else vmLine2Error)
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var previousFillActive by remember(selectedTab) { mutableStateOf(false) }
    var panelStopReadings by remember(selectedTab) { mutableStateOf(0) }
    var statusTick by remember { mutableStateOf(0) }
    // قياس حي لسرعة القراءة (زمن الاستجابة بالمللي ثانية ومتوسط الفاصل بين قراءتين ناجحتين)
    var pollLatencyMs by remember(selectedTab) { mutableStateOf(0L) }
    var pollGapMsEma by remember(selectedTab) { mutableStateOf(0L) }
    var logFetchFailures by remember(selectedTab) { mutableStateOf(0) }
    // أول ثوانٍ بعد فتح الصفحة: «جارٍ الاتصال…» بدل بطاقة «غير متصل» الحمراء
    var connectingGrace by remember(selectedTab, line1Ip, line2Ip) { mutableStateOf(true) }
    LaunchedEffect(selectedTab, line1Ip, line2Ip) { delay(4000); connectingGrace = false }
    // تخبر الخدمة الخلفية بالخط المفتوح فتخفف قراءتها له كي لا تزاحم قراءة الميزان السريعة (المتحكم يخدم طلباً واحداً في كل مرة)
    DisposableEffect(selectedTab) {
        viewModel.equipmentPanelActiveLine = selectedTab
        onDispose { viewModel.equipmentPanelActiveLine = -1 }
    }
    var fillFailureWarningState by remember(selectedTab) { mutableStateOf<Pair<Double, Double>?>(null) }
    val controllerAlerts by viewModel.activeControllerAlerts.collectAsState()

    // Live target weight text input & auto-fill dosing mode
    var targetInput by remember(selectedTab) { mutableStateOf("") }
    var fillStartWeight by remember(selectedTab) { mutableDoubleStateOf(0.0) }
    var fillRequestedWaterAmount by remember(selectedTab) { mutableDoubleStateOf(0.0) }

    // Alert Dialog / Notification state for scale action results & confirmation
    var scaleActionResultNotification by remember { mutableStateOf<ScaleActionResultData?>(null) }
    var showHardwareZeroConfirmDialog by remember { mutableStateOf(false) }
    var showRestartConfirmDialog by remember { mutableStateOf(false) }

    // Alert Dialog state for scale disconnected safety refusal
    var showScaleDisconnectedDialog by remember { mutableStateOf(false) }

    // Scale Weight Display Mode (WEIGHT = actual kg, CONTAINERS = pack count mode)
    val weightDisplayMode = if (selectedTab == 0) line1WeightMode else line2WeightMode
    val packageUnitWeightInput = if (selectedTab == 0) line1PkgWeight else line2PkgWeight
    val tankTareInput = if (selectedTab == 0) line1TankTare else line2TankTare
    var showWeightModeDialog by remember { mutableStateOf(false) }
    var tempWeightDisplayMode by remember { mutableStateOf("WEIGHT") }
    var tempPackageUnitWeightInput by remember { mutableStateOf("24.35") }
    var tempTankTareInput by remember { mutableStateOf("0") }
    var showFullScreenScaleMode by remember { mutableStateOf(false) }

    // Controller System Stats State
    data class ControllerSystemStats(
        val memoryUsedPct: Double = 0.0,
        val freeHeap: Long = 0,
        val totalHeap: Long = 0,
        val freeStorage: Long = 0,
        val totalStorage: Long = 0,
        val storageUsedPct: Double = 0.0,
        val rebootReason: String = ""
    )
    var systemStats by remember(selectedTab) { mutableStateOf<ControllerSystemStats?>(null) }
    var isLoadingStats by remember(selectedTab) { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }

    // Fault Logs State
    var faultLogsList by remember(selectedTab) { mutableStateOf<List<FaultLogEntry>>(emptyList()) }
    var isLoadingFaults by remember(selectedTab) { mutableStateOf(false) }
    var showFaultsDialog by remember { mutableStateOf(false) }
    var showClearFaultLogsConfirmDialog by remember { mutableStateOf(false) }
    val faultLogsScrollStateTab0 = rememberScrollState()
    val faultLogsScrollStateTab1 = rememberScrollState()
    val faultLogsScrollState = if (selectedTab == 0) faultLogsScrollStateTab0 else faultLogsScrollStateTab1

    // Execution Logs State
    var executionLogsList by remember(selectedTab) { mutableStateOf<List<ExecutionLogEntry>>(emptyList()) }
    // فلتر فئات سجل التنفيذ: الخيارات تُشتق من السجلات الواردة فعلاً (لا قائمة افتراضية)
    var logCategoryFilter by remember(selectedTab) { mutableStateOf<Set<String>>(emptySet()) }
    var showLogFilterDialog by remember { mutableStateOf(false) }
    var pendingMixerTareKg by remember { mutableStateOf<Double?>(null) }
    var isLoadingExecutionLogs by remember(selectedTab) { mutableStateOf(false) }
    var isShowingOfflineLogs by remember(selectedTab) { mutableStateOf(false) }
    var showClearLogsConfirmDialog by remember { mutableStateOf(false) }
    val executionLogsScrollStateTab0 = rememberScrollState()
    val executionLogsScrollStateTab1 = rememberScrollState()
    val executionLogsScrollState = if (selectedTab == 0) executionLogsScrollStateTab0 else executionLogsScrollStateTab1

    // WiFi Saved Networks State
    var savedWifiNetworks by remember(selectedTab) { mutableStateOf<List<SavedWifiItem>>(emptyList()) }
    var isLoadingWifiNetworks by remember(selectedTab) { mutableStateOf(false) }
    var showWifiDialog by remember { mutableStateOf(false) }
    var newWifiSsid by remember { mutableStateOf("") }
    var newWifiPass by remember { mutableStateOf("") }

    // --- State variables for Industrial Safety Alerts (Line 2) ---
    var showNoResponseDialog by remember { mutableStateOf(false) }
    var noResponseDialogTitle by remember { mutableStateOf("") }
    var noResponseDialogMessage by remember { mutableStateOf("") }

    var isMonitoringFillWeight by remember { mutableStateOf(false) }
    var showWaterNoIncreaseDialog by remember { mutableStateOf(false) }

    // --- Sensors Status, Last Run Timestamps & Wiring State (Line 2) ---
    val prefs = remember { context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE) }
    // تفعيل نوافذ العمليات الجارية (المحرك / الهيدروليك / السرعة) لكل خط على حدة
    var opBoxMotor by remember(selectedTab) { mutableStateOf(prefs.getBoolean("line_${selectedTab + 1}_opbox_motor", true)) }
    var opBoxHyd by remember(selectedTab) { mutableStateOf(prefs.getBoolean("line_${selectedTab + 1}_opbox_hyd", true)) }
    var opBoxSpeed by remember(selectedTab) { mutableStateOf(prefs.getBoolean("line_${selectedTab + 1}_opbox_speed", true)) }
    var motorLastRunTime by remember { mutableStateOf(prefs.getLong("line2_sensor_last_run_motor", 0L)) }
    var liftLastRunTime by remember { mutableStateOf(prefs.getLong("line2_sensor_last_run_lift", 0L)) }
    var lowerLastRunTime by remember { mutableStateOf(prefs.getLong("line2_sensor_last_run_lower", 0L)) }
    var speedLastRunTime by remember { mutableStateOf(prefs.getLong("line2_sensor_last_run_speed", 0L)) }
    var statsDialogTab by remember(selectedTab) { mutableStateOf(if (selectedTab == 1) 1 else 0) }
    var showMaintenanceDialog by remember { mutableStateOf(false) }
    var showSensorsFullScreenPage by remember { mutableStateOf(false) }

    // Synchronize sensors last run timestamps with real-time feedback
    LaunchedEffect(lineStatus?.motor_running, lineStatus?.relays, lineStatus?.hydraulic_up_active, lineStatus?.hydraulic_down_active, lineStatus?.speed_adjusting, selectedTab) {
        if (selectedTab == 1 && lineStatus != null) {
            val now = System.currentTimeMillis()
            val editor = prefs.edit()
            var changed = false
            if (lineStatus?.motor_running == true) {
                motorLastRunTime = now
                editor.putLong("line2_sensor_last_run_motor", now)
                changed = true
            }
            if (lineStatus?.hydraulic_up_active == true || lineStatus?.relays?.find { it.id == 4 }?.state == true) {
                liftLastRunTime = now
                editor.putLong("line2_sensor_last_run_lift", now)
                changed = true
            }
            if (lineStatus?.hydraulic_down_active == true || lineStatus?.relays?.find { it.id == 5 }?.state == true) {
                lowerLastRunTime = now
                editor.putLong("line2_sensor_last_run_lower", now)
                changed = true
            }
            if (lineStatus?.speed_adjusting == true || lineStatus?.relays?.find { it.id == 7 }?.state == true || lineStatus?.relays?.find { it.id == 8 }?.state == true) {
                speedLastRunTime = now
                editor.putLong("line2_sensor_last_run_speed", now)
                changed = true
            }
            if (changed) {
                editor.apply()
            }
        }
    }

    // Mutex to synchronize all network calls (prevent concurrent socket exhaustion on ESP32)
    val communicationMutex = remember { Mutex() }

    val fetchSystemStats: () -> Unit = {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank()) {
            isLoadingStats = true
            coroutineScope.launch {
                try {
                    val jsonStr = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/system/stats"))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) response.body?.string() ?: "" else ""
                            }
                        }
                    }
                    if (jsonStr.isNotBlank() && jsonStr.startsWith("{")) {
                        val obj = JSONObject(jsonStr)
                        val totalH = obj.optLong("heap_total_bytes", obj.optLong("total_heap", obj.optLong("total_memory", 240000L)))
                        val freeH = obj.optLong("heap_free_bytes", obj.optLong("free_heap", obj.optLong("free_memory", 0L)))
                        
                        var memPct = safeParseDouble(obj, "heap_used_percent", safeParseDouble(obj, "memory_used_pct", safeParseDouble(obj, "heap_used_pct", 0.0)))
                        if (memPct == 0.0 && totalH > 0 && freeH > 0) {
                            val usedH = totalH - freeH
                            memPct = ((usedH.toDouble() / totalH.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                        }
                        val calcFreeH = if (freeH == 0L && totalH > 0 && memPct > 0) {
                            (totalH * (1.0 - (memPct / 100.0))).toLong()
                        } else {
                            freeH
                        }

                        val totalS = obj.optLong("flash_total_bytes", obj.optLong("total_storage", obj.optLong("total_spiffs", 1500000L)))
                        val freeS = obj.optLong("flash_free_bytes", obj.optLong("free_storage", obj.optLong("free_spiffs", 0L)))
                        val usedS = obj.optLong("flash_used_bytes", if (totalS > 0 && freeS > 0) totalS - freeS else 0L)
                        
                        var flashPct = safeParseDouble(obj, "flash_used_percent", safeParseDouble(obj, "storage_used_pct", 0.0))
                        if (flashPct == 0.0 && totalS > 0 && (freeS > 0 || usedS > 0)) {
                            val calculatedUsedS = if (usedS > 0) usedS else (totalS - freeS)
                            flashPct = ((calculatedUsedS.toDouble() / totalS.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                        }
                        val calcFreeS = if (freeS == 0L && totalS > 0 && flashPct > 0) {
                            (totalS * (1.0 - (flashPct / 100.0))).toLong()
                        } else {
                            freeS
                        }

                        val rebootR = obj.optString("last_reset_reason", obj.optString("reboot_reason", obj.optString("reset_reason", obj.optString("last_reboot", "تشغيل عادي")))).trim()
                        systemStats = ControllerSystemStats(
                            memoryUsedPct = memPct,
                            freeHeap = calcFreeH,
                            totalHeap = totalH,
                            freeStorage = calcFreeS,
                            totalStorage = totalS,
                            storageUsedPct = flashPct,
                            rebootReason = rebootR
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isLoadingStats = false
                }
            }
        }
    }

    fun fetchFaultLogs(isSilent: Boolean = false) {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank()) {
            if (!isSilent) {
                isLoadingFaults = true
            }
            coroutineScope.launch {
                try {
                    val jsonStr = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/log/faults"))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) response.body?.string() ?: "" else ""
                            }
                        }
                    }
                    if (jsonStr.isNotBlank()) {
                        val list = mutableListOf<FaultLogEntry>()
                        val jsonTrimmed = jsonStr.trim()
                        if (jsonTrimmed.startsWith("{")) {
                            val obj = JSONObject(jsonTrimmed)
                            val arr = obj.optJSONArray("log") ?: obj.optJSONArray("faults") ?: obj.optJSONArray("logs")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val optObj = arr.optJSONObject(i)
                                    if (optObj != null) {
                                        list.add(parseFaultItem(optObj))
                                    } else {
                                        val itemStr = arr.optString(i).trim()
                                        if (itemStr.isNotBlank()) list.add(parseFaultItem(itemStr))
                                    }
                                }
                            }
                        } else if (jsonTrimmed.startsWith("[")) {
                            val arr = JSONArray(jsonTrimmed)
                            for (i in 0 until arr.length()) {
                                val optObj = arr.optJSONObject(i)
                                if (optObj != null) {
                                    list.add(parseFaultItem(optObj))
                                } else {
                                    val itemStr = arr.optString(i).trim()
                                    if (itemStr.isNotBlank()) list.add(parseFaultItem(itemStr))
                                }
                            }
                        }
                        // Only mutate state if list actually changed to prevent recomposition jitter / resetting scroll
                        if (faultLogsList != list) {
                            faultLogsList = list
                        }
                    } else {
                        if (faultLogsList.isNotEmpty()) {
                            faultLogsList = emptyList()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    if (!isSilent) {
                        isLoadingFaults = false
                    }
                }
            }
        }
    }

    val clearFaultLogs: () -> Unit = {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank()) {
            coroutineScope.launch {
                try {
                    val success = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/log/faults/clear"))
                                .build()
                            client.newCall(request).execute().use { response -> response.isSuccessful }
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "تم حذف سجل الأعطال بنجاح 🧹", Toast.LENGTH_SHORT).show()
                        fetchFaultLogs()
                    } else {
                        Toast.makeText(context, "فشل مسح سجل الأعطال", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val clearFullLogs: () -> Unit = {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank()) {
            coroutineScope.launch {
                try {
                    val success = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/log/clear"))
                                .build()
                            client.newCall(request).execute().use { response -> response.isSuccessful }
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "تم حذف السجل الدائم بالكامل 🧹", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "فشل مسح السجل الدائم", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun fetchExecutionLogs(isSilent: Boolean = false) {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        
        // Load local cache immediately so the user can see saved logs instantly (Offline-First)
        val key = "cached_logs_line_$selectedTab"
        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
        val cachedStr = prefs.getString(key, null)
        if (!cachedStr.isNullOrBlank()) {
            val cachedList = mutableListOf<ExecutionLogEntry>()
            try {
                val arr = JSONArray(cachedStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    cachedList.add(
                        ExecutionLogEntry(
                            time = obj.optString("time", ""),
                            text = obj.optString("text", ""),
                            category = obj.optString("category", ""),
                            event = obj.optString("event", ""),
                            details = obj.optString("details", "")
                        )
                    )
                }
                // تحميل النسخة المحفوظة فقط عند أول فتح (الجدول فارغ) ودون وسم «غير متصل» — كان الوسم يومض مع كل تحديث
                if (executionLogsList.isEmpty() && cachedList.isNotEmpty()) {
                    executionLogsList = cachedList
                }
            } catch (parseEx: Exception) {
                parseEx.printStackTrace()
            }
        } else {
            // No cache yet, if not connected we will start empty
            if (executionLogsList.isNotEmpty()) {
                executionLogsList = emptyList()
            }
            isShowingOfflineLogs = false
        }

        if (!isSilent) {
            isLoadingExecutionLogs = true
        }

        coroutineScope.launch {
            if (activeIp.isNotBlank()) {
                try {
                    val jsonStr = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/log"))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) response.body?.string() ?: "" else ""
                            }
                        }
                    }
                    logFetchFailures = 0
                    if (jsonStr.isNotBlank()) {
                        val list = mutableListOf<ExecutionLogEntry>()
                        val jsonTrimmed = jsonStr.trim()
                        if (jsonTrimmed.startsWith("{")) {
                            val obj = JSONObject(jsonTrimmed)
                            val arr = obj.optJSONArray("log") ?: obj.optJSONArray("logs") ?: obj.optJSONArray("faults")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val optObj = arr.optJSONObject(i)
                                    if (optObj != null) {
                                        list.add(parseExecutionLogItem(optObj))
                                    } else {
                                        val itemStr = arr.optString(i).trim()
                                        if (itemStr.isNotBlank()) list.add(parseExecutionLogItem(itemStr))
                                    }
                                }
                            } else {
                                val keys = obj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val item = obj.opt(k)
                                    if (item != null) list.add(parseExecutionLogItem(item))
                                }
                            }
                        } else if (jsonTrimmed.startsWith("[")) {
                            val arr = JSONArray(jsonTrimmed)
                            for (i in 0 until arr.length()) {
                                val optObj = arr.optJSONObject(i)
                                if (optObj != null) {
                                    list.add(parseExecutionLogItem(optObj))
                                } else {
                                    val itemStr = arr.optString(i).trim()
                                    if (itemStr.isNotBlank()) list.add(parseExecutionLogItem(itemStr))
                                }
                            }
                        } else {
                            val lines = jsonTrimmed.split("\n")
                            for (line in lines) {
                                val trimmed = line.trim()
                                if (trimmed.isNotBlank()) {
                                    list.add(parseExecutionLogItem(trimmed))
                                }
                            }
                        }
                        // Only update state if entries actually changed to prevent any UI recomposition/jitter
                        if (executionLogsList != list || isShowingOfflineLogs) {
                            executionLogsList = list
                            isShowingOfflineLogs = false
                        }

                        // Update local cache with fresh logs
                        val arrJson = JSONArray()
                        for (entry in list) {
                            val entryObj = JSONObject()
                            entryObj.put("time", entry.time)
                            entryObj.put("text", entry.text)
                            entryObj.put("category", entry.category)
                            entryObj.put("event", entry.event)
                            entryObj.put("details", entry.details)
                            arrJson.put(entryObj)
                        }
                        prefs.edit().putString(key, arrJson.toString()).apply()
                    } else {
                        // Deleting locally because it's deleted on the controller (or returned empty)
                        if (executionLogsList.isNotEmpty() || isShowingOfflineLogs) {
                            executionLogsList = emptyList()
                            isShowingOfflineLogs = false
                        }
                        prefs.edit().putString(key, "[]").apply()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // فشل الجلب: لا نعلن «غير متصل» إلا بعد 3 إخفاقات متتالية أو انقطاع مؤكد للمتحكم (طلب واحد متأخر ليس انقطاعاً)
                    logFetchFailures++
                    if (logFetchFailures >= 3 || isPollingError) isShowingOfflineLogs = true
                } finally {
                    if (!isSilent) {
                        isLoadingExecutionLogs = false
                    }
                }
            } else {
                // IP is blank -> already showing local cache, ensure offline mode indicator is active
                isShowingOfflineLogs = true
                if (!isSilent) {
                    isLoadingExecutionLogs = false
                }
            }
        }
    }

    val clearExecutionLogs: () -> Unit = {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank() && (isPollingError || lineStatus == null)) {
            // مسح سجل المتحكم يتطلب اتصالاً فعلياً — لا نرسل الطلب ولا نمسح النسخة المحلية
            Toast.makeText(context, "الحذف متاح فقط عند الاتصال بالمتحكم", Toast.LENGTH_SHORT).show()
        } else if (activeIp.isNotBlank()) {
            coroutineScope.launch {
                try {
                    val success = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/log/clear"))
                                .build()
                            client.newCall(request).execute().use { response -> response.isSuccessful }
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "تم مسح سجل التنفيذ بنجاح 🧹", Toast.LENGTH_SHORT).show()
                        
                        // Clear local cache immediately
                        val key = "cached_logs_line_$selectedTab"
                        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString(key, "[]").apply()
                        
                        fetchExecutionLogs()
                    } else {
                        Toast.makeText(context, "فشل مسح سجل التنفيذ", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // Also allow clearing offline cache when IP is blank
            val key = "cached_logs_line_$selectedTab"
            val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(key, "[]").apply()
            executionLogsList = emptyList()
            isShowingOfflineLogs = false
            Toast.makeText(context, "تم مسح السجل المحلي المخزن بالهاتف 🧹", Toast.LENGTH_SHORT).show()
        }
    }

    val fetchSavedWifiNetworks: () -> Unit = {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank()) {
            isLoadingWifiNetworks = true
            coroutineScope.launch {
                try {
                    val jsonStr = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/wifi/saved-networks"))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) response.body?.string() ?: "" else ""
                            }
                        }
                    }
                    if (jsonStr.isNotBlank()) {
                        val list = mutableListOf<SavedWifiItem>()
                        if (jsonStr.startsWith("{")) {
                            val obj = JSONObject(jsonStr)
                            val arr = obj.optJSONArray("networks") ?: obj.optJSONArray("saved_networks") ?: obj.optJSONArray("ssids")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    val itemObj = arr.optJSONObject(i)
                                    if (itemObj != null) {
                                        val ssid = itemObj.optString("ssid", itemObj.optString("name", ""))
                                        val conn = itemObj.optBoolean("connected", itemObj.optBoolean("is_connected", false))
                                        if (ssid.isNotBlank()) list.add(SavedWifiItem(ssid, conn))
                                    } else {
                                        val strVal = arr.optString(i).trim()
                                        if (strVal.startsWith("{")) {
                                            try {
                                                val parsedObj = JSONObject(strVal)
                                                val ssid = parsedObj.optString("ssid", parsedObj.optString("name", ""))
                                                val conn = parsedObj.optBoolean("connected", parsedObj.optBoolean("is_connected", false))
                                                if (ssid.isNotBlank()) list.add(SavedWifiItem(ssid, conn))
                                            } catch (e: Exception) {
                                                if (strVal.isNotBlank()) list.add(SavedWifiItem(strVal, false))
                                            }
                                        } else if (strVal.isNotBlank()) {
                                            list.add(SavedWifiItem(strVal, false))
                                        }
                                    }
                                }
                            }
                        } else if (jsonStr.startsWith("[")) {
                            val arr = JSONArray(jsonStr)
                            for (i in 0 until arr.length()) {
                                val itemObj = arr.optJSONObject(i)
                                if (itemObj != null) {
                                    val ssid = itemObj.optString("ssid", itemObj.optString("name", ""))
                                    val conn = itemObj.optBoolean("connected", itemObj.optBoolean("is_connected", false))
                                    if (ssid.isNotBlank()) list.add(SavedWifiItem(ssid, conn))
                                } else {
                                    val strVal = arr.optString(i).trim()
                                    if (strVal.isNotBlank()) list.add(SavedWifiItem(strVal, false))
                                }
                            }
                        }
                        savedWifiNetworks = list
                    } else {
                        savedWifiNetworks = emptyList()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isLoadingWifiNetworks = false
                }
            }
        }
    }

    val addWifiNetwork: (String, String) -> Unit = { ssid, pass ->
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank() && ssid.isNotBlank()) {
            coroutineScope.launch {
                try {
                    val encodedSsid = java.net.URLEncoder.encode(ssid, "UTF-8")
                    val encodedPass = java.net.URLEncoder.encode(pass, "UTF-8")
                    val url = resolveUrl(activeIp, "/wifi/add-network?ssid=$encodedSsid&pass=$encodedPass")
                    val success = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder().url(url).build()
                            client.newCall(request).execute().use { response -> response.isSuccessful }
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "تمت إضافة الشبكة ($ssid) بنجاح 📶", Toast.LENGTH_SHORT).show()
                        newWifiSsid = ""
                        newWifiPass = ""
                        fetchSavedWifiNetworks()
                    } else {
                        Toast.makeText(context, "فشل إرسال الشبكة للمتحكم", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val deleteWifiNetwork: (String) -> Unit = { ssid ->
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank() && ssid.isNotBlank()) {
            coroutineScope.launch {
                try {
                    val encodedSsid = java.net.URLEncoder.encode(ssid, "UTF-8")
                    val url = resolveUrl(activeIp, "/wifi/delete-network?ssid=$encodedSsid")
                    val success = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder().url(url).build()
                            client.newCall(request).execute().use { response -> response.isSuccessful }
                        }
                    }
                    if (success) {
                        Toast.makeText(context, "تم حذف الشبكة ($ssid) من المحفوظات 🗑️", Toast.LENGTH_SHORT).show()
                        fetchSavedWifiNetworks()
                    } else {
                        Toast.makeText(context, "فشل حذف الشبكة", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val handleScaleAction: (String, String) -> Unit = { url, label ->
        coroutineScope.launch {
            try {
                val jsonStr = communicationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(url)
                            .build()
                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string()?.trim() ?: ""
                            if (response.isSuccessful) {
                                if (bodyStr.startsWith("{")) {
                                    val obj = JSONObject(bodyStr)
                                    val isSuccess = obj.optBoolean("success", true)
                                    val errorMsg = obj.optString("error", obj.optString("message", "")).trim()
                                    if (!isSuccess || errorMsg.isNotBlank()) {
                                        val displayErr = if (errorMsg.isNotBlank()) errorMsg else "فشل عملية الـ $label"
                                        throw Exception(displayErr)
                                    }
                                }
                                bodyStr
                            } else {
                                var errorMsg = ""
                                if (bodyStr.startsWith("{")) {
                                    try {
                                        val obj = JSONObject(bodyStr)
                                        errorMsg = obj.optString("error", obj.optString("message", "")).trim()
                                    } catch (e: Exception) {}
                                }
                                if (errorMsg.isBlank()) {
                                    errorMsg = "خطأ من السيرفر: ${response.code}"
                                }
                                throw Exception(errorMsg)
                            }
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    if (jsonStr.isNotBlank() && jsonStr.startsWith("{")) {
                        val obj = JSONObject(jsonStr)
                        val netWeight = safeParseDouble(obj, "net_weight", safeParseDouble(obj, "gross_weight_after", 0.0))
                        
                        val grossAtZero = if (obj.has("gross_weight_at_zero")) {
                            safeParseDouble(obj, "gross_weight_at_zero", 0.0)
                        } else if (obj.has("gross_weight_before")) {
                            safeParseDouble(obj, "gross_weight_before", 0.0)
                        } else if (obj.has("gross_weight")) {
                            safeParseDouble(obj, "gross_weight", 0.0)
                        } else {
                            lineStatus?.weight ?: 0.0
                        }
                        
                        scaleActionResultNotification = ScaleActionResultData(
                            actionLabel = label,
                            netWeightAfter = netWeight,
                            grossWeightAtZero = grossAtZero
                        )
                    } else {
                        scaleActionResultNotification = ScaleActionResultData(
                            actionLabel = label,
                            netWeightAfter = 0.0,
                            grossWeightAtZero = lineStatus?.weight ?: 0.0
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, e.message ?: "خطأ في الاتصال", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val refreshConnectionStatus: () -> Unit = {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        val activeName = if (selectedTab == 0) line1Name else line2Name
        if (activeIp.isBlank()) {
            Toast.makeText(context, "لم يتم ضبط عنوان IP لـ $activeName ⚠️", Toast.LENGTH_SHORT).show()
            isRefreshing = false
        } else {
            isRefreshing = true
            coroutineScope.launch {
                try {
                    val updatedStatus = communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            val request = Request.Builder()
                                .url(resolveUrl(activeIp, "/status"))
                                .build()
                            client.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    val jsonStr = response.body?.string() ?: ""
                                    parseLineStatus(jsonStr)
                                } else {
                                    throw Exception("Unsuccessful response: ${response.code}")
                                }
                            }
                        }
                    }
                    lineStatus = updatedStatus
                    isPollingError = false
                    fetchSystemStats()
                    fetchFaultLogs()
                    fetchSavedWifiNetworks()
                    fetchExecutionLogs()
                    Toast.makeText(context, "تم تحديث حالة الاتصال بـ $activeName بنجاح 🟢", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    isPollingError = true
                    Toast.makeText(context, "تعذر تحديث الاتصال بـ $activeName (غير متصل) 🔴", Toast.LENGTH_SHORT).show()
                } finally {
                    delay(300)
                    isRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(selectedTab, line1Ip, line2Ip) {
        delay(1200) // قراءة الحالة (الوزن) أولاً؛ الباقي بعدها كي لا تنتظر خلفه
        fetchSystemStats()
        fetchFaultLogs()
        fetchSavedWifiNetworks()
        fetchExecutionLogs(isSilent = executionLogsList.isNotEmpty())
    }

    // Periodic Execution Logs Auto-update (Completely silent in background without disrupting table)
    LaunchedEffect(selectedTab, line1Ip, line2Ip) {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        if (activeIp.isNotBlank()) {
            while (true) {
                delay(15000) // سجل التنفيذ كل 15 ثانية (كان 5) كي لا يزاحم قراءة الميزان
                if (!communicationMutex.isLocked) fetchExecutionLogs(isSilent = true)
            }
        }
    }

    // Periodic Polling when IP is not empty
    LaunchedEffect(selectedTab, line1Ip, line2Ip, line1UpdateRate, line2UpdateRate) {
        val activeIp = if (selectedTab == 0) line1Ip else line2Ip
        val activeUpdateRate = if (selectedTab == 0) line1UpdateRate else line2UpdateRate
        if (activeIp.isBlank()) {
            lineStatus = null
            isPollingError = true
            return@LaunchedEffect
        }

        var consecutiveFailures = 0
        // حد أدنى 250ms: المتحكم يخدم طلباً واحداً في كل مرة وقراءة الميزان نفسها ≈120–200ms، وأسرع من ذلك يُراكم الطلبات ويبطّئ العرض
        val effectiveRate = activeUpdateRate.coerceAtLeast(250L)
        var lastPollSuccessAt = 0L

        while (true) {
            val pollStartedAt = System.currentTimeMillis()
            try {
                val updatedStatus = communicationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(resolveUrl(activeIp, "/status"))
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val jsonStr = response.body?.string() ?: ""
                                if (!isCompleteStatusJson(jsonStr)) throw Exception("Incomplete status reading")
                                parseLineStatus(jsonStr)
                            } else {
                                throw Exception("Unsuccessful response: ${response.code}")
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    lineStatus = updatedStatus
                    statusTick++
                    consecutiveFailures = 0
                    isPollingError = false
                    val nowMs = System.currentTimeMillis()
                    pollLatencyMs = nowMs - pollStartedAt
                    if (lastPollSuccessAt > 0L) {
                        val gap = (nowMs - lastPollSuccessAt).coerceAtLeast(1L)
                        pollGapMsEma = if (pollGapMsEma == 0L) gap else (pollGapMsEma * 7 + gap * 3) / 10
                    }
                    lastPollSuccessAt = nowMs
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        isPollingError = true
                    }
                }
            }
            // ننتظر (السرعة − زمن الطلب) بدل إضافتها فوقه، وبحد أدنى صغير يترك المجال لبقية الطلبات
            delay((effectiveRate - (System.currentTimeMillis() - pollStartedAt)).coerceAtLeast(40L))
        }
    }

    // Phone notification progress updates for active fill operation
    LaunchedEffect(lineStatus, statusTick, selectedTab, targetInput, isPollingError) {
        val status = lineStatus
        val activeName = if (selectedTab == 0) line1Name else line2Name
        val fillActive = status?.fill_active == true && !isPollingError
        val fillTarget = if ((status?.fill_target ?: 0.0) > 0) status!!.fill_target else (targetInput.toDoubleOrNull() ?: 0.0)
        val currentWeight = status?.weight ?: 0.0

        if (status?.fill_active == true && (status.fill_target ?: 0.0) > 0.0 && targetInput.isBlank()) {
            targetInput = String.format(Locale.US, "%.1f", status.fill_target)
        }

        // أثناء فشل الاتصال لا نحكم على التعبئة بالنجاح أو الفشل (قد تكون ما زالت تعمل)
        if (isPollingError && previousFillActive) return@LaunchedEffect
        if (fillActive) panelStopReadings = 0
        val stopConfirmed = !(previousFillActive && !fillActive) || (++panelStopReadings >= 2)
        if (!stopConfirmed) return@LaunchedEffect

        if (previousFillActive && !fillActive && fillTarget > 0) {
            panelStopReadings = 0
            val isSuccess = currentWeight >= (fillTarget - 0.5) || currentWeight >= (fillTarget * 0.95)
            if (isSuccess) {
                EquipmentNotifications.showEquipmentFillCompletedNotification(context, selectedTab, activeName, currentWeight, fillTarget)
            } else {
                EquipmentNotifications.showEquipmentFillFailedNotification(
                    context = context,
                    lineIndex = selectedTab,
                    lineName = activeName,
                    currentWeight = currentWeight,
                    targetWeight = fillTarget,
                    reason = "توقفت التعبئة قبل بلوغ الهدف (راجع سجل أعطال المتحكم للتفاصيل)"
                )
                fillFailureWarningState = Pair(currentWeight, fillTarget)
                viewModel.refreshAllControllerAlerts()
            }
        } else {
            EquipmentNotifications.updateEquipmentFillNotification(context, selectedTab, activeName, fillActive, currentWeight, fillTarget)
        }
        previousFillActive = fillActive
    }

    // المربع العائم للعمليات الجارية (محرك/هيدروليك/سرعة)؛ تُفعَّل/تُوقف لكل خط من ترس إعدادات الخط
    LaunchedEffect(
        lineStatus?.motor_running, lineStatus?.motor_timed_run_active, lineStatus?.motor_timed_run_remaining_seconds,
        lineStatus?.motor_timed_run_paused, lineStatus?.hydraulic_up_active, lineStatus?.hydraulic_down_active,
        lineStatus?.speed_adjusting, lineStatus?.relays, selectedTab, isPollingError, opBoxMotor, opBoxHyd, opBoxSpeed
    ) {
        // نشر القراءة الحية للصفحة إلى الحالة المشتركة كي يعرضها المربع العائم فوراً (بدل انتظار القراءة الخلفية البطيئة)
        val live = if (isPollingError) null else lineStatus
        if (live != null) {
            if (selectedTab == 0) viewModel.line1Status.value = live else viewModel.line2Status.value = live
        }
    }

    // Periodic polling of fault logs from controller for Line 2 (Source of Truth for Alerts)
    LaunchedEffect(selectedTab, line2Ip) {
        if (selectedTab == 1 && line2Ip.isNotBlank()) {
            while (true) {
                delay(6000L) // سجل الأعطال كل 6 ثوانٍ (كان 4)
                try {
                    if (!communicationMutex.isLocked) fetchFaultLogs(isSilent = true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    var dismissedFaults by remember { mutableStateOf(setOf<String>()) }
    var activeUrgentFault by remember { mutableStateOf<FaultLogEntry?>(null) }

    LaunchedEffect(faultLogsList, selectedTab) {
        if (selectedTab == 1) {
            if (faultLogsList.isEmpty()) {
                dismissedFaults = emptySet()
                activeUrgentFault = null
            } else {
                // Find the first fault log entry that has not been dismissed yet
                val newFault = faultLogsList.firstOrNull { fault ->
                    val uniqueKey = "${fault.time}_${fault.text}"
                    !dismissedFaults.contains(uniqueKey)
                }
                if (newFault != null) {
                    activeUrgentFault = newFault
                    
                    val txt = newFault.text.lowercase(Locale.ROOT)
                    when {
                        txt.contains("motor") || txt.contains("محرك") || txt.contains("relay 2") || txt.contains("relay 3") -> {
                            noResponseDialogTitle = "⚠️ عطل عاجل: لا توجد استجابة من المحرك"
                            noResponseDialogMessage = "سجل المتحكم العطل التالي في سجل الأعطال:\n\n${newFault.text}\n\nيرجى التحقق من التوصيلات وسير الحركة."
                        }
                        txt.contains("water") || txt.contains("water_valve") || txt.contains("fill") || txt.contains("scale") || txt.contains("وزن") || txt.contains("ماء") || txt.contains("مؤشر") || txt.contains("صمام") -> {
                            noResponseDialogTitle = "⚠️ عطل عاجل: فشل ضخ المياه / لا توجد زيادة بالوزن"
                            noResponseDialogMessage = "سجل المتحكم العطل التالي في سجل الأعطال:\n\n${newFault.text}\n\nيرجى فحص صمام المياه أو مؤشر الوزن فوراً."
                        }
                        else -> {
                            noResponseDialogTitle = "⚠️ تنبيه عطل من المتحكم"
                            noResponseDialogMessage = "سجل المتحكم العطل التالي في سجل الأعطال نشطاً:\n\n${newFault.text}\n\nيرجى المتابعة والتحقق."
                        }
                    }
                    showNoResponseDialog = true
                } else {
                    activeUrgentFault = null
                    showNoResponseDialog = false
                }
            }
        }
    }

    // Universal Background Command Sender with robust HTTP response and safety error handling
    val sendCommand: (String, (LineStatus?) -> Unit, (Exception) -> Unit) -> Unit = { urlString, onSuccess, onFailure ->
        coroutineScope.launch {
            try {
                val updatedStatus = communicationMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(urlString)
                            .build()
                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string()?.trim() ?: ""
                            
                            // Check HTTP status code success (200..299)
                            if (response.isSuccessful) {
                                if (bodyStr.startsWith("{")) {
                                    val obj = JSONObject(bodyStr)
                                    val isSuccess = obj.optBoolean("success", true)
                                    val errorMsg = obj.optString("error", obj.optString("message", "")).trim()
                                    
                                    if (!isSuccess || errorMsg.isNotBlank()) {
                                        val displayErr = if (errorMsg.isNotBlank()) errorMsg else "رفض المتحكم تنفيذ الأمر"
                                        throw Exception(displayErr)
                                    }
                                    parseLineStatus(bodyStr)
                                } else {
                                    null
                                }
                            } else {
                                // Extract error message from JSON body if present (e.g., HTTP 409 Conflict when scale is disconnected)
                                var errorMsg = ""
                                if (bodyStr.startsWith("{")) {
                                    try {
                                        val obj = JSONObject(bodyStr)
                                        errorMsg = obj.optString("error", obj.optString("message", "")).trim()
                                    } catch (e: Exception) {
                                        errorMsg = ""
                                    }
                                }
                                if (errorMsg.isBlank()) {
                                    errorMsg = when (response.code) {
                                        409 -> "الميزان غير متصل! يتعذر فتح صمام الماء أو بدء التعبئة التلقائية."
                                        400 -> "طلب غير صالح للمتحكم (${response.code})"
                                        500 -> "خطأ داخلي في نظام المتحكم (${response.code})"
                                        else -> "رفض المتحكم الطلب (رمز الاستجابة: ${response.code})"
                                    }
                                }
                                throw Exception(errorMsg)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (selectedTab == 1) {
                        val now = System.currentTimeMillis()
                        val editor = prefs.edit()
                        var changed = false
                        if (urlString.contains("relay=2")) {
                            motorLastRunTime = now
                            editor.putLong("line2_sensor_last_run_motor", now)
                            changed = true
                        }
                        if (urlString.contains("relay=4")) {
                            liftLastRunTime = now
                            editor.putLong("line2_sensor_last_run_lift", now)
                            changed = true
                        }
                        if (urlString.contains("relay=5")) {
                            lowerLastRunTime = now
                            editor.putLong("line2_sensor_last_run_lower", now)
                            changed = true
                        }
                        if (urlString.contains("relay=7") || urlString.contains("relay=8")) {
                            speedLastRunTime = now
                            editor.putLong("line2_sensor_last_run_speed", now)
                            changed = true
                        }
                        if (changed) {
                            editor.apply()
                        }
                    }
                    onSuccess(updatedStatus)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onFailure(e)
                }
            }
        }
    }

    val triggerEmergencyStop = {
        if (currentLineIp.isBlank()) {
            Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
        } else {
            coroutineScope.launch {
                try {
                    communicationMutex.withLock {
                        withContext(Dispatchers.IO) {
                            if (selectedTab == 1) {
                                // Line 2 (ESP32-S3): Emergency Stop Sequence
                                // 1. Stop active fill process
                                try {
                                    val fillStopUrl = resolveUrl(currentLineIp, "/fill/stop")
                                    val stopFillRequest = Request.Builder().url(fillStopUrl).build()
                                    client.newCall(stopFillRequest).execute().use {}
                                } catch (e: Exception) {}

                                // 2. Send pulse to stop motor on Relay 3 (state=on)
                                try {
                                    val stopMotorUrl = resolveUrl(currentLineIp, "/control?relay=3&state=on")
                                    val stopMotorRequest = Request.Builder().url(stopMotorUrl).build()
                                    client.newCall(stopMotorRequest).execute().use {}
                                } catch (e: Exception) {}

                                // 3. Turn off Relays 1, 4, 5, 7, 8
                                val line2RelaysToStop = listOf(1, 4, 5, 7, 8)
                                for (id in line2RelaysToStop) {
                                    try {
                                        val relayUrl = resolveUrl(currentLineIp, "/control?relay=$id&state=off")
                                        val stopRelayRequest = Request.Builder().url(relayUrl).build()
                                        client.newCall(stopRelayRequest).execute().use {}
                                    } catch (e: Exception) {}
                                }
                            } else {
                                // Line 1: Existing behavior
                                // 1. Stop active fill process
                                try {
                                    val fillStopUrl = resolveUrl(currentLineIp, "/fill/stop")
                                    val stopFillRequest = Request.Builder().url(fillStopUrl).build()
                                    client.newCall(stopFillRequest).execute().use {}
                                } catch (e: Exception) {}

                                // 2. Turn off all relays (1 to 4)
                                for (id in 1..4) {
                                    try {
                                        val relayUrl = resolveUrl(currentLineIp, "/control?relay=$id&state=off")
                                        val stopRelayRequest = Request.Builder().url(relayUrl).build()
                                        client.newCall(stopRelayRequest).execute().use {}
                                    } catch (e: Exception) {}
                                }
                            }

                            // 3. Trigger immediate status update to refresh the UI state
                            try {
                                val statusUrl = resolveUrl(currentLineIp, "/status")
                                val statusRequest = Request.Builder().url(statusUrl).build()
                                client.newCall(statusRequest).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val jsonStr = response.body?.string() ?: ""
                                        if (jsonStr.startsWith("{")) {
                                            val updatedStatus = parseLineStatus(jsonStr)
                                            withContext(Dispatchers.Main) {
                                                lineStatus = updatedStatus
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                } finally {
                    Toast.makeText(context, "🚨 تم إيقاف الطوارئ لجميع المخارج والمنافذ فوراً!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Active controller connectivity status
    val isControllerOnline = !isPollingError && lineStatus != null && currentLineIp.isNotBlank()

    if (showLine2AutopilotScreen && selectedTab == 1) {
        Line2AutopilotScreen(
            currentLineName = currentLineName,
            currentLineIp = currentLineIp,
            isControllerOnline = isControllerOnline,
            lineStatus = lineStatus,
            onBack = { showLine2AutopilotScreen = false },
            onScaleDisconnected = { showScaleDisconnectedDialog = true },
            sendCommand = sendCommand,
            onStatusUpdate = { updated -> lineStatus = updated },
            triggerEmergencyStop = { triggerEmergencyStop() },
            onOpenQuickRemote = {
                showLine2AutopilotScreen = false
                showLine2QuickRemoteScreen = true
            }
        )
    } else if (showLine2QuickRemoteScreen && selectedTab == 1) {
        Line2QuickRemoteScreen(
            currentLineName = currentLineName,
            currentLineIp = currentLineIp,
            isControllerOnline = isControllerOnline,
            lineStatus = lineStatus,
            onBack = { showLine2QuickRemoteScreen = false },
            onScaleDisconnected = { showScaleDisconnectedDialog = true },
            sendCommand = sendCommand,
            onStatusUpdate = { updated -> lineStatus = updated },
            triggerEmergencyStop = { triggerEmergencyStop() },
            onOpenAutopilot = {
                showLine2QuickRemoteScreen = false
                showLine2AutopilotScreen = true
            }
        )
    } else {
        // Main Scaffold Layout
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Gear",
                            tint = GBRBlueMain,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "التحكم بالمعدات الكيميائية",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = GBRDarkIndigo
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (viewModel.waterStepContext.value != null) viewModel.returnFromEquipmentToWaterStep()
                            else viewModel.showSegment(viewModel.previousSegmentForEquipmentControl)
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = GBRBlueMain,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { triggerEmergencyStop() },
                        enabled = isControllerOnline,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F), // Industrial Red
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFFE0E0E0),
                            disabledContentColor = Color.Gray
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .height(30.dp)
                            .testTag("appbar_emergency_stop_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dangerous,
                                contentDescription = "طوارئ",
                                tint = if (isControllerOnline) Color.White else Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "طوارئ 🚨",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                color = if (isControllerOnline) Color.White else Color.Gray
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = GBRDarkIndigo
                ),
                modifier = Modifier
                    .height(48.dp)
                    .border(0.dp, Color.Transparent)
            )
        },
        containerColor = IndustrialGrayBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            waterStepCtx?.let { w -> WaterStepBanner(w) { viewModel.returnFromEquipmentToWaterStep() } }

            // 1. Ultra-Compact Sub-Header with Production Line Tabs ONLY & Controller Live Status IP Badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Sleek, Ultra-Compact Production Line Switcher Tabs
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF3F4F6))
                        .border(1.dp, Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .wrapContentWidth()
                            .widthIn(min = 55.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 0) GBRBlueMain else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(horizontal = 8.dp)
                            .testTag("tab_line_1"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = line1Name,
                            color = if (selectedTab == 0) Color.White else Color.DarkGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .wrapContentWidth()
                            .widthIn(min = 55.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 1) GBRBlueMain else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(horizontal = 8.dp)
                            .testTag("tab_line_2"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = line2Name,
                            color = if (selectedTab == 1) Color.White else Color.DarkGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Interactive Transmission Signal Indicator Button (Opens Device Health & Stats Dialog)
                    Button(
                        onClick = {
                            fetchSystemStats()
                            showStatsDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isControllerOnline) Color(0xFFF0FDF4) else Color(0xFFFEF2F2),
                            contentColor = if (isControllerOnline) SuccessGreen else ErrorRed
                        ),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (isControllerOnline) SuccessGreen.copy(alpha = 0.4f) else ErrorRed.copy(alpha = 0.4f)),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("line_signal_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PhoneSignalBars(
                                rssi = lineStatus?.rssi ?: 0,
                                isOnline = isControllerOnline
                            )
                            Text(
                                text = if (!isControllerOnline) "غير متصل 📡" else if ((lineStatus?.rssi ?: 0) != 0) "${lineStatus?.rssi} dBm" else "ممتازة 📡",
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.5.sp,
                                color = if (isControllerOnline) GBRDarkIndigo else ErrorRed,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // High-fidelity bottom border divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(IndustrialBorder)
            )

            // 2. Active Screen Content
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshConnectionStatus() },
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                // Edit Settings Dialog (Only opens when "Edit" is clicked)
                if (showEditDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditDialog = false },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val newRate = parseUpdateRateInput(editUpdateRateStr, 1000L)
                                    if (selectedTab == 0) {
                                        line1Name = editName.ifBlank { "خط الإنتاج 1" }
                                        line1Ip = editIp.trim()
                                        line1UpdateRate = newRate
                                    } else {
                                        line2Name = editName.ifBlank { "خط الإنتاج 2" }
                                        line2Ip = editIp.trim()
                                        line2UpdateRate = newRate
                                    }
                                    saveLines(context, line1Name, line1Ip, line1UpdateRate, line2Name, line2Ip, line2UpdateRate)
                                    showEditDialog = false
                                    Toast.makeText(context, "تم حفظ البيانات وسرعة تحديث الميزان ($newRate ms) بنجاح ⚡💾", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("save_line_settings_btn")
                            ) {
                                Text("حفظ", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showEditDialog = false }
                            ) {
                                Text("إلغاء", color = Color.Gray)
                            }
                        },
                        title = {
                            Text(
                                text = "تعديل إعدادات خط الإنتاج",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Right,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = "قم بتعديل اسم خط الإنتاج وعنوان IP وسرعة تحديث القراءة للمتحكم:",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    label = { Text("اسم الخط") },
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("line_name_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = GBRBlueMain,
                                        unfocusedBorderColor = IndustrialBorder
                                    )
                                )

                                OutlinedTextField(
                                    value = editIp,
                                    onValueChange = { editIp = it },
                                    label = { Text("عنوان IP للمتحكم") },
                                    placeholder = { Text("مثال: 192.168.1.100") },
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("line_ip_input"),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = GBRBlueMain,
                                        unfocusedBorderColor = IndustrialBorder
                                    )
                                )

                                LineOpsBoxSwitches(
                                    showMotor = opBoxMotor, showHydraulic = opBoxHyd, showSpeed = opBoxSpeed,
                                    onMotor = { opBoxMotor = it; prefs.edit().putBoolean("line_${selectedTab + 1}_opbox_motor", it).apply() },
                                    onHydraulic = { opBoxHyd = it; prefs.edit().putBoolean("line_${selectedTab + 1}_opbox_hyd", it).apply() },
                                    onSpeed = { opBoxSpeed = it; prefs.edit().putBoolean("line_${selectedTab + 1}_opbox_speed", it).apply() }
                                )

                                // Device Scan Panel Card
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Search,
                                                    contentDescription = null,
                                                    tint = GBRBlueMain,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = "البحث عن الأجهزة",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = GBRDarkIndigo
                                                )
                                            }

                                            Button(
                                                onClick = { startDeviceScan() },
                                                enabled = !isScanningDevices,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = GBRBlueMain,
                                                    disabledContainerColor = Color.LightGray
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                if (isScanningDevices) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(14.dp),
                                                        color = Color.White,
                                                        strokeWidth = 2.dp
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("جاري البحث...", fontSize = 11.sp, color = Color.White)
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Wifi,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("بحث عن الأجهزة", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                }
                                            }
                                        }

                                        // Subnet Range Info Box
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White)
                                                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                                .padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "📱 عنوان الهاتف الحالي: $detectedPhoneIp",
                                                fontSize = 10.5.sp,
                                                color = Color(0xFF475569),
                                                fontWeight = FontWeight.Medium
                                            )

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = "نطاق الشبكة:",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = GBRDarkIndigo
                                                )

                                                OutlinedTextField(
                                                    value = scanSubnetPrefix,
                                                    onValueChange = { scanSubnetPrefix = it },
                                                    placeholder = { Text("مثال: 10.92.32.") },
                                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                                    singleLine = true,
                                                    enabled = !isScanningDevices,
                                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp),
                                                    colors = OutlinedTextFieldDefaults.colors(
                                                        focusedBorderColor = GBRBlueMain,
                                                        unfocusedBorderColor = Color(0xFFCBD5E1)
                                                    )
                                                )

                                                IconButton(
                                                    onClick = {
                                                        val currentIp = getLocalDeviceIpAddress(context)
                                                        detectedPhoneIp = currentIp ?: "غير معروف"
                                                        scanSubnetPrefix = extractSubnetPrefix(currentIp)
                                                        Toast.makeText(context, "تم إعادة قراءة نطاق شبكة الهاتف 🔄", Toast.LENGTH_SHORT).show()
                                                    },
                                                    enabled = !isScanningDevices,
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "تحديث النطاق",
                                                        tint = GBRBlueMain,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Progress Bar & Status Text
                                        if (isScanningDevices || scanProgress > 0) {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                LinearProgressIndicator(
                                                    progress = { scanProgress / 254f },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp)),
                                                    color = GBRBlueMain,
                                                    trackColor = Color(0xFFE2E8F0)
                                                )
                                                Text(
                                                    text = scanStatusText,
                                                    fontSize = 10.sp,
                                                    color = if (isScanningDevices) GBRBlueMain else Color.DarkGray,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }

                                        // Discovered Devices List
                                        if (discoveredGbrDevices.isNotEmpty()) {
                                            Text(
                                                text = "أجهزة GBR Paints المكتشفة (${discoveredGbrDevices.size}):",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SuccessGreen
                                            )

                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                discoveredGbrDevices.forEach { dev ->
                                                    Card(
                                                        shape = RoundedCornerShape(8.dp),
                                                        colors = CardDefaults.cardColors(containerColor = Color.White),
                                                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f)),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(8.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(
                                                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                                ) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(6.dp)
                                                                            .clip(CircleShape)
                                                                            .background(SuccessGreen)
                                                                    )
                                                                    Text(
                                                                        text = dev.ip,
                                                                        fontWeight = FontWeight.Bold,
                                                                        fontSize = 12.sp,
                                                                        color = GBRDarkIndigo
                                                                    )
                                                                }
                                                                Text(
                                                                    text = "جهاز: ${dev.deviceName}" + (if (dev.hostname.isNotBlank()) " (${dev.hostname})" else ""),
                                                                    fontSize = 10.sp,
                                                                    color = Color.Gray
                                                                )
                                                                Text(
                                                                    text = "المصنع: ${dev.manufacturer} ✓",
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = SuccessGreen
                                                                )
                                                            }

                                                            Button(
                                                                onClick = {
                                                                    editIp = dev.ip
                                                                    Toast.makeText(context, "تم اختيار العنوان ${dev.ip} 🎯", Toast.LENGTH_SHORT).show()
                                                                },
                                                                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                                                shape = RoundedCornerShape(6.dp),
                                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(30.dp)
                                                            ) {
                                                                Text("استخدام هذا الجهاز", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "سرعة تحديث قراءة الميزان (اختر خياراً سريعاً أو أدخل الرقم):",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = GBRDarkIndigo
                                    )

                                    // Quick Preset Buttons
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        val presets = listOf(
                                            Pair("200", "200ms ⚡"),
                                            Pair("300", "300ms 🚀"),
                                            Pair("500", "500ms"),
                                            Pair("1000", "1000ms ⏱️"),
                                            Pair("2000", "2000ms")
                                        )
                                        presets.forEach { (rateVal, label) ->
                                            val currentVal = parseUpdateRateInput(editUpdateRateStr, 1000L)
                                            val isSelected = currentVal == rateVal.toLong()
                                            Surface(
                                                onClick = { editUpdateRateStr = rateVal },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) GBRBlueMain else Color(0xFFF1F5F9),
                                                border = BorderStroke(1.dp, if (isSelected) GBRBlueMain else Color(0xFFCBD5E1)),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    color = if (isSelected) Color.White else GBRDarkIndigo,
                                                    modifier = Modifier.padding(vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = editUpdateRateStr,
                                        onValueChange = { editUpdateRateStr = it },
                                        label = { Text("أدخل السرعة بالميلي ثانية أو الثواني") },
                                        placeholder = { Text("مثال: 200 أو 0.5") },
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("line_update_rate_input"),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = GBRBlueMain,
                                            unfocusedBorderColor = IndustrialBorder
                                        )
                                    )

                                    val currentParsedMs = parseUpdateRateInput(editUpdateRateStr, 1000L)
                                    val freqHz = if (currentParsedMs > 0) String.format(Locale.US, "%.1f", 1000.0 / currentParsedMs) else "1.0"
                                    Text(
                                        text = "السرعة المعتمدة بعد الحفظ: $currentParsedMs ميلي ثانية ($freqHz تحديثات/ثانية) 🎯" +
                                            (if (currentParsedMs < 250L) "\n⚠️ التحديث الفعلي 250 مللي ثانية كحد أدنى: المتحكم يخدم طلباً واحداً في كل مرة، وأسرع من ذلك يبطّئ القراءة بدل أن يسرّعها" else ""),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRBlueMain
                                    )
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White
                    )
                }

                // IP Not Entered Warning (Allows layout preview while notifying users clearly)
                if (currentLineIp.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(WarningOrange.copy(alpha = 0.15f))
                            .border(1.dp, WarningOrange, RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "لم يتم تحديد عنوان IP بعد - أدخله لتفعيل الاتصال بالمتحكم والميزان.",
                                color = GBRDarkIndigo,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (!isControllerOnline && lineStatus == null && !isPollingError && connectingGrace) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        border = BorderStroke(1.dp, GBRBlueMain.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("line_connecting_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = GBRBlueMain)
                            Text(
                                text = "⏳ جارٍ الاتصال بالمتحكم…",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = GBRBlueMain
                            )
                        }
                    }
                } else if (!isControllerOnline) {
                    // Line Offline Banner Warning Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, ErrorRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("line_offline_warning_card")
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SignalWifiOff,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "⚠️ خط الإنتاج غير متصل حالياً بالمتحكم",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = ErrorRed
                                )
                                Text(
                                    text = "تم تعطيل جميع أزرار وعمليات التحكم للحماية حتى استعادة الاتصال بالمتحكم ($currentLineIp).",
                                    fontSize = 11.sp,
                                    color = Color(0xFF991B1B)
                                )
                            }
                            Button(
                                onClick = { refreshConnectionStatus() },
                                enabled = !isRefreshing,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ErrorRed,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("offline_banner_test_connection_btn")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isRefreshing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "جاري التحديث...",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "تحديث",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ==========================================
                // أ) بطاقة الوزن (العنصر الأكبر والأكثر أهمية بصرياً)
                // ==========================================
                val currentWeight = lineStatus?.weight ?: 0.0
                val scaleConnected = lineStatus?.scale_connected == true && !isPollingError
                val fillActive = lineStatus?.fill_active == true && !isPollingError
                val fillTargetVal = lineStatus?.fill_target ?: 0.0

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, IndustrialBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("weight_card")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Title (Centered) and Connection Status Badges (المتحكم والميزان) underneath
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Small Fullscreen Icon in Top-Left Corner without outer box/border
                            val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
                            val topLeftAlign = if (layoutDir == androidx.compose.ui.unit.LayoutDirection.Rtl) Alignment.TopEnd else Alignment.TopStart

                            IconButton(
                                onClick = { showFullScreenScaleMode = true },
                                modifier = Modifier
                                    .align(topLeftAlign)
                                    .size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CropFree,
                                    contentDescription = "عرض كامل الشاشة",
                                    tint = GBRDarkIndigo.copy(alpha = 0.5f),
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "ميزان خط الإنتاج",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = GBRDarkIndigo,
                                    textAlign = TextAlign.Center
                                )

                                val controllerOnline = !isPollingError && lineStatus != null && currentLineIp.isNotBlank()

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Controller Status Badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (controllerOnline) SuccessGreen.copy(alpha = 0.15f)
                                                else ErrorRed.copy(alpha = 0.15f)
                                            )
                                            .border(
                                                1.dp,
                                                if (controllerOnline) SuccessGreen.copy(alpha = 0.4f) else ErrorRed.copy(alpha = 0.4f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (controllerOnline) SuccessGreen else ErrorRed)
                                            )
                                            Text(
                                                text = if (controllerOnline) "المتحكم: متصل 🟢" else "المتحكم: غير متصل 🔴",
                                                color = if (controllerOnline) SuccessGreen else ErrorRed,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Scale Status Badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (scaleConnected) SuccessGreen.copy(alpha = 0.15f)
                                                else ErrorRed.copy(alpha = 0.15f)
                                            )
                                            .border(
                                                1.dp,
                                                if (scaleConnected) SuccessGreen.copy(alpha = 0.4f) else ErrorRed.copy(alpha = 0.4f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(if (scaleConnected) SuccessGreen else ErrorRed)
                                            )
                                            Text(
                                                text = if (scaleConnected) "الميزان: متصل 🟢" else "الميزان: غير متصل 🔴",
                                                color = if (scaleConnected) SuccessGreen else ErrorRed,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (scaleConnected && pollLatencyMs > 0L) {
                            Text(
                                text = "⏱ ${pollLatencyMs}ms" + (if (pollGapMsEma > 0L) " · ${String.format(Locale.US, "%.1f", 1000.0 / pollGapMsEma)} قراءة/ث" else ""),
                                fontSize = 10.sp,
                                color = Color.Gray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().testTag("poll_rate_indicator")
                            )
                        }
                        // Big Weight Display (أكبر عنصر نصي في الشاشة)
                        val pkgWeightVal = packageUnitWeightInput.toDoubleOrNull() ?: 24.35
                        val validPkgWeight = if (pkgWeightVal > 0.0) pkgWeightVal else 24.35
                        // وزن الخزان/الخلاط الفارغ يُطرح من قراءة الميزان قبل حساب عدد العبوات
                        val tankTareVal = (tankTareInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                        val netMixerWeight = (currentWeight - tankTareVal).coerceAtLeast(0.0)
                        val containerCount = if (validPkgWeight > 0.0) kotlin.math.floor(netMixerWeight / validPkgWeight).toInt() else 0

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    if (scaleConnected) {
                                        tempWeightDisplayMode = weightDisplayMode
                                        tempPackageUnitWeightInput = packageUnitWeightInput
                                        tempTankTareInput = tankTareInput
                                        showWeightModeDialog = true
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "⛔ يتعذر تغيير نمط العرض لأن الميزان/المتحكم غير متصل حالياً",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                if (weightDisplayMode == "CONTAINERS") {
                                    Text(
                                        text = "$containerCount",
                                        fontSize = 58.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (scaleConnected) GBRBlueMain else Color.Gray,
                                        modifier = Modifier.testTag("weight_value")
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "عبوة",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                } else {
                                    // عرض ناعم: يتحرك الرقم نحو آخر قراءة (تحسين بصري فقط، القيمة الحقيقية تبقى هي المستخدمة في القرارات)
                                    val shownWeight by animateFloatAsState(
                                        targetValue = currentWeight.toFloat(),
                                        animationSpec = tween(durationMillis = 220, easing = LinearEasing),
                                        label = "weight_smooth"
                                    )
                                    Text(
                                        text = String.format(Locale.US, "%.1f", shownWeight.toDouble()),
                                        fontSize = 58.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (scaleConnected) GBRBlueMain else Color.Gray,
                                        modifier = Modifier.testTag("weight_value")
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "كجم",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                }
                            }

                            // Interactive badge below big text indicating mode & allowing tap to change when connected
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (scaleConnected) Color(0xFFF1F5F9) else Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, if (scaleConnected) Color(0xFFCBD5E1) else Color(0xFFE2E8F0)),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (weightDisplayMode == "CONTAINERS") Icons.Default.Inventory2 else Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = if (scaleConnected) GBRBlueMain else Color.Gray,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = if (!scaleConnected) {
                                            if (weightDisplayMode == "CONTAINERS")
                                                "نمط العرض: عدد العبوات (الميزان غير متصل 🔴)"
                                            else
                                                "نمط العرض: الوزن الفعلي (الميزان غير متصل 🔴)"
                                        } else {
                                            if (weightDisplayMode == "CONTAINERS")
                                                "نمط العرض: عدد العبوات ($validPkgWeight كجم/عبوة - الوزن الفعلي: ${String.format(Locale.US, "%.1f", currentWeight)} كجم" + (if (tankTareVal > 0.0) " - الخزان: ${String.format(Locale.US, "%.1f", tankTareVal)} = الصافي ${String.format(Locale.US, "%.1f", netMixerWeight)} كجم" else "") + ") ⚙️"
                                            else
                                                "نمط العرض: الوزن الفعلي (كجم) ⚙️ (اضغط للتغيير)"
                                        },
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (scaleConnected) GBRDarkIndigo else Color.Gray
                                    )
                                }
                            }
                        }

                        // Target weight progress calculation (Dynamic target based on live fill_target or manual targetInput)
                        // الهدف = الوزن النهائي المطلوب على الميزان (وليس كمية تُضاف فوق القراءة الحالية)
                        val liveFillTarget = lineStatus?.fill_target ?: 0.0
                        val targetScaleCutoff = if (fillActive && liveFillTarget > 0.0) liveFillTarget else (targetInput.toDoubleOrNull() ?: 0.0)
                        val remainingToTarget = maxOf(0.0, targetScaleCutoff - currentWeight)

                        // التقدم = قراءة الميزان الحالية ÷ الهدف (500 من 600 ≈ 83%)
                        val progressPct = if (targetScaleCutoff > 0.0) {
                            ((currentWeight / targetScaleCutoff) * 100.0).coerceIn(0.0, 100.0)
                        } else {
                            0.0
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(IndustrialGrayBg)
                                .border(1.dp, IndustrialBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Adjust,
                                        contentDescription = null,
                                        tint = GBRBlueMain,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "التقدم نحو الوزن المستهدف على الميزان",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRDarkIndigo
                                    )
                                }
                                Text(
                                    text = "${String.format(Locale.US, "%.1f", progressPct)}%",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (progressPct >= 100.0) SuccessGreen else GBRBlueMain
                                )
                            }

                            // Linear Progress Indicator
                            LinearProgressIndicator(
                                progress = (progressPct / 100.0).toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                color = if (progressPct >= 100.0) SuccessGreen else GBRBlueMain,
                                trackColor = Color(0xFFE0E0E0)
                            )

                            if (fillActive || targetScaleCutoff > 0) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "الميزان الآن: ${String.format(Locale.US, "%.1f", currentWeight)} كجم",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GBRDarkIndigo
                                        )
                                        Text(
                                            text = "الوزن المستهدف: ${String.format(Locale.US, "%.1f", targetScaleCutoff)} كجم",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.Gray
                                        )
                                    }

                                    val reachedTarget = remainingToTarget <= 0.05
                                    Text(
                                        text = when {
                                            reachedTarget -> "بلغ الميزان الوزن المستهدف 🎉"
                                            fillActive -> "المتبقي للضخ: ${String.format(Locale.US, "%.1f", remainingToTarget)} كجم"
                                            else -> "سيتم ضخ ${String.format(Locale.US, "%.1f", remainingToTarget)} كجم عند البدء"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.fillMaxWidth(),
                                        color = if (reachedTarget) SuccessGreen else if (fillActive) WarningOrange else GBRBlueMain
                                    )

                                    val fillStatusLabel = when {
                                        fillActive -> "جاري التعبئة ⏳"
                                        fillFailureWarningState != null -> "توقفت التعبئة قبل بلوغ الهدف ⚠️"
                                        progressPct >= 95.0 -> "اكتملت التعبئة ✅"
                                        else -> "جاهز للبدء ⏹️"
                                    }
                                    val fillStatusColor = when {
                                        fillActive -> GBRBlueMain
                                        fillFailureWarningState != null -> ErrorRed
                                        progressPct >= 95.0 -> SuccessGreen
                                        else -> Color.Gray
                                    }
                                    Text(
                                        text = fillStatusLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = fillStatusColor,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                Text(
                                    text = "💡 أدخل الوزن النهائي المطلوب على الميزان أدناه لرؤية مؤشر التقدم.",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Warning card if fill stopped prematurely or failed
                        if (fillFailureWarningState != null && !fillActive) {
                            val failedPair = fillFailureWarningState!!
                            Card(
                                colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.08f)),
                                border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = ErrorRed,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "تنبيه: توقفت التعبئة قبل اكتمال الهدف!",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ErrorRed
                                        )
                                        Text(
                                            text = "الوزن المحقق: ${String.format(Locale.US, "%.1f", failedPair.first)} كجم من أصل ${String.format(Locale.US, "%.1f", failedPair.second)} كجم. تحقق من مصدر المياه أو استجابة الصمام.",
                                            fontSize = 11.sp,
                                            color = Color.DarkGray
                                        )
                                    }
                                    IconButton(
                                        onClick = { fillFailureWarningState = null },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "إغلاق التنبيه",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Controller Hardware Alerts for current line (e.g. WATER_SUPPLY_FAILURE)
                        val lineAlerts = controllerAlerts.filter { it.lineIndex == selectedTab }
                        if (lineAlerts.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                lineAlerts.forEach { alert ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.10f)),
                                        border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.6f)),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                tint = WarningOrange,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = "تنبيه من المتحكم #${alert.id}",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = WarningOrange
                                                    )
                                                    Text(
                                                        text = alert.time,
                                                        fontSize = 10.sp,
                                                        color = Color.Gray
                                                    )
                                                }
                                                Text(
                                                    text = alert.message,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color.DarkGray
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.acknowledgeControllerAlert(alert)
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "تأكيد واستلام التنبيه",
                                                    tint = WarningOrange,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // زر واحد: تصفير المؤشر الفعلي (hardware zero). التصفير النسبي (Tare) حُذف نهائياً
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (currentLineIp.isBlank()) {
                                        Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                                        return@OutlinedButton
                                    }
                                    showHardwareZeroConfirmDialog = true
                                },
                                enabled = isControllerOnline,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = GBRBlueMain,
                                    disabledContentColor = Color.Gray
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("scale_zero_btn")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "تصفير المؤشر",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Divider(color = IndustrialBorder)

                        // 3. قسم "تعبئة تلقائية بوزن مستهدف" (Auto-fill section inside weight card)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🎯 نظام تعبئة وضخ الماء الذكي (Auto-Fill System)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = GBRDarkIndigo
                            )

                            // Target weight input occupies its own full-width line
                            OutlinedTextField(
                                value = targetInput,
                                onValueChange = { targetInput = it },
                                enabled = isControllerOnline,
                                label = { Text("الوزن النهائي المطلوب على الميزان (كجم)") },
                                placeholder = { Text("مثال: لو الميزان 500 وتريد 600 اكتب 600") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = GBRBlueMain,
                                    unfocusedBorderColor = IndustrialBorder
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("fill_target_input")
                            )

                            // Live Calculation Box for complete transparency and operator confidence
                            val inputAmount = targetInput.toDoubleOrNull() ?: 0.0
                            if (inputAmount > 0.0) {
                                val currentScaleW = lineStatus?.weight ?: 0.0
                                val netWaterToPump = maxOf(0.0, inputAmount - currentScaleW)
                                val calculatedFinalCutoff = inputAmount

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFF0FDF4),
                                    border = BorderStroke(1.dp, Color(0xFF86EFAC)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "💧 الكمية التي سيتم ضخها (الباقي للهدف):",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF166534)
                                            )
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f", netWaterToPump)} كجم",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF166534)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "⚖️ قراءة الميزان الآن:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF475569)
                                            )
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f", currentScaleW)} كجم",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF475569)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "🎯 الوزن المستهدف على الميزان:",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GBRDarkIndigo
                                            )
                                            Text(
                                                text = "${String.format(Locale.US, "%.1f", calculatedFinalCutoff)} كجم",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Black,
                                                color = GBRBlueMain
                                            )
                                        }
                                    }
                                }
                            }

                            val waterValveOn = lineStatus?.relays?.find { it.id == 1 }?.state ?: false

                            // Control buttons row placed cleanly below the input box
                            if (!fillActive) {
                                // When filling is inactive, show the Start Auto-Fill button (Full Width)
                                Button(
                                    onClick = {
                                        if (currentLineIp.isBlank()) {
                                            Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        val targetNum = targetInput.toDoubleOrNull()
                                        if (targetNum == null || targetNum <= 0) {
                                            Toast.makeText(context, "الرجاء إدخال وزن مستهدف صحيح أكبر من الصفر", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }

                                        val currentScale = lineStatus?.weight ?: 0.0
                                        val netNeeded = maxOf(0.0, targetNum - currentScale)
                                        if (netNeeded <= 0) {
                                            Toast.makeText(context, "الميزان يقرأ ($currentScale كجم) وقد بلغ أو تجاوز الوزن المستهدف ($targetNum كجم).", Toast.LENGTH_LONG).show()
                                            return@Button
                                        }
                                        val finalCutoffTarget = targetNum

                                        fillStartWeight = currentScale
                                        fillRequestedWaterAmount = netNeeded
                                        fillFailureWarningState = null

                                        val formattedCutoff = String.format(Locale.US, "%.1f", finalCutoffTarget)
                                        sendCommand("http://$currentLineIp/fill/start?target=$formattedCutoff", { updated ->
                                            if (updated != null) {
                                                lineStatus = updated
                                            }
                                            Toast.makeText(context, "تم بدء التعبئة 🚀 سيتم ضخ ${String.format(Locale.US, "%.1f", netNeeded)} كجم للوصول إلى $formattedCutoff كجم على الميزان", Toast.LENGTH_SHORT).show()
                                        }, { err ->
                                            val msg = err.message ?: ""
                                            if (msg.contains("الميزان غير متصل") || msg.contains("409") || msg.contains("scale", ignoreCase = true)) {
                                                showScaleDisconnectedDialog = true
                                            } else {
                                                Toast.makeText(context, "فشل تشغيل التعبئة: $msg", Toast.LENGTH_LONG).show()
                                            }
                                        })
                                    },
                                    enabled = isControllerOnline,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen,
                                        disabledContainerColor = Color(0xFFE0E0E0),
                                        disabledContentColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("fill_start_btn")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (isControllerOnline) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("بدء ضخ الماء التلقائي", fontWeight = FontWeight.Bold, color = if (isControllerOnline) Color.White else Color.Gray, fontSize = 13.sp)
                                }
                            } else {
                                // Active Fill Action Controls (Pause / Resume and Cancel side-by-side below input box)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Pause / Resume Button
                                    val isPause = waterValveOn
                                    Button(
                                        onClick = {
                                            if (currentLineIp.isBlank()) {
                                                Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            val nextState = if (isPause) "off" else "on"
                                            sendCommand("http://$currentLineIp/control?relay=1&state=$nextState", { updated ->
                                                if (updated != null) {
                                                    lineStatus = updated
                                                }
                                                val msg = if (isPause) "تم الإيقاف المؤقت لعملية التعبئة ⏸️" else "تم استئناف عملية التعبئة ▶️"
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }, { err ->
                                                val msgErr = err.message ?: ""
                                                if (msgErr.contains("الميزان غير متصل") || msgErr.contains("409") || msgErr.contains("scale", ignoreCase = true)) {
                                                    showScaleDisconnectedDialog = true
                                                } else {
                                                    Toast.makeText(context, "فشل التحكم: $msgErr", Toast.LENGTH_LONG).show()
                                                }
                                            })
                                        },
                                        enabled = isControllerOnline,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isPause) WarningOrange else SuccessGreen,
                                            disabledContainerColor = Color(0xFFE0E0E0),
                                            disabledContentColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("fill_pause_resume_btn")
                                    ) {
                                        Icon(
                                            imageVector = if (isPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = if (isControllerOnline) Color.White else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isPause) "إيقاف مؤقت" else "استئناف",
                                            fontWeight = FontWeight.Bold,
                                            color = if (isControllerOnline) Color.White else Color.Gray,
                                            fontSize = 13.sp
                                        )
                                    }

                                    // Cancel Command Button
                                    Button(
                                        onClick = {
                                            if (currentLineIp.isBlank()) {
                                                Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            sendCommand("http://$currentLineIp/fill/stop", { updated ->
                                                if (updated != null) {
                                                    lineStatus = updated
                                                }
                                                Toast.makeText(context, "تم إلغاء عملية التعبئة وإغلاق الصمام نهائياً 🛑", Toast.LENGTH_SHORT).show()
                                            }, { err ->
                                                Toast.makeText(context, "فشل إلغاء التعبئة: ${err.message}", Toast.LENGTH_SHORT).show()
                                            })
                                        },
                                        enabled = isControllerOnline,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ErrorRed,
                                            disabledContainerColor = Color(0xFFE0E0E0),
                                            disabledContentColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("fill_stop_btn")
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null, tint = if (isControllerOnline) Color.White else Color.Gray, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("إلغاء الأمر", fontWeight = FontWeight.Bold, color = if (isControllerOnline) Color.White else Color.Gray, fontSize = 13.sp)
                                    }
                                }
                            }

                            // Live auto-fill progress displayed directly below target input during filling
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (fillActive) {
                                            if (waterValveOn) SuccessGreen.copy(alpha = 0.08f)
                                            else WarningOrange.copy(alpha = 0.08f)
                                        } else Color.LightGray.copy(alpha = 0.15f)
                                    )
                                    .border(
                                        1.dp,
                                        if (fillActive) {
                                            if (waterValveOn) SuccessGreen.copy(alpha = 0.3f)
                                            else WarningOrange.copy(alpha = 0.3f)
                                        } else Color.LightGray.copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (fillActive) {
                                            if (waterValveOn) "⏳ حالة التعبئة: نشطة وجاري الضخ" else "⏸️ حالة التعبئة: موقوفة مؤقتاً"
                                        } else "⏹️ حالة التعبئة: متوقفة حالياً",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = if (fillActive) {
                                            if (waterValveOn) SuccessGreen else WarningOrange
                                        } else Color.Gray
                                    )

                                    if (fillActive) {
                                        Text(
                                            text = "التقدم: ${String.format(Locale.US, "%.1f", currentWeight)} / ${String.format(Locale.US, "%.1f", fillTargetVal)} كجم",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp,
                                            color = if (waterValveOn) SuccessGreen else WarningOrange
                                        )
                                    } else {
                                        Text(
                                            text = "لا توجد تعبئة نشطة",
                                            fontSize = 11.sp,
                                            color = Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // =========================================================================
                // PRODUCTION LINE 2 (ESP32-S3): DEDICATED SPECIALIZED MODULES
                // =========================================================================
                if (selectedTab == 1) {
                    // Quick Navigation Buttons for Production Line 2 (التحكم السريع والبرمجة الآلية متجاورتين)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Line2QuickRemoteLaunchBanner(
                            onOpenQuickRemote = { showLine2QuickRemoteScreen = true },
                            modifier = Modifier.weight(1f)
                        )
                        Line2AutopilotLaunchBanner(
                            onOpenAutopilot = { showLine2AutopilotScreen = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. Motor Control System (Relays 2 & 3, Timed Run, Countdown & Safety Interlocks)
                    Line2MotorCard(
                        currentLineIp = currentLineIp,
                        isControllerOnline = isControllerOnline,
                        lineStatus = lineStatus,
                        sendCommand = sendCommand,
                        onStatusUpdate = { updated ->
                            lineStatus = updated
                        }
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Hydraulic Lift / Lower System (Relay 4 & 5 - Momentary Hold)
                    Line2HydraulicCard(
                        currentLineIp = currentLineIp,
                        isControllerOnline = isControllerOnline,
                        lineStatus = lineStatus,
                        sendCommand = sendCommand,
                        onStatusUpdate = { updated ->
                            lineStatus = updated
                        }
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. Speed Control System (Relays 7 & 8 - Momentary Hold, Interlocked with motor_running)
                    Line2SpeedControlCard(
                        currentLineIp = currentLineIp,
                        isControllerOnline = isControllerOnline,
                        lineStatus = lineStatus,
                        sendCommand = sendCommand,
                        onStatusUpdate = { updated ->
                            lineStatus = updated
                        }
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. Dedicated Water Valve Control (Relay 1)
                    Line2WaterValveCard(
                        currentLineIp = currentLineIp,
                        isControllerOnline = isControllerOnline,
                        lineStatus = lineStatus,
                        onScaleDisconnected = { showScaleDisconnectedDialog = true },
                        sendCommand = sendCommand,
                        onStatusUpdate = { updated ->
                            lineStatus = updated
                        }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                } else {
                    // =====================================================================
                    // PRODUCTION LINE 1: UNTOUCHED CLASSIC 4-RELAYS INDUSTRIAL REMOTE PANEL
                    // =====================================================================
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🎛️ لوحة مفاتيح التحكم اللاسلكي الصناعي (الريليهات)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = GBRDarkIndigo
                        )

                        val relay1State = lineStatus?.relays?.find { it.id == 1 }?.state ?: false
                        val relay2State = lineStatus?.relays?.find { it.id == 2 }?.state ?: false

                        val relayList = listOf(
                            Triple(1, "صمام المياه\n(Water Valve)", relay1State),
                            Triple(2, "الخلاط الكيميائي\n(Mixer Motor)", relay2State)
                        )

                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFECEFF1)), // Heavy plastic grey shell
                            border = BorderStroke(2.dp, Color(0xFFB0BEC5)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "🎮 جهاز التحكم الصناعي اللاسلكي",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFF455A64),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Render buttons in rows of exactly 2
                                relayList.chunked(2).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        rowItems.forEach { (id, label, state) ->
                                            Box(
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                // Industrial Remote Push Button Module
                                                Surface(
                                                    onClick = {
                                                        if (currentLineIp.isBlank()) {
                                                            Toast.makeText(context, "الرجاء تحديد عنوان IP أولاً", Toast.LENGTH_SHORT).show()
                                                            return@Surface
                                                        }
                                                        val targetState = !state
                                                        val stateStr = if (targetState) "on" else "off"
                                                        val url = resolveUrl(currentLineIp, "/control?relay=$id&state=$stateStr")
                                                        sendCommand(
                                                            url,
                                                            { updatedStatus ->
                                                                if (updatedStatus != null) {
                                                                    lineStatus = updatedStatus
                                                                }
                                                                val action = if (targetState) "تشغيل" else "إيقاف"
                                                                Toast.makeText(context, "تم $action $label", Toast.LENGTH_SHORT).show()
                                                            },
                                                            { err ->
                                                                val msg = err.message ?: ""
                                                                if (msg.contains("الميزان غير متصل") || msg.contains("409") || msg.contains("scale", ignoreCase = true)) {
                                                                    showScaleDisconnectedDialog = true
                                                                } else {
                                                                    Toast.makeText(context, "فشل في التحكم: $msg", Toast.LENGTH_LONG).show()
                                                                }
                                                            }
                                                        )
                                                    },
                                                    enabled = isControllerOnline,
                                                    shape = RoundedCornerShape(16.dp),
                                                    color = Color.White,
                                                    border = BorderStroke(
                                                        width = if (state) 2.dp else 1.dp,
                                                        color = if (state) Color(0xFF4CAF50) else Color(0xFFCFD8DC)
                                                    ),
                                                    shadowElevation = if (state) 1.dp else 4.dp,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("relay_button_$id")
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 12.dp, horizontal = 8.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        // Circular Tactile Push-Button Cap
                                                        Box(
                                                            modifier = Modifier
                                                                .size(52.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                    Brush.radialGradient(
                                                                        colors = if (!isControllerOnline) listOf(Color(0xFFB0BEC5), Color(0xFF78909C))
                                                                        else if (state) listOf(Color(0xFF81C784), Color(0xFF2E7D32))
                                                                        else listOf(Color(0xFFEF5350), Color(0xFFC62828))
                                                                    )
                                                                )
                                                                .border(
                                                                    width = 3.dp,
                                                                    color = Color(0xFF37474F),
                                                                    shape = CircleShape
                                                                ),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            // Inner Bezel
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(42.dp)
                                                                    .clip(CircleShape)
                                                                    .background(
                                                                        if (!isControllerOnline) Color(0xFF90A4AE)
                                                                        else if (state) Color(0xFF43A047)
                                                                        else Color(0xFFE57373)
                                                                    )
                                                                    .border(
                                                                        width = 2.dp,
                                                                        color = Color.White.copy(alpha = 0.5f),
                                                                        shape = CircleShape
                                                                    )
                                                            ) {
                                                                Icon(
                                                                    imageVector = when (id) {
                                                                        1 -> Icons.Default.WaterDrop
                                                                        2 -> Icons.Default.Refresh
                                                                        3 -> Icons.Default.KeyboardArrowUp
                                                                        4 -> Icons.Default.KeyboardArrowDown
                                                                        else -> Icons.Default.Build
                                                                    },
                                                                    contentDescription = null,
                                                                    tint = if (isControllerOnline) Color.White else Color.Gray,
                                                                    modifier = Modifier
                                                                        .size(22.dp)
                                                                        .align(Alignment.Center)
                                                                )
                                                            }
                                                        }

                                                        // Label under button
                                                        Text(
                                                            text = label,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 11.sp,
                                                            color = if (isControllerOnline) Color(0xFF37474F) else Color.Gray,
                                                            textAlign = TextAlign.Center,
                                                            lineHeight = 14.sp,
                                                            modifier = Modifier.heightIn(min = 28.dp)
                                                        )

                                                        // Status Pill Indicator
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .background(
                                                                    if (!isControllerOnline) Color(0xFFECEFF1)
                                                                    else if (state) Color(0xFFE8F5E9)
                                                                    else Color(0xFFFFEBEE)
                                                                )
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        ) {
                                                            Text(
                                                                text = if (!isControllerOnline) "غير متصل 🔴" else if (state) "نشط (ON)" else "متوقف (OFF)",
                                                                color = if (!isControllerOnline) Color.Gray else if (state) Color(0xFF2E7D32) else Color(0xFFC62828),
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } // End of selectedTab check (Line 2 specialized cards vs Line 1 classic remote)

                Spacer(modifier = Modifier.height(14.dp))

                // =========================================================================
                // EXECUTION LOGS MODULE (سجل التنفيذ للمعدات)
                // =========================================================================
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, IndustrialBorder),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.List,
                                    contentDescription = null,
                                    tint = GBRBlueMain,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "سجل التنفيذ 📋",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = GBRDarkIndigo
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Button(
                                    onClick = {
                                        fetchFaultLogs()
                                        showFaultsDialog = true
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ErrorRed.copy(alpha = 0.08f),
                                        contentColor = ErrorRed
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(24.dp),
                                    border = BorderStroke(0.5.dp, ErrorRed.copy(alpha = 0.25f))
                                ) {
                                    Text("سجل الأعطال ⚠️", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Filter button (فلتر الفئات من السجل)
                                run {
                                    val activeFilterCount = logCategoryFilter.intersect(executionLogsList.map { it.category }.toSet()).size
                                    BadgedBox(badge = { if (activeFilterCount > 0) Badge { Text("$activeFilterCount") } }) {
                                        IconButton(
                                            onClick = { showLogFilterDialog = true },
                                            enabled = executionLogsList.isNotEmpty(),
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FilterList,
                                                contentDescription = "فلترة السجل",
                                                tint = if (executionLogsList.isEmpty()) Color.LightGray else if (activeFilterCount > 0) WarningOrange else GBRBlueMain,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                                // Refresh Button
                                IconButton(
                                    onClick = { fetchExecutionLogs(isSilent = false) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    if (isLoadingExecutionLogs && !isPollingError) {
                                        CircularProgressIndicator(
                                            color = GBRBlueMain,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "تحديث",
                                            tint = GBRBlueMain,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                if (executionLogsList.isNotEmpty()) {
                                    Button(
                                        onClick = { showClearLogsConfirmDialog = true },
                                        enabled = !isPollingError && lineStatus != null,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ErrorRed.copy(alpha = 0.1f), contentColor = ErrorRed,
                                            disabledContainerColor = Color.LightGray.copy(alpha = 0.25f), disabledContentColor = Color.Gray
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.size(28.dp),
                                        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.3f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        
                                    }
                                }
                            }
                        }

                        if ((isShowingOfflineLogs || isPollingError) && executionLogsList.isNotEmpty()) {
                            Text(
                                text = "غير متصل 💾 — يُعرض آخر سجل محفوظ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarningOrange,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isLoadingExecutionLogs && executionLogsList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = GBRBlueMain, modifier = Modifier.size(24.dp))
                            }
                        } else if (executionLogsList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("لا توجد سجلات تنفيذ حالية 📭", fontSize = 12.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("انقر على أيقونة التحديث أعلاه لجلب البيانات من وحدة التحكم", fontSize = 10.sp, color = Color.Gray.copy(alpha = 0.7f))
                                }
                            }
                        } else {
                            run {
                                val eff = logCategoryFilter.intersect(executionLogsList.map { it.category }.toSet())
                                if (eff.isNotEmpty()) {
                                    val shown = executionLogsList.count { it.category in eff }
                                    Text(
                                        text = "🔽 مفلتر: ${eff.joinToString("، ")} — $shown من ${executionLogsList.size}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = WarningOrange,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                            }
                            // Table Header
                            Surface(
                                color = Color(0xFFF1F5F9),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp, horizontal = 10.dp)
                                ) {
                                    Text("الوقت", modifier = Modifier.weight(0.25f), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GBRDarkIndigo, textAlign = TextAlign.Start)
                                    Text("الفئة", modifier = Modifier.weight(0.22f), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GBRDarkIndigo, textAlign = TextAlign.Center)
                                    Text("الحدث", modifier = Modifier.weight(0.28f), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GBRDarkIndigo, textAlign = TextAlign.Center)
                                    Text("التفاصيل", modifier = Modifier.weight(0.25f), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = GBRDarkIndigo, textAlign = TextAlign.End)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Table Rows
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp)
                                    .verticalScroll(executionLogsScrollState),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val presentCategories = executionLogsList.map { it.category }.toSet()
                                val effectiveFilter = logCategoryFilter.intersect(presentCategories)
                                val sortedLogs = (if (effectiveFilter.isEmpty()) executionLogsList else executionLogsList.filter { it.category in effectiveFilter }).reversed()
                                sortedLogs.forEachIndexed { index, entry ->
                                    val timeParts = entry.time.split(" ")
                                    val currentDate = if (timeParts.isNotEmpty()) timeParts[0] else ""
                                    val previousDate = if (index > 0) {
                                        val prevParts = sortedLogs[index - 1].time.split(" ")
                                        if (prevParts.isNotEmpty()) prevParts[0] else ""
                                    } else ""

                                    if (currentDate.isNotBlank() && currentDate != "-" && currentDate != previousDate) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                         ) {
                                             Box(
                                                 modifier = Modifier
                                                     .weight(1f)
                                                     .height(1.5.dp)
                                                     .background(Color(0xFFCBD5E1))
                                             )
                                             Box(
                                                 modifier = Modifier
                                                     .padding(horizontal = 10.dp)
                                                     .background(Color(0xFFEFF6FF), RoundedCornerShape(12.dp))
                                                     .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(12.dp))
                                                     .padding(horizontal = 10.dp, vertical = 3.dp)
                                             ) {
                                                 Text(
                                                     text = "📅 التاريخ: $currentDate",
                                                     fontSize = 10.sp,
                                                     fontWeight = FontWeight.Bold,
                                                     color = GBRDarkIndigo
                                                 )
                                             }
                                             Box(
                                                 modifier = Modifier
                                                     .weight(1f)
                                                     .height(1.5.dp)
                                                     .background(Color(0xFFCBD5E1))
                                             )
                                         }
                                     }

                                     Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (index % 2 == 0) Color.White else Color(0xFFF8FAFC))
                                            .combinedClickable(
                                                onClick = {},
                                                onLongClick = { logWeightKgOf(entry)?.let { pendingMixerTareKg = it } }
                                            )
                                            .padding(vertical = 8.dp, horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val timeParts = entry.time.split(" ")
                                        Column(
                                            modifier = Modifier.weight(0.25f),
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            if (timeParts.size > 1) {
                                                Text(
                                                    text = timeParts[0],
                                                    fontSize = 9.sp,
                                                    color = Color.Gray,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = timeParts[1],
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color.DarkGray,
                                                    maxLines = 1
                                                )
                                            } else {
                                                Text(
                                                    text = entry.time,
                                                    fontSize = 11.sp,
                                                    color = Color.DarkGray,
                                                    maxLines = 2
                                                )
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(0.22f)
                                                .wrapContentWidth(Alignment.CenterHorizontally)
                                        ) {
                                            val badgeColor = when {
                                                entry.category.contains("صمام") -> Color(0xFFE0F2FE) to Color(0xFF0369A1)
                                                entry.category.contains("محرك") -> Color(0xFFF3E8FF) to Color(0xFF6B21A8)
                                                entry.category.contains("ميزان") -> Color(0xFFFEF3C7) to Color(0xFFB45309)
                                                entry.category.contains("هيدروليك") -> Color(0xFFF1F5F9) to Color(0xFF334155)
                                                entry.category.contains("أمان") || entry.category.contains("تحذير") -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
                                                else -> Color(0xFFE2E8F0) to Color(0xFF475569)
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(badgeColor.first)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = entry.category,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeColor.second
                                                )
                                            }
                                        }

                                        Text(
                                            text = entry.event,
                                            modifier = Modifier.weight(0.28f),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = GBRDarkIndigo,
                                            textAlign = TextAlign.Center,
                                            maxLines = 2
                                        )

                                        Text(
                                            text = entry.details,
                                            modifier = Modifier.weight(0.25f),
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.End,
                                            maxLines = 2
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                pendingMixerTareKg?.let { kg ->
                    val kgText = String.format(Locale.US, "%.2f", kg).trimEnd('0').trimEnd('.')
                    AlertDialog(
                        onDismissRequest = { pendingMixerTareKg = null },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = Color.White,
                        title = { Text("⚖️ اعتماد وزن الخلاط الفارغ", fontWeight = FontWeight.Bold, color = GBRDarkIndigo, fontSize = 16.sp) },
                        text = {
                            Text(
                                "هل تريد اعتماد $kgText كجم كوزن الخلاط الفارغ؟\nسيُحفظ في خانة «وزن الخلاط» لـ ${if (selectedTab == 0) line1Name else line2Name} ويُطرح من قراءة الميزان عند حساب عدد العبوات.",
                                fontSize = 13.sp, color = Color.DarkGray
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val v = kgText
                                    if (selectedTab == 0) line1TankTare = v else line2TankTare = v
                                    context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE).edit()
                                        .putString(if (selectedTab == 0) "line_1_tank_tare" else "line_2_tank_tare", v).apply()
                                    pendingMixerTareKg = null
                                    Toast.makeText(context, "تم اعتماد $v كجم كوزن الخلاط الفارغ ✅", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                shape = RoundedCornerShape(10.dp)
                            ) { Text("نعم، اعتمد", color = Color.White) }
                        },
                        dismissButton = { TextButton(onClick = { pendingMixerTareKg = null }) { Text("إلغاء") } }
                    )
                }
                // Dialog popup for clearing logs confirmation
                if (showLogFilterDialog) {
        val catCounts = executionLogsList.groupingBy { it.category }.eachCount()
        AlertDialog(
            onDismissRequest = { showLogFilterDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = { Text("🔽 فلترة سجل التنفيذ", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GBRDarkIndigo) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "اختر الفئات التي تريد عرضها فقط (الفئات مأخوذة من السجلات الواردة من المتحكم). بدون اختيار = عرض الكل.",
                        fontSize = 11.sp, color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (catCounts.isEmpty()) {
                        Text("لا توجد سجلات لاستخراج الفئات منها.", fontSize = 12.sp, color = Color.Gray)
                    }
                    catCounts.forEach { (cat, cnt) ->
                        val checked = cat in logCategoryFilter
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { logCategoryFilter = if (checked) logCategoryFilter - cat else logCategoryFilter + cat }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { on -> logCategoryFilter = if (on) logCategoryFilter + cat else logCategoryFilter - cat })
                            Text(cat.ifBlank { "(بدون فئة)" }, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = GBRDarkIndigo, modifier = Modifier.weight(1f))
                            Text("$cnt", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showLogFilterDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain)) {
                    Text("تم", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { logCategoryFilter = emptySet() }) {
                    Text("إعادة ضبط (عرض الكل)", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showClearLogsConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearLogsConfirmDialog = false },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "تأكيد مسح سجل التنفيذ ⚠️",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        text = {
                            Text(
                                text = "هل أنت متأكد من مسح سجل التنفيذ من المتحكم نفسه بشكل نهائي؟ لا يمكن التراجع عن هذا الإجراء.",
                                fontSize = 13.sp,
                                color = Color.DarkGray,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearLogsConfirmDialog = false
                                    clearExecutionLogs()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("تأكيد المسح", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showClearLogsConfirmDialog = false },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("إلغاء", fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White
                    )
                }

                // Dialog popup for clearing fault logs confirmation
                if (showClearFaultLogsConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearFaultLogsConfirmDialog = false },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = ErrorRed,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "تأكيد مسح سجل الأعطال 🧹",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        text = {
                            Text(
                                text = "هل أنت متأكد من مسح كافة سجلات الأعطال المخزنة في المتحكم بشكل نهائي؟\n\nمسار المتحكم: /log/faults/clear",
                                fontSize = 13.sp,
                                color = Color.DarkGray,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showClearFaultLogsConfirmDialog = false
                                    clearFaultLogs()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("تأكيد المسح", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showClearFaultLogsConfirmDialog = false },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("إلغاء", fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White
                    )
                }

                // Dialog popup for Hardware Scale Zero confirmation
                if (showHardwareZeroConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showHardwareZeroConfirmDialog = false },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = WarningOrange,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "تأكيد تصفير الميزان ⚠️",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        text = {
                            Text(
                                text = "هل أنت ألكيد من إجراء التصفير الفعلي للميزان؟ قد تؤدي هذه العملية إلى تصفير المؤشر وفقدان الوزن الحالي.",
                                fontSize = 13.sp,
                                color = Color.DarkGray,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showHardwareZeroConfirmDialog = false
                                    handleScaleAction(resolveUrl(currentLineIp, "/scale/hardware-zero"), "تصفير الميزان الحقيقي")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("تأكيد التصفير", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showHardwareZeroConfirmDialog = false },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("إلغاء", fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White
                    )
                }

                // Dialog popup for Scale Action Results (Zero / Tare / Hardware Zero)
                scaleActionResultNotification?.let { data ->
                    AlertDialog(
                        onDismissRequest = { scaleActionResultNotification = null },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = SuccessGreen,
                                modifier = Modifier.size(36.dp)
                            )
                        },
                        title = {
                            Text(
                                text = "تم ${data.actionLabel} بنجاح ⚖️",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Side-by-side weight comparison badge
                                Surface(
                                    color = Color(0xFFF8FAFC),
                                    border = BorderStroke(1.dp, IndustrialBorder),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "الموقع: ${String.format(Locale.US, "%.1f", data.netWeightAfter)} كجم  ·  شاشة الميزان: ${String.format(Locale.US, "%.1f", data.grossWeightAtZero)} كجم",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = GBRDarkIndigo,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = "توضيح: التصفير المعتاد برمجي للبرنامج، وطمأنة أن الرقمان متزامنان ودقيقان.",
                                            fontSize = 10.5.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { scaleActionResultNotification = null },
                                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("dismiss_scale_result_dialog_btn")
                            ) {
                                Text("حسناً", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = Color.White
                    )
                }

                // High-Priority Safety Modal Dialog for Scale Disconnected Refusal (HTTP 409)
                if (showScaleDisconnectedDialog) {
                    AlertDialog(
                        onDismissRequest = { showScaleDisconnectedDialog = false },
                        icon = {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFEF2F2))
                                    .border(2.5.dp, Color(0xFFFCA5A5), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WaterDrop,
                                    contentDescription = null,
                                    tint = Color(0xFFDC2626),
                                    modifier = Modifier.size(40.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .align(Alignment.BottomEnd)
                                        .clip(CircleShape)
                                        .background(Color(0xFFDC2626))
                                        .border(2.dp, Color.White, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Block,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        },
                        title = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = t("⚠️ تم رفض الإجراء - الميزان غير متصل", "⚠️ Refused - Scale Disconnected"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = Color(0xFF991B1B),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "HTTP 409 Conflict | " + t("حماية الأمان والإنتاج", "Production Safety Guard"),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFDC2626),
                                    modifier = Modifier
                                        .background(Color(0xFFFEF2F2), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = t(
                                        "يرفض المتحكم الذكي فتح صمام الماء أو بدء التعبئة التلقائية عند عدم اتصال الميزان الإلكتروني، وذلك حمايةً للدفعة من إضافة مياه أو مواد زائدة دون قياس دقيق.",
                                        "The smart controller rejects opening the water valve or starting auto-fill when the digital scale is disconnected, preventing inaccurate water dosing or formula ruin."
                                    ),
                                    fontSize = 12.5.sp,
                                    color = Color(0xFF374151),
                                    textAlign = TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                                    border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
                                        Text(
                                            text = t(
                                                "الخطوات المطلوبة: تحقق من كابل RS232/مخرج الميزان وطاقة الشاشة ثم أعد المحاولة.",
                                                "Required steps: Check RS232 scale cable, display power, and retry."
                                            ),
                                            fontSize = 11.sp,
                                            color = Color(0xFF92400E),
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = { showScaleDisconnectedDialog = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("scale_disconnected_dialog_ok_btn")
                            ) {
                                Text(
                                    text = t("حسنًا، فهمت الإجراء", "Got It, Understand"),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = Color.White
                                )
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = Color.White
                    )
                }

                // Full Screen Scale Reading Display Mode (Keep screen awake, force fixed landscape orientation via graphicsLayer, no glitch)
                val contextFS = LocalContext.current
                DisposableEffect(showFullScreenScaleMode) {
                    val activity = contextFS as? android.app.Activity
                    val previousOrientation = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    if (showFullScreenScaleMode && activity != null) {
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        try {
                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        } catch (e: Exception) {
                            // Fallback if locked is restricted
                        }
                    }
                    onDispose {
                        if (activity != null) {
                            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            try {
                                activity.requestedOrientation = previousOrientation
                            } catch (e: Exception) {
                                // Fallback
                            }
                        }
                    }
                }

                if (showFullScreenScaleMode) {
                    val currentWeightFS = lineStatus?.weight ?: 0.0
                    val scaleConnectedFS = lineStatus?.scale_connected == true && !isPollingError
                    val fillActiveFS = lineStatus?.fill_active == true && !isPollingError
                    val fillTargetValFS = lineStatus?.fill_target ?: 0.0
                    val pkgWeightValFS = packageUnitWeightInput.toDoubleOrNull() ?: 24.35
                    val validPkgWeightFS = if (pkgWeightValFS > 0.0) pkgWeightValFS else 24.35
                    val tankTareValFS = (tankTareInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                    val netMixerWeightFS = (currentWeightFS - tankTareValFS).coerceAtLeast(0.0)
                    val containerCountFS = if (validPkgWeightFS > 0.0) kotlin.math.floor(netMixerWeightFS / validPkgWeightFS).toInt() else 0
                    val targetValFS = if (fillActiveFS && fillTargetValFS > 0) fillTargetValFS else (targetInput.toDoubleOrNull() ?: 0.0)
                    val waterTargetFS = if (fillRequestedWaterAmount > 0) fillRequestedWaterAmount else targetValFS
                    val waterPumpedFS = if (fillActiveFS) (currentWeightFS - fillStartWeight).coerceAtLeast(0.0) else 0.0
                    val progressPctFS = if (waterTargetFS > 0) ((waterPumpedFS / waterTargetFS) * 100.0).coerceIn(0.0, 100.0) else 0.0
                    // الوضع الدكن + لون الأرقام للشاشة الكاملة (يُحفظ على الجهاز)
                    val fsPrefs = remember { context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE) }
                    var fsDark by remember { mutableStateOf(fsPrefs.getBoolean("fs_dark", false)) }
                    var fsColorIdx by remember { mutableIntStateOf(fsPrefs.getInt("fs_color", 0)) }
                    // إخفاء مربعات التفاصيل (وزن العبوة / الوزن الفعلي / الخزان / الصافي) والإبقاء على الرقم الكبير فقط
                    var fsHideDetails by remember { mutableStateOf(fsPrefs.getBoolean("fs_hide_details", false)) }
                    // (فاتح، دكن): ألوان عالية التباين تُقرأ من بعيد
                    val fsPalette = listOf(
                        Color(0xFF047857) to Color(0xFF22FF99),
                        Color(0xFF1D4ED8) to Color(0xFF4DD0FF),
                        Color(0xFFB45309) to Color(0xFFFFD600),
                        Color(0xFF111827) to Color(0xFFFFFFFF)
                    )
                    val fsNumColor = fsPalette[fsColorIdx.coerceIn(0, fsPalette.lastIndex)].let { if (fsDark) it.second else it.first }
                    val fsBg = if (fsDark) Color.Black else Color(0xFFF8FAFC)
                    val fsText = if (fsDark) Color.White else GBRDarkIndigo
                    val fsSubText = if (fsDark) Color(0xFFD1D5DB) else GBRDarkIndigo.copy(alpha = 0.75f)

                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showFullScreenScaleMode = false },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false
                        )
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(fsBg)
                        ) {
                            val isPortrait = maxHeight > maxWidth
                            val contentWidth = if (isPortrait) maxHeight else maxWidth
                            val contentHeight = if (isPortrait) maxWidth else maxHeight

                            Box(
                                modifier = Modifier
                                    // requiredSize: يتجاهل قيود الأب كي يأخذ الصندوق (ارتفاع الشاشة × عرضها) فعلاً قبل التدوير؛ size كانت تُقلَّص إلى عرض الشاشة فيظهر العرض مضغوطاً ومتداخلاً
                                    .requiredSize(width = contentWidth, height = contentHeight)
                                    .align(Alignment.Center)
                                    .then(
                                        if (isPortrait) {
                                            Modifier.graphicsLayer {
                                                rotationZ = 90f
                                            }
                                        } else Modifier
                                    )
                                    .padding(20.dp)
                            ) {
                                // زر الإغلاق
                                IconButton(
                                    onClick = { showFullScreenScaleMode = false },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(fsText.copy(alpha = 0.12f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "إغلاق العرض الكامل",
                                        tint = fsText,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                // مؤشر الاتصال أعلى اليسار
                                Row(
                                    modifier = Modifier.align(Alignment.TopStart),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(if (scaleConnectedFS) SuccessGreen else ErrorRed)
                                    )
                                    Text(
                                        text = if (scaleConnectedFS) "الميزان متصل" else "الميزان غير متصل",
                                        color = if (scaleConnectedFS) (if (fsDark) Color(0xFF22FF99) else SuccessGreen) else (if (fsDark) Color(0xFFFF6B6B) else ErrorRed),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // أدوات المظهر أعلى الوسط: وضع دكن/فاتح + 4 ألوان
                                Row(
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            fsDark = !fsDark
                                            fsPrefs.edit().putBoolean("fs_dark", fsDark).apply()
                                        },
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(fsText.copy(alpha = 0.12f))
                                    ) {
                                        Icon(
                                            imageVector = if (fsDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                                            contentDescription = "تبديل الوضع الدكن",
                                            tint = fsText,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    if (weightDisplayMode == "CONTAINERS") {
                                        IconButton(
                                            onClick = {
                                                fsHideDetails = !fsHideDetails
                                                fsPrefs.edit().putBoolean("fs_hide_details", fsHideDetails).apply()
                                            },
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(fsText.copy(alpha = 0.12f))
                                        ) {
                                            Icon(
                                                imageVector = if (fsHideDetails) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = if (fsHideDetails) "إظهار تفاصيل الحساب" else "إخفاء تفاصيل الحساب",
                                                tint = fsText,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    fsPalette.forEachIndexed { idx, pair ->
                                        val c = if (fsDark) pair.second else pair.first
                                        Box(
                                            modifier = Modifier
                                                .size(30.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                                .border(
                                                    width = if (idx == fsColorIdx) 3.dp else 1.dp,
                                                    color = if (idx == fsColorIdx) fsText else fsText.copy(alpha = 0.35f),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    fsColorIdx = idx
                                                    fsPrefs.edit().putInt("fs_color", idx).apply()
                                                }
                                        )
                                    }
                                }

                                // العرض الرئيسي
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 44.dp, bottom = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = if (weightDisplayMode == "CONTAINERS") "قراءة الميزان - عدد العبوات" else "قراءة الميزان - الوزن الفعلي",
                                        color = fsSubText,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    val numSp = (contentHeight.value * (if (weightDisplayMode == "CONTAINERS") 0.46f else 0.34f)).coerceIn(72f, 240f).sp
                                    val numColorFinal = if (scaleConnectedFS) fsNumColor else Color.Gray
                                    Row(
                                        verticalAlignment = Alignment.Bottom,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = if (weightDisplayMode == "CONTAINERS") "$containerCountFS" else String.format(Locale.US, "%.1f", currentWeightFS),
                                            fontSize = numSp,
                                            lineHeight = numSp,
                                            fontWeight = FontWeight.Black,
                                            maxLines = 1,
                                            color = numColorFinal
                                        )
                                        Spacer(modifier = Modifier.width(14.dp))
                                        Text(
                                            text = if (weightDisplayMode == "CONTAINERS") "عبوة" else "كجم",
                                            fontSize = (numSp.value * 0.28f).sp,
                                            fontWeight = FontWeight.Bold,
                                            color = fsText,
                                            modifier = Modifier.padding(bottom = (numSp.value * 0.12f).dp)
                                        )
                                    }

                                    if (targetValFS > 0) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = fsNumColor.copy(alpha = 0.12f),
                                            border = BorderStroke(1.5.dp, fsNumColor.copy(alpha = 0.5f))
                                        ) {
                                            Text(
                                                text = "الهدف: ${String.format(Locale.US, "%.1f", targetValFS)} كجم (${String.format(Locale.US, "%.0f", progressPctFS)}%)",
                                                color = fsNumColor,
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp)
                                            )
                                        }
                                    } else if (weightDisplayMode == "CONTAINERS" && !fsHideDetails) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        // بطاقات التفاصيل: تسمية صغيرة فوق وقيمة كبيرة تحتها
                                        val chips = buildList {
                                            add("وزن العبوة" to String.format(Locale.US, "%.2f", validPkgWeightFS).trimEnd('0').trimEnd('.'))
                                            add("الوزن الفعلي" to String.format(Locale.US, "%.1f", currentWeightFS))
                                            if (tankTareValFS > 0.0) {
                                                add("الخزان" to String.format(Locale.US, "%.1f", tankTareValFS))
                                                add("الصافي" to String.format(Locale.US, "%.1f", netMixerWeightFS))
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            chips.forEach { (label, value) ->
                                                Surface(
                                                    modifier = Modifier.weight(1f),
                                                    shape = RoundedCornerShape(14.dp),
                                                    color = fsText.copy(alpha = if (fsDark) 0.10f else 0.06f),
                                                    border = BorderStroke(1.dp, fsText.copy(alpha = 0.25f))
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Text(label, color = fsSubText, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                                        Text(
                                                            "$value كجم",
                                                            color = if (label == "الصافي") fsNumColor else fsText,
                                                            fontSize = 26.sp,
                                                            fontWeight = FontWeight.Black,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Modal Dialog for selecting weight display mode (Weight vs Package Count)
                if (showWeightModeDialog) {
                    val currentWeight = lineStatus?.weight ?: 0.0
                    AlertDialog(
                        onDismissRequest = { showWeightModeDialog = false },
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEFF6FF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = GBRBlueMain,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        },
                        title = {
                            Text(
                                text = "اختيار نمط عرض وزن الخلاط ⚖️",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = GBRDarkIndigo,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "اختر كيفية عرض كمية أو وزن المادة داخل الخلاط على الشاشة الرئيسية:",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Right
                                )

                                // Option 1: Actual Weight
                                Surface(
                                    onClick = { tempWeightDisplayMode = "WEIGHT" },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (tempWeightDisplayMode == "WEIGHT") Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                                    border = BorderStroke(
                                        if (tempWeightDisplayMode == "WEIGHT") 2.dp else 1.dp,
                                        if (tempWeightDisplayMode == "WEIGHT") GBRBlueMain else Color(0xFFE2E8F0)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        RadioButton(
                                            selected = (tempWeightDisplayMode == "WEIGHT"),
                                            onClick = { tempWeightDisplayMode = "WEIGHT" },
                                            colors = RadioButtonDefaults.colors(selectedColor = GBRBlueMain)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "1. الوزن الفعلي المباشر (كجم)",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = GBRDarkIndigo
                                            )
                                            Text(
                                                text = "عرض القراءة الحقيقية للميزان بالكيلوجرام (مثلاً: ${String.format(Locale.US, "%.1f", lineStatus?.weight ?: 0.0)} كجم)",
                                                fontSize = 11.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }

                                // Option 2: Container/Pack Count
                                Surface(
                                    onClick = { tempWeightDisplayMode = "CONTAINERS" },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (tempWeightDisplayMode == "CONTAINERS") Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                                    border = BorderStroke(
                                        if (tempWeightDisplayMode == "CONTAINERS") 2.dp else 1.dp,
                                        if (tempWeightDisplayMode == "CONTAINERS") GBRBlueMain else Color(0xFFE2E8F0)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            RadioButton(
                                                selected = (tempWeightDisplayMode == "CONTAINERS"),
                                                onClick = { tempWeightDisplayMode = "CONTAINERS" },
                                                colors = RadioButtonDefaults.colors(selectedColor = GBRBlueMain)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "2. حساب عدد العبوات في الخلاط (عبوة)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = GBRDarkIndigo
                                                )
                                                Text(
                                                    text = "عرض عدد العبوات كأرقام صحيحة بدلاً من الوزن (مثلاً: 100، 99، 98)",
                                                    fontSize = 11.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                        }

                                        if (tempWeightDisplayMode == "CONTAINERS") {
                                            OutlinedTextField(
                                                value = tempPackageUnitWeightInput,
                                                onValueChange = { tempPackageUnitWeightInput = it },
                                                label = { Text("وزن العبوة الواحدة (كجم)") },
                                                placeholder = { Text("مثلاً: 24.35") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GBRBlueMain,
                                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                                )
                                            )

                                            OutlinedTextField(
                                                value = tempTankTareInput,
                                                onValueChange = { tempTankTareInput = it },
                                                label = { Text("وزن الخزان/الخلاط الفارغ (كجم) - يُطرح من الوزن") },
                                                placeholder = { Text("مثلاً: 200 (اتركه 0 لعدم الطرح)") },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = GBRBlueMain,
                                                    unfocusedBorderColor = Color(0xFFCBD5E1)
                                                )
                                            )

                                            val tempVal = tempPackageUnitWeightInput.toDoubleOrNull() ?: 24.35
                                            val tempTare = (tempTankTareInput.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                                            val tempNet = ((lineStatus?.weight ?: 0.0) - tempTare).coerceAtLeast(0.0)
                                            val sampleCount = if (tempVal > 0) kotlin.math.floor(tempNet / tempVal).toInt() else 0
                                            Text(
                                                text = "النتيجة المتوقعة الآن: $sampleCount عبوة (الميزان ${String.format(Locale.US, "%.1f", currentWeight)} كجم" + (if (tempTare > 0.0) " − الخزان ${String.format(Locale.US, "%.1f", tempTare)} = الصافي ${String.format(Locale.US, "%.1f", tempNet)} كجم" else "") + ")",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = GBRBlueMain
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val finalMode = tempWeightDisplayMode
                                    val finalPkgWeight = if (tempPackageUnitWeightInput.isBlank()) "24.35" else tempPackageUnitWeightInput
                                    val finalTankTare = if (tempTankTareInput.isBlank()) "0" else tempTankTareInput

                                    if (selectedTab == 0) {
                                        line1WeightMode = finalMode
                                        line1PkgWeight = finalPkgWeight
                                        line1TankTare = finalTankTare
                                    } else {
                                        line2WeightMode = finalMode
                                        line2PkgWeight = finalPkgWeight
                                        line2TankTare = finalTankTare
                                    }

                                    val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
                                    prefs.edit()
                                        .putString(if (selectedTab == 0) "line_1_weight_mode" else "line_2_weight_mode", finalMode)
                                        .putString(if (selectedTab == 0) "line_1_pkg_weight" else "line_2_pkg_weight", finalPkgWeight)
                                        .putString(if (selectedTab == 0) "line_1_tank_tare" else "line_2_tank_tare", finalTankTare)
                                        .apply()

                                    showWeightModeDialog = false
                                    Toast.makeText(context, "تم حفظ نمط العرض ووزن العبوة ($finalPkgWeight كجم) بنجاح 💾", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("تطبيق التغيير 🎯", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = { showWeightModeDialog = false },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("إلغاء", fontWeight = FontWeight.Bold)
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        containerColor = Color.White
                    )
                }

                // ==========================================
                // ب) بقية التفاصيل بترتيب أقل أهمية بصرياً
                // ==========================================

                // Active Fault Reason Banner (If controller has an active fault reason)
                val activeFaultReasonBanner = lineStatus?.fault_reason.orEmpty()
                if (activeFaultReasonBanner.isNotBlank()) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        border = BorderStroke(1.dp, ErrorRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(20.dp))
                            Column {
                                Text(
                                    text = "سبب التنبيه الحالي في المتحكم ⚠️",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = ErrorRed
                                )
                                Text(
                                    text = activeFaultReasonBanner,
                                    fontSize = 11.sp,
                                    color = Color(0xFF991B1B),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Bottom Actions: Connection Test & Controller Restart (/restart)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, IndustrialBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = t("💡 أدوات فحص وتحديث اتصال لوحة المتحكم", "💡 Controller Connection & Reset Tools"),
                            fontSize = 12.sp,
                            color = GBRDarkIndigo,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Shortened Connection Test Button (زر اختبار الاتصال بمحتوى نصي مختصر)
                            OutlinedButton(
                                onClick = {
                                    if (currentLineIp.isBlank()) {
                                        Toast.makeText(context, t("الرجاء تحديد عنوان IP أولاً", "Please set IP address first"), Toast.LENGTH_SHORT).show()
                                        return@OutlinedButton
                                    }
                                    sendCommand("http://$currentLineIp/led?action=blink", {
                                        Toast.makeText(context, t("تم إرسال أمر وميض اللوحة الذكية للتأكيد بنجاح! 💡", "Blink command sent successfully! 💡"), Toast.LENGTH_SHORT).show()
                                    }, { err ->
                                        Toast.makeText(context, t("فشل إرسال أمر الوميض: ${err.message}", "Failed to send blink command: ${err.message}"), Toast.LENGTH_SHORT).show()
                                    })
                                },
                                enabled = isControllerOnline,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = GBRBlueMain,
                                    disabledContentColor = Color.Gray
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .testTag("blink_led_btn")
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = t("اختبار الاتصال", "Test Connection"),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // 2. Controller Restart Button (زر إعادة تشغيل المتحكم عبر المسار /restart)
                            OutlinedButton(
                                onClick = {
                                    if (currentLineIp.isBlank()) {
                                        Toast.makeText(context, t("الرجاء تحديد عنوان IP أولاً", "Please set IP address first"), Toast.LENGTH_SHORT).show()
                                        return@OutlinedButton
                                    }
                                    showRestartConfirmDialog = true
                                },
                                enabled = currentLineIp.isNotBlank(),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isControllerOnline) WarningOrange else Color.LightGray),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = WarningOrange,
                                    disabledContentColor = Color.Gray
                                ),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .testTag("restart_controller_btn")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = t("إعادة تشغيل المتحكم", "Restart Controller"),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Bottom spacer to ensure scrolling content clears the floating E-Stop button
                Spacer(modifier = Modifier.height(72.dp))
            }
        }

    // Confirmation Dialog for Controller Restart (/restart)
    if (showRestartConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRestartConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = t("تأكيد إعادة تشغيل المتحكم 🔄", "Confirm Controller Restart 🔄"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = GBRDarkIndigo,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = t(
                        "هل أنت متأكد من رغبتك في إعادة تشغيل لوحة المتحكم لـ ($currentLineName)؟ سيتم إرسال أمر إعادة التشغيل للمتحكم عبر المسار /restart وإعادة تهيئة الاتصال.",
                        "Are you sure you want to restart the controller for ($currentLineName)? The restart command will be sent via /restart."
                    ),
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartConfirmDialog = false
                        if (currentLineIp.isBlank()) {
                            Toast.makeText(context, t("الرجاء تحديد عنوان IP أولاً", "Please set IP address first"), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val ipToRestart = currentLineIp.trim()
                        val wasOnlineBefore = isControllerOnline

                        coroutineScope.launch {
                            Toast.makeText(
                                context,
                                t("جاري إرسال أمر إعادة التشغيل للمتحكم... ⏳", "Sending restart command to controller... ⏳"),
                                Toast.LENGTH_SHORT
                            ).show()

                            var httpSuccess = false
                            var exceptionEncountered: Exception? = null

                            // 1. Send restart command with a short timeout to prevent hanging when controller immediately reboots
                            withContext(Dispatchers.IO) {
                                try {
                                    val shortTimeoutClient = client.newBuilder()
                                        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .writeTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                                        .build()
                                    val request = Request.Builder()
                                        .url(resolveUrl(ipToRestart, "/restart"))
                                        .build()
                                    shortTimeoutClient.newCall(request).execute().use { response ->
                                        if (response.isSuccessful) {
                                            httpSuccess = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    exceptionEncountered = e
                                }
                            }

                            // 2. Monitor connectivity right after sending /restart
                            // When the microcontroller reboots, it closes its TCP socket / drops off WiFi.
                            var connectionDropped = false
                            withContext(Dispatchers.IO) {
                                delay(600) // Brief delay for controller hardware reset to begin
                                try {
                                    val pingClient = client.newBuilder()
                                        .connectTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .readTimeout(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .build()
                                    val pingReq = Request.Builder()
                                        .url(resolveUrl(ipToRestart, "/status"))
                                        .build()
                                    pingClient.newCall(pingReq).execute().use { resp ->
                                        if (!resp.isSuccessful) {
                                            connectionDropped = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Network exception (SocketTimeout, ConnectionRefused, EOF, etc.) proves controller is rebooting
                                    connectionDropped = true
                                }
                            }

                            withContext(Dispatchers.Main) {
                                // If HTTP was successful OR connection dropped/disconnected as expected on reboot OR it was previously online
                                if (httpSuccess || connectionDropped || wasOnlineBefore) {
                                    isPollingError = true
                                    lineStatus = null
                                    Toast.makeText(
                                        context,
                                        t("تمت إعادة تشغيل المتحكم بنجاح! 🔄 انقطع الاتصال لإعادة الإقلاع، جاري محاولة الاتصال التلقائي...", "Controller restarted successfully! 🔄 Disconnected for reboot, reconnecting automatically..."),
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    val errMsg = exceptionEncountered?.message ?: "لم يستجب المتحكم"
                                    Toast.makeText(
                                        context,
                                        t("فشل إرسال أمر إعادة التشغيل: $errMsg", "Failed to restart controller: $errMsg"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("confirm_restart_btn")
                ) {
                    Text(t("إعادة التشغيل الآن", "Restart Now"), color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRestartConfirmDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(t("إلغاء", "Cancel"), color = Color.DarkGray)
                }
            }
        )
    }

    // =========================================================================
    // LINE 2 MAINTENANCE HUB & SENSORS FULL-SCREEN DIAGNOSTIC PAGE
    // =========================================================================

    // Maintenance Dialog for Line 2 (نافذة مركز الصيانة لخط إنتاج 2)
    if (showMaintenanceDialog) {
        AlertDialog(
            onDismissRequest = { showMaintenanceDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(GBRBlueMain.copy(alpha = 0.12f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = GBRBlueMain,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "مركز الصيانة 🛠️",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = GBRDarkIndigo
                            )
                            Text(
                                text = "أدوات الفحص الميداني والتشخيص السريع",
                                fontSize = 10.5.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    IconButton(
                        onClick = { showMaintenanceDialog = false },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "اختر أداة الصيانة المطلوبة لفحص الخط، وتشخيص المتحكم والدوائر الكهربائية:",
                        fontSize = 11.5.sp,
                        color = Color.DarkGray
                    )

                    // First Button: Sensors Status (زر حالة المستشعرات)
                    Card(
                        onClick = {
                            showMaintenanceDialog = false
                            showSensorsFullScreenPage = true
                        },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        border = BorderStroke(1.5.dp, Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("maintenance_btn_sensors_status")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color(0xFF3B82F6), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sensors,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = "حالة المستشعرات ⚡",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = Color(0xFF1E3A8A)
                                    )
                                    Surface(
                                        color = Color(0xFFDBEAFE),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "4 مستشعرات (GPIO)",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D4ED8),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                    Text(
                                        text = "تشخيص لحظي للمحرك، الهيدروليك، والسرعة مع منافذ GPIO.",
                                        fontSize = 10.sp,
                                        color = Color(0xFF1E40AF),
                                        lineHeight = 13.sp
                                    )
                                }
                            }
                            Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color(0xFF3B82F6), modifier = Modifier.size(22.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMaintenanceDialog = false }) {
                    Text("إغلاق", color = Color.Gray, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }

    // Full-Screen Sensors Diagnostic Page for Line 2 (صفحة كاملة لحالة وتشخيص المستشعرات)
    if (showSensorsFullScreenPage) {
        Dialog(
            onDismissRequest = { showSensorsFullScreenPage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFFF8FAFC)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                ) {
                    // Top Full-Screen Bar (Compact)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            IconButton(
                                onClick = { showSensorsFullScreenPage = false },
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(0xFFF1F5F9), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = "رجوع",
                                    tint = GBRDarkIndigo,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "حالة مستشعرات خط 2 ⚡",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.5.sp,
                                    color = GBRDarkIndigo
                                )
                                Surface(
                                    color = if (isControllerOnline) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = if (isControllerOnline) "متصل 🟢" else "غير متصل 🔴",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isControllerOnline) Color(0xFF166534) else Color(0xFF991B1B),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { refreshConnectionStatus() },
                            colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("تحديث 🔄", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = IndustrialBorder)

                    // Scrollable Page Content
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val isMotorActive = lineStatus?.motor_running == true
                        // المستشعرات الفعلية من /status (hydraulic_up_active / hydraulic_down_active / speed_adjusting)،
                        // وحالة الريليه احتياط فقط إن لم يرسل المتحكم الحقل (حالة الريليه تبقى false أثناء الضغط في برمجية خط 2)
                        val isLiftActive = lineStatus?.hydraulic_up_active ?: (lineStatus?.relays?.find { it.id == 4 }?.state == true)
                        val isLowerActive = lineStatus?.hydraulic_down_active ?: (lineStatus?.relays?.find { it.id == 5 }?.state == true)
                        val isSpeedInc = lineStatus?.relays?.find { it.id == 7 }?.state == true
                        val isSpeedDec = lineStatus?.relays?.find { it.id == 8 }?.state == true
                        val isSpeedActive = lineStatus?.speed_adjusting ?: (isSpeedInc || isSpeedDec)
                        val speedActiveLabel = if (isSpeedInc) "جاري الزيادة ⏩" else if (isSpeedDec) "جاري الخفض ⏪" else "تعديل نشط"

                        // Diagnostic Summary Header Card (Compact)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isControllerOnline) Color(0xFFF0FDF4) else Color(0xFFFEF2F2)
                            ),
                            border = BorderStroke(1.dp, if (isControllerOnline) SuccessGreen.copy(alpha = 0.5f) else ErrorRed.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(if (isControllerOnline) SuccessGreen.copy(alpha = 0.15f) else ErrorRed.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isControllerOnline) Icons.Default.Sensors else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (isControllerOnline) SuccessGreen else ErrorRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        text = if (isControllerOnline) "المستشعرات متصلة وتعمل بنجاح 🟢" else "المتحكم غير متصل حالياً 🔴",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isControllerOnline) Color(0xFF166534) else Color(0xFF991B1B)
                                    )
                                    Text(
                                        text = "عرض حالة التشغيل لكل مستشعر في صف مستقل مع منفذ GPIO وألوان الأسلاك.",
                                        fontSize = 9.5.sp,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                        }

                        // EACH SENSOR CARD IN ITS OWN ROW (كل بطاقة مستشعر في صف مستقل)
                        // 1. مستشعر المحرك (GPIO 17)
                        SensorStatusDiagnosticCard(
                            title = "مستشعر المحرك",
                            sensorRole = "محرك الخلط الرئيسي",
                            gpioPort = "GPIO 17",
                            isActive = isMotorActive,
                            activeText = "يعمل الآن ⚡",
                            inactiveText = "متوقف",
                            lastRunText = formatSensorLastRun(motorLastRunTime, isMotorActive, lineStatus?.motor_runtime_seconds ?: 0L),
                            wireArabic = "أبيض وبرتقالي",
                            wireEnglish = "White / Orange",
                            wireColors = listOf(Color.White, Color(0xFFEA580C)),
                            diagnosticDetails = "يراقب دوران وتشغيل محرك الخلط الرئيسي وحساس الحماية (Relay 2 بدء / Relay 3 إيقاف).",
                            icon = Icons.Default.ElectricBolt,
                            iconTint = Color(0xFFEA580C)
                        )

                        // 2. مستشعر رفع الهيدروليك (GPIO 18)
                        SensorStatusDiagnosticCard(
                            title = "مستشعر رفع الهيدروليك",
                            sensorRole = "صمام الصعود الهيدروليكي",
                            gpioPort = "GPIO 18",
                            isActive = isLiftActive,
                            activeText = "يرفع الآن ⬆️",
                            inactiveText = "متوقف",
                            lastRunText = formatSensorLastRun(liftLastRunTime, isLiftActive),
                            wireArabic = "أبيض وأزرق",
                            wireEnglish = "White / Blue",
                            wireColors = listOf(Color.White, Color(0xFF2563EB)),
                            diagnosticDetails = "يراقب صمام الرفع الهيدروليكي لصعود رأس الخلاط للأعلى (Relay 4 - ضغط مستمر للتشغيل).",
                            icon = Icons.Default.KeyboardArrowUp,
                            iconTint = Color(0xFF2563EB)
                        )

                        // 3. مستشعر تنزيل الهيدروليك (GPIO 8)
                        SensorStatusDiagnosticCard(
                            title = "مستشعر تنزيل الهيدروليك",
                            sensorRole = "صمام الهبوط والتفريغ",
                            gpioPort = "GPIO 8",
                            isActive = isLowerActive,
                            activeText = "ينزل الآن ⬇️",
                            inactiveText = "متوقف",
                            lastRunText = formatSensorLastRun(lowerLastRunTime, isLowerActive),
                            wireArabic = "أخضر",
                            wireEnglish = "Green",
                            wireColors = listOf(Color(0xFF16A34A)),
                            diagnosticDetails = "يراقب صمام التنزيل الهيدروليكي لنزول رأس الخلاط بسلاسة للأسفل (Relay 5 - ضغط مستمر للتشغيل).",
                            icon = Icons.Default.KeyboardArrowDown,
                            iconTint = Color(0xFF16A34A)
                        )

                        // 4. مستشعر تغيير السرعة (GPIO 9)
                        SensorStatusDiagnosticCard(
                            title = "مستشعر تغيير السرعة",
                            sensorRole = "مغير السرعات الميكانيكي",
                            gpioPort = "GPIO 9",
                            isActive = isSpeedActive,
                            activeText = speedActiveLabel,
                            inactiveText = "وضع الثبات",
                            lastRunText = formatSensorLastRun(speedLastRunTime, isSpeedActive),
                            wireArabic = "أبيض وأخضر",
                            wireEnglish = "White / Green",
                            wireColors = listOf(Color.White, Color(0xFF16A34A)),
                            diagnosticDetails = "يراقب محرك مغير السرعة للبكرات (Relay 7 زيادة / Relay 8 تخفيض، مقفل أثناء توقف المحرك).",
                            icon = Icons.Default.Speed,
                            iconTint = Color(0xFFD97706)
                        )

                        // Spacer to ensure last sensor card is fully visible without clipping when scrolling
                        Spacer(modifier = Modifier.height(100.dp))
                    }
                }
            }
        }
    }

    // 1. Device Health Dialog (GET /system/stats)
    if (showStatsDialog) {
        AlertDialog(
            onDismissRequest = { showStatsDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = GBRBlueMain
                        )
                        Text(
                            text = "صحة الجهاز وموارد المتحكم 🩺",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = GBRDarkIndigo
                        )
                    }
                    // Gear Icon Button ("زر الترس") - Opens Edit Settings/IP Dialog
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier
                            .size(34.dp)
                            .background(GBRBlueMain.copy(alpha = 0.12f), CircleShape)
                            .testTag("open_line_settings_gear_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "إعدادات IP والخط",
                            tint = GBRBlueMain,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (selectedTab == 1) {
                        Button(
                            onClick = {
                                showStatsDialog = false
                                showMaintenanceDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFF6FF)),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.5.dp, Color(0xFF3B82F6)),
                            modifier = Modifier.fillMaxWidth().testTag("line2_maintenance_hub_btn")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = GBRBlueMain, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("مركز الصيانة", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1E3A8A))
                            }
                        }
                    }

                    if (!isControllerOnline) {
                        // Faded / Dimmed Offline Banner for Disconnected Device
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                            border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = ErrorRed,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "الجهاز غير متصل حالياً 📡⚠️",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ErrorRed,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "عنوان IP المسجل: ${if (currentLineIp.isBlank()) "غير مخصص" else currentLineIp}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.DarkGray,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "تعذر جلب صحة الجهاز وموارده لأن المتحكم غير متصل. يمكنك الضغط على زر الترس ⚙️ في أعلى النافذة للبحث عن المتحكم في الشبكة أو تعديل عنوان IP.",
                                    fontSize = 11.sp,
                                    color = Color.Gray,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                                Button(
                                    onClick = { showEditDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تعديل إعدادات IP والبحث عن المتحكم ⚙️", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                        }
                    } else if (isLoadingStats) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GBRBlueMain)
                        }
                    } else if (systemStats == null) {
                        Text("لم يتم استلام بيانات الموارد بعد. أعد المحاولة بعد التأكد من اتصال المتحكم.", fontSize = 12.sp, color = Color.Gray)
                    } else {
                        val stats = systemStats!!

                        // GBR Control Center Top Status Evaluation Banner
                        val isBrownout = stats.rebootReason.uppercase().contains("BROWNOUT") || stats.rebootReason.uppercase().contains("POWER_DROP")
                        val isHighRam = stats.memoryUsedPct > 85.0
                        val hasFaults = faultLogsList.isNotEmpty()
                        val activeFault = lineStatus?.fault_reason?.isNotBlank() == true
                        val isRssiWeak = (lineStatus?.rssi ?: 0) < -80 && (lineStatus?.rssi ?: 0) != 0
                        val isIssueFound = isBrownout || isHighRam || hasFaults || activeFault || isRssiWeak

                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (isIssueFound) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)),
                            border = BorderStroke(1.dp, if (isIssueFound) ErrorRed else SuccessGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isIssueFound) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (isIssueFound) ErrorRed else SuccessGreen,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column {
                                    Text(
                                        text = if (isIssueFound) "الجهاز يعمل لكن هناك ملاحظات تستحق المتابعة ⚠️" else "✓ الجهاز يعمل بشكل طبيعي وصحي",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isIssueFound) ErrorRed else SuccessGreen
                                    )
                                    if (isIssueFound) {
                                        val issueDetails = mutableListOf<String>()
                                        if (isBrownout) issueDetails.add("انخفاض مفاجئ في كهرباء المتحكم")
                                        if (isHighRam) issueDetails.add("استخدام مرتفع جداً للذاكرة")
                                        if (activeFault) issueDetails.add("وجود سبب تنبيه فعال")
                                        if (hasFaults) issueDetails.add("وجود أعطال مسجلة")
                                        if (isRssiWeak) issueDetails.add("إشارة WiFi ضعيفة")
                                        Text(
                                            text = issueDetails.joinToString(" • "),
                                            fontSize = 10.sp,
                                            color = Color(0xFF991B1B)
                                        )
                                    }
                                }
                            }
                        }

                        // Active Fault Reason (If present)
                        val currentFaultReason = lineStatus?.fault_reason.orEmpty()
                        if (currentFaultReason.isNotBlank()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                                border = BorderStroke(1.dp, ErrorRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(16.dp))
                                        Text("سبب التنبيه الحالي في المتحكم (Fault Reason):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ErrorRed)
                                    }
                                    Text(currentFaultReason, fontSize = 11.sp, color = Color(0xFF991B1B), fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        // WiFi Signal Strength Rating Card with Phone Signal Bars
                        if ((lineStatus?.rssi ?: 0) != 0) {
                            val rssiPair = formatRssiDescription(lineStatus?.rssi ?: 0)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.dp, IndustrialBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("قوة إشارة الشبكة اللاسلكية (WiFi Signal):", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                                        Text(rssiPair.first, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = rssiPair.second)
                                    }
                                    PhoneSignalBars(
                                        rssi = lineStatus?.rssi ?: 0,
                                        isOnline = isControllerOnline,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }

                        // Quick Action Buttons transferred from Diagnostics box: Fault Logs & WiFi Management
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Fault Log Button with Integrated Trash Icon to clear faults (/log/faults/clear)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (isControllerOnline) WarningOrange else Color.LightGray),
                                color = Color.Transparent,
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Main button area to view faults
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable(enabled = isControllerOnline) {
                                                fetchFaultLogs()
                                                showFaultsDialog = true
                                            }
                                            .padding(horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = if (isControllerOnline) WarningOrange else Color.Gray,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            "سجل الأعطال",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isControllerOnline) WarningOrange else Color.Gray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Divider between action and delete icon
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(18.dp)
                                            .background(if (isControllerOnline) WarningOrange.copy(alpha = 0.4f) else Color.LightGray)
                                    )

                                    // Integrated Trash Icon to clear faults
                                    IconButton(
                                        onClick = { showClearFaultLogsConfirmDialog = true },
                                        enabled = isControllerOnline,
                                        modifier = Modifier.size(34.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "حذف سجل الأعطال",
                                            tint = if (isControllerOnline) ErrorRed else Color.Gray,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }

                            // WiFi Management Button
                            OutlinedButton(
                                onClick = {
                                    fetchSavedWifiNetworks()
                                    showWifiDialog = true
                                },
                                enabled = isControllerOnline,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF2563EB),
                                    disabledContentColor = Color.Gray
                                ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.weight(1f).height(38.dp)
                            ) {
                                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("إدارة WiFi 📶", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        // Memory & Storage Circular Indicators Row (Side-by-Side, Compact)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. RAM Card
                            val ramPair = formatRamUsageDescription(stats.memoryUsedPct)
                            val ramProgress = (stats.memoryUsedPct / 100.0).coerceIn(0.0, 1.0).toFloat()
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.dp, IndustrialBorder),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "الذاكرة RAM",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRDarkIndigo,
                                        maxLines = 1
                                    )

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(52.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            progress = 1f,
                                            modifier = Modifier.size(52.dp),
                                            color = Color(0xFFE2E8F0),
                                            strokeWidth = 4.5.dp
                                        )
                                        CircularProgressIndicator(
                                            progress = ramProgress,
                                            modifier = Modifier.size(52.dp),
                                            color = ramPair.second,
                                            strokeWidth = 4.5.dp
                                        )
                                        Text(
                                            text = "${stats.memoryUsedPct.toInt()}%",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ramPair.second
                                        )
                                    }

                                    if (stats.totalHeap > 0) {
                                        val freeKb = stats.freeHeap / 1024
                                        Text(
                                            text = "حر: ${freeKb}KB",
                                            fontSize = 9.5.sp,
                                            color = Color.Gray,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text(
                                            text = ramPair.first,
                                            fontSize = 9.5.sp,
                                            color = ramPair.second,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // 2. Storage (Flash) Card
                            val storageProgress = (stats.storageUsedPct / 100.0).coerceIn(0.0, 1.0).toFloat()
                            val formattedFlash = String.format(Locale.US, "%.0f", stats.storageUsedPct)
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                border = BorderStroke(1.dp, IndustrialBorder),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "التخزين Flash",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GBRDarkIndigo,
                                        maxLines = 1
                                    )

                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(52.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            progress = 1f,
                                            modifier = Modifier.size(52.dp),
                                            color = Color(0xFFE2E8F0),
                                            strokeWidth = 4.5.dp
                                        )
                                        CircularProgressIndicator(
                                            progress = storageProgress,
                                            modifier = Modifier.size(52.dp),
                                            color = GBRBlueMain,
                                            strokeWidth = 4.5.dp
                                        )
                                        Text(
                                            text = "$formattedFlash%",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GBRBlueMain
                                        )
                                    }

                                    if (stats.totalStorage > 0) {
                                        val freeKb = stats.freeStorage / 1024
                                        Text(
                                            text = "متبقي: ${freeKb}KB",
                                            fontSize = 9.5.sp,
                                            color = Color.DarkGray,
                                            maxLines = 1
                                        )
                                    } else {
                                        Text(
                                            text = "مستقر",
                                            fontSize = 9.5.sp,
                                            color = Color.DarkGray,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        confirmButton = {
            Button(
                onClick = {
                    fetchSystemStats()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "تحديث الآن",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
            dismissButton = {
                TextButton(onClick = { showStatsDialog = false }) {
                    Text("إغلاق", color = Color.Gray)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }

    // 2. Controller Faults Dialog (GET /log/faults & GET /log/faults/clear)
    if (showFaultsDialog) {
        AlertDialog(
            onDismissRequest = { showFaultsDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = WarningOrange)
                        Text("سجل أعطال المتحكم المنفصل ⚠️", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = GBRDarkIndigo)
                    }
                    if (faultLogsList.isNotEmpty()) {
                        IconButton(
                            onClick = { showClearFaultLogsConfirmDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "مسح سجل الأعطال",
                                tint = ErrorRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    Text("سجل منفصل لتشخيص أعطال الشبكة، الانقطاع، التصفير المفاجئ والأعطال البرمجية:", fontSize = 11.sp, color = Color.Gray)

                    if (isLoadingFaults) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GBRBlueMain)
                        }
                    } else if (faultLogsList.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                            border = BorderStroke(1.dp, Color(0xFFBBF7D0)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(36.dp))
                                Text("✅ لا توجد أعطال مسجلة - الجهاز يعمل بدون مشاكل", fontSize = 12.5.sp, color = SuccessGreen, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                        }
                    } else {
                        val sortedFaults = faultLogsList.reversed()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(faultLogsScrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sortedFaults.forEachIndexed { index, item ->
                                val timeParts = item.time.split(" ")
                                val currentDate = if (timeParts.isNotEmpty()) timeParts[0] else ""
                                val previousDate = if (index > 0) {
                                    val prevParts = sortedFaults[index - 1].time.split(" ")
                                    if (prevParts.isNotEmpty()) prevParts[0] else ""
                                } else ""

                                if (currentDate.isNotBlank() && currentDate != "-" && currentDate != previousDate) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(1.5.dp)
                                                .background(Color(0xFFCBD5E1))
                                        )
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp)
                                                .background(Color(0xFFFEF2F2), RoundedCornerShape(12.dp))
                                                .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(12.dp))
                                                .padding(horizontal = 10.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "📅 التاريخ: $currentDate",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF991B1B)
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(1.5.dp)
                                                .background(Color(0xFFCBD5E1))
                                        )
                                    }
                                }

                                val translatedText = translateFaultLogText(item.text)
                                val isWarningOrError = item.text.contains("Unexpected", ignoreCase = true) ||
                                        item.text.contains("FAILED", ignoreCase = true) ||
                                        item.text.contains("Blocked", ignoreCase = true) ||
                                        item.text.contains("BROWNOUT", ignoreCase = true) ||
                                        item.text.contains("DISCONNECTED", ignoreCase = true)

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isWarningOrError) Color(0xFFFEF2F2) else Color(0xFFF8FAFC)
                                    ),
                                    border = BorderStroke(1.dp, if (isWarningOrError) Color(0xFFFCA5A5) else IndustrialBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text("${index + 1}.", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isWarningOrError) ErrorRed else GBRDarkIndigo)
                                                Icon(
                                                    imageVector = if (isWarningOrError) Icons.Default.Warning else Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = if (isWarningOrError) ErrorRed else GBRBlueMain,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                if (item.time.isNotBlank()) {
                                                    val displayTime = if (timeParts.size > 1) timeParts[1] else item.time
                                                    Surface(
                                                        color = if (isWarningOrError) Color(0xFFFEE2E2) else Color(0xFFE2E8F0),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        Text(
                                                            text = displayTime,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (isWarningOrError) Color(0xFF991B1B) else GBRDarkIndigo,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        Text(
                                            text = translatedText,
                                            fontSize = 11.5.sp,
                                            color = if (isWarningOrError) Color(0xFF991B1B) else Color(0xFF1E293B),
                                            fontWeight = FontWeight.SemiBold,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (faultLogsList.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showClearFaultLogsConfirmDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مسح سجل الأعطال (/log/faults/clear)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { fetchFaultLogs() },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("تحديث", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFaultsDialog = false }) {
                    Text("إغلاق", color = Color.Gray)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }

    // 3. Saved WiFi Networks Dialog (GET /wifi/saved-networks)
    if (showWifiDialog) {
        AlertDialog(
            onDismissRequest = { showWifiDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, tint = GBRBlueMain)
                    Text("إدارة شبكات WiFi المحفوظة 📶", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GBRDarkIndigo)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    Text("الشبكات المحفوظة حالياً على المتحكم (بدون كلمات المرور):", fontSize = 11.sp, color = Color.Gray)

                    if (isLoadingWifiNetworks) {
                        Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = GBRBlueMain)
                        }
                    } else if (savedWifiNetworks.isEmpty()) {
                        Text("لا توجد شبكات محفوظة مسجلة.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            savedWifiNetworks.forEach { wifi ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (wifi.connected) Color(0xFFF0FDF4) else Color(0xFFF8FAFC)
                                    ),
                                    border = BorderStroke(1.dp, if (wifi.connected) Color(0xFFBBF7D0) else IndustrialBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.Wifi,
                                                contentDescription = null,
                                                tint = if (wifi.connected) SuccessGreen else GBRBlueMain,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Column {
                                                Text(wifi.ssid, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
                                                Text(
                                                    text = if (wifi.connected) "🟢 متصلة حالياً" else "⚪ غير متصلة",
                                                    fontSize = 10.sp,
                                                    color = if (wifi.connected) SuccessGreen else Color.Gray,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }

                                        IconButton(
                                            onClick = { deleteWifiNetwork(wifi.ssid) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "حذف الشبكة", tint = ErrorRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = IndustrialBorder)

                    Text("إضافة شبكة WiFi جديدة للمتحكم:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)

                    OutlinedTextField(
                        value = newWifiSsid,
                        onValueChange = { newWifiSsid = it },
                        label = { Text("اسم الشبكة (SSID)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GBRBlueMain, unfocusedBorderColor = IndustrialBorder),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newWifiPass,
                        onValueChange = { newWifiPass = it },
                        label = { Text("كلمة المرور (Password)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GBRBlueMain, unfocusedBorderColor = IndustrialBorder),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { addWifiNetwork(newWifiSsid, newWifiPass) },
                        enabled = newWifiSsid.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة الشبكة للمتحكم", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWifiDialog = false }) {
                    Text("إغلاق", color = Color.Gray)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }

    // 4. Urgent No Response Alert Dialog for Line 2
    if (showNoResponseDialog) {
        AlertDialog(
            onDismissRequest = {
                showNoResponseDialog = false
                activeUrgentFault?.let { fault ->
                    val uniqueKey = "${fault.time}_${fault.text}"
                    dismissedFaults = dismissedFaults + uniqueKey
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFD32F2F),
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = noResponseDialogTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFFD32F2F)
                    )
                }
            },
            text = {
                Text(
                    text = noResponseDialogMessage,
                    fontSize = 13.sp,
                    color = Color.DarkGray,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNoResponseDialog = false
                        activeUrgentFault?.let { fault ->
                            val uniqueKey = "${fault.time}_${fault.text}"
                            dismissedFaults = dismissedFaults + uniqueKey
                        }
                        showFaultsDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("عرض سجل الأعطال 🛠️", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNoResponseDialog = false
                        activeUrgentFault?.let { fault ->
                            val uniqueKey = "${fault.time}_${fault.text}"
                            dismissedFaults = dismissedFaults + uniqueKey
                        }
                    }
                ) {
                    Text("تجاهل", color = Color.Gray)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = Color.White
        )
    }
}
}
}
}
