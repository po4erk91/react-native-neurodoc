package com.neurodoc

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.util.UUID

object FormProcessor {

    fun getFormFields(pdfUrl: String, promise: Promise) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)
            val acroForm = doc.documentCatalog.acroForm

            val fields = WritableNativeArray()

            if (acroForm != null) {
                for (field in acroForm.fields) {
                    val type = when (field) {
                        is PDTextField -> "text"
                        is PDCheckBox -> "checkbox"
                        is PDRadioButton -> "radio"
                        is PDChoice -> "dropdown"
                        is PDSignatureField -> "signature"
                        else -> "unknown"
                    }

                    val options = WritableNativeArray()
                    if (field is PDChoice) {
                        for (option in field.options) {
                            options.pushString(option)
                        }
                    }

                    fields.pushMap(WritableNativeMap().apply {
                        putString("id", field.fullyQualifiedName ?: "")
                        putString("name", field.fullyQualifiedName ?: "")
                        putString("type", type)
                        putString("value", field.valueAsString ?: "")
                        putArray("options", options)
                        // Return font info if available
                        if (field is PDTextField) {
                            val da = field.defaultAppearance
                            if (da != null) {
                                // Default appearance format: "/FontName fontSize Tf"
                                val parts = da.split(" ")
                                val tfIndex = parts.indexOf("Tf")
                                if (tfIndex >= 2) {
                                    val fontName = parts[tfIndex - 2].removePrefix("/")
                                    val fontSize = parts[tfIndex - 1].toDoubleOrNull()
                                    if (fontName.isNotEmpty()) putString("fontName", fontName)
                                    if (fontSize != null && fontSize > 0) putDouble("fontSize", fontSize)
                                }
                            }
                        }
                    })
                }
            }

            doc.close()
            promise.resolve(WritableNativeMap().apply {
                putArray("fields", fields)
            })
        } catch (e: Exception) {
            promise.reject("FORM_FAILED", e.message, e)
        }
    }

    fun fillForm(pdfUrl: String, fields: ReadableArray, flatten: Boolean, tempDir: File, promise: Promise) {
        try {
            val file = resolveFile(pdfUrl)
            val doc = PDDocument.load(file)
            val acroForm = doc.documentCatalog.acroForm

            if (acroForm == null) {
                doc.close()
                promise.reject("FORM_FAILED", "PDF does not contain form fields")
                return
            }

            for (i in 0 until fields.size()) {
                val fieldData = fields.getMap(i) ?: continue
                val fieldId = fieldData.getString("id") ?: continue
                val value = fieldData.getString("value") ?: continue

                val field = acroForm.getField(fieldId) ?: continue

                when (field) {
                    is PDCheckBox -> {
                        if (value == "true" || value == "Yes" || value == "1") {
                            field.check()
                        } else {
                            field.unCheck()
                        }
                    }
                    is PDRadioButton -> {
                        field.setValue(value)
                    }
                    else -> {
                        field.setValue(value)
                    }
                }
            }

            if (flatten) {
                acroForm.flatten()
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

    // MARK: - Helpers

    private fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }
}
