package com.neurodoc

import android.content.Context
import android.graphics.Bitmap
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object OcrProcessor {

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizePage(context: Context, pdfUrl: String, pageIndex: Int, language: String, promise: Promise) {
        try {
            val bitmap = PdfUtils.renderPageToBitmap(pdfUrl, pageIndex)
            val blocks = performOcr(bitmap)

            val blocksArray = WritableNativeArray()
            for (block in blocks) {
                val blockMap = WritableNativeMap().apply {
                    putString("text", block.text)
                    putMap("boundingBox", WritableNativeMap().apply {
                        putDouble("x", block.x.toDouble())
                        putDouble("y", block.y.toDouble())
                        putDouble("width", block.width.toDouble())
                        putDouble("height", block.height.toDouble())
                    })
                    putDouble("confidence", block.confidence.toDouble())
                }
                blocksArray.pushMap(blockMap)
            }

            bitmap.recycle()

            promise.resolve(WritableNativeMap().apply {
                putArray("blocks", blocksArray)
            })
        } catch (e: Exception) {
            promise.reject("OCR_FAILED", e.message, e)
        }
    }

    suspend fun makeSearchable(context: Context, pdfUrl: String, language: String, pageIndexes: List<Int>?, tempDir: File, promise: Promise) {
        try {
            val srcFile = PdfUtils.resolveFile(pdfUrl)
            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
            var pagesProcessed = 0

            PDDocument.load(srcFile).use { srcDoc ->
                val targetPages = pageIndexes ?: (0 until srcDoc.numberOfPages).toList()

                for (i in targetPages) {
                    if (i >= srcDoc.numberOfPages) continue

                    val bitmap = PdfUtils.renderPageToBitmap(pdfUrl, i)
                    val blocks = performOcr(bitmap)

                    if (blocks.isNotEmpty()) {
                        val page = srcDoc.getPage(i)
                        val mediaBox = page.mediaBox

                        val cs = PDPageContentStream(srcDoc, page, PDPageContentStream.AppendMode.APPEND, true, true)

                        val gs = PDExtendedGraphicsState()
                        gs.nonStrokingAlphaConstant = 0.0f
                        cs.setGraphicsStateParameters(gs)

                        val font = PDType1Font.HELVETICA

                        for (block in blocks) {
                            val pdfX = block.x * mediaBox.width
                            val pdfY = mediaBox.height - (block.y + block.height) * mediaBox.height
                            val pdfH = block.height * mediaBox.height

                            val fontSize = (pdfH * 0.8f).coerceIn(4f, 72f)

                            cs.beginText()
                            cs.setFont(font, fontSize)
                            cs.newLineAtOffset(pdfX, pdfY)
                            try {
                                cs.showText(block.text)
                            } catch (_: Exception) {
                                // Some characters may not be encodable in PDType1Font
                            }
                            cs.endText()
                        }

                        cs.close()
                        pagesProcessed++
                    }

                    bitmap.recycle()
                }

                srcDoc.save(outputFile)
            }

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
                putInt("pagesProcessed", pagesProcessed)
            })
        } catch (e: Exception) {
            promise.reject("OCR_FAILED", e.message, e)
        }
    }

    // region Helpers

    data class OcrBlock(
        val text: String,
        val x: Float,      // normalized 0-1
        val y: Float,
        val width: Float,
        val height: Float,
        val confidence: Float
    )

    private suspend fun performOcr(bitmap: Bitmap): List<OcrBlock> = suspendCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        textRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val blocks = mutableListOf<OcrBlock>()
                val imgW = bitmap.width.toFloat()
                val imgH = bitmap.height.toFloat()

                for (block in text.textBlocks) {
                    val box = block.boundingBox ?: continue
                    blocks.add(
                        OcrBlock(
                            text = block.text,
                            x = box.left / imgW,
                            y = box.top / imgH,
                            width = box.width() / imgW,
                            height = box.height() / imgH,
                            confidence = block.lines.firstOrNull()?.confidence ?: 0f
                        )
                    )
                }

                cont.resume(blocks)
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    // endregion
}
