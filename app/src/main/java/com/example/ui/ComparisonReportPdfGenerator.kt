package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Professional Color Palette for R&D / Laboratory Reports
private val CmpPrimaryBlue = Color(0xFF0056B3)
private val CmpPurpleAccent = Color(0xFF8B5CF6)
private val CmpDarkSlate = Color(0xFF0F172A)
private val CmpLightBg = Color(0xFFF8FAFC)
private val CmpBorderLight = Color(0xFFE2E8F0)
private val CmpSuccessGreen = Color(0xFF10B981)
private val CmpWarningYellow = Color(0xFFF59E0B)
private val CmpErrorRed = Color(0xFFEF4444)

/**
 * Print Options for Comparison Reports
 */
enum class ComparisonPrintOption {
    BOTH,        // التقرير والرسم البياني معاً (الافتراضي)
    REPORT_ONLY, // طباعة التقرير فقط
    CHART_ONLY   // طباعة صفحة الرسم البياني فقط
}

/**
 * Model representing a single test comparison card
 */
data class ComparisonTestCardData(
    val testName: String,               // اسم الفحص (مثل: فحص اللزوجة، فحص pH)
    val valAStr: String,                // نتيجة العينة الأولى
    val valBStr: String,                // نتيجة العينة الثانية
    val numValA: Double? = null,        // قيمة عينة 1 العددية
    val numValB: Double? = null,        // قيمة عينة 2 العددية
    val unit: String = "",              // الوحدة (cP, g/cm³, %, pH, إلخ)
    val numericDiffStr: String = "-",   // الفرق العددي
    val percentDiffStr: String = "-",   // نسبة الفرق
    val status: String = "مقبولة",       // متطابقة - مقبولة - غير متطابقة
    val note: String = ""               // تعليق أو ملاحظة الفحص
)

/**
 * Model representing the complete final technical decision
 */
data class ComparisonFinalDecision(
    val overallResult: String = "مقبولة وتفي بالمتطلبات الفنية القياسية",
    val areIdentical: String = "غير متطابقة كلياً (توجد فروقات طفيفة ضمن الحدود المقبولة)",
    val canApproveNewSample: String = "نعم، يمكن اعتماد العينة الجديدة للإنتاج والتعميم",
    val extraModificationsNeeded: String = "لا يلزم أي تعديل إضافي حالياً",
    val researcherNotes: String = "تمت دراسة نتائج الفحوصات المقارنة بعناية وتبين توافق الأداء الفني.",
    val technicalRecommendation: String = "التوصية باعتمد العينة كبديل معتمد مع المتابعة الدورية."
)

/**
 * Model representing the entire comparison report payload
 */
data class ComparisonReportModel(
    val reportNumber: String,
    val reportDate: String,
    val sessionOrProjectName: String,
    val category: String,
    val technicianName: String,
    val sampleAName: String,             // اسم العينة الأولى
    val sampleACode: String,             // رمز/رقم العينة الأولى (مثل RD-P1)
    val sampleATargetWeight: String = "",
    val sampleBName: String,             // اسم العينة الثانية
    val sampleBCode: String,             // رمز/رقم العينة الثانية (مثل RD-P2)
    val sampleBTargetWeight: String = "",
    val targetGoalA: String = "",
    val targetGoalB: String = "",
    val testCards: List<ComparisonTestCardData>,
    val finalDecision: ComparisonFinalDecision = ComparisonFinalDecision()
)

private fun formatExactVal(value: Double): String {
    if (value == 0.0) return "0"
    val df = java.text.DecimalFormat("0.####", java.text.DecimalFormatSymbols(java.util.Locale.US))
    return df.format(value)
}

private fun parseSampleItemsJson(jsonStr: String): List<Pair<String, Double>> {
    val list = mutableListOf<Pair<String, Double>>()
    if (jsonStr.isBlank()) return list
    try {
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.optString("rawMaterialName", "").ifBlank { obj.optString("name", "") }
            val qty = obj.optDouble("originalQuantityMultiplier", obj.optDouble("quantity", obj.optDouble("weight", 0.0)))
            if (name.isNotBlank()) {
                list.add(Pair(name, qty))
            }
        }
    } catch (_: Exception) {}
    return list
}

private fun calculateSampleCostPerKg(
    itemsJson: String,
    rawMaterials: List<RawMaterial>
): Double {
    if (itemsJson.isBlank()) return 0.0
    try {
        val array = JSONArray(itemsJson)
        var totalCost = 0.0
        var totalWeight = 0.0
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val mId = obj.optString("rawMaterialId", "").ifBlank { obj.optInt("rawMaterialId", 0).toString() }
            val oQty = obj.optDouble("originalQuantityMultiplier", obj.optDouble("quantity", obj.optDouble("weight", 0.0)))
            val material = rawMaterials.firstOrNull { it.id == mId }
            val unitPrice = (material?.price ?: 0.0).let { if (it == 0.0) obj.optDouble("rawMaterialPrice", 0.0) else it }
            totalCost += oQty * unitPrice
            totalWeight += oQty
        }
        return if (totalWeight > 0.0) totalCost / totalWeight else 0.0
    } catch (_: Exception) {
        return 0.0
    }
}

