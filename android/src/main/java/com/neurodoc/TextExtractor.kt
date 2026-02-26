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
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs

object TextExtractor {

    data class TextBlock(
        val text: String,
        val x: Float,       // normalized 0-1, top-left origin
        val y: Float,
        val width: Float,
        val height: Float,
        val fontSize: Float,
        val fontName: String,
        val confidence: Float
    )

    suspend fun extractText(
        context: Context,
        pdfUrl: String,
        pageIndex: Int,
        mode: String,
        language: String,
        promise: Promise
    ) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)

            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) {
                doc.close()
                promise.reject("TEXT_EXTRACTION_FAILED", "Invalid page index: $pageIndex")
                return
            }

            val page = doc.getPage(pageIndex)
            val mediaBox = page.mediaBox
            val pageWidth = mediaBox.width
            val pageHeight = mediaBox.height

            val blocks: List<TextBlock>
            val usedMode: String

            when (mode) {
                "native" -> {
                    blocks = extractNativeText(doc, pageIndex, pageWidth, pageHeight)
                    usedMode = "native"
                }
                "ocr" -> {
                    blocks = extractWithOcr(pdfUrl, pageIndex, pageWidth, pageHeight)
                    usedMode = "ocr"
                }
                else -> { // "auto"
                    val nativeBlocks = extractNativeText(doc, pageIndex, pageWidth, pageHeight)
                    if (nativeBlocks.isNotEmpty() && nativeBlocks.any { it.text.trim().isNotEmpty() }) {
                        blocks = nativeBlocks
                        usedMode = "native"
                    } else {
                        blocks = extractWithOcr(pdfUrl, pageIndex, pageWidth, pageHeight)
                        usedMode = "ocr"
                    }
                }
            }

            doc.close()
            promise.resolve(buildResult(blocks, pageWidth, pageHeight, usedMode))
        } catch (e: Exception) {
            promise.reject("TEXT_EXTRACTION_FAILED", e.message, e)
        }
    }

    // MARK: - Native text extraction with PDFBox

    private fun extractNativeText(
        doc: PDDocument,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float
    ): List<TextBlock> {
        val stripper = PositionalTextStripper(pageWidth, pageHeight)
        stripper.startPage = pageIndex + 1 // PDFTextStripper is 1-based
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)

        return groupCharactersIntoWords(stripper.characters, pageWidth, pageHeight)
    }

    private class PositionalTextStripper(
        private val pageWidth: Float,
        private val pageHeight: Float
    ) : PDFTextStripper() {

        val characters = mutableListOf<CharInfo>()

        data class CharInfo(
            val char: String,
            val x: Float,         // absolute PDF coords (bottom-left origin)
            val y: Float,
            val width: Float,
            val height: Float,
            val fontSize: Float,
            val fontName: String
        )

        override fun processTextPosition(text: TextPosition) {
            characters.add(
                CharInfo(
                    char = text.unicode,
                    x = text.xDirAdj,
                    y = text.yDirAdj,
                    width = text.widthDirAdj,
                    height = text.heightDir,
                    fontSize = text.fontSize,
                    fontName = text.font?.name ?: "Unknown"
                )
            )
        }
    }

    private fun groupCharactersIntoWords(
        characters: List<PositionalTextStripper.CharInfo>,
        pageWidth: Float,
        pageHeight: Float
    ): List<TextBlock> {
        if (characters.isEmpty()) return emptyList()

        val words = mutableListOf<TextBlock>()
        var currentText = StringBuilder()
        var currentX = characters[0].x
        var currentY = characters[0].y
        var currentMaxX = characters[0].x + characters[0].width
        var currentHeight = characters[0].height
        var currentFontSize = characters[0].fontSize
        var currentFontName = characters[0].fontName

        for (i in characters.indices) {
            val char = characters[i]
            val isWhitespace = char.char.isBlank()

            if (i > 0) {
                val prev = characters[i - 1]
                val isNewLine = abs(char.y - prev.y) > prev.height * 0.5f
                val gap = char.x - (prev.x + prev.width)
                val isLargeGap = gap > prev.width * 0.4f

                if (isWhitespace || isNewLine || isLargeGap) {
                    // Save current word
                    val text = currentText.toString().trim()
                    if (text.isNotEmpty()) {
                        words.add(toNormalizedBlock(
                            text, currentX, currentY, currentMaxX - currentX, currentHeight,
                            currentFontSize, currentFontName, pageWidth, pageHeight
                        ))
                    }

                    if (!isWhitespace) {
                        currentText = StringBuilder(char.char)
                        currentX = char.x
                        currentY = char.y
                        currentMaxX = char.x + char.width
                        currentHeight = char.height
                        currentFontSize = char.fontSize
                        currentFontName = char.fontName
                    } else {
                        currentText = StringBuilder()
                        currentX = 0f
                        currentY = 0f
                        currentMaxX = 0f
                        currentHeight = 0f
                    }
                    continue
                }
            }

            if (!isWhitespace) {
                if (currentText.isEmpty()) {
                    currentX = char.x
                    currentY = char.y
                    currentMaxX = char.x + char.width
                    currentHeight = char.height
                    currentFontSize = char.fontSize
                    currentFontName = char.fontName
                } else {
                    currentMaxX = char.x + char.width
                    if (char.height > currentHeight) currentHeight = char.height
                }
                currentText.append(char.char)
            }
        }

        // Last word
        val text = currentText.toString().trim()
        if (text.isNotEmpty()) {
            words.add(toNormalizedBlock(
                text, currentX, currentY, currentMaxX - currentX, currentHeight,
                currentFontSize, currentFontName, pageWidth, pageHeight
            ))
        }

        return words
    }

    /**
     * Convert PDFTextStripper coordinates to normalized 0-1, top-left origin.
     * PDFTextStripper's yDirAdj is already in top-left coordinate system (distance from top).
     */
    private fun toNormalizedBlock(
        text: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        fontSize: Float,
        fontName: String,
        pageWidth: Float,
        pageHeight: Float
    ): TextBlock {
        val nx = x / pageWidth
        // yDirAdj is from top, but it's the baseline; adjust to top of text
        val ny = (y - height) / pageHeight
        val nw = width / pageWidth
        val nh = height / pageHeight

        return TextBlock(
            text = text,
            x = nx.coerceIn(0f, 1f),
            y = ny.coerceIn(0f, 1f),
            width = nw.coerceIn(0f, 1f),
            height = nh.coerceIn(0f, 1f),
            fontSize = fontSize,
            fontName = fontName,
            confidence = 1.0f
        )
    }

    // MARK: - OCR fallback

    private suspend fun extractWithOcr(
        pdfUrl: String,
        pageIndex: Int,
        pageWidth: Float,
        pageHeight: Float
    ): List<TextBlock> {
        val bitmap = renderPageToBitmap(pdfUrl, pageIndex)
        val blocks = performOcr(bitmap, pageWidth, pageHeight)
        bitmap.recycle()
        return blocks
    }

    private suspend fun performOcr(
        bitmap: Bitmap,
        pageWidth: Float,
        pageHeight: Float
    ): List<TextBlock> = suspendCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val blocks = mutableListOf<TextBlock>()
                val imgW = bitmap.width.toFloat()
                val imgH = bitmap.height.toFloat()

                for (block in text.textBlocks) {
                    val box = block.boundingBox ?: continue
                    val nx = box.left / imgW
                    val ny = box.top / imgH
                    val nw = box.width() / imgW
                    val nh = box.height() / imgH

                    val fontSize = nh * pageHeight * 0.85f

                    blocks.add(TextBlock(
                        text = block.text,
                        x = nx,
                        y = ny,
                        width = nw,
                        height = nh,
                        fontSize = fontSize,
                        fontName = "Unknown",
                        confidence = block.lines.firstOrNull()?.confidence ?: 0f
                    ))
                }

                cont.resume(blocks)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    // MARK: - Helpers

    private fun renderPageToBitmap(pdfUrl: String, pageIndex: Int, scale: Float = 2f): Bitmap {
        val path = if (pdfUrl.startsWith("file://")) pdfUrl.removePrefix("file://") else pdfUrl
        val file = File(path)
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)

        val page = renderer.openPage(pageIndex)
        val width = (page.width * scale).toInt()
        val height = (page.height * scale).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        page.close()
        renderer.close()
        fd.close()

        return bitmap
    }

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }

    private fun buildResult(
        blocks: List<TextBlock>,
        pageWidth: Float,
        pageHeight: Float,
        mode: String
    ): WritableNativeMap {
        val blocksArray = WritableNativeArray()
        for (block in blocks) {
            blocksArray.pushMap(WritableNativeMap().apply {
                putString("text", block.text)
                putMap("boundingBox", WritableNativeMap().apply {
                    putDouble("x", block.x.toDouble())
                    putDouble("y", block.y.toDouble())
                    putDouble("width", block.width.toDouble())
                    putDouble("height", block.height.toDouble())
                })
                putDouble("fontSize", block.fontSize.toDouble())
                putString("fontName", block.fontName)
                putDouble("confidence", block.confidence.toDouble())
            })
        }

        return WritableNativeMap().apply {
            putArray("textBlocks", blocksArray)
            putDouble("pageWidth", pageWidth.toDouble())
            putDouble("pageHeight", pageHeight.toDouble())
            putString("mode", mode)
        }
    }
}
