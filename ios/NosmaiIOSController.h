#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^NosmaiIOSCompletion)(id _Nullable value,
                                    NSString *_Nullable code,
                                    NSString *_Nullable message,
                                    NSError *_Nullable error);

@protocol NosmaiIOSModuleEventSink <NSObject>
- (void)nosmaiControllerDidChangeActiveEffects:(NSDictionary *)state;
- (void)nosmaiControllerDidChangeLicenseStatus:(NSString *)status;
- (void)nosmaiControllerDidReceiveError:(NSDictionary *)error;
- (void)nosmaiControllerDidUpdateRecordingProgress:(NSDictionary *)progress;
- (void)nosmaiControllerDidUpdateDownloadProgress:(NSDictionary *)progress;
- (void)nosmaiControllerDidReceiveGameEvent:(NSDictionary *)event;
- (void)nosmaiControllerDidMakeFrameAvailable:(NSDictionary *)metadata;
@end

@protocol NosmaiIOSPreviewSink <NSObject>
- (void)nosmaiControllerShowTransition;
- (void)nosmaiControllerDidRenderFirstFrame;
- (void)nosmaiControllerDidReceiveCameraErrorWithCode:(NSString *)code
                                               message:(NSString *)message;
@end

/**
 * Process-wide owner of the Nosmai iOS SDK, camera session, and active preview.
 * Public entry points are safe to call from any queue; state and UIKit work are
 * serialized on the main queue, while blocking camera transitions use a
 * dedicated serial queue.
 */
@interface NosmaiIOSController : NSObject

+ (instancetype)shared;

- (void)attachModuleSink:(id<NosmaiIOSModuleEventSink>)sink;
- (void)detachModuleSink:(id<NosmaiIOSModuleEventSink>)sink;

- (void)attachPreviewSink:(id<NosmaiIOSPreviewSink>)sink
                 container:(UIView *)container
                   position:(NSString *)position
                     mirror:(BOOL)mirror;
- (void)updatePreviewSink:(id<NosmaiIOSPreviewSink>)sink
                  position:(NSString *)position
                    mirror:(BOOL)mirror;
- (void)detachPreviewSink:(id<NosmaiIOSPreviewSink>)sink;

- (void)initializeWithLicenseKey:(NSString *)licenseKey
                       completion:(NosmaiIOSCompletion)completion;
- (void)configureCameraPosition:(NSString *)position
                  sessionPreset:(nullable NSString *)sessionPreset
                      completion:(NosmaiIOSCompletion)completion;
- (void)startProcessingWithCompletion:(NosmaiIOSCompletion)completion;
- (void)stopProcessingWithCompletion:(NosmaiIOSCompletion)completion;
- (void)pauseCameraWithCompletion:(NosmaiIOSCompletion)completion;
- (void)resumeCameraWithCompletion:(NosmaiIOSCompletion)completion;
- (void)switchCameraWithCompletion:(NosmaiIOSCompletion)completion;
- (void)hasFlashWithCompletion:(NosmaiIOSCompletion)completion;
- (void)hasTorchWithCompletion:(NosmaiIOSCompletion)completion;
- (void)setFlashMode:(NSString *)mode completion:(NosmaiIOSCompletion)completion;
- (void)setTorchMode:(NSString *)mode completion:(NosmaiIOSCompletion)completion;
- (void)getFlashModeWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getTorchModeWithCompletion:(NosmaiIOSCompletion)completion;
- (void)cleanupWithCompletion:(NosmaiIOSCompletion)completion;
- (void)startFrameStreamWithMaxFramesPerSecond:(double)maxFramesPerSecond
                                     completion:(NosmaiIOSCompletion)completion;
- (void)stopFrameStreamWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getLatestFrameWithCompletion:(NosmaiIOSCompletion)completion;
- (void)isFrameStreamActiveWithCompletion:(NosmaiIOSCompletion)completion;

- (void)startRecordingWithCompletion:(NosmaiIOSCompletion)completion;
- (void)stopRecordingWithCompletion:(NosmaiIOSCompletion)completion;
- (void)isRecordingWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getCurrentRecordingDurationWithCompletion:
    (NosmaiIOSCompletion)completion;
- (void)capturePhotoWithCompletion:(NosmaiIOSCompletion)completion;
- (void)saveImageToGalleryAtURI:(NSString *)imageURI
                           name:(nullable NSString *)name
                     completion:(NosmaiIOSCompletion)completion;
- (void)saveVideoToGalleryAtURI:(NSString *)videoURI
                           name:(nullable NSString *)name
                     completion:(NosmaiIOSCompletion)completion;

- (void)applyEffectAtPath:(NSString *)packagePath
                completion:(NosmaiIOSCompletion)completion;
- (void)getActiveEffectsWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getActiveFilterInfoWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getActiveEffectInfoWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getEffectParametersWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getEffectParameterValue:(NSString *)parameterName
                      completion:(NosmaiIOSCompletion)completion;