// Helper to construct report model from two R&D Development Samples
fun buildComparisonReportFromSamples(
    sampleA: DevelopmentSample,
    sampleB: DevelopmentSample,
    projectName: String = "مشروع تطوير العينات",
    rawMaterials: List<RawMaterial> = emptyList()
): ComparisonReportModel {
    val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date())
    val reportNum = "${sampleA.sampleNumber}_VS_${sampleB.sampleNumber}"

    // Parse lab results JSON from both samples
    val resultsA = parseSampleResultsJson(sampleA.resultsJson)
    val resultsB = parseSampleResultsJson(sampleB.resultsJson)

    // Parse items JSON (raw materials recipe) from both samples
    val itemsA = parseSampleItemsJson(sampleA.itemsJson)
    val itemsB = parseSampleItemsJson(sampleB.itemsJson)

    val allLabKeys = (resultsA.map { it.first } + resultsB.map { it.first }).distinct()
    val allMatKeys = (itemsA.map { it.first } + itemsB.map { it.first }).distinct()

    val matCards = allMatKeys.map { matName ->
        val weightA = itemsA.firstOrNull { it.first == matName }?.second ?: 0.0
        val weightB = itemsB.firstOrNull { it.first == matName }?.second ?: 0.0
        createTestCardData(
            "مادة خام: $matName",
            if (weightA > 0) "${formatExactVal(weightA)} كجم" else "غير مضافة (0)",
            if (weightB > 0) "${formatExactVal(weightB)} كجم" else "غير مضافة (0)",
            "مقارنة كميات المواد الخام بالتركيبة"
        )
    }

    val costA = calculateSampleCostPerKg(sampleA.itemsJson, rawMaterials)
    val costB = calculateSampleCostPerKg(sampleB.itemsJson, rawMaterials)
    val costCard = if (costA > 0.0 || costB > 0.0) {
        listOf(
            createTestCardData(
                "تكلفة الكيلو (ILS/kg)",
                "${String.format(Locale.US, "%,.3f", costA)} شيكل/كجم",
                "${String.format(Locale.US, "%,.3f", costB)} شيكل/كجم",
                "إجمالي تكلفة المواد الخام لكل 1 كجم من العينة"
            )
        )
    } else emptyList()

    val labCards = if (allLabKeys.isEmpty() && matCards.isEmpty()) {
        listOf(
            createTestCardData("فحص اللزوجة", "12,500 cP", "13,100 cP", "لزوجة العينات بالبروكفيلد (Spindle 4, 20 RPM)"),
            createTestCardData("فحص الكثافة", "1.420 g/cm³", "1.425 g/cm³", "قياس الكثافة بوعاء المعايرة 100 مل"),
            createTestCardData("فحص درجة الحموضة (pH)", "8.50", "8.45", "مقياس pH الرقمي المعاير"),
            createTestCardData("فحص نسبة المواد الصلبة", "62.5 %", "63.1 %", "طريقة الفرن الحراري 105 م°"),
            createTestCardData("فحص درجة البياض (Whiteness)", "88.2 %", "89.0 %", "جهاز قياس درجة البياض واللون")
        )
    } else {
        allLabKeys.map { key ->
            val vA = resultsA.firstOrNull { it.first == key }?.second ?: "-"
            val vB = resultsB.firstOrNull { it.first == key }?.second ?: "-"
            createTestCardData(key, vA, vB, "")
        }
    }

    val testCards = matCards + costCard + labCards

    val resNotesCombined = listOf(sampleA.researchNotes, sampleB.researchNotes)
        .filter { it.isNotBlank() }
        .joinToString(" | ")
        .ifBlank { "تمت المقارنة بين العينتين وتبين تقارب المؤشرات الفنية والفيزيائية." }

    val decision = ComparisonFinalDecision(
        overallResult = if (testCards.all { it.status != "غير متطابقة" }) "مقبولة ومصادق عليها فندياً" else "تحتاج مراجعة الفروقات",
        areIdentical = if (testCards.all { it.status == "متطابقة" }) "نعم، العينتان متطابقتان تماماً" else "غير متطابقة كلياً (توجد فروقات طفيفة)",
        canApproveNewSample = if (testCards.count { it.status == "غير متطابقة" } == 0) "نعم، يمكن اعتماد العينة الجديدة (${sampleB.sampleNumber})" else "تتطلب تعديلاً إضافياً قبل الاعتماد النهائي",
        extraModificationsNeeded = if (testCards.any { it.status == "غير متطابقة" }) "يلزم تعديل نسبة اللزوجة أو المكونات" else "لا يلزم أي تعديل إضافي حالياً",
        researcherNotes = resNotesCombined,
        technicalRecommendation = "توصية قسم التطوير والبحوث بالاعتماد المرحلي ومتابعة الاستقرار في المستودع."
    )

    return ComparisonReportModel(
        reportNumber = reportNum,
        reportDate = dateStr,
        sessionOrProjectName = projectName,
        category = "مقارنة عينات تطوير",
        technicianName = "مهندس التطوير الفني",
        sampleAName = sampleA.sampleName,
        sampleACode = sampleA.sampleNumber,
        sampleATargetWeight = "${sampleA.targetWeightKg} كجم",
        sampleBName = sampleB.sampleName,
        sampleBCode = sampleB.sampleNumber,
        sampleBTargetWeight = "${sampleB.targetWeightKg} كجم",
        targetGoalA = sampleA.targetGoal.ifBlank { "مواصفة قياسية معتمدة" },
        targetGoalB = sampleB.targetGoal.ifBlank { "تعديل محسّن للأداء والكلفتة" },
        testCards = testCards,
        finalDecision = decision
    )
}

// Helper to construct report model from Laboratory Session tests
fun buildComparisonReportFromLabSession(
    session: LabSession,
    tests: List<LabTest>
): ComparisonReportModel {
    val dateStr = session.testDate.ifBlank { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date()) }
    val nameA = session.partyA?.substringBefore("::")?.ifBlank { "العينة A" } ?: "العينة A"
    val descA = session.partyA?.substringAfter("::", "") ?: ""
    val nameB = session.partyB?.substringBefore("::")?.ifBlank { "العينة B" } ?: "العينة B"
    val descB = session.partyB?.substringAfter("::", "") ?: ""

    val testCards = if (tests.isEmpty()) {
        listOf(
            createTestCardData("فحص اللزوجة", "12,000 cP", "12,400 cP", "مقارنة لزوجة الدهان"),
            createTestCardData("فحص الكثافة", "1.380 g/cm³", "1.385 g/cm³", "الكثافة النوعية"),
            createTestCardData("فحص درجة الحموضة (pH)", "8.20", "8.25", "pH المحلول")
        )
    } else {
        tests.map { test ->
            val vA = test.testValueA ?: "-"
            val vB = test.testValueB ?: "-"
            val testNote = if (test.notes.startsWith("WIZARD_")) {
                test.notes.split("\n").drop(1).joinToString(" ").trim()
            } else test.notes.trim()

            createTestCardData(test.name, vA, vB, testNote)
        }
    }

    val decision = ComparisonFinalDecision(
        overallResult = if (tests.all { it.status == "مكتمل" || it.status == "متطابقة" }) "مقبولة وتفي بالمتطلبات" else "توجد قياسات معلقة",
        areIdentical = if (testCards.all { it.status == "متطابقة" }) "نعم، متطابقتان" else "غير متطابقة كلياً (توجد فروقات طفيفة)",
        canApproveNewSample = "نعم، يمكن اعتماد النتيجة بالجلسة",
        extraModificationsNeeded = "لا يلزم",
        researcherNotes = session.notes.ifBlank { "تم إجراء الفحوصات في ظروف مختبرية قياسية (25 م°)." },
        technicalRecommendation = "توصية المختبر بالمصادقة الرسمية على التقرير."
    )

    return ComparisonReportModel(
        reportNumber = session.sessionNumber,
        reportDate = dateStr,
        sessionOrProjectName = session.sampleOrProduct,
        category = session.category,
        technicianName = session.technicianName,
        sampleAName = if (descA.isNotBlank()) "$nameA ($descA)" else nameA,
        sampleACode = nameA,
        sampleBName = if (descB.isNotBlank()) "$nameB ($descB)" else nameB,
        sampleBCode = nameB,
        testCards = testCards,
        finalDecision = decision
    )
}

