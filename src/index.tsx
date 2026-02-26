export { default as NeuroDoc, isNeurodocError } from './NativeNeurodoc';
export type {
  Spec as NeurodocSpec,
  NeurodocError,
  NeurodocErrorCode,
} from './NativeNeurodoc';

// Native view (codegen spec + commands) — consumers build their own wrapper
export {
  default as NativePdfViewerView,
  Commands as PdfViewerCommands,
} from './NativePdfViewerView';
export type {
  NativePdfViewerViewProps,
  PdfViewerViewType,
} from './NativePdfViewerView';

// Template types — consumers can define custom templates
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
} from './templates/types';
