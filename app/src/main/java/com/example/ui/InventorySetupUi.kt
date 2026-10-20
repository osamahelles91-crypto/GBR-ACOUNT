package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
import com.example.data.INVENTORY_CATEGORIES
import com.example.data.INVENTORY_ZONES
import com.example.data.InventoryItemProfile
import com.example.data.InventorySupplier
import com.example.data.StockLocation
import com.example.data.UomConversion
import com.example.data.inventoryCategoryLabel

private fun numText(x: Double): String = if (x == 0.0) "" else fmtQty(x)

// =====================================================================================
// 3) إعداد المواد: سجل المادة المخزني + معاملات تحويل الوحدات
// =====================================================================================
@Composable
internal fun InventorySetupTab(vm: InventoryViewModel, rows: List<StockRow>, canEdit: Boolean) {
    val suppliers by vm.suppliers.collectAsState()
    val allConversions by vm.conversions.collectAsState()
    var itemId by remember { mutableStateOf<String?>(null) }
    val row = rows.firstOrNull { it.item.id == itemId }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SimpleDropdown(
            label = "المادة",
            selectedText = row?.item?.name ?: "اختر مادة",
            options = rows.map { it.item.id to it.item.name },
            onSelect = { itemId = it }
        )
        if (row == null) {
            Text("اختر مادة لتعريف تصنيفها ووحدتها وحدود مخزونها ومدة صلاحيتها.", color = Color.Gray, fontSize = 13.sp)
            return@Column
        }
        if (!canEdit) {
            Text("للعرض فقط: التعديل لمدير النظام أو أمين المخزن.", color = Color(0xFFB45309), fontSize = 12.sp)
        }

        val p = row.profile
        val key = row.item.id + "|" + (p?.updatedAt ?: 0L)
        var category by remember(key) { mutableStateOf(p?.category ?: "OTHER") }
        var baseUom by remember(key) { mutableStateOf(p?.baseUom ?: "KG") }
        var density by remember(key) { mutableStateOf(numText(p?.densityKgPerL ?: 0.0)) }
        var shelf by remember(key) { mutableStateOf(if ((p?.shelfLifeDays ?: 0) == 0) "" else p!!.shelfLifeDays.toString()) }
        var retest by remember(key) { mutableStateOf(if ((p?.retestDays ?: 0) == 0) "" else p!!.retestDays.toString()) }
        var minL by remember(key) { mutableStateOf(numText(p?.minLevel ?: 0.0)) }
        var maxL by remember(key) { mutableStateOf(numText(p?.maxLevel ?: 0.0)) }
        var rop by remember(key) { mutableStateOf(numText(p?.reorderPoint ?: 0.0)) }
        var ss by remember(key) { mutableStateOf(numText(p?.safetyStock ?: 0.0)) }
        var lead by remember(key) { mutableStateOf(if ((p?.leadTimeDays ?: 0) == 0) "" else p!!.leadTimeDays.toString()) }
        var moq by remember(key) { mutableStateOf(numText(p?.moq ?: 0.0)) }
        var hazard by remember(key) { mutableStateOf(p?.hazardClass ?: "") }
        var flash by remember(key) { mutableStateOf(p?.flashPointC?.let { fmtQty(it) } ?: "") }
        var zone by remember(key) { mutableStateOf(p?.storageZone ?: "") }
        var tol by remember(key) { mutableStateOf(numText(p?.weighTolerancePct ?: 0.0)) }
        var ved by remember(key) { mutableStateOf(p?.vedClass ?: "") }
        var supplierId by remember(key) { mutableStateOf(p?.preferredSupplierId) }
        var sds by remember(key) { mutableStateOf(p?.sdsNote ?: "") }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("سجل المادة المخزني", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                SimpleDropdown("الفئة", inventoryCategoryLabel(category), INVENTORY_CATEGORIES, { category = it })
                SimpleDropdown(
                    "وحدة الأساس", if (baseUom == "L") "لتر" else "كجم",
                    listOf("KG" to "كجم", "L" to "لتر"), { baseUom = it }
                )
                NumField("الكثافة (كجم/لتر) — للتحويل بين اللتر والكجم", density, { density = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("مدة الصلاحية (يوم)", shelf, { shelf = it.filter { c -> c.isDigit() } }, Modifier.weight(1f))
                    NumField("إعادة الفحص (يوم)", retest, { retest = it.filter { c -> c.isDigit() } }, Modifier.weight(1f))
                }
                Text("حدود المخزون (بوحدة الأساس)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("الحد الأدنى", minL, { minL = it }, Modifier.weight(1f))
                    NumField("الحد الأقصى", maxL, { maxL = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("نقطة إعادة الطلب", rop, { rop = it }, Modifier.weight(1f))
                    NumField("مخزون الأمان", ss, { ss = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("مهلة التوريد (يوم)", lead, { lead = it.filter { c -> c.isDigit() } }, Modifier.weight(1f))
                    NumField("أقل كمية طلب", moq, { moq = it }, Modifier.weight(1f))
                }
                SimpleDropdown(
                    "المورد المفضل",
                    suppliers.firstOrNull { it.id == supplierId }?.name ?: "بلا",
                    listOf<Pair<String?, String>>(null to "بلا") + suppliers.filter { it.isActive }.map { it.id to it.name },
                    { supplierId = it }
                )
                Text("السلامة والتخزين", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(hazard, { hazard = it }, label = { Text("فئة الخطر (GHS/NFPA)", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                NumField("نقطة الوميض (°م)", flash, { flash = it })
                SimpleDropdown(
                    "منطقة التخزين", INVENTORY_ZONES.firstOrNull { it.first == zone }?.second ?: "غير محددة",
                    listOf("" to "غير محددة") + INVENTORY_ZONES, { zone = it }
                )
                NumField("تسامح الوزن %", tol, { tol = it })
                SimpleDropdown(
                    "الحرجية (VED)", when (ved) { "V" -> "حيوية"; "E" -> "أساسية"; "D" -> "مرغوبة"; else -> "غير محددة" },
                    listOf("" to "غير محددة", "V" to "حيوية (V)", "E" to "أساسية (E)", "D" to "مرغوبة (D)"), { ved = it }
                )
                OutlinedTextField(sds, { sds = it }, label = { Text("ملاحظة ورقة السلامة SDS", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                if (canEdit) {
                    Button(onClick = {
                        vm.saveProfile(
                            InventoryItemProfile(
                                itemId = row.item.id,
                                category = category,
                                baseUom = baseUom,
                                densityKgPerL = density.toDoubleOrNull() ?: 0.0,
                                shelfLifeDays = shelf.toIntOrNull() ?: 0,
                                retestDays = retest.toIntOrNull() ?: 0,
                                minLevel = minL.toDoubleOrNull() ?: 0.0,
                                maxLevel = maxL.toDoubleOrNull() ?: 0.0,
                                reorderPoint = rop.toDoubleOrNull() ?: 0.0,
                                safetyStock = ss.toDoubleOrNull() ?: 0.0,
                                leadTimeDays = lead.toIntOrNull() ?: 0,
                                moq = moq.toDoubleOrNull() ?: 0.0,
                                hazardClass = hazard.trim(),
                                flashPointC = flash.toDoubleOrNull(),
                                storageZone = zone,
                                weighTolerancePct = tol.toDoubleOrNull() ?: 0.0,
                                vedClass = ved,
                                preferredSupplierId = supplierId,
                                sdsNote = sds.trim()
                            )
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text("حفظ سجل المادة") }
                }
            }
        }

        // ---- معاملات تحويل الوحدات ----
        val itemConv = allConversions.filter { it.itemId == row.item.id }
        var convId by remember(row.item.id) { mutableStateOf<String?>(null) }
        var convName by remember(row.item.id) { mutableStateOf("") }
        var convFactor by remember(row.item.id) { mutableStateOf("") }
        val unitWord = if (baseUom == "L") "لتر" else "كجم"
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("وحدات الشراء/التخزين (تحويل إلى $unitWord)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (itemConv.isEmpty()) Text("لا وحدات معرَّفة (مثال: كيس = 25 كجم، برميل = 200 كجم).", fontSize = 12.sp, color = Color.Gray)
                itemConv.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.name} = ${fmtQty(c.factorToBase)} $unitWord", modifier = Modifier.weight(1f), fontSize = 13.sp)
                        if (canEdit) {
                            TextButton(onClick = { convId = c.id; convName = c.name; convFactor = fmtQty(c.factorToBase) }) { Text("تعديل", fontSize = 12.sp) }
                        }
                    }
                }
                if (canEdit) {
                    OutlinedTextField(convName, { convName = it }, label = { Text("اسم الوحدة (كيس، برميل…)", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    NumField("كم $unitWord في الوحدة الواحدة", convFactor, { convFactor = it })
                    Button(onClick = {
                        vm.saveConversion(
                            UomConversion(
                                id = convId ?: java.util.UUID.randomUUID().toString(),
                                itemId = row.item.id, name = convName.trim(), factorToBase = convFactor.toDoubleOrNull() ?: 0.0
                            )
                        )
                        convId = null; convName = ""; convFactor = ""
                    }) { Text(if (convId == null) "إضافة وحدة" else "حفظ التعديل") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// =====================================================================================
// 4) الموردون والمواقع
// =====================================================================================
@Composable
internal fun InventoryPartiesTab(vm: InventoryViewModel, canEdit: Boolean) {
    val suppliers by vm.suppliers.collectAsState()
    val locations by vm.locations.collectAsState()

    var sName by remember { mutableStateOf("") }
    var sPhone by remember { mutableStateOf("") }
    var sNotes by remember { mutableStateOf("") }
    var lName by remember { mutableStateOf("") }
    var lZone by remember { mutableStateOf("GENERAL") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الموردون", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (suppliers.isEmpty()) Text("لا موردين بعد.", fontSize = 12.sp, color = Color.Gray)
                suppliers.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name + if (!s.isActive) " (غير فعّال)" else "", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (s.phone.isNotBlank()) Text(s.phone, fontSize = 11.sp, color = Color.Gray)
                        }
                        Text(if (s.isApproved) "معتمد" else "غير معتمد", fontSize = 11.sp, color = if (s.isApproved) Color(0xFF15803D) else Color(0xFFB45309))
                        if (canEdit) {
                            Switch(checked = s.isApproved, onCheckedChange = { vm.saveSupplier(s.copy(isApproved = it)) })
                        }
                    }
                }
                if (canEdit) {
                    OutlinedTextField(sName, { sName = it }, label = { Text("اسم المورد", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sPhone, { sPhone = it }, label = { Text("هاتف", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sNotes, { sNotes = it }, label = { Text("ملاحظات", fontSize = 12.sp) }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        vm.saveSupplier(InventorySupplier(name = sName.trim(), phone = sPhone.trim(), notes = sNotes.trim()))
                        sName = ""; sPhone = ""; sNotes = ""
                    }) { Text("إضافة مورد") }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مواقع التخزين", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                locations.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.name + if (!l.isActive) " (غير فعّال)" else "", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(INVENTORY_ZONES.firstOrNull { it.first == l.zone }?.second ?: l.zone, fontSize = 11.sp, color = Color.Gray)
                        }
                        if (canEdit && l.id != com.example.data.InventoryIds.LOC_MAIN) {
                            Switch(checked = l.isActive, onCheckedChange = { vm.saveLocation(l.copy(isActive = it)) })
                        }
                    }
                }
                if (canEdit) {
                    OutlinedTextField(lName, { lName = it }, label = { Text("اسم الموقع", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    SimpleDropdown("المنطقة", INVENTORY_ZONES.firstOrNull { it.first == lZone }?.second ?: lZone, INVENTORY_ZONES, { lZone = it })
                    Button(onClick = {
                        vm.saveLocation(StockLocation(name = lName.trim(), zone = lZone))
                        lName = ""
                    }) { Text("إضافة موقع") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
