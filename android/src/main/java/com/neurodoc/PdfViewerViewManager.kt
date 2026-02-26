package com.neurodoc

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.common.MapBuilder
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

@ReactModule(name = PdfViewerViewManager.NAME)
class PdfViewerViewManager : SimpleViewManager<PdfViewerView>() {

    override fun getName(): String = NAME

    override fun createViewInstance(reactContext: ThemedReactContext): PdfViewerView {
        return PdfViewerView(reactContext)
    }

    @ReactProp(name = "pdfUrl")
    fun setPdfUrl(view: PdfViewerView, url: String?) {
        view.pdfUrl = url ?: ""
    }

    @ReactProp(name = "pageIndex", defaultInt = 0)
    fun setPageIndex(view: PdfViewerView, index: Int) {
        view.pageIndex = index
    }

    @ReactProp(name = "spacing", defaultFloat = 8f)
    fun setSpacing(view: PdfViewerView, spacing: Float) {
        view.spacing = spacing.toInt()
    }

    @ReactProp(name = "showScrollIndicator", defaultBoolean = true)
    fun setShowScrollIndicator(view: PdfViewerView, show: Boolean) {
        view.showScrollIndicator = show
    }

    @ReactProp(name = "minZoom", defaultFloat = 1f)
    fun setMinZoom(view: PdfViewerView, zoom: Float) {
        view.minZoom = zoom
    }

    @ReactProp(name = "maxZoom", defaultFloat = 4f)
    fun setMaxZoom(view: PdfViewerView, zoom: Float) {
        view.maxZoom = zoom
    }

    @ReactProp(name = "textOverlays")
    fun setTextOverlays(view: PdfViewerView, json: String?) {
        view.setTextOverlays(json)
    }

    @ReactProp(name = "enableOverlayTap", defaultBoolean = false)
    fun setEnableOverlayTap(view: PdfViewerView, enabled: Boolean) {
        view.enableOverlayTap = enabled
    }

    @ReactProp(name = "disableSelection", defaultBoolean = false)
    fun setDisableSelection(view: PdfViewerView, disabled: Boolean) {
        view.disableSelection = disabled
    }

    override fun getCommandsMap(): Map<String, Int> {
        return mapOf(
            "goToPage" to CMD_GO_TO_PAGE,
            "zoomTo" to CMD_ZOOM_TO
        )
    }

    override fun receiveCommand(view: PdfViewerView, commandId: String, args: ReadableArray?) {
        when (commandId) {
            "goToPage" -> {
                val pageIndex = args?.getInt(0) ?: 0
                view.goToPage(pageIndex)
            }
            "zoomTo" -> {
                val scale = args?.getDouble(0)?.toFloat() ?: 1f
                view.zoomTo(scale)
            }
        }
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any>? {
        return MapBuilder.builder<String, Any>()
            .put("onPageChanged", MapBuilder.of("registrationName", "onPageChanged"))
            .put("onDocumentLoaded", MapBuilder.of("registrationName", "onDocumentLoaded"))
            .put("onDocumentLoadFailed", MapBuilder.of("registrationName", "onDocumentLoadFailed"))
            .put("onLongPress", MapBuilder.of("registrationName", "onLongPress"))
            .put("onOverlayTap", MapBuilder.of("registrationName", "onOverlayTap"))
            .put("onTap", MapBuilder.of("registrationName", "onTap"))
            .put("onOverlayMoved", MapBuilder.of("registrationName", "onOverlayMoved"))
            .put("onOverlayResized", MapBuilder.of("registrationName", "onOverlayResized"))
            .build()
    }

    companion object {
        const val NAME = "NeurodocPdfViewerView"
        private const val CMD_GO_TO_PAGE = 1
        private const val CMD_ZOOM_TO = 2
    }
}
