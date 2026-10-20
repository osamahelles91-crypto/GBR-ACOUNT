@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MrpRow
import com.example.data.PlanRow

private fun fmt1(x: Double): String = String.format(java.util.Locale.US, "%.1f", x)

// =====================================================================================
// التخطيط: نقطة إعادة الطلب ومخزون الأمان وEOQ واقتراح الطلب + احتياج صافٍ لدفعات مخططة
// =====================================================================================
@Composable
internal fun PlanningTab(vm: InventoryViewModel, canEdit: Boolean) {
    val plan by vm.planRows.collectAsState()
    val forms by vm.formulations.collectAsState()
    val k by vm.orderCost.collectAsState()
    val h by vm.holdPct.collectAsState()
    val context = LocalContext.current
    var kText by remember(k) { mutableStateOf(if (k > 0.0) fmtQty(k) else "") }
    var hText by remember(h) { mutableStateOf(fmtQty(h)) }
    var onlyOrder by remember { mutableStateOf(false) }

    var pickedId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var kgText by remember { mutableStateOf("") }
    var daysText by remember { mutableStateOf("7") }
    val basket = remember { mutableStateListOf<Pair<String, Double>>() }
    var mrp by remember { mutableStateOf<List<MrpRow>?>(null) }
    val formName = forms.associate { it.id to it.name }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "الحسابات مشتقة من الأوامر المكتملة (آخر 180 يوماً) وأوامر الشراء المستلمة. نقطة إعادة الطلب = الاستهلاك اليومي × مهلة التوريد + مخزون الأمان. " +
                    "مخزون الأمان إحصائي (ثقة 95%) عند توفّر 6 ملاحظات مهلة فأكثر، وإلا بطريقة الأقصى-الأدنى. اقتراحات تقديرية للمراجعة وليست أوامر.",
                fontSize = 12.sp, color = Color.Gray
            )
        }
        if (canEdit) item {
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("معاملات الكمية الاقتصادية (EOQ)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(kText, { kText = it }, singleLine = true, label = { Text("تكلفة إصدار طلب", fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                    OutlinedTextField(hText, { hText = it }, singleLine = true, label = { Text("تكلفة الاحتفاظ %/سنة", fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val kv = kText.trim().toDoubleOrNull() ?: 0.0
                        val hv = hText.trim().toDoubleOrNull() ?: 0.0
                        if (kv < 0.0 || hv < 0.0 || hv > 100.0) {
                            Toast.makeText(context, "قيم غير صالحة", Toast.LENGTH_SHORT).show()
                        } else {
                            vm.savePlanSettings(kv, hv)
                            Toast.makeText(context, "تم الحفظ", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("حفظ") }
                }
                Text("بدون تكلفة الطلب لا تُحسب EOQ ويُقترح الطلب حتى الحد الأعلى أو تغطية مهلة التوريد.", fontSize = 11.sp, color = Color.Gray)
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اقتراحات إعادة الطلب", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("المحتاج طلباً فقط", fontSize = 12.sp)
                Switch(checked = onlyOrder, onCheckedChange = { onlyOrder = it })
            }
        }
        val shown = if (onlyOrder) plan.filter { it.status == "ORDER" } else plan
        if (shown.isEmpty()) item { Text("لا بيانات استهلاك كافية بعد (تحتاج أوامر إنتاج مكتملة).", color = Color.Gray, fontSize = 13.sp) }
        items(shown, key = { it.itemId }) { r -> PlanCard(r) }

        item { HorizontalDivider() }
        item { Text("احتياج صافٍ لدفعات مخططة (MRP)", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        item {
            Column(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (pickedId == null) "اختر تركيبة" else (formName[pickedId] ?: "—"), fontSize = 12.sp, maxLines = 1)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            forms.sortedBy { it.name }.forEach { f ->
                                DropdownMenuItem(text = { Text(f.name) }, onClick = { pickedId = f.id; menuOpen = false })
                            }
                        }
                    }
                    OutlinedTextField(kgText, { kgText = it }, singleLine = true, label = { Text("كجم", fontSize = 11.sp) }, modifier = Modifier.width(90.dp))
                    Button(onClick = {
                        val kg = kgText.trim().toDoubleOrNull() ?: 0.0
                        val id = pickedId
                        if (id == null || kg <= 0.0) {
                            Toast.makeText(context, "اختر تركيبة وأدخل كمية صحيحة", Toast.LENGTH_SHORT).show()
                        } else {
                            basket.add(Pair(id, kg)); kgText = ""; mrp = null
                        }
                    }) { Text("إضافة") }
                }
                basket.forEachIndexed { i, b ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((formName[b.first] ?: "—") + " — " + fmtQty(b.second) + " كجم", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { basket.removeAt(i); mrp = null }) { Text("حذف", color = Color(0xFFB91C1C)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(daysText, { daysText = it }, singleLine = true, label = { Text("تلزم المواد بعد (يوم)", fontSize = 11.sp) }, modifier = Modifier.weight(1f))
                    Button(onClick = {
                        if (basket.isEmpty()) Toast.makeText(context, "أضف تركيبة أولاً", Toast.LENGTH_SHORT).show()
                        else mrp = vm.runMrp(basket.toList(), daysText.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0)
                    }) { Text("احسب") }
                }
            }
        }
        val res = mrp
        if (res != null) {
            if (res.isEmpty()) item { Text("لا مواد في التركيبات المختارة.", color = Color.Gray, fontSize = 13.sp) }
            items(res, key = { it.itemId }) { r -> MrpCard(r) }
        }
    }
}

