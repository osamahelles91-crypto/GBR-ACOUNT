package com.example.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ActivityLog
import com.example.ui.theme.*

/** تبويب "سجل النشاط" داخل شاشة سجل النظام: للقراءة فقط + ضبط اسم هذا الجهاز. */
@Composable
fun ActivityLogTab() {
    val context = LocalContext.current
    val entries by ActivityLog.entries.collectAsState()
    var filter by remember { mutableStateOf("all") }
    var deviceNameInput by remember { mutableStateOf(ActivityLog.customDeviceName(context)) }
    var savedHint by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, IndustrialBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("🧾 سجل النشاط — من فعل ماذا ومتى", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo)
            Text(
                "يسجّل العمليات المهمة (التركيبات، أوامر الإنتاج، المواد الخام، المختبر) ويظهر على كل الأجهزة. يُحتفظ بآخر 30 يوماً، وهو للقراءة فقط.",
                fontSize = 10.5.sp, color = Color.Gray, lineHeight = 15.sp
            )

            // اسم هذا الجهاز
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = deviceNameInput,
                    onValueChange = { deviceNameInput = it; savedHint = false },
                    label = { Text("اسم هذا الجهاز (مثال: كمبيوتر البيت)", fontSize = 11.sp) },
                    placeholder = { Text(ActivityLog.defaultDeviceName(), fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { ActivityLog.setDeviceName(context, deviceNameInput); savedHint = true },
                    colors = ButtonDefaults.buttonColors(containerColor = GBRBlueMain),
                    shape = RoundedCornerShape(10.dp)
                ) { Text(if (savedHint) "تم ✓" else "حفظ", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }

            val filters = listOf(
                "all" to "الكل", "formulation" to "التركيبات", "order" to "أوامر الإنتاج",
                "material" to "المواد الخام", "lab" to "المختبر والتطوير"
            )
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.forEach { (code, label) ->
                    val sel = filter == code
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) GBRBlueMain else Color.LightGray.copy(alpha = 0.2f))
                            .clickable { filter = code }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Color.DarkGray) }
                }
            }

            val shown = remember(entries, filter) { if (filter == "all") entries else entries.filter { it.category == filter } }
            Box(
                modifier = Modifier.fillMaxWidth().height(380.dp)
                    .background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp))
                    .border(1.dp, IndustrialBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد أحداث مسجلة بعد. تظهر هنا العمليات الجديدة فور حدوثها.", color = Color.Gray, fontSize = 11.sp)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { e ->
                            val timeStr = remember(e.timestamp) {
                                try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.timestamp)) } catch (x: Exception) { "--" }
                            }
                            val icon = when (e.category) { "formulation" -> "🧪"; "order" -> "🏭"; "material" -> "🧱"; "lab" -> "🔬"; else -> "•" }
                            Column {
                                Text("$icon ${e.message}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, lineHeight = 16.sp)
                                Text("👤 ${e.username}   ·   💻 ${e.deviceName}   ·   🕒 $timeStr", fontSize = 10.sp, color = Color.Gray)
                                HorizontalDivider(color = IndustrialBorder, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
