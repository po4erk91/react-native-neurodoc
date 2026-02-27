package com.neurodoc

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.BaseActivityEventListener
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
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class NeurodocModule(reactContext: ReactApplicationContext) :
    NativeNeurodocSpec(reactContext) {


    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pickerPromise: Promise? = null
    private var pickerResultKey: String = "pdfUrl"
    private var saveSourcePath: String? = null

    private val tempDir: File
        get() {
            val dir = File(reactApplicationContext.cacheDir, "neurodoc")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    private val activityEventListener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == SAVE_DOCUMENT_REQUEST) {
                handleSaveResult(resultCode, data)
                return
            }

            if (requestCode != PICK_DOCUMENT_REQUEST && requestCode != PICK_FILE_REQUEST) return

            val promise = pickerPromise ?: return
            pickerPromise = null

            if (resultCode != Activity.RESULT_OK) {
                promise.reject("PICKER_CANCELLED", "User cancelled document picker")
                return
            }

            val uri = data?.data
            if (uri == null) {
                promise.reject("PICKER_FAILED", "No file selected")
                return
            }

            try {
                // Copy to temp dir so we have a stable file:// URL
                val contentResolver = reactApplicationContext.contentResolver
                val ext = if (requestCode == PICK_DOCUMENT_REQUEST) ".pdf" else ""
                val outputFile = File(tempDir, "${UUID.randomUUID()}$ext")

                contentResolver.openInputStream(uri)?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    promise.reject("PICKER_FAILED", "Could not read selected file")
                    return
                }

                val result = WritableNativeMap().apply {
                    putString(pickerResultKey, "file://${outputFile.absolutePath}")
                }
                promise.resolve(result)
            } catch (e: Exception) {
                promise.reject("PICKER_FAILED", "Failed to process selected file: ${e.message}", e)
            }
        }
    }

    private fun handleSaveResult(resultCode: Int, data: Intent?) {
        val promise = pickerPromise ?: return
        pickerPromise = null
        val sourcePath = saveSourcePath
        saveSourcePath = null

        if (resultCode != Activity.RESULT_OK) {
            promise.reject("SAVE_FAILED", "User cancelled save")
            return
        }

        val uri = data?.data
        if (uri == null) {
            promise.reject("SAVE_FAILED", "No destination selected")
            return
        }

        if (sourcePath == null) {
            promise.reject("SAVE_FAILED", "Source file path lost")
            return
        }

        try {
            val sourceFile = resolveFile(sourcePath)
            val contentResolver = reactApplicationContext.contentResolver
            contentResolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: run {
                promise.reject("SAVE_FAILED", "Failed to open output stream")
                return
            }

            promise.resolve(WritableNativeMap().apply {
                putString("savedPath", uri.toString())
            })
        } catch (e: Exception) {
            promise.reject("SAVE_FAILED", "Failed to save PDF: ${e.message}", e)
        }
    }

    init {
        PDFBoxResourceLoader.init(reactContext)
        reactContext.addActivityEventListener(activityEventListener)
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
                val pdfUrl = options.getString("pdfUrl")!!
                val bookmarks = options.getArray("bookmarks")!!

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
                val pdfUrl = options.getString("pdfUrl")!!
                val indexes = options.getArray("indexes")!!

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
                val inputPath = options.getString("inputPath")!!
                val preserveImages = if (options.hasKey("preserveImages"))
                    options.getBoolean("preserveImages") else true
                val pageSize = if (options.hasKey("pageSize") && !options.isNull("pageSize"))
                    options.getString("pageSize")!! else "A4"

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
                val inputPath = options.getString("inputPath")!!
                val mode = if (options.hasKey("mode") && !options.isNull("mode"))
                    options.getString("mode")!! else "textAndImages"
                val language = if (options.hasKey("language") && !options.isNull("language"))
                    options.getString("language")!! else "auto"

                DocxConverter.convertPdfToDocx(
                    reactApplicationContext, inputPath, mode, language, tempDir, promise
                )
            } catch (e: Exception) {
                promise.reject("CONVERSION_FAILED", e.message, e)
            }
        }
    }

    // MARK: - pickDocument

    override fun pickDocument(promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("PICKER_FAILED", "No current activity")
            return
        }

        pickerPromise = promise
        pickerResultKey = "pdfUrl"

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        activity.startActivityForResult(intent, PICK_DOCUMENT_REQUEST)
    }

    // MARK: - pickFile

    override fun pickFile(types: ReadableArray, promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("PICKER_FAILED", "No current activity")
            return
        }

        pickerPromise = promise
        pickerResultKey = "fileUrl"

        val mimeTypes = mutableListOf<String>()
        for (i in 0 until types.size()) {
            val t = types.getString(i) ?: continue
            // Map common UTType identifiers to MIME types
            val mime = when (t) {
                "org.openxmlformats.wordprocessingml.document" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "com.microsoft.word.doc" -> "application/msword"
                "com.adobe.pdf" -> "application/pdf"
                "public.plain-text" -> "text/plain"
                "public.image" -> "image/*"
                "public.png" -> "image/png"
                "public.jpeg" -> "image/jpeg"
                else -> t // assume it's already a MIME type
            }
            mimeTypes.add(mime)
        }

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (mimeTypes.size == 1) {
                type = mimeTypes[0]
            } else {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
        }
        activity.startActivityForResult(intent, PICK_FILE_REQUEST)
    }

    // MARK: - comparePdfs

    override fun comparePdfs(options: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val pdfUrl1 = options.getString("pdfUrl1")!!
                val pdfUrl2 = options.getString("pdfUrl2")!!
                val addedColor   = if (options.hasKey("addedColor") && !options.isNull("addedColor"))
                    options.getString("addedColor")!! else "#00CC00"
                val deletedColor = if (options.hasKey("deletedColor") && !options.isNull("deletedColor"))
                    options.getString("deletedColor")!! else "#FF4444"
                val changedColor = if (options.hasKey("changedColor") && !options.isNull("changedColor"))
                    options.getString("changedColor")!! else "#FFAA00"
                val opacity = if (options.hasKey("opacity")) options.getDouble("opacity").toFloat() else 0.35f
                val annotateSource = if (options.hasKey("annotateSource")) options.getBoolean("annotateSource") else true
                val annotateTarget = if (options.hasKey("annotateTarget")) options.getBoolean("annotateTarget") else true

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
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("CLEANUP_FAILED", e.message, e)
            }
        }
    }

    // MARK: - saveTo

    override fun saveTo(pdfUrl: String, fileName: String, promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("SAVE_FAILED", "No current activity")
            return
        }

        val sourceFile = resolveFile(pdfUrl)
        if (!sourceFile.exists()) {
            promise.reject("SAVE_FAILED", "Source file does not exist: $pdfUrl")
            return
        }

        pickerPromise = promise
        saveSourcePath = pdfUrl

        val safeName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, safeName)
        }
        activity.startActivityForResult(intent, SAVE_DOCUMENT_REQUEST)
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
        private const val PICK_DOCUMENT_REQUEST = 1001
        private const val PICK_FILE_REQUEST = 1002
        private const val SAVE_DOCUMENT_REQUEST = 1003
    }
}
