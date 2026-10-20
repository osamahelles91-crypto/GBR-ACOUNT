package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// إنذار الأعطال العاجلة القادمة من المتحكم: نفس فكرة تنبيه انتهاء الطحن (نافذة + صوت متكرر + اهتزاز حتى يضغط المستخدم إيقاف).
@Composable
fun ControllerAlarmHost(viewModel: GbrViewModel) {
    val alarm by viewModel.activeControllerAlarm.collectAsState()
    val a = alarm ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current

    DisposableEffect(a.key) {
        var mediaPlayer: android.media.MediaPlayer? = null
        val vibrator = ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator

        // نفس صوت تنبيه الطحن بالضبط
        try {
            val defaultAlarmUri: android.net.Uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(ctx, defaultAlarmUri)
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator?.let { v ->
                if (v.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 1))
                    } else {
                        @Suppress("DEPRECATION")
                        v.vibrate(longArrayOf(0, 500, 500), 1)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            try {
                mediaPlayer?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try { vibrator?.cancel() } catch (e: Exception) { e.printStackTrace() }
        }
    }

    AlertDialog(
        onDismissRequest = { viewModel.dismissControllerAlarm() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp))
                Text("🚨 تنبيه عاجل من المتحكم!", fontWeight = FontWeight.Black, fontSize = 18.sp, color = Color(0xFF1E293B))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "ورد من متحكم الخط عطل عاجل يحتاج إلى تدخل فوري:",
                    fontSize = 13.sp, color = Color.DarkGray, lineHeight = 18.sp
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                    border = BorderStroke(1.dp, Color(0xFFFECACA)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🏭 الخط: ${a.lineName}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                        Text("⚠️ النوع: ${a.typeText}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF991B1B))
                        if (a.message.isNotBlank()) {
                            Text(a.message, fontSize = 12.sp, color = Color(0xFF7F1D1D))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.dismissControllerAlarm()
                    viewModel.showSegment("operational_alerts")
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004A99)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("فتح التنبيهات ➡️", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.dismissControllerAlarm() }) {
                Text("موافق - إيقاف الإنذار 📴", fontSize = 12.sp, color = Color.Gray)
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White
    )
}
