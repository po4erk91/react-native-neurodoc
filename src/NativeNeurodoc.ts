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
  | 'CLEANUP_FAILED';

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

  // --- Document Picker ---

  /**
   * Open system document picker to select a PDF file.
   */
  pickDocument(): Promise<{ pdfUrl: string }>;

  // --- Cleanup ---

  /**
   * Cleanup temporary files created by Neurodoc.
   */
  cleanupTempFiles(): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Neurodoc');
