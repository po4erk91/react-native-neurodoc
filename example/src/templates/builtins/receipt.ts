import type { TemplateDefinition } from 'react-native-neurodoc';

export interface ReceiptData {
  businessName: string;
  businessAddress?: string;
  receiptNumber: string;
  date: string;
  items: Array<{
    description: string;
    quantity: number | string;
    price: number | string;
  }>;
  subtotal: string;
  tax?: string;
  total: string;
  paymentMethod?: string;
  customerName?: string;
}

export const receiptTemplate: TemplateDefinition = {
  pageSize: { width: 396, height: 612 },
  margins: { top: 30, right: 30, bottom: 30, left: 30 },
  defaultFont: { family: 'Helvetica', size: 10, color: '#333333' },
  body: [
    // Business name
    {
      type: 'text',
      content: '{{businessName}}',
      font: { size: 18, bold: true, color: '{{primaryColor}}' },
      alignment: 'center',
      marginBottom: 4,
    },
    {
      type: 'text',
      content: '{{businessAddress}}',
      font: { size: 9, color: '#666666' },
      alignment: 'center',
      marginBottom: 12,
    },
    // Divider
    { type: 'line', thickness: 1, color: '#CCCCCC', marginBottom: 12 },
    // Receipt details
    {
      type: 'keyValue',
      entries: [
        { label: 'Receipt #: ', value: '{{receiptNumber}}' },
        { label: 'Date: ', value: '{{date}}' },
        { label: 'Customer: ', value: '{{customerName}}' },
      ],
      labelFont: { size: 9, bold: true, color: '#999999' },
      valueFont: { size: 10 },
      marginBottom: 12,
    },
    // Divider
    { type: 'line', thickness: 1, color: '#CCCCCC', marginBottom: 8 },
    // Items table
    {
      type: 'table',
      columns: [
        { header: 'Item', key: 'description', width: 3, alignment: 'left' },
        { header: 'Qty', key: 'quantity', width: 1, alignment: 'center' },
        { header: 'Price', key: 'price', width: 1, alignment: 'right' },
      ],
      dataKey: 'items',
      headerFont: { size: 9, bold: true },
      bodyFont: { size: 10 },
      showGridLines: true,
      gridLineColor: '#E0E0E0',
      marginBottom: 8,
    },
    // Divider
    { type: 'line', thickness: 1, color: '#CCCCCC', marginBottom: 8 },
    // Totals
    {
      type: 'keyValue',
      entries: [
        { label: 'Subtotal: ', value: '{{subtotal}}' },
        { label: 'Tax: ', value: '{{tax}}' },
      ],
      labelFont: { size: 10, color: '#666666' },
      valueFont: { size: 10 },
      gap: 10,
      marginBottom: 4,
    },
    { type: 'line', thickness: 1, color: '#E0E0E0', marginBottom: 4 },
    {
      type: 'keyValue',
      entries: [{ label: 'TOTAL: ', value: '{{total}}' }],
      labelFont: { size: 13, bold: true, color: '{{primaryColor}}' },
      valueFont: { size: 13, bold: true, color: '{{primaryColor}}' },
      gap: 10,
      marginBottom: 12,
    },
    // Payment method
    { type: 'line', thickness: 1, color: '#CCCCCC', marginBottom: 8 },
    {
      type: 'text',
      content: 'Payment: {{paymentMethod}}',
      font: { size: 9, color: '#666666' },
      alignment: 'center',
      marginBottom: 16,
    },
    // Thank you
    {
      type: 'text',
      content: 'Thank you!',
      font: { size: 12, bold: true, color: '#999999' },
      alignment: 'center',
    },
  ],
};
