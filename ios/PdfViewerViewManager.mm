#import <React/RCTViewManager.h>
#import <React/RCTUIManager.h>

#if __has_include("react_native_neurodoc/react_native_neurodoc-Swift.h")
#import "react_native_neurodoc/react_native_neurodoc-Swift.h"
#elif __has_include("react-native-neurodoc/react-native-neurodoc-Swift.h")
#import "react-native-neurodoc/react-native-neurodoc-Swift.h"
#else
#import "Neurodoc-Swift.h"
#endif

@interface NeurodocPdfViewerViewManager : RCTViewManager
@end

@implementation NeurodocPdfViewerViewManager

RCT_EXPORT_MODULE(NeurodocPdfViewerView)

- (UIView *)view {
    return [[PdfViewerView alloc] init];
}

RCT_EXPORT_VIEW_PROPERTY(pdfUrl, NSString)
RCT_EXPORT_VIEW_PROPERTY(pageIndex, NSInteger)
RCT_EXPORT_VIEW_PROPERTY(spacing, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(showScrollIndicator, BOOL)
RCT_EXPORT_VIEW_PROPERTY(minZoom, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(maxZoom, CGFloat)
RCT_EXPORT_VIEW_PROPERTY(displayMode, NSString)
RCT_EXPORT_VIEW_PROPERTY(showThumbnails, BOOL)
RCT_EXPORT_VIEW_PROPERTY(onPageChanged, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onDocumentLoaded, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onDocumentLoadFailed, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onLongPress, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onDocumentChanged, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(textOverlays, NSString)
RCT_EXPORT_VIEW_PROPERTY(enableOverlayTap, BOOL)
RCT_EXPORT_VIEW_PROPERTY(disableSelection, BOOL)
RCT_EXPORT_VIEW_PROPERTY(onOverlayTap, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onTap, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onOverlayMoved, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onOverlayResized, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onTextSelected, RCTDirectEventBlock)
RCT_EXPORT_VIEW_PROPERTY(onAddFieldFromSelection, RCTDirectEventBlock)

RCT_EXPORT_METHOD(goToPage:(nonnull NSNumber *)reactTag pageIndex:(NSInteger)pageIndex) {
    [self.bridge.uiManager addUIBlock:^(RCTUIManager *uiManager, NSDictionary<NSNumber *, UIView *> *viewRegistry) {
        PdfViewerView *view = (PdfViewerView *)viewRegistry[reactTag];
        if ([view isKindOfClass:[PdfViewerView class]]) {
            [view goToPage:pageIndex];
        }
    }];
}

RCT_EXPORT_METHOD(zoomTo:(nonnull NSNumber *)reactTag scale:(CGFloat)scale) {
    [self.bridge.uiManager addUIBlock:^(RCTUIManager *uiManager, NSDictionary<NSNumber *, UIView *> *viewRegistry) {
        PdfViewerView *view = (PdfViewerView *)viewRegistry[reactTag];
        if ([view isKindOfClass:[PdfViewerView class]]) {
            [view zoomTo:scale];
        }
    }];
}

@end