- (void)setEffectParameter:(NSString *)parameterName
                       value:(double)value
                  completion:(NosmaiIOSCompletion)completion;
- (void)setEffectParameterString:(NSString *)parameterName
                            value:(NSString *)value
                       completion:(NosmaiIOSCompletion)completion;
- (void)isGameReadyWithCompletion:(NosmaiIOSCompletion)completion;
- (void)sendGameTapAtNormalizedX:(double)x
                               y:(double)y
                      completion:(NosmaiIOSCompletion)completion;
- (void)sendGameInput:(NSString *)name
           normalizedX:(double)x
                     y:(double)y
                 value:(double)value
            completion:(NosmaiIOSCompletion)completion;
- (void)pauseGameWithCompletion:(NosmaiIOSCompletion)completion;
- (void)resumeGameWithCompletion:(NosmaiIOSCompletion)completion;
- (void)restartGameWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getLocalFiltersOfType:(nullable NSString *)packageType
                    completion:(NosmaiIOSCompletion)completion;
- (void)getDebugFiltersOfType:(nullable NSString *)packageType
                    completion:(NosmaiIOSCompletion)completion;
- (void)isCloudFilterEnabledWithCompletion:(NosmaiIOSCompletion)completion;
- (void)getCloudFiltersOfType:(nullable NSString *)packageType
                       version:(NSString *)version
                          page:(double)page
                         limit:(double)limit
                 fetchAllPages:(BOOL)fetchAllPages
                    completion:(NosmaiIOSCompletion)completion;
- (void)downloadCloudFilterWithIdentifier:(NSString *)filterId
                                completion:(NosmaiIOSCompletion)completion;
- (void)removeCloudFilterWithIdentifier:(NSString *)filterId
                              completion:(NosmaiIOSCompletion)completion;
- (void)removeEffectAtPath:(NSString *)packagePath
                 completion:(NosmaiIOSCompletion)completion;
- (void)clearFilterWithCompletion:(NosmaiIOSCompletion)completion;
- (void)clearAREffectWithCompletion:(NosmaiIOSCompletion)completion;
- (void)clearAllWithCompletion:(NosmaiIOSCompletion)completion;

- (void)isBeautyEffectEnabledWithCompletion:(NosmaiIOSCompletion)completion;
- (void)isAdvancedFiltersEnabledWithCompletion:(NosmaiIOSCompletion)completion;
- (void)setBeautyControl:(NSString *)control
                    value:(double)value
               completion:(NosmaiIOSCompletion)completion;
- (void)clearBeautyWithCompletion:(NosmaiIOSCompletion)completion;
- (void)applyMakeupOfType:(NSString *)makeupType
                     style:(NSString *)style
                       red:(double)red
                     green:(double)green
                      blue:(double)blue
                 intensity:(double)intensity
                completion:(NosmaiIOSCompletion)completion;
- (void)setMakeupIntensityForType:(NSString *)makeupType
                         intensity:(double)intensity
                        completion:(NosmaiIOSCompletion)completion;
- (void)removeMakeupOfType:(NSString *)makeupType
                  completion:(NosmaiIOSCompletion)completion;
- (void)isMakeupActiveOfType:(NSString *)makeupType
                   completion:(NosmaiIOSCompletion)completion;
- (void)clearMakeupWithCompletion:(NosmaiIOSCompletion)completion;
- (void)setReshapeOfType:(NSString *)reshapeType
                    value:(double)value
               completion:(NosmaiIOSCompletion)completion;
- (void)clearReshapesWithCompletion:(NosmaiIOSCompletion)completion;
- (void)setEyeColorRed:(double)red
                  green:(double)green
                   blue:(double)blue
              intensity:(double)intensity
             completion:(NosmaiIOSCompletion)completion;
- (void)setEyeColorIntensity:(double)intensity
                    completion:(NosmaiIOSCompletion)completion;
- (void)removeEyeColorWithCompletion:(NosmaiIOSCompletion)completion;
- (void)isEyeColorActiveWithCompletion:(NosmaiIOSCompletion)completion;
- (void)setColorControl:(NSString *)control
                  value1:(double)value1
                  value2:(double)value2
                  value3:(double)value3
              completion:(NosmaiIOSCompletion)completion;
- (void)resetColorAdjustmentsWithCompletion:(NosmaiIOSCompletion)completion;
- (void)setBackgroundMode:(NSString *)mode
               resourceURI:(nullable NSString *)resourceURI
                       red:(double)red
                     green:(double)green
                      blue:(double)blue
                     alpha:(double)alpha
              blurStrength:(double)blurStrength
                completion:(NosmaiIOSCompletion)completion;
- (void)clearBackgroundWithCompletion:(NosmaiIOSCompletion)completion;

@end

NS_ASSUME_NONNULL_END
