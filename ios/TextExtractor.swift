import Foundation
import PDFKit
import Vision
import UIKit

class TextExtractor {

    struct TextBlock {
        let text: String
        let x: Double      // normalized 0-1, top-left origin
        let y: Double
        let width: Double
        let height: Double
        let fontSize: Double
        let fontName: String
        let confidence: Double
    }

    static func extractText(
        pdfUrl: String,
        pageIndex: Int,
        mode: String,
        language: String,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
            rejecter("TEXT_EXTRACTION_FAILED", "Failed to load PDF", nil)
            return
        }

        guard pageIndex >= 0 && pageIndex < doc.pageCount, let page = doc.page(at: pageIndex) else {
            rejecter("TEXT_EXTRACTION_FAILED", "Invalid page index: \(pageIndex)", nil)
            return
        }

        let bounds = page.bounds(for: .mediaBox)
        let pageWidth = Double(bounds.width)
        let pageHeight = Double(bounds.height)

        switch mode {
        case "native":
            let blocks = extractNativeText(page: page, pageWidth: pageWidth, pageHeight: pageHeight)
            resolver(buildResult(blocks: blocks, pageWidth: pageWidth, pageHeight: pageHeight, mode: "native"))

        case "ocr":
            let blocks = extractWithOcr(page: page, pageWidth: pageWidth, pageHeight: pageHeight, language: language)
            resolver(buildResult(blocks: blocks, pageWidth: pageWidth, pageHeight: pageHeight, mode: "ocr"))

        default: // "auto"
            let nativeBlocks = extractNativeText(page: page, pageWidth: pageWidth, pageHeight: pageHeight)
            let hasText = nativeBlocks.contains { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

            if hasText && nativeBlocks.count > 0 {
                resolver(buildResult(blocks: nativeBlocks, pageWidth: pageWidth, pageHeight: pageHeight, mode: "native"))
            } else {
                let ocrBlocks = extractWithOcr(page: page, pageWidth: pageWidth, pageHeight: pageHeight, language: language)
                resolver(buildResult(blocks: ocrBlocks, pageWidth: pageWidth, pageHeight: pageHeight, mode: "ocr"))
            }
        }
    }

    // MARK: - Native text extraction

    private static func extractNativeText(page: PDFPage, pageWidth: Double, pageHeight: Double) -> [TextBlock] {
        guard let pageString = page.string, !pageString.isEmpty else {
            return []
        }

        // Get character-level bounds and group into words
        var characters: [(char: Character, bounds: CGRect)] = []

        for i in 0..<pageString.count {
            let selection = page.selection(for: NSRange(location: i, length: 1))
            guard let sel = selection else { continue }

            let selBounds = sel.bounds(for: page)
            guard selBounds.width > 0 && selBounds.height > 0 else { continue }

            let charIndex = pageString.index(pageString.startIndex, offsetBy: i)
            characters.append((char: pageString[charIndex], bounds: selBounds))
        }

        return groupCharactersIntoWords(characters: characters, pageWidth: pageWidth, pageHeight: pageHeight)
    }

    private static func groupCharactersIntoWords(
        characters: [(char: Character, bounds: CGRect)],
        pageWidth: Double,
        pageHeight: Double
    ) -> [TextBlock] {
        guard !characters.isEmpty else { return [] }

        var words: [TextBlock] = []
        var currentWord = String(characters[0].char)
        var currentBounds = characters[0].bounds

        for i in 1..<characters.count {
            let char = characters[i]
            let prev = characters[i - 1]

            // Start new word on whitespace, large horizontal gap, or line change
            let isWhitespace = char.char.isWhitespace || char.char.isNewline
            let isNewLine = abs(char.bounds.midY - prev.bounds.midY) > prev.bounds.height * 0.5
            let gap = char.bounds.minX - prev.bounds.maxX
            let isLargeGap = gap > prev.bounds.width * 0.4

            if isWhitespace || isNewLine || isLargeGap {
                // Save current word if non-empty
                let trimmed = currentWord.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty {
                    words.append(boundsToBlock(text: trimmed, bounds: currentBounds, pageWidth: pageWidth, pageHeight: pageHeight))
                }

                if !isWhitespace {
                    // Start new word with this character
                    currentWord = String(char.char)
                    currentBounds = char.bounds
                } else {
                    // Reset for next non-whitespace character
                    currentWord = ""
                    currentBounds = .zero
                }
            } else {
                currentWord.append(char.char)
                currentBounds = currentBounds.union(char.bounds)
            }
        }

        // Don't forget the last word
        let trimmed = currentWord.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty {
            words.append(boundsToBlock(text: trimmed, bounds: currentBounds, pageWidth: pageWidth, pageHeight: pageHeight))
        }

        return words
    }

    /// Convert PDFKit bounds (bottom-left origin, points) to normalized block (top-left origin, 0-1)
    private static func boundsToBlock(text: String, bounds: CGRect, pageWidth: Double, pageHeight: Double) -> TextBlock {
        let nx = Double(bounds.origin.x) / pageWidth
        // PDFKit: origin.y is bottom edge, we need top edge
        let ny = 1.0 - (Double(bounds.origin.y) + Double(bounds.height)) / pageHeight
        let nw = Double(bounds.width) / pageWidth
        let nh = Double(bounds.height) / pageHeight

        // Estimate font size from character height (~80% of bounding box height)
        let fontSize = Double(bounds.height) * 0.85

        return TextBlock(
            text: text,
            x: nx,
            y: ny,
            width: nw,
            height: nh,
            fontSize: fontSize,
            fontName: "Unknown",
            confidence: 1.0
        )
    }

    // MARK: - OCR fallback

    private static func extractWithOcr(page: PDFPage, pageWidth: Double, pageHeight: Double, language: String) -> [TextBlock] {
        guard let cgImage = renderPageToImage(page: page) else { return [] }

        var blocks: [TextBlock] = []

        let request = VNRecognizeTextRequest { request, _ in
            if let observations = request.results as? [VNRecognizedTextObservation] {
                for observation in observations {
                    guard let candidate = observation.topCandidates(1).first else { continue }

                    let box = observation.boundingBox
                    // Vision: bottom-left origin, normalized 0-1
                    let nx = box.origin.x
                    let ny = 1.0 - box.origin.y - box.height
                    let nw = box.width
                    let nh = box.height

                    let fontSize = Double(nh) * pageHeight * 0.85

                    blocks.append(TextBlock(
                        text: candidate.string,
                        x: nx,
                        y: ny,
                        width: nw,
                        height: nh,
                        fontSize: fontSize,
                        fontName: "Unknown",
                        confidence: Double(candidate.confidence)
                    ))
                }
            }
        }

        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        if language != "auto" {
            request.recognitionLanguages = [language]
        }

        // VNImageRequestHandler.perform is synchronous — completion is called before perform returns
        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        try? handler.perform([request])

        return blocks
    }

    // MARK: - Helpers

    private static func buildResult(blocks: [TextBlock], pageWidth: Double, pageHeight: Double, mode: String) -> [String: Any] {
        let blocksArray: [[String: Any]] = blocks.map { block in
            [
                "text": block.text,
                "boundingBox": [
                    "x": block.x,
                    "y": block.y,
                    "width": block.width,
                    "height": block.height,
                ] as [String: Any],
                "fontSize": block.fontSize,
                "fontName": block.fontName,
                "confidence": block.confidence,
            ] as [String: Any]
        }

        return [
            "textBlocks": blocksArray,
            "pageWidth": pageWidth,
            "pageHeight": pageHeight,
            "mode": mode,
        ] as [String: Any]
    }
}
