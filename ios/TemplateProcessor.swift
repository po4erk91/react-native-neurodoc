import Foundation
import UIKit

// MARK: - Template Models

struct TemplateModel: Decodable {
    var pageSize: PageSizeModel?
    var margins: MarginsModel?
    var defaultFont: FontSpecModel?
    var backgroundColor: String?
    var header: [ElementModel]?
    var footer: [ElementModel]?
    var body: [ElementModel]
}

struct PageSizeModel: Decodable {
    var width: CGFloat
    var height: CGFloat
}

struct MarginsModel: Decodable {
    var top: CGFloat
    var right: CGFloat
    var bottom: CGFloat
    var left: CGFloat
}

struct FontSpecModel: Decodable {
    var family: String?
    var size: CGFloat?
    var bold: Bool?
    var italic: Bool?
    var color: String?
}

struct TableColumnModel: Decodable {
    var header: String
    var key: String
    var width: CGFloat
    var alignment: String?
}

struct ColumnDefModel: Decodable {
    var width: CGFloat
    var elements: [ElementModel]
}

struct KeyValueEntryModel: Decodable {
    var label: String
    var value: String
}

enum ElementModel: Decodable {
    case text(TextModel)
    case image(ImageModel)
    case line(LineModel)
    case spacer(SpacerModel)
    case rect(RectModel)
    case columns(ColumnsModel)
    case table(TableModel)
    case keyValue(KeyValueModel)

    struct TextModel: Decodable {
        var content: String
        var font: FontSpecModel?
        var alignment: String?
        var maxWidth: CGFloat?
        var marginBottom: CGFloat?
    }

    struct ImageModel: Decodable {
        var src: String
        var width: CGFloat
        var height: CGFloat
        var alignment: String?
        var marginBottom: CGFloat?
    }

    struct LineModel: Decodable {
        var thickness: CGFloat?
        var color: String?
        var marginBottom: CGFloat?
    }

    struct SpacerModel: Decodable {
        var height: CGFloat
    }

    struct RectModel: Decodable {
        var width: CGFloat
        var height: CGFloat
        var fillColor: String?
        var borderColor: String?
        var borderWidth: CGFloat?
        var cornerRadius: CGFloat?
        var marginBottom: CGFloat?
    }

    struct ColumnsModel: Decodable {
        var columns: [ColumnDefModel]
        var gap: CGFloat?
        var marginBottom: CGFloat?
    }

    struct TableModel: Decodable {
        var columns: [TableColumnModel]
        var dataKey: String
        var headerFont: FontSpecModel?
        var bodyFont: FontSpecModel?
        var stripeColor: String?
        var showGridLines: Bool?
        var gridLineColor: String?
        var rowHeight: CGFloat?
        var marginBottom: CGFloat?
    }

    struct KeyValueModel: Decodable {
        var entries: [KeyValueEntryModel]
        var labelFont: FontSpecModel?
        var valueFont: FontSpecModel?
        var gap: CGFloat?
        var marginBottom: CGFloat?
    }

    private enum CodingKeys: String, CodingKey {
        case type
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let type = try container.decode(String.self, forKey: .type)

        let singleContainer = try decoder.singleValueContainer()
        switch type {
        case "text":
            self = .text(try singleContainer.decode(TextModel.self))
        case "image":
            self = .image(try singleContainer.decode(ImageModel.self))
        case "line":
            self = .line(try singleContainer.decode(LineModel.self))
        case "spacer":
            self = .spacer(try singleContainer.decode(SpacerModel.self))
        case "rect":
            self = .rect(try singleContainer.decode(RectModel.self))
        case "columns":
            self = .columns(try singleContainer.decode(ColumnsModel.self))
        case "table":
            self = .table(try singleContainer.decode(TableModel.self))
        case "keyValue":
            self = .keyValue(try singleContainer.decode(KeyValueModel.self))
        default:
            throw DecodingError.dataCorruptedError(
                forKey: .type,
                in: container,
                debugDescription: "Unknown element type: \(type)"
            )
        }
    }
}

// MARK: - TemplateProcessor

class TemplateProcessor {

    private static let templateRegex = try? NSRegularExpression(pattern: "\\{\\{([^}]+)\\}\\}")

    // MARK: - Public API

