package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import java.util.UUID

object ContentEditor {

    fun editContent(
        pdfUrl: String,
        edits: ReadableArray,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)

            // Group edits by pageIndex
            val editsByPage = mutableMapOf<Int, MutableList<EditInfo>>()
            for (i in 0 until edits.size()) {
                val edit = edits.getMap(i) ?: continue
                val pageIndex = edit.getInt("pageIndex")
                val bbox = edit.getMap("boundingBox") ?: continue
                val newText = edit.getString("newText") ?: continue

                val color = if (edit.hasKey("color") && !edit.isNull("color"))
                    parseHexColor(edit.getString("color") ?: "#000000")
                else intArrayOf(0, 0, 0)

                val fontSize = if (edit.hasKey("fontSize") && !edit.isNull("fontSize"))
                    edit.getDouble("fontSize").toFloat() else null

                val fontName = if (edit.hasKey("fontName") && !edit.isNull("fontName"))
                    edit.getString("fontName") else null

                editsByPage.getOrPut(pageIndex) { mutableListOf() }.add(
                    EditInfo(
                        x = bbox.getDouble("x").toFloat(),
                        y = bbox.getDouble("y").toFloat(),
                        width = bbox.getDouble("width").toFloat(),
                        height = bbox.getDouble("height").toFloat(),
                        newText = newText,
                        fontSize = fontSize,
                        fontName = fontName,
                        color = color
                    )
                )
            }

            var editsApplied = 0

            for ((pageIndex, pageEdits) in editsByPage) {
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val mediaBox = page.mediaBox

                val cs = PDPageContentStream(
                    doc, page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,  // compress
                    true   // reset context
                )

                for (edit in pageEdits) {
                    // Convert normalized top-left to PDF bottom-left coordinates
                    val pdfX = edit.x * mediaBox.width
                    val pdfY = mediaBox.height - edit.y * mediaBox.height - edit.height * mediaBox.height
                    val pdfW = edit.width * mediaBox.width
                    val pdfH = edit.height * mediaBox.height

                    // 1. White-out: draw white rectangle (slightly expanded)
                    cs.setNonStrokingColor(255, 255, 255)
                    cs.addRect(pdfX - 1f, pdfY - 1f, pdfW + 2f, pdfH + 2f)
                    cs.fill()

                    // 2. Determine font
                    val font = resolveFont(edit.fontName)
                    val fontSize = edit.fontSize
                        ?: (pdfH * 0.85f).coerceAtLeast(4f)

                    // 3. Draw new text
                    cs.beginText()
                    cs.setFont(font, fontSize)
                    cs.setNonStrokingColor(edit.color[0], edit.color[1], edit.color[2])

                    // Position: baseline ~20% up from bottom of box
                    val baselineY = pdfY + pdfH * 0.15f
                    cs.newLineAtOffset(pdfX + 1f, baselineY)
                    cs.showText(edit.newText)
                    cs.endText()

                    editsApplied++
                }

                cs.close()
            }

            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
            doc.save(outputFile)
            doc.close()

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
                putInt("editsApplied", editsApplied)
            })
        } catch (e: Exception) {
            promise.reject("CONTENT_EDIT_FAILED", e.message, e)
        }
    }

    // MARK: - Helpers

    private data class EditInfo(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val newText: String,
        val fontSize: Float?,
        val fontName: String?,
        val color: IntArray
    )

    private fun resolveFont(fontName: String?): PDType1Font {
        return when (fontName?.lowercase()) {
            "courier" -> PDType1Font.COURIER
            "timesnewroman", "times", "times-roman" -> PDType1Font.TIMES_ROMAN
            else -> PDType1Font.HELVETICA
        }
    }

    private fun parseHexColor(hex: String): IntArray {
        val cleanHex = hex.removePrefix("#")
        if (cleanHex.length != 6) return intArrayOf(0, 0, 0)
        return intArrayOf(
            cleanHex.substring(0, 2).toInt(16),
            cleanHex.substring(2, 4).toInt(16),
            cleanHex.substring(4, 6).toInt(16)
        )
    }

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }
}
