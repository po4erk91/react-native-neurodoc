package com.neurodoc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.RCTModernEventEmitter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.Collections
import java.util.UUID

class PdfViewerView(context: Context) : FrameLayout(context) {
    // Scroll mode: RecyclerView with vertical LinearLayoutManager
    private var scrollRecyclerView: RecyclerView
    private var scrollAdapter: ScrollPageAdapter? = null

    // Single page mode: ViewPager2
    private var viewPager: ViewPager2? = null
    private var pagerAdapter: SinglePageAdapter? = null

    // Grid mode: RecyclerView with GridLayoutManager
    private var gridRecyclerView: RecyclerView? = null
    private var gridAdapter: GridPageAdapter? = null

    // Thumbnail strip
    private var thumbnailStrip: HorizontalScrollView? = null
    private var thumbnailContainer: LinearLayout? = null

    // Grid drag & drop
    private var gridItemTouchHelper: ItemTouchHelper? = null

    // PDF rendering
    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var currentFilePath: String? = null
    private var lastSavedUrl: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pageCount = 0
    private var currentPageIndex = 0
    private var currentDisplayMode = "scroll"

    // Image picker
    private var insertTargetIndex = 0
    private var imagePickLauncher: ActivityResultLauncher<String>? = null
    companion object {
        @Suppress("unused") private const val IMAGE_PICK_REQUEST = 9876 // legacy, unused
        private var pdfBoxInitialized = false
    }

    // Page dimensions (width x height in PDF points)
    private val pageDimensions = mutableListOf<Pair<Int, Int>>()

    // Memory-aware bitmap cache (~48MB max for full-size pages)
    // Do NOT recycle bitmaps in entryRemoved — ImageView may still reference them.
    // Let GC handle it after ImageView releases the reference.
    private val bitmapCache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    // Smaller cache for thumbnails (~8MB)
    private val thumbCache = object : LruCache<Int, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    private var scaleFactor = 1.0f
    private var panOffsetX = 0f
    private var panOffsetY = 0f
    private var lastPanTouchX = 0f
    private var lastPanTouchY = 0f

