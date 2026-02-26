import type {
  TemplateDefinition,
  TemplateElement,
} from 'react-native-neurodoc';

export interface ContractData {
  title: string;
  date: string;
  partyA: string;
  partyAAddress?: string;
  partyB: string;
  partyBAddress?: string;
  sections: Array<{
    heading: string;
    content: string;
  }>;
  signatureDate?: string;
}

export const contractTemplate: TemplateDefinition = {
  pageSize: { width: 595, height: 842 },
  margins: { top: 60, right: 60, bottom: 60, left: 60 },
  defaultFont: { family: 'Times', size: 11, color: '#333333' },
  footer: [
    {
      type: 'line',
      thickness: 0.5,
      color: '#CCCCCC',
      marginBottom: 4,
    },
    {
      type: 'text',
      content: '{{title}}',
      font: { size: 8, color: '#999999' },
      alignment: 'center',
    },
  ],
  body: [
    // Title
    {
      type: 'text',
      content: '{{title}}',
      font: { size: 20, bold: true, color: '{{primaryColor}}' },
      alignment: 'center',
      marginBottom: 8,
    },
    // Date
    {
      type: 'text',
      content: 'Date: {{date}}',
      font: { size: 10, color: '#666666' },
      alignment: 'center',
      marginBottom: 24,
    },
    // Parties
    {
      type: 'text',
      content: 'BETWEEN',
      font: { size: 10, bold: true, color: '#999999' },
      alignment: 'center',
      marginBottom: 12,
    },
    {
      type: 'columns',
      columns: [
        {
          width: 1,
          elements: [
            {
              type: 'text',
              content: 'Party A',
              font: { size: 9, bold: true, color: '#999999' },
              marginBottom: 4,
            },
            {
              type: 'text',
              content: '{{partyA}}',
              font: { size: 11, bold: true },
              marginBottom: 2,
            },
            {
              type: 'text',
              content: '{{partyAAddress}}',
              font: { size: 9, color: '#666666' },
            },
          ],
        },
        {
          width: 1,
          elements: [
            {
              type: 'text',
              content: 'Party B',
              font: { size: 9, bold: true, color: '#999999' },
              marginBottom: 4,
            },
            {
              type: 'text',
              content: '{{partyB}}',
              font: { size: 11, bold: true },
              marginBottom: 2,
            },
            {
              type: 'text',
              content: '{{partyBAddress}}',
              font: { size: 9, color: '#666666' },
            },
          ],
        },
      ],
      gap: 30,
      marginBottom: 20,
    },
    { type: 'line', thickness: 1, color: '#E0E0E0', marginBottom: 20 },
    // Sections placeholder — expanded dynamically in generatePdf()
    // The __sections__ marker will be replaced with actual section elements
    {
      type: 'text',
      content: '__sections__',
      font: { size: 0 },
    },
    // Signature block
    { type: 'spacer', height: 40 },
    {
      type: 'text',
      content:
        'IN WITNESS WHEREOF, the parties have executed this agreement as of {{signatureDate}}.',
      font: { size: 10, italic: true },
      marginBottom: 30,
    },
    {
      type: 'columns',
      columns: [
        {
          width: 1,
          elements: [
            { type: 'line', thickness: 1, color: '#333333', marginBottom: 4 },
            {
              type: 'text',
              content: '{{partyA}}',
              font: { size: 10 },
              alignment: 'center',
            },
          ],
        },
        {
          width: 1,
          elements: [
            { type: 'line', thickness: 1, color: '#333333', marginBottom: 4 },
            {
              type: 'text',
              content: '{{partyB}}',
              font: { size: 10 },
              alignment: 'center',
            },
          ],
        },
      ],
      gap: 40,
    },
  ],
};

/**
 * Expand the __sections__ placeholder into real TemplateElements
 * based on the data.sections array.
 */
export function expandContractSections(
  template: TemplateDefinition,
  data: Record<string, unknown>
): TemplateDefinition {
  const sections = data.sections as
    | Array<{ heading: string; content: string }>
    | undefined;
  if (!sections || sections.length === 0) {
    // Remove the placeholder
    return {
      ...template,
      body: template.body.filter(
        (el) => !(el.type === 'text' && el.content === '__sections__')
      ),
    };
  }

  const sectionElements: TemplateElement[] = [];
  sections.forEach((section, index) => {
    sectionElements.push({
      type: 'text',
      content: `${index + 1}. ${section.heading}`,
      font: { size: 12, bold: true },
      marginBottom: 6,
    });
    sectionElements.push({
      type: 'text',
      content: section.content,
      font: { size: 11 },
      marginBottom: 16,
    });
  });

  const newBody: TemplateElement[] = [];
  for (const el of template.body) {
    if (el.type === 'text' && el.content === '__sections__') {
      newBody.push(...sectionElements);
    } else {
      newBody.push(el);
    }
  }

  return { ...template, body: newBody };
}
