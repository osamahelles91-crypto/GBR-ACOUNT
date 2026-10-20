package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.A4WysiwygPreview
import com.example.PrintConfigPanel
import com.example.data.*
import com.example.ui.theme.GBRDarkIndigo

/**
 * نافذة طباعة/تصدير PDF لعينة أبحاث وتطوير — تعيد استخدام نفس لوحة الإعدادات والمعاينة ومولّد الوثيقة الخاص بالتركيبات
 * عبر تحويل العينة إلى «تركيبة مؤقتة» (لا تُحفظ في قاعدة البيانات).
 */
@Composable
fun SamplePrintDialog(
    sample: DevelopmentSample,
    items: List<Map<String, Any>>,
    results: List<Map<String, String>>,
    notes: String,
    targetWeightKg: Double,
    rawMaterials: List<RawMaterial>,
    allPackagings: List<GbrViewModel.CustomPackaging>,
    allQCDefinitions: List<QualityTest>,
    onDismiss: () -> Unit
) {
    var useRealNames by remember { mutableStateOf(true) }
    var usePercentage by remember { mutableStateOf(false) }
    var includeCost by remember { mutableStateOf(false) }

    fun num(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.3f", v)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    val detailItems = remember(items, rawMaterials) {
        items.mapIndexed { i, m ->
            val id = (m["rawMaterialId"] as? String) ?: (m["rawMaterialId"] as? Int)?.toString() ?: ""
            val name = (m["rawMaterialName"] as? String) ?: ""
            val qty = (m["originalQuantityMultiplier"] as? Number)?.toDouble() ?: 0.0
            val rm = rawMaterials.firstOrNull { it.id == id }
            FormulationItemWithDetails(
                id = "${sample.id}_$i",
                formulationId = sample.id,
                rawMaterialId = id,
                quantityMultiplier = qty,
                rawMaterialName = name,
                rawMaterialProductionName = rm?.productionName?.ifBlank { name } ?: name,
                rawMaterialUnit = "كجم",
                needsGrinding = false,
                grindingDurationMinutes = 0,
                rawMaterialPrice = rm?.price ?: 0.0,
                rawMaterialPriceUnit = rm?.priceUnit ?: "شيكل",
                simulatedPrice = null,
                sequence = i
            )
        }
    }

    val formula = remember(sample, detailItems, results, notes, targetWeightKg, includeCost) {
        val parts = mutableListOf<String>()
        if (sample.targetGoal.isNotBlank()) parts.add("الهدف: ${sample.targetGoal}")
        parts.add("الوزن المستهدف: ${num(targetWeightKg)} كجم")
        if (results.isNotEmpty()) {
            parts.add("نتائج الفحوصات: " + results.joinToString(" | ") { "${it["name"]}: ${it["value"]}" })
        }
        if (notes.isNotBlank()) parts.add("ملاحظات: $notes")
        if (includeCost) {
            val tot = detailItems.sumOf { it.quantityMultiplier }
            val cost = detailItems.sumOf { it.quantityMultiplier * it.rawMaterialPrice }
            if (tot > 0.0) parts.add("تكلفة الكيلو (مواد خام): ${num(cost / tot)} شيكل")
        }
        Formulation(
            id = sample.id,
            name = sample.sampleName,
            code = sample.sampleNumber,
            description = sample.targetGoal,
            version = sample.sampleNumber,
            // الوثيقة تعرض «معتمدة للإنتاج» ما لم تحتوِ الحالة على «مسودة» — العينة غير المعتمدة تُعرض تحت المراجعة
            status = if (sample.isApproved) "معتمدة" else "مسودة عينة - ${sample.status ?: ""}",
            createdAt = sample.createdAt,
            notes = parts.joinToString("   ◆   ")
        )
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val config = androidx.compose.ui.platform.LocalConfiguration.current
        val isTablet = config.screenWidthDp > 768
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isTablet) 0.95f else 0.98f)
                .fillMaxHeight(if (isTablet) 0.92f else 0.98f),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF8FAFC),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) { Icon(imageVector = Icons.Default.Close, contentDescription = "إغلاق") }
                    Text(
                        text = "📊 مراجعة ومعاينة وثيقة التصدير الفنية الرسمية PDF",
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GBRDarkIndigo, textAlign = TextAlign.Right
                    )
                }
                HorizontalDivider(color = Color(0xFFE2E8F0), thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = includeCost, onCheckedChange = { includeCost = it })
                    Text("تضمين تكلفة الكيلو (مواد خام فقط) في ملاحظات الوثيقة", fontSize = 12.sp, color = Color(0xFF334155))
                }

                val config2: @Composable (Modifier) -> Unit = { mod ->
                    Box(mod) {
                        PrintConfigPanel(
                            formula = formula,
                            formulationItems = detailItems,
                            qualityTests = emptyList(),
                            recipePhases = emptyList(),
                            allPackagings = allPackagings,
                            allQCDefinitions = allQCDefinitions,
                            useRealNames = useRealNames,
                            onUseRealNamesChange = { useRealNames = it },
                            usePercentage = usePercentage,
                            onUsePercentageChange = { usePercentage = it },
                            onClose = onDismiss
                        )
                    }
                }
                val preview: @Composable (Modifier) -> Unit = { mod ->
                    Box(mod.background(Color(0xFFE2E8F0), RoundedCornerShape(16.dp)).padding(12.dp)) {
                        A4WysiwygPreview(
                            formula = formula,
                            items = detailItems,
                            qualityTests = emptyList(),
                            recipePhases = emptyList(),
                            useRealNames = useRealNames,
                            usePercentage = usePercentage
                        )
                    }
                }

                if (isTablet) {
                    Row(Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        config2(Modifier.weight(0.32f).fillMaxHeight())
                        preview(Modifier.weight(0.68f).fillMaxHeight())
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        config2(Modifier.fillMaxWidth())
                        preview(Modifier.fillMaxWidth().height(550.dp))
                    }
                }
            }
        }
    }
}
