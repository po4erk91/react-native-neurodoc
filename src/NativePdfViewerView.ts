import type { HostComponent, ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Double,
  Int32,
  WithDefault,
} from 'react-native/Libraries/Types/CodegenTypesNamespace';
import { codegenNativeCommands } from 'react-native';
import { codegenNativeComponent } from 'react-native';

export interface NativePdfViewerViewProps extends ViewProps {
  pdfUrl: string;
  pageIndex?: Int32;
  spacing?: Double;
  showScrollIndicator?: WithDefault<boolean, true>;
  minZoom?: Double;
  maxZoom?: Double;
  displayMode?: string;
  showThumbnails?: WithDefault<boolean, false>;

  onPageChanged?: DirectEventHandler<
    Readonly<{
      pageIndex: Int32;
      pageCount: Int32;
    }>
  >;
  onDocumentLoaded?: DirectEventHandler<
    Readonly<{
      pageCount: Int32;
    }>
  >;
  onDocumentLoadFailed?: DirectEventHandler<
    Readonly<{
      error: string;
    }>
  >;
  onLongPress?: DirectEventHandler<
    Readonly<{
      pageIndex: Int32;
      x: Double;
      y: Double;
    }>
  >;
  onDocumentChanged?: DirectEventHandler<
    Readonly<{
      pdfUrl: string;
      pageCount: Int32;
    }>
  >;

  // Overlay props
  textOverlays?: string; // JSON string: [{id, pageIndex, x, y, width, height, selected, label}]
  enableOverlayTap?: WithDefault<boolean, false>;
  disableSelection?: WithDefault<boolean, false>;

  onOverlayTap?: DirectEventHandler<
    Readonly<{
      id: string;
      pageIndex: Int32;
    }>
  >;
  onTap?: DirectEventHandler<
    Readonly<{
      pageIndex: Int32;
      x: Double;
      y: Double;
    }>
  >;
  onOverlayMoved?: DirectEventHandler<
    Readonly<{
      id: string;
      x: Double;
      y: Double;
    }>
  >;
  onOverlayResized?: DirectEventHandler<
    Readonly<{
      id: string;
      x: Double;
      y: Double;
      width: Double;
      height: Double;
    }>
  >;
  onTextSelected?: DirectEventHandler<
    Readonly<{
      text: string;
      pageIndex: Int32;
      x: Double;
      y: Double;
      width: Double;
      height: Double;
    }>
  >;
  onAddFieldFromSelection?: DirectEventHandler<
    Readonly<{
      text: string;
      pageIndex: Int32;
      x: Double;
      y: Double;
      width: Double;
      height: Double;
    }>
  >;
}

export type PdfViewerViewType = HostComponent<NativePdfViewerViewProps>;

interface NativeCommands {
  goToPage: (
    viewRef: React.ElementRef<PdfViewerViewType>,
    pageIndex: Int32
  ) => void;
  zoomTo: (viewRef: React.ElementRef<PdfViewerViewType>, scale: Double) => void;
}

export const Commands: NativeCommands = codegenNativeCommands<NativeCommands>({
  supportedCommands: ['goToPage', 'zoomTo'],
});

export default codegenNativeComponent<NativePdfViewerViewProps>(
  'NeurodocPdfViewerView'
) as PdfViewerViewType;
