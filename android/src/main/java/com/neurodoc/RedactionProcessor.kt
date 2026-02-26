package com.neurodoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.util.UUID

object RedactionProcessor {

    fun redact(
        pdfUrl: String,
        redactions: ReadableArray,
        dpi: Float,
        stripMetadata: Boolean,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val file = resolveFile(pdfUrl)
            val srcDoc = PDDocument.load(file)

            // Group redactions by pageIndex
            val redactionsByPage = mutableMapOf<Int, MutableList<RedactionArea>>()
            for (i in 0 until redactions.size()) {
                val redaction = redactions.getMap(i) ?: continue
                val pageIndex = redaction.getInt("pageIndex")
                val color = if (redaction.hasKey("color") && !redaction.isNull("color"))
                    parseHexColor(redaction.getString("color") ?: "#000000") else Color.BLACK

                val rects = redaction.getArray("rects") ?: continue
                for (j in 0 until rects.size()) {
                    val rect = rects.getMap(j) ?: continue
                    redactionsByPage.getOrPut(pageIndex) { mutableListOf() }.add(
                        RedactionArea(
                            x = rect.getDouble("x").toFloat(),
                            y = rect.getDouble("y").toFloat(),
                            width = rect.getDouble("width").toFloat(),
                            height = rect.getDouble("height").toFloat(),
                            color = color
                        )
                    )
                }
            }

            val scale = dpi / 72f
            val outputDoc = PDDocument()
            var pagesRedacted = 0

            for (i in 0 until srcDoc.numberOfPages) {
                val srcPage = srcDoc.getPage(i)
                val mediaBox = srcPage.mediaBox

                if (redactionsByPage.containsKey(i)) {
                    val areas = redactionsByPage[i]!!

                    // Rasterize page to destroy original content
                    val bitmap = renderPageToBitmap(pdfUrl, i, scale)
                    val canvas = Canvas(bitmap)
                    val paint = Paint().apply { style = Paint.Style.FILL }

                    // Draw redaction rectangles
                    for (area in areas) {
                        paint.color = area.color
                        val left = area.x * bitmap.width
                        val top = area.y * bitmap.height
                        val right = left + area.width * bitmap.width
                        val bottom = top + area.height * bitmap.height
                        canvas.drawRect(left, top, right, bottom, paint)
                    }

                    // Create new page with rasterized image
                    val newPage = PDPage(PDRectangle(mediaBox.width, mediaBox.height))
                    outputDoc.addPage(newPage)

                    val pdImage = JPEGFactory.createFromImage(outputDoc, bitmap, 0.95f)
                    val cs = PDPageContentStream(outputDoc, newPage)
                    cs.drawImage(pdImage, 0f, 0f, mediaBox.width, mediaBox.height)
                    cs.close()

                    bitmap.recycle()
                    pagesRedacted++
                } else {
                    // Pass-through: import page as-is
                    val imported = outputDoc.importPage(srcDoc.getPage(i))
                    imported.mediaBox = mediaBox
                }
            }

            if (stripMetadata) {
                val info = outputDoc.documentInformation
                info.title = null
                info.author = null
                info.subject = null
                info.keywords = null
                info.creator = null
                info.producer = null
            }

            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
            outputDoc.save(outputFile)
            outputDoc.close()
            srcDoc.close()

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
                putInt("pagesRedacted", pagesRedacted)
            })
        } catch (e: Exception) {
            promise.reject("REDACTION_FAILED", e.message, e)
        }
    }

    // MARK: - Helpers

    private fun renderPageToBitmap(pdfUrl: String, pageIndex: Int, scale: Float): Bitmap {
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
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

        page.close()
        renderer.close()
        fd.close()

        return bitmap
    }

    private fun parseHexColor(hex: String): Int {
        val cleanHex = hex.removePrefix("#")
        return if (cleanHex.length == 6) {
            Color.rgb(
                cleanHex.substring(0, 2).toInt(16),
                cleanHex.substring(2, 4).toInt(16),
                cleanHex.substring(4, 6).toInt(16)
            )
        } else {
            Color.BLACK
        }
    }

    private data class RedactionArea(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val color: Int
    )

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }
}
