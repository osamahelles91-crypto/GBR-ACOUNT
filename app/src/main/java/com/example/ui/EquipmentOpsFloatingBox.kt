package com.example.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private data class OpsRow(val title: String, val detail: String, val progress: Float? = null)

private fun mmss(sec: Long): String {
    val t = if (sec < 0) 0L else sec
    return String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
}

// الأجزاء العاملة حالياً على الخط (المحرك / الهيدروليك / السرعة بحسب مفاتيح الخط في الترس + تعبئة الماء)
private fun opsRows(prefs: android.content.SharedPreferences, lineIndex: Int, s: LineStatus?, error: Boolean): List<OpsRow> {
    if (s == null || error) return emptyList()
    val n = lineIndex + 1
    val rows = mutableListOf<OpsRow>()

    if (prefs.getBoolean("line_${n}_opbox_motor", true) && s.motor_running) {
        val elapsed = s.motor_runtime_seconds
        val remaining = s.motor_timed_run_remaining_seconds
        if (s.motor_timed_run_active && remaining > 0) {
            val total = elapsed + remaining
            val p = if (total > 0) (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            rows.add(
                OpsRow(
                    if (s.motor_timed_run_paused) "⚙️ المحرك — تشغيل مستهدف (متوقف مؤقتاً)" else "⚙️ المحرك — تشغيل لمدة مستهدفة",
                    "المتبقي ${mmss(remaining)} من ${mmss(total)}", p
                )
            )
        } else rows.add(OpsRow("⚙️ المحرك يعمل", "مدة التشغيل ${mmss(elapsed)}"))
    }

    if (prefs.getBoolean("line_${n}_opbox_hyd", true)) {
        val up = s.hydraulic_up_active ?: (s.relays.find { it.id == 4 }?.state == true)
        val down = s.hydraulic_down_active ?: (s.relays.find { it.id == 5 }?.state == true)
        if (up || down) rows.add(OpsRow("🔧 الهيدروليك", if (up) "جاري الرفع ⬆️" else "جاري الخفض ⬇️"))
    }

    if (prefs.getBoolean("line_${n}_opbox_speed", true)) {
        val inc = s.relays.find { it.id == 7 }?.state == true
        val dec = s.relays.find { it.id == 8 }?.state == true
        val on = s.speed_adjusting ?: (inc || dec)
        if (on) rows.add(OpsRow("🎚️ السرعة", if (inc) "جاري الزيادة ⏩" else if (dec) "جاري الخفض ⏪" else "تعديل السرعة نشط"))
    }

    if (s.fill_active && s.fill_target > 0) {
        val p = (s.weight / s.fill_target).toFloat().coerceIn(0f, 1f)
        rows.add(
            OpsRow(
                "🚰 تعبئة الماء",
                String.format(Locale.US, "%.1f / %.1f كجم", s.weight, s.fill_target), p
            )
        )
    }
    return rows
}

// مربع عائم داخل التطبيق يجمع كل الأجزاء العاملة على خطوط الإنتاج، مع ✕ لإغلاقه (يعود عند بدء عملية جديدة).
@Composable
fun EquipmentOpsFloatingBox(viewModel: GbrViewModel) {
    val s1 by viewModel.line1Status.collectAsState()
    val s2 by viewModel.line2Status.collectAsState()
    val e1 by viewModel.line1PollingError.collectAsState()
    val e2 by viewModel.line2PollingError.collectAsState()
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE) }

    val rows1 = opsRows(prefs, 0, s1, e1)
    val rows2 = opsRows(prefs, 1, s2, e2)

    var closed1 by remember { mutableStateOf(false) }
    var closed2 by remember { mutableStateOf(false) }
    LaunchedEffect(rows1.isEmpty()) { if (rows1.isEmpty()) closed1 = false }
    LaunchedEffect(rows2.isEmpty()) { if (rows2.isEmpty()) closed2 = false }

    val show1 = rows1.isNotEmpty() && !closed1
    val show2 = rows2.isNotEmpty() && !closed2
    if (!show1 && !show2) return

    val name1 = prefs.getString("line_1_name", "خط الإنتاج رقم 1") ?: "خط الإنتاج رقم 1"
    val name2 = prefs.getString("line_2_name", "خط الإنتاج رقم 2") ?: "خط الإنتاج رقم 2"

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (show1) OpsCard(name1, rows1) { closed1 = true }
            if (show2) OpsCard(name2, rows2) { closed2 = true }
        }
    }
}

@Composable
private fun OpsCard(lineName: String, rows: List<OpsRow>, onClose: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2A47)),
        border = BorderStroke(1.dp, Color(0xFF2A78D6)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(lineName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color(0xFFB8C7D6), modifier = Modifier.size(18.dp))
                }
            }
            rows.forEachIndexed { i, r ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(r.detail, color = Color(0xFFB8C7D6), fontSize = 12.sp)
                }
                if (r.progress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { r.progress },
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(5.dp),
                        color = Color(0xFF1BAF7A),
                        trackColor = Color(0xFF2B3A4A)
                    )
                }
            }
        }
    }
}
