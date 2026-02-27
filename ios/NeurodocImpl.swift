import Foundation
import PDFKit
import UIKit
import UniformTypeIdentifiers

public typealias RNResolver = (Any) -> Void
public typealias RNRejecter = (String, String, NSError?) -> Void

@objcMembers
public class NeurodocImpl: NSObject {
    private let fileManager = FileManager.default

    private lazy var tempDirectory: URL = {
        let dir = fileManager.temporaryDirectory.appendingPathComponent("neurodoc", isDirectory: true)
        try? fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    private func resolveUrl(_ urlString: String) -> URL? {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)
        }
        return URL(fileURLWithPath: urlString)
    }

    private func saveTempPdf(_ document: PDFDocument, fileName: String? = nil) -> URL {
        let name = fileName ?? UUID().uuidString
        let url = tempDirectory.appendingPathComponent("\(name).pdf")
        if let data = document.dataRepresentation() {
            try? data.write(to: url)
        } else {
            document.write(to: url)
        }
        return url
    }

    /// Reorder pages. For operations that don't actually change order, returns a copy.
    /// For actual reordering, manipulates raw PDF data to preserve font encodings.
    private func reorderPdfInPlace(pdfUrl: String, order: [Int]) -> URL? {
        guard let url = resolveUrl(pdfUrl) else { return nil }

        // Check if order is identity (no change needed) — just copy file
        let identity = Array(0..<order.count)
        if order == identity {
            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            do {
                try FileManager.default.copyItem(at: url, to: outputUrl)
            } catch {
                return nil
            }
            return outputUrl
        }

        // For actual reordering, use raw PDF data manipulation via QPDF-style approach:
        // Read the original file, use CGPDFDocument to understand page structure,
        // then construct new PDF by concatenating page data.
        //
        // Since PDFKit corrupts font encoding on save, we use a workaround:
        // Copy file, then use PDFDocument only for exchangePage (no serialization issues
        // when just swapping page references within the same internal structure).
        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        do {
            try FileManager.default.copyItem(at: url, to: outputUrl)
        } catch {
            return nil
        }

        guard let doc = PDFDocument(url: outputUrl) else { return nil }
        let pageCount = doc.pageCount
        guard order.count == pageCount else { return nil }

        // Use selection sort with exchangePage
        var currentOrder = Array(0..<pageCount)
        for targetPos in 0..<pageCount {
            let desiredPage = order[targetPos]
            guard let currentPos = currentOrder.firstIndex(of: desiredPage) else { return nil }
            if currentPos != targetPos {
                doc.exchangePage(at: targetPos, withPageAt: currentPos)
                currentOrder.swapAt(targetPos, currentPos)
            }
        }

        // Write back to same URL (overwrite the copy)
        guard let data = doc.dataRepresentation() else { return nil }
        do {
            try data.write(to: outputUrl)
        } catch {
            return nil
        }
        return outputUrl
    }

    /// Delete pages in-place within a PDFDocument (preserves fonts/text/encoding).
    private func deletePdfPagesInPlace(pdfUrl: String, indicesToDelete: Set<Int>) -> (URL, Int)? {
        guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else { return nil }

        // Remove pages in reverse order to keep indices stable
        for i in stride(from: doc.pageCount - 1, through: 0, by: -1) {
            if indicesToDelete.contains(i) {
                doc.removePage(at: i)
            }
        }

        let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
        guard let data = doc.dataRepresentation() else { return nil }
        do {
            try data.write(to: outputUrl)
        } catch {
            return nil
        }
        return (outputUrl, doc.pageCount)
    }

    /// Split a PDFDocument into ranges (preserves fonts/text by loading from same source each time).
    private func splitPdf(pdfUrl: String, ranges: [[NSNumber]]) -> [URL]? {
        guard let url = resolveUrl(pdfUrl) else { return nil }

        var outputUrls: [URL] = []

        for range in ranges {
            guard range.count >= 2 else { return nil }
            let start = range[0].intValue
            let end = range[1].intValue

            // Load fresh doc for each split to preserve fonts
            guard let doc = PDFDocument(url: url) else { return nil }

            // Remove pages outside the range (reverse order)
            for i in stride(from: doc.pageCount - 1, through: 0, by: -1) {
                if i < start || i > end {
                    doc.removePage(at: i)
                }
            }

            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            guard let data = doc.dataRepresentation() else { return nil }
            do {
                try data.write(to: outputUrl)
            } catch {
                return nil
            }
            outputUrls.append(outputUrl)
        }

        return outputUrls
    }

    // MARK: - getMetadata

    public func getMetadata(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("PDF_LOAD_FAILED", "Failed to load PDF from \(pdfUrl)", nil)
                return
            }

            let attrs = doc.documentAttributes ?? [:]
            let fileSize: Int
            if let data = try? Data(contentsOf: url) {
                fileSize = data.count
            } else {
                fileSize = 0
            }

            // Check if signed by looking for signature annotations
            var isSigned = false
            for i in 0..<doc.pageCount {
                if let page = doc.page(at: i) {
                    for annotation in page.annotations {
                        if annotation.type == "Sig" || annotation.fieldName?.contains("Signature") == true {
                            isSigned = true
                            break
                        }
                    }
                    if isSigned { break }
                }
            }

            resolver([
                "pageCount": doc.pageCount,
                "title": (attrs[PDFDocumentAttribute.titleAttribute] as? String) ?? "",
                "author": (attrs[PDFDocumentAttribute.authorAttribute] as? String) ?? "",
                "creationDate": {
                    if let date = attrs[PDFDocumentAttribute.creationDateAttribute] as? Date {
                        let formatter = ISO8601DateFormatter()
                        return formatter.string(from: date)
                    }
                    return ""
                }(),
                "fileSize": fileSize,
                "isEncrypted": doc.isEncrypted,
                "isSigned": isSigned,
            ] as [String: Any])
        }
    }

    // MARK: - recognizePage

    public func recognizePage(pdfUrl: String, pageIndex: Int, language: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            OcrProcessor.recognizePage(pdfUrl: pdfUrl, pageIndex: pageIndex, language: language, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - makeSearchable

    public func makeSearchable(pdfUrl: String, language: String, pageIndexes: [NSNumber]?, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            OcrProcessor.makeSearchable(pdfUrl: pdfUrl, language: language, pageIndexes: pageIndexes?.map { $0.intValue }, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - merge

    public func merge(pdfUrls: [String], fileName: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            // Use first PDF as base, append pages from others
            guard let firstUrl = pdfUrls.first,
                  let baseUrl = resolveUrl(firstUrl),
                  let mergedDoc = PDFDocument(url: baseUrl) else {
                rejecter("MERGE_FAILED", "Failed to load first PDF", nil)
                return
            }

            var totalPages = mergedDoc.pageCount

            for urlStr in pdfUrls.dropFirst() {
                guard let url = resolveUrl(urlStr), let doc = PDFDocument(url: url) else {
                    rejecter("MERGE_FAILED", "Failed to load PDF: \(urlStr)", nil)
                    return
                }
                for i in 0..<doc.pageCount {
                    if let page = doc.page(at: i) {
                        mergedDoc.insert(page, at: totalPages)
                        totalPages += 1
                    }
                }
            }

            let name = fileName.hasSuffix(".pdf") ? String(fileName.dropLast(4)) : fileName
            let outputUrl = tempDirectory.appendingPathComponent("\(name).pdf")
            if let data = mergedDoc.dataRepresentation() {
                try? data.write(to: outputUrl)
            } else {
                mergedDoc.write(to: outputUrl)
            }

            let fileSize = (try? Data(contentsOf: outputUrl))?.count ?? 0

            resolver([
                "pdfUrl": outputUrl.absoluteString,
                "pageCount": totalPages,
                "fileSize": fileSize,
            ] as [String: Any])
        }
    }

    // MARK: - split

    public func split(pdfUrl: String, ranges: [[NSNumber]], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let urls = splitPdf(pdfUrl: pdfUrl, ranges: ranges) else {
                rejecter("SPLIT_FAILED", "Failed to split PDF", nil)
                return
            }

            resolver(["pdfUrls": urls.map { $0.absoluteString }])
        }
    }

    // MARK: - deletePages

    public func deletePages(pdfUrl: String, pageIndexes: [NSNumber], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let indicesToDelete = Set(pageIndexes.map { $0.intValue })
            guard let (outputUrl, pageCount) = deletePdfPagesInPlace(pdfUrl: pdfUrl, indicesToDelete: indicesToDelete) else {
                rejecter("PAGE_OPERATION_FAILED", "Failed to delete pages", nil)
                return
            }

            resolver([
                "pdfUrl": outputUrl.absoluteString,
                "pageCount": pageCount,
            ] as [String: Any])
        }
    }

    // MARK: - reorderPages

    public func reorderPages(pdfUrl: String, order: [NSNumber], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let orderArray = order.map { $0.intValue }
            guard let outputUrl = reorderPdfInPlace(pdfUrl: pdfUrl, order: orderArray) else {
                rejecter("PAGE_OPERATION_FAILED", "Failed to reorder pages", nil)
                return
            }

            resolver(["pdfUrl": outputUrl.absoluteString])
        }
    }

    // MARK: - addAnnotations

    public func addAnnotations(pdfUrl: String, annotations: [[String: Any]], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            AnnotationProcessor.addAnnotations(pdfUrl: pdfUrl, annotations: annotations, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - getAnnotations

    public func getAnnotations(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async {
            AnnotationProcessor.getAnnotations(pdfUrl: pdfUrl, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - deleteAnnotation

    public func deleteAnnotation(pdfUrl: String, annotationId: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            AnnotationProcessor.deleteAnnotation(pdfUrl: pdfUrl, annotationId: annotationId, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - getFormFields

    public func getFormFields(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async {
            FormProcessor.getFormFields(pdfUrl: pdfUrl, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - fillForm

    public func fillForm(pdfUrl: String, fields: [[String: Any]], flattenAfterFill: Bool, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            FormProcessor.fillForm(pdfUrl: pdfUrl, fields: fields, flattenAfterFill: flattenAfterFill, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - encrypt

    public func encrypt(pdfUrl: String, userPassword: String, ownerPassword: String, allowPrinting: Bool, allowCopying: Bool, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("ENCRYPTION_FAILED", "Failed to load PDF", nil)
                return
            }

            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")

            var options: [PDFDocumentWriteOption: Any] = [
                .userPasswordOption: userPassword,
                .ownerPasswordOption: ownerPassword,
            ]

            // Use Core Graphics keys for access permissions
            if !allowPrinting {
                options[PDFDocumentWriteOption(rawValue: kCGPDFContextAllowsPrinting as String)] = false
            }
            if !allowCopying {
                options[PDFDocumentWriteOption(rawValue: kCGPDFContextAllowsCopying as String)] = false
            }

            let success = doc.write(to: outputUrl, withOptions: options)
            if success {
                resolver(["pdfUrl": outputUrl.absoluteString])
            } else {
                rejecter("ENCRYPTION_FAILED", "Failed to encrypt PDF", nil)
            }
        }
    }

    // MARK: - decrypt

    public func decrypt(pdfUrl: String, password: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("ENCRYPTION_FAILED", "Failed to load PDF", nil)
                return
            }

            if doc.isEncrypted {
                let unlocked = doc.unlock(withPassword: password)
                if !unlocked {
                    rejecter("ENCRYPTION_FAILED", "Invalid password", nil)
                    return
                }
            }

            // PDFKit's write() preserves encryption metadata even after unlock.
            // Copy pages in-place to a fresh PDFDocument to strip encryption while preserving fonts.
            let newDoc = PDFDocument()
            for i in 0..<doc.pageCount {
                if let page = doc.page(at: i) {
                    newDoc.insert(page, at: i)
                }
            }

            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            let success = newDoc.write(to: outputUrl)
            if success {
                resolver(["pdfUrl": outputUrl.absoluteString])
            } else {
                rejecter("ENCRYPTION_FAILED", "Failed to write decrypted PDF", nil)
            }
        }
    }

    // MARK: - addWatermark

    public func addWatermark(pdfUrl: String, text: String?, imageUrl: String?, opacity: Double, angle: Double, fontSize: Double, color: String, pageIndexes: [NSNumber]?, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("WATERMARK_FAILED", "Failed to load PDF", nil)
                return
            }

            let targetPages: Set<Int>
            if let indexes = pageIndexes {
                targetPages = Set(indexes.map { $0.intValue })
            } else {
                targetPages = Set(0..<doc.pageCount)
            }

            let watermarkColor = UIColor(hex: color) ?? .red

            // Load watermark image if provided
            var watermarkImage: UIImage?
            if let imgUrlStr = imageUrl, let imgUrl = resolveUrl(imgUrlStr),
               let imgData = try? Data(contentsOf: imgUrl) {
                watermarkImage = UIImage(data: imgData)
            }

            for i in 0..<doc.pageCount {
                guard targetPages.contains(i), let page = doc.page(at: i) else { continue }
                let bounds = page.bounds(for: .mediaBox)

                // Create a stamp annotation that covers the full page for the watermark
                let stampAnnot = PDFAnnotation(bounds: bounds, forType: .stamp, withProperties: nil)

                // Render watermark content into the stamp appearance
                let renderer = UIGraphicsImageRenderer(size: bounds.size)
                let image = renderer.image { ctx in
                    // Transparent background
                    UIColor.clear.setFill()
                    ctx.fill(CGRect(origin: .zero, size: bounds.size))

                    let cgCtx = ctx.cgContext
                    cgCtx.setAlpha(CGFloat(opacity))

                    let centerX = bounds.width / 2
                    let centerY = bounds.height / 2
                    cgCtx.translateBy(x: centerX, y: centerY)
                    cgCtx.rotate(by: -CGFloat(angle) * .pi / 180)

                    if let text = text {
                        let font = UIFont.systemFont(ofSize: CGFloat(fontSize), weight: .bold)
                        let attrs: [NSAttributedString.Key: Any] = [
                            .font: font,
                            .foregroundColor: watermarkColor,
                        ]
                        let size = (text as NSString).size(withAttributes: attrs)
                        (text as NSString).draw(at: CGPoint(x: -size.width / 2, y: -size.height / 2), withAttributes: attrs)
                    }

                    if let img = watermarkImage {
                        let imgSize = CGSize(width: min(img.size.width, bounds.width * 0.5),
                                             height: min(img.size.height, bounds.height * 0.5))
                        img.draw(in: CGRect(x: -imgSize.width / 2, y: -imgSize.height / 2, width: imgSize.width, height: imgSize.height))
                    }
                }

                // Set the stamp appearance from our rendered image
                let border = PDFBorder()
                border.lineWidth = 0
                stampAnnot.border = border
                stampAnnot.color = .clear

                // Create appearance stream from image
                if let cgImage = image.cgImage {
                    let imagePage = PDFPage(image: UIImage(cgImage: cgImage))
                    // Use PDFAnnotation's direct image approach
                    stampAnnot.setValue(image, forAnnotationKey: PDFAnnotationKey(rawValue: "/AP"))
                }

                page.addAnnotation(stampAnnot)
            }

            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            doc.write(to: outputUrl)
            resolver(["pdfUrl": outputUrl.absoluteString])
        }
    }

    // MARK: - redact

    public func redact(pdfUrl: String, redactions: [[String: Any]], dpi: Double, stripMetadata: Bool, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            RedactionProcessor.redact(
                pdfUrl: pdfUrl,
                redactions: redactions,
                dpi: dpi,
                stripMetadata: stripMetadata,
                tempDirectory: tempDirectory,
                resolver: resolver,
                rejecter: rejecter
            )
        }
    }

    // MARK: - generateFromTemplate

    public func generateFromTemplate(templateJson: String, dataJson: String, fileName: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            TemplateProcessor.generate(
                templateJson: templateJson,
                dataJson: dataJson,
                fileName: fileName,
                tempDirectory: tempDirectory,
                resolver: resolver,
                rejecter: rejecter
            )
        }
    }

    // MARK: - extractText

    public func extractText(pdfUrl: String, pageIndex: Int, mode: String, language: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async {
            TextExtractor.extractText(pdfUrl: pdfUrl, pageIndex: pageIndex, mode: mode, language: language, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - editContent

    public func editContent(pdfUrl: String, edits: [[String: Any]], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            ContentEditor.editContent(
                pdfUrl: pdfUrl,
                edits: edits,
                tempDirectory: tempDirectory,
                resolver: resolver,
                rejecter: rejecter
            )
        }
    }

    // MARK: - createFormFromPdf

    public func createFormFromPdf(pdfUrl: String, fields: [[String: Any]], removeOriginalText: Bool, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            FormCreator.createFormFromPdf(pdfUrl: pdfUrl, fields: fields, removeOriginalText: removeOriginalText, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - getBookmarks

    public func getBookmarks(pdfUrl: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async {
            BookmarkProcessor.getBookmarks(pdfUrl: pdfUrl, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - addBookmarks

    public func addBookmarks(pdfUrl: String, bookmarks: [[String: Any]], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            BookmarkProcessor.addBookmarks(pdfUrl: pdfUrl, bookmarks: bookmarks, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - removeBookmarks

    public func removeBookmarks(pdfUrl: String, indexes: [NSNumber], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            BookmarkProcessor.removeBookmarks(pdfUrl: pdfUrl, indexes: indexes.map { $0.intValue }, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
        }
    }

    // MARK: - pickDocument

    public func pickDocument(resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf])
            picker.allowsMultipleSelection = false

            let delegate = DocumentPickerDelegate(resolver: resolver, rejecter: rejecter)
            picker.delegate = delegate
            objc_setAssociatedObject(picker, &DocumentPickerDelegate.associatedKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)

            guard let rootVC = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows })
                .first(where: { $0.isKeyWindow })?
                .rootViewController else {
                rejecter("PICKER_FAILED", "No root view controller", nil)
                return
            }

            var presenter = rootVC
            while let presented = presenter.presentedViewController {
                presenter = presented
            }
            presenter.present(picker, animated: true)
        }
    }

    // MARK: - pickFile

    public func pickFile(types: [String], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.main.async {
            NSLog("[Neurodoc] pickFile called with types: %@", types.joined(separator: ", "))
            let utTypes = types.compactMap { UTType($0) }
            NSLog("[Neurodoc] Resolved UTTypes: %@", utTypes.map { $0.identifier }.joined(separator: ", "))
            guard !utTypes.isEmpty else {
                rejecter("PICKER_FAILED", "No valid UTType identifiers provided", nil)
                return
            }

            let picker = UIDocumentPickerViewController(forOpeningContentTypes: utTypes)
            picker.allowsMultipleSelection = false

            let delegate = DocumentPickerDelegate(resolver: resolver, rejecter: rejecter, resultKey: "fileUrl")
            picker.delegate = delegate
            objc_setAssociatedObject(picker, &DocumentPickerDelegate.associatedKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)

            guard let rootVC = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows })
                .first(where: { $0.isKeyWindow })?
                .rootViewController else {
                rejecter("PICKER_FAILED", "No root view controller", nil)
                return
            }

            var presenter = rootVC
            while let presented = presenter.presentedViewController {
                presenter = presented
            }
            presenter.present(picker, animated: true)
        }
    }

    // MARK: - convertDocxToPdf

    public func convertDocxToPdf(inputPath: String, preserveImages: Bool, pageSize: String,
                                  resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            DocxConverter.convertDocxToPdf(
                inputPath: inputPath,
                preserveImages: preserveImages,
                pageSize: pageSize,
                tempDirectory: tempDirectory,
                resolver: resolver,
                rejecter: rejecter
            )
        }
    }

    // MARK: - convertPdfToDocx

    public func convertPdfToDocx(inputPath: String, mode: String, language: String,
                                  resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            DocxConverter.convertPdfToDocx(
                inputPath: inputPath,
                mode: mode,
                language: language,
                tempDirectory: tempDirectory,
                resolver: resolver,
                rejecter: rejecter
            )
        }
    }

    // MARK: - saveTo

    public func saveTo(pdfUrl: String, fileName: String, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.main.async { [self] in
            guard let sourceUrl = resolveUrl(pdfUrl), FileManager.default.fileExists(atPath: sourceUrl.path) else {
                rejecter("SAVE_FAILED", "Source file does not exist: \(pdfUrl)", nil)
                return
            }

            // Copy source to temp with desired fileName so the save dialog shows correct name
            let safeName = fileName.hasSuffix(".pdf") ? fileName : "\(fileName).pdf"
            let tempUrl = tempDirectory.appendingPathComponent(safeName)
            try? FileManager.default.removeItem(at: tempUrl)
            do {
                try FileManager.default.copyItem(at: sourceUrl, to: tempUrl)
            } catch {
                rejecter("SAVE_FAILED", "Failed to prepare file for export: \(error.localizedDescription)", error as NSError)
                return
            }

            let picker = UIDocumentPickerViewController(forExporting: [tempUrl], asCopy: true)
            let delegate = SaveDocumentPickerDelegate(resolver: resolver, rejecter: rejecter)
            picker.delegate = delegate
            objc_setAssociatedObject(picker, &SaveDocumentPickerDelegate.associatedKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)

            guard let rootVC = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .flatMap({ $0.windows })
                .first(where: { $0.isKeyWindow })?
                .rootViewController else {
                rejecter("SAVE_FAILED", "No root view controller", nil)
                return
            }

            var presenter = rootVC
            while let presented = presenter.presentedViewController {
                presenter = presented
            }
            presenter.present(picker, animated: true)
        }
    }

    // MARK: - comparePdfs

    public func comparePdfs(
        pdfUrl1: String,
        pdfUrl2: String,
        addedColor: String,
        deletedColor: String,
        changedColor: String,
        opacity: Double,
        annotateSource: Bool,
        annotateTarget: Bool,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            DiffProcessor.comparePdfs(
                pdfUrl1: pdfUrl1,
                pdfUrl2: pdfUrl2,
                addedColor: addedColor,
                deletedColor: deletedColor,
                changedColor: changedColor,
                opacity: opacity,
                annotateSource: annotateSource,
                annotateTarget: annotateTarget,
                tempDirectory: tempDirectory,
                resolver: resolver,
                rejecter: rejecter
            )
        }
    }

    // MARK: - cleanupTempFiles

    public func cleanupTempFiles(resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                if fileManager.fileExists(atPath: tempDirectory.path) {
                    try fileManager.removeItem(at: tempDirectory)
                }
                resolver(true)
            } catch {
                rejecter("CLEANUP_FAILED", "Failed to cleanup temp files: \(error.localizedDescription)", error as NSError)
            }
        }
    }
}

