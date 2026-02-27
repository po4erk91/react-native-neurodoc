package com.neurodoc

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableNativeMap
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles document picker and save-to flows that require Activity interaction.
 * Extracted from NeurodocModule to isolate the ActivityEventListener concern.
 */
internal class DocumentPickerHelper(
    private val reactContext: ReactApplicationContext,
    private val tempDir: File
) : BaseActivityEventListener() {

    private val pickerPromise = AtomicReference<Promise?>(null)
    private var pickerResultKey: String = "pdfUrl"
    private var saveSourcePath: String? = null

    companion object {
        const val PICK_DOCUMENT_REQUEST = 1001
        const val PICK_FILE_REQUEST = 1002
        const val SAVE_DOCUMENT_REQUEST = 1003
    }

    // -------------------------------------------------------------------------
    // ActivityEventListener
    // -------------------------------------------------------------------------

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == SAVE_DOCUMENT_REQUEST) {
            handleSaveResult(resultCode, data)
            return
        }

        if (requestCode != PICK_DOCUMENT_REQUEST && requestCode != PICK_FILE_REQUEST) return

        val promise = pickerPromise.getAndSet(null) ?: return

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
            val contentResolver = reactContext.contentResolver
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

            promise.resolve(WritableNativeMap().apply {
                putString(pickerResultKey, "file://${outputFile.absolutePath}")
            })
        } catch (e: Exception) {
            promise.reject("PICKER_FAILED", "Failed to process selected file: ${e.message}", e)
        }
    }

    private fun handleSaveResult(resultCode: Int, data: Intent?) {
        val promise = pickerPromise.getAndSet(null) ?: return
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
            val sourceFile = PdfUtils.resolveFile(sourcePath)
            val contentResolver = reactContext.contentResolver
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

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun pickDocument(promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("PICKER_FAILED", "No current activity")
            return
        }

        pickerPromise.set(promise)
        pickerResultKey = "pdfUrl"

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        activity.startActivityForResult(intent, PICK_DOCUMENT_REQUEST)
    }

    fun pickFile(types: ReadableArray, promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("PICKER_FAILED", "No current activity")
            return
        }

        pickerPromise.set(promise)
        pickerResultKey = "fileUrl"

        val mimeTypes = mutableListOf<String>()
        for (i in 0 until types.size()) {
            val t = types.getString(i) ?: continue
            val mime = when (t) {
                "org.openxmlformats.wordprocessingml.document" ->
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "com.microsoft.word.doc" -> "application/msword"
                "com.adobe.pdf" -> "application/pdf"
                "public.plain-text" -> "text/plain"
                "public.image" -> "image/*"
                "public.png" -> "image/png"
                "public.jpeg" -> "image/jpeg"
                else -> t
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

    fun saveTo(pdfUrl: String, fileName: String, promise: Promise) {
        val activity = reactContext.currentActivity
        if (activity == null) {
            promise.reject("SAVE_FAILED", "No current activity")
            return
        }

        val sourceFile = PdfUtils.resolveFile(pdfUrl)
        if (!sourceFile.exists()) {
            promise.reject("SAVE_FAILED", "Source file does not exist: $pdfUrl")
            return
        }

        pickerPromise.set(promise)
        saveSourcePath = pdfUrl

        val safeName = if (fileName.endsWith(".pdf", ignoreCase = true)) fileName else "$fileName.pdf"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, safeName)
        }
        activity.startActivityForResult(intent, SAVE_DOCUMENT_REQUEST)
    }
}
