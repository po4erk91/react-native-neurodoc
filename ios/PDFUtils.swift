import Foundation
import PDFKit
import UIKit

// MARK: - URL Resolution

/// Resolves a file path or file:// URL string to a URL.
func resolveUrl(_ urlString: String) -> URL? {
    if urlString.hasPrefix("file://") {
        return URL(string: urlString)
    }
    return URL(fileURLWithPath: urlString)
}

// MARK: - Page Rendering

/// Renders a PDF page to a CGImage at the given scale (default 2x for OCR quality).
func renderPageToImage(page: PDFPage, scale: CGFloat = 2.0) -> CGImage? {
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

// MARK: - UIColor Extensions

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

    var hexString: String? {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return String(format: "#%02X%02X%02X", Int(r * 255), Int(g * 255), Int(b * 255))
    }
}
