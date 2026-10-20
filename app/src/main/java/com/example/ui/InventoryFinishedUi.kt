@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.INVENTORY_REASONS
import com.example.data.inventoryReasonLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fgStatusLabel(s: String) = when (s) {
    "QC_HOLD" -> "حجر الجودة"
    "RELEASED" -> "مُفرَج (قابل للبيع)"
    "REJECTED" -> "مرفوضة"
    "REWORK" -> "إعادة تشغيل"
    else -> s
}

private fun fgStatusColor(s: String) = when (s) {
    "QC_HOLD" -> Color(0xFFB45309)
    "RELEASED" -> Color(0xFF15803D)
    "REJECTED" -> Color(0xFFB91C1C)
    "REWORK" -> Color(0xFF7C3AED)
    else -> Color(0xFF64748B)
}

private fun fgDate(ms: Long?): String = if (ms == null) "—" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

private enum class FgAction { RELEASE, REJECT, REWORK, ADJUST }

// =====================================================================================
// المنتج التام: دفعات بحالة جودة. لا يُباع إلا المُفرَج غير المنتهي.
// =====================================================================================
@Composable
internal fun FinishedGoodsTab(vm: InventoryViewModel, canEdit: Boolean, canRelease: Boolean, userName: String) {
    val rows by vm.fgLots.collectAsState()
    var filter by remember { mutableStateOf("ACTIVE") }
    var dialogLot by remember { mutableStateOf<FgLotRow?>(null) }
    var action by remember { mutableStateOf(FgAction.RELEASE) }
    var traceLines by remember { mutableStateOf<List<String>?>(null) }

    val now = System.currentTimeMillis()
    fun expired(r: FgLotRow) = r.lot.expiryDate != null && r.lot.expiryDate < now
    fun sellable(r: FgLotRow) = r.lot.status == "RELEASED" && !expired(r) && r.availCount > 0.0

    // ملخص لكل SKU: القابل للبيع وما في الحجر
    val skuSummary = rows.groupBy { it.lot.itemId }.map { (_, ls) ->
        val first = ls.first()
        Triple(first.product + " — " + first.pack, ls.filter { sellable(it) }.sumOf { it.availCount }, ls.sumOf { it.qcCount })
    }.sortedBy { it.first }

    val shown = rows.filter { r ->
        when (filter) {
            "QC" -> r.lot.status == "QC_HOLD" || r.lot.status == "REWORK"
            "SELL" -> sellable(r)
            "ALL" -> true
            else -> r.qcCount > 0.0 || r.availCount > 0.0
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "كل إنتاج مكتمل يدخل حجر الجودة بدفعة مستقلة لكل عبوة، ولا يُباع قبل الإفراج بمرجع فحص. " +
                "يبدأ التتبع من أقدم رصيد افتتاحي مسجَّل؛ ما قبله تاريخي ولا يُستلم. الصلاحية الافتراضية 24 شهراً.",
            fontSize = 12.sp, color = Color.Gray
        )
        if (skuSummary.isNotEmpty()) {
            Text("القابل للبيع / في الحجر (عبوات)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            skuSummary.forEach { (name, sell, qc) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(name, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(fmtQty(sell) + " / " + fmtQty(qc), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == "ACTIVE", onClick = { filter = "ACTIVE" }, label = { Text("برصيد", fontSize = 12.sp) })
            FilterChip(selected = filter == "QC", onClick = { filter = "QC" }, label = { Text("حجر الجودة", fontSize = 12.sp) })
            FilterChip(selected = filter == "SELL", onClick = { filter = "SELL" }, label = { Text("قابل للبيع", fontSize = 12.sp) })
            FilterChip(selected = filter == "ALL", onClick = { filter = "ALL" }, label = { Text("الكل", fontSize = 12.sp) })
        }
        if (shown.isEmpty()) Text("لا دفعات منتج تام مطابقة.", color = Color.Gray, fontSize = 13.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.lot.id }) { r ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row {
                            Text(r.product + " — " + r.pack, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(fgStatusLabel(r.lot.status), color = fgStatusColor(r.lot.status), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text("الدفعة: " + r.lot.lotNo + "   ·   الأمر: " + r.orderNumber, fontSize = 12.sp, color = Color(0xFF334155))
                        Text(
                            "في الحجر: " + fmtQty(r.qcCount) + " عبوة   |   في المخزن: " + fmtQty(r.availCount) + " عبوة" +
                                (if (r.netKg > 0.0) "   (" + fmtQty(r.availCount * r.netKg) + " كجم)" else ""),
                            fontSize = 12.sp, color = Color(0xFF334155)
                        )
                        Text(
                            "الانتهاء: " + fgDate(r.lot.expiryDate) + (if (expired(r)) " — منتهية: لا تُباع" else "") +
                                (if (r.theoreticalKg > 0.0) "   ·   إنتاجية الأمر: " + fmtQty(Math.round(r.orderYieldPct * 10.0) / 10.0) + "%" else ""),
                            fontSize = 11.sp, color = if (expired(r)) Color(0xFFB91C1C) else Color.Gray
                        )
                        if (sellable(r)) Text("✔ قابلة للبيع", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { traceLines = vm.traceFinished(r.lot.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("تتبع", fontSize = 12.sp) }
                            if (canRelease && (r.lot.status == "QC_HOLD" || r.lot.status == "REWORK") && r.qcCount > 0.0) {
                                Button(onClick = { dialogLot = r; action = FgAction.RELEASE }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("إفراج", fontSize = 12.sp) }
                                if (r.lot.status == "QC_HOLD") {
                                    OutlinedButton(onClick = { dialogLot = r; action = FgAction.REWORK }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("إعادة تشغيل", fontSize = 12.sp) }
                                }
                                TextButton(onClick = { dialogLot = r; action = FgAction.REJECT }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("رفض", fontSize = 12.sp, color = Color(0xFFB91C1C))
                                }
                            }
                            if (canEdit && r.lot.status == "RELEASED" && r.availCount > 0.0) {
                                OutlinedButton(onClick = { dialogLot = r; action = FgAction.ADJUST }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("إخراج/عينة", fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }

    traceLines?.let { ls -> TraceDialog("تتبع الدفعة", ls) { traceLines = null } }

    dialogLot?.let { r ->
        var text by remember(r.lot.id, action) { mutableStateOf("") }
        var countText by remember(r.lot.id, action) { mutableStateOf("") }
        var reason by remember(r.lot.id, action) { mutableStateOf("") }
        var err by remember(r.lot.id, action) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { dialogLot = null },
            title = {
                Text(
                    when (action) {
                        FgAction.RELEASE -> "إفراج للبيع: "
                        FgAction.REJECT -> "رفض: "
                        FgAction.REWORK -> "إعادة تشغيل: "
                        FgAction.ADJUST -> "إخراج عبوات: "
                    } + r.lot.lotNo,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (action == FgAction.ADJUST) {
                        NumField("عدد العبوات", countText, { countText = it.filter { c -> c.isDigit() } })
                        SimpleDropdown(
                            label = "السبب (إلزامي)",
                            selectedText = if (reason.isBlank()) "اختر" else inventoryReasonLabel(reason),
                            options = INVENTORY_REASONS,
                            onSelect = { reason = it }
                        )
                    }
                    OutlinedTextField(
                        text, { text = it },
                        label = { Text(if (action == FgAction.RELEASE) "مرجع/نتيجة الفحص (إلزامي)" else if (action == FgAction.ADJUST) "ملاحظة" else "السبب (إلزامي)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (action == FgAction.ADJUST) {
                        val c = countText.toIntOrNull() ?: 0
                        if (c <= 0) {
                            err = "أدخل عدداً صحيحاً"
                        } else if (reason.isBlank()) {
                            err = "اختر السبب"
                        } else {
                            vm.adjustFinished(r.lot.id, c, reason, text, userName)
                            dialogLot = null
                        }
                    } else if (text.isBlank()) {
                        err = "هذا الحقل إلزامي"
                    } else {
                        when (action) {
                            FgAction.RELEASE -> vm.releaseFinished(r.lot.id, text, userName)
                            FgAction.REJECT -> vm.rejectFinished(r.lot.id, text, userName)
                            else -> vm.markRework(r.lot.id, text, userName)
                        }
                        dialogLot = null
                    }
                }) { Text("تأكيد") }
            },
            dismissButton = { TextButton(onClick = { dialogLot = null }) { Text("إلغاء") } }
        )
    }
}
