import Foundation
import PDFKit
import UIKit

class AnnotationProcessor {
    /// System/internal PDF annotation types that should be filtered from user-facing lists and diff.
    /// Watermark is intentionally NOT included — it should be visible in diff.
    private static let systemAnnotationTypes: Set<String> = ["Widget", "Link", "Popup", "PrinterMark", "TrapNet"]

    static func addAnnotations(pdfUrl: String, annotations: [[String: Any]], tempDirectory: URL, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("ANNOTATION_FAILED", "Failed to load PDF", nil)
            return
        }

        for annotationData in annotations {
            guard let type = annotationData["type"] as? String,
                  let pageIndex = annotationData["pageIndex"] as? Int,
                  let page = doc.page(at: pageIndex) else { continue }

            let pageBounds = page.bounds(for: .mediaBox)
            let color = UIColor(hex: (annotationData["color"] as? String) ?? "#FFFF00") ?? .yellow
            let opacity = CGFloat((annotationData["opacity"] as? Double) ?? 1.0)

            switch type {
            case "highlight":
                guard let rects = annotationData["rects"] as? [[String: Any]] else { continue }
                for rect in rects {
                    guard let x = rect["x"] as? Double,
                          let y = rect["y"] as? Double,
                          let w = rect["width"] as? Double,
                          let h = rect["height"] as? Double else { continue }

                    // Convert normalized coordinates to PDF coordinates (bottom-left origin)
                    let pdfX = CGFloat(x) * pageBounds.width
                    let pdfY = (1.0 - CGFloat(y) - CGFloat(h)) * pageBounds.height
                    let pdfW = CGFloat(w) * pageBounds.width
                    let pdfH = CGFloat(h) * pageBounds.height

                    let bounds = CGRect(x: pdfX, y: pdfY, width: pdfW, height: pdfH)
                    let annotation = PDFAnnotation(bounds: bounds, forType: .highlight, withProperties: nil)
                    annotation.color = color.withAlphaComponent(opacity)
                    page.addAnnotation(annotation)
                }

            case "note":
                guard let x = annotationData["x"] as? Double,
                      let y = annotationData["y"] as? Double else { continue }
                let text = annotationData["text"] as? String ?? ""

                let pdfX = CGFloat(x) * pageBounds.width
                let pdfY = (1.0 - CGFloat(y)) * pageBounds.height - 24

                let bounds = CGRect(x: pdfX, y: pdfY, width: 24, height: 24)
                let annotation = PDFAnnotation(bounds: bounds, forType: .text, withProperties: nil)
                annotation.contents = text
                annotation.color = color
                page.addAnnotation(annotation)

            case "freehand":
                guard let points = annotationData["points"] as? [[Double]] else { continue }
                let strokeWidth = CGFloat((annotationData["strokeWidth"] as? Double) ?? 2.0)

                guard points.count >= 2 else { continue }

                let path = UIBezierPath()
                let firstPoint = CGPoint(
                    x: CGFloat(points[0][0]) * pageBounds.width,
                    y: (1.0 - CGFloat(points[0][1])) * pageBounds.height
                )
                path.move(to: firstPoint)

                for i in 1..<points.count {
                    let pt = CGPoint(
                        x: CGFloat(points[i][0]) * pageBounds.width,
                        y: (1.0 - CGFloat(points[i][1])) * pageBounds.height
                    )
                    path.addLine(to: pt)
                }

                let annotation = PDFAnnotation(bounds: pageBounds, forType: .ink, withProperties: nil)
                annotation.add(path)
                annotation.color = color
                annotation.border = PDFBorder()
                annotation.border?.lineWidth = strokeWidth
                page.addAnnotation(annotation)

            default:
                break
            }
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)
        resolver(["pdfUrl": outputUrl.absoluteString])
    }

    static func getAnnotations(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("ANNOTATION_FAILED", "Failed to load PDF", nil)
            return
        }

        var annotations: [[String: Any]] = []

        for pageIdx in 0..<doc.pageCount {
            guard let page = doc.page(at: pageIdx) else { continue }
            let pageBounds = page.bounds(for: .mediaBox)

            for annotation in page.annotations {
                // Skip system/internal annotation types
                let rawType = annotation.type ?? ""
                guard !systemAnnotationTypes.contains(rawType) else { continue }

                let bounds = annotation.bounds
                let normalizedX = bounds.origin.x / pageBounds.width
                let normalizedY = 1.0 - (bounds.origin.y + bounds.height) / pageBounds.height
                let normalizedW = bounds.width / pageBounds.width
                let normalizedH = bounds.height / pageBounds.height

                let type: String
                switch annotation.type {
                case "Highlight": type = "highlight"
                case "Text": type = "note"
                case "Ink": type = "freehand"
                case "Underline": type = "underline"
                case "StrikeOut": type = "strikethrough"
                default: type = annotation.type ?? "unknown"
                }

                let colorHex = annotation.color.hexString ?? "#000000"

                annotations.append([
                    "id": "\(pageIdx)_\(bounds.origin.x)_\(bounds.origin.y)_\(type)",
                    "type": type,
                    "pageIndex": pageIdx,
                    "color": colorHex,
                    "x": normalizedX,
                    "y": normalizedY,
                    "width": normalizedW,
                    "height": normalizedH,
                    "text": annotation.contents ?? "",
                ] as [String: Any])
            }
        }

        resolver(["annotations": annotations])
    }

    static func deleteAnnotation(pdfUrl: String, annotationId: String, tempDirectory: URL, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("ANNOTATION_FAILED", "Failed to load PDF", nil)
            return
        }

        var found = false

        for pageIdx in 0..<doc.pageCount {
            guard let page = doc.page(at: pageIdx) else { continue }
            let pageBounds = page.bounds(for: .mediaBox)

            for annotation in page.annotations {
                let type: String
                switch annotation.type {
                case "Highlight": type = "highlight"
                case "Text": type = "note"
                case "Ink": type = "freehand"
                case "Underline": type = "underline"
                case "StrikeOut": type = "strikethrough"
                default: type = annotation.type ?? "unknown"
                }

                let bounds = annotation.bounds
                let id = "\(pageIdx)_\(bounds.origin.x)_\(bounds.origin.y)_\(type)"

                if id == annotationId {
                    page.removeAnnotation(annotation)
                    // Remove orphaned Popup annotations (PDFKit creates one per Text annotation)
                    // After removing the note, clean up Popups that no longer have a parent
                    let remainingNotes = page.annotations.filter { $0.type == "Text" }
                    let popups = page.annotations.filter { $0.type == "Popup" }
                    // If more popups than notes, remove excess (orphaned)
                    if popups.count > remainingNotes.count {
                        for popup in popups.suffix(popups.count - remainingNotes.count) {
                            page.removeAnnotation(popup)
                        }
                    }
                    found = true
                    break
                }
            }
            if found { break }
        }

        if !found {
            rejecter("ANNOTATION_FAILED", "Annotation not found: \(annotationId)", nil)
            return
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        doc.write(to: outputUrl)
        resolver(["pdfUrl": outputUrl.absoluteString])
    }

    // MARK: - Helpers

    private static func resolveUrl(_ urlString: String) -> URL? {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)
        }
        return URL(fileURLWithPath: urlString)
    }
}

// MARK: - UIColor hex string

extension UIColor {
    var hexString: String? {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int(r * 255), Int(g * 255), Int(b * 255))
    }
}
