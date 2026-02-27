import PDFKit
import PhotosUI
import React
import UIKit

private var kPdfViewerViewKey: UInt8 = 0
private var kOriginalBuildMenuKey: UInt8 = 0

@objcMembers
public class PdfViewerView: UIView {
    private var pdfView: PDFView!
    private var thumbnailView: PDFThumbnailView!
    private var gridCollectionView: UICollectionView!
    private var currentDocument: PDFDocument?
    private var thumbnailCache = NSCache<NSNumber, UIImage>()
    private var lastSavedUrl: String?
    private var insertTargetIndex: Int = 0
    private weak var observedScrollView: UIScrollView?

    private let thumbnailHeight: CGFloat = 80
    private static let gridCellID = "GridPageCell"

    // Overlay
    private var overlayView: OverlayDrawingView!
    private var overlayItems: [OverlayItem] = []
    private var tapGesture: UITapGestureRecognizer?
    private var longPressGesture: UILongPressGestureRecognizer?  // our onLongPress callback
    private var overlayDragGesture: UILongPressGestureRecognizer? // long press → drag/resize overlays

    // Drag state
    private var draggedItemId: String?
    private var dragStartPoint: CGPoint = .zero
    private var dragOriginalRect: CGRect = .zero  // in view coords
    private var dragOriginalNormalized: (x: CGFloat, y: CGFloat) = (0, 0)
    private var dragPageIndex: Int = 0

    // Resize state
    private enum ResizeHandle { case none, topLeft, topRight, bottomLeft, bottomRight }
    private var resizeHandle: ResizeHandle = .none
    private var resizeItemId: String?
    private var resizeOriginalNormalized: (x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat) = (0, 0, 0, 0)
    private var resizeStartPoint: CGPoint = .zero
    private var resizePageBounds: CGRect = .zero  // page bounds in view coords

    // MARK: - Props

    public var pdfUrl: String = "" {
        didSet {
            if pdfUrl != oldValue {
                if pdfUrl == lastSavedUrl {
                    lastSavedUrl = nil
                    return
                }
                loadDocument()
            }
        }
    }

    public var pageIndex: Int = 0 {
        didSet {
            if pageIndex != oldValue { goToPageInternal(pageIndex) }
        }
    }

    public var spacing: CGFloat = 8 {
        didSet { pdfView.pageBreakMargins = UIEdgeInsets(top: spacing, left: 0, bottom: spacing, right: 0) }
    }

    public var showScrollIndicator: Bool = true {
        didSet {
            if let scrollView = findScrollView() {
                scrollView.showsVerticalScrollIndicator = showScrollIndicator
                scrollView.showsHorizontalScrollIndicator = showScrollIndicator
            }
        }
    }

    public var minZoom: CGFloat = 1.0 {
        didSet { applyZoomLimits() }
    }

    public var maxZoom: CGFloat = 4.0 {
        didSet { applyZoomLimits() }
    }

    public var displayMode: NSString = "scroll" {
        didSet {
            applyDisplayMode()
        }
    }

    public var showThumbnails: Bool = false {
        didSet {
            thumbnailView.isHidden = !showThumbnails
            setNeedsLayout()
        }
    }

    // MARK: - Overlay Props

    public var textOverlays: NSString = "" {
        didSet { parseOverlays() }
    }

    public var enableOverlayTap: Bool = false {
        didSet {
            overlayView?.interactiveOverlays = enableOverlayTap
        }
    }

    public var disableSelection: Bool = false {
        didSet {
            applySelectionState()
        }
    }

    // MARK: - Callbacks

    public var onPageChanged: RCTDirectEventBlock?
    public var onDocumentLoaded: RCTDirectEventBlock?
    public var onDocumentLoadFailed: RCTDirectEventBlock?
    public var onLongPress: RCTDirectEventBlock?
    public var onDocumentChanged: RCTDirectEventBlock?
    public var onOverlayTap: RCTDirectEventBlock?
    public var onTap: RCTDirectEventBlock?
    public var onOverlayMoved: RCTDirectEventBlock?
    public var onOverlayResized: RCTDirectEventBlock?
    public var onTextSelected: RCTDirectEventBlock?
    public var onAddFieldFromSelection: RCTDirectEventBlock?

