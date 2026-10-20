package com.example

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.print.PrintAttributes
import android.print.PrintManager
import android.print.PrintDocumentAdapter
import android.os.Bundle
import android.os.CancellationSignal
import android.print.PageRange
import android.print.PrintDocumentInfo
import android.os.ParcelFileDescriptor
import android.widget.Toast
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Professional Industrial PDF Engine for GBR Paints Formulation System
 */
object GbrPdfGenerator {

    // Helper class to manage PDF canvas, page numbering, corporate header, and vertical cursor layout
    class PdfCanvasHelper(
        val context: Context,
        val pdfDocument: PdfDocument,
        val formulation: Formulation,
        val docType: Int, // 0 = Full, 1 = Short, 2 = Cost, 3 = Product Card
        val useRealNames: Boolean,
        val usePercentage: Boolean,
        val headerTitle: String,
        val pageWidth: Int = 595, // A4 page width (72 pt/inch)
        val pageHeight: Int = 842, // A4 page height
        val margin: Float = 36f, // 0.5 inch margins (36pt)
        val footerText: String = "G Paints Production Cloud System | دهانات GBR"
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
            
            // Draw official corporate letterhead framework
            drawPageDecorations()
            currentY = margin + 55f // Leave precise breathing room below the top header
        }

        private fun drawPageDecorations() {
            if (docType == 0) return // Skip for type 0 as it draws its own custom single A4 layout
            val cv = canvas ?: return
            val paint = Paint().apply { isAntiAlias = true }

            val prefs = context.getSharedPreferences("gbr_prefs", android.content.Context.MODE_PRIVATE)
            val customLogoUri = prefs.getString("print_header_logo_uri", "") ?: ""
            val customTitle = prefs.getString("print_header_title", "دهانات GBR Paints") ?: "دهانات GBR Paints"
            val customSubtitle = prefs.getString("print_header_subtitle", "مجموعة مصانع الدهانات الممتازة والخاصة") ?: "مجموعة مصانع الدهانات الممتازة والخاصة"
            val customFooter = prefs.getString("print_footer_text", footerText) ?: footerText

            // 1. Draw solid thin borders with premium corporate gray color (#CBD5E1)
            paint.color = android.graphics.Color.parseColor("#94A3B8")
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            cv.drawRect(margin - 8f, margin - 8f, pageWidth - margin + 8f, pageHeight - margin + 8f, paint)

            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            paint.strokeWidth = 0.5f
            cv.drawRect(margin - 11f, margin - 11f, pageWidth - margin + 11f, pageHeight - margin + 11f, paint)

            // 2. Draw Top Header Ribbon with GBR Paints Logo Drawing
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

                // Draw a white stylish 'G' inside the logo
                paint.color = android.graphics.Color.WHITE
                paint.textSize = 12f
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                val logoTextG = "G"
                val textGWidth = paint.measureText(logoTextG)
                cv.drawText(logoTextG, circleX - (textGWidth / 2f), circleY + 4f, paint)
            }

            // Split accent bar next to logo
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor("#2563EB") // Highlight blue
            cv.drawRect(pageWidth - margin - 50f, margin, pageWidth - margin - 46f, margin + 28f, paint)

            // Corporate logo text
            drawParagraph(
                cv = cv,
                text = "$customTitle\n$customSubtitle",
                x = pageWidth - margin - 230f,
                y = margin,
                width = 170,
                textSize = 8.5f,
                textColorHex = "#1E3A8A",
                isBold = true,
                align = "right"
            )

            // Document Identification Block (Left header)
            val docTypeName = when (docType) {
                1 -> "ورقة تشغيل مختصرة"
                2 -> "تقرير تكلفة التركيبة"
                3 -> "بطاقة وثيقة منتج"
                else -> "وثيقة فنية كاملة"
            }
            val printTypeName = if (useRealNames) "أسماء حقيقية" else "أسماء متداولة"
            val displayModeName = if (usePercentage) "كميات مئوية ٪" else "كميات فعلية كغم"
            
            val headerMetaText = "نوع الوثيقة: $docTypeName\nنوع الأسماء: $printTypeName | $displayModeName"
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

