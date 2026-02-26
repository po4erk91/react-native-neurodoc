import { NeuroDoc, type TemplateDefinition } from 'react-native-neurodoc';
import { invoiceTemplate } from './builtins/invoice';
import { receiptTemplate } from './builtins/receipt';
import {
  contractTemplate,
  expandContractSections,
} from './builtins/contract';
import { letterTemplate } from './builtins/letter';

export type BuiltInTemplate = 'invoice' | 'receipt' | 'contract' | 'letter';

export interface GenerateOptions {
  /** Use a built-in template by name, or provide a custom TemplateDefinition */
  template: BuiltInTemplate | TemplateDefinition;
  /** Data to fill into the template */
  data: Record<string, unknown>;
  /** Style overrides for built-in templates */
  style?: {
    primaryColor?: string;
    secondaryColor?: string;
    accentColor?: string;
    logoUrl?: string;
  };
  /** Output file name (without extension) */
  fileName?: string;
}

export interface GenerateResult {
  pdfUrl: string;
  pageCount: number;
  fileSize: number;
}

const builtInTemplates: Record<BuiltInTemplate, TemplateDefinition> = {
  invoice: invoiceTemplate,
  receipt: receiptTemplate,
  contract: contractTemplate,
  letter: letterTemplate,
};

/**
 * Generate a PDF from a template and data.
 *
 * @example
 * // Built-in invoice
 * const result = await generatePdf({
 *   template: 'invoice',
 *   data: { companyName: 'Acme', invoiceNumber: 'INV-001', items: [...], total: '$100' },
 *   style: { primaryColor: '#1A1A2E', accentColor: '#E94560' },
 * });
 *
 * @example
 * // Custom template
 * const result = await generatePdf({
 *   template: {
 *     body: [
 *       { type: 'text', content: '{{title}}', font: { size: 24, bold: true } },
 *       { type: 'text', content: '{{body}}' },
 *     ],
 *   },
 *   data: { title: 'Hello', body: 'World' },
 * });
 */
export async function generatePdf(
  options: GenerateOptions
): Promise<GenerateResult> {
  let template: TemplateDefinition;

  if (typeof options.template === 'string') {
    const base = builtInTemplates[options.template];
    if (!base) {
      throw new Error(`Unknown built-in template: ${options.template}`);
    }
    template = applyStyleOverrides(
      JSON.parse(JSON.stringify(base)),
      options.style
    );

    // Contract-specific: expand sections
    if (options.template === 'contract') {
      template = expandContractSections(template, options.data);
    }
  } else {
    template = options.template;
  }

  return NeuroDoc.generateFromTemplate({
    templateJson: JSON.stringify(template),
    dataJson: JSON.stringify(options.data),
    fileName: options.fileName,
  });
}

function applyStyleOverrides(
  template: TemplateDefinition,
  style?: GenerateOptions['style']
): TemplateDefinition {
  if (!style) {
    // Apply defaults
    return JSON.parse(
      JSON.stringify(template)
        .replace(/\{\{primaryColor\}\}/g, '#000000')
        .replace(/\{\{secondaryColor\}\}/g, '#666666')
        .replace(/\{\{accentColor\}\}/g, '#007AFF')
        .replace(/\{\{logoUrl\}\}/g, '')
    );
  }

  const json = JSON.stringify(template);
  const replaced = json
    .replace(/\{\{primaryColor\}\}/g, style.primaryColor ?? '#000000')
    .replace(/\{\{secondaryColor\}\}/g, style.secondaryColor ?? '#666666')
    .replace(/\{\{accentColor\}\}/g, style.accentColor ?? '#007AFF')
    .replace(/\{\{logoUrl\}\}/g, style.logoUrl ?? '');
  return JSON.parse(replaced);
}

export type {
  TemplateDefinition,
  TemplateElement,
  TextElement,
  ImageElement,
  LineElement,
  SpacerElement,
  RectElement,
  ColumnsElement,
  TableElement,
  KeyValueElement,
  TableColumn,
  FontSpec,
  PageSize,
  Margins,
  Alignment,
  Color,
} from 'react-native-neurodoc';
