package com.neurodoc

import android.content.Context
import android.graphics.Bitmap
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
            val fis = FileInputStream(file)
            val docx = XWPFDocument(fis)

            val pageDims = getPageDimensions(pageSize)
            val pageWidth = pageDims.first
            val pageHeight = pageDims.second
            val margin = 56f
            val contentLeft = margin
            val contentWidth = pageWidth - margin * 2
            val contentTop = margin
            val contentBottom = pageHeight - margin

            val doc = PDDocument()
            val warnings = mutableListOf<String>()

            var cursorY = contentTop
            var pageCount = 0
            var contentStream: PDPageContentStream? = null
            var currentPage: PDPage? = null

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

                        // Detect headings and list items
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

                        // List prefix
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

                            // Check for images
                            if (preserveImages && run.embeddedPictures.isNotEmpty()) {
                                for (pic in run.embeddedPictures) {
                                    try {
                                        val imgData = pic.pictureData.data
                                        val pdImage = PDImageXObject.createFromByteArray(doc, imgData, pic.pictureData.fileName)

                                        val maxImgWidth = contentWidth * 0.9f
                                        val scale = min(1f, maxImgWidth / pdImage.width.toFloat())
                                        val imgW = pdImage.width * scale
                                        val imgH = pdImage.height * scale

                                        // Flush current text first
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

                            var fontSize = run.fontSize.toFloat()
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
                            // Simple word-wrapping: estimate if we need multiple lines
                            val totalText = segments.joinToString("") { it.text }
                            val avgCharWidth = (segments.firstOrNull()?.fontSize ?: 12f) * 0.5f
                            val availableWidth = contentWidth - listIndent
                            val estimatedLines = ceil((totalText.length * avgCharWidth) / availableWidth).toInt().coerceAtLeast(1)
                            val totalHeight = lineHeight * estimatedLines

                            ensureSpace(totalHeight)
                            drawTextSegments(segments, cs, contentLeft + listIndent,
                                toPdfY(cursorY, lineHeight), availableWidth)
                            cursorY += totalHeight

                            // Paragraph spacing
                            cursorY += 4f
                        } else if (para.runs.isEmpty()) {
                            // Empty paragraph = line break
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
                            // Measure row height
                            var rowHeight = 20f
                            for (cell in row.tableCells) {
                                val cellText = cell.text ?: ""
                                val textHeight = 12f * 1.3f * (cellText.split("\n").size)
                                rowHeight = maxOf(rowHeight, textHeight + cellPadding * 2)
                            }

                            ensureSpace(rowHeight)

                            // Draw cells
                            for ((colIdx, cell) in row.tableCells.withIndex()) {
                                val cellX = contentLeft + colIdx * colWidth
                                val pdfY = toPdfY(cursorY, rowHeight)

                                // Cell border
                                cs.setStrokingColor(128, 128, 128)
                                cs.setLineWidth(0.5f)
                                cs.addRect(cellX, pdfY, colWidth, rowHeight)
                                cs.stroke()

                                // Cell text
                                val cellText = cell.text ?: ""
                                if (cellText.isNotEmpty()) {
                                    val font = PDType1Font.HELVETICA
                                    val fontSize = 10f
                                    cs.beginText()
                                    cs.setFont(font, fontSize)
                                    cs.setNonStrokingColor(0, 0, 0)
                                    cs.newLineAtOffset(cellX + cellPadding, pdfY + rowHeight - cellPadding - fontSize)
                                    // Truncate text to fit cell
                                    val maxChars = ((colWidth - cellPadding * 2) / (fontSize * 0.5f)).toInt()
                                    val displayText = if (cellText.length > maxChars)
                                        cellText.take(maxChars) else cellText
                                    cs.showText(displayText.replace("\n", " "))
                                    cs.endText()
                                }
                            }
                            cursorY += rowHeight
                        }
                        cursorY += 8f // spacing after table
                    }

                    else -> {
                        // Unsupported element type
                    }
                }
            }

            contentStream?.close()
            docx.close()
            fis.close()

            val name = "${UUID.randomUUID()}.pdf"
            val outputFile = File(tempDir, name)
            doc.save(outputFile)
            doc.close()

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
            val doc = PDDocument.load(file)
            val pageCount = doc.numberOfPages

            if (pageCount == 0) {
                doc.close()
                promise.reject("CONVERSION_FAILED", "PDF has no pages")
                return
            }

            val pages = mutableListOf<PageContent>()
            var imageIdx = 0
            var actualMode = if (mode == "ocrFallback") "auto" else mode

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

            doc.close()

            // Build DOCX ZIP
            val baos = ByteArrayOutputStream()
            val zos = ZipOutputStream(baos)

            val images = mutableListOf<Pair<String, ByteArray>>()
            for (page in pages) {
                if (page.image != null && page.imageIndex != null) {
                    images.add("word/media/image${page.imageIndex}.png" to page.image)
                }
            }

            // [Content_Types].xml
            addZipEntry(zos, "[Content_Types].xml", buildContentTypes(images.isNotEmpty()))

            // _rels/.rels
            addZipEntry(zos, "_rels/.rels", buildRootRels())

            // word/_rels/document.xml.rels
            addZipEntry(zos, "word/_rels/document.xml.rels", buildDocumentRels(images.size))

            // word/document.xml
            addZipEntry(zos, "word/document.xml", buildDocumentXml(pages))

            // Images
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
            cs.setNonStrokingColor(0, 0, 0)
            cs.newLineAtOffset(currentX, y)

            // Handle encoding: PDType1Font only supports WinAnsiEncoding
            val safeText = seg.text.map {
                try { font.encode(it.toString()); it } catch (_: Exception) { '?' }
            }.joinToString("")

            cs.showText(safeText)
            currentX = 0f // subsequent segments continue from current position
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
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().build())

        return suspendCoroutine { continuation ->
            recognizer.process(inputImage)
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
                .addOnFailureListener { e ->
                    bitmap.recycle()
                    continuation.resume(emptyList())
                }
        }
    }

    private fun renderPageToBitmap(pdfPath: String, pageIndex: Int): Bitmap? {
        return try {
            val file = resolveFile(pdfPath)
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)
            val page = renderer.openPage(pageIndex)

            val scale = 2f
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            fd.close()
            bitmap
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
        var xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>"""
        if (hasImages) {
            xml += """
  <Default Extension="png" ContentType="image/png"/>
  <Default Extension="jpg" ContentType="image/jpeg"/>"""
        }
        xml += "\n</Types>"
        return xml
    }

    private fun buildRootRels(): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
    }

    private fun buildDocumentRels(imageCount: Int): String {
        var xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""
        for (i in 0 until imageCount) {
            xml += """
  <Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image$i.png"/>"""
        }
        xml += "\n</Relationships>"
        return xml
    }

    private fun buildDocumentXml(pages: List<PageContent>): String {
        var xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
  <w:body>
"""

        for ((pageIdx, page) in pages.withIndex()) {
            val blocks = page.blocks
            val pageHeight = page.pageHeight.toDouble()
            val pageWidth = page.pageWidth.toDouble()
            val imageIndex = page.imageIndex

            // Group blocks into paragraphs
            val paragraphs = groupBlocksIntoParagraphs(blocks)

            for (para in paragraphs) {
                xml += "    <w:p>\n"

                val avgFontSize = para.map { it.fontSize.toDouble() }.average()
                if (avgFontSize > 18) {
                    xml += "      <w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>\n"
                } else if (avgFontSize > 15) {
                    xml += "      <w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>\n"
                }

                for (block in para) {
                    val escaped = escapeXml(block.text)
                    val szVal = (block.fontSize * 2).toInt()
                    val isBold = block.fontName.lowercase().contains("bold")
                    val isItalic = block.fontName.lowercase().let {
                        it.contains("italic") || it.contains("oblique")
                    }

                    xml += "      <w:r>\n"
                    xml += "        <w:rPr>\n"
                    xml += "          <w:sz w:val=\"$szVal\"/>\n"
                    xml += "          <w:szCs w:val=\"$szVal\"/>\n"
                    if (isBold) xml += "          <w:b/>\n"
                    if (isItalic) xml += "          <w:i/>\n"
                    xml += "        </w:rPr>\n"
                    xml += "        <w:t xml:space=\"preserve\">$escaped</w:t>\n"
                    xml += "      </w:r>\n"
                }

                xml += "    </w:p>\n"
            }

            // Page image
            if (imageIndex != null) {
                val emuW = (pageWidth * 914400.0 / 72.0 * 0.9).toInt()
                val emuH = (pageHeight * 914400.0 / 72.0 * 0.9).toInt()
                xml += """    <w:p>
      <w:r>
        <w:drawing>
          <wp:inline distT="0" distB="0" distL="0" distR="0">
            <wp:extent cx="$emuW" cy="$emuH"/>
            <wp:docPr id="${imageIndex + 1}" name="Image ${imageIndex + 1}"/>
            <a:graphic>
              <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                <pic:pic>
                  <pic:nvPicPr>
                    <pic:cNvPr id="${imageIndex + 1}" name="image$imageIndex.png"/>
                    <pic:cNvPicPr/>
                  </pic:nvPicPr>
                  <pic:blipFill>
                    <a:blip r:embed="rId${imageIndex + 1}"/>
                    <a:stretch><a:fillRect/></a:stretch>
                  </pic:blipFill>
                  <pic:spPr>
                    <a:xfrm>
                      <a:off x="0" y="0"/>
                      <a:ext cx="$emuW" cy="$emuH"/>
                    </a:xfrm>
                    <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                  </pic:spPr>
                </pic:pic>
              </a:graphicData>
            </a:graphic>
          </wp:inline>
        </w:drawing>
      </w:r>
    </w:p>
"""
            }

            // Page break between pages
            if (pageIdx < pages.size - 1) {
                xml += "    <w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n"
            }
        }

        xml += """  </w:body>
</w:document>"""
        return xml
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
