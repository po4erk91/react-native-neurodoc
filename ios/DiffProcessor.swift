import Foundation
import PDFKit
import UIKit
import Vision

class DiffProcessor {

    /// System/internal PDF annotation types filtered from diff comparison.
    /// Watermark is intentionally NOT included — it should be detected in diff.
    private static let systemAnnotationTypes: Set<String> = ["Widget", "Link", "Popup", "PrinterMark", "TrapNet"]

    // MARK: - Public entry point

    static func comparePdfs(
        pdfUrl1: String,
        pdfUrl2: String,
        addedColor: String,
        deletedColor: String,
        changedColor: String,
        opacity: Double,
        annotateSource: Bool,
        annotateTarget: Bool,
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        guard let url1 = resolveUrl(pdfUrl1), let doc1 = PDFDocument(url: url1) else {
            rejecter("COMPARISON_FAILED", "Failed to load PDF: \(pdfUrl1)", nil)
            return
        }
        guard let url2 = resolveUrl(pdfUrl2), let doc2 = PDFDocument(url: url2) else {
            rejecter("COMPARISON_FAILED", "Failed to load PDF: \(pdfUrl2)", nil)
            return
        }

        let colorAdded   = UIColor(hex: addedColor)   ?? UIColor(red: 0, green: 0.8,  blue: 0, alpha: 1)
        let colorDeleted = UIColor(hex: deletedColor) ?? UIColor(red: 1, green: 0.267, blue: 0.267, alpha: 1)
        let colorChanged = UIColor(hex: changedColor) ?? UIColor(red: 1, green: 0.667, blue: 0, alpha: 1)
        let alpha = CGFloat(opacity)

        let pageCount1 = doc1.pageCount
        let pageCount2 = doc2.pageCount
        let sharedPages = min(pageCount1, pageCount2)

        var changesPerPage: [[String: Any]] = []
        var totalAdded = 0
        var totalDeleted = 0
        var totalChanged = 0

        NSLog("[Neurodoc DiffProcessor] doc1 pages=%d, doc2 pages=%d, shared=%d", pageCount1, pageCount2, sharedPages)

        // --- Compare shared pages ---
        for i in 0..<sharedPages {
            let (blocks1, mode1) = extractPageBlocksAuto(doc: doc1, pageIndex: i)
            let (blocks2, mode2) = extractPageBlocksAuto(doc: doc2, pageIndex: i)

            NSLog("[Neurodoc DiffProcessor] page %d: blocks1=%d (%@), blocks2=%d (%@)", i, blocks1.count, mode1, blocks2.count, mode2)
            if !blocks1.isEmpty { NSLog("[Neurodoc DiffProcessor] first block1: '%@'", blocks1[0].text) }
            if !blocks2.isEmpty { NSLog("[Neurodoc DiffProcessor] first block2: '%@'", blocks2[0].text) }

            let diff = myersDiff(old: blocks1, new: blocks2)

            var added = 0; var deleted = 0; var changed = 0

            // Collect highlight rects per document
            var sourceRects: [[String: Any]] = []
            var targetRects: [[String: Any]] = []

            for op in diff {
                switch op {
                case .delete(let block):
                    if block.width > 0 { sourceRects.append(rectDict(block)) }
                    deleted += 1
                case .insert(let block):
                    if block.width > 0 { targetRects.append(rectDict(block)) }
                    added += 1
                case .change(let oldBlock, let newBlock):
                    if oldBlock.width > 0 { sourceRects.append(rectDict(oldBlock)) }
                    if newBlock.width > 0 { targetRects.append(rectDict(newBlock)) }
                    changed += 1
                case .equal:
                    break
                }
            }

            // --- Also compare annotations (notes, highlights, etc.) ---
            if let page1 = doc1.page(at: i), let page2 = doc2.page(at: i) {
                let annots1 = extractAnnotationBlocks(page: page1)
                let annots2 = extractAnnotationBlocks(page: page2)

                NSLog("[Neurodoc DiffProcessor] page %d: annots1=%d, annots2=%d", i, annots1.count, annots2.count)

                let annotDiff = myersDiff(old: annots1, new: annots2)
                for op in annotDiff {
                    switch op {
                    case .delete(let block):
                        if block.width > 0 { sourceRects.append(rectDict(block)) }
                        deleted += 1
                    case .insert(let block):
                        if block.width > 0 { targetRects.append(rectDict(block)) }
                        added += 1
                    case .change(let oldBlock, let newBlock):
                        if oldBlock.width > 0 { sourceRects.append(rectDict(oldBlock)) }
                        if newBlock.width > 0 { targetRects.append(rectDict(newBlock)) }
                        changed += 1
                    case .equal:
                        break
                    }
                }
            }

            NSLog("[Neurodoc DiffProcessor] page %d: added=%d deleted=%d changed=%d sourceRects=%d targetRects=%d", i, added, deleted, changed, sourceRects.count, targetRects.count)

            // Apply highlights
            if annotateSource && !sourceRects.isEmpty, let page = doc1.page(at: i) {
                applyHighlights(page: page, rects: sourceRects, color: colorDeleted, opacity: alpha)
            }
            if annotateTarget && !targetRects.isEmpty, let page = doc2.page(at: i) {
                applyHighlights(page: page, rects: targetRects, color: colorAdded, opacity: alpha)
            }

            changesPerPage.append([
                "pageIndex1": i,
                "pageIndex2": i,
                "added": added,
                "deleted": deleted,
                "changed": changed,
            ])
            totalAdded += added
            totalDeleted += deleted
            totalChanged += changed
        }

        // --- Pages only in doc1 (deleted pages) ---
        for i in sharedPages..<pageCount1 {
            let (blocks, _) = extractPageBlocksAuto(doc: doc1, pageIndex: i)
            if annotateSource, let page = doc1.page(at: i) {
                let allRects = blocks.filter { $0.width > 0 }.map { rectDict($0) }
                if !allRects.isEmpty {
                    applyHighlights(page: page, rects: allRects, color: colorDeleted, opacity: alpha)
                }
            }
            changesPerPage.append([
                "pageIndex1": i,
                "pageIndex2": -1,
                "added": 0,
                "deleted": blocks.count,
                "changed": 0,
            ])
            totalDeleted += blocks.count
        }

        // --- Pages only in doc2 (added pages) ---
        for i in sharedPages..<pageCount2 {
            let (blocks, _) = extractPageBlocksAuto(doc: doc2, pageIndex: i)
            if annotateTarget, let page = doc2.page(at: i) {
                let allRects = blocks.filter { $0.width > 0 }.map { rectDict($0) }
                if !allRects.isEmpty {
                    applyHighlights(page: page, rects: allRects, color: colorAdded, opacity: alpha)
                }
            }
            changesPerPage.append([
                "pageIndex1": -1,
                "pageIndex2": i,
                "added": blocks.count,
                "deleted": 0,
                "changed": 0,
            ])
            totalAdded += blocks.count
        }

        // --- Save annotated PDFs ---
        var sourcePdfUrl = ""
        var targetPdfUrl = ""

        if annotateSource {
            let outUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString)_diff_source.pdf")
            doc1.write(to: outUrl)
            sourcePdfUrl = outUrl.absoluteString
        }
        if annotateTarget {
            let outUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString)_diff_target.pdf")
            doc2.write(to: outUrl)
            targetPdfUrl = outUrl.absoluteString
        }

        resolver([
            "sourcePdfUrl": sourcePdfUrl,
            "targetPdfUrl": targetPdfUrl,
            "changes": changesPerPage,
            "totalAdded": totalAdded,
            "totalDeleted": totalDeleted,
            "totalChanged": totalChanged,
        ] as [String: Any])
    }

    // MARK: - Text extraction (auto: native word-level → OCR fallback)

    private struct Block {
        let text: String
        let x: Double
        let y: Double
        let width: Double
        let height: Double
    }

    /// Auto extraction: tries native word-level first, falls back to OCR if no blocks found
    private static func extractPageBlocksAuto(doc: PDFDocument, pageIndex: Int) -> ([Block], String) {
        guard let page = doc.page(at: pageIndex) else {
            return ([], "no_page")
        }

        // Try native word-level extraction first
        let nativeBlocks = extractNativeWordBlocks(page: page)
        if !nativeBlocks.isEmpty {
            return (nativeBlocks, "native")
        }

        // Native failed — try OCR
        NSLog("[Neurodoc DiffProcessor] page %d: native extraction returned 0 blocks, trying OCR", pageIndex)
        let ocrBlocks = extractOcrBlocks(page: page)
        if !ocrBlocks.isEmpty {
            return (ocrBlocks, "ocr")
        }

        NSLog("[Neurodoc DiffProcessor] page %d: OCR also returned 0 blocks", pageIndex)
        return ([], "empty")
    }

    // MARK: - Native word-level extraction

    /// Extract words from PDF page using NSString.enumerateSubstrings + PDFPage.selection
    private static func extractNativeWordBlocks(page: PDFPage) -> [Block] {
        guard let pageString = page.string, !pageString.isEmpty else {
            NSLog("[Neurodoc DiffProcessor] extractNativeWordBlocks: page.string is nil/empty")
            return []
        }

        let bounds = page.bounds(for: .mediaBox)
        let pageW = Double(bounds.width)
        let pageH = Double(bounds.height)
        let nsString = pageString as NSString
        let fullRange = NSRange(location: 0, length: nsString.length)

        var blocks: [Block] = []

        nsString.enumerateSubstrings(in: fullRange, options: .byWords) { word, wordRange, _, _ in
            guard let word = word, !word.isEmpty else { return }

            // Try to get selection bounds for this word range
            if let selection = page.selection(for: wordRange) {
                let b = selection.bounds(for: page)
                if b.width > 0 && b.height > 0 {
                    blocks.append(Block(
                        text: word,
                        x: Double(b.origin.x) / pageW,
                        y: 1.0 - (Double(b.origin.y) + Double(b.height)) / pageH,
                        width: Double(b.width) / pageW,
                        height: Double(b.height) / pageH
                    ))
                    return
                }
            }

            // Selection failed for this word — still include for diff (no bounds for highlighting)
            blocks.append(Block(text: word, x: 0, y: 0, width: 0, height: 0))
        }

        NSLog("[Neurodoc DiffProcessor] extractNativeWordBlocks: nsString.length=%d, words=%d", nsString.length, blocks.count)
        return blocks
    }

    // MARK: - OCR fallback extraction

    /// Extract text blocks using Vision OCR (for scanned/image PDFs)
    private static func extractOcrBlocks(page: PDFPage) -> [Block] {
        guard let cgImage = renderPageToImage(page: page) else {
            NSLog("[Neurodoc DiffProcessor] extractOcrBlocks: failed to render page to CGImage")
            return []
        }

        var observations: [VNRecognizedTextObservation] = []
        let semaphore = DispatchSemaphore(value: 0)

        let request = VNRecognizeTextRequest { request, error in
            if let results = request.results as? [VNRecognizedTextObservation] {
                observations = results
            }
            if let error = error {
                NSLog("[Neurodoc DiffProcessor] OCR error: %@", error.localizedDescription)
            }
            semaphore.signal()
        }

        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        do {
            try handler.perform([request])
        } catch {
            NSLog("[Neurodoc DiffProcessor] VNImageRequestHandler error: %@", error.localizedDescription)
            semaphore.signal()
        }
        semaphore.wait()

        // Split each observation into words for finer-grained diff
        var blocks: [Block] = []
        for observation in observations {
            guard let candidate = observation.topCandidates(1).first else { continue }
            let text = candidate.string
            let box = observation.boundingBox // normalized 0-1, bottom-left origin

            // Split line into words
            let words = text.components(separatedBy: .whitespaces).filter { !$0.isEmpty }
            if words.count <= 1 {
                // Single word or whole line as one block
                blocks.append(Block(
                    text: text.trimmingCharacters(in: .whitespaces),
                    x: box.origin.x,
                    y: 1.0 - box.origin.y - box.height,
                    width: box.width,
                    height: box.height
                ))
            } else {
                // Approximate word positions by dividing line box proportionally
                let totalChars = words.reduce(0) { $0 + $1.count }
                var xOffset = box.origin.x
                for word in words {
                    let wordFraction = Double(word.count) / Double(totalChars)
                    let wordWidth = box.width * wordFraction
                    blocks.append(Block(
                        text: word,
                        x: xOffset,
                        y: 1.0 - box.origin.y - box.height,
                        width: wordWidth,
                        height: box.height
                    ))
                    xOffset += wordWidth
                }
            }
        }

        NSLog("[Neurodoc DiffProcessor] extractOcrBlocks: observations=%d, words=%d", observations.count, blocks.count)
        return blocks
    }

    // MARK: - Annotation extraction

    /// Extract annotation blocks (notes, highlights, etc.) from a PDF page for diffing.
    /// Annotations are not part of page.string — they are separate overlay objects.
    private static func extractAnnotationBlocks(page: PDFPage) -> [Block] {
        let pageBounds = page.bounds(for: .mediaBox)
        let pageW = Double(pageBounds.width)
        let pageH = Double(pageBounds.height)

        var blocks: [Block] = []

        for annotation in page.annotations {
            // Skip system/internal annotation types
            let aType = annotation.type ?? ""
            guard !systemAnnotationTypes.contains(aType) else { continue }

            let bounds = annotation.bounds
            let contents = annotation.contents ?? ""
            // Build a unique text identifier for the annotation: type + contents
            let text = contents.isEmpty ? "[\(aType)]" : "[\(aType)] \(contents)"

            let nx = Double(bounds.origin.x) / pageW
            let ny = 1.0 - (Double(bounds.origin.y) + Double(bounds.height)) / pageH
            let nw = Double(bounds.width) / pageW
            let nh = Double(bounds.height) / pageH

            blocks.append(Block(text: text, x: nx, y: ny, width: nw, height: nh))
        }

        return blocks
    }

    // MARK: - Diff algorithm (Myers / LCS)

    private enum DiffOp {
        case equal(Block)
        case delete(Block)
        case insert(Block)
        case change(Block, Block) // old, new — fuzzy matched pair
    }

    /// LCS-based diff on text content of blocks.
    private static func myersDiff(old: [Block], new: [Block]) -> [DiffOp] {
        let m = old.count, n = new.count

        // Handle edge cases: empty arrays
        if m == 0 && n == 0 { return [] }
        if m == 0 { return new.map { .insert($0) } }
        if n == 0 { return old.map { .delete($0) } }

        // Build LCS table
        var lcs = Array(repeating: Array(repeating: 0, count: n + 1), count: m + 1)

        for i in 1...m {
            for j in 1...n {
                if old[i-1].text.lowercased() == new[j-1].text.lowercased() {
                    lcs[i][j] = lcs[i-1][j-1] + 1
                } else {
                    lcs[i][j] = max(lcs[i-1][j], lcs[i][j-1])
                }
            }
        }

        // Backtrack to get diff operations (raw delete/insert/equal)
        var ops: [DiffOp] = []
        var i = m, j = n
        while i > 0 || j > 0 {
            if i > 0 && j > 0 && old[i-1].text.lowercased() == new[j-1].text.lowercased() {
                ops.append(.equal(old[i-1]))
                i -= 1; j -= 1
            } else if j > 0 && (i == 0 || lcs[i][j-1] >= lcs[i-1][j]) {
                ops.append(.insert(new[j-1]))
                j -= 1
            } else {
                ops.append(.delete(old[i-1]))
                i -= 1
            }
        }
        ops.reverse()

        // Post-process: match adjacent delete+insert pairs as 'change' if fuzzy similar
        return mergeFuzzyChanges(ops: ops)
    }

    private static func mergeFuzzyChanges(ops: [DiffOp]) -> [DiffOp] {
        var result: [DiffOp] = []
        var pending: [Block] = [] // pending deletes

        for op in ops {
            switch op {
            case .delete(let b):
                pending.append(b)
            case .insert(let b):
                if let del = pending.first {
                    if fuzzyRatio(del.text, b.text) > 0.8 {
                        result.append(.change(del, b))
                        pending.removeFirst()
                    } else {
                        result.append(contentsOf: pending.map { .delete($0) })
                        pending.removeAll()
                        result.append(.insert(b))
                    }
                } else {
                    result.append(.insert(b))
                }
            default:
                result.append(contentsOf: pending.map { .delete($0) })
                pending.removeAll()
                result.append(op)
            }
        }
        result.append(contentsOf: pending.map { .delete($0) })
        return result
    }

    // MARK: - Fuzzy matching

    /// Levenshtein similarity ratio: 0.0 (completely different) to 1.0 (identical)
    private static func fuzzyRatio(_ a: String, _ b: String) -> Double {
        let a = a.lowercased(), b = b.lowercased()
        if a == b { return 1.0 }
        if a.isEmpty || b.isEmpty { return 0.0 }

        let lenA = a.count, lenB = b.count
        // Skip expensive Levenshtein for long strings — use prefix heuristic
        if lenA > 30 || lenB > 30 {
            var common = 0
            for (c1, c2) in zip(a, b) { if c1 != c2 { break }; common += 1 }
            return Double(common * 2) / Double(lenA + lenB)
        }

        var prev = Array(0...lenB)
        var curr = Array(repeating: 0, count: lenB + 1)

        for (i, ca) in a.enumerated() {
            curr[0] = i + 1
            for (j, cb) in b.enumerated() {
                let cost = ca == cb ? 0 : 1
                curr[j + 1] = Swift.min(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            prev = curr
        }

        let dist = Double(curr[lenB])
        let maxLen = Double(max(lenA, lenB))
        return 1.0 - dist / maxLen
    }

    // MARK: - Annotation helpers

    private static func rectDict(_ block: Block) -> [String: Any] {
        return ["x": block.x, "y": block.y, "width": block.width, "height": block.height]
    }

    private static func applyHighlights(page: PDFPage, rects: [[String: Any]], color: UIColor, opacity: CGFloat) {
        let pageBounds = page.bounds(for: .mediaBox)

        for rect in rects {
            guard let x = rect["x"] as? Double,
                  let y = rect["y"] as? Double,
                  let w = rect["width"] as? Double,
                  let h = rect["height"] as? Double else { continue }

            // Skip zero-size rects (blocks without bounds)
            guard w > 0 && h > 0 else { continue }

            let pdfX = CGFloat(x) * pageBounds.width
            let pdfY = (1.0 - CGFloat(y) - CGFloat(h)) * pageBounds.height
            let pdfW = CGFloat(w) * pageBounds.width
            let pdfH = CGFloat(h) * pageBounds.height

            // Add small padding for better visibility
            let padding: CGFloat = 2
            let bounds = CGRect(
                x: pdfX - padding,
                y: pdfY - padding,
                width: pdfW + padding * 2,
                height: pdfH + padding * 2
            )

            // Use square annotation (always visible) instead of highlight (requires text overlap)
            let annotation = PDFAnnotation(bounds: bounds, forType: .square, withProperties: nil)
            let fillColor = color.withAlphaComponent(opacity)
            annotation.color = color.withAlphaComponent(min(opacity + 0.2, 1.0)) // border
            annotation.setValue(fillColor, forAnnotationKey: .interiorColor)

            let border = PDFBorder()
            border.lineWidth = 1
            annotation.border = border

            page.addAnnotation(annotation)
        }
    }

}
