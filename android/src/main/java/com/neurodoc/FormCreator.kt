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
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import android.util.Log
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.io.ByteArrayOutputStream
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

                // Remove original text by parsing and rewriting the content stream
                // (white-rectangle approach fails on non-white backgrounds)
                if (removeOriginalText) {
                    val regions = pageFields.mapNotNull { field ->
                        val bbox = field["boundingBox"] as? Map<*, *> ?: return@mapNotNull null
                        val nx = (bbox["x"] as? Double)?.toFloat() ?: 0f
                        val ny = (bbox["y"] as? Double)?.toFloat() ?: 0f
                        val nw = (bbox["width"] as? Double)?.toFloat() ?: 0f
                        val nh = (bbox["height"] as? Double)?.toFloat() ?: 0f
                        floatArrayOf(
                            nx * mediaBox.width,
                            mediaBox.height * (1f - ny - nh),
                            nw * mediaBox.width,
                            nh * mediaBox.height
                        )
                    }

                    val allTokens = mutableListOf<Any>()
                    val parser = PDFStreamParser(page)
                    try {
                        var token = parser.parseNextToken()
                        while (token != null) {
                            allTokens.add(token)
                            token = parser.parseNextToken()
                        }
                    } catch (_: Exception) {}

                    val filtered = filterTextTokens(allTokens, regions)

                    val baos = ByteArrayOutputStream()
                    val writer = ContentStreamWriter(baos)
                    writer.writeTokens(filtered)

                    val cosStream = doc.document.createCOSStream()
                    cosStream.createOutputStream().use { os -> os.write(baos.toByteArray()) }
                    page.cosObject.setItem(COSName.CONTENTS, cosStream)

                    Log.d("FormCreator", "Page $pageIndex: ${allTokens.size} tokens -> ${filtered.size} filtered (removed ${allTokens.size - filtered.size})")

                    // Also filter text inside Form XObjects — template PDFs often
                    // store all visible content inside an XObject referenced via Do.
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

                                    Log.d("FormCreator", "  XObject '$xName': ${xTokens.size} tokens")
                                    val xFiltered = filterTextTokens(xTokens, regions)
                                    val removed = xTokens.size - xFiltered.size
                                    Log.d("FormCreator", "  XObject '$xName': removed $removed tokens")

                                    if (removed > 0) {
                                        val xBaos = ByteArrayOutputStream()
                                        ContentStreamWriter(xBaos).writeTokens(xFiltered)
                                        (xObj.cosObject as? COSStream)?.createOutputStream()?.use { os ->
                                            os.write(xBaos.toByteArray())
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("FormCreator", "XObject processing error: ${e.message}")
                            }
                        }
                    }
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

                    // Always compute fontSize from widget height to prevent clipping.
                    // The JS-side fontSize reflects the ORIGINAL text size, but the
                    // form widget height (from bounding-box) may be smaller.
                    val pdfH_tmp = nh * mediaBox.height
                    var fontSize = (pdfH_tmp * 0.75f).coerceAtLeast(4f)  // 75% of box height

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

    // MARK: - Content stream text removal (same logic as ContentEditor)

    private fun filterTextTokens(tokens: List<Any>, regions: List<FloatArray>, tag: String = "FormCreator"): List<Any> {
        for ((idx, r) in regions.withIndex()) {
            Log.d(tag, "  Region $idx: x=${r[0]} y=${r[1]} w=${r[2]} h=${r[3]}")
        }

        // CTM (a, b, c, d, e, f) — identity to start
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

            // Map through CTM to get page coordinates
            val pageX = tmX * ctmA + tmY * ctmC + ctmE
            val pageY = tmX * ctmB + tmY * ctmD + ctmF

            val skip = when (op) {
                "Tj", "TJ", "'", "\"" -> regions.any { r -> isInRegion(pageX, pageY, r) }
                else -> false
            }

            // Log every text-show operator for debugging
            if (op == "Tj" || op == "TJ" || op == "'" || op == "\"") {
                val preview = if (pending.isNotEmpty()) pending.last().toString().take(40) else "?"
                Log.d(tag, "  $op at page(${pageX}, ${pageY}) tm(${tmX}, ${tmY}) ctm(${ctmE}, ${ctmF}) skip=$skip text='$preview'")
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

    private fun isInRegion(x: Float, y: Float, region: FloatArray): Boolean {
        val tolerance = 15f
        return x >= region[0] - tolerance && x <= region[0] + region[2] + tolerance &&
               y >= region[1] - tolerance && y <= region[1] + region[3] + tolerance
    }

    private fun num(obj: Any?): Float = (obj as? COSNumber)?.floatValue() ?: 0f

    // MARK: - Helpers

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }
}
