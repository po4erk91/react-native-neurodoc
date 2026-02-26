package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object TemplateProcessor {

    fun generate(
        templateJson: String,
        dataJson: String,
        fileName: String?,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val template = JSONObject(templateJson)
            val data = JSONObject(dataJson)

            val pageWidth = template.optJSONObject("pageSize")?.optDouble("width", 595.0)?.toFloat() ?: 595f
            val pageHeight = template.optJSONObject("pageSize")?.optDouble("height", 842.0)?.toFloat() ?: 842f

            val marginsObj = template.optJSONObject("margins")
            val marginTop = marginsObj?.optDouble("top", 40.0)?.toFloat() ?: 40f
            val marginRight = marginsObj?.optDouble("right", 40.0)?.toFloat() ?: 40f
            val marginBottom = marginsObj?.optDouble("bottom", 40.0)?.toFloat() ?: 40f
            val marginLeft = marginsObj?.optDouble("left", 40.0)?.toFloat() ?: 40f

            val defaultFontObj = template.optJSONObject("defaultFont")
            val bgColor = template.optString("backgroundColor", "")

            val contentLeft = marginLeft
            val contentWidth = pageWidth - marginLeft - marginRight

            val headerElements = template.optJSONArray("header")
            val footerElements = template.optJSONArray("footer")
            val bodyElements = template.optJSONArray("body") ?: JSONArray()

            val headerHeight = measureElements(headerElements, contentWidth, defaultFontObj, data)
            val footerHeight = measureElements(footerElements, contentWidth, defaultFontObj, data)

            val contentTop = marginTop + headerHeight
            val contentBottom = pageHeight - marginBottom - footerHeight

            val doc = PDDocument()

            val ctx = RenderContext(
                doc = doc,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                contentLeft = contentLeft,
                contentWidth = contentWidth,
                contentTop = contentTop,
                contentBottom = contentBottom,
                marginTop = marginTop,
                marginBottom = marginBottom,
                defaultFontObj = defaultFontObj,
                data = data,
                bgColor = bgColor,
                headerElements = headerElements,
                footerElements = footerElements,
                headerHeight = headerHeight,
                footerHeight = footerHeight,
                tempDir = tempDir
            )

            startNewPage(ctx)

            for (i in 0 until bodyElements.length()) {
                renderElement(bodyElements.getJSONObject(i), ctx)
            }

            // Render footer on last page
            renderFooter(ctx)
            ctx.contentStream?.close()

            val name = if (fileName.isNullOrEmpty()) UUID.randomUUID().toString() else fileName
            val outputFile = File(tempDir, "$name.pdf")
            doc.save(outputFile)
            doc.close()

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
                putInt("pageCount", ctx.pageCount)
                putDouble("fileSize", outputFile.length().toDouble())
            })

        } catch (e: Exception) {
            promise.reject("TEMPLATE_FAILED", "Template generation failed: ${e.message}", e)
        }
    }

    // MARK: - Render Context

    class RenderContext(
        val doc: PDDocument,
        val pageWidth: Float,
        val pageHeight: Float,
        val contentLeft: Float,
        val contentWidth: Float,
        val contentTop: Float,
        val contentBottom: Float,
        val marginTop: Float,
        val marginBottom: Float,
        val defaultFontObj: JSONObject?,
        val data: JSONObject,
        val bgColor: String,
        val headerElements: JSONArray?,
        val footerElements: JSONArray?,
        val headerHeight: Float,
        val footerHeight: Float,
        val tempDir: File
    ) {
        var cursorY: Float = contentTop // logical Y from top
        var pageCount: Int = 0
        var contentStream: PDPageContentStream? = null
        var currentPage: PDPage? = null
    }

    // MARK: - Coordinate helper
    // PDFBox uses bottom-left origin. We use top-left logical coordinates.

    private fun toPdfY(ctx: RenderContext, logicalY: Float, elementHeight: Float): Float {
        return ctx.pageHeight - logicalY - elementHeight
    }

    // MARK: - Page Management

    private fun startNewPage(ctx: RenderContext) {
        ctx.contentStream?.close()

        val pageRect = PDRectangle(ctx.pageWidth, ctx.pageHeight)
        val page = PDPage(pageRect)
        ctx.doc.addPage(page)
        ctx.currentPage = page
        ctx.pageCount++
        ctx.cursorY = ctx.contentTop

        val cs = PDPageContentStream(ctx.doc, page)
        ctx.contentStream = cs

        // Background
        if (ctx.bgColor.isNotEmpty() && ctx.bgColor != "null") {
            val rgb = parseColor(ctx.bgColor)
            cs.setNonStrokingColor(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
            cs.addRect(0f, 0f, ctx.pageWidth, ctx.pageHeight)
            cs.fill()
        }

        // Header
        var headerY = ctx.marginTop
        val headerElements = ctx.headerElements
        if (headerElements != null) {
            for (i in 0 until headerElements.length()) {
                val el = headerElements.getJSONObject(i)
                val h = measureElement(el, ctx.contentWidth, ctx.defaultFontObj, ctx.data)
                drawElement(el, ctx.contentLeft, headerY, ctx.contentWidth, ctx.defaultFontObj, ctx.data, cs, ctx)
                headerY += h
            }
        }
    }

    private fun renderFooter(ctx: RenderContext) {
        val cs = ctx.contentStream ?: return
        val footerY = ctx.pageHeight - ctx.marginBottom - ctx.footerHeight
        var y = footerY
        val footerElements = ctx.footerElements
        if (footerElements != null) {
            for (i in 0 until footerElements.length()) {
                val el = footerElements.getJSONObject(i)
                val h = measureElement(el, ctx.contentWidth, ctx.defaultFontObj, ctx.data)
                drawElement(el, ctx.contentLeft, y, ctx.contentWidth, ctx.defaultFontObj, ctx.data, cs, ctx)
                y += h
            }
        }
    }

    private fun ensureSpace(needed: Float, ctx: RenderContext) {
        if (ctx.cursorY + needed > ctx.contentBottom) {
            renderFooter(ctx)
            startNewPage(ctx)
        }
    }

    // MARK: - Element Rendering

    private fun renderElement(el: JSONObject, ctx: RenderContext) {
        when (el.getString("type")) {
            "text" -> renderText(el, ctx)
            "image" -> renderImage(el, ctx)
            "line" -> renderLine(el, ctx)
            "spacer" -> renderSpacer(el, ctx)
            "rect" -> renderRect(el, ctx)
            "columns" -> renderColumns(el, ctx)
            "table" -> renderTable(el, ctx)
            "keyValue" -> renderKeyValue(el, ctx)
        }
    }

    // MARK: Text

    private fun renderText(el: JSONObject, ctx: RenderContext) {
        val content = resolveString(el.getString("content"), ctx.data)
        if (content.isEmpty()) return

        val fontObj = el.optJSONObject("font")
        val font = resolveFont(fontObj, ctx.defaultFontObj)
        val fontSize = resolveFontSize(fontObj, ctx.defaultFontObj)
        val color = resolveFontColor(fontObj, ctx.defaultFontObj)
        val alignment = el.optString("alignment", "left")
        val maxW = el.optDouble("maxWidth", ctx.contentWidth.toDouble()).toFloat()
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        val lines = wrapText(content, font, fontSize, maxW)
        val lineHeight = fontSize * 1.2f
        val textHeight = lines.size * lineHeight

        ensureSpace(textHeight + marginBot, ctx)

        val cs = ctx.contentStream ?: return
        cs.setNonStrokingColor(color[0].toInt(), color[1].toInt(), color[2].toInt())

        for ((lineIdx, line) in lines.withIndex()) {
            val textWidth = font.getStringWidth(line) / 1000 * fontSize
            val x = when (alignment) {
                "center" -> ctx.contentLeft + (ctx.contentWidth - textWidth) / 2
                "right" -> ctx.contentLeft + ctx.contentWidth - textWidth
                else -> ctx.contentLeft
            }
            val logicalY = ctx.cursorY + lineIdx * lineHeight
            val pdfY = toPdfY(ctx, logicalY, fontSize)

            cs.beginText()
            cs.setFont(font, fontSize)
            cs.newLineAtOffset(x, pdfY)
            cs.showText(line)
            cs.endText()
        }

        ctx.cursorY += textHeight + marginBot
    }

    // MARK: Image

    private fun renderImage(el: JSONObject, ctx: RenderContext) {
        val src = resolveString(el.getString("src"), ctx.data)
        if (src.isEmpty()) return

        val imgWidth = el.getDouble("width").toFloat()
        val imgHeight = el.getDouble("height").toFloat()
        val alignment = el.optString("alignment", "left")
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        ensureSpace(imgHeight + marginBot, ctx)

        val imgFile = resolveFile(src)
        if (!imgFile.exists()) return

        try {
            val pdImage = PDImageXObject.createFromFileByContent(imgFile, ctx.doc)
            val cs = ctx.contentStream ?: return

            val x = when (alignment) {
                "center" -> ctx.contentLeft + (ctx.contentWidth - imgWidth) / 2
                "right" -> ctx.contentLeft + ctx.contentWidth - imgWidth
                else -> ctx.contentLeft
            }
            val pdfY = toPdfY(ctx, ctx.cursorY, imgHeight)
            cs.drawImage(pdImage, x, pdfY, imgWidth, imgHeight)
        } catch (_: Exception) {}

        ctx.cursorY += imgHeight + marginBot
    }

    // MARK: Line

    private fun renderLine(el: JSONObject, ctx: RenderContext) {
        val thickness = el.optDouble("thickness", 1.0).toFloat()
        val color = parseColor(el.optString("color", "#CCCCCC"))
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        ensureSpace(thickness + marginBot, ctx)

        val cs = ctx.contentStream ?: return
        cs.setStrokingColor(color[0].toInt(), color[1].toInt(), color[2].toInt())
        cs.setLineWidth(thickness)
        val pdfY = toPdfY(ctx, ctx.cursorY, thickness / 2)
        cs.moveTo(ctx.contentLeft, pdfY)
        cs.lineTo(ctx.contentLeft + ctx.contentWidth, pdfY)
        cs.stroke()

        ctx.cursorY += thickness + marginBot
    }

    // MARK: Spacer

    private fun renderSpacer(el: JSONObject, ctx: RenderContext) {
        val height = el.getDouble("height").toFloat()
        ensureSpace(height, ctx)
        ctx.cursorY += height
    }

    // MARK: Rect

    private fun renderRect(el: JSONObject, ctx: RenderContext) {
        val w = el.getDouble("width").toFloat()
        val h = el.getDouble("height").toFloat()
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        ensureSpace(h + marginBot, ctx)

        val cs = ctx.contentStream ?: return
        val pdfY = toPdfY(ctx, ctx.cursorY, h)

        val fillColor = el.optString("fillColor", "")
        if (fillColor.isNotEmpty() && fillColor != "null") {
            val rgb = parseColor(fillColor)
            cs.setNonStrokingColor(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
            cs.addRect(ctx.contentLeft, pdfY, w, h)
            cs.fill()
        }

        val borderColor = el.optString("borderColor", "")
        if (borderColor.isNotEmpty() && borderColor != "null") {
            val rgb = parseColor(borderColor)
            cs.setStrokingColor(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
            cs.setLineWidth(el.optDouble("borderWidth", 1.0).toFloat())
            cs.addRect(ctx.contentLeft, pdfY, w, h)
            cs.stroke()
        }

        ctx.cursorY += h + marginBot
    }

    // MARK: Columns

    private fun renderColumns(el: JSONObject, ctx: RenderContext) {
        val columnsArr = el.getJSONArray("columns")
        val gap = el.optDouble("gap", 10.0).toFloat()
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        var totalWeight = 0f
        for (i in 0 until columnsArr.length()) {
            totalWeight += columnsArr.getJSONObject(i).getDouble("width").toFloat()
        }

        val totalGaps = gap * maxOf(0, columnsArr.length() - 1)
        val availableWidth = ctx.contentWidth - totalGaps

        // Measure max height
        var maxHeight = 0f
        val columnWidths = mutableListOf<Float>()
        for (i in 0 until columnsArr.length()) {
            val col = columnsArr.getJSONObject(i)
            val colWidth = availableWidth * (col.getDouble("width").toFloat() / totalWeight)
            columnWidths.add(colWidth)
            val elements = col.optJSONArray("elements")
            val h = measureElements(elements, colWidth, ctx.defaultFontObj, ctx.data)
            maxHeight = maxOf(maxHeight, h)
        }

        ensureSpace(maxHeight + marginBot, ctx)

        val cs = ctx.contentStream ?: return
        var colX = ctx.contentLeft

        for (i in 0 until columnsArr.length()) {
            val col = columnsArr.getJSONObject(i)
            val elements = col.optJSONArray("elements") ?: continue
            var colY = ctx.cursorY

            for (j in 0 until elements.length()) {
                val childEl = elements.getJSONObject(j)
                val h = measureElement(childEl, columnWidths[i], ctx.defaultFontObj, ctx.data)
                drawElement(childEl, colX, colY, columnWidths[i], ctx.defaultFontObj, ctx.data, cs, ctx)
                colY += h
            }
            colX += columnWidths[i] + gap
        }

        ctx.cursorY += maxHeight + marginBot
    }

    // MARK: Table

    private fun renderTable(el: JSONObject, ctx: RenderContext) {
        val columnsArr = el.getJSONArray("columns")
        val dataKey = el.getString("dataKey")
        val rows = resolveArray(dataKey, ctx.data)

        var totalWeight = 0f
        for (i in 0 until columnsArr.length()) {
            totalWeight += columnsArr.getJSONObject(i).getDouble("width").toFloat()
        }
        val columnWidths = (0 until columnsArr.length()).map {
            ctx.contentWidth * (columnsArr.getJSONObject(it).getDouble("width").toFloat() / totalWeight)
        }

        val headerFontObj = el.optJSONObject("headerFont")
        val bodyFontObj = el.optJSONObject("bodyFont")
        val headerFont = resolveFont(headerFontObj, JSONObject().apply { put("bold", true) }.let { merged ->
            ctx.defaultFontObj?.keys()?.forEach { key -> merged.put(key, ctx.defaultFontObj.get(key)) }
            merged.put("bold", true)
            merged
        })
        val headerFontSize = resolveFontSize(headerFontObj, ctx.defaultFontObj)
        val bodyFont = resolveFont(bodyFontObj, ctx.defaultFontObj)
        val bodyFontSize = resolveFontSize(bodyFontObj, ctx.defaultFontObj)

        val rowPadding = 6f
        val defaultRowH = el.optDouble("rowHeight", (bodyFontSize * 1.2f + rowPadding * 2).toDouble()).toFloat()
        val headerRowH = headerFontSize * 1.2f + rowPadding * 2
        val showGrid = el.optBoolean("showGridLines", true)
        val gridColor = parseColor(el.optString("gridLineColor", "#CCCCCC"))
        val stripeColorStr = el.optString("stripeColor", "")
        val headerFontColor = resolveFontColor(headerFontObj, ctx.defaultFontObj)
        val bodyFontColor = resolveFontColor(bodyFontObj, ctx.defaultFontObj)
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        fun drawHeaderRow(cursorY: Float) {
            val cs = ctx.contentStream ?: return
            val pdfY = toPdfY(ctx, cursorY, headerRowH)

            // Header background
            cs.setNonStrokingColor(240, 240, 240)
            cs.addRect(ctx.contentLeft, pdfY, ctx.contentWidth, headerRowH)
            cs.fill()

            var colX = ctx.contentLeft
            for (i in 0 until columnsArr.length()) {
                val col = columnsArr.getJSONObject(i)
                val text = col.getString("header")
                val alignment = col.optString("alignment", "left")

                val textWidth = headerFont.getStringWidth(text) / 1000 * headerFontSize
                val x = when (alignment) {
                    "center" -> colX + (columnWidths[i] - textWidth) / 2
                    "right" -> colX + columnWidths[i] - textWidth - 4
                    else -> colX + 4
                }

                cs.setNonStrokingColor(headerFontColor[0].toInt(), headerFontColor[1].toInt(), headerFontColor[2].toInt())
                cs.beginText()
                cs.setFont(headerFont, headerFontSize)
                cs.newLineAtOffset(x, pdfY + rowPadding)
                cs.showText(text)
                cs.endText()

                colX += columnWidths[i]
            }

            if (showGrid) {
                cs.setStrokingColor(gridColor[0].toInt(), gridColor[1].toInt(), gridColor[2].toInt())
                cs.setLineWidth(0.5f)
                cs.moveTo(ctx.contentLeft, pdfY)
                cs.lineTo(ctx.contentLeft + ctx.contentWidth, pdfY)
                cs.stroke()
            }
        }

        ensureSpace(headerRowH + defaultRowH, ctx)
        drawHeaderRow(ctx.cursorY)
        ctx.cursorY += headerRowH

        // Render data rows
        for (rowIdx in 0 until rows.length()) {
            val rowObj = rows.optJSONObject(rowIdx) ?: continue
            val rowHeight = defaultRowH

            if (ctx.cursorY + rowHeight > ctx.contentBottom) {
                renderFooter(ctx)
                startNewPage(ctx)
                drawHeaderRow(ctx.cursorY)
                ctx.cursorY += headerRowH
            }

            val cs = ctx.contentStream ?: continue
            val pdfY = toPdfY(ctx, ctx.cursorY, rowHeight)

            // Stripe
            if (stripeColorStr.isNotEmpty() && stripeColorStr != "null" && rowIdx % 2 == 1) {
                val stripe = parseColor(stripeColorStr)
                cs.setNonStrokingColor(stripe[0].toInt(), stripe[1].toInt(), stripe[2].toInt())
                cs.addRect(ctx.contentLeft, pdfY, ctx.contentWidth, rowHeight)
                cs.fill()
            }

            // Cell text
            var colX = ctx.contentLeft
            for (i in 0 until columnsArr.length()) {
                val col = columnsArr.getJSONObject(i)
                val key = col.getString("key")
                val value = resolveString(stringValue(rowObj.opt(key)), ctx.data)
                val alignment = col.optString("alignment", "left")

                val textWidth = bodyFont.getStringWidth(value) / 1000 * bodyFontSize
                val x = when (alignment) {
                    "center" -> colX + (columnWidths[i] - textWidth) / 2
                    "right" -> colX + columnWidths[i] - textWidth - 4
                    else -> colX + 4
                }

                cs.setNonStrokingColor(bodyFontColor[0].toInt(), bodyFontColor[1].toInt(), bodyFontColor[2].toInt())
                cs.beginText()
                cs.setFont(bodyFont, bodyFontSize)
                cs.newLineAtOffset(x, pdfY + rowPadding)
                cs.showText(value)
                cs.endText()

                colX += columnWidths[i]
            }

            // Grid line
            if (showGrid) {
                cs.setStrokingColor(gridColor[0].toInt(), gridColor[1].toInt(), gridColor[2].toInt())
                cs.setLineWidth(0.5f)
                cs.moveTo(ctx.contentLeft, pdfY)
                cs.lineTo(ctx.contentLeft + ctx.contentWidth, pdfY)
                cs.stroke()
            }

            ctx.cursorY += rowHeight
        }

        ctx.cursorY += marginBot
    }

    // MARK: KeyValue

    private fun renderKeyValue(el: JSONObject, ctx: RenderContext) {
        val entries = el.getJSONArray("entries")
        val labelFontObj = el.optJSONObject("labelFont")
        val valueFontObj = el.optJSONObject("valueFont")

        val labelFont = resolveFont(labelFontObj, JSONObject().apply {
            ctx.defaultFontObj?.keys()?.forEach { key -> put(key, ctx.defaultFontObj.get(key)) }
            put("bold", true)
        })
        val labelFontSize = resolveFontSize(labelFontObj, ctx.defaultFontObj)
        val labelColor = resolveFontColor(labelFontObj, ctx.defaultFontObj)

        val valueFont = resolveFont(valueFontObj, ctx.defaultFontObj)
        val valueFontSize = resolveFontSize(valueFontObj, ctx.defaultFontObj)
        val valueColor = resolveFontColor(valueFontObj, ctx.defaultFontObj)

        val gap = el.optDouble("gap", 8.0).toFloat()
        val lineSpacing = 4f
        val marginBot = el.optDouble("marginBottom", 0.0).toFloat()

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val label = resolveString(entry.getString("label"), ctx.data)
            val value = resolveString(entry.getString("value"), ctx.data)

            val lineHeight = maxOf(labelFontSize, valueFontSize) * 1.2f

            ensureSpace(lineHeight + lineSpacing, ctx)

            val cs = ctx.contentStream ?: continue
            val pdfY = toPdfY(ctx, ctx.cursorY, labelFontSize)

            // Label
            cs.setNonStrokingColor(labelColor[0].toInt(), labelColor[1].toInt(), labelColor[2].toInt())
            cs.beginText()
            cs.setFont(labelFont, labelFontSize)
            cs.newLineAtOffset(ctx.contentLeft, pdfY)
            cs.showText(label)
            cs.endText()

            // Value
            val labelWidth = labelFont.getStringWidth(label) / 1000 * labelFontSize
            cs.setNonStrokingColor(valueColor[0].toInt(), valueColor[1].toInt(), valueColor[2].toInt())
            cs.beginText()
            cs.setFont(valueFont, valueFontSize)
            cs.newLineAtOffset(ctx.contentLeft + labelWidth + gap, pdfY)
            cs.showText(value)
            cs.endText()

            ctx.cursorY += lineHeight + lineSpacing
        }

        ctx.cursorY += marginBot
    }

    // MARK: - Draw Element at absolute position (for header/footer/columns)

    private fun drawElement(
        el: JSONObject,
        x: Float,
        y: Float,
        width: Float,
        defaultFontObj: JSONObject?,
        data: JSONObject,
        cs: PDPageContentStream,
        ctx: RenderContext
    ) {
        when (el.getString("type")) {
            "text" -> {
                val content = resolveString(el.getString("content"), data)
                if (content.isEmpty()) return

                val fontObj = el.optJSONObject("font")
                val font = resolveFont(fontObj, defaultFontObj)
                val fontSize = resolveFontSize(fontObj, defaultFontObj)
                val color = resolveFontColor(fontObj, defaultFontObj)
                val alignment = el.optString("alignment", "left")
                val maxW = el.optDouble("maxWidth", width.toDouble()).toFloat()

                val lines = wrapText(content, font, fontSize, maxW)
                val lineHeight = fontSize * 1.2f

                cs.setNonStrokingColor(color[0].toInt(), color[1].toInt(), color[2].toInt())

                for ((lineIdx, line) in lines.withIndex()) {
                    val textWidth = font.getStringWidth(line) / 1000 * fontSize
                    val drawX = when (alignment) {
                        "center" -> x + (width - textWidth) / 2
                        "right" -> x + width - textWidth
                        else -> x
                    }
                    val logicalY = y + lineIdx * lineHeight
                    val pdfY = toPdfY(ctx, logicalY, fontSize)

                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.newLineAtOffset(drawX, pdfY)
                    cs.showText(line)
                    cs.endText()
                }
            }
            "image" -> {
                val src = resolveString(el.getString("src"), data)
                if (src.isEmpty()) return
                val imgWidth = el.getDouble("width").toFloat()
                val imgHeight = el.getDouble("height").toFloat()
                val alignment = el.optString("alignment", "left")

                val imgFile = resolveFile(src)
                if (!imgFile.exists()) return

                try {
                    val pdImage = PDImageXObject.createFromFileByContent(imgFile, ctx.doc)
                    val drawX = when (alignment) {
                        "center" -> x + (width - imgWidth) / 2
                        "right" -> x + width - imgWidth
                        else -> x
                    }
                    val pdfY = toPdfY(ctx, y, imgHeight)
                    cs.drawImage(pdImage, drawX, pdfY, imgWidth, imgHeight)
                } catch (_: Exception) {}
            }
            "line" -> {
                val thickness = el.optDouble("thickness", 1.0).toFloat()
                val color = parseColor(el.optString("color", "#CCCCCC"))
                cs.setStrokingColor(color[0].toInt(), color[1].toInt(), color[2].toInt())
                cs.setLineWidth(thickness)
                val pdfY = toPdfY(ctx, y, thickness / 2)
                cs.moveTo(x, pdfY)
                cs.lineTo(x + width, pdfY)
                cs.stroke()
            }
            "spacer" -> { /* nothing to draw */ }
            "rect" -> {
                val w = el.getDouble("width").toFloat()
                val h = el.getDouble("height").toFloat()
                val pdfY = toPdfY(ctx, y, h)

                val fillColor = el.optString("fillColor", "")
                if (fillColor.isNotEmpty() && fillColor != "null") {
                    val rgb = parseColor(fillColor)
                    cs.setNonStrokingColor(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
                    cs.addRect(x, pdfY, w, h)
                    cs.fill()
                }

                val borderColor = el.optString("borderColor", "")
                if (borderColor.isNotEmpty() && borderColor != "null") {
                    val rgb = parseColor(borderColor)
                    cs.setStrokingColor(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
                    cs.setLineWidth(el.optDouble("borderWidth", 1.0).toFloat())
                    cs.addRect(x, pdfY, w, h)
                    cs.stroke()
                }
            }
        }
    }

    // MARK: - Measurement

    private fun measureElements(elements: JSONArray?, contentWidth: Float, defaultFontObj: JSONObject?, data: JSONObject): Float {
        if (elements == null) return 0f
        var total = 0f
        for (i in 0 until elements.length()) {
            total += measureElement(elements.getJSONObject(i), contentWidth, defaultFontObj, data)
        }
        return total
    }

    private fun measureElement(el: JSONObject, contentWidth: Float, defaultFontObj: JSONObject?, data: JSONObject): Float {
        return when (el.getString("type")) {
            "text" -> {
                val content = resolveString(el.getString("content"), data)
                if (content.isEmpty()) return 0f
                val fontObj = el.optJSONObject("font")
                val font = resolveFont(fontObj, defaultFontObj)
                val fontSize = resolveFontSize(fontObj, defaultFontObj)
                val maxW = el.optDouble("maxWidth", contentWidth.toDouble()).toFloat()
                val lines = wrapText(content, font, fontSize, maxW)
                val lineHeight = fontSize * 1.2f
                lines.size * lineHeight + el.optDouble("marginBottom", 0.0).toFloat()
            }
            "image" -> el.getDouble("height").toFloat() + el.optDouble("marginBottom", 0.0).toFloat()
            "line" -> el.optDouble("thickness", 1.0).toFloat() + el.optDouble("marginBottom", 0.0).toFloat()
            "spacer" -> el.getDouble("height").toFloat()
            "rect" -> el.getDouble("height").toFloat() + el.optDouble("marginBottom", 0.0).toFloat()
            "columns" -> {
                val columnsArr = el.getJSONArray("columns")
                val gap = el.optDouble("gap", 10.0).toFloat()
                var totalWeight = 0f
                for (i in 0 until columnsArr.length()) {
                    totalWeight += columnsArr.getJSONObject(i).getDouble("width").toFloat()
                }
                val totalGaps = gap * maxOf(0, columnsArr.length() - 1)
                val availableWidth = contentWidth - totalGaps
                var maxH = 0f
                for (i in 0 until columnsArr.length()) {
                    val col = columnsArr.getJSONObject(i)
                    val colWidth = availableWidth * (col.getDouble("width").toFloat() / totalWeight)
                    val h = measureElements(col.optJSONArray("elements"), colWidth, defaultFontObj, data)
                    maxH = maxOf(maxH, h)
                }
                maxH + el.optDouble("marginBottom", 0.0).toFloat()
            }
            "table" -> {
                val rows = resolveArray(el.getString("dataKey"), data)
                val bodyFontObj = el.optJSONObject("bodyFont")
                val bodyFontSize = resolveFontSize(bodyFontObj, defaultFontObj)
                val headerFontSize = resolveFontSize(el.optJSONObject("headerFont"), defaultFontObj)
                val rowPadding = 6f
                val defaultRowH = el.optDouble("rowHeight", (bodyFontSize * 1.2f + rowPadding * 2).toDouble()).toFloat()
                val headerH = headerFontSize * 1.2f + rowPadding * 2
                headerH + defaultRowH * rows.length() + el.optDouble("marginBottom", 0.0).toFloat()
            }
            "keyValue" -> {
                val entries = el.getJSONArray("entries")
                val labelFontSize = resolveFontSize(el.optJSONObject("labelFont"), defaultFontObj)
                val lineHeight = labelFontSize * 1.2f + 4
                lineHeight * entries.length() + el.optDouble("marginBottom", 0.0).toFloat()
            }
            else -> 0f
        }
    }

    // MARK: - Helpers

    private fun resolveString(template: String, data: JSONObject): String {
        val pattern = Regex("\\{\\{([^}]+)\\}\\}")
        return pattern.replace(template) { matchResult ->
            val keyPath = matchResult.groupValues[1]
            resolveKeyPath(keyPath, data)
        }
    }

    private fun resolveKeyPath(keyPath: String, data: JSONObject): String {
        val parts = keyPath.split(".")
        var current: Any = data
        for (part in parts) {
            current = when (current) {
                is JSONObject -> current.opt(part) ?: return ""
                else -> return ""
            }
        }
        return stringValue(current)
    }

    private fun resolveArray(key: String, data: JSONObject): JSONArray {
        val parts = key.split(".")
        var current: Any = data
        for (part in parts) {
            current = when (current) {
                is JSONObject -> current.opt(part) ?: return JSONArray()
                else -> return JSONArray()
            }
        }
        return current as? JSONArray ?: JSONArray()
    }

    private fun stringValue(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return ""
        return value.toString()
    }

    private fun resolveFont(fontObj: JSONObject?, defaultFontObj: JSONObject?): PDType1Font {
        val family = fontObj?.optString("family", "") ?: ""
        val resolvedFamily = if (family.isNotEmpty()) family else (defaultFontObj?.optString("family", "Helvetica") ?: "Helvetica")
        val bold = fontObj?.optBoolean("bold", false) ?: (defaultFontObj?.optBoolean("bold", false) ?: false)
        val italic = fontObj?.optBoolean("italic", false) ?: (defaultFontObj?.optBoolean("italic", false) ?: false)

        return when (resolvedFamily) {
            "Courier" -> when {
                bold && italic -> PDType1Font.COURIER_BOLD_OBLIQUE
                bold -> PDType1Font.COURIER_BOLD
                italic -> PDType1Font.COURIER_OBLIQUE
                else -> PDType1Font.COURIER
            }
            "Times" -> when {
                bold && italic -> PDType1Font.TIMES_BOLD_ITALIC
                bold -> PDType1Font.TIMES_BOLD
                italic -> PDType1Font.TIMES_ITALIC
                else -> PDType1Font.TIMES_ROMAN
            }
            else -> when { // Helvetica default
                bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                bold -> PDType1Font.HELVETICA_BOLD
                italic -> PDType1Font.HELVETICA_OBLIQUE
                else -> PDType1Font.HELVETICA
            }
        }
    }

    private fun resolveFontSize(fontObj: JSONObject?, defaultFontObj: JSONObject?): Float {
        val size = fontObj?.optDouble("size", 0.0)?.toFloat() ?: 0f
        if (size > 0) return size
        val defaultSize = defaultFontObj?.optDouble("size", 12.0)?.toFloat() ?: 12f
        return defaultSize
    }

    private fun resolveFontColor(fontObj: JSONObject?, defaultFontObj: JSONObject?): FloatArray {
        val color = fontObj?.optString("color", "") ?: ""
        if (color.isNotEmpty() && color != "null") return parseColor(color)
        val defaultColor = defaultFontObj?.optString("color", "") ?: ""
        if (defaultColor.isNotEmpty() && defaultColor != "null") return parseColor(defaultColor)
        return floatArrayOf(0f, 0f, 0f)
    }

    private fun wrapText(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        if (text.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()

        // Split by newlines first
        val paragraphs = text.split("\n")

        for (paragraph in paragraphs) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }

            val words = paragraph.split(" ")
            var currentLine = StringBuilder()

            for (word in words) {
                val testLine = if (currentLine.isEmpty()) word else "${currentLine} $word"
                val testWidth = try {
                    font.getStringWidth(testLine) / 1000 * fontSize
                } catch (_: Exception) {
                    // Fallback for unsupported characters
                    testLine.length * fontSize * 0.5f
                }

                if (testWidth > maxWidth && currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    currentLine = StringBuilder(testLine)
                }
            }

            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
        }

        return lines
    }

    private fun parseColor(hex: String): FloatArray {
        val cleanHex = hex.removePrefix("#")
        if (cleanHex.length < 6) return floatArrayOf(0f, 0f, 0f)
        return try {
            val colorInt = cleanHex.toLong(16)
            floatArrayOf(
                ((colorInt shr 16) and 0xFF).toFloat(),
                ((colorInt shr 8) and 0xFF).toFloat(),
                (colorInt and 0xFF).toFloat()
            )
        } catch (_: Exception) {
            floatArrayOf(0f, 0f, 0f)
        }
    }

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) {
            urlString.removePrefix("file://")
        } else {
            urlString
        }
        return File(path)
    }
}
