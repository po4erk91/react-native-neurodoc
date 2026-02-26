package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class NeurodocModule(reactContext: ReactApplicationContext) :
    NativeNeurodocSpec(reactContext) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tempDir: File
        get() {
            val dir = File(reactApplicationContext.cacheDir, "neurodoc")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    init {
        PDFBoxResourceLoader.init(reactContext)
    }

    // MARK: - getMetadata

    override fun getMetadata(pdfUrl: String, promise: Promise) {
        scope.launch {
            try {
                val file = resolveFile(pdfUrl)
                val doc = PDDocument.load(file)
                val info = doc.documentInformation

                val result = WritableNativeMap().apply {
                    putInt("pageCount", doc.numberOfPages)
                    putString("title", info?.title ?: "")
                    putString("author", info?.author ?: "")
                    putString("creationDate", info?.creationDate?.time?.let {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(it)
                    } ?: "")
                    putDouble("fileSize", file.length().toDouble())
                    putBoolean("isEncrypted", doc.isEncrypted)
                    putBoolean("isSigned", doc.signatureDictionaries?.isNotEmpty() == true)
                }

                doc.close()
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("PDF_LOAD_FAILED", e.message, e)
            }
        }
    }

    // MARK: - recognizePage

    override fun recognizePage(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val pageIndex = options.getInt("pageIndex")
                val language = options.getString("language") ?: "auto"

                OcrProcessor.recognizePage(reactApplicationContext, pdfUrl, pageIndex, language, promise)
            } catch (e: Exception) {
                promise.reject("OCR_FAILED", e.message, e)
            }
        }
    }

    // MARK: - makeSearchable

    override fun makeSearchable(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val language = options.getString("language") ?: "auto"
                val pageIndexes = if (options.hasKey("pageIndexes") && !options.isNull("pageIndexes")) {
                    val arr = options.getArray("pageIndexes")!!
                    (0 until arr.size()).map { arr.getInt(it) }
                } else null

                OcrProcessor.makeSearchable(reactApplicationContext, pdfUrl, language, pageIndexes, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("OCR_FAILED", e.message, e)
            }
        }
    }

    // MARK: - merge

    override fun merge(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrls = options.getArray("pdfUrls")!!
                val fileName = if (options.hasKey("fileName") && !options.isNull("fileName"))
                    options.getString("fileName") else "merged"

                val outputFile = File(tempDir, "${fileName}.pdf")
                val merger = PDFMergerUtility()
                merger.destinationFileName = outputFile.absolutePath

                for (i in 0 until pdfUrls.size()) {
                    val file = resolveFile(pdfUrls.getString(i)!!)
                    merger.addSource(file)
                }

                merger.mergeDocuments(null)

                val doc = PDDocument.load(outputFile)
                val pageCount = doc.numberOfPages
                doc.close()

                val result = WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                    putInt("pageCount", pageCount)
                    putDouble("fileSize", outputFile.length().toDouble())
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("MERGE_FAILED", e.message, e)
            }
        }
    }

    // MARK: - split

    override fun split(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val ranges = options.getArray("ranges")!!

                val file = resolveFile(pdfUrl)
                val doc = PDDocument.load(file)
                val outputUrls = WritableNativeArray()

                for (i in 0 until ranges.size()) {
                    val range = ranges.getArray(i)!!
                    val start = range.getInt(0)
                    val end = range.getInt(1)

                    val splitDoc = PDDocument()
                    for (j in start..minOf(end, doc.numberOfPages - 1)) {
                        splitDoc.addPage(doc.getPage(j))
                    }

                    val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                    splitDoc.save(outputFile)
                    splitDoc.close()

                    outputUrls.pushString("file://${outputFile.absolutePath}")
                }

                doc.close()
                promise.resolve(WritableNativeMap().apply {
                    putArray("pdfUrls", outputUrls)
                })
            } catch (e: Exception) {
                promise.reject("SPLIT_FAILED", e.message, e)
            }
        }
    }

    // MARK: - deletePages

    override fun deletePages(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val pageIndexes = options.getArray("pageIndexes")!!
                val indicesToDelete = (0 until pageIndexes.size()).map { pageIndexes.getInt(it) }.toSet()

                val file = resolveFile(pdfUrl)
                val doc = PDDocument.load(file)

                // Remove pages in reverse order to maintain indices
                val sorted = indicesToDelete.sortedDescending()
                for (idx in sorted) {
                    if (idx < doc.numberOfPages) {
                        doc.removePage(idx)
                    }
                }

                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                doc.save(outputFile)
                val pageCount = doc.numberOfPages
                doc.close()

                promise.resolve(WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                    putInt("pageCount", pageCount)
                })
            } catch (e: Exception) {
                promise.reject("PAGE_OPERATION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - reorderPages

    override fun reorderPages(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val order = options.getArray("order")!!

                val file = resolveFile(pdfUrl)
                val srcDoc = PDDocument.load(file)
                val newDoc = PDDocument()

                for (i in 0 until order.size()) {
                    val oldIdx = order.getInt(i)
                    newDoc.addPage(srcDoc.getPage(oldIdx))
                }

                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                newDoc.save(outputFile)
                newDoc.close()
                srcDoc.close()

                promise.resolve(WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                })
            } catch (e: Exception) {
                promise.reject("PAGE_OPERATION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - addAnnotations

    override fun addAnnotations(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val annotations = options.getArray("annotations")!!

                AnnotationProcessor.addAnnotations(pdfUrl, annotations, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("ANNOTATION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - getAnnotations

    override fun getAnnotations(pdfUrl: String, promise: Promise) {
        scope.launch {
            try {
                AnnotationProcessor.getAnnotations(pdfUrl, promise)
            } catch (e: Exception) {
                promise.reject("ANNOTATION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - deleteAnnotation

    override fun deleteAnnotation(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val annotationId = options.getString("annotationId")!!

                AnnotationProcessor.deleteAnnotation(pdfUrl, annotationId, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("ANNOTATION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - getFormFields

    override fun getFormFields(pdfUrl: String, promise: Promise) {
        scope.launch {
            try {
                FormProcessor.getFormFields(pdfUrl, promise)
            } catch (e: Exception) {
                promise.reject("FORM_FAILED", e.message, e)
            }
        }
    }

    // MARK: - fillForm

    override fun fillForm(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val fields = options.getArray("fields")!!
                val flatten = if (options.hasKey("flattenAfterFill")) options.getBoolean("flattenAfterFill") else false

                FormProcessor.fillForm(pdfUrl, fields, flatten, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("FORM_FAILED", e.message, e)
            }
        }
    }

    // MARK: - encrypt

    override fun encrypt(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val userPassword = options.getString("userPassword")!!
                val ownerPassword = options.getString("ownerPassword")!!
                val allowPrinting = if (options.hasKey("allowPrinting")) options.getBoolean("allowPrinting") else true
                val allowCopying = if (options.hasKey("allowCopying")) options.getBoolean("allowCopying") else true

                val file = resolveFile(pdfUrl)
                val doc = PDDocument.load(file)

                val permissions = AccessPermission().apply {
                    setCanPrint(allowPrinting)
                    setCanExtractContent(allowCopying)
                }

                val policy = StandardProtectionPolicy(ownerPassword, userPassword, permissions)
                policy.encryptionKeyLength = 256
                doc.protect(policy)

                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                doc.save(outputFile)
                doc.close()

                promise.resolve(WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                })
            } catch (e: Exception) {
                promise.reject("ENCRYPTION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - decrypt

    override fun decrypt(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val password = options.getString("password")!!

                val file = resolveFile(pdfUrl)
                val doc = PDDocument.load(file, password)

                doc.isAllSecurityToBeRemoved = true

                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                doc.save(outputFile)
                doc.close()

                promise.resolve(WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                })
            } catch (e: Exception) {
                promise.reject("ENCRYPTION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - addWatermark

    override fun addWatermark(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val text = if (options.hasKey("text") && !options.isNull("text")) options.getString("text") else null
                val imageUrl = if (options.hasKey("imageUrl") && !options.isNull("imageUrl")) options.getString("imageUrl") else null
                val opacity = if (options.hasKey("opacity")) options.getDouble("opacity").toFloat() else 0.3f
                val angle = if (options.hasKey("angle")) options.getDouble("angle").toFloat() else 45f
                val fontSize = if (options.hasKey("fontSize")) options.getDouble("fontSize").toFloat() else 48f
                val color = if (options.hasKey("color")) options.getString("color") else "#FF0000"
                val pageIndexes = if (options.hasKey("pageIndexes") && !options.isNull("pageIndexes")) {
                    val arr = options.getArray("pageIndexes")!!
                    (0 until arr.size()).map { arr.getInt(it) }.toSet()
                } else null

                val file = resolveFile(pdfUrl)
                val doc = PDDocument.load(file)

                val rgb = parseColor(color ?: "#FF0000")

                for (i in 0 until doc.numberOfPages) {
                    if (pageIndexes != null && !pageIndexes.contains(i)) continue

                    val page = doc.getPage(i)
                    val mediaBox = page.mediaBox

                    val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)

                    val gs = PDExtendedGraphicsState()
                    gs.nonStrokingAlphaConstant = opacity
                    gs.strokingAlphaConstant = opacity
                    cs.setGraphicsStateParameters(gs)

                    if (text != null) {
                        val font = PDType1Font.HELVETICA_BOLD
                        val textWidth = font.getStringWidth(text) / 1000 * fontSize
                        val textHeight = fontSize

                        cs.saveGraphicsState()

                        // Move to center and rotate
                        val centerX = mediaBox.width / 2
                        val centerY = mediaBox.height / 2

                        val radians = Math.toRadians(angle.toDouble())
                        val cos = Math.cos(radians).toFloat()
                        val sin = Math.sin(radians).toFloat()

                        cs.transform(com.tom_roush.pdfbox.util.Matrix(cos, sin, -sin, cos, centerX, centerY))

                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.setNonStrokingColor(rgb[0].toInt(), rgb[1].toInt(), rgb[2].toInt())
                        cs.newLineAtOffset(-textWidth / 2, -textHeight / 2)
                        cs.showText(text)
                        cs.endText()

                        cs.restoreGraphicsState()
                    }

                    if (imageUrl != null) {
                        val imgFile = resolveFile(imageUrl)
                        if (imgFile.exists()) {
                            val pdImage = PDImageXObject.createFromFileByContent(imgFile, doc)
                            val imgW = minOf(pdImage.width.toFloat(), mediaBox.width * 0.5f)
                            val imgH = minOf(pdImage.height.toFloat(), mediaBox.height * 0.5f)
                            val imgX = (mediaBox.width - imgW) / 2
                            val imgY = (mediaBox.height - imgH) / 2

                            cs.drawImage(pdImage, imgX, imgY, imgW, imgH)
                        }
                    }

                    cs.close()
                }

                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                doc.save(outputFile)
                doc.close()

                promise.resolve(WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                })
            } catch (e: Exception) {
                promise.reject("WATERMARK_FAILED", e.message, e)
            }
        }
    }

    // MARK: - redact

    override fun redact(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val redactions = options.getArray("redactions")!!
                val dpi = if (options.hasKey("dpi") && !options.isNull("dpi"))
                    options.getDouble("dpi").toFloat() else 300f
                val stripMetadata = if (options.hasKey("stripMetadata") && !options.isNull("stripMetadata"))
                    options.getBoolean("stripMetadata") else false

                RedactionProcessor.redact(pdfUrl, redactions, dpi, stripMetadata, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("REDACTION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - editContent

    override fun editContent(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val edits = options.getArray("edits")!!

                ContentEditor.editContent(pdfUrl, edits, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("CONTENT_EDIT_FAILED", e.message, e)
            }
        }
    }

    // MARK: - generateFromTemplate

    override fun generateFromTemplate(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val templateJson = options.getString("templateJson")!!
                val dataJson = options.getString("dataJson")!!
                val fileName = if (options.hasKey("fileName") && !options.isNull("fileName"))
                    options.getString("fileName") else null

                TemplateProcessor.generate(templateJson, dataJson, fileName, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("TEMPLATE_FAILED", e.message, e)
            }
        }
    }

    // MARK: - extractText

    override fun extractText(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val pageIndex = options.getInt("pageIndex")
                val mode = if (options.hasKey("mode") && !options.isNull("mode"))
                    options.getString("mode")!! else "auto"
                val language = if (options.hasKey("language") && !options.isNull("language"))
                    options.getString("language")!! else "auto"

                TextExtractor.extractText(reactApplicationContext, pdfUrl, pageIndex, mode, language, promise)
            } catch (e: Exception) {
                promise.reject("TEXT_EXTRACTION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - createFormFromPdf

    override fun createFormFromPdf(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getString("pdfUrl")!!
                val fields = options.getArray("fields")!!
                val removeOriginalText = if (options.hasKey("removeOriginalText"))
                    options.getBoolean("removeOriginalText") else true

                FormCreator.createFormFromPdf(pdfUrl, fields, removeOriginalText, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("FORM_FAILED", e.message, e)
            }
        }
    }

    // MARK: - pickDocument

    override fun pickDocument(promise: Promise) {
        promise.reject("NOT_IMPLEMENTED", "pickDocument is not yet implemented on Android")
    }

    // MARK: - cleanupTempFiles

    override fun cleanupTempFiles(promise: Promise) {
        scope.launch {
            try {
                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                }
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("CLEANUP_FAILED", e.message, e)
            }
        }
    }

    // MARK: - Helpers

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) {
            urlString.removePrefix("file://")
        } else {
            urlString
        }
        return File(path)
    }

    private fun parseColor(hex: String): FloatArray {
        val cleanHex = hex.removePrefix("#")
        val colorInt = cleanHex.toLong(16)
        return floatArrayOf(
            ((colorInt shr 16) and 0xFF).toFloat(),
            ((colorInt shr 8) and 0xFF).toFloat(),
            (colorInt and 0xFF).toFloat()
        )
    }

    companion object {
        const val NAME = NativeNeurodocSpec.NAME
    }
}
