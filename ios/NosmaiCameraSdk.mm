#import "NosmaiCameraSdk.h"

#import "NosmaiIOSController.h"

#include <atomic>
#include <memory>

static NosmaiIOSCompletion NosmaiIOSPromiseCompletion(
    RCTPromiseResolveBlock resolve,
    RCTPromiseRejectBlock reject) {
  auto settled = std::make_shared<std::atomic_bool>(false);
  return ^(id value, NSString *code, NSString *message, NSError *error) {
    if (settled->exchange(true)) {
      return;
    }
    dispatch_block_t settlement = ^{
      if (code) {
        reject(code, message ?: @"The native Nosmai SDK operation failed", error);
      } else {
        resolve(value);
      }
    };
    if ([NSThread isMainThread]) {
      settlement();
    } else {
      dispatch_async(dispatch_get_main_queue(), settlement);
    }
  };
}

@interface NosmaiCameraSdk () <NosmaiIOSModuleEventSink>
@property(nonatomic, assign) BOOL nosmaiEventsReady;
@end

@implementation NosmaiCameraSdk

+ (BOOL)requiresMainQueueSetup {
  return YES;
}

- (void)setEventEmitterCallback:
    (EventEmitterCallbackWrapper *)eventEmitterCallbackWrapper {
  [super setEventEmitterCallback:eventEmitterCallbackWrapper];
  self.nosmaiEventsReady = YES;
  [[NosmaiIOSController shared] attachModuleSink:self];
}

- (void)invalidate {
  self.nosmaiEventsReady = NO;
  [[NosmaiIOSController shared] detachModuleSink:self];
}

- (void)dealloc {
  [[NosmaiIOSController shared] detachModuleSink:self];
}

- (void)initialize:(NSString *)licenseKey
           resolve:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      initializeWithLicenseKey:licenseKey
                    completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)configureCamera:(NSString *)position
          sessionPreset:(NSString *_Nullable)sessionPreset
                resolve:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      configureCameraPosition:position
                sessionPreset:sessionPreset
                    completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)startProcessing:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      startProcessingWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)stopProcessing:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      stopProcessingWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)pauseCamera:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      pauseCameraWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)resumeCamera:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      resumeCameraWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)switchCamera:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      switchCameraWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)hasFlash:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      hasFlashWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)hasTorch:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      hasTorchWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setFlashMode:(NSString *)mode
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setFlashMode:mode
         completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setTorchMode:(NSString *)mode
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setTorchMode:mode
         completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getFlashMode:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getFlashModeWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getTorchMode:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getTorchModeWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)cleanup:(RCTPromiseResolveBlock)resolve
         reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      cleanupWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)startFrameStream:(double)maxFramesPerSecond
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      startFrameStreamWithMaxFramesPerSecond:maxFramesPerSecond
                                   completion:NosmaiIOSPromiseCompletion(resolve,
                                                                         reject)];
}

- (void)stopFrameStream:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      stopFrameStreamWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                               reject)];
}

- (void)getLatestFrame:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getLatestFrameWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                              reject)];
}

- (void)isFrameStreamActive:(RCTPromiseResolveBlock)resolve
                      reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isFrameStreamActiveWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                                   reject)];
}

- (void)startRecording:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      startRecordingWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)stopRecording:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      stopRecordingWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)isRecording:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isRecordingWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getCurrentRecordingDuration:(RCTPromiseResolveBlock)resolve
                              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getCurrentRecordingDurationWithCompletion:
          NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)capturePhoto:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      capturePhotoWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)saveImageToGallery:(NSString *)imageURI
                       name:(NSString *_Nullable)name
                    resolve:(RCTPromiseResolveBlock)resolve
                     reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      saveImageToGalleryAtURI:imageURI
                         name:name
                   completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)saveVideoToGallery:(NSString *)videoURI
                       name:(NSString *_Nullable)name
                    resolve:(RCTPromiseResolveBlock)resolve
                     reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      saveVideoToGalleryAtURI:videoURI
                         name:name
                   completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)applyEffect:(NSString *)packagePath
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      applyEffectAtPath:packagePath
              completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getActiveEffects:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getActiveEffectsWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                                reject)];
}

