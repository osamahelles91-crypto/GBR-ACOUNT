@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.INVENTORY_CATEGORIES
import com.example.data.INVENTORY_REASONS
import com.example.data.InventoryMath
import com.example.data.InventoryTypes
import com.example.data.LedgerRow
import com.example.data.inventoryCategoryLabel
import com.example.data.inventoryReasonLabel
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- مساعدات عرض (أرقام لاتينية) ----------
internal fun fmtQty(x: Double): String {
    val s = String.format(Locale.US, "%.3f", x).trimEnd('0').trimEnd('.')
    return if (s.isEmpty() || s == "-") "0" else s
}

internal fun fmtMoney(x: Double): String = String.format(Locale.US, "%,.2f", x)

internal fun fmtDateTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

internal fun uomLabel(uom: String) = if (uom == "L") "لتر" else "كجم"

internal fun statusLabel(s: String) = when (s) {
    "NO_BALANCE" -> "بلا رصيد"
    "OK" -> "متوفر"
    "REORDER" -> "إعادة طلب"
    "LOW" -> "تحت الحد"
    "NEGATIVE" -> "رصيد سالب"
    else -> s
}

internal fun statusColor(s: String) = when (s) {
    "OK" -> Color(0xFF15803D)
    "REORDER" -> Color(0xFFB45309)
    "LOW", "NEGATIVE" -> Color(0xFFB91C1C)
    else -> Color(0xFF64748B)
}

internal fun moveTypeLabel(t: String) = when (t) {
    InventoryTypes.OPENING -> "رصيد افتتاحي"
    InventoryTypes.ADJUSTMENT -> "تسوية"
    InventoryTypes.REVERSAL -> "عكس حركة"
    InventoryTypes.RECEIPT -> "استلام (حجر)"
    InventoryTypes.RELEASE -> "إفراج دفعة"
    InventoryTypes.REJECT -> "رفض دفعة"
    InventoryTypes.ISSUE -> "صرف للإنتاج"
    InventoryTypes.FG_RECEIPT -> "استلام منتج تام (حجر الجودة)"
    InventoryTypes.FG_RELEASE -> "إفراج منتج تام"
    InventoryTypes.FG_REJECT -> "رفض منتج تام"
    InventoryTypes.FG_ADJUST -> "إخراج منتج تام"
    InventoryTypes.FG_DELIVERY -> "تسليم لعميل"
    InventoryTypes.FG_RETURN -> "مرتجع من عميل"
    InventoryTypes.FG_RETURN_RESTOCK -> "مرتجع: إعادة للمخزن"
    InventoryTypes.FG_RETURN_SCRAP -> "مرتجع: هالك"
    else -> t
}

@Composable
internal fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.replace(',', '.').filter { it.isDigit() || it == '.' || it == '-' }) },
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun <T> SimpleDropdown(
    label: String,
    selectedText: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
            Text("$label: $selectedText", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (k, v) ->
                DropdownMenuItem(text = { Text(v) }, onClick = { onSelect(k); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryPanel(viewModel: GbrViewModel) {
    val vm: InventoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val context = LocalContext.current
    val user by viewModel.currentUser.collectAsState()
    val role = user?.role
    val isWarehouseRole = role == "المخزن"
    val canEdit = role == "مدير النظام" || role == "المخزن" || role == "مشرف الإنتاج" || user?.isSupervisor == true
    val canApprove = role == "مدير النظام" || role == "مشرف الإنتاج" || user?.isSupervisor == true
    val canRelease = canApprove || role == "المختبر"
    val userName = user?.fullName?.takeIf { it.isNotBlank() } ?: user?.name ?: ""

    val rows by vm.stockRows.collectAsState()
    val message by vm.message.collectAsState()
    var tab by remember { mutableStateOf(InventoryNav.consume() ?: 0) }
    var selectedItemId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.message.value = null
        }
    }

    BackHandler(enabled = !isWarehouseRole) { viewModel.showSegment(null) }

    val titles = listOf("الأرصدة", "بطاقة الصنف", "الاستلام والشراء", "الدفعات وFEFO", "الإنتاج والتوفر", "المنتج التام", "البيع والتسليم", "الجرد الدوري", "التكلفة", "إعداد المواد", "الموردون والمواقع", "التقييم وABC", "التخطيط", "المؤشرات", "الكتالوج")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("المخزون", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("دفتر حركات لا يُعدَّل — الرصيد يُحسب من الحركات", fontSize = 11.sp, color = Color.LightGray)
                    }
                },
                navigationIcon = {
                    if (!isWarehouseRole) {
                        IconButton(onClick = { viewModel.showSegment(null) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GBRBlueMain)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().background(Color(0xFFF8FAFC)).padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> StockTab(vm, rows, canEdit, userName) { id -> selectedItemId = id; tab = 1 }
                    1 -> LedgerTab(vm, rows, selectedItemId, canEdit, userName) { selectedItemId = it }
                    2 -> ReceivingTab(vm, rows, canEdit, canApprove, userName)
                    3 -> LotsTab(vm, rows, canRelease, userName)
                    4 -> ProductionLinkTab(vm, canEdit, userName)
                    5 -> FinishedGoodsTab(vm, canEdit, canRelease, userName)
                    6 -> SalesTab(vm, canEdit, canApprove, canRelease, userName)
                    7 -> CountTab(vm, rows, canEdit, canApprove, userName)
                    8 -> CostTab(vm)
                    9 -> InventorySetupTab(vm, rows, canEdit)
                    10 -> InventoryPartiesTab(vm, canEdit)
                    11 -> ReportsTab(vm, rows)
                    12 -> PlanningTab(vm, canEdit)
                    13 -> KpiTab(vm)
                    else -> com.example.MaterialsPanel(viewModel = viewModel)
                }
            }
        }
    }
}

