import Compression
import Foundation
import PDFKit
import UIKit
import Vision

// MARK: - DOCX Models

struct DocxDocument {
    var paragraphs: [DocxElement] = []
}

enum DocxElement {
    case paragraph(DocxParagraph)
    case table(DocxTable)
}

struct DocxParagraph {
    var runs: [DocxRun] = []
    var alignment: String = "left"
    var headingLevel: Int = 0 // 0 = normal, 1-6 = heading
    var isBullet: Bool = false
    var isNumbered: Bool = false
    var listLevel: Int = 0
    var numberingIndex: Int = 0
    var spacingBefore: CGFloat = 0
    var spacingAfter: CGFloat = 4
}

struct DocxRun {
    var text: String = ""
    var bold: Bool = false
    var italic: Bool = false
    var underline: Bool = false
    var fontSize: CGFloat = 12
    var fontFamily: String = "Helvetica"
    var color: String? = nil
    var imageRelId: String? = nil
    var imageWidth: CGFloat = 0
    var imageHeight: CGFloat = 0
}

struct DocxTable {
    var rows: [[DocxTableCell]] = []
}

struct DocxTableCell {
    var paragraphs: [DocxParagraph] = []
    var gridSpan: Int = 1
}

// MARK: - ZIP Reader (minimal, using Compression framework)

struct ZipReader {
    struct Entry {
        let name: String
        let compressedSize: UInt32
        let uncompressedSize: UInt32
        let offset: UInt32
        let compressionMethod: UInt16
    }

    private let data: Data
    private(set) var entries: [Entry] = []

    init(data: Data) throws {
        self.data = data
        self.entries = try Self.parseCentralDirectory(data: data)
    }

    func extractData(entryName: String) -> Data? {
        guard let entry = entries.first(where: { $0.name == entryName }) else { return nil }
        return extractEntry(entry)
    }

    private func extractEntry(_ entry: Entry) -> Data? {
        let offset = Int(entry.offset)
        guard offset + 30 <= data.count else { return nil }

        // Read local file header to get variable-length fields
        let nameLen = Int(data.uint16(at: offset + 26))
        let extraLen = Int(data.uint16(at: offset + 28))
        let dataStart = offset + 30 + nameLen + extraLen

        guard dataStart + Int(entry.compressedSize) <= data.count else { return nil }

        let compressedData = data.subdata(in: dataStart..<(dataStart + Int(entry.compressedSize)))

        if entry.compressionMethod == 0 {
            // Stored (no compression)
            return compressedData
        } else if entry.compressionMethod == 8 {
            // Deflate
            return decompressDeflate(compressedData, uncompressedSize: Int(entry.uncompressedSize))
        }
        return nil
    }

    private func decompressDeflate(_ data: Data, uncompressedSize: Int) -> Data? {
        // Use raw DEFLATE (no zlib header) via Compression framework
        guard uncompressedSize > 0 else { return Data() }
        var decompressed = Data(count: uncompressedSize)
        let result = decompressed.withUnsafeMutableBytes { destPtr in
            data.withUnsafeBytes { srcPtr in
                compression_decode_buffer(
                    destPtr.bindMemory(to: UInt8.self).baseAddress!,
                    uncompressedSize,
                    srcPtr.bindMemory(to: UInt8.self).baseAddress!,
                    data.count,
                    nil,
                    COMPRESSION_ZLIB
                )
            }
        }
        guard result > 0 else { return nil }
        return decompressed.prefix(result)
    }

    private static func parseCentralDirectory(data: Data) throws -> [Entry] {
        // Find End of Central Directory record (search backward for signature 0x06054b50)
        var eocdOffset = -1
        let minEocdSize = 22
        guard data.count >= minEocdSize else { throw DocxError.invalidZip }

        for i in stride(from: data.count - minEocdSize, through: max(0, data.count - 65557), by: -1) {
            if data.uint32(at: i) == 0x06054b50 {
                eocdOffset = i
                break
            }
        }
        guard eocdOffset >= 0 else { throw DocxError.invalidZip }

        let cdEntries = Int(data.uint16(at: eocdOffset + 10))
        let cdOffset = Int(data.uint32(at: eocdOffset + 16))

        var entries: [Entry] = []
        var pos = cdOffset

        for _ in 0..<cdEntries {
            guard pos + 46 <= data.count else { break }
            guard data.uint32(at: pos) == 0x02014b50 else { break }

            let compressionMethod = data.uint16(at: pos + 10)
            let compressedSize = data.uint32(at: pos + 20)
            let uncompressedSize = data.uint32(at: pos + 24)
            let nameLen = Int(data.uint16(at: pos + 28))
            let extraLen = Int(data.uint16(at: pos + 30))
            let commentLen = Int(data.uint16(at: pos + 32))
            let localHeaderOffset = data.uint32(at: pos + 42)

            let nameData = data.subdata(in: (pos + 46)..<(pos + 46 + nameLen))
            let name = String(data: nameData, encoding: .utf8) ?? ""

            entries.append(Entry(
                name: name,
                compressedSize: compressedSize,
                uncompressedSize: uncompressedSize,
                offset: localHeaderOffset,
                compressionMethod: compressionMethod
            ))

            pos += 46 + nameLen + extraLen + commentLen
        }

        return entries
    }
}

