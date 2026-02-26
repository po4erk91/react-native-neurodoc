import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
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
  ActivityIndicator,
  Switch,
  KeyboardAvoidingView,
  Platform,
} from 'react-native';
import type { StyleProp, ViewStyle } from 'react-native';
import { NeuroDoc } from 'react-native-neurodoc';
import { PdfViewer } from './PdfViewer';
import type { PdfViewerRef, TextOverlay } from './PdfViewer';

export interface FormField {
  id: string;
  name: string;
  type: string; // 'text' | 'checkbox' | 'radio' | 'dropdown'
  value: string;
  options: string[];
  fontSize?: number;
  fontName?: string;
}

export interface PdfFormFillerProps {
  pdfUrl: string;
  /** Called after preview fill (non-destructive, shows preview PDF) */
  onFormFilled?: (filledPdfUrl: string) => void;
  /** Called after final save (baked PDF with correct fonts) */
  onSaved?: (savedPdfUrl: string) => void;
  /** Called when form fields are loaded */
  onFieldsLoaded?: (fields: FormField[]) => void;
  /** Called on error */
  onError?: (error: string) => void;
  style?: StyleProp<ViewStyle>;
  displayMode?: 'scroll' | 'single';
  showThumbnails?: boolean;
}

export interface PdfFormFillerRef {
  /** Get current field values */
  getFieldValues: () => Array<{ id: string; value: string }>;
  /** Set field values programmatically */
  setFieldValues: (values: Array<{ id: string; value: string }>) => void;
  /** Fill preview and return the new PDF URL */
  fillForm: (flatten?: boolean) => Promise<string>;
  /** Reload form fields from the PDF */
  reloadFields: () => Promise<void>;
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

const fontDisplayName = (name: string) => name.replace('-', ' ');

export const PdfFormFiller = forwardRef<PdfFormFillerRef, PdfFormFillerProps>(
  (
    {
      pdfUrl,
      onFormFilled,
      onSaved,
      onFieldsLoaded,
      onError,
      style,
      displayMode,
      showThumbnails,
    },
    ref
  ) => {
    const pdfRef = useRef<PdfViewerRef>(null);
    // templateUrl is the original template — never changes
    const templateUrl = useRef(pdfUrl);
    // previewUrl is the currently displayed PDF (preview or original)
    const [previewUrl, setPreviewUrl] = useState(pdfUrl);
    const [fields, setFields] = useState<FormField[]>([]);
    const [values, setValues] = useState<Record<string, string>>({});
    const [fontNames, setFontNames] = useState<Record<string, string>>({});
    const [fontSizes, setFontSizes] = useState<Record<string, number>>({});
    const [loading, setLoading] = useState(false);
    const [showForm, setShowForm] = useState(true);
    const [editingFieldId, setEditingFieldId] = useState<string | null>(null);
    const [fontPickerFieldId, setFontPickerFieldId] = useState<string | null>(
      null
    );
    const [sizePickerFieldId, setSizePickerFieldId] = useState<string | null>(
      null
    );

    // Update templateUrl if pdfUrl prop changes (e.g. new template selected)
    useEffect(() => {
      templateUrl.current = pdfUrl;
      setPreviewUrl(pdfUrl);
    }, [pdfUrl]);

    // Load form fields from the original template
    const loadFields = useCallback(async () => {
      const url = templateUrl.current;
      if (!url) return;
      setLoading(true);
      try {
        const result = await NeuroDoc.getFormFields(url);

        setFields((prevFields) => {
          setValues((prevValues) => {
            const next: Record<string, string> = {};
            for (let i = 0; i < result.fields.length; i++) {
              const newField = result.fields[i]!;
              const prevField = prevFields[i];
              next[newField.id] = prevField
                ? (prevValues[prevField.id] ?? newField.value ?? '')
                : (newField.value ?? '');
            }
            return next;
          });
          setFontNames((prevFonts) => {
            const next: Record<string, string> = {};
            for (let i = 0; i < result.fields.length; i++) {
              const newField = result.fields[i]!;
              const prevField = prevFields[i];
              const defaultFont = newField.fontName || 'Helvetica';
              next[newField.id] = prevField
                ? (prevFonts[prevField.id] ?? defaultFont)
                : defaultFont;
            }
            return next;
          });
          setFontSizes((prevSizes) => {
            const next: Record<string, number> = {};
            for (let i = 0; i < result.fields.length; i++) {
              const newField = result.fields[i]!;
              const prevField = prevFields[i];
              const defaultSize = newField.fontSize || 12;
              next[newField.id] = prevField
                ? (prevSizes[prevField.id] ?? defaultSize)
                : defaultSize;
            }
            return next;
          });
          return result.fields;
        });

        onFieldsLoaded?.(result.fields);
      } catch (e: any) {
        const msg = e.message || 'Failed to load form fields';
        onError?.(msg);
        Alert.alert('Error', msg);
      } finally {
        setLoading(false);
      }
    }, [onFieldsLoaded, onError]);

    useEffect(() => {
      loadFields();
    }, [loadFields]);

    const updateValue = useCallback((id: string, value: string) => {
      setValues((prev) => ({ ...prev, [id]: value }));
    }, []);

    const updateFontName = useCallback((id: string, fontName: string) => {
      setFontNames((prev) => ({ ...prev, [id]: fontName }));
      setFontPickerFieldId(null);
    }, []);

    const updateFontSize = useCallback((id: string, fontSize: number) => {
      setFontSizes((prev) => ({ ...prev, [id]: fontSize }));
      setSizePickerFieldId(null);
    }, []);

    // Build field values array from current state
    const buildFieldValues = useCallback(() => {
      return fields.map((f) => ({
        id: f.id,
        value: values[f.id] || '',
        fontName: fontNames[f.id] || 'Helvetica',
        fontSize: fontSizes[f.id] || 12,
      }));
    }, [fields, values, fontNames, fontSizes]);

    // Fill = preview. Always fills from the original template.
    // flattenAfterFill=false → just sets annotation values, no baking.
    const fillForm = useCallback(
      async (flatten?: boolean): Promise<string> => {
        const result = await NeuroDoc.fillForm({
          pdfUrl: templateUrl.current,
          fields: buildFieldValues(),
          flattenAfterFill: flatten ?? false,
        });

        if (!flatten) {
          // Preview mode — update displayed PDF
          setPreviewUrl(result.pdfUrl);
          onFormFilled?.(result.pdfUrl);
        }
        return result.pdfUrl;
      },
      [buildFieldValues, onFormFilled]
    );

    // Fill preview
    const handleFill = useCallback(async () => {
      setLoading(true);
      try {
        await fillForm(false);
      } catch (e: any) {
        const msg = e.message || 'Failed to fill form';
        onError?.(msg);
        Alert.alert('Error', msg);
      } finally {
        setLoading(false);
      }
    }, [fillForm, onError]);

    // Save = flatten from the original template with correct fonts baked in
    const handleSave = useCallback(async () => {
      setLoading(true);
      try {
        const url = await fillForm(true);
        onSaved?.(url);
        Alert.alert('Saved', 'PDF has been saved with your values.');
      } catch (e: any) {
        const msg = e.message || 'Failed to save PDF';
        onError?.(msg);
        Alert.alert('Error', msg);
      } finally {
        setLoading(false);
      }
    }, [fillForm, onSaved, onError]);

    useImperativeHandle(ref, () => ({
      getFieldValues: () =>
        fields.map((f) => ({ id: f.id, value: values[f.id] || '' })),
      setFieldValues: (newValues) => {
        setValues((prev) => {
          const next = { ...prev };
          for (const v of newValues) {
            next[v.id] = v.value;
          }
          return next;
        });
      },
      fillForm,
      reloadFields: loadFields,
    }));

    const overlays: TextOverlay[] = [];

    const textFields = fields.filter(
      (f) => f.type === 'text' || f.type === 'dropdown'
    );
    const checkboxFields = fields.filter(
      (f) => f.type === 'checkbox' || f.type === 'radio'
    );

    return (
      <KeyboardAvoidingView
        style={[styles.container, style]}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      >
        <PdfViewer
          ref={pdfRef}
          pdfUrl={previewUrl}
          displayMode={displayMode}
          showThumbnails={showThumbnails}
          textOverlays={overlays}
          style={showForm ? styles.pdfHalf : styles.pdfFull}
        />

        {/* Toggle form panel */}
        <TouchableOpacity
          style={styles.toggleBtn}
          onPress={() => setShowForm(!showForm)}
        >
          <Text style={styles.toggleBtnText}>
            {showForm ? 'Hide Form' : `Show Form (${fields.length})`}
          </Text>
        </TouchableOpacity>

        {/* Form panel */}
        {showForm && (
          <View style={styles.formPanel}>
            <View style={styles.formHeader}>
              <Text style={styles.formTitle}>
                {fields.length} field{fields.length !== 1 ? 's' : ''}
              </Text>
              <View style={styles.headerButtons}>
                <TouchableOpacity
                  style={[
                    styles.fillButton,
                    fields.length === 0 && styles.buttonDisabled,
                  ]}
                  onPress={handleFill}
                  disabled={fields.length === 0 || loading}
                >
                  <Text style={styles.fillButtonText}>
                    {loading ? '...' : 'Fill'}
                  </Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={[
                    styles.saveButton,
                    fields.length === 0 && styles.buttonDisabled,
                  ]}
                  onPress={handleSave}
                  disabled={fields.length === 0 || loading}
                >
                  <Text style={styles.saveButtonText}>Save</Text>
                </TouchableOpacity>
              </View>
            </View>

            {loading && fields.length === 0 ? (
              <ActivityIndicator style={styles.loader} size="small" />
            ) : fields.length === 0 ? (
              <Text style={styles.emptyText}>
                No form fields found in this PDF
              </Text>
            ) : (
              <ScrollView
                style={styles.fieldList}
                keyboardShouldPersistTaps="handled"
              >
                {/* Text fields */}
                {textFields.map((field) => (
                  <View key={field.id} style={styles.fieldRow}>
                    <Text style={styles.fieldLabel}>{field.name}</Text>
                    {field.type === 'dropdown' && field.options.length > 0 ? (
                      <View style={styles.dropdownContainer}>
                        {field.options.map((opt) => (
                          <TouchableOpacity
                            key={opt}
                            style={[
                              styles.dropdownOption,
                              values[field.id] === opt &&
                                styles.dropdownOptionSelected,
                            ]}
                            onPress={() => updateValue(field.id, opt)}
                          >
                            <Text
                              style={[
                                styles.dropdownOptionText,
                                values[field.id] === opt &&
                                  styles.dropdownOptionTextSelected,
                              ]}
                            >
                              {opt}
                            </Text>
                          </TouchableOpacity>
                        ))}
                      </View>
                    ) : (
                      <>
                        <TextInput
                          style={styles.fieldInput}
                          value={values[field.id] || ''}
                          onChangeText={(text) => updateValue(field.id, text)}
                          placeholder={`Enter ${field.name}`}
                          placeholderTextColor="#aaa"
                          onFocus={() => setEditingFieldId(field.id)}
                          onBlur={() => setEditingFieldId(null)}
                          returnKeyType="done"
                        />
                        {/* Font & Size selectors */}
                        <View style={styles.fontSizeRow}>
                          <TouchableOpacity
                            style={styles.fontChip}
                            onPress={() => {
                              setSizePickerFieldId(null);
                              setFontPickerFieldId(
                                fontPickerFieldId === field.id ? null : field.id
                              );
                            }}
                          >
                            <Text style={styles.fontChipText} numberOfLines={1}>
                              {fontDisplayName(
                                fontNames[field.id] || 'Helvetica'
                              )}
                            </Text>
                          </TouchableOpacity>
                          <TouchableOpacity
                            style={styles.sizeChip}
                            onPress={() => {
                              setFontPickerFieldId(null);
                              setSizePickerFieldId(
                                sizePickerFieldId === field.id ? null : field.id
                              );
                            }}
                          >
                            <Text style={styles.sizeChipText}>
                              {fontSizes[field.id] || 12}pt
                            </Text>
                          </TouchableOpacity>
                        </View>

                        {/* Inline font picker */}
                        {fontPickerFieldId === field.id && (
                          <ScrollView
                            horizontal
                            showsHorizontalScrollIndicator={false}
                            style={styles.inlinePicker}
                            contentContainerStyle={styles.inlinePickerContent}
                          >
                            {FONT_OPTIONS.map((font) => (
                              <TouchableOpacity
                                key={font}
                                style={[
                                  styles.pickerOption,
                                  fontNames[field.id] === font &&
                                    styles.pickerOptionSelected,
                                ]}
                                onPress={() => updateFontName(field.id, font)}
                              >
                                <Text
                                  style={[
                                    styles.pickerOptionText,
                                    fontNames[field.id] === font &&
                                      styles.pickerOptionTextSelected,
                                  ]}
                                >
                                  {fontDisplayName(font)}
                                </Text>
                              </TouchableOpacity>
                            ))}
                          </ScrollView>
                        )}

                        {/* Inline size picker */}
                        {sizePickerFieldId === field.id && (
                          <ScrollView
                            horizontal
                            showsHorizontalScrollIndicator={false}
                            style={styles.inlinePicker}
                            contentContainerStyle={styles.inlinePickerContent}
                          >
                            {FONT_SIZE_OPTIONS.map((size) => (
                              <TouchableOpacity
                                key={size}
                                style={[
                                  styles.pickerOption,
                                  fontSizes[field.id] === size &&
                                    styles.pickerOptionSelected,
                                ]}
                                onPress={() => updateFontSize(field.id, size)}
                              >
                                <Text
                                  style={[
                                    styles.pickerOptionText,
                                    fontSizes[field.id] === size &&
                                      styles.pickerOptionTextSelected,
                                  ]}
                                >
                                  {size}
                                </Text>
                              </TouchableOpacity>
                            ))}
                          </ScrollView>
                        )}
                      </>
                    )}
                  </View>
                ))}

                {/* Checkbox fields */}
                {checkboxFields.map((field) => (
                  <View key={field.id} style={styles.checkboxRow}>
                    <Text style={styles.fieldLabel}>{field.name}</Text>
                    <Switch
                      value={values[field.id] === 'true'}
                      onValueChange={(checked) =>
                        updateValue(field.id, checked ? 'true' : 'false')
                      }
                      trackColor={{ false: '#ddd', true: '#34C759' }}
                    />
                  </View>
                ))}
              </ScrollView>
            )}
          </View>
        )}

        {loading && fields.length > 0 && (
          <View style={styles.loadingOverlay}>
            <ActivityIndicator size="large" color="#fff" />
          </View>
        )}
      </KeyboardAvoidingView>
    );
  }
);

PdfFormFiller.displayName = 'PdfFormFiller';

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  pdfHalf: {
    flex: 1,
  },
  pdfFull: {
    flex: 1,
  },
  toggleBtn: {
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#ddd',
    paddingVertical: 8,
    alignItems: 'center',
  },
  toggleBtnText: {
    fontSize: 14,
    fontWeight: '600',
    color: '#007AFF',
  },
  formPanel: {
    maxHeight: 350,
    backgroundColor: '#fff',
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#ddd',
  },
  formHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#eee',
  },
  formTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: '#333',
  },
  headerButtons: {
    flexDirection: 'row',
    gap: 8,
  },
  fillButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#007AFF',
  },
  fillButtonText: {
    fontSize: 14,
    fontWeight: '700',
    color: '#fff',
  },
  saveButton: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 8,
    backgroundColor: '#34C759',
  },
  saveButtonText: {
    fontSize: 14,
    fontWeight: '700',
    color: '#fff',
  },
  buttonDisabled: {
    backgroundColor: '#ccc',
  },
  fieldList: {
    paddingHorizontal: 12,
  },
  fieldRow: {
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  fieldLabel: {
    fontSize: 13,
    fontWeight: '600',
    color: '#555',
    marginBottom: 4,
  },
  fieldInput: {
    fontSize: 15,
    color: '#111',
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: '#fafafa',
  },
  fontSizeRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 6,
  },
  fontChip: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 6,
    backgroundColor: '#E8F4FD',
    maxWidth: 140,
  },
  fontChipText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#007AFF',
  },
  sizeChip: {
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 6,
    backgroundColor: '#E8F4FD',
  },
  sizeChipText: {
    fontSize: 12,
    fontWeight: '600',
    color: '#007AFF',
  },
  inlinePicker: {
    marginTop: 6,
    maxHeight: 38,
  },
  inlinePickerContent: {
    gap: 6,
  },
  pickerOption: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: '#ddd',
    backgroundColor: '#fafafa',
  },
  pickerOptionSelected: {
    borderColor: '#007AFF',
    backgroundColor: '#E8F4FD',
  },
  pickerOptionText: {
    fontSize: 13,
    color: '#333',
  },
  pickerOptionTextSelected: {
    color: '#007AFF',
    fontWeight: '600',
  },
  checkboxRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#f0f0f0',
  },
  dropdownContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 4,
  },
  dropdownOption: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    borderWidth: 1,
    borderColor: '#ddd',
    backgroundColor: '#fafafa',
  },
  dropdownOptionSelected: {
    borderColor: '#007AFF',
    backgroundColor: '#E8F4FD',
  },
  dropdownOptionText: {
    fontSize: 14,
    color: '#333',
  },
  dropdownOptionTextSelected: {
    color: '#007AFF',
    fontWeight: '600',
  },
  emptyText: {
    textAlign: 'center',
    color: '#999',
    fontSize: 14,
    paddingVertical: 20,
  },
  loader: {
    paddingVertical: 20,
  },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.3)',
    justifyContent: 'center',
    alignItems: 'center',
  },
});
