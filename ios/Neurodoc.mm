#import "Neurodoc.h"
#import <React/RCTViewManager.h>

// Import Swift-generated header
#if __has_include("react_native_neurodoc/react_native_neurodoc-Swift.h")
#import "react_native_neurodoc/react_native_neurodoc-Swift.h"
#elif __has_include("react-native-neurodoc/react-native-neurodoc-Swift.h")
#import "react-native-neurodoc/react-native-neurodoc-Swift.h"
#else
#import "Neurodoc-Swift.h"
#endif

@implementation Neurodoc {
    NeurodocImpl *_impl;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _impl = [[NeurodocImpl alloc] init];
    }
    return self;
}

+ (NSString *)moduleName {
    return @"Neurodoc";
}

// MARK: - getMetadata

- (void)getMetadata:(NSString *)pdfUrl
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
    [_impl getMetadataWithPdfUrl:pdfUrl
                        resolver:^(NSDictionary *result) { resolve(result); }
                        rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - recognizePage

- (void)recognizePage:(JS::NativeNeurodoc::SpecRecognizePageOptions &)options
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSInteger pageIndex = (NSInteger)options.pageIndex();
    NSString *language = options.language();

    [_impl recognizePageWithPdfUrl:pdfUrl
                         pageIndex:pageIndex
                          language:language
                          resolver:^(NSDictionary *result) { resolve(result); }
                          rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - makeSearchable

- (void)makeSearchable:(JS::NativeNeurodoc::SpecMakeSearchableOptions &)options
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSString *language = options.language();

    NSMutableArray<NSNumber *> *pageIndexes = nil;
    auto rawIndexes = options.pageIndexes();
    if (rawIndexes.has_value()) {
        pageIndexes = [NSMutableArray new];
        auto vec = rawIndexes.value();
        for (size_t i = 0; i < vec.size(); i++) {
            [pageIndexes addObject:@(vec[i])];
        }
    }

    [_impl makeSearchableWithPdfUrl:pdfUrl
                           language:language
                        pageIndexes:pageIndexes
                           resolver:^(NSDictionary *result) { resolve(result); }
                           rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - merge

- (void)merge:(JS::NativeNeurodoc::SpecMergeOptions &)options
      resolve:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject {
    auto lazyUrls = options.pdfUrls();
    NSMutableArray<NSString *> *pdfUrls = [NSMutableArray new];
    for (size_t i = 0; i < lazyUrls.size(); i++) {
        [pdfUrls addObject:lazyUrls[i]];
    }
    NSString *fileName = options.fileName() ?: @"merged";

    [_impl mergeWithPdfUrls:pdfUrls
                   fileName:fileName
                   resolver:^(NSDictionary *result) { resolve(result); }
                   rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - split

- (void)split:(JS::NativeNeurodoc::SpecSplitOptions &)options
      resolve:(RCTPromiseResolveBlock)resolve
       reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    auto rawRanges = options.ranges();
    NSMutableArray<NSArray<NSNumber *> *> *ranges = [NSMutableArray new];
    for (size_t i = 0; i < rawRanges.size(); i++) {
        auto range = rawRanges[i];
        NSMutableArray<NSNumber *> *pair = [NSMutableArray new];
        for (size_t j = 0; j < range.size(); j++) {
            [pair addObject:@(range[j])];
        }
        [ranges addObject:pair];
    }

    [_impl splitWithPdfUrl:pdfUrl
                    ranges:ranges
                  resolver:^(NSDictionary *result) { resolve(result); }
                  rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - deletePages

- (void)deletePages:(JS::NativeNeurodoc::SpecDeletePagesOptions &)options
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    auto rawIndexes = options.pageIndexes();
    NSMutableArray<NSNumber *> *pageIndexes = [NSMutableArray new];
    for (size_t i = 0; i < rawIndexes.size(); i++) {
        [pageIndexes addObject:@(rawIndexes[i])];
    }

    [_impl deletePagesWithPdfUrl:pdfUrl
                     pageIndexes:pageIndexes
                        resolver:^(NSDictionary *result) { resolve(result); }
                        rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - reorderPages

- (void)reorderPages:(JS::NativeNeurodoc::SpecReorderPagesOptions &)options
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    auto rawOrder = options.order();
    NSMutableArray<NSNumber *> *order = [NSMutableArray new];
    for (size_t i = 0; i < rawOrder.size(); i++) {
        [order addObject:@(rawOrder[i])];
    }

    [_impl reorderPagesWithPdfUrl:pdfUrl
                            order:order
                         resolver:^(NSDictionary *result) { resolve(result); }
                         rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - addAnnotations

- (void)addAnnotations:(JS::NativeNeurodoc::SpecAddAnnotationsOptions &)options
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    auto rawAnnotations = options.annotations();
    NSMutableArray<NSDictionary *> *annotations = [NSMutableArray new];

    for (size_t i = 0; i < rawAnnotations.size(); i++) {
        auto a = rawAnnotations[i];
        NSMutableDictionary *dict = [NSMutableDictionary new];
        dict[@"type"] = a.type();
        dict[@"pageIndex"] = @(a.pageIndex());

        if (a.color()) dict[@"color"] = a.color();
        if (a.opacity().has_value()) dict[@"opacity"] = @(a.opacity().value());
        if (a.x().has_value()) dict[@"x"] = @(a.x().value());
        if (a.y().has_value()) dict[@"y"] = @(a.y().value());
        if (a.text()) dict[@"text"] = a.text();
        if (a.strokeWidth().has_value()) dict[@"strokeWidth"] = @(a.strokeWidth().value());

        auto rawRects = a.rects();
        if (rawRects.has_value()) {
            auto rectsVec = rawRects.value();
            NSMutableArray *rects = [NSMutableArray new];
            for (size_t j = 0; j < rectsVec.size(); j++) {
                auto r = rectsVec[j];
                [rects addObject:@{
                    @"x": @(r.x()),
                    @"y": @(r.y()),
                    @"width": @(r.width()),
                    @"height": @(r.height()),
                }];
            }
            dict[@"rects"] = rects;
        }

        auto rawPoints = a.points();
        if (rawPoints.has_value()) {
            auto pointsVec = rawPoints.value();
            NSMutableArray *points = [NSMutableArray new];
            for (size_t j = 0; j < pointsVec.size(); j++) {
                auto pt = pointsVec[j];
                NSMutableArray *pair = [NSMutableArray new];
                for (size_t k = 0; k < pt.size(); k++) {
                    [pair addObject:@(pt[k])];
                }
                [points addObject:pair];
            }
            dict[@"points"] = points;
        }

        [annotations addObject:dict];
    }

    [_impl addAnnotationsWithPdfUrl:pdfUrl
                        annotations:annotations
                           resolver:^(NSDictionary *result) { resolve(result); }
                           rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - getAnnotations

- (void)getAnnotations:(NSString *)pdfUrl
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
    [_impl getAnnotationsWithPdfUrl:pdfUrl
                           resolver:^(NSDictionary *result) { resolve(result); }
                           rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - deleteAnnotation

- (void)deleteAnnotation:(JS::NativeNeurodoc::SpecDeleteAnnotationOptions &)options
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSString *annotationId = options.annotationId();

    [_impl deleteAnnotationWithPdfUrl:pdfUrl
                         annotationId:annotationId
                             resolver:^(NSDictionary *result) { resolve(result); }
                             rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - getFormFields

- (void)getFormFields:(NSString *)pdfUrl
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
    [_impl getFormFieldsWithPdfUrl:pdfUrl
                          resolver:^(NSDictionary *result) { resolve(result); }
                          rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - fillForm

- (void)fillForm:(JS::NativeNeurodoc::SpecFillFormOptions &)options
         resolve:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    bool flatten = options.flattenAfterFill().has_value() ? options.flattenAfterFill().value() : false;

    auto rawFields = options.fields();
    NSMutableArray<NSDictionary *> *fields = [NSMutableArray new];
    for (size_t i = 0; i < rawFields.size(); i++) {
        auto f = rawFields[i];
        NSMutableDictionary *dict = [NSMutableDictionary new];
        dict[@"id"] = f.id_();
        dict[@"value"] = f.value();
        if (f.fontSize().has_value()) dict[@"fontSize"] = @(f.fontSize().value());
        if (f.fontName()) dict[@"fontName"] = f.fontName();
        [fields addObject:dict];
    }

    [_impl fillFormWithPdfUrl:pdfUrl
                       fields:fields
             flattenAfterFill:flatten
                     resolver:^(NSDictionary *result) { resolve(result); }
                     rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - encrypt

- (void)encrypt:(JS::NativeNeurodoc::SpecEncryptOptions &)options
        resolve:(RCTPromiseResolveBlock)resolve
         reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSString *userPassword = options.userPassword();
    NSString *ownerPassword = options.ownerPassword();
    bool allowPrinting = options.allowPrinting().has_value() ? options.allowPrinting().value() : true;
    bool allowCopying = options.allowCopying().has_value() ? options.allowCopying().value() : true;

    [_impl encryptWithPdfUrl:pdfUrl
                userPassword:userPassword
               ownerPassword:ownerPassword
               allowPrinting:allowPrinting
                allowCopying:allowCopying
                    resolver:^(NSDictionary *result) { resolve(result); }
                    rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - decrypt

- (void)decrypt:(JS::NativeNeurodoc::SpecDecryptOptions &)options
        resolve:(RCTPromiseResolveBlock)resolve
         reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSString *password = options.password();

    [_impl decryptWithPdfUrl:pdfUrl
                    password:password
                    resolver:^(NSDictionary *result) { resolve(result); }
                    rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - addWatermark

- (void)addWatermark:(JS::NativeNeurodoc::SpecAddWatermarkOptions &)options
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSString *text = options.text();
    NSString *imageUrl = options.imageUrl();
    double opacity = options.opacity().has_value() ? options.opacity().value() : 0.3;
    double angle = options.angle().has_value() ? options.angle().value() : 45;
    double fontSize = options.fontSize().has_value() ? options.fontSize().value() : 48;
    NSString *color = options.color() ?: @"#FF0000";

    NSMutableArray<NSNumber *> *pageIndexes = nil;
    auto rawIndexes = options.pageIndexes();
    if (rawIndexes.has_value()) {
        pageIndexes = [NSMutableArray new];
        auto vec = rawIndexes.value();
        for (size_t i = 0; i < vec.size(); i++) {
            [pageIndexes addObject:@(vec[i])];
        }
    }

    [_impl addWatermarkWithPdfUrl:pdfUrl
                             text:text
                         imageUrl:imageUrl
                          opacity:opacity
                            angle:angle
                         fontSize:fontSize
                            color:color
                      pageIndexes:pageIndexes
                         resolver:^(NSDictionary *result) { resolve(result); }
                         rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - redact

- (void)redact:(JS::NativeNeurodoc::SpecRedactOptions &)options
       resolve:(RCTPromiseResolveBlock)resolve
        reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    double dpi = options.dpi().has_value() ? options.dpi().value() : 300.0;
    bool stripMetadata = options.stripMetadata().has_value() ? options.stripMetadata().value() : false;

    auto rawRedactions = options.redactions();
    NSMutableArray<NSDictionary *> *redactions = [NSMutableArray new];

    for (size_t i = 0; i < rawRedactions.size(); i++) {
        auto r = rawRedactions[i];
        NSMutableDictionary *dict = [NSMutableDictionary new];
        dict[@"pageIndex"] = @(r.pageIndex());
        if (r.color()) dict[@"color"] = r.color();

        auto rawRects = r.rects();
        NSMutableArray *rects = [NSMutableArray new];
        for (size_t j = 0; j < rawRects.size(); j++) {
            auto rect = rawRects[j];
            [rects addObject:@{
                @"x": @(rect.x()),
                @"y": @(rect.y()),
                @"width": @(rect.width()),
                @"height": @(rect.height()),
            }];
        }
        dict[@"rects"] = rects;

        [redactions addObject:dict];
    }

    [_impl redactWithPdfUrl:pdfUrl
                 redactions:redactions
                        dpi:dpi
              stripMetadata:stripMetadata
                   resolver:^(NSDictionary *result) { resolve(result); }
                   rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - editContent

- (void)editContent:(JS::NativeNeurodoc::SpecEditContentOptions &)options
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();

    auto rawEdits = options.edits();
    NSMutableArray<NSDictionary *> *edits = [NSMutableArray new];

    for (size_t i = 0; i < rawEdits.size(); i++) {
        auto e = rawEdits[i];
        NSMutableDictionary *dict = [NSMutableDictionary new];
        dict[@"pageIndex"] = @(e.pageIndex());
        dict[@"newText"] = e.newText();

        auto bbox = e.boundingBox();
        dict[@"boundingBox"] = @{
            @"x": @(bbox.x()),
            @"y": @(bbox.y()),
            @"width": @(bbox.width()),
            @"height": @(bbox.height()),
        };

        if (e.fontSize().has_value()) dict[@"fontSize"] = @(e.fontSize().value());
        if (e.fontName()) dict[@"fontName"] = e.fontName();
        if (e.color()) dict[@"color"] = e.color();

        [edits addObject:dict];
    }

    [_impl editContentWithPdfUrl:pdfUrl
                           edits:edits
                        resolver:^(NSDictionary *result) { resolve(result); }
                        rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - generateFromTemplate

- (void)generateFromTemplate:(JS::NativeNeurodoc::SpecGenerateFromTemplateOptions &)options
                      resolve:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject {
    NSString *templateJson = options.templateJson();
    NSString *dataJson = options.dataJson();
    NSString *fileName = options.fileName() ?: @"";

    [_impl generateFromTemplateWithTemplateJson:templateJson
                                       dataJson:dataJson
                                       fileName:fileName
                                       resolver:^(NSDictionary *result) { resolve(result); }
                                       rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - extractText

- (void)extractText:(JS::NativeNeurodoc::SpecExtractTextOptions &)options
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    NSInteger pageIndex = (NSInteger)options.pageIndex();
    NSString *mode = options.mode() ?: @"auto";
    NSString *language = options.language() ?: @"auto";

    [_impl extractTextWithPdfUrl:pdfUrl
                       pageIndex:pageIndex
                            mode:mode
                        language:language
                        resolver:^(NSDictionary *result) { resolve(result); }
                        rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - createFormFromPdf

- (void)createFormFromPdf:(JS::NativeNeurodoc::SpecCreateFormFromPdfOptions &)options
                  resolve:(RCTPromiseResolveBlock)resolve
                   reject:(RCTPromiseRejectBlock)reject {
    NSString *pdfUrl = options.pdfUrl();
    bool removeOriginalText = options.removeOriginalText().has_value() ? options.removeOriginalText().value() : true;

    auto rawFields = options.fields();
    NSMutableArray<NSDictionary *> *fields = [NSMutableArray new];
    for (size_t i = 0; i < rawFields.size(); i++) {
        auto f = rawFields[i];
        NSMutableDictionary *dict = [NSMutableDictionary new];
        dict[@"name"] = f.name();
        dict[@"pageIndex"] = @(f.pageIndex());

        auto bbox = f.boundingBox();
        dict[@"boundingBox"] = @{
            @"x": @(bbox.x()),
            @"y": @(bbox.y()),
            @"width": @(bbox.width()),
            @"height": @(bbox.height()),
        };

        if (f.type()) dict[@"type"] = f.type();
        if (f.defaultValue()) dict[@"defaultValue"] = f.defaultValue();
        if (f.fontSize().has_value()) dict[@"fontSize"] = @(f.fontSize().value());
        if (f.fontName()) dict[@"fontName"] = f.fontName();

        [fields addObject:dict];
    }

    [_impl createFormFromPdfWithPdfUrl:pdfUrl
                                fields:fields
                    removeOriginalText:removeOriginalText
                              resolver:^(NSDictionary *result) { resolve(result); }
                              rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - pickDocument

- (void)pickDocument:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
    [_impl pickDocumentWithResolver:^(NSDictionary *result) { resolve(result); }
                           rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - cleanupTempFiles

- (void)cleanupTempFiles:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
    [_impl cleanupTempFilesWithResolver:^(NSNumber *result) { resolve(result); }
                               rejecter:^(NSString *code, NSString *message, NSError *error) { reject(code, message, error); }];
}

// MARK: - TurboModule

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
    return std::make_shared<facebook::react::NativeNeurodocSpecJSI>(params);
}

@end