// MARK: - ZIP Writer (minimal)

class ZipWriter {
    struct FileEntry {
        let name: String
        let data: Data
        let crc32: UInt32
        let offset: UInt32
    }

    private var buffer = Data()
    private var entries: [FileEntry] = []

    func addEntry(name: String, data: Data) {
        let crc = data.crc32()
        let offset = UInt32(buffer.count)

        // Local file header
        buffer.appendUInt32(0x04034b50)    // signature
        buffer.appendUInt16(20)             // version needed
        buffer.appendUInt16(0)              // flags
        buffer.appendUInt16(0)              // compression (stored)
        buffer.appendUInt16(0)              // mod time
        buffer.appendUInt16(0)              // mod date
        buffer.appendUInt32(crc)
        buffer.appendUInt32(UInt32(data.count))   // compressed size
        buffer.appendUInt32(UInt32(data.count))   // uncompressed size
        let nameData = name.data(using: .utf8) ?? Data()
        buffer.appendUInt16(UInt16(nameData.count))
        buffer.appendUInt16(0)              // extra field length
        buffer.append(nameData)
        buffer.append(data)

        entries.append(FileEntry(name: name, data: data, crc32: crc, offset: offset))
    }

    func finalize() -> Data {
        let cdOffset = UInt32(buffer.count)

        // Central directory
        for entry in entries {
            let nameData = entry.name.data(using: .utf8) ?? Data()
            buffer.appendUInt32(0x02014b50)   // signature
            buffer.appendUInt16(20)            // version made by
            buffer.appendUInt16(20)            // version needed
            buffer.appendUInt16(0)             // flags
            buffer.appendUInt16(0)             // compression
            buffer.appendUInt16(0)             // mod time
            buffer.appendUInt16(0)             // mod date
            buffer.appendUInt32(entry.crc32)
            buffer.appendUInt32(UInt32(entry.data.count))
            buffer.appendUInt32(UInt32(entry.data.count))
            buffer.appendUInt16(UInt16(nameData.count))
            buffer.appendUInt16(0)             // extra field length
            buffer.appendUInt16(0)             // comment length
            buffer.appendUInt16(0)             // disk number
            buffer.appendUInt16(0)             // internal attributes
            buffer.appendUInt32(0)             // external attributes
            buffer.appendUInt32(entry.offset)
            buffer.append(nameData)
        }

        let cdSize = UInt32(buffer.count) - cdOffset

        // End of central directory
        buffer.appendUInt32(0x06054b50)
        buffer.appendUInt16(0)              // disk number
        buffer.appendUInt16(0)              // cd start disk
        buffer.appendUInt16(UInt16(entries.count))
        buffer.appendUInt16(UInt16(entries.count))
        buffer.appendUInt32(cdSize)
        buffer.appendUInt32(cdOffset)
        buffer.appendUInt16(0)              // comment length

        return buffer
    }
}

// MARK: - Data Helpers

private extension Data {
    func uint16(at offset: Int) -> UInt16 {
        guard offset + 2 <= count else { return 0 }
        var value: UInt16 = 0
        withUnsafeBytes { ptr in
            memcpy(&value, ptr.baseAddress!.advanced(by: offset), 2)
        }
        return value.littleEndian
    }

    func uint32(at offset: Int) -> UInt32 {
        guard offset + 4 <= count else { return 0 }
        var value: UInt32 = 0
        withUnsafeBytes { ptr in
            memcpy(&value, ptr.baseAddress!.advanced(by: offset), 4)
        }
        return value.littleEndian
    }

    mutating func appendUInt16(_ value: UInt16) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    mutating func appendUInt32(_ value: UInt32) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    func crc32() -> UInt32 {
        var crc: UInt32 = 0xFFFFFFFF
        for byte in self {
            var lookup = (crc ^ UInt32(byte)) & 0xFF
            for _ in 0..<8 {
                if lookup & 1 == 1 {
                    lookup = (lookup >> 1) ^ 0xEDB88320
                } else {
                    lookup >>= 1
                }
            }
            crc = (crc >> 8) ^ lookup
        }
        return crc ^ 0xFFFFFFFF
    }
}

// MARK: - Errors

enum DocxError: Error, LocalizedError {
    case invalidZip
    case missingDocumentXml
    case parseError(String)
    case renderError(String)

    var errorDescription: String? {
        switch self {
        case .invalidZip: return "Invalid or corrupted DOCX file"
        case .missingDocumentXml: return "Missing word/document.xml in DOCX"
        case .parseError(let msg): return "DOCX parse error: \(msg)"
        case .renderError(let msg): return "Render error: \(msg)"
        }
    }
}

// MARK: - DOCX XML Parser

class DocxParser: NSObject, XMLParserDelegate {
    private var document = DocxDocument()
    private var warnings: [String] = []
    private var relationships: [String: String] = [:] // rId -> target

    // Parser state
    private var elementStack: [String] = []
    private var currentParagraph: DocxParagraph?
    private var currentRun: DocxRun?
    private var currentTable: DocxTable?
    private var currentRow: [DocxTableCell]?
    private var currentCell: DocxTableCell?
    private var isInTable = false
    private var textBuffer = ""