            val exportTimeStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US).format(Date())
            val exportDateLabel = "تاريخ الطباعة: $exportTimeStr"
            drawParagraph(
                cv = cv,
                text = exportDateLabel,
                x = margin,
                y = margin + 22f,
                width = 200,
                textSize = 6.5f,
                textColorHex = "#64748B",
                isBold = false,
                align = "left"
            )

            // Horizontal line below header
            paint.color = android.graphics.Color.parseColor("#CBD5E1")
            paint.strokeWidth = 1f
            cv.drawLine(margin, margin + 32f, pageWidth - margin, margin + 32f, paint)

            // 3. Draw Page Footer
            paint.color = android.graphics.Color.parseColor("#CBD5E1")
            paint.strokeWidth = 0.75f
            cv.drawLine(margin, pageHeight - margin - 14f, pageWidth - margin, pageHeight - margin - 14f, paint)

            // Footer note (Right)
            drawParagraph(
                cv = cv,
                text = customFooter,
                x = pageWidth / 2f,
                y = pageHeight - margin - 11f,
                width = (pageWidth / 2f - margin).toInt(),
                textSize = 7.5f,
                textColorHex = "#475569",
                isBold = false,
                align = "right"
            )

            // Document reference ID & Page Number (Left)
            val docNumber = "رقم الوثيقة: DOC-GBR-${formulation.id}-${formulation.code.ifBlank { "PRD" }}"
            val pageNumText = "الصفحة ${currentPageNumber - 1} | $docNumber"
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

    /**
     * Draw text block that respects RTL shaping and word-wrapping using StaticLayout
     */
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

    /**
     * Draws a professional section header with a nice left/right border accent
     */
    fun drawSectionHeader(helper: PdfCanvasHelper, title: String, isPrimaryAccent: Boolean = true) {
        helper.ensureSpace(34f)
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }
        
        val drawY = helper.currentY + 6f
        val blockColor = if (isPrimaryAccent) "#1E3A8A" else "#475569"
        
        // Solid vertical bar on the right side
        paint.color = android.graphics.Color.parseColor(blockColor)
        paint.style = Paint.Style.FILL
        cv.drawRect(helper.pageWidth - helper.margin - 5f, drawY, helper.pageWidth - helper.margin, drawY + 16f, paint)

        // Title text next to vertical bar
        drawParagraph(
            cv = cv,
            text = title,
            x = helper.margin,
            y = drawY + 1f,
            width = (helper.pageWidth - helper.margin * 2 - 10f).toInt(),
            textSize = 10f,
            textColorHex = blockColor,
            isBold = true,
            align = "right"
        )
        
        // Thin separator line below title
        paint.color = android.graphics.Color.parseColor("#E2E8F0")
        paint.strokeWidth = 1f
        cv.drawLine(helper.margin, drawY + 20f, helper.pageWidth - helper.margin, drawY + 20f, paint)

        helper.currentY = drawY + 24f
    }

    /**
     * Draws structured tables with customized layouts, page breaks and column widths
     */
    fun drawGridTable(
        helper: PdfCanvasHelper,
        headers: List<String>,
        colWidthPercentages: List<Float>, // Sum must match 1.0
        rows: List<List<String>>,
        highlightLastRow: Boolean = false,
        boldColumnIndex: Int = 1 // column to make bold (usually material name)
    ) {
        val totalWidth = helper.pageWidth - helper.margin * 2
        val columnWidths = colWidthPercentages.map { it * totalWidth }
        val headerHeight = 24f
        val paint = Paint().apply { isAntiAlias = true }

        // Local helper to draw table header row
        fun drawHeaderBlock(drawY: Float) {
            val cv = helper.canvas ?: return
            
            // Header Fill Background (Dark Navy)
            paint.color = android.graphics.Color.parseColor("#1E3A8A")
            paint.style = Paint.Style.FILL
            cv.drawRect(helper.margin, drawY, helper.pageWidth - helper.margin, drawY + headerHeight, paint)

            var currentX = helper.pageWidth - helper.margin
            for (i in headers.indices) {
                val colWidth = columnWidths[i]
                val headerText = headers[i]

                // Vertical separation border in header
                paint.color = android.graphics.Color.parseColor("#3B82F6")
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.75f
                cv.drawLine(currentX, drawY, currentX, drawY + headerHeight, paint)

                drawParagraph(
                    cv = cv,
                    text = headerText,
                    x = currentX - colWidth + 4f,
                    y = drawY + 5f,
                    width = colWidth.toInt() - 8,
                    textSize = 8.5f,
                    textColorHex = "#FFFFFF",
                    isBold = true,
                    align = "center"
                )
                currentX -= colWidth
            }
            // Left margin line
            paint.color = android.graphics.Color.parseColor("#1E3A8A")
            cv.drawLine(helper.margin, drawY, helper.margin, drawY + headerHeight, paint)
        }

        // Draw initial table header
        helper.ensureSpace(headerHeight + 25f)
        var topY = helper.currentY
        drawHeaderBlock(topY)
        helper.currentY = topY + headerHeight

        // Draw table body rows
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val isSumRow = highlightLastRow && (rowIndex == rows.size - 1)
            
            // Pre-calculate dynamic row height based on cell text wraps
            val tempCanvas = Canvas()
            var maxRowHeight = 20f
            
            for (i in row.indices) {
                val colWidth = columnWidths[i]
                val cellText = row[i]
                val itemBold = (i == boldColumnIndex) || isSumRow
                val wrappedHeight = drawParagraph(
                    cv = tempCanvas,
                    text = cellText,
                    x = 0f,
                    y = 0f,
                    width = colWidth.toInt() - 8,
                    textSize = 8f,
                    isBold = itemBold
                )
                val cellHeight = wrappedHeight + 10f
                if (cellHeight > maxRowHeight) {
                    maxRowHeight = cellHeight
                }
            }

            // Ensure vertical space on sheet before painting this row
            helper.ensureSpace(maxRowHeight)
            
            // If page break was triggered during space check, redraw headers!
            if (helper.currentY == helper.margin + 55f) {
                drawHeaderBlock(helper.margin + 55f)
                helper.currentY = helper.margin + 55f + headerHeight
            }

            val cv = helper.canvas ?: break
            val rowY = helper.currentY

            // Background coloring
            if (isSumRow) {
                paint.color = android.graphics.Color.parseColor("#EFF6FF") // Light soft blue for subtotal row
            } else if (rowIndex % 2 == 1) {
                paint.color = android.graphics.Color.parseColor("#F8FAFC") // Soft slate gray alternating color
            } else {
                paint.color = android.graphics.Color.parseColor("#FFFFFF")
            }
            paint.style = Paint.Style.FILL
            cv.drawRect(helper.margin, rowY, helper.pageWidth - helper.margin, rowY + maxRowHeight, paint)

            // Draw cells and column borders
            var cellX = helper.pageWidth - helper.margin
            for (i in row.indices) {
                val colWidth = columnWidths[i]
                val cellText = row[i]
                val isBoldStyle = (i == boldColumnIndex) || isSumRow || (i == 4 && !isSumRow) // Total cost column and material name are bold, quantity is regular

                // Draw cell right border
                paint.color = android.graphics.Color.parseColor("#CBD5E1")
                paint.strokeWidth = 0.5f
                paint.style = Paint.Style.STROKE
                cv.drawLine(cellX, rowY, cellX, rowY + maxRowHeight, paint)

                val contentColorStr = if (isSumRow) "#1E3A8A" else if (i == 2) "#0F172A" else "#334155"
                val cellAlign = if (i == boldColumnIndex || i == 1) "right" else "center"
                
                drawParagraph(
                    cv = cv,
                    text = cellText,
                    x = cellX - colWidth + 4f,
                    y = rowY + 5f,
                    width = colWidth.toInt() - 8,
                    textSize = if (isBoldStyle) 8.5f else 8f,
                    textColorHex = contentColorStr,
                    isBold = isBoldStyle,
                    align = cellAlign
                )
                cellX -= colWidth
            }
            // Left border line
            paint.color = android.graphics.Color.parseColor("#CBD5E1")
            paint.strokeWidth = 0.5f
            cv.drawLine(helper.margin, rowY, helper.margin, rowY + maxRowHeight, paint)
            
            // Bottom outline line of row
            cv.drawLine(helper.margin, rowY + maxRowHeight, helper.pageWidth - helper.margin, rowY + maxRowHeight, paint)

            helper.currentY = rowY + maxRowHeight
        }
    }

    fun estimateParagraphHeight(text: String, width: Float, textSize: Float, lineSpacingMult: Float = 1.15f): Float {
        if (text.isBlank()) return 0f
        val paint = TextPaint().apply {
            isAntiAlias = true
            this.textSize = textSize
        }
        val alignment = Layout.Alignment.ALIGN_NORMAL
        val heuristic = android.text.TextDirectionHeuristics.RTL
        val staticLayout = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(10))
                .setAlignment(alignment)
                .setTextDirection(heuristic)
                .setLineSpacing(0f, lineSpacingMult)
                .setIncludePad(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, width.toInt().coerceAtLeast(10), alignment, lineSpacingMult, 0f, true)
        }
        return staticLayout.height.toFloat()
    }

    /**
     * Draws the ultra-clean, exact replica of the GBR Paints factory printed A4 sheet shown in the reference image.
     * Fits perfectly on a single A4 page (595 x 842 pt).
     */
    fun drawOfficialA4FactoryDoc(
        helper: PdfCanvasHelper,
        formulation: Formulation,
        items: List<FormulationItemWithDetails>,
        qualityTests: List<FormulationQualityTest>,
        recipePhases: List<RecipePhase>,
        useRealNames: Boolean,
        usePercentage: Boolean,
        totalWeight: Double
    ) {
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }
        
        // Setup margins and page bounds - high side margins for premium printing breathing room
        val startX = 38f 
        val endX = helper.pageWidth - 38f
        val contentW = endX - startX // 519pt width
        
        val topMargin = 26f
        val bottomMargin = 26f
        val pageWidth = helper.pageWidth
        val pageHeight = helper.pageHeight
        
        // Reset vertical cursor
        helper.currentY = topMargin
        
        // ----------------- Page Decors (Double border with corner styling - placed at outer frame) -----------------
        val marginFrame = 22f
        paint.color = android.graphics.Color.parseColor("#94A3B8")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        cv.drawRect(marginFrame, marginFrame, pageWidth - marginFrame, pageHeight - marginFrame, paint)
        
        // Inner double border
        paint.color = android.graphics.Color.parseColor("#E2E8F0")
        paint.strokeWidth = 0.5f
        cv.drawRect(marginFrame + 3f, marginFrame + 3f, pageWidth - marginFrame - 3f, pageHeight - marginFrame - 3f, paint)
        
        // Corner decors: small L-shapes on the four corners
        paint.color = android.graphics.Color.parseColor("#1E3A8A")
        paint.strokeWidth = 1.5f
        // Top-Left corner
        cv.drawLine(marginFrame, marginFrame, marginFrame + 12f, marginFrame, paint)
        cv.drawLine(marginFrame, marginFrame, marginFrame, marginFrame + 12f, paint)
        // Top-Right corner
        cv.drawLine(pageWidth - marginFrame, marginFrame, pageWidth - marginFrame - 12f, marginFrame, paint)
        cv.drawLine(pageWidth - marginFrame, marginFrame, pageWidth - marginFrame, marginFrame + 12f, paint)
        // Bottom-Left corner
        cv.drawLine(marginFrame, pageHeight - marginFrame, marginFrame + 12f, pageHeight - marginFrame, paint)
        cv.drawLine(marginFrame, pageHeight - marginFrame, marginFrame, pageHeight - marginFrame - 12f, paint)
        // Bottom-Right corner
        cv.drawLine(pageWidth - marginFrame, pageHeight - marginFrame, pageWidth - marginFrame - 12f, pageHeight - marginFrame, paint)
        cv.drawLine(pageWidth - marginFrame, pageHeight - marginFrame, pageWidth - marginFrame, pageHeight - marginFrame - 12f, paint)
 
        // ----------------- 1. Top Logo & Product Spotlight Header -----------------
        val headerY = topMargin + 10f
        
        val prefs = helper.context.getSharedPreferences("gbr_prefs", android.content.Context.MODE_PRIVATE)
        val customLogoUri = prefs.getString("print_header_logo_uri", "") ?: ""
        val customTitle = prefs.getString("print_header_title", "دهانات GBR Paints") ?: "دهانات GBR Paints"
        val customSubtitle = prefs.getString("print_header_subtitle", "المجموعة الصناعية الفنية للدهانات") ?: "المجموعة الصناعية الفنية للدهانات"
        val customFooter = prefs.getString("print_footer_text", "المستند الإنتاجي المعتمد لرقابة الجودة بمصانع دهانات GBR") ?: "المستند الإنتاجي المعتمد لرقابة الجودة بمصانع دهانات GBR"

        var logoBitmap: android.graphics.Bitmap? = null
        if (customLogoUri.isNotBlank()) {
            try {
                val uri = android.net.Uri.parse(customLogoUri)
                val inputStream = helper.context.contentResolver.openInputStream(uri)
                logoBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (logoBitmap != null) {
            val destRect = android.graphics.RectF(startX, headerY, startX + 38f, headerY + 22f)
            cv.drawBitmap(logoBitmap, null, destRect, paint)
        } else {
            // Draw GBR Paint logo circle on top left
            paint.color = android.graphics.Color.parseColor("#1E3A8A") // Corporate Indigo Blue
            paint.style = Paint.Style.FILL
            cv.drawRoundRect(startX, headerY, startX + 38f, headerY + 22f, 4f, 4f, paint)
            
            // Draw "GBR" text inside logo
            paint.color = android.graphics.Color.WHITE
            paint.textSize = 10.5f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            cv.drawText("GBR", startX + 6f, headerY + 15f, paint)
        }
        
        // Draw Logo Subtitles (left aligned, next to logo)
        drawParagraph(
            cv = cv,
            text = "$customTitle\n$customSubtitle",
            x = startX + 44f,
            y = headerY - 2f,
            width = 160,
            textSize = 7.5f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "left"
        )
        
        // Highlighted Product Name (Most prominent element on top right)
        val productNameText = "المنتج: ${formulation.name}"
        drawParagraph(
            cv = cv,
            text = productNameText,
            x = endX - 300f,
            y = headerY - 5f,
            width = 300,
            textSize = 14f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "right"
        )
        
        // Subtitle Under Product Name
        drawParagraph(
            cv = cv,
            text = "وثيقة التركيبة الفنية والإنتاجية المعتمدة  📋",
            x = endX - 240f,
            y = headerY + 13f,
            width = 240,
            textSize = 8.5f,
            textColorHex = "#64748B",
            isBold = false,
            align = "right"
        )
        
        // Thin gray line separating header from cards row
        paint.color = android.graphics.Color.parseColor("#E2E8F0")
        paint.strokeWidth = 0.5f
        cv.drawLine(startX, headerY + 29f, endX, headerY + 29f, paint)
        
        // ----------------- 2. Horizontal Information Cards (3 columns to prevent congestion) -----------------
        val cardsY = headerY + 34f
        val cardW = (contentW - 20f) / 3f
        val cardH = 30f
        
        // Current date string
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale("ar"))
        val printDateStr = sdf.format(Date())
        
        val cardsData = listOf(
            Triple("تاريخ الطباعة", "$printDateStr  📅", "#1E3A8A"),
            Triple("الإصدار المعتمد", "إصدار ${formulation.version}", "#0F172A"),
            Triple("حالة التركيبة", if (formulation.status.contains("غير") || formulation.status.contains("مسودة")) "تحت المراجعة ⚠️" else "معتمدة للإنتاج ✅", if (formulation.status.contains("غير") || formulation.status.contains("مسودة")) "#B45309" else "#065F46")
        )
        
        for (i in 0..2) {
            val cardX = startX + i * (cardW + 10f)
            val data = cardsData[i]
            
            // Draw card background
            paint.style = Paint.Style.FILL
            if (i == 2) {
                paint.color = android.graphics.Color.parseColor(if (formulation.status.contains("غير") || formulation.status.contains("مسودة")) "#FEF3C7" else "#D1FAE5")
            } else {
                paint.color = android.graphics.Color.parseColor("#F8FAFC")
            }
            cv.drawRoundRect(cardX, cardsY, cardX + cardW, cardsY + cardH, 5f, 5f, paint)
            
            // Draw card border
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.75f
            paint.color = android.graphics.Color.parseColor(if (i == 2) {
                if (formulation.status.contains("غير") || formulation.status.contains("مسودة")) "#FCD34D" else "#10B981"
            } else "#E2E8F0")
            cv.drawRoundRect(cardX, cardsY, cardX + cardW, cardsY + cardH, 5f, 5f, paint)
            
            // Draw texts inside card
            drawParagraph(
                cv = cv,
                text = data.first,
                x = cardX + 4f,
                y = cardsY + 4f,
                width = cardW.toInt() - 8,
                textSize = 7f,
                textColorHex = if (i == 2) {
                    if (formulation.status.contains("غير") || formulation.status.contains("مسودة")) "#B45309" else "#64748B"
                } else "#64748B",
                isBold = false,
                align = "center"
            )
            drawParagraph(
                cv = cv,
                text = data.second,
                x = cardX + 4f,
                y = cardsY + 14f,
                width = cardW.toInt() - 8,
                textSize = 8.5f,
                textColorHex = data.third,
                isBold = true,
                align = "center"
            )
        }
        
        // ----------------- 3. Metrics/Dashboard capsule bar (3 columns, taller with larger values) -----------------
        val capsuleY = cardsY + cardH + 12f // increased vertical spacing
        val capsuleH = 32f // taller statistics bar as requested
        val capsuleW = contentW
        
        // Draw background container
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.parseColor("#EBF5FF") // Brand light blue capsule
        cv.drawRoundRect(startX, capsuleY, startX + capsuleW, capsuleY + capsuleH, 12f, 12f, paint)
        
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        paint.color = android.graphics.Color.parseColor("#BFDBFE")
        cv.drawRoundRect(startX, capsuleY, startX + capsuleW, capsuleY + capsuleH, 12f, 12f, paint)
        
        val segW = capsuleW / 3f
        val metricsList = listOf(
            Pair("⚙️ مراحل التشغيل", "${recipePhases.size} مراحل"),
            Pair("🧪 عدد المواد الخام", "${items.size} مادة"),
            Pair("⚖️ إجمالي الوزن الكلي", "${formatQuantity(totalWeight)} كغم")
        )
        
        for (i in 0..2) {
            val segX = startX + i * segW
            
            // Draw divider vertical line
            if (i > 0) {
                paint.color = android.graphics.Color.parseColor("#BFDBFE")
                paint.strokeWidth = 0.5f
                cv.drawLine(segX, capsuleY + 4f, segX, capsuleY + capsuleH - 4f, paint)
            }
            
            // Write description beautifully inline
            val label = metricsList[2 - i] // RTL order
            
            drawParagraph(
                cv = cv,
                text = label.first,
                x = segX + 2f,
                y = capsuleY + 4f,
                width = segW.toInt() - 4,
                textSize = 7.5f,
                textColorHex = "#475569",
                isBold = false,
                align = "center"
            )
            drawParagraph(
                cv = cv,
                text = label.second,
                x = segX + 2f,
                y = capsuleY + 16f,
                width = segW.toInt() - 4,
                textSize = 10f,
                textColorHex = "#1E3A8A",
                isBold = true,
                align = "center"
            )
        }
        
        // ----------------- 4. Dynamic Height & Spacing Calculations for Single-Page Layout -----------------
        val hasNotes = formulation.notes.isNotBlank()
        val limitPhases = recipePhases.take(if (items.size > 14) 4 else 6)
        val numPhases = limitPhases.size
        
        // Col width for bottom sections
        val colW = if (hasNotes) ((contentW - 14f) / 2f) else contentW
        
        val generalInstructionsList = limitPhases.mapNotNull { if (it.instructions.isBlank()) null else it.instructions }
        val finalInstructionsText = if (generalInstructionsList.isNotEmpty()) {
            generalInstructionsList.joinToString("\n") { "• $it" }
        } else {
            "• دعم خلط متجانس بدون تعليمات كيميائية إضافية.\n• التأكد من تجانس الخلطة قبل الانتقال للمرحلة التالية."
        }
        
        // Estimate Heights on Page
        val instLayoutHeight = estimateParagraphHeight(finalInstructionsText, colW - 6f, 7.5f)
        val notesLayoutHeight = if (hasNotes) {
            estimateParagraphHeight(formulation.notes, colW - 10f, 7.5f)
        } else 0f
        
        // Compute base height required for bottom sections
        // 12f (title) + 8f (spacing) + 12f (table header) + (numPhases * 12f) + 8f (space to instructions) + 10f (instructions title) + instLayoutHeight + 10f padding
        val baseRecipeHeight = 12f + 8f + 12f + (numPhases * 12f) + 8f + 10f + instLayoutHeight + 10f
        val baseNotesHeight = if (hasNotes) {
            12f + 8f + notesLayoutHeight + 20f
        } else 0f
        val bottomSectionNeededHeight = if (hasNotes) maxOf(baseRecipeHeight, baseNotesHeight) else baseRecipeHeight
        
        // Calculate remaining space for raw materials table
        val maxUsableY = 785f
        val topContentEndY = capsuleY + capsuleH // 146f
        // 16f (spaceA) + 12f (title) + 10f (titleToTable) + 16f (headerH) + 16f (tableToBottomSpace)
        val rawTableTitleAndHeaderH = 16f + 12f + 10f + 16f + 16f
        
        // Raw Materials row height calculation
        val spaceForRawTableContent = maxUsableY - topContentEndY - rawTableTitleAndHeaderH - bottomSectionNeededHeight
        val rawRowH = (spaceForRawTableContent / (items.size + 1)).coerceIn(12.5f, 22.0f)
        
        // Surplus distribution logic to prevent empty dead spaces and align visuals perfectly
        val totalFixedAndAllocated = topContentEndY + rawTableTitleAndHeaderH + ((items.size + 1) * rawRowH) + bottomSectionNeededHeight
        val surplus = (maxUsableY - totalFixedAndAllocated).coerceAtLeast(0f)
        
        // Distribute surplus
        val spaceA = 16f + surplus * 0.15f
        val titleToTable = 10f + surplus * 0.10f
        val tableToBottomSpace = 16f + surplus * 0.20f
        val bottomTitleSpacing = 8f + surplus * 0.05f
        val spaceBeforeInstructions = 8f + surplus * 0.05f
        val phaseRowH = 12f + if (numPhases > 0) (surplus * 0.15f / numPhases).coerceAtMost(6f) else 0f
        val notesCushion = 20f + surplus * 0.15f
        
        // Text sizes scaling based on row heights to look gorgeous and legible
        val rawTextSize = if (rawRowH < 14f) 6.5f else if (rawRowH < 17f) 7.2f else 8.0f
        val phaseTextSize = if (phaseRowH < 14f) 7.0f else 8.0f
        
        // ----------------- 5. Draw Raw Materials List Section (Fully RTL) -----------------
        var yCursor = topContentEndY + spaceA
        
        drawParagraph(
            cv = cv,
            text = "المكونات ونسب المواد الخام المعتمدة للإنتاج 🧪",
            x = endX - 350f,
            y = yCursor,
            width = 350,
            textSize = 9.5f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "right"
        )
        
        yCursor += 12f + titleToTable
        
        // Table columns from Right to Left:
        // م (index 0) ← اسم المادة (index 1) ← الكمية (index 2) ← الوحدة (index 3) ← المعلومات الإضافية (index 4)
        val tHeaders = listOf(
            "م",
            "اسم المادة الخام والمكون الكيميائي",
            "الكمية المعتمدة",
            "الوحدة",
            "طريقة الإضافة والتوجيه والتجهيز"
        )
        // Optimized percentages: Name column takes 58% (+14% wider than original) for long text!
        val tPercentages = listOf(0.04f, 0.58f, 0.14f, 0.06f, 0.18f) 
        val tColWidths = tPercentages.map { it * contentW }
        val tableHeaderH = 16f
        
        // Draw Header Blue background
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.parseColor("#1D4ED8") // GBR Blue header background
        cv.drawRect(startX, yCursor, startX + contentW, yCursor + tableHeaderH, paint)
        
        var currentX = startX + contentW
        for (i in tHeaders.indices) {
            val colW = tColWidths[i]
            
            // Draw internal header line
            if (i > 0) {
                paint.color = android.graphics.Color.parseColor("#60A5FA")
                paint.strokeWidth = 0.5f
                cv.drawLine(currentX, yCursor, currentX, yCursor + tableHeaderH, paint)
            }
            
            drawParagraph(
                cv = cv,
                text = tHeaders[i],
                x = currentX - colW,
                y = yCursor + 3f,
                width = colW.toInt(),
                textSize = 7.5f,
                textColorHex = "#FFFFFF",
                isBold = true,
                align = "center"
            )
            currentX -= colW
        }
        
        yCursor += tableHeaderH
        
        // Draw ingredient rows
        items.forEachIndexed { index, item ->
            val isEven = index % 2 == 1
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor(if (isEven) "#F8FAFC" else "#FFFFFF")
            cv.drawRect(startX, yCursor, startX + contentW, yCursor + rawRowH, paint)
            
            // Draw outer border lines
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            cv.drawRect(startX, yCursor, startX + contentW, yCursor + rawRowH, paint)
            
            val numStr = (index + 1).toString()
            val rawName = if (useRealNames) item.rawMaterialName else item.rawMaterialProductionName.ifBlank { item.rawMaterialName }
            
            val quantStr: String
            val unitStr: String
            if (usePercentage) {
                val pct = if (totalWeight > 0.0) (item.quantityMultiplier / totalWeight) * 100.0 else 0.0
                quantStr = formatQuantity(pct)
                unitStr = "٪"
            } else {
                quantStr = formatQuantity(item.quantityMultiplier)
                unitStr = "كغم"
            }
            
            val instructionStr = if (item.needsGrinding) "طحن وميكنة (${item.grindingDurationMinutes} د)" else "خلط وإضافة مباشرة"
            
            // Cells drawing from Right to Left to align with headers perfectly
            val rowCells = listOf(numStr, rawName, quantStr, unitStr, instructionStr)
            
            var cellX = startX + contentW
            for (i in rowCells.indices) {
                val colW = tColWidths[i]
                
                // Draw inner dividing borders
                paint.color = android.graphics.Color.parseColor("#E2E8F0")
                paint.strokeWidth = 0.5f
                paint.style = Paint.Style.STROKE
                cv.drawLine(cellX, yCursor, cellX, yCursor + rawRowH, paint)
                
                // Alignment: right-align for raw material name, center for numbers/details
                val alignType = if (i == 1) "right" else "center"
                val textPadR = if (i == 1) 6f else 0f
                val isBoldCol = (i == 1 || i == 2)
                
                drawParagraph(
                    cv = cv,
                    text = rowCells[i],
                    x = cellX - colW + textPadR,
                    y = yCursor + (rawRowH - 9f)/2f,
                    width = colW.toInt() - (textPadR * 2).toInt(),
                    textSize = rawTextSize,
                    textColorHex = if (isBoldCol) "#0F172A" else "#475569",
                    isBold = isBoldCol,
                    align = alignType
                )
                cellX -= colW
            }
            yCursor += rawRowH
        }
        
        // Draw the total summary weight row
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.parseColor("#EFF6FF") // Light soft blue for subtotal row
        cv.drawRect(startX, yCursor, startX + contentW, yCursor + rawRowH, paint)
        
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.5f
        paint.color = android.graphics.Color.parseColor("#BFDBFE")
        cv.drawRect(startX, yCursor, startX + contentW, yCursor + rawRowH, paint)
        
        val valueSumStr = if (usePercentage) "100 ٪" else "${formatQuantity(totalWeight)} كغم"
        drawParagraph(
            cv = cv,
            text = "إجمالي الوزن الكلي للوجبة ⚖️",
            x = startX + 120f,
            y = yCursor + (rawRowH - 10f)/2f,
            width = (contentW - 140f).toInt(),
            textSize = 8.5f,
            textColorHex = "#1D4ED8",
            isBold = true,
            align = "right"
        )
        drawParagraph(
            cv = cv,
            text = valueSumStr,
            x = startX + 12f,
            y = yCursor + (rawRowH - 10f)/2f,
            width = 150,
            textSize = 9.0f,
            textColorHex = "#1D4ED8",
            isBold = true,
            align = "left"
        )
        
        yCursor += rawRowH + tableToBottomSpace
        
        // ----------------- 6. Cooking Recipe & Notes Section (Dual Column RTL) -----------------
        val columnsY = yCursor
        
        // Left Column: وصفة ومراحل التشغيل والخلط (RTL, spans full width if notes are blank)
        val lColX = startX
        drawParagraph(
            cv = cv,
            text = "وصفة التشغيل ومراحل الخلط ⚙️",
            x = lColX + colW - 250f, // Align Section title nicely RTL. It will end exactly at lColX + colW
            y = columnsY,
            width = 250,
            textSize = 9f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "right"
        )
        
        // Headers for mini-recipe table
        val lHeaders = listOf("المرحلة", "السرعة (RPM)", "مدة التشغيل والملاحظات")
        val lPercentages = if (hasNotes) listOf(0.18f, 0.32f, 0.50f) else listOf(0.12f, 0.24f, 0.64f)
        val lColWidths = lPercentages.map { it * colW }
        val lHeaderY = columnsY + 12f + bottomTitleSpacing
        val lHeaderH = 13f
        
        // Paint header box
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.parseColor("#1E3A8A")
        cv.drawRect(lColX, lHeaderY, lColX + colW, lHeaderY + lHeaderH, paint)
        
        var lCurrX = lColX + colW
        for (i in lHeaders.indices) {
            val cellW = lColWidths[i]
            drawParagraph(
                cv = cv,
                text = lHeaders[i],
                x = lCurrX - cellW,
                y = lHeaderY + 2f,
                width = cellW.toInt(),
                textSize = 7f,
                textColorHex = "#FFFFFF",
                isBold = true,
                align = "center"
            )
            lCurrX -= cellW
        }
        
        var lCurrY = lHeaderY + lHeaderH
        limitPhases.forEachIndexed { idx, ph ->
            paint.style = Paint.Style.FILL
            paint.color = android.graphics.Color.parseColor(if (idx % 2 == 1) "#F8FAFC" else "#FFFFFF")
            cv.drawRect(lColX, lCurrY, lColX + colW, lCurrY + phaseRowH, paint)
            
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            cv.drawRect(lColX, lCurrY, lColX + colW, lCurrY + phaseRowH, paint)
            
            // RTL Row cells order
            val cells = listOf((idx + 1).toString(), ph.mixerRpm.toString(), "${ph.durationMinutes} دقيقة")
            var cellX = lColX + colW
            for (i in cells.indices) {
                val cw = lColWidths[i]
                cv.drawLine(cellX, lCurrY, cellX, lCurrY + phaseRowH, paint)
                
                drawParagraph(
                    cv = cv,
                    text = cells[i],
                    x = cellX - cw,
                    y = lCurrY + (phaseRowH - 9f)/2f,
                    width = cw.toInt(),
                    textSize = phaseTextSize,
                    textColorHex = if (i == 0) "#0F172A" else "#334155",
                    isBold = (i == 0),
                    align = "center"
                )
                cellX -= cw
            }
            lCurrY += phaseRowH
        }
        
        // General instructions below the mini table
        val instructionsY = lCurrY + spaceBeforeInstructions
        drawParagraph(
            cv = cv,
            text = "إرشادات عامة للمشغل 💬",
            x = lColX + colW - 200f,
            y = instructionsY,
            width = 200,
            textSize = 8.0f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "right"
        )
        
        drawParagraph(
            cv = cv,
            text = finalInstructionsText,
            x = lColX + 2f,
            y = instructionsY + 11f,
            width = colW.toInt() - 4,
            textSize = 7.2f,
            textColorHex = "#475569",
            isBold = false,
            align = "right"
        )
        
        // Right Column: ملاحظات فنية (Only draw if notes exist!)
        if (hasNotes) {
            val rColX = startX + colW + 14f
            drawParagraph(
                cv = cv,
                text = "ملاحظات فنية 📝",
                x = rColX + colW - 150f,
                y = columnsY,
                width = 150,
                textSize = 9f,
                textColorHex = "#1E3A8A",
                isBold = true,
                align = "right"
            )
            
            val notesStartY = columnsY + 12f + bottomTitleSpacing
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.5f
            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            
            drawParagraph(
                cv = cv,
                text = formulation.notes,
                x = rColX + 6f,
                y = notesStartY + 4f,
                width = colW.toInt() - 12,
                textSize = 7.5f,
                textColorHex = "#475569",
                isBold = false,
                align = "right"
            )
            
            val finalBottomSectionHeight = if (hasNotes) maxOf(baseRecipeHeight, baseNotesHeight) else baseRecipeHeight
            val cardBottomY = columnsY + finalBottomSectionHeight
            
            // Draw nice rounded card container around technical notes
            paint.style = Paint.Style.STROKE
            paint.color = android.graphics.Color.parseColor("#E2E8F0")
            cv.drawRoundRect(rColX, notesStartY, rColX + colW, cardBottomY, 4f, 4f, paint)
            
            // Draw decorative dotted notes line filler inside the card if there is empty space
            val dottedLinesStartY = notesStartY + 4f + notesLayoutHeight + 8f
            val dottedLinesCount = ((cardBottomY - 10f - dottedLinesStartY) / 10f).toInt().coerceIn(0, 3)
            var dottedY = dottedLinesStartY
            for (lineIdx in 0 until dottedLinesCount) {
                if (dottedY + 8f < cardBottomY) {
                    drawParagraph(
                        cv = cv,
                        text = ". . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . . .",
                        x = rColX + 5f,
                        y = dottedY,
                        width = colW.toInt() - 10,
                        textSize = 6f,
                        textColorHex = "#94A3B8",
                        isBold = false,
                        align = "center"
                    )
                    dottedY += 10f
                }
            }
        }
        
        // ----------------- Page Footer (Aligned strictly along the bottom) -----------------
        val footerY = pageHeight - bottomMargin - 15f
        paint.color = android.graphics.Color.parseColor("#E2E8F0")
        paint.strokeWidth = 0.5f
        cv.drawLine(startX, footerY - 4f, endX, footerY - 4f, paint)
        
        // Far left: page count
        drawParagraph(
            cv = cv,
            text = "الصفحة ١ من ١",
            x = startX,
            y = footerY,
            width = 100,
            textSize = 7f,
            textColorHex = "#94A3B8",
            isBold = false,
            align = "left"
        )
        
        // Center: Corporate text
        drawParagraph(
            cv = cv,
            text = customFooter,
            x = startX + 100f,
            y = footerY,
            width = (contentW - 200f).toInt(),
            textSize = 7f,
            textColorHex = "#94A3B8",
            align = "center"
        )
        
        // Far right: GBR tag
        drawParagraph(
            cv = cv,
            text = "مستند إنتاجي معتمد الفنية",
            x = endX - 150f,
            y = footerY,
            width = 150,
            textSize = 7f,
            textColorHex = "#1D4ED8",
            isBold = true,
            align = "right"
        )
    }

    fun printFormulationPdf(
        context: Context,
        formulation: Formulation,
        items: List<FormulationItemWithDetails>,
        qualityTests: List<FormulationQualityTest>,
        allQCDefinitions: List<QualityTest>,
        recipePhases: List<RecipePhase>,
        customPackagings: List<com.example.ui.GbrViewModel.CustomPackaging>,
        useRealNames: Boolean,
        usePercentage: Boolean,
        checkedSections: Map<String, Boolean>,
        docType: Int = 0 // 0 = Full, 1 = Short, 2 = Cost, 3 = Product Card
    ) {
        try {
            val pdfDocument = PdfDocument()
            val docTitleStr = when (docType) {
                1 -> "ورقة تشغيل مختصرة - جاهزة للإنتاج"
                2 -> "تقرير تكلفة وجدوى إنتاج التركيبة"
                3 -> "بطاقة وثيقة الخصائص التعريفية للمنتج"
                else -> "وثيقة التركيبة الفنية والتشغيلية الكاملة"
            }
            
            val helper = PdfCanvasHelper(
                context = context,
                pdfDocument = pdfDocument,
                formulation = formulation,
                docType = docType,
                useRealNames = useRealNames,
                usePercentage = usePercentage,
                headerTitle = docTitleStr
            )

            // Calculate total weight multipliers
            val totalWeightSum = items.sumOf { it.quantityMultiplier }

            if (docType == 0) {
                // TYPE 0 IS THE PREMIUM SINGLE-PAGE A4 FACTORY LAYOUT AS SHOWN IN THE IMAGE
                drawOfficialA4FactoryDoc(
                    helper = helper,
                    formulation = formulation,
                    items = items,
                    qualityTests = qualityTests,
                    recipePhases = recipePhases,
                    useRealNames = useRealNames,
                    usePercentage = usePercentage,
                    totalWeight = totalWeightSum
                )
            } else {
                // ==========================================
                // Other Legacy/Document Formats
                // ==========================================
                drawProductHeroSection(helper, formulation, docType)
                drawQuickSummaryRibbon(helper, items, recipePhases, qualityTests, customPackagings, formulation, totalWeightSum)
                helper.currentY += 15f

                when (docType) {
                    1 -> {
                        // TYPE 1: One-page short production sheet (ورقة تشغيل مختصرة)
                        if (items.isNotEmpty()) {
                            drawSectionHeader(helper, "مكونات التركيبة والمواد الخام المعتمدة", isPrimaryAccent = true)
                            drawIngredientsCompactTable(helper, items, useRealNames, usePercentage, totalWeightSum)
                            helper.currentY += 10f
                        }

                        if (recipePhases.isNotEmpty()) {
                            drawSectionHeader(helper, "وصفة ومراحل التشغيل المباشرة", isPrimaryAccent = false)
                            drawRecipePhasesSummaryList(helper, recipePhases, compactMode = true)
                        }
                    }
                    
                    2 -> {
                        // TYPE 2: Formulation cost report (تقرير تكلفة التركيبة)
                        if (items.isNotEmpty()) {
                            drawSectionHeader(helper, "تكاليف المواد الخام والمكونات الأساسية", isPrimaryAccent = true)
                            val totalRawCost = drawIngredientsCostTable(helper, items, useRealNames, totalWeightSum)
                            helper.currentY += 14f

                            drawSectionHeader(helper, "جدول احتساب تكاليف التعبئة والتشغيل", isPrimaryAccent = true)
                            drawPackagingCostAnalysisTable(helper, formulation, customPackagings, totalWeightSum, totalRawCost)
                        }
                    }

                    3 -> {
                        // TYPE 3: Brief product card (بطاقة منتج مختصرة)
                        if (formulation.description.isNotBlank()) {
                            drawSectionHeader(helper, "لمحة وصفية للمنتج ومجالات التطبيق", isPrimaryAccent = true)
                            drawBriefProductDescription(helper, formulation.description)
                            helper.currentY += 12f
                        }

                        val activeTests = qualityTests.filter { it.isEnabled }
                        if (activeTests.isNotEmpty()) {
                            drawSectionHeader(helper, "الخصائص الفنية ومعايير الجودة المعتمدة", isPrimaryAccent = false)
                            drawTechnicalParametersTable(helper, activeTests, allQCDefinitions)
                            helper.currentY += 12f
                        }

                        // Packagings supported list
                        drawSectionHeader(helper, "فئات وأحجام التعبئة المتاحة للموزعين والمستهلكين", isPrimaryAccent = true)
                        drawPackagingSpecsTable(helper, formulation, customPackagings)
                        helper.currentY += 12f

                        if (formulation.notes.isNotBlank()) {
                            drawSectionHeader(helper, "التوجيهات والتوصيات الفنية للسلامة والتطبيق", isPrimaryAccent = false)
                            drawNotesAlertBox(helper, formulation.notes)
                        }
                    }
                }
            }

            // Finish PDF Compilation
            helper.finishDocument()

            // Save document file in cache
            val cleanName = formulation.name.replace("/", "_").replace("\\", "_").replace(" ", "_")
            val docPrefixName = when (docType) {
                1 -> "Short_Work_Sheet"
                2 -> "Cost_Report"
                3 -> "Product_Card"
                else -> "Full_Technical_Spec"
            }
            val fileName = "${docPrefixName}_${cleanName}_V${formulation.version}.pdf"
            val file = File(context.cacheDir, fileName)
            
            val outputStream = FileOutputStream(file)
            pdfDocument.writeTo(outputStream)
            outputStream.flush()
            outputStream.close()
            pdfDocument.close()

            // Start Android Printer
            startPrintJob(context, file, fileName)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "فشل بناء وتصدير مستند PDF: ${e.localizedMessage} ❌", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Draws GBR Paints Large Hero Product Header and State Badge
     */
    private fun drawProductHeroSection(helper: PdfCanvasHelper, formulation: Formulation, docType: Int) {
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }
        
        // Large box grouping product info on page start
        val panelH = 58f
        helper.ensureSpace(panelH + 10f)

        val activeY = helper.currentY
        paint.color = android.graphics.Color.parseColor("#F8FAFC")
        paint.style = Paint.Style.FILL
        cv.drawRoundRect(
            helper.margin,
            activeY,
            helper.pageWidth - helper.margin,
            activeY + panelH,
            6f, 6f,
            paint
        )

        paint.color = android.graphics.Color.parseColor("#E2E8F0")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        cv.drawRoundRect(
            helper.margin,
            activeY,
            helper.pageWidth - helper.margin,
            activeY + panelH,
            6f, 6f,
            paint
        )

        // Title text in Center (Dominant visual hierarchy)
        val docTypeHeader = when (docType) {
            1 -> "ورقة تشغيل وصنع فنية"
            2 -> "تقرير دراسة وتكلفة التركيبة"
            3 -> "بطاقة تحديد خواص المنتج"
            else -> "وثيقة إنتاج وتكامل فني"
        }
        
        drawParagraph(
            cv = cv,
            text = "$docTypeHeader: ${formulation.name}",
            x = helper.margin + 15f,
            y = activeY + 10f,
            width = (helper.pageWidth - helper.margin * 2 - 30f).toInt(),
            textSize = 14f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "center"
        )

        // Draw sub-details beneath the main name
        val metaSubText = "كود المستند المخزني: ${formulation.code.ifBlank { "N/A" }}   |   رقم النسخة المعتمدة: V${formulation.version}   |   الحالة: ${formulation.status}"
        drawParagraph(
            cv = cv,
            text = metaSubText,
            x = helper.margin + 15f,
            y = activeY + 34f,
            width = (helper.pageWidth - helper.margin * 2 - 30f).toInt(),
            textSize = 8.5f,
            textColorHex = "#475569",
            isBold = false,
            align = "center"
        )

        helper.currentY = activeY + panelH + 12f
    }

    /**
     * Draws the 5-metric dashboard Ribbon (Quick Summary Ribbon) spanning across page 1
     */
    private fun drawQuickSummaryRibbon(
        helper: PdfCanvasHelper,
        items: List<FormulationItemWithDetails>,
        recipePhases: List<RecipePhase>,
        qualityTests: List<FormulationQualityTest>,
        customPackagings: List<com.example.ui.GbrViewModel.CustomPackaging>,
        formulation: Formulation,
        totalWeight: Double
    ) {
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }

        val ribbonH = 42f
        helper.ensureSpace(ribbonH + 10f)

        val startY = helper.currentY
        val totalWidth = helper.pageWidth - helper.margin * 2
        
        // Background for entire ribbon row
        paint.color = android.graphics.Color.parseColor("#F1F5F9")
        paint.style = Paint.Style.FILL
        cv.drawRoundRect(helper.margin, startY, helper.pageWidth - helper.margin, startY + ribbonH, 4f, 4f, paint)

        paint.color = android.graphics.Color.parseColor("#E2E8F0")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        cv.drawRoundRect(helper.margin, startY, helper.pageWidth - helper.margin, startY + ribbonH, 4f, 4f, paint)

        // Gather metrics values
        val materialsCountInt = items.size
        val phasesCountInt = recipePhases.size
        val testsCountInt = qualityTests.filter { it.isEnabled }.size
        
        var pkgCountInt = 0
        if (formulation.supports18L) pkgCountInt++
        if (formulation.supports5L) pkgCountInt++
        try {
            if (formulation.packagingWeightsJson.isNotBlank()) {
                val json = JSONObject(formulation.packagingWeightsJson)
                json.keys().forEach { k ->
                    if (k != "total_operating_cost" && json.optString(k, "").isNotBlank()) {
                        pkgCountInt++
                    }
                }
            }
        } catch (e: Exception) {}

        val listMetrics = listOf(
            Pair("إجمالي الوزن", String.format(Locale.US, "%,.1f كغم", totalWeight)),
            Pair("عدد المواد الخام", "$materialsCountInt صنف"),
            Pair("مراحل التشغيل المعتمدة", "$phasesCountInt مرحلة"),
            Pair("فحوصات الجودة الفنية", "$testsCountInt فحص"),
            Pair("فئات العبوات المدعومة", "$pkgCountInt عبوات")
        )

        val size = listMetrics.size
        val blockWidth = totalWidth / size

        var currentX = helper.pageWidth - helper.margin
        for (i in listMetrics.indices) {
            val metric = listMetrics[i]
            
            // Draw division line
            if (i > 0) {
                paint.color = android.graphics.Color.parseColor("#DFE4EC")
                paint.strokeWidth = 1f
                cv.drawLine(currentX, startY + 5f, currentX, startY + ribbonH - 5f, paint)
            }

            // Metric small title on top
            drawParagraph(
                cv = cv,
                text = metric.first,
                x = currentX - blockWidth + 2f,
                y = startY + 6f,
                width = blockWidth.toInt() - 4,
                textSize = 7.5f,
                textColorHex = "#64748B",
                isBold = false,
                align = "center"
            )

            // Metric large value below
            drawParagraph(
                cv = cv,
                text = metric.second,
                x = currentX - blockWidth + 2f,
                y = startY + 18f,
                width = blockWidth.toInt() - 4,
                textSize = 10f,
                textColorHex = "#1E3A8A",
                isBold = true,
                align = "center"
            )

            currentX -= blockWidth
        }

        helper.currentY = startY + ribbonH + 10f
    }

    /**
     * Prints a beautiful, compact and highly readable ingredients table
     */
    private fun drawIngredientsCompactTable(
        helper: PdfCanvasHelper,
        items: List<FormulationItemWithDetails>,
        useRealNames: Boolean,
        usePercentage: Boolean,
        totalWeight: Double
    ) {
        val headers = listOf("الرقم", "اسم وخامة المادة المعتمدة", "كمية التوجيه", "الوحدة", "عملية الطحن والتمديد")
        val percentages = listOf(0.08f, 0.46f, 0.16f, 0.12f, 0.18f)

        val rows = ArrayList<List<String>>()
        items.forEachIndexed { idx, item ->
            val num = (idx + 1).toString()
            val rawName = if (useRealNames) item.rawMaterialName else item.rawMaterialProductionName.ifBlank { item.rawMaterialName }
            
            val quantStr: String
            val unitStr: String
            if (usePercentage) {
                val pct = if (totalWeight > 0.0) (item.quantityMultiplier / totalWeight) * 100.0 else 0.0
                quantStr = formatQuantity(pct)
                unitStr = "٪"
            } else {
                quantStr = formatQuantity(item.quantityMultiplier)
                unitStr = "كغم"
            }

            val machineSpec = if (item.needsGrinding) "مطحنة (${item.grindingDurationMinutes} د)" else "خلط وإضافة مباشرة"
            rows.add(listOf(num, rawName, quantStr, unitStr, machineSpec))
        }

        // Add overall calculation sum total row
        val valueTotalStr = if (usePercentage) "100.000" else String.format(Locale.US, "%,.2f", totalWeight)
        val unitTotalStr = if (usePercentage) "٪" else "كغم"
        rows.add(listOf("∑", "الوزن الكلّي المتكامل للوجبة", valueTotalStr, unitTotalStr, "قاعدة صلبة مستقرة"))

        drawGridTable(
            helper = helper,
            headers = headers,
            colWidthPercentages = percentages,
            rows = rows,
            highlightLastRow = true,
            boldColumnIndex = 1
        )
    }

    /**
     * Prints the ingredients list detailed table decorated with cost breakdown computations
     */
    private fun drawIngredientsCostTable(
        helper: PdfCanvasHelper,
        items: List<FormulationItemWithDetails>,
        useRealNames: Boolean,
        totalWeight: Double
    ): Double {
        val headers = listOf("الرقم", "اسم الخامة والمكون والمغذيات الفنية", "كمية المدخل (كغم)", "سعر الوحدة / كغم", "تكلفة المكون الكلّية")
        val percentages = listOf(0.08f, 0.44f, 0.16f, 0.15f, 0.17f)

        var totalRawCostValue = 0.0
        val rows = ArrayList<List<String>>()
        
        items.forEachIndexed { idx, item ->
            val num = (idx + 1).toString()
            val rawName = if (useRealNames) item.rawMaterialName else item.rawMaterialProductionName.ifBlank { item.rawMaterialName }
            
            // Quantity in kg
            val qtyMultiplierValue = item.quantityMultiplier
            val qtyStr = String.format(Locale.US, "%,.2f", qtyMultiplierValue)
            
            // Price calculation per item
            val itemPrice = item.simulatedPrice ?: item.rawMaterialPrice
            val itemPriceStr = String.format(Locale.US, "%,.2f", itemPrice)
            
            val itemCostValue = qtyMultiplierValue * itemPrice
            totalRawCostValue += itemCostValue
            val itemCostStr = String.format(Locale.US, "%,.2f", itemCostValue)

            rows.add(listOf(num, rawName, qtyStr, itemPriceStr, itemCostStr))
        }

        // Append subtotal summing row
        val totalRawCostStr = String.format(Locale.US, "%,.2f", totalRawCostValue)
        val totalWeightStr = String.format(Locale.US, "%,.2f كغم", totalWeight)
        rows.add(listOf("∑", "إجمالي تكلفة المواد الخام", totalWeightStr, "-", totalRawCostStr))

        drawGridTable(
            helper = helper,
            headers = headers,
            colWidthPercentages = percentages,
            rows = rows,
            highlightLastRow = true,
            boldColumnIndex = 1
        )

        // Show Cost per Single Kilogram (Highlight Block Card)
        val costPerKg = if (totalWeight > 0.0) totalRawCostValue / totalWeight else 0.0
        val costPerTon = costPerKg * 1000.0

        helper.ensureSpace(42f)
        val cv = helper.canvas ?: return totalRawCostValue
        val paint = Paint().apply { isAntiAlias = true }
        val infoY = helper.currentY + 6f

        paint.color = android.graphics.Color.parseColor("#EFF6FF")
        paint.style = Paint.Style.FILL
        cv.drawRoundRect(helper.margin, infoY, helper.pageWidth - helper.margin, infoY + 34f, 4f, 4f, paint)

        paint.color = android.graphics.Color.parseColor("#BFDBFE")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        cv.drawRoundRect(helper.margin, infoY, helper.pageWidth - helper.margin, infoY + 34f, 4f, 4f, paint)

        val totalCostText = "التكلفة الإجمالية للوجبة: ${String.format(Locale.US, "%,.2f", totalRawCostValue)} شيكل"
        drawParagraph(
            cv = cv,
            text = totalCostText,
            x = helper.margin + 10f,
            y = infoY + 4f,
            width = (helper.pageWidth - helper.margin * 2 - 20f).toInt(),
            textSize = 10.5f,
            textColorHex = "#1E3A8A",
            isBold = true,
            align = "center"
        )

        val subDetailsText = "تكلفة الكيلو الصافي: ${String.format(Locale.US, "%,.3f", costPerKg)}   |   تكلفة الطن (1,000 كغم): ${String.format(Locale.US, "%,.2f", costPerTon)}"
        drawParagraph(
            cv = cv,
            text = subDetailsText,
            x = helper.margin + 10f,
            y = infoY + 18f,
            width = (helper.pageWidth - helper.margin * 2 - 20f).toInt(),
            textSize = 8f,
            textColorHex = "#475569",
            isBold = false,
            align = "center"
        )
        
        helper.currentY = infoY + 40f
        return totalRawCostValue
    }

    /**
     * Prints the cost calculations table showing packaging sizes, net weight, empty container costs, paint value, operating loads, and combined total cost
     */
    private fun drawPackagingCostAnalysisTable(
        helper: PdfCanvasHelper,
        formulation: Formulation,
        customPackagings: List<com.example.ui.GbrViewModel.CustomPackaging>,
        totalWeight: Double,
        totalRawCost: Double
    ) {
        val costPerKgValue = if (totalWeight > 0.0) totalRawCost / totalWeight else 0.0
        
        // Parse dynamic configurations
        val parsedWeights = try {
            if (formulation.packagingWeightsJson.isBlank()) {
                emptyMap()
            } else {
                val json = JSONObject(formulation.packagingWeightsJson)
                val map = mutableMapOf<String, String>()
                json.keys().forEach { k ->
                    map[k] = json.optString(k, "")
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }

        // Determine loaded operating overheads
        val totalOperatingOverheadValue = parsedWeights["total_operating_cost"]?.toDoubleOrNull() ?: 0.0
        val operatingCostLoadPerKg = if (totalWeight > 0.0) totalOperatingOverheadValue / totalWeight else 0.0

        val headers = listOf("العبوة المدعومة", "الوزن الصافي", "الوزن الاجمالي", "تكلفة العبوة فارغة", "قيمة المادة فقط", "تحميل المصاريف", "التكلفة الاجمالية")
        val percentages = listOf(0.22f, 0.11f, 0.12f, 0.14f, 0.14f, 0.13f, 0.14f)
        val rows = ArrayList<List<String>>()

        fun createPackagingRow(name: String, netW: Double, emptyPkgCost: Double, emptyContainerWeight: Double) {
            val grossWeight = netW + emptyContainerWeight
            val paintInsideValue = netW * costPerKgValue
            val operatingCharge = netW * operatingCostLoadPerKg
            val totalFilledCombined = emptyPkgCost + paintInsideValue + operatingCharge

            rows.add(listOf(
                name,
                "${String.format(Locale.US, "%.1f", netW)} كغم",
                "${String.format(Locale.US, "%.1f", grossWeight)} كغم",
                "${String.format(Locale.US, "%.2f", emptyPkgCost)}",
                "${String.format(Locale.US, "%.2f", paintInsideValue)}",
                "${String.format(Locale.US, "%.2f", operatingCharge)}",
                "${String.format(Locale.US, "%.2f", totalFilledCombined)}"
            ))
        }

        // Standard options (18L canister & 5L drum)
        if (formulation.supports18L) {
            val netW18 = formulation.netWeight18L.toDoubleOrNull() ?: 18.0
            val pkg18 = customPackagings.find { it.id == "1" || it.netWeight == 18.0 || it.name.contains("18") }
            val emptyW18 = pkg18?.let { it.weightWithLid - it.netWeight } ?: 1.2
            createPackagingRow("عبوة كبيرة (18 لتر)", netW18, 15.0, emptyW18)
        }
        if (formulation.supports5L) {
            val netW5 = formulation.netWeight5L.toDoubleOrNull() ?: 5.0
            val pkg5 = customPackagings.find { it.id == "2" || it.netWeight == 5.0 || it.name.contains("5") }
            val emptyW5 = pkg5?.let { it.weightWithLid - it.netWeight } ?: 0.4
            createPackagingRow("جالون متوسط (5 لتر)", netW5, 5.50, emptyW5)
        }

        // Dynamic extra configurations
        customPackagings.forEach { cPkg ->
            val specRawVal = parsedWeights[cPkg.id]
            if (!specRawVal.isNullOrBlank()) {
                val parts = specRawVal.split(":")
                val netW = parts.getOrNull(0)?.toDoubleOrNull() ?: cPkg.netWeight
                val emptyW = cPkg.weightWithLid - cPkg.netWeight
                createPackagingRow(cPkg.name, netW, cPkg.price, emptyW)
            }
        }

        if (rows.isEmpty()) {
            rows.add(listOf("لا تتوفر مواصفات دقيقة لجدوى تعبئة أي عبوة مسجلة حالياً.", "-", "-", "-", "-", "-", "-"))
        }

        drawGridTable(
            helper = helper,
            headers = headers,
            colWidthPercentages = percentages,
            rows = rows,
            highlightLastRow = false,
            boldColumnIndex = 0
        )
    }

    /**
     * Prints supporting packaging options and targeting net weight bounds
     */
    private fun drawPackagingSpecsTable(
        helper: PdfCanvasHelper,
        formulation: Formulation,
        customPackagings: List<com.example.ui.GbrViewModel.CustomPackaging>
    ) {
        val parsedWeights = try {
            if (formulation.packagingWeightsJson.isBlank()) {
                emptyMap()
            } else {
                val json = JSONObject(formulation.packagingWeightsJson)
                val map = mutableMapOf<String, String>()
                json.keys().forEach { k ->
                    map[k] = json.optString(k, "")
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }

        val headers = listOf("الرقم", "فئة العبوة وسعة التعبئة المقررة", "مواصفة وتوجيه الوزن الصافي المستهدف")
        val percentages = listOf(0.12f, 0.48f, 0.40f)
        val rows = ArrayList<List<String>>()

        var pIdx = 1
        if (formulation.supports18L) {
            val w = formulation.netWeight18L.ifBlank { "غير محدد بدقة" }
            rows.add(listOf((pIdx++).toString(), "سطل كبير (18 لتر معياري)", "$w كغم مستهدف وزن صافي"))
        }
        if (formulation.supports5L) {
            val w = formulation.netWeight5L.ifBlank { "غير محدد بدقة" }
            rows.add(listOf((pIdx++).toString(), "جالون متوسط (5 لتر معياري)", "$w كغم مستهدف وزن صافي"))
        }

        customPackagings.forEach { cPkg ->
            val specRawVal = parsedWeights[cPkg.id]
            if (!specRawVal.isNullOrBlank()) {
                val netWeightValueSpec = specRawVal.split(":").firstOrNull() ?: ""
                if (netWeightValueSpec.isNotBlank()) {
                    rows.add(listOf((pIdx++).toString(), cPkg.name, "$netWeightValueSpec كغم مستهدف وزن صافي"))
                }
            }
        }

        if (rows.isEmpty()) {
            rows.add(listOf("-", "لا تتوفر مواصفات أحجام تعبئة معلنة لهذه التركيبة.", "-"))
        }

        drawGridTable(
            helper = helper,
            headers = headers,
            colWidthPercentages = percentages,
            rows = rows,
            highlightLastRow = false,
            boldColumnIndex = 1
        )
    }

    /**
     * Prints Quality Specifications Table
     */
    private fun drawTechnicalParametersTable(
        helper: PdfCanvasHelper,
        activeTests: List<FormulationQualityTest>,
        allQCDefinitions: List<QualityTest>
    ) {
        val headers = listOf("الرقم", "مسند المطابقة ومعيار فحص الجودة", "الأسلوب الفني للقياس والتحليل", "الحد الأدنى للقبول", "الحد الأقصى للقبول")
        val percentages = listOf(0.08f, 0.42f, 0.20f, 0.15f, 0.15f)
        val rows = ArrayList<List<String>>()

        activeTests.forEachIndexed { idx, fTest ->
            val num = (idx + 1).toString()
            val qcDef = allQCDefinitions.find { it.id == fTest.testId }
            val tName = qcDef?.name ?: "تحليل داخلي #${fTest.testId}"
            val checkToolStr = "قياسي ومعياري"
            val minStr = fTest.minValue?.let { String.format(Locale.US, "%.2f", it) } ?: "مستمر"
            val maxStr = fTest.maxValue?.let { String.format(Locale.US, "%.2f", it) } ?: "مستمر"

            rows.add(listOf(num, tName, checkToolStr, minStr, maxStr))
        }

        drawGridTable(
            helper = helper,
            headers = headers,
            colWidthPercentages = percentages,
            rows = rows,
            highlightLastRow = false,
            boldColumnIndex = 1
        )
    }

    /**
     * Render detailed sequential card-like recipe phases
     */
    private fun drawRecipePhasesSummaryList(
        helper: PdfCanvasHelper,
        recipePhases: List<RecipePhase>,
        compactMode: Boolean
    ) {
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }

        val sortedPhases = recipePhases.sortedBy { it.sequence }

        if (compactMode) {
            // One-page Short View: Render as a single beautiful compact table
            val headers = listOf("المرحلة", "اسم المرحلة والعملية", "سرعة الخالط (RPM)", "الزمن (دقيقة)")
            val percentages = listOf(0.12f, 0.52f, 0.20f, 0.16f)
            val rows = ArrayList<List<String>>()

            sortedPhases.forEach { phase ->
                rows.add(listOf(
                    "مرحلة ${phase.sequence}",
                    phase.name,
                    if (phase.mixerRpm > 0) "${phase.mixerRpm} دقيقة/لفة" else "إضافة يدوية",
                    "${phase.durationMinutes} دقيقة"
                ))
            }
            drawGridTable(helper, headers, percentages, rows, boldColumnIndex = 1)
        } else {
            // Full Detailed View: Render elegant cards with bullet connectors!
            for (idx in sortedPhases.indices) {
                val phase = sortedPhases[idx]
                val cardH = 80f
                helper.ensureSpace(cardH)

                val cardY = helper.currentY + 6f
                val totalW = helper.pageWidth - helper.margin * 2

                // Draw background card shadow outline in subtle blue/gray
                paint.color = android.graphics.Color.parseColor("#FFFFFF")
                paint.style = Paint.Style.FILL
                cv.drawRoundRect(helper.margin, cardY, helper.pageWidth - helper.margin, cardY + cardH - 10f, 4f, 4f, paint)

                paint.color = android.graphics.Color.parseColor("#E2E8F0")
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                cv.drawRoundRect(helper.margin, cardY, helper.pageWidth - helper.margin, cardY + cardH - 10f, 4f, 4f, paint)

                // Colored ribbon indicator inside the card
                paint.color = android.graphics.Color.parseColor("#3B82F6") // Vibrant Blue for phases
                paint.style = Paint.Style.FILL
                cv.drawRoundRect(helper.pageWidth - helper.margin - 4f, cardY, helper.pageWidth - helper.margin, cardY + 16f, 2f, 2f, paint)

                // Sequence Pill text on right
                val stepTitle = "المرحلة ${phase.sequence}: ${phase.name}"
                drawParagraph(
                    cv = cv,
                    text = stepTitle,
                    x = helper.margin + 120f,
                    y = cardY + 6f,
                    width = (totalW - 130f).toInt(),
                    textSize = 9.5f,
                    textColorHex = "#1E3A8A",
                    isBold = true,
                    align = "right"
                )

                // Mixing Speed & time badge on left
                val badgeText = "⏱️ ${phase.durationMinutes} دقيقة  |  ⚙️ ${phase.mixerRpm} RPM"
                paint.color = android.graphics.Color.parseColor("#FEF3C7") // amber pill background
                cv.drawRoundRect(helper.margin + 6f, cardY + 5f, helper.margin + 120f, cardY + 20f, 3f, 3f, paint)

                drawParagraph(
                    cv = cv,
                    text = badgeText,
                    x = helper.margin + 8f,
                    y = cardY + 7f,
                    width = 110,
                    textSize = 7.5f,
                    textColorHex = "#B55309",
                    isBold = true,
                    align = "center"
                )

                // Separator line inside card
                paint.color = android.graphics.Color.parseColor("#F1F5F9")
                paint.strokeWidth = 0.5f
                cv.drawLine(helper.margin + 12f, cardY + 24f, helper.pageWidth - helper.margin - 12f, cardY + 24f, paint)

                // Instructions body
                val instructionNotes = phase.instructions.ifBlank { "دعم خلط متجانس بدون تعليمات كيميائية إضافية." }
                val spaceTextHeight = drawParagraph(
                    cv = cv,
                    text = "إرشادات المشغل: $instructionNotes",
                    x = helper.margin + 12f,
                    y = cardY + 28f,
                    width = (totalW - 24f).toInt(),
                    textSize = 8f,
                    textColorHex = "#475569",
                    isBold = false,
                    align = "right"
                )

                helper.currentY = cardY + cardH + 2f
            }
        }
    }

    /**
     * Draws Product Marketing Description Box
     */
    private fun drawBriefProductDescription(helper: PdfCanvasHelper, description: String) {
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }
        
        val wrapWidth = (helper.pageWidth - helper.margin * 2 - 24f).toInt()
        val textHeight = drawParagraph(
            cv = Canvas(),
            text = description,
            x = 0f, y = 0f,
            width = wrapWidth,
            textSize = 8.5f
        )

        val blockH = textHeight + 16f
        helper.ensureSpace(blockH)

        val activeY = helper.currentY
        paint.color = android.graphics.Color.parseColor("#F8FAFC")
        paint.style = Paint.Style.FILL
        cv.drawRect(helper.margin, activeY, helper.pageWidth - helper.margin, activeY + blockH, paint)

        paint.color = android.graphics.Color.parseColor("#CBD5E1")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        cv.drawRect(helper.margin, activeY, helper.pageWidth - helper.margin, activeY + blockH, paint)

        drawParagraph(
            cv = cv,
            text = description,
            x = helper.margin + 12f,
            y = activeY + 8f,
            width = wrapWidth,
            textSize = 8.5f,
            textColorHex = "#334155",
            isBold = false,
            align = "right"
        )

        helper.currentY = activeY + blockH + 6f
    }

    /**
     * Draws notes or important alerts section highlighted as solid boxes
     */
    private fun drawNotesAlertBox(helper: PdfCanvasHelper, rawNotes: String) {
        val cv = helper.canvas ?: return
        val paint = Paint().apply { isAntiAlias = true }

        val hasAlertPrefix = rawNotes.startsWith("[IMPORTANT_ALERT]")
        val cleanNote = if (hasAlertPrefix) rawNotes.removePrefix("[IMPORTANT_ALERT]").trim() else rawNotes

        val bgColor = if (hasAlertPrefix) "#FEF2F2" else "#F8FAFC"
        val borderColors = if (hasAlertPrefix) "#EF4444" else "#475569"
        val textColorHexVal = if (hasAlertPrefix) "#991B1B" else "#334155"
        val prefixTitle = if (hasAlertPrefix) "⚠️ تنبيه معملي وتحذير تطبيق هام جداً:\n" else "توصيات وملاحظات فنية شاملة:\n"

        val wrapWidth = (helper.pageWidth - helper.margin * 2 - 24f).toInt()
        val bodyText = prefixTitle + cleanNote
        val textHeightVal = drawParagraph(
            cv = Canvas(),
            text = bodyText,
            x = 0f, y = 0f,
            width = wrapWidth,
            textSize = 8.5f
        )

        val blockH = textHeightVal + 18f
        helper.ensureSpace(blockH)

        val activeY = helper.currentY
        paint.color = android.graphics.Color.parseColor(bgColor)
        paint.style = Paint.Style.FILL
        cv.drawRoundRect(helper.margin, activeY, helper.pageWidth - helper.margin, activeY + blockH, 4f, 4f, paint)

        paint.color = android.graphics.Color.parseColor(borderColors)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        cv.drawRoundRect(helper.margin, activeY, helper.pageWidth - helper.margin, activeY + blockH, 4f, 4f, paint)

        drawParagraph(
            cv = cv,
            text = bodyText,
            x = helper.margin + 12f,
            y = activeY + 9f,
            width = wrapWidth,
            textSize = 8.5f,
            textColorHex = textColorHexVal,
            isBold = hasAlertPrefix,
            align = "right"
        )

        helper.currentY = activeY + blockH + 10f
    }

    /**
     * Launches printer via Android Print Spooler framework
     */
    /**
     * Open and share the generated PDF directly without PrintManager Dialog
     */
    private fun openPdfFileDirectly(context: Context, pdfFile: File, jobName: String) {
        try {
            val authority = "${context.packageName}.provider"
            val uri = FileProvider.getUriForFile(context, authority, pdfFile)
            
            // 1. Intent to view the PDF directly via any default viewer
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // 2. Intent to share the PDF if they prefer sharing/saving to Drive
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, jobName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // 3. Create a beautiful system chooser with a preview option and a sharing option
            val chooserIntent = Intent.createChooser(viewIntent, "مستند PDF الفني والتشغيلي المصدّر 📄").apply {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(shareIntent))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(chooserIntent)
            Toast.makeText(context, "تم تصدير وثيقة PDF بنجاح 📄", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to sending/sharing the file directly
            try {
                val authority = "${context.packageName}.provider"
                val uri = FileProvider.getUriForFile(context, authority, pdfFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(shareIntent, "مشاركة وثيقة PDF المصدّرة 📄"))
            } catch (ex: Exception) {
                Toast.makeText(context, "فشل فتح ومشاركة مستند PDF: ${e.localizedMessage} ❌", Toast.LENGTH_LONG).show()
            }
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

    /**
     * Standard Custom adapter class to feed the PDF onto the Spooler dialog
     */
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
            } catch (e: IOException) {
                callback?.onWriteFailed(e.toString())
            } finally {
                try {
                    inputStream?.close()
                    outputStream?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
}