    // MARK: - Init

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupViews()
    }

    private func setupViews() {
        thumbnailCache.countLimit = 100

        // PDF View
        pdfView = PDFView()
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.autoScales = true
        pdfView.pageBreakMargins = UIEdgeInsets(top: spacing, left: 0, bottom: spacing, right: 0)
        addSubview(pdfView)

        // Thumbnail View
        thumbnailView = PDFThumbnailView()
        thumbnailView.pdfView = pdfView
        thumbnailView.thumbnailSize = CGSize(width: 44, height: 60)
        thumbnailView.layoutMode = .horizontal
        thumbnailView.backgroundColor = UIColor(white: 0.95, alpha: 1)
        thumbnailView.isHidden = !showThumbnails
        addSubview(thumbnailView)

        // Grid Collection View
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 8
        layout.minimumLineSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)

        gridCollectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        gridCollectionView.backgroundColor = UIColor(white: 0.95, alpha: 1)
        gridCollectionView.register(GridPageCell.self, forCellWithReuseIdentifier: PdfViewerView.gridCellID)
        gridCollectionView.dataSource = self
        gridCollectionView.delegate = self
        gridCollectionView.isHidden = true
        addSubview(gridCollectionView)

        // Long press + drag gesture for reordering pages
        let reorderGesture = UILongPressGestureRecognizer(target: self, action: #selector(handleGridReorder(_:)))
        reorderGesture.minimumPressDuration = 0.5
        gridCollectionView.addGestureRecognizer(reorderGesture)

        // Overlay drawing view — sits on top of pdfView, intercepts touches on overlays
        overlayView = OverlayDrawingView()
        overlayView.isUserInteractionEnabled = true
        overlayView.backgroundColor = .clear
        addSubview(overlayView)

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(pageChanged),
            name: .PDFViewPageChanged,
            object: pdfView
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(overlayNeedsRedraw),
            name: .PDFViewScaleChanged,
            object: pdfView
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(overlayNeedsRedraw),
            name: .PDFViewAnnotationHit,
            object: pdfView
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleSelectionChanged),
            name: .PDFViewSelectionChanged,
            object: pdfView
        )

        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        pdfView.addGestureRecognizer(longPress)
        longPressGesture = longPress

        // Tap gesture for overlay — on overlayView so it fires above pdfView
        let tap = UITapGestureRecognizer(target: self, action: #selector(handleOverlayTap(_:)))
        overlayView.addGestureRecognizer(tap)
        tapGesture = tap

        // Single long-press gesture for both drag and resize — on overlayView
        // Short press duration so it feels responsive on handles
        let dragLp = UILongPressGestureRecognizer(target: self, action: #selector(handleOverlayInteraction(_:)))
        dragLp.minimumPressDuration = 0.15
        dragLp.allowableMovement = 10
        overlayView.addGestureRecognizer(dragLp)
        overlayDragGesture = dragLp

        // Tap gesture for empty area (onTap callback) — on pdfView so it fires when overlayView passes through
        let pdfTap = UITapGestureRecognizer(target: self, action: #selector(handlePdfTap(_:)))
        pdfView.addGestureRecognizer(pdfTap)

        // Observe scroll for overlay sync
        addScrollViewObserver()
    }

    public override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey: Any]?, context: UnsafeMutableRawPointer?) {
        if keyPath == "contentOffset" {
            redrawOverlays()
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
        }
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        removeScrollViewObserver()
    }

    private func applySelectionState() {
        // Our own gesture recognizers - identified by stored references
        let ownGestures: Set<ObjectIdentifier> = [
            tapGesture, longPressGesture, overlayDragGesture
        ].compactMap { $0 }.map { ObjectIdentifier($0) }.reduce(into: Set<ObjectIdentifier>()) { $0.insert($1) }

        // Disable/enable internal PDFView gesture recognizers for text selection
        disableInternalGestures(in: pdfView, ownGestures: ownGestures, disable: disableSelection)

        // Clear any existing selection when disabling
        if disableSelection {
            pdfView.clearSelection()
        }
    }

    private func disableInternalGestures(in view: UIView, ownGestures: Set<ObjectIdentifier>, disable: Bool) {
        for gr in view.gestureRecognizers ?? [] {
            if !ownGestures.contains(ObjectIdentifier(gr)) {
                if gr is UILongPressGestureRecognizer || (gr is UIPanGestureRecognizer && !(gr is UIScreenEdgePanGestureRecognizer)) {
                    gr.isEnabled = !disable
                }
            }
        }
        for subview in view.subviews {
            disableInternalGestures(in: subview, ownGestures: ownGestures, disable: disable)
        }
    }

    private func framesEqual(_ a: CGRect, _ b: CGRect, tolerance: CGFloat = 0.5) -> Bool {
        return abs(a.origin.x - b.origin.x) < tolerance
            && abs(a.origin.y - b.origin.y) < tolerance
            && abs(a.size.width - b.size.width) < tolerance
            && abs(a.size.height - b.size.height) < tolerance
    }

    public override func layoutSubviews() {
        super.layoutSubviews()

        if displayMode == "grid" {
            if !framesEqual(gridCollectionView.frame, bounds) { gridCollectionView.frame = bounds }
            if pdfView.frame != .zero { pdfView.frame = .zero }
            if thumbnailView.frame != .zero { thumbnailView.frame = .zero }

            // Update cell sizes for current width
            if let layout = gridCollectionView.collectionViewLayout as? UICollectionViewFlowLayout {
                let cellWidth = (bounds.width - 24) / 2
                let cellHeight = cellWidth * 1.414 + 24
                let newSize = CGSize(width: cellWidth, height: cellHeight)
                if layout.itemSize != newSize { layout.itemSize = newSize }
            }
        } else {
            if !gridCollectionView.frame.isEmpty { gridCollectionView.frame = .zero }

            let thumbH = (showThumbnails && !thumbnailView.isHidden) ? thumbnailHeight : 0
            let pdfFrame = CGRect(x: 0, y: 0, width: bounds.width, height: bounds.height - thumbH)
            let thumbFrame = CGRect(x: 0, y: bounds.height - thumbH, width: bounds.width, height: thumbH)

            if !framesEqual(pdfView.frame, pdfFrame) {
                // Save scroll state — setting pdfView.frame resets zoom/scroll
                let savedScale = pdfView.scaleFactor
                let savedOffset = findScrollView()?.contentOffset
                let hadDocument = pdfView.document != nil

                pdfView.frame = pdfFrame
                applyZoomLimits()

                // Restore zoom and scroll position after frame change
                if hadDocument, let offset = savedOffset {
                    pdfView.scaleFactor = savedScale
                    findScrollView()?.setContentOffset(offset, animated: false)
                }
            }
            if !framesEqual(thumbnailView.frame, thumbFrame) { thumbnailView.frame = thumbFrame }
            if !framesEqual(overlayView.frame, pdfFrame) { overlayView.frame = pdfFrame }
        }

        redrawOverlays()
    }

    // MARK: - Display Mode

    private func applyDisplayMode() {
        if displayMode == "grid" {
            pdfView.isHidden = true
            thumbnailView.isHidden = true
            gridCollectionView.isHidden = false
            gridCollectionView.reloadData()
        } else if displayMode == "single" {
            pdfView.isHidden = false
            thumbnailView.isHidden = !showThumbnails
            thumbnailView.pdfView = pdfView
            gridCollectionView.isHidden = true
            pdfView.displayMode = .singlePage
            pdfView.displayDirection = .horizontal
            pdfView.displaysPageBreaks = true
            pdfView.usePageViewController(true, withViewOptions: nil)
        } else {
            pdfView.isHidden = false
            thumbnailView.isHidden = true
            thumbnailView.pdfView = nil
            gridCollectionView.isHidden = true
            pdfView.usePageViewController(false)
            pdfView.displayMode = .singlePageContinuous
            pdfView.displayDirection = .vertical
            pdfView.displaysPageBreaks = false
        }

        if displayMode != "grid" {
            pdfView.autoScales = true
            applyZoomLimits()
            addScrollViewObserver()
        }

        setNeedsLayout()
    }

    // MARK: - Document Loading

    private func loadDocument() {
        guard !pdfUrl.isEmpty else { return }

        let isRemote = pdfUrl.hasPrefix("http://") || pdfUrl.hasPrefix("https://")

        if isRemote {
            guard let remoteUrl = URL(string: pdfUrl) else {
                onDocumentLoadFailed?(["error": "Invalid URL: \(pdfUrl)"])
                return
            }
            URLSession.shared.dataTask(with: remoteUrl) { [weak self] data, _, error in
                guard let self = self else { return }
                guard let data = data, error == nil, let document = PDFDocument(data: data) else {
                    DispatchQueue.main.async {
                        self.onDocumentLoadFailed?(["error": error?.localizedDescription ?? "Failed to download PDF"])
                    }
                    return
                }
                self.presentDocument(document)
            }.resume()
        } else {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else { return }

                let url: URL?
                if self.pdfUrl.hasPrefix("file://") {
                    url = URL(string: self.pdfUrl)
                        ?? URL(string: self.pdfUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? self.pdfUrl)
                } else {
                    url = URL(fileURLWithPath: self.pdfUrl)
                }

                guard let docUrl = url, let document = PDFDocument(url: docUrl) else {
                    DispatchQueue.main.async {
                        self.onDocumentLoadFailed?(["error": "Failed to load PDF from \(self.pdfUrl)"])
                    }
                    return
                }
                self.presentDocument(document)
            }
        }
    }

    private func presentDocument(_ document: PDFDocument) {
        DispatchQueue.main.async {
            self.currentDocument = document
            self.thumbnailCache.removeAllObjects()
            self.pdfView.document = document
            self.pdfView.autoScales = true

            self.applyZoomLimits()

            // Install "Add Field" menu item on internal PDFView subviews
            // (must be done after document is set, as that's when internal views are created)
            self.installAddFieldMenu()

            self.onDocumentLoaded?(["pageCount": document.pageCount])

            if self.pageIndex > 0 {
                self.goToPageInternal(self.pageIndex)
            }

            if self.displayMode == "grid" {
                self.gridCollectionView.reloadData()
            }
        }
    }

    private func applyZoomLimits() {
        guard pdfView.document != nil else { return }
        let baseFactor = pdfView.scaleFactorForSizeToFit
        guard baseFactor > 0 else { return }
        pdfView.minScaleFactor = minZoom * baseFactor
        pdfView.maxScaleFactor = maxZoom * baseFactor
    }

    // MARK: - Commands

    public func goToPage(_ pageIndex: Int) {
        goToPageInternal(pageIndex)
    }

    public func zoomTo(_ scale: CGFloat) {
        let baseFactor = pdfView.scaleFactorForSizeToFit
        pdfView.scaleFactor = scale * baseFactor
    }

    private func goToPageInternal(_ index: Int) {
        guard let doc = pdfView.document, let page = doc.page(at: index) else { return }
        pdfView.go(to: page)
    }

    // MARK: - Events

    @objc private func pageChanged() {
        guard let currentPage = pdfView.currentPage,
              let doc = pdfView.document,
              let idx = doc.index(for: currentPage) as Int? else { return }

        onPageChanged?([
            "pageIndex": idx,
            "pageCount": doc.pageCount,
        ])
    }

    @objc private func handleSelectionChanged() {
        guard let selection = pdfView.currentSelection,
              let text = selection.string, !text.isEmpty else { return }

        // Get bounding box across all pages in the selection
        guard let firstPage = selection.pages.first,
              let doc = pdfView.document else { return }

        let pageIndex = doc.index(for: firstPage)
        let pageBounds = firstPage.bounds(for: .mediaBox)

        // Get the selection bounds on this page
        let selBounds = selection.bounds(for: firstPage)

        // Convert to normalized coordinates (top-left origin, 0-1 range)
        let nx = selBounds.origin.x / pageBounds.width
        let ny = 1.0 - (selBounds.origin.y + selBounds.height) / pageBounds.height
        let nw = selBounds.width / pageBounds.width
        let nh = selBounds.height / pageBounds.height

        onTextSelected?([
            "text": text,
            "pageIndex": pageIndex,
            "x": nx,
            "y": ny,
            "width": nw,
            "height": nh,
        ])
    }

    // MARK: - Custom Menu: "Add Field" in native context menu
    //
    // On iOS 16+ UIMenuController.shared.menuItems doesn't work for PDFView because
    // the internal PDFDocumentView becomes the first responder (not our PdfViewerView).
    // We inject the "Add Field" action directly into PDFDocumentView via runtime:
    //   1. Add handleAddField: method to PDFDocumentView class
    //   2. Swizzle buildMenu(with:) on PDFDocumentView to insert our UIAction
    //   3. Store a weak reference to self so the injected method can call back

    private static var pdfDocumentViewClass: AnyClass?
    private static var swizzledClasses = Set<String>()

    private func installAddFieldMenu() {
        installAddFieldMenuOnInternalViews(pdfView)
    }

    private func installAddFieldMenuOnInternalViews(_ view: UIView) {
        let className = String(describing: type(of: view))

        if className.contains("PDFDocumentView") {
            let viewClass: AnyClass = type(of: view)
            PdfViewerView.pdfDocumentViewClass = viewClass

            // Store weak self on the view so the injected action can call back
            objc_setAssociatedObject(view, &kPdfViewerViewKey, self, .OBJC_ASSOCIATION_ASSIGN)

            // Only swizzle once per class
            if !PdfViewerView.swizzledClasses.contains(className) {
                PdfViewerView.swizzledClasses.insert(className)

                // Swizzle buildMenu(with:) to add our "Add Field" action
                let buildMenuSelector = #selector(UIResponder.buildMenu(with:))
                if let originalMethod = class_getInstanceMethod(viewClass, buildMenuSelector) {
                    let originalImp = method_getImplementation(originalMethod)

                    // Store original IMP
                    objc_setAssociatedObject(viewClass, &kOriginalBuildMenuKey,
                                             originalImp, .OBJC_ASSOCIATION_ASSIGN)

                    let block: @convention(block) (AnyObject, UIMenuBuilder) -> Void = { obj, builder in
                        // Call original first
                        let origFn = unsafeBitCast(originalImp, to: (@convention(c) (AnyObject, Selector, UIMenuBuilder) -> Void).self)
                        origFn(obj, buildMenuSelector, builder)

                        guard builder.system == .context else { return }

                        // Get our PdfViewerView from the associated object
                        guard let pdfViewerView = objc_getAssociatedObject(obj, &kPdfViewerViewKey) as? PdfViewerView else { return }
                        guard let selection = pdfViewerView.pdfView.currentSelection,
                              let text = selection.string, !text.isEmpty else { return }

                        let addFieldAction = UIAction(
                            title: "Add Field",
                            image: UIImage(systemName: "plus.rectangle.on.rectangle")
                        ) { [weak pdfViewerView] _ in
                            pdfViewerView?.handleAddField(nil)
                        }

                        let customMenu = UIMenu(title: "", options: .displayInline, children: [addFieldAction])
                        builder.insertChild(customMenu, atStartOfMenu: .standardEdit)
                    }

                    let newImp = imp_implementationWithBlock(block)
                    method_setImplementation(originalMethod, newImp)
                }
            }
        }

        for subview in view.subviews {
            installAddFieldMenuOnInternalViews(subview)
        }
    }

    @objc private func handleAddField(_ sender: Any?) {
        guard let selection = pdfView.currentSelection,
              let text = selection.string, !text.isEmpty else { return }

        guard let firstPage = selection.pages.first,
              let doc = pdfView.document else { return }

        let pageIndex = doc.index(for: firstPage)
        let pageBounds = firstPage.bounds(for: .mediaBox)
        let selBounds = selection.bounds(for: firstPage)

        let nx = selBounds.origin.x / pageBounds.width
        let ny = 1.0 - (selBounds.origin.y + selBounds.height) / pageBounds.height
        let nw = selBounds.width / pageBounds.width
        let nh = selBounds.height / pageBounds.height

        onAddFieldFromSelection?([
            "text": text,
            "pageIndex": pageIndex,
            "x": nx,
            "y": ny,
            "width": nw,
            "height": nh,
        ])

        // Clear selection after adding field
        pdfView.clearSelection()
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }

        let locationInView = gesture.location(in: pdfView)
        guard let page = pdfView.page(for: locationInView, nearest: true),
              let doc = pdfView.document else { return }

        let pagePoint = pdfView.convert(locationInView, to: page)
        let pageBounds = page.bounds(for: .mediaBox)

        // Normalize coordinates 0-1
        let normalizedX = pagePoint.x / pageBounds.width
        let normalizedY = pagePoint.y / pageBounds.height

        let pageIdx = doc.index(for: page)

        onLongPress?([
            "pageIndex": pageIdx,
            "x": normalizedX,
            "y": normalizedY,
        ])
    }

    // MARK: - Overlay: Parsing

    private func parseOverlays() {
        let str = textOverlays as String
        guard !str.isEmpty,
              let data = str.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            overlayItems = []
            redrawOverlays()
            return
        }

        overlayItems = json.compactMap { dict -> OverlayItem? in
            guard let id = dict["id"] as? String,
                  let pageIndex = dict["pageIndex"] as? Int,
                  let x = dict["x"] as? Double,
                  let y = dict["y"] as? Double,
                  let width = dict["width"] as? Double,
                  let height = dict["height"] as? Double else { return nil }
            return OverlayItem(
                id: id,
                pageIndex: pageIndex,
                x: CGFloat(x), y: CGFloat(y),
                width: CGFloat(width), height: CGFloat(height),
                selected: dict["selected"] as? Bool ?? false,
                label: dict["label"] as? String ?? ""
            )
        }

        NSLog("[Neurodoc] parseOverlays: parsed %d items from JSON (%d chars)", overlayItems.count, str.count)
        redrawOverlays()
    }

    @objc private func overlayNeedsRedraw() {
        redrawOverlays()
    }

    // MARK: - Overlay: Drawing

    private func redrawOverlays() {
        // Only reorder if needed — bringSubviewToFront triggers layout
        if let lastSubview = subviews.last, lastSubview !== overlayView {
            bringSubviewToFront(overlayView)
        }

        guard !overlayItems.isEmpty, let doc = pdfView.document else {
            overlayView.rects = []
            overlayView.setNeedsDisplay()
            return
        }

        var rects: [OverlayDrawingView.OverlayRect] = []

        for item in overlayItems {
            guard item.pageIndex >= 0, item.pageIndex < doc.pageCount,
                  let page = doc.page(at: item.pageIndex) else { continue }

            let pageBounds = page.bounds(for: .mediaBox)

            // Normalized top-left → PDF bottom-left coordinate system
            let pdfX = item.x * pageBounds.width
            let pdfY = pageBounds.height - item.y * pageBounds.height - item.height * pageBounds.height
            let pdfW = item.width * pageBounds.width
            let pdfH = item.height * pageBounds.height

            // Convert PDF rect corners to pdfView's coordinate space
            let bottomLeft = pdfView.convert(CGPoint(x: pdfX, y: pdfY), from: page)
            let topRight = pdfView.convert(CGPoint(x: pdfX + pdfW, y: pdfY + pdfH), from: page)

            // Convert from pdfView's coords to overlayView's coords
            let blInOverlay = pdfView.convert(bottomLeft, to: overlayView)
            let trInOverlay = pdfView.convert(topRight, to: overlayView)

            let viewRect = CGRect(
                x: min(blInOverlay.x, trInOverlay.x),
                y: min(blInOverlay.y, trInOverlay.y),
                width: abs(trInOverlay.x - blInOverlay.x),
                height: abs(trInOverlay.y - blInOverlay.y)
            )

            rects.append(OverlayDrawingView.OverlayRect(
                id: item.id,
                rect: viewRect,
                selected: item.selected,
                label: item.label
            ))
        }

        if !rects.isEmpty {
            NSLog("[Neurodoc] redrawOverlays: %d rects, first rect: (%.1f, %.1f, %.1f, %.1f), overlayView bounds: (%.1f, %.1f, %.1f, %.1f)",
                  rects.count,
                  rects[0].rect.origin.x, rects[0].rect.origin.y, rects[0].rect.size.width, rects[0].rect.size.height,
                  overlayView.bounds.origin.x, overlayView.bounds.origin.y, overlayView.bounds.size.width, overlayView.bounds.size.height)
        }

        overlayView.rects = rects
        overlayView.setNeedsDisplay()
    }

    // MARK: - Overlay: Tap Handling

    /// Tap on overlay rect — fires onOverlayTap (gesture on overlayView)
    @objc private func handleOverlayTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended, enableOverlayTap else { return }

        let location = gesture.location(in: pdfView)

        if let hit = hitTestOverlay(at: location) {
            onOverlayTap?(["id": hit.id, "pageIndex": hit.pageIndex])
        }
    }

    /// Tap on empty area — fires onTap (gesture on pdfView, fires when no overlay is hit)
    @objc private func handlePdfTap(_ gesture: UITapGestureRecognizer) {
        guard gesture.state == .ended, enableOverlayTap else { return }

        let location = gesture.location(in: pdfView)

        guard let page = pdfView.page(for: location, nearest: true),
              let doc = pdfView.document else { return }

        let pagePoint = pdfView.convert(location, to: page)
        let pageBounds = page.bounds(for: .mediaBox)
        let normalizedX = pagePoint.x / pageBounds.width
        let normalizedY = 1.0 - (pagePoint.y + 0) / pageBounds.height
        let pageIdx = doc.index(for: page)

        onTap?(["pageIndex": pageIdx, "x": normalizedX, "y": normalizedY])
    }

    /// Returns the view-rect (in pdfView coords) for a given overlay item
    private func viewRectForOverlay(_ item: OverlayItem) -> CGRect? {
        guard let doc = pdfView.document,
              item.pageIndex >= 0, item.pageIndex < doc.pageCount,
              let page = doc.page(at: item.pageIndex) else { return nil }

        let pageBounds = page.bounds(for: .mediaBox)
        let pdfX = item.x * pageBounds.width
        let pdfY = pageBounds.height - item.y * pageBounds.height - item.height * pageBounds.height
        let pdfW = item.width * pageBounds.width
        let pdfH = item.height * pageBounds.height

        let bl = pdfView.convert(CGPoint(x: pdfX, y: pdfY), from: page)
        let tr = pdfView.convert(CGPoint(x: pdfX + pdfW, y: pdfY + pdfH), from: page)

        return CGRect(
            x: min(bl.x, tr.x),
            y: min(bl.y, tr.y),
            width: abs(tr.x - bl.x),
            height: abs(tr.y - bl.y)
        )
    }

    private func hitTestOverlay(at viewPoint: CGPoint) -> OverlayItem? {
        for item in overlayItems.reversed() {
            guard let viewRect = viewRectForOverlay(item) else { continue }
            if viewRect.insetBy(dx: -4, dy: -4).contains(viewPoint) {
                return item
            }
        }
        return nil
    }

    // MARK: - Overlay: Long Press → Drag or Resize

    @objc private func handleOverlayInteraction(_ gesture: UILongPressGestureRecognizer) {
        guard enableOverlayTap else { return }
        let location = gesture.location(in: pdfView)

        switch gesture.state {
        case .began:
            // First try resize handle (selected overlays only)
            if tryBeginResize(at: location) {
                findScrollView()?.isScrollEnabled = false
                return
            }
            // Otherwise try drag
            if let hit = hitTestOverlay(at: location) {
                draggedItemId = hit.id
                dragStartPoint = location
                dragOriginalNormalized = (hit.x, hit.y)
                dragPageIndex = hit.pageIndex
                findScrollView()?.isScrollEnabled = false
            }

        case .changed:
            if resizeHandle != .none, resizeItemId != nil {
                updateResize(at: location)
            } else if draggedItemId != nil {
                updateDrag(at: location)
            }

        case .ended, .cancelled:
            if resizeHandle != .none, resizeItemId != nil {
                endResize(at: location)
            } else if draggedItemId != nil {
                endDrag(at: location)
            }
            draggedItemId = nil
            resizeItemId = nil
            resizeHandle = .none
            findScrollView()?.isScrollEnabled = true

        default:
            break
        }
    }

    /// Try to begin a resize at the given point. Returns true if a resize handle was hit.
    private func tryBeginResize(at viewPoint: CGPoint) -> Bool {
        for item in overlayItems.reversed() where item.selected {
            guard let viewRect = viewRectForOverlay(item) else { continue }

            let handleSize: CGFloat = 30
            let corners: [(ResizeHandle, CGPoint)] = [
                (.topLeft, CGPoint(x: viewRect.minX, y: viewRect.minY)),
                (.topRight, CGPoint(x: viewRect.maxX, y: viewRect.minY)),
                (.bottomLeft, CGPoint(x: viewRect.minX, y: viewRect.maxY)),
                (.bottomRight, CGPoint(x: viewRect.maxX, y: viewRect.maxY)),
            ]

            for (handle, corner) in corners {
                let handleRect = CGRect(x: corner.x - handleSize/2, y: corner.y - handleSize/2, width: handleSize, height: handleSize)
                if handleRect.contains(viewPoint) {
                    resizeHandle = handle
                    resizeItemId = item.id
                    resizeOriginalNormalized = (item.x, item.y, item.width, item.height)
                    resizeStartPoint = viewPoint
                    resizePageBounds = viewRect
                    return true
                }
            }
        }
        return false
    }

    private func updateDrag(at viewPoint: CGPoint) {
        guard let dragId = draggedItemId,
              let doc = pdfView.document,
              let page = doc.page(at: dragPageIndex) else { return }

        let pageBounds = page.bounds(for: .mediaBox)
        let deltaView = CGPoint(x: viewPoint.x - dragStartPoint.x, y: viewPoint.y - dragStartPoint.y)

        // Convert delta from view to normalized coords
        // We need page width/height in view coords for the conversion
        let origin = pdfView.convert(CGPoint(x: 0, y: 0), from: page)
        let corner = pdfView.convert(CGPoint(x: pageBounds.width, y: pageBounds.height), from: page)
        let viewPageWidth = abs(corner.x - origin.x)
        let viewPageHeight = abs(corner.y - origin.y)

        guard viewPageWidth > 0, viewPageHeight > 0 else { return }

        let deltaNormX = deltaView.x / viewPageWidth
        // Y is inverted between view (top-down) and normalized (top-down matches here since both go top-to-bottom in view space)
        let deltaNormY = deltaView.y / viewPageHeight

        // Update overlay visually via temporary override
        if let idx = overlayItems.firstIndex(where: { $0.id == dragId }) {
            overlayItems[idx] = OverlayItem(
                id: overlayItems[idx].id,
                pageIndex: overlayItems[idx].pageIndex,
                x: dragOriginalNormalized.x + deltaNormX,
                y: dragOriginalNormalized.y + deltaNormY,
                width: overlayItems[idx].width,
                height: overlayItems[idx].height,
                selected: overlayItems[idx].selected,
                label: overlayItems[idx].label
            )
            redrawOverlays()
        }
    }

    private func endDrag(at viewPoint: CGPoint) {
        guard let dragId = draggedItemId,
              let item = overlayItems.first(where: { $0.id == dragId }) else { return }

        onOverlayMoved?(["id": dragId, "x": item.x, "y": item.y])
    }

    private func updateResize(at viewPoint: CGPoint) {
        guard let resizeId = resizeItemId,
              let doc = pdfView.document,
              let idx = overlayItems.firstIndex(where: { $0.id == resizeId }),
              overlayItems[idx].pageIndex >= 0, overlayItems[idx].pageIndex < doc.pageCount,
              let page = doc.page(at: overlayItems[idx].pageIndex) else { return }

        let pageBounds = page.bounds(for: .mediaBox)
        let origin = pdfView.convert(CGPoint(x: 0, y: 0), from: page)
        let corner = pdfView.convert(CGPoint(x: pageBounds.width, y: pageBounds.height), from: page)
        let viewPageWidth = abs(corner.x - origin.x)
        let viewPageHeight = abs(corner.y - origin.y)
        guard viewPageWidth > 0, viewPageHeight > 0 else { return }

        let dx = (viewPoint.x - resizeStartPoint.x) / viewPageWidth
        let dy = (viewPoint.y - resizeStartPoint.y) / viewPageHeight

        let orig = resizeOriginalNormalized
        let minW: CGFloat = 0.02
        let minH: CGFloat = 0.01

        var newX = orig.x, newY = orig.y, newW = orig.w, newH = orig.h

        switch resizeHandle {
        case .bottomRight:
            newW = max(minW, orig.w + dx)
            newH = max(minH, orig.h + dy)
        case .bottomLeft:
            let proposedW = orig.w - dx
            if proposedW >= minW {
                newX = orig.x + dx
                newW = proposedW
            }
            newH = max(minH, orig.h + dy)
        case .topRight:
            newW = max(minW, orig.w + dx)
            let proposedH = orig.h - dy
            if proposedH >= minH {
                newY = orig.y + dy
                newH = proposedH
            }
        case .topLeft:
            let proposedW = orig.w - dx
            if proposedW >= minW {
                newX = orig.x + dx
                newW = proposedW
            }
            let proposedH = orig.h - dy
            if proposedH >= minH {
                newY = orig.y + dy
                newH = proposedH
            }
        case .none:
            break
        }

        overlayItems[idx] = OverlayItem(
            id: overlayItems[idx].id,
            pageIndex: overlayItems[idx].pageIndex,
            x: newX, y: newY, width: newW, height: newH,
            selected: overlayItems[idx].selected,
            label: overlayItems[idx].label
        )
        redrawOverlays()
    }

    private func endResize(at viewPoint: CGPoint) {
        guard let resizeId = resizeItemId,
              let item = overlayItems.first(where: { $0.id == resizeId }) else { return }

        onOverlayResized?([
            "id": resizeId,
            "x": item.x,
            "y": item.y,
            "width": item.width,
            "height": item.height,
        ])
    }

    // MARK: - Grid: Reorder via Long Press + Drag

    @objc private func handleGridReorder(_ gesture: UILongPressGestureRecognizer) {
        let location = gesture.location(in: gridCollectionView)

        switch gesture.state {
        case .began:
            guard let indexPath = gridCollectionView.indexPathForItem(at: location) else { return }
            gridCollectionView.beginInteractiveMovementForItem(at: indexPath)

            if let cell = gridCollectionView.cellForItem(at: indexPath) {
                UIView.animate(withDuration: 0.25, delay: 0, usingSpringWithDamping: 0.8, initialSpringVelocity: 0, options: .curveEaseOut) {
                    cell.transform = CGAffineTransform(scaleX: 1.15, y: 1.15)
                    cell.layer.shadowOpacity = 0.3
                    cell.layer.shadowRadius = 8
                }
            }
        case .changed:
            gridCollectionView.updateInteractiveMovementTargetPosition(location)
        case .ended:
            gridCollectionView.endInteractiveMovement()
            resetDraggedCells()
        default:
            gridCollectionView.cancelInteractiveMovement()
            resetDraggedCells()
        }
    }

    private func resetDraggedCells() {
        for cell in gridCollectionView.visibleCells {
            UIView.animate(withDuration: 0.2) {
                cell.transform = .identity
                cell.layer.shadowOpacity = 0
                cell.layer.shadowRadius = 0
            }
        }
    }

    // MARK: - Grid: Save & Notify

    private func saveAndNotifyDocumentChanged() {
        guard let doc = currentDocument else { return }

        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("neurodoc", isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let outputUrl = tempDir.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)

        let urlString = outputUrl.absoluteString
        lastSavedUrl = urlString

        // Reload from file so PDFView / PDFThumbnailView detect the change
        // (reassigning the same PDFDocument object is ignored by the views)
        if let freshDoc = PDFDocument(url: outputUrl) {
            currentDocument = freshDoc
            pdfView.document = freshDoc

            // Reconnect thumbnail view so it picks up the new document
            if showThumbnails && displayMode != "grid" {
                thumbnailView.pdfView = pdfView
            }
        }

        onDocumentChanged?([
            "pdfUrl": urlString,
            "pageCount": currentDocument?.pageCount ?? doc.pageCount,
        ])
    }

    // MARK: - Grid: Thumbnail Generation

    private func thumbnailForPage(at index: Int, size: CGSize) -> UIImage? {
        let key = NSNumber(value: index)
        if let cached = thumbnailCache.object(forKey: key) {
            return cached
        }

        guard let doc = currentDocument, let page = doc.page(at: index) else { return nil }

        let scale = UIScreen.main.scale
        let thumbSize = CGSize(width: size.width * scale, height: size.height * scale)
        let thumbnail = page.thumbnail(of: thumbSize, for: .mediaBox)
        thumbnailCache.setObject(thumbnail, forKey: key)
        return thumbnail
    }

    // MARK: - Grid: Image Picker

    private func presentImagePicker() {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1

        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self

        guard let rootVC = window?.rootViewController else { return }
        var presenter = rootVC
        while let presented = presenter.presentedViewController {
            presenter = presented
        }
        presenter.present(picker, animated: true)
    }

    // MARK: - Helpers

    /// Creates a PDF page with the given size, drawing the image scaled to fit (aspect-fit, centered).
    private func createPDFPage(from image: UIImage, fitting pageSize: CGSize) -> PDFPage? {
        let imageSize = image.size
        let scaleX = pageSize.width / imageSize.width
        let scaleY = pageSize.height / imageSize.height
        let scale = min(scaleX, scaleY)

        let scaledWidth = imageSize.width * scale
        let scaledHeight = imageSize.height * scale
        let originX = (pageSize.width - scaledWidth) / 2
        let originY = (pageSize.height - scaledHeight) / 2

        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize))
        let data = renderer.pdfData { context in
            context.beginPage()
            // White background
            UIColor.white.setFill()
            UIRectFill(CGRect(origin: .zero, size: pageSize))
            // Draw image centered
            image.draw(in: CGRect(x: originX, y: originY, width: scaledWidth, height: scaledHeight))
        }

        guard let pdfDoc = PDFDocument(data: data), let page = pdfDoc.page(at: 0) else { return nil }
        return page
    }

    private func findScrollView() -> UIScrollView? {
        for subview in pdfView.subviews {
            if let scrollView = subview as? UIScrollView {
                return scrollView
            }
        }
        return nil
    }

    private func addScrollViewObserver() {
        removeScrollViewObserver()
        if let scrollView = findScrollView() {
            scrollView.addObserver(self, forKeyPath: "contentOffset", options: .new, context: nil)
            observedScrollView = scrollView
        }
    }

    private func removeScrollViewObserver() {
        if let scrollView = observedScrollView {
            scrollView.removeObserver(self, forKeyPath: "contentOffset")
            observedScrollView = nil
        }
    }
}