    func parse(zip: ZipReader) throws -> (DocxDocument, [String]) {
        // Parse relationships
        if let relsData = zip.extractData(entryName: "word/_rels/document.xml.rels") {
            parseRelationships(data: relsData)
        }

        // Parse main document
        guard let docData = zip.extractData(entryName: "word/document.xml") else {
            throw DocxError.missingDocumentXml
        }

        let parser = XMLParser(data: docData)
        parser.delegate = self
        parser.shouldProcessNamespaces = false
        parser.shouldReportNamespacePrefixes = false

        guard parser.parse() else {
            throw DocxError.parseError(parser.parserError?.localizedDescription ?? "Unknown XML error")
        }

        return (document, warnings)
    }

    private func parseRelationships(data: Data) {
        let parser = RelsParser()
        let xmlParser = XMLParser(data: data)
        xmlParser.delegate = parser
        xmlParser.parse()
        relationships = parser.relationships
    }

    func resolveRelationship(_ rId: String) -> String? {
        return relationships[rId]
    }

    // MARK: - XMLParserDelegate

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        elementStack.append(elementName)

        switch elementName {
        case "w:p":
            currentParagraph = DocxParagraph()

        case "w:r":
            currentRun = DocxRun()

        case "w:t":
            textBuffer = ""

        case "w:b":
            currentRun?.bold = (attributes["w:val"] ?? "true") != "false"

        case "w:i":
            currentRun?.italic = (attributes["w:val"] ?? "true") != "false"

        case "w:u":
            let val = attributes["w:val"] ?? "single"
            currentRun?.underline = val != "none"

        case "w:sz":
            if let val = attributes["w:val"], let halfPts = CGFloat(exactly: Int(val) ?? 24) {
                currentRun?.fontSize = halfPts / 2.0
            }

        case "w:rFonts":
            if let font = attributes["w:ascii"] ?? attributes["w:hAnsi"] ?? attributes["w:cs"] {
                currentRun?.fontFamily = font
            }

        case "w:color":
            if let val = attributes["w:val"], val != "auto" {
                currentRun?.color = "#\(val)"
            }

        case "w:jc":
            if let val = attributes["w:val"] {
                currentParagraph?.alignment = val
            }

        case "w:pStyle":
            if let val = attributes["w:val"] {
                if val.hasPrefix("Heading") || val.hasPrefix("heading") {
                    let num = val.filter { $0.isNumber }
                    currentParagraph?.headingLevel = Int(num) ?? 1
                }
            }

        case "w:numPr":
            break // handled by child elements

        case "w:ilvl":
            if let val = attributes["w:val"], let level = Int(val) {
                currentParagraph?.listLevel = level
            }

        case "w:numId":
            if let val = attributes["w:val"], let numId = Int(val) {
                if numId > 0 {
                    currentParagraph?.numberingIndex = numId
                    // Heuristic: even numIds are often bullets, odd are numbered
                    // This is a simplification; real numbering requires word/numbering.xml
                    currentParagraph?.isBullet = true
                }
            }

        case "w:tbl":
            currentTable = DocxTable()
            isInTable = true

        case "w:tr":
            currentRow = []

        case "w:tc":
            currentCell = DocxTableCell()

        case "w:gridSpan":
            if let val = attributes["w:val"], let span = Int(val) {
                currentCell?.gridSpan = span
            }

        case "w:drawing", "wp:inline", "wp:anchor":
            break

        case "wp:extent":
            // EMU (English Metric Units): 1 inch = 914400 EMUs
            if let cxStr = attributes["cx"], let cx = Int(cxStr),
               let cyStr = attributes["cy"], let cy = Int(cyStr) {
                currentRun?.imageWidth = CGFloat(cx) / 914400.0 * 72.0  // to points
                currentRun?.imageHeight = CGFloat(cy) / 914400.0 * 72.0
            }

        case "a:blip":
            if let rEmbed = attributes["r:embed"] {
                currentRun?.imageRelId = rEmbed
            }

        case "w:sectPr", "w:headerReference", "w:footerReference":
            break // skip silently

        case "w:footnoteReference":
            if !warnings.contains("Footnotes are not supported") {
                warnings.append("Footnotes are not supported")
            }

        case "mc:AlternateContent":
            if !warnings.contains("SmartArt/shapes are not supported") {
                warnings.append("SmartArt/shapes are not supported")
            }

        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if elementStack.last == "w:t" {
            textBuffer += string
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
        switch elementName {
        case "w:t":
            currentRun?.text += textBuffer
            textBuffer = ""

        case "w:r":
            if var run = currentRun {
                if run.text.isEmpty && run.imageRelId == nil {
                    // Empty run, skip
                } else {
                    // Inherit heading font sizes
                    if let para = currentParagraph, para.headingLevel > 0 && run.fontSize == 12 {
                        run.fontSize = headingFontSize(level: para.headingLevel)
                        run.bold = true
                    }
                    currentParagraph?.runs.append(run)
                }
            }
            currentRun = nil

        case "w:p":
            if let para = currentParagraph {
                if isInTable {
                    currentCell?.paragraphs.append(para)
                } else {
                    document.paragraphs.append(.paragraph(para))
                }
            }
            currentParagraph = nil

        case "w:tc":
            if let cell = currentCell {
                currentRow?.append(cell)
            }
            currentCell = nil

        case "w:tr":
            if let row = currentRow {
                currentTable?.rows.append(row)
            }
            currentRow = nil

        case "w:tbl":
            if let table = currentTable {
                document.paragraphs.append(.table(table))
            }
            currentTable = nil
            isInTable = false

        default:
            break
        }

        if elementStack.last == elementName {
            elementStack.removeLast()
        }
    }

    private func headingFontSize(level: Int) -> CGFloat {
        switch level {
        case 1: return 24
        case 2: return 20
        case 3: return 16
        case 4: return 14
        case 5: return 12
        case 6: return 11
        default: return 12
        }
    }
}

// MARK: - Relationships Parser

private class RelsParser: NSObject, XMLParserDelegate {
    var relationships: [String: String] = [:]

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
        if elementName == "Relationship" {
            if let id = attributes["Id"], let target = attributes["Target"] {
                relationships[id] = target
            }
        }
    }
}

