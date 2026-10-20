package com.example.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

// يلتقط أي انهيار غير معالَج ويعرض سببه كإشعار على الهاتف (اسم الخطأ + أول أسطر التتبع) ويحفظه في ملف،
// كي نعرف سبب الإغلاق المفاجئ دون الحاجة لأدوات المطورين. لا يغيّر سلوك التطبيق.
object CrashReporter {
    private const val CHANNEL_ID = "gbr_crash_report"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val full = sw.toString()
                try { java.io.File(app.filesDir, "last_crash.txt").writeText("thread=${thread.name}\n$full") } catch (_: Throwable) {}
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "تقارير الانهيار", NotificationManager.IMPORTANCE_DEFAULT))
                    }
                    val root = generateSequence(e) { it.cause }.last()
                    val title = "⚠️ توقف التطبيق: ${root.javaClass.simpleName}"
                    val body = (root.message ?: "").take(300) + "\n" + full.lineSequence().filter { it.contains("com.example") }.take(6).joinToString("\n")
                    val n = NotificationCompat.Builder(app, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle(title)
                        .setContentText((root.message ?: "").take(120))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1500)))
                        .setAutoCancel(true)
                        .build()
                    nm.notify(9911, n)
                }
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
