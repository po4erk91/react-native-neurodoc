import type { TemplateDefinition } from 'react-native-neurodoc';

export interface InvoiceData {
  companyName: string;
  companyAddress?: string;
  companyEmail?: string;
  companyPhone?: string;
  clientName: string;
  clientAddress?: string;
  clientEmail?: string;
  invoiceNumber: string;
  invoiceDate: string;
  dueDate: string;
  items: Array<{
    description: string;
    quantity: number | string;
    rate: number | string;
    amount: number | string;
  }>;
  subtotal: string;
  tax?: string;
  discount?: string;
  total: string;
  notes?: string;
  paymentTerms?: string;
}

export const invoiceTemplate: TemplateDefinition = {
  pageSize: { width: 595, height: 842 },
  margins: { top: 40, right: 40, bottom: 40, left: 40 },
  defaultFont: { family: 'Helvetica', size: 10, color: '#333333' },
  body: [
    // Header with company name
    {
      type: 'columns',
      columns: [
        {
          width: 1,
          elements: [
            {
              type: 'text',
              content: '{{companyName}}',
              font: { size: 20, bold: true, color: '{{primaryColor}}' },
            },
            {
              type: 'text',
              content: '{{companyAddress}}',
              font: { size: 9, color: '#666666' },
            },
            {
              type: 'text',
              content: '{{companyEmail}}',
              font: { size: 9, color: '#666666' },
            },
            {
              type: 'text',
              content: '{{companyPhone}}',
              font: { size: 9, color: '#666666' },
            },
          ],
        },
        {
          width: 1,
          elements: [
            {
              type: 'text',
              content: 'INVOICE',
              font: { size: 28, bold: true, color: '{{accentColor}}' },
              alignment: 'right',
            },
          ],
        },
      ],
      marginBottom: 20,
    },
    // Divider
    { type: 'line', thickness: 2, color: '{{accentColor}}', marginBottom: 20 },
    // Client info + Invoice details
    {
      type: 'columns',
      columns: [
        {
          width: 1,
          elements: [
            {
              type: 'text',
              content: 'Bill To:',
              font: { size: 9, bold: true, color: '#999999' },
              marginBottom: 4,
            },
            {
              type: 'text',
              content: '{{clientName}}',
              font: { size: 11, bold: true },
            },
            {
              type: 'text',
              content: '{{clientAddress}}',
              font: { size: 9, color: '#666666' },
            },
            {
              type: 'text',
              content: '{{clientEmail}}',
              font: { size: 9, color: '#666666' },
            },
          ],
        },
        {
          width: 1,
          elements: [
            {
              type: 'keyValue',
              entries: [
                { label: 'Invoice #: ', value: '{{invoiceNumber}}' },
                { label: 'Date: ', value: '{{invoiceDate}}' },
                { label: 'Due Date: ', value: '{{dueDate}}' },
              ],
              labelFont: { size: 9, bold: true, color: '#999999' },
              valueFont: { size: 10 },
            },
          ],
        },
      ],
      marginBottom: 20,
    },
    // Items table
    {
      type: 'table',
      columns: [
        { header: 'Description', key: 'description', width: 4, alignment: 'left' },
        { header: 'Qty', key: 'quantity', width: 1, alignment: 'center' },
        { header: 'Rate', key: 'rate', width: 1, alignment: 'right' },
        { header: 'Amount', key: 'amount', width: 1, alignment: 'right' },
      ],
      dataKey: 'items',
      headerFont: { size: 9, bold: true, color: '#333333' },
      bodyFont: { size: 10 },
      stripeColor: '#F9F9F9',
      showGridLines: true,
      gridLineColor: '#E0E0E0',
      marginBottom: 10,
    },
    // Totals (right-aligned)
    {
      type: 'columns',
      columns: [
        { width: 4, elements: [] },
        {
          width: 3,
          elements: [
            {
              type: 'keyValue',
              entries: [
                { label: 'Subtotal: ', value: '{{subtotal}}' },
                { label: 'Tax: ', value: '{{tax}}' },
                { label: 'Discount: ', value: '{{discount}}' },
              ],
              labelFont: { size: 10, color: '#666666' },
              valueFont: { size: 10 },
              gap: 10,
            },
            {
              type: 'line',
              thickness: 1,
              color: '#E0E0E0',
              marginBottom: 4,
            },
            {
              type: 'keyValue',
              entries: [{ label: 'Total: ', value: '{{total}}' }],
              labelFont: { size: 12, bold: true, color: '{{primaryColor}}' },
              valueFont: { size: 12, bold: true, color: '{{primaryColor}}' },
              gap: 10,
            },
          ],
        },
      ],
      marginBottom: 20,
    },
    // Notes
    { type: 'line', thickness: 1, color: '#E0E0E0', marginBottom: 10 },
    {
      type: 'text',
      content: '{{notes}}',
      font: { size: 9, color: '#666666' },
      marginBottom: 8,
    },
    {
      type: 'text',
      content: '{{paymentTerms}}',
      font: { size: 9, color: '#666666' },
      marginBottom: 20,
    },
    // Footer
    {
      type: 'text',
      content: 'Thank you for your business!',
      font: { size: 10, italic: true, color: '#999999' },
      alignment: 'center',
    },
  ],
};