@Composable
private fun PlanCard(r: PlanRow) {
    val order = r.status == "ORDER"
    Column(
        Modifier.fillMaxWidth().background(if (order) Color(0xFFFEF2F2) else Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row {
            Text(r.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("XYZ: " + r.xyz, fontSize = 11.sp, color = Color.Gray)
        }
        val u = uomLabel(r.uom)
        Text(
            "المتاح " + fmtQty(r.available) + " " + u + "   |   على الطلب " + fmtQty(r.onOrder) +
                "   |   استهلاك/يوم " + fmtQty(r.meanDaily),
            fontSize = 12.sp
        )
        Text(
            "التغطية " + (r.coverageDays?.let { fmt1(it) + " يوم" } ?: "—") + "   |   المهلة " + (r.leadDays?.let { fmt1(it) + " يوم" } ?: "—"),
            fontSize = 12.sp
        )
        if (r.rop != null && r.safetyStock != null) {
            Text(
                "ROP " + fmtQty(r.rop) + "   |   مخزون الأمان " + fmtQty(r.safetyStock) + " (" + r.method + ")" +
                    (r.eoq?.let { "   |   EOQ " + fmtQty(it) } ?: ""),
                fontSize = 12.sp
            )
        } else {
            Text(r.method, fontSize = 12.sp, color = Color(0xFFB45309))
        }
        if (order) Text("اقتراح الطلب: " + fmtQty(r.suggested) + " " + u, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB91C1C))
    }
}

@Composable
private fun MrpCard(r: MrpRow) {
    val short = r.net > 0.0
    Column(
        Modifier.fillMaxWidth().background(if (short) Color(0xFFFEF2F2) else Color(0xFFF0FDF4), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        val u = uomLabel(r.uom)
        Text(
            "الاحتياج " + fmtQty(r.gross) + "   |   المتاح " + fmtQty(r.available) + "   |   على الطلب " + fmtQty(r.onOrder) + " " + u,
            fontSize = 12.sp
        )
        if (short) {
            Text("المطلوب شراؤه: " + fmtQty(r.net) + " " + u, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFFB91C1C))
            val d = r.orderByDays
            if (d == null) Text("مهلة التوريد غير معروفة", fontSize = 11.sp, color = Color.Gray)
            else if (d < 0.0) Text("متأخر: كان يجب إصدار الطلب قبل " + fmt1(-d) + " يوم", fontSize = 12.sp, color = Color(0xFFB91C1C))
            else Text("آخر موعد لإصدار الطلب: بعد " + fmt1(d) + " يوم", fontSize = 12.sp)
        } else {
            Text("مغطّى", fontSize = 12.sp, color = Color(0xFF15803D))
        }
    }
}

// =====================================================================================
// المؤشرات
// =====================================================================================
@Composable
internal fun KpiTab(vm: InventoryViewModel) {
    val k by vm.kpis.collectAsState()
    val bad = Color(0xFFB91C1C)
    val good = Color(0xFF15803D)
    val neutral = Color(0xFF334155)

    @Composable
    fun Tile(title: String, value: String, note: String, color: Color) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, fontSize = 12.sp, color = Color.Gray)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
            if (note.isNotBlank()) Text(note, fontSize = 11.sp, color = Color.Gray)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("كلها مشتقة تلقائياً من الدفتر والأوامر والجرد، دون إدخال يدوي.", fontSize = 12.sp, color = Color.Gray) }
        item { Tile("قيمة مخزون المواد الخام", fmtMoney(k.rawValue), "بمتوسط التكلفة المتحرك", neutral) }
        item {
            Tile(
                "أيام المخزون", k.daysOfInventory?.let { fmt1(it) + " يوم" } ?: "—",
                "المخزون ÷ متوسط الاستهلاك اليومي لآخر " + k.windowDays + " يوماً", neutral
            )
        }
        item { Tile("دوران المخزون (سنوي)", k.turnover?.let { fmt1(it) + " مرة" } ?: "—", "الاستهلاك السنوي المقدَّر ÷ قيمة المخزون", neutral) }
        item {
            Tile(
                "مخزون راكد (خام > 180 يوماً بلا صرف)", k.stagnantCount.toString() + " صنف",
                "قيمته " + fmtMoney(k.stagnantValue), if (k.stagnantCount > 0) bad else good
            )
        }
        item {
            Tile(
                "دفعات خام تنتهي صلاحيتها", "30 يوماً: " + k.expiring30 + "   |   60: " + k.expiring60 + "   |   90: " + k.expiring90,
                "دفعات لها رصيد فعلي (تشمل المنتهية)", if (k.expiring30 > 0) bad else neutral
            )
        }
        item { Tile("أرصدة سالبة", k.negativeCount.toString(), "يجب أن تكون صفراً", if (k.negativeCount > 0) bad else good) }
        item {
            val acc = k.countAccuracyPct
            Tile(
                "دقة الجرد", if (acc != null) fmt1(acc) + "%" else "—",
                "أسطر الجرد المعتمدة ضمن ±2% من الدفتري (الهدف ≥ 97%)",
                if (acc == null) neutral else if (acc >= 97.0) good else bad
            )
        }
        item { Tile("متوسط تأخير الإفراج (خام)", k.avgRawReleaseHours?.let { fmt1(it) + " ساعة" } ?: "—", "من الاستلام حتى إفراج الجودة", neutral) }
        item { Tile("متوسط تأخير الإفراج (منتج تام)", k.avgFgReleaseHours?.let { fmt1(it) + " ساعة" } ?: "—", "من الإنتاج حتى إفراج الجودة", neutral) }
        item { Tile("متوسط إنتاجية الأوامر", k.avgYieldPct?.let { fmt1(it) + "%" } ?: "—", "المعبأ ÷ النظري", neutral) }
        item {
            Tile(
                "دفعات منتج تام راكدة (> 90 يوماً)", k.fgStagnantLots.toString(), "لها رصيد ولا تسليم للصنف منذ 90 يوماً",
                if (k.fgStagnantLots > 0) bad else good
            )
        }
    }
}
