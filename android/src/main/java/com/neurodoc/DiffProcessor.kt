package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object DiffProcessor {

    // -------------------------------------------------------------------------
    // Public entry point
    // -------------------------------------------------------------------------

    fun comparePdfs(
        pdfUrl1: String,
        pdfUrl2: String,
        addedColor: String,
        deletedColor: String,
        changedColor: String,
        opacity: Float,
        annotateSource: Boolean,
        annotateTarget: Boolean,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val file1 = PdfUtils.resolveFile(pdfUrl1)
            val file2 = PdfUtils.resolveFile(pdfUrl2)

            val colorAdded   = parseColor(addedColor)
            val colorDeleted = parseColor(deletedColor)

            var sourcePdfUrl = ""
            var targetPdfUrl = ""
            val changesPerPage = WritableNativeArray()
            var totalAdded = 0; var totalDeleted = 0; var totalChanged = 0

            PDDocument.load(file1).use { doc1 ->
                PDDocument.load(file2).use { doc2 ->
                    val pageCount1 = doc1.numberOfPages
                    val pageCount2 = doc2.numberOfPages
                    val sharedPages = min(pageCount1, pageCount2)

                    // --- Shared pages ---
                    for (i in 0 until sharedPages) {
                        val blocks1 = extractPageBlocks(doc1, i)
                        val blocks2 = extractPageBlocks(doc2, i)

                        val diff = myersDiff(blocks1, blocks2)

                        val sourceRects = mutableListOf<FloatArray>()
                        val targetRects = mutableListOf<FloatArray>()
                        var added = 0; var deleted = 0; var changed = 0

                        for (op in diff) {
                            when (op) {
                                is DiffOp.Delete -> { sourceRects.add(op.block.toRect()); deleted++ }
                                is DiffOp.Insert -> { targetRects.add(op.block.toRect()); added++ }
                                is DiffOp.Change -> {
                                    sourceRects.add(op.old.toRect())
                                    targetRects.add(op.new.toRect())
                                    changed++
                                }
                                is DiffOp.Equal -> Unit
                            }
                        }

                        if (annotateSource && sourceRects.isNotEmpty()) {
                            applyHighlights(doc1, doc1.getPage(i), sourceRects, colorDeleted, opacity)
                        }
                        if (annotateTarget && targetRects.isNotEmpty()) {
                            applyHighlights(doc2, doc2.getPage(i), targetRects, colorAdded, opacity)
                        }

                        changesPerPage.pushMap(pageChangeMap(i, i, added, deleted, changed))
                        totalAdded += added; totalDeleted += deleted; totalChanged += changed
                    }

                    // --- Pages only in doc1 (deleted pages) ---
                    for (i in sharedPages until pageCount1) {
                        val blocks = extractPageBlocks(doc1, i)
                        if (annotateSource && blocks.isNotEmpty()) {
                            applyHighlights(doc1, doc1.getPage(i), blocks.map { it.toRect() }, colorDeleted, opacity)
                        }
                        changesPerPage.pushMap(pageChangeMap(i, -1, 0, blocks.size, 0))
                        totalDeleted += blocks.size
                    }

                    // --- Pages only in doc2 (added pages) ---
                    for (i in sharedPages until pageCount2) {
                        val blocks = extractPageBlocks(doc2, i)
                        if (annotateTarget && blocks.isNotEmpty()) {
                            applyHighlights(doc2, doc2.getPage(i), blocks.map { it.toRect() }, colorAdded, opacity)
                        }
                        changesPerPage.pushMap(pageChangeMap(-1, i, blocks.size, 0, 0))
                        totalAdded += blocks.size
                    }

                    // --- Save annotated PDFs ---
                    if (annotateSource) {
                        val outFile = File(tempDir, "${UUID.randomUUID()}_diff_source.pdf")
                        doc1.save(outFile)
                        sourcePdfUrl = "file://${outFile.absolutePath}"
                    }
                    if (annotateTarget) {
                        val outFile = File(tempDir, "${UUID.randomUUID()}_diff_target.pdf")
                        doc2.save(outFile)
                        targetPdfUrl = "file://${outFile.absolutePath}"
                    }
                }
            }

            promise.resolve(WritableNativeMap().apply {
                putString("sourcePdfUrl", sourcePdfUrl)
                putString("targetPdfUrl", targetPdfUrl)
                putArray("changes", changesPerPage)
                putInt("totalAdded", totalAdded)
                putInt("totalDeleted", totalDeleted)
                putInt("totalChanged", totalChanged)
            })
        } catch (e: Exception) {
            promise.reject("COMPARISON_FAILED", e.message, e)
        }
    }

    // -------------------------------------------------------------------------
    // Text extraction (internal, native only for speed)
    // -------------------------------------------------------------------------

    private data class Block(
        val text: String,
        val x: Float,      // normalized 0-1, top-left origin
        val y: Float,
        val width: Float,
        val height: Float
    ) {
        fun toRect(): FloatArray = floatArrayOf(x, y, width, height)
    }

    private fun extractPageBlocks(doc: PDDocument, pageIndex: Int): List<Block> {
        val page = doc.getPage(pageIndex)
        val mediaBox = page.mediaBox
        val pageWidth = mediaBox.width
        val pageHeight = mediaBox.height

        val stripper = SimpleTextStripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.getText(doc)

        return groupToBlocks(stripper.characters, pageWidth, pageHeight)
    }

    private class SimpleTextStripper : PDFTextStripper() {
        data class CharInfo(
            val char: String,
            val x: Float,
            val y: Float,       // yDirAdj = distance from top (baseline)
            val width: Float,
            val height: Float
        )
        val characters = mutableListOf<CharInfo>()

        override fun processTextPosition(text: TextPosition) {
            characters.add(
                CharInfo(
                    char = text.unicode,
                    x = text.xDirAdj,
                    y = text.yDirAdj,
                    width = text.widthDirAdj,
                    height = text.heightDir
                )
            )
        }
    }

    private fun groupToBlocks(
        characters: List<SimpleTextStripper.CharInfo>,
        pageWidth: Float,
        pageHeight: Float
    ): List<Block> {
        if (characters.isEmpty()) return emptyList()

        val words = mutableListOf<Block>()
        var currentText = StringBuilder()
        var curX = characters[0].x
        var curY = characters[0].y
        var curMaxX = characters[0].x + characters[0].width
        var curH = characters[0].height

        for (i in characters.indices) {
            val char = characters[i]
            val isWhitespace = char.char.isBlank()

            if (i > 0) {
                val prev = characters[i - 1]
                val isNewLine = abs(char.y - prev.y) > prev.height * 0.5f
                val gap = char.x - (prev.x + prev.width)
                val isLargeGap = gap > prev.width * 0.4f

                if (isWhitespace || isNewLine || isLargeGap) {
                    val text = currentText.toString().trim()
                    if (text.isNotEmpty()) {
                        words.add(toBlock(text, curX, curY, curMaxX - curX, curH, pageWidth, pageHeight))
                    }
                    if (!isWhitespace) {
                        currentText = StringBuilder(char.char)
                        curX = char.x; curY = char.y
                        curMaxX = char.x + char.width; curH = char.height
                    } else {
                        currentText = StringBuilder()
                        curX = 0f; curY = 0f; curMaxX = 0f; curH = 0f
                    }
                    continue
                }
            }

            if (!isWhitespace) {
                if (currentText.isEmpty()) {
                    curX = char.x; curY = char.y; curH = char.height
                }
                currentText.append(char.char)
                curMaxX = max(curMaxX, char.x + char.width)
                curH = max(curH, char.height)
            }
        }

        val text = currentText.toString().trim()
        if (text.isNotEmpty()) {
            words.add(toBlock(text, curX, curY, curMaxX - curX, curH, pageWidth, pageHeight))
        }
        return words
    }

    private fun toBlock(
        text: String, x: Float, y: Float, width: Float, height: Float,
        pageWidth: Float, pageHeight: Float
    ): Block {
        val nx = x / pageWidth
        val ny = (y - height) / pageHeight
        val nw = width / pageWidth
        val nh = height / pageHeight
        return Block(text, nx, ny.coerceIn(0f, 1f), nw, nh)
    }

    // -------------------------------------------------------------------------
    // Diff algorithm (LCS + fuzzy change detection)
    // -------------------------------------------------------------------------

    private sealed class DiffOp {
        data class Equal(val block: Block) : DiffOp()
        data class Delete(val block: Block) : DiffOp()
        data class Insert(val block: Block) : DiffOp()
        data class Change(val old: Block, val new: Block) : DiffOp()
    }

    private fun myersDiff(old: List<Block>, new: List<Block>): List<DiffOp> {
        val m = old.size; val n = new.size
        val lcs = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                lcs[i][j] = if (old[i-1].text.lowercase() == new[j-1].text.lowercase()) {
                    lcs[i-1][j-1] + 1
                } else {
                    max(lcs[i-1][j], lcs[i][j-1])
                }
            }
        }
        val ops = mutableListOf<DiffOp>()
        var i = m; var j = n
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && old[i-1].text.lowercase() == new[j-1].text.lowercase() -> {
                    ops.add(DiffOp.Equal(old[i-1])); i--; j--
                }
                j > 0 && (i == 0 || lcs[i][j-1] >= lcs[i-1][j]) -> {
                    ops.add(DiffOp.Insert(new[j-1])); j--
                }
                else -> { ops.add(DiffOp.Delete(old[i-1])); i-- }
            }
        }
        ops.reverse()
        return mergeFuzzyChanges(ops)
    }

    private fun mergeFuzzyChanges(ops: List<DiffOp>): List<DiffOp> {
        val result = mutableListOf<DiffOp>()
        val pending = mutableListOf<Block>()

        for (op in ops) {
            when (op) {
                is DiffOp.Delete -> pending.add(op.block)
                is DiffOp.Insert -> {
                    val del = pending.firstOrNull()
                    if (del != null && fuzzyRatio(del.text, op.block.text) > 0.8) {
                        result.add(DiffOp.Change(del, op.block))
                        pending.removeAt(0)
                    } else {
                        result.addAll(pending.map { DiffOp.Delete(it) })
                        pending.clear()
                        result.add(op)
                    }
                }
                else -> {
                    result.addAll(pending.map { DiffOp.Delete(it) })
                    pending.clear()
                    result.add(op)
                }
            }
        }
        result.addAll(pending.map { DiffOp.Delete(it) })
        return result
    }

    // -------------------------------------------------------------------------
    // Fuzzy matching (Levenshtein ratio)
    // -------------------------------------------------------------------------

    private fun fuzzyRatio(a: String, b: String): Double {
        val aL = a.lowercase(); val bL = b.lowercase()
        if (aL == bL) return 1.0
        if (aL.isEmpty() || bL.isEmpty()) return 0.0
        val lenA = aL.length; val lenB = bL.length
        if (lenA > 30 || lenB > 30) {
            val common = aL.zip(bL).takeWhile { it.first == it.second }.size
            return (common * 2).toDouble() / (lenA + lenB)
        }
        var prev = IntArray(lenB + 1) { it }
        var curr = IntArray(lenB + 1)
        for ((i, ca) in aL.withIndex()) {
            curr[0] = i + 1
            for ((j, cb) in bL.withIndex()) {
                val cost = if (ca == cb) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            prev = curr.copyOf()
        }
        val dist = curr[lenB].toDouble()
        return 1.0 - dist / max(lenA, lenB)
    }

    // -------------------------------------------------------------------------
    // Annotation helpers
    // -------------------------------------------------------------------------

    private fun applyHighlights(
        doc: PDDocument,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        rects: List<FloatArray>,
        color: FloatArray,
        opacity: Float
    ) {
        val mediaBox = page.mediaBox
        for (rect in rects) {
            val x = rect[0]; val y = rect[1]; val w = rect[2]; val h = rect[3]

            val pdfX = x * mediaBox.width
            val pdfY = (1f - y - h) * mediaBox.height
            val pdfW = w * mediaBox.width
            val pdfH = h * mediaBox.height

            val annotation = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
            annotation.rectangle = PDRectangle(pdfX, pdfY, pdfW, pdfH)
            annotation.setColor(PDColor(color, PDDeviceRGB.INSTANCE))
            annotation.quadPoints = floatArrayOf(
                pdfX, pdfY + pdfH,
                pdfX + pdfW, pdfY + pdfH,
                pdfX, pdfY,
                pdfX + pdfW, pdfY
            )
            page.annotations.add(annotation)

            val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
            cs.saveGraphicsState()
            val gs = PDExtendedGraphicsState()
            gs.nonStrokingAlphaConstant = opacity
            cs.setGraphicsStateParameters(gs)
            cs.setNonStrokingColor(color[0], color[1], color[2])
            cs.addRect(pdfX, pdfY, pdfW, pdfH)
            cs.fill()
            cs.restoreGraphicsState()
            cs.close()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun pageChangeMap(pi1: Int, pi2: Int, added: Int, deleted: Int, changed: Int) =
        WritableNativeMap().apply {
            putInt("pageIndex1", pi1)
            putInt("pageIndex2", pi2)
            putInt("added", added)
            putInt("deleted", deleted)
            putInt("changed", changed)
        }

    private fun parseColor(hex: String): FloatArray {
        val cleaned = hex.trimStart('#')
        return try {
            val r = cleaned.substring(0, 2).toInt(16) / 255f
            val g = cleaned.substring(2, 4).toInt(16) / 255f
            val b = cleaned.substring(4, 6).toInt(16) / 255f
            floatArrayOf(r, g, b)
        } catch (_: Exception) {
            floatArrayOf(1f, 1f, 0f) // yellow fallback
        }
    }
}