// =====================================================================================
// 1) الأرصدة
// =====================================================================================
@Composable
private fun StockTab(
    vm: InventoryViewModel,
    rows: List<StockRow>,
    canEdit: Boolean,
    userName: String,
    onOpenCard: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }
    var openingFor by remember { mutableStateOf<StockRow?>(null) }
    var adjustFor by remember { mutableStateOf<StockRow?>(null) }

    val totalValue = rows.filter { it.hasMoves }.sumOf { it.value }
    val lowCount = rows.count { it.status == "LOW" || it.status == "REORDER" || it.status == "NEGATIVE" }
    val noBalance = rows.count { it.status == "NO_BALANCE" }

    val shown = rows.filter { r ->
        (search.isBlank() || r.item.name.contains(search, true) || r.item.productionName.contains(search, true)) &&
            when (filter) {
                "LOW" -> r.status == "LOW" || r.status == "REORDER" || r.status == "NEGATIVE"
                "NONE" -> r.status == "NO_BALANCE"
                else -> true
            }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryBox("قيمة المخزون", fmtMoney(totalValue), Modifier.weight(1f))
            SummaryBox("تحت الحد/إعادة طلب", "$lowCount", Modifier.weight(1f), if (lowCount > 0) Color(0xFFB91C1C) else null)
            SummaryBox("بلا رصيد مسجَّل", "$noBalance", Modifier.weight(1f))
        }
        OutlinedTextField(
            value = search, onValueChange = { search = it }, singleLine = true,
            label = { Text("بحث عن مادة", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = filter == "ALL", onClick = { filter = "ALL" }, label = { Text("الكل") })
            FilterChip(selected = filter == "LOW", onClick = { filter = "LOW" }, label = { Text("تحت الحد") })
            FilterChip(selected = filter == "NONE", onClick = { filter = "NONE" }, label = { Text("بلا رصيد") })
        }
        if (shown.isEmpty()) {
            Text("لا توجد مواد مطابقة", color = Color.Gray, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.item.id }) { r ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenCard(r.item.id) }
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(statusLabel(r.status), color = statusColor(r.status), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        if (r.hasMoves) {
                            Text(
                                "المتاح: ${fmtQty(r.qtyDisplay)} ${uomLabel(r.uom)}   |   متوسط التكلفة: ${fmtMoney(r.avgCost)}   |   القيمة: ${fmtMoney(r.value)}",
                                fontSize = 12.sp, color = Color(0xFF334155)
                            )
                            if (r.quarantineStorage > 0L) {
                                Text("🔒 في الحجر (غير متاح): ${fmtQty(r.quarantineDisplay)} ${uomLabel(r.uom)}", fontSize = 11.sp, color = Color(0xFFB45309))
                            }
                        } else {
                            Text("لم يُسجَّل رصيد افتتاحي بعد", fontSize = 12.sp, color = Color.Gray)
                        }
                        if (canEdit) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!r.hasMoves) {
                                    OutlinedButton(onClick = { openingFor = r }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                        Text("رصيد افتتاحي", fontSize = 12.sp)
                                    }
                                } else {
                                    OutlinedButton(onClick = { adjustFor = r }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                        Text("تسوية", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    openingFor?.let { r ->
        OpeningDialog(vm, r, userName) { openingFor = null }
    }
    adjustFor?.let { r ->
        AdjustDialog(vm, r, userName) { adjustFor = null }
    }
}

@Composable
private fun SummaryBox(title: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 17.sp, color = valueColor ?: GBRBlueMain, maxLines = 1)
            Text(title, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OpeningDialog(vm: InventoryViewModel, r: StockRow, userName: String, onClose: () -> Unit) {
    val conversions by vm.conversions.collectAsState()
    val itemConv = conversions.filter { it.itemId == r.item.id }
    var qty by remember { mutableStateOf("") }
    var unitName by remember { mutableStateOf("") }          // "" = وحدة الأساس
    var cost by remember { mutableStateOf(if (r.item.price > 0) fmtQty(r.item.price) else "") }
    var expiry by remember { mutableStateOf("") }            // yyyy-MM-dd اختياري
    var err by remember { mutableStateOf<String?>(null) }
    val factor = itemConv.firstOrNull { it.name == unitName }?.factorToBase ?: 1.0

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("رصيد افتتاحي: ${r.item.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("يُسجَّل مرة واحدة فقط من جرد فعلي. التصحيح بعدها بالتسوية بسبب مكتوب.", fontSize = 12.sp, color = Color.Gray)
                if (itemConv.isNotEmpty()) {
                    SimpleDropdown(
                        label = "الوحدة",
                        selectedText = if (unitName.isBlank()) uomLabel(r.uom) else unitName,
                        options = listOf("" to uomLabel(r.uom)) + itemConv.map { it.name to "${it.name} (= ${fmtQty(it.factorToBase)} ${uomLabel(r.uom)})" },
                        onSelect = { unitName = it }
                    )
                }
                NumField("الكمية (${if (unitName.isBlank()) uomLabel(r.uom) else unitName})", qty, { qty = it })
                NumField("تكلفة ${uomLabel(r.uom)} الواحد", cost, { cost = it })
                OutlinedTextField(
                    value = expiry, onValueChange = { expiry = it }, singleLine = true,
                    label = { Text("تاريخ الانتهاء (اختياري) yyyy-MM-dd", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val q = qty.toDoubleOrNull() ?: 0.0
                val c = cost.toDoubleOrNull()
                var exp: Long? = null
                var e: String? = null
                if (q <= 0.0) {
                    e = "أدخل كمية صحيحة"
                } else if (expiry.isNotBlank()) {
                    exp = try {
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(expiry.trim())?.time
                    } catch (ex: Exception) { null }
                    if (exp == null) e = "صيغة تاريخ الانتهاء غير صحيحة"
                }
                if (e != null) {
                    err = e
                } else {
                    vm.openingBalance(r.item.id, r.uom, q * factor, c, exp, userName)
                    onClose()
                }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

@Composable
private fun AdjustDialog(vm: InventoryViewModel, r: StockRow, userName: String, onClose: () -> Unit) {
    var increase by remember { mutableStateOf(false) }
    var qty by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("تسوية: ${r.item.name}", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الرصيد الحالي: ${fmtQty(r.qtyDisplay)} ${uomLabel(r.uom)}", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !increase, onClick = { increase = false }, label = { Text("نقص") })
                    FilterChip(selected = increase, onClick = { increase = true }, label = { Text("زيادة") })
                }
                NumField("الكمية (${uomLabel(r.uom)})", qty, { qty = it })
                SimpleDropdown(
                    label = "السبب (إلزامي)",
                    selectedText = if (reason.isBlank()) "اختر" else inventoryReasonLabel(reason),
                    options = INVENTORY_REASONS,
                    onSelect = { reason = it }
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, label = { Text("ملاحظة", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                err?.let { Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val q = qty.toDoubleOrNull() ?: 0.0
                if (q <= 0.0) {
                    err = "أدخل كمية صحيحة"
                } else if (reason.isBlank()) {
                    err = "اختر سبب التسوية"
                } else {
                    vm.adjust(r.item.id, r.uom, if (increase) q else -q, reason, note, userName)
                    onClose()
                }
            }) { Text("تسجيل") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إلغاء") } }
    )
}

// =====================================================================================
// 2) بطاقة الصنف (دفتر الحركات مع الرصيد الجاري)
// =====================================================================================
@Composable
private fun LedgerTab(
    vm: InventoryViewModel,
    rows: List<StockRow>,
    selectedItemId: String?,
    canEdit: Boolean,
    userName: String,
    onSelect: (String) -> Unit
) {
    val sel = rows.firstOrNull { it.item.id == selectedItemId }
    var reverseFor by remember { mutableStateOf<LedgerRow?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SimpleDropdown(
            label = "المادة",
            selectedText = sel?.item?.name ?: "اختر مادة",
            options = rows.map { it.item.id to it.item.name },
            onSelect = onSelect
        )
        if (sel == null) {
            Text("اختر مادة لعرض بطاقة الصنف (كل حركاتها مرتبة مع الرصيد الجاري).", color = Color.Gray, fontSize = 13.sp)
            return@Column
        }
        val ledger by remember(sel.item.id, sel.item.price) { vm.ledgerFor(sel.item.id, sel.item.price) }.collectAsState()
        Text(
            "الرصيد: ${fmtQty(sel.qtyDisplay)} ${uomLabel(sel.uom)}   |   المتوسط: ${fmtMoney(sel.avgCost)}   |   القيمة: ${fmtMoney(sel.value)}",
            fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GBRBlueMain
        )
        if (ledger.isEmpty()) {
            Text("لا توجد حركات لهذه المادة بعد.", color = Color.Gray, fontSize = 13.sp)
        }
        val reversed = ledger.mapNotNull { it.move.reversesMoveId }.toSet()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(ledger.asReversed(), key = { it.move.moveId }) { row ->
                val m = row.move
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row {
                            Text(moveTypeLabel(m.type), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            val s = row.signedQty
                            Text(
                                (if (s > 0) "+" else if (s < 0) "-" else "") + fmtQty(InventoryMath.toDisplay(kotlin.math.abs(if (s != 0L) s else m.qtyBase))) + " " + uomLabel(m.uom),
                                color = if (s > 0) Color(0xFF15803D) else if (s < 0) Color(0xFFB91C1C) else Color.Gray,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                        Text(
                            "الرصيد بعد الحركة: ${fmtQty(InventoryMath.toDisplay(row.runningQty))}   |   المتوسط: ${fmtMoney(row.avgCostAfter)}   |   القيمة: ${fmtMoney(row.runningValue)}",
                            fontSize = 11.sp, color = Color(0xFF334155)
                        )
                        val extra = buildString {
                            append(fmtDateTime(m.serverCreatedAt ?: m.clientCreatedAt))
                            if (m.userId.isNotBlank()) append(" · ").append(m.userId)
                            if (m.reasonCode.isNotBlank() && m.type != InventoryTypes.OPENING) append(" · ").append(inventoryReasonLabel(m.reasonCode))
                            if (m.refDoc.isNotBlank() && m.refDoc != "opening") append(" · ").append(m.refDoc)
                            if (!m.uploaded) append(" · ⏳ لم تُرفع بعد")
                        }
                        Text(extra, fontSize = 11.sp, color = Color.Gray)
                        if (canEdit && m.type != InventoryTypes.REVERSAL && m.moveId !in reversed) {
                            TextButton(onClick = { reverseFor = row }, contentPadding = PaddingValues(0.dp)) {
                                Text("عكس هذه الحركة", fontSize = 12.sp)
                            }
                        } else if (m.moveId in reversed) {
                            Text("مُعكوسة", fontSize = 11.sp, color = Color(0xFFB45309))
                        }
                    }
                }
            }
        }
    }

    reverseFor?.let { row ->
        var reason by remember(row.move.moveId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reverseFor = null },
            title = { Text("عكس حركة", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("لا تُحذف الحركة ولا تُعدَّل؛ تُسجَّل حركة عكسية تلغي أثرها. السبب إلزامي.", fontSize = 12.sp, color = Color.Gray)
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("السبب") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (reason.isNotBlank()) {
                        vm.reverse(row.move.moveId, reason.trim(), userName)
                        reverseFor = null
                    }
                }) { Text("عكس") }
            },
            dismissButton = { TextButton(onClick = { reverseFor = null }) { Text("إلغاء") } }
        )
    }
}

// =====================================================================================
// 5) التقييم وABC وإعادة الطلب
// =====================================================================================
@Composable
private fun ReportsTab(vm: InventoryViewModel, rows: List<StockRow>) {
    val abc by vm.abcRows.collectAsState()
    val abcByConsumption by vm.abcIsConsumptionBased.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val withBalance = rows.filter { it.hasMoves }
    val total = withBalance.sumOf { it.value }
    val byCategory = withBalance.groupBy { it.profile?.category ?: "OTHER" }
        .mapValues { e -> e.value.sumOf { it.value } }.entries.sortedByDescending { it.value }
    val reorder = rows.filter { it.status == "LOW" || it.status == "REORDER" || it.status == "NEGATIVE" }

    fun suggested(r: StockRow): Double {
        val p = r.profile ?: return 0.0
        return if (p.maxLevel > 0.0) kotlin.math.max(p.maxLevel - r.qtyDisplay, p.moq) else p.moq
    }

    val reportText = buildString {
        appendLine("تقرير المخزون — GBR Pro")
        appendLine("إجمالي قيمة المخزون: ${fmtMoney(total)}")
        appendLine()
        appendLine("التقييم حسب الفئة:")
        byCategory.forEach { appendLine("- ${inventoryCategoryLabel(it.key)}: ${fmtMoney(it.value)}") }
        appendLine()
        appendLine("أرصدة المواد:")
        withBalance.forEach { appendLine("- ${it.item.name}: ${fmtQty(it.qtyDisplay)} ${uomLabel(it.uom)} × ${fmtMoney(it.avgCost)} = ${fmtMoney(it.value)}") }
        if (reorder.isNotEmpty()) {
            appendLine()
            appendLine("إعادة الطلب:")
            reorder.forEach { appendLine("- ${it.item.name}: ${fmtQty(it.qtyDisplay)} (${statusLabel(it.status)}) — مقترح: ${fmtQty(suggested(it))}") }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SummaryBox("إجمالي قيمة المخزون", fmtMoney(total), Modifier.weight(1f))
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(reportText))
                    Toast.makeText(context, "تم نسخ التقرير", Toast.LENGTH_SHORT).show()
                }) { Text("نسخ التقرير", fontSize = 12.sp) }
            }
        }
        item { Text("التقييم حسب الفئة", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        if (byCategory.isEmpty()) item { Text("لا أرصدة مسجَّلة بعد.", color = Color.Gray, fontSize = 13.sp) }
        items(byCategory) { e ->
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Text(inventoryCategoryLabel(e.key), modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(fmtMoney(e.value), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        item { Text("إعادة الطلب (تحت الحد أو عند نقطة الطلب)", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        if (reorder.isEmpty()) item { Text("لا مواد تحتاج إعادة طلب حسب الحدود المُعرَّفة.", color = Color.Gray, fontSize = 13.sp) }
        items(reorder, key = { "ro_" + it.item.id }) { r ->
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Row {
                    Text(r.item.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(statusLabel(r.status), color = statusColor(r.status), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text("الرصيد ${fmtQty(r.qtyDisplay)} ${uomLabel(r.uom)} — الكمية المقترحة ${fmtQty(suggested(r))}", fontSize = 12.sp, color = Color(0xFF334155))
            }
        }
        item {
            Text(
                if (abcByConsumption) "تحليل ABC (قيمة استهلاك آخر 12 شهراً من الأوامر المكتملة)"
                else "تحليل ABC (بقيمة المخزون الحالية — لا توجد أوامر مكتملة خلال 12 شهراً)",
                fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
        }
        if (abc.isEmpty()) item { Text("لا بيانات كافية للتحليل.", color = Color.Gray, fontSize = 13.sp) }
        items(abc, key = { "abc_" + it.itemId }) { a ->
            Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.abcClass, fontWeight = FontWeight.Black, fontSize = 16.sp, color = when (a.abcClass) { "A" -> Color(0xFFB91C1C); "B" -> Color(0xFFB45309); else -> Color(0xFF15803D) }, modifier = Modifier.width(28.dp))
                Text(a.name, modifier = Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmtMoney(a.consumptionValue)}  (${String.format(Locale.US, "%.1f", a.sharePct)}%)", fontSize = 12.sp)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