// MARK: - OverlayItem

struct OverlayItem {
    let id: String
    let pageIndex: Int
    let x: CGFloat, y: CGFloat, width: CGFloat, height: CGFloat  // normalized 0-1, top-left origin
    let selected: Bool
    let label: String
}

// MARK: - OverlayDrawingView

private class OverlayDrawingView: UIView {
    struct OverlayRect {
        let id: String
        let rect: CGRect
        let selected: Bool
        let label: String
    }

    var rects: [OverlayRect] = []
    /// When true, hitTest returns self for touches on overlay rects (so gestures fire).
    var interactiveOverlays: Bool = false

    private let selectedColor = UIColor(red: 0.2, green: 0.8, blue: 0.3, alpha: 1.0)
    private let unselectedColor = UIColor(red: 0.2, green: 0.5, blue: 1.0, alpha: 1.0)
    private let handleSize: CGFloat = 8
    private let handleHitSize: CGFloat = 30

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard interactiveOverlays else { return nil }

        // Check resize handles first (selected overlays)
        for overlay in rects where overlay.selected {
            let r = overlay.rect
            let corners = [
                CGPoint(x: r.minX, y: r.minY),
                CGPoint(x: r.maxX, y: r.minY),
                CGPoint(x: r.minX, y: r.maxY),
                CGPoint(x: r.maxX, y: r.maxY),
            ]
            for corner in corners {
                let handleRect = CGRect(
                    x: corner.x - handleHitSize / 2,
                    y: corner.y - handleHitSize / 2,
                    width: handleHitSize,
                    height: handleHitSize
                )
                if handleRect.contains(point) { return self }
            }
        }

