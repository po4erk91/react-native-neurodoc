package com.neurodoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.apache.poi.xwpf.usermodel.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

object DocxConverter {

    private val ocrClient by lazy {
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    private data class TextSegment(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val fontSize: Float,
        val fontFamily: String
    )

    private data class PageContent(
        val blocks: List<TextExtractor.TextBlock>,
        val pageWidth: Float,
        val pageHeight: Float,
        val image: ByteArray?,
        val imageIndex: Int?
    )

    // MARK: - DOCX -> PDF

    fun convertDocxToPdf(
        inputPath: String,
        preserveImages: Boolean,
        pageSize: String,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val file = resolveFile(inputPath)

            val pageDims = getPageDimensions(pageSize)
            val pageWidth = pageDims.first
            val pageHeight = pageDims.second
            val margin = 56f
            val contentLeft = margin
            val contentWidth = pageWidth - margin * 2
            val contentTop = margin
            val contentBottom = pageHeight - margin

            val warnings = mutableListOf<String>()
            var cursorY = contentTop
            var pageCount = 0
            var contentStream: PDPageContentStream? = null
            var currentPage: PDPage? = null
            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")

            FileInputStream(file).use { fis ->
                XWPFDocument(fis).use { docx ->
                    PDDocument().use { doc ->

                        fun startNewPage() {
                            contentStream?.close()
                            val rect = PDRectangle(pageWidth, pageHeight)
                            val page = PDPage(rect)
                            doc.addPage(page)
                            currentPage = page
                            pageCount++
                            cursorY = contentTop
                            contentStream = PDPageContentStream(doc, page)
                        }

                        fun ensureSpace(needed: Float) {
                            if (cursorY + needed > contentBottom) {
                                startNewPage()
                            }
                        }

                        fun toPdfY(logicalY: Float, height: Float): Float {
                            return pageHeight - logicalY - height
                        }

                        startNewPage()

                        for (element in docx.bodyElements) {
                            when (element) {
                                is XWPFParagraph -> {
                                    val para = element
                                    val cs = contentStream ?: continue

                                    val style = para.style ?: ""
                                    val isHeading = style.startsWith("Heading", ignoreCase = true) ||
                                            style.startsWith("heading")
                                    val headingLevel = if (isHeading) {
                                        style.filter { it.isDigit() }.toIntOrNull() ?: 1
                                    } else 0

                                    val isList = para.numID != null
                                    val listIndent = if (isList) {
                                        val level = try { para.numIlvl?.toInt() ?: 0 } catch (_: Exception) { 0 }
                                        (level + 1) * 18f
                                    } else 0f

                                    val segments = mutableListOf<TextSegment>()

                                    if (isList) {
                                        segments.add(TextSegment(
                                            text = "\u2022 ",
                                            bold = false, italic = false,
                                            fontSize = 12f, fontFamily = "Helvetica"
                                        ))
                                    }

                                    for (run in para.runs) {
                                        val text = run.text() ?: continue
                                        if (text.isEmpty()) continue

                                        if (preserveImages && run.embeddedPictures.isNotEmpty()) {
                                            for (pic in run.embeddedPictures) {
                                                try {
                                                    val imgData = pic.pictureData.data
                                                    val pdImage = PDImageXObject.createFromByteArray(doc, imgData, pic.pictureData.fileName)

                                                    val maxImgWidth = contentWidth * 0.9f
                                                    val scale = min(1f, maxImgWidth / pdImage.width.toFloat())
                                                    val imgW = pdImage.width * scale
                                                    val imgH = pdImage.height * scale

                                                    if (segments.isNotEmpty()) {
                                                        val lineHeight = (segments.maxOfOrNull { it.fontSize } ?: 12f) * 1.3f
                                                        ensureSpace(lineHeight)
                                                        drawTextSegments(segments, cs, contentLeft + listIndent,
                                                            toPdfY(cursorY, lineHeight), contentWidth - listIndent)
                                                        cursorY += lineHeight
                                                        segments.clear()
                                                    }

                                                    ensureSpace(imgH)
                                                    cs.drawImage(pdImage, contentLeft, toPdfY(cursorY, imgH), imgW, imgH)
                                                    cursorY += imgH + 4
                                                } catch (_: Exception) {
                                                    // Skip broken images silently
                                                }
                                            }
                                        }

                                        var fontSize = run.fontSizeAsDouble?.toFloat() ?: 12f
                                        if (fontSize <= 0) fontSize = 12f
                                        if (headingLevel > 0 && fontSize == 12f) {
                                            fontSize = headingFontSize(headingLevel)
                                        }

                                        val bold = run.isBold || headingLevel > 0
                                        val italic = run.isItalic

                                        segments.add(TextSegment(
                                            text = text,
                                            bold = bold,
                                            italic = italic,
                                            fontSize = fontSize,
                                            fontFamily = run.fontFamily ?: "Helvetica"
                                        ))
                                    }

                                    if (segments.isNotEmpty()) {
                                        val lineHeight = (segments.maxOfOrNull { it.fontSize } ?: 12f) * 1.3f
                                        val totalText = segments.joinToString("") { it.text }
                                        val avgCharWidth = (segments.firstOrNull()?.fontSize ?: 12f) * 0.5f
                                        val availableWidth = contentWidth - listIndent
                                        val estimatedLines = ceil((totalText.length * avgCharWidth) / availableWidth).toInt().coerceAtLeast(1)
                                        val totalHeight = lineHeight * estimatedLines

                                        ensureSpace(totalHeight)
                                        drawTextSegments(segments, cs, contentLeft + listIndent,
                                            toPdfY(cursorY, lineHeight), availableWidth)
                                        cursorY += totalHeight
                                        cursorY += 4f
                                    } else if (para.runs.isEmpty()) {
                                        cursorY += 14f
                                    }
                                }

                                is XWPFTable -> {
                                    val table = element
                                    val cs = contentStream ?: continue
                                    val numCols = table.rows.firstOrNull()?.tableCells?.size ?: continue
                                    val colWidth = contentWidth / numCols
                                    val cellPadding = 4f

                                    for (row in table.rows) {
                                        var rowHeight = 20f
                                        for (cell in row.tableCells) {
                                            val cellText = cell.text ?: ""
                                            val textHeight = 12f * 1.3f * (cellText.split("\n").size)
                                            rowHeight = maxOf(rowHeight, textHeight + cellPadding * 2)
                                        }

                                        ensureSpace(rowHeight)

                                        for ((colIdx, cell) in row.tableCells.withIndex()) {
                                            val cellX = contentLeft + colIdx * colWidth
                                            val pdfY = toPdfY(cursorY, rowHeight)

                                            cs.setStrokingColor(128/255f, 128/255f, 128/255f)
                                            cs.setLineWidth(0.5f)
                                            cs.addRect(cellX, pdfY, colWidth, rowHeight)
                                            cs.stroke()

                                            val cellText = cell.text ?: ""
                                            if (cellText.isNotEmpty()) {
                                                val font = PDType1Font.HELVETICA
                                                val fontSize = 10f
                                                cs.beginText()
                                                cs.setFont(font, fontSize)
                                                cs.setNonStrokingColor(0f, 0f, 0f)
                                                cs.newLineAtOffset(cellX + cellPadding, pdfY + rowHeight - cellPadding - fontSize)
                                                val maxChars = ((colWidth - cellPadding * 2) / (fontSize * 0.5f)).toInt()
                                                val displayText = if (cellText.length > maxChars)
                                                    cellText.take(maxChars) else cellText
                                                cs.showText(displayText.replace("\n", " "))
                                                cs.endText()
                                            }
                                        }
                                        cursorY += rowHeight
                                    }
                                    cursorY += 8f
                                }

                                else -> { /* Unsupported element type */ }
                            }
                        }

                        contentStream?.close()
                        doc.save(outputFile)
                    }
                }
            }

            val warningsArray = WritableNativeArray().apply {
                warnings.forEach { pushString(it) }
            }

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
                putInt("pageCount", pageCount)
                putDouble("fileSize", outputFile.length().toDouble())
                putArray("warnings", warningsArray)
            })

        } catch (e: Exception) {
            promise.reject("CONVERSION_FAILED", "DOCX to PDF conversion failed: ${e.message}", e)
        }
    }

    // MARK: - PDF -> DOCX

    suspend fun convertPdfToDocx(
        context: Context,
        inputPath: String,
        mode: String,
        language: String,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val file = resolveFile(inputPath)
            val pages = mutableListOf<PageContent>()
            var imageIdx = 0
            var actualMode = if (mode == "ocrFallback") "auto" else mode
            var pageCount = 0

            PDDocument.load(file).use { doc ->
                pageCount = doc.numberOfPages
                if (pageCount == 0) throw IllegalStateException("PDF has no pages")

                for (i in 0 until pageCount) {
                    val page = doc.getPage(i)
                    val mediaBox = page.mediaBox
                    val pageWidth = mediaBox.width
                    val pageHeight = mediaBox.height

                    var blocks: List<TextExtractor.TextBlock>
                    when (mode) {
                        "text", "textAndImages" -> {
                            blocks = extractNativeBlocks(doc, i, pageWidth, pageHeight)
                            if (blocks.isEmpty() && mode != "text") {
                                blocks = extractOcrBlocks(context, inputPath, i, pageWidth, pageHeight)
                                actualMode = "ocr"
                            }
                        }
                        else -> {
                            blocks = extractNativeBlocks(doc, i, pageWidth, pageHeight)
                            if (blocks.isEmpty()) {
                                blocks = extractOcrBlocks(context, inputPath, i, pageWidth, pageHeight)
                                actualMode = "ocr"
                            }
                        }
                    }

                    var pageImage: ByteArray? = null
                    if (mode == "textAndImages") {
                        pageImage = renderPageAsPng(inputPath, i)
                        if (pageImage != null) imageIdx++
                    }

                    pages.add(PageContent(
                        blocks = blocks,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                        image = pageImage,
                        imageIndex = if (pageImage != null) imageIdx - 1 else null
                    ))
                }
            }

            // Build DOCX ZIP
            val baos = ByteArrayOutputStream()
            val zos = ZipOutputStream(baos)

            val images = mutableListOf<Pair<String, ByteArray>>()
            for (page in pages) {
                if (page.image != null && page.imageIndex != null) {
                    images.add("word/media/image${page.imageIndex}.png" to page.image)
                }
            }

            addZipEntry(zos, "[Content_Types].xml", buildContentTypes(images.isNotEmpty()))
            addZipEntry(zos, "_rels/.rels", buildRootRels())
            addZipEntry(zos, "word/_rels/document.xml.rels", buildDocumentRels(images.size))
            addZipEntry(zos, "word/document.xml", buildDocumentXml(pages))

            for ((name, data) in images) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }

            zos.close()
            val docxData = baos.toByteArray()

            val outputFile = File(tempDir, "${UUID.randomUUID()}.docx")
            outputFile.writeBytes(docxData)

            promise.resolve(WritableNativeMap().apply {
                putString("docxUrl", "file://${outputFile.absolutePath}")
                putInt("pageCount", pageCount)
                putDouble("fileSize", outputFile.length().toDouble())
                putString("mode", actualMode)
            })

        } catch (e: Exception) {
            promise.reject("CONVERSION_FAILED", "PDF to DOCX conversion failed: ${e.message}", e)
        }
    }

    // MARK: - Helpers

    private fun drawTextSegments(
        segments: List<TextSegment>,
        cs: PDPageContentStream,
        x: Float,
        y: Float,
        maxWidth: Float
    ) {
        var currentX = x
        cs.beginText()

        for (seg in segments) {
            val font = when {
                seg.bold && seg.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
                seg.bold -> PDType1Font.HELVETICA_BOLD
                seg.italic -> PDType1Font.HELVETICA_OBLIQUE
                else -> PDType1Font.HELVETICA
            }

            cs.setFont(font, seg.fontSize)
            cs.setNonStrokingColor(0f, 0f, 0f)
            cs.newLineAtOffset(currentX, y)

            val safeText = seg.text.map {
                try { font.encode(it.toString()); it } catch (_: Exception) { '?' }
            }.joinToString("")

            cs.showText(safeText)
            currentX = 0f
        }

        cs.endText()
    }

    private fun headingFontSize(level: Int): Float {
        return when (level) {
            1 -> 24f
            2 -> 20f
            3 -> 16f
            4 -> 14f
            5 -> 12f
            6 -> 11f
            else -> 12f
        }
    }

    private fun getPageDimensions(size: String): Pair<Float, Float> {
        return when (size.uppercase()) {
            "LETTER" -> 612f to 792f
            "LEGAL" -> 612f to 1008f
            else -> 595f to 842f // A4
        }
    }

    // MARK: - PDF -> DOCX: Text Extraction

    private fun extractNativeBlocks(
        doc: PDDocument,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float
    ): List<TextExtractor.TextBlock> {
        val stripper = object : PDFTextStripper() {
            val blocks = mutableListOf<TextExtractor.TextBlock>()

            override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                if (text.isNullOrBlank() || textPositions.isNullOrEmpty()) return

                val firstPos = textPositions.first()
                val lastPos = textPositions.last()

                val x = firstPos.x / pageWidth
                val y = firstPos.y / pageHeight
                val width = (lastPos.x + lastPos.width - firstPos.x) / pageWidth
                val height = firstPos.height / pageHeight
                val fontSize = firstPos.fontSize
                val fontName = firstPos.font?.name ?: "Unknown"

                blocks.add(TextExtractor.TextBlock(
                    text = text.trim(),
                    x = x, y = y,
                    width = width, height = height,
                    fontSize = fontSize,
                    fontName = fontName,
                    confidence = 1f
                ))
            }
        }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)
        return stripper.blocks
    }

    private suspend fun extractOcrBlocks(
        context: Context,
        pdfPath: String,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float
    ): List<TextExtractor.TextBlock> {
        val bitmap = renderPageToBitmap(pdfPath, pageIndex) ?: return emptyList()
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return suspendCoroutine { continuation ->
            ocrClient.process(inputImage)
                .addOnSuccessListener { text ->
                    val blocks = mutableListOf<TextExtractor.TextBlock>()
                    for (block in text.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox ?: continue
                            blocks.add(TextExtractor.TextBlock(
                                text = line.text,
                                x = box.left.toFloat() / bitmap.width,
                                y = box.top.toFloat() / bitmap.height,
                                width = box.width().toFloat() / bitmap.width,
                                height = box.height().toFloat() / bitmap.height,
                                fontSize = (box.height().toFloat() / bitmap.height) * pageHeight * 0.85f,
                                fontName = "Unknown",
                                confidence = line.confidence ?: 0.9f
                            ))
                        }
                    }
                    bitmap.recycle()
                    continuation.resume(blocks)
                }
                .addOnFailureListener {
                    bitmap.recycle()
                    continuation.resume(emptyList())
                }
        }
    }

    private fun renderPageToBitmap(pdfPath: String, pageIndex: Int): Bitmap? {
        return try {
            val file = resolveFile(pdfPath)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(pageIndex).use { page ->
                        Bitmap.createBitmap(
                            (page.width * 2f).toInt(),
                            (page.height * 2f).toInt(),
                            Bitmap.Config.ARGB_8888
                        ).also { bmp ->
                            Canvas(bmp).drawColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun renderPageAsPng(pdfPath: String, pageIndex: Int): ByteArray? {
        val bitmap = renderPageToBitmap(pdfPath, pageIndex) ?: return null
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    // MARK: - PDF -> DOCX: XML Builders

    private fun buildContentTypes(hasImages: Boolean): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n")
        sb.append("  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n")
        sb.append("  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n")
        sb.append("  <Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        if (hasImages) {
            sb.append("\n  <Default Extension=\"png\" ContentType=\"image/png\"/>")
            sb.append("\n  <Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        }
        sb.append("\n</Types>")
        return sb.toString()
    }

    private fun buildRootRels(): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
            "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>\n" +
            "</Relationships>"
    }

    private fun buildDocumentRels(imageCount: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (i in 0 until imageCount) {
            sb.append("\n  <Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$i.png\"/>")
        }
        sb.append("\n</Relationships>")
        return sb.toString()
    }

    private fun buildDocumentXml(pages: List<PageContent>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"\n")
        sb.append("            xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"\n")
        sb.append("            xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"\n")
        sb.append("            xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"\n")
        sb.append("            xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">\n")
        sb.append("  <w:body>\n")

        for ((pageIdx, page) in pages.withIndex()) {
            val blocks = page.blocks
            val pageHeight = page.pageHeight.toDouble()
            val pageWidth = page.pageWidth.toDouble()
            val imageIndex = page.imageIndex

            val paragraphs = groupBlocksIntoParagraphs(blocks)

            for (para in paragraphs) {
                sb.append("    <w:p>\n")

                val avgFontSize = para.map { it.fontSize.toDouble() }.average()
                if (avgFontSize > 18) {
                    sb.append("      <w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>\n")
                } else if (avgFontSize > 15) {
                    sb.append("      <w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>\n")
                }

                for (block in para) {
                    val escaped = escapeXml(block.text)
                    val szVal = (block.fontSize * 2).toInt()
                    val isBold = block.fontName.lowercase().contains("bold")
                    val isItalic = block.fontName.lowercase().let {
                        it.contains("italic") || it.contains("oblique")
                    }

                    sb.append("      <w:r>\n")
                    sb.append("        <w:rPr>\n")
                    sb.append("          <w:sz w:val=\"$szVal\"/>\n")
                    sb.append("          <w:szCs w:val=\"$szVal\"/>\n")
                    if (isBold) sb.append("          <w:b/>\n")
                    if (isItalic) sb.append("          <w:i/>\n")
                    sb.append("        </w:rPr>\n")
                    sb.append("        <w:t xml:space=\"preserve\">$escaped</w:t>\n")
                    sb.append("      </w:r>\n")
                }

                sb.append("    </w:p>\n")
            }

            if (imageIndex != null) {
                val emuW = (pageWidth * 914400.0 / 72.0 * 0.9).toInt()
                val emuH = (pageHeight * 914400.0 / 72.0 * 0.9).toInt()
                sb.append("    <w:p>\n")
                sb.append("      <w:r>\n")
                sb.append("        <w:drawing>\n")
                sb.append("          <wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">\n")
                sb.append("            <wp:extent cx=\"$emuW\" cy=\"$emuH\"/>\n")
                sb.append("            <wp:docPr id=\"${imageIndex + 1}\" name=\"Image ${imageIndex + 1}\"/>\n")
                sb.append("            <a:graphic>\n")
                sb.append("              <a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">\n")
                sb.append("                <pic:pic>\n")
                sb.append("                  <pic:nvPicPr>\n")
                sb.append("                    <pic:cNvPr id=\"${imageIndex + 1}\" name=\"image$imageIndex.png\"/>\n")
                sb.append("                    <pic:cNvPicPr/>\n")
                sb.append("                  </pic:nvPicPr>\n")
                sb.append("                  <pic:blipFill>\n")
                sb.append("                    <a:blip r:embed=\"rId${imageIndex + 1}\"/>\n")
                sb.append("                    <a:stretch><a:fillRect/></a:stretch>\n")
                sb.append("                  </pic:blipFill>\n")
                sb.append("                  <pic:spPr>\n")
                sb.append("                    <a:xfrm>\n")
                sb.append("                      <a:off x=\"0\" y=\"0\"/>\n")
                sb.append("                      <a:ext cx=\"$emuW\" cy=\"$emuH\"/>\n")
                sb.append("                    </a:xfrm>\n")
                sb.append("                    <a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>\n")
                sb.append("                  </pic:spPr>\n")
                sb.append("                </pic:pic>\n")
                sb.append("              </a:graphicData>\n")
                sb.append("            </a:graphic>\n")
                sb.append("          </wp:inline>\n")
                sb.append("        </w:drawing>\n")
                sb.append("      </w:r>\n")
                sb.append("    </w:p>\n")
            }

            if (pageIdx < pages.size - 1) {
                sb.append("    <w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n")
            }
        }

        sb.append("  </w:body>\n")
        sb.append("</w:document>")
        return sb.toString()
    }

    private fun groupBlocksIntoParagraphs(blocks: List<TextExtractor.TextBlock>): List<List<TextExtractor.TextBlock>> {
        if (blocks.isEmpty()) return emptyList()

        val sorted = blocks.sortedWith(compareBy<TextExtractor.TextBlock> {
            it.y
        }.thenBy { it.x })

        val paragraphs = mutableListOf<MutableList<TextExtractor.TextBlock>>()
        var currentGroup = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val block = sorted[i]
            val prev = currentGroup.last()

            val lineHeight = maxOf(prev.height, block.height)
            val verticalGap = abs(block.y - prev.y)

            if (verticalGap < lineHeight * 1.5f) {
                currentGroup.add(block)
            } else {
                paragraphs.add(currentGroup)
                currentGroup = mutableListOf(block)
            }
        }

        if (currentGroup.isNotEmpty()) {
            paragraphs.add(currentGroup)
        }

        return paragraphs
    }

    // MARK: - Utilities

    private fun addZipEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) {
            urlString.removePrefix("file://")
        } else {
            urlString
        }
        return File(java.net.URLDecoder.decode(path, "UTF-8"))
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
