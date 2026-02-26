package com.neurodoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.LinearLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class PdfViewerView(context: Context) : FrameLayout(context) {
    private var scrollView: ScrollView
    private var pagesContainer: LinearLayout
    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pageViews = mutableListOf<ImageView>()

    private val bitmapCache = LruCache<Int, Bitmap>(5)

    private var scaleFactor = 1.0f
    private var currentPageIndex = 0
    private var pageCount = 0

    // Props
    var pdfUrl: String = ""
        set(value) {
            if (field != value) {
                field = value
                loadDocument()
            }
        }

    var pageIndex: Int = 0
        set(value) {
            if (field != value) {
                field = value
                goToPage(value)
            }
        }

    var spacing: Int = 8
        set(value) {
            field = value
            updateSpacing()
        }

    var showScrollIndicator: Boolean = true
        set(value) {
            field = value
            scrollView.isVerticalScrollBarEnabled = value
        }

    var minZoom: Float = 1.0f
    var maxZoom: Float = 4.0f

    // Overlay
    private var overlayView: OverlayView
    var enableOverlayTap: Boolean = false
    var disableSelection: Boolean = false
    private var overlayItems: List<OverlayItemData> = emptyList()

    // Scale gesture
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = showScrollIndicator
        }

        pagesContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(pagesContainer)
        addView(scrollView)

        overlayView = OverlayView(context, this)
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minZoom, maxZoom)
                applyZoom()
                overlayView.invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                handleLongPress(e)
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (enableOverlayTap) {
                    handleOverlayTap(e)
                    return true
                }
                return false
            }
        })

        // Scroll listener for page change detection + overlay sync
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            detectCurrentPage()
            overlayView.invalidate()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return scaleGestureDetector.isInProgress || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    private fun loadDocument() {
        if (pdfUrl.isEmpty()) return

        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val path = if (pdfUrl.startsWith("file://")) pdfUrl.removePrefix("file://") else pdfUrl
                    File(path)
                }

                if (!file.exists()) {
                    emitDocumentLoadFailed("File not found: $pdfUrl")
                    return@launch
                }

                closeRenderer()

                withContext(Dispatchers.IO) {
                    fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(fileDescriptor!!)
                }

                pageCount = renderer!!.pageCount
                renderPages()

                emitDocumentLoaded(pageCount)

                if (pageIndex > 0) {
                    goToPage(pageIndex)
                }
            } catch (e: Exception) {
                emitDocumentLoadFailed(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun renderPages() {
        pagesContainer.removeAllViews()
        pageViews.clear()

        val pdfRenderer = renderer ?: return
        val containerWidth = if (width > 0) width else 1080 // fallback

        for (i in 0 until pdfRenderer.pageCount) {
            withContext(Dispatchers.IO) {
                val page = pdfRenderer.openPage(i)
                val ratio = page.width.toFloat() / page.height.toFloat()
                val bitmapWidth = (containerWidth * scaleFactor).toInt()
                val bitmapHeight = (bitmapWidth / ratio).toInt()

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                bitmapCache.put(i, bitmap)
            }

            withContext(Dispatchers.Main) {
                val bitmap = bitmapCache.get(i) ?: return@withContext

                val imageView = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (spacing * resources.displayMetrics.density).toInt()
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setImageBitmap(bitmap)
                }

                pageViews.add(imageView)
                pagesContainer.addView(imageView)
            }
        }
    }

    fun goToPage(index: Int) {
        if (index < 0 || index >= pageViews.size) return
        val view = pageViews[index]
        scrollView.post { scrollView.smoothScrollTo(0, view.top) }
    }

    fun zoomTo(scale: Float) {
        scaleFactor = scale.coerceIn(minZoom, maxZoom)
        applyZoom()
    }

    private fun applyZoom() {
        pagesContainer.scaleX = scaleFactor
        pagesContainer.scaleY = scaleFactor
        pagesContainer.pivotX = pagesContainer.width / 2f
        pagesContainer.pivotY = 0f
    }

    private fun updateSpacing() {
        for (view in pageViews) {
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: continue
            params.bottomMargin = (spacing * resources.displayMetrics.density).toInt()
            view.layoutParams = params
        }
    }

    private fun detectCurrentPage() {
        val scrollY = scrollView.scrollY
        for ((i, view) in pageViews.withIndex()) {
            if (view.top <= scrollY + height / 2 && view.bottom >= scrollY + height / 2) {
                if (i != currentPageIndex) {
                    currentPageIndex = i
                    emitPageChanged(i, pageCount)
                }
                break
            }
        }
    }

    private fun handleLongPress(event: MotionEvent) {
        val scrollY = scrollView.scrollY
        val touchY = event.y + scrollY
        val touchX = event.x

        for ((i, view) in pageViews.withIndex()) {
            if (touchY >= view.top && touchY <= view.bottom) {
                val normalizedX = touchX / view.width.toFloat()
                val normalizedY = (touchY - view.top) / view.height.toFloat()
                emitLongPress(i, normalizedX.toDouble(), normalizedY.toDouble())
                break
            }
        }
    }

    // MARK: - Overlay

    fun setTextOverlays(json: String?) {
        if (json.isNullOrEmpty()) {
            overlayItems = emptyList()
            overlayView.invalidate()
            return
        }
        try {
            val arr = JSONArray(json)
            val items = mutableListOf<OverlayItemData>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(OverlayItemData(
                    id = obj.getString("id"),
                    pageIndex = obj.getInt("pageIndex"),
                    x = obj.getDouble("x").toFloat(),
                    y = obj.getDouble("y").toFloat(),
                    width = obj.getDouble("width").toFloat(),
                    height = obj.getDouble("height").toFloat(),
                    selected = obj.optBoolean("selected", false),
                    label = obj.optString("label", "")
                ))
            }
            overlayItems = items
        } catch (e: Exception) {
            overlayItems = emptyList()
        }
        overlayView.invalidate()
    }

    private fun handleOverlayTap(event: MotionEvent) {
        val hit = hitTestOverlay(event.x, event.y)
        if (hit != null) {
            emitOverlayTap(hit.id, hit.pageIndex)
        } else {
            // Emit generic tap with normalized coords
            val scrollY = scrollView.scrollY
            val touchY = event.y + scrollY
            val touchX = event.x
            for ((i, view) in pageViews.withIndex()) {
                if (touchY >= view.top && touchY <= view.bottom) {
                    val normalizedX = touchX / view.width.toFloat()
                    val normalizedY = (touchY - view.top) / view.height.toFloat()
                    emitTap(i, normalizedX.toDouble(), normalizedY.toDouble())
                    break
                }
            }
        }
    }

    private fun hitTestOverlay(touchX: Float, touchY: Float): OverlayItemData? {
        val scrollY = scrollView.scrollY
        val adjustedTouchY = touchY + scrollY

        for (item in overlayItems.reversed()) {
            if (item.pageIndex < 0 || item.pageIndex >= pageViews.size) continue
            val pageView = pageViews[item.pageIndex]
            val pw = pageView.width.toFloat()
            val ph = pageView.height.toFloat()
            val pageTop = pageView.top.toFloat()

            val left = item.x * pw - 4
            val top = pageTop + item.y * ph - 4
            val right = (item.x + item.width) * pw + 4
            val bottom = pageTop + (item.y + item.height) * ph + 4

            if (touchX in left..right && adjustedTouchY in top..bottom) {
                return item
            }
        }
        return null
    }

    fun getPageViews(): List<ImageView> = pageViews
    fun getScrollY(): Int = scrollView.scrollY
    fun getOverlayItems(): List<OverlayItemData> = overlayItems

    fun setTextOverlaysInternal(items: List<OverlayItemData>) {
        overlayItems = items
    }

    // MARK: - Event Emitters

    private fun emitPageChanged(pageIndex: Int, pageCount: Int) {
        val event = Arguments.createMap().apply {
            putInt("pageIndex", pageIndex)
            putInt("pageCount", pageCount)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onPageChanged", event)
    }

    private fun emitDocumentLoaded(pageCount: Int) {
        val event = Arguments.createMap().apply {
            putInt("pageCount", pageCount)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onDocumentLoaded", event)
    }

    private fun emitDocumentLoadFailed(error: String) {
        val event = Arguments.createMap().apply {
            putString("error", error)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onDocumentLoadFailed", event)
    }

    private fun emitLongPress(pageIndex: Int, x: Double, y: Double) {
        val event = Arguments.createMap().apply {
            putInt("pageIndex", pageIndex)
            putDouble("x", x)
            putDouble("y", y)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onLongPress", event)
    }

    private fun emitOverlayTap(overlayId: String, pageIndex: Int) {
        val event = Arguments.createMap().apply {
            putString("id", overlayId)
            putInt("pageIndex", pageIndex)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onOverlayTap", event)
    }

    private fun emitTap(pageIndex: Int, x: Double, y: Double) {
        val event = Arguments.createMap().apply {
            putInt("pageIndex", pageIndex)
            putDouble("x", x)
            putDouble("y", y)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onTap", event)
    }

    fun emitOverlayMoved(overlayId: String, x: Double, y: Double) {
        val event = Arguments.createMap().apply {
            putString("id", overlayId)
            putDouble("x", x)
            putDouble("y", y)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onOverlayMoved", event)
    }

    fun emitOverlayResized(overlayId: String, x: Double, y: Double, width: Double, height: Double) {
        val event = Arguments.createMap().apply {
            putString("id", overlayId)
            putDouble("x", x)
            putDouble("y", y)
            putDouble("width", width)
            putDouble("height", height)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, "onOverlayResized", event)
    }

    private fun closeRenderer() {
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
        bitmapCache.evictAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closeRenderer()
    }
}

data class OverlayItemData(
    val id: String,
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val selected: Boolean,
    val label: String
)

private class OverlayView(context: Context, private val pdfViewer: PdfViewerView) : View(context) {

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 51, 204, 77) // green fill
        style = Paint.Style.FILL
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 51, 204, 77)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val unselectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 51, 128, 255) // blue fill
        style = Paint.Style.FILL
    }
    private val unselectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(153, 51, 128, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 51, 204, 77)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 51, 128, 255)
        textSize = 24f
    }

    private val handleSize = 16f

    // Drag state
    private var draggedId: String? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOrigX = 0f
    private var dragOrigY = 0f
    private var dragPageIndex = 0

    // Resize state
    private var resizeId: String? = null
    private var resizeCorner = -1 // 0=TL, 1=TR, 2=BL, 3=BR
    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizeOrigX = 0f
    private var resizeOrigY = 0f
    private var resizeOrigW = 0f
    private var resizeOrigH = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pageViews = pdfViewer.getPageViews()
        val scrollY = pdfViewer.getScrollY()
        val items = pdfViewer.getOverlayItems()

        for (item in items) {
            if (item.pageIndex < 0 || item.pageIndex >= pageViews.size) continue
            val pv = pageViews[item.pageIndex]
            val pw = pv.width.toFloat()
            val ph = pv.height.toFloat()
            val pageTop = pv.top.toFloat() - scrollY

            val left = item.x * pw
            val top = pageTop + item.y * ph
            val right = (item.x + item.width) * pw
            val bottom = pageTop + (item.y + item.height) * ph

            val rect = RectF(left, top, right, bottom)

            if (item.selected) {
                canvas.drawRect(rect, selectedPaint)
                canvas.drawRect(rect, selectedStroke)
                // Draw resize handles
                drawHandle(canvas, left, top)
                drawHandle(canvas, right, top)
                drawHandle(canvas, left, bottom)
                drawHandle(canvas, right, bottom)
            } else {
                canvas.drawRect(rect, unselectedPaint)
                canvas.drawRect(rect, unselectedStroke)
            }

            // Label
            if (item.label.isNotEmpty() && rect.width() > 40 && rect.height() > 20) {
                val fontSize = minOf(24f, rect.height() * 0.7f)
                labelPaint.textSize = fontSize
                labelPaint.color = if (item.selected) Color.argb(200, 51, 204, 77) else Color.argb(200, 51, 128, 255)
                canvas.drawText(item.label, left + 4f, top + fontSize, labelPaint)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        val rect = RectF(cx - handleSize, cy - handleSize, cx + handleSize, cy + handleSize)
        canvas.drawRect(rect, handlePaint)
        canvas.drawRect(rect, handleStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pdfViewer.enableOverlayTap) return false

        val pageViews = pdfViewer.getPageViews()
        val scrollY = pdfViewer.getScrollY()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val items = pdfViewer.getOverlayItems()
                // Check resize handles first (selected items)
                for (item in items.reversed()) {
                    if (!item.selected || item.pageIndex < 0 || item.pageIndex >= pageViews.size) continue
                    val pv = pageViews[item.pageIndex]
                    val pw = pv.width.toFloat()
                    val ph = pv.height.toFloat()
                    val pageTop = pv.top.toFloat() - scrollY

                    val left = item.x * pw
                    val top = pageTop + item.y * ph
                    val right = (item.x + item.width) * pw
                    val bottom = pageTop + (item.y + item.height) * ph

                    val corners = listOf(
                        Pair(0, Pair(left, top)),
                        Pair(1, Pair(right, top)),
                        Pair(2, Pair(left, bottom)),
                        Pair(3, Pair(right, bottom))
                    )
                    for ((corner, pos) in corners) {
                        val hRect = RectF(pos.first - handleSize * 2, pos.second - handleSize * 2,
                            pos.first + handleSize * 2, pos.second + handleSize * 2)
                        if (hRect.contains(event.x, event.y)) {
                            resizeId = item.id
                            resizeCorner = corner
                            resizeStartX = event.x
                            resizeStartY = event.y
                            resizeOrigX = item.x
                            resizeOrigY = item.y
                            resizeOrigW = item.width
                            resizeOrigH = item.height
                            parent.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }

                // Check drag on any overlay
                val adjustedY = event.y + scrollY
                for (item in items.reversed()) {
                    if (item.pageIndex < 0 || item.pageIndex >= pageViews.size) continue
                    val pv = pageViews[item.pageIndex]
                    val pw = pv.width.toFloat()
                    val ph = pv.height.toFloat()
                    val pageTop = pv.top.toFloat()

                    val left = item.x * pw
                    val top = pageTop + item.y * ph
                    val right = (item.x + item.width) * pw
                    val bottom = pageTop + (item.y + item.height) * ph

                    if (event.x in left..right && adjustedY in top..bottom) {
                        draggedId = item.id
                        dragStartX = event.x
                        dragStartY = event.y
                        dragOrigX = item.x
                        dragOrigY = item.y
                        dragPageIndex = item.pageIndex
                        parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (resizeId != null) {
                    updateResize(event.x, event.y, pageViews, scrollY)
                    return true
                }
                if (draggedId != null) {
                    updateDrag(event.x, event.y, pageViews, scrollY)
                    return true
                }
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (resizeId != null) {
                    val items = pdfViewer.getOverlayItems()
                    val item = items.find { it.id == resizeId }
                    if (item != null) {
                        pdfViewer.emitOverlayResized(item.id, item.x.toDouble(), item.y.toDouble(), item.width.toDouble(), item.height.toDouble())
                    }
                    resizeId = null
                    resizeCorner = -1
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (draggedId != null) {
                    val items = pdfViewer.getOverlayItems()
                    val item = items.find { it.id == draggedId }
                    if (item != null) {
                        pdfViewer.emitOverlayMoved(item.id, item.x.toDouble(), item.y.toDouble())
                    }
                    draggedId = null
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                return false
            }
        }
        return false
    }

    private fun updateDrag(touchX: Float, touchY: Float, pageViews: List<ImageView>, scrollY: Int) {
        if (dragPageIndex < 0 || dragPageIndex >= pageViews.size) return
        val pv = pageViews[dragPageIndex]
        val pw = pv.width.toFloat()
        val ph = pv.height.toFloat()
        if (pw <= 0 || ph <= 0) return

        val dx = (touchX - dragStartX) / pw
        val dy = (touchY - dragStartY) / ph

        val newX = dragOrigX + dx
        val newY = dragOrigY + dy

        val items = pdfViewer.getOverlayItems().toMutableList()
        val idx = items.indexOfFirst { it.id == draggedId }
        if (idx >= 0) {
            items[idx] = items[idx].copy(x = newX, y = newY)
            pdfViewer.setTextOverlaysInternal(items)
            invalidate()
        }
    }

    private fun updateResize(touchX: Float, touchY: Float, pageViews: List<ImageView>, scrollY: Int) {
        val items = pdfViewer.getOverlayItems().toMutableList()
        val idx = items.indexOfFirst { it.id == resizeId }
        if (idx < 0) return
        val item = items[idx]
        if (item.pageIndex < 0 || item.pageIndex >= pageViews.size) return
        val pv = pageViews[item.pageIndex]
        val pw = pv.width.toFloat()
        val ph = pv.height.toFloat()
        if (pw <= 0 || ph <= 0) return

        val dx = (touchX - resizeStartX) / pw
        val dy = (touchY - resizeStartY) / ph
        val minW = 0.02f
        val minH = 0.01f

        var newX = resizeOrigX
        var newY = resizeOrigY
        var newW = resizeOrigW
        var newH = resizeOrigH

        when (resizeCorner) {
            3 -> { // BR
                newW = maxOf(minW, resizeOrigW + dx)
                newH = maxOf(minH, resizeOrigH + dy)
            }
            2 -> { // BL
                val proposedW = resizeOrigW - dx
                if (proposedW >= minW) { newX = resizeOrigX + dx; newW = proposedW }
                newH = maxOf(minH, resizeOrigH + dy)
            }
            1 -> { // TR
                newW = maxOf(minW, resizeOrigW + dx)
                val proposedH = resizeOrigH - dy
                if (proposedH >= minH) { newY = resizeOrigY + dy; newH = proposedH }
            }
            0 -> { // TL
                val proposedW = resizeOrigW - dx
                if (proposedW >= minW) { newX = resizeOrigX + dx; newW = proposedW }
                val proposedH = resizeOrigH - dy
                if (proposedH >= minH) { newY = resizeOrigY + dy; newH = proposedH }
            }
        }

        items[idx] = item.copy(x = newX, y = newY, width = newW, height = newH)
        pdfViewer.setTextOverlaysInternal(items)
        invalidate()
    }
}
