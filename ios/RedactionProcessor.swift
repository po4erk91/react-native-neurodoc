import Foundation
import PDFKit
import UIKit

class RedactionProcessor {

    static func redact(
        pdfUrl: String,
        redactions: [[String: Any]],
        dpi: Double,
        stripMetadata: Bool,
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("REDACTION_FAILED", "Failed to load PDF", nil)
            return
        }

        // Group redactions by pageIndex
        var redactionsByPage: [Int: [RedactionArea]] = [:]
        for redaction in redactions {
            guard let pageIndex = redaction["pageIndex"] as? Int,
                  let rects = redaction["rects"] as? [[String: Any]] else { continue }

            let color = UIColor(hex: (redaction["color"] as? String) ?? "#000000") ?? .black

            let areas = rects.compactMap { rect -> RedactionArea? in
                guard let x = rect["x"] as? Double,
                      let y = rect["y"] as? Double,
                      let w = rect["width"] as? Double,
                      let h = rect["height"] as? Double else { return nil }
                return RedactionArea(x: x, y: y, width: w, height: h, color: color)
            }

            redactionsByPage[pageIndex, default: []].append(contentsOf: areas)
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")

        guard let pdfContext = CGContext(outputUrl as CFURL, mediaBox: nil, nil) else {
            rejecter("REDACTION_FAILED", "Failed to create PDF context", nil)
            return
        }

        let scale = CGFloat(dpi) / 72.0
        var pagesRedacted = 0

        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            let bounds = page.bounds(for: .mediaBox)
            var mediaBox = bounds

            if let areas = redactionsByPage[i], !areas.isEmpty {
                // Rasterize this page to destroy original content
                let pixelWidth = Int(bounds.width * scale)
                let pixelHeight = Int(bounds.height * scale)
                let size = CGSize(width: pixelWidth, height: pixelHeight)

                let renderer = UIGraphicsImageRenderer(size: size)
                let image = renderer.image { ctx in
                    UIColor.white.setFill()
                    ctx.fill(CGRect(origin: .zero, size: size))

                    // Draw original page (flip for PDFPage.draw which uses bottom-left origin)
                    ctx.cgContext.saveGState()
                    ctx.cgContext.translateBy(x: 0, y: CGFloat(pixelHeight))
                    ctx.cgContext.scaleBy(x: scale, y: -scale)
                    page.draw(with: .mediaBox, to: ctx.cgContext)
                    ctx.cgContext.restoreGState()

                    // Draw redaction rectangles (normalized 0-1, top-left origin → pixel space)
                    for area in areas {
                        let rectX = CGFloat(area.x) * CGFloat(pixelWidth)
                        let rectY = CGFloat(area.y) * CGFloat(pixelHeight)
                        let rectW = CGFloat(area.width) * CGFloat(pixelWidth)
                        let rectH = CGFloat(area.height) * CGFloat(pixelHeight)

                        area.color.setFill()
                        ctx.fill(CGRect(x: rectX, y: rectY, width: rectW, height: rectH))
                    }
                }

                guard let cgImage = image.cgImage else { continue }

                pdfContext.beginPage(mediaBox: &mediaBox)
                pdfContext.draw(cgImage, in: bounds)
                pdfContext.endPage()

                pagesRedacted += 1
            } else {
                // Pass-through: keep original vector content
                pdfContext.beginPage(mediaBox: &mediaBox)
                page.draw(with: .mediaBox, to: pdfContext)
                pdfContext.endPage()
            }
        }

        pdfContext.closePDF()

        if stripMetadata {
            stripPdfMetadata(at: outputUrl)
        }

        resolver([
            "pdfUrl": outputUrl.absoluteString,
            "pagesRedacted": pagesRedacted,
        ] as [String: Any])
    }

    // MARK: - Helpers

    private static func stripPdfMetadata(at url: URL) {
        guard let doc = PDFDocument(url: url) else { return }
        doc.documentAttributes = [
            PDFDocumentAttribute.titleAttribute: "",
            PDFDocumentAttribute.authorAttribute: "",
            PDFDocumentAttribute.subjectAttribute: "",
            PDFDocumentAttribute.creatorAttribute: "",
            PDFDocumentAttribute.producerAttribute: "",
            PDFDocumentAttribute.keywordsAttribute: [] as [String],
        ]
        doc.write(to: url)
    }

    private struct RedactionArea {
        let x: Double
        let y: Double
        let width: Double
        let height: Double
        let color: UIColor
    }

}