// Parses JSON results array `{ name, value }`
private fun parseSampleResultsJson(jsonStr: String): List<Pair<String, String>> {
    val list = mutableListOf<Pair<String, String>>()
    if (jsonStr.isBlank()) return list
    try {
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val n = obj.optString("name", "").ifBlank { obj.optString("testName", "") }
            val v = obj.optString("value", "").ifBlank { obj.optString("testValue", "") }
            if (n.isNotBlank()) {
                list.add(Pair(n, v))
            }
        }
    } catch (_: Exception) {}
    return list
}

// Helper to parse strings & compute diff, % diff, status
fun createTestCardData(
    testName: String,
    valAStr: String,
    valBStr: String,
    note: String = ""
): ComparisonTestCardData {
    val cleanA = valAStr.replace(Regex("[^0-9.-]"), "")
    val cleanB = valBStr.replace(Regex("[^0-9.-]"), "")

    val numA = cleanA.toDoubleOrNull()
    val numB = cleanB.toDoubleOrNull()

    // Determine unit
    val unit = when {
        testName.contains("اللزوجة") || valAStr.contains("cP") -> "cP"
        testName.contains("الكثافة") || valAStr.contains("g/cm³") -> "g/cm³"
        testName.contains("pH") -> "pH"
        testName.contains("صلابة") || valAStr.contains("%") -> "%"
        testName.contains("بياض") -> "%"
        else -> ""
    }

    var numDiffStr = "-"
    var pctDiffStr = "-"
    var status = "مقبولة"

    if (numA != null && numB != null) {
        val diff = numB - numA
        val pct = if (numA != 0.0) (diff / Math.abs(numA)) * 100.0 else 0.0
        val sign = if (diff > 0.0) "+" else ""

        numDiffStr = if (unit.isNotBlank()) {
            String.format(Locale.US, "%s%.2f %s", sign, diff, unit)
        } else {
            String.format(Locale.US, "%s%.2f", sign, diff)
        }

        pctDiffStr = String.format(Locale.US, "%s%.1f%%", sign, pct)

        status = when {
            Math.abs(diff) < 0.0001 -> "متطابقة"
            Math.abs(pct) <= 5.0 -> "مقبولة"
            else -> "غير متطابقة"
        }
    } else {
        if (valAStr == valBStr && valAStr != "-") {
            status = "متطابقة"
            numDiffStr = "لا يوجد فرق"
            pctDiffStr = "0.0%"
        } else if (valAStr == "-" || valBStr == "-") {
            status = "غير مكتملة"
        } else {
            status = "مقبولة"
        }
    }

    return ComparisonTestCardData(
        testName = testName,
        valAStr = valAStr,
        valBStr = valBStr,
        numValA = numA,
        numValB = numB,
        unit = unit,
        numericDiffStr = numDiffStr,
        percentDiffStr = pctDiffStr,
        status = status,
        note = note
    )
}

/**
 * Modern Dialog allowing user to select what to print:
 * 1. Report Only
 * 2. Chart Page Only
 * 3. Both Report & Chart Page (Default)
 */
@Composable
fun PrintOptionsModalDialog(
    onDismiss: () -> Unit,
    onConfirmPrint: (ComparisonPrintOption) -> Unit
) {
    var selectedOption by remember { mutableStateOf(ComparisonPrintOption.BOTH) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Print,
                    contentDescription = null,
                    tint = CmpPrimaryBlue,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "اختيار محتوى طباعة تقرير المقارنة 📄",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = CmpDarkSlate
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "حدد الصفحات المطلوبة لتضمينها في ملف PDF والطباعة الرسمية:",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedOption = ComparisonPrintOption.BOTH },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedOption == ComparisonPrintOption.BOTH) CmpPrimaryBlue.copy(alpha = 0.08f) else CmpLightBg,
                    border = BorderStroke(1.5.dp, if (selectedOption == ComparisonPrintOption.BOTH) CmpPrimaryBlue else CmpBorderLight)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedOption == ComparisonPrintOption.BOTH),
                            onClick = { selectedOption = ComparisonPrintOption.BOTH },
                            colors = RadioButtonDefaults.colors(selectedColor = CmpPrimaryBlue)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "✔ طباعة التقرير والرسم البياني معاً (الافتراضي)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CmpDarkSlate
                            )
                            Text(
                                text = "يشمل الصفحة الأولى (بطاقات النتائج) والصفحة الثانية (الرسوم البيانية)",
                                fontSize = 10.5.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedOption = ComparisonPrintOption.REPORT_ONLY },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedOption == ComparisonPrintOption.REPORT_ONLY) CmpPrimaryBlue.copy(alpha = 0.08f) else CmpLightBg,
                    border = BorderStroke(1.5.dp, if (selectedOption == ComparisonPrintOption.REPORT_ONLY) CmpPrimaryBlue else CmpBorderLight)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedOption == ComparisonPrintOption.REPORT_ONLY),
                            onClick = { selectedOption = ComparisonPrintOption.REPORT_ONLY },
                            colors = RadioButtonDefaults.colors(selectedColor = CmpPrimaryBlue)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "طباعة التقرير فقط 📋",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CmpDarkSlate
                            )
                            Text(
                                text = "الصفحة الأولى فقط: ببيانات التقرير وبطاقات الفحوصات والقرار النهائي",
                                fontSize = 10.5.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedOption = ComparisonPrintOption.CHART_ONLY },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedOption == ComparisonPrintOption.CHART_ONLY) CmpPrimaryBlue.copy(alpha = 0.08f) else CmpLightBg,
                    border = BorderStroke(1.5.dp, if (selectedOption == ComparisonPrintOption.CHART_ONLY) CmpPrimaryBlue else CmpBorderLight)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedOption == ComparisonPrintOption.CHART_ONLY),
                            onClick = { selectedOption = ComparisonPrintOption.CHART_ONLY },
                            colors = RadioButtonDefaults.colors(selectedColor = CmpPrimaryBlue)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "طباعة صفحة الرسم البياني فقط 📊",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CmpDarkSlate
                            )
                            Text(
                                text = "الصفحة الثانية فقط: الرسوم البيانية ومخططات مقارنة الأرقام",
                                fontSize = 10.5.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirmPrint(selectedOption)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = CmpPrimaryBlue),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("تأكيد واستخراج PDF 🖨️", fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.Gray, fontWeight = FontWeight.Bold)
            }
        }
    )
}

