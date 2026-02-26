import {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  ScrollView,
  Alert,
} from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';
import { NeuroDoc } from 'react-native-neurodoc';
import { PdfViewer } from './PdfViewer';
import type { PdfViewerRef, TextOverlay } from './PdfViewer';

export interface MarkedField {
  id: string;
  name: string;
  pageIndex: number;
  boundingBox: { x: number; y: number; width: number; height: number };
  text: string;
  fontSize: number;
  fontName: string;
}

export interface PdfFieldMarkerProps {
  pdfUrl: string;
  onFieldsChanged?: (fields: MarkedField[]) => void;
  onCreateTemplate?: (templateUrl: string) => void;
  style?: StyleProp<ViewStyle>;
  displayMode?: 'scroll' | 'single';
  showThumbnails?: boolean;
  /** Default width for new fields (normalized 0-1). Default: 0.25 */
  defaultFieldWidth?: number;
  /** Default height for new fields (normalized 0-1). Default: 0.03 */
  defaultFieldHeight?: number;
}

export interface PdfFieldMarkerRef {
  getFields: () => MarkedField[];
  createTemplate: (removeOriginalText?: boolean) => Promise<string>;
  clearFields: () => void;
  addField: (
    pageIndex: number,
    x: number,
    y: number,
    width?: number,
    height?: number
  ) => void;
  removeField: (id: string) => void;
}

interface FieldEntry {
  id: string;
  name: string;
  pageIndex: number;
  x: number;
  y: number;
  width: number;
  height: number;
  fontSize: number;
  fontName: string;
}

const FONT_OPTIONS = [
  'Helvetica',
  'Helvetica-Bold',
  'Times-Roman',
  'Times-Bold',
  'Courier',
  'Courier-Bold',
  'Arial',
  'Georgia',
];

const FONT_SIZE_OPTIONS = [8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32];

type Mode = 'view' | 'placing' | 'adjusting';

let fieldCounter = 0;