        // Check overlay body
        for overlay in rects {
            if overlay.rect.insetBy(dx: -4, dy: -4).contains(point) {
                return self
            }
        }

        // Miss — pass through to pdfView below
        return nil
    }

    override func draw(_ rect: CGRect) {
        super.draw(rect)
        guard let ctx = UIGraphicsGetCurrentContext() else { return }

        for overlay in rects {
            let r = overlay.rect

            if overlay.selected {
                // Selected: green border + light fill
                ctx.setFillColor(selectedColor.withAlphaComponent(0.15).cgColor)
                ctx.fill(r)
                ctx.setStrokeColor(selectedColor.cgColor)
                ctx.setLineWidth(2.0)
                ctx.stroke(r)

                // Draw resize handles
                drawHandle(ctx: ctx, at: CGPoint(x: r.minX, y: r.minY))
                drawHandle(ctx: ctx, at: CGPoint(x: r.maxX, y: r.minY))
                drawHandle(ctx: ctx, at: CGPoint(x: r.minX, y: r.maxY))
                drawHandle(ctx: ctx, at: CGPoint(x: r.maxX, y: r.maxY))
            } else {
                // Unselected: blue border + light fill
                ctx.setFillColor(unselectedColor.withAlphaComponent(0.08).cgColor)
                ctx.fill(r)
                ctx.setStrokeColor(unselectedColor.withAlphaComponent(0.6).cgColor)
                ctx.setLineWidth(1.0)
                ctx.stroke(r)
            }

            // Label
            if !overlay.label.isEmpty && r.width > 20 && r.height > 10 {
                let fontSize: CGFloat = min(10, r.height * 0.7)
                let attrs: [NSAttributedString.Key: Any] = [
                    .font: UIFont.systemFont(ofSize: fontSize),
                    .foregroundColor: overlay.selected ? selectedColor : unselectedColor.withAlphaComponent(0.8),
                ]
                let text = overlay.label as NSString
                let textSize = text.size(withAttributes: attrs)
                if textSize.width <= r.width + 4 {
                    let textRect = CGRect(
                        x: r.minX + 2,
                        y: r.minY + (r.height - textSize.height) / 2,
                        width: min(textSize.width, r.width - 4),
                        height: textSize.height
                    )
                    text.draw(in: textRect, withAttributes: attrs)
                }
            }
        }
    }

    private func drawHandle(ctx: CGContext, at point: CGPoint) {
        let rect = CGRect(x: point.x - handleSize/2, y: point.y - handleSize/2, width: handleSize, height: handleSize)
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fill(rect)
        ctx.setStrokeColor(selectedColor.cgColor)
        ctx.setLineWidth(1.5)
        ctx.stroke(rect)
    }
}

