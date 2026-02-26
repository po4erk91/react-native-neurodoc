import { useState, useRef, useCallback } from 'react';
import {
  Text,
  View,
  StyleSheet,
  TouchableOpacity,
  ScrollView,
  Alert,
  ActivityIndicator,
  SafeAreaView,
  Share,
} from 'react-native';
import { NeuroDoc } from 'react-native-neurodoc';
import { PdfViewer, type PdfViewerRef } from './PdfViewer';
import { PdfFieldMarker, type PdfFieldMarkerRef } from './PdfFieldMarker';
import { PdfFormFiller, type PdfFormFillerRef } from './PdfFormFiller';
import { generatePdf } from './templates';
import { randomInvoiceData, randomReceiptData, randomContractData, randomLetterData } from './randomData';

type Screen = 'home' | 'viewer' | 'metadata' | 'annotations' | 'formFields' | 'fieldMarker' | 'formFiller';

export default function App() {
  const [screen, setScreen] = useState<Screen>('home');
  const [pdfUrl, setPdfUrl] = useState('');
  const [metadata, setMetadata] = useState<Record<string, unknown> | null>(
    null
  );
  const [annotations, setAnnotations] = useState<any[]>([]);
  const [formFields, setFormFields] = useState<any[]>([]);
  const [pageInfo, setPageInfo] = useState({ page: 0, total: 0 });
  const [viewMode, setViewMode] = useState<'scroll' | 'single' | 'grid'>('scroll');
  const [loading, setLoading] = useState(false);
  const [templateUrl, setTemplateUrl] = useState('');
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
      Alert.alert('Split', `Created ${result.pdfUrls.length} PDFs. Showing first part.`);
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
      Alert.alert('Done', `First page deleted. ${result.pageCount} pages left.`);
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
      const reversed = Array.from({ length: meta.pageCount }, (_, i) => meta.pageCount - 1 - i);
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
        Alert.alert('Not enough text', 'Need at least 3 text blocks to demo form creation');
        return;
      }

      // Pick first 3 text blocks as "dynamic" fields
      const fields = extracted.textBlocks.slice(0, 3).map((block: any, i: number) => ({
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
      Alert.alert('Form Created', `Created fillable PDF with ${fields.length} fields. Use "Get Form Fields" to verify.`);
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, [pdfUrl]);

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
      Alert.alert('Invoice Generated', `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`);
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
      Alert.alert('Receipt Generated', `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`);
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
      Alert.alert('Contract Generated', `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`);
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
      Alert.alert('Letter Generated', `${result.pageCount} page(s), ${(result.fileSize / 1024).toFixed(0)} KB`);
    } catch (e: any) {
      Alert.alert('Error', e.message);
    } finally {
      setLoading(false);
    }
  }, []);

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
            <Text style={[styles.toolbarBtn, !templateUrl && { color: '#ccc' }]}>
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
              style={[styles.modeBtn, viewMode === 'scroll' && styles.modeBtnActive]}
              onPress={() => setViewMode('scroll')}
            >
              <Text style={[styles.modeBtnText, viewMode === 'scroll' && styles.modeBtnTextActive]}>
                Scroll
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.modeBtn, viewMode === 'single' && styles.modeBtnActive]}
              onPress={() => setViewMode('single')}
            >
              <Text style={[styles.modeBtnText, viewMode === 'single' && styles.modeBtnTextActive]}>
                Page
              </Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.modeBtn, viewMode === 'grid' && styles.modeBtnActive]}
              onPress={() => setViewMode('grid')}
            >
              <Text style={[styles.modeBtnText, viewMode === 'grid' && styles.modeBtnTextActive]}>
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
          onDocumentLoadFailed={(error) => Alert.alert('Load Error', error)}
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
        <TouchableOpacity style={styles.pickButton} onPress={handlePickDocument}>
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

        {/* Text Extraction & Form Creation */}
        <Text style={styles.sectionTitle}>Text Extraction</Text>
        <View style={styles.buttonRow}>
          <ActionButton
            title="Extract Text"
            onPress={handleExtractText}
            disabled={!pdfUrl}
            color="#8E44AD"
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
      <Text
        style={[styles.actionButtonText, disabled && { color: '#999' }]}
      >
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
});
