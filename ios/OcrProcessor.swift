import Foundation
import PDFKit
import Vision
import UIKit

class OcrProcessor {
    static func recognizePage(pdfUrl: String, pageIndex: Int, language: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("OCR_FAILED", "Failed to load PDF", nil)
            return
        }

        guard let page = doc.page(at: pageIndex) else {
            rejecter("OCR_FAILED", "Invalid page index: \(pageIndex)", nil)
            return
        }

        guard let cgImage = renderPageToImage(page: page) else {
            rejecter("OCR_FAILED", "Failed to render page to image", nil)
            return
        }

        let request = VNRecognizeTextRequest { request, error in
            if let error = error {
                rejecter("OCR_FAILED", "OCR failed: \(error.localizedDescription)", error as NSError)
                return
            }

            guard let observations = request.results as? [VNRecognizedTextObservation] else {
                resolver(["blocks": []])
                return
            }

            let blocks: [[String: Any]] = observations.compactMap { observation in
                guard let candidate = observation.topCandidates(1).first else { return nil }

                let box = observation.boundingBox
                return [
                    "text": candidate.string,
                    "boundingBox": [
                        "x": box.origin.x,
                        "y": 1.0 - box.origin.y - box.height, // Convert from bottom-left to top-left
                        "width": box.width,
                        "height": box.height,
                    ],
                    "confidence": candidate.confidence,
                ] as [String: Any]
            }

            resolver(["blocks": blocks])
        }

        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true

        if language != "auto" {
            request.recognitionLanguages = [language]
        }

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        do {
            try handler.perform([request])
        } catch {
            rejecter("OCR_FAILED", "OCR request failed: \(error.localizedDescription)", error as NSError)
        }
    }

    static func makeSearchable(pdfUrl: String, language: String, pageIndexes: [Int]?, tempDirectory: URL, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("OCR_FAILED", "Failed to load PDF", nil)
            return
        }

        let targetPages = pageIndexes ?? Array(0..<doc.pageCount)
        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")

        // Use CGContext directly for thread safety
        guard let pdfContext = CGContext(outputUrl as CFURL, mediaBox: nil, nil) else {
            rejecter("OCR_FAILED", "Failed to create PDF context", nil)
            return
        }

        var pagesProcessed = 0

        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            let bounds = page.bounds(for: .mediaBox)
            var mediaBox = bounds

            pdfContext.beginPage(mediaBox: &mediaBox)

            // Draw original page — PDF context is already in PDF coordinate space
            // (bottom-left origin), same as PDFPage.draw, so no transform needed
            page.draw(with: .mediaBox, to: pdfContext)

            // Add OCR text layer for target pages
            if targetPages.contains(i) {
                if let cgImage = renderPageToImage(page: page) {
                    let ocrResults = performOcrSync(cgImage: cgImage, language: language)

                    // Flip context for UIKit text drawing (NSString.draw expects top-left origin)
                    pdfContext.saveGState()
                    pdfContext.translateBy(x: 0, y: bounds.height)
                    pdfContext.scaleBy(x: 1, y: -1)

                    UIGraphicsPushContext(pdfContext)

                    for block in ocrResults {
                        // Vision coordinates are bottom-left, normalized 0-1
                        let textX = block.boundingBox.origin.x * bounds.width
                        let textY = block.boundingBox.origin.y * bounds.height
                        let textW = block.boundingBox.width * bounds.width
                        let textH = block.boundingBox.height * bounds.height

                        let fontSize = max(textH * 0.8, 4)

                        let attrs: [NSAttributedString.Key: Any] = [
                            .font: UIFont.systemFont(ofSize: fontSize),
                            .foregroundColor: UIColor.clear,
                        ]

                        // Convert from bottom-left origin to top-left origin for drawing
                        let rect = CGRect(x: textX, y: bounds.height - textY - textH, width: textW, height: textH)
                        (block.text as NSString).draw(in: rect, withAttributes: attrs)
                    }

                    UIGraphicsPopContext()
                    pdfContext.restoreGState()
                    pagesProcessed += 1
                }
            }

            pdfContext.endPage()
        }

        pdfContext.closePDF()

        resolver([
            "pdfUrl": outputUrl.absoluteString,
            "pagesProcessed": pagesProcessed,
        ] as [String: Any])
    }

    // MARK: - Helpers

    private static func resolveUrl(_ urlString: String) -> URL? {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)
        }
        return URL(fileURLWithPath: urlString)
    }

    private static func renderPageToImage(page: PDFPage, scale: CGFloat = 2.0) -> CGImage? {
        let bounds = page.bounds(for: .mediaBox)
        let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)

        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))

            ctx.cgContext.saveGState()
            ctx.cgContext.translateBy(x: 0, y: size.height)
            ctx.cgContext.scaleBy(x: scale, y: -scale)
            page.draw(with: .mediaBox, to: ctx.cgContext)
            ctx.cgContext.restoreGState()
        }

        return image.cgImage
    }

    private struct OcrBlock {
        let text: String
        let boundingBox: CGRect // Vision normalized coords (bottom-left origin)
    }

    private static func performOcrSync(cgImage: CGImage, language: String) -> [OcrBlock] {
        var results: [OcrBlock] = []
        let semaphore = DispatchSemaphore(value: 0)

        let request = VNRecognizeTextRequest { request, _ in
            if let observations = request.results as? [VNRecognizedTextObservation] {
                for observation in observations {
                    if let candidate = observation.topCandidates(1).first {
                        results.append(OcrBlock(
                            text: candidate.string,
                            boundingBox: observation.boundingBox
                        ))
                    }
                }
            }
            semaphore.signal()
        }

        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        if language != "auto" {
            request.recognitionLanguages = [language]
        }

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try? handler.perform([request])

        // If the request was synchronous (it usually is for perform), signal may already be done
        semaphore.wait()

        return results
    }
}
