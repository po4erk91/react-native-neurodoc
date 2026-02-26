import {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useMemo,
  useRef,
} from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import {
  NativePdfViewerView,
  PdfViewerCommands as Commands,
  type PdfViewerViewType,
} from 'react-native-neurodoc';

export interface PdfViewerRef {
  goToPage: (pageIndex: number) => void;
  zoomTo: (scale: number) => void;
}

export interface TextOverlay {
  id: string;
  pageIndex: number;
  x: number;
  y: number;
  width: number;
  height: number;
  selected?: boolean;
  label?: string;
}

export interface PdfViewerProps {
  pdfUrl: string;
  pageIndex?: number;
  spacing?: number;
  showScrollIndicator?: boolean;
  minZoom?: number;
  maxZoom?: number;
  displayMode?: 'scroll' | 'single' | 'grid';
  showThumbnails?: boolean;
  onPageChanged?: (pageIndex: number, pageCount: number) => void;
  onDocumentLoaded?: (pageCount: number) => void;
  onDocumentLoadFailed?: (error: string) => void;
  onLongPress?: (pageIndex: number, x: number, y: number) => void;
  onDocumentChanged?: (pdfUrl: string, pageCount: number) => void;
  // Overlay props
  textOverlays?: TextOverlay[];
  enableOverlayTap?: boolean;
  disableSelection?: boolean;
  onOverlayTap?: (id: string, pageIndex: number) => void;
  onTap?: (pageIndex: number, x: number, y: number) => void;
  onOverlayMoved?: (id: string, x: number, y: number) => void;
  onOverlayResized?: (
    id: string,
    x: number,
    y: number,
    width: number,
    height: number
  ) => void;
  onTextSelected?: (
    text: string,
    pageIndex: number,
    x: number,
    y: number,
    width: number,
    height: number
  ) => void;
  onAddFieldFromSelection?: (
    text: string,
    pageIndex: number,
    x: number,
    y: number,
    width: number,
    height: number
  ) => void;
  style?: StyleProp<ViewStyle>;
}