// MARK: - GridPageCell

private class GridPageCell: UICollectionViewCell {
    let imageView = UIImageView()
    let pageLabel = UILabel()
    let menuButton = UIButton(type: .custom)

    override init(frame: CGRect) {
        super.init(frame: frame)

        contentView.backgroundColor = .white
        contentView.layer.cornerRadius = 6
        contentView.layer.shadowColor = UIColor.black.cgColor
        contentView.layer.shadowOpacity = 0.1
        contentView.layer.shadowOffset = CGSize(width: 0, height: 1)
        contentView.layer.shadowRadius = 3
        contentView.clipsToBounds = false

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.layer.cornerRadius = 4
        contentView.addSubview(imageView)

        pageLabel.textAlignment = .center
        pageLabel.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        pageLabel.textColor = .secondaryLabel
        contentView.addSubview(pageLabel)

        // Small "..." menu button in top-right corner — tap shows popup menu
        let config = UIImage.SymbolConfiguration(pointSize: 14, weight: .semibold)
        menuButton.setImage(UIImage(systemName: "ellipsis.circle.fill", withConfiguration: config), for: .normal)
        menuButton.tintColor = UIColor(white: 0.5, alpha: 0.8)
        menuButton.showsMenuAsPrimaryAction = true
        contentView.addSubview(menuButton)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let labelHeight: CGFloat = 20
        let btnSize: CGFloat = 30
        imageView.frame = CGRect(x: 4, y: 4, width: contentView.bounds.width - 8, height: contentView.bounds.height - labelHeight - 8)
        pageLabel.frame = CGRect(x: 0, y: contentView.bounds.height - labelHeight, width: contentView.bounds.width, height: labelHeight)
        menuButton.frame = CGRect(x: contentView.bounds.width - btnSize - 4, y: 4, width: btnSize, height: btnSize)
    }

