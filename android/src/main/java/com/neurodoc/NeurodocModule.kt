package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class NeurodocModule(reactContext: ReactApplicationContext) :
    NativeNeurodocSpec(reactContext) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tempDir: File by lazy {
        File(reactApplicationContext.cacheDir, "neurodoc").also { it.mkdirs() }
    }

    private val picker = DocumentPickerHelper(reactApplicationContext, tempDir)

    init {
        PDFBoxResourceLoader.init(reactContext)
        reactContext.addActivityEventListener(picker)
    }

    override fun invalidate() {
        scope.cancel()
        reactApplicationContext.removeActivityEventListener(picker)
        super.invalidate()
    }

    // MARK: - getMetadata

    override fun getMetadata(pdfUrl: String, promise: Promise) {
        scope.launch {
            try {
                val file = PdfUtils.resolveFile(pdfUrl)
                PDDocument.load(file).use { doc ->
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
                    promise.resolve(result)
                }
            } catch (e: Exception) {
                promise.reject("PDF_LOAD_FAILED", e.message, e)
            }
        }
    }

    // MARK: - recognizePage

    override fun recognizePage(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("OCR_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val pageIndex = options.getInt("pageIndex")
                val language = options.getStringOrNull("language") ?: "auto"

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("OCR_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val language = options.getStringOrNull("language") ?: "auto"
                val pageIndexes = options.getIntListOrNull("pageIndexes")

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
                val pdfUrls = options.getArrayOrNull("pdfUrls") ?: run {
                    promise.reject("MERGE_FAILED", "'pdfUrls' is required")
                    return@launch
                }
                val fileName = options.getStringOrNull("fileName") ?: "merged"

                val outputFile = File(tempDir, "$fileName.pdf")
                val merger = PDFMergerUtility()
                merger.destinationFileName = outputFile.absolutePath

                for (i in 0 until pdfUrls.size()) {
                    val urlStr = pdfUrls.getString(i) ?: continue
                    merger.addSource(PdfUtils.resolveFile(urlStr))
                }

                merger.mergeDocuments(null)

                val pageCount = PDDocument.load(outputFile).use { it.numberOfPages }

                promise.resolve(WritableNativeMap().apply {
                    putString("pdfUrl", "file://${outputFile.absolutePath}")
                    putInt("pageCount", pageCount)
                    putDouble("fileSize", outputFile.length().toDouble())
                })
            } catch (e: Exception) {
                promise.reject("MERGE_FAILED", e.message, e)
            }
        }
    }

    // MARK: - split

    override fun split(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("SPLIT_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val ranges = options.getArrayOrNull("ranges") ?: run {
                    promise.reject("SPLIT_FAILED", "'ranges' is required")
                    return@launch
                }

                val file = PdfUtils.resolveFile(pdfUrl)
                val outputUrls = WritableNativeArray()

                PDDocument.load(file).use { doc ->
                    for (i in 0 until ranges.size()) {
                        val range = ranges.getArray(i) ?: continue
                        val start = range.getInt(0)
                        val end = range.getInt(1)

                        PDDocument().use { splitDoc ->
                            for (j in start..minOf(end, doc.numberOfPages - 1)) {
                                splitDoc.addPage(doc.getPage(j))
                            }
                            val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")
                            splitDoc.save(outputFile)
                            outputUrls.pushString("file://${outputFile.absolutePath}")
                        }
                    }
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("PAGE_OPERATION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val pageIndexes = options.getArrayOrNull("pageIndexes") ?: run {
                    promise.reject("PAGE_OPERATION_FAILED", "'pageIndexes' is required")
                    return@launch
                }
                val indicesToDelete = (0 until pageIndexes.size()).map { pageIndexes.getInt(it) }.toSet()

                val file = PdfUtils.resolveFile(pdfUrl)
                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")

                PDDocument.load(file).use { doc ->
                    val sorted = indicesToDelete.sortedDescending()
                    for (idx in sorted) {
                        if (idx < doc.numberOfPages) {
                            doc.removePage(idx)
                        }
                    }
                    doc.save(outputFile)
                    promise.resolve(WritableNativeMap().apply {
                        putString("pdfUrl", "file://${outputFile.absolutePath}")
                        putInt("pageCount", doc.numberOfPages)
                    })
                }
            } catch (e: Exception) {
                promise.reject("PAGE_OPERATION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - reorderPages

    override fun reorderPages(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("PAGE_OPERATION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val order = options.getArrayOrNull("order") ?: run {
                    promise.reject("PAGE_OPERATION_FAILED", "'order' is required")
                    return@launch
                }

                val file = PdfUtils.resolveFile(pdfUrl)
                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")

                PDDocument.load(file).use { srcDoc ->
                    PDDocument().use { newDoc ->
                        for (i in 0 until order.size()) {
                            val oldIdx = order.getInt(i)
                            newDoc.addPage(srcDoc.getPage(oldIdx))
                        }
                        newDoc.save(outputFile)
                    }
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("ANNOTATION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val annotations = options.getArrayOrNull("annotations") ?: run {
                    promise.reject("ANNOTATION_FAILED", "'annotations' is required")
                    return@launch
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("ANNOTATION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val annotationId = options.getStringOrNull("annotationId") ?: run {
                    promise.reject("ANNOTATION_FAILED", "'annotationId' is required")
                    return@launch
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("FORM_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val fields = options.getArrayOrNull("fields") ?: run {
                    promise.reject("FORM_FAILED", "'fields' is required")
                    return@launch
                }
                val flatten = options.getBooleanOrDefault("flattenAfterFill")

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("ENCRYPTION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val userPassword = options.getStringOrNull("userPassword") ?: run {
                    promise.reject("ENCRYPTION_FAILED", "'userPassword' is required")
                    return@launch
                }
                val ownerPassword = options.getStringOrNull("ownerPassword") ?: run {
                    promise.reject("ENCRYPTION_FAILED", "'ownerPassword' is required")
                    return@launch
                }
                val allowPrinting = options.getBooleanOrDefault("allowPrinting", true)
                val allowCopying = options.getBooleanOrDefault("allowCopying", true)

                val file = PdfUtils.resolveFile(pdfUrl)
                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")

                PDDocument.load(file).use { doc ->
                    val permissions = AccessPermission().apply {
                        setCanPrint(allowPrinting)
                        setCanExtractContent(allowCopying)
                    }
                    val policy = StandardProtectionPolicy(ownerPassword, userPassword, permissions)
                    policy.encryptionKeyLength = 256
                    doc.protect(policy)
                    doc.save(outputFile)
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("ENCRYPTION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val password = options.getStringOrNull("password") ?: run {
                    promise.reject("ENCRYPTION_FAILED", "'password' is required")
                    return@launch
                }

                val file = PdfUtils.resolveFile(pdfUrl)
                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")

                PDDocument.load(file, password).use { doc ->
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(outputFile)
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("WATERMARK_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val text = options.getStringOrNull("text")
                val imageUrl = options.getStringOrNull("imageUrl")
                val opacity = options.getFloatOrDefault("opacity", 0.3f)
                val angle = options.getFloatOrDefault("angle", 45f)
                val fontSize = options.getFloatOrDefault("fontSize", 48f)
                val color = options.getStringOrNull("color") ?: "#FF0000"
                val pageIndexes = options.getIntListOrNull("pageIndexes")?.toSet()

                val file = PdfUtils.resolveFile(pdfUrl)
                val outputFile = File(tempDir, "${UUID.randomUUID()}.pdf")

                PDDocument.load(file).use { doc ->
                    val rgb = parseColor(color)

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

                            val centerX = mediaBox.width / 2
                            val centerY = mediaBox.height / 2

                            val radians = Math.toRadians(angle.toDouble())
                            val cos = Math.cos(radians).toFloat()
                            val sin = Math.sin(radians).toFloat()

                            cs.transform(com.tom_roush.pdfbox.util.Matrix(cos, sin, -sin, cos, centerX, centerY))

                            cs.beginText()
                            cs.setFont(font, fontSize)
                            cs.setNonStrokingColor(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f)
                            cs.newLineAtOffset(-textWidth / 2, -textHeight / 2)
                            cs.showText(text)
                            cs.endText()

                            cs.restoreGraphicsState()
                        }

                        if (imageUrl != null) {
                            val imgFile = PdfUtils.resolveFile(imageUrl)
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

                    doc.save(outputFile)
                }

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("REDACTION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val redactions = options.getArrayOrNull("redactions") ?: run {
                    promise.reject("REDACTION_FAILED", "'redactions' is required")
                    return@launch
                }
                val dpi = options.getFloatOrDefault("dpi", 300f)
                val stripMetadata = options.getBooleanOrDefault("stripMetadata")

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("CONTENT_EDIT_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val edits = options.getArrayOrNull("edits") ?: run {
                    promise.reject("CONTENT_EDIT_FAILED", "'edits' is required")
                    return@launch
                }

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
                val templateJson = options.getStringOrNull("templateJson") ?: run {
                    promise.reject("TEMPLATE_FAILED", "'templateJson' is required")
                    return@launch
                }
                val dataJson = options.getStringOrNull("dataJson") ?: run {
                    promise.reject("TEMPLATE_FAILED", "'dataJson' is required")
                    return@launch
                }
                val fileName = options.getStringOrNull("fileName")

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("TEXT_EXTRACTION_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val pageIndex = options.getInt("pageIndex")
                val mode = options.getStringOrNull("mode") ?: "auto"
                val language = options.getStringOrNull("language") ?: "auto"

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
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("FORM_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val fields = options.getArrayOrNull("fields") ?: run {
                    promise.reject("FORM_FAILED", "'fields' is required")
                    return@launch
                }
                val removeOriginalText = options.getBooleanOrDefault("removeOriginalText", true)

                FormCreator.createFormFromPdf(pdfUrl, fields, removeOriginalText, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("FORM_FAILED", e.message, e)
            }
        }
    }

    // MARK: - getBookmarks

    override fun getBookmarks(pdfUrl: String, promise: Promise) {
        scope.launch {
            try {
                BookmarkProcessor.getBookmarks(pdfUrl, promise)
            } catch (e: Exception) {
                promise.reject("BOOKMARK_FAILED", e.message, e)
            }
        }
    }

    // MARK: - addBookmarks

    override fun addBookmarks(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("BOOKMARK_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val bookmarks = options.getArrayOrNull("bookmarks") ?: run {
                    promise.reject("BOOKMARK_FAILED", "'bookmarks' is required")
                    return@launch
                }

                BookmarkProcessor.addBookmarks(pdfUrl, bookmarks, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("BOOKMARK_FAILED", e.message, e)
            }
        }
    }

    // MARK: - removeBookmarks

    override fun removeBookmarks(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl = options.getStringOrNull("pdfUrl") ?: run {
                    promise.reject("BOOKMARK_FAILED", "'pdfUrl' is required")
                    return@launch
                }
                val indexes = options.getArrayOrNull("indexes") ?: run {
                    promise.reject("BOOKMARK_FAILED", "'indexes' is required")
                    return@launch
                }

                BookmarkProcessor.removeBookmarks(pdfUrl, indexes, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("BOOKMARK_FAILED", e.message, e)
            }
        }
    }

    // MARK: - convertDocxToPdf

    override fun convertDocxToPdf(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val inputPath = options.getStringOrNull("inputPath") ?: run {
                    promise.reject("CONVERSION_FAILED", "'inputPath' is required")
                    return@launch
                }
                val preserveImages = options.getBooleanOrDefault("preserveImages", true)
                val pageSize = options.getStringOrNull("pageSize") ?: "A4"

                DocxConverter.convertDocxToPdf(inputPath, preserveImages, pageSize, tempDir, promise)
            } catch (e: Exception) {
                promise.reject("CONVERSION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - convertPdfToDocx

    override fun convertPdfToDocx(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val inputPath = options.getStringOrNull("inputPath") ?: run {
                    promise.reject("CONVERSION_FAILED", "'inputPath' is required")
                    return@launch
                }
                val mode = options.getStringOrNull("mode") ?: "textAndImages"
                val language = options.getStringOrNull("language") ?: "auto"

                DocxConverter.convertPdfToDocx(
                    reactApplicationContext, inputPath, mode, language, tempDir, promise
                )
            } catch (e: Exception) {
                promise.reject("CONVERSION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - pickDocument

    override fun pickDocument(promise: Promise) = picker.pickDocument(promise)

    // MARK: - pickFile

    override fun pickFile(types: ReadableArray, promise: Promise) = picker.pickFile(types, promise)

    // MARK: - comparePdfs

    override fun comparePdfs(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl1 = options.getStringOrNull("pdfUrl1") ?: run {
                    promise.reject("COMPARISON_FAILED", "'pdfUrl1' is required")
                    return@launch
                }
                val pdfUrl2 = options.getStringOrNull("pdfUrl2") ?: run {
                    promise.reject("COMPARISON_FAILED", "'pdfUrl2' is required")
                    return@launch
                }
                val addedColor = options.getStringOrNull("addedColor") ?: "#00CC00"
                val deletedColor = options.getStringOrNull("deletedColor") ?: "#FF4444"
                val changedColor = options.getStringOrNull("changedColor") ?: "#FFAA00"
                val opacity = options.getFloatOrDefault("opacity", 0.35f)
                val annotateSource = options.getBooleanOrDefault("annotateSource", true)
                val annotateTarget = options.getBooleanOrDefault("annotateTarget", true)

                DiffProcessor.comparePdfs(
                    pdfUrl1, pdfUrl2,
                    addedColor, deletedColor, changedColor,
                    opacity, annotateSource, annotateTarget,
                    tempDir, promise
                )
            } catch (e: Exception) {
                promise.reject("COMPARISON_FAILED", e.message, e)
            }
        }
    }

    // MARK: - cleanupTempFiles

    override fun cleanupTempFiles(promise: Promise) {
        scope.launch {
            try {
                if (tempDir.exists()) {
                    tempDir.deleteRecursively()
                }
                tempDir.mkdirs()
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("CLEANUP_FAILED", e.message, e)
            }
        }
    }

    // MARK: - saveTo

    override fun saveTo(pdfUrl: String, fileName: String, promise: Promise) =
        picker.saveTo(pdfUrl, fileName, promise)

    // MARK: - Helpers

    private fun parseColor(hex: String): FloatArray {
        val cleanHex = hex.removePrefix("#")
        return try {
            val colorInt = cleanHex.toLong(16)
            floatArrayOf(
                ((colorInt shr 16) and 0xFF).toFloat(),
                ((colorInt shr 8) and 0xFF).toFloat(),
                (colorInt and 0xFF).toFloat()
            )
        } catch (_: Exception) {
            floatArrayOf(255f, 0f, 0f)
        }
    }

    companion object {
        const val NAME = NativeNeurodocSpec.NAME
    }
}