// MARK: - DocxConverter

class DocxConverter {

    // MARK: - DOCX -> PDF

    static func convertDocxToPdf(
        inputPath: String,
        preserveImages: Bool,
        pageSize: String,
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        do {
            NSLog("[DocxConverter] convertDocxToPdf called with path: %@", inputPath)
            guard let url = resolveUrl(inputPath) else {
                rejecter("CONVERSION_FAILED", "Invalid input path", nil)
                return
            }
            NSLog("[DocxConverter] Loading file data...")
            let fileData = try Data(contentsOf: url)
            NSLog("[DocxConverter] File loaded: %d bytes", fileData.count)
            let zip = try ZipReader(data: fileData)
            NSLog("[DocxConverter] ZIP parsed successfully")

            let parser = DocxParser()
            NSLog("[DocxConverter] Parsing DOCX XML...")
            let (document, warnings) = try parser.parse(zip: zip)
            NSLog("[DocxConverter] Parsed: %d elements, %d warnings", document.paragraphs.count, warnings.count)

            // Render to PDF
            let pageDimensions = pageSizeDimensions(pageSize)
            let pageRect = CGRect(x: 0, y: 0, width: pageDimensions.width, height: pageDimensions.height)
            let margins = (top: CGFloat(56), right: CGFloat(56), bottom: CGFloat(56), left: CGFloat(56))
            let contentLeft = margins.left
            let contentWidth = pageDimensions.width - margins.left - margins.right
            let contentTop = margins.top
            let contentBottom = pageDimensions.height - margins.bottom

            let pdfData = NSMutableData()
            UIGraphicsBeginPDFContextToData(pdfData, pageRect, nil)

            var cursorY = contentTop
            var pageCount = 0

            // First page
            UIGraphicsBeginPDFPageWithInfo(pageRect, nil)
            pageCount += 1

            NSLog("[DocxConverter] Rendering %d elements to PDF...", document.paragraphs.count)

            for (idx, element) in document.paragraphs.enumerated() {
                NSLog("[DocxConverter] Rendering element %d/%d", idx + 1, document.paragraphs.count)
                switch element {
                case .paragraph(let para):
                    renderParagraph(
                        para, zip: zip, parser: parser, preserveImages: preserveImages,
                        contentLeft: contentLeft, contentWidth: contentWidth,
                        contentTop: contentTop, contentBottom: contentBottom, pageRect: pageRect,
                        cursorY: &cursorY, pageCount: &pageCount
                    )

                case .table(let table):
                    renderTable(
                        table, contentLeft: contentLeft, contentWidth: contentWidth,
                        contentTop: contentTop, contentBottom: contentBottom, pageRect: pageRect,
                        cursorY: &cursorY, pageCount: &pageCount
                    )
                }
            }

            UIGraphicsEndPDFContext()
            NSLog("[DocxConverter] PDF rendering complete, %d pages", pageCount)

            // Save
            try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            try pdfData.write(to: outputUrl, options: .atomic)

            let fileSize = pdfData.length

            resolver([
                "pdfUrl": outputUrl.absoluteString,
                "pageCount": pageCount,
                "fileSize": fileSize,
                "warnings": warnings,
            ] as [String: Any])

        } catch {
            rejecter("CONVERSION_FAILED", "DOCX to PDF conversion failed: \(error.localizedDescription)", error as NSError)
        }
    }

    // MARK: - PDF -> DOCX

