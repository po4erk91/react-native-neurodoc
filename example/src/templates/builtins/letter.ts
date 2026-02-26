import type { TemplateDefinition } from 'react-native-neurodoc';

export interface LetterData {
  senderName: string;
  senderAddress?: string;
  senderPhone?: string;
  senderEmail?: string;
  recipientName: string;
  recipientAddress?: string;
  date: string;
  subject?: string;
  greeting: string;
  body: string;
  closing: string;
  senderSignatureName: string;
}

export const letterTemplate: TemplateDefinition = {
  pageSize: { width: 595, height: 842 },
  margins: { top: 50, right: 50, bottom: 50, left: 50 },
  defaultFont: { family: 'Times', size: 11, color: '#333333' },
  body: [
    // Sender info
    {
      type: 'text',
      content: '{{senderName}}',
      font: { size: 14, bold: true, color: '{{primaryColor}}' },
      marginBottom: 2,
    },
    {
      type: 'text',
      content: '{{senderAddress}}',
      font: { size: 9, color: '#666666' },
    },
    {
      type: 'text',
      content: '{{senderPhone}}',
      font: { size: 9, color: '#666666' },
    },
    {
      type: 'text',
      content: '{{senderEmail}}',
      font: { size: 9, color: '#666666' },
      marginBottom: 20,
    },
    // Date
    {
      type: 'text',
      content: '{{date}}',
      marginBottom: 16,
    },
    // Recipient
    {
      type: 'text',
      content: '{{recipientName}}',
      font: { bold: true },
      marginBottom: 2,
    },
    {
      type: 'text',
      content: '{{recipientAddress}}',
      font: { size: 10, color: '#666666' },
      marginBottom: 20,
    },
    // Subject
    {
      type: 'text',
      content: 'Re: {{subject}}',
      font: { size: 11, bold: true },
      marginBottom: 16,
    },
    // Greeting
    {
      type: 'text',
      content: '{{greeting}}',
      marginBottom: 12,
    },
    // Body text
    {
      type: 'text',
      content: '{{body}}',
      font: { size: 11 },
      marginBottom: 20,
    },
    // Closing
    {
      type: 'text',
      content: '{{closing}}',
      marginBottom: 30,
    },
    // Signature
    {
      type: 'text',
      content: '{{senderSignatureName}}',
      font: { bold: true },
    },
  ],
};
