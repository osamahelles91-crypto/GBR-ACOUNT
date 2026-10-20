@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InventoryCount
import com.example.data.InventoryMath
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun countStatusLabel(s: String) = when (s) {
    "OPEN" -> "قيد العدّ"
    "SUBMITTED" -> "بانتظار الاعتماد"
    "APPROVED" -> "معتمد"
    "CANCELLED" -> "ملغى"
    else -> s
}

// =====================================================================================
// الجرد الدوري بالعدّ الأعمى: العدّاد لا يرى الرصيد الدفتري، والمعتمِد غير العدّاد
// =====================================================================================
@Composable
internal fun CountTab(vm: InventoryViewModel, rows: List<StockRow>, canEdit: Boolean, canApprove: Boolean, userName: String) {
    val counts by vm.counts.collectAsState()
    val lines by vm.countLines.collectAsState()
    val abc by vm.abcRows.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }

    val opened = counts.firstOrNull { it.id == openId }
    if (opened != null) {
        CountDetail(vm, opened, rows, lines.filter { it.countId == opened.id }, canEdit, canApprove, userName) { openId = null }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "العدّاد يُدخل الكميات دون أن يرى الرصيد الدفتري. عند التسليم يُلتقط الرصيد، ثم يعتمد شخص آخر الفروق فتُرحَّل كتسويات بسبب «جرد دوري».",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        if (canEdit) item { Button(onClick = { showNew = true }) { Text("جلسة جرد جديدة", fontSize = 13.sp) } }
        if (counts.isEmpty()) item { Text("لا جلسات جرد بعد.", color = Color.Gray, fontSize = 13.sp) }
        items(counts, key = { it.id }) { c ->
            val cl = lines.filter { it.countId == c.id }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                onClick = { openId = c.id }
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row {
                        Text(c.number, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(countStatusLabel(c.status), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0B7A6E))
                    }
                    Text(
                        (if (c.scope == "A") "مواد الفئة A" else "كل المواد ذات الرصيد") + " · " + cl.count { it.hasCount } + "/" + cl.size + " مادة معدودة" +
                            (if (c.countedBy.isNotBlank()) " · العدّاد: " + c.countedBy else "") +
                            (if (c.approvedBy.isNotBlank()) " · المعتمِد: " + c.approvedBy else ""),
                        fontSize = 11.sp, color = Color.Gray
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showNew) {
        var scope by remember { mutableStateOf("ALL") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("جلسة جرد جديدة", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تشمل المواد التي لها رصيد مسجَّل فقط.", fontSize = 12.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = scope == "ALL", onClick = { scope = "ALL" }, label = { Text("كل المواد") })
                        FilterChip(selected = scope == "A", onClick = { scope = "A" }, label = { Text("الفئة A فقط") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val withMoves = rows.filter { it.hasMoves }.map { it.item.id }
                    val ids = if (scope == "A") {
                        val a = abc.filter { it.abcClass == "A" }.map { it.itemId }.toSet()
                        withMoves.filter { it in a }
                    } else {
                        withMoves
                    }
                    vm.createCount(scope, ids, userName)
                    showNew = false
                }) { Text("إنشاء") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun CountDetail(
    vm: InventoryViewModel,
    c: InventoryCount,
    rows: List<StockRow>,
    lines: List<com.example.data.InventoryCountLine>,
    canEdit: Boolean,
    canApprove: Boolean,
    userName: String,
    onBack: () -> Unit
) {
    val nameOf = rows.associate { it.item.id to it }
    val sameUser = c.countedBy.trim().equals(userName.trim(), ignoreCase = true)

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("◀ رجوع") }
                Text(c.number + " — " + countStatusLabel(c.status), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (c.status == "SUBMITTED") {
            item {
                Text(
                    if (sameUser) "أنت العدّاد: لا يمكنك اعتماد هذا الجرد. يعتمده شخص آخر." else "راجع الفروق ثم اعتمد لترحيلها كتسويات.",
                    fontSize = 12.sp, color = if (sameUser) Color(0xFFB45309) else Color.Gray
                )
            }
        }
        items(lines.sortedBy { nameOf[it.itemId]?.item?.name ?: it.itemId }, key = { it.id }) { l ->
            val row = nameOf[l.itemId]
            val uom = row?.uom ?: "KG"
            var text by remember(l.id, l.updatedAt) { mutableStateOf(if (l.hasCount) fmtQty(InventoryMath.toDisplay(l.counted)) else "") }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((row?.item?.name ?: l.itemId) + " (" + uomLabel(uom) + ")", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (c.status == "OPEN" && canEdit) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("الكمية المعدودة", text, { text = it }, Modifier.weight(1f))
                            Button(onClick = {
                                val q = text.toDoubleOrNull()
                                if (q != null) vm.saveCountLine(l.id, q, userName)
                            }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("حفظ", fontSize = 12.sp) }
                        }
                        if (l.hasCount) Text("✔ معدود: " + fmtQty(InventoryMath.toDisplay(l.counted)), fontSize = 11.sp, color = Color(0xFF15803D))
                    } else if (c.status == "SUBMITTED" || c.status == "APPROVED") {
                        val diff = InventoryMath.toDisplay(l.counted - l.expected)
                        Text(
                            "الدفتري " + fmtQty(InventoryMath.toDisplay(l.expected)) + "   |   المعدود " + fmtQty(InventoryMath.toDisplay(l.counted)) +
                                "   |   الفرق " + (if (diff > 0) "+" else "") + fmtQty(diff),
                            fontSize = 12.sp, color = if (diff == 0.0) Color(0xFF15803D) else Color(0xFFB91C1C)
                        )
                    } else {
                        Text(if (l.hasCount) "معدود" else "لم يُعدّ", fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.status == "OPEN" && canEdit) {
                    Button(onClick = { vm.submitCount(c.id, userName) }) { Text("تسليم للاعتماد", fontSize = 13.sp) }
                }
                if (c.status == "SUBMITTED" && canApprove && !sameUser) {
                    Button(onClick = { vm.approveCount(c.id, userName) }) { Text("اعتماد وترحيل الفروق", fontSize = 13.sp) }
                }
                if ((c.status == "OPEN" || c.status == "SUBMITTED") && canApprove) {
                    TextButton(onClick = { vm.cancelCount(c.id); onBack() }) { Text("إلغاء الجلسة", fontSize = 12.sp, color = Color(0xFFB91C1C)) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// =====================================================================================
// التكلفة: فعلي مقابل معياري + قيمة المخزون بتاريخ
// =====================================================================================
@Composable
internal fun CostTab(vm: InventoryViewModel) {
    val costs by vm.orderCosts.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var dateText by remember { mutableStateOf(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())) }
    var asOf by remember { mutableStateOf<List<Triple<String, Double, Double>>?>(null) }
    var asOfLabel by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "الفعلي = كمية المواد المصروفة من الدفتر × متوسط التكلفة وقت الصرف. المعياري = كمية الأمر × سعر المادة لحظة إنشائه. " +
                    "الفرق الموجب = أغلى من المعياري.",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        item { Text("تكلفة مواد الأوامر المكتملة", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        if (costs.isEmpty()) item { Text("لا أوامر لها صرف مسجَّل بعد.", color = Color.Gray, fontSize = 13.sp) }
        items(costs, key = { it.orderNumber + "|" + it.completedAt }) { r ->
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(r.orderNumber + " — " + r.product, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "فعلي " + fmtMoney(r.actual) + "   |   معياري " + fmtMoney(r.standard) + "   |   الفرق " + (if (r.variance > 0) "+" else "") + fmtMoney(r.variance),
                    fontSize = 12.sp, color = if (r.variance > 0.005) Color(0xFFB91C1C) else if (r.variance < -0.005) Color(0xFF15803D) else Color(0xFF334155)
                )
                if (r.kg > 0.0) Text("تكلفة المواد للكجم (فعلي): " + fmtMoney(r.actualPerKg), fontSize = 11.sp, color = Color.Gray)
            }
        }

        item { Text("قيمة المخزون بتاريخ", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(dateText, { dateText = it }, singleLine = true, label = { Text("yyyy-MM-dd", fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    val ms = try {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(dateText.trim())?.time
                    } catch (e: Exception) { null }
                    if (ms == null) {
                        err = "صيغة التاريخ غير صحيحة"
                    } else {
                        err = null
                        // نهاية اليوم المختار
                        val cal = Calendar.getInstance().apply { timeInMillis = ms; add(Calendar.DAY_OF_YEAR, 1); add(Calendar.MILLISECOND, -1) }
                        asOf = vm.valuationAsOf(cal.timeInMillis)
                        asOfLabel = dateText.trim()
                    }
                }) { Text("احسب", fontSize = 12.sp) }
            }
        }
        err?.let { e -> item { Text(e, color = Color(0xFFB91C1C), fontSize = 12.sp) } }
        asOf?.let { list ->
            val total = list.sumOf { it.third }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("الإجمالي بتاريخ " + asOfLabel + ": " + fmtMoney(total), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val txt = "قيمة مخزون المواد الخام بتاريخ " + asOfLabel + "\n" + list.joinToString("\n") { "- " + it.first + ": " + fmtQty(it.second) + " = " + fmtMoney(it.third) } + "\nالإجمالي: " + fmtMoney(total)
                        clipboard.setText(AnnotatedString(txt))
                        Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                    }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("نسخ", fontSize = 12.sp) }
                }
            }
            item { Text("يشمل ما في الحجر. يعتمد على أوقات الحركات المسجَّلة.", fontSize = 11.sp, color = Color.Gray) }
            items(list, key = { "av_" + it.first }) { t ->
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp)) {
                    Text(t.first, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(fmtQty(t.second) + " = " + fmtMoney(t.third), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
