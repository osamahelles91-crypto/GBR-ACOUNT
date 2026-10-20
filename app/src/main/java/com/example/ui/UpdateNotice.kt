package com.example.ui

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
import com.example.data.AppVersion
import com.example.data.UpdateChecker

/** الجوال: شريط هادئ يظهر عند توفر إصدار أحدث؛ إن أُغلق يعود بعد 24 ساعة. */
@Composable
fun UpdateAvailableNotice(enabled: Boolean) {
    val context = LocalContext.current
    val latest by UpdateChecker.latest.collectAsState()
    var dismissedNow by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) UpdateChecker.refresh(context) }
    if (!enabled || dismissedNow || latest == null || !UpdateChecker.shouldNotify(context)) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
        border = BorderStroke(1.dp, Color(0xFFFDE68A))
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "⬆️ يتوفر إصدار أحدث ${latest} (المثبّت عندك ${AppVersion.name})",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E), modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { UpdateChecker.dismiss(context); dismissedNow = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color(0xFF92400E), modifier = Modifier.size(16.dp))
            }
        }
    }
}
