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
        document.write(to: url)
        return url
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
            let mergedDoc = PDFDocument()
            var totalPages = 0

            for urlStr in pdfUrls {
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

            let outputUrl = saveTempPdf(mergedDoc, fileName: fileName)
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
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("SPLIT_FAILED", "Failed to load PDF", nil)
                return
            }

            var outputUrls: [String] = []

            for (idx, range) in ranges.enumerated() {
                guard range.count >= 2 else {
                    rejecter("SPLIT_FAILED", "Invalid range at index \(idx)", nil)
                    return
                }
                let start = range[0].intValue
                let end = range[1].intValue

                let splitDoc = PDFDocument()
                var pageIdx = 0
                for i in start...min(end, doc.pageCount - 1) {
                    if let page = doc.page(at: i) {
                        splitDoc.insert(page, at: pageIdx)
                        pageIdx += 1
                    }
                }

                let outputUrl = saveTempPdf(splitDoc)
                outputUrls.append(outputUrl.absoluteString)
            }

            resolver(["pdfUrls": outputUrls])
        }
    }

    // MARK: - deletePages

    public func deletePages(pdfUrl: String, pageIndexes: [NSNumber], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("PAGE_OPERATION_FAILED", "Failed to load PDF", nil)
                return
            }

            let indicesToDelete = Set(pageIndexes.map { $0.intValue })
            let newDoc = PDFDocument()
            var newIdx = 0

            for i in 0..<doc.pageCount {
                if !indicesToDelete.contains(i), let page = doc.page(at: i) {
                    newDoc.insert(page, at: newIdx)
                    newIdx += 1
                }
            }

            let outputUrl = saveTempPdf(newDoc)
            resolver([
                "pdfUrl": outputUrl.absoluteString,
                "pageCount": newDoc.pageCount,
            ] as [String: Any])
        }
    }

    // MARK: - reorderPages

    public func reorderPages(pdfUrl: String, order: [NSNumber], resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            guard let url = resolveUrl(pdfUrl), let doc = PDFDocument(url: url) else {
                rejecter("PAGE_OPERATION_FAILED", "Failed to load PDF", nil)
                return
            }

            let newDoc = PDFDocument()
            for (newIdx, oldIdx) in order.enumerated() {
                let idx = oldIdx.intValue
                guard idx >= 0 && idx < doc.pageCount, let page = doc.page(at: idx) else {
                    rejecter("PAGE_OPERATION_FAILED", "Invalid page index: \(idx)", nil)
                    return
                }
                newDoc.insert(page, at: newIdx)
            }

            let outputUrl = saveTempPdf(newDoc)
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
            // Copy all pages into a fresh PDFDocument to strip encryption.
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

            let targetPages: [Int]
            if let indexes = pageIndexes {
                targetPages = indexes.map { $0.intValue }
            } else {
                targetPages = Array(0..<doc.pageCount)
            }

            let watermarkColor = UIColor(hex: color) ?? .red

            // Load watermark image if provided
            var watermarkImage: UIImage?
            if let imgUrlStr = imageUrl, let imgUrl = resolveUrl(imgUrlStr),
               let data = try? Data(contentsOf: imgUrl) {
                watermarkImage = UIImage(data: data)
            }

            let outputUrl = tempDirectory.appendingPathComponent("\(UUID().uuidString).pdf")
            let renderer = UIGraphicsPDFRenderer(bounds: .zero)

            // We need to create the PDF page by page
            let pdfData = NSMutableData()
            UIGraphicsBeginPDFContextToData(pdfData, .zero, nil)

            for i in 0..<doc.pageCount {
                guard let page = doc.page(at: i) else { continue }
                let bounds = page.bounds(for: .mediaBox)

                UIGraphicsBeginPDFPageWithInfo(bounds, nil)
                guard let ctx = UIGraphicsGetCurrentContext() else { continue }

                // Draw original page
                ctx.saveGState()
                ctx.translateBy(x: 0, y: bounds.height)
                ctx.scaleBy(x: 1, y: -1)
                page.draw(with: .mediaBox, to: ctx)
                ctx.restoreGState()

                // Draw watermark if this page is targeted
                if targetPages.contains(i) {
                    ctx.saveGState()
                    ctx.setAlpha(CGFloat(opacity))

                    let centerX = bounds.width / 2
                    let centerY = bounds.height / 2
                    ctx.translateBy(x: centerX, y: centerY)
                    ctx.rotate(by: -CGFloat(angle) * .pi / 180)

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

                    ctx.restoreGState()
                }
            }

            UIGraphicsEndPDFContext()

            do {
                try pdfData.write(to: outputUrl, options: .atomic)
                resolver(["pdfUrl": outputUrl.absoluteString])
            } catch {
                rejecter("WATERMARK_FAILED", "Failed to save watermarked PDF: \(error.localizedDescription)", error as NSError)
            }
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

    // MARK: - createFormFromPdf

    public func createFormFromPdf(pdfUrl: String, fields: [[String: Any]], removeOriginalText: Bool, resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            FormCreator.createFormFromPdf(pdfUrl: pdfUrl, fields: fields, removeOriginalText: removeOriginalText, tempDirectory: tempDirectory, resolver: resolver, rejecter: rejecter)
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

    init(resolver: @escaping RNResolver, rejecter: @escaping RNRejecter) {
        self.resolver = resolver
        self.rejecter = rejecter
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
            resolver(["pdfUrl": dest.absoluteString])
        } catch {
            url.stopAccessingSecurityScopedResource()
            rejecter("PICKER_FAILED", "Failed to copy file: \(error.localizedDescription)", error as NSError)
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        rejecter("PICKER_CANCELLED", "User cancelled", nil)
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
