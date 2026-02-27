package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import android.util.Log
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.ByteArrayOutputStream
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
            val file = PdfUtils.resolveFile(pdfUrl)
            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
            var editsApplied = 0

            PDDocument.load(file).use { doc ->
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

                for ((pageIndex, pageEdits) in editsByPage) {
                    if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue
                    val page = doc.getPage(pageIndex)
                    val mediaBox = page.mediaBox

                    val pdfRegions = pageEdits.map { edit ->
                        floatArrayOf(
                            edit.x * mediaBox.width,
                            mediaBox.height * (1f - edit.y - edit.height),
                            edit.width * mediaBox.width,
                            edit.height * mediaBox.height
                        )
                    }

                    // Step 1: Filter out text tokens that fall inside target regions
                    val allTokens = mutableListOf<Any>()
                    val parser = PDFStreamParser(page)
                    try {
                        var token = parser.parseNextToken()
                        while (token != null) {
                            allTokens.add(token)
                            token = parser.parseNextToken()
                        }
                    } catch (_: Exception) { /* end of stream */ }

                    val filteredTokens = filterTextTokens(allTokens, pdfRegions)

                    // Step 2: Serialize filtered tokens as the page's content stream
                    val baos = ByteArrayOutputStream()
                    val writer = ContentStreamWriter(baos)
                    writer.writeTokens(filteredTokens)

                    val cosStream = doc.document.createCOSStream()
                    cosStream.createOutputStream().use { os ->
                        os.write(baos.toByteArray())
                    }
                    page.cosObject.setItem(COSName.CONTENTS, cosStream)

                    // Also filter text inside Form XObjects
                    val pageResources = page.resources
                    if (pageResources != null) {
                        for (xName in pageResources.xObjectNames) {
                            try {
                                val xObj = pageResources.getXObject(xName)
                                if (xObj is PDFormXObject) {
                                    val xTokens = mutableListOf<Any>()
                                    val xParser = PDFStreamParser(xObj)
                                    try {
                                        var t = xParser.parseNextToken()
                                        while (t != null) { xTokens.add(t); t = xParser.parseNextToken() }
                                    } catch (_: Exception) {}

                                    val xFiltered = filterTextTokens(xTokens, pdfRegions)

                                    if (xTokens.size != xFiltered.size) {
                                        val xBaos = ByteArrayOutputStream()
                                        ContentStreamWriter(xBaos).writeTokens(xFiltered)
                                        (xObj.cosObject as? COSStream)?.createOutputStream()?.use { os ->
                                            os.write(xBaos.toByteArray())
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ContentEditor", "XObject processing error: ${e.message}")
                            }
                        }
                    }

                    // Step 3: Append new replacement text
                    val cs = PDPageContentStream(
                        doc, page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    )

                    for (edit in pageEdits) {
                        val pdfX = edit.x * mediaBox.width
                        val pdfY = mediaBox.height * (1f - edit.y - edit.height)
                        val pdfW = edit.width * mediaBox.width
                        val pdfH = edit.height * mediaBox.height

                        val font = resolveFont(edit.fontName)
                        var fontSize = edit.fontSize ?: (pdfH * 0.85f).coerceAtLeast(4f)

                        while (fontSize > 4f) {
                            val textWidth = font.getStringWidth(edit.newText) / 1000f * fontSize
                            if (textWidth <= pdfW - 2f) break
                            fontSize -= 0.5f
                        }

                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setNonStrokingColor(edit.color[0] / 255f, edit.color[1] / 255f, edit.color[2] / 255f)
                        cs.newLineAtOffset(pdfX + 1f, pdfY + pdfH * 0.15f)
                        cs.showText(edit.newText)
                        cs.endText()

                        editsApplied++
                    }

                    cs.close()
                }

                doc.save(outputFile)
            }

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
                putInt("editsApplied", editsApplied)
            })
        } catch (e: Exception) {
            promise.reject("CONTENT_EDIT_FAILED", e.message, e)
        }
    }

    /**
     * Walk through the token list and drop text-show operators (Tj/TJ/'/") whose
     * page-space position falls within any of the given [regions].
     *
     * Tracks the CTM (q/Q/cm) in addition to text-matrix operators (Tm/Td/TD/TL/T*)
     * so that text positioned via CTM translations is correctly detected and removed.
     */
    private fun filterTextTokens(tokens: List<Any>, regions: List<FloatArray>): List<Any> {
        var ctmA = 1f; var ctmB = 0f; var ctmC = 0f
        var ctmD = 1f; var ctmE = 0f; var ctmF = 0f
        data class CtmSnap(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float)
        val ctmStack = ArrayDeque<CtmSnap>()

        var tmX = 0f; var tmY = 0f
        var tlmX = 0f; var tlmY = 0f
        var textLeading = 0f

        val result = mutableListOf<Any>()
        val pending = mutableListOf<Any>()

        for (token in tokens) {
            if (token !is Operator) { pending.add(token); continue }
            val op = token.name

            if (op == "'" || op == "\"") { tlmY -= textLeading; tmX = tlmX; tmY = tlmY }

            val pageX = tmX * ctmA + tmY * ctmC + ctmE
            val pageY = tmX * ctmB + tmY * ctmD + ctmF

            val skip = when (op) {
                "Tj", "TJ", "'", "\"" -> regions.any { r -> isInRegion(pageX, pageY, r) }
                else -> false
            }

            when (op) {
                "q" -> ctmStack.addLast(CtmSnap(ctmA, ctmB, ctmC, ctmD, ctmE, ctmF))
                "Q" -> ctmStack.removeLastOrNull()?.let {
                    ctmA = it.a; ctmB = it.b; ctmC = it.c; ctmD = it.d; ctmE = it.e; ctmF = it.f
                }
                "cm" -> if (pending.size >= 6) {
                    val a2 = num(pending[0]); val b2 = num(pending[1])
                    val c2 = num(pending[2]); val d2 = num(pending[3])
                    val e2 = num(pending[4]); val f2 = num(pending[5])
                    val na = a2 * ctmA + b2 * ctmC; val nb = a2 * ctmB + b2 * ctmD
                    val nc = c2 * ctmA + d2 * ctmC; val nd = c2 * ctmB + d2 * ctmD
                    val ne = e2 * ctmA + f2 * ctmC + ctmE
                    val nf = e2 * ctmB + f2 * ctmD + ctmF
                    ctmA = na; ctmB = nb; ctmC = nc; ctmD = nd; ctmE = ne; ctmF = nf
                }
                "BT" -> { tmX = 0f; tmY = 0f; tlmX = 0f; tlmY = 0f; textLeading = 0f }
                "Tm" -> if (pending.size >= 6) {
                    tmX = num(pending[4]); tmY = num(pending[5]); tlmX = tmX; tlmY = tmY
                }
                "Td" -> if (pending.size >= 2) {
                    tlmX += num(pending[0]); tlmY += num(pending[1]); tmX = tlmX; tmY = tlmY
                }
                "TD" -> if (pending.size >= 2) {
                    textLeading = -num(pending[1])
                    tlmX += num(pending[0]); tlmY += num(pending[1]); tmX = tlmX; tmY = tlmY
                }
                "TL" -> if (pending.isNotEmpty()) { textLeading = num(pending[0]) }
                "T*" -> { tlmY -= textLeading; tmX = tlmX; tmY = tlmY }
            }

            if (!skip) { result.addAll(pending); result.add(token) }
            pending.clear()
        }

        result.addAll(pending)
        return result
    }

    /**
     * Returns true if (x, y) is within region [rx, ry, rw, rh] with a small tolerance.
     * Coordinates in PDF page space (bottom-left origin).
     */
    private fun isInRegion(x: Float, y: Float, region: FloatArray): Boolean {
        val tolerance = 15f
        return x >= region[0] - tolerance && x <= region[0] + region[2] + tolerance &&
               y >= region[1] - tolerance && y <= region[1] + region[3] + tolerance
    }

    private fun num(obj: Any?): Float = (obj as? COSNumber)?.floatValue() ?: 0f

    // region Helpers

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
        return try {
            intArrayOf(
                cleanHex.substring(0, 2).toInt(16),
                cleanHex.substring(2, 4).toInt(16),
                cleanHex.substring(4, 6).toInt(16)
            )
        } catch (_: Exception) {
            intArrayOf(0, 0, 0)
        }
    }

    // endregion
}
