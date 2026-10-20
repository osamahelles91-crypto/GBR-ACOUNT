package com.example.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.DevelopmentSample
import com.example.data.RawMaterial
import org.json.JSONArray

/** مقارنة متعددة العينات (2–4) داخل مشروع أبحاث وتطوير: مواد + نسب/كميات + تكاليف. مشتركة بين الجوال والمكتب. */
const val MAX_COMPARE_SAMPLES = 4

private data class CmpLine(val key: String, val name: String, val qty: Double)

private fun cmpParseItems(json: String): List<CmpLine> {
    val out = mutableListOf<CmpLine>()
    try {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("rawMaterialId").ifBlank { o.optInt("rawMaterialId", 0).toString() }
            val name = o.optString("rawMaterialName")
            out.add(CmpLine(if (id.isBlank() || id == "0") "n:$name" else "i:$id", name, o.optDouble("originalQuantityMultiplier", 0.0)))
        }
    } catch (_: Exception) {}
    return out
}

private fun cmpNum(v: Double, dec: Int = 2): String {
    val s = String.format(java.util.Locale.US, "%.${dec}f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

private enum class CmpBase { KG1, KG100, CUSTOM }
private enum class CmpView { AMOUNT_PCT, AMOUNT_QTY, COST }

@Composable
fun MultiSampleCompareScreen(
    samples: List<DevelopmentSample>,
    rawMaterials: List<RawMaterial>,
    onBack: () -> Unit,
    onOpenPairReport: (() -> Unit)? = null
) {
    val green = Color(0xFF047857)
    val red = Color(0xFFB91C1C)
    val blue = Color(0xFF1D4ED8)
    val slate = Color(0xFF0F172A)

    var base by remember { mutableStateOf(CmpBase.KG1) }
    var customKg by remember { mutableStateOf("") }
    var view by remember { mutableStateOf(CmpView.AMOUNT_PCT) }

    val baseKg = when (base) {
        CmpBase.KG1 -> 1.0
        CmpBase.KG100 -> 100.0
        CmpBase.CUSTOM -> customKg.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
    }

    val parsed = remember(samples) { samples.map { cmpParseItems(it.itemsJson) } }
    val totals = parsed.map { l -> l.sumOf { it.qty } }
    val priceOf: (CmpLine) -> Double = { line ->
        val id = line.key.removePrefix("i:")
        rawMaterials.firstOrNull { line.key.startsWith("i:") && it.id == id }?.price
            ?: rawMaterials.firstOrNull { it.name == line.name }?.price ?: 0.0
    }

    // اتحاد المواد بترتيب الظهور
    val keys = remember(parsed) {
        val seen = LinkedHashMap<String, String>()
        parsed.forEach { l -> l.forEach { if (!seen.containsKey(it.key)) seen[it.key] = it.name } }
        seen.entries.toList()
    }
    // pct[sample][key]
    val pct: List<Map<String, Double>> = parsed.mapIndexed { si, l ->
        val tot = totals[si]
        l.groupBy { it.key }.mapValues { (_, v) -> if (tot > 0) v.sumOf { it.qty } / tot * 100.0 else 0.0 }
    }
    val costOfKg: List<Map<String, Double>> = parsed.mapIndexed { si, l ->
        // تكلفة المادة لكل كجم من التركيبة = نسبة × سعر
        val tot = totals[si]
        l.groupBy { it.key }.mapValues { (_, v) -> if (tot > 0) v.sumOf { it.qty * priceOf(it) } / tot else 0.0 }
    }
    val costPerKg = costOfKg.map { it.values.sum() }
    val minCost = costPerKg.filter { it > 0.0 }.minOrNull()

    val colW = 92.dp
    val nameW = 130.dp
    val diffW = 84.dp

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, shape = RoundedCornerShape(10.dp)) { Text("رجوع", fontSize = 12.sp) }
            Text("⚖️ مقارنة ${samples.size} عينات", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = slate, modifier = Modifier.weight(1f))
            if (samples.size == 2 && onOpenPairReport != null) {
                OutlinedButton(onClick = onOpenPairReport, shape = RoundedCornerShape(10.dp)) { Text("تقرير PDF للثنائية", fontSize = 11.sp) }
            }
        }

        // خيارات العرض
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, RndBorderLight)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("أساس المقارنة", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = base == CmpBase.KG1, onClick = { base = CmpBase.KG1 }, label = { Text("لكل 1 كجم", fontSize = 11.sp) })
                    FilterChip(selected = base == CmpBase.KG100, onClick = { base = CmpBase.KG100 }, label = { Text("لكل 100 كجم", fontSize = 11.sp) })
                    FilterChip(selected = base == CmpBase.CUSTOM, onClick = { base = CmpBase.CUSTOM }, label = { Text("وزن مستهدف", fontSize = 11.sp) })
                }
                if (base == CmpBase.CUSTOM) {
                    OutlinedTextField(
                        value = customKg, onValueChange = { customKg = it },
                        label = { Text("الوزن المستهدف (كجم)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("طريقة العرض", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(selected = view == CmpView.AMOUNT_PCT, onClick = { view = CmpView.AMOUNT_PCT }, label = { Text("نسبة %", fontSize = 11.sp) })
                    FilterChip(selected = view == CmpView.AMOUNT_QTY, onClick = { view = CmpView.AMOUNT_QTY }, label = { Text("كمية (غم/كجم)", fontSize = 11.sp) })
                    FilterChip(selected = view == CmpView.COST, onClick = { view = CmpView.COST }, label = { Text("تكلفة المادة", fontSize = 11.sp) })
                }
            }
        }

        // جدول المقارنة — البطاقة بعرض الصفحة، وأعمدة العينات تتوزع على العرض المتاح (حد أدنى colW) ويبقى التمرير الأفقي عند الضيق
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val nameWide = if (maxWidth > 600.dp) 220.dp else nameW
        val colWide = maxOf(colW, (maxWidth - 16.dp - nameWide - diffW - 4.dp) / samples.size)
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, RndBorderLight)) {
            Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                // الترويسة
                Row(Modifier.background(Color(0xFFF1F5F9), RoundedCornerShape(8.dp)).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("المادة", Modifier.width(nameWide).padding(horizontal = 6.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = slate)
                    samples.forEachIndexed { i, s ->
                        Column(Modifier.width(colWide).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.sampleNumber + if (i == 0) " ★" else "", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = RndPurpleAccent, maxLines = 1)
                            Text(s.sampleName, fontSize = 9.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("الفرق", Modifier.width(diffW), fontWeight = FontWeight.Bold, fontSize = 11.sp, color = slate, textAlign = TextAlign.Center)
                }
                keys.forEach { (key, name) ->
                    val vals: List<Double?> = samples.indices.map { si -> pct[si][key] }
                    val present = vals.count { it != null }
                    val partial = present in 1 until samples.size
                    val shown: List<Double?> = samples.indices.map { si ->
                        when (view) {
                            CmpView.AMOUNT_PCT -> pct[si][key]
                            CmpView.AMOUNT_QTY -> pct[si][key]?.let { it / 100.0 * baseKg * (if (baseKg <= 1.0) 1000.0 else 1.0) }
                            CmpView.COST -> costOfKg[si][key]?.let { it * baseKg }
                        }
                    }
                    val ref = shown.firstOrNull() ?: 0.0
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text((if (partial) "◆ " else "") + name, Modifier.width(nameWide).padding(horizontal = 6.dp), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (partial) Color(0xFFB45309) else slate, fontWeight = if (partial) FontWeight.Bold else FontWeight.Normal)
                        shown.forEachIndexed { si, v ->
                            val dec = if (view == CmpView.AMOUNT_QTY && baseKg <= 1.0) 1 else 2
                            val txt: String; var col = slate
                            if (v == null) { txt = "—"; col = Color.LightGray }
                            else {
                                var arrow = ""
                                if (si > 0 && shown.first() != null) {
                                    val d = v - ref
                                    if (kotlin.math.abs(d) > (if (dec == 1) 0.05 else 0.005)) { arrow = if (d > 0) " ▲" else " ▼"; col = if (d > 0) red else blue }
                                }
                                txt = cmpNum(v, dec) + arrow
                            }
                            Text(txt, Modifier.width(colWide), textAlign = TextAlign.Center, fontSize = 11.sp, color = col, fontWeight = FontWeight.SemiBold)
                        }
                        val nums = shown.filterNotNull()
                        val diff = if (nums.size >= 2) nums.max() - nums.min() else (nums.firstOrNull() ?: 0.0)
                        Text(cmpNum(diff, 2), Modifier.width(diffW), textAlign = TextAlign.Center, fontSize = 11.sp, color = if (diff > 0.005) Color(0xFFB45309) else Color.Gray)
                    }
                    HorizontalDivider(color = Color(0xFFF1F5F9))
                }
                // الإجمالي
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("الإجمالي", Modifier.width(nameWide).padding(horizontal = 6.dp), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    samples.indices.forEach { si ->
                        val t = when (view) {
                            CmpView.AMOUNT_PCT -> "100%"
                            CmpView.AMOUNT_QTY -> if (baseKg <= 1.0) "${cmpNum(baseKg * 1000.0, 1)} غم" else "${cmpNum(baseKg, 2)} كجم"
                            CmpView.COST -> cmpNum(costPerKg[si] * baseKg, 2)
                        }
                        Text(t, Modifier.width(colWide), textAlign = TextAlign.Center, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(diffW))
                }
            }
        }
        }
        Text("★ العينة المرجعية (الأولى) · ▲ أعلى منها · ▼ أقل منها · ◆ مادة موجودة في بعض العينات فقط", fontSize = 10.sp, color = Color.Gray)

        // ملخص التكاليف
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)), border = BorderStroke(1.dp, Color(0xFFBBF7D0))) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("💰 مقارنة التكاليف (المواد الخام فقط، بأسعارها الحالية)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = green)
                samples.forEachIndexed { si, s ->
                    val cpk = costPerKg[si]
                    val refC = costPerKg.firstOrNull() ?: 0.0
                    val pctDiff = if (si > 0 && refC > 0.0) (cpk - refC) / refC * 100.0 else null
                    val top = costOfKg[si].maxByOrNull { it.value }
                    val topName = keys.firstOrNull { it.key == top?.key }?.value
                    val isMin = minCost != null && cpk > 0.0 && cpk == minCost && samples.size > 1
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.sampleNumber} - ${s.sampleName}" + if (si == 0) " ★" else "", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isMin) Text("✔ الأرخص", color = green, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                        }
                        Text("تكلفة الكجم: ${cmpNum(cpk, 2)} شيكل" + (pctDiff?.let { "  (${if (it > 0) "+" else ""}${cmpNum(it, 1)}% عن المرجعية)" } ?: ""),
                            fontSize = 12.sp, color = if (pctDiff == null) slate else if (pctDiff > 0) red else green)
                        Text("عدد المواد: ${parsed[si].size}" + (if (topName != null && (top?.value ?: 0.0) > 0) " · الأعلى كلفة: $topName (${cmpNum(top!!.value, 2)} شيكل/كجم)" else ""),
                            fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