    static func convertPdfToDocx(
        inputPath: String,
        mode: String,
        language: String,
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        do {
            guard let url = resolveUrl(inputPath), let pdfDoc = PDFDocument(url: url) else {
                rejecter("CONVERSION_FAILED", "Failed to load PDF", nil)
                return
            }

            let pageCount = pdfDoc.pageCount
            guard pageCount > 0 else {
                rejecter("CONVERSION_FAILED", "PDF has no pages", nil)
                return
            }

            var allPages: [PageContent] = []
            var imageIndex = 0
            var actualMode = mode == "ocrFallback" ? "auto" : mode

            for i in 0..<pageCount {
                guard let page = pdfDoc.page(at: i) else { continue }
                let bounds = page.bounds(for: .mediaBox)
                let pageWidth = Double(bounds.width)
                let pageHeight = Double(bounds.height)

                // Extract text blocks
                var blocks: [TextExtractor.TextBlock]
                if mode == "text" || mode == "textAndImages" {
                    blocks = extractNativeTextBlocks(page: page, pageWidth: pageWidth, pageHeight: pageHeight)
                    if blocks.isEmpty && mode != "text" {
                        blocks = extractOcrBlocks(page: page, pageWidth: pageWidth, pageHeight: pageHeight, language: language)
                        actualMode = "ocr"
                    }
                } else {
                    // ocrFallback: try native first
                    blocks = extractNativeTextBlocks(page: page, pageWidth: pageWidth, pageHeight: pageHeight)
                    if blocks.isEmpty {
                        blocks = extractOcrBlocks(page: page, pageWidth: pageWidth, pageHeight: pageHeight, language: language)
                        actualMode = "ocr"
                    }
                }

                // Extract page image if needed
                var pageImage: Data? = nil
                if mode == "textAndImages" {
                    pageImage = renderPageAsImage(page: page, index: &imageIndex)
                }

                allPages.append(PageContent(
                    blocks: blocks,
                    pageWidth: pageWidth,
                    pageHeight: pageHeight,
                    image: pageImage,
                    imageIndex: pageImage != nil ? imageIndex - 1 : nil
                ))
            }

            // Build DOCX
            let writer = ZipWriter()
            var images: [(name: String, data: Data)] = []

            // Collect images
            for page in allPages {
                if let img = page.image, let idx = page.imageIndex {
                    images.append(("word/media/image\(idx).png", img))
                }
            }

            // [Content_Types].xml
            writer.addEntry(name: "[Content_Types].xml", data: buildContentTypes(hasImages: !images.isEmpty).data(using: .utf8)!)

            // _rels/.rels
            writer.addEntry(name: "_rels/.rels", data: buildRootRels().data(using: .utf8)!)

            // word/_rels/document.xml.rels
            writer.addEntry(name: "word/_rels/document.xml.rels", data: buildDocumentRels(imageCount: images.count).data(using: .utf8)!)

            // word/document.xml
            let docXml = buildDocumentXml(pages: allPages)
            writer.addEntry(name: "word/document.xml", data: docXml.data(using: .utf8)!)

            // Images
            for img in images {
                writer.addEntry(name: img.name, data: img.data)
            }

            let docxData = writer.finalize()

            // Save
            try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).docx")
            try docxData.write(to: outputUrl, options: .atomic)

            resolver([
                "docxUrl": outputUrl.absoluteString,
                "pageCount": pageCount,
                "fileSize": docxData.count,
                "mode": actualMode,
            ] as [String: Any])

        } catch {
            rejecter("CONVERSION_FAILED", "PDF to DOCX conversion failed: \(error.localizedDescription)", error as NSError)
        }
    }

    // MARK: - DOCX -> PDF: Paragraph Rendering

    private static func newPage(pageRect: CGRect, contentTop: CGFloat, cursorY: inout CGFloat, pageCount: inout Int) {
        UIGraphicsBeginPDFPageWithInfo(pageRect, nil)
        pageCount += 1
        cursorY = contentTop
    }

    private static func renderParagraph(
        _ para: DocxParagraph,
        zip: ZipReader,
        parser: DocxParser,
        preserveImages: Bool,
        contentLeft: CGFloat,
        contentWidth: CGFloat,
        contentTop: CGFloat,
        contentBottom: CGFloat,
        pageRect: CGRect,
        cursorY: inout CGFloat,
        pageCount: inout Int
    ) {
        // Spacing before
        cursorY += para.spacingBefore

        // List prefix
        var listPrefix = ""
        if para.isBullet || para.isNumbered {
            if para.isNumbered {
                listPrefix = "\(para.numberingIndex). "
            } else {
                listPrefix = "\u{2022} "
            }
        }

        // Build attributed string from runs
        let attrString = NSMutableAttributedString()

        if !listPrefix.isEmpty {
            let listAttrs = runAttributes(
                bold: false, italic: false, underline: false,
                fontSize: para.runs.first?.fontSize ?? 12,
                fontFamily: para.runs.first?.fontFamily ?? "Helvetica",
                color: nil, alignment: para.alignment
            )
            attrString.append(NSAttributedString(string: listPrefix, attributes: listAttrs))
        }

        for run in para.runs {
            if let relId = run.imageRelId, preserveImages {
                // Image run — render separately
                if let target = parser.resolveRelationship(relId) {
                    let imagePath = target.hasPrefix("/") ? String(target.dropFirst()) : "word/\(target)"
                    if let imageData = zip.extractData(entryName: imagePath),
                       let uiImage = UIImage(data: imageData) {
                        let imgW = min(run.imageWidth > 0 ? run.imageWidth : contentWidth * 0.8, contentWidth)
                        let imgH = run.imageHeight > 0 ? run.imageHeight : imgW * (uiImage.size.height / uiImage.size.width)

                        // Flush text before image
                        if attrString.length > 0 {
                            let textHeight = measureAttrString(attrString, width: contentWidth)
                            if cursorY + textHeight > contentBottom {
                                newPage(pageRect: pageRect, contentTop: contentTop, cursorY: &cursorY, pageCount: &pageCount)
                            }
                            drawAttrString(attrString, x: contentLeft + CGFloat(para.listLevel) * 18, y: cursorY, width: contentWidth - CGFloat(para.listLevel) * 18)
                            cursorY += textHeight
                            attrString.setAttributedString(NSAttributedString())
                        }

                        // Draw image
                        if cursorY + imgH > contentBottom {
                            newPage(pageRect: pageRect, contentTop: contentTop, cursorY: &cursorY, pageCount: &pageCount)
                        }

                        var imgX = contentLeft
                        if para.alignment == "center" {
                            imgX = contentLeft + (contentWidth - imgW) / 2
                        } else if para.alignment == "right" {
                            imgX = contentLeft + contentWidth - imgW
                        }

                        uiImage.draw(in: CGRect(x: imgX, y: cursorY, width: imgW, height: imgH))
                        cursorY += imgH + 4
                    }
                }
                continue
            }

            if run.text.isEmpty { continue }

            let attrs = runAttributes(
                bold: run.bold, italic: run.italic, underline: run.underline,
                fontSize: run.fontSize, fontFamily: run.fontFamily,
                color: run.color, alignment: para.alignment
            )
            attrString.append(NSAttributedString(string: run.text, attributes: attrs))
        }

        if attrString.length > 0 {
            let indent = CGFloat(para.listLevel) * 18.0
            let drawWidth = contentWidth - indent
            let textHeight = measureAttrString(attrString, width: drawWidth)

            if cursorY + textHeight > contentBottom {
                newPage(pageRect: pageRect, contentTop: contentTop, cursorY: &cursorY, pageCount: &pageCount)
            }

            drawAttrString(attrString, x: contentLeft + indent, y: cursorY, width: drawWidth)
            cursorY += textHeight
        }

        // Spacing after
        cursorY += para.spacingAfter
    }

    // MARK: - DOCX -> PDF: Table Rendering

    private static func renderTable(
        _ table: DocxTable,
        contentLeft: CGFloat,
        contentWidth: CGFloat,
        contentTop: CGFloat,
        contentBottom: CGFloat,
        pageRect: CGRect,
        cursorY: inout CGFloat,
        pageCount: inout Int
    ) {
        guard !table.rows.isEmpty else { return }

        let maxCols = table.rows.map { $0.reduce(0) { $0 + $1.gridSpan } }.max() ?? 1
        let colWidth = contentWidth / CGFloat(maxCols)
        let cellPadding: CGFloat = 4

        for row in table.rows {
            // Measure row height
            var rowHeight: CGFloat = 20
            var colOffset = 0
            for cell in row {
                let cellW = colWidth * CGFloat(cell.gridSpan) - cellPadding * 2
                for para in cell.paragraphs {
                    let attrStr = paragraphToAttributedString(para)
                    let h = measureAttrString(attrStr, width: cellW)
                    rowHeight = max(rowHeight, h + cellPadding * 2)
                }
                colOffset += cell.gridSpan
            }

            if cursorY + rowHeight > contentBottom {
                newPage(pageRect: pageRect, contentTop: contentTop, cursorY: &cursorY, pageCount: &pageCount)
            }

            // Draw row
            colOffset = 0
            guard let ctx = UIGraphicsGetCurrentContext() else { continue }

            for cell in row {
                let cellX = contentLeft + CGFloat(colOffset) * colWidth
                let cellW = colWidth * CGFloat(cell.gridSpan)

                // Cell border
                ctx.setStrokeColor(UIColor.gray.cgColor)
                ctx.setLineWidth(0.5)
                ctx.stroke(CGRect(x: cellX, y: cursorY, width: cellW, height: rowHeight))

                // Cell text
                var cellY = cursorY + cellPadding
                for para in cell.paragraphs {
                    let attrStr = paragraphToAttributedString(para)
                    let textW = cellW - cellPadding * 2
                    let textH = measureAttrString(attrStr, width: textW)
                    drawAttrString(attrStr, x: cellX + cellPadding, y: cellY, width: textW)
                    cellY += textH
                }

                colOffset += cell.gridSpan
            }

            cursorY += rowHeight
        }

        cursorY += 8 // spacing after table
    }

    // MARK: - DOCX -> PDF: Helpers

    private static func runAttributes(
        bold: Bool, italic: Bool, underline: Bool,
        fontSize: CGFloat, fontFamily: String,
        color: String?, alignment: String
    ) -> [NSAttributedString.Key: Any] {
        var traits: UIFontDescriptor.SymbolicTraits = []
        if bold { traits.insert(.traitBold) }
        if italic { traits.insert(.traitItalic) }

        var font: UIFont
        if let descriptor = UIFontDescriptor(name: fontFamily, size: fontSize).withSymbolicTraits(traits) {
            font = UIFont(descriptor: descriptor, size: fontSize)
        } else {
            font = UIFont.systemFont(ofSize: fontSize, weight: bold ? .bold : .regular)
            if italic {
                font = font.withTraits(.traitItalic)
            }
        }

        let paraStyle = NSMutableParagraphStyle()
        switch alignment {
        case "center": paraStyle.alignment = .center
        case "right": paraStyle.alignment = .right
        case "both", "justify": paraStyle.alignment = .justified
        default: paraStyle.alignment = .left
        }

        var attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .paragraphStyle: paraStyle,
        ]

        if let colorStr = color, let uiColor = UIColor(hex: colorStr) {
            attrs[.foregroundColor] = uiColor
        } else {
            attrs[.foregroundColor] = UIColor.black
        }

        if underline {
            attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue
        }

        return attrs
    }

    private static func paragraphToAttributedString(_ para: DocxParagraph) -> NSAttributedString {
        let result = NSMutableAttributedString()
        for run in para.runs {
            if run.text.isEmpty { continue }
            let attrs = runAttributes(
                bold: run.bold, italic: run.italic, underline: run.underline,
                fontSize: run.fontSize, fontFamily: run.fontFamily,
                color: run.color, alignment: para.alignment
            )
            result.append(NSAttributedString(string: run.text, attributes: attrs))
        }
        return result
    }

    private static func measureAttrString(_ attrStr: NSAttributedString, width: CGFloat) -> CGFloat {
        guard attrStr.length > 0 else { return 0 }
        let rect = attrStr.boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            context: nil
        )
        return ceil(rect.height)
    }

    private static func drawAttrString(_ attrStr: NSAttributedString, x: CGFloat, y: CGFloat, width: CGFloat) {
        guard attrStr.length > 0 else { return }
        let height = measureAttrString(attrStr, width: width)
        attrStr.draw(with: CGRect(x: x, y: y, width: width, height: height),
                     options: [.usesLineFragmentOrigin, .usesFontLeading],
                     context: nil)
    }

    private static func pageSizeDimensions(_ size: String) -> (width: CGFloat, height: CGFloat) {
        switch size.uppercased() {
        case "LETTER": return (612, 792)
        case "LEGAL": return (612, 1008)
        default: return (595, 842) // A4
        }
    }

    // MARK: - PDF -> DOCX: Text Extraction

    struct PageContent {
        let blocks: [TextExtractor.TextBlock]
        let pageWidth: Double
        let pageHeight: Double
        let image: Data?
        let imageIndex: Int?
    }

    private static func extractNativeTextBlocks(page: PDFPage, pageWidth: Double, pageHeight: Double) -> [TextExtractor.TextBlock] {
        guard let pageString = page.string, !pageString.isEmpty else { return [] }

        var blocks: [TextExtractor.TextBlock] = []
        // Group into lines using selection ranges
        let nsString = pageString as NSString
        let lines = nsString.components(separatedBy: .newlines)
        var charOffset = 0

        for line in lines {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty {
                charOffset += line.count + 1
                continue
            }

            // Get bounds for this line
            let range = NSRange(location: charOffset, length: line.count)
            if let selection = page.selection(for: range) {
                let bounds = selection.bounds(for: page)
                if bounds.width > 0 && bounds.height > 0 {
                    let nx = Double(bounds.origin.x) / pageWidth
                    let ny = 1.0 - (Double(bounds.origin.y) + Double(bounds.height)) / pageHeight
                    let nw = Double(bounds.width) / pageWidth
                    let nh = Double(bounds.height) / pageHeight
                    let fontSize = Double(bounds.height) * 0.85

                    blocks.append(TextExtractor.TextBlock(
                        text: trimmed,
                        x: nx, y: ny, width: nw, height: nh,
                        fontSize: fontSize,
                        fontName: "Unknown",
                        confidence: 1.0
                    ))
                }
            }

            charOffset += line.count + 1
        }

        return blocks
    }

    private static func extractOcrBlocks(page: PDFPage, pageWidth: Double, pageHeight: Double, language: String) -> [TextExtractor.TextBlock] {
        guard let cgImage = renderPageToCGImage(page: page) else { return [] }

        var blocks: [TextExtractor.TextBlock] = []
        let semaphore = DispatchSemaphore(value: 0)

        let request = VNRecognizeTextRequest { request, _ in
            if let observations = request.results as? [VNRecognizedTextObservation] {
                for observation in observations {
                    guard let candidate = observation.topCandidates(1).first else { continue }
                    let box = observation.boundingBox
                    let fontSize = Double(box.height) * pageHeight * 0.85

                    blocks.append(TextExtractor.TextBlock(
                        text: candidate.string,
                        x: box.origin.x,
                        y: 1.0 - box.origin.y - box.height,
                        width: box.width,
                        height: box.height,
                        fontSize: fontSize,
                        fontName: "Unknown",
                        confidence: Double(candidate.confidence)
                    ))
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
        semaphore.wait()

        return blocks
    }

    private static func renderPageToCGImage(page: PDFPage, scale: CGFloat = 2.0) -> CGImage? {
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

    private static func renderPageAsImage(page: PDFPage, index: inout Int) -> Data? {
        guard let cgImage = renderPageToCGImage(page: page, scale: 1.5) else { return nil }
        let uiImage = UIImage(cgImage: cgImage)
        let data = uiImage.pngData()
        if data != nil { index += 1 }
        return data
    }

    // MARK: - PDF -> DOCX: XML Builders

    private static func buildContentTypes(hasImages: Bool) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        """
        if hasImages {
            xml += """
              <Default Extension="png" ContentType="image/png"/>
              <Default Extension="jpg" ContentType="image/jpeg"/>
            """
        }
        xml += "\n</Types>"
        return xml
    }

    private static func buildRootRels() -> String {
        return """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
        """
    }

    private static func buildDocumentRels(imageCount: Int) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        """
        for i in 0..<imageCount {
            xml += """
              <Relationship Id="rId\(i + 1)" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image\(i).png"/>
            """
        }
        xml += "\n</Relationships>"
        return xml
    }

    private static func buildDocumentXml(pages: [PageContent]) -> String {
        var xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                    xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                    xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                    xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
          <w:body>
        """

        for (pageIdx, page) in pages.enumerated() {
            // Group text blocks into paragraphs by vertical proximity
            let paragraphs = groupBlocksIntoParagraphs(blocks: page.blocks, pageHeight: page.pageHeight)

            for para in paragraphs {
                xml += "    <w:p>\n"

                // Paragraph properties
                let avgFontSize = para.map { $0.fontSize }.reduce(0, +) / max(Double(para.count), 1)
                if avgFontSize > 18 {
                    xml += "      <w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>\n"
                } else if avgFontSize > 15 {
                    xml += "      <w:pPr><w:pStyle w:val=\"Heading2\"/></w:pPr>\n"
                }

                for block in para {
                    let escaped = escapeXml(block.text)
                    let szVal = Int(block.fontSize * 2) // half-points
                    let isBold = block.fontName.lowercased().contains("bold")
                    let isItalic = block.fontName.lowercased().contains("italic") || block.fontName.lowercased().contains("oblique")

                    xml += "      <w:r>\n"
                    xml += "        <w:rPr>\n"
                    xml += "          <w:sz w:val=\"\(szVal)\"/>\n"
                    xml += "          <w:szCs w:val=\"\(szVal)\"/>\n"
                    if isBold { xml += "          <w:b/>\n" }
                    if isItalic { xml += "          <w:i/>\n" }
                    xml += "        </w:rPr>\n"
                    xml += "        <w:t xml:space=\"preserve\">\(escaped)</w:t>\n"
                    xml += "      </w:r>\n"
                }

                xml += "    </w:p>\n"
            }

            // Page image
            if let imgIdx = page.imageIndex {
                let emuW = Int(page.pageWidth * 914400.0 / 72.0 * 0.9) // 90% width
                let emuH = Int(page.pageHeight * 914400.0 / 72.0 * 0.9)
                xml += """
                    <w:p>
                      <w:r>
                        <w:drawing>
                          <wp:inline distT="0" distB="0" distL="0" distR="0">
                            <wp:extent cx="\(emuW)" cy="\(emuH)"/>
                            <wp:docPr id="\(imgIdx + 1)" name="Image \(imgIdx + 1)"/>
                            <a:graphic>
                              <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
                                <pic:pic>
                                  <pic:nvPicPr>
                                    <pic:cNvPr id="\(imgIdx + 1)" name="image\(imgIdx).png"/>
                                    <pic:cNvPicPr/>
                                  </pic:nvPicPr>
                                  <pic:blipFill>
                                    <a:blip r:embed="rId\(imgIdx + 1)"/>
                                    <a:stretch><a:fillRect/></a:stretch>
                                  </pic:blipFill>
                                  <pic:spPr>
                                    <a:xfrm>
                                      <a:off x="0" y="0"/>
                                      <a:ext cx="\(emuW)" cy="\(emuH)"/>
                                    </a:xfrm>
                                    <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                                  </pic:spPr>
                                </pic:pic>
                              </a:graphicData>
                            </a:graphic>
                          </wp:inline>
                        </w:drawing>
                      </w:r>
                    </w:p>
                """
            }

            // Page break between pages (not after last)
            if pageIdx < pages.count - 1 {
                xml += "    <w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n"
            }
        }

        xml += """
          </w:body>
        </w:document>
        """
        return xml
    }

    private static func groupBlocksIntoParagraphs(blocks: [TextExtractor.TextBlock], pageHeight: Double) -> [[TextExtractor.TextBlock]] {
        guard !blocks.isEmpty else { return [] }

        // Sort by vertical position (top to bottom), then left to right
        let sorted = blocks.sorted {
            if abs($0.y - $1.y) < $0.height * 0.5 {
                return $0.x < $1.x
            }
            return $0.y < $1.y
        }

        var paragraphs: [[TextExtractor.TextBlock]] = []
        var currentGroup: [TextExtractor.TextBlock] = [sorted[0]]

        for i in 1..<sorted.count {
            let block = sorted[i]
            let prev = currentGroup.last!

            // Same line if vertical overlap
            let lineHeight = max(prev.height, block.height)
            let verticalGap = abs(block.y - prev.y)

            if verticalGap < lineHeight * 1.5 {
                currentGroup.append(block)
            } else {
                paragraphs.append(currentGroup)
                currentGroup = [block]
            }
        }

        if !currentGroup.isEmpty {
            paragraphs.append(currentGroup)
        }

        return paragraphs
    }

    // MARK: - Common Helpers

    private static func resolveUrl(_ urlString: String) -> URL? {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)
        }
        return URL(fileURLWithPath: urlString)
    }

    private static func escapeXml(_ text: String) -> String {
        return text
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}

// MARK: - UIFont Extension

private extension UIFont {
    func withTraits(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        guard let descriptor = fontDescriptor.withSymbolicTraits(traits) else { return self }
        return UIFont(descriptor: descriptor, size: pointSize)
    }
}
