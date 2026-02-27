import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export type NeurodocErrorCode =
  | 'INVALID_INPUT'
  | 'PDF_LOAD_FAILED'
  | 'OCR_FAILED'
  | 'MERGE_FAILED'
  | 'SPLIT_FAILED'
  | 'PAGE_OPERATION_FAILED'
  | 'ANNOTATION_FAILED'
  | 'FORM_FAILED'
  | 'ENCRYPTION_FAILED'
  | 'WATERMARK_FAILED'
  | 'TEMPLATE_FAILED'
  | 'TEXT_EXTRACTION_FAILED'
  | 'REDACTION_FAILED'
  | 'CONTENT_EDIT_FAILED'
  | 'BOOKMARK_FAILED'
  | 'CONVERSION_FAILED'
  | 'COMPARISON_FAILED'
  | 'CLEANUP_FAILED'
  | 'SAVE_FAILED';

export interface NeurodocError extends Error {
  code: NeurodocErrorCode;
}

export function isNeurodocError(error: unknown): error is NeurodocError {
  return (
    error instanceof Error &&
    'code' in error &&
    typeof (error as NeurodocError).code === 'string'
  );
}

export interface Spec extends TurboModule {
  /**
   * Get PDF document metadata.
   */
  getMetadata(pdfUrl: string): Promise<{
    pageCount: number;
    title: string;
    author: string;
    creationDate: string;
    fileSize: number;
    isEncrypted: boolean;
    isSigned: boolean;
  }>;

  // --- OCR ---

  /**
   * Recognize text from a single PDF page using OCR.
   * iOS: Vision framework, Android: Google ML Kit.
   */
  recognizePage(options: {
    pdfUrl: string;
    pageIndex: number;
    language: string; // 'auto', 'en', 'uk', 'pl', etc.
  }): Promise<{
    blocks: Array<{
      text: string;
      boundingBox: {
        x: number;
        y: number;
        width: number;
        height: number;
      };
      confidence: number;
    }>;
  }>;

  /**
   * Create a searchable PDF by embedding an invisible text layer.
   * Returns a new PDF URL with embedded text.
   */
  makeSearchable(options: {
    pdfUrl: string;
    language: string;
    pageIndexes?: number[];
  }): Promise<{
    pdfUrl: string;
    pagesProcessed: number;
  }>;

  // --- Page Operations ---

  /**
   * Merge multiple PDFs into one.
   */
  merge(options: { pdfUrls: string[]; fileName?: string }): Promise<{
    pdfUrl: string;
    pageCount: number;
    fileSize: number;
  }>;

  /**
   * Split a PDF into multiple files by page ranges.
   * Each range is [startPage, endPage] (inclusive, 0-based).
   */
  split(options: { pdfUrl: string; ranges: Array<number[]> }): Promise<{
    pdfUrls: string[];
  }>;

  /**
   * Delete pages from a PDF by their indices.
   */
  deletePages(options: { pdfUrl: string; pageIndexes: number[] }): Promise<{
    pdfUrl: string;
    pageCount: number;
  }>;

  /**
   * Reorder pages in a PDF.
   * The order array contains the new index arrangement, e.g. [2, 0, 1, 3].
   */
  reorderPages(options: { pdfUrl: string; order: number[] }): Promise<{
    pdfUrl: string;
  }>;

  // --- Annotations ---

  /**
   * Add annotations to a PDF.
   * Coordinates are normalized (0-1 range).
   */
  addAnnotations(options: {
    pdfUrl: string;
    annotations: Array<{
      type: string; // 'highlight' | 'note' | 'freehand'
      pageIndex: number;
      // highlight
      rects?: Array<{
        x: number;
        y: number;
        width: number;
        height: number;
      }>;
      color?: string;
      opacity?: number;
      // note
      x?: number;
      y?: number;
      text?: string;
      // freehand
      points?: Array<number[]>;
      strokeWidth?: number;
    }>;
  }): Promise<{
    pdfUrl: string;
  }>;

  /**
   * Get all annotations from a PDF.
   */
  getAnnotations(pdfUrl: string): Promise<{
    annotations: Array<{
      id: string;
      type: string;
      pageIndex: number;
      color: string;
      // Annotation-specific fields serialized as available
      x: number;
      y: number;
      width: number;
      height: number;
      text: string;
    }>;
  }>;

  /**
   * Delete an annotation by ID.
   */
  deleteAnnotation(options: { pdfUrl: string; annotationId: string }): Promise<{
    pdfUrl: string;
  }>;

  // --- Forms (AcroForms) ---