- (void)getActiveFilterInfo:(RCTPromiseResolveBlock)resolve
                     reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getActiveFilterInfoWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                                   reject)];
}

- (void)getActiveEffectInfo:(RCTPromiseResolveBlock)resolve
                     reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getActiveEffectInfoWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                                   reject)];
}

- (void)getEffectParameters:(RCTPromiseResolveBlock)resolve
                      reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getEffectParametersWithCompletion:
          NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getEffectParameterValue:(NSString *)parameterName
                         resolve:(RCTPromiseResolveBlock)resolve
                          reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getEffectParameterValue:parameterName
                    completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setEffectParameter:(NSString *)parameterName
                       value:(double)value
                     resolve:(RCTPromiseResolveBlock)resolve
                      reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setEffectParameter:parameterName
                   value:value
              completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setEffectParameterString:(NSString *)parameterName
                            value:(NSString *)value
                          resolve:(RCTPromiseResolveBlock)resolve
                           reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setEffectParameterString:parameterName
                          value:value
                     completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)isGameReady:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isGameReadyWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)sendGameTap:(double)normalizedX
        normalizedY:(double)normalizedY
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      sendGameTapAtNormalizedX:normalizedX
                             y:normalizedY
                    completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)sendGameInput:(NSString *)name
          normalizedX:(double)normalizedX
          normalizedY:(double)normalizedY
                value:(double)value
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      sendGameInput:name
         normalizedX:normalizedX
                   y:normalizedY
               value:value
          completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)pauseGame:(RCTPromiseResolveBlock)resolve
           reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      pauseGameWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)resumeGame:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      resumeGameWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)restartGame:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      restartGameWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getLocalFilters:(NSString *_Nullable)packageType
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getLocalFiltersOfType:packageType
                  completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getDebugFilters:(NSString *_Nullable)packageType
                  resolve:(RCTPromiseResolveBlock)resolve
                   reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getDebugFiltersOfType:packageType
                  completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)isCloudFilterEnabled:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isCloudFilterEnabledWithCompletion:
          NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)getCloudFilters:(NSString *_Nullable)packageType
                 version:(NSString *)version
                    page:(double)page
                   limit:(double)limit
           fetchAllPages:(BOOL)fetchAllPages
                 resolve:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      getCloudFiltersOfType:packageType
                     version:version
                        page:page
                       limit:limit
               fetchAllPages:fetchAllPages
                  completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)downloadCloudFilter:(NSString *)filterId
                     resolve:(RCTPromiseResolveBlock)resolve
                      reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      downloadCloudFilterWithIdentifier:filterId
                              completion:NosmaiIOSPromiseCompletion(resolve,
                                                                    reject)];
}

- (void)removeCloudFilter:(NSString *)filterId
                   resolve:(RCTPromiseResolveBlock)resolve
                    reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      removeCloudFilterWithIdentifier:filterId
                            completion:NosmaiIOSPromiseCompletion(resolve,
                                                                  reject)];
}

