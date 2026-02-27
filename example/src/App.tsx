import { useState, useRef, useCallback } from 'react';
import {
  Text,
  View,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Alert,
  ActivityIndicator,
  Share,
  Modal,
  TextInput,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { NeuroDoc } from 'react-native-neurodoc';
import { PdfViewer, type PdfViewerRef } from './PdfViewer';
import { PdfFieldMarker, type PdfFieldMarkerRef } from './PdfFieldMarker';
import { PdfFormFiller, type PdfFormFillerRef } from './PdfFormFiller';
import { generatePdf } from './templates';
import {
  randomInvoiceData,
  randomReceiptData,
  randomContractData,
  randomLetterData,
} from './randomData';

type Screen =
  | 'home'
  | 'viewer'
  | 'metadata'
  | 'annotations'
  | 'bookmarks'
  | 'formFields'
  | 'fieldMarker'
  | 'formFiller'
  | 'compare';

function AppContent() {
  const [screen, setScreen] = useState<Screen>('home');
  const [pdfUrl, setPdfUrl] = useState('');
  const [metadata, setMetadata] = useState<Record<string, unknown> | null>(
    null
  );
  const [annotations, setAnnotations] = useState<any[]>([]);
  const [bookmarks, setBookmarks] = useState<any[]>([]);
  const [formFields, setFormFields] = useState<any[]>([]);
  const [pageInfo, setPageInfo] = useState({ page: 0, total: 0 });
  const [viewMode, setViewMode] = useState<'scroll' | 'single' | 'grid'>(
    'scroll'
  );
  const [loading, setLoading] = useState(false);
  const [templateUrl, setTemplateUrl] = useState('');
  const [passwordPrompt, setPasswordPrompt] = useState(false);
  const [passwordInput, setPasswordInput] = useState('');
  const [diffResult, setDiffResult] = useState<{
    sourcePdfUrl: string;
    targetPdfUrl: string;
    totalAdded: number;
    totalDeleted: number;
    totalChanged: number;
  } | null>(null);
  const [compareTab, setCompareTab] = useState<'source' | 'target'>('target');
  const viewerRef = useRef<PdfViewerRef>(null);
  const fieldMarkerRef = useRef<PdfFieldMarkerRef>(null);
  const formFillerRef = useRef<PdfFormFillerRef>(null);

  // --- Pick PDF ---
  const handlePickDocument = useCallback(async () => {
    try {
      const result = await NeuroDoc.pickDocument();
      setPdfUrl(result.pdfUrl);
    } catch (e: any) {
      if (e.code !== 'PICKER_CANCELLED') {
        Alert.alert('Error', e.message);
      }
    }
  }, []);

  // --- Metadata ---
  const handleGetMetadata = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const meta = await NeuroDoc.getMetadata(pdfUrl);
      setMetadata(meta as unknown as Record<string, unknown>);
      setScreen('metadata');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- OCR ---
  const handleOcr = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.recognizePage({
        pdfUrl,
        pageIndex: pageInfo.page,
        language: 'auto',
      });
      const blockCount = result.blocks.length;
      const text = result.blocks
        .map((b: { text: string }) => b.text)
        .join('\n');
      Alert.alert(
        `OCR Result (${blockCount} blocks)`,
        text.substring(0, 500) + (text.length > 500 ? '...' : '')
      );
    } catch (e: any) {
      Alert.alert('OCR Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl, pageInfo.page]);

  // --- Make Searchable ---
  const handleMakeSearchable = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.makeSearchable({
        pdfUrl,
        language: 'auto',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Done',
        `Searchable PDF created (${result.pagesProcessed} pages processed)`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Merge ---
  const handleMerge = useCallback(async () => {
    if (!pdfUrl) return;
    try {
      const second = await NeuroDoc.pickDocument();
      setLoading(true);
      const result = await NeuroDoc.merge({
        pdfUrls: [pdfUrl, second.pdfUrl],
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Merged',
        `${result.pageCount} pages, ${(result.fileSize / 1024).toFixed(0)} KB`
      );
    } catch (e: any) {
      if (e.code !== 'PICKER_CANCELLED') {
        Alert.alert('Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Split ---
  const handleSplit = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const meta = await NeuroDoc.getMetadata(pdfUrl);
      if (meta.pageCount < 2) {
        Alert.alert('Cannot split', 'PDF has only 1 page');
        return;
      }
      const mid = Math.floor(meta.pageCount / 2);
      const result = await NeuroDoc.split({
        pdfUrl,
        ranges: [
          [0, mid - 1],
          [mid, meta.pageCount - 1],
        ],
      });
      setPdfUrl(result.pdfUrls[0]!);
      Alert.alert(
        'Split',
        `Created ${result.pdfUrls.length} PDFs. Showing first part.`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Delete first page ---
  const handleDeletePage = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.deletePages({
        pdfUrl,
        pageIndexes: [0],
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Done',
        `First page deleted. ${result.pageCount} pages left.`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Reverse page order ---
  const handleReorderPages = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const meta = await NeuroDoc.getMetadata(pdfUrl);
      const reversed = Array.from(
        { length: meta.pageCount },
        (_, i) => meta.pageCount - 1 - i
      );
      const result = await NeuroDoc.reorderPages({
        pdfUrl,
        order: reversed,
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', 'Page order reversed');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Get Annotations ---
  const handleGetAnnotations = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.getAnnotations(pdfUrl);
      setAnnotations(result.annotations);
      setScreen('annotations');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Add highlight annotation ---
  const handleAddHighlight = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.addAnnotations({
        pdfUrl,
        annotations: [
          {
            type: 'highlight',
            pageIndex: 0,
            rects: [{ x: 0.1, y: 0.1, width: 0.8, height: 0.05 }],
            color: '#FFFF00',
            opacity: 0.5,
          },
        ],
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', 'Highlight added to page 1');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Add note annotation ---
  const handleAddNote = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.addAnnotations({
        pdfUrl,
        annotations: [
          {
            type: 'note',
            pageIndex: 0,
            x: 0.5,
            y: 0.5,
            text: 'Sample note from NeuroDoc',
            color: '#FF6600',
          },
        ],
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', 'Note added to page 1');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Get Bookmarks ---
  const handleGetBookmarks = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.getBookmarks(pdfUrl);
      setBookmarks(result.bookmarks);
      setScreen('bookmarks');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Add Bookmark ---
  const handleAddBookmark = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.addBookmarks({
        pdfUrl,
        bookmarks: [
          { title: 'Bookmark Page 1', pageIndex: 0 },
        ],
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', 'Bookmark added to page 1');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Remove All Bookmarks ---
  const handleRemoveBookmarks = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const { bookmarks: bms } = await NeuroDoc.getBookmarks(pdfUrl);
      if (bms.length === 0) {
        Alert.alert('No bookmarks', 'This PDF has no bookmarks to remove');
        return;
      }
      const allIndexes = bms.map((_: any, i: number) => i);
      const result = await NeuroDoc.removeBookmarks({
        pdfUrl,
        indexes: allIndexes,
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', `Removed ${bms.length} bookmark(s)`);
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Form Fields ---
  const handleGetFormFields = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.getFormFields(pdfUrl);
      setFormFields(result.fields);
      setScreen('formFields');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Fill Form ---
  const handleFillForm = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const { fields } = await NeuroDoc.getFormFields(pdfUrl);
      if (fields.length === 0) {
        Alert.alert('No fields', 'This PDF has no form fields to fill');
        return;
      }
      const filledFields = fields.map((f) => ({
        id: f.id,
        value:
          f.type === 'checkbox'
            ? 'true'
            : f.type === 'dropdown' && f.options.length > 0
            ? f.options[0]!
            : `Test ${f.name}`,
      }));
      const result = await NeuroDoc.fillForm({
        pdfUrl,
        fields: filledFields,
        flattenAfterFill: false,
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', `Filled ${filledFields.length} fields`);
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Encrypt ---
  const handleEncrypt = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.encrypt({
        pdfUrl,
        userPassword: '1234',
        ownerPassword: 'admin',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Encrypted', 'Password: 1234');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Decrypt ---
  const handleDecrypt = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.decrypt({
        pdfUrl,
        password: '1234',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Decrypted', 'PDF unlocked');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Watermark ---
  const handleAddWatermark = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.addWatermark({
        pdfUrl,
        text: 'CONFIDENTIAL',
        opacity: 0.2,
        angle: 45,
        fontSize: 60,
        color: '#FF0000',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Done', 'Watermark added');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Redact ---
  const handleRedact = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.redact({
        pdfUrl,
        redactions: [
          {
            pageIndex: 0,
            rects: [
              { x: 0.1, y: 0.1, width: 0.8, height: 0.05 },
              { x: 0.1, y: 0.3, width: 0.6, height: 0.04 },
            ],
            color: '#000000',
          },
        ],
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert('Redacted', `${result.pagesRedacted} page(s) redacted`);
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Extract Text ---
  const handleExtractText = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.extractText({
        pdfUrl,
        pageIndex: 0,
      });
      const blockCount = result.textBlocks.length;
      const text = result.textBlocks
        .map((b: { text: string }) => b.text)
        .join(' ');
      Alert.alert(
        `Text Extracted (${result.mode}, ${blockCount} blocks)`,
        text.substring(0, 500) + (text.length > 500 ? '...' : '')
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Edit Content (inline text replacement) ---
  const handleEditContent = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.extractText({
        pdfUrl,
        pageIndex: 0,
      });

      if (result.textBlocks.length === 0) {
        Alert.alert('No text', 'No text blocks found on page 1');
        return;
      }

      // Filter: only real text blocks (2+ chars, reasonable size < 5% page height)
      const textBlocks = result.textBlocks.filter(
        (b: any) =>
          b.text.trim().length >= 2 &&
          b.boundingBox.height < 0.05 &&
          b.boundingBox.width < 0.5
      );

      if (textBlocks.length === 0) {
        Alert.alert('No suitable text', 'No small text blocks found on page 1');
        return;
      }

      // Pick up to 3 blocks from the middle of the page
      const sorted = [...textBlocks].sort(
        (a: any, b: any) => a.boundingBox.y - b.boundingBox.y
      );
      const midStart = Math.floor(sorted.length / 3);
      const chosen = sorted.slice(midStart, midStart + 3);

      const preview = chosen
        .map((b: any, i: number) => `${i + 1}. "${b.text}" → "Edited #${i + 1}"`)
        .join('\n');

      Alert.alert('Edit Content', `Will replace:\n${preview}`, [
        { text: 'Cancel', style: 'cancel', onPress: () => setLoading(false) },
        {
          text: 'Apply',
          onPress: async () => {
            try {
              const edits = chosen.map((block: any, i: number) => ({
                pageIndex: 0,
                boundingBox: block.boundingBox,
                newText: `Edited #${i + 1}`,
                fontSize: block.fontSize,
              }));

              const edited = await NeuroDoc.editContent({ pdfUrl, edits });
              setPdfUrl(edited.pdfUrl);
              Alert.alert(
                'Content Edited',
                `${edited.editsApplied} text block(s) replaced`
              );
            } catch (e: any) {
              Alert.alert('Error', e.message);
            } finally {
              setLoading(false);
            }
          },
        },
      ]);
    } catch (e: any) {
      Alert.alert('Error', e.message);
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Create Form From PDF ---
  const handleCreateFormFromPdf = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      // First extract text to find positions
      const extracted = await NeuroDoc.extractText({
        pdfUrl,
        pageIndex: 0,
      });

      if (extracted.textBlocks.length < 3) {
        Alert.alert(
          'Not enough text',
          'Need at least 3 text blocks to demo form creation'
        );
        return;
      }

      // Pick first 3 text blocks as "dynamic" fields
      const fields = extracted.textBlocks
        .slice(0, 3)
        .map((block: any, i: number) => ({
          name: `field_${i}`,
          pageIndex: 0,
          boundingBox: block.boundingBox,
          type: 'text' as const,
          defaultValue: block.text,
          fontSize: block.fontSize,
        }));

      const result = await NeuroDoc.createFormFromPdf({
        pdfUrl,
        fields,
        removeOriginalText: true,
      });

      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Form Created',
        `Created fillable PDF with ${fields.length} fields. Use "Get Form Fields" to verify.`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- DOCX → PDF ---
  const handleConvertDocxToPdf = useCallback(async () => {
    setLoading(true);
    try {
      const picked = await NeuroDoc.pickFile([
        'org.openxmlformats.wordprocessingml.document',
      ]);
      const result = await NeuroDoc.convertDocxToPdf({
        inputPath: picked.fileUrl,
      });
      setPdfUrl(result.pdfUrl);
      const msg = `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`;
      const warnings = result.warnings.length > 0
        ? `\nWarnings: ${result.warnings.join(', ')}`
        : '';
      Alert.alert('DOCX → PDF', msg + warnings);
    } catch (e: any) {
      if (e.code !== 'PICKER_CANCELLED') {
        Alert.alert('Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // --- PDF → DOCX ---
  const handleConvertPdfToDocx = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.convertPdfToDocx({
        inputPath: pdfUrl,
      });
      Alert.alert(
        'PDF → DOCX',
        `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB, mode: ${result.mode}`
      );
      await Share.share({ url: result.docxUrl });
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Compare PDFs ---
  const handleComparePdfs = useCallback(async () => {
    setLoading(true);
    try {
      const pick1 = await NeuroDoc.pickDocument();
      const pick2 = await NeuroDoc.pickDocument();

      const result = await NeuroDoc.comparePdfs({
        pdfUrl1: pick1.pdfUrl,
        pdfUrl2: pick2.pdfUrl,
      });
      setDiffResult(result);
      setCompareTab('target');
      setScreen('compare');
    } catch (e: any) {
      if (e.code !== 'PICKER_CANCELLED') {
        Alert.alert('Compare Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  // --- Cleanup ---
  const handleCleanup = useCallback(async () => {
    try {
      await NeuroDoc.cleanupTempFiles();
      setPdfUrl('');
      Alert.alert('Done', 'Temp files cleaned up');
    } catch (e: any) {
      Alert.alert('Error', e.message);
    }
  }, []);

  // --- Generate Invoice ---
  const handleGenerateInvoice = useCallback(async () => {
    setLoading(true);
    try {
      const { data, style } = randomInvoiceData();
      const result = await generatePdf({
        template: 'invoice',
        data,
        style,
        fileName: 'invoice-demo',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Invoice Generated',
        `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  // --- Generate Receipt ---
  const handleGenerateReceipt = useCallback(async () => {
    setLoading(true);
    try {
      const { data, style } = randomReceiptData();
      const result = await generatePdf({
        template: 'receipt',
        data,
        style,
        fileName: 'receipt-demo',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Receipt Generated',
        `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  // --- Generate Contract ---
  const handleGenerateContract = useCallback(async () => {
    setLoading(true);
    try {
      const { data, style } = randomContractData();
      const result = await generatePdf({
        template: 'contract',
        data,
        style,
        fileName: 'contract-demo',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Contract Generated',
        `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  // --- Generate Letter ---
  const handleGenerateLetter = useCallback(async () => {
    setLoading(true);
    try {
      const { data, style } = randomLetterData();
      const result = await generatePdf({
        template: 'letter',
        data,
        style,
        fileName: 'letter-demo',
      });
      setPdfUrl(result.pdfUrl);
      Alert.alert(
        'Letter Generated',
        `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`
      );
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, []);

  // --- Save To ---
  const handleSaveTo = useCallback(async () => {
    if (!pdfUrl) return;
    setLoading(true);
    try {
      const result = await NeuroDoc.saveTo(pdfUrl, 'document');
      Alert.alert('Saved', `File saved to:\n${result.savedPath}`);
    } catch (e: any) {
      if (e.code !== 'SAVE_FAILED' || !e.message?.includes('cancel')) {
        Alert.alert('Save Error', e.message);
      }
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

  // --- Share ---
  const handleShare = useCallback(async () => {
    if (!pdfUrl) return;
    try {
      await Share.share({ url: pdfUrl });
    } catch (e: any) {
      Alert.alert('Error', e.message);
    }
  }, [pdfUrl]);

  // ============ SCREENS ============

  // --- Compare ---
  if (screen === 'compare' && diffResult) {
    const activePdfUrl =
      compareTab === 'target' ? diffResult.targetPdfUrl : diffResult.sourcePdfUrl;
    const { totalAdded, totalDeleted, totalChanged } = diffResult;

    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity
            onPress={() => {
              setScreen('home');
              setDiffResult(null);
            }}
          >
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>Compare PDFs</Text>
          {/* Tab switcher */}
          <View style={styles.modeToggle}>
            <TouchableOpacity
              style={[styles.modeBtn, compareTab === 'source' && styles.modeBtnActive]}
              onPress={() => setCompareTab('source')}
            >
              <Text style={[styles.modeBtnText, compareTab === 'source' && styles.modeBtnTextActive]}>
                v1
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.modeBtn, compareTab === 'target' && styles.modeBtnActive]}
              onPress={() => setCompareTab('target')}
            >
              <Text style={[styles.modeBtnText, compareTab === 'target' && styles.modeBtnTextActive]}>
                v2
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Stats bar */}
        <View style={styles.diffStatsBar}>
          <View style={styles.diffStat}>
            <View style={[styles.diffDot, { backgroundColor: '#00C853' }]} />
            <Text style={styles.diffStatText}>
              +{totalAdded} added
            </Text>
          </View>
          <View style={styles.diffStat}>
            <View style={[styles.diffDot, { backgroundColor: '#FF3B30' }]} />
            <Text style={styles.diffStatText}>
              -{totalDeleted} deleted
            </Text>
          </View>
          <View style={styles.diffStat}>
            <View style={[styles.diffDot, { backgroundColor: '#FF9500' }]} />
            <Text style={styles.diffStatText}>
              ~{totalChanged} changed
            </Text>
          </View>
          <Text style={styles.diffTabHint}>
            {compareTab === 'target' ? 'Showing v2 (new)' : 'Showing v1 (old)'}
          </Text>
        </View>

        {/* Legend */}
        <View style={styles.diffLegend}>
          {compareTab === 'target' ? (
            <>
              <View style={[styles.legendSwatch, { backgroundColor: '#00CC0059' }]} />
              <Text style={styles.legendText}>Green = added</Text>
              <View style={[styles.legendSwatch, { backgroundColor: '#FFAA0059', marginLeft: 12 }]} />
              <Text style={styles.legendText}>Yellow = changed</Text>
            </>
          ) : (
            <>
              <View style={[styles.legendSwatch, { backgroundColor: '#FF444459' }]} />
              <Text style={styles.legendText}>Red = deleted</Text>
              <View style={[styles.legendSwatch, { backgroundColor: '#FFAA0059', marginLeft: 12 }]} />
              <Text style={styles.legendText}>Yellow = changed</Text>
            </>
          )}
        </View>

        <PdfViewer
          key={activePdfUrl}
          pdfUrl={activePdfUrl}
          displayMode="scroll"
          spacing={8}
          minZoom={1}
          maxZoom={5}
          onPageChanged={(page, total) => setPageInfo({ page, total })}
          onDocumentLoaded={(total) => setPageInfo({ page: 0, total })}
          onDocumentLoadFailed={(error) => Alert.alert('Load Error', error)}
          style={styles.flex}
        />
      </SafeAreaView>
    );
  }

  // --- Field Marker ---
  if (screen === 'fieldMarker' && pdfUrl) {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>Field Marker</Text>
          <TouchableOpacity
            onPress={() => {
              if (templateUrl) {
                setPdfUrl(templateUrl);
                setTemplateUrl('');
                setScreen('formFiller');
              }
            }}
            disabled={!templateUrl}
          >
            <Text
              style={[styles.toolbarBtn, !templateUrl && { color: '#ccc' }]}
            >
              Fill
            </Text>
          </TouchableOpacity>
        </View>
        <PdfFieldMarker
          ref={fieldMarkerRef}
          pdfUrl={pdfUrl}
          onFieldsChanged={() => {
            // Optional: track selection changes
          }}
          onCreateTemplate={(url) => {
            setTemplateUrl(url);
            Alert.alert(
              'Template Created',
              'Tap "View" in top-right to open it, then use "Fill Form" to fill fields.'
            );
          }}
          style={styles.flex}
        />
        {loading && (
          <View style={styles.loadingOverlay}>
            <ActivityIndicator size="large" color="#fff" />
          </View>
        )}
      </SafeAreaView>
    );
  }

  // --- Form Filler ---
  if (screen === 'formFiller' && pdfUrl) {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>Fill Template</Text>
          <TouchableOpacity onPress={handleShare}>
            <Text style={styles.toolbarBtn}>Share</Text>
          </TouchableOpacity>
        </View>
        <PdfFormFiller
          ref={formFillerRef}
          pdfUrl={pdfUrl}
          onFormFilled={() => {
            // Preview updated — no need to change pdfUrl (template stays the same)
          }}
          onSaved={(savedUrl) => {
            setPdfUrl(savedUrl);
          }}
          onError={(error) => Alert.alert('Error', error)}
          style={styles.flex}
        />
        {loading && (
          <View style={styles.loadingOverlay}>
            <ActivityIndicator size="large" color="#fff" />
          </View>
        )}
      </SafeAreaView>
    );
  }

  // --- Viewer ---
  if (screen === 'viewer' && pdfUrl) {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>
            {pageInfo.page + 1} / {pageInfo.total}
          </Text>
          <View style={styles.modeToggle}>
            <TouchableOpacity
              style={[
                styles.modeBtn,
                viewMode === 'scroll' && styles.modeBtnActive,
              ]}
              onPress={() => setViewMode('scroll')}
            >
              <Text
                style={[
                  styles.modeBtnText,
                  viewMode === 'scroll' && styles.modeBtnTextActive,
                ]}
              >
                Scroll
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[
                styles.modeBtn,
                viewMode === 'single' && styles.modeBtnActive,
              ]}
              onPress={() => setViewMode('single')}
            >
              <Text
                style={[
                  styles.modeBtnText,
                  viewMode === 'single' && styles.modeBtnTextActive,
                ]}
              >
                Page
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[
                styles.modeBtn,
                viewMode === 'grid' && styles.modeBtnActive,
              ]}
              onPress={() => setViewMode('grid')}
            >
              <Text
                style={[
                  styles.modeBtnText,
                  viewMode === 'grid' && styles.modeBtnTextActive,
                ]}
              >
                Grid
              </Text>
            </TouchableOpacity>
          </View>
        </View>

        <PdfViewer
          ref={viewerRef}
          pdfUrl={pdfUrl}
          spacing={8}
          minZoom={1}
          maxZoom={5}
          displayMode={viewMode}
          showThumbnails={true}
          onPageChanged={(page, total) => setPageInfo({ page, total })}
          onDocumentLoaded={(total) => setPageInfo({ page: 0, total })}
          onDocumentLoadFailed={(error) => {
            const isPasswordError =
              /password|encrypt|protected|security/i.test(error);
            if (isPasswordError) {
              setPasswordInput('');
              setPasswordPrompt(true);
            } else {
              Alert.alert('Load Error', error);
            }
          }}
          onDocumentChanged={(newPdfUrl, newPageCount) => {
            setPdfUrl(newPdfUrl);
            setPageInfo((prev) => ({ ...prev, total: newPageCount }));
          }}
          onLongPress={(page, x, y) =>
            Alert.alert(
              'Long Press',
              `Page ${page}, x=${x.toFixed(2)}, y=${y.toFixed(2)}`
            )
          }
          style={styles.flex}
        />

        {loading && (
          <View style={styles.loadingOverlay}>
            <ActivityIndicator size="large" color="#fff" />
          </View>
        )}

        <Modal
          visible={passwordPrompt}
          transparent
          animationType="fade"
          onRequestClose={() => setPasswordPrompt(false)}
        >
          <View style={styles.modalOverlay}>
            <View style={styles.modalCard}>
              <Text style={styles.modalTitle}>Password Required</Text>
              <Text style={styles.modalSubtitle}>
                This PDF is encrypted. Enter password to open.
              </Text>
              <TextInput
                style={styles.modalInput}
                placeholder="Password"
                secureTextEntry
                autoFocus
                value={passwordInput}
                onChangeText={setPasswordInput}
                onSubmitEditing={async () => {
                  if (!passwordInput) return;
                  setPasswordPrompt(false);
                  setLoading(true);
                  try {
                    const result = await NeuroDoc.decrypt({
                      pdfUrl,
                      password: passwordInput,
                    });
                    setPdfUrl(result.pdfUrl);
                  } catch (e: any) {
                    Alert.alert('Decrypt Error', e.message);
                  } finally {
                    setLoading(false);
                  }
                }}
              />
              <View style={styles.modalButtons}>
                <TouchableOpacity
                  style={styles.modalBtnCancel}
                  onPress={() => {
                    setPasswordPrompt(false);
                    setScreen('home');
                  }}
                >
                  <Text style={styles.modalBtnCancelText}>Cancel</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={styles.modalBtnOk}
                  onPress={async () => {
                    if (!passwordInput) return;
                    setPasswordPrompt(false);
                    setLoading(true);
                    try {
                      const result = await NeuroDoc.decrypt({
                        pdfUrl,
                        password: passwordInput,
                      });
                      setPdfUrl(result.pdfUrl);
                    } catch (e: any) {
                      Alert.alert('Decrypt Error', e.message);
                    } finally {
                      setLoading(false);
                    }
                  }}
                >
                  <Text style={styles.modalBtnOkText}>Unlock</Text>
                </TouchableOpacity>
              </View>
            </View>
          </View>
        </Modal>
      </SafeAreaView>
    );
  }

  // --- Metadata ---
  if (screen === 'metadata' && metadata) {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>Metadata</Text>
          <View style={{ width: 40 }} />
        </View>
        <ScrollView style={styles.listContainer}>
          {Object.entries(metadata).map(([key, value]) => (
            <View key={key} style={styles.listRow}>
              <Text style={styles.listKey}>{key}</Text>
              <Text style={styles.listValue}>{String(value)}</Text>
            </View>
          ))}
        </ScrollView>
      </SafeAreaView>
    );
  }

  // --- Annotations list ---
  if (screen === 'annotations') {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>
            Annotations ({annotations.length})
          </Text>
          <View style={{ width: 40 }} />
        </View>
        <ScrollView style={styles.listContainer}>
          {annotations.length === 0 ? (
            <Text style={styles.emptyText}>No annotations found</Text>
          ) : (
            annotations.map((a, i) => (
              <View key={a.id || i} style={styles.listRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.listKey}>
                    {a.type} (page {a.pageIndex + 1})
                  </Text>
                  {a.text ? (
                    <Text style={styles.listValue}>{a.text}</Text>
                  ) : null}
                  <Text style={styles.listValue}>
                    color: {a.color} | ({a.x?.toFixed(2)}, {a.y?.toFixed(2)})
                  </Text>
                </View>
              </View>
            ))
          )}
        </ScrollView>
      </SafeAreaView>
    );
  }

  // --- Bookmarks list ---
  if (screen === 'bookmarks') {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>
            Bookmarks ({bookmarks.length})
          </Text>
          <View style={{ width: 40 }} />
        </View>
        <ScrollView style={styles.listContainer}>
          {bookmarks.length === 0 ? (
            <Text style={styles.emptyText}>No bookmarks found</Text>
          ) : (
            bookmarks.map((b, i) => (
              <View key={i} style={styles.listRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.listKey}>
                    {'  '.repeat(b.level)}{b.title}
                  </Text>
                  <Text style={styles.listValue}>
                    Page {b.pageIndex + 1}
                    {b.children > 0 ? ` | ${b.children} child(ren)` : ''}
                  </Text>
                </View>
              </View>
            ))
          )}
        </ScrollView>
      </SafeAreaView>
    );
  }

  // --- Form Fields list ---
  if (screen === 'formFields') {
    return (
      <SafeAreaView style={styles.flex}>
        <View style={styles.toolbar}>
          <TouchableOpacity onPress={() => setScreen('home')}>
            <Text style={styles.toolbarBtn}>Back</Text>
          </TouchableOpacity>
          <Text style={styles.toolbarTitle}>
            Form Fields ({formFields.length})
          </Text>
          <View style={{ width: 40 }} />
        </View>
        <ScrollView style={styles.listContainer}>
          {formFields.length === 0 ? (
            <Text style={styles.emptyText}>No form fields found</Text>
          ) : (
            formFields.map((f, i) => (
              <View key={f.id || i} style={styles.listRow}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.listKey}>
                    {f.name} ({f.type})
                  </Text>
                  <Text style={styles.listValue}>
                    value: {f.value || '(empty)'}
                  </Text>
                  {f.options?.length > 0 && (
                    <Text style={styles.listValue}>
                      options: {f.options.join(', ')}
                    </Text>
                  )}
                </View>
              </View>
            ))
          )}
        </ScrollView>
      </SafeAreaView>
    );
  }

  // ============ HOME ============

  const pdfFileName = pdfUrl ? pdfUrl.split('/').pop() : null;

  return (
    <SafeAreaView style={styles.flex}>
      <ScrollView style={styles.flex} contentContainerStyle={styles.container}>
        <Text style={styles.title}>NeuroDoc Demo</Text>
        <Text style={styles.subtitle}>
          PDF Viewer, OCR & Document Operations
        </Text>

        {/* Pick PDF */}
        <TouchableOpacity
          style={styles.pickButton}
          onPress={handlePickDocument}
        >
          <Text style={styles.pickButtonText}>
            {pdfFileName ?? 'Pick PDF Document'}
          </Text>
          {pdfFileName && (
            <Text style={styles.pickButtonHint}>Tap to change</Text>
          )}
        </TouchableOpacity>

        {/* Viewer & Info */}
        <Text style={styles.sectionTitle}>View & Info</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Open Viewer"
            onPress={() => setScreen('viewer')}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Metadata"
            onPress={handleGetMetadata}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Save"
            onPress={handleSaveTo}
            disabled={!pdfUrl}
            color="#34C759"
          />
          <ActionButton
            title="Share"
            onPress={handleShare}
            disabled={!pdfUrl}
          />
        </View>

        {/* OCR */}
        <Text style={styles.sectionTitle}>OCR</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="OCR Page 1"
            onPress={handleOcr}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Make Searchable"
            onPress={handleMakeSearchable}
            disabled={!pdfUrl}
          />
        </View>

        {/* Page Operations */}
        <Text style={styles.sectionTitle}>Page Operations</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Merge with..."
            onPress={handleMerge}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Split in Half"
            onPress={handleSplit}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Delete Page 1"
            onPress={handleDeletePage}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Reverse Pages"
            onPress={handleReorderPages}
            disabled={!pdfUrl}
          />
        </View>

        {/* Annotations */}
        <Text style={styles.sectionTitle}>Annotations</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="List Annotations"
            onPress={handleGetAnnotations}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Add Highlight"
            onPress={handleAddHighlight}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Add Note"
            onPress={handleAddNote}
            disabled={!pdfUrl}
          />
        </View>

        {/* Bookmarks */}
        <Text style={styles.sectionTitle}>Bookmarks</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Get Bookmarks"
            onPress={handleGetBookmarks}
            disabled={!pdfUrl}
            color="#16A085"
          />
          <ActionButton
            title="Add Bookmark"
            onPress={handleAddBookmark}
            disabled={!pdfUrl}
            color="#16A085"
          />
          <ActionButton
            title="Remove All"
            onPress={handleRemoveBookmarks}
            disabled={!pdfUrl}
            color="#16A085"
          />
        </View>

        {/* Text Extraction & Content Editing */}
        <Text style={styles.sectionTitle}>Text & Content</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Extract Text"
            onPress={handleExtractText}
            disabled={!pdfUrl}
            color="#8E44AD"
          />
          <ActionButton
            title="Edit Content"
            onPress={handleEditContent}
            disabled={!pdfUrl}
            color="#D35400"
          />
          <ActionButton
            title="Create Form from PDF"
            onPress={handleCreateFormFromPdf}
            disabled={!pdfUrl}
            color="#27AE60"
          />
          <ActionButton
            title="Field Marker"
            onPress={() => setScreen('fieldMarker')}
            disabled={!pdfUrl}
            color="#E67E22"
          />
        </View>

        {/* Forms */}
        <Text style={styles.sectionTitle}>Forms</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Get Form Fields"
            onPress={handleGetFormFields}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Fill Form (auto)"
            onPress={handleFillForm}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Fill Template"
            onPress={() => setScreen('formFiller')}
            disabled={!pdfUrl}
            color="#E67E22"
          />
        </View>

        {/* Encryption */}
        <Text style={styles.sectionTitle}>Encryption</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Encrypt (pw: 1234)"
            onPress={handleEncrypt}
            disabled={!pdfUrl}
          />
          <ActionButton
            title="Decrypt (pw: 1234)"
            onPress={handleDecrypt}
            disabled={!pdfUrl}
          />
        </View>

        {/* Watermark */}
        <Text style={styles.sectionTitle}>Watermark</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Add Watermark"
            onPress={handleAddWatermark}
            disabled={!pdfUrl}
          />
        </View>

        {/* Redaction */}
        <Text style={styles.sectionTitle}>Redaction</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Redact Page 1"
            onPress={handleRedact}
            disabled={!pdfUrl}
            color="#1C1C1E"
          />
        </View>

        {/* Document Conversion */}
        <Text style={styles.sectionTitle}>Document Conversion</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="DOCX → PDF"
            onPress={handleConvertDocxToPdf}
            color="#2980B9"
          />
          <ActionButton
            title="PDF → DOCX"
            onPress={handleConvertPdfToDocx}
            disabled={!pdfUrl}
            color="#2980B9"
          />
        </View>

        {/* Document Comparison */}
        <Text style={styles.sectionTitle}>Document Comparison</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Compare PDFs..."
            onPress={handleComparePdfs}
            color="#5856D6"
          />
        </View>

        {/* Templates */}
        <Text style={styles.sectionTitle}>Templates</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Generate Invoice"
            onPress={handleGenerateInvoice}
            color="#E94560"
          />
          <ActionButton
            title="Generate Receipt"
            onPress={handleGenerateReceipt}
            color="#6B4423"
          />
          <ActionButton
            title="Generate Contract"
            onPress={handleGenerateContract}
            color="#2C3E50"
          />
          <ActionButton
            title="Generate Letter"
            onPress={handleGenerateLetter}
            color="#1B4F72"
          />
        </View>

        {/* Cleanup */}
        <View style={styles.cleanupSection}>
          <ActionButton
            title="Cleanup Temp Files"
            onPress={handleCleanup}
            color="#FF3B30"
          />
        </View>

        {loading && <ActivityIndicator style={styles.loader} size="large" />}
      </ScrollView>
    </SafeAreaView>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <AppContent />
    </SafeAreaProvider>
  );
}

// --- Action Button Component ---

function ActionButton({
  title,
  onPress,
  disabled,
  color = '#007AFF',
}: {
  title: string;
  onPress: () => void;
  disabled?: boolean;
  color?: string;
}) {
  return (
    <TouchableOpacity
      style={[
        styles.actionButton,
        { backgroundColor: disabled ? '#ddd' : color },
      ]}
      onPress={onPress}
      disabled={disabled}
    >
      <Text style={[styles.actionButtonText, disabled && { color: '#999' }]}>
        {title}
      </Text>
    </TouchableOpacity>
  );
}

// --- Styles ---

const styles = StyleSheet.create({
  flex: { flex: 1, backgroundColor: '#f5f5f5' },
  container: {
    padding: 20,
    paddingBottom: 40,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    marginTop: 12,
    color: '#111',
  },
  subtitle: {
    fontSize: 14,
    color: '#666',
    marginTop: 4,
    marginBottom: 24,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: '#999',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginTop: 20,
    marginBottom: 8,
  },
  pickButton: {
    borderWidth: 2,
    borderColor: '#007AFF',
    borderStyle: 'dashed',
    borderRadius: 12,
    paddingVertical: 20,
    paddingHorizontal: 16,
    alignItems: 'center',
    backgroundColor: '#fff',
  },
  pickButtonText: {
    color: '#007AFF',
    fontSize: 16,
    fontWeight: '600',
  },
  pickButtonHint: {
    color: '#999',
    fontSize: 12,
    marginTop: 4,
  },
  buttonRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  actionButton: {
    paddingVertical: 10,
    paddingHorizontal: 14,
    borderRadius: 8,
  },
  actionButtonText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '600',
  },
  cleanupSection: {
    marginTop: 32,
    paddingTop: 20,
    borderTopWidth: 1,
    borderTopColor: '#ddd',
  },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
    backgroundColor: '#fff',
  },
  toolbarBtn: {
    color: '#007AFF',
    fontSize: 16,
    fontWeight: '600',
  },
  toolbarTitle: {
    fontSize: 14,
    fontWeight: '600',
  },
  modeToggle: {
    flexDirection: 'row',
    borderRadius: 6,
    borderWidth: 1,
    borderColor: '#007AFF',
    overflow: 'hidden',
  },
  modeBtn: {
    paddingVertical: 4,
    paddingHorizontal: 10,
  },
  modeBtnActive: {
    backgroundColor: '#007AFF',
  },
  modeBtnText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#007AFF',
  },
  modeBtnTextActive: {
    color: '#fff',
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.4)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  loader: {
    marginTop: 20,
  },
  listContainer: {
    flex: 1,
    padding: 16,
  },
  listRow: {
    flexDirection: 'row',
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#f0f0f0',
  },
  listKey: {
    fontWeight: '600',
    color: '#333',
    marginBottom: 2,
  },
  listValue: {
    color: '#666',
    fontSize: 13,
  },
  emptyText: {
    color: '#999',
    fontSize: 15,
    textAlign: 'center',
    marginTop: 40,
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalCard: {
    backgroundColor: '#fff',
    borderRadius: 14,
    padding: 24,
    width: 300,
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#111',
    marginBottom: 6,
  },
  modalSubtitle: {
    fontSize: 14,
    color: '#666',
    marginBottom: 16,
  },
  modalInput: {
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
    marginBottom: 16,
  },
  modalButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 12,
  },
  modalBtnCancel: {
    paddingVertical: 8,
    paddingHorizontal: 16,
  },
  modalBtnCancelText: {
    color: '#999',
    fontSize: 16,
    fontWeight: '600',
  },
  modalBtnOk: {
    backgroundColor: '#007AFF',
    paddingVertical: 8,
    paddingHorizontal: 20,
    borderRadius: 8,
  },
  modalBtnOkText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  diffStatsBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 8,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
    gap: 12,
  },
  diffStat: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  diffDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  diffStatText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#333',
  },
  diffTabHint: {
    marginLeft: 'auto',
    fontSize: 12,
    color: '#999',
  },
  diffLegend: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 6,
    backgroundColor: '#fafafa',
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
    gap: 4,
  },
  legendSwatch: {
    width: 16,
    height: 10,
    borderRadius: 2,
  },
  legendText: {
    fontSize: 12,
    color: '#555',
  },
});