  /**
   * Get all form fields from a PDF.
   */
  getFormFields(pdfUrl: string): Promise<{
    fields: Array<{
      id: string;
      name: string;
      type: string; // 'text' | 'checkbox' | 'radio' | 'dropdown'
      value: string;
      options: string[];
      fontSize?: number;
      fontName?: string;
    }>;
  }>;

  /**
   * Fill form fields in a PDF.
   */
  fillForm(options: {
    pdfUrl: string;
    fields: Array<{
      id: string;
      value: string;
      fontSize?: number;
      fontName?: string;
    }>;
    flattenAfterFill?: boolean;
  }): Promise<{
    pdfUrl: string;
  }>;

  // --- Encryption ---

  /**
   * Encrypt a PDF with password protection (AES).
   */
  encrypt(options: {
    pdfUrl: string;
    userPassword: string;
    ownerPassword: string;
    allowPrinting?: boolean;
    allowCopying?: boolean;
  }): Promise<{
    pdfUrl: string;
  }>;

  /**
   * Decrypt a password-protected PDF.
   */
  decrypt(options: { pdfUrl: string; password: string }): Promise<{
    pdfUrl: string;
  }>;

  // --- Watermark ---

  /**
   * Add a text or image watermark to PDF pages.
   */
  addWatermark(options: {
    pdfUrl: string;
    text?: string;
    imageUrl?: string;
    opacity?: number;
    angle?: number;
    fontSize?: number;
    color?: string;
    pageIndexes?: number[];
  }): Promise<{
    pdfUrl: string;
  }>;

  // --- Redaction ---

  /**
   * Redact (permanently destroy) content from PDF regions.
   * Uses rasterization to guarantee byte-level content destruction.
   * Coordinates are normalized (0-1 range, top-left origin).
   * Only pages containing redactions are rasterized; others pass through unchanged.
   */
  redact(options: {
    pdfUrl: string;
    redactions: Array<{
      pageIndex: number;
      rects: Array<{
        x: number;
        y: number;
        width: number;
        height: number;
      }>;
      color?: string; // fill color for redaction, default '#000000'
    }>;
    dpi?: number; // rasterization DPI, default 300
    stripMetadata?: boolean; // remove PDF metadata, default false
  }): Promise<{
    pdfUrl: string;
    pagesRedacted: number;
  }>;

  // --- Template PDF Generation ---

  /**
   * Generate a PDF from a template definition and data.
   * Template and data are JSON strings to simplify bridge crossing for deeply nested structures.
   */
  generateFromTemplate(options: {
    templateJson: string;
    dataJson: string;
    fileName?: string;
  }): Promise<{
    pdfUrl: string;
    pageCount: number;
    fileSize: number;
  }>;

  // --- Text Extraction ---

  /**
   * Extract text with positions from a PDF page.
   * Supports native text extraction (from PDF content stream) and OCR fallback.
   * Coordinates are normalized (0-1 range, top-left origin).
   */
  extractText(options: {
    pdfUrl: string;
    pageIndex: number;
    mode?: string; // 'native' | 'ocr' | 'auto' (default 'auto')
    language?: string; // for OCR fallback, default 'auto'
  }): Promise<{
    textBlocks: Array<{
      text: string;
      boundingBox: {
        x: number;
        y: number;
        width: number;
        height: number;
      };
      fontSize: number;
      fontName: string;
      confidence: number;
    }>;
    pageWidth: number;
    pageHeight: number;
    mode: string;
  }>;

  // --- Content Editing ---

  /**
   * Replace text in a PDF by visually covering original text and overlaying new text.
   * Uses white-out + overlay approach (no content stream modification).
   * Coordinates are normalized (0-1 range, top-left origin).
   * Workflow: extractText() to get positions, then editContent() with bounding boxes.
   */
  editContent(options: {
    pdfUrl: string;
    edits: Array<{
      pageIndex: number;
      boundingBox: {
        x: number;
        y: number;
        width: number;
        height: number;
      };
      newText: string;
      fontSize?: number;
      fontName?: string; // 'Helvetica' | 'Courier' | 'TimesNewRoman'
      color?: string; // hex color, default '#000000'
    }>;
  }): Promise<{
    pdfUrl: string;
    editsApplied: number;
  }>;

  // --- Form Creation ---

  /**
   * Create a fillable PDF form from an existing PDF.
   * Overlays AcroForm fields at specified positions, optionally whiting out original text.
   * Coordinates are normalized (0-1 range, top-left origin).
   */
  createFormFromPdf(options: {
    pdfUrl: string;
    fields: Array<{
      name: string;
      pageIndex: number;
      boundingBox: {
        x: number;
        y: number;
        width: number;
        height: number;
      };
      type?: string; // 'text' | 'checkbox' (default 'text')
      defaultValue?: string;
      fontSize?: number;
      fontName?: string;
    }>;
    removeOriginalText?: boolean;
  }): Promise<{
    pdfUrl: string;
  }>;