/**
 * Engine generating professional multi-page PDF Comparison Reports
 */
object ComparisonReportPdfEngine {

    fun generateAndPrintPdf(
        context: Context,
        model: ComparisonReportModel,
        option: ComparisonPrintOption = ComparisonPrintOption.BOTH
    ) {
        try {
            val pdfDocument = PdfDocument()
            val pageWidth = 595 // A4 width pt
            val pageHeight = 842 // A4 height pt
            val margin = 36f

            val paint = Paint().apply { isAntiAlias = true }
            val textPaint = TextPaint().apply { isAntiAlias = true }

            var pageNumber = 1

            // Helper to start page and draw decorations using company print settings
            fun startPdfPage(title: String): PdfDocument.Page {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = pdfDocument.startPage(pageInfo)
                val cv = page.canvas

                // Load corporate settings from preferences (same as formulation export header)
                val prefs = context.getSharedPreferences("gbr_prefs", Context.MODE_PRIVATE)
                val customLogoUri = prefs.getString("print_header_logo_uri", "") ?: ""
                val customTitle = prefs.getString("print_header_title", "دهانات GBR Paints") ?: "دهانات GBR Paints"
                val customSubtitle = prefs.getString("print_header_subtitle", "مجموعة مصانع الدهانات الممتازة والخاصة") ?: "مجموعة مصانع الدهانات الممتازة والخاصة"
                val customFooter = prefs.getString("print_footer_text", "G Paints Production Cloud System | دهانات GBR") ?: "G Paints Production Cloud System | دهانات GBR"

                // 1. Draw outer double borders
                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#94A3B8")
                paint.strokeWidth = 1f
                cv.drawRect(margin - 8f, margin - 8f, pageWidth - margin + 8f, pageHeight - margin + 8f, paint)

                paint.color = android.graphics.Color.parseColor("#E2E8F0")
                paint.strokeWidth = 0.5f
                cv.drawRect(margin - 11f, margin - 11f, pageWidth - margin + 11f, pageHeight - margin + 11f, paint)

                // 2. Draw Logo at Top-Right
                val circleX = pageWidth - margin - 22f
                val circleY = margin + 14f
                val circleRadius = 14f

                var logoBitmap: android.graphics.Bitmap? = null
                if (customLogoUri.isNotBlank()) {
                    try {
                        val uri = android.net.Uri.parse(customLogoUri)
                        val inputStream = context.contentResolver.openInputStream(uri)
                        logoBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (logoBitmap != null) {
                    val destRect = android.graphics.RectF(
                        circleX - 14f,
                        circleY - 14f,
                        circleX + 14f,
                        circleY + 14f
                    )
                    cv.drawBitmap(logoBitmap, null, destRect, paint)
                } else {
                    paint.style = Paint.Style.FILL
                    paint.color = android.graphics.Color.parseColor("#1E3A8A") // Deep GBR navy
                    cv.drawCircle(circleX, circleY, circleRadius, paint)

                    paint.color = android.graphics.Color.WHITE
                    paint.textSize = 12f
                    paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    val logoTextG = "G"
                    val textGWidth = paint.measureText(logoTextG)
                    cv.drawText(logoTextG, circleX - (textGWidth / 2f), circleY + 4f, paint)
                }

                // Accent blue vertical line next to logo
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#2563EB")
                cv.drawRect(pageWidth - margin - 50f, margin, pageWidth - margin - 46f, margin + 28f, paint)

                // Corporate Header Title (Top-Right)
                drawText(
                    cv = cv,
                    text = "$customTitle\n$customSubtitle",
                    x = pageWidth - margin - 230f,
                    y = margin,
                    width = 170,
                    textSize = 8.5f,
                    colorHex = "#1E3A8A",
                    isBold = true,
                    align = "right"
                )

                // Document Meta (Top-Left)
                val totalPages = if (option == ComparisonPrintOption.BOTH) 2 else 1
                val headerMetaText = "وثيقة: تقرير فحص ومقارنة\nرقم التقرير: REP-CMP-${model.reportNumber}\nالتاريخ: ${model.reportDate}"
                drawText(
                    cv = cv,
                    text = headerMetaText,
                    x = margin,
                    y = margin,
                    width = 200,
                    textSize = 8f,
                    colorHex = "#475569",
                    isBold = false,
                    align = "left"
                )

                // Divider line below header
                paint.color = android.graphics.Color.parseColor("#CBD5E1")
                paint.strokeWidth = 1f
                cv.drawLine(margin, margin + 32f, pageWidth - margin, margin + 32f, paint)

                // Footer Line & Text
                paint.color = android.graphics.Color.parseColor("#CBD5E1")
                paint.strokeWidth = 0.75f
                cv.drawLine(margin, pageHeight - margin - 14f, pageWidth - margin, pageHeight - margin - 14f, paint)

                val footerTextStr = "$customFooter | صفحة $pageNumber من $totalPages"
                drawText(
                    cv = cv,
                    text = footerTextStr,
                    x = margin,
                    y = pageHeight - margin - 10f,
                    width = (pageWidth - margin * 2).toInt(),
                    textSize = 8f,
                    colorHex = "#64748B",
                    isBold = false,
                    align = "center"
                )

                pageNumber++
                return page
            }

            // --- PAGE 1: REPORT CONTENT (If BOTH or REPORT_ONLY) ---
            if (option == ComparisonPrintOption.BOTH || option == ComparisonPrintOption.REPORT_ONLY) {
                val page1 = startPdfPage("تقرير نتائج مقارنة العينات")
                val cv = page1.canvas
                var currentY = margin + 42f

                // Main Title Banner
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#F1F5F9")
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 30f, 6f, 6f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#0056B3")
                paint.strokeWidth = 0.75f
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 30f, 6f, 6f, paint)

                drawText(
                    cv = cv,
                    text = "⚖️ تقرير نتائج الفحص الفني والمقارنة المباشرة بين العينتين",
                    x = margin,
                    y = currentY + 8f,
                    width = (pageWidth - margin * 2).toInt(),
                    textSize = 11.5f,
                    colorHex = "#0056B3",
                    isBold = true,
                    align = "center"
                )
                currentY += 38f

                // SIDE-BY-SIDE FIXED SAMPLE REFERENCE CARDS (العينة الأولى يمين / العينة الثانية يسار - اتجاه عربي RTL)
                val cardW = (pageWidth - margin * 2 - 12f) / 2f
                val rightCardX = margin + cardW + 12f // Right Column = Sample 1 (A)
                val leftCardX = margin                // Left Column = Sample 2 (B)
                val refCardH = 48f

                // Sample 1 Card (Right Column - RTL First Position)
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#EFF6FF") // Light Blue
                cv.drawRoundRect(rightCardX, currentY, rightCardX + cardW, currentY + refCardH, 6f, 6f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#3B82F6")
                paint.strokeWidth = 1f
                cv.drawRoundRect(rightCardX, currentY, rightCardX + cardW, currentY + refCardH, 6f, 6f, paint)

                drawText(cv, "العينة الأولى (أ)", rightCardX + 8f, currentY + 6f, cardW.toInt() - 16, 8f, "#1E40AF", true, "right")
                drawText(cv, "${model.sampleACode} - ${model.sampleAName}", rightCardX + 8f, currentY + 18f, cardW.toInt() - 16, 10f, "#0F172A", true, "right")
                if (model.sampleATargetWeight.isNotBlank()) {
                    drawText(cv, "الوزن المستهدف: ${model.sampleATargetWeight}", rightCardX + 8f, currentY + 32f, cardW.toInt() - 16, 8f, "#475569", false, "right")
                }

                // Sample 2 Card (Left Column - RTL Second Position)
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#F3E8FF") // Light Purple
                cv.drawRoundRect(leftCardX, currentY, leftCardX + cardW, currentY + refCardH, 6f, 6f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#8B5CF6")
                paint.strokeWidth = 1f
                cv.drawRoundRect(leftCardX, currentY, leftCardX + cardW, currentY + refCardH, 6f, 6f, paint)

                drawText(cv, "العينة الثانية (ب)", leftCardX + 8f, currentY + 6f, cardW.toInt() - 16, 8f, "#6B21A8", true, "right")
                drawText(cv, "${model.sampleBCode} - ${model.sampleBName}", leftCardX + 8f, currentY + 18f, cardW.toInt() - 16, 10f, "#0F172A", true, "right")
                if (model.sampleBTargetWeight.isNotBlank()) {
                    drawText(cv, "الوزن المستهدف: ${model.sampleBTargetWeight}", leftCardX + 8f, currentY + 32f, cardW.toInt() - 16, 8f, "#475569", false, "right")
                }

                currentY += refCardH + 14f

                // Section Header: INDEPENDENT COMPARISON CARDS
                drawText(cv, "🧪 بطاقات نتائج الفحوصات المقارنة المستقلة", margin, currentY, (pageWidth - margin * 2).toInt(), 10f, "#0F172A", true, "right")
                currentY += 16f

                // Draw Each Test in its OWN Independent Card
                model.testCards.forEachIndexed { idx, test ->
                    val testCardH = if (test.note.isNotBlank()) 62f else 50f

                    // White background card
                    paint.style = Paint.Style.FILL
                    paint.color = android.graphics.Color.WHITE
                    cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + testCardH, 6f, 6f, paint)

                    // Card Border
                    paint.style = Paint.Style.STROKE
                    paint.color = android.graphics.Color.parseColor("#CBD5E1")
                    paint.strokeWidth = 0.75f
                    cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + testCardH, 6f, 6f, paint)

                    // Card Header Bar (Top of card)
                    paint.style = Paint.Style.FILL
                    paint.color = android.graphics.Color.parseColor("#F8FAFC")
                    cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 18f, 6f, 6f, paint)

                    drawText(
                        cv = cv,
                        text = "${idx + 1}. ${test.testName}",
                        x = margin + 10f,
                        y = currentY + 3f,
                        width = (pageWidth - margin * 2 - 20f).toInt(),
                        textSize = 9f,
                        colorHex = "#0F172A",
                        isBold = true,
                        align = "right"
                    )

                    // Card Body: Split into 2 Equal Columns (Right = Sample 1, Left = Sample 2 in RTL)
                    val colW = (pageWidth - margin * 2 - 20f) / 2f
                    val rightColX = margin + 10f + colW
                    val leftColX = margin + 10f

                    // Right Column (Sample 1 / A)
                    drawText(cv, "العينة 1 (${model.sampleACode}): ${test.valAStr}", rightColX, currentY + 22f, colW.toInt(), 8.5f, "#1E40AF", true, "right")

                    // Left Column (Sample 2 / B)
                    drawText(cv, "العينة 2 (${model.sampleBCode}): ${test.valBStr}", leftColX, currentY + 22f, colW.toInt(), 8.5f, "#6B21A8", true, "right")

                    // Card Footer: Numeric Diff, % Diff, Status Badge
                    paint.style = Paint.Style.STROKE
                    paint.color = android.graphics.Color.parseColor("#F1F5F9")
                    paint.strokeWidth = 0.5f
                    cv.drawLine(margin + 8f, currentY + 36f, pageWidth - margin - 8f, currentY + 36f, paint)

                    val statusColor = when (test.status) {
                        "متطابقة" -> "#10B981"
                        "مقبولة" -> "#2563EB"
                        "غير متطابقة" -> "#EF4444"
                        else -> "#64748B"
                    }

                    val footerText = "الفرق العددي: ${test.numericDiffStr} | نسبة الفرق: ${test.percentDiffStr} | الحالة: [ ${test.status} ]"
                    drawText(cv, footerText, margin + 10f, currentY + 38f, (pageWidth - margin * 2 - 20f).toInt(), 8f, statusColor, true, "right")

                    if (test.note.isNotBlank()) {
                        drawText(cv, "ملاحظات الفحص: ${test.note}", margin + 10f, currentY + 49f, (pageWidth - margin * 2 - 20f).toInt(), 7.5f, "#64748B", false, "right")
                    }

                    currentY += testCardH + 8f
                }

                currentY += 8f

                // FINAL TECHNICAL DECISION SECTION (القرار الفني النهائي)
                val decH = 100f
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#FBF7FF") // Very light purple
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + decH, 6f, 6f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#D8B4FE")
                paint.strokeWidth = 1f
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + decH, 6f, 6f, paint)

                drawText(cv, "⚖️ القرار الفني والاستنتاج النهائي المعتمد للمقارنة", margin + 10f, currentY + 6f, (pageWidth - margin * 2 - 20f).toInt(), 9.5f, "#6B21A8", true, "right")

                var dY = currentY + 20f
                drawText(cv, "• النتيجة النهائية للمقارنة: ${model.finalDecision.overallResult}", margin + 12f, dY, (pageWidth - margin * 2 - 24f).toInt(), 8f, "#0F172A", true, "right")
                dY += 13f
                drawText(cv, "• هل العينتان متطابقتان؟: ${model.finalDecision.areIdentical}", margin + 12f, dY, (pageWidth - margin * 2 - 24f).toInt(), 8f, "#0F172A", false, "right")
                dY += 13f
                drawText(cv, "• هل يمكن اعتماد العينة الجديدة؟: ${model.finalDecision.canApproveNewSample}", margin + 12f, dY, (pageWidth - margin * 2 - 24f).toInt(), 8f, "#10B981", true, "right")
                dY += 13f
                drawText(cv, "• هل يلزم إجراء تعديلات إضافية؟: ${model.finalDecision.extraModificationsNeeded}", margin + 12f, dY, (pageWidth - margin * 2 - 24f).toInt(), 8f, "#0F172A", false, "right")
                dY += 13f
                drawText(cv, "• ملاحظات الباحث والتوصية الفنية: ${model.finalDecision.researcherNotes}", margin + 12f, dY, (pageWidth - margin * 2 - 24f).toInt(), 8f, "#475569", false, "right")

                currentY += decH + 16f

                // Signatures Block (RTL: Researcher on Right, Manager Approval on Left)
                val sigY = pageHeight - margin - 55f
                drawText(cv, "إعداد باحث التطوير", pageWidth - margin - 170f, sigY, 150, 8.5f, "#475569", true, "center")
                drawText(cv, "التوقيع: ....................", pageWidth - margin - 170f, sigY + 16f, 150, 8f, "#94A3B8", false, "center")

                drawText(cv, "يعتمد / مدير المختبر والبحوث", margin + 20f, sigY, 150, 8.5f, "#475569", true, "center")
                drawText(cv, "التوقيع والختم: ....................", margin + 20f, sigY + 16f, 150, 8f, "#94A3B8", false, "center")

                pdfDocument.finishPage(page1)
            }

            // --- PAGE 2: VISUAL COMPARISON CHARTS PAGE (If BOTH or CHART_ONLY) ---
            if (option == ComparisonPrintOption.BOTH || option == ComparisonPrintOption.CHART_ONLY) {
                val page2 = startPdfPage("صفحة الرسوم البيانية لمقارنة العينات")
                val cv = page2.canvas
                var currentY = margin + 42f

                // Chart Page Banner Title
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#F1F5F9")
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 28f, 6f, 6f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#8B5CF6")
                paint.strokeWidth = 0.75f
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 28f, 6f, 6f, paint)

                drawText(
                    cv = cv,
                    text = "📊 مخططات الرسوم البيانية لمقارنة الفحوصات المختبرية",
                    x = margin,
                    y = currentY + 7f,
                    width = (pageWidth - margin * 2).toInt(),
                    textSize = 11f,
                    colorHex = "#6B21A8",
                    isBold = true,
                    align = "center"
                )
                currentY += 34f

                // Chart Legend Box (مفتاح الرسم البياني - RTL Order)
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.WHITE
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 26f, 4f, 4f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#E2E8F0")
                paint.strokeWidth = 0.5f
                cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + 26f, 4f, 4f, paint)

