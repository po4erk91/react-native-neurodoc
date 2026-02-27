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

        var editsApplied = 0

        for (pageIndex, pageEdits) in editsByPage {
            guard pageIndex >= 0 && pageIndex < doc.pageCount,
                  let page = doc.page(at: pageIndex) else { continue }
            let bounds = page.bounds(for: .mediaBox)

            for edit in pageEdits {
                // Convert normalized (0-1, top-left origin) to PDF coords (points, bottom-left origin)
                let pdfX = CGFloat(edit.x) * bounds.width
                let pdfW = CGFloat(edit.width) * bounds.width
                let pdfH = CGFloat(edit.height) * bounds.height
                // top-left y → bottom-left y: flip and account for height
                let pdfY = bounds.height - CGFloat(edit.y) * bounds.height - pdfH

                let annotRect = CGRect(x: pdfX, y: pdfY, width: pdfW, height: pdfH)

                // 1. White-out: add a white rectangle annotation to cover original text
                let whiteOut = PDFAnnotation(bounds: annotRect.insetBy(dx: -1, dy: -1), forType: .freeText, withProperties: nil)
                whiteOut.font = UIFont.systemFont(ofSize: 1)
                whiteOut.contents = ""
                whiteOut.color = .clear
                whiteOut.backgroundColor = .white
                // Use interior color for the fill
                whiteOut.setValue(UIColor.white, forAnnotationKey: .color)
                // Make border invisible
                let whiteBorder = PDFBorder()
                whiteBorder.lineWidth = 0
                whiteOut.border = whiteBorder
                page.addAnnotation(whiteOut)

                // 2. Determine font with auto-shrink
                var fontSize: CGFloat
                if let fs = edit.fontSize, fs > 0 {
                    fontSize = CGFloat(fs)
                } else {
                    fontSize = pdfH * 0.85
                }

                let fontName = edit.fontName ?? "Helvetica"
                let minFontSize: CGFloat = 4.0
                let availableWidth = pdfW - 2

                var font = UIFont(name: fontName, size: fontSize)
                    ?? UIFont.systemFont(ofSize: fontSize)
                var attrs: [NSAttributedString.Key: Any] = [.font: font]
                var textSize = (edit.newText as NSString).size(withAttributes: attrs)

                while textSize.width > availableWidth && fontSize > minFontSize {
                    fontSize -= 0.5
                    font = UIFont(name: fontName, size: fontSize)
                        ?? UIFont.systemFont(ofSize: fontSize)
                    attrs[.font] = font
                    textSize = (edit.newText as NSString).size(withAttributes: attrs)
                }

                // 3. Add FreeText annotation with the new text
                let textAnnot = PDFAnnotation(bounds: annotRect, forType: .freeText, withProperties: nil)
                textAnnot.contents = edit.newText
                textAnnot.font = font
                textAnnot.fontColor = edit.color
                textAnnot.color = .clear
                textAnnot.backgroundColor = .clear
                let textBorder = PDFBorder()
                textBorder.lineWidth = 0
                textAnnot.border = textBorder
                textAnnot.alignment = .left
                page.addAnnotation(textAnnot)

                editsApplied += 1
            }
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)

        resolver([
            "pdfUrl": outputUrl.absoluteString,
            "editsApplied": editsApplied,
        ] as [String: Any])
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
