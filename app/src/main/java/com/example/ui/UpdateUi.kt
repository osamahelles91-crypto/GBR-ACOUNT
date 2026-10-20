package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.InfoRowItem
import com.example.data.AppVersion
import com.example.data.UpdateChecker
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/** قسم "التحديث" داخل صفحة حول التطبيق: المثبّت مقابل الأحدث + فحص + خيار التنبيه. */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by UpdateChecker.latest.collectAsState()
    val checking by UpdateChecker.checking.collectAsState()
    val failed by UpdateChecker.lastCheckFailed.collectAsState()
    var alertOn by remember { mutableStateOf(UpdateChecker.alertEnabled(context)) }
    LaunchedEffect(Unit) { if (latest == null) UpdateChecker.refresh(context) }

    val hasUpdate = latest?.let { UpdateChecker.isNewer(it, AppVersion.name) } == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        InfoRowItem(label = "الإصدار المثبّت", value = AppVersion.display)
        InfoRowItem(label = "أحدث إصدار متاح", value = latest ?: if (checking) "جارٍ الفحص..." else "غير معروف")
        val (msg, color) = when {
            checking && latest == null -> "جارٍ فحص التحديثات..." to Color.Gray
            latest == null && failed -> "تعذّر الفحص (تحقق من الاتصال)" to Color(0xFFB45309)
            hasUpdate -> "⬆️ يتوفر إصدار أحدث: ${latest}" to Color(0xFFB45309)
            else -> "✅ تطبيقك محدّث" to Color(0xFF15803D)
        }
        Text(msg, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = { scope.launch { UpdateChecker.refresh(context) } }, enabled = !checking, shape = RoundedCornerShape(10.dp)) {
                Text("فحص التحديث", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("نبّهني عند توفر تحديث", fontSize = 12.sp, color = Color.DarkGray)
                Switch(checked = alertOn, onCheckedChange = { alertOn = it; UpdateChecker.setAlertEnabled(context, it) })
            }
        }
    }
}