export const PdfViewer = forwardRef<PdfViewerRef, PdfViewerProps>(
  (
    {
      pdfUrl,
      pageIndex = 0,
      spacing,
      showScrollIndicator,
      minZoom,
      maxZoom,
      displayMode,
      showThumbnails,
      onPageChanged,
      onDocumentLoaded,
      onDocumentLoadFailed,
      onLongPress,
      onDocumentChanged,
      textOverlays,
      enableOverlayTap,
      disableSelection,
      onOverlayTap,
      onTap,
      onOverlayMoved,
      onOverlayResized,
      onTextSelected,
      onAddFieldFromSelection,
      style,
    },
    ref
  ) => {
    const nativeRef = useRef<React.ElementRef<PdfViewerViewType>>(null);

    useImperativeHandle(ref, () => ({
      goToPage: (idx: number) => {
        if (nativeRef.current) {
          Commands.goToPage(nativeRef.current, idx);
        }
      },
      zoomTo: (scale: number) => {
        if (nativeRef.current) {
          Commands.zoomTo(nativeRef.current, scale);
        }
      },
    }));

    const handlePageChanged = useCallback(
      (event: { nativeEvent: { pageIndex: number; pageCount: number } }) => {
        onPageChanged?.(event.nativeEvent.pageIndex, event.nativeEvent.pageCount);
      },
      [onPageChanged]
    );

    const handleDocumentLoaded = useCallback(
      (event: { nativeEvent: { pageCount: number } }) => {
        onDocumentLoaded?.(event.nativeEvent.pageCount);
      },
      [onDocumentLoaded]
    );

    const handleDocumentLoadFailed = useCallback(
      (event: { nativeEvent: { error: string } }) => {
        onDocumentLoadFailed?.(event.nativeEvent.error);
      },
      [onDocumentLoadFailed]
    );

    const handleLongPress = useCallback(
      (event: { nativeEvent: { pageIndex: number; x: number; y: number } }) => {
        onLongPress?.(
          event.nativeEvent.pageIndex,
          event.nativeEvent.x,
          event.nativeEvent.y
        );
      },
      [onLongPress]
    );

    const handleDocumentChanged = useCallback(
      (event: { nativeEvent: { pdfUrl: string; pageCount: number } }) => {
        onDocumentChanged?.(event.nativeEvent.pdfUrl, event.nativeEvent.pageCount);
      },
      [onDocumentChanged]
    );

    const handleOverlayTap = useCallback(
      (event: { nativeEvent: { id: string; pageIndex: number } }) => {
        onOverlayTap?.(event.nativeEvent.id, event.nativeEvent.pageIndex);
      },
      [onOverlayTap]
    );

    const handleTap = useCallback(
      (event: { nativeEvent: { pageIndex: number; x: number; y: number } }) => {
        onTap?.(
          event.nativeEvent.pageIndex,
          event.nativeEvent.x,
          event.nativeEvent.y
        );
      },
      [onTap]
    );

    const handleOverlayMoved = useCallback(
      (event: { nativeEvent: { id: string; x: number; y: number } }) => {
        onOverlayMoved?.(
          event.nativeEvent.id,
          event.nativeEvent.x,
          event.nativeEvent.y
        );
      },
      [onOverlayMoved]
    );

    const handleOverlayResized = useCallback(
      (event: {
        nativeEvent: {
          id: string;
          x: number;
          y: number;
          width: number;
          height: number;
        };
      }) => {
        onOverlayResized?.(
          event.nativeEvent.id,
          event.nativeEvent.x,
          event.nativeEvent.y,
          event.nativeEvent.width,
          event.nativeEvent.height
        );
      },
      [onOverlayResized]
    );

    const handleTextSelected = useCallback(
      (event: {
        nativeEvent: {
          text: string;
          pageIndex: number;
          x: number;
          y: number;
          width: number;
          height: number;
        };
      }) => {
        onTextSelected?.(
          event.nativeEvent.text,
          event.nativeEvent.pageIndex,
          event.nativeEvent.x,
          event.nativeEvent.y,
          event.nativeEvent.width,
          event.nativeEvent.height
        );
      },
      [onTextSelected]
    );

    const handleAddFieldFromSelection = useCallback(
      (event: {
        nativeEvent: {
          text: string;
          pageIndex: number;
          x: number;
          y: number;
          width: number;
          height: number;
        };
      }) => {
        onAddFieldFromSelection?.(
          event.nativeEvent.text,
          event.nativeEvent.pageIndex,
          event.nativeEvent.x,
          event.nativeEvent.y,
          event.nativeEvent.width,
          event.nativeEvent.height
        );
      },
      [onAddFieldFromSelection]
    );

    const serializedOverlays = useMemo(
      () => (textOverlays ? JSON.stringify(textOverlays) : undefined),
      [textOverlays]
    );

    return (
      <NativePdfViewerView
        ref={nativeRef}
        pdfUrl={pdfUrl}
        pageIndex={pageIndex}
        spacing={spacing}
        showScrollIndicator={showScrollIndicator}
        minZoom={minZoom}
        maxZoom={maxZoom}
        displayMode={displayMode}
        showThumbnails={showThumbnails}
        onPageChanged={handlePageChanged}
        onDocumentLoaded={handleDocumentLoaded}
        onDocumentLoadFailed={handleDocumentLoadFailed}
        onLongPress={handleLongPress}
        onDocumentChanged={handleDocumentChanged}
        textOverlays={serializedOverlays}
        enableOverlayTap={enableOverlayTap}
        disableSelection={disableSelection}
        onOverlayTap={handleOverlayTap}
        onTap={handleTap}
        onOverlayMoved={handleOverlayMoved}
        onOverlayResized={handleOverlayResized}
        onTextSelected={handleTextSelected}
        onAddFieldFromSelection={handleAddFieldFromSelection}
        style={style}
      />
    );
  }
);

PdfViewer.displayName = 'PdfViewer';
