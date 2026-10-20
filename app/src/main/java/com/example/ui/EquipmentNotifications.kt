package com.example.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import java.util.Locale

object EquipmentNotifications {

    const val CHANNEL_ID = "equipment_water_fill_channel"
    private const val CHANNEL_NAME = "تعبئة المياه والمعدات"

    // قناة مستقلة لإشعارات العمليات الجارية (محرك/هيدروليك/سرعة): صامتة لكنها ظاهرة في شريط الحالة
    const val OPS_CHANNEL_ID = "equipment_ops_channel_v1"
    private const val OPS_CHANNEL_NAME = "عمليات المعدات الجارية"

    const val ALERT_CHANNEL_ID = "equipment_urgent_alerts_channel_v3"
    private const val ALERT_CHANNEL_NAME = "التنبيهات العاجلة للمعدات"

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            
            // 1. Water filling progress channel (low importance, silent)
            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "متابعة تقدم تعبئة المياه التلقائية وشريط الإشعارات الحي"
                    setSound(null, null)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(channel)
            }

            // 1b. Ongoing operations channel (default importance but silent)
            if (notificationManager.getNotificationChannel(OPS_CHANNEL_ID) == null) {
                val opsChannel = NotificationChannel(
                    OPS_CHANNEL_ID,
                    OPS_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "إشعارات تشغيل المحرك والهيدروليك وتعديل السرعة أثناء العمل"
                    setSound(null, null)
                    enableVibration(false)
                }
                notificationManager.createNotificationChannel(opsChannel)
            }

            // 2. Urgent alerts channel (high importance, sound and vibration enabled)
            if (notificationManager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
                val alertChannel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    ALERT_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "تنبيهات عاجلة عند انقطاع مصدر المياه أو عدم استجابة المحرك"
                    
                    // Explicitly enable and configure sound
                    val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .build()
                    setSound(defaultSoundUri, audioAttributes)
                    
                    // Explicitly enable and configure vibration
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    
                    // Enable lights
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                }
                notificationManager.createNotificationChannel(alertChannel)
            }
        }
    }

    fun updateEquipmentFillNotification(
        context: Context,
        lineIndex: Int,
        lineName: String,
        fillActive: Boolean,
        currentWeight: Double,
        targetWeight: Double
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(context)

        val notificationId = 4100 + lineIndex

        if (fillActive && targetWeight > 0) {
            val pct = ((currentWeight / targetWeight) * 100.0).coerceIn(0.0, 100.0)
            val progressMax = 1000
            val progressCurrent = (pct * 10.0).toInt().coerceIn(0, 1000)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val formatCurr = String.format(Locale.US, "%.1f", currentWeight)
            val formatTarget = String.format(Locale.US, "%.1f", targetWeight)
            val formatPct = String.format(Locale.US, "%.1f", pct)
            val formatRemaining = String.format(Locale.US, "%.1f", maxOf(0.0, targetWeight - currentWeight))

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("🚰 جاري تعبئة المياه ($lineName)")
                .setContentText("الحالي: $formatCurr كجم / المستهدف: $formatTarget كجم ($formatPct%) - متبقي $formatRemaining كجم")
                .setSubText("$formatPct%")
                .setProgress(progressMax, progressCurrent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            notificationManager.notify(notificationId, builder.build())
        } else {
            notificationManager.cancel(notificationId)
        }
    }

    fun showEquipmentFillCompletedNotification(
        context: Context,
        lineIndex: Int,
        lineName: String,
        finalWeight: Double,
        targetWeight: Double
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(context)

        val notificationId = 4100 + lineIndex
        val formatFinal = String.format(Locale.US, "%.1f", finalWeight)
        val formatTarget = String.format(Locale.US, "%.1f", targetWeight)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ اكتملت تعبئة المياه ($lineName)")
            .setContentText("الوزن النهائي المحقق: $formatFinal كجم من المستهدف: $formatTarget كجم")
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(notificationId, builder.build())
    }

    fun showEquipmentFillFailedNotification(
        context: Context,
        lineIndex: Int,
        lineName: String,
        currentWeight: Double,
        targetWeight: Double,
        reason: String = "انقطاع مصدر المياه أو عدم زيادة الوزن بعد فتح الصمام"
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(context)

        // Cancel the progress notification if running
        notificationManager.cancel(4100 + lineIndex)

        val notificationId = 4200 + lineIndex
        val formatCurr = String.format(Locale.US, "%.1f", currentWeight)
        val formatTarget = String.format(Locale.US, "%.1f", targetWeight)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("goToEquipment", true)
            putExtra("lineIndex", lineIndex)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠️ توقفت تعبئة المياه ($lineName) - لم يكتمل الهدف")
            .setContentText("توقف الضخ عند $formatCurr كجم من أصل $formatTarget كجم ($reason)")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "توقفت عملية التعبئة تلقائياً قبل بلوغ الوزن المستهدف.\n" +
                    "• الوزن الفعلي المحقق: $formatCurr كجم\n" +
                    "• الوزن المستهدف: $formatTarget كجم\n" +
                    "• السبب المرجح: $reason"
                )
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        notificationManager.notify(notificationId, builder.build())
    }

    // إشعارات العمليات الجارية للمحرك / الهيدروليك / السرعة (صامتة ومستمرة، مثل إشعار تعبئة الماء)
    private fun fmtMmSs(totalSec: Long): String {
        val t = if (totalSec < 0) 0L else totalSec
        return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
    }

    private fun postOngoing(context: Context, id: Int, title: String?, text: String, progress: Int? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (title == null) { nm.cancel(id); return }
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, OPS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (progress != null) b.setProgress(1000, progress.coerceIn(0, 1000), false)
        nm.notify(id, b.build())
    }

    fun updateEquipmentOpsNotifications(
        context: Context,
        lineIndex: Int,
        lineName: String,
        status: LineStatus?,
        showMotor: Boolean,
        showHydraulic: Boolean,
        showSpeed: Boolean
    ) {
        val base = 4300 + lineIndex * 3
        val s = status
        val motorOn = s?.motor_running == true
        val liftOn = s?.hydraulic_up_active ?: (s?.relays?.find { it.id == 4 }?.state == true)
        val lowerOn = s?.hydraulic_down_active ?: (s?.relays?.find { it.id == 5 }?.state == true)
        val spInc = s?.relays?.find { it.id == 7 }?.state == true
        val spDec = s?.relays?.find { it.id == 8 }?.state == true
        val speedOn = s?.speed_adjusting ?: (spInc || spDec)

        // المحرك (مع المدة المستهدفة إن وُجدت)
        if (showMotor && motorOn && s != null) {
            val elapsed = s.motor_runtime_seconds
            val remaining = s.motor_timed_run_remaining_seconds
            if (s.motor_timed_run_active && remaining > 0) {
                val total = elapsed + remaining
                val pct = if (total > 0) (elapsed * 1000L / total).toInt() else 0
                val head = if (s.motor_timed_run_paused) "⚙️ المحرك — تشغيل مستهدف متوقف مؤقتاً ($lineName)" else "⚙️ المحرك يعمل لمدة مستهدفة ($lineName)"
                postOngoing(context, base, head, "المتبقي ${fmtMmSs(remaining)} من ${fmtMmSs(total)}", pct)
            } else {
                postOngoing(context, base, "⚙️ المحرك يعمل ($lineName)", "مدة التشغيل ${fmtMmSs(elapsed)}")
            }
        } else postOngoing(context, base, null, "")

        // الهيدروليك
        if (showHydraulic && (liftOn || lowerOn)) {
            postOngoing(context, base + 1, "🔧 الهيدروليك ($lineName)", if (liftOn) "جاري الرفع ⬆️" else "جاري الخفض ⬇️")
        } else postOngoing(context, base + 1, null, "")

        // السرعة
        if (showSpeed && speedOn) {
            postOngoing(context, base + 2, "🎚️ السرعة ($lineName)", if (spInc) "جاري الزيادة ⏩" else if (spDec) "جاري الخفض ⏪" else "تعديل السرعة نشط")
        } else postOngoing(context, base + 2, null, "")
    }

    // يقرأ مفاتيح التفعيل المحفوظة لكل خط (من ترس إعدادات الخط) ثم يحدّث الإشعارات؛ تُستدعى من القراءة الخلفية لتعمل من أي صفحة
    fun updateEquipmentOpsFromPrefs(context: Context, lineIndex: Int, lineName: String, status: LineStatus?) {
        val prefs = context.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE)
        val n = lineIndex + 1
        updateEquipmentOpsNotifications(
            context, lineIndex, lineName, status,
            prefs.getBoolean("line_${n}_opbox_motor", true),
            prefs.getBoolean("line_${n}_opbox_hyd", true),
            prefs.getBoolean("line_${n}_opbox_speed", true)
        )
    }

    fun cancelEquipmentOpsNotifications(context: Context, lineIndex: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        for (k in 0..2) nm.cancel(4300 + lineIndex * 3 + k)
    }

    fun cancelEquipmentFillNotification(context: Context, lineIndex: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        notificationManager.cancel(4100 + lineIndex)
    }

    fun showControllerHardwareAlertNotification(
        context: Context,
        alert: ControllerHardwareAlert
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(context)

        val notificationId = 5100 + alert.id + (alert.lineIndex * 1000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("goToAlerts", true)
            putExtra("alertLineIndex", alert.lineIndex)
            putExtra("alertId", alert.id)
            putExtra("alertType", alert.type)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emoji = when (alert.type) {
            "NO_SENSOR_RESPONSE" -> "⚠️"
            "WATER_SUPPLY_FAILURE" -> "🚰❌"
            "SCALE_DISCONNECTED_WHILE_FILLING" -> "⚖️❌"
            else -> "🚨"
        }

        val typeText = when (alert.type) {
            "NO_SENSOR_RESPONSE" -> "عدم استجابة المستشعر"
            "WATER_SUPPLY_FAILURE" -> "انقطاع مصدر المياه"
            "SCALE_DISCONNECTED_WHILE_FILLING" -> "انقطاع اتصال الميزان"
            else -> alert.type
        }

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$emoji تنبيه عتادي [$typeText] - ${alert.lineName}")
            .setContentText(alert.message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setDefaults(NotificationCompat.DEFAULT_ALL)

        notificationManager.notify(notificationId, builder.build())
    }
}