    // Block RecyclerView scrolling while zoomed — panning replaces scrolling
    private var isZoomTouchBlockerActive = false
    private val zoomTouchBlocker = object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent) = true
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    // Props
    var pdfUrl: String = ""
        set(value) {
            if (field != value) {
                field = value
                // Skip reload if this URL was set by our own save operation
                if (value != lastSavedUrl) {
                    loadDocument()
                }
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
            scrollAdapter?.spacing = (value * resources.displayMetrics.density).toInt()
        }

    var showScrollIndicator: Boolean = true
        set(value) {
            field = value
            scrollRecyclerView.isVerticalScrollBarEnabled = value
        }

    var minZoom: Float = 1.0f
    var maxZoom: Float = 4.0f

    var displayMode: String = "scroll"
        set(value) {
            if (field != value) {
                field = value
                applyDisplayMode()
            }
        }

    var showThumbnails: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateThumbnailVisibility()
            }
        }

    // Overlay
    private var overlayView: OverlayView
    var enableOverlayTap: Boolean = false
    var disableSelection: Boolean = false
    private var overlayItems: List<OverlayItemData> = emptyList()

    // Gestures
    private val scaleGestureDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init {
        scrollRecyclerView = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
            isVerticalScrollBarEnabled = showScrollIndicator
            setHasFixedSize(false)
            // Prefetch pages near the visible area
            (layoutManager as LinearLayoutManager).initialPrefetchItemCount = 3
        }
        addView(scrollRecyclerView)

        overlayView = OverlayView(context, this)
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(minZoom, maxZoom)
                val r = scaleFactor / prevScale  // actual ratio after clamping

                // Adjust pan so the pinch focal point stays fixed on screen.
                // Formula: newTrans = (focus - pivot) * (1 - r) + oldTrans * r
                val cx = scrollRecyclerView.width / 2f
                val cy = scrollRecyclerView.height / 2f
                panOffsetX = (detector.focusX - cx) * (1 - r) + panOffsetX * r
                panOffsetY = (detector.focusY - cy) * (1 - r) + panOffsetY * r

                clampPan()
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

        scrollRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                detectCurrentPage()
                overlayView.invalidate()
            }
        })
    }

    // MARK: - Layout (React Native Yoga bridge)

    private var layoutRequested = false

    private fun scheduleLayout() {
        if (layoutRequested) return
        layoutRequested = true
        Choreographer.getInstance().postFrameCallback {
            layoutRequested = false
            manuallyLayoutChildren()
        }
    }

    private fun manuallyLayoutChildren() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        layout(left, top, right, bottom)
    }

    override fun requestLayout() {
        super.requestLayout()
        scheduleLayout()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Track pan when zoomed in (both X and Y)
        if (scaleFactor > 1.01f && currentDisplayMode == "scroll") {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastPanTouchX = ev.x
                    lastPanTouchY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1 && !scaleGestureDetector.isInProgress) {
                        val dx = ev.x - lastPanTouchX
                        val dy = ev.y - lastPanTouchY
                        panOffsetX += dx
                        panOffsetY += dy
                        clampPan()
                        applyZoom()
                        overlayView.invalidate()
                    }
                    lastPanTouchX = ev.x
                    lastPanTouchY = ev.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    lastPanTouchX = ev.x
                    lastPanTouchY = ev.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // Track the REMAINING finger, not the one being lifted.
                    // ev.x/y returns the lifted finger's coords — next MOVE uses
                    // the remaining finger → huge delta → view jumps to corner.
                    val liftedIdx = ev.actionIndex
                    val remainingIdx = if (liftedIdx == 0) 1 else 0
                    if (remainingIdx < ev.pointerCount) {
                        lastPanTouchX = ev.getX(remainingIdx)
                        lastPanTouchY = ev.getY(remainingIdx)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Feed events so the detector can detect pinch start
        scaleGestureDetector.onTouchEvent(ev)
        return scaleGestureDetector.isInProgress || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        // Return true while pinching so we keep receiving events
        return scaleGestureDetector.isInProgress || super.onTouchEvent(event)
    }

    // MARK: - Page Rendering (lazy, on-demand)

    /**
     * Renders a single page at the given width. Returns cached bitmap if available.
     * MUST be called from a background thread (Dispatchers.IO).
     */
    @Synchronized
    fun renderPage(pageIndex: Int, targetWidth: Int): Bitmap? {
        // Check cache first
        bitmapCache.get(pageIndex)?.let { if (!it.isRecycled) return it }

        val pdfRenderer = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) return null

        return try {
            val page = pdfRenderer.openPage(pageIndex)
            val ratio = page.width.toFloat() / page.height.toFloat()
            val bitmapWidth = targetWidth.coerceAtLeast(100)
            val bitmapHeight = (bitmapWidth / ratio).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            bitmapCache.put(pageIndex, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Renders a small thumbnail for the given page.
     * MUST be called from a background thread.
     */
    @Synchronized
    fun renderThumbnail(pageIndex: Int): Bitmap? {
        thumbCache.get(pageIndex)?.let { if (!it.isRecycled) return it }

        val pdfRenderer = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) return null

        return try {
            val thumbWidth = (44 * resources.displayMetrics.density).toInt()
            val page = pdfRenderer.openPage(pageIndex)
            val ratio = page.width.toFloat() / page.height.toFloat()
            val thumbHeight = (thumbWidth / ratio).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()

            thumbCache.put(pageIndex, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    fun getPageDimensions(): List<Pair<Int, Int>> = pageDimensions
    fun getPageCount(): Int = pageCount

    // MARK: - Display Mode

    private fun applyDisplayMode() {
        currentDisplayMode = displayMode

        // Remove non-scroll mode views
        viewPager?.let { removeView(it) }
        viewPager = null
        pagerAdapter = null
        gridRecyclerView?.let { removeView(it) }
        gridRecyclerView = null
        gridAdapter = null

        when (displayMode) {
            "single" -> {
                scrollRecyclerView.visibility = View.GONE
                overlayView.visibility = View.GONE
                setupSingleMode()
            }
            "grid" -> {
                scrollRecyclerView.visibility = View.GONE
                overlayView.visibility = View.GONE
                setupGridMode()
            }
            else -> {
                scrollRecyclerView.visibility = View.VISIBLE
                overlayView.visibility = View.VISIBLE
            }
        }

        updateThumbnailVisibility()
        if (pageCount > 0) {
            notifyAdapters()
        }
    }

    private fun setupSingleMode() {
        val pager = ViewPager2(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val adapter = SinglePageAdapter(this)
        pager.adapter = adapter
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position != currentPageIndex) {
                    currentPageIndex = position
                    emitPageChanged(position, pageCount)
                    highlightThumbnail(position)
                }
            }
        })

        // Insert before overlay
        addView(pager, childCount - 1)
        viewPager = pager
        pagerAdapter = adapter
    }

    private fun setupGridMode() {
        val rv = RecyclerView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            layoutManager = GridLayoutManager(context, 2)
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            clipChildren = false
        }
        val adapter = GridPageAdapter(this,
            onPageTap = { position ->
                displayMode = "scroll"
                post { goToPage(position) }
            },
            onRotatePage = { position, degrees -> rotatePage(position, degrees) },
            onDeletePage = { position -> deletePage(position) },
            onInsertImage = { position -> requestInsertImage(position) }
        )
        rv.adapter = adapter
        addView(rv, childCount - 1)
        gridRecyclerView = rv
        gridAdapter = adapter

        // Drag & drop reorder
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            private var dragFrom = -1
            private var dragTo = -1

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                dragTo = to
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = true

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                when (actionState) {
                    ItemTouchHelper.ACTION_STATE_DRAG -> {
                        dragFrom = viewHolder?.bindingAdapterPosition ?: -1
                        dragTo = dragFrom
                        viewHolder?.itemView?.animate()?.scaleX(1.1f)?.scaleY(1.1f)?.setDuration(150)?.start()
                        viewHolder?.itemView?.elevation = 12f * resources.displayMetrics.density
                    }
                    ItemTouchHelper.ACTION_STATE_IDLE -> {
                        if (dragFrom >= 0 && dragTo >= 0 && dragFrom != dragTo) {
                            reorderPages(dragFrom, dragTo)
                        }
                        dragFrom = -1
                        dragTo = -1
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                viewHolder.itemView.elevation = 0f
            }
        }
        val helper = ItemTouchHelper(touchCallback)
        helper.attachToRecyclerView(rv)
        gridItemTouchHelper = helper
    }

    private fun notifyAdapters() {
        when (currentDisplayMode) {
            "single" -> {
                pagerAdapter?.notifyDataSetChanged()
                viewPager?.setCurrentItem(currentPageIndex, false)
            }
            "grid" -> {
                gridAdapter?.notifyDataSetChanged()
            }
            else -> {
                scrollAdapter?.notifyDataSetChanged()
            }
        }
    }

    // MARK: - Thumbnails

    private fun updateThumbnailVisibility() {
        if (showThumbnails && currentDisplayMode != "grid") {
            if (thumbnailStrip == null) {
                setupThumbnailStrip()
            }
            thumbnailStrip?.visibility = View.VISIBLE
            updateThumbnails()
        } else {
            thumbnailStrip?.visibility = View.GONE
        }
        post(::manuallyLayoutChildren)
    }

    private fun setupThumbnailStrip() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val strip = HorizontalScrollView(context).apply {
            val thumbH = (70 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, thumbH).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.argb(240, 245, 245, 245))
            addView(container)
        }

        thumbnailContainer = container
        thumbnailStrip = strip
        addView(strip)
    }

    private fun updateThumbnails() {
        val container = thumbnailContainer ?: return
        container.removeAllViews()

        if (pageCount == 0) return

        val density = resources.displayMetrics.density
        val thumbW = (44 * density).toInt()
        val thumbH = (58 * density).toInt()
        val margin = (4 * density).toInt()

        for (i in 0 until pageCount) {
            val iv = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).apply {
                    setMargins(margin, 0, margin, 0)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
                setPadding(2, 2, 2, 2)
                setOnClickListener { goToPage(i) }
            }
            if (i == currentPageIndex) {
                iv.setBackgroundColor(Color.argb(255, 0, 122, 255))
            }
            container.addView(iv)

            // Load thumbnail async
            val pageIdx = i
            scope.launch {
                val thumb = withContext(Dispatchers.IO) { renderThumbnail(pageIdx) }
                if (thumb != null && !thumb.isRecycled) {
                    iv.setImageBitmap(thumb)
                }
            }
        }
    }

    private fun highlightThumbnail(index: Int) {
        val container = thumbnailContainer ?: return
        for (i in 0 until container.childCount) {
            val iv = container.getChildAt(i)
            iv.setBackgroundColor(
                if (i == index) Color.argb(255, 0, 122, 255) else Color.WHITE
            )
        }
    }

    // MARK: - Layout

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val w = right - left
        val h = bottom - top
        if (w == 0 || h == 0) return

        val thumbH = if (showThumbnails && currentDisplayMode != "grid" && thumbnailStrip?.visibility == View.VISIBLE) {
            (70 * resources.displayMetrics.density).toInt()
        } else 0

        val contentH = h - thumbH

        scrollRecyclerView.layout(0, 0, w, contentH)
        viewPager?.layout(0, 0, w, contentH)
        gridRecyclerView?.layout(0, 0, w, contentH)
        overlayView.layout(0, 0, w, contentH)
        thumbnailStrip?.let { it.layout(0, contentH, w, h) }
    }

    // MARK: - Document Loading

    private fun loadDocument() {
        if (pdfUrl.isEmpty()) return

        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    var path = if (pdfUrl.startsWith("file://")) pdfUrl.removePrefix("file://") else pdfUrl
                    path = URLDecoder.decode(path, "UTF-8")
                    File(path)
                }

                if (!file.exists()) {
                    emitDocumentLoadFailed("File not found: $pdfUrl")
                    return@launch
                }

                currentFilePath = file.absolutePath
                closeRenderer()

                withContext(Dispatchers.IO) {
                    fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(fileDescriptor!!)
                }

                val pdfRenderer = renderer!!
                pageCount = pdfRenderer.pageCount

                // Collect page dimensions (lightweight, no bitmap rendering)
                pageDimensions.clear()
                withContext(Dispatchers.IO) {
                    for (i in 0 until pdfRenderer.pageCount) {
                        val page = pdfRenderer.openPage(i)
                        pageDimensions.add(Pair(page.width, page.height))
                        page.close()
                    }
                }

                // Set up scroll adapter if not yet
                if (scrollAdapter == null) {
                    scrollAdapter = ScrollPageAdapter(this@PdfViewerView).apply {
                        spacing = (this@PdfViewerView.spacing * resources.displayMetrics.density).toInt()
                    }
                    scrollRecyclerView.adapter = scrollAdapter
                }

                // Notify adapters
                notifyAdapters()
                updateThumbnails()
                emitDocumentLoaded(pageCount)

                if (pageIndex > 0) {
                    goToPage(pageIndex)
                }
            } catch (e: Exception) {
                emitDocumentLoadFailed(e.message ?: "Unknown error")
            }
        }
    }

    fun goToPage(index: Int) {
        when (currentDisplayMode) {
            "single" -> {
                viewPager?.setCurrentItem(index, true)
            }
            "grid" -> {
                gridRecyclerView?.scrollToPosition(index)
            }
            else -> {
                scrollRecyclerView.scrollToPosition(index)
            }
        }
        highlightThumbnail(index)
    }

    fun zoomTo(scale: Float) {
        scaleFactor = scale.coerceIn(minZoom, maxZoom)
        clampPan()
        applyZoom()
    }

    private fun applyZoom() {
        // Reset pan when back to normal zoom
        if (scaleFactor <= 1.01f) {
            panOffsetX = 0f
            panOffsetY = 0f
            if (isZoomTouchBlockerActive) {
                scrollRecyclerView.removeOnItemTouchListener(zoomTouchBlocker)
                isZoomTouchBlockerActive = false
            }
        } else {
            // Block RecyclerView scrolling while zoomed — we handle pan ourselves
            if (!isZoomTouchBlockerActive) {
                scrollRecyclerView.addOnItemTouchListener(zoomTouchBlocker)
                isZoomTouchBlockerActive = true
            }
        }
        // Apply scale and pan to the current content view
        when (currentDisplayMode) {
            "scroll" -> {
                scrollRecyclerView.scaleX = scaleFactor
                scrollRecyclerView.scaleY = scaleFactor
                scrollRecyclerView.pivotX = scrollRecyclerView.width / 2f
                scrollRecyclerView.pivotY = scrollRecyclerView.height / 2f
                scrollRecyclerView.translationX = panOffsetX
                scrollRecyclerView.translationY = panOffsetY

                // Keep overlay aligned with zoomed content
                overlayView.scaleX = scaleFactor
                overlayView.scaleY = scaleFactor
                overlayView.pivotX = overlayView.width / 2f
                overlayView.pivotY = overlayView.height / 2f
                overlayView.translationX = panOffsetX
                overlayView.translationY = panOffsetY
            }
            else -> { /* ViewPager2 and grid handle their own zoom */ }
        }
    }

    private fun clampPan() {
        val maxPanX = scrollRecyclerView.width * (scaleFactor - 1) / 2
        val maxPanY = scrollRecyclerView.height * (scaleFactor - 1) / 2
        panOffsetX = panOffsetX.coerceIn(-maxPanX, maxPanX)
        panOffsetY = panOffsetY.coerceIn(-maxPanY, maxPanY)
    }

    private fun detectCurrentPage() {
        val layoutManager = scrollRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return

        // Pick the page whose center is closest to viewport center
        val viewportCenter = scrollRecyclerView.height / 2
        var bestPage = firstVisible
        var bestDist = Int.MAX_VALUE

        for (i in firstVisible..lastVisible) {
            val child = layoutManager.findViewByPosition(i) ?: continue
            val childCenter = (child.top + child.bottom) / 2
            val dist = Math.abs(childCenter - viewportCenter)
            if (dist < bestDist) {
                bestDist = dist
                bestPage = i
            }
        }

        if (bestPage != currentPageIndex) {
            currentPageIndex = bestPage
            emitPageChanged(bestPage, pageCount)
            highlightThumbnail(bestPage)
        }
    }

    private fun handleLongPress(event: MotionEvent) {
        if (currentDisplayMode != "scroll") return
        val layoutManager = scrollRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()

        for (i in first..last) {
            val child = layoutManager.findViewByPosition(i) ?: continue
            if (event.y >= child.top && event.y <= child.bottom) {
                val normalizedX = event.x / child.width.toFloat()
                val normalizedY = (event.y - child.top) / child.height.toFloat()
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
            if (currentDisplayMode != "scroll") return
            val layoutManager = scrollRecyclerView.layoutManager as? LinearLayoutManager ?: return
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()

            for (i in first..last) {
                val child = layoutManager.findViewByPosition(i) ?: continue
                if (event.y >= child.top && event.y <= child.bottom) {
                    val normalizedX = event.x / child.width.toFloat()
                    val normalizedY = (event.y - child.top) / child.height.toFloat()
                    emitTap(i, normalizedX.toDouble(), normalizedY.toDouble())
                    break
                }
            }
        }
    }

    private fun hitTestOverlay(touchX: Float, touchY: Float): OverlayItemData? {
        if (currentDisplayMode != "scroll") return null
        val layoutManager = scrollRecyclerView.layoutManager as? LinearLayoutManager ?: return null

        for (item in overlayItems.reversed()) {
            val child = layoutManager.findViewByPosition(item.pageIndex) ?: continue
            val pw = child.width.toFloat()
            val ph = child.height.toFloat()
            val pageTop = child.top.toFloat()

            val left = item.x * pw - 4
            val top = pageTop + item.y * ph - 4
            val right = (item.x + item.width) * pw + 4
            val bottom = pageTop + (item.y + item.height) * ph + 4

            if (touchX in left..right && touchY in top..bottom) {
                return item
            }
        }
        return null
    }

    // Used by OverlayView to find page positions
    fun getScrollPageViewPosition(pageIndex: Int): View? {
        if (currentDisplayMode != "scroll") return null
        val layoutManager = scrollRecyclerView.layoutManager as? LinearLayoutManager ?: return null
        return layoutManager.findViewByPosition(pageIndex)
    }

    fun getCurrentScrollY(): Int = 0 // Not applicable with RecyclerView; overlay uses child positions directly
    fun getOverlayItems(): List<OverlayItemData> = overlayItems
    fun getVisiblePageRange(): Pair<Int, Int> {
        val layoutManager = scrollRecyclerView.layoutManager as? LinearLayoutManager
            ?: return Pair(0, 0)
        return Pair(
            layoutManager.findFirstVisibleItemPosition().coerceAtLeast(0),
            layoutManager.findLastVisibleItemPosition().coerceAtLeast(0)
        )
    }

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
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onPageChanged", event)
    }

    private fun emitDocumentLoaded(pageCount: Int) {
        val event = Arguments.createMap().apply {
            putInt("pageCount", pageCount)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onDocumentLoaded", event)
    }

    private fun emitDocumentLoadFailed(error: String) {
        val event = Arguments.createMap().apply {
            putString("error", error)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onDocumentLoadFailed", event)
    }

    private fun emitLongPress(pageIndex: Int, x: Double, y: Double) {
        val event = Arguments.createMap().apply {
            putInt("pageIndex", pageIndex)
            putDouble("x", x)
            putDouble("y", y)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onLongPress", event)
    }

    fun emitOverlayTap(overlayId: String, pageIndex: Int) {
        val event = Arguments.createMap().apply {
            putString("id", overlayId)
            putInt("pageIndex", pageIndex)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onOverlayTap", event)
    }

    fun emitTap(pageIndex: Int, x: Double, y: Double) {
        val event = Arguments.createMap().apply {
            putInt("pageIndex", pageIndex)
            putDouble("x", x)
            putDouble("y", y)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onTap", event)
    }

    fun emitOverlayMoved(overlayId: String, x: Double, y: Double) {
        val event = Arguments.createMap().apply {
            putString("id", overlayId)
            putDouble("x", x)
            putDouble("y", y)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onOverlayMoved", event)
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
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onOverlayResized", event)
    }

    // MARK: - Grid: PDF Manipulation

    private fun ensurePdfBoxInit() {
        if (!pdfBoxInitialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
        }
    }

    private fun reorderPages(fromIdx: Int, toIdx: Int) {
        val filePath = currentFilePath ?: return
        scope.launch {
            try {
                val newPath = withContext(Dispatchers.IO) {
                    ensurePdfBoxInit()
                    val doc = PDDocument.load(File(filePath))
                    val page = doc.getPage(fromIdx)
                    doc.removePage(fromIdx)
                    if (toIdx < doc.numberOfPages) {
                        doc.pages.insertBefore(page, doc.getPage(toIdx))
                    } else {
                        doc.addPage(page)
                    }

                    val outFile = createTempPdfFile()
                    doc.save(outFile)
                    doc.close()
                    outFile.absolutePath
                }
                // Remap caches to match new page order
                remapCacheAfterReorder(bitmapCache, fromIdx, toIdx)
                remapCacheAfterReorder(thumbCache, fromIdx, toIdx)
                // Reorder dimensions to match
                if (fromIdx < pageDimensions.size) {
                    val dim = pageDimensions.removeAt(fromIdx)
                    val insertAt = minOf(toIdx, pageDimensions.size)
                    pageDimensions.add(insertAt, dim)
                }
                swapRendererOnly(newPath)
                emitDocumentChanged("file://$newPath", pageCount)
            } catch (e: Exception) {
                Log.e("Neurodoc", "reorderPages failed", e)
                gridAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun rotatePage(pageIdx: Int, degrees: Int) {
        val filePath = currentFilePath ?: return
        scope.launch {
            try {
                val newPath = withContext(Dispatchers.IO) {
                    ensurePdfBoxInit()
                    val doc = PDDocument.load(File(filePath))
                    val page = doc.getPage(pageIdx)
                    page.rotation = (page.rotation + degrees + 360) % 360
                    val outFile = createTempPdfFile()
                    doc.save(outFile)
                    doc.close()
                    outFile.absolutePath
                }
                // Only evict the rotated page (keep all other pages cached!)
                bitmapCache.remove(pageIdx)
                thumbCache.remove(pageIdx)
                swapRendererOnly(newPath)
                // Update dimension in-place (swap w/h for 90/270)
                if (pageIdx < pageDimensions.size && (degrees % 180 != 0)) {
                    val (w, h) = pageDimensions[pageIdx]
                    pageDimensions[pageIdx] = Pair(h, w)
                }
                // Pre-render the rotated page so there's no white flash
                val gridWidth = (width / 2).coerceAtLeast(200)
                withContext(Dispatchers.IO) { renderPage(pageIdx, gridWidth) }
                gridAdapter?.notifyItemChanged(pageIdx)
                emitDocumentChanged("file://$newPath", pageCount)
            } catch (e: Exception) {
                Log.e("Neurodoc", "rotatePage failed", e)
            }
        }
    }

    private fun deletePage(pageIdx: Int) {
        if (pageCount <= 1) return // don't delete last page
        val filePath = currentFilePath ?: return
        scope.launch {
            try {
                val newPath = withContext(Dispatchers.IO) {
                    ensurePdfBoxInit()
                    val doc = PDDocument.load(File(filePath))
                    doc.removePage(pageIdx)
                    val outFile = createTempPdfFile()
                    doc.save(outFile)
                    doc.close()
                    outFile.absolutePath
                }
                // Remap caches: remove deleted page, shift higher keys down
                remapCacheAfterDelete(bitmapCache, pageIdx)
                remapCacheAfterDelete(thumbCache, pageIdx)
                swapRendererOnly(newPath)
                if (pageIdx < pageDimensions.size) {
                    pageDimensions.removeAt(pageIdx)
                }
                gridAdapter?.notifyItemRemoved(pageIdx)
                emitDocumentChanged("file://$newPath", pageCount)
            } catch (e: Exception) {
                Log.e("Neurodoc", "deletePage failed", e)
            }
        }
    }

    private fun requestInsertImage(afterPageIdx: Int) {
        insertTargetIndex = afterPageIdx + 1
        val reactContext = context as? ReactContext ?: return
        val activity = reactContext.currentActivity as? ComponentActivity ?: return

        // Unregister previous launcher if any
        imagePickLauncher?.unregister()

        // Use ActivityResultRegistry — works on all Android versions without onActivityResult
        val key = "neurodoc_pick_${System.currentTimeMillis()}"
        imagePickLauncher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.GetContent()
        ) { uri ->
            Log.d("Neurodoc", "Image pick result: $uri")
            if (uri != null) {
                handleImagePicked(uri)
            }
            imagePickLauncher?.unregister()
            imagePickLauncher = null
        }
        imagePickLauncher?.launch("image/*")
    }

    private fun handleImagePicked(uri: Uri) {
        val filePath = currentFilePath ?: return
        val targetIdx = insertTargetIndex
        scope.launch {
            try {
                data class InsertResult(val path: String, val pageW: Int, val pageH: Int)
                val result = withContext(Dispatchers.IO) {
                    ensurePdfBoxInit()
                    val doc = PDDocument.load(File(filePath))

                    // Decode image from URI with explicit ARGB_8888 for PDFBox compatibility
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open image URI: $uri")
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, opts)
                    inputStream.close()
                    if (bitmap == null) throw Exception("Failed to decode image bitmap")

                    // Match existing page size or use A4
                    val pageSize = if (doc.numberOfPages > 0) {
                        val firstPage = doc.getPage(0)
                        PDRectangle(firstPage.mediaBox.width, firstPage.mediaBox.height)
                    } else {
                        PDRectangle.A4
                    }

                    val newPage = PDPage(pageSize)
                    val pdImage = LosslessFactory.createFromImage(doc, bitmap)

                    // Aspect-fit image into page
                    val scaleX = pageSize.width / bitmap.width.toFloat()
                    val scaleY = pageSize.height / bitmap.height.toFloat()
                    val scale = minOf(scaleX, scaleY)
                    val drawW = bitmap.width * scale
                    val drawH = bitmap.height * scale
                    val drawX = (pageSize.width - drawW) / 2
                    val drawY = (pageSize.height - drawH) / 2

                    val cs = PDPageContentStream(doc, newPage)
                    cs.setNonStrokingColor(1f, 1f, 1f)
                    cs.addRect(0f, 0f, pageSize.width, pageSize.height)
                    cs.fill()
                    cs.drawImage(pdImage, drawX, drawY, drawW, drawH)
                    cs.close()

                    // Insert page at target position
                    val idx = minOf(targetIdx, doc.numberOfPages)
                    if (idx >= doc.numberOfPages) {
                        doc.addPage(newPage)
                    } else {
                        doc.pages.insertBefore(newPage, doc.getPage(idx))
                    }

                    bitmap.recycle()
                    val outFile = createTempPdfFile()
                    doc.save(outFile)
                    doc.close()
                    InsertResult(outFile.absolutePath, pageSize.width.toInt(), pageSize.height.toInt())
                }
                // Remap caches: shift keys at/after insert position up
                val idx = minOf(targetIdx, pageDimensions.size)
                remapCacheAfterInsert(bitmapCache, idx)
                remapCacheAfterInsert(thumbCache, idx)
                swapRendererOnly(result.path)
                pageDimensions.add(idx, Pair(result.pageW, result.pageH))
                gridAdapter?.notifyItemInserted(idx)
                emitDocumentChanged("file://${result.path}", pageCount)
            } catch (e: Exception) {
                Log.e("Neurodoc", "handleImagePicked failed", e)
            }
        }
    }

    private fun createTempPdfFile(): File {
        val dir = File(context.cacheDir, "neurodoc")
        dir.mkdirs()
        return File(dir, "${UUID.randomUUID()}.pdf")
    }

    /** Remap cache keys after a page deletion: remove deleted idx, shift higher keys down */
    private fun remapCacheAfterDelete(cache: LruCache<Int, Bitmap>, deletedIdx: Int) {
        val snapshot = cache.snapshot()
        cache.evictAll()
        for ((key, bitmap) in snapshot) {
            when {
                key < deletedIdx -> cache.put(key, bitmap)
                key > deletedIdx -> cache.put(key - 1, bitmap)
                // key == deletedIdx: skip (deleted page)
            }
        }
    }

    /** Remap cache keys after a page insertion: shift keys at/after insertIdx up */
    private fun remapCacheAfterInsert(cache: LruCache<Int, Bitmap>, insertedIdx: Int) {
        val snapshot = cache.snapshot()
        cache.evictAll()
        for ((key, bitmap) in snapshot) {
            if (key >= insertedIdx) {
                cache.put(key + 1, bitmap)
            } else {
                cache.put(key, bitmap)
            }
        }
    }

    /** Remap cache keys after a page reorder (move fromIdx to toIdx) */
    private fun remapCacheAfterReorder(cache: LruCache<Int, Bitmap>, fromIdx: Int, toIdx: Int) {
        val snapshot = cache.snapshot()
        cache.evictAll()
        for ((key, bitmap) in snapshot) {
            val newKey = when {
                key == fromIdx -> toIdx
                fromIdx < toIdx && key > fromIdx && key <= toIdx -> key - 1
                fromIdx > toIdx && key >= toIdx && key < fromIdx -> key + 1
                else -> key
            }
            cache.put(newKey, bitmap)
        }
    }

    /**
     * Lightweight renderer swap: closes old renderer & opens new file.
     * Does NOT evict caches, does NOT re-read page dimensions.
     * Callers must manage caches and pageDimensions themselves.
     */
    private suspend fun swapRendererOnly(newFilePath: String) {
        currentFilePath = newFilePath
        lastSavedUrl = "file://$newFilePath"
        // Close renderer without evicting caches
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null

        withContext(Dispatchers.IO) {
            val file = File(newFilePath)
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor!!)
        }
        pageCount = renderer!!.pageCount
    }

    private fun emitDocumentChanged(pdfUrl: String, pageCount: Int) {
        val event = Arguments.createMap().apply {
            putString("pdfUrl", pdfUrl)
            putInt("pageCount", pageCount)
        }
        val reactContext = context as? ReactContext ?: return
        reactContext.getJSModule(RCTModernEventEmitter::class.java)
            .receiveEvent(UIManagerHelper.getSurfaceId(this), id, "onDocumentChanged", event)
    }

    private fun closeRenderer() {
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
        bitmapCache.evictAll()
        thumbCache.evictAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closeRenderer()
        imagePickLauncher?.unregister()
        imagePickLauncher = null
        scope.cancel()
    }

    // ========================================================================
    // MARK: - Scroll Mode Adapter (RecyclerView + lazy page rendering)
    // ========================================================================

    private class ScrollPageAdapter(
        private val pdfViewer: PdfViewerView
    ) : RecyclerView.Adapter<ScrollPageAdapter.PageVH>() {
        var spacing: Int = 0

        override fun getItemCount(): Int = pdfViewer.getPageCount()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val iv = ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
            }
            return PageVH(iv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val iv = holder.itemView as ImageView
            val params = iv.layoutParams as RecyclerView.LayoutParams
            params.bottomMargin = spacing

            // Set estimated height from page dimensions to avoid jumpy scrolling
            val dims = pdfViewer.getPageDimensions()
            if (position < dims.size) {
                val (pw, ph) = dims[position]
                val containerWidth = pdfViewer.width.coerceAtLeast(1)
                val ratio = pw.toFloat() / ph.toFloat()
                params.height = (containerWidth / ratio).toInt()
            }
            iv.layoutParams = params

            // Check bitmap cache first (synchronous)
            val cached = pdfViewer.bitmapCache.get(position)
            if (cached != null && !cached.isRecycled) {
                iv.setImageBitmap(cached)
                return
            }

            // Placeholder while loading
            iv.setImageBitmap(null)

            // Render async
            val targetWidth = pdfViewer.width.coerceAtLeast(720)
            pdfViewer.scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    pdfViewer.renderPage(position, targetWidth)
                }
                if (bitmap != null && !bitmap.isRecycled && holder.bindingAdapterPosition == position) {
                    iv.setImageBitmap(bitmap)
                }
            }
        }

        override fun onViewRecycled(holder: PageVH) {
            (holder.itemView as ImageView).setImageBitmap(null)
        }

        class PageVH(view: View) : RecyclerView.ViewHolder(view)
    }

    // ========================================================================
    // MARK: - Single Page Adapter (ViewPager2)
    // ========================================================================

    private class SinglePageAdapter(
        private val pdfViewer: PdfViewerView
    ) : RecyclerView.Adapter<SinglePageAdapter.PageVH>() {

        override fun getItemCount(): Int = pdfViewer.getPageCount()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
            }
            return PageVH(iv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            val iv = holder.itemView as ImageView

            val cached = pdfViewer.bitmapCache.get(position)
            if (cached != null && !cached.isRecycled) {
                iv.setImageBitmap(cached)
                return
            }

            iv.setImageBitmap(null)

            val targetWidth = pdfViewer.width.coerceAtLeast(720)
            pdfViewer.scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    pdfViewer.renderPage(position, targetWidth)
                }
                if (bitmap != null && !bitmap.isRecycled && holder.bindingAdapterPosition == position) {
                    iv.setImageBitmap(bitmap)
                }
            }
        }

        override fun onViewRecycled(holder: PageVH) {
            (holder.itemView as ImageView).setImageBitmap(null)
        }

        class PageVH(view: View) : RecyclerView.ViewHolder(view)
    }

    // ========================================================================
    // MARK: - Grid Adapter
    // ========================================================================

    private class GridPageAdapter(
        private val pdfViewer: PdfViewerView,
        private val onPageTap: (Int) -> Unit,
        private val onRotatePage: (Int, Int) -> Unit,
        private val onDeletePage: (Int) -> Unit,
        private val onInsertImage: (Int) -> Unit
    ) : RecyclerView.Adapter<GridPageAdapter.GridVH>() {

        override fun getItemCount(): Int = pdfViewer.getPageCount()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridVH {
            val density = parent.resources.displayMetrics.density
            val outerPad = (6 * density).toInt()

            // Outer container with padding to give space for elevation shadow
            val card = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(outerPad, outerPad, outerPad, outerPad)
                clipChildren = false
                clipToPadding = false
            }

            // Inner card: white background, rounded corners, elevation shadow
            val inner = FrameLayout(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(Color.WHITE)
                elevation = 4 * density
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 6 * density)
                    }
                }
            }

            // Vertical content: thumbnail + page label
            val content = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val iv = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (200 * density).toInt()
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.WHITE)
            }

            val label = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.DKGRAY)
                val vPad = (6 * density).toInt()
                setPadding(0, vPad, 0, vPad)
            }

            content.addView(iv)
            content.addView(label)
            inner.addView(content)

            // Menu button — INSIDE inner card so it's not hidden by elevation
            val btnSize = (30 * density).toInt()
            val menuBtn = ImageButton(parent.context).apply {
                layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    val m = (6 * density).toInt()
                    setMargins(m, m, m, 0)
                }
                background = createCircleBackground(density)
                setImageDrawable(createEllipsisDrawable(density))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            inner.addView(menuBtn)

            card.addView(inner)
            return GridVH(card, iv, label, menuBtn)
        }

        private fun createCircleBackground(density: Float): android.graphics.drawable.Drawable {
            val size = (30 * density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - density, paint)
            return android.graphics.drawable.BitmapDrawable(pdfViewer.resources, bitmap)
        }

        private fun createEllipsisDrawable(density: Float): android.graphics.drawable.Drawable {
            val size = (22 * density).toInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(230, 60, 60, 60)
                style = Paint.Style.FILL
            }
            val dotR = 2.5f * density
            val cx = size / 2f
            canvas.drawCircle(cx, size * 0.25f, dotR, paint)
            canvas.drawCircle(cx, size * 0.5f, dotR, paint)
            canvas.drawCircle(cx, size * 0.75f, dotR, paint)
            return android.graphics.drawable.BitmapDrawable(pdfViewer.resources, bitmap)
        }

        override fun onBindViewHolder(holder: GridVH, position: Int) {
            holder.label.text = "Page ${position + 1}"
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPageTap(pos)
            }

            // Menu button
            holder.menuBtn.setOnClickListener { view ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val popup = PopupMenu(view.context, view)
                popup.menu.add(0, 1, 0, "Add Page from Image")
                popup.menu.add(0, 2, 1, "Rotate Right")
                popup.menu.add(0, 3, 2, "Rotate Left")
                popup.menu.add(0, 4, 3, "Delete Page")
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { onInsertImage(pos); true }
                        2 -> { onRotatePage(pos, 90); true }
                        3 -> { onRotatePage(pos, -90); true }
                        4 -> { onDeletePage(pos); true }
                        else -> false
                    }
                }
                popup.show()
            }

            // Render at smaller width for grid thumbnails
            val gridWidth = (pdfViewer.width / 2).coerceAtLeast(200)
            val cached = pdfViewer.bitmapCache.get(position)
            if (cached != null && !cached.isRecycled) {
                holder.imageView.setImageBitmap(cached)
                return
            }

            holder.imageView.setImageBitmap(null)

            pdfViewer.scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    pdfViewer.renderPage(position, gridWidth)
                }
                if (bitmap != null && !bitmap.isRecycled && holder.bindingAdapterPosition == position) {
                    holder.imageView.setImageBitmap(bitmap)
                }
            }
        }

        override fun onViewRecycled(holder: GridVH) {
            holder.imageView.setImageBitmap(null)
        }

        class GridVH(view: View, val imageView: ImageView, val label: TextView, val menuBtn: ImageButton) :
            RecyclerView.ViewHolder(view)
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
        color = Color.argb(38, 51, 204, 77)
        style = Paint.Style.FILL
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 51, 204, 77)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val unselectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 51, 128, 255)
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
    private var resizeCorner = -1
    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizeOrigX = 0f
    private var resizeOrigY = 0f
    private var resizeOrigW = 0f
    private var resizeOrigH = 0f

    // Tap detection state
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var isPendingTap = false
    private val tapSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val items = pdfViewer.getOverlayItems()
        val (firstVisible, lastVisible) = pdfViewer.getVisiblePageRange()

        for (item in items) {
            if (item.pageIndex < firstVisible || item.pageIndex > lastVisible) continue
            val pageView = pdfViewer.getScrollPageViewPosition(item.pageIndex) ?: continue
            val pw = pageView.width.toFloat()
            val ph = pageView.height.toFloat()
            val pageTop = pageView.top.toFloat()

            val left = item.x * pw
            val top = pageTop + item.y * ph
            val right = (item.x + item.width) * pw
            val bottom = pageTop + (item.y + item.height) * ph

            val rect = RectF(left, top, right, bottom)

            if (item.selected) {
                canvas.drawRect(rect, selectedPaint)
                canvas.drawRect(rect, selectedStroke)
                drawHandle(canvas, left, top)
                drawHandle(canvas, right, top)
                drawHandle(canvas, left, bottom)
                drawHandle(canvas, right, bottom)
            } else {
                canvas.drawRect(rect, unselectedPaint)
                canvas.drawRect(rect, unselectedStroke)
            }

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

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val items = pdfViewer.getOverlayItems()
                val (firstVisible, lastVisible) = pdfViewer.getVisiblePageRange()

                // Check resize handles first
                for (item in items.reversed()) {
                    if (!item.selected) continue
                    if (item.pageIndex < firstVisible || item.pageIndex > lastVisible) continue
                    val pageView = pdfViewer.getScrollPageViewPosition(item.pageIndex) ?: continue
                    val pw = pageView.width.toFloat()
                    val ph = pageView.height.toFloat()
                    val pageTop = pageView.top.toFloat()

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
                            isPendingTap = false
                            parent.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }

                // Check drag
                for (item in items.reversed()) {
                    if (item.pageIndex < firstVisible || item.pageIndex > lastVisible) continue
                    val pageView = pdfViewer.getScrollPageViewPosition(item.pageIndex) ?: continue
                    val pw = pageView.width.toFloat()
                    val ph = pageView.height.toFloat()
                    val pageTop = pageView.top.toFloat()

                    val left = item.x * pw
                    val top = pageTop + item.y * ph
                    val right = (item.x + item.width) * pw
                    val bottom = pageTop + (item.y + item.height) * ph

                    if (event.x in left..right && event.y in top..bottom) {
                        draggedId = item.id
                        dragStartX = event.x
                        dragStartY = event.y
                        dragOrigX = item.x
                        dragOrigY = item.y
                        dragPageIndex = item.pageIndex
                        isPendingTap = false
                        parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                // No overlay hit — consume for tap detection
                pendingTapX = event.x
                pendingTapY = event.y
                isPendingTap = true
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (resizeId != null) {
                    updateResize(event.x, event.y)
                    return true
                }
                if (draggedId != null) {
                    updateDrag(event.x, event.y)
                    return true
                }
                if (isPendingTap) {
                    val dx = event.x - pendingTapX
                    val dy = event.y - pendingTapY
                    if (dx * dx + dy * dy > tapSlop * tapSlop) {
                        // Moved too far — not a tap, let parent scroll
                        isPendingTap = false
                        parent.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
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
                if (isPendingTap && event.action == MotionEvent.ACTION_UP) {
                    isPendingTap = false
                    parent.requestDisallowInterceptTouchEvent(false)
                    dispatchTap(event.x, event.y)
                    return true
                }
                isPendingTap = false
                parent.requestDisallowInterceptTouchEvent(false)
                return false
            }
        }
        return false
    }

    private fun dispatchTap(touchX: Float, touchY: Float) {
        // Check overlay hit first
        val items = pdfViewer.getOverlayItems()
        val (firstVisible, lastVisible) = pdfViewer.getVisiblePageRange()

        for (item in items.reversed()) {
            if (item.pageIndex < firstVisible || item.pageIndex > lastVisible) continue
            val pageView = pdfViewer.getScrollPageViewPosition(item.pageIndex) ?: continue
            val pw = pageView.width.toFloat()
            val ph = pageView.height.toFloat()
            val pageTop = pageView.top.toFloat()

            val left = item.x * pw - 4
            val top = pageTop + item.y * ph - 4
            val right = (item.x + item.width) * pw + 4
            val bottom = pageTop + (item.y + item.height) * ph + 4

            if (touchX in left..right && touchY in top..bottom) {
                pdfViewer.emitOverlayTap(item.id, item.pageIndex)
                return
            }
        }

        // No overlay hit — find the page and emit tap with normalized coordinates
        val (first, last) = pdfViewer.getVisiblePageRange()

        for (i in first..last) {
            val child = pdfViewer.getScrollPageViewPosition(i) ?: continue
            if (touchY >= child.top && touchY <= child.bottom) {
                val normalizedX = touchX / child.width.toFloat()
                val normalizedY = (touchY - child.top) / child.height.toFloat()
                pdfViewer.emitTap(i, normalizedX.toDouble(), normalizedY.toDouble())
                return
            }
        }
    }

    private fun updateDrag(touchX: Float, touchY: Float) {
        val pageView = pdfViewer.getScrollPageViewPosition(dragPageIndex) ?: return
        val pw = pageView.width.toFloat()
        val ph = pageView.height.toFloat()
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

    private fun updateResize(touchX: Float, touchY: Float) {
        val items = pdfViewer.getOverlayItems().toMutableList()
        val idx = items.indexOfFirst { it.id == resizeId }
        if (idx < 0) return
        val item = items[idx]
        val pageView = pdfViewer.getScrollPageViewPosition(item.pageIndex) ?: return
        val pw = pageView.width.toFloat()
        val ph = pageView.height.toFloat()
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
            3 -> {
                newW = maxOf(minW, resizeOrigW + dx)
                newH = maxOf(minH, resizeOrigH + dy)
            }
            2 -> {
                val proposedW = resizeOrigW - dx
                if (proposedW >= minW) { newX = resizeOrigX + dx; newW = proposedW }
                newH = maxOf(minH, resizeOrigH + dy)
            }
            1 -> {
                newW = maxOf(minW, resizeOrigW + dx)
                val proposedH = resizeOrigH - dy
                if (proposedH >= minH) { newY = resizeOrigY + dy; newH = proposedH }
            }
            0 -> {
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
