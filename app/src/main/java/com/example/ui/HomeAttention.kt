package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

/** تمرير تبويب البداية للمخزون من الصفحة الرئيسية (يُقرأ مرة واحدة عند فتح لوحة المخزون). */
object InventoryNav {
    @Volatile var pendingTab: Int? = null
    fun consume(): Int? {
        val t = pendingTab
        pendingTab = null
        return t
    }
}

@Composable
internal fun HomeSectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), modifier = Modifier.padding(top = 4.dp))
}

private data class AttentionItem(val count: Int, val label: String, val tab: Int, val severe: Boolean)

/**
 * بطاقة «يحتاج انتباهك»: أرقام حيّة مشتقة من الدفتر؛ كل صف يفتح تبويبه في المخزون.
 * لا تعرض إلا البنود غير الصفرية.
 */
@Composable
internal fun HomeAttentionCard(onOpen: (Int) -> Unit) {
    val vm: InventoryViewModel = viewModel()
    val stock by vm.stockRows.collectAsState()
    val lots by vm.lots.collectAsState()
    val fg by vm.fgLots.collectAsState()
    val counts by vm.counts.collectAsState()
    val shortages by vm.shortages.collectAsState()
    val k by vm.kpis.collectAsState()

    // الصفحة لا تُنذر قبل أن يبدأ المخزون فعلياً
    if (stock.none { it.hasMoves } && lots.isEmpty()) return

    val items = ArrayList<AttentionItem>()
    val low = stock.count { it.status == "LOW" || it.status == "REORDER" }
    if (low > 0) items.add(AttentionItem(low, "مواد عند أو تحت نقطة إعادة الطلب", 0, false))
    val negative = stock.count { it.status == "NEGATIVE" }
    if (negative > 0) items.add(AttentionItem(negative, "أرصدة سالبة تحتاج تصحيحاً", 0, true))
    val short = shortages.filter { !it.noBalance }.map { it.orderNumber }.distinct().size
    if (short > 0) items.add(AttentionItem(short, "أوامر إنتاج مفتوحة ينقصها مواد", 4, true))
    if (k.expiring30 > 0) items.add(AttentionItem(k.expiring30, "دفعات خام تنتهي صلاحيتها خلال 30 يوماً", 3, true))
    val rawQuar = lots.count { !it.itemId.startsWith("FG|") && it.status == "QUARANTINE" }
    if (rawQuar > 0) items.add(AttentionItem(rawQuar, "دفعات خام بانتظار الإفراج", 3, false))
    val fgHold = fg.count { it.qcCount > 0.0 }
    if (fgHold > 0) items.add(AttentionItem(fgHold, "دفعات منتج تام بانتظار فحص الجودة", 5, false))
    val pendingCounts = counts.count { it.status == "SUBMITTED" }
    if (pendingCounts > 0) items.add(AttentionItem(pendingCounts, "جلسات جرد بانتظار الاعتماد", 7, false))

    val bg = if (items.any { it.severe }) Color(0xFFFEF2F2) else if (items.isNotEmpty()) Color(0xFFFFFBEB) else Color(0xFFF0FDF4)
    val line = if (items.any { it.severe }) Color(0xFFFCA5A5) else if (items.isNotEmpty()) Color(0xFFFCD34D) else Color(0xFFBBF7D0)

    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, line), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("يحتاج انتباهك", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF1E293B))
        if (items.isEmpty()) {
            Text("لا شيء معلّق في المخزون الآن ✓", fontSize = 13.sp, color = Color(0xFF15803D))
        }
        items.forEach { it ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(it.tab) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    it.count.toString(), fontWeight = FontWeight.Black, fontSize = 20.sp,
                    color = if (it.severe) Color(0xFFB91C1C) else Color(0xFFB45309), modifier = Modifier.width(36.dp)
                )
                Text(it.label, fontSize = 13.sp, color = Color(0xFF334155), modifier = Modifier.weight(1f))
                Text("›", fontSize = 18.sp, color = Color.Gray)
            }
        }
    }
}