  // --- Bookmarks ---

  /**
   * Get all bookmarks (outline) from a PDF.
   * Returns a flat list with nesting level info.
   */
  getBookmarks(pdfUrl: string): Promise<{
    bookmarks: Array<{
      title: string;
      pageIndex: number;
      level: number;
      children: number;
    }>;
  }>;

  /**
   * Add bookmarks to a PDF.
   */
  addBookmarks(options: {
    pdfUrl: string;
    bookmarks: Array<{
      title: string;
      pageIndex: number;
      parentIndex?: number;
    }>;
  }): Promise<{
    pdfUrl: string;
  }>;

  /**
   * Remove bookmarks from a PDF by their flat-list indexes.
   */
  removeBookmarks(options: {
    pdfUrl: string;
    indexes: number[];
  }): Promise<{
    pdfUrl: string;
  }>;

  // --- Document Conversion ---

  /**
   * Convert a DOCX file to PDF.
   * Supports: paragraphs, headings, bold/italic/underline, font size/color,
   * inline images, basic tables, bullet/numbered lists.
   */
  convertDocxToPdf(options: {
    inputPath: string;
    preserveImages?: boolean; // default true
    pageSize?: string; // 'A4' | 'Letter' | 'Legal', default 'A4'
  }): Promise<{
    pdfUrl: string;
    pageCount: number;
    fileSize: number;
    warnings: string[];
  }>;

  /**
   * Convert a PDF file to DOCX.
   * Uses text extraction (native + OCR fallback) to reconstruct document structure.
   * Layout fidelity depends on the PDF source.
   */
  convertPdfToDocx(options: {
    inputPath: string;
    mode?: string; // 'text' | 'textAndImages' | 'ocrFallback', default 'textAndImages'
    language?: string; // for OCR fallback, default 'auto'
  }): Promise<{
    docxUrl: string;
    pageCount: number;
    fileSize: number;
    mode: string;
  }>;

  // --- Document Picker ---

  /**
   * Open system document picker to select a PDF file.
   */
  pickDocument(): Promise<{ pdfUrl: string }>;

  /**
   * Open system document picker to select a file of specified types.
   * @param types - Array of UTType identifiers (iOS) / MIME types (Android)
   *   e.g. ['org.openxmlformats.wordprocessingml.document'] for DOCX
   */
  pickFile(types: string[]): Promise<{ fileUrl: string }>;

  // --- Save ---

  /**
   * Open system "Save As" dialog for the user to choose where to save the PDF.
   * On Android: uses ACTION_CREATE_DOCUMENT (Storage Access Framework).
   * On iOS: uses UIDocumentPickerViewController in export mode.
   */
  saveTo(pdfUrl: string, fileName: string): Promise<{ savedPath: string }>;

  // --- Diff / Compare ---

  /**
   * Compare two PDF documents and return annotated versions with highlighted differences.
   * Uses word-level text extraction + Myers diff algorithm.
   * - added blocks (in doc2 only) → highlighted on targetPdfUrl (green by default)
   * - deleted blocks (in doc1 only) → highlighted on sourcePdfUrl (red by default)
   * - changed blocks (fuzzy match >80%) → highlighted on both (yellow by default)
   */
  comparePdfs(options: {
    pdfUrl1: string;
    pdfUrl2: string;
    addedColor?: string;      // hex, default '#00CC00'
    deletedColor?: string;    // hex, default '#FF4444'
    changedColor?: string;    // hex, default '#FFAA00'
    opacity?: number;         // 0-1, default 0.35
    annotateSource?: boolean; // annotate pdfUrl1 with deletions/changes, default true
    annotateTarget?: boolean; // annotate pdfUrl2 with additions/changes, default true
  }): Promise<{
    sourcePdfUrl: string;  // annotated pdfUrl1 (empty string if annotateSource=false)
    targetPdfUrl: string;  // annotated pdfUrl2 (empty string if annotateTarget=false)
    changes: Array<{
      pageIndex1: number;  // -1 if page only exists in doc2
      pageIndex2: number;  // -1 if page only exists in doc1
      added: number;
      deleted: number;
      changed: number;
    }>;
    totalAdded: number;
    totalDeleted: number;
    totalChanged: number;
  }>;

  // --- Cleanup ---

  /**
   * Cleanup temporary files created by Neurodoc.
   */
  cleanupTempFiles(): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Neurodoc');
