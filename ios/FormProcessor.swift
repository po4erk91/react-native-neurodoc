import Foundation
import PDFKit

class FormProcessor {

    private struct WidgetInfo {
        let fieldName: String
        let bounds: CGRect
        let fieldType: PDFAnnotationWidgetSubtype
        let value: String
        let font: UIFont
        let fontColor: UIColor
    }

    static func getFormFields(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("FORM_FAILED", "Failed to load PDF", nil)
            return
        }

        var fields: [[String: Any]] = []

        for pageIdx in 0..<doc.pageCount {
            guard let page = doc.page(at: pageIdx) else { continue }

            for annotation in page.annotations {
                guard annotation.type == "Widget" else { continue }

                let fieldName = annotation.fieldName ?? ""
                let widgetType = annotation.widgetFieldType
                let value = annotation.widgetStringValue ?? ""

                let type: String
                var options: [String] = []

                switch widgetType {
                case .button:
                    if annotation.buttonWidgetStateString == "Yes" || annotation.buttonWidgetStateString == "Off" {
                        type = "checkbox"
                    } else {
                        type = "radio"
                    }
                case .choice:
                    type = "dropdown"
                    if let choices = annotation.choices {
                        options = choices
                    }
                case .text:
                    type = "text"
                default:
                    type = "unknown"
                }

                var fieldDict: [String: Any] = [
                    "id": fieldName,
                    "name": fieldName,
                    "type": type,
                    "value": value,
                    "options": options,
                ]
                if let font = annotation.font {
                    fieldDict["fontName"] = font.fontName
                    fieldDict["fontSize"] = font.pointSize
                }
                fields.append(fieldDict)
            }
        }

        NSLog("[Neurodoc] getFormFields: found %d fields: %@", fields.count,
              fields.map { ($0["id"] as? String ?? "?") }.joined(separator: ", "))
        resolver(["fields": fields])
    }

    static func fillForm(pdfUrl: String, fields: [[String: Any]], flattenAfterFill: Bool, tempDirectory: URL, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("FORM_FAILED", "Failed to load PDF", nil)
            return
        }

        // Build lookups
        var fieldValues: [String: String] = [:]
        var fieldDicts: [String: [String: Any]] = [:]
        for field in fields {
            if let id = field["id"] as? String {
                if let value = field["value"] as? String {
                    fieldValues[id] = value
                }
                fieldDicts[id] = field
            }
        }

        // Collect widget info and remove them from pages
        var widgetsByPage: [Int: [WidgetInfo]] = [:]

        for pageIdx in 0..<doc.pageCount {
            guard let page = doc.page(at: pageIdx) else { continue }
            var pageWidgets: [WidgetInfo] = []

            let annotations = page.annotations.filter { $0.type == "Widget" }

            for annotation in annotations {
                guard let fieldName = annotation.fieldName,
                      let newValue = fieldValues[fieldName] else { continue }

                var targetFont = annotation.font ?? UIFont(name: "Helvetica", size: 12)!
                let targetColor = annotation.fontColor ?? .black

                if annotation.widgetFieldType == .text || annotation.widgetFieldType == .choice {
                    if let fieldDict = fieldDicts[fieldName] {
                        let requestedFontName = fieldDict["fontName"] as? String
                        let requestedFontSize = fieldDict["fontSize"] as? Double
                        let fontName = (requestedFontName != nil && !requestedFontName!.isEmpty)
                            ? requestedFontName!
                            : targetFont.fontName
                        let fontSize = (requestedFontSize != nil && requestedFontSize! > 0)
                            ? CGFloat(requestedFontSize!)
                            : targetFont.pointSize
                        if let f = UIFont(name: fontName, size: fontSize) {
                            targetFont = f
                        }
                    }
                }

                pageWidgets.append(WidgetInfo(
                    fieldName: fieldName,
                    bounds: annotation.bounds,
                    fieldType: annotation.widgetFieldType,
                    value: newValue,
                    font: targetFont,
                    fontColor: targetColor
                ))

                // Remove widget so page.draw() won't render stale text
                page.removeAnnotation(annotation)
            }
            widgetsByPage[pageIdx] = pageWidgets
        }

        // Render pages via CGContext with manual text drawing
        let pdfData = NSMutableData()
        UIGraphicsBeginPDFContextToData(pdfData, .zero, nil)

        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            let bounds = page.bounds(for: .mediaBox)

            UIGraphicsBeginPDFPageWithInfo(bounds, nil)
            guard let ctx = UIGraphicsGetCurrentContext() else { continue }

            // Draw page content (widgets removed)
            ctx.saveGState()
            ctx.translateBy(x: 0, y: bounds.height)
            ctx.scaleBy(x: 1, y: -1)
            page.draw(with: .mediaBox, to: ctx)
            ctx.restoreGState()

            // Draw field text with correct fonts
            if let widgets = widgetsByPage[i] {
                for w in widgets where w.fieldType != .button {
                    let drawRect = CGRect(
                        x: w.bounds.origin.x,
                        y: bounds.height - w.bounds.origin.y - w.bounds.height,
                        width: w.bounds.width,
                        height: w.bounds.height
                    )

                    // White-out field area to cover previously baked text
                    UIColor.white.setFill()
                    UIRectFill(drawRect)

                    let attrs: [NSAttributedString.Key: Any] = [
                        .font: w.font,
                        .foregroundColor: w.fontColor,
                    ]
                    let text = w.value as NSString
                    let textSize = text.size(withAttributes: attrs)
                    let yOffset = max(0, (drawRect.height - textSize.height) / 2)
                    let textRect = CGRect(
                        x: drawRect.origin.x + 2,
                        y: drawRect.origin.y + yOffset,
                        width: drawRect.width - 4,
                        height: textSize.height
                    )
                    text.draw(in: textRect, withAttributes: attrs)
                }
            }
        }

        UIGraphicsEndPDFContext()

        guard let outputDoc = PDFDocument(data: pdfData as Data) else {
            rejecter("FORM_FAILED", "Failed to create PDF", nil)
            return
        }

        if !flattenAfterFill {
            // Preview mode: re-add widget annotations so the form stays editable.
            // Widgets have empty value — the visible text is baked into page content.
            for i in 0..<outputDoc.pageCount {
                guard let outputPage = outputDoc.page(at: i),
                      let widgets = widgetsByPage[i] else { continue }

                for w in widgets {
                    let fresh = PDFAnnotation(bounds: w.bounds, forType: .widget, withProperties: nil)
                    fresh.fieldName = w.fieldName
                    fresh.widgetFieldType = w.fieldType

                    if w.fieldType == .button {
                        let boolValue = w.value == "true" || w.value == "Yes" || w.value == "1"
                        fresh.buttonWidgetState = boolValue ? .onState : .offState
                    } else {
                        fresh.widgetStringValue = ""
                        fresh.font = w.font
                        fresh.fontColor = w.fontColor
                    }
                    fresh.backgroundColor = .clear
                    let border = PDFBorder()
                    border.lineWidth = 0
                    fresh.border = border
                    outputPage.addAnnotation(fresh)
                }
            }
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        if flattenAfterFill {
            // Write raw data for flatten (no annotation overhead)
            do {
                try pdfData.write(to: outputUrl, options: .atomic)
                resolver(["pdfUrl": outputUrl.absoluteString])
            } catch {
                rejecter("FORM_FAILED", "Failed to save PDF: \(error.localizedDescription)", error as NSError)
            }
        } else {
            outputDoc.write(to: outputUrl)
            resolver(["pdfUrl": outputUrl.absoluteString])
        }
    }

}