- (void)removeEffect:(NSString *)packagePath
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      removeEffectAtPath:packagePath
               completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearFilter:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearFilterWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearAREffect:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearAREffectWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearAll:(RCTPromiseResolveBlock)resolve
          reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearAllWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)isBeautyEffectEnabled:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isBeautyEffectEnabledWithCompletion:
          NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)isAdvancedFiltersEnabled:(RCTPromiseResolveBlock)resolve
                          reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isAdvancedFiltersEnabledWithCompletion:
          NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setBeautyValue:(NSString *)control
                 value:(double)value
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setBeautyControl:control
                  value:value
             completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearBeauty:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearBeautyWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)applyMakeup:(NSString *)makeupType
              style:(NSString *)style
                red:(double)red
              green:(double)green
               blue:(double)blue
          intensity:(double)intensity
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      applyMakeupOfType:makeupType
                   style:style
                     red:red
                   green:green
                    blue:blue
               intensity:intensity
              completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setMakeupIntensity:(NSString *)makeupType
                 intensity:(double)intensity
                   resolve:(RCTPromiseResolveBlock)resolve
                    reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setMakeupIntensityForType:makeupType
                       intensity:intensity
                      completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)removeMakeup:(NSString *)makeupType
             resolve:(RCTPromiseResolveBlock)resolve
              reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      removeMakeupOfType:makeupType
                completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)isMakeupActive:(NSString *)makeupType
               resolve:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isMakeupActiveOfType:makeupType
                 completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearMakeup:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearMakeupWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setReshape:(NSString *)reshapeType
             value:(double)value
           resolve:(RCTPromiseResolveBlock)resolve
            reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setReshapeOfType:reshapeType
                  value:value
             completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearReshapes:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearReshapesWithCompletion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setEyeColor:(double)red
              green:(double)green
               blue:(double)blue
          intensity:(double)intensity
            resolve:(RCTPromiseResolveBlock)resolve
             reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setEyeColorRed:red
                green:green
                 blue:blue
            intensity:intensity
           completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setEyeColorIntensity:(double)intensity
                     resolve:(RCTPromiseResolveBlock)resolve
                      reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setEyeColorIntensity:intensity
                  completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)removeEyeColor:(RCTPromiseResolveBlock)resolve
                reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      removeEyeColorWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                              reject)];
}

- (void)isEyeColorActive:(RCTPromiseResolveBlock)resolve
                  reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      isEyeColorActiveWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                                reject)];
}

- (void)setColorAdjustment:(NSString *)control
                    value1:(double)value1
                    value2:(double)value2
                    value3:(double)value3
                   resolve:(RCTPromiseResolveBlock)resolve
                    reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setColorControl:control
                value1:value1
                value2:value2
                value3:value3
            completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)resetColorAdjustments:(RCTPromiseResolveBlock)resolve
                       reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      resetColorAdjustmentsWithCompletion:
          NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)setBackground:(NSString *)mode
          resourceUri:(NSString *_Nullable)resourceUri
                  red:(double)red
                green:(double)green
                 blue:(double)blue
                alpha:(double)alpha
         blurStrength:(double)blurStrength
              resolve:(RCTPromiseResolveBlock)resolve
               reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      setBackgroundMode:mode
             resourceURI:resourceUri
                     red:red
                   green:green
                    blue:blue
                   alpha:alpha
            blurStrength:blurStrength
              completion:NosmaiIOSPromiseCompletion(resolve, reject)];
}

- (void)clearBackground:(RCTPromiseResolveBlock)resolve
                 reject:(RCTPromiseRejectBlock)reject {
  [[NosmaiIOSController shared]
      clearBackgroundWithCompletion:NosmaiIOSPromiseCompletion(resolve,
                                                               reject)];
}

#pragma mark - Controller events

- (void)nosmaiControllerDidChangeActiveEffects:(NSDictionary *)state {
  if (self.nosmaiEventsReady) {
    [self emitOnActiveEffectsChanged:state];
  }
}

- (void)nosmaiControllerDidChangeLicenseStatus:(NSString *)status {
  if (self.nosmaiEventsReady) {
    [self emitOnLicenseStatusChanged:status];
  }
}

- (void)nosmaiControllerDidReceiveError:(NSDictionary *)error {
  if (self.nosmaiEventsReady) {
    [self emitOnError:error];
  }
}

- (void)nosmaiControllerDidUpdateRecordingProgress:(NSDictionary *)progress {
  if (self.nosmaiEventsReady) {
    [self emitOnRecordingProgress:progress];
  }
}

- (void)nosmaiControllerDidUpdateDownloadProgress:(NSDictionary *)progress {
  if (self.nosmaiEventsReady) {
    [self emitOnDownloadProgress:progress];
  }
}

- (void)nosmaiControllerDidReceiveGameEvent:(NSDictionary *)event {
  if (self.nosmaiEventsReady) {
    [self emitOnGameEvent:event];
  }
}

- (void)nosmaiControllerDidMakeFrameAvailable:(NSDictionary *)metadata {
  if (self.nosmaiEventsReady) {
    [self emitOnFrameAvailable:metadata];
  }
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeNosmaiCameraSdkSpecJSI>(params);
}

+ (NSString *)moduleName {
  return @"NosmaiCameraSdk";
}

@end
