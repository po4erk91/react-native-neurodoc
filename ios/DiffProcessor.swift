import Foundation
import PDFKit
import UIKit

class DiffProcessor {

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

        // --- Compare shared pages ---
        for i in 0..<sharedPages {
            let blocks1 = extractPageBlocks(doc: doc1, pageIndex: i)
            let blocks2 = extractPageBlocks(doc: doc2, pageIndex: i)

            let diff = myersDiff(old: blocks1, new: blocks2)

            var added = 0; var deleted = 0; var changed = 0

            // Collect highlight rects per document
            var sourceRects: [[String: Any]] = []
            var targetRects: [[String: Any]] = []

            for op in diff {
                switch op {
                case .delete(let block):
                    sourceRects.append(rectDict(block))
                    deleted += 1
                case .insert(let block):
                    targetRects.append(rectDict(block))
                    added += 1
                case .change(let oldBlock, let newBlock):
                    sourceRects.append(rectDict(oldBlock))
                    targetRects.append(rectDict(newBlock))
                    changed += 1
                case .equal:
                    break
                }
            }

            // Apply highlights
            if annotateSource && !sourceRects.isEmpty, let page = doc1.page(at: i) {
                applyHighlights(page: page, rects: sourceRects, color: colorDeleted, opacity: alpha)
                // changed blocks on source in changed color
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
            if annotateSource, let page = doc1.page(at: i) {
                let blocks = extractPageBlocks(doc: doc1, pageIndex: i)
                let allRects = blocks.map { rectDict($0) }
                if !allRects.isEmpty {
                    applyHighlights(page: page, rects: allRects, color: colorDeleted, opacity: alpha)
                }
            }
            changesPerPage.append([
                "pageIndex1": i,
                "pageIndex2": -1,
                "added": 0,
                "deleted": extractPageBlocks(doc: doc1, pageIndex: i).count,
                "changed": 0,
            ])
            totalDeleted += extractPageBlocks(doc: doc1, pageIndex: i).count
        }

        // --- Pages only in doc2 (added pages) ---
        for i in sharedPages..<pageCount2 {
            if annotateTarget, let page = doc2.page(at: i) {
                let blocks = extractPageBlocks(doc: doc2, pageIndex: i)
                let allRects = blocks.map { rectDict($0) }
                if !allRects.isEmpty {
                    applyHighlights(page: page, rects: allRects, color: colorAdded, opacity: alpha)
                }
            }
            changesPerPage.append([
                "pageIndex1": -1,
                "pageIndex2": i,
                "added": extractPageBlocks(doc: doc2, pageIndex: i).count,
                "deleted": 0,
                "changed": 0,
            ])
            totalAdded += extractPageBlocks(doc: doc2, pageIndex: i).count
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

    // MARK: - Text extraction (internal, native only for speed)

    private struct Block {
        let text: String
        let x: Double
        let y: Double
        let width: Double
        let height: Double
    }

    private static func extractPageBlocks(doc: PDFDocument, pageIndex: Int) -> [Block] {
        guard let page = doc.page(at: pageIndex),
              let pageString = page.string, !pageString.isEmpty else { return [] }

        let bounds = page.bounds(for: .mediaBox)
        let pageW = Double(bounds.width)
        let pageH = Double(bounds.height)

        var characters: [(char: Character, bounds: CGRect)] = []

        for i in 0..<pageString.count {
            let selection = page.selection(for: NSRange(location: i, length: 1))
            guard let sel = selection else { continue }
            let b = sel.bounds(for: page)
            guard b.width > 0, b.height > 0 else { continue }
            let charIndex = pageString.index(pageString.startIndex, offsetBy: i)
            characters.append((char: pageString[charIndex], bounds: b))
        }

        return groupToBlocks(characters: characters, pageW: pageW, pageH: pageH)
    }

    private static func groupToBlocks(characters: [(char: Character, bounds: CGRect)], pageW: Double, pageH: Double) -> [Block] {
        guard !characters.isEmpty else { return [] }

        var words: [Block] = []
        var currentWord = String(characters[0].char)
        var currentBounds = characters[0].bounds

        for i in 1..<characters.count {
            let char = characters[i]
            let prev = characters[i - 1]

            let isWhitespace = char.char.isWhitespace || char.char.isNewline
            let isNewLine = abs(char.bounds.midY - prev.bounds.midY) > prev.bounds.height * 0.5
            let gap = char.bounds.minX - prev.bounds.maxX
            let isLargeGap = gap > prev.bounds.width * 0.4

            if isWhitespace || isNewLine || isLargeGap {
                let trimmed = currentWord.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty {
                    words.append(makeBlock(text: trimmed, bounds: currentBounds, pageW: pageW, pageH: pageH))
                }
                if !isWhitespace {
                    currentWord = String(char.char)
                    currentBounds = char.bounds
                } else {
                    currentWord = ""
                    currentBounds = .zero
                }
            } else {
                currentWord.append(char.char)
                currentBounds = currentBounds.union(char.bounds)
            }
        }

        let trimmed = currentWord.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty {
            words.append(makeBlock(text: trimmed, bounds: currentBounds, pageW: pageW, pageH: pageH))
        }
        return words
    }

    private static func makeBlock(text: String, bounds: CGRect, pageW: Double, pageH: Double) -> Block {
        Block(
            text: text,
            x: Double(bounds.origin.x) / pageW,
            y: 1.0 - (Double(bounds.origin.y) + Double(bounds.height)) / pageH,
            width: Double(bounds.width) / pageW,
            height: Double(bounds.height) / pageH
        )
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
        // Build LCS table
        let m = old.count, n = new.count
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

            let pdfX = CGFloat(x) * pageBounds.width
            let pdfY = (1.0 - CGFloat(y) - CGFloat(h)) * pageBounds.height
            let pdfW = CGFloat(w) * pageBounds.width
            let pdfH = CGFloat(h) * pageBounds.height

            let bounds = CGRect(x: pdfX, y: pdfY, width: pdfW, height: pdfH)
            let annotation = PDFAnnotation(bounds: bounds, forType: .highlight, withProperties: nil)
            annotation.color = color.withAlphaComponent(opacity)
            page.addAnnotation(annotation)
        }
    }

    // MARK: - URL helper

    private static func resolveUrl(_ urlString: String) -> URL? {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)
        }
        return URL(fileURLWithPath: urlString)
    }
}