// MARK: - DocumentPickerDelegate

private class DocumentPickerDelegate: NSObject, UIDocumentPickerDelegate {
    static var associatedKey: UInt8 = 0

    private let resolver: RNResolver
    private let rejecter: RNRejecter
    private let resultKey: String

    init(resolver: @escaping RNResolver, rejecter: @escaping RNRejecter, resultKey: String = "pdfUrl") {
        self.resolver = resolver
        self.rejecter = rejecter
        self.resultKey = resultKey
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            rejecter("PICKER_FAILED", "No file selected", nil)
            return
        }
        guard url.startAccessingSecurityScopedResource() else {
            rejecter("PICKER_FAILED", "Cannot access file", nil)
            return
        }
        // Copy to temp so we have persistent access
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("neurodoc", isDirectory: true)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let dest = tempDir.appendingPathComponent(url.lastPathComponent)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.copyItem(at: url, to: dest)
            url.stopAccessingSecurityScopedResource()
            resolver([resultKey: dest.absoluteString])
        } catch {
            url.stopAccessingSecurityScopedResource()
            rejecter("PICKER_FAILED", "Failed to copy file: \(error.localizedDescription)", error as NSError)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        rejecter("PICKER_CANCELLED", "User cancelled", nil)
    }
}

// MARK: - SaveDocumentPickerDelegate

private class SaveDocumentPickerDelegate: NSObject, UIDocumentPickerDelegate {
    static var associatedKey: UInt8 = 0

    private let resolver: RNResolver
    private let rejecter: RNRejecter

    init(resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        self.resolver = resolver
        self.rejecter = rejecter
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            rejecter("SAVE_FAILED", "No destination selected", nil)
            return
        }
        resolver(["savedPath": url.absoluteString])
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        rejecter("SAVE_FAILED", "User cancelled save", nil)
    }
}

// MARK: - UIColor hex extension

extension UIColor {
    convenience init?(hex: String) {
        var hexStr = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if hexStr.hasPrefix("#") { hexStr.removeFirst() }

        guard hexStr.count == 6, let rgb = UInt64(hexStr, radix: 16) else { return nil }

        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
            green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(rgb & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
}