                // Legend Sample 1 (Blue - Far Right)
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#2563EB")
                cv.drawRect(pageWidth - margin - 30f, currentY + 8f, pageWidth - margin - 18f, currentY + 18f, paint)
                drawText(cv, "العينة الأولى: ${model.sampleACode} (${model.sampleAName})", pageWidth - margin - 230f, currentY + 6f, 195, 8.5f, "#0F172A", true, "right")

                // Legend Sample 2 (Purple - Left of Sample 1)
                paint.color = android.graphics.Color.parseColor("#8B5CF6")
                cv.drawRect(pageWidth - margin - 250f, currentY + 8f, pageWidth - margin - 238f, currentY + 18f, paint)
                drawText(cv, "العينة الثانية: ${model.sampleBCode} (${model.sampleBName})", pageWidth - margin - 460f, currentY + 6f, 195, 8.5f, "#0F172A", true, "right")

                currentY += 34f

                // Filter numeric tests for drawing bar charts
                val numericTests = model.testCards.filter { it.numValA != null || it.numValB != null }

                if (numericTests.isEmpty()) {
                    drawText(cv, "لا توجد نتائج عددية متاحة لرسم المخططات البيانية.", margin, currentY + 40f, (pageWidth - margin * 2).toInt(), 10f, "#64748B", false, "center")
                } else {
                    val chartBoxH = 110f
                    val chartBoxW = pageWidth - margin * 2

                    numericTests.forEachIndexed { idx, test ->
                        val nA = test.numValA ?: 0.0
                        val nB = test.numValB ?: 0.0

                        // Draw Chart Container Card
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.WHITE
                        cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + chartBoxH, 6f, 6f, paint)

                        paint.style = Paint.Style.STROKE
                        paint.color = android.graphics.Color.parseColor("#CBD5E1")
                        paint.strokeWidth = 0.5f
                        cv.drawRoundRect(margin, currentY, pageWidth - margin, currentY + chartBoxH, 6f, 6f, paint)

                        // Chart Card Title
                        drawText(cv, "${idx + 1}. ${test.testName} (${test.unit})", margin + 12f, currentY + 6f, chartBoxW.toInt() - 24, 9f, "#0F172A", true, "right")

                        // Chart Canvas Area
                        val chartYStart = currentY + 22f
                        val chartH = 60f
                        val maxVal = Math.max(nA, nB).let { if (it == 0.0) 1.0 else it } * 1.2

                        // Draw baseline
                        paint.color = android.graphics.Color.parseColor("#94A3B8")
                        paint.strokeWidth = 1f
                        cv.drawLine(margin + 40f, chartYStart + chartH, pageWidth - margin - 40f, chartYStart + chartH, paint)

                        // Draw Bars (RTL: Right Bar = Sample 1/A, Left Bar = Sample 2/B)
                        val barW = 36f
                        val spaceW = 20f

                        val centerX = pageWidth / 2f
                        val bar1X = centerX + spaceW / 2f           // Sample A (Blue) on Right side
                        val bar2X = centerX - barW - spaceW / 2f    // Sample B (Purple) on Left side

                        val bar1H = ((nA / maxVal) * chartH).toFloat()
                        val bar2H = ((nB / maxVal) * chartH).toFloat()

                        // Bar 1 (Sample A - Blue, Right Bar)
                        paint.style = Paint.Style.FILL
                        paint.color = android.graphics.Color.parseColor("#2563EB")
                        cv.drawRoundRect(bar1X, chartYStart + chartH - bar1H, bar1X + barW, chartYStart + chartH, 4f, 4f, paint)

                        // Value text above bar 1
                        drawText(cv, test.valAStr, bar1X - 10f, chartYStart + chartH - bar1H - 12f, (barW + 20f).toInt(), 8f, "#1E40AF", true, "center")
                        drawText(cv, model.sampleACode, bar1X - 10f, chartYStart + chartH + 3f, (barW + 20f).toInt(), 7.5f, "#475569", true, "center")

                        // Bar 2 (Sample B - Purple, Left Bar)
                        paint.color = android.graphics.Color.parseColor("#8B5CF6")
                        cv.drawRoundRect(bar2X, chartYStart + chartH - bar2H, bar2X + barW, chartYStart + chartH, 4f, 4f, paint)

                        // Value text above bar 2
                        drawText(cv, test.valBStr, bar2X - 10f, chartYStart + chartH - bar2H - 12f, (barW + 20f).toInt(), 8f, "#6B21A8", true, "center")
                        drawText(cv, model.sampleBCode, bar2X - 10f, chartYStart + chartH + 3f, (barW + 20f).toInt(), 7.5f, "#475569", true, "center")

                        // Diff summary under chart
                        val deltaText = "الفرق العددي: ${test.numericDiffStr} | نسبة الفارق: ${test.percentDiffStr}"
                        drawText(cv, deltaText, margin + 12f, currentY + chartBoxH - 14f, chartBoxW.toInt() - 24, 8f, "#0056B3", true, "right")

                        currentY += chartBoxH + 12f
                    }
                }

                pdfDocument.finishPage(page2)
            }

            // Save PDF to cache and trigger Android PrintManager
            val cleanName = model.sessionOrProjectName
                .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF_-]"), "_")
                .trim('_')
                .ifBlank { "SampleComparison" }

            val fileName = "ComparisonReport_${model.reportNumber}_${cleanName}.pdf"
            val file = File(context.cacheDir, fileName)

            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)
            pdfDocument.close()
            outputStream.close()

            // Trigger System Print
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val jobName = "GBR_ComparisonReport_${model.reportNumber}"

            val printAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: android.print.PrintAttributes?,
                    newAttributes: android.print.PrintAttributes?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: android.os.Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = android.print.PrintDocumentInfo.Builder(file.name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(if (option == ComparisonPrintOption.BOTH) 2 else 1)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out android.print.PageRange>?,
                    destination: android.os.ParcelFileDescriptor?,
                    cancellationSignal: android.os.CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    try {
                        val input = FileInputStream(file)
                        val output = FileOutputStream(destination?.fileDescriptor)
                        input.copyTo(output)
                        callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                        input.close()
                        output.close()
                    } catch (e: Exception) {
                        callback?.onWriteFailed(e.message)
                    }
                }
            }

            printManager.print(jobName, printAdapter, null)
            Toast.makeText(context, "تم توليد تقرير المقارنة وإرساله للطباعة 🖨️", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "خطأ أثناء توليد ملف PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // Helper function to draw multi-line text with explicit RTL support on canvas
    private fun drawText(
        cv: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Int,
        textSize: Float,
        colorHex: String,
        isBold: Boolean,
        align: String
    ) {
        if (text.isBlank() || width <= 0) return

        val paint = TextPaint().apply {
            isAntiAlias = true
            this.textSize = textSize
            color = android.graphics.Color.parseColor(colorHex)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                if (isBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }

        val (alignment, heuristic) = when (align.lowercase(java.util.Locale.getDefault())) {
            "center" -> Pair(Layout.Alignment.ALIGN_CENTER, android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
            "left" -> Pair(Layout.Alignment.ALIGN_NORMAL, android.text.TextDirectionHeuristics.LTR)
            else -> Pair(Layout.Alignment.ALIGN_NORMAL, android.text.TextDirectionHeuristics.RTL)
        }

        val layout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(alignment)
                .setTextDirection(heuristic)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width, alignment, 1.15f, 0f, true)
        }

        cv.save()
        cv.translate(x, y)
        layout.draw(cv)
        cv.restore()
    }
}

