@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InventoryMath
import com.example.data.InventorySalesOrder
import com.example.data.InventorySoLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** نافذة عرض سطور تتبع (للخلف/للأمام). */
@Composable
internal fun TraceDialog(title: String, lines: List<String>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { l ->
                    Text(l, fontSize = 12.sp, fontWeight = if (l.startsWith("—")) FontWeight.Bold else FontWeight.Normal)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("إغلاق") } }
    )
}

private fun soStatusLabel(so: InventorySalesOrder, ordered: Long, delivered: Long): String = when (so.status) {
    "DRAFT" -> "مسودة"
    "CANCELLED" -> "ملغى"
    "CLOSED" -> "مغلق"
    else -> if (ordered > 0L && delivered >= ordered) "مسلَّم كاملاً" else if (delivered > 0L) "مسلَّم جزئياً" else "مؤكَّد (محجوز)"
}

private data class DeliverTarget(val line: InventorySoLine, val skuName: String, val remaining: Double)
private data class ReturnTarget(val line: InventorySoLine, val skuName: String)

// =====================================================================================
// البيع والتسليم والمرتجعات
// =====================================================================================
@Composable
internal fun SalesTab(vm: InventoryViewModel, canEdit: Boolean, canApprove: Boolean, canRelease: Boolean, userName: String) {
    val sos by vm.salesOrders.collectAsState()
    val lines by vm.soLines.collectAsState()
    val delivered by vm.deliveredByRef.collectAsState()
    val fg by vm.fgLots.collectAsState()
    val avail by vm.skuAvailability.collectAsState()

    var showNew by remember { mutableStateOf(false) }
    var deliverFor by remember { mutableStateOf<DeliverTarget?>(null) }
    var returnFor by remember { mutableStateOf<ReturnTarget?>(null) }
    var dispFor by remember { mutableStateOf<FgLotRow?>(null) }

    val skuName = fg.associate { it.lot.itemId to it.skuName }
    val returns = fg.filter { it.retCount > 0.0 }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "طلب البيع المؤكَّد يحجز كميته من الدفعات المُفرَجة فقط. التسليم بترتيب أقرب انتهاء (FEFO) ولا يتجاوز المتبقي. " +
                    "المرتجع يدخل حجر المرتجعات ولا يعود للبيع إلا بقرار فحص.",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        if (canEdit) {
            item { Button(onClick = { showNew = true }) { Text("طلب بيع جديد", fontSize = 13.sp) } }
        }
        if (avail.isNotEmpty()) {
            item { Text("المتاح للوعد (عبوات)", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            items(avail.entries.sortedBy { skuName[it.key] ?: it.key }, key = { "av_" + it.key }) { e ->
                Row(Modifier.fillMaxWidth()) {
                    Text(skuName[e.key] ?: e.key, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        "قابل للبيع " + fmtQty(e.value.first) + " · محجوز " + fmtQty(e.value.second) + " · للوعد " + fmtQty(e.value.first - e.value.second),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (returns.isNotEmpty()) {
            item { Text("حجر المرتجعات", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB45309)) }
            items(returns, key = { "ret_" + it.lot.id }) { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.skuName + " — " + r.lot.lotNo + ": " + fmtQty(r.retCount) + " عبوة", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (canRelease) {
                        OutlinedButton(onClick = { dispFor = r }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("قرار", fontSize = 12.sp) }
                    }
                }
            }
        }

        item { Text("طلبات البيع", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        if (sos.isEmpty()) item { Text("لا طلبات بيع بعد.", color = Color.Gray, fontSize = 13.sp) }
        items(sos, key = { it.id }) { so ->
            val soLines = lines.filter { it.soId == so.id }
            val orderedTotal = soLines.sumOf { it.qtyOrdered }
            val deliveredTotal = soLines.sumOf { delivered["SO:" + so.id + ":" + it.id] ?: 0L }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(so.number, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(soStatusLabel(so, orderedTotal, deliveredTotal), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0B7A6E))
                    }
                    Text(so.customer + "  ·  " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(so.createdAt)) + (if (so.createdBy.isNotBlank()) "  ·  " + so.createdBy else ""), fontSize = 11.sp, color = Color.Gray)
                    soLines.forEach { l ->
                        val got = delivered["SO:" + so.id + ":" + l.id] ?: 0L
                        val rem = InventoryMath.toDisplay((l.qtyOrdered - got).coerceAtLeast(0L))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (skuName[l.sku] ?: l.sku) + ": " + fmtQty(InventoryMath.toDisplay(got)) + " / " + fmtQty(InventoryMath.toDisplay(l.qtyOrdered)),
                                fontSize = 12.sp, modifier = Modifier.weight(1f)
                            )
                            if (canEdit && so.status == "CONFIRMED" && rem > 0.0) {
                                TextButton(onClick = { deliverFor = DeliverTarget(l, skuName[l.sku] ?: l.sku, rem) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("تسليم", fontSize = 12.sp) }
                            }
                            if (canEdit && got > 0L && (so.status == "CONFIRMED" || so.status == "CLOSED")) {
                                TextButton(onClick = { returnFor = ReturnTarget(l, skuName[l.sku] ?: l.sku) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("مرتجع", fontSize = 12.sp) }
                            }
                        }
                    }
                    if (so.notes.isNotBlank()) Text(so.notes, fontSize = 11.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (canApprove && so.status == "DRAFT") {
                            OutlinedButton(onClick = { vm.setSoStatus(so.id, "CONFIRMED") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("تأكيد وحجز", fontSize = 12.sp) }
                        }
                        if (canApprove && so.status == "CONFIRMED") {
                            OutlinedButton(onClick = { vm.setSoStatus(so.id, "CLOSED") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("إغلاق", fontSize = 12.sp) }
                        }
                        if (canApprove && (so.status == "DRAFT" || so.status == "CONFIRMED")) {
                            TextButton(onClick = { vm.setSoStatus(so.id, "CANCELLED") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("إلغاء", fontSize = 12.sp, color = Color(0xFFB91C1C))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showNew) NewSoDialog(vm, avail, skuName, userName) { showNew = false }
    deliverFor?.let { t -> DeliverDialog(vm, t, fg, canApprove, userName) { deliverFor = null } }
    returnFor?.let { t -> ReturnDialog(vm, t, fg, userName) { returnFor = null } }
    dispFor?.let { r -> DispositionDialog(vm, r, userName) { dispFor = null } }
}

@Composable
private fun NewSoDialog(vm: InventoryViewModel, avail: Map<String, Pair<Double, Double>>, skuName: Map<String, String>, userName: String, onClose: () -> Unit) {
    var customer by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<Pair<String, Int>>() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("طلب بيع جديد", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(customer, { customer = it }, label = { Text("العميل", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                lines.forEachIndexed { i, l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((skuName[l.first] ?: l.first) + ": " + l.second, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { lines.removeAt(i) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("حذف", fontSize = 11.sp) }
                    }
                }
                SimpleDropdown(
                    label = "المنتج والعبوة",
                    selectedText = sku?.let { (skuName[it] ?: it) } ?: "اختر",
                    options = avail.keys.sortedBy { skuName[it] ?: it }.map { k ->
                        k to ((skuName[k] ?: k) + " (للوعد " + fmtQty((avail[k]?.first ?: 0.0) - (avail[k]?.second ?: 0.0)) + ")")
                    },
                    onSelect = { sku = it }
                )
                NumField("عدد العبوات", count, { count = it.filter { c -> c.isDigit() } })
                OutlinedButton(onClick = {
                    val c = count.toIntOrNull() ?: 0
                    val s = sku
                    if (s == null) {
                        err = "اختر المنتج"
                    } else if (c <= 0) {
                        err = "أدخل عدداً صحيحاً"
                    } else {
                        lines.add(Pair(s, c)); sku = null; count = ""; err = null
                    }
                }) { Text("إضافة البند", fontSize = 12.sp) }
                OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (customer.isBlank()) {
                    err = "اسم العميل مطلوب"
                } else if (lines.isEmpty()) {
                    err = "أضف بنداً واحداً على الأقل"
                } else {
                    vm.createSo(customer, notes, userName, lines.toList())
                    onClose()
                }
            }) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

@Composable
private fun DeliverDialog(vm: InventoryViewModel, t: DeliverTarget, fg: List<FgLotRow>, canOverride: Boolean, userName: String, onClose: () -> Unit) {
    var count by remember { mutableStateOf(fmtQty(t.remaining)) }
    var override by remember { mutableStateOf(false) }
    var lotId by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val candidates = fg.filter { it.lot.itemId == t.line.sku && it.lot.status == "RELEASED" && it.availCount > 0.0 && (it.lot.expiryDate == null || it.lot.expiryDate > now) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("تسليم: " + t.skuName, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المتبقي في الطلب: " + fmtQty(t.remaining) + " عبوة. التسليم تلقائي بأقرب انتهاء.", fontSize = 12.sp, color = Color.Gray)
                NumField("عدد العبوات", count, { count = it.filter { c -> c.isDigit() } })
                if (canOverride && candidates.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = override, onCheckedChange = { override = it })
                        Text("تجاوز ترتيب FEFO (بسبب مكتوب)", fontSize = 12.sp)
                    }
                    if (override) {
                        SimpleDropdown(
                            label = "الدفعة",
                            selectedText = candidates.firstOrNull { it.lot.id == lotId }?.lot?.lotNo ?: "اختر",
                            options = candidates.map { it.lot.id to (it.lot.lotNo + " — " + fmtQty(it.availCount) + " عبوة") },
                            onSelect = { lotId = it }
                        )
                        OutlinedTextField(reason, { reason = it }, label = { Text("سبب التجاوز (إلزامي)", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                    }
                }
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = count.toIntOrNull() ?: 0
                if (c <= 0) {
                    err = "أدخل عدداً صحيحاً"
                } else if (override && (lotId == null || reason.isBlank())) {
                    err = "اختر الدفعة واكتب السبب"
                } else {
                    vm.deliver(t.line.id, c, if (override) lotId else null, reason, userName)
                    onClose()
                }
            }) { Text("تسليم") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

@Composable
private fun ReturnDialog(vm: InventoryViewModel, t: ReturnTarget, fg: List<FgLotRow>, userName: String, onClose: () -> Unit) {
    var lotId by remember { mutableStateOf<String?>(null) }
    var count by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val lots = fg.filter { it.lot.itemId == t.line.sku }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("مرتجع: " + t.skuName, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("يُقبل فقط ما سُلّم من الدفعة نفسها في هذا الطلب. يدخل حجر المرتجعات.", fontSize = 12.sp, color = Color.Gray)
                SimpleDropdown(
                    label = "الدفعة",
                    selectedText = lots.firstOrNull { it.lot.id == lotId }?.lot?.lotNo ?: "اختر",
                    options = lots.map { it.lot.id to it.lot.lotNo },
                    onSelect = { lotId = it }
                )
                NumField("عدد العبوات", count, { count = it.filter { c -> c.isDigit() } })
                OutlinedTextField(reason, { reason = it }, label = { Text("سبب المرتجع (إلزامي)", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = count.toIntOrNull() ?: 0
                val l = lotId
                if (l == null) {
                    err = "اختر الدفعة"
                } else if (c <= 0) {
                    err = "أدخل عدداً صحيحاً"
                } else if (reason.isBlank()) {
                    err = "السبب إلزامي"
                } else {
                    vm.returnFromCustomer(t.line.id, l, c, reason.trim(), userName)
                    onClose()
                }
            }) { Text("تسجيل") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

@Composable
private fun DispositionDialog(vm: InventoryViewModel, r: FgLotRow, userName: String, onClose: () -> Unit) {
    var count by remember { mutableStateOf(fmtQty(r.retCount)) }
    var restock by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("قرار المرتجع: " + r.lot.lotNo, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("في حجر المرتجعات: " + fmtQty(r.retCount) + " عبوة.", fontSize = 12.sp, color = Color.Gray)
                NumField("عدد العبوات", count, { count = it.filter { c -> c.isDigit() } })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !restock, onClick = { restock = false }, label = { Text("هالك") })
                    FilterChip(selected = restock, onClick = { restock = true }, label = { Text("إعادة للمخزن") })
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("نتيجة الفحص/السبب (إلزامي)", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = count.toIntOrNull() ?: 0
                if (c <= 0) {
                    err = "أدخل عدداً صحيحاً"
                } else if (reason.isBlank()) {
                    err = "السبب إلزامي"
                } else {
                    vm.dispositionReturn(r.lot.id, c, restock, reason.trim(), userName)
                    onClose()
                }
            }) { Text("تأكيد") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}
