package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// مفاتيح تفعيل إشعارات العمليات الجارية (المحرك / الهيدروليك / السرعة) لكل خط، داخل ترس إعدادات الخط.

@Composable
fun LineOpsBoxSwitches(
    showMotor: Boolean, showHydraulic: Boolean, showSpeed: Boolean,
    onMotor: (Boolean) -> Unit, onHydraulic: (Boolean) -> Unit, onSpeed: (Boolean) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF8FAFC))
            .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "إشعارات العمليات الجارية على هذا الخط",
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B),
            textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth()
        )
        listOf(
            Triple("تشغيل المحرك (مع المدة المستهدفة)", showMotor, onMotor),
            Triple("الهيدروليك (رفع / خفض)", showHydraulic, onHydraulic),
            Triple("التحكم بالسرعة", showSpeed, onSpeed)
        ).forEach { (label, on, set) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, fontSize = 12.sp, color = Color(0xFF475569), modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = set)
            }
        }
    }
}
