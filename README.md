# react-native-neurodoc

A high-performance React Native TurboModule for comprehensive PDF operations on iOS and Android. Built with native Swift (PDFKit + Vision) and Kotlin (PDFBox + ML Kit) for maximum speed and reliability.

## Features

- **Metadata** - Extract page count, title, author, encryption status
- **OCR** - Recognize text from scanned pages with bounding boxes and confidence scores
- **Make Searchable** - Embed invisible text layer into scanned PDFs
- **Text Extraction** - Extract text with position, font info; auto-fallback to OCR
- **Merge / Split** - Combine multiple PDFs or split by page ranges
- **Page Operations** - Delete, reorder pages
- **Annotations** - Add/read/delete highlights, notes, freehand drawings
- **Forms (AcroForm)** - Read fields, fill values, create forms from scratch, flatten
- **Encryption** - Password-protect PDFs with AES; decrypt to remove protection
- **Watermarks** - Text and image watermarks with opacity, rotation, page targeting
- **Template Generation** - Create PDFs from declarative templates with data binding
- **Native PDF Viewer** - Scroll/page/grid modes, overlays, text selection, zoom
- **Document Picker** - System file picker with cross-platform support

## Installation

```sh
npm install react-native-neurodoc
cd ios && pod install
```

### Requirements

| Platform | Minimum Version |
|----------|----------------|
| iOS      | 16.0           |
| Android  | SDK 24         |

## Quick Start

```typescript
import { NeuroDoc } from 'react-native-neurodoc';

// Pick a PDF
const { pdfUrl } = await NeuroDoc.pickDocument();

// Get info
const meta = await NeuroDoc.getMetadata(pdfUrl);
console.log(`${meta.pageCount} pages, encrypted: ${meta.isEncrypted}`);

// Extract text from first page
const { textBlocks } = await NeuroDoc.extractText({
  pdfUrl,
  pageIndex: 0,
  mode: 'auto',
});
```

## API Reference

### Import

```typescript
import { NeuroDoc, isNeurodocError } from 'react-native-neurodoc';
```

All methods return Promises. All output PDFs are written to a platform temp directory; call `cleanupTempFiles()` when done.

---

### Metadata

```typescript
const meta = await NeuroDoc.getMetadata(pdfUrl);
// { pageCount, title, author, creationDate, fileSize, isEncrypted, isSigned }
```

---

### OCR

```typescript
// Recognize text on a single page
const { blocks } = await NeuroDoc.recognizePage({
  pdfUrl,
  pageIndex: 0,
  language: 'auto', // or 'en', 'uk', 'pl', etc.
});
// blocks: [{ text, boundingBox: { x, y, width, height }, confidence }]

// Make entire document searchable
const result = await NeuroDoc.makeSearchable({
  pdfUrl,
  language: 'auto',
  pageIndexes: [0, 1, 2], // optional, omit for all pages
});
// { pdfUrl, pagesProcessed }
```

---

### Text Extraction

```typescript
const { textBlocks, pageWidth, pageHeight, mode } = await NeuroDoc.extractText({
  pdfUrl,
  pageIndex: 0,
  mode: 'auto', // 'native' | 'ocr' | 'auto'
  language: 'auto',
});
// textBlocks: [{ text, boundingBox, fontSize, fontName, confidence }]
```

`auto` tries native extraction first, falls back to OCR if no text found.

---

### Page Operations

```typescript
// Merge
const merged = await NeuroDoc.merge({
  pdfUrls: [url1, url2, url3],
  fileName: 'combined',
});
// { pdfUrl, pageCount, fileSize }

// Split by ranges (0-based, inclusive)
const { pdfUrls } = await NeuroDoc.split({
  pdfUrl,
  ranges: [[0, 4], [5, 9]],
});

// Delete pages
const result = await NeuroDoc.deletePages({
  pdfUrl,
  pageIndexes: [2, 5],
});
// { pdfUrl, pageCount }

// Reorder pages
const result = await NeuroDoc.reorderPages({
  pdfUrl,
  order: [3, 0, 1, 2],
});
```

---

### Annotations