    static func generate(
        templateJson: String,
        dataJson: String,
        fileName: String,
        tempDirectory: URL,
        resolver: @escaping RNResolver,
        rejecter: @escaping RNRejecter
    ) {
        do {
            let decoder = JSONDecoder()

            guard let templateData = templateJson.data(using: .utf8) else {
                rejecter("TEMPLATE_FAILED", "Invalid template JSON", nil)
                return
            }
            guard let dataData = dataJson.data(using: .utf8) else {
                rejecter("TEMPLATE_FAILED", "Invalid data JSON", nil)
                return
            }

            let template = try decoder.decode(TemplateModel.self, from: templateData)
            let data = try JSONSerialization.jsonObject(with: dataData) as? [String: Any] ?? [:]

            let pageWidth = template.pageSize?.width ?? 595
            let pageHeight = template.pageSize?.height ?? 842
            let margins = template.margins ?? MarginsModel(top: 40, right: 40, bottom: 40, left: 40)

            let pageRect = CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight)
            let contentLeft = margins.left
            let contentWidth = pageWidth - margins.left - margins.right

            let defaultFont = template.defaultFont ?? FontSpecModel()

            // Measure header/footer heights
            let headerHeight = measureElements(template.header ?? [], contentWidth: contentWidth, defaultFont: defaultFont, data: data)
            let footerHeight = measureElements(template.footer ?? [], contentWidth: contentWidth, defaultFont: defaultFont, data: data)

            let contentTop = margins.top + headerHeight
            let contentBottom = pageHeight - margins.bottom - footerHeight

            let pdfData = NSMutableData()
            UIGraphicsBeginPDFContextToData(pdfData, pageRect, nil)

            var ctx = RenderContext(
                pageRect: pageRect,
                contentLeft: contentLeft,
                contentWidth: contentWidth,
                contentTop: contentTop,
                contentBottom: contentBottom,
                margins: margins,
                cursorY: contentTop,
                pageCount: 0,
                defaultFont: defaultFont,
                data: data,
                backgroundColor: template.backgroundColor,
                headerElements: template.header ?? [],
                footerElements: template.footer ?? [],
                headerHeight: headerHeight,
                footerHeight: footerHeight
            )

            // Start first page
            startNewPage(&ctx)

            // Render body elements
            for element in template.body {
                renderElement(element, ctx: &ctx)
            }

            // Render footer on last page
            renderFooter(&ctx)

            UIGraphicsEndPDFContext()

            // Save
            let name = fileName.isEmpty ? UUID().uuidString : fileName
            try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
            let outputUrl = tempDirectory.appendingPathComponent("\(name).pdf")
            try pdfData.write(to: outputUrl, options: .atomic)

            let fileSize = (try? Data(contentsOf: outputUrl))?.count ?? pdfData.length

            resolver([
                "pdfUrl": outputUrl.absoluteString,
                "pageCount": ctx.pageCount,
                "fileSize": fileSize,
            ] as [String: Any])

        } catch {
            rejecter("TEMPLATE_FAILED", "Template generation failed: \(error.localizedDescription)", error as NSError)
        }
    }

    // MARK: - Render Context

    struct RenderContext {
        var pageRect: CGRect
        var contentLeft: CGFloat
        var contentWidth: CGFloat
        var contentTop: CGFloat
        var contentBottom: CGFloat
        var margins: MarginsModel
        var cursorY: CGFloat
        var pageCount: Int
        var defaultFont: FontSpecModel
        var data: [String: Any]
        var backgroundColor: String?
        var headerElements: [ElementModel]
        var footerElements: [ElementModel]
        var headerHeight: CGFloat
        var footerHeight: CGFloat
    }

    // MARK: - Page Management

    private static func startNewPage(_ ctx: inout RenderContext) {
        UIGraphicsBeginPDFPageWithInfo(ctx.pageRect, nil)
        ctx.pageCount += 1
        ctx.cursorY = ctx.contentTop

        // Background
        if let bgColor = ctx.backgroundColor, let color = UIColor(hex: bgColor) {
            guard let cgCtx = UIGraphicsGetCurrentContext() else { return }
            cgCtx.setFillColor(color.cgColor)
            cgCtx.fill(ctx.pageRect)
        }

        // Header
        var headerY = ctx.margins.top
        for element in ctx.headerElements {
            let h = measureElement(element, contentWidth: ctx.contentWidth, defaultFont: ctx.defaultFont, data: ctx.data)
            drawElement(element, x: ctx.contentLeft, y: headerY, width: ctx.contentWidth, defaultFont: ctx.defaultFont, data: ctx.data)
            headerY += h
        }
    }

    private static func renderFooter(_ ctx: inout RenderContext) {
        let footerY = ctx.pageRect.height - ctx.margins.bottom - ctx.footerHeight
        var y = footerY
        for element in ctx.footerElements {
            let h = measureElement(element, contentWidth: ctx.contentWidth, defaultFont: ctx.defaultFont, data: ctx.data)
            drawElement(element, x: ctx.contentLeft, y: y, width: ctx.contentWidth, defaultFont: ctx.defaultFont, data: ctx.data)
            y += h
        }
    }

    private static func ensureSpace(_ needed: CGFloat, ctx: inout RenderContext) {
        if ctx.cursorY + needed > ctx.contentBottom {
            renderFooter(&ctx)
            startNewPage(&ctx)
        }
    }

    // MARK: - Element Rendering

    private static func renderElement(_ element: ElementModel, ctx: inout RenderContext) {
        switch element {
        case .text(let m):
            renderText(m, ctx: &ctx)
        case .image(let m):
            renderImage(m, ctx: &ctx)
        case .line(let m):
            renderLine(m, ctx: &ctx)
        case .spacer(let m):
            renderSpacer(m, ctx: &ctx)
        case .rect(let m):
            renderRect(m, ctx: &ctx)
        case .columns(let m):
            renderColumns(m, ctx: &ctx)
        case .table(let m):
            renderTable(m, ctx: &ctx)
        case .keyValue(let m):
            renderKeyValue(m, ctx: &ctx)
        }
    }

    // MARK: Text

    private static func renderText(_ m: ElementModel.TextModel, ctx: inout RenderContext) {
        let resolved = resolveString(m.content, data: ctx.data)
        if resolved.isEmpty { return }

        let font = resolveUIFont(m.font, fallback: ctx.defaultFont)
        let color = resolveUIColor(m.font?.color ?? ctx.defaultFont.color) ?? .black
        let alignment = resolveAlignment(m.alignment)

        let paraStyle = NSMutableParagraphStyle()
        paraStyle.alignment = alignment

        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color,
            .paragraphStyle: paraStyle,
        ]

        let maxW = m.maxWidth ?? ctx.contentWidth
        let textRect = CGRect(x: ctx.contentLeft, y: ctx.cursorY, width: maxW, height: .greatestFiniteMagnitude)
        let boundingRect = (resolved as NSString).boundingRect(
            with: CGSize(width: maxW, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: attrs,
            context: nil
        )
        let textHeight = ceil(boundingRect.height)

        ensureSpace(textHeight + (m.marginBottom ?? 0), ctx: &ctx)

        var drawX = ctx.contentLeft
        if alignment == .center {
            drawX = ctx.contentLeft + (ctx.contentWidth - maxW) / 2
        } else if alignment == .right {
            drawX = ctx.contentLeft + ctx.contentWidth - maxW
        }

        let drawRect = CGRect(x: drawX, y: ctx.cursorY, width: maxW, height: textHeight)
        (resolved as NSString).draw(with: drawRect, options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attrs, context: nil)

        ctx.cursorY += textHeight + (m.marginBottom ?? 0)
    }

    // MARK: Image

    private static func renderImage(_ m: ElementModel.ImageModel, ctx: inout RenderContext) {
        let src = resolveString(m.src, data: ctx.data)
        guard !src.isEmpty else { return }

        guard let image = loadImage(src) else { return }

        ensureSpace(m.height + (m.marginBottom ?? 0), ctx: &ctx)

        var drawX = ctx.contentLeft
        let alignment = m.alignment ?? "left"
        if alignment == "center" {
            drawX = ctx.contentLeft + (ctx.contentWidth - m.width) / 2
        } else if alignment == "right" {
            drawX = ctx.contentLeft + ctx.contentWidth - m.width
        }

        let drawRect = CGRect(x: drawX, y: ctx.cursorY, width: m.width, height: m.height)
        image.draw(in: drawRect)

        ctx.cursorY += m.height + (m.marginBottom ?? 0)
    }

    // MARK: Line

    private static func renderLine(_ m: ElementModel.LineModel, ctx: inout RenderContext) {
        let thickness = m.thickness ?? 1
        let color = resolveUIColor(m.color) ?? UIColor(white: 0.8, alpha: 1)

        ensureSpace(thickness + (m.marginBottom ?? 0), ctx: &ctx)

        guard let cgCtx = UIGraphicsGetCurrentContext() else { return }
        cgCtx.saveGState()
        cgCtx.setStrokeColor(color.cgColor)
        cgCtx.setLineWidth(thickness)
        let y = ctx.cursorY + thickness / 2
        cgCtx.move(to: CGPoint(x: ctx.contentLeft, y: y))
        cgCtx.addLine(to: CGPoint(x: ctx.contentLeft + ctx.contentWidth, y: y))
        cgCtx.strokePath()
        cgCtx.restoreGState()

        ctx.cursorY += thickness + (m.marginBottom ?? 0)
    }

    // MARK: Spacer

    private static func renderSpacer(_ m: ElementModel.SpacerModel, ctx: inout RenderContext) {
        ensureSpace(m.height, ctx: &ctx)
        ctx.cursorY += m.height
    }

    // MARK: Rect

    private static func renderRect(_ m: ElementModel.RectModel, ctx: inout RenderContext) {
        ensureSpace(m.height + (m.marginBottom ?? 0), ctx: &ctx)

        guard let cgCtx = UIGraphicsGetCurrentContext() else { return }
        cgCtx.saveGState()

        let rect = CGRect(x: ctx.contentLeft, y: ctx.cursorY, width: m.width, height: m.height)

        if let radius = m.cornerRadius, radius > 0 {
            let path = UIBezierPath(roundedRect: rect, cornerRadius: radius)
            if let fill = m.fillColor, let color = UIColor(hex: fill) {
                cgCtx.setFillColor(color.cgColor)
                path.fill()
            }
            if let border = m.borderColor, let color = UIColor(hex: border) {
                cgCtx.setStrokeColor(color.cgColor)
                cgCtx.setLineWidth(m.borderWidth ?? 1)
                path.stroke()
            }
        } else {
            if let fill = m.fillColor, let color = UIColor(hex: fill) {
                cgCtx.setFillColor(color.cgColor)
                cgCtx.fill(rect)
            }
            if let border = m.borderColor, let color = UIColor(hex: border) {
                cgCtx.setStrokeColor(color.cgColor)
                cgCtx.setLineWidth(m.borderWidth ?? 1)
                cgCtx.stroke(rect)
            }
        }

        cgCtx.restoreGState()
        ctx.cursorY += m.height + (m.marginBottom ?? 0)
    }

    // MARK: Columns

    private static func renderColumns(_ m: ElementModel.ColumnsModel, ctx: inout RenderContext) {
        let gap = m.gap ?? 10
        let totalWeight = m.columns.reduce(CGFloat(0)) { $0 + $1.width }
        let totalGaps = gap * CGFloat(max(0, m.columns.count - 1))
        let availableWidth = ctx.contentWidth - totalGaps

        // Measure max height across all columns
        var maxHeight: CGFloat = 0
        var columnWidths: [CGFloat] = []

        for col in m.columns {
            let colWidth = availableWidth * (col.width / totalWeight)
            columnWidths.append(colWidth)
            let h = measureElements(col.elements, contentWidth: colWidth, defaultFont: ctx.defaultFont, data: ctx.data)
            maxHeight = max(maxHeight, h)
        }

        ensureSpace(maxHeight + (m.marginBottom ?? 0), ctx: &ctx)

        // Render each column
        var colX = ctx.contentLeft
        for (i, col) in m.columns.enumerated() {
            var colY = ctx.cursorY
            for element in col.elements {
                let h = measureElement(element, contentWidth: columnWidths[i], defaultFont: ctx.defaultFont, data: ctx.data)
                drawElement(element, x: colX, y: colY, width: columnWidths[i], defaultFont: ctx.defaultFont, data: ctx.data)
                colY += h
            }
            colX += columnWidths[i] + gap
        }

        ctx.cursorY += maxHeight + (m.marginBottom ?? 0)
    }

    // MARK: Table

    private static func renderTable(_ m: ElementModel.TableModel, ctx: inout RenderContext) {
        let rows = resolveArray(m.dataKey, data: ctx.data)
        let totalWeight = m.columns.reduce(CGFloat(0)) { $0 + $1.width }
        let columnWidths = m.columns.map { ctx.contentWidth * ($0.width / totalWeight) }

        let headerFont = resolveUIFont(m.headerFont, fallback: FontSpecModel(family: ctx.defaultFont.family, size: ctx.defaultFont.size, bold: true))
        let bodyFont = resolveUIFont(m.bodyFont, fallback: ctx.defaultFont)
        let rowPadding: CGFloat = 6
        let defaultRowHeight = m.rowHeight ?? (bodyFont.lineHeight + rowPadding * 2)
        let headerRowHeight = headerFont.lineHeight + rowPadding * 2
        let showGrid = m.showGridLines ?? true
        let gridColor = resolveUIColor(m.gridLineColor) ?? UIColor(white: 0.8, alpha: 1)
        let stripeColor = resolveUIColor(m.stripeColor)
        let headerFontColor = resolveUIColor(m.headerFont?.color) ?? .black
        let bodyFontColor = resolveUIColor(m.bodyFont?.color ?? ctx.defaultFont.color) ?? .black

        // Render header row
        func drawHeaderRow(at y: CGFloat) {
            guard let cgCtx = UIGraphicsGetCurrentContext() else { return }

            // Header background
            cgCtx.saveGState()
            cgCtx.setFillColor(UIColor(white: 0.95, alpha: 1).cgColor)
            cgCtx.fill(CGRect(x: ctx.contentLeft, y: y, width: ctx.contentWidth, height: headerRowHeight))
            cgCtx.restoreGState()

            var colX = ctx.contentLeft
            for (i, col) in m.columns.enumerated() {
                let text = col.header
                let alignment = resolveAlignment(col.alignment)
                let paraStyle = NSMutableParagraphStyle()
                paraStyle.alignment = alignment

                let attrs: [NSAttributedString.Key: Any] = [
                    .font: headerFont,
                    .foregroundColor: headerFontColor,
                    .paragraphStyle: paraStyle,
                ]

                let textRect = CGRect(x: colX + 4, y: y + rowPadding, width: columnWidths[i] - 8, height: headerRowHeight - rowPadding * 2)
                (text as NSString).draw(with: textRect, options: [.usesLineFragmentOrigin], attributes: attrs, context: nil)

                colX += columnWidths[i]
            }

            // Grid lines
            if showGrid {
                cgCtx.saveGState()
                cgCtx.setStrokeColor(gridColor.cgColor)
                cgCtx.setLineWidth(0.5)

                // Bottom of header
                cgCtx.move(to: CGPoint(x: ctx.contentLeft, y: y + headerRowHeight))
                cgCtx.addLine(to: CGPoint(x: ctx.contentLeft + ctx.contentWidth, y: y + headerRowHeight))
                cgCtx.strokePath()

                cgCtx.restoreGState()
            }
        }

        // Ensure space for at least header + 1 row
        ensureSpace(headerRowHeight + defaultRowHeight, ctx: &ctx)
        drawHeaderRow(at: ctx.cursorY)
        ctx.cursorY += headerRowHeight

        // Render data rows
        for (rowIdx, row) in rows.enumerated() {
            let rowDict = row as? [String: Any] ?? [:]

            // Measure row height by checking text heights
            var rowHeight = defaultRowHeight
            for (i, col) in m.columns.enumerated() {
                let value = stringValue(rowDict[col.key])
                let resolved = resolveString(value, data: ctx.data)
                let attrs: [NSAttributedString.Key: Any] = [.font: bodyFont]
                let textSize = (resolved as NSString).boundingRect(
                    with: CGSize(width: columnWidths[i] - 8, height: .greatestFiniteMagnitude),
                    options: [.usesLineFragmentOrigin],
                    attributes: attrs,
                    context: nil
                )
                rowHeight = max(rowHeight, ceil(textSize.height) + rowPadding * 2)
            }

            // Page break check
            if ctx.cursorY + rowHeight > ctx.contentBottom {
                renderFooter(&ctx)
                startNewPage(&ctx)
                drawHeaderRow(at: ctx.cursorY)
                ctx.cursorY += headerRowHeight
            }

            guard let cgCtx = UIGraphicsGetCurrentContext() else { continue }

            // Stripe background
            if let stripe = stripeColor, rowIdx % 2 == 1 {
                cgCtx.saveGState()
                cgCtx.setFillColor(stripe.cgColor)
                cgCtx.fill(CGRect(x: ctx.contentLeft, y: ctx.cursorY, width: ctx.contentWidth, height: rowHeight))
                cgCtx.restoreGState()
            }

            // Cell text
            var colX = ctx.contentLeft
            for (i, col) in m.columns.enumerated() {
                let value = stringValue(rowDict[col.key])
                let resolved = resolveString(value, data: ctx.data)
                let alignment = resolveAlignment(col.alignment)
                let paraStyle = NSMutableParagraphStyle()
                paraStyle.alignment = alignment

                let attrs: [NSAttributedString.Key: Any] = [
                    .font: bodyFont,
                    .foregroundColor: bodyFontColor,
                    .paragraphStyle: paraStyle,
                ]

                let textRect = CGRect(x: colX + 4, y: ctx.cursorY + rowPadding, width: columnWidths[i] - 8, height: rowHeight - rowPadding * 2)
                (resolved as NSString).draw(with: textRect, options: [.usesLineFragmentOrigin], attributes: attrs, context: nil)

                colX += columnWidths[i]
            }

            // Grid line
            if showGrid {
                cgCtx.saveGState()
                cgCtx.setStrokeColor(gridColor.cgColor)
                cgCtx.setLineWidth(0.5)
                cgCtx.move(to: CGPoint(x: ctx.contentLeft, y: ctx.cursorY + rowHeight))
                cgCtx.addLine(to: CGPoint(x: ctx.contentLeft + ctx.contentWidth, y: ctx.cursorY + rowHeight))
                cgCtx.strokePath()
                cgCtx.restoreGState()
            }

            ctx.cursorY += rowHeight
        }

        ctx.cursorY += m.marginBottom ?? 0
    }

    // MARK: KeyValue

    private static func renderKeyValue(_ m: ElementModel.KeyValueModel, ctx: inout RenderContext) {
        let labelFont = resolveUIFont(m.labelFont, fallback: FontSpecModel(family: ctx.defaultFont.family, size: ctx.defaultFont.size, bold: true))
        let valueFont = resolveUIFont(m.valueFont, fallback: ctx.defaultFont)
        let labelColor = resolveUIColor(m.labelFont?.color) ?? .black
        let valueColor = resolveUIColor(m.valueFont?.color ?? ctx.defaultFont.color) ?? .black
        let gap = m.gap ?? 8
        let lineSpacing: CGFloat = 4

        for entry in m.entries {
            let label = resolveString(entry.label, data: ctx.data)
            let value = resolveString(entry.value, data: ctx.data)

            let labelAttrs: [NSAttributedString.Key: Any] = [.font: labelFont, .foregroundColor: labelColor]
            let valueAttrs: [NSAttributedString.Key: Any] = [.font: valueFont, .foregroundColor: valueColor]

            let labelSize = (label as NSString).size(withAttributes: labelAttrs)
            let valueWidth = ctx.contentWidth - labelSize.width - gap
            let valueBounding = (value as NSString).boundingRect(
                with: CGSize(width: max(valueWidth, 50), height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin],
                attributes: valueAttrs,
                context: nil
            )

            let lineHeight = max(labelSize.height, ceil(valueBounding.height))

            ensureSpace(lineHeight + lineSpacing, ctx: &ctx)

            (label as NSString).draw(at: CGPoint(x: ctx.contentLeft, y: ctx.cursorY), withAttributes: labelAttrs)

            let valueRect = CGRect(x: ctx.contentLeft + labelSize.width + gap, y: ctx.cursorY, width: max(valueWidth, 50), height: lineHeight)
            (value as NSString).draw(with: valueRect, options: [.usesLineFragmentOrigin], attributes: valueAttrs, context: nil)

            ctx.cursorY += lineHeight + lineSpacing
        }

        ctx.cursorY += (m.marginBottom ?? 0)
    }

    // MARK: - Measurement

    private static func measureElements(_ elements: [ElementModel], contentWidth: CGFloat, defaultFont: FontSpecModel, data: [String: Any]) -> CGFloat {
        var total: CGFloat = 0
        for element in elements {
            total += measureElement(element, contentWidth: contentWidth, defaultFont: defaultFont, data: data)
        }
        return total
    }

    private static func measureElement(_ element: ElementModel, contentWidth: CGFloat, defaultFont: FontSpecModel, data: [String: Any]) -> CGFloat {
        switch element {
        case .text(let m):
            let resolved = resolveString(m.content, data: data)
            if resolved.isEmpty { return 0 }
            let font = resolveUIFont(m.font, fallback: defaultFont)
            let attrs: [NSAttributedString.Key: Any] = [.font: font]
            let maxW = m.maxWidth ?? contentWidth
            let boundingRect = (resolved as NSString).boundingRect(
                with: CGSize(width: maxW, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: attrs,
                context: nil
            )
            return ceil(boundingRect.height) + (m.marginBottom ?? 0)

        case .image(let m):
            return m.height + (m.marginBottom ?? 0)

        case .line(let m):
            return (m.thickness ?? 1) + (m.marginBottom ?? 0)

        case .spacer(let m):
            return m.height

        case .rect(let m):
            return m.height + (m.marginBottom ?? 0)

        case .columns(let m):
            let gap = m.gap ?? 10
            let totalWeight = m.columns.reduce(CGFloat(0)) { $0 + $1.width }
            let totalGaps = gap * CGFloat(max(0, m.columns.count - 1))
            let availableWidth = contentWidth - totalGaps
            var maxH: CGFloat = 0
            for col in m.columns {
                let colWidth = availableWidth * (col.width / totalWeight)
                let h = measureElements(col.elements, contentWidth: colWidth, defaultFont: defaultFont, data: data)
                maxH = max(maxH, h)
            }
            return maxH + (m.marginBottom ?? 0)

        case .table(let m):
            let rows = resolveArray(m.dataKey, data: data)
            let bodyFont = resolveUIFont(m.bodyFont, fallback: defaultFont)
            let headerFont = resolveUIFont(m.headerFont, fallback: FontSpecModel(family: defaultFont.family, size: defaultFont.size, bold: true))
            let rowPadding: CGFloat = 6
            let defaultRowH = m.rowHeight ?? (bodyFont.lineHeight + rowPadding * 2)
            let headerH = headerFont.lineHeight + rowPadding * 2
            return headerH + defaultRowH * CGFloat(rows.count) + (m.marginBottom ?? 0)

        case .keyValue(let m):
            let labelFont = resolveUIFont(m.labelFont, fallback: FontSpecModel(family: defaultFont.family, size: defaultFont.size, bold: true))
            let lineHeight = labelFont.lineHeight + 4
            return lineHeight * CGFloat(m.entries.count) + (m.marginBottom ?? 0)
        }
    }

    // MARK: - Draw element at absolute position (for headers/footers/columns)

    private static func drawElement(_ element: ElementModel, x: CGFloat, y: CGFloat, width: CGFloat, defaultFont: FontSpecModel, data: [String: Any]) {
        switch element {
        case .text(let m):
            let resolved = resolveString(m.content, data: data)
            if resolved.isEmpty { return }
            let font = resolveUIFont(m.font, fallback: defaultFont)
            let color = resolveUIColor(m.font?.color ?? defaultFont.color) ?? .black
            let alignment = resolveAlignment(m.alignment)
            let paraStyle = NSMutableParagraphStyle()
            paraStyle.alignment = alignment
            let attrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: color,
                .paragraphStyle: paraStyle,
            ]
            let maxW = m.maxWidth ?? width
            let boundingRect = (resolved as NSString).boundingRect(
                with: CGSize(width: maxW, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: attrs,
                context: nil
            )
            let drawRect = CGRect(x: x, y: y, width: maxW, height: ceil(boundingRect.height))
            (resolved as NSString).draw(with: drawRect, options: [.usesLineFragmentOrigin, .usesFontLeading], attributes: attrs, context: nil)

        case .image(let m):
            let src = resolveString(m.src, data: data)
            guard !src.isEmpty, let image = loadImage(src) else { return }
            var drawX = x
            let alignment = m.alignment ?? "left"
            if alignment == "center" { drawX = x + (width - m.width) / 2 }
            else if alignment == "right" { drawX = x + width - m.width }
            image.draw(in: CGRect(x: drawX, y: y, width: m.width, height: m.height))

        case .line(let m):
            let thickness = m.thickness ?? 1
            let color = resolveUIColor(m.color) ?? UIColor(white: 0.8, alpha: 1)
            guard let cgCtx = UIGraphicsGetCurrentContext() else { return }
            cgCtx.saveGState()
            cgCtx.setStrokeColor(color.cgColor)
            cgCtx.setLineWidth(thickness)
            let lineY = y + thickness / 2
            cgCtx.move(to: CGPoint(x: x, y: lineY))
            cgCtx.addLine(to: CGPoint(x: x + width, y: lineY))
            cgCtx.strokePath()
            cgCtx.restoreGState()

        case .spacer:
            break // nothing to draw

        case .rect(let m):
            guard let cgCtx = UIGraphicsGetCurrentContext() else { return }
            cgCtx.saveGState()
            let rect = CGRect(x: x, y: y, width: m.width, height: m.height)
            if let fill = m.fillColor, let color = UIColor(hex: fill) {
                cgCtx.setFillColor(color.cgColor)
                cgCtx.fill(rect)
            }
            if let border = m.borderColor, let color = UIColor(hex: border) {
                cgCtx.setStrokeColor(color.cgColor)
                cgCtx.setLineWidth(m.borderWidth ?? 1)
                cgCtx.stroke(rect)
            }
            cgCtx.restoreGState()

        case .columns, .table, .keyValue:
            // These complex elements are not expected in header/footer
            break
        }
    }

    // MARK: - Helpers

    private static func resolveString(_ template: String, data: [String: Any]) -> String {
        guard let regex = templateRegex else { return template }

        var result = template
        let matches = regex.matches(in: result, range: NSRange(result.startIndex..., in: result))

        // Process matches in reverse to maintain indices
        for match in matches.reversed() {
            guard let keyRange = Range(match.range(at: 1), in: result) else { continue }
            let keyPath = String(result[keyRange])
            let value = resolveKeyPath(keyPath, in: data)
            let matchRange = Range(match.range, in: result)!
            result.replaceSubrange(matchRange, with: value)
        }

        return result
    }

    private static func resolveKeyPath(_ keyPath: String, in data: [String: Any]) -> String {
        let parts = keyPath.split(separator: ".").map(String.init)
        var current: Any = data

        for part in parts {
            if let dict = current as? [String: Any], let val = dict[part] {
                current = val
            } else {
                return ""
            }
        }

        return stringValue(current)
    }

    private static func resolveArray(_ key: String, data: [String: Any]) -> [Any] {
        let parts = key.split(separator: ".").map(String.init)
        var current: Any = data
        for part in parts {
            if let dict = current as? [String: Any], let val = dict[part] {
                current = val
            } else {
                return []
            }
        }
        return current as? [Any] ?? []
    }

    private static func stringValue(_ value: Any?) -> String {
        guard let value = value else { return "" }
        if let str = value as? String { return str }
        if let num = value as? NSNumber { return num.stringValue }
        if let int = value as? Int { return String(int) }
        if let double = value as? Double { return String(double) }
        return "\(value)"
    }

    private static func resolveUIFont(_ spec: FontSpecModel?, fallback: FontSpecModel) -> UIFont {
        let family = spec?.family ?? fallback.family ?? "Helvetica"
        let size = spec?.size ?? fallback.size ?? 12
        let bold = spec?.bold ?? fallback.bold ?? false
        let italic = spec?.italic ?? fallback.italic ?? false

        let fontName: String
        switch family {
        case "Helvetica":
            if bold && italic { fontName = "Helvetica-BoldOblique" }
            else if bold { fontName = "Helvetica-Bold" }
            else if italic { fontName = "Helvetica-Oblique" }
            else { fontName = "Helvetica" }
        case "Courier":
            if bold && italic { fontName = "Courier-BoldOblique" }
            else if bold { fontName = "Courier-Bold" }
            else if italic { fontName = "Courier-Oblique" }
            else { fontName = "Courier" }
        case "Times":
            if bold && italic { fontName = "TimesNewRomanPS-BoldItalicMT" }
            else if bold { fontName = "TimesNewRomanPS-BoldMT" }
            else if italic { fontName = "TimesNewRomanPS-ItalicMT" }
            else { fontName = "TimesNewRomanPSMT" }
        default:
            fontName = "Helvetica"
        }

        return UIFont(name: fontName, size: size) ?? UIFont.systemFont(ofSize: size)
    }

    private static func resolveUIColor(_ hex: String?) -> UIColor? {
        guard let hex = hex else { return nil }
        return UIColor(hex: hex)
    }

    private static func resolveAlignment(_ alignment: String?) -> NSTextAlignment {
        switch alignment {
        case "center": return .center
        case "right": return .right
        default: return .left
        }
    }

    private static func loadImage(_ src: String) -> UIImage? {
        let path: String
        if src.hasPrefix("file://") {
            path = String(src.dropFirst(7))
        } else {
            path = src
        }

        if let image = UIImage(contentsOfFile: path) {
            return image
        }

        // Try as URL
        if let url = URL(string: src), let data = try? Data(contentsOf: url) {
            return UIImage(data: data)
        }

        return nil
    }
}