    func configure(image: UIImage?, pageNumber: Int, menu: UIMenu) {
        imageView.image = image
        pageLabel.text = "\(pageNumber)"
        menuButton.menu = menu
    }
}


// MARK: - UICollectionViewDataSource

extension PdfViewerView: UICollectionViewDataSource {
    public func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return currentDocument?.pageCount ?? 0
    }

    public func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: PdfViewerView.gridCellID, for: indexPath) as! GridPageCell

        let cellWidth = (bounds.width - 24) / 2
        let thumbSize = CGSize(width: cellWidth - 8, height: cellWidth * 1.414 - 8)
        let thumbnail = thumbnailForPage(at: indexPath.item, size: thumbSize)
        let menu = buildPageMenu(for: indexPath.item)
        cell.configure(image: thumbnail, pageNumber: indexPath.item + 1, menu: menu)

        return cell
    }

    private func buildPageMenu(for pageIdx: Int) -> UIMenu {
        let insertAction = UIAction(
            title: "Add Page from Image",
            image: UIImage(systemName: "photo.badge.plus")
        ) { [weak self] _ in
            self?.insertTargetIndex = pageIdx + 1
            self?.presentImagePicker()
        }

        let rotateRightAction = UIAction(
            title: "Rotate Right",
            image: UIImage(systemName: "rotate.right")
        ) { [weak self] _ in
            self?.rotatePageInGrid(at: pageIdx, by: 90)
        }

        let rotateLeftAction = UIAction(
            title: "Rotate Left",
            image: UIImage(systemName: "rotate.left")
        ) { [weak self] _ in
            self?.rotatePageInGrid(at: pageIdx, by: -90)
        }

        let rotateMenu = UIMenu(
            title: "Rotate Page",
            image: UIImage(systemName: "rotate.right"),
            children: [rotateRightAction, rotateLeftAction]
        )

        let deleteAction = UIAction(
            title: "Delete Page",
            image: UIImage(systemName: "trash"),
            attributes: .destructive
        ) { [weak self] _ in
            self?.deletePageInGrid(at: pageIdx)
        }

        return UIMenu(children: [insertAction, rotateMenu, deleteAction])
    }

    public func collectionView(_ collectionView: UICollectionView, canMoveItemAt indexPath: IndexPath) -> Bool {
        return true
    }

    public func collectionView(_ collectionView: UICollectionView, moveItemAt sourceIndexPath: IndexPath, to destinationIndexPath: IndexPath) {
        guard let doc = currentDocument else { return }

        let sourceIdx = sourceIndexPath.item
        let destIdx = destinationIndexPath.item
        guard sourceIdx != destIdx, let page = doc.page(at: sourceIdx) else { return }

        doc.removePage(at: sourceIdx)
        doc.insert(page, at: destIdx)

        thumbnailCache.removeAllObjects()
        saveAndNotifyDocumentChanged()
    }
}

