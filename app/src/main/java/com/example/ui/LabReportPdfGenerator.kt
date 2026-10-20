package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.LabSession
import com.example.data.LabTest
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Local Color Scheme matching Laboratory theme
private val LabBlueMain = Color(0xFF0056B3)
private val LabDarkIndigo = Color(0xFF0F172A)
private val LabLightBg = Color(0xFFF8FAFC)
private val LabBorder = Color(0xFFE2E8F0)
private val LabSuccessGreen = Color(0xFF10B981)
private val LabWarningYellow = Color(0xFFF59E0B)
private val LabErrorRed = Color(0xFFEF4444)
private val LabPurple = Color(0xFF8B5CF6)
private val LabCyan = Color(0xFF06B6D4)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionReportView(
    session: LabSession,
    viewModel: GbrViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val tests by viewModel.getLabTestsForSession(session.id)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    BackHandler {
        onBack()
    }

    var showComparisonPrintDialog by remember { mutableStateOf(false) }

    if (showComparisonPrintDialog) {
        PrintOptionsModalDialog(
            onDismiss = { showComparisonPrintDialog = false },
            onConfirmPrint = { option ->
                val compModel = buildComparisonReportFromLabSession(session, tests)
                ComparisonReportPdfEngine.generateAndPrintPdf(context, compModel, option)
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LabLightBg)
    ) {
        // Upper Navigation & Action Bar
        Surface(
            tonalElevation = 4.dp,
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع",
                            tint = LabDarkIndigo
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "تقرير نتائج الجلسة الفنية 📋",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        Text(
                            text = "معاينة الوثيقة الرسمية وحفظها كملف PDF للطباعة",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                // PRINT / PDF ACTION BUTTON
                Button(
                    onClick = {
                        if (session.testType == "⚖️ فحص مقارنة") {
                            showComparisonPrintDialog = true
                        } else {
                            LabReportPdfGenerator.printLabSessionPdf(context, session, tests)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LabBlueMain),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = "طباعة",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("طباعة وتصدير PDF 📄", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Printable / Readable Document Scroll Wrapper
        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Document Frame mimicking official GBR letterhead
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.5.dp, LabBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    
                    // 1. Corporate Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Right side logo drawing & titles
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(LabBlueMain, shape = RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("G", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("مجموعة مصانع دهانات GBR", fontSize = 13.sp, fontWeight = FontWeight.Black, color = LabDarkIndigo)
                                Text("قطاع الجودة والتطوير الفني والبحثي", fontSize = 9.sp, color = Color.Gray)
                            }
                        }

                        // Left side doc meta
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "رقم التقرير: LAB-${session.sessionNumber}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = LabBlueMain
                            )
                            Text(
                                text = "فئة المستند: تقرير مختبر معتمد 🔒",
                                fontSize = 8.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), thickness = 1.dp, color = LabBorder)

                    // 2. Report Document Title Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LabBlueMain.copy(alpha = 0.04f), shape = RoundedCornerShape(8.dp))
                            .border(1.dp, LabBlueMain.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "تقرير نتائج التحاليل والفحوصات الفنية الرسمية 📊",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabBlueMain,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 3. Metadata Grid (Structured Key-Value Blocks)
                    Text(
                        text = "📋 البيانات العامة والتعريفية للجلسة",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, LabBorder),
                        shape = RoundedCornerShape(8.dp),
                        color = LabLightBg.copy(alpha = 0.5f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    ReportMetaItem("رقم جلسة الفحص:", session.sessionNumber)
                                    ReportMetaItem("اسم العينة/المنتج الخاضع:", session.sampleOrProduct)
                                    ReportMetaItem("نوع الجلسة الفنية:", session.testType)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    ReportMetaItem("تاريخ الفحص والتدوين:", session.testDate)
                                    ReportMetaItem("الفني المسؤول والباحث:", session.technicianName)
                                    ReportMetaItem("التصنيف الفرعي بالأرشيف:", session.category)
                                }
                            }
                            
                            // Parties details for comparative tests
                            if (session.testType == "⚖️ فحص مقارنة" && (!session.partyA.isNullOrBlank() || !session.partyB.isNullOrBlank())) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = LabBorder.copy(alpha = 0.7f), thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val nameA = session.partyA?.substringBefore("::")?.ifBlank { "الطرف أ" } ?: "الطرف أ"
                                        val descA = session.partyA?.substringAfter("::", "") ?: ""
                                        ReportMetaItem("الطرف الأول المقارن (A):", "$nameA ${if (descA.isNotBlank()) "($descA)" else ""}")
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        val nameB = session.partyB?.substringBefore("::")?.ifBlank { "الطرف ب" } ?: "الطرف ب"
                                        val descB = session.partyB?.substringAfter("::", "") ?: ""
                                        ReportMetaItem("الطرف الثاني المقارن (B):", "$nameB ${if (descB.isNotBlank()) "($descB)" else ""}")
                                    }
                                }
                            }
                        }
                    }

                    if (session.sampleProperties.isNotBlank()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "📦 خصائص العينة وملاحظات التحضير",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LabBlueMain.copy(alpha = 0.03f), shape = RoundedCornerShape(8.dp))
                                .border(1.dp, LabBlueMain.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = session.sampleProperties,
                                fontSize = 11.sp,
                                color = LabDarkIndigo,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 4. Compiled Test Results
                    Text(
                        text = "🧪 تفاصيل ونتائج الفحوصات المقاسة",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    if (tests.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("لا توجد أي فحوصات مسجلة في هذه الجلسة حتى الآن.", color = Color.Gray, fontSize = 11.sp)
                        }
                    } else {
                        tests.forEachIndexed { index, test ->
                            RenderTestResultCard(index + 1, test, session)
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 5. Technical Comments Section
                    Text(
                        text = "📝 القرار الفني والاستنتاج النهائي للمختبر",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = LabDarkIndigo,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LabPurple.copy(alpha = 0.03f), shape = RoundedCornerShape(8.dp))
                            .border(1.dp, LabPurple.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = LabPurple,
                                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = "القرار الفني الموثق والملاحظات:",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = LabPurple
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = session.notes.ifBlank { "لم يتم تسجيل أي ملاحظات أو قرار فني إجمالي على هذه الجلسة حتى الآن." },
                                        fontSize = 11.sp,
                                        color = LabDarkIndigo,
                                        lineHeight = 16.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // 6. Signatures Area
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            if (tests.all { it.status == "مكتمل" || it.status == "خارج المواصفة" } && tests.isNotEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = LabSuccessGreen, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تم التحقق واعتماد النتائج بالكامل 🎖️", color = LabSuccessGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = LabWarningYellow, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("مسودة أولية - بعض الفحوصات قيد القياس ⏳", color = LabWarningYellow, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("مسؤول الجودة والتحليل الفني", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(".............................................", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("توقيع واعتماد", fontSize = 9.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReportMetaItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(end = 4.dp))
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = LabDarkIndigo, textAlign = TextAlign.Right)
    }
}

@Composable
fun RenderTestResultCard(num: Int, test: LabTest, parentSession: LabSession) {
    val notes = test.notes
    var subtitleText = ""
    var resultText = ""
    val isComp = parentSession.testType == "⚖️ فحص مقارنة"

    // Unified extraction logic (Same as PDF)
    if (test.name.contains("اللزوجة") || notes.startsWith("WIZARD_VISCOSITY:") || notes.startsWith("WIZARD_COMP_VISCOSITY:")) {
        if (notes.startsWith("WIZARD_VISCOSITY:")) {
            val pureJson = notes.removePrefix("WIZARD_VISCOSITY:")
            val data = try {
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    .adapter(ViscosityTestData::class.java).fromJson(pureJson)
            } catch (e: Exception) { null }
            if (data != null) {
                val valid = data.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgVisc = if (valid.isNotEmpty()) valid.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                subtitleText = "مغزل: ${data.spindle} | سرعة: ${data.speed} RPM"
                if (data.testType == "RHEOLOGY") {
                    val validSecond = data.readingsSecondSpeed.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                    val avgViscSec = if (validSecond.isNotEmpty()) validSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                    val rIndex = if (avgViscSec > 0.0) String.format(Locale.US, "%.2f", avgVisc / avgViscSec) else "-"
                    val reduction = if (avgVisc > 0.0) String.format(Locale.US, "%.1f%%", ((avgVisc - avgViscSec) / avgVisc) * 100.0) else "-"
                    subtitleText += " | مؤشر (R.I): $rIndex | هبوط القص: $reduction"
                } else if (data.testType == "DILUTION") {
                    subtitleText += " | تخفيف (${data.paintWeight}جم عينة + ${data.waterWeight}جم ماء)"
                }
                resultText = String.format(Locale.US, "%,.0f cP", avgVisc)
            }
        } else if (notes.startsWith("WIZARD_COMP_VISCOSITY:")) {
            val pureJson = notes.removePrefix("WIZARD_COMP_VISCOSITY:")
            val data = try {
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    .adapter(ComparisonViscosityTestData::class.java).fromJson(pureJson)
            } catch (e: Exception) { null }
            if (data != null) {
                val validA = data.dataA.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgA = if (validA.isNotEmpty()) validA.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                val validB = data.dataB.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                val avgB = if (validB.isNotEmpty()) validB.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                val valAStr = if (avgA > 0) String.format(Locale.US, "%,.0f cP", avgA) else "-"
                val valBStr = if (avgB > 0) String.format(Locale.US, "%,.0f cP", avgB) else "-"
                val delta = calculateDelta(valAStr, valBStr)
                subtitleText = "مقارنة لزوجة الطرفين A و B"
                resultText = "A: $valAStr | B: $valBStr | الفرق: $delta"
            }
        } else {
            resultText = test.notes.ifBlank { "لم تسجل" }
        }
    } else if (test.name.contains("الكثافة") || notes.startsWith("WIZARD_DENSITY:") || notes.startsWith("WIZARD_COMP_DENSITY:")) {
        if (notes.startsWith("WIZARD_DENSITY:")) {
            val pureJson = notes.removePrefix("WIZARD_DENSITY:")
            val data = try {
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    .adapter(DensityTestData::class.java).fromJson(pureJson)
            } catch (e: Exception) { null }
            if (data != null) {
                val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                val sampleWeight = filled - empty
                val density = if (data.volumeMl > 0.0) sampleWeight / data.volumeMl else 0.0
                subtitleText = "الكثافة بوعاء معايرة (${data.volumeMl} مل)"
                resultText = String.format(Locale.US, "%.3f g/cm³", density)
            }
        } else if (notes.startsWith("WIZARD_COMP_DENSITY:")) {
            val pureJson = notes.removePrefix("WIZARD_COMP_DENSITY:")
            val data = try {
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    .adapter(ComparisonDensityTestData::class.java).fromJson(pureJson)
            } catch (e: Exception) { null }
            if (data != null) {
                val emptyA = data.dataA.emptyWeight.toDoubleOrNull() ?: 0.0
                val filledA = data.dataA.filledWeight.toDoubleOrNull() ?: 0.0
                val densityA = if (data.dataA.volumeMl > 0.0) (filledA - emptyA) / data.dataA.volumeMl else 0.0
                val emptyB = data.dataB.emptyWeight.toDoubleOrNull() ?: 0.0
                val filledB = data.dataB.filledWeight.toDoubleOrNull() ?: 0.0
                val densityB = if (data.dataB.volumeMl > 0.0) (filledB - emptyB) / data.dataB.volumeMl else 0.0
                val valAStr = if (densityA > 0) String.format(Locale.US, "%.3f", densityA) else "-"
                val valBStr = if (densityB > 0) String.format(Locale.US, "%.3f", densityB) else "-"
                val delta = calculateDelta(valAStr, valBStr)
                subtitleText = "مقارنة كثافة الطرفين A و B"
                resultText = "A: $valAStr | B: $valBStr | الفرق: $delta"
            }
        } else {
            resultText = test.notes.ifBlank { "لم تسجل" }
        }
    } else if (test.name.contains("الصلابة") || notes.startsWith("WIZARD_SOLID_CONTENT:") || notes.startsWith("WIZARD_COMP_SOLID_CONTENT:")) {
        if (notes.startsWith("WIZARD_SOLID_CONTENT:")) {
            val pureJson = notes.removePrefix("WIZARD_SOLID_CONTENT:")
            val data = try {
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    .adapter(SolidContentTestData::class.java).fromJson(pureJson)
            } catch (e: Exception) { null }
            if (data != null) {
                if (data.useDirectInput) {
                    subtitleText = "إدخال مباشر ونوعي لنسبة المواد الصلبة"
                    resultText = "${data.directPct.ifBlank { "0" }} %"
                } else {
                    val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                    val w = data.wetWeight.toDoubleOrNull() ?: 0.0
                    val dr = data.dryWeight.toDoubleOrNull() ?: 0.0
                    val netW = w - d
                    val netD = dr - d
                    val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                    subtitleText = "طريقة التجفيف توازن رطب/جاف"
                    resultText = String.format(Locale.US, "%.2f %%", pct)
                }
            }
        } else if (notes.startsWith("WIZARD_COMP_SOLID_CONTENT:")) {
            val pureJson = notes.removePrefix("WIZARD_COMP_SOLID_CONTENT:")
            val data = try {
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                    .adapter(ComparisonSolidContentTestData::class.java).fromJson(pureJson)
            } catch (e: Exception) { null }
            if (data != null) {
                val pctA = if (data.dataA.useDirectInput) {
                    data.dataA.directPct.trim() + "%"
                } else {
                    val d = data.dataA.dishWeight.toDoubleOrNull() ?: 0.0
                    val w = data.dataA.wetWeight.toDoubleOrNull() ?: 0.0
                    val dr = data.dataA.dryWeight.toDoubleOrNull() ?: 0.0
                    val netW = w - d
                    val netD = dr - d
                    val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                    String.format(Locale.US, "%.2f %%", pct)
                }
                val pctB = if (data.dataB.useDirectInput) {
                    data.dataB.directPct.trim() + "%"
                } else {
                    val d = data.dataB.dishWeight.toDoubleOrNull() ?: 0.0
                    val w = data.dataB.wetWeight.toDoubleOrNull() ?: 0.0
                    val dr = data.dataB.dryWeight.toDoubleOrNull() ?: 0.0
                    val netW = w - d
                    val netD = dr - d
                    val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                    String.format(Locale.US, "%.2f %%", pct)
                }
                val delta = calculateDelta(pctA.replace("%", "").trim(), pctB.replace("%", "").trim())
                subtitleText = "مقارنة نسبة الصلابة للطرفين A و B"
                resultText = "A: $pctA | B: $pctB | الفرق: $delta"
            }
        } else {
            resultText = test.notes.ifBlank { "لم تسجل" }
        }
    } else {
        if (isComp) {
            val valA = test.testValueA ?: "-"
            val valB = test.testValueB ?: "-"
            val delta = if (valA != "-" && valB != "-") calculateDelta(valA, valB) else "-"
            subtitleText = "فحص مقارنة فني"
            resultText = "A: $valA | B: $valB | الفرق: $delta"
        } else {
            subtitleText = "فحص ومعايرة فنية"
            resultText = test.testValueA ?: "لم تسجل نتيجة"
        }
    }

    val operatorNotes = if (test.notes.startsWith("WIZARD_")) {
        val lines = test.notes.split("\n")
        lines.drop(1).joinToString("\n").trim()
    } else {
        test.notes.trim()
    }
    val hasOperatorNotes = operatorNotes.isNotBlank()

    // Test Status Badge Colors
    val badgeColor = when (test.status) {
        "مكتمل" -> LabSuccessGreen
        "لم يبدأ" -> Color.LightGray
        "قيد التنفيذ" -> LabBlueMain
        "بانتظار النتيجة" -> LabWarningYellow
        "خارج المواصفة" -> LabErrorRed
        else -> Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, LabBorder.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Right Column: Title & Subtitle
                Row(
                    modifier = Modifier.weight(1.3f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(LabBlueMain.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(num.toString(), color = LabBlueMain, fontSize = 10.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "فحص ${test.name}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = LabDarkIndigo
                        )
                        if (subtitleText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = subtitleText,
                                fontSize = 8.5.sp,
                                color = Color.Gray,
                                lineHeight = 11.sp
                            )
                        }
                    }
                }

                // Left Column: Result & Status
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = resultText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = if (test.status == "خارج المواصفة") LabErrorRed else LabBlueMain,
                        textAlign = TextAlign.End
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Box(
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = test.status,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = badgeColor
                            )
                        }
                    }
                }
            }

            if (hasOperatorNotes) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = LabBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "ملاحظات الفاحص: $operatorNotes",
                    fontSize = 9.sp,
                    color = Color.DarkGray,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

fun getCleanTestNotesAndSummary(test: LabTest): String {
    val notes = test.notes
    if (notes.isBlank()) return ""
    val lines = notes.split("\n")
    val firstLine = lines.firstOrNull() ?: ""
    val hasWizard = firstLine.startsWith("WIZARD_")
    
    val humanNotes = if (hasWizard) {
        lines.drop(1).joinToString("\n").trim()
    } else {
        notes.trim()
    }
    
    val wizardSummary = if (hasWizard) {
        try {
            getTestSummaryText(firstLine)
        } catch (e: Exception) {
            "بيانات الفحص المسجلة"
        }
    } else {
        ""
    }
    
    return when {
        wizardSummary.isNotBlank() && humanNotes.isNotBlank() -> {
            "$wizardSummary\n• ملاحظات إضافية: $humanNotes"
        }
        wizardSummary.isNotBlank() -> {
            wizardSummary
        }
        else -> {
            humanNotes
        }
    }
}


/**
 * Industrial pdf generator engine for Lab Reports
 */
object LabReportPdfGenerator {

    class LabPdfCanvasHelper(
        val context: Context,
        val pdfDocument: PdfDocument,
        val session: LabSession,
        val pageWidth: Int = 595, // A4 page width (72 pt/inch)
        val pageHeight: Int = 842, // A4 page height
        val margin: Float = 36f, // 0.5 inch margins (36pt)
        val footerText: String = "قسم الجودة والتحليل الفني | دهانات GBR Paints"
    ) {
        var currentPageNumber = 1
        var currentPage: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var currentY = margin

        init {
            startNewPage()
        }

        fun startNewPage() {
            currentPage?.let {
                pdfDocument.finishPage(it)
            }
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNumber).create()
            val page = pdfDocument.startPage(pageInfo)
            currentPage = page
            canvas = page.canvas
            currentPageNumber++
            
            drawPageDecorations()
            currentY = margin + 55f
        }

        private fun drawPageDecorations() {
            val cv = canvas ?: return
            val paint = Paint().apply { isAntiAlias = true }

            // Page limits borders
            paint.color = android.graphics.Color.parseColor("#94A3B8")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            cv.drawRect(margin - 8f, margin - 8f, pageWidth - margin + 8f, pageHeight - margin + 8f, paint)

            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            paint.strokeWidth = 0.5f
            cv.drawRect(margin - 11f, margin - 11f, pageWidth - margin + 11f, pageHeight - margin + 11f, paint)

            // Top Header circle
            val circleX = pageWidth - margin - 22f
            val circleY = margin + 14f
            val circleRadius = 14f
            
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#0056B3")
            cv.drawCircle(circleX, circleY, circleRadius, paint)

            paint.color = android.graphics.Color.WHITE
            paint.textSize = 12f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            val logoTextG = "G"
            val textGWidth = paint.measureText(logoTextG)
            cv.drawText(logoTextG, circleX - (textGWidth / 2f), circleY + 4f, paint)

            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#2563EB")
            cv.drawRect(pageWidth - margin - 50f, margin, pageWidth - margin - 46f, margin + 28f, paint)

            // Logo Titles
            drawParagraph(
                cv = cv,
                text = "مختبر دهانات GBR اللامحدود\nإدارة الجودة وتحليل عينات الدهانات",
                x = pageWidth - margin - 230f,
                y = margin,
                width = 170,
                textSize = 8.5f,
                textColorHex = "#0056B3",
                isBold = true,
                align = "right"
            )

            // Document category
            val headerMetaText = "رقم المستند: GBR-LAB-${session.sessionNumber}\nالتقرير: تقرير التحاليل الفني المعتمد "
            drawParagraph(
                cv = cv,
                text = headerMetaText,
                x = margin,
                y = margin,
                width = 200,
                textSize = 8f,
                textColorHex = "#475569",
                isBold = false,
                align = "left"
            )

            paint.color = android.graphics.Color.parseColor("#CBD5E1")
            paint.strokeWidth = 1f
            cv.drawLine(margin, margin + 32f, pageWidth - margin, margin + 32f, paint)

            // Page bottom footer
            paint.color = android.graphics.Color.parseColor("#CBD5E1")
            paint.strokeWidth = 0.75f
            cv.drawLine(margin, pageHeight - margin - 14f, pageWidth - margin, pageHeight - margin - 14f, paint)

            // Footer note
            drawParagraph(
                cv = cv,
                text = footerText,
                x = pageWidth / 2f,
                y = pageHeight - margin - 11f,
                width = (pageWidth / 2f - margin).toInt(),
                textSize = 7.5f,
                textColorHex = "#475569",
                isBold = false,
                align = "right"
            )

            val pageNumText = "الصفحة ${currentPageNumber - 1} | تاريخ التقرير: ${SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())}"
            drawParagraph(
                cv = cv,
                text = pageNumText,
                x = margin,
                y = pageHeight - margin - 11f,
                width = (pageWidth / 2f - margin).toInt(),
                textSize = 7.5f,
                textColorHex = "#475569",
                isBold = false,
                align = "left"
            )
        }

        fun ensureSpace(neededHeight: Float) {
            if (currentY + neededHeight > pageHeight - margin - 22f) {
                startNewPage()
            }
        }

        fun finishDocument() {
            currentPage?.let {
                pdfDocument.finishPage(it)
            }
            currentPage = null
            canvas = null
        }
    }

    fun drawParagraph(
        cv: Canvas,
        text: String,
        x: Float,
        y: Float,
        width: Int,
        textSize: Float,
        textColorHex: String = "#0F172A",
        isBold: Boolean = false,
        align: String = "right"
    ): Int {
        val textPaint = TextPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor(textColorHex)
            this.textSize = textSize
            typeface = if (isBold) {
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.NORMAL)
            }
        }

        val (alignment, heuristic) = when (align.lowercase(Locale.getDefault())) {
            "center" -> Pair(Layout.Alignment.ALIGN_CENTER, android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR)
            "left" -> Pair(Layout.Alignment.ALIGN_NORMAL, android.text.TextDirectionHeuristics.LTR)
            else -> Pair(Layout.Alignment.ALIGN_NORMAL, android.text.TextDirectionHeuristics.RTL)
        }
        
        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(alignment)
                .setTextDirection(heuristic)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, textPaint, width, alignment, 1.15f, 0f, true)
        }

        cv.save()
        cv.translate(x, y)
        staticLayout.draw(cv)
        cv.restore()

        return staticLayout.height
    }

    fun drawSectionHeader(helper: LabPdfCanvasHelper, title: String) {
        helper.ensureSpace(34f)
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }
        
        val drawY = helper.currentY + 6f
        
        paint.color = android.graphics.Color.parseColor("#0056B3")
        paint.style = Paint.Style.FILL
        cv.drawRect(helper.pageWidth - helper.margin - 5f, drawY, helper.pageWidth - helper.margin, drawY + 16f, paint)

        drawParagraph(
            cv = cv,
            text = title,
            x = helper.margin,
            y = drawY + 2f,
            width = (helper.pageWidth - helper.margin * 2 - 12f).toInt(),
            textSize = 10f,
            textColorHex = "#0F172A",
            isBold = true,
            align = "right"
        )
        
        helper.currentY = drawY + 24f
    }

    fun printLabSessionPdf(
        context: Context,
        session: LabSession,
        tests: List<LabTest>
    ) {
        if (session.testType == "⚖️ فحص مقارنة") {
            val compModel = buildComparisonReportFromLabSession(session, tests)
            ComparisonReportPdfEngine.generateAndPrintPdf(context, compModel, ComparisonPrintOption.BOTH)
            return
        }

        try {
            val pdfDocument = PdfDocument()
            val helper = LabPdfCanvasHelper(context, pdfDocument, session)
            var cv = helper.canvas ?: return

            fun checkSpace(neededHeight: Float) {
                helper.ensureSpace(neededHeight)
                cv = helper.canvas!!
            }

            fun drawHeaderLocal(title: String) {
                drawSectionHeader(helper, title)
                cv = helper.canvas!!
            }
            val paint = Paint().apply { isAntiAlias = true }

            val startX = helper.margin
            val endX = helper.pageWidth - helper.margin
            val contentW = helper.pageWidth - helper.margin * 2

            // Title Banner
            checkSpace(50f)
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#F1F5F9")
            cv.drawRoundRect(startX, helper.currentY, endX, helper.currentY + 36f, 6f, 6f, paint)

            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.parseColor("#CBD5E1")
            paint.strokeWidth = 0.5f
            cv.drawRoundRect(startX, helper.currentY, endX, helper.currentY + 36f, 6f, 6f, paint)

            drawParagraph(
                cv = cv,
                text = "تقرير نتائج التحاليل والفحوصات الفنية الرسمية المعتمّدة",
                x = startX,
                y = helper.currentY + 10f,
                width = contentW.toInt(),
                textSize = 12f,
                textColorHex = "#0056B3",
                isBold = true,
                align = "center"
            )
            helper.currentY += 48f

            // Metadata block
            checkSpace(120f)
            val startMetaY = helper.currentY
            val blockHeight = if (session.testType == "⚖️ فحص مقارنة") 105f else 85f
            
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#F8FAFC")
            cv.drawRoundRect(startX, startMetaY, endX, startMetaY + blockHeight, 6f, 6f, paint)

            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            paint.strokeWidth = 0.75f
            cv.drawRoundRect(startX, startMetaY, endX, startMetaY + blockHeight, 6f, 6f, paint)

            // Values
            val rightColX = endX - (contentW / 2f) + 10f
            val leftColX = startX + 10f
            val itemWidth = (contentW / 2f) - 20f

            var metaY = startMetaY + 10f

            // Row 1
            drawParagraph(cv, "رقم الجلسة: ${session.sessionNumber}", rightColX, metaY, itemWidth.toInt(), 8.5f, "#0056B3", true, "right")
            drawParagraph(cv, "تاريخ الفحص: ${session.testDate}", leftColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", false, "right")
            metaY += 16f

            // Row 2
            drawParagraph(cv, "العينة / المنتج: ${session.sampleOrProduct}", rightColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", false, "right")
            drawParagraph(cv, "الفاحص والباحث المسؤول: ${session.technicianName}", leftColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", false, "right")
            metaY += 16f

            // Row 3
            drawParagraph(cv, "نوع الفحص: ${session.testType}", rightColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", false, "right")
            drawParagraph(cv, "التصنيف الفرعي بالأرشيف: ${session.category}", leftColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", false, "right")
            
            if (session.testType == "⚖️ فحص مقارنة") {
                metaY += 16f
                paint.color = android.graphics.Color.parseColor("#CBD5E1")
                paint.strokeWidth = 0.5f
                cv.drawLine(startX + 10f, metaY, endX - 10f, metaY, paint)
                metaY += 8f

                val nameA = session.partyA?.substringBefore("::")?.ifBlank { "الطرف أ" } ?: "الطرف أ"
                val nameB = session.partyB?.substringBefore("::")?.ifBlank { "الطرف ب" } ?: "الطرف ب"
                
                drawParagraph(cv, "الطرف الأول المقارن (A): $nameA", rightColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", true, "right")
                drawParagraph(cv, "الطرف الثاني المقارن (B): $nameB", leftColX, metaY, itemWidth.toInt(), 8.5f, "#0F172A", true, "right")
            }

            helper.currentY += blockHeight + 20f

            // Draw sample properties block
            if (session.sampleProperties.isNotBlank()) {
                checkSpace(60f)
                val startPropsY = helper.currentY
                val propsBlockHeight = 45f
                
                paint.style = Paint.Style.FILL
                paint.color = android.graphics.Color.parseColor("#F1F5F9")
                cv.drawRoundRect(startX, startPropsY, endX, startPropsY + propsBlockHeight, 4f, 4f, paint)

                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.parseColor("#CBD5E1")
                paint.strokeWidth = 0.5f
                cv.drawRoundRect(startX, startPropsY, endX, startPropsY + propsBlockHeight, 4f, 4f, paint)

                drawParagraph(cv, "خصائص العينة وظروف سحبها وتحضيرها 📦", rightColX + 5f, startPropsY + 6f, itemWidth.toInt(), 8.5f, "#0F172A", true, "right")
                drawParagraph(cv, session.sampleProperties, leftColX + 5f, startPropsY + 22f, (contentW - 30f).toInt(), 8f, "#0056B3", false, "right")
                
                helper.currentY += propsBlockHeight + 15f
            }

            // Print test results
            if (tests.isEmpty()) {
                checkSpace(40f)
                drawParagraph(cv, "لم تسجل أي نتائج فحوصات معملية لهذه الجلسة حتى الآن.", startX, helper.currentY, contentW.toInt(), 9f, "#64748B", false, "center")
                helper.currentY += 40f
            } else {
                drawSectionHeader(helper, "🧪 تفاصيل ونتائج الفحوصات المعملية المعتمدة")
                cv = helper.canvas!!

                tests.forEachIndexed { idx, test ->
                    val notes = test.notes
                    var subtitleText = ""
                    var resultText = ""

                    // Unified extraction logic (Same as on-screen)
                    if (test.name.contains("اللزوجة") || notes.startsWith("WIZARD_VISCOSITY:") || notes.startsWith("WIZARD_COMP_VISCOSITY:")) {
                        if (notes.startsWith("WIZARD_VISCOSITY:")) {
                            val pureJson = notes.removePrefix("WIZARD_VISCOSITY:")
                            val data = try {
                                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                                    .adapter(ViscosityTestData::class.java).fromJson(pureJson)
                            } catch (e: Exception) { null }
                            if (data != null) {
                                val valid = data.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                                val avgVisc = if (valid.isNotEmpty()) valid.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                                subtitleText = "المغزل: ${data.spindle} | السرعة: ${data.speed} RPM"
                                if (data.testType == "RHEOLOGY") {
                                    val validSecond = data.readingsSecondSpeed.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                                    val avgViscSec = if (validSecond.isNotEmpty()) validSecond.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                                    val rIndex = if (avgViscSec > 0.0) String.format(Locale.US, "%.2f", avgVisc / avgViscSec) else "-"
                                    val reduction = if (avgVisc > 0.0) String.format(Locale.US, "%.1f%%", ((avgVisc - avgViscSec) / avgVisc) * 100.0) else "-"
                                    subtitleText += " | مؤشر (R.I): $rIndex | الهبوط: $reduction"
                                } else if (data.testType == "DILUTION") {
                                    subtitleText += " | تخفيف (${data.paintWeight}جم عينة + ${data.waterWeight}جم ماء)"
                                }
                                resultText = String.format(Locale.US, "%,.0f cP", avgVisc)
                            }
                        } else if (notes.startsWith("WIZARD_COMP_VISCOSITY:")) {
                            val pureJson = notes.removePrefix("WIZARD_COMP_VISCOSITY:")
                            val data = try {
                                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                                    .adapter(ComparisonViscosityTestData::class.java).fromJson(pureJson)
                            } catch (e: Exception) { null }
                            if (data != null) {
                                val validA = data.dataA.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                                val avgA = if (validA.isNotEmpty()) validA.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                                val validB = data.dataB.readings.filter { it.viscosity.isNotBlank() && it.torque.isNotBlank() }
                                val avgB = if (validB.isNotEmpty()) validB.mapNotNull { it.viscosity.toDoubleOrNull() }.average() else 0.0
                                val valAStr = if (avgA > 0) String.format(Locale.US, "%,.0f cP", avgA) else "-"
                                val valBStr = if (avgB > 0) String.format(Locale.US, "%,.0f cP", avgB) else "-"
                                val delta = calculateDelta(valAStr, valBStr)
                                subtitleText = "مقارنة اللزوجة للطرفين A و B"
                                resultText = "أول: $valAStr | ثاني: $valBStr | الفرق: $delta"
                            }
                        } else {
                            resultText = test.notes.ifBlank { "لم تسجل" }
                        }
                    } else if (test.name.contains("الكثافة") || notes.startsWith("WIZARD_DENSITY:") || notes.startsWith("WIZARD_COMP_DENSITY:")) {
                        if (notes.startsWith("WIZARD_DENSITY:")) {
                            val pureJson = notes.removePrefix("WIZARD_DENSITY:")
                            val data = try {
                                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                                    .adapter(DensityTestData::class.java).fromJson(pureJson)
                            } catch (e: Exception) { null }
                            if (data != null) {
                                val empty = data.emptyWeight.toDoubleOrNull() ?: 0.0
                                val filled = data.filledWeight.toDoubleOrNull() ?: 0.0
                                val sampleWeight = filled - empty
                                val density = if (data.volumeMl > 0.0) sampleWeight / data.volumeMl else 0.0
                                subtitleText = "الكثافة بوعاء معايرة (${data.volumeMl} مل)"
                                resultText = String.format(Locale.US, "%.3f g/cm³", density)
                            }
                        } else if (notes.startsWith("WIZARD_COMP_DENSITY:")) {
                            val pureJson = notes.removePrefix("WIZARD_COMP_DENSITY:")
                            val data = try {
                                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                                    .adapter(ComparisonDensityTestData::class.java).fromJson(pureJson)
                            } catch (e: Exception) { null }
                            if (data != null) {
                                val emptyA = data.dataA.emptyWeight.toDoubleOrNull() ?: 0.0
                                val filledA = data.dataA.filledWeight.toDoubleOrNull() ?: 0.0
                                val densityA = if (data.dataA.volumeMl > 0.0) (filledA - emptyA) / data.dataA.volumeMl else 0.0
                                val emptyB = data.dataB.emptyWeight.toDoubleOrNull() ?: 0.0
                                val filledB = data.dataB.filledWeight.toDoubleOrNull() ?: 0.0
                                val densityB = if (data.dataB.volumeMl > 0.0) (filledB - emptyB) / data.dataB.volumeMl else 0.0
                                val valAStr = if (densityA > 0) String.format(Locale.US, "%.3f", densityA) else "-"
                                val valBStr = if (densityB > 0) String.format(Locale.US, "%.3f", densityB) else "-"
                                val delta = calculateDelta(valAStr, valBStr)
                                subtitleText = "مقارنة كثافة الطرفين A و B"
                                resultText = "أول: $valAStr | ثاني: $valBStr | الفرق: $delta"
                            }
                        } else {
                            resultText = test.notes.ifBlank { "لم تسجل" }
                        }
                    } else if (test.name.contains("الصلابة") || notes.startsWith("WIZARD_SOLID_CONTENT:") || notes.startsWith("WIZARD_COMP_SOLID_CONTENT:")) {
                        if (notes.startsWith("WIZARD_SOLID_CONTENT:")) {
                            val pureJson = notes.removePrefix("WIZARD_SOLID_CONTENT:")
                            val data = try {
                                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                                    .adapter(SolidContentTestData::class.java).fromJson(pureJson)
                            } catch (e: Exception) { null }
                            if (data != null) {
                                if (data.useDirectInput) {
                                    subtitleText = "إدخال مباشر ونوعي لنسبة الصلابة"
                                    resultText = "${data.directPct.ifBlank { "0" }} %"
                                } else {
                                    val d = data.dishWeight.toDoubleOrNull() ?: 0.0
                                    val w = data.wetWeight.toDoubleOrNull() ?: 0.0
                                    val dr = data.dryWeight.toDoubleOrNull() ?: 0.0
                                    val netW = w - d
                                    val netD = dr - d
                                    val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                                    subtitleText = "طريقة التجفيف توازن رطب/جاف"
                                    resultText = String.format(Locale.US, "%.2f %%", pct)
                                }
                            }
                        } else if (notes.startsWith("WIZARD_COMP_SOLID_CONTENT:")) {
                            val pureJson = notes.removePrefix("WIZARD_COMP_SOLID_CONTENT:")
                            val data = try {
                                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
                                    .adapter(ComparisonSolidContentTestData::class.java).fromJson(pureJson)
                            } catch (e: Exception) { null }
                            if (data != null) {
                                val pctA = if (data.dataA.useDirectInput) {
                                    data.dataA.directPct.trim() + "%"
                                } else {
                                    val d = data.dataA.dishWeight.toDoubleOrNull() ?: 0.0
                                    val w = data.dataA.wetWeight.toDoubleOrNull() ?: 0.0
                                    val dr = data.dataA.dryWeight.toDoubleOrNull() ?: 0.0
                                    val netW = w - d
                                    val netD = dr - d
                                    val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                                    String.format(Locale.US, "%.2f %%", pct)
                                }
                                val pctB = if (data.dataB.useDirectInput) {
                                    data.dataB.directPct.trim() + "%"
                                } else {
                                    val d = data.dataB.dishWeight.toDoubleOrNull() ?: 0.0
                                    val w = data.dataB.wetWeight.toDoubleOrNull() ?: 0.0
                                    val dr = data.dataB.dryWeight.toDoubleOrNull() ?: 0.0
                                    val netW = w - d
                                    val netD = dr - d
                                    val pct = if (netW > 0.0) (netD / netW) * 100.0 else 0.0
                                    String.format(Locale.US, "%.2f %%", pct)
                                }
                                val delta = calculateDelta(pctA.replace("%", "").trim(), pctB.replace("%", "").trim())
                                subtitleText = "مقارنة نسبة الصلابة للطرفين A و B"
                                resultText = "أول: $pctA | ثاني: $pctB | الفرق: $delta"
                            }
                        } else {
                            resultText = test.notes.ifBlank { "لم تسجل" }
                        }
                    } else {
                        val isComparison = session.testType == "⚖️ فحص مقارنة"
                        if (isComparison) {
                            val valA = test.testValueA ?: "-"
                            val valB = test.testValueB ?: "-"
                            val delta = if (valA != "-" && valB != "-") calculateDelta(valA, valB) else "-"
                            subtitleText = "فحص مقارنة للطرفين"
                            resultText = "أول: $valA | ثاني: $valB | الفرق: $delta"
                        } else {
                            subtitleText = "فحص ومعايرة فنية"
                            resultText = test.testValueA ?: "لم تسجل"
                        }
                    }

                    val operatorNotes = if (test.notes.startsWith("WIZARD_")) {
                        val lines = test.notes.split("\n")
                        lines.drop(1).joinToString("\n").trim()
                    } else {
                        test.notes.trim()
                    }
                    val hasOperatorNotes = operatorNotes.isNotBlank()

                    // Layout & Draw PDF Card
                    val cardHeight = if (hasOperatorNotes) 52f else 38f
                    checkSpace(cardHeight + 10f)
                    cv = helper.canvas!!

                    // Draw white background
                    paint.style = Paint.Style.FILL
                    paint.color = android.graphics.Color.parseColor("#FFFFFF")
                    cv.drawRoundRect(startX, helper.currentY, endX, helper.currentY + cardHeight, 4f, 4f, paint)

                    // Draw thin gray border
                    paint.style = Paint.Style.STROKE
                    paint.color = android.graphics.Color.parseColor("#E2E8F0")
                    paint.strokeWidth = 0.5f
                    cv.drawRoundRect(startX, helper.currentY, endX, helper.currentY + cardHeight, 4f, 4f, paint)

                    // Draw status color bar on right side
                    val statusBarColor = when (test.status) {
                        "مكتمل" -> "#10B981"
                        "خارج المواصفة" -> "#EF4444"
                        "قيد التنفيذ" -> "#3B82F6"
                        "بانتظار النتيجة" -> "#F59E0B"
                        else -> "#94A3B8"
                    }
                    paint.style = Paint.Style.FILL
                    paint.color = android.graphics.Color.parseColor(statusBarColor)
                    cv.drawRect(endX - 3f, helper.currentY, endX, helper.currentY + cardHeight, paint)

                    val colWidth = contentW * 0.48f

                    // 1. Title & Parameters (Right Column)
                    val titleText = "${idx + 1}. فحص ${test.name}"
                    drawParagraph(cv, titleText, endX - 12f - colWidth, helper.currentY + 6f, colWidth.toInt(), 9.5f, "#0F172A", true, "right")

                    if (subtitleText.isNotBlank()) {
                        drawParagraph(cv, subtitleText, endX - 12f - colWidth, helper.currentY + 20f, colWidth.toInt(), 7.5f, "#475569", false, "right")
                    }

                    // 2. Result & Status (Left Column)
                    val resultLabel = when {
                        test.name.contains("اللزوجة") -> "اللزوجة المقاسة:"
                        test.name.contains("الكثافة") -> "الكثافة المستنتجة:"
                        test.name.contains("الصلابة") -> "نسبة المواد الصلبة:"
                        else -> "قيمة القياس:"
                    }
                    val resultValueAndLabel = "$resultLabel $resultText"
                    val resultColorHex = if (test.status == "خارج المواصفة") "#DC2626" else "#0056B3"

                    drawParagraph(cv, resultValueAndLabel, startX + 10f, helper.currentY + 6f, colWidth.toInt(), 10f, resultColorHex, true, "left")

                    val statusBadgeText = "الحالة: ${test.status}"
                    drawParagraph(cv, statusBadgeText, startX + 10f, helper.currentY + 20f, colWidth.toInt(), 7.5f, statusBarColor, true, "left")

                    // 3. Operator Notes (at bottom)
                    if (hasOperatorNotes) {
                        paint.style = Paint.Style.STROKE
                        paint.color = android.graphics.Color.parseColor("#F1F5F9")
                        paint.strokeWidth = 0.5f
                        cv.drawLine(startX + 10f, helper.currentY + 34f, endX - 10f, helper.currentY + 34f, paint)

                        drawParagraph(cv, "ملاحظات الفاحص المسؤول: $operatorNotes", startX + 12f, helper.currentY + 38f, (contentW - 24f).toInt(), 7.5f, "#64748B", false, "right")
                    }

                    helper.currentY += cardHeight + 8f
                }
            }

            // Technical Observations
            checkSpace(120f)
            drawHeaderLocal("ملاحظات التوعية الفنية والقرار النهائي للمختبر")
            
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#FBF7FF") // very light purple background
            cv.drawRoundRect(startX + 10f, helper.currentY, endX - 10f, helper.currentY + 60f, 6f, 6f, paint)

            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.parseColor("#E9D5FF") // purple border accent
            paint.strokeWidth = 0.75f
            cv.drawRoundRect(startX + 10f, helper.currentY, endX - 10f, helper.currentY + 60f, 6f, 6f, paint)

            val sessionNotesText = session.notes.ifBlank { "لم يدوّن المشرف الفني أي استنتاج نهائي بعد على تقرير الجلسة." }
            drawParagraph(
                cv = cv,
                text = "القرار الفني والاستنتاج النهائي المعتمد:\n$sessionNotesText",
                x = startX + 18f,
                y = helper.currentY + 10f,
                width = (contentW - 36f).toInt(),
                textSize = 9f,
                textColorHex = "#6B21A8",
                isBold = true,
                align = "right"
            )
            helper.currentY += 80f

            // Signatures block
            checkSpace(80f)
            val signaturesY = helper.currentY + 10f
            
            // Authorized text Left
            val verifiedBadgeStr = if (tests.all { it.status == "مكتمل" || it.status == "خارج المواصفة" } && tests.isNotEmpty()) {
                "فحص مكتمل ومعتمد ١٠٠٪ 🎖️"
            } else {
                "فحوصات معلقة معملياً ⏳"
            }
            drawParagraph(cv, verifiedBadgeStr, startX + 10f, signaturesY + 15f, 200, 8.5f, "#10B981", true, "left")

            // Verified text Right
            drawParagraph(cv, "مسؤول الجودة والتحاليل بمختبر GBR", endX - 210f, signaturesY, 200, 9f, "#475569", true, "center")
            drawParagraph(cv, "توقيع الفاحص: .............................", endX - 210f, signaturesY + 22f, 200, 8.5f, "#94A3B8", false, "center")
            drawParagraph(cv, "تاريخ الاعتماد: ${session.testDate}", endX - 210f, signaturesY + 38f, 200, 8f, "#94A3B8", false, "center")

            helper.currentY += 80f

            // Final PDF Compilation
            helper.finishDocument()

            // Save document file in cache path
            val cleanName = session.sampleOrProduct
                .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF_-]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .ifBlank { "Sample" }
            val fileName = "LabReport_${session.sessionNumber}_${cleanName}.pdf"
            val file = File(context.cacheDir, fileName)
            
            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)
            outputStream.flush()
            outputStream.close()
            pdfDocument.close()

            // Start Android Printer / Chooser
            startPrintJob(context, file, "تقرير جودة المختبر رقم ${session.sessionNumber}")

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "فشل تصدير وثيقة PDF: ${e.localizedMessage} ❌", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPrintJob(context: Context, pdfFile: File, jobName: String) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            printManager.print(jobName, MyPrintAdapter(context, pdfFile), null)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to sharing/opening directly
            openPdfFileDirectly(context, pdfFile, jobName)
        }
    }

    private fun openPdfFileDirectly(context: Context, pdfFile: File, jobName: String) {
        try {
            val authority = "${context.packageName}.provider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)
            
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, jobName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val chooserIntent = Intent.createChooser(viewIntent, "مستند تقرير فحص المختبر المصدّر 📄").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(chooserIntent)
            Toast.makeText(context, "تم بناء وتصدير مستند الـ PDF بنجاح 📄", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val authority = "${context.packageName}.provider"
                val uri = FileProvider.getUriForFile(context, authority, pdfFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val chooser = Intent.createChooser(shareIntent, "مشاركة تقرير المختبر بصيغة PDF 📄").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (ex: Exception) {
                ex.printStackTrace()
                Toast.makeText(context, "لا يوجد مستعرض أو مشارك PDF مدعم على جهازك: ${ex.localizedMessage ?: e.localizedMessage} ❌", Toast.LENGTH_LONG).show()
            }
        }
    }

    class MyPrintAdapter(val context: Context, val pdfFile: File) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }

            val info = PrintDocumentInfo.Builder(pdfFile.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()

            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback?
        ) {
            var inputStream: FileInputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                inputStream = FileInputStream(pdfFile)
                outputStream = FileOutputStream(destination?.fileDescriptor)

                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } >= 0) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onWriteCancelled()
                        return
                    }
                    outputStream.write(buffer, 0, bytesRead)
                }

                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback?.onWriteFailed(e.toString())
            } finally {
                try {
                    inputStream?.close()
                    outputStream?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