export const PdfFieldMarker = forwardRef<PdfFieldMarkerRef, PdfFieldMarkerProps>(
  (
    {
      pdfUrl,
      onFieldsChanged,
      onCreateTemplate,
      style,
      displayMode,
      showThumbnails,
      defaultFieldWidth = 0.25,
      defaultFieldHeight = 0.03,
    },
    ref
  ) => {
    const pdfRef = useRef<PdfViewerRef>(null);
    const [fields, setFields] = useState<FieldEntry[]>([]);
    const [mode, setMode] = useState<Mode>('view');
    // The field currently being placed/adjusted (not yet confirmed)
    const [activeField, setActiveField] = useState<FieldEntry | null>(null);
    const [editingNameId, setEditingNameId] = useState<string | null>(null);
    const [showFieldList, setShowFieldList] = useState(false);
    const [showFontPicker, setShowFontPicker] = useState(false);
    const [showSizePicker, setShowSizePicker] = useState(false);

    const toMarkedField = useCallback((f: FieldEntry): MarkedField => ({
      id: f.id,
      name: f.name,
      pageIndex: f.pageIndex,
      boundingBox: { x: f.x, y: f.y, width: f.width, height: f.height },
      text: f.name,
      fontSize: f.fontSize,
      fontName: f.fontName,
    }), []);

    const notifyChange = useCallback(
      (updatedFields: FieldEntry[]) => {
        onFieldsChanged?.(updatedFields.map(toMarkedField));
      },
      [onFieldsChanged, toMarkedField]
    );

    const getFields = useCallback((): MarkedField[] => {
      return fields.map(toMarkedField);
    }, [fields, toMarkedField]);

    const addField = useCallback(
      (
        pageIndex: number,
        x: number,
        y: number,
        width?: number,
        height?: number
      ) => {
        const id = `field_${++fieldCounter}`;
        const w = width ?? defaultFieldWidth;
        const h = height ?? defaultFieldHeight;
        // Center the field on tap point
        const entry: FieldEntry = {
          id,
          name: `Field ${fieldCounter}`,
          pageIndex,
          x: Math.max(0, Math.min(x - w / 2, 1 - w)),
          y: Math.max(0, Math.min(y - h / 2, 1 - h)),
          width: w,
          height: h,
          fontSize: 12,
          fontName: 'Helvetica',
        };
        setActiveField(entry);
        setMode('adjusting');
        setShowFontPicker(false);
        setShowSizePicker(false);
      },
      [defaultFieldWidth, defaultFieldHeight]
    );

    const confirmActiveField = useCallback(() => {
      if (!activeField) return;
      setFields((prev) => {
        const next = [...prev, activeField];
        notifyChange(next);
        return next;
      });
      setActiveField(null);
      setMode('view');
      setShowFontPicker(false);
      setShowSizePicker(false);
    }, [activeField, notifyChange]);

    const cancelActiveField = useCallback(() => {
      setActiveField(null);
      setMode('view');
      setShowFontPicker(false);
      setShowSizePicker(false);
    }, []);

    const removeField = useCallback(
      (id: string) => {
        setFields((prev) => {
          const next = prev.filter((f) => f.id !== id);
          notifyChange(next);
          return next;
        });
      },
      [notifyChange]
    );

    const clearFields = useCallback(() => {
      setFields([]);
      setActiveField(null);
      setMode('view');
      notifyChange([]);
    }, [notifyChange]);

    const createTemplate = useCallback(
      async (removeOriginalText = true): Promise<string> => {
        if (fields.length === 0) {
          throw new Error('No fields marked');
        }

        const result = await NeuroDoc.createFormFromPdf({
          pdfUrl,
          fields: fields.map((f) => ({
            name: f.name,
            pageIndex: f.pageIndex,
            boundingBox: { x: f.x, y: f.y, width: f.width, height: f.height },
            type: 'text',
            defaultValue: '',
            fontSize: f.fontSize,
            fontName: f.fontName,
          })),
          removeOriginalText,
        });

        onCreateTemplate?.(result.pdfUrl);
        return result.pdfUrl;
      },
      [pdfUrl, fields, onCreateTemplate]
    );

    useImperativeHandle(ref, () => ({
      getFields,
      createTemplate,
      clearFields,
      addField,
      removeField,
    }));

    // When user taps on PDF in "placing" mode, create a new field
    const handleTap = useCallback(
      (pageIndex: number, x: number, y: number) => {
        if (mode === 'placing') {
          addField(pageIndex, x, y);
        }
      },
      [mode, addField]
    );

    // Handle tap on existing overlay (confirmed fields)
    const handleOverlayTap = useCallback(
      (id: string, _pageIndex: number) => {
        if (mode !== 'view') return;
        // Select field for editing/deletion
        Alert.alert('Field Options', `Field: ${fields.find((f) => f.id === id)?.name}`, [
          {
            text: 'Rename',
            onPress: () => setEditingNameId(id),
          },
          {
            text: 'Delete',
            style: 'destructive',
            onPress: () => removeField(id),
          },
          { text: 'Cancel', style: 'cancel' },
        ]);
      },
      [mode, fields, removeField]
    );

    // Handle overlay moved (for confirmed fields)
    const handleOverlayMoved = useCallback(
      (id: string, x: number, y: number) => {
        // Check if it's the active field being moved
        if (activeField && activeField.id === id) {
          setActiveField((prev) => (prev ? { ...prev, x, y } : null));
          return;
        }
        setFields((prev) =>
          prev.map((f) => (f.id === id ? { ...f, x, y } : f))
        );
      },
      [activeField]
    );

    // Handle overlay resized
    const handleOverlayResized = useCallback(
      (
        id: string,
        x: number,
        y: number,
        width: number,
        height: number
      ) => {
        if (activeField && activeField.id === id) {
          setActiveField((prev) =>
            prev ? { ...prev, x, y, width, height } : null
          );
          return;
        }
        setFields((prev) =>
          prev.map((f) =>
            f.id === id ? { ...f, x, y, width, height } : f
          )
        );
      },
      [activeField]
    );

    const handleRenameField = useCallback(
      (id: string, newName: string) => {
        setFields((prev) => {
          const next = prev.map((f) =>
            f.id === id ? { ...f, name: newName || f.name } : f
          );
          notifyChange(next);
          return next;
        });
        setEditingNameId(null);
      },
      [notifyChange]
    );

    // Build overlays: confirmed fields + active field
    const overlays: TextOverlay[] = useMemo(() => {
      const result: TextOverlay[] = fields.map((f) => ({
        id: f.id,
        pageIndex: f.pageIndex,
        x: f.x,
        y: f.y,
        width: f.width,
        height: f.height,
        selected: false,
        label: f.name,
      }));
      if (activeField) {
        result.push({
          id: activeField.id,
          pageIndex: activeField.pageIndex,
          x: activeField.x,
          y: activeField.y,
          width: activeField.width,
          height: activeField.height,
          selected: true, // Active field shown as selected (green, with handles)
          label: activeField.name,
        });
      }
      return result;
    }, [fields, activeField]);

    // Handle "Add Field" from native text selection context menu
    const handleAddFieldFromSelection = useCallback(
      (
        text: string,
        pageIndex: number,
        x: number,
        y: number,
        width: number,
        height: number
      ) => {
        if (mode === 'adjusting') return; // Don't create while adjusting another field
        const id = `field_${++fieldCounter}`;
        const entry: FieldEntry = {
          id,
          name: text.substring(0, 30).trim() || `Field ${fieldCounter}`,
          pageIndex,
          x,
          y,
          width,
          height,
          fontSize: 12,
          fontName: 'Helvetica',
        };
        setActiveField(entry);
        setMode('adjusting');
        setShowFontPicker(false);
        setShowSizePicker(false);
      },
      [mode]
    );

    const hasOverlays = overlays.length > 0;
    const interacting = mode === 'placing' || mode === 'adjusting';

    // Font display name (shorten for UI)
    const fontDisplayName = (name: string) => {
      return name.replace('-', ' ');
    };

    return (
      <View style={[styles.container, style]}>
        <PdfViewer
          ref={pdfRef}
          pdfUrl={pdfUrl}
          displayMode={displayMode}
          showThumbnails={showThumbnails}
          textOverlays={overlays}
          enableOverlayTap={hasOverlays || interacting}
          disableSelection={interacting}
          onOverlayTap={handleOverlayTap}
          onTap={handleTap}
          onOverlayMoved={handleOverlayMoved}
          onOverlayResized={handleOverlayResized}
          onAddFieldFromSelection={handleAddFieldFromSelection}
          style={styles.pdf}
        />

        {/* Field list panel */}
        {showFieldList && fields.length > 0 && (
          <View style={styles.fieldListPanel}>
            <View style={styles.fieldListHeader}>
              <Text style={styles.fieldListTitle}>
                Fields ({fields.length})
              </Text>
              <TouchableOpacity onPress={() => setShowFieldList(false)}>
                <Text style={styles.closeBtn}>Close</Text>
              </TouchableOpacity>
            </View>
            <ScrollView style={styles.fieldListScroll}>
              {fields.map((f) => (
                <View key={f.id} style={styles.fieldListItem}>
                  {editingNameId === f.id ? (
                    <TextInput
                      style={styles.fieldNameInput}
                      defaultValue={f.name}
                      autoFocus
                      onSubmitEditing={(e) =>
                        handleRenameField(f.id, e.nativeEvent.text)
                      }
                      onBlur={() => setEditingNameId(null)}
                      returnKeyType="done"
                    />
                  ) : (
                    <TouchableOpacity
                      style={styles.fieldNameArea}
                      onPress={() => setEditingNameId(f.id)}
                    >
                      <Text style={styles.fieldName}>{f.name}</Text>
                      <Text style={styles.fieldMeta}>
                        Page {f.pageIndex + 1} | {fontDisplayName(f.fontName)} {f.fontSize}pt
                      </Text>
                    </TouchableOpacity>
                  )}
                  <TouchableOpacity
                    style={styles.deleteBtn}
                    onPress={() => removeField(f.id)}
                  >
                    <Text style={styles.deleteBtnText}>x</Text>
                  </TouchableOpacity>
                </View>
              ))}
            </ScrollView>
          </View>
        )}

        {/* Font picker dropdown */}
        {showFontPicker && activeField && (
          <View style={styles.pickerPanel}>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.pickerScroll}>
              {FONT_OPTIONS.map((font) => (
                <TouchableOpacity
                  key={font}
                  style={[
                    styles.pickerOption,
                    activeField.fontName === font && styles.pickerOptionSelected,
                  ]}
                  onPress={() => {
                    setActiveField((prev) => prev ? { ...prev, fontName: font } : null);
                    setShowFontPicker(false);
                  }}
                >
                  <Text
                    style={[
                      styles.pickerOptionText,
                      activeField.fontName === font && styles.pickerOptionTextSelected,
                    ]}
                  >
                    {fontDisplayName(font)}
                  </Text>
                </TouchableOpacity>
              ))}
            </ScrollView>
          </View>
        )}

        {/* Size picker dropdown */}
        {showSizePicker && activeField && (
          <View style={styles.pickerPanel}>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.pickerScroll}>
              {FONT_SIZE_OPTIONS.map((size) => (
                <TouchableOpacity
                  key={size}
                  style={[
                    styles.pickerOption,
                    activeField.fontSize === size && styles.pickerOptionSelected,
                  ]}
                  onPress={() => {
                    setActiveField((prev) => prev ? { ...prev, fontSize: size } : null);
                    setShowSizePicker(false);
                  }}
                >
                  <Text
                    style={[
                      styles.pickerOptionText,
                      activeField.fontSize === size && styles.pickerOptionTextSelected,
                    ]}
                  >
                    {size}
                  </Text>
                </TouchableOpacity>
              ))}
            </ScrollView>
          </View>
        )}

        {/* Toolbar */}
        <View style={styles.toolbar}>
          {mode === 'view' && (
            <>
              {/* Add Field tool */}
              <TouchableOpacity
                style={styles.toolButton}
                onPress={() => setMode('placing')}
              >
                <View style={styles.addFieldIcon}>
                  <Text style={styles.addFieldIconText}>+</Text>
                </View>
                <Text style={styles.toolLabel}>Add Field</Text>
              </TouchableOpacity>

              {/* Field count / list toggle */}
              {fields.length > 0 && (
                <TouchableOpacity
                  style={styles.counterBtn}
                  onPress={() => setShowFieldList(!showFieldList)}
                >
                  <Text style={styles.counterText}>
                    {fields.length} field{fields.length !== 1 ? 's' : ''}
                  </Text>
                </TouchableOpacity>
              )}

              {/* Clear all */}
              {fields.length > 0 && (
                <TouchableOpacity
                  style={styles.button}
                  onPress={() =>
                    Alert.alert(
                      'Clear All Fields',
                      'Remove all marked fields?',
                      [
                        { text: 'Cancel', style: 'cancel' },
                        {
                          text: 'Clear',
                          style: 'destructive',
                          onPress: clearFields,
                        },
                      ]
                    )
                  }
                >
                  <Text style={styles.buttonText}>Clear</Text>
                </TouchableOpacity>
              )}

              {/* Create Template */}
              {fields.length > 0 && (
                <TouchableOpacity
                  style={[styles.button, styles.buttonPrimary]}
                  onPress={() => createTemplate()}
                >
                  <Text style={[styles.buttonText, styles.buttonTextPrimary]}>
                    Create Template
                  </Text>
                </TouchableOpacity>
              )}
            </>
          )}

          {mode === 'placing' && (
            <>
              <Text style={styles.modeHint}>
                Tap on the document to place a field
              </Text>
              <TouchableOpacity
                style={styles.button}
                onPress={() => setMode('view')}
              >
                <Text style={styles.buttonText}>Cancel</Text>
              </TouchableOpacity>
            </>
          )}

          {mode === 'adjusting' && activeField && (
            <>
              {/* Font selector */}
              <TouchableOpacity
                style={styles.fontBtn}
                onPress={() => {
                  setShowFontPicker(!showFontPicker);
                  setShowSizePicker(false);
                }}
              >
                <Text style={styles.fontBtnText} numberOfLines={1}>
                  {fontDisplayName(activeField.fontName)}
                </Text>
              </TouchableOpacity>

              {/* Size selector */}
              <TouchableOpacity
                style={styles.sizeBtn}
                onPress={() => {
                  setShowSizePicker(!showSizePicker);
                  setShowFontPicker(false);
                }}
              >
                <Text style={styles.sizeBtnText}>{activeField.fontSize}</Text>
              </TouchableOpacity>

              <View style={styles.toolbarSpacer} />

              <TouchableOpacity
                style={styles.button}
                onPress={cancelActiveField}
              >
                <Text style={styles.buttonText}>Cancel</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.confirmButton]}
                onPress={confirmActiveField}
              >
                <Text style={styles.confirmButtonText}>Confirm</Text>
              </TouchableOpacity>
            </>
          )}
        </View>
      </View>
    );
  }
);

