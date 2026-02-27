package com.neurodoc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Shared utilities for PDF processing across all processor objects.
 * Centralises resolveFile and renderPageToBitmap to eliminate code duplication
 * and ensure resources are always safely closed.
 */
internal object PdfUtils {

    fun resolveFile(urlString: String): File {
        val path = if (urlString.startsWith("file://")) urlString.removePrefix("file://") else urlString
        return File(path)
    }

    /**
     * Renders a single PDF page to a Bitmap.
     * ParcelFileDescriptor, PdfRenderer, and PdfRenderer.Page are all closed via use{},
     * even when exceptions are thrown.
     *
     * @throws IndexOutOfBoundsException if pageIndex >= document's page count
     */
    fun renderPageToBitmap(
        pdfUrl: String,
        pageIndex: Int,
        scale: Float = 2f,
        renderMode: Int = PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
    ): Bitmap {
        val file = resolveFile(pdfUrl)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (pageIndex >= renderer.pageCount) {
                    throw IndexOutOfBoundsException(
                        "Page index $pageIndex is out of bounds (pageCount: ${renderer.pageCount})"
                    )
                }
                renderer.openPage(pageIndex).use { page ->
                    val w = (page.width * scale).toInt()
                    val h = (page.height * scale).toInt()
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, renderMode)
                    }
                }
            }
        }
    }
}
