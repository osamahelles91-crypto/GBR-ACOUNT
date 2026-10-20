@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InventoryIds
import com.example.data.InventoryMath
import com.example.data.InventoryPoLine
import com.example.data.InventoryPurchaseOrder
import com.example.data.InventorySupplier
import com.example.data.StockLot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun parseYmd(s: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(s.trim())?.time
} catch (e: Exception) {
    null
}

private fun fmtDate(ms: Long?): String = if (ms == null) "—" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

private const val DAY_MS = 86_400_000L

private fun poStatusLabel(po: InventoryPurchaseOrder, orderedTotal: Long, receivedTotal: Long): String = when (po.status) {
    "DRAFT" -> "مسودة"
    "CANCELLED" -> "ملغى"
    "CLOSED" -> "مغلق"
    else -> if (orderedTotal > 0L && receivedTotal >= orderedTotal) "مستلم كاملاً" else if (receivedTotal > 0L) "مستلم جزئياً" else "معتمد"
}

private fun lotStatusLabel(s: String) = when (s) {
    "QUARANTINE" -> "حجر"
    "RELEASED" -> "مُفرَج عنها"
    "REJECTED" -> "مرفوضة"
    else -> s
}

private fun lotStatusColor(s: String) = when (s) {
    "QUARANTINE" -> Color(0xFFB45309)
    "RELEASED" -> Color(0xFF15803D)
    "REJECTED" -> Color(0xFFB91C1C)
    else -> Color(0xFF64748B)
}

/** رمز QR للدفعة: نص ASCII فقط (معرّف الدفعة + رقمها) كي يُقرأ على أي جهاز. */
private fun qrBitmap(text: String, size: Int = 480): android.graphics.Bitmap? = try {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            px[y * size + x] = if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
} catch (e: Exception) {
    null
}

private data class ReceiveTarget(val itemId: String, val poLineId: String, val supplierId: String, val remaining: Double, val price: Double)