// MARK: - UICollectionViewDelegate

extension PdfViewerView: UICollectionViewDelegate {
    private func rotatePageInGrid(at index: Int, by degrees: Int) {
        guard let doc = currentDocument, let page = doc.page(at: index) else { return }

        page.rotation = (page.rotation + degrees + 360) % 360
        thumbnailCache.removeAllObjects()
        gridCollectionView.reloadItems(at: [IndexPath(item: index, section: 0)])
        saveAndNotifyDocumentChanged()
    }

    private func deletePageInGrid(at index: Int) {
        guard let doc = currentDocument, doc.pageCount > 1 else {
            return // Don't delete the last page
        }

        doc.removePage(at: index)
        thumbnailCache.removeAllObjects()

        gridCollectionView.performBatchUpdates {
            gridCollectionView.deleteItems(at: [IndexPath(item: index, section: 0)])
        } completion: { _ in
            self.gridCollectionView.reloadData()
        }

        saveAndNotifyDocumentChanged()
    }
}


// MARK: - PHPickerViewControllerDelegate

extension PdfViewerView: PHPickerViewControllerDelegate {
    public func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)

        guard let result = results.first else { return }

        result.itemProvider.loadObject(ofClass: UIImage.self) { [weak self] object, error in
            guard let self = self, let image = object as? UIImage else { return }

            DispatchQueue.main.async {
                guard let doc = self.currentDocument else { return }

                let pdfPage: PDFPage?

                // Match existing document page size so the new page scales consistently
                if let firstPage = doc.page(at: 0) {
                    let pageBounds = firstPage.bounds(for: .mediaBox)
                    pdfPage = self.createPDFPage(from: image, fitting: pageBounds.size)
                } else {
                    pdfPage = PDFPage(image: image)
                }

                guard let page = pdfPage else { return }

                let idx = min(self.insertTargetIndex, doc.pageCount)
                doc.insert(page, at: idx)

                self.thumbnailCache.removeAllObjects()
                self.gridCollectionView.reloadData()
                self.saveAndNotifyDocumentChanged()
            }
        }
    }
}
