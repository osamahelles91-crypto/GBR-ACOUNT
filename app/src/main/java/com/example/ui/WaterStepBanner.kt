package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// تلميح أعلى صفحة التحكم بالمعدات عند القدوم من خطوة مادة الماء في صالة الإنتاج: الكمية المطلوبة + زر العودة لنفس الخطوة.
@Composable
fun WaterStepBanner(ctx: GbrViewModel.WaterStepContext, onReturn: () -> Unit) {
    val qty = String.format(Locale.US, "%.2f", ctx.quantityKg).trimEnd('0').trimEnd('.')
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFEFF6FF),
        border = BorderStroke(1.dp, Color(0xFF93C5FD))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("💧 المطلوب من الماء: $qty كجم", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF1E3A8A))
                Text("أمر الإنتاج ${ctx.orderNumber}", fontSize = 11.sp, color = Color(0xFF475569))
            }
            Button(
                onClick = onReturn,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("العودة للأمر", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