```typescript
// Add
await NeuroDoc.addAnnotations({
  pdfUrl,
  annotations: [
    {
      type: 'highlight',
      pageIndex: 0,
      rects: [{ x: 0.1, y: 0.2, width: 0.5, height: 0.03 }],
      color: '#FFFF00',
      opacity: 0.5,
    },
    {
      type: 'note',
      pageIndex: 0,
      x: 0.8, y: 0.1,
      text: 'Review this section',
    },
    {
      type: 'freehand',
      pageIndex: 1,
      points: [[0.1, 0.1], [0.2, 0.15], [0.3, 0.1]],
      color: '#FF0000',
      strokeWidth: 2,
    },
  ],
});

// Read all
const { annotations } = await NeuroDoc.getAnnotations(pdfUrl);
// [{ id, type, pageIndex, color, x, y, width, height, text }]

// Delete one
await NeuroDoc.deleteAnnotation({ pdfUrl, annotationId: annotations[0].id });
```

---

### Forms (AcroForm)

```typescript
// Read fields
const { fields } = await NeuroDoc.getFormFields(pdfUrl);
// [{ id, name, type, value, options, fontSize, fontName }]

// Fill
const { pdfUrl: filled } = await NeuroDoc.fillForm({
  pdfUrl,
  fields: [
    { id: 'name', value: 'John Doe', fontSize: 12 },
    { id: 'agree', value: 'true' },
  ],
  flattenAfterFill: false,
});

// Create form fields on existing PDF
const { pdfUrl: form } = await NeuroDoc.createFormFromPdf({
  pdfUrl,
  fields: [
    {
      name: 'full_name',
      pageIndex: 0,
      boundingBox: { x: 0.15, y: 0.25, width: 0.4, height: 0.035 },
      type: 'text',
      defaultValue: '',
      fontSize: 12,
    },
    {
      name: 'accepted',
      pageIndex: 0,
      boundingBox: { x: 0.15, y: 0.85, width: 0.03, height: 0.03 },
      type: 'checkbox',
    },
  ],
  removeOriginalText: true,
});
```

---

### Encryption

```typescript
// Encrypt
const { pdfUrl: encrypted } = await NeuroDoc.encrypt({
  pdfUrl,
  userPassword: 'open123',
  ownerPassword: 'admin456',
  allowPrinting: true,
  allowCopying: false,
});

// Decrypt (strips password completely)
const { pdfUrl: decrypted } = await NeuroDoc.decrypt({
  pdfUrl: encrypted,
  password: 'open123',
});
```

---

### Watermark

```typescript
await NeuroDoc.addWatermark({
  pdfUrl,
  text: 'CONFIDENTIAL',
  opacity: 0.3,
  angle: 45,
  fontSize: 48,
  color: '#FF0000',
  pageIndexes: [0, 1], // optional, omit for all
});

// Image watermark
await NeuroDoc.addWatermark({
  pdfUrl,
  imageUrl: 'file:///path/to/logo.png',
  opacity: 0.2,
});
```

---

### Template PDF Generation

Generate PDFs from declarative JSON templates with `{{placeholder}}` data binding.

```typescript
const result = await NeuroDoc.generateFromTemplate({
  templateJson: JSON.stringify({
    pageSize: { width: 595, height: 842 }, // A4
    margins: { top: 40, right: 40, bottom: 40, left: 40 },
    body: [
      { type: 'text', content: 'Hello {{name}}!', font: { size: 24, bold: true } },
      { type: 'line', marginBottom: 12 },
      {
        type: 'table',
        dataKey: 'items',
        columns: [
          { header: 'Item', key: 'description', width: 2 },
          { header: 'Price', key: 'price', width: 1, alignment: 'right' },
        ],
      },
      { type: 'spacer', height: 20 },
      { type: 'text', content: 'Total: {{total}}', font: { bold: true }, alignment: 'right' },
    ],
  }),
  dataJson: JSON.stringify({
    name: 'World',
    items: [
      { description: 'Widget', price: '$10' },
      { description: 'Gadget', price: '$25' },
    ],
    total: '$35',
  }),
  fileName: 'output',
});
// { pdfUrl, pageCount, fileSize }
```

#### Template Elements

| Type | Description |
|------|-------------|
| `text` | Text with font, alignment, `{{placeholders}}` |
| `image` | Image from file URL |
| `line` | Horizontal divider |
| `spacer` | Vertical space |
| `rect` | Rectangle with fill/border/corner radius |
| `columns` | Multi-column layout with nested elements |
| `table` | Data table bound to an array in data |
| `keyValue` | Label-value pairs (e.g., "Invoice #: 001") |

#### Template Types

All type definitions are exported for building custom templates:

