package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.util.UUID

object FormCreator {

    fun createFormFromPdf(
        pdfUrl: String,
        fields: ReadableArray,
        removeOriginalText: Boolean,
        tempDir: File,
        promise: Promise
    ) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)

            // Ensure AcroForm exists
            var acroForm = doc.documentCatalog.acroForm
            if (acroForm == null) {
                acroForm = PDAcroForm(doc)
                doc.documentCatalog.acroForm = acroForm

                // Set default resources with Helvetica font
                val resources = acroForm.defaultResources
                    ?: com.tom_roush.pdfbox.pdmodel.PDResources()
                resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
                acroForm.defaultResources = resources
            }

            // Group fields by page
            val fieldsByPage = mutableMapOf<Int, MutableList<Map<String, Any?>>>()
            for (i in 0 until fields.size()) {
                val fieldMap = fields.getMap(i) ?: continue
                val pageIndex = if (fieldMap.hasKey("pageIndex")) fieldMap.getInt("pageIndex") else 0
                val field = mutableMapOf<String, Any?>()
                field["name"] = fieldMap.getString("name")
                field["pageIndex"] = pageIndex

                if (fieldMap.hasKey("boundingBox") && !fieldMap.isNull("boundingBox")) {
                    val bbox = fieldMap.getMap("boundingBox")!!
                    field["boundingBox"] = mapOf(
                        "x" to bbox.getDouble("x"),
                        "y" to bbox.getDouble("y"),
                        "width" to bbox.getDouble("width"),
                        "height" to bbox.getDouble("height")
                    )
                }

                field["type"] = if (fieldMap.hasKey("type") && !fieldMap.isNull("type")) fieldMap.getString("type") else "text"
                field["defaultValue"] = if (fieldMap.hasKey("defaultValue") && !fieldMap.isNull("defaultValue")) fieldMap.getString("defaultValue") else ""
                field["fontSize"] = if (fieldMap.hasKey("fontSize") && !fieldMap.isNull("fontSize")) fieldMap.getDouble("fontSize") else 0.0

                fieldsByPage.getOrPut(pageIndex) { mutableListOf() }.add(field)
            }

            // Process each page
            for ((pageIndex, pageFields) in fieldsByPage) {
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val mediaBox = page.mediaBox

                // White-out original text regions
                if (removeOriginalText) {
                    val cs = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    cs.setNonStrokingColor(255, 255, 255)

                    for (field in pageFields) {
                        val bbox = field["boundingBox"] as? Map<*, *> ?: continue
                        val nx = (bbox["x"] as? Double)?.toFloat() ?: 0f
                        val ny = (bbox["y"] as? Double)?.toFloat() ?: 0f
                        val nw = (bbox["width"] as? Double)?.toFloat() ?: 0f
                        val nh = (bbox["height"] as? Double)?.toFloat() ?: 0f

                        val pdfX = nx * mediaBox.width - 1f
                        val pdfY = mediaBox.height - ny * mediaBox.height - nh * mediaBox.height - 1f
                        val pdfW = nw * mediaBox.width + 2f
                        val pdfH = nh * mediaBox.height + 2f

                        cs.addRect(pdfX, pdfY, pdfW, pdfH)
                        cs.fill()
                    }

                    cs.close()
                }

                // Add form fields
                for (field in pageFields) {
                    val name = field["name"] as? String ?: continue
                    val bbox = field["boundingBox"] as? Map<*, *> ?: continue
                    val type = (field["type"] as? String) ?: "text"
                    val defaultValue = (field["defaultValue"] as? String) ?: ""
                    val nx = (bbox["x"] as? Double)?.toFloat() ?: 0f
                    val ny = (bbox["y"] as? Double)?.toFloat() ?: 0f
                    val nw = (bbox["width"] as? Double)?.toFloat() ?: 0f
                    val nh = (bbox["height"] as? Double)?.toFloat() ?: 0f

                    var fontSize = (field["fontSize"] as? Double)?.toFloat() ?: 0f
                    // If fontSize is 0, estimate from bounding box height (~85% of box height in points)
                    if (fontSize <= 0f) {
                        fontSize = nh * mediaBox.height * 0.85f
                        if (fontSize < 4f) fontSize = 12f // fallback minimum
                    }

                    val pdfX = nx * mediaBox.width
                    val pdfY = mediaBox.height - ny * mediaBox.height - nh * mediaBox.height
                    val pdfW = nw * mediaBox.width
                    val pdfH = nh * mediaBox.height

                    val rect = PDRectangle(pdfX, pdfY, pdfW, pdfH)

                    when (type) {
                        "checkbox" -> addCheckboxField(doc, page, acroForm, name, rect, defaultValue)
                        else -> addTextField(doc, page, acroForm, name, rect, defaultValue, fontSize)
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
            promise.reject("FORM_FAILED", e.message, e)
        }
    }

    private fun addTextField(
        doc: PDDocument,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        acroForm: PDAcroForm,
        name: String,
        rect: PDRectangle,
        defaultValue: String,
        fontSize: Float
    ) {
        val textField = PDTextField(acroForm)
        textField.partialName = name

        // Default appearance string
        textField.defaultAppearance = "/Helv $fontSize Tf 0 0 0 rg"

        // Create widget annotation
        val widget = PDAnnotationWidget()
        widget.rectangle = rect
        widget.page = page

        // Border
        val border = PDBorderStyleDictionary()
        border.width = 0.5f
        widget.borderStyle = border

        textField.widgets = listOf(widget)
        page.annotations.add(widget)
        acroForm.fields.add(textField)

        // Set value after widget is attached
        if (defaultValue.isNotEmpty()) {
            try {
                textField.value = defaultValue
            } catch (_: Exception) {
                // Ignore if setting default value fails
            }
        }
    }

    private fun addCheckboxField(
        doc: PDDocument,
        page: com.tom_roush.pdfbox.pdmodel.PDPage,
        acroForm: PDAcroForm,
        name: String,
        rect: PDRectangle,
        defaultValue: String
    ) {
        val checkbox = PDCheckBox(acroForm)
        checkbox.partialName = name

        val widget = PDAnnotationWidget()
        widget.rectangle = rect
        widget.page = page

        val border = PDBorderStyleDictionary()
        border.width = 0.5f
        widget.borderStyle = border

        checkbox.widgets = listOf(widget)
        page.annotations.add(widget)
        acroForm.fields.add(checkbox)

        val isChecked = defaultValue == "true" || defaultValue == "Yes" || defaultValue == "1"
        try {
            if (isChecked) checkbox.check() else checkbox.unCheck()
        } catch (_: Exception) {
            // Ignore
        }
    }

    // MARK: - Helpers

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }
}
