import Foundation
import PDFKit
import UIKit

class FormCreator {

    static func createFormFromPdf(
        pdfUrl: String,
        fields: [[String: Any]],
        removeOriginalText: Bool,
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("FORM_FAILED", "Failed to load PDF", nil)
            return
        }

        // Group fields by page
        var fieldsByPage: [Int: [[String: Any]]] = [:]
        for field in fields {
            let pageIndex = (field["pageIndex"] as? Int) ?? 0
            fieldsByPage[pageIndex, default: []].append(field)
        }

        for (pageIndex, pageFields) in fieldsByPage {
            guard pageIndex >= 0 && pageIndex < doc.pageCount, let page = doc.page(at: pageIndex) else { continue }

            let bounds = page.bounds(for: .mediaBox)

            for field in pageFields {
                guard let name = field["name"] as? String,
                      let bbox = field["boundingBox"] as? [String: Any] else { continue }

                let nx = (bbox["x"] as? Double) ?? 0
                let ny = (bbox["y"] as? Double) ?? 0
                let nw = (bbox["width"] as? Double) ?? 0
                let nh = (bbox["height"] as? Double) ?? 0

                // Convert from normalized top-left to PDF bottom-left
                let pdfX = CGFloat(nx) * bounds.width
                let pdfY = bounds.height - CGFloat(ny) * bounds.height - CGFloat(nh) * bounds.height
                let pdfW = CGFloat(nw) * bounds.width
                let pdfH = CGFloat(nh) * bounds.height

                let rect = CGRect(x: pdfX, y: pdfY, width: pdfW, height: pdfH)

                // White-out original text
                if removeOriginalText {
                    addWhiteoutAnnotation(page: page, rect: rect)
                }

                // Add form field
                let type = (field["type"] as? String) ?? "text"
                let defaultValue = (field["defaultValue"] as? String) ?? ""
                let fontSize = CGFloat((field["fontSize"] as? Double) ?? 0)

                let fontName = field["fontName"] as? String

                if type == "checkbox" {
                    addCheckboxField(page: page, rect: rect, name: name, defaultValue: defaultValue)
                } else {
                    addTextField(page: page, rect: rect, name: name, defaultValue: defaultValue, fontSize: fontSize, fontName: fontName)
                }
            }
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)

        resolver(["pdfUrl": outputUrl.absoluteString])
    }

    // MARK: - White-out

    private static func addWhiteoutAnnotation(page: PDFPage, rect: CGRect) {
        // Slightly expand rect to ensure full coverage
        let expandedRect = rect.insetBy(dx: -1, dy: -1)

        let whiteout = PDFAnnotation(bounds: expandedRect, forType: .square, withProperties: nil)
        whiteout.color = .white
        whiteout.interiorColor = .white

        let border = PDFBorder()
        border.lineWidth = 0
        whiteout.border = border

        page.addAnnotation(whiteout)
    }

    // MARK: - Font detection

    /// Detect font name and size of existing text at a given rect on the page.
    /// Uses PDFKit's attributedString from a selection covering the rect.
    private static func detectFont(page: PDFPage, rect: CGRect) -> (name: String, size: CGFloat)? {
        // Try to get a selection covering the field rect
        guard let selection = page.selection(for: rect) else { return nil }
        guard let attrString = selection.attributedString, attrString.length > 0 else { return nil }

        // Sample font from the first character of the selection
        let attrs = attrString.attributes(at: 0, effectiveRange: nil)
        guard let font = attrs[.font] as? UIFont else { return nil }

        return (name: font.fontName, size: font.pointSize)
    }

    // MARK: - Form fields

    private static func addTextField(page: PDFPage, rect: CGRect, name: String, defaultValue: String, fontSize: CGFloat, fontName: String?) {
        // Try to detect original font at this location
        let detectedFont = detectFont(page: page, rect: rect)

        let effectiveFontSize = (fontSize > 0) ? fontSize : (detectedFont?.size ?? 12.0)
        let effectiveFontName = fontName ?? detectedFont?.name ?? "Helvetica"

        let textWidget = PDFAnnotation(bounds: rect, forType: .widget, withProperties: nil)
        textWidget.fieldName = name
        textWidget.widgetFieldType = .text
        textWidget.widgetStringValue = defaultValue
        textWidget.font = UIFont(name: effectiveFontName, size: effectiveFontSize) ?? UIFont.systemFont(ofSize: effectiveFontSize)
        textWidget.fontColor = .black
        textWidget.backgroundColor = UIColor.clear

        let border = PDFBorder()
        border.lineWidth = 0.5
        textWidget.border = border

        page.addAnnotation(textWidget)
    }

    private static func addCheckboxField(page: PDFPage, rect: CGRect, name: String, defaultValue: String) {
        let checkbox = PDFAnnotation(bounds: rect, forType: .widget, withProperties: nil)
        checkbox.fieldName = name
        checkbox.widgetFieldType = .button

        let isChecked = defaultValue == "true" || defaultValue == "Yes" || defaultValue == "1"
        checkbox.buttonWidgetState = isChecked ? .onState : .offState
        checkbox.backgroundColor = UIColor.clear

        let border = PDFBorder()
        border.lineWidth = 0.5
        checkbox.border = border

        page.addAnnotation(checkbox)
    }

}
