import Foundation
import PDFKit

class BookmarkProcessor {

    // MARK: - getBookmarks

    static func getBookmarks(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("BOOKMARK_FAILED", "Failed to load PDF from \(pdfUrl)", nil)
            return
        }

        var bookmarks: [[String: Any]] = []

        if let outline = doc.outlineRoot {
            flattenOutline(outline, level: 0, bookmarks: &bookmarks, document: doc)
        }

        resolver(["bookmarks": bookmarks])
    }

    // MARK: - addBookmarks

    static func addBookmarks(pdfUrl: String, bookmarks: [[String: Any]], tempDirectory: URL, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("BOOKMARK_FAILED", "Failed to load PDF from \(pdfUrl)", nil)
            return
        }

        // Ensure outline root exists
        if doc.outlineRoot == nil {
            doc.outlineRoot = PDFOutline()
        }

        guard let root = doc.outlineRoot else {
            rejecter("BOOKMARK_FAILED", "Failed to create outline root", nil)
            return
        }

        // Build flat list of existing bookmarks for parentIndex resolution
        var existingNodes: [PDFOutline] = []
        flattenOutlineNodes(root, nodes: &existingNodes)

        for bm in bookmarks {
            guard let title = bm["title"] as? String,
                  let pageIndex = bm["pageIndex"] as? Int else {
                continue
            }

            guard pageIndex >= 0 && pageIndex < doc.pageCount,
                  let page = doc.page(at: pageIndex) else {
                continue
            }

            let outline = PDFOutline()
            outline.label = title
            outline.destination = PDFDestination(page: page, at: CGPoint(x: 0, y: page.bounds(for: .mediaBox).height))

            if let parentIndex = bm["parentIndex"] as? Int,
               parentIndex >= 0 && parentIndex < existingNodes.count {
                let parent = existingNodes[parentIndex]
                parent.insertChild(outline, at: parent.numberOfChildren)
            } else {
                root.insertChild(outline, at: root.numberOfChildren)
            }

            existingNodes.append(outline)
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)
        resolver(["pdfUrl": outputUrl.absoluteString])
    }

    // MARK: - removeBookmarks

    static func removeBookmarks(pdfUrl: String, indexes: [Int], tempDirectory: URL, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("BOOKMARK_FAILED", "Failed to load PDF from \(pdfUrl)", nil)
            return
        }

        guard let root = doc.outlineRoot else {
            // No bookmarks to remove
            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            doc.write(to: outputUrl)
            resolver(["pdfUrl": outputUrl.absoluteString])
            return
        }

        // Build flat list
        var nodes: [PDFOutline] = []
        flattenOutlineNodes(root, nodes: &nodes)

        // Remove in reverse order to preserve indexes
        let sortedIndexes = Set(indexes).sorted(by: >)
        for idx in sortedIndexes {
            guard idx >= 0 && idx < nodes.count else { continue }
            let node = nodes[idx]
            node.removeFromParent()
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)
        resolver(["pdfUrl": outputUrl.absoluteString])
    }

    // MARK: - Helpers

    private static func flattenOutline(_ outline: PDFOutline, level: Int, bookmarks: inout [[String: Any]], document: PDFDocument) {
        for i in 0..<outline.numberOfChildren {
            guard let child = outline.child(at: i) else { continue }

            let pageIndex: Int
            if let dest = child.destination, let page = dest.page {
                pageIndex = document.index(for: page)
            } else {
                pageIndex = -1
            }

            bookmarks.append([
                "title": child.label ?? "",
                "pageIndex": pageIndex,
                "level": level,
                "children": child.numberOfChildren,
            ])

            if child.numberOfChildren > 0 {
                flattenOutline(child, level: level + 1, bookmarks: &bookmarks, document: document)
            }
        }
    }

    private static func flattenOutlineNodes(_ outline: PDFOutline, nodes: inout [PDFOutline]) {
        for i in 0..<outline.numberOfChildren {
            guard let child = outline.child(at: i) else { continue }
            nodes.append(child)
            if child.numberOfChildren > 0 {
                flattenOutlineNodes(child, nodes: &nodes)
            }
        }
    }
}