// =====================================================================================
// الاستلام وأوامر الشراء
// =====================================================================================
@Composable
internal fun ReceivingTab(vm: InventoryViewModel, rows: List<StockRow>, canEdit: Boolean, canApprove: Boolean, userName: String) {
    val pos by vm.purchaseOrders.collectAsState()
    val lines by vm.poLines.collectAsState()
    val suppliers by vm.suppliers.collectAsState()
    val received by vm.receivedByRef.collectAsState()
    var showNewPo by remember { mutableStateOf(false) }
    var showDirect by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ReceiveTarget?>(null) }

    val nameOfItem = rows.associate { it.item.id to it.item.name }
    val nameOfSupplier = suppliers.associate { it.id to it.name }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "كل استلام يدخل منطقة الحجر بدفعة جديدة ولا يُحتسب متاحاً ولا يُصرف قبل الإفراج من تبويب «الدفعات».",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        if (canEdit) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showNewPo = true }) { Text("أمر شراء جديد", fontSize = 13.sp) }
                    OutlinedButton(onClick = { showDirect = true }) { Text("استلام بلا أمر شراء", fontSize = 13.sp) }
                }
            }
        }
        if (pos.isEmpty()) {
            item { Text("لا أوامر شراء بعد.", color = Color.Gray, fontSize = 13.sp) }
        }
        items(pos, key = { it.id }) { po ->
            val poLines = lines.filter { it.poId == po.id }
            val orderedTotal = poLines.sumOf { it.qtyOrdered }
            val receivedTotal = poLines.sumOf { received["PO:" + po.id + ":" + it.id] ?: 0L }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(po.number, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(poStatusLabel(po, orderedTotal, receivedTotal), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF0B7A6E))
                    }
                    Text(
                        (nameOfSupplier[po.supplierId] ?: "مورد محذوف") + "  ·  " + fmtDateTime(po.createdAt) +
                            (if (po.createdBy.isNotBlank()) "  ·  " + po.createdBy else ""),
                        fontSize = 11.sp, color = Color.Gray
                    )
                    poLines.forEach { l ->
                        val got = received["PO:" + po.id + ":" + l.id] ?: 0L
                        val remaining = InventoryMath.toDisplay((l.qtyOrdered - got).coerceAtLeast(0L))
                        val u = rows.firstOrNull { it.item.id == l.itemId }?.uom ?: "KG"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (nameOfItem[l.itemId] ?: l.itemId) + ": " + fmtQty(InventoryMath.toDisplay(got)) + " / " +
                                    fmtQty(InventoryMath.toDisplay(l.qtyOrdered)) + " " + uomLabel(u) +
                                    (if (l.unitPrice > 0.0) "  @ " + fmtMoney(l.unitPrice) else ""),
                                fontSize = 12.sp, modifier = Modifier.weight(1f)
                            )
                            if (canEdit && po.status == "APPROVED") {
                                TextButton(
                                    onClick = { target = ReceiveTarget(l.itemId, l.id, po.supplierId, remaining, l.unitPrice) },
                                    contentPadding = PaddingValues(horizontal = 6.dp)
                                ) { Text("استلام", fontSize = 12.sp) }
                            }
                        }
                    }
                    if (po.notes.isNotBlank()) Text(po.notes, fontSize = 11.sp, color = Color.Gray)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (canApprove && po.status == "DRAFT") {
                            OutlinedButton(onClick = { vm.setPoStatus(po.id, "APPROVED") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("اعتماد", fontSize = 12.sp) }
                        }
                        if (canApprove && po.status == "APPROVED") {
                            OutlinedButton(onClick = { vm.setPoStatus(po.id, "CLOSED") }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("إغلاق", fontSize = 12.sp) }
                        }
                        if (canApprove && (po.status == "DRAFT" || po.status == "APPROVED")) {
                            TextButton(onClick = { vm.setPoStatus(po.id, "CANCELLED") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("إلغاء", fontSize = 12.sp, color = Color(0xFFB91C1C))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showNewPo) NewPoDialog(vm, rows, suppliers, userName) { showNewPo = false }
    if (showDirect) ReceiveDialog(vm, rows, null, suppliers, userName) { showDirect = false }
    target?.let { t -> ReceiveDialog(vm, rows, t, suppliers, userName) { target = null } }
}

@Composable
private fun NewPoDialog(vm: InventoryViewModel, rows: List<StockRow>, suppliers: List<InventorySupplier>, userName: String, onClose: () -> Unit) {
    var supplierId by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf("") }
    var itemId by remember { mutableStateOf<String?>(null) }
    var qty by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val lines = remember { mutableStateListOf<Triple<String, Double, Double>>() }
    val usable = suppliers.filter { it.isActive && it.isApproved }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("أمر شراء جديد", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (usable.isEmpty()) {
                    Text("لا يوجد مورد معتمد. أضف مورداً من تبويب «الموردون والمواقع» أولاً.", fontSize = 12.sp, color = Color(0xFFB91C1C))
                }
                SimpleDropdown(
                    label = "المورد (معتمد)",
                    selectedText = usable.firstOrNull { it.id == supplierId }?.name ?: "اختر",
                    options = usable.map { it.id to it.name },
                    onSelect = { supplierId = it }
                )
                Text("بنود الأمر", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                lines.forEachIndexed { i, l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val nm = rows.firstOrNull { it.item.id == l.first }?.item?.name ?: l.first
                        Text(nm + ": " + fmtQty(l.second) + (if (l.third > 0.0) " @ " + fmtMoney(l.third) else ""), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { lines.removeAt(i) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("حذف", fontSize = 11.sp) }
                    }
                }
                SimpleDropdown(
                    label = "المادة",
                    selectedText = rows.firstOrNull { it.item.id == itemId }?.item?.name ?: "اختر",
                    options = rows.map { it.item.id to it.item.name },
                    onSelect = { itemId = it }
                )
                NumField("الكمية (بوحدة الأساس)", qty, { qty = it })
                NumField("سعر الوحدة (اختياري)", price, { price = it })
                OutlinedButton(onClick = {
                    val q = qty.toDoubleOrNull() ?: 0.0
                    val iid = itemId
                    if (iid == null) {
                        err = "اختر المادة"
                    } else if (q <= 0.0) {
                        err = "أدخل كمية صحيحة"
                    } else {
                        lines.add(Triple(iid, q, price.toDoubleOrNull() ?: 0.0))
                        itemId = null; qty = ""; price = ""; err = null
                    }
                }) { Text("إضافة البند", fontSize = 12.sp) }
                OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val sid = supplierId
                if (sid == null) {
                    err = "اختر المورد"
                } else if (lines.isEmpty()) {
                    err = "أضف بنداً واحداً على الأقل"
                } else {
                    vm.createPo(sid, notes, userName, lines.toList())
                    onClose()
                }
            }) { Text("إنشاء") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

@Composable
private fun ReceiveDialog(
    vm: InventoryViewModel,
    rows: List<StockRow>,
    target: ReceiveTarget?,
    suppliers: List<InventorySupplier>,
    userName: String,
    onClose: () -> Unit
) {
    val conversions by vm.conversions.collectAsState()
    var itemId by remember { mutableStateOf(target?.itemId) }
    var supplierId by remember { mutableStateOf(target?.supplierId) }
    var unitName by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf(if (target != null && target.remaining > 0.0) fmtQty(target.remaining) else "") }
    var cost by remember { mutableStateOf(if (target != null && target.price > 0.0) fmtQty(target.price) else "") }
    var lotNo by remember { mutableStateOf("") }
    var mfg by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    val row = rows.firstOrNull { it.item.id == itemId }
    val uom = row?.uom ?: "KG"
    val itemConv = conversions.filter { it.itemId == itemId }
    val factor = itemConv.firstOrNull { it.name == unitName }?.factorToBase ?: 1.0
    val usable = suppliers.filter { it.isActive && it.isApproved }

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Text(if (target != null) "استلام على أمر شراء" else "استلام بلا أمر شراء", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("يدخل الاستلام منطقة الحجر بدفعة جديدة حتى الإفراج.", fontSize = 12.sp, color = Color.Gray)
                if (target == null) {
                    SimpleDropdown(
                        label = "المادة",
                        selectedText = row?.item?.name ?: "اختر",
                        options = rows.map { it.item.id to it.item.name },
                        onSelect = { itemId = it; unitName = "" }
                    )
                    SimpleDropdown<String?>(
                        label = "المورد",
                        selectedText = usable.firstOrNull { it.id == supplierId }?.name ?: "بلا",
                        options = listOf<Pair<String?, String>>(null to "بلا") + usable.map { it.id to it.name },
                        onSelect = { supplierId = it }
                    )
                } else {
                    Text(row?.item?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                if (itemConv.isNotEmpty()) {
                    SimpleDropdown(
                        label = "الوحدة",
                        selectedText = if (unitName.isBlank()) uomLabel(uom) else unitName,
                        options = listOf("" to uomLabel(uom)) + itemConv.map { it.name to (it.name + " (= " + fmtQty(it.factorToBase) + " " + uomLabel(uom) + ")") },
                        onSelect = { unitName = it }
                    )
                }
                NumField("الكمية (" + (if (unitName.isBlank()) uomLabel(uom) else unitName) + ")", qty, { qty = it })
                NumField("تكلفة " + uomLabel(uom) + " الواحد", cost, { cost = it })
                OutlinedTextField(lotNo, { lotNo = it }, label = { Text("رقم دفعة المورد", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mfg, { mfg = it }, label = { Text("تاريخ الإنتاج yyyy-MM-dd (اختياري)", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(expiry, { expiry = it }, label = { Text("تاريخ الانتهاء yyyy-MM-dd (اختياري)", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val iid = itemId
                val q = qty.toDoubleOrNull() ?: 0.0
                var e: String? = null
                var exp: Long? = null
                var mf: Long? = null
                if (iid == null) {
                    e = "اختر المادة"
                } else if (q <= 0.0) {
                    e = "أدخل كمية صحيحة"
                }
                if (e == null && expiry.isNotBlank()) {
                    exp = parseYmd(expiry)
                    if (exp == null) e = "صيغة تاريخ الانتهاء غير صحيحة"
                }
                if (e == null && mfg.isNotBlank()) {
                    mf = parseYmd(mfg)
                    if (mf == null) e = "صيغة تاريخ الإنتاج غير صحيحة"
                }
                if (e != null || iid == null) {
                    err = e
                } else {
                    vm.receive(iid, uom, q * factor, target?.poLineId, supplierId, lotNo, mf, exp, cost.toDoubleOrNull(), userName)
                    onClose()
                }
            }) { Text("استلام") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

// =====================================================================================
// الدفعات وFEFO وتنبيه الصلاحية
// =====================================================================================
@Composable
internal fun LotsTab(vm: InventoryViewModel, rows: List<StockRow>, canRelease: Boolean, userName: String) {
    val lots by vm.lots.collectAsState()
    val bal by vm.lotBalances.collectAsState()
    val context = LocalContext.current
    var filter by remember { mutableStateOf("ACTIVE") }
    var qrFor by remember { mutableStateOf<StockLot?>(null) }
    var rejectFor by remember { mutableStateOf<StockLot?>(null) }

    val now = System.currentTimeMillis()
    val nameOf = rows.associate { it.item.id to it.item.name }
    val balMap = bal.associate { (it.lotId + "|" + it.locId) to it.qty }
    fun mainQty(l: StockLot): Long = balMap[l.id + "|" + InventoryIds.LOC_MAIN] ?: 0L
    fun quarQty(l: StockLot): Long = balMap[l.id + "|" + InventoryIds.LOC_QUAR] ?: 0L
    fun totalQty(l: StockLot): Long = bal.filter { it.lotId == l.id }.sumOf { it.qty }
    fun isExpired(l: StockLot): Boolean = l.expiryDate != null && l.expiryDate < now
    fun isNear(l: StockLot): Boolean = l.expiryDate != null && l.expiryDate >= now && l.expiryDate < now + 30 * DAY_MS

    val sorted = lots.sortedWith(compareBy<StockLot> { it.expiryDate ?: Long.MAX_VALUE }.thenBy { it.receivedAt })
    // الأول في الصرف لكل مادة: أقدم دفعة مُفرَج عنها غير منتهية ولها رصيد في المخزن الرئيسي
    val fefoFirst = HashSet<String>()
    val seenItems = HashSet<String>()
    for (l in sorted) {
        if (l.status == "RELEASED" && !isExpired(l) && mainQty(l) > 0L && seenItems.add(l.itemId)) fefoFirst.add(l.id)
    }
    val expiredWithStock = sorted.count { isExpired(it) && totalQty(it) > 0L }
    val nearWithStock = sorted.count { isNear(it) && totalQty(it) > 0L }
    val inQuarantine = sorted.count { it.status == "QUARANTINE" && quarQty(it) > 0L }

    val shown = sorted.filter { l ->
        when (filter) {
            "QUAR" -> l.status == "QUARANTINE"
            "REJ" -> l.status == "REJECTED"
            "EXP" -> (isExpired(l) || isNear(l)) && totalQty(l) > 0L
            "ALL" -> true
            else -> totalQty(l) > 0L
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (expiredWithStock > 0 || nearWithStock > 0 || inQuarantine > 0) {
            Text(
                (if (expiredWithStock > 0) "⚠ منتهية بها رصيد: $expiredWithStock   " else "") +
                    (if (nearWithStock > 0) "⏳ تنتهي خلال 30 يوماً: $nearWithStock   " else "") +
                    (if (inQuarantine > 0) "🔒 بانتظار الإفراج: $inQuarantine" else ""),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == "ACTIVE", onClick = { filter = "ACTIVE" }, label = { Text("برصيد", fontSize = 12.sp) })
            FilterChip(selected = filter == "QUAR", onClick = { filter = "QUAR" }, label = { Text("حجر", fontSize = 12.sp) })
            FilterChip(selected = filter == "EXP", onClick = { filter = "EXP" }, label = { Text("صلاحية", fontSize = 12.sp) })
            FilterChip(selected = filter == "REJ", onClick = { filter = "REJ" }, label = { Text("مرفوضة", fontSize = 12.sp) })
            FilterChip(selected = filter == "ALL", onClick = { filter = "ALL" }, label = { Text("الكل", fontSize = 12.sp) })
        }
        Text("الترتيب حسب أقرب انتهاء (FEFO). الدفعة الموسومة «الأولى بالصرف» هي المقترحة.", fontSize = 11.sp, color = Color.Gray)
        if (shown.isEmpty()) Text("لا دفعات مطابقة.", color = Color.Gray, fontSize = 13.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.id }) { l ->
                val uom = rows.firstOrNull { it.item.id == l.itemId }?.uom ?: "KG"
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (nameOf[l.itemId] ?: l.itemId) + " — " + l.lotNo,
                                fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(lotStatusLabel(l.status), color = lotStatusColor(l.status), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(
                            "المتاح: " + fmtQty(InventoryMath.toDisplay(mainQty(l))) + "   |   في الحجر: " + fmtQty(InventoryMath.toDisplay(quarQty(l))) + " " + uomLabel(uom),
                            fontSize = 12.sp, color = Color(0xFF334155)
                        )
                        val expColor = if (isExpired(l)) Color(0xFFB91C1C) else if (isNear(l)) Color(0xFFB45309) else Color.Gray
                        Text(
                            "الانتهاء: " + fmtDate(l.expiryDate) + (if (isExpired(l)) " — منتهية: لا تُصرف" else if (isNear(l)) " — قريبة الانتهاء" else "") +
                                (if (l.supplierLotNo.isNotBlank()) "   ·   دفعة المورد: " + l.supplierLotNo else ""),
                            fontSize = 11.sp, color = expColor
                        )
                        if (l.id in fefoFirst) Text("◀ الأولى بالصرف (FEFO)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { qrFor = l }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("ملصق QR", fontSize = 12.sp) }
                            if (canRelease && l.status == "QUARANTINE" && quarQty(l) > 0L) {
                                Button(onClick = { vm.releaseLot(l.id, userName) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("إفراج", fontSize = 12.sp) }
                                TextButton(onClick = { rejectFor = l }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("رفض", fontSize = 12.sp, color = Color(0xFFB91C1C))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    qrFor?.let { l ->
        val clipboard = LocalClipboardManager.current
        val code = "GBRLOT|" + l.id + "|" + l.lotNo
        val bmp = remember(l.id) { qrBitmap(code) }
        val labelText = (nameOf[l.itemId] ?: l.itemId) + "\nدفعة: " + l.lotNo + "\nانتهاء: " + fmtDate(l.expiryDate) +
            (if (l.supplierLotNo.isNotBlank()) "\nدفعة المورد: " + l.supplierLotNo else "") + "\nالحالة: " + lotStatusLabel(l.status)
        AlertDialog(
            onDismissRequest = { qrFor = null },
            title = { Text("ملصق الدفعة", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    if (bmp != null) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(220.dp))
                    } else {
                        Text("تعذّر توليد الرمز", color = Color(0xFFB91C1C), fontSize = 12.sp)
                    }
                    Text(labelText, fontSize = 13.sp)
                    Text(code, fontSize = 10.sp, color = Color.Gray)
                }
            },
            confirmButton = {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(labelText + "\n" + code))
                    Toast.makeText(context, "تم نسخ بيانات الملصق", Toast.LENGTH_SHORT).show()
                }) { Text("نسخ") }
            },
            dismissButton = { TextButton(onClick = { qrFor = null }) { Text("إغلاق") } }
        )
    }

    rejectFor?.let { l ->
        var reason by remember(l.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rejectFor = null },
            title = { Text("رفض الدفعة " + l.lotNo, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تخرج الدفعة من المخزون الفعلي. السبب إلزامي ويُحفظ في الحركة.", fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(reason, { reason = it }, label = { Text("السبب") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (reason.isNotBlank()) {
                        vm.rejectLot(l.id, reason.trim(), userName)
                        rejectFor = null
                    }
                }) { Text("رفض") }
            },
            dismissButton = { TextButton(onClick = { rejectFor = null }) { Text("إلغاء") } }
        )
    }
}