PdfFieldMarker.displayName = 'PdfFieldMarker';

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  pdf: {
    flex: 1,
  },
  toolbar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#ddd',
    gap: 8,
  },
  toolButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 6,
    paddingHorizontal: 10,
    borderRadius: 8,
    backgroundColor: '#E8F4FD',
    gap: 6,
  },
  addFieldIcon: {
    width: 24,
    height: 24,
    borderRadius: 6,
    borderWidth: 2,
    borderColor: '#007AFF',
    borderStyle: 'dashed',
    alignItems: 'center',
    justifyContent: 'center',
  },
  addFieldIconText: {
    fontSize: 16,
    fontWeight: '700',
    color: '#007AFF',
    marginTop: -1,
  },
  toolLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: '#007AFF',
  },
  counterBtn: {
    flex: 1,
    alignItems: 'center',
  },
  counterText: {
    fontSize: 13,
    color: '#666',
  },
  button: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#f0f0f0',
  },
  buttonPrimary: {
    backgroundColor: '#007AFF',
  },
  buttonText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  buttonTextPrimary: {
    color: '#fff',
  },
  confirmButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#34C759',
  },
  confirmButtonText: {
    fontSize: 14,
    fontWeight: '700',
    color: '#fff',
  },
  modeHint: {
    flex: 1,
    fontSize: 13,
    color: '#666',
    fontStyle: 'italic',
  },
  // Font / Size buttons in adjusting toolbar
  fontBtn: {
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#E8F4FD',
    maxWidth: 120,
  },
  fontBtnText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#007AFF',
  },
  sizeBtn: {
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#E8F4FD',
    minWidth: 36,
    alignItems: 'center',
  },
  sizeBtnText: {
    fontSize: 13,
    fontWeight: '600',
    color: '#007AFF',
  },
  toolbarSpacer: {
    flex: 1,
  },
  // Picker panel (font / size)
  pickerPanel: {
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#ddd',
    paddingVertical: 6,
  },
  pickerScroll: {
    paddingHorizontal: 12,
    gap: 6,
  },
  pickerOption: {
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#ddd',
    backgroundColor: '#fafafa',
  },
  pickerOptionSelected: {
    borderColor: '#007AFF',
    backgroundColor: '#E8F4FD',
  },
  pickerOptionText: {
    fontSize: 14,
    color: '#333',
  },
  pickerOptionTextSelected: {
    color: '#007AFF',
    fontWeight: '600',
  },
  // Field list panel
  fieldListPanel: {
    maxHeight: 200,
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#ddd',
  },
  fieldListHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#eee',
  },
  fieldListTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  closeBtn: {
    fontSize: 14,
    color: '#007AFF',
    fontWeight: '600',
  },
  fieldListScroll: {
    paddingHorizontal: 12,
  },
  fieldListItem: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  fieldNameArea: {
    flex: 1,
  },
  fieldName: {
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
  },
  fieldMeta: {
    fontSize: 11,
    color: '#999',
    marginTop: 1,
  },
  fieldNameInput: {
    flex: 1,
    fontSize: 14,
    fontWeight: '500',
    color: '#333',
    borderBottomWidth: 1,
    borderBottomColor: '#007AFF',
    paddingVertical: 2,
  },
  deleteBtn: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#FF3B30',
    alignItems: 'center',
    justifyContent: 'center',
    marginLeft: 8,
  },
  deleteBtnText: {
    fontSize: 14,
    fontWeight: '700',
    color: '#fff',
  },
});
