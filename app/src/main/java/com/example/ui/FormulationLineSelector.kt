package com.example.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// اختيار خط الإنتاج التابع للتركيبة (اختياري). القيمة: -1 بدون، 0 الخط الأول، 1 الخط الثاني. الأسماء من إعدادات الخطوط.
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FormulationLineSelector(selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("gbr_equipment_prefs", Context.MODE_PRIVATE) }
    val name1 = prefs.getString("line_1_name", "خط الإنتاج رقم 1") ?: "خط الإنتاج رقم 1"
    val name2 = prefs.getString("line_2_name", "خط الإنتاج رقم 2") ?: "خط الإنتاج رقم 2"
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("خط الإنتاج التابع لهذه التركيبة (اختياري)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-1 to "بدون خط", 0 to name1, 1 to name2).forEach { (v, label) ->
                FilterChip(
                    selected = selected == v,
                    onClick = { onSelect(v) },
                    enabled = enabled,
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        Text("عند ربط التركيبة بخط تظهر أيقونة في خطوة مادة الماء بصالة الإنتاج تنقلك إلى صفحة هذا الخط.", fontSize = 11.sp, color = Color.Gray)
    }
}
