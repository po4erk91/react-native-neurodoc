import Foundation
import PDFKit
import UIKit

class ContentEditor {

    static func editContent(
        pdfUrl: String,
        edits: [[String: Any]],
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("CONTENT_EDIT_FAILED", "Failed to load PDF", nil)
            return
        }

        // Group edits by pageIndex
        var editsByPage: [Int: [EditInfo]] = [:]
        for edit in edits {
            guard let pageIndex = edit["pageIndex"] as? Int,
                  let bbox = edit["boundingBox"] as? [String: Any],
                  let newText = edit["newText"] as? String else { continue }

            let x = (bbox["x"] as? Double) ?? 0
            let y = (bbox["y"] as? Double) ?? 0
            let width = (bbox["width"] as? Double) ?? 0
            let height = (bbox["height"] as? Double) ?? 0
            let fontSize = edit["fontSize"] as? Double
            let fontName = edit["fontName"] as? String
            let colorHex = (edit["color"] as? String) ?? "#000000"
            let color = UIColor(hex: colorHex) ?? .black

            editsByPage[pageIndex, default: []].append(EditInfo(
                x: x, y: y, width: width, height: height,
                newText: newText,
                fontSize: fontSize,
                fontName: fontName,
                color: color
            ))
        }

        let pdfData = NSMutableData()
        UIGraphicsBeginPDFContextToData(pdfData, .zero, nil)

        var editsApplied = 0

        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            let bounds = page.bounds(for: .mediaBox)

            UIGraphicsBeginPDFPageWithInfo(bounds, nil)
            guard let ctx = UIGraphicsGetCurrentContext() else { continue }

            // Draw original page vector content
            ctx.saveGState()
            ctx.translateBy(x: 0, y: bounds.height)
            ctx.scaleBy(x: 1, y: -1)
            page.draw(with: .mediaBox, to: ctx)
            ctx.restoreGState()

            // Apply edits for this page (UIKit coords: top-left origin after restoreGState)
            if let pageEdits = editsByPage[i] {
                for edit in pageEdits {
                    let rectX = CGFloat(edit.x) * bounds.width
                    let rectY = CGFloat(edit.y) * bounds.height
                    let rectW = CGFloat(edit.width) * bounds.width
                    let rectH = CGFloat(edit.height) * bounds.height

                    let drawRect = CGRect(x: rectX, y: rectY, width: rectW, height: rectH)

                    // 1. White-out original text (slightly expanded for coverage)
                    UIColor.white.setFill()
                    UIRectFill(drawRect.insetBy(dx: -1, dy: -1))

                    // 2. Determine font
                    let targetFontSize: CGFloat
                    if let fs = edit.fontSize, fs > 0 {
                        targetFontSize = CGFloat(fs)
                    } else {
                        targetFontSize = rectH * 0.85
                    }

                    let font = UIFont(name: edit.fontName ?? "Helvetica", size: targetFontSize)
                        ?? UIFont.systemFont(ofSize: targetFontSize)

                    // 3. Draw new text vertically centered
                    let attrs: [NSAttributedString.Key: Any] = [
                        .font: font,
                        .foregroundColor: edit.color,
                    ]
                    let textSize = (edit.newText as NSString).size(withAttributes: attrs)
                    let yOffset = max(0, (drawRect.height - textSize.height) / 2)
                    let textRect = CGRect(
                        x: drawRect.origin.x + 1,
                        y: drawRect.origin.y + yOffset,
                        width: drawRect.width - 2,
                        height: textSize.height
                    )
                    (edit.newText as NSString).draw(in: textRect, withAttributes: attrs)

                    editsApplied += 1
                }
            }
        }

        UIGraphicsEndPDFContext()

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        do {
            try pdfData.write(to: outputUrl, options: .atomic)
            resolver([
                "pdfUrl": outputUrl.absoluteString,
                "editsApplied": editsApplied,
            ] as [String: Any])
        } catch {
            rejecter("CONTENT_EDIT_FAILED", "Failed to save edited PDF: \(error.localizedDescription)", error as NSError)
        }
    }

    // MARK: - Helpers

    private struct EditInfo {
        let x: Double
        let y: Double
        let width: Double
        let height: Double
        let newText: String
        let fontSize: Double?
        let fontName: String?
        let color: UIColor
    }

    private static func resolveUrl(_ urlString: String) -> URL? {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)
        }
        return URL(fileURLWithPath: urlString)
    }
}