```typescript
import type {
  TemplateDefinition,
  TemplateElement,
  TextElement,
  TableElement,
  TableColumn,
  ColumnsElement,
  KeyValueElement,
  ImageElement,
  LineElement,
  SpacerElement,
  RectElement,
  FontSpec,
  PageSize,
  Margins,
} from 'react-native-neurodoc';
```

---

### Native PDF Viewer

The library includes a native PDF viewer component with scroll/page/grid modes, text selection, overlays, and zoom.

```typescript
import {
  NativePdfViewerView,
  PdfViewerCommands,
  type PdfViewerViewType,
} from 'react-native-neurodoc';
```

#### Props

| Prop | Type | Description |
|------|------|-------------|
| `pdfUrl` | `string` | PDF file path or URL |
| `pageIndex` | `number` | Current page (0-based) |
| `displayMode` | `string` | `'scroll'` / `'single'` / `'grid'` |
| `showThumbnails` | `boolean` | Show thumbnail strip |
| `minZoom` / `maxZoom` | `number` | Zoom limits |
| `spacing` | `number` | Gap between pages |
| `showScrollIndicator` | `boolean` | Show scroll bar |
| `textOverlays` | `string` | JSON array of overlay objects |
| `enableOverlayTap` | `boolean` | Enable tap on overlays |
| `disableSelection` | `boolean` | Disable text selection |

#### Events

| Event | Payload |
|-------|---------|
| `onPageChanged` | `{ pageIndex, pageCount }` |
| `onDocumentLoaded` | `{ pageCount }` |
| `onDocumentLoadFailed` | `{ error }` |
| `onLongPress` | `{ pageIndex, x, y }` |
| `onDocumentChanged` | `{ pdfUrl, pageCount }` |
| `onOverlayTap` | `{ id, pageIndex }` |
| `onTap` | `{ pageIndex, x, y }` |
| `onOverlayMoved` | `{ id, x, y }` |
| `onOverlayResized` | `{ id, x, y, width, height }` |
| `onTextSelected` | `{ text, pageIndex, x, y, width, height }` |
| `onAddFieldFromSelection` | `{ text, pageIndex, x, y, width, height }` |

#### Commands

```typescript
const ref = useRef<PdfViewerViewType>(null);

PdfViewerCommands.goToPage(ref.current!, 5);
PdfViewerCommands.zoomTo(ref.current!, 2.0);
```

---

### Document Picker

```typescript
const { pdfUrl } = await NeuroDoc.pickDocument();
```

Opens the system file picker. Rejects with `PICKER_CANCELLED` if dismissed.

---

### Cleanup

```typescript
await NeuroDoc.cleanupTempFiles();
```

Removes all temp files created by Neurodoc operations.

---

## Error Handling

```typescript
import { NeuroDoc, isNeurodocError } from 'react-native-neurodoc';

try {
  await NeuroDoc.getMetadata(pdfUrl);
} catch (error) {
  if (isNeurodocError(error)) {
    switch (error.code) {
      case 'PDF_LOAD_FAILED':
        // handle...
        break;
      case 'ENCRYPTION_FAILED':
        // handle...
        break;
    }
  }
}
```

### Error Codes

| Code | When |
|------|------|
| `INVALID_INPUT` | Bad parameters |
| `PDF_LOAD_FAILED` | Cannot open PDF |
| `OCR_FAILED` | Text recognition error |
| `MERGE_FAILED` | Merge operation error |
| `SPLIT_FAILED` | Split operation error |
| `PAGE_OPERATION_FAILED` | Delete/reorder error |
| `ANNOTATION_FAILED` | Annotation error |
| `FORM_FAILED` | Form operation error |
| `ENCRYPTION_FAILED` | Encrypt/decrypt error |
| `WATERMARK_FAILED` | Watermark error |
| `TEMPLATE_FAILED` | Template generation error |
| `TEXT_EXTRACTION_FAILED` | Text extraction error |
| `CLEANUP_FAILED` | Temp cleanup error |

---

## Platform Details

|  | iOS | Android |
|--|-----|---------|
| PDF Engine | PDFKit | PDFBox |
| OCR | Vision framework | Google ML Kit |
| Encryption | AES (Core Graphics) | AES-256 (PDFBox) |
| Concurrency | GCD | Coroutines |
| Min Version | iOS 16.0 | SDK 24 |

## Coordinates

All bounding boxes and positions use **normalized coordinates** (0.0 - 1.0) relative to page dimensions, with **top-left origin**. This ensures consistent behavior across platforms and page sizes.

## License

MIT
