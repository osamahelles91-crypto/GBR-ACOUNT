package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class GrindingTimerService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var tickerJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var vibrator: Vibrator? = null

    private var orderId: String = ""
    private var stepKey: String = ""
    private var title: String = ""
    private var durationMinutes: Int = 0
    private var startTimeMs: Long = -1L
    private var isPlayingAlarm = false
    private var orderNumber: String = ""
    private var batchNumber: String = ""

    companion object {
        const val CHANNEL_ONGOING_ID = "com.example.grinding_timer_ongoing_channel"
        const val CHANNEL_FINISHED_ID = "com.example.grinding_timer_finished_channel"
        const val NOTIFICATION_ID = 9182

        const val ACTION_START = "com.example.ACTION_START_TIMER"
        const val ACTION_STOP = "com.example.ACTION_STOP_ALARM"

        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopAlarmInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        // Parse extras
        orderId = intent?.getStringExtra("orderId") ?: ""
        stepKey = intent?.getStringExtra("stepKey") ?: ""
        title = intent?.getStringExtra("title") ?: "طحن وتجانس"
        durationMinutes = intent?.getIntExtra("durationMinutes", 0) ?: 0
        startTimeMs = intent?.getLongExtra("startTimeMs", -1L) ?: -1L
        orderNumber = intent?.getStringExtra("orderNumber") ?: ""
        batchNumber = intent?.getStringExtra("batchNumber") ?: ""

        if (startTimeMs == -1L) {
            startTimeMs = System.currentTimeMillis()
        }

        // Ensure both channels are created
        createNotificationChannel()

        // Start Foreground immediately with a silent notification to meet Android OS requirements
        val initialNotif = buildForegroundNotification("⏳ بدء مؤقت الطحن...", "المادة: $title", durationMinutes * 60, 0)
        startForeground(NOTIFICATION_ID, initialNotif)

        // Start timer countdown loop
        startCheckingTimer()

        return START_STICKY
    }

    private fun startCheckingTimer() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            val totalSec = durationMinutes * 60
            while (true) {
                val elapsedSec = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt().coerceAtLeast(0)
                val remainingSec = totalSec - elapsedSec

                if (remainingSec > 0) {
                    val mins = remainingSec / 60
                    val secs = remainingSec % 60
                    val timeLeftStr = String.format(Locale.US, "%02d:%02d", mins, secs)
                    
                    val percent = if (totalSec > 0) {
                        (elapsedSec * 100 / totalSec).coerceIn(0, 100)
                    } else 0
                    
                    val titleMsg = if (orderNumber.isNotEmpty()) {
                        "⏳ طحن [$orderNumber]: $title ($percent%)"
                    } else {
                        "⏳ جاري طحن: $title ($percent%)"
                    }
                    val progressMsg = "الزمن المتبقي: $timeLeftStr • التقدم: $percent%"
                    
                    updateNotification(
                        buildForegroundNotification(
                            titleMsg,
                            progressMsg,
                            totalSec,
                            elapsedSec
                        )
                    )
                } else {
                    // Timer finished!
                    triggerAlarmAndNotification()
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun triggerAlarmAndNotification() {
        isPlayingAlarm = true

        // Play loud sound ONLY upon actual expiry
        try {
            val defaultAlarmUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, defaultAlarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e("GrindingTimerService", "Failed to start media player, playing backup tone", e)
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 180000) // 3 mins max
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        // Vibrate upon actual expiry
        try {
            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val pattern = longArrayOf(0, 800, 400, 800, 400, 800, 400)
                        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0)
                        v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, 1)) // 1 means repeat from index 1
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(longArrayOf(0, 800, 400, 800, 400), 1)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Update Notification to alerts state with a DISMISS button using Finished Channel and promote to foreground sticky
        val finishedNotification = buildFinishedNotification()
        startForeground(NOTIFICATION_ID, finishedNotification)
    }

    private fun buildForegroundNotification(titleText: String, contentText: String, progressMax: Int = 0, progressCurrent: Int = 0): Notification {
        val stopIntent = Intent(this, GrindingTimerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("orderId", orderId)
            putExtra("stepKey", stepKey)
        }
        val openPendingIntent = if (openIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setContentTitle(titleText)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Keeps modification silent and free of repeated chirps
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "إيقاف ومغادرة 🛑",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Default priority fitting countdown
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (progressMax > 0) {
            builder.setProgress(progressMax, progressCurrent, false)
        }

        return builder.build()
    }

    private fun buildFinishedNotification(): Notification {
        val stopIntent = Intent(this, GrindingTimerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("orderId", orderId)
            putExtra("stepKey", stepKey)
        }
        val openPendingIntent = if (openIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        } else null

        val details = StringBuilder()
        details.append("المادة: $title\n")
        if (orderNumber.isNotEmpty()) {
            details.append("أمر إنتاج: $orderNumber")
        }
        if (batchNumber.isNotEmpty()) {
            details.append(" (دفعة: $batchNumber)")
        }
        details.append("\nانتهى زمن طحن المادة بنجاح. اضغط لفتح المادة مباشرة واستئناف العمل ✓")

        return NotificationCompat.Builder(this, CHANNEL_FINISHED_ID)
            .setContentTitle("🏭 نظام GBR الصناعي • انتهى وقت الطحن!")
            .setContentText(details.toString())
            .setStyle(NotificationCompat.BigTextStyle().bigText(details.toString()))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true) // Kept until user explicitly dismisses
            .setContentIntent(openPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .addAction(
                android.R.drawable.ic_menu_send,
                "فتح واستئناف العمل ➡️",
                openPendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "موافق - إيقاف التنبيه 📴",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun stopAlarmInternal() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }

        try {
            toneGenerator?.let {
                it.stopTone()
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            toneGenerator = null
        }

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isPlayingAlarm = false
    }

    override fun onDestroy() {
        isServiceRunning = false
        stopAlarmInternal()
        tickerJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. Ongoing channel: IMPORTANCE_DEFAULT, no sound, no vibration
            val ongoingName = "مؤقت طحن وتجانس المادة الجاري"
            val ongoingDesc = "يظهر شريط تقدّم مؤقت خلط وطحن المواد الجارية في الصالة بشكل صامت"
            val ongoingChannel = NotificationChannel(
                CHANNEL_ONGOING_ID,
                ongoingName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = ongoingDesc
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(ongoingChannel)

            // 2. Finished channel: IMPORTANCE_HIGH, plays alarm sound, vibration
            val finishedName = "تنبيهات انتهاء طحن وتجانس المادة"
            val finishedDesc = "يصدر تنبيهاً مسموعاً ويدعم الاهتزاز الفوري فور انتهاء مؤقت طحن المادة بالكامل"
            val finishedChannel = NotificationChannel(
                CHANNEL_FINISHED_ID,
                finishedName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = finishedDesc
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                try {
                    val defaultAlarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    setSound(
                        defaultAlarmUri,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            manager.createNotificationChannel(finishedChannel)
        }
    }
}
