package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import java.io.File
import java.util.UUID

object AnnotationProcessor {

    fun addAnnotations(pdfUrl: String, annotations: ReadableArray, tempDir: File, promise: Promise) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)

            for (i in 0 until annotations.size()) {
                val annotMap = annotations.getMap(i) ?: continue
                val type = annotMap.getString("type") ?: continue
                val pageIndex = annotMap.getInt("pageIndex")

                if (pageIndex >= doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val mediaBox = page.mediaBox

                val colorArray = parseColor(annotMap.getString("color") ?: "#FFFF00")

                val opacity = if (annotMap.hasKey("opacity")) annotMap.getDouble("opacity").toFloat() else 0.5f

                when (type) {
                    "highlight" -> {
                        val rects = annotMap.getArray("rects") ?: continue
                        for (j in 0 until rects.size()) {
                            val rect = rects.getMap(j) ?: continue
                            val x = rect.getDouble("x").toFloat()
                            val y = rect.getDouble("y").toFloat()
                            val w = rect.getDouble("width").toFloat()
                            val h = rect.getDouble("height").toFloat()

                            // Convert normalized to PDF coordinates (bottom-left origin)
                            val pdfX = x * mediaBox.width
                            val pdfY = (1f - y - h) * mediaBox.height
                            val pdfW = w * mediaBox.width
                            val pdfH = h * mediaBox.height

                            val annotation = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
                            annotation.rectangle = PDRectangle(pdfX, pdfY, pdfW, pdfH)
                            annotation.setColor(com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(
                                colorArray, com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
                            ))

                            // Set quad points for highlight
                            val quadPoints = floatArrayOf(
                                pdfX, pdfY + pdfH,           // bottom-left
                                pdfX + pdfW, pdfY + pdfH,    // bottom-right
                                pdfX, pdfY,                   // top-left
                                pdfX + pdfW, pdfY             // top-right
                            )
                            annotation.quadPoints = quadPoints

                            page.annotations.add(annotation)

                            // Draw highlight into content stream so Android PdfRenderer shows it
                            val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                            cs.saveGraphicsState()
                            val gs = PDExtendedGraphicsState()
                            gs.nonStrokingAlphaConstant = opacity
                            cs.setGraphicsStateParameters(gs)
                            cs.setNonStrokingColor(
                                (colorArray[0] * 255).toInt(),
                                (colorArray[1] * 255).toInt(),
                                (colorArray[2] * 255).toInt()
                            )
                            cs.addRect(pdfX, pdfY, pdfW, pdfH)
                            cs.fill()
                            cs.restoreGraphicsState()
                            cs.close()
                        }
                    }

                    "note" -> {
                        val x = if (annotMap.hasKey("x")) annotMap.getDouble("x").toFloat() else 0.5f
                        val y = if (annotMap.hasKey("y")) annotMap.getDouble("y").toFloat() else 0.5f
                        val text = annotMap.getString("text") ?: ""

                        val pdfX = x * mediaBox.width
                        val pdfY = (1f - y) * mediaBox.height - 24

                        val annotation = PDAnnotationText()
                        annotation.rectangle = PDRectangle(pdfX, pdfY, 24f, 24f)
                        annotation.contents = text
                        annotation.setColor(com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(
                            colorArray, com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
                        ))

                        page.annotations.add(annotation)

                        // Draw note icon into content stream so Android PdfRenderer shows it
                        val noteSize = 24f
                        val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                        cs.saveGraphicsState()

                        // Filled circle as note marker
                        cs.setNonStrokingColor(
                            (colorArray[0] * 255).toInt(),
                            (colorArray[1] * 255).toInt(),
                            (colorArray[2] * 255).toInt()
                        )
                        val cx = pdfX + noteSize / 2
                        val cy = pdfY + noteSize / 2
                        val r = noteSize / 2
                        // Approximate circle with 4 bezier curves
                        val k = 0.5523f * r
                        cs.moveTo(cx + r, cy)
                        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
                        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
                        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
                        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
                        cs.fill()

                        // White "N" letter inside
                        cs.setNonStrokingColor(255, 255, 255)
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA_BOLD, noteSize * 0.6f)
                        cs.newLineAtOffset(cx - noteSize * 0.17f, cy - noteSize * 0.2f)
                        cs.showText("N")
                        cs.endText()

                        cs.restoreGraphicsState()
                        cs.close()
                    }

                    "freehand" -> {
                        // Freehand/ink annotation via PDAnnotationMarkup
                        val points = annotMap.getArray("points") ?: continue
                        if (points.size() < 2) continue

                        // Build path coordinates
                        var minX = Float.MAX_VALUE
                        var minY = Float.MAX_VALUE
                        var maxX = Float.MIN_VALUE
                        var maxY = Float.MIN_VALUE

                        val pdfPoints = mutableListOf<FloatArray>()
                        for (j in 0 until points.size()) {
                            val pt = points.getArray(j) ?: continue
                            val px = pt.getDouble(0).toFloat() * mediaBox.width
                            val py = (1f - pt.getDouble(1).toFloat()) * mediaBox.height

                            pdfPoints.add(floatArrayOf(px, py))
                            minX = minOf(minX, px)
                            minY = minOf(minY, py)
                            maxX = maxOf(maxX, px)
                            maxY = maxOf(maxY, py)
                        }

                        // Use a text markup annotation as a simple approximation for ink
                        // (Full ink annotation support requires PDAnnotationInk which may vary by pdfbox version)
                        val annotation = PDAnnotationMarkup()
                        annotation.rectangle = PDRectangle(minX - 5, minY - 5, maxX - minX + 10, maxY - minY + 10)
                        annotation.contents = "Freehand drawing"
                        annotation.setColor(com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor(
                            colorArray, com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB.INSTANCE
                        ))

                        page.annotations.add(annotation)

                        // Draw freehand path into content stream
                        if (pdfPoints.size >= 2) {
                            val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                            cs.saveGraphicsState()
                            cs.setStrokingColor(
                                (colorArray[0] * 255).toInt(),
                                (colorArray[1] * 255).toInt(),
                                (colorArray[2] * 255).toInt()
                            )
                            cs.setLineWidth(2f)
                            cs.moveTo(pdfPoints[0][0], pdfPoints[0][1])
                            for (k in 1 until pdfPoints.size) {
                                cs.lineTo(pdfPoints[k][0], pdfPoints[k][1])
                            }
                            cs.stroke()
                            cs.restoreGraphicsState()
                            cs.close()
                        }
                    }
                }
            }

            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
            doc.save(outputFile)
            doc.close()

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
            })
        } catch (e: Exception) {
            promise.reject("ANNOTATION_FAILED", e.message, e)
        }
    }

    fun getAnnotations(pdfUrl: String, promise: Promise) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)
            val annotations = WritableNativeArray()

            for (pageIdx in 0 until doc.numberOfPages) {
                val page = doc.getPage(pageIdx)
                val mediaBox = page.mediaBox

                for (annotation in page.annotations) {
                    // Skip form widgets
                    val subType = annotation.subtype ?: continue
                    if (subType == "Widget" || subType == "Link") continue

                    val rect = annotation.rectangle ?: continue
                    val normalizedX = rect.lowerLeftX / mediaBox.width
                    val normalizedY = 1f - (rect.upperRightY / mediaBox.height)
                    val normalizedW = rect.width / mediaBox.width
                    val normalizedH = rect.height / mediaBox.height

                    val type = when (subType) {
                        "Highlight" -> "highlight"
                        "Text" -> "note"
                        "Ink" -> "freehand"
                        "Underline" -> "underline"
                        "StrikeOut" -> "strikethrough"
                        else -> subType
                    }

                    val colorHex = annotation.color?.components?.let { c ->
                        if (c.size >= 3) {
                            String.format("#%02X%02X%02X", (c[0] * 255).toInt(), (c[1] * 255).toInt(), (c[2] * 255).toInt())
                        } else "#000000"
                    } ?: "#000000"

                    annotations.pushMap(WritableNativeMap().apply {
                        putString("id", "${pageIdx}_${rect.lowerLeftX}_${rect.lowerLeftY}_${type}")
                        putString("type", type)
                        putInt("pageIndex", pageIdx)
                        putString("color", colorHex)
                        putDouble("x", normalizedX.toDouble())
                        putDouble("y", normalizedY.toDouble())
                        putDouble("width", normalizedW.toDouble())
                        putDouble("height", normalizedH.toDouble())
                        putString("text", annotation.contents ?: "")
                    })
                }
            }

            doc.close()
            promise.resolve(WritableNativeMap().apply {
                putArray("annotations", annotations)
            })
        } catch (e: Exception) {
            promise.reject("ANNOTATION_FAILED", e.message, e)
        }
    }

    fun deleteAnnotation(pdfUrl: String, annotationId: String, tempDir: File, promise: Promise) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)
            var found = false

            for (pageIdx in 0 until doc.numberOfPages) {
                val page = doc.getPage(pageIdx)
                val mediaBox = page.mediaBox
                val iterator = page.annotations.iterator()

                while (iterator.hasNext()) {
                    val annotation = iterator.next()
                    val subType = annotation.subtype ?: continue
                    if (subType == "Widget" || subType == "Link") continue

                    val rect = annotation.rectangle ?: continue
                    val type = when (subType) {
                        "Highlight" -> "highlight"
                        "Text" -> "note"
                        "Ink" -> "freehand"
                        "Underline" -> "underline"
                        "StrikeOut" -> "strikethrough"
                        else -> subType
                    }

                    val id = "${pageIdx}_${rect.lowerLeftX}_${rect.lowerLeftY}_${type}"
                    if (id == annotationId) {
                        iterator.remove()
                        found = true
                        break
                    }
                }
                if (found) break
            }

            if (!found) {
                doc.close()
                promise.reject("ANNOTATION_FAILED", "Annotation not found: $annotationId")
                return
            }

            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
            doc.save(outputFile)
            doc.close()

            promise.resolve(WritableNativeMap().apply {
                putString("pdfUrl", "file://${outputFile.absolutePath}")
            })
        } catch (e: Exception) {
            promise.reject("ANNOTATION_FAILED", e.message, e)
        }
    }

    // MARK: - Helpers

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }

    private fun parseColor(hex: String): FloatArray {
        val cleanHex = hex.removePrefix("#")
        val colorInt = cleanHex.toLong(16)
        return floatArrayOf(
            ((colorInt shr 16) and 0xFF).toFloat() / 255f,
            ((colorInt shr 8) and 0xFF).toFloat() / 255f,
            (colorInt and 0xFF).toFloat() / 255f
        )
    }
}