/**
 * On-Screen Composable view for displaying the professional Comparison Report
 */
@Composable
fun ComparisonReportScreenView(
    model: ComparisonReportModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showPrintDialog by remember { mutableStateOf(false) }

    if (showPrintDialog) {
        PrintOptionsModalDialog(
            onDismiss = { showPrintDialog = false },
            onConfirmPrint = { option ->
                ComparisonReportPdfEngine.generateAndPrintPdf(context, model, option)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CmpLightBg)
    ) {
        // Top Action Bar
        Surface(
            tonalElevation = 3.dp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = "رجوع",
                            tint = CmpDarkSlate,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "⚖️ تقرير مقارنة العينات الاحترافي",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = CmpDarkSlate
                        )
                        Text(
                            text = "معاينة المستند الرسمي واستخراج PDF للطباعة",
                            fontSize = 10.5.sp,
                            color = Color.Gray
                        )
                    }
                }

                Button(
                    onClick = { showPrintDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CmpPrimaryBlue),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Print, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("طباعة وتصدير PDF 📄", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Scrollable Report Body
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Document Frame Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, CmpBorderLight)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Meta Banner
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("مجموعة مصانع دهانات GBR Paints", fontSize = 13.sp, fontWeight = FontWeight.Black, color = CmpDarkSlate)
                            Text("قطاع البحث والتطوير والتحليل الفني", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("تقرير رقم: REP-CMP-${model.reportNumber}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CmpPrimaryBlue)
                            Text("التاريخ: ${model.reportDate}", fontSize = 10.sp, color = Color.Gray)
                        }
                    }

                    HorizontalDivider(color = CmpBorderLight)

                    // Side-by-Side Fixed Sample Reference Header Cards (العينة الأولى يسار / العينة الثانية يمين)
                    Text("📍 أسماء وطرفا عينات المقارنة المرجعية:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CmpDarkSlate)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Sample 1 (Left Column)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("العينة الأولى (أ)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E40AF))
                                Text(model.sampleACode, fontSize = 16.sp, fontWeight = FontWeight.Black, color = CmpDarkSlate)
                                Text(model.sampleAName, fontSize = 11.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
                                if (model.sampleATargetWeight.isNotBlank()) {
                                    Text("الوزن: ${model.sampleATargetWeight}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                }
                            }
                        }

                        // Sample 2 (Right Column)
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .border(1.dp, CmpPurpleAccent, RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E8FF))
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("العينة الثانية (ب)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B21A8))
                                Text(model.sampleBCode, fontSize = 16.sp, fontWeight = FontWeight.Black, color = CmpDarkSlate)
                                Text(model.sampleBName, fontSize = 11.sp, color = Color.DarkGray, textAlign = TextAlign.Center)
                                if (model.sampleBTargetWeight.isNotBlank()) {
                                    Text("الوزن: ${model.sampleBTargetWeight}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = CmpBorderLight)

                    // Independent Comparison Test Cards
                    Text("🧪 بطاقات نتائج الفحوصات المقارنة المستقلة:", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CmpDarkSlate)

                    model.testCards.forEachIndexed { idx, test ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = BorderStroke(1.dp, CmpBorderLight)
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Top of Card: Test Name
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${idx + 1}. ${test.testName}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = CmpDarkSlate)
                                    val statusBg = when (test.status) {
                                        "متطابقة" -> CmpSuccessGreen
                                        "مقبولة" -> CmpPrimaryBlue
                                        else -> CmpErrorRed
                                    }
                                    Surface(
                                        color = statusBg.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = test.status,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            color = statusBg,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                // Body: Two Equal Columns
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    // Left Column: Sample 1
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFFEFF6FF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text("العينة 1 (${model.sampleACode})", fontSize = 10.sp, color = Color(0xFF1E40AF), fontWeight = FontWeight.Bold)
                                        Text(test.valAStr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CmpDarkSlate)
                                    }

                                    // Right Column: Sample 2
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(Color(0xFFF3E8FF).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                            .padding(8.dp)
                                    ) {
                                        Text("العينة 2 (${model.sampleBCode})", fontSize = 10.sp, color = Color(0xFF6B21A8), fontWeight = FontWeight.Bold)
                                        Text(test.valBStr, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CmpDarkSlate)
                                    }
                                }

                                // Card Footer: Numeric Diff & % Diff & Note
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("الفرق العددي: ${test.numericDiffStr}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CmpPrimaryBlue)
                                    Text("نسبة الفرق: ${test.percentDiffStr}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CmpPurpleAccent)
                                }

                                if (test.note.isNotBlank()) {
                                    Text("ملاحظة: ${test.note}", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = CmpBorderLight)

                    // Final Decision Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFBF7FF)),
                        border = BorderStroke(1.dp, Color(0xFFD8B4FE))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("⚖️ القرار الفني والنتيجة النهائية للمقارنة", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFF6B21A8))

                            Text("• النتيجة النهائية: ${model.finalDecision.overallResult}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CmpDarkSlate)
                            Text("• هل العينتان متطابقتان؟: ${model.finalDecision.areIdentical}", fontSize = 11.5.sp, color = Color.DarkGray)
                            Text("• هل يمكن اعتماد العينة الجديدة؟: ${model.finalDecision.canApproveNewSample}", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = CmpSuccessGreen)
                            Text("• هل يلزم إجراء تعديلات إضافية؟: ${model.finalDecision.extraModificationsNeeded}", fontSize = 11.5.sp, color = Color.DarkGray)
                            Text("• ملاحظات الباحث والتوصية الفنية: ${model.finalDecision.researcherNotes}", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}
