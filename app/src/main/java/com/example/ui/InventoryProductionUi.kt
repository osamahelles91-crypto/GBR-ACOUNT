@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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

// =====================================================================================
// الإنتاج والتوفر: فحص نقص المواد للأوامر المفتوحة + تدقيق الصرف التلقائي
// =====================================================================================
@Composable
internal fun ProductionLinkTab(vm: InventoryViewModel, canEdit: Boolean, userName: String) {
    val shortages by vm.shortages.collectAsState()
    val reviews by vm.reviews.collectAsState()
    var confirmFor by remember { mutableStateOf<IssueReview?>(null) }

    val byOrder = shortages.groupBy { it.orderNumber }

    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "الصرف تلقائي: عند اكتمال أمر إنتاج تُصرف مواده من المخزن الرئيسي (أقرب دفعة انتهاءً أولاً). " +
                    "يُحتسب الأمر فقط للمواد التي سُجّل لها رصيد افتتاحي وكان إكماله بعد ذلك الرصيد. العكس يدوي من هنا دائماً.",
                fontSize = 12.sp, color = Color.Gray
            )
        }

        item { Text("تدقيق الصرف (" + reviews.size + ")", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        if (reviews.isEmpty()) item { Text("لا ملاحظات: كل صرف مطابق لأوامره.", fontSize = 12.sp, color = Color(0xFF15803D)) }
        items(reviews, key = { it.kind + "|" + it.orderId + "|" + it.title }) { r ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, if (r.kind == "DIFF") Color(0xFFE2E8F0) else Color(0xFFFCA5A5)),
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(r.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(r.detail, fontSize = 12.sp, color = Color(0xFF334155))
                    if (canEdit && (r.kind == "CANCELLED" || r.kind == "ORPHAN")) {
                        OutlinedButton(onClick = { confirmFor = r }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                            Text("عكس صرف هذا الأمر", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item { Text("نقص المواد للأوامر المفتوحة", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        if (shortages.isEmpty()) item { Text("لا نقص: المتاح يغطي احتياج الأوامر المفتوحة (للمواد ذات الرصيد المسجَّل).", fontSize = 12.sp, color = Color(0xFF15803D)) }
        byOrder.forEach { (orderNo, list) ->
            item(key = "sh_" + orderNo) {
                Column(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(orderNo + " — " + list.first().product + "  (" + list.first().status + ")", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    list.forEach { s ->
                        if (s.noBalance) {
                            Text(s.itemName + ": بلا رصيد مسجَّل (يلزم جرد افتتاحي) — يحتاج " + fmtQty(s.required) + " " + uomLabel(s.uom), fontSize = 12.sp, color = Color(0xFFB45309))
                        } else {
                            Text(
                                s.itemName + ": يحتاج " + fmtQty(s.required) + " والمتاح " + fmtQty(s.available) + " " + uomLabel(s.uom) +
                                    " — ينقص " + fmtQty(s.required - s.available),
                                fontSize = 12.sp, color = Color(0xFFB91C1C)
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    confirmFor?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text("عكس صرف الأمر", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text("ستُسجَّل حركات عكسية تعيد المواد المصروفة لهذا الأمر إلى المخزن. لا يُحذف شيء من السجل.\n" + r.title, fontSize = 13.sp) },
            confirmButton = {
                Button(onClick = { vm.reverseOrderIssues(r.orderId, userName); confirmFor = null }) { Text("عكس") }
            },
            dismissButton = { TextButton(onClick = { confirmFor = null }) { Text("إلغاء") } }
        )
    }
}
