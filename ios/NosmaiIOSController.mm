#import "NosmaiIOSController.h"
#import "NosmaiIOSFrameStream.h"
#import "NosmaiRecordingFailurePolicy.h"

#import <AVFoundation/AVFoundation.h>
#import <CommonCrypto/CommonDigest.h>
#import <ImageIO/ImageIO.h>
#import <Photos/Photos.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#import <nosmai/Nosmai.h>

#include <algorithm>
#include <atomic>
#include <cfloat>
#include <cmath>

// NosmaiCameraSDK 3.0.x exposes asynchronous clear methods but no public clear
// completion. Its engine owns this queue fence; the pod is pinned to 3.0.x so
// cleanup can wait for the clear block itself, not merely an early state read.
@interface NosmaiEffectsEngine (NosmaiReactNativeQueueFence)
- (void)performEffectQueueSync:(dispatch_block_t)block;
@end

static NSString *const NosmaiIOSErrorInvalidArgument = @"E_INVALID_ARGUMENT";
static NSString *const NosmaiIOSErrorInvalidState = @"E_INVALID_STATE";
static NSString *const NosmaiIOSErrorNotInitialized = @"E_NOT_INITIALIZED";
static NSString *const NosmaiIOSErrorInitialization = @"E_INITIALIZATION";
static NSString *const NosmaiIOSErrorLicenseMismatch = @"E_LICENSE_KEY_MISMATCH";
static NSString *const NosmaiIOSErrorCameraPermission = @"E_CAMERA_PERMISSION";
static NSString *const NosmaiIOSErrorCameraStart = @"E_CAMERA_START";
static NSString *const NosmaiIOSErrorCameraDevice = @"E_CAMERA_DEVICE";
static NSString *const NosmaiIOSErrorNoPreview = @"E_NO_PREVIEW";
static NSString *const NosmaiIOSErrorProcessingStart = @"E_PROCESSING_START";
static NSString *const NosmaiIOSErrorProcessingStop = @"E_PROCESSING_STOP";
static NSString *const NosmaiIOSErrorInvalidPackagePath =
    @"E_INVALID_PACKAGE_PATH";
static NSString *const NosmaiIOSErrorEffectApply = @"E_EFFECT_APPLY";
static NSString *const NosmaiIOSErrorEffectClear = @"E_EFFECT_CLEAR";
static NSString *const NosmaiIOSErrorEffectState = @"E_EFFECT_STATE";
static NSString *const NosmaiIOSErrorEffectParameter = @"E_EFFECT_PARAMETER";
static NSString *const NosmaiIOSErrorLocalCatalog = @"E_LOCAL_CATALOG";
static NSString *const NosmaiIOSErrorDebugFilters = @"E_DEBUG_FILTERS";
static NSString *const NosmaiIOSErrorOperationCancelled = @"E_OPERATION_CANCELLED";
static NSString *const NosmaiIOSErrorCleanup = @"E_CLEANUP";
static NSString *const NosmaiIOSErrorRecordingPermission =
    @"E_RECORDING_PERMISSION";
static NSString *const NosmaiIOSErrorRecordingInProgress =
    @"E_RECORDING_IN_PROGRESS";
static NSString *const NosmaiIOSErrorNotRecording = @"E_NOT_RECORDING";
static NSString *const NosmaiIOSErrorRecordingStart = @"E_RECORDING_START";
static NSString *const NosmaiIOSErrorRecordingStop = @"E_RECORDING_STOP";
static NSString *const NosmaiIOSErrorRecordingStorageFull =
    @"E_RECORDING_STORAGE_FULL";
static NSString *const NosmaiIOSErrorRecordingAudioMux =
    @"E_RECORDING_AUDIO_MUX";
static NSString *const NosmaiIOSErrorRecordingInterrupted =
    @"E_RECORDING_INTERRUPTED";
static NSString *const NosmaiIOSErrorCaptureInProgress =
    @"E_CAPTURE_IN_PROGRESS";
static NSString *const NosmaiIOSErrorCaptureFailed = @"E_CAPTURE_FAILED";
static NSString *const NosmaiIOSErrorMediaNotFound = @"E_MEDIA_NOT_FOUND";
static NSString *const NosmaiIOSErrorGalleryPermission =
    @"E_GALLERY_PERMISSION";
static NSString *const NosmaiIOSErrorGallerySave = @"E_GALLERY_SAVE";
static NSString *const NosmaiIOSErrorCloudDisabled = @"E_CLOUD_DISABLED";
static NSString *const NosmaiIOSErrorCloudCatalog = @"E_CLOUD_CATALOG";
static NSString *const NosmaiIOSErrorCloudDownload = @"E_CLOUD_DOWNLOAD";
static NSString *const NosmaiIOSErrorCloudDownloadTimeout =
    @"E_CLOUD_DOWNLOAD_TIMEOUT";
static NSString *const NosmaiIOSErrorCloudRemove = @"E_CLOUD_REMOVE";
static NSString *const NosmaiIOSErrorBeautyDisabled = @"E_BEAUTY_DISABLED";
static NSString *const NosmaiIOSErrorAdvancedFiltersDisabled =
    @"E_ADVANCED_FILTERS_DISABLED";
static NSString *const NosmaiIOSErrorVisualControl = @"E_VISUAL_CONTROL";
static NSString *const NosmaiIOSErrorBackgroundResource =
    @"E_BACKGROUND_RESOURCE";
static NSString *const NosmaiIOSErrorFrameStream = @"E_FRAME_STREAM";

static NSTimeInterval const NosmaiIOSCameraSwitchDebounceSeconds = 0.3;
static NSTimeInterval const NosmaiIOSEffectStateTimeoutSeconds = 3.0;
static NSTimeInterval const NosmaiIOSRecordingProgressIntervalSeconds = 0.5;
static NSTimeInterval const NosmaiIOSRecordingStartTimeoutSeconds = 10.0;
static NSTimeInterval const NosmaiIOSRecordingStopTimeoutSeconds = 15.0;
static NSTimeInterval const NosmaiIOSCaptureTimeoutSeconds = 5.0;
// The native SDK allows up to 300 seconds for a package transfer. The bridge
// watchdog is deliberately longer so it guards a lost callback rather than
// racing the native network timeout. The SDK has no public cancellation API.
static NSTimeInterval const NosmaiIOSCloudDownloadTimeoutSeconds = 315.0;
static unsigned long long const NosmaiIOSMaxBackgroundImageBytes =
    64ULL * 1024ULL * 1024ULL;
static size_t const NosmaiIOSMaxBackgroundImageSourceDimension = 32768;
static double const NosmaiIOSMaxBackgroundImageSourcePixels =
    268435456.0;
static size_t const NosmaiIOSBackgroundImageTargetDimension = 2048;
static NSUInteger const NosmaiIOSMaxEffectParameterCount = 256;
static NSUInteger const NosmaiIOSMaxEffectParameterNameLength = 128;
static NSUInteger const NosmaiIOSMaxEffectParameterTypeLength = 64;
static NSUInteger const NosmaiIOSMaxEffectParameterDisplayNameLength = 256;
static NSUInteger const NosmaiIOSMaxEffectParameterDescriptionLength = 2048;
static NSUInteger const NosmaiIOSMaxEffectParameterStringLength = 16384;
static NSUInteger const NosmaiIOSMaxEffectParameterVectorLength = 64;
static NSUInteger const NosmaiIOSMaxEffectParameterOptionCount = 128;
static NSUInteger const NosmaiIOSMaxEffectParameterOptionLength = 256;

typedef NS_ENUM(NSInteger, NosmaiIOSRecordingState) {
  NosmaiIOSRecordingStateIdle = 0,
  NosmaiIOSRecordingStateStarting,
  NosmaiIOSRecordingStateRecording,
  NosmaiIOSRecordingStateStopping,
};

typedef BOOL (^NosmaiIOSPipelineCondition)(NosmaiPipelineState *state,
                                           NosmaiSDK *sdk);
typedef void (^NosmaiIOSEffectWork)(NosmaiIOSCompletion finish);
typedef void (^NosmaiIOSVisualMutation)(NosmaiEffectsEngine *effects,
                                        NosmaiSDK *sdk);
typedef void (^NosmaiIOSCloudCatalogCompletion)(
    NSArray<NSDictionary *> *_Nullable filters,
    NosmaiCloudFilterPaginationInfo *_Nullable pagination,
    NSError *_Nullable error);

static void NosmaiIOSRunOnMain(dispatch_block_t block) {
  if ([NSThread isMainThread]) {
    block();
  } else {
    dispatch_async(dispatch_get_main_queue(), block);
  }
}

static NSError *NosmaiIOSError(NSString *code,
                               NSString *message,
                               NSError *_Nullable underlying) {
  NSMutableDictionary *userInfo = [@{
    NSLocalizedDescriptionKey : message,
    @"nosmaiCode" : code,
  } mutableCopy];
  if (underlying) {
    userInfo[NSUnderlyingErrorKey] = underlying;
  }
  return [NSError errorWithDomain:@"com.nosmai.reactnative.camera"
                             code:underlying ? underlying.code : 0
                         userInfo:userInfo];
}

static NSError *NosmaiIOSErrorFromException(NSException *exception,
                                             NSString *code,
                                             NSString *fallback) {
  NSString *message = exception.reason.length > 0 ? exception.reason : fallback;
  return NosmaiIOSError(code, message, nil);
}

static NSString *NosmaiIOSLicenseDigest(NSString *licenseKey) {
  NSData *data = [licenseKey dataUsingEncoding:NSUTF8StringEncoding];
  unsigned char digest[CC_SHA256_DIGEST_LENGTH];
  CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
  NSMutableString *value =
      [NSMutableString stringWithCapacity:CC_SHA256_DIGEST_LENGTH * 2];
  for (NSUInteger index = 0; index < CC_SHA256_DIGEST_LENGTH; index++) {
    [value appendFormat:@"%02x", digest[index]];
  }
  return value;
}

static NSString *NosmaiIOSNormalizedPosition(NSString *position) {
  NSString *normalized = position.lowercaseString;
  if (normalized.length == 0 || [normalized isEqualToString:@"front"]) {
    return @"front";
  }
  if ([normalized isEqualToString:@"back"]) {
    return @"back";
  }
  return nil;
}

static NSString *NosmaiIOSNormalizedPreset(NSString *_Nullable preset) {
  if (preset.length == 0) {
    return AVCaptureSessionPreset1280x720;
  }

  NSString *normalized = preset.lowercaseString;
  if ([normalized isEqualToString:@"default"] ||
      [normalized isEqualToString:@"high"] ||
      [normalized isEqualToString:@"720p"] ||
      [normalized isEqualToString:@"1280x720"] ||
      [normalized isEqualToString:@"hd1280x720"] ||
      [preset isEqualToString:AVCaptureSessionPreset1280x720]) {
    return AVCaptureSessionPreset1280x720;
  }
  if ([normalized isEqualToString:@"480p"] ||
      [normalized isEqualToString:@"640x480"] ||
      [normalized isEqualToString:@"vga640x480"] ||
      [preset isEqualToString:AVCaptureSessionPreset640x480]) {
    return AVCaptureSessionPreset640x480;
  }
  return nil;
}

static NSString *_Nullable NosmaiIOSNonEmptyString(id _Nullable value) {
  if (![value isKindOfClass:NSString.class]) return nil;
  NSString *string =
      [(NSString *)value stringByTrimmingCharactersInSet:
                              NSCharacterSet.whitespaceAndNewlineCharacterSet];
  return string.length > 0 ? string : nil;
}

static NSNumber *_Nullable NosmaiIOSFiniteNumber(id _Nullable value) {
  double number = 0.0;
  if ([value isKindOfClass:NSNumber.class]) {
    number = [((NSNumber *)value) doubleValue];
  } else if ([value isKindOfClass:NSString.class]) {
    NSString *string = NosmaiIOSNonEmptyString(value);
    if (!string) return nil;
    NSScanner *scanner = [NSScanner scannerWithString:string];
    if (![scanner scanDouble:&number] || !scanner.isAtEnd) return nil;
  } else {
    return nil;
  }
  return std::isfinite(number) ? @(number) : nil;
}

static NSString *_Nullable NosmaiIOSNormalizedCloudIdentifier(
    id _Nullable value) {
  NSString *identifier = NosmaiIOSNonEmptyString(value);
  if (!identifier || [identifier isEqualToString:@"."] ||
      [identifier isEqualToString:@".."] ||
      [identifier containsString:@".."] ||
      [identifier containsString:@"/"] ||
      [identifier containsString:@"\\"]) {
    return nil;
  }
  for (NSUInteger index = 0; index < identifier.length; index++) {
    if ([identifier characterAtIndex:index] < 0x20) return nil;
  }
  return identifier;
}

static NSString *_Nullable NosmaiIOSCloudPackageType(id _Nullable value) {
  NSString *raw = NosmaiIOSNonEmptyString(value).lowercaseString;
  if (!raw) return nil;
  NSString *normalized = [raw stringByReplacingOccurrencesOfString:@"-"
                                                         withString:@"_"];
  if ([normalized isEqualToString:@"filter"] ||
      [normalized isEqualToString:@"filters"] ||
      [normalized isEqualToString:@"fx_and_filter"] ||
      [normalized isEqualToString:@"fx_and_filters"] ||
      [normalized isEqualToString:@"cloud_filter"] ||
      [normalized isEqualToString:@"cloud_filters"]) {
    return @"filter";
  }
  if ([normalized isEqualToString:@"effect"] ||
      [normalized isEqualToString:@"effects"] ||
      [normalized isEqualToString:@"special_effect"] ||
      [normalized isEqualToString:@"special_effects"]) {
    return @"effect";
  }
  if ([normalized isEqualToString:@"background"] ||
      [normalized isEqualToString:@"backgrounds"] ||
      [normalized isEqualToString:@"bg"]) {
    return @"background";
  }
  if ([normalized isEqualToString:@"beauty"] ||
      [normalized isEqualToString:@"beauty_effect"] ||
      [normalized isEqualToString:@"beauty_effects"] ||
      [normalized isEqualToString:@"beautyeffect"]) {
    return @"beauty_effect";
  }
  if ([normalized isEqualToString:@"game"] ||
      [normalized isEqualToString:@"games"]) {
    return @"game";
  }
  return nil;
}

static NSString *_Nullable NosmaiIOSCloudRequestType(
    NSString *_Nullable packageType) {
  if ([packageType isEqualToString:@"filter"]) return @"filter";
  if ([packageType isEqualToString:@"effect"]) return @"effects";
  if ([packageType isEqualToString:@"background"]) return @"bg";
  if ([packageType isEqualToString:@"beauty_effect"]) {
    return @"beauty_effect";
  }
  if ([packageType isEqualToString:@"game"]) return @"games";
  return nil;
}

static NSString *_Nullable NosmaiIOSSafeRemoteURL(id _Nullable value) {
  NSString *string = NosmaiIOSNonEmptyString(value);
  if (!string) return nil;
  NSURL *url = [NSURL URLWithString:string];
  NSString *scheme = url.scheme.lowercaseString;
  if ((!([scheme isEqualToString:@"https"] ||
         [scheme isEqualToString:@"http"])) ||
      url.host.length == 0) {
    return nil;
  }
  return string;
}

static BOOL NosmaiIOSFiniteInRange(double value, double minimum,
                                   double maximum) {
  return std::isfinite(value) && value >= minimum && value <= maximum;
}

static BOOL NosmaiIOSContainsControlCharacter(NSString *value) {
  for (NSUInteger index = 0; index < value.length; index++) {
    unichar character = [value characterAtIndex:index];
    if (character < 0x20 || character == 0x7f) return YES;
  }
  return NO;
}

static NSString *_Nullable NosmaiIOSSanitizedEffectText(
    id _Nullable value, NSUInteger maximumLength) {
  if (![value isKindOfClass:NSString.class]) return nil;
  NSString *string = (NSString *)value;
  NSUInteger sourceLength = MIN(string.length, maximumLength);
  NSMutableString *sanitized =
      [NSMutableString stringWithCapacity:sourceLength];
  for (NSUInteger index = 0; index < sourceLength; index++) {
    unichar character = [string characterAtIndex:index];
    if (character >= 0x20 && character != 0x7f) {
      [sanitized appendFormat:@"%C", character];
    }
  }
  return [sanitized copy];
}

static NSString *_Nullable NosmaiIOSSafeParameterName(id _Nullable value) {
  if (![value isKindOfClass:NSString.class] ||
      [((NSString *)value) length] > NosmaiIOSMaxEffectParameterNameLength) {
    return nil;
  }
  NSString *name = NosmaiIOSNonEmptyString(value);
  if (!name || NosmaiIOSContainsControlCharacter(name)) return nil;
  return name;
}

static NSString *NosmaiIOSCanonicalParameterType(id _Nullable value) {
  NSString *raw = NosmaiIOSNonEmptyString(value);
  if (!raw || raw.length > NosmaiIOSMaxEffectParameterTypeLength) {
    return @"unknown";
  }
  raw = raw.lowercaseString;
  if ([raw isEqualToString:@"float"] || [raw isEqualToString:@"double"] ||
      [raw isEqualToString:@"number"]) {
    return @"float";
  }
  if ([raw isEqualToString:@"int"] || [raw isEqualToString:@"integer"]) {
    return @"int";
  }
  if ([raw isEqualToString:@"bool"] || [raw isEqualToString:@"boolean"]) {
    return @"bool";
  }
  if ([raw isEqualToString:@"string"] || [raw isEqualToString:@"text"]) {
    return @"string";
  }
  if ([raw isEqualToString:@"vector"] || [raw isEqualToString:@"vec2"] ||
      [raw isEqualToString:@"vec3"] || [raw isEqualToString:@"vec4"] ||
      [raw isEqualToString:@"color"] || [raw isEqualToString:@"color3"] ||
      [raw isEqualToString:@"color4"]) {
    return @"vector";
  }
  if ([raw isEqualToString:@"enum"] || [raw isEqualToString:@"select"] ||
      [raw isEqualToString:@"option"]) {
    return @"enum";
  }
  return @"unknown";
}

static id NosmaiIOSParameterValue(id _Nullable value, NSString *type) {
  if (!value || value == NSNull.null) return NSNull.null;
  if ([type isEqualToString:@"float"] || [type isEqualToString:@"int"]) {
    NSNumber *number = NosmaiIOSFiniteNumber(value);
    if (!number) return NSNull.null;
    if ([type isEqualToString:@"int"]) {
      double integerValue = number.doubleValue;
      if (std::floor(integerValue) != integerValue ||
          std::fabs(integerValue) > 9007199254740991.0) {
        return NSNull.null;
      }
    }
    return number;
  }
  if ([type isEqualToString:@"bool"]) {
    NSNumber *number = NosmaiIOSFiniteNumber(value);
    if (!number || (number.doubleValue != 0.0 && number.doubleValue != 1.0)) {
      return NSNull.null;
    }
    return @(number.boolValue);
  }
  if ([type isEqualToString:@"string"]) {
    return NosmaiIOSSanitizedEffectText(
               value, NosmaiIOSMaxEffectParameterStringLength) ?: NSNull.null;
  }
  if ([type isEqualToString:@"vector"]) {
    if (![value isKindOfClass:NSArray.class] ||
        [((NSArray *)value) count] > NosmaiIOSMaxEffectParameterVectorLength) {
      return NSNull.null;
    }
    NSMutableArray<NSNumber *> *components = [NSMutableArray array];
    for (id component in (NSArray *)value) {
      NSNumber *number = NosmaiIOSFiniteNumber(component);
      if (!number) return NSNull.null;
      [components addObject:number];
    }
    return [components copy];
  }
  if ([type isEqualToString:@"enum"]) {
    if ([value isKindOfClass:NSString.class]) {
      return NosmaiIOSSanitizedEffectText(
                 value, NosmaiIOSMaxEffectParameterOptionLength) ?: NSNull.null;
    }
    return NosmaiIOSFiniteNumber(value) ?: NSNull.null;
  }
  if ([value isKindOfClass:NSString.class]) {
    return NosmaiIOSSanitizedEffectText(
               value, NosmaiIOSMaxEffectParameterStringLength) ?: NSNull.null;
  }
  if ([value isKindOfClass:NSNumber.class]) {
    return NosmaiIOSFiniteNumber(value) ?: NSNull.null;
  }
  return NSNull.null;
}

static NSDictionary *_Nullable NosmaiIOSParameterMap(id _Nullable value) {
  if (![value isKindOfClass:NSDictionary.class]) return nil;
  NSDictionary *raw = (NSDictionary *)value;
  NSString *name = NosmaiIOSSafeParameterName(raw[@"name"]);
  if (!name) return nil;
  NSString *type = NosmaiIOSCanonicalParameterType(raw[@"type"]);
  NSString *displayName = NosmaiIOSSanitizedEffectText(
      raw[@"displayName"], NosmaiIOSMaxEffectParameterDisplayNameLength);
  if (NosmaiIOSNonEmptyString(displayName).length == 0) displayName = name;
  NSString *description = NosmaiIOSSanitizedEffectText(
      raw[@"description"], NosmaiIOSMaxEffectParameterDescriptionLength) ?: @"";
  BOOL hasRange = [raw[@"hasRange"] boolValue];
  NSNumber *minimum = hasRange
      ? NosmaiIOSFiniteNumber(raw[@"minValue"] ?: raw[@"min"])
      : nil;
  NSNumber *maximum = hasRange
      ? NosmaiIOSFiniteNumber(raw[@"maxValue"] ?: raw[@"max"])
      : nil;
  hasRange = hasRange && minimum && maximum &&
      minimum.doubleValue <= maximum.doubleValue;
  NSMutableArray<NSString *> *options = [NSMutableArray array];
  if ([raw[@"options"] isKindOfClass:NSArray.class]) {
    for (id option in raw[@"options"]) {
      if (options.count >= NosmaiIOSMaxEffectParameterOptionCount) break;
      NSString *sanitized = NosmaiIOSSanitizedEffectText(
          option, NosmaiIOSMaxEffectParameterOptionLength);
      if (sanitized) [options addObject:sanitized];
    }
  }
  NSNumber *passId = NosmaiIOSFiniteNumber(raw[@"passId"]);
  double passIdValue = passId.doubleValue;
  BOOL hasPassId = passId && passIdValue >= 0.0 &&
      std::floor(passIdValue) == passIdValue &&
      passIdValue <= 9007199254740991.0;
  id currentValue = raw[@"currentValue"] ?: raw[@"value"];
  id defaultValue = raw[@"defaultValue"] ?: raw[@"default"];
  return @{
    @"name" : name,
    @"type" : type,
    @"displayName" : displayName,
    @"description" : description,
    @"currentValue" : NosmaiIOSParameterValue(currentValue, type),
    @"defaultValue" : NosmaiIOSParameterValue(defaultValue, type),
    @"hasRange" : @(hasRange),
    @"minValue" : hasRange ? minimum : NSNull.null,
    @"maxValue" : hasRange ? maximum : NSNull.null,
    @"options" : [options copy],
    @"passId" : hasPassId ? @(passIdValue) : NSNull.null,
  };
}

static BOOL NosmaiIOSSafeParameterString(id _Nullable value) {
  if (![value isKindOfClass:NSString.class] ||
      [((NSString *)value) length] > NosmaiIOSMaxEffectParameterStringLength) {
    return NO;
  }
  return !NosmaiIOSContainsControlCharacter((NSString *)value);
}

static BOOL NosmaiIOSHasNumericParameter(NSArray *_Nullable parameters,
                                         NSString *name) {
  NSUInteger inspected = 0;
  for (id candidate in parameters ?: @[]) {
    if (inspected >= NosmaiIOSMaxEffectParameterCount) break;
    inspected++;
    if (![candidate isKindOfClass:NSDictionary.class]) continue;
    NSDictionary *parameter = (NSDictionary *)candidate;
    if (![NosmaiIOSSafeParameterName(parameter[@"name"])
            isEqualToString:name]) {
      continue;
    }
    NSString *type = NosmaiIOSCanonicalParameterType(parameter[@"type"]);
    return [type isEqualToString:@"float"] || [type isEqualToString:@"int"] ||
        [type isEqualToString:@"bool"];
  }
  return NO;
}

static BOOL NosmaiIOSIsMakeupType(NSString *_Nullable makeupType) {
  return [makeupType isEqualToString:@"lipstick"] ||
      [makeupType isEqualToString:@"eyeshadow"] ||
      [makeupType isEqualToString:@"blusher"] ||
      [makeupType isEqualToString:@"eyelash"] ||
      [makeupType isEqualToString:@"eyebrow"];
}

static BOOL NosmaiIOSIsMakeupLayerActive(NosmaiSDK *sdk,
                                         NSString *makeupType) {
  if ([makeupType isEqualToString:@"lipstick"]) return sdk.hasLipstick;
  if ([makeupType isEqualToString:@"eyeshadow"]) return sdk.hasEyeshadow;
  if ([makeupType isEqualToString:@"blusher"]) return sdk.hasBlusher;
  if ([makeupType isEqualToString:@"eyelash"]) return sdk.hasEyelash;
  return sdk.hasEyebrow;
}

static BOOL NosmaiIOSMakeupStyle(NSString *_Nullable makeupType,
                                 NSString *_Nullable style,
                                 NSInteger *styleValue) {
  if ([makeupType isEqualToString:@"lipstick"]) {
    if ([style isEqualToString:@"classic"]) {
      *styleValue = NosmaiLipstickStyleClassic;
    } else if ([style isEqualToString:@"matte"]) {
      *styleValue = NosmaiLipstickStyleMatte;
    } else if ([style isEqualToString:@"natural"]) {
      *styleValue = NosmaiLipstickStyleNatural;
    } else {
      return NO;
    }
    return YES;
  }
  if ([makeupType isEqualToString:@"eyeshadow"]) {
    if ([style isEqualToString:@"smokey"]) {
      *styleValue = NosmaiEyeshadowStyleSmokey;
    } else if ([style isEqualToString:@"shimmer"]) {
      *styleValue = NosmaiEyeshadowStyleShimmer;
    } else if ([style isEqualToString:@"natural"]) {
      *styleValue = NosmaiEyeshadowStyleNatural;
    } else {
      return NO;
    }
    return YES;
  }
  if ([makeupType isEqualToString:@"blusher"]) {
    if ([style isEqualToString:@"round"]) {
      *styleValue = NosmaiBlusherStyleRound;
    } else if ([style isEqualToString:@"contour"]) {
      *styleValue = NosmaiBlusherStyleContour;
    } else if ([style isEqualToString:@"natural"]) {
      *styleValue = NosmaiBlusherStyleNatural;
    } else {
      return NO;
    }
    return YES;
  }
  if ([makeupType isEqualToString:@"eyelash"]) {
    if ([style isEqualToString:@"natural"]) {
      *styleValue = NosmaiEyelashStyleNatural;
    } else if ([style isEqualToString:@"dramatic"]) {
      *styleValue = NosmaiEyelashStyleDramatic;
    } else if ([style isEqualToString:@"wispy"]) {
      *styleValue = NosmaiEyelashStyleWispy;
    } else {
      return NO;
    }
    return YES;
  }
  if ([makeupType isEqualToString:@"eyebrow"]) {
    if ([style isEqualToString:@"natural"]) {
      *styleValue = NosmaiEyebrowStyleNatural;
    } else if ([style isEqualToString:@"bold"]) {
      *styleValue = NosmaiEyebrowStyleBold;
    } else if ([style isEqualToString:@"arched"]) {
      *styleValue = NosmaiEyebrowStyleArched;
    } else {
      return NO;
    }
    return YES;
  }
  return NO;
}

static BOOL NosmaiIOSReshapeSpecification(NSString *_Nullable reshapeType,
                                          NosmaiReshapeType *nativeType,
                                          double *minimum,
                                          double *maximum) {
  if ([reshapeType isEqualToString:@"lip"]) {
    *nativeType = NosmaiReshapeTypeLip;
    *minimum = -1.0;
    *maximum = 1.0;
  } else if ([reshapeType isEqualToString:@"faceSlim"]) {
    *nativeType = NosmaiReshapeTypeFaceSlim;
    *minimum = -1.2;
    *maximum = 1.2;
  } else if ([reshapeType isEqualToString:@"eye"]) {
    *nativeType = NosmaiReshapeTypeEye;
    *minimum = -1.3;
    *maximum = 1.3;
  } else if ([reshapeType isEqualToString:@"nose"]) {
    *nativeType = NosmaiReshapeTypeNose;
    *minimum = -0.5;
    *maximum = 0.5;
  } else if ([reshapeType isEqualToString:@"chin"]) {
    *nativeType = NosmaiReshapeTypeChin;
    *minimum = -0.8;
    *maximum = 0.8;
  } else if ([reshapeType isEqualToString:@"brow"]) {
    *nativeType = NosmaiReshapeTypeBrow;
    *minimum = -1.0;
    *maximum = 1.0;
  } else if ([reshapeType isEqualToString:@"browThickness"]) {
    *nativeType = NosmaiReshapeTypeBrowThickness;
    *minimum = -1.0;
    *maximum = 1.0;
  } else if ([reshapeType isEqualToString:@"jaw"]) {
    *nativeType = NosmaiReshapeTypeJaw;
    *minimum = -1.0;
    *maximum = 1.0;
  } else if ([reshapeType isEqualToString:@"mouthWidth"]) {
    *nativeType = NosmaiReshapeTypeMouthWidth;
    *minimum = -1.0;
    *maximum = 1.0;
  } else if ([reshapeType isEqualToString:@"forehead"]) {
    *nativeType = NosmaiReshapeTypeForehead;
    *minimum = -1.0;
    *maximum = 1.0;
  } else {
    return NO;
  }
  return YES;
}

static UIImage *NosmaiIOSImageByMirroringHorizontally(UIImage *image) {
  if (!image || image.size.width <= 0 || image.size.height <= 0) {
    return image;
  }
  UIGraphicsBeginImageContextWithOptions(image.size, YES, image.scale);
  CGContextRef context = UIGraphicsGetCurrentContext();
  if (!context) {
    UIGraphicsEndImageContext();
    return image;
  }
  CGContextTranslateCTM(context, image.size.width, 0);
  CGContextScaleCTM(context, -1.0, 1.0);
  [image drawInRect:(CGRect){CGPointZero, image.size}];
  UIImage *mirrored = UIGraphicsGetImageFromCurrentImageContext();
  UIGraphicsEndImageContext();
  return mirrored ?: image;
}

@interface NosmaiIOSController () <NosmaiDelegate,
                                   NosmaiCameraDelegate,
                                   NosmaiEffectsDelegate>
- (nullable NSString *)normalizedPackagePath:(NSString *)input
                             requireReadable:(BOOL)requireReadable;
- (nullable NSDictionary *)filterInfoMap:(nullable NosmaiFilterInfo *)info
                            fallbackPath:(nullable NSString *)fallbackPath
                            fallbackType:(NSString *)fallbackType;
- (BOOL)pathsEqual:(nullable NSString *)first
             other:(nullable NSString *)second;
- (void)enqueueEffectWork:(NosmaiIOSEffectWork)work
                completion:(NosmaiIOSCompletion)completion;
- (void)runNextEffectOperation;
- (void)finishActiveEffectOperation;
- (void)whenEffectOperationsDrained:(dispatch_block_t)completion;
- (void)waitForPipelineUntil:(NSTimeInterval)deadline
                         condition:(NosmaiIOSPipelineCondition)condition
                        completion:(NosmaiIOSCompletion)completion;
- (BOOL)ensureRecordingIdleForCompletion:(NosmaiIOSCompletion)completion
                               operation:(NSString *)operation;
- (void)requestMicrophoneAuthorization:
    (void (^)(BOOL authorized))completion;
- (void)continueRecordingStartAfterAuthorizationForGeneration:
    (NSUInteger)generation;
- (void)beginNativeRecordingStop;
- (void)stopRecordingForTeardown:(dispatch_block_t)completion;
- (void)finishRecordingTeardownCompletions;
- (void)startRecordingProgressTimer;
- (void)stopRecordingProgressTimer;
- (NSTimeInterval)authoritativeRecordingDuration;
- (void)handleRecordingStartTimeoutForGeneration:(NSUInteger)generation;
- (void)handleRecordingStopTimeoutForGeneration:(NSUInteger)generation
                                         message:(NSString *)message;
- (void)cancelPendingCaptureWithMessage:(NSString *)message;
- (void)applyPendingPreviewPresentationIfPossible;
- (nullable NSURL *)readableMediaURLFromInput:(NSString *)input;
- (void)requestPhotoLibraryAddAuthorization:
    (void (^)(BOOL authorized))completion;
- (void)saveMediaAtURI:(NSString *)mediaURI
                  name:(nullable NSString *)name
             mediaType:(NSString *)mediaType
            completion:(NosmaiIOSCompletion)completion;
- (BOOL)ensureCloudEnabledForCompletion:(NosmaiIOSCompletion)completion;
- (void)fetchMergedCloudCatalogWithEffects:(NosmaiEffectsEngine *)effects
                                      page:(NSInteger)page
                                     limit:(NSInteger)limit
                             fetchAllPages:(BOOL)fetchAllPages
                                completion:
                                    (NosmaiIOSCloudCatalogCompletion)completion;
- (nullable NSDictionary *)cloudFilterMapFromDictionary:(NSDictionary *)raw
                                    requestedPackageType:
                                        (nullable NSString *)packageType
                                                 effects:
                                                     (NosmaiEffectsEngine *)effects;
- (void)finishCloudDownloadForIdentifier:(NSString *)filterId
                               generation:(NSUInteger)generation
                                    value:(nullable id)value
                                     code:(nullable NSString *)code
                                  message:(nullable NSString *)message
                                    error:(nullable NSError *)error;
- (BOOL)ensureVisualMutationReadyForCompletion:
    (NosmaiIOSCompletion)completion;
- (BOOL)ensureVisualFeaturesRequiringBeauty:(BOOL)requiresBeauty
                                    advanced:(BOOL)requiresAdvanced
                                  completion:(NosmaiIOSCompletion)completion;
- (void)enqueueVisualMutationRequiringBeauty:(BOOL)requiresBeauty
                                     advanced:(BOOL)requiresAdvanced
                                     keepAlive:(nullable id)keepAlive
                                      mutation:(NosmaiIOSVisualMutation)mutation
                                    afterFence:
                                        (nullable dispatch_block_t)afterFence
                                    completion:(NosmaiIOSCompletion)completion;
- (void)performVisualMutationAtFIFOTurnRequiringBeauty:
            (BOOL)requiresBeauty
                                                   advanced:
                                                       (BOOL)requiresAdvanced
                                                   keepAlive:(nullable id)keepAlive
                                                    mutation:
                                                        (NosmaiIOSVisualMutation)mutation
                                                  afterFence:
                                                      (nullable dispatch_block_t)afterFence
                                                      finish:
                                                          (NosmaiIOSCompletion)finish;
- (nullable NSURL *)strictLocalFileURLFromInput:(NSString *)input;
- (nullable UIImage *)downsampledBackgroundImageAtURL:(NSURL *)url;
- (BOOL)isSupportedBackgroundVideoAtURL:(NSURL *)url;
- (void)resetCameraLightModes;
- (void)updateLiveFrameCallback;
- (void)updateLiveFrameOutputDemand;
- (void)clearLiveFrameCallbackAfterProcessingStopped;
- (void)stopFrameStreamInternal;
- (void)updateGameEventHandler;
@end

@implementation NosmaiIOSController {
  __weak id<NosmaiIOSModuleEventSink> _moduleSink;
  __weak id<NosmaiIOSPreviewSink> _previewSink;
  __weak UIView *_previewContainer;
  __weak id<NosmaiIOSPreviewSink> _pendingPreviewSink;
  __weak UIView *_pendingPreviewContainer;
  __weak id<NosmaiIOSPreviewSink> _pendingPresentationSink;

  dispatch_queue_t _cameraQueue;
  dispatch_queue_t _mediaQueue;
  dispatch_queue_t _cloudQueue;

  BOOL _initialized;
  BOOL _initializing;
  BOOL _processing;
  BOOL _nativeProcessingActive;
  BOOL _starting;
  BOOL _stopping;
  BOOL _manualPaused;
  BOOL _hostPaused;
  BOOL _resumeInProgress;
  BOOL _switching;
  BOOL _cleanupInProgress;
  BOOL _readyDelivered;
  BOOL _processedFrameReadinessArmed;
  BOOL _liveFrameDispatcherInstalled;
  std::atomic_bool _processedFrameReadinessArmedAtomic;
  std::atomic_uint64_t _readyGenerationAtomic;
  std::atomic<double> _readyNotBeforeTimestampAtomic;
  BOOL _recordingNativeStartInFlight;
  BOOL _recordingAwaitingMicrophonePermissionLifecycle;
  BOOL _recordingAuthorizationPendingResume;
  BOOL _recordingStopCallbackReceived;
  BOOL _recordingForceStopRequested;
  BOOL _captureInProgress;
  BOOL _captureNativeCallbackReceived;
  BOOL _hasPendingPreviewAttachment;
  BOOL _hasPendingPresentation;
  BOOL _eyeColorConfigured;

  NSString *_initializedLicenseDigest;
  NSString *_initializingLicenseDigest;
  NSString *_desiredPosition;
  NSString *_sessionPreset;
  NSString *_lastLicenseStatus;
  NSString *_pendingPreviewPosition;
  NSString *_pendingPresentationPosition;

  NSUInteger _sessionGeneration;
  NSUInteger _viewGeneration;
  NSUInteger _cameraTransitionGeneration;
  NSUInteger _resumeGeneration;
  NSUInteger _readyGeneration;
  NSUInteger _pendingEffectPromiseCount;
  NSUInteger _recordingGeneration;
  NSUInteger _recordingAuthorizationGeneration;
  NSUInteger _captureGeneration;
  NSUInteger _pendingPreviewAttachmentGeneration;
  NSTimeInterval _lastSwitchTime;
  NSTimeInterval _recordingStartedAtSystemUptime;
  NSTimeInterval _lastReportedRecordingDuration;
  NSTimeInterval _recordingDurationAtStop;
  double _eyeColorIntensity;

  NSMutableArray *_initializationCompletions;
  NSMutableArray *_startCompletions;
  NSMutableArray *_stopCompletions;
  NSMutableArray *_cleanupCompletions;
  NSMutableArray *_effectOperations;
  NSMutableArray *_effectDrainCompletions;
  NSMutableArray *_recordingStartCompletions;
  NSMutableArray *_recordingStopCompletions;
  NSMutableArray *_recordingTeardownCompletions;
  NSMutableDictionary<NSString *, NSMutableArray *> *_cloudDownloadCompletions;
  NSMutableDictionary<NSString *, NSNumber *> *_cloudDownloadGenerations;
  NosmaiIOSCompletion _captureCompletion;
  NSTimer *_recordingProgressTimer;
  UIBackgroundTaskIdentifier _recordingBackgroundTaskIdentifier;
  NosmaiIOSFrameStream *_frameStream;
  NosmaiIOSRecordingState _recordingState;
  BOOL _effectOperationActive;
  BOOL _desiredMirror;
  BOOL _pendingPreviewMirror;
  BOOL _pendingPresentationMirror;
  AVCaptureFlashMode _currentFlashMode;
  AVCaptureTorchMode _currentTorchMode;
  NSUInteger _cloudDownloadGeneration;
}

+ (instancetype)shared {
  static NosmaiIOSController *controller;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    controller = [[self alloc] initPrivate];
  });
  return controller;
}

- (instancetype)initPrivate {
  self = [super init];
  if (self) {
    _cameraQueue = dispatch_queue_create("com.nosmai.reactnative.camera.ios",
                                         DISPATCH_QUEUE_SERIAL);
    _mediaQueue = dispatch_queue_create("com.nosmai.reactnative.media.ios",
                                        DISPATCH_QUEUE_SERIAL);
    _cloudQueue = dispatch_queue_create("com.nosmai.reactnative.cloud.ios",
                                        DISPATCH_QUEUE_SERIAL);
    _desiredPosition = @"front";
    _currentFlashMode = AVCaptureFlashModeOff;
    _currentTorchMode = AVCaptureTorchModeOff;
    _sessionPreset = AVCaptureSessionPreset1280x720;
    _lastLicenseStatus = @"unknown";
    _initializationCompletions = [NSMutableArray array];
    _startCompletions = [NSMutableArray array];
    _stopCompletions = [NSMutableArray array];
    _cleanupCompletions = [NSMutableArray array];
    _effectOperations = [NSMutableArray array];
    _effectDrainCompletions = [NSMutableArray array];
    _recordingStartCompletions = [NSMutableArray array];
    _recordingStopCompletions = [NSMutableArray array];
    _recordingTeardownCompletions = [NSMutableArray array];
    _cloudDownloadCompletions = [NSMutableDictionary dictionary];
    _cloudDownloadGenerations = [NSMutableDictionary dictionary];
    _frameStream = [[NosmaiIOSFrameStream alloc] init];
    _recordingState = NosmaiIOSRecordingStateIdle;
    _recordingBackgroundTaskIdentifier = UIBackgroundTaskInvalid;
    _hostPaused = UIApplication.sharedApplication.applicationState !=
                  UIApplicationStateActive;
    _processedFrameReadinessArmedAtomic.store(false);
    _readyGenerationAtomic.store(0);
    _readyNotBeforeTimestampAtomic.store(DBL_MAX);

    NSNotificationCenter *center = NSNotificationCenter.defaultCenter;
    [center addObserver:self
               selector:@selector(applicationWillResignActive:)
                   name:UIApplicationWillResignActiveNotification
                 object:nil];
    [center addObserver:self
               selector:@selector(applicationDidBecomeActive:)
                   name:UIApplicationDidBecomeActiveNotification
                 object:nil];
  }
  return self;
}

- (instancetype)init {
  return [NosmaiIOSController shared];
}

- (void)dealloc {
  [NosmaiCore shared].effects.gameEventHandler = nil;
  [NSNotificationCenter.defaultCenter removeObserver:self];
}

#pragma mark - React ownership

- (void)attachModuleSink:(id<NosmaiIOSModuleEventSink>)sink {
  NosmaiIOSRunOnMain(^{
    self->_moduleSink = sink;
    [self updateGameEventHandler];
    if (self->_initialized) {
      [sink nosmaiControllerDidChangeLicenseStatus:self->_lastLicenseStatus];
      NSDictionary *state = [self currentPipelineStateMap];
      if (state) {
        [sink nosmaiControllerDidChangeActiveEffects:state];
      }
    }
  });
}

- (void)detachModuleSink:(id<NosmaiIOSModuleEventSink>)sink {
  NosmaiIOSRunOnMain(^{
    if (self->_moduleSink == sink) {
      self->_moduleSink = nil;
      [self updateGameEventHandler];
    }
  });
}

- (void)updateGameEventHandler {
  NSAssert(NSThread.isMainThread,
           @"Game event ownership must be updated on the main thread");
  NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
  if (!effects) return;
  if (!_initialized || !_moduleSink) {
    effects.gameEventHandler = nil;
    return;
  }

  __weak NosmaiIOSController *weakSelf = self;
  effects.gameEventHandler = ^(NSDictionary<NSString *, id> *event) {
    NosmaiIOSRunOnMain(^{
      NosmaiIOSController *strongSelf = weakSelf;
      if (!strongSelf || !strongSelf->_initialized ||
          !strongSelf->_moduleSink || ![event isKindOfClass:NSDictionary.class]) {
        return;
      }
      [strongSelf->_moduleSink nosmaiControllerDidReceiveGameEvent:event];
    });
  };
}

- (void)attachPreviewSink:(id<NosmaiIOSPreviewSink>)sink
                 container:(UIView *)container
                   position:(NSString *)position
                     mirror:(BOOL)mirror {
  NosmaiIOSRunOnMain(^{
    NSString *normalized = NosmaiIOSNormalizedPosition(position) ?: @"front";
    if (self->_recordingState != NosmaiIOSRecordingStateIdle) {
      NSUInteger pendingGeneration =
          ++self->_pendingPreviewAttachmentGeneration;
      self->_pendingPreviewSink = sink;
      self->_pendingPreviewContainer = container;
      self->_pendingPreviewPosition = [normalized copy];
      self->_pendingPreviewMirror = mirror;
      self->_hasPendingPreviewAttachment = YES;
      [sink nosmaiControllerShowTransition];
      __weak id<NosmaiIOSPreviewSink> weakSink = sink;
      __weak UIView *weakContainer = container;
      [self stopRecordingForTeardown:^{
        id<NosmaiIOSPreviewSink> pendingSink = weakSink;
        UIView *pendingContainer = weakContainer;
        if (!self->_hasPendingPreviewAttachment ||
            pendingGeneration != self->_pendingPreviewAttachmentGeneration ||
            self->_pendingPreviewSink != pendingSink ||
            self->_pendingPreviewContainer != pendingContainer) {
          return;
        }
        NSString *pendingPosition = self->_pendingPreviewPosition;
        BOOL pendingMirror = self->_pendingPreviewMirror;
        self->_hasPendingPreviewAttachment = NO;
        self->_pendingPreviewSink = nil;
        self->_pendingPreviewContainer = nil;
        self->_pendingPreviewPosition = nil;
        if (!pendingSink || !pendingContainer) return;
        [self attachPreviewSink:pendingSink
                       container:pendingContainer
                         position:pendingPosition
                           mirror:pendingMirror];
      }];
      return;
    }

    self->_hasPendingPreviewAttachment = NO;
    self->_pendingPreviewSink = nil;
    self->_pendingPreviewContainer = nil;
    self->_pendingPreviewPosition = nil;
    BOOL positionChanged =
        ![self->_desiredPosition isEqualToString:normalized];
    id<NosmaiIOSPreviewSink> previousSink = self->_previewSink;
    BOOL previewReplaced = previousSink && previousSink != sink;
    if (positionChanged || previewReplaced) {
      [self stopFrameStreamInternal];
      [self invalidateProcessedFrameReadiness];
    }
    if (positionChanged) [self resetCameraLightModes];
    if (previousSink && previousSink != sink && self->_initialized) {
      @try {
        [[NosmaiCore shared].camera detachFromView];
      } @catch (__unused NSException *exception) {
      }
    }

    self->_viewGeneration++;
    self->_previewSink = sink;
    self->_previewContainer = container;
    self->_desiredPosition = normalized;
    self->_desiredMirror = mirror;
    [sink nosmaiControllerShowTransition];
    [self applyPreviewPresentation];

    if (self->_initialized) {
      [self attachCurrentPreview];
      if (positionChanged) {
        [self configureNativeCameraForCurrentSettingsWithCompletion:nil];
      } else if (self->_processing && !self->_manualPaused &&
                 !self->_hostPaused) {
        [self armProcessedFrameReadiness];
      }
    }
  });
}

- (void)updatePreviewSink:(id<NosmaiIOSPreviewSink>)sink
                  position:(NSString *)position
                    mirror:(BOOL)mirror {
  NosmaiIOSRunOnMain(^{
    NSString *normalized = NosmaiIOSNormalizedPosition(position);
    if (!normalized) {
      [sink nosmaiControllerDidReceiveCameraErrorWithCode:
                    NosmaiIOSErrorInvalidArgument
                                                   message:
                    @"cameraPosition must be 'front' or 'back'"];
      return;
    }
    if (self->_hasPendingPreviewAttachment &&
        self->_pendingPreviewSink == sink) {
      self->_pendingPreviewPosition = [normalized copy];
      self->_pendingPreviewMirror = mirror;
      return;
    }
    if (self->_previewSink != sink) {
      return;
    }

    BOOL positionChanged = ![self->_desiredPosition isEqualToString:normalized];
    BOOL presentationChanged = positionChanged || self->_desiredMirror != mirror;
    BOOL recordingBusy =
        self->_recordingState != NosmaiIOSRecordingStateIdle;
    BOOL captureBusy = self->_captureInProgress;
    if ((recordingBusy || captureBusy) &&
        (presentationChanged || self->_hasPendingPresentation)) {
      self->_pendingPresentationSink = sink;
      self->_pendingPresentationPosition = [normalized copy];
      self->_pendingPresentationMirror = mirror;
      self->_hasPendingPresentation = YES;
      [self emitAsyncErrorWithCode:
                recordingBusy ? NosmaiIOSErrorRecordingInProgress
                              : NosmaiIOSErrorCaptureInProgress
                           message:recordingBusy
                               ? @"Stop recording before changing camera presentation"
                               : @"Wait for photo capture before changing camera presentation"
                             error:nil
                        cameraView:NO];
      return;
    }
    if (self->_pendingPresentationSink == sink) {
      self->_hasPendingPresentation = NO;
      self->_pendingPresentationSink = nil;
      self->_pendingPresentationPosition = nil;
    }
    if (positionChanged) {
      [self stopFrameStreamInternal];
      [self invalidateProcessedFrameReadiness];
      [self resetCameraLightModes];
    }
    self->_desiredPosition = normalized;
    self->_desiredMirror = mirror;
    [self applyPreviewPresentation];

    if (positionChanged && self->_initialized) {
      [sink nosmaiControllerShowTransition];
      [self configureNativeCameraForCurrentSettingsWithCompletion:nil];
    }
  });
}

- (void)detachPreviewSink:(id<NosmaiIOSPreviewSink>)sink {
  NosmaiIOSRunOnMain(^{
    if (self->_pendingPreviewSink == sink) {
      self->_pendingPreviewAttachmentGeneration++;
      self->_hasPendingPreviewAttachment = NO;
      self->_pendingPreviewSink = nil;
      self->_pendingPreviewContainer = nil;
      self->_pendingPreviewPosition = nil;
    }
    if (self->_pendingPresentationSink == sink) {
      self->_hasPendingPresentation = NO;
      self->_pendingPresentationSink = nil;
      self->_pendingPresentationPosition = nil;
    }
    if (self->_previewSink != sink) {
      return;
    }

    self->_viewGeneration++;
    NSUInteger detachedViewGeneration = self->_viewGeneration;
    self->_cameraTransitionGeneration++;
    [self stopFrameStreamInternal];
    [self invalidateProcessedFrameReadiness];
    self->_previewSink = nil;
    self->_previewContainer = nil;
    self->_manualPaused = NO;

    BOOL shouldStop = self->_processing || self->_starting;
    self->_processing = NO;
    self->_nativeProcessingActive = NO;
    self->_starting = NO;
    self->_stopping = NO;
    self->_resumeInProgress = NO;
    [self finishCompletions:self->_startCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"The camera preview was unmounted"
                      error:nil];
    [self finishCompletions:self->_stopCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"The camera preview was unmounted"
                      error:nil];
    [self cancelPendingCaptureWithMessage:
              @"Photo capture was cancelled because the preview was unmounted"];

    NosmaiCore *core = [NosmaiCore shared];
    NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
    [self stopRecordingForTeardown:^{
      dispatch_async(self->_cameraQueue, ^{
        if (shouldStop) {
          @try {
            [core.camera stopCapture];
          } @catch (__unused NSException *exception) {
          }
          @try {
            [sdk stopProcessing];
          } @catch (__unused NSException *exception) {
          }
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          [self clearLiveFrameCallbackAfterProcessingStopped];
          if (detachedViewGeneration != self->_viewGeneration ||
              self->_previewSink) {
            return;
          }
          @try {
            [core.camera detachFromView];
          } @catch (__unused NSException *exception) {
          }
        });
      });
    }];
  });
}

#pragma mark - Initialization

- (void)initializeWithLicenseKey:(NSString *)licenseKey
                       completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    NSString *trimmed =
        [licenseKey stringByTrimmingCharactersInSet:
                        NSCharacterSet.whitespaceAndNewlineCharacterSet];
    if (trimmed.length == 0) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"licenseKey must not be empty", nil);
      return;
    }
    if (self->_cleanupInProgress) {
      completion(nil, NosmaiIOSErrorOperationCancelled,
                 @"Session cleanup is in progress", nil);
      return;
    }

    NSString *digest = NosmaiIOSLicenseDigest(trimmed);
    if (self->_initializedLicenseDigest &&
        ![self->_initializedLicenseDigest isEqualToString:digest]) {
      completion(nil, NosmaiIOSErrorLicenseMismatch,
                 @"This process was already initialized with a different license key",
                 nil);
      return;
    }
    if (self->_initializing) {
      if (![self->_initializingLicenseDigest isEqualToString:digest]) {
        completion(nil, NosmaiIOSErrorLicenseMismatch,
                   @"SDK initialization is already using a different license key",
                   nil);
      } else {
        [self->_initializationCompletions addObject:[completion copy]];
      }
      return;
    }

    [self->_initializationCompletions addObject:[completion copy]];
    self->_initializing = YES;
    self->_initializingLicenseDigest = digest;
    NSUInteger operationSession = self->_sessionGeneration;
    [self emitLicenseStatus:@"unverified"];

    NosmaiCore *core = [NosmaiCore shared];
    core.delegate = self;
    [core initializeWithAPIKey:trimmed
                    completion:^(BOOL success, NSError *error) {
      NosmaiIOSRunOnMain(^{
        if (!self->_initializing || operationSession != self->_sessionGeneration) {
          return;
        }

        self->_initializing = NO;
        self->_initializingLicenseDigest = nil;
        if (!success || error || !core.isInitialized || !core.camera ||
            !core.effects || ![NosmaiSDK sharedInstance]) {
          NSString *message = error.localizedDescription ?:
              @"Unable to initialize the Nosmai SDK";
          NSString *code = [message containsString:@"different API key"]
              ? NosmaiIOSErrorLicenseMismatch
              : NosmaiIOSErrorInitialization;
          [self finishCompletions:self->_initializationCompletions
                            value:nil
                             code:code
                          message:message
                            error:error];
          return;
        }

        self->_initialized = YES;
        self->_initializedLicenseDigest = digest;
        self->_eyeColorConfigured = NO;
        self->_eyeColorIntensity = 0.0;
        core.delegate = self;
        core.camera.delegate = self;
        core.effects.delegate = self;
        [self updateGameEventHandler];
        [NosmaiSDK sharedInstance].delegate = self;

        [self configureNativeCameraForCurrentSettingsWithCompletion:nil];
        [self attachCurrentPreview];
        [self emitLicenseStatus:core.isLicenseValid ? @"valid" : @"unverified"];
        [self emitCurrentPipelineState];
        [self finishCompletions:self->_initializationCompletions
                          value:@YES
                           code:nil
                        message:nil
                          error:nil];
      });
    }];
  });
}

#pragma mark - Camera configuration and processing

- (void)configureCameraPosition:(NSString *)position
                  sessionPreset:(NSString *_Nullable)sessionPreset
                      completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureNotCleaningForCompletion:completion]) {
      return;
    }
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"configure the camera"]) {
      return;
    }
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"Wait for photo capture to finish before configuring the camera",
                 nil);
      return;
    }
    NSString *normalizedPosition = NosmaiIOSNormalizedPosition(position);
    if (!normalizedPosition) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"position must be 'front' or 'back'", nil);
      return;
    }
    NSString *normalizedPreset = NosmaiIOSNormalizedPreset(sessionPreset);
    if (!normalizedPreset) {
      completion(nil, @"E_UNSUPPORTED_CAMERA_PRESET",
                 [NSString stringWithFormat:@"Unsupported iOS camera preset: %@",
                                            sessionPreset ?: @"<null>"],
                 nil);
      return;
    }

    BOOL positionChanged =
        ![self->_desiredPosition isEqualToString:normalizedPosition];
    BOOL presetChanged = ![self->_sessionPreset isEqualToString:normalizedPreset];
    if (positionChanged || presetChanged) {
      [self stopFrameStreamInternal];
      [self invalidateProcessedFrameReadiness];
    }
    if (positionChanged) [self resetCameraLightModes];
    self->_desiredPosition = normalizedPosition;
    self->_sessionPreset = normalizedPreset;
    [self applyPreviewPresentation];

    if (!self->_initialized) {
      completion(nil, nil, nil, nil);
      return;
    }
    if (positionChanged && self->_processing && !self->_manualPaused &&
        !self->_hostPaused) {
      [self->_previewSink nosmaiControllerShowTransition];
    }
    [self configureNativeCameraForCurrentSettingsWithCompletion:completion];
  });
}

- (void)startProcessingWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) {
      return;
    }
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"start processing"]) {
      return;
    }
    if (!self->_previewSink || !self->_previewContainer) {
      completion(nil, NosmaiIOSErrorNoPreview,
                 @"Mount NosmaiCameraView before starting", nil);
      return;
    }
    if (self->_hostPaused) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Cannot start the camera while the app is inactive", nil);
      return;
    }
    if (self->_resumeInProgress) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Camera resume is already in progress", nil);
      return;
    }
    if (self->_processing && self->_nativeProcessingActive &&
        !self->_stopping) {
      completion(nil, nil, nil, nil);
      return;
    }
    if (self->_starting) {
      [self->_startCompletions addObject:[completion copy]];
      return;
    }
    if (self->_stopping) {
      completion(nil, NosmaiIOSErrorOperationCancelled,
                 @"Processing is still stopping", nil);
      return;
    }

    [self->_startCompletions addObject:[completion copy]];
    self->_starting = YES;
    self->_processing = NO;
    self->_nativeProcessingActive = NO;
    NSUInteger transition = ++self->_cameraTransitionGeneration;
    NSUInteger operationSession = self->_sessionGeneration;
    [self->_previewSink nosmaiControllerShowTransition];
    [self attachCurrentPreview];

    [self requestCameraAuthorization:^(BOOL authorized) {
      if (!self->_starting || transition != self->_cameraTransitionGeneration ||
          operationSession != self->_sessionGeneration) {
        return;
      }
      if (!authorized) {
        self->_starting = NO;
        [self finishCompletions:self->_startCompletions
                          value:nil
                           code:NosmaiIOSErrorCameraPermission
                        message:@"Camera permission is required"
                          error:nil];
        return;
      }
      if (self->_hostPaused) {
        self->_starting = NO;
        [self finishCompletions:self->_startCompletions
                          value:nil
                           code:NosmaiIOSErrorInvalidState
                        message:@"Cannot start the camera while the app is inactive"
                          error:nil];
        return;
      }

      // Install the dispatcher while native processing is quiescent. The SDK
      // reads this block lock-free on its frame thread, so it must not be
      // swapped while a processing session can still deliver callbacks.
      [self updateLiveFrameCallback];
      [self updateLiveFrameOutputDemand];
      NosmaiCore *core = [NosmaiCore shared];
      NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
      dispatch_async(self->_cameraQueue, ^{
        __block BOOL started = NO;
        __block NSError *failure = nil;
        @try {
          started = [core.camera startCapture];
          if (!started) {
            failure = NosmaiIOSError(NosmaiIOSErrorCameraStart,
                                     @"The native camera rejected startCapture",
                                     nil);
          } else {
            [sdk startProcessing];
          }
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorProcessingStart,
              @"Unable to start Nosmai processing");
        }

        if (failure && started) {
          @try {
            [core.camera stopCapture];
          } @catch (__unused NSException *exception) {
          }
          @try {
            [sdk stopProcessing];
          } @catch (__unused NSException *exception) {
          }
          started = NO;
        }

        dispatch_async(dispatch_get_main_queue(), ^{
          if (transition != self->_cameraTransitionGeneration ||
              operationSession != self->_sessionGeneration) {
            if (started) {
              dispatch_async(self->_cameraQueue, ^{
                @try {
                  [core.camera stopCapture];
                } @catch (__unused NSException *exception) {
                }
                @try {
                  [sdk stopProcessing];
                } @catch (__unused NSException *exception) {
                }
                dispatch_async(dispatch_get_main_queue(), ^{
                  [self clearLiveFrameCallbackAfterProcessingStopped];
                });
              });
            } else {
              [self clearLiveFrameCallbackAfterProcessingStopped];
            }
            self->_starting = NO;
            [self finishCompletions:self->_startCompletions
                              value:nil
                               code:NosmaiIOSErrorOperationCancelled
                            message:@"Camera start was superseded by a newer transition"
                              error:nil];
            return;
          }
          self->_starting = NO;
          if (!started || failure) {
            self->_processing = NO;
            self->_nativeProcessingActive = NO;
            [self clearLiveFrameCallbackAfterProcessingStopped];
            [self finishCompletions:self->_startCompletions
                              value:nil
                               code:failure.userInfo[@"nosmaiCode"] ?:
                                    NosmaiIOSErrorCameraStart
                            message:failure.localizedDescription ?:
                                    @"Unable to start the camera"
                              error:failure];
            return;
          }

          self->_processing = YES;
          self->_nativeProcessingActive = YES;
          self->_manualPaused = NO;
          [self armProcessedFrameReadiness];
          [self finishCompletions:self->_startCompletions
                            value:nil
                             code:nil
                          message:nil
                            error:nil];
        });
      });
    }];
  });
}

- (void)stopProcessingWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) {
      return;
    }
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"stop processing"]) {
      return;
    }
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"Wait for photo capture to finish before stopping processing",
                 nil);
      return;
    }
    [self stopFrameStreamInternal];
    [self->_stopCompletions addObject:[completion copy]];
    if (self->_stopping) {
      return;
    }
    if (!self->_processing && !self->_starting) {
      [self clearLiveFrameCallbackAfterProcessingStopped];
      [self finishCompletions:self->_stopCompletions
                        value:nil
                         code:nil
                      message:nil
                        error:nil];
      return;
    }

    self->_stopping = YES;
    self->_processing = NO;
    self->_nativeProcessingActive = NO;
    self->_manualPaused = NO;
    self->_starting = NO;
    NSUInteger transition = ++self->_cameraTransitionGeneration;
    [self invalidateProcessedFrameReadiness];
    [self->_previewSink nosmaiControllerShowTransition];
    [self finishCompletions:self->_startCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"Processing was stopped before camera start completed"
                      error:nil];

    NosmaiCore *core = [NosmaiCore shared];
    NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
    dispatch_async(self->_cameraQueue, ^{
      __block NSError *failure = nil;
      @try {
        [core.camera stopCapture];
      } @catch (NSException *exception) {
        failure = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorProcessingStop,
            @"Unable to stop Nosmai processing");
      }
      @try {
        [sdk stopProcessing];
      } @catch (NSException *exception) {
        if (!failure) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorProcessingStop,
              @"Unable to stop Nosmai processing");
        }
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        if (transition != self->_cameraTransitionGeneration) {
          if (!self->_cleanupInProgress) {
            self->_stopping = NO;
            [self finishCompletions:self->_stopCompletions
                              value:nil
                               code:NosmaiIOSErrorOperationCancelled
                            message:@"Camera stop was superseded by a newer transition"
                              error:nil];
          }
          return;
        }
        self->_stopping = NO;
        [self clearLiveFrameCallbackAfterProcessingStopped];
        [self finishCompletions:self->_stopCompletions
                          value:nil
                           code:failure ? NosmaiIOSErrorProcessingStop : nil
                        message:failure.localizedDescription
                          error:failure];
      });
    });
  });
}

- (void)pauseCameraWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) {
      return;
    }
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"pause the camera"]) {
      return;
    }
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"Wait for photo capture to finish before pausing the camera",
                 nil);
      return;
    }
    if (!self->_processing || self->_starting || self->_stopping ||
        self->_resumeInProgress) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Cannot pause when processing is inactive or resuming", nil);
      return;
    }
    [self stopFrameStreamInternal];
    if (self->_manualPaused) {
      completion(@YES, nil, nil, nil);
      return;
    }

    self->_manualPaused = YES;
    NSUInteger transition = ++self->_cameraTransitionGeneration;
    [self invalidateProcessedFrameReadiness];
    [self->_previewSink nosmaiControllerShowTransition];
    NosmaiCamera *camera = [NosmaiCore shared].camera;
    dispatch_async(self->_cameraQueue, ^{
      __block NSError *failure = nil;
      @try {
        [camera stopCapture];
      } @catch (NSException *exception) {
        failure = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorProcessingStop,
            @"Unable to pause the camera");
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        if (transition != self->_cameraTransitionGeneration) {
          completion(nil, NosmaiIOSErrorOperationCancelled,
                     @"Camera pause was superseded by a newer transition", nil);
        } else if (failure) {
          self->_manualPaused = NO;
          completion(nil, NosmaiIOSErrorProcessingStop,
                     failure.localizedDescription, failure);
        } else {
          completion(@YES, nil, nil, nil);
        }
      });
    });
  });
}

- (void)resumeCameraWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) {
      return;
    }
    if (!self->_processing || self->_starting || self->_stopping) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Cannot resume when processing is not active", nil);
      return;
    }
    if (self->_resumeInProgress) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Camera resume is already in progress", nil);
      return;
    }
    if (!self->_manualPaused) {
      completion(@YES, nil, nil, nil);
      return;
    }

    self->_manualPaused = NO;
    if (self->_hostPaused) {
      completion(@YES, nil, nil, nil);
      return;
    }

    NSUInteger transition = ++self->_cameraTransitionGeneration;
    self->_resumeInProgress = YES;
    self->_resumeGeneration = transition;
    self->_nativeProcessingActive = NO;
    [self attachCurrentPreview];
    [self->_previewSink nosmaiControllerShowTransition];
    [self updateLiveFrameCallback];
    [self updateLiveFrameOutputDemand];
    NosmaiCamera *camera = [NosmaiCore shared].camera;
    NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
    dispatch_async(self->_cameraQueue, ^{
      __block BOOL started = NO;
      __block NSError *failure = nil;
      @try {
        started = [camera startCapture];
        if (started) {
          [sdk startProcessing];
        }
      } @catch (NSException *exception) {
        failure = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorCameraStart,
            @"Unable to resume the camera");
      }
      if (failure && started) {
        @try {
          [camera stopCapture];
        } @catch (__unused NSException *exception) {
        }
        @try {
          [sdk stopProcessing];
        } @catch (__unused NSException *exception) {
        }
        started = NO;
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        if (self->_resumeGeneration == transition) {
          self->_resumeInProgress = NO;
        }
        if (transition != self->_cameraTransitionGeneration) {
          completion(nil, NosmaiIOSErrorOperationCancelled,
                     @"Camera resume was superseded by a newer transition", nil);
        } else if (!started || failure) {
          self->_manualPaused = YES;
          self->_nativeProcessingActive = NO;
          [self clearLiveFrameCallbackAfterProcessingStopped];
          completion(nil, NosmaiIOSErrorCameraStart,
                     failure.localizedDescription ?: @"Unable to resume the camera",
                     failure);
        } else {
          self->_nativeProcessingActive = YES;
          [self armProcessedFrameReadiness];
          completion(@YES, nil, nil, nil);
        }
      });
    });
  });
}

- (void)switchCameraWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) {
      return;
    }
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"switch cameras"]) {
      return;
    }
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"Wait for photo capture to finish before switching cameras",
                 nil);
      return;
    }
    if (self->_starting || self->_stopping || self->_resumeInProgress) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Cannot switch cameras during a start, stop, or resume transition",
                 nil);
      return;
    }
    NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
    if (self->_switching ||
        now - self->_lastSwitchTime < NosmaiIOSCameraSwitchDebounceSeconds) {
      completion(@NO, nil, nil, nil);
      return;
    }

    [self stopFrameStreamInternal];
    [self invalidateProcessedFrameReadiness];
    self->_switching = YES;
    self->_lastSwitchTime = now;
    NSString *previousPosition = self->_desiredPosition;
    [self resetCameraLightModes];
    self->_desiredPosition = [previousPosition isEqualToString:@"front"]
        ? @"back"
        : @"front";
    [self applyPreviewPresentation];
    [self->_previewSink nosmaiControllerShowTransition];
    NSUInteger transition = ++self->_cameraTransitionGeneration;
    NosmaiCameraPosition target = [self->_desiredPosition isEqualToString:@"back"]
        ? NosmaiCameraPositionBack
        : NosmaiCameraPositionFront;
    NosmaiCamera *camera = [NosmaiCore shared].camera;

    dispatch_async(self->_cameraQueue, ^{
      __block BOOL switched = NO;
      __block NSError *failure = nil;
      @try {
        switched = [camera switchToPosition:target];
      } @catch (NSException *exception) {
        failure = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorCameraDevice,
            @"Unable to switch the camera");
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        self->_switching = NO;
        if (transition != self->_cameraTransitionGeneration) {
          completion(nil, NosmaiIOSErrorOperationCancelled,
                     @"Camera switch was superseded by a newer transition", nil);
          return;
        }
        if (!switched || failure) {
          self->_desiredPosition = previousPosition;
          [self applyPreviewPresentation];
          completion(nil, NosmaiIOSErrorCameraDevice,
                     failure.localizedDescription ?: @"Unable to switch the camera",
                     failure);
          return;
        }
        if (self->_processing && !self->_manualPaused && !self->_hostPaused) {
          [self armProcessedFrameReadiness];
        }
        completion(@YES, nil, nil, nil);
      });
    });
  });
}

#pragma mark - Flash and torch

- (void)hasFlashWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      completion(@([[NosmaiCore shared].camera hasFlash]), nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorCameraDevice,
          @"Unable to read the current camera flash capability");
      completion(nil, NosmaiIOSErrorCameraDevice, error.localizedDescription,
                 error);
    }
  });
}

- (void)hasTorchWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      completion(@([[NosmaiCore shared].camera hasTorch]), nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorCameraDevice,
          @"Unable to read the current camera torch capability");
      completion(nil, NosmaiIOSErrorCameraDevice, error.localizedDescription,
                 error);
    }
  });
}

- (void)setFlashMode:(NSString *)mode
           completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *normalized = NosmaiIOSNonEmptyString(mode).lowercaseString;
    AVCaptureFlashMode nativeMode;
    if ([normalized isEqualToString:@"off"]) {
      nativeMode = AVCaptureFlashModeOff;
    } else if ([normalized isEqualToString:@"on"]) {
      nativeMode = AVCaptureFlashModeOn;
    } else if ([normalized isEqualToString:@"auto"]) {
      nativeMode = AVCaptureFlashModeAuto;
    } else {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"mode must be off, on, or auto", nil);
      return;
    }

    NosmaiCamera *camera = [NosmaiCore shared].camera;
    if (nativeMode != AVCaptureFlashModeOff && !camera.hasFlash) {
      completion(@NO, nil, nil, nil);
      return;
    }
    NSUInteger operationSession = self->_sessionGeneration;
    dispatch_async(self->_cameraQueue, ^{
      __block BOOL success = NO;
      __block NSError *failure = nil;
      @try {
        success = [camera setFlashMode:nativeMode];
      } @catch (NSException *exception) {
        failure = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorCameraDevice,
            @"Unable to set the camera flash mode");
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        if (operationSession != self->_sessionGeneration) {
          completion(nil, NosmaiIOSErrorOperationCancelled,
                     @"Flash mode was superseded by session cleanup", nil);
        } else if (failure) {
          completion(nil, NosmaiIOSErrorCameraDevice,
                     failure.localizedDescription, failure);
        } else {
          if (success) self->_currentFlashMode = nativeMode;
          completion(@(success), nil, nil, nil);
        }
      });
    });
  });
}

- (void)setTorchMode:(NSString *)mode
           completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *normalized = NosmaiIOSNonEmptyString(mode).lowercaseString;
    AVCaptureTorchMode nativeMode;
    if ([normalized isEqualToString:@"off"]) {
      nativeMode = AVCaptureTorchModeOff;
    } else if ([normalized isEqualToString:@"on"]) {
      nativeMode = AVCaptureTorchModeOn;
    } else {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"torch mode must be off or on", nil);
      return;
    }

    NosmaiCamera *camera = [NosmaiCore shared].camera;
    if (nativeMode != AVCaptureTorchModeOff && !camera.hasTorch) {
      completion(@NO, nil, nil, nil);
      return;
    }
    NSUInteger operationSession = self->_sessionGeneration;
    dispatch_async(self->_cameraQueue, ^{
      __block BOOL success = NO;
      __block NSError *failure = nil;
      @try {
        success = [camera setTorchMode:nativeMode];
      } @catch (NSException *exception) {
        failure = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorCameraDevice,
            @"Unable to set the camera torch mode");
      }
      dispatch_async(dispatch_get_main_queue(), ^{
        if (operationSession != self->_sessionGeneration) {
          completion(nil, NosmaiIOSErrorOperationCancelled,
                     @"Torch mode was superseded by session cleanup", nil);
        } else if (failure) {
          completion(nil, NosmaiIOSErrorCameraDevice,
                     failure.localizedDescription, failure);
        } else {
          if (success) self->_currentTorchMode = nativeMode;
          completion(@(success), nil, nil, nil);
        }
      });
    });
  });
}

- (void)getFlashModeWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *mode = @"off";
    if ([NosmaiCore shared].camera.hasFlash) {
      if (self->_currentFlashMode == AVCaptureFlashModeOn) mode = @"on";
      if (self->_currentFlashMode == AVCaptureFlashModeAuto) mode = @"auto";
    }
    completion(mode, nil, nil, nil);
  });
}

- (void)getTorchModeWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *mode = @"off";
    if ([NosmaiCore shared].camera.hasTorch) {
      if (self->_currentTorchMode == AVCaptureTorchModeOn) mode = @"on";
    }
    completion(mode, nil, nil, nil);
  });
}

#pragma mark - Cleanup

- (void)cleanupWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"clean up the session"]) {
      return;
    }
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"Wait for photo capture to finish before cleaning up the session",
                 nil);
      return;
    }
    [self->_cleanupCompletions addObject:[completion copy]];
    if (self->_cleanupInProgress) {
      return;
    }
    [self resetCameraLightModes];
    [self stopFrameStreamInternal];
    [NosmaiCore shared].effects.gameEventHandler = nil;
    self->_cleanupInProgress = YES;
    self->_sessionGeneration++;
    self->_cameraTransitionGeneration++;
    [self invalidateProcessedFrameReadiness];
    [self->_previewSink nosmaiControllerShowTransition];
    [self finishCompletions:self->_initializationCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"SDK initialization was cancelled by cleanup"
                      error:nil];
    [self finishCompletions:self->_startCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"Camera start was cancelled by cleanup"
                      error:nil];
    [self finishCompletions:self->_stopCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"Camera stop was superseded by cleanup"
                      error:nil];

    self->_initializing = NO;
    self->_starting = NO;
    self->_stopping = NO;
    self->_processing = NO;
    self->_nativeProcessingActive = NO;
    self->_manualPaused = NO;
    self->_resumeInProgress = NO;
    self->_switching = NO;

    // An in-flight native apply cannot be cancelled. Wait for the serialized
    // mutation queue to finish its native callback/state confirmation before
    // clearing the pipeline, so a late apply cannot undo cleanup.
    [self whenEffectOperationsDrained:^{
      NosmaiCore *core = [NosmaiCore shared];
      NosmaiCamera *camera = core.camera;
      NosmaiEffectsEngine *effects = core.effects;
      NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
      dispatch_async(self->_cameraQueue, ^{
        __block NSError *failure = nil;
        @try {
          [camera stopCapture];
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorCleanup,
              @"Unable to stop the native camera session during cleanup");
        }
        @try {
          [sdk stopProcessing];
        } @catch (NSException *exception) {
          if (!failure) {
            failure = NosmaiIOSErrorFromException(
                exception, NosmaiIOSErrorCleanup,
                @"Unable to stop Nosmai processing during cleanup");
          }
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          dispatch_block_t finalizeCleanup = ^{
            NosmaiIOSController *controller = self;
            if (!controller) return;
            @try {
              [camera detachFromView];
              [controller clearLiveFrameCallbackAfterProcessingStopped];
              if (core.delegate == controller) core.delegate = nil;
              if (camera.delegate == controller) camera.delegate = nil;
              if (effects.delegate == controller) effects.delegate = nil;
              if (sdk.delegate == controller) sdk.delegate = nil;
            } @catch (NSException *exception) {
              if (!failure) {
                failure = NosmaiIOSErrorFromException(
                    exception, NosmaiIOSErrorCleanup,
                    @"Unable to finish Nosmai session cleanup");
              }
            }

            controller->_eyeColorConfigured = NO;
            controller->_eyeColorIntensity = 0.0;
            controller->_initialized = NO;
            controller->_cleanupInProgress = NO;
            [controller finishCompletions:controller->_cleanupCompletions
                                    value:nil
                                     code:failure ? NosmaiIOSErrorCleanup : nil
                                  message:failure.localizedDescription
                                    error:failure];
          };

          if (!effects || !sdk) {
            finalizeCleanup();
            return;
          }

          @try {
            [effects clearAll];
          } @catch (NSException *exception) {
            if (!failure) {
              failure = NosmaiIOSErrorFromException(
                  exception, NosmaiIOSErrorCleanup,
                  @"Unable to clear effects during cleanup");
            }
            finalizeCleanup();
            return;
          }

          dispatch_async(self->_cameraQueue, ^{
            @try {
              [effects performEffectQueueSync:^{}];
            } @catch (NSException *exception) {
              if (!failure) {
                failure = NosmaiIOSErrorFromException(
                    exception, NosmaiIOSErrorCleanup,
                    @"Unable to fence the native effect cleanup queue");
              }
            }

            dispatch_async(dispatch_get_main_queue(), ^{
              if (failure) {
                finalizeCleanup();
                return;
              }
              [self waitForPipelineUntil:
                        NSProcessInfo.processInfo.systemUptime +
                        NosmaiIOSEffectStateTimeoutSeconds
                                     condition:^BOOL(NosmaiPipelineState *state,
                                                     NosmaiSDK *nativeSdk) {
                return state.activeFilterPath.length == 0 &&
                       state.activeEffectPath.length == 0 &&
                       state.activeBackgroundPackagePath.length == 0 &&
                       !state.backgroundActive &&
                       ![nativeSdk hasActiveBuiltInFilters];
              }
                                    completion:^(__unused id value,
                                                 NSString *code,
                                                 NSString *message,
                                                 NSError *waitError) {
                if (code && !failure) {
                  failure = NosmaiIOSError(
                      NosmaiIOSErrorCleanup,
                      message ?: @"Unable to verify effect cleanup", waitError);
                }
                finalizeCleanup();
              }];
            });
          });
        });
      });
    }];
  });
}

#pragma mark - Processed frame stream

- (void)startFrameStreamWithMaxFramesPerSecond:(double)maxFramesPerSecond
                                     completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    if (!std::isfinite(maxFramesPerSecond) ||
        std::floor(maxFramesPerSecond) != maxFramesPerSecond ||
        maxFramesPerSecond < 1.0 || maxFramesPerSecond > 5.0) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"maxFramesPerSecond must be an integer between 1 and 5", nil);
      return;
    }
    if (!self->_processing || !self->_nativeProcessingActive ||
        self->_starting || self->_stopping || self->_manualPaused ||
        self->_hostPaused || !self->_previewSink || !self->_previewContainer) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Start an active camera preview before starting the processed frame stream",
                 nil);
      return;
    }
    if (self->_frameStream.isActive) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"The processed frame stream is already active", nil);
      return;
    }

    __weak NosmaiIOSController *weakSelf = self;
    @try {
      [self->_frameStream
          startWithMaxFramesPerSecond:(NSUInteger)maxFramesPerSecond
                      metadataHandler:^(NSDictionary *metadata) {
        NosmaiIOSController *strongSelf = weakSelf;
        if (strongSelf && strongSelf->_moduleSink) {
          [strongSelf->_moduleSink nosmaiControllerDidMakeFrameAvailable:metadata];
        }
      }
                         errorHandler:^(NSString *code, NSString *message) {
        NosmaiIOSController *strongSelf = weakSelf;
        if (strongSelf) {
          [strongSelf emitAsyncErrorWithCode:code
                                     message:message
                                       error:nil
                                  cameraView:NO];
        }
      }];
      [self updateLiveFrameCallback];
      [self updateLiveFrameOutputDemand];
      completion(nil, nil, nil, nil);
    } @catch (NSException *exception) {
      [self stopFrameStreamInternal];
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorFrameStream,
          @"Unable to start the processed frame stream");
      completion(nil, NosmaiIOSErrorFrameStream, error.localizedDescription,
                 error);
    }
  });
}

- (void)stopFrameStreamWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    [self stopFrameStreamInternal];
    completion(nil, nil, nil, nil);
  });
}

- (void)getLatestFrameWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    [self->_frameStream
        takeLatestWithCompletion:^(NSDictionary *frame, NSString *code,
                                   NSString *message, NSError *error) {
      completion(frame, code, message, error);
    }];
  });
}

- (void)isFrameStreamActiveWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    completion(@(self->_frameStream.isActive), nil, nil, nil);
  });
}

#pragma mark - Capture and recording

- (void)startRecordingWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    if (self->_recordingState != NosmaiIOSRecordingStateIdle) {
      completion(nil, NosmaiIOSErrorRecordingInProgress,
                 @"A recording operation is already in progress", nil);
      return;
    }
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"Wait for photo capture to finish before recording", nil);
      return;
    }
    if (!self->_processing || !self->_nativeProcessingActive ||
        !self->_readyDelivered || self->_starting || self->_stopping ||
        self->_resumeInProgress || self->_switching || self->_manualPaused ||
        self->_hostPaused || !self->_previewSink ||
        ![NosmaiCore shared].camera.isCapturing) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Start processing and wait for the preview before recording",
                 nil);
      return;
    }

    [self->_recordingStartCompletions addObject:[completion copy]];
    self->_recordingState = NosmaiIOSRecordingStateStarting;
    self->_recordingNativeStartInFlight = NO;
    self->_recordingAuthorizationPendingResume = NO;
    self->_recordingAuthorizationGeneration = 0;
    self->_recordingStopCallbackReceived = NO;
    self->_recordingForceStopRequested = NO;
    self->_recordingStartedAtSystemUptime = 0;
    self->_lastReportedRecordingDuration = 0;
    NSUInteger generation = ++self->_recordingGeneration;
    self->_recordingAwaitingMicrophonePermissionLifecycle =
        [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio] ==
        AVAuthorizationStatusNotDetermined;

    [self requestMicrophoneAuthorization:^(BOOL authorized) {
      if (generation != self->_recordingGeneration ||
          self->_recordingState != NosmaiIOSRecordingStateStarting) {
        return;
      }
      if (!authorized) {
        self->_recordingAwaitingMicrophonePermissionLifecycle = NO;
        self->_recordingAuthorizationPendingResume = NO;
        self->_recordingAuthorizationGeneration = 0;
        self->_recordingState = NosmaiIOSRecordingStateIdle;
        [self finishCompletions:self->_recordingStartCompletions
                          value:nil
                           code:NosmaiIOSErrorRecordingPermission
                        message:@"Microphone permission is required for recording"
                          error:nil];
        [self finishRecordingTeardownCompletions];
        [self applyPendingPreviewPresentationIfPossible];
        return;
      }
      if (self->_recordingAwaitingMicrophonePermissionLifecycle &&
          (self->_hostPaused || self->_resumeInProgress)) {
        self->_recordingAuthorizationPendingResume = YES;
        self->_recordingAuthorizationGeneration = generation;
        return;
      }
      self->_recordingAwaitingMicrophonePermissionLifecycle = NO;
      [self continueRecordingStartAfterAuthorizationForGeneration:generation];
    }];
  });
}

- (void)stopRecordingWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    if (self->_recordingState == NosmaiIOSRecordingStateIdle) {
      completion(nil, NosmaiIOSErrorNotRecording,
                 @"No recording is in progress", nil);
      return;
    }
    if (self->_recordingState != NosmaiIOSRecordingStateRecording) {
      completion(nil, NosmaiIOSErrorRecordingInProgress,
                 @"The recording is still starting or stopping", nil);
      return;
    }

    [self->_recordingStopCompletions addObject:[completion copy]];
    [self beginNativeRecordingStop];
  });
}

- (void)isRecordingWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    completion(@(self->_recordingState != NosmaiIOSRecordingStateIdle), nil,
               nil, nil);
  });
}

- (void)getCurrentRecordingDurationWithCompletion:
    (NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    completion(@([self authoritativeRecordingDuration]), nil, nil, nil);
  });
}

- (void)capturePhotoWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    if (self->_captureInProgress) {
      completion(nil, NosmaiIOSErrorCaptureInProgress,
                 @"A photo capture is already in progress", nil);
      return;
    }
    if (![self ensureRecordingIdleForCompletion:completion
                                      operation:@"capture a photo"]) {
      return;
    }
    if (!self->_processing || !self->_nativeProcessingActive ||
        !self->_readyDelivered || self->_starting || self->_stopping ||
        self->_resumeInProgress || self->_switching || self->_manualPaused ||
        self->_hostPaused || !self->_previewSink ||
        ![NosmaiCore shared].camera.isCapturing) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Start processing and wait for the preview before capturing",
                 nil);
      return;
    }

    self->_captureInProgress = YES;
    self->_captureNativeCallbackReceived = NO;
    self->_captureCompletion = [completion copy];
    NSUInteger generation = ++self->_captureGeneration;
    BOOL nativeMirrored = [self->_desiredPosition isEqualToString:@"front"];
    BOOL mirrorOutput = self->_desiredMirror != nativeMirrored;

    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW,
                      (int64_t)(NosmaiIOSCaptureTimeoutSeconds *
                                NSEC_PER_SEC)),
        dispatch_get_main_queue(), ^{
          if (generation != self->_captureGeneration ||
              !self->_captureInProgress) {
            return;
          }
          self->_captureGeneration++;
          self->_captureInProgress = NO;
          self->_captureNativeCallbackReceived = NO;
          NosmaiIOSCompletion pending = self->_captureCompletion;
          self->_captureCompletion = nil;
          NSError *timeoutError = NosmaiIOSError(
              NosmaiIOSErrorCaptureFailed,
              @"Photo capture did not complete in time", nil);
          pending(nil, NosmaiIOSErrorCaptureFailed,
                  timeoutError.localizedDescription, timeoutError);
          [self applyPendingPreviewPresentationIfPossible];
        });

    @try {
      [[NosmaiCore shared] capturePhoto:^(UIImage *image, NSError *error) {
        NosmaiIOSRunOnMain(^{
          if (generation != self->_captureGeneration ||
              !self->_captureInProgress ||
              self->_captureNativeCallbackReceived) {
            return;
          }
          self->_captureNativeCallbackReceived = YES;
          if (!image || error) {
            NosmaiIOSCompletion pending = self->_captureCompletion;
            self->_captureCompletion = nil;
            self->_captureInProgress = NO;
            self->_captureNativeCallbackReceived = NO;
            pending(nil, NosmaiIOSErrorCaptureFailed,
                    error.localizedDescription ?: @"Unable to capture a photo",
                    error);
            [self applyPendingPreviewPresentationIfPossible];
            return;
          }

          UIImage *outputImage = mirrorOutput
              ? NosmaiIOSImageByMirroringHorizontally(image)
              : image;
          CGFloat scale = outputImage.scale > 0 ? outputImage.scale : 1.0;
          NSUInteger width = outputImage.CGImage
              ? CGImageGetWidth(outputImage.CGImage)
              : (NSUInteger)std::llround(outputImage.size.width * scale);
          NSUInteger height = outputImage.CGImage
              ? CGImageGetHeight(outputImage.CGImage)
              : (NSUInteger)std::llround(outputImage.size.height * scale);

          dispatch_async(self->_mediaQueue, ^{
            NSData *jpeg = UIImageJPEGRepresentation(outputImage, 0.8);
            NSError *writeError = nil;
            NSURL *outputURL = nil;
            if (jpeg.length > 0) {
              NSString *filename = [NSString
                  stringWithFormat:@"nosmai_photo_%@.jpg", NSUUID.UUID.UUIDString];
              outputURL = [NSURL fileURLWithPath:
                  [NSTemporaryDirectory() stringByAppendingPathComponent:filename]];
              if (![jpeg writeToURL:outputURL
                            options:NSDataWritingAtomic
                              error:&writeError]) {
                outputURL = nil;
              }
            }

            NSNumber *fileSize = nil;
            if (outputURL) {
              NSDictionary *attributes =
                  [NSFileManager.defaultManager
                      attributesOfItemAtPath:outputURL.path
                                       error:&writeError];
              fileSize = attributes[NSFileSize];
              if (fileSize.unsignedLongLongValue == 0) outputURL = nil;
            }

            dispatch_async(dispatch_get_main_queue(), ^{
              if (generation != self->_captureGeneration ||
                  !self->_captureInProgress) {
                if (outputURL) {
                  [NSFileManager.defaultManager removeItemAtURL:outputURL
                                                          error:nil];
                }
                return;
              }
              NosmaiIOSCompletion pending = self->_captureCompletion;
              self->_captureCompletion = nil;
              self->_captureInProgress = NO;
              self->_captureNativeCallbackReceived = NO;
              if (!outputURL) {
                pending(nil, NosmaiIOSErrorCaptureFailed,
                        writeError.localizedDescription ?:
                            @"Unable to encode the captured photo",
                        writeError);
                [self applyPendingPreviewPresentationIfPossible];
                return;
              }
              pending(@{
                @"uri" : outputURL.absoluteString,
                @"width" : @(width),
                @"height" : @(height),
                @"fileSizeBytes" : fileSize ?: @0,
                @"mimeType" : @"image/jpeg",
              }, nil, nil, nil);
              [self applyPendingPreviewPresentationIfPossible];
            });
          });
        });
      }];
    } @catch (NSException *exception) {
      NosmaiIOSCompletion pending = self->_captureCompletion;
      self->_captureCompletion = nil;
      self->_captureInProgress = NO;
      self->_captureNativeCallbackReceived = NO;
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorCaptureFailed,
          @"Unable to capture a photo");
      pending(nil, NosmaiIOSErrorCaptureFailed, error.localizedDescription,
              error);
      [self applyPendingPreviewPresentationIfPossible];
    }
  });
}

- (void)saveImageToGalleryAtURI:(NSString *)imageURI
                           name:(NSString *_Nullable)name
                     completion:(NosmaiIOSCompletion)completion {
  [self saveMediaAtURI:imageURI
                  name:name
             mediaType:@"photo"
            completion:completion];
}

- (void)saveVideoToGalleryAtURI:(NSString *)videoURI
                           name:(NSString *_Nullable)name
                     completion:(NosmaiIOSCompletion)completion {
  [self saveMediaAtURI:videoURI
                  name:name
             mediaType:@"video"
            completion:completion];
}

#pragma mark - Protected effects

- (void)applyEffectAtPath:(NSString *)packagePath
                completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    if (!self->_processing || !self->_nativeProcessingActive ||
        self->_starting || self->_stopping || self->_resumeInProgress ||
        self->_manualPaused || self->_hostPaused) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Start processing and keep the app active before applying an effect",
                 nil);
      return;
    }
    NSString *path = [self normalizedPackagePath:packagePath
                                 requireReadable:YES];
    if (!path) {
      completion(nil, NosmaiIOSErrorInvalidPackagePath,
                 @"packagePath must reference a readable local .nosmai file",
                 nil);
      return;
    }

    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      // An apply can wait behind another serialized mutation. Revalidate the
      // physical pipeline when its turn actually begins so lifecycle/cleanup
      // transitions cannot send a deferred native apply into a stopped SDK.
      if (self->_cleanupInProgress) {
        finish(nil, NosmaiIOSErrorOperationCancelled,
               @"Effect apply was cancelled by session cleanup", nil);
        return;
      }
      if (!self->_processing || !self->_nativeProcessingActive ||
          self->_starting || self->_stopping || self->_resumeInProgress ||
          self->_manualPaused || self->_hostPaused) {
        finish(nil, NosmaiIOSErrorInvalidState,
               @"Start processing and keep the app active before applying an effect",
               nil);
        return;
      }

      self->_pendingEffectPromiseCount++;
      __block BOOL applyFinished = NO;
      NosmaiIOSCompletion finishApply =
          ^(id value, NSString *code, NSString *message, NSError *error) {
        if (applyFinished) return;
        applyFinished = YES;
        if (self->_pendingEffectPromiseCount > 0) {
          self->_pendingEffectPromiseCount--;
        }
        finish(value, code, message, error);
      };

      @try {
        [[NosmaiCore shared].effects
            applyEffect:path
             completion:^(BOOL success, NSError *error) {
          NosmaiIOSRunOnMain(^{
            if (!success || error) {
              finishApply(nil, NosmaiIOSErrorEffectApply,
                          error.localizedDescription ?:
                              @"The effect package could not be applied",
                          error);
              return;
            }

            [self waitForPipelineUntil:
                      NSProcessInfo.processInfo.systemUptime +
                      NosmaiIOSEffectStateTimeoutSeconds
                               condition:^BOOL(NosmaiPipelineState *state,
                                               __unused NosmaiSDK *sdk) {
              return [self pathsEqual:path other:state.activeFilterPath] ||
                     [self pathsEqual:path other:state.activeEffectPath] ||
                     [self pathsEqual:path
                                other:state.activeBackgroundPackagePath];
            }
                              completion:^(id value, NSString *code,
                                           NSString *message,
                                           NSError *waitError) {
              if (code) {
                finishApply(nil, NosmaiIOSErrorEffectApply, message, waitError);
              } else {
                // Applying an authored AR/beauty package replaces the native
                // effect slot and therefore invalidates bridge-only eye state.
                // Filter and background package slots intentionally preserve it.
                @try {
                  NosmaiPipelineState *state =
                      [[NosmaiCore shared].effects currentPipelineState];
                  if ([self pathsEqual:path other:state.activeEffectPath]) {
                    self->_eyeColorConfigured = NO;
                    self->_eyeColorIntensity = 0.0;
                    [self emitCurrentPipelineState];
                  }
                } @catch (__unused NSException *exception) {
                  // State was already confirmed by waitForPipelineUntil. A
                  // supplemental tracker read must not turn success into error.
                }
                finishApply(@YES, nil, nil, nil);
              }
            }];
          });
        }];
      } @catch (NSException *exception) {
        NSError *error = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorEffectApply,
            @"Unable to apply the effect package");
        finishApply(nil, NosmaiIOSErrorEffectApply,
                    error.localizedDescription, error);
      }
    }
                  completion:completion];
  });
}

- (void)getActiveEffectsWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      NSDictionary *state = [self currentPipelineStateMap];
      if (!state) {
        completion(nil, NosmaiIOSErrorEffectState,
                   @"The native pipeline state is unavailable", nil);
      } else {
        completion(state, nil, nil, nil);
      }
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to read the native pipeline state");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)getActiveFilterInfoWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      NosmaiPipelineState *state =
          [[NosmaiCore shared].effects currentPipelineState];
      NSDictionary *map =
          [self filterInfoMap:[[NosmaiSDK sharedInstance] activeFilterInfo]
                 fallbackPath:state.activeFilterPath
                 fallbackType:@"filter"];
      completion(map, nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to read active filter metadata");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)getActiveEffectInfoWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      NosmaiPipelineState *state =
          [[NosmaiCore shared].effects currentPipelineState];
      NSDictionary *stateMap = [state dictionaryRepresentation];
      NSString *type = [stateMap[@"activeEffectType"] isKindOfClass:NSString.class]
          ? stateMap[@"activeEffectType"]
          : @"effect";
      NSDictionary *map =
          [self filterInfoMap:[[NosmaiSDK sharedInstance] activeEffectInfo]
                 fallbackPath:state.activeEffectPath
                 fallbackType:type];
      completion(map, nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to read active effect metadata");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)getEffectParametersWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      dispatch_async(self->_cameraQueue, ^{
        __block NSArray<NSDictionary *> *rawParameters = nil;
        __block NSError *failure = nil;
        @try {
          rawParameters = [[[NosmaiSDK sharedInstance] getEffectParameters] copy];
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorEffectParameter,
              @"Unable to read effect parameters");
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (failure) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   failure.localizedDescription, failure);
            return;
          }
          NSMutableArray<NSDictionary *> *items = [NSMutableArray array];
          for (id candidate in rawParameters ?: @[]) {
            if (items.count >= NosmaiIOSMaxEffectParameterCount) break;
            NSDictionary *parameter = NosmaiIOSParameterMap(candidate);
            if (parameter) [items addObject:parameter];
          }
          finish(@{ @"items" : [items copy] }, nil, nil, nil);
        });
      });
    }
                completion:completion];
  });
}

- (void)getEffectParameterValue:(NSString *)parameterName
                      completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *name = NosmaiIOSSafeParameterName(parameterName);
    if (!name) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"parameterName must not be empty", nil);
      return;
    }
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      dispatch_async(self->_cameraQueue, ^{
        __block float value = NAN;
        __block NSArray *parameters = nil;
        __block NSError *failure = nil;
        @try {
          NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
          parameters = [[sdk getEffectParameters] copy];
          if (NosmaiIOSHasNumericParameter(parameters, name)) {
            value = [sdk getEffectParameterValue:name];
          }
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorEffectParameter,
              @"Unable to read the effect parameter");
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (failure) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   failure.localizedDescription, failure);
          } else if (!std::isfinite(value)) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   [NSString stringWithFormat:
                       @"Parameter '%@' was not found or is not numeric", name],
                   nil);
          } else {
            finish(@(value), nil, nil, nil);
          }
        });
      });
    }
                completion:completion];
  });
}

- (void)setEffectParameter:(NSString *)parameterName
                       value:(double)value
                  completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *name = NosmaiIOSSafeParameterName(parameterName);
    if (!name || !std::isfinite(value)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"parameterName must be non-empty and value must be finite",
                 nil);
      return;
    }
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      dispatch_async(self->_cameraQueue, ^{
        __block BOOL success = NO;
        __block NSError *failure = nil;
        @try {
          success = [[NosmaiSDK sharedInstance]
              setEffectParameter:name
                           value:(float)value];
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorEffectParameter,
              @"Unable to set the effect parameter");
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (failure) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   failure.localizedDescription, failure);
          } else if (!success) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   [NSString stringWithFormat:
                       @"Parameter '%@' does not accept a numeric value", name],
                   nil);
          } else {
            finish(@YES, nil, nil, nil);
          }
        });
      });
    }
                completion:completion];
  });
}

- (void)setEffectParameterString:(NSString *)parameterName
                            value:(NSString *)value
                       completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *name = NosmaiIOSSafeParameterName(parameterName);
    if (!name || !NosmaiIOSSafeParameterString(value)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"parameterName must be non-empty and value must be a string",
                 nil);
      return;
    }
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      dispatch_async(self->_cameraQueue, ^{
        __block BOOL success = NO;
        __block NSError *failure = nil;
        @try {
          success = [[NosmaiSDK sharedInstance]
              setEffectParameter:name
                     stringValue:value];
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorEffectParameter,
              @"Unable to set the string effect parameter");
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (failure) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   failure.localizedDescription, failure);
          } else if (!success) {
            finish(nil, NosmaiIOSErrorEffectParameter,
                   [NSString stringWithFormat:
                       @"Parameter '%@' does not accept a string value", name],
                   nil);
          } else {
            finish(@YES, nil, nil, nil);
          }
        });
      });
    }
                completion:completion];
  });
}

- (void)isGameReadyWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      completion(@([[NosmaiCore shared].effects isGameReady]), nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to read the active game state");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)sendGameTapAtNormalizedX:(double)x
                               y:(double)y
                      completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    if (!std::isfinite(x) || !std::isfinite(y) || x < 0.0 || x > 1.0 ||
        y < 0.0 || y > 1.0) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Game tap coordinates must be normalized from 0 through 1",
                 nil);
      return;
    }
    @try {
      completion(@([[NosmaiCore shared].effects
                     sendGameTapAtNormalizedX:(float)x
                                           y:(float)y]),
                 nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to send input to the active game");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)sendGameInput:(NSString *)name
           normalizedX:(double)x
                     y:(double)y
                 value:(double)value
            completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *inputName = NosmaiIOSNonEmptyString(name);
    if (!inputName || !std::isfinite(x) || !std::isfinite(y) ||
        !std::isfinite(value) || x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Game input requires normalized coordinates, a non-empty name, and a finite value",
                 nil);
      return;
    }
    @try {
      completion(@([[NosmaiCore shared].effects
                     sendGameInput:inputName
                       normalizedX:(float)x
                                 y:(float)y
                             value:(float)value]),
                 nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to send input to the active game");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)pauseGameWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      [[NosmaiCore shared].effects pauseGame];
      completion(nil, nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to pause the active game");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)resumeGameWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      [[NosmaiCore shared].effects resumeGame];
      completion(nil, nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to resume the active game");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)restartGameWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      [[NosmaiCore shared].effects restartGame];
      completion(nil, nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"Unable to restart the active game");
      completion(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
                 error);
    }
  });
}

- (void)getLocalFiltersOfType:(NSString *_Nullable)packageType
                    completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;

    NosmaiFilterType nativeType = NosmaiFilterTypeUnknown;
    if (packageType) {
      if ([packageType isEqualToString:@"filter"]) {
        nativeType = NosmaiFilterTypeFilter;
      } else if ([packageType isEqualToString:@"effect"]) {
        nativeType = NosmaiFilterTypeEffect;
      } else if ([packageType isEqualToString:@"background"]) {
        nativeType = NosmaiFilterTypeBackground;
      } else if ([packageType isEqualToString:@"beauty_effect"]) {
        nativeType = NosmaiFilterTypeBeautyEffect;
      } else if ([packageType isEqualToString:@"game"]) {
        nativeType = NosmaiFilterTypeGame;
      } else {
        completion(nil, NosmaiIOSErrorInvalidArgument,
                   @"packageType must be filter, effect, background, beauty_effect, or game",
                   nil);
        return;
      }
    }

    @try {
      NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
      NSArray<NosmaiFilterInfo *> *filters = packageType
          ? [sdk getFiltersOfType:nativeType]
          : [sdk getFilters];
      NSMutableArray<NSDictionary *> *items =
          [NSMutableArray arrayWithCapacity:filters.count];
      for (NosmaiFilterInfo *info in filters) {
        NSDictionary *map = [self filterInfoMap:info
                                   fallbackPath:info.path
                                   fallbackType:info.typeKey ?: packageType ?: @"filter"];
        if (map) [items addObject:map];
      }
      completion(@{ @"items" : [items copy] }, nil, nil, nil);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorLocalCatalog,
          @"Unable to load the local Nosmai package catalog");
      completion(nil, NosmaiIOSErrorLocalCatalog, error.localizedDescription,
                 error);
    }
  });
}

- (void)getDebugFiltersOfType:(NSString *_Nullable)packageType
                    completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;

    NosmaiFilterType nativeType = NosmaiFilterTypeUnknown;
    if (packageType) {
      if ([packageType isEqualToString:@"filter"]) {
        nativeType = NosmaiFilterTypeFilter;
      } else if ([packageType isEqualToString:@"effect"]) {
        nativeType = NosmaiFilterTypeEffect;
      } else if ([packageType isEqualToString:@"background"]) {
        nativeType = NosmaiFilterTypeBackground;
      } else if ([packageType isEqualToString:@"beauty_effect"]) {
        nativeType = NosmaiFilterTypeBeautyEffect;
      } else if ([packageType isEqualToString:@"game"]) {
        nativeType = NosmaiFilterTypeGame;
      } else {
        completion(nil, NosmaiIOSErrorInvalidArgument,
                   @"packageType must be filter, effect, background, beauty_effect, or game",
                   nil);
        return;
      }
    }

    void (^debugCompletion)(NSArray<NosmaiFilterInfo *> *, NSError *) =
        ^(NSArray<NosmaiFilterInfo *> *filters, NSError *error) {
      NosmaiIOSRunOnMain(^{
        if (error) {
          completion(nil, NosmaiIOSErrorDebugFilters,
                     error.localizedDescription ?: @"Unable to load bundled debug filters",
                     error);
          return;
        }

        NSMutableArray<NSDictionary *> *items =
            [NSMutableArray arrayWithCapacity:filters.count];
        for (NosmaiFilterInfo *info in filters) {
          NSDictionary *map = [self filterInfoMap:info
                                     fallbackPath:info.path
                                     fallbackType:info.typeKey ?: packageType ?: @"effect"];
          if (map) [items addObject:map];
        }
        completion(@{ @"items" : [items copy] }, nil, nil, nil);
      });
    };

    @try {
      NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
      if (packageType) {
        [sdk getDebugFiltersOfType:nativeType completion:debugCompletion];
      } else {
        [sdk getDebugFiltersWithCompletion:debugCompletion];
      }
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorDebugFilters,
          @"Unable to load bundled debug filters");
      completion(nil, NosmaiIOSErrorDebugFilters, error.localizedDescription,
                 error);
    }
  });
}

- (void)isCloudFilterEnabledWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
      if (!effects) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorCloudDisabled,
            @"Cloud filters are unavailable for this SDK session", nil);
        completion(nil, NosmaiIOSErrorCloudDisabled,
                   error.localizedDescription, error);
        return;
      }
      completion(@([effects isCloudFilterEnabled]), nil, nil, nil);
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorCloudDisabled,
          @"Unable to read cloud-filter availability", nil);
      completion(nil, NosmaiIOSErrorCloudDisabled,
                 error.localizedDescription, error);
    }
  });
}

- (void)fetchMergedCloudCatalogWithEffects:(NosmaiEffectsEngine *)effects
                                      page:(NSInteger)page
                                     limit:(NSInteger)limit
                             fetchAllPages:(BOOL)fetchAllPages
                                completion:
                                    (NosmaiIOSCloudCatalogCompletion)completion {
  NSArray<NSDictionary<NSString *, NSString *> *> *buckets = @[
    @{ @"packageType" : @"effect", @"requestType" : @"effects" },
    @{ @"packageType" : @"filter", @"requestType" : @"filter" },
    @{ @"packageType" : @"background", @"requestType" : @"bg" },
    @{ @"packageType" : @"beauty_effect", @"requestType" : @"beauty_effects" },
    @{ @"packageType" : @"game", @"requestType" : @"games" },
  ];
  NSMutableArray<NSDictionary *> *merged = [NSMutableArray array];
  __block NSUInteger bucketIndex = 0;
  __block NSUInteger successfulBuckets = 0;
  __block NSInteger currentPage = MAX(1, page);
  __block NSInteger totalPages = 1;
  __block NSInteger totalItems = 0;
  __block BOOL hasNextPage = NO;
  __block BOOL hasPreviousPage = NO;
  __block NSError *lastError = nil;
  __block void (^fetchNext)(void) = nil;

  fetchNext = ^{
    if (bucketIndex >= buckets.count) {
      fetchNext = nil;
      if (successfulBuckets == 0) {
        NSError *error = lastError ?: NosmaiIOSError(
            NosmaiIOSErrorCloudCatalog,
            @"Unable to load the cloud-filter catalog", nil);
        completion(nil, nil, error);
        return;
      }

      totalItems = MAX(totalItems, (NSInteger)merged.count);
      totalPages = MAX(totalPages, currentPage);
      if (currentPage >= totalPages) hasNextPage = NO;
      if (currentPage > 1) hasPreviousPage = YES;
      NosmaiCloudFilterPaginationInfo *pagination =
          [[NosmaiCloudFilterPaginationInfo alloc]
              initWithCurrentPage:currentPage
                       totalPages:totalPages
                     totalFilters:totalItems
                            limit:MAX(1, limit)
                      hasNextPage:hasNextPage
                  hasPreviousPage:hasPreviousPage];
      completion([merged copy], pagination, nil);
      return;
    }

    NSDictionary<NSString *, NSString *> *bucket = buckets[bucketIndex];
    NSString *packageType = bucket[@"packageType"];
    NosmaiCloudFilterRequestOptions *options =
        [NosmaiCloudFilterRequestOptions defaultOptions];
    options.version = NosmaiCloudFilterVersion2;
    options.page = MAX(1, page);
    options.limit = MAX(1, limit);
    options.filterType = bucket[@"requestType"];
    options.fetchAllPages = fetchAllPages;
    options.cleanupRemovedFilters = NO;

    @try {
      [effects getCloudFiltersWithOptions:options
                               completion:^(NSArray<NSDictionary *> *filters,
                                            NosmaiCloudFilterPaginationInfo *pagination,
                                            NSError *nativeError) {
        NosmaiIOSRunOnMain(^{
          if (!nativeError && filters && pagination) {
            successfulBuckets += 1;
            for (id candidate in filters) {
              if (![candidate isKindOfClass:NSDictionary.class]) continue;
              NSMutableDictionary *annotated =
                  [(NSDictionary *)candidate mutableCopy];
              annotated[@"filterType"] = packageType;
              [merged addObject:[annotated copy]];
            }

            currentPage = MAX(currentPage, MAX(1, pagination.currentPage));
            totalPages = MAX(totalPages, MAX(1, pagination.totalPages));
            NSInteger bucketTotal = MAX(0, pagination.totalFilters);
            if (totalItems > NSIntegerMax - bucketTotal) {
              totalItems = NSIntegerMax;
            } else {
              totalItems += bucketTotal;
            }
            hasNextPage = hasNextPage || pagination.hasNextPage;
            hasPreviousPage =
                hasPreviousPage || pagination.hasPreviousPage;
          } else if (nativeError) {
            lastError = nativeError;
          }

          bucketIndex += 1;
          if (fetchNext) fetchNext();
        });
      }];
    } @catch (NSException *exception) {
      lastError = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorCloudCatalog,
          @"Unable to load a cloud-filter category");
      bucketIndex += 1;
      NosmaiIOSRunOnMain(^{
        if (fetchNext) fetchNext();
      });
    }
  };

  fetchNext();
}

- (void)getCloudFiltersOfType:(NSString *_Nullable)packageType
                       version:(NSString *)version
                          page:(double)page
                         limit:(double)limit
                 fetchAllPages:(BOOL)fetchAllPages
                    completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;

    NSString *normalizedPackageType = nil;
    if (packageType != nil) {
      NSString *inputType = NosmaiIOSNonEmptyString(packageType);
      normalizedPackageType = NosmaiIOSCloudPackageType(inputType);
      if (!inputType || ![inputType isEqualToString:normalizedPackageType]) {
        completion(nil, NosmaiIOSErrorInvalidArgument,
                   @"packageType must be filter, effect, background, beauty_effect, or game",
                   nil);
        return;
      }
    }

    NSString *normalizedVersion = NosmaiIOSNonEmptyString(version);
    if (![normalizedVersion isEqualToString:@"2.0.0"]) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Only cloud catalog version 2.0.0 is supported", nil);
      return;
    }
    if (!std::isfinite(page) || page < 0.0 || floor(page) != page ||
        page > (double)NSIntegerMax) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"page must be zero or a positive integer", nil);
      return;
    }
    if (!std::isfinite(limit) || limit < 1.0 || limit > 100.0 ||
        floor(limit) != limit) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"limit must be an integer from 1 through 100", nil);
      return;
    }
    if (![self ensureCloudEnabledForCompletion:completion]) return;

    NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
    NosmaiCloudFilterRequestOptions *options =
        [NosmaiCloudFilterRequestOptions defaultOptions];
    options.version = NosmaiCloudFilterVersion2;
    options.page = page == 0.0 ? 1 : (NSInteger)page;
    options.limit = (NSInteger)limit;
    options.filterType = NosmaiIOSCloudRequestType(normalizedPackageType);
    options.fetchAllPages = fetchAllPages;
    // Never let a catalog read delete cache entries. Removal belongs only to
    // the explicit removeCloudFilter API exposed by this bridge.
    options.cleanupRemovedFilters = NO;

    __block BOOL settled = NO;
    void (^finish)(id _Nullable, NSString *_Nullable, NSString *_Nullable,
                   NSError *_Nullable) =
        ^(id value, NSString *code, NSString *message, NSError *error) {
      NosmaiIOSRunOnMain(^{
        if (settled) return;
        settled = YES;
        completion(value, code, message, error);
      });
    };

    void (^processCatalog)(NSArray<NSDictionary *> *,
                           NosmaiCloudFilterPaginationInfo *) =
        ^(NSArray<NSDictionary *> *filters,
          NosmaiCloudFilterPaginationInfo *pagination) {
      @try {
        NSArray<NSDictionary *> *catalog = [filters copy];
        NSInteger currentPage = MAX(1, pagination.currentPage);
        NSInteger totalPages = MAX(1, pagination.totalPages);
        NSInteger totalItems = MAX(0, pagination.totalFilters);
        NSInteger itemsPerPage = MAX(1, pagination.limit);
        BOOL hasNextPage = pagination.hasNextPage;
        BOOL hasPreviousPage = pagination.hasPreviousPage;

        dispatch_async(self->_cloudQueue, ^{
          @try {
            NSMutableArray<NSDictionary *> *items =
                [NSMutableArray arrayWithCapacity:catalog.count];
            NSMutableSet<NSString *> *seenIdentifiers = [NSMutableSet set];
            for (id candidate in catalog) {
              if (![candidate isKindOfClass:NSDictionary.class]) continue;
              NSDictionary *map =
                  [self cloudFilterMapFromDictionary:(NSDictionary *)candidate
                                requestedPackageType:normalizedPackageType
                                             effects:effects];
              NSString *filterId = map[@"filterId"];
              if (!map || [seenIdentifiers containsObject:filterId]) continue;
              [seenIdentifiers addObject:filterId];
              [items addObject:map];
            }

            finish(@{
              @"items" : [items copy],
              @"pagination" : @{
                @"currentPage" : @(currentPage),
                @"totalPages" : @(totalPages),
                @"totalItems" : @(MAX(totalItems, (NSInteger)items.count)),
                @"itemsPerPage" : @(itemsPerPage),
                @"hasNextPage" : @(hasNextPage),
                @"hasPreviousPage" : @(hasPreviousPage),
              },
            }, nil, nil, nil);
          } @catch (__unused NSException *exception) {
            NSError *error = NosmaiIOSError(
                NosmaiIOSErrorCloudCatalog,
                @"Unable to process the cloud-filter catalog", nil);
            finish(nil, NosmaiIOSErrorCloudCatalog,
                   error.localizedDescription, error);
          }
        });
      } @catch (__unused NSException *exception) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorCloudCatalog,
            @"Unable to process the cloud-filter catalog", nil);
        finish(nil, NosmaiIOSErrorCloudCatalog,
               error.localizedDescription, error);
      }
    };

    @try {
      [effects getCloudFiltersWithOptions:options
                               completion:^(NSArray<NSDictionary *> *filters,
                                            NosmaiCloudFilterPaginationInfo *pagination,
                                            NSError *nativeError) {
        if (!nativeError && filters && pagination) {
          processCatalog(filters, pagination);
          return;
        }

        if (normalizedPackageType == nil) {
          [self fetchMergedCloudCatalogWithEffects:effects
                                              page:options.page
                                             limit:options.limit
                                     fetchAllPages:options.fetchAllPages
                                        completion:^(NSArray<NSDictionary *> *fallbackFilters,
                                                     NosmaiCloudFilterPaginationInfo *fallbackPagination,
                                                     NSError *fallbackError) {
            if (!fallbackError && fallbackFilters && fallbackPagination) {
              processCatalog(fallbackFilters, fallbackPagination);
              return;
            }
            NSError *error = fallbackError ?: nativeError ?: NosmaiIOSError(
                NosmaiIOSErrorCloudCatalog,
                @"Unable to load the cloud-filter catalog", nil);
            finish(nil, NosmaiIOSErrorCloudCatalog,
                   error.localizedDescription, error);
          }];
          return;
        }

        NSError *error = nativeError ?: NosmaiIOSError(
            NosmaiIOSErrorCloudCatalog,
            @"Unable to load the cloud-filter catalog", nil);
        finish(nil, NosmaiIOSErrorCloudCatalog,
               error.localizedDescription, error);
      }];
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorCloudCatalog,
          @"Unable to load the cloud-filter catalog", nil);
      finish(nil, NosmaiIOSErrorCloudCatalog, error.localizedDescription,
             error);
    }
  });
}

- (void)downloadCloudFilterWithIdentifier:(NSString *)filterId
                                completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *identifier = NosmaiIOSNormalizedCloudIdentifier(filterId);
    if (!identifier) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"filterId must be a safe non-empty identifier", nil);
      return;
    }
    if (![self ensureCloudEnabledForCompletion:completion]) return;

    NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
    @try {
      BOOL reportedDownloaded = [effects isCloudFilterDownloaded:identifier];
      NSString *existingPath = [self
          normalizedPackagePath:[effects getCloudFilterLocalPath:identifier]
                 requireReadable:YES];
      if (existingPath) {
        completion(@{
          @"filterId" : identifier,
          @"path" : existingPath,
          @"alreadyDownloaded" : @YES,
        }, nil, nil, nil);
        return;
      }
      if (reportedDownloaded) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorCloudDownload,
            @"The downloaded cloud package is unavailable", nil);
        completion(nil, NosmaiIOSErrorCloudDownload,
                   error.localizedDescription, error);
        return;
      }
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorCloudDownload,
          @"Unable to inspect the cloud package", nil);
      completion(nil, NosmaiIOSErrorCloudDownload,
                 error.localizedDescription, error);
      return;
    }

    NSMutableArray *waiters = self->_cloudDownloadCompletions[identifier];
    if (waiters) {
      [waiters addObject:[completion copy]];
      return;
    }

    waiters = [NSMutableArray arrayWithObject:[completion copy]];
    self->_cloudDownloadCompletions[identifier] = waiters;
    NSUInteger generation = ++self->_cloudDownloadGeneration;
    self->_cloudDownloadGenerations[identifier] = @(generation);

    dispatch_after(
        dispatch_time(DISPATCH_TIME_NOW,
                      (int64_t)(NosmaiIOSCloudDownloadTimeoutSeconds *
                                NSEC_PER_SEC)),
        dispatch_get_main_queue(), ^{
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorCloudDownloadTimeout,
          @"The cloud-filter download timed out", nil);
      [self finishCloudDownloadForIdentifier:identifier
                                   generation:generation
                                        value:nil
                                         code:NosmaiIOSErrorCloudDownloadTimeout
                                      message:error.localizedDescription
                                        error:error];
    });

    @try {
      [effects downloadCloudFilter:identifier
                          progress:^(float nativeProgress) {
        NosmaiIOSRunOnMain(^{
          NSNumber *activeGeneration =
              self->_cloudDownloadGenerations[identifier];
          if (activeGeneration.unsignedIntegerValue != generation ||
              !self->_cloudDownloadCompletions[identifier] ||
              !std::isfinite((double)nativeProgress)) {
            return;
          }
          double progress = std::max(0.0, std::min(1.0,
                                                   (double)nativeProgress));
          [self->_moduleSink nosmaiControllerDidUpdateDownloadProgress:@{
            @"filterId" : identifier,
            @"progress" : @(progress),
          }];
        });
      }
                        completion:^(BOOL success, NSString *localPath,
                                     NSError *nativeError) {
        NosmaiIOSRunOnMain(^{
          NSNumber *activeGeneration =
              self->_cloudDownloadGenerations[identifier];
          if (activeGeneration.unsignedIntegerValue != generation ||
              !self->_cloudDownloadCompletions[identifier]) {
            return;
          }

          NSString *resolvedPath = nil;
          if (success && !nativeError) {
            resolvedPath = [self normalizedPackagePath:localPath
                                       requireReadable:YES];
            if (!resolvedPath) {
              @try {
                resolvedPath = [self
                    normalizedPackagePath:
                        [effects getCloudFilterLocalPath:identifier]
                           requireReadable:YES];
              } @catch (__unused NSException *exception) {
                resolvedPath = nil;
              }
            }
          }

          if (!success || nativeError || !resolvedPath) {
            NSError *error = NosmaiIOSError(
                NosmaiIOSErrorCloudDownload,
                @"The cloud-filter package could not be downloaded", nil);
            [self finishCloudDownloadForIdentifier:identifier
                                         generation:generation
                                              value:nil
                                               code:NosmaiIOSErrorCloudDownload
                                            message:error.localizedDescription
                                              error:error];
            return;
          }

          [self finishCloudDownloadForIdentifier:identifier
                                       generation:generation
                                            value:@{
                                              @"filterId" : identifier,
                                              @"path" : resolvedPath,
                                              @"alreadyDownloaded" : @NO,
                                            }
                                             code:nil
                                          message:nil
                                            error:nil];
        });
      }];
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorCloudDownload,
          @"The cloud-filter package could not be downloaded", nil);
      [self finishCloudDownloadForIdentifier:identifier
                                   generation:generation
                                        value:nil
                                         code:NosmaiIOSErrorCloudDownload
                                      message:error.localizedDescription
                                        error:error];
    }
  });
}

- (void)removeCloudFilterWithIdentifier:(NSString *)filterId
                              completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *identifier = NosmaiIOSNormalizedCloudIdentifier(filterId);
    if (!identifier) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"filterId must be a safe non-empty identifier", nil);
      return;
    }
    if (![self ensureCloudEnabledForCompletion:completion]) return;

    NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
    dispatch_async(self->_cloudQueue, ^{
      BOOL existed = NO;
      BOOL removed = NO;
      @try {
        existed = [effects isCloudFilterDownloaded:identifier];
        removed = [effects removeCloudFilter:identifier];
      } @catch (__unused NSException *exception) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorCloudRemove,
            @"Unable to remove the cloud-filter package", nil);
        NosmaiIOSRunOnMain(^{
          completion(nil, NosmaiIOSErrorCloudRemove,
                     error.localizedDescription, error);
        });
        return;
      }

      NosmaiIOSRunOnMain(^{
        if (existed && !removed) {
          NSError *error = NosmaiIOSError(
              NosmaiIOSErrorCloudRemove,
              @"Unable to remove the cloud-filter package", nil);
          completion(nil, NosmaiIOSErrorCloudRemove,
                     error.localizedDescription, error);
        } else {
          completion(@(removed), nil, nil, nil);
        }
      });
    });
  });
}

- (void)removeEffectAtPath:(NSString *)packagePath
                 completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    NSString *path = [self normalizedPackagePath:packagePath
                                 requireReadable:NO];
    if (!path) {
      completion(nil, NosmaiIOSErrorInvalidPackagePath,
                 @"packagePath must not be empty", nil);
      return;
    }

    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      @try {
        NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
        NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
        NosmaiPipelineState *state = [effects currentPipelineState];
        NosmaiIOSPipelineCondition condition = nil;

        if ([self pathsEqual:path other:state.activeFilterPath]) {
          [effects clearFilter];
          condition = ^BOOL(NosmaiPipelineState *snapshot,
                            __unused NosmaiSDK *nativeSdk) {
            return snapshot.activeFilterPath.length == 0;
          };
        } else if ([self pathsEqual:path other:state.activeEffectPath]) {
          [effects clearAREffect];
          condition = ^BOOL(NosmaiPipelineState *snapshot,
                            __unused NosmaiSDK *nativeSdk) {
            return snapshot.activeEffectPath.length == 0;
          };
        } else if ([self pathsEqual:path
                              other:state.activeBackgroundPackagePath]) {
          NosmaiFilterInfo *info = [NosmaiFilterInfo filterInfoWithDictionary:@{
            @"path" : path,
            @"effectPath" : path,
            @"filterType" : @"background",
            @"type" : @"local",
            @"isDownloaded" : @YES,
          }];
          [sdk removeEffectInfo:info];
          condition = ^BOOL(NosmaiPipelineState *snapshot,
                            __unused NosmaiSDK *nativeSdk) {
            return snapshot.activeBackgroundPackagePath.length == 0;
          };
        } else {
          finish(@NO, nil, nil, nil);
          return;
        }

        [self waitForPipelineUntil:
                  NSProcessInfo.processInfo.systemUptime +
                  NosmaiIOSEffectStateTimeoutSeconds
                               condition:condition
                              completion:^(id value, NSString *code,
                                           NSString *message, NSError *error) {
          if (code) {
            finish(nil, NosmaiIOSErrorEffectClear, message, error);
          } else {
            finish(@YES, nil, nil, nil);
          }
        }];
      } @catch (NSException *exception) {
        NSError *error = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorEffectClear,
            @"Unable to remove the active effect package");
        finish(nil, NosmaiIOSErrorEffectClear, error.localizedDescription,
               error);
      }
    }
                  completion:completion];
  });
}

- (void)clearFilterWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      @try {
        [[NosmaiCore shared].effects clearFilter];
        [self waitForPipelineUntil:
                  NSProcessInfo.processInfo.systemUptime +
                  NosmaiIOSEffectStateTimeoutSeconds
                               condition:^BOOL(NosmaiPipelineState *state,
                                               __unused NosmaiSDK *sdk) {
          return state.activeFilterPath.length == 0;
        }
                              completion:finish];
      } @catch (NSException *exception) {
        NSError *error = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorEffectClear,
            @"Unable to clear the active color filter");
        finish(nil, NosmaiIOSErrorEffectClear, error.localizedDescription,
               error);
      }
    }
                  completion:completion];
  });
}

- (void)clearAREffectWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      @try {
        [[NosmaiCore shared].effects clearAREffect];
        [self waitForPipelineUntil:
                  NSProcessInfo.processInfo.systemUptime +
                  NosmaiIOSEffectStateTimeoutSeconds
                               condition:^BOOL(NosmaiPipelineState *state,
                                               __unused NosmaiSDK *sdk) {
          return state.activeEffectPath.length == 0;
        }
                              completion:finish];
      } @catch (NSException *exception) {
        NSError *error = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorEffectClear,
            @"Unable to clear the active AR effect");
        finish(nil, NosmaiIOSErrorEffectClear, error.localizedDescription,
               error);
      }
    }
                  completion:completion];
  });
}

- (void)clearAllWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      @try {
        [[NosmaiCore shared].effects clearAll];
        [self waitForPipelineUntil:
                  NSProcessInfo.processInfo.systemUptime +
                  NosmaiIOSEffectStateTimeoutSeconds
                               condition:^BOOL(NosmaiPipelineState *state,
                                               NosmaiSDK *sdk) {
          return state.activeFilterPath.length == 0 &&
                 state.activeEffectPath.length == 0 &&
                 state.activeBackgroundPackagePath.length == 0 &&
                 !state.backgroundActive && ![sdk hasActiveBuiltInFilters];
        }
                              completion:^(id value, NSString *code,
                                           NSString *message,
                                           NSError *waitError) {
          if (!code) {
            self->_eyeColorConfigured = NO;
            self->_eyeColorIntensity = 0.0;
            [self emitCurrentPipelineState];
          }
          finish(value, code, message, waitError);
        }];
      } @catch (NSException *exception) {
        NSError *error = NosmaiIOSErrorFromException(
            exception, NosmaiIOSErrorEffectClear,
            @"Unable to clear the Nosmai effects pipeline");
        finish(nil, NosmaiIOSErrorEffectClear, error.localizedDescription,
               error);
      }
    }
                  completion:completion];
  });
}

#pragma mark - Visual controls

- (void)isBeautyEffectEnabledWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
      if (!effects) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorVisualControl,
            @"The native visual-effects engine is unavailable", nil);
        completion(nil, NosmaiIOSErrorVisualControl,
                   error.localizedDescription, error);
        return;
      }
      BOOL enabled = [self->_lastLicenseStatus isEqualToString:@"valid"] &&
          [effects isBeautyEffectEnabled];
      completion(@(enabled), nil, nil, nil);
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorVisualControl,
          @"Unable to read beauty-effect availability", nil);
      completion(nil, NosmaiIOSErrorVisualControl,
                 error.localizedDescription, error);
    }
  });
}

- (void)isAdvancedFiltersEnabledWithCompletion:
    (NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureInitializedForCompletion:completion]) return;
    @try {
      NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
      if (!effects) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorVisualControl,
            @"The native visual-effects engine is unavailable", nil);
        completion(nil, NosmaiIOSErrorVisualControl,
                   error.localizedDescription, error);
        return;
      }
      BOOL enabled = [self->_lastLicenseStatus isEqualToString:@"valid"] &&
          [effects isAdvancedFiltersEnabled];
      completion(@(enabled), nil, nil, nil);
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorVisualControl,
          @"Unable to read advanced-filter availability", nil);
      completion(nil, NosmaiIOSErrorVisualControl,
                 error.localizedDescription, error);
    }
  });
}

- (void)setBeautyControl:(NSString *)control
                    value:(double)value
               completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    BOOL supported = [control isEqualToString:@"skinSmoothing"] ||
        [control isEqualToString:@"skinWhitening"] ||
        [control isEqualToString:@"teethWhitening"];
    if (!supported || !NosmaiIOSFiniteInRange(value, 0.0, 1.0)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Beauty control and value are invalid", nil);
      return;
    }
    [self enqueueVisualMutationRequiringBeauty:YES
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   __unused NosmaiSDK *sdk) {
      if ([control isEqualToString:@"skinSmoothing"]) {
        [effects applySkinSmoothing:(float)value];
      } else if ([control isEqualToString:@"skinWhitening"]) {
        [effects applySkinWhitening:(float)value];
      } else {
        // The Nosmai iOS 3.0.4 compatibility line still applies a legacy /10 conversion
        // internally despite documenting this API as [0,1]. Compensate here
        // so the React Native contract remains a real [0,1] intensity.
        [effects applyTeethWhitening:(float)(value * 10.0)];
      }
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)clearBeautyWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   NosmaiSDK *sdk) {
      // This scope is intentionally narrower than clearAll: authored packages,
      // manual backgrounds, and color controls must survive clearBeauty.
      [effects applySkinSmoothing:0.0f];
      [effects applySkinWhitening:0.0f];
      [effects applyTeethWhitening:0.0f];
      [sdk removeAllMakeup];
      [effects clearReshapes];
      [sdk removeEyeColoring];
    }
                                      afterFence:^{
      self->_eyeColorConfigured = NO;
      self->_eyeColorIntensity = 0.0;
    }
                                      completion:completion];
  });
}

- (void)applyMakeupOfType:(NSString *)makeupType
                     style:(NSString *)style
                       red:(double)red
                     green:(double)green
                      blue:(double)blue
                 intensity:(double)intensity
                completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    NSInteger nativeStyle = 0;
    if (!NosmaiIOSMakeupStyle(makeupType, style, &nativeStyle) ||
        !NosmaiIOSFiniteInRange(red, 0.0, 1.0) ||
        !NosmaiIOSFiniteInRange(green, 0.0, 1.0) ||
        !NosmaiIOSFiniteInRange(blue, 0.0, 1.0) ||
        !NosmaiIOSFiniteInRange(intensity, 0.0, 1.0)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Makeup type, style, color, or intensity is invalid", nil);
      return;
    }

    [self enqueueVisualMutationRequiringBeauty:YES
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(__unused NosmaiEffectsEngine *effects,
                                                   NosmaiSDK *sdk) {
      if ([makeupType isEqualToString:@"lipstick"]) {
        [sdk applyLipstickWithStyle:(NosmaiLipstickStyle)nativeStyle
                                  r:(float)red
                                  g:(float)green
                                  b:(float)blue];
        [sdk setLipstickIntensity:(float)intensity];
      } else if ([makeupType isEqualToString:@"eyeshadow"]) {
        [sdk applyEyeshadowWithStyle:(NosmaiEyeshadowStyle)nativeStyle
                                   r:(float)red
                                   g:(float)green
                                   b:(float)blue];
        [sdk setEyeshadowIntensity:(float)intensity];
      } else if ([makeupType isEqualToString:@"blusher"]) {
        [sdk applyBlusherWithStyle:(NosmaiBlusherStyle)nativeStyle
                                 r:(float)red
                                 g:(float)green
                                 b:(float)blue];
        [sdk setBlusherIntensity:(float)intensity];
      } else if ([makeupType isEqualToString:@"eyelash"]) {
        // Eyelashes have no color parameter in the iOS SDK by design.
        [sdk applyEyelashWithStyle:(NosmaiEyelashStyle)nativeStyle];
        [sdk setEyelashIntensity:(float)intensity];
      } else {
        [sdk applyEyebrowWithStyle:(NosmaiEyebrowStyle)nativeStyle
                                 r:(float)red
                                 g:(float)green
                                 b:(float)blue];
        [sdk setEyebrowIntensity:(float)intensity];
      }
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)setMakeupIntensityForType:(NSString *)makeupType
                         intensity:(double)intensity
                        completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (!NosmaiIOSIsMakeupType(makeupType) ||
        !NosmaiIOSFiniteInRange(intensity, 0.0, 1.0)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Makeup type or intensity is invalid", nil);
      return;
    }
    if (![self ensureVisualMutationReadyForCompletion:completion] ||
        ![self ensureVisualFeaturesRequiringBeauty:YES
                                          advanced:NO
                                        completion:completion]) {
      return;
    }
    @try {
      if (!NosmaiIOSIsMakeupLayerActive([NosmaiSDK sharedInstance],
                                        makeupType)) {
        completion(nil, NosmaiIOSErrorInvalidState,
                   @"Apply this makeup layer before changing its intensity",
                   nil);
        return;
      }
    } @catch (__unused NSException *exception) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorVisualControl,
          @"Unable to read the makeup state", nil);
      completion(nil, NosmaiIOSErrorVisualControl,
                 error.localizedDescription, error);
      return;
    }

    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      if (![self ensureVisualMutationReadyForCompletion:finish] ||
          ![self ensureVisualFeaturesRequiringBeauty:YES
                                            advanced:NO
                                          completion:finish]) {
        return;
      }
      NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
      @try {
        if (!NosmaiIOSIsMakeupLayerActive(sdk, makeupType)) {
          finish(nil, NosmaiIOSErrorInvalidState,
                 @"Apply this makeup layer before changing its intensity",
                 nil);
          return;
        }
      } @catch (__unused NSException *exception) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorVisualControl,
            @"Unable to read the makeup state", nil);
        finish(nil, NosmaiIOSErrorVisualControl,
               error.localizedDescription, error);
        return;
      }

      [self performVisualMutationAtFIFOTurnRequiringBeauty:YES
                                                  advanced:NO
                                                  keepAlive:nil
                                                   mutation:^(__unused NosmaiEffectsEngine *effects,
                                                              NosmaiSDK *nativeSdk) {
        if ([makeupType isEqualToString:@"lipstick"]) {
          [nativeSdk setLipstickIntensity:(float)intensity];
        } else if ([makeupType isEqualToString:@"eyeshadow"]) {
          [nativeSdk setEyeshadowIntensity:(float)intensity];
        } else if ([makeupType isEqualToString:@"blusher"]) {
          [nativeSdk setBlusherIntensity:(float)intensity];
        } else if ([makeupType isEqualToString:@"eyelash"]) {
          [nativeSdk setEyelashIntensity:(float)intensity];
        } else {
          [nativeSdk setEyebrowIntensity:(float)intensity];
        }
      }
                                                afterFence:nil
                                                    finish:finish];
    }
                    completion:completion];
  });
}

- (void)removeMakeupOfType:(NSString *)makeupType
                  completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (!NosmaiIOSIsMakeupType(makeupType)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"makeupType is not supported", nil);
      return;
    }
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(__unused NosmaiEffectsEngine *effects,
                                                   NosmaiSDK *sdk) {
      if ([makeupType isEqualToString:@"lipstick"]) {
        [sdk removeLipstick];
      } else if ([makeupType isEqualToString:@"eyeshadow"]) {
        [sdk removeEyeshadow];
      } else if ([makeupType isEqualToString:@"blusher"]) {
        [sdk removeBlusher];
      } else if ([makeupType isEqualToString:@"eyelash"]) {
        [sdk removeEyelash];
      } else {
        [sdk removeEyebrow];
      }
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)isMakeupActiveOfType:(NSString *)makeupType
                   completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (!NosmaiIOSIsMakeupType(makeupType)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"makeupType is not supported", nil);
      return;
    }
    if (![self ensureVisualMutationReadyForCompletion:completion]) return;
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      if (![self ensureVisualMutationReadyForCompletion:finish]) return;
      NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
      dispatch_async(self->_cameraQueue, ^{
        __block NSError *fenceError = nil;
        @try {
          [effects performEffectQueueSync:^{}];
        } @catch (__unused NSException *exception) {
          fenceError = NosmaiIOSError(
              NosmaiIOSErrorVisualControl,
              @"Unable to confirm the native makeup state", nil);
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (fenceError) {
            finish(nil, NosmaiIOSErrorVisualControl,
                   fenceError.localizedDescription, fenceError);
            return;
          }
          if (![self ensureVisualMutationReadyForCompletion:finish]) return;
          @try {
            BOOL active = NosmaiIOSIsMakeupLayerActive(
                [NosmaiSDK sharedInstance], makeupType);
            finish(@(active), nil, nil, nil);
          } @catch (__unused NSException *exception) {
            NSError *error = NosmaiIOSError(
                NosmaiIOSErrorVisualControl,
                @"Unable to read the makeup state", nil);
            finish(nil, NosmaiIOSErrorVisualControl,
                   error.localizedDescription, error);
          }
        });
      });
    }
                    completion:completion];
  });
}

- (void)clearMakeupWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(__unused NosmaiEffectsEngine *effects,
                                                   NosmaiSDK *sdk) {
      [sdk removeAllMakeup];
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)setReshapeOfType:(NSString *)reshapeType
                    value:(double)value
               completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    NosmaiReshapeType nativeType = NosmaiReshapeTypeLip;
    double minimum = 0.0;
    double maximum = 0.0;
    if (!NosmaiIOSReshapeSpecification(reshapeType, &nativeType, &minimum,
                                       &maximum) ||
        !NosmaiIOSFiniteInRange(value, minimum, maximum)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Reshape type or signed value is invalid", nil);
      return;
    }
    [self enqueueVisualMutationRequiringBeauty:YES
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   __unused NosmaiSDK *sdk) {
      [effects setReshapeType:nativeType value:(float)value];
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)clearReshapesWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   __unused NosmaiSDK *sdk) {
      [effects clearReshapes];
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)setEyeColorRed:(double)red
                  green:(double)green
                   blue:(double)blue
              intensity:(double)intensity
             completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (!NosmaiIOSFiniteInRange(red, 0.0, 1.0) ||
        !NosmaiIOSFiniteInRange(green, 0.0, 1.0) ||
        !NosmaiIOSFiniteInRange(blue, 0.0, 1.0) ||
        !NosmaiIOSFiniteInRange(intensity, 0.0, 1.0)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Eye color and intensity values must be between zero and one",
                 nil);
      return;
    }
    [self enqueueVisualMutationRequiringBeauty:YES
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(__unused NosmaiEffectsEngine *effects,
                                                   NosmaiSDK *sdk) {
      [sdk setEyeColorR:(float)red g:(float)green b:(float)blue];
      [sdk setEyeColorIntensity:(float)intensity];
    }
                                      afterFence:^{
      self->_eyeColorConfigured = YES;
      self->_eyeColorIntensity = intensity;
    }
                                      completion:completion];
  });
}

- (void)setEyeColorIntensity:(double)intensity
                    completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (!NosmaiIOSFiniteInRange(intensity, 0.0, 1.0)) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Eye-color intensity must be between zero and one", nil);
      return;
    }
    if (![self ensureVisualMutationReadyForCompletion:completion] ||
        ![self ensureVisualFeaturesRequiringBeauty:YES
                                          advanced:NO
                                        completion:completion]) {
      return;
    }
    if (!self->_eyeColorConfigured) {
      completion(nil, NosmaiIOSErrorInvalidState,
                 @"Set an eye color before changing its intensity", nil);
      return;
    }
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      if (![self ensureVisualMutationReadyForCompletion:finish] ||
          ![self ensureVisualFeaturesRequiringBeauty:YES
                                            advanced:NO
                                          completion:finish]) {
        return;
      }
      if (!self->_eyeColorConfigured) {
        finish(nil, NosmaiIOSErrorInvalidState,
               @"Set an eye color before changing its intensity", nil);
        return;
      }
      [self performVisualMutationAtFIFOTurnRequiringBeauty:YES
                                                  advanced:NO
                                                  keepAlive:nil
                                                   mutation:^(__unused NosmaiEffectsEngine *effects,
                                                              NosmaiSDK *sdk) {
        [sdk setEyeColorIntensity:(float)intensity];
      }
                                                afterFence:^{
        self->_eyeColorConfigured = intensity > 0.0;
        self->_eyeColorIntensity = intensity;
      }
                                                    finish:finish];
    }
                    completion:completion];
  });
}

- (void)removeEyeColorWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(__unused NosmaiEffectsEngine *effects,
                                                   NosmaiSDK *sdk) {
      [sdk removeEyeColoring];
    }
                                      afterFence:^{
      self->_eyeColorConfigured = NO;
      self->_eyeColorIntensity = 0.0;
    }
                                      completion:completion];
  });
}

- (void)isEyeColorActiveWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    if (![self ensureVisualMutationReadyForCompletion:completion]) return;
    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      if (![self ensureVisualMutationReadyForCompletion:finish]) return;
      NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
      dispatch_async(self->_cameraQueue, ^{
        __block NSError *fenceError = nil;
        @try {
          [effects performEffectQueueSync:^{}];
        } @catch (__unused NSException *exception) {
          fenceError = NosmaiIOSError(
              NosmaiIOSErrorVisualControl,
              @"Unable to confirm the native eye-color state", nil);
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (fenceError) {
            finish(nil, NosmaiIOSErrorVisualControl,
                   fenceError.localizedDescription, fenceError);
            return;
          }
          if (![self ensureVisualMutationReadyForCompletion:finish]) return;
          finish(@(self->_eyeColorConfigured), nil, nil, nil);
        });
      });
    }
                    completion:completion];
  });
}

- (void)setColorControl:(NSString *)control
                  value1:(double)value1
                  value2:(double)value2
                  value3:(double)value3
              completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    BOOL finite = std::isfinite(value1) && std::isfinite(value2) &&
        std::isfinite(value3);
    BOOL valid = NO;
    if ([control isEqualToString:@"brightness"]) {
      valid = NosmaiIOSFiniteInRange(value1, -1.0, 1.0) &&
          value2 == 0.0 && value3 == 0.0;
    } else if ([control isEqualToString:@"contrast"]) {
      valid = NosmaiIOSFiniteInRange(value1, 0.0, 2.0) &&
          value2 == 0.0 && value3 == 0.0;
    } else if ([control isEqualToString:@"rgb"]) {
      valid = NosmaiIOSFiniteInRange(value1, 0.0, 2.0) &&
          NosmaiIOSFiniteInRange(value2, 0.0, 2.0) &&
          NosmaiIOSFiniteInRange(value3, 0.0, 2.0);
    } else if ([control isEqualToString:@"sharpening"]) {
      valid = NosmaiIOSFiniteInRange(value1, 0.0, 1.0) &&
          value2 == 0.0 && value3 == 0.0;
    } else if ([control isEqualToString:@"grayscale"]) {
      valid = (value1 == 0.0 || value1 == 1.0) && value2 == 0.0 &&
          value3 == 0.0;
    } else if ([control isEqualToString:@"hue"]) {
      valid = NosmaiIOSFiniteInRange(value1, 0.0, 360.0) &&
          value2 == 0.0 && value3 == 0.0;
    } else if ([control isEqualToString:@"whiteBalance"]) {
      valid = NosmaiIOSFiniteInRange(value1, 2000.0, 8000.0) &&
          NosmaiIOSFiniteInRange(value2, -1.0, 1.0) && value3 == 0.0;
    } else if ([control isEqualToString:@"hsb"]) {
      valid = NosmaiIOSFiniteInRange(value1, -360.0, 360.0) &&
          NosmaiIOSFiniteInRange(value2, 0.0, 2.0) &&
          NosmaiIOSFiniteInRange(value3, 0.0, 2.0);
    }
    if (!finite || !valid) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Color-control name or value is invalid", nil);
      return;
    }

    [self enqueueVisualMutationRequiringBeauty:YES
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   __unused NosmaiSDK *sdk) {
      if ([control isEqualToString:@"brightness"]) {
        [effects applyBrightnessFilter:(float)value1];
      } else if ([control isEqualToString:@"contrast"]) {
        [effects applyContrastFilter:(float)value1];
      } else if ([control isEqualToString:@"rgb"]) {
        [effects applyRGBFilterWithRed:(float)value1
                                 green:(float)value2
                                  blue:(float)value3];
      } else if ([control isEqualToString:@"sharpening"]) {
        [effects applySharpening:(float)value1];
      } else if ([control isEqualToString:@"grayscale"]) {
        if (value1 == 1.0) {
          [effects applyGrayscaleFilter];
        } else {
          [effects removeBuiltInFilterByName:@"GrayscaleFilter"];
        }
      } else if ([control isEqualToString:@"hue"]) {
        [effects applyHue:(float)value1];
      } else if ([control isEqualToString:@"whiteBalance"]) {
        [effects applyWhiteBalanceWithTemperature:(float)value1
                                              tint:(float)value2];
      } else {
        // The pinned iOS SDK's HSB primitive is additive. The React Native
        // setter is absolute, so replace only HSB state before applying it.
        [effects resetHSBFilter];
        [effects adjustHSBWithHue:(float)value1
                       saturation:(float)value2
                       brightness:(float)value3];
      }
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)resetColorAdjustmentsWithCompletion:
    (NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   __unused NosmaiSDK *sdk) {
      // Remove only the controls exposed by this color API. Beauty, makeup,
      // reshape, eye color, packages, and manual background remain untouched.
      [effects removeBuiltInFilterByName:@"BrightnessFilter"];
      [effects removeBuiltInFilterByName:@"ContrastFilter"];
      [effects removeBuiltInFilterByName:@"RGBFilter"];
      [effects applySharpening:0.0f];
      [effects removeBuiltInFilterByName:@"GrayscaleFilter"];
      [effects removeBuiltInFilterByName:@"HueFilter"];
      [effects removeBuiltInFilterByName:@"WhiteBalanceFilter"];
      [effects resetHSBFilter];
      [effects removeBuiltInFilterByName:@"HSBFilter"];
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

- (void)setBackgroundMode:(NSString *)mode
               resourceURI:(NSString *_Nullable)resourceURI
                       red:(double)red
                     green:(double)green
                      blue:(double)blue
                     alpha:(double)alpha
              blurStrength:(double)blurStrength
                completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    BOOL isBlur = [mode isEqualToString:@"blur"];
    BOOL isColor = [mode isEqualToString:@"color"];
    BOOL isImage = [mode isEqualToString:@"image"];
    BOOL isVideo = [mode isEqualToString:@"video"];
    if (!isBlur && !isColor && !isImage && !isVideo) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Background mode must be blur, color, image, or video", nil);
      return;
    }

    BOOL validArguments = NO;
    if (isBlur) {
      validArguments = resourceURI == nil && red == 0.0 && green == 0.0 &&
          blue == 0.0 && alpha == 1.0 &&
          NosmaiIOSFiniteInRange(blurStrength, 0.0, 1.0);
    } else if (isColor) {
      validArguments = resourceURI == nil &&
          NosmaiIOSFiniteInRange(red, 0.0, 1.0) &&
          NosmaiIOSFiniteInRange(green, 0.0, 1.0) &&
          NosmaiIOSFiniteInRange(blue, 0.0, 1.0) &&
          NosmaiIOSFiniteInRange(alpha, 0.0, 1.0) && blurStrength == 0.0;
    } else {
      validArguments = NosmaiIOSFiniteInRange(red, 0.0, 0.0) &&
          NosmaiIOSFiniteInRange(green, 0.0, 0.0) &&
          NosmaiIOSFiniteInRange(blue, 0.0, 0.0) && alpha == 1.0 &&
          blurStrength == 0.0;
    }
    if (!validArguments) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Background arguments do not match the selected mode", nil);
      return;
    }

    if (isBlur || isColor) {
      [self enqueueVisualMutationRequiringBeauty:NO
                                         advanced:YES
                                         keepAlive:nil
                                          mutation:^(NosmaiEffectsEngine *effects,
                                                     __unused NosmaiSDK *sdk) {
        NosmaiBackgroundSegmentationConfig *config =
            [[NosmaiBackgroundSegmentationConfig alloc] init];
        if (isBlur) {
          config.mode = NosmaiBackgroundSegmentationModeBlur;
          // The cross-platform contract and native SDK both use raw [0,1].
          config.blurStrength = (float)blurStrength;
        } else {
          config.mode = NosmaiBackgroundSegmentationModeColor;
          config.replacementColor = [UIColor colorWithRed:(CGFloat)red
                                                    green:(CGFloat)green
                                                     blue:(CGFloat)blue
                                                    alpha:(CGFloat)alpha];
        }
        [effects setBackgroundSegmentation:config];
      }
                                        afterFence:nil
                                        completion:completion];
      return;
    }

    NSURL *resourceURL = [self strictLocalFileURLFromInput:resourceURI];
    if (!resourceURL) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorBackgroundResource,
          @"Background resource must be a strict local file URI", nil);
      completion(nil, NosmaiIOSErrorBackgroundResource,
                 error.localizedDescription, error);
      return;
    }
    if (![self ensureVisualMutationReadyForCompletion:completion] ||
        ![self ensureVisualFeaturesRequiringBeauty:NO
                                          advanced:YES
                                        completion:completion]) {
      return;
    }

    [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
      if (![self ensureVisualMutationReadyForCompletion:finish] ||
          ![self ensureVisualFeaturesRequiringBeauty:NO
                                            advanced:YES
                                          completion:finish]) {
        return;
      }
      dispatch_async(self->_mediaQueue, ^{
        @autoreleasepool {
          UIImage *image = isImage
              ? [self downsampledBackgroundImageAtURL:resourceURL]
              : nil;
          BOOL validVideo = isVideo
              ? [self isSupportedBackgroundVideoAtURL:resourceURL]
              : NO;
          NSURL *resolvedURL = validVideo
              ? [NSURL fileURLWithPath:
                    resourceURL.path.stringByResolvingSymlinksInPath]
              : nil;
          dispatch_async(dispatch_get_main_queue(), ^{
            if ((isImage && !image) || (isVideo && !resolvedURL)) {
              NSError *error = NosmaiIOSError(
                  NosmaiIOSErrorBackgroundResource,
                  isImage
                      ? @"Background image is unreadable, unsupported, or too large"
                      : @"Background video is unreadable or unsupported",
                  nil);
              finish(nil, NosmaiIOSErrorBackgroundResource,
                     error.localizedDescription, error);
              return;
            }
            id retainedResource = isImage ? image : resolvedURL;
            [self performVisualMutationAtFIFOTurnRequiringBeauty:NO
                                                        advanced:YES
                                                        keepAlive:retainedResource
                                                         mutation:^(NosmaiEffectsEngine *effects,
                                                                    __unused NosmaiSDK *sdk) {
              NosmaiBackgroundSegmentationConfig *config =
                  [[NosmaiBackgroundSegmentationConfig alloc] init];
              if (isImage) {
                config.mode = NosmaiBackgroundSegmentationModeImage;
                config.replacementImage = image;
              } else {
                config.mode = NosmaiBackgroundSegmentationModeVideo;
                config.replacementVideoURL = resolvedURL;
              }
              [effects setBackgroundSegmentation:config];
            }
                                                      afterFence:nil
                                                          finish:finish];
          });
        }
      });
    }
                    completion:completion];
  });
}

- (void)clearBackgroundWithCompletion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    [self enqueueVisualMutationRequiringBeauty:NO
                                       advanced:NO
                                       keepAlive:nil
                                        mutation:^(NosmaiEffectsEngine *effects,
                                                   __unused NosmaiSDK *sdk) {
      // This is deliberately the manual segmentation slot only. Authored
      // type:background packages and AR-owned backgrounds must remain active.
      [effects clearBackgroundSegmentation];
    }
                                      afterFence:nil
                                      completion:completion];
  });
}

#pragma mark - Native delegates

- (void)nosmaiDidFailWithError:(NSError *)error {
  NosmaiIOSRunOnMain(^{
    [self emitAsyncErrorWithCode:@"E_NATIVE_FAILURE"
                         message:error.localizedDescription ?:
                                 @"The Nosmai SDK reported an error"
                           error:error
                      cameraView:NO];
  });
}

- (void)nosmaiDidChangeLicenseStatus:(BOOL)isValid status:(NSString *)status {
  (void)isValid;
  NosmaiIOSRunOnMain(^{
    NSString *normalized = status.lowercaseString;
    if (![normalized isEqualToString:@"valid"] &&
        ![normalized isEqualToString:@"invalid"] &&
        ![normalized isEqualToString:@"expired"] &&
        ![normalized isEqualToString:@"unverified"]) {
      normalized = @"unknown";
    }
    [self emitLicenseStatus:normalized];
  });
}

- (void)nosmaiCameraDidFailWithError:(NSError *)error {
  NosmaiIOSRunOnMain(^{
    [self emitAsyncErrorWithCode:NosmaiIOSErrorCameraDevice
                         message:error.localizedDescription ?:
                                 @"The native camera reported an error"
                           error:error
                      cameraView:YES];
  });
}

- (void)nosmaiEffectsDidChangePipelineState:(NosmaiPipelineState *)state {
  NosmaiIOSRunOnMain(^{
    NSDictionary *map = [self pipelineStateMap:state];
    if (map) {
      [self->_moduleSink nosmaiControllerDidChangeActiveEffects:map];
    }
  });
}

- (void)nosmaiEffectDidFailWithError:(NSError *)error
                           forEffect:(NSString *)effectID {
  (void)effectID;
  NosmaiIOSRunOnMain(^{
    if (self->_pendingEffectPromiseCount > 0) {
      return;
    }
    [self emitAsyncErrorWithCode:@"E_EFFECT_APPLY"
                         message:error.localizedDescription ?:
                                 @"The Nosmai effect failed"
                           error:error
                      cameraView:NO];
  });
}

#pragma mark - App lifecycle

- (void)applicationWillResignActive:(NSNotification *)notification {
  (void)notification;
  NosmaiIOSRunOnMain(^{
    self->_hostPaused = YES;
    [self stopFrameStreamInternal];
    BOOL wasStarting = self->_starting;
    BOOL hasMediaWork =
        self->_recordingState != NosmaiIOSRecordingStateIdle ||
        self->_captureInProgress;
    BOOL awaitingMicrophonePermission =
        self->_recordingState == NosmaiIOSRecordingStateStarting &&
        !self->_recordingNativeStartInFlight &&
        self->_recordingAwaitingMicrophonePermissionLifecycle;
    if (!self->_processing && !wasStarting && !hasMediaWork) {
      return;
    }
    NSUInteger transition = ++self->_cameraTransitionGeneration;
    self->_resumeInProgress = NO;
    self->_nativeProcessingActive = NO;
    if (wasStarting) {
      self->_starting = NO;
      [self finishCompletions:self->_startCompletions
                        value:nil
                         code:NosmaiIOSErrorOperationCancelled
                      message:@"Camera start was cancelled because the app became inactive"
                        error:nil];
    }
    [self invalidateProcessedFrameReadiness];
    [self->_previewSink nosmaiControllerShowTransition];
    [self cancelPendingCaptureWithMessage:
              @"Photo capture was cancelled because the app became inactive"];
    NosmaiCamera *camera = [NosmaiCore shared].camera;
    NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
    UIApplication *application = UIApplication.sharedApplication;
    if (!awaitingMicrophonePermission &&
        self->_recordingState != NosmaiIOSRecordingStateIdle &&
        self->_recordingBackgroundTaskIdentifier == UIBackgroundTaskInvalid) {
      __weak NosmaiIOSController *weakSelf = self;
      self->_recordingBackgroundTaskIdentifier =
          [application beginBackgroundTaskWithName:@"NosmaiRecordingFinalize"
                                 expirationHandler:^{
        NosmaiIOSRunOnMain(^{
          NosmaiIOSController *strongSelf = weakSelf;
          if (!strongSelf ||
              strongSelf->_recordingBackgroundTaskIdentifier ==
                  UIBackgroundTaskInvalid) {
            return;
          }
          UIBackgroundTaskIdentifier expiredTask =
              strongSelf->_recordingBackgroundTaskIdentifier;
          strongSelf->_recordingBackgroundTaskIdentifier =
              UIBackgroundTaskInvalid;
          if (strongSelf->_recordingState ==
              NosmaiIOSRecordingStateStopping) {
            [strongSelf
                handleRecordingStopTimeoutForGeneration:
                    strongSelf->_recordingGeneration
                                                 message:
                    @"Recording finalization exceeded the iOS background time limit"];
          } else if (strongSelf->_recordingState ==
                     NosmaiIOSRecordingStateStarting) {
            [strongSelf handleRecordingStartTimeoutForGeneration:
                            strongSelf->_recordingGeneration];
          }
          [application endBackgroundTask:expiredTask];
        });
      }];
    }

    dispatch_block_t pauseCameraAndEndBackgroundTask = ^{
      UIBackgroundTaskIdentifier backgroundTask =
          self->_recordingBackgroundTaskIdentifier;
      self->_recordingBackgroundTaskIdentifier = UIBackgroundTaskInvalid;
      if (transition == self->_cameraTransitionGeneration &&
          self->_hostPaused) {
        dispatch_async(self->_cameraQueue, ^{
          @try {
            [camera stopCapture];
          } @catch (__unused NSException *exception) {
          }
          @try {
            [sdk stopProcessing];
          } @catch (__unused NSException *exception) {
          }
          dispatch_async(dispatch_get_main_queue(), ^{
            if (transition == self->_cameraTransitionGeneration &&
                self->_hostPaused) {
              [self clearLiveFrameCallbackAfterProcessingStopped];
            }
          });
        });
      }
      if (backgroundTask != UIBackgroundTaskInvalid) {
        [application endBackgroundTask:backgroundTask];
      }
    };
    if (awaitingMicrophonePermission) {
      pauseCameraAndEndBackgroundTask();
      return;
    }
    [self stopRecordingForTeardown:pauseCameraAndEndBackgroundTask];
  });
}

- (void)applicationDidBecomeActive:(NSNotification *)notification {
  (void)notification;
  NosmaiIOSRunOnMain(^{
    // NosmaiCore observes the same notification. Resume on the next main-queue
    // turn so its internal lifecycle transition completes first, then restore
    // both physical capture and processing explicitly.
    dispatch_async(dispatch_get_main_queue(), ^{
      if (UIApplication.sharedApplication.applicationState !=
          UIApplicationStateActive) {
        return;
      }
      self->_hostPaused = NO;
      if (!self->_initialized || !self->_processing || self->_manualPaused ||
          !self->_previewSink) {
        if (self->_recordingAuthorizationPendingResume &&
            self->_recordingState == NosmaiIOSRecordingStateStarting) {
          self->_recordingAwaitingMicrophonePermissionLifecycle = NO;
          self->_recordingAuthorizationPendingResume = NO;
          self->_recordingAuthorizationGeneration = 0;
          self->_recordingState = NosmaiIOSRecordingStateIdle;
          self->_recordingForceStopRequested = NO;
          [self finishCompletions:self->_recordingStartCompletions
                            value:nil
                             code:NosmaiIOSErrorOperationCancelled
                          message:@"Recording start was cancelled while restoring the camera session"
                            error:nil];
          [self finishRecordingTeardownCompletions];
          [self applyPendingPreviewPresentationIfPossible];
        }
        return;
      }

      NSUInteger transition = ++self->_cameraTransitionGeneration;
      self->_resumeInProgress = YES;
      self->_resumeGeneration = transition;
      self->_nativeProcessingActive = NO;
      [self attachCurrentPreview];
      [self updateLiveFrameCallback];
      [self updateLiveFrameOutputDemand];
      NosmaiCamera *camera = [NosmaiCore shared].camera;
      NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
      dispatch_async(self->_cameraQueue, ^{
        __block BOOL started = NO;
        __block NSError *failure = nil;
        @try {
          started = [camera startCapture];
          if (started) {
            [sdk startProcessing];
          }
        } @catch (NSException *exception) {
          failure = NosmaiIOSErrorFromException(
              exception, NosmaiIOSErrorProcessingStart,
              @"Unable to resume Nosmai processing after activation");
        }
        if (failure && started) {
          @try {
            [camera stopCapture];
          } @catch (__unused NSException *exception) {
          }
          @try {
            [sdk stopProcessing];
          } @catch (__unused NSException *exception) {
          }
          started = NO;
        }
        dispatch_async(dispatch_get_main_queue(), ^{
          if (self->_resumeGeneration == transition) {
            self->_resumeInProgress = NO;
          }
          if (transition != self->_cameraTransitionGeneration ||
              self->_hostPaused || !self->_processing ||
              self->_manualPaused) {
            return;
          }
          if (started && !failure) {
            self->_nativeProcessingActive = YES;
            [self armProcessedFrameReadiness];
            if (self->_recordingAuthorizationPendingResume &&
                self->_recordingAuthorizationGeneration ==
                    self->_recordingGeneration &&
                self->_recordingState == NosmaiIOSRecordingStateStarting) {
              NSUInteger recordingGeneration =
                  self->_recordingAuthorizationGeneration;
              [self
                  continueRecordingStartAfterAuthorizationForGeneration:
                      recordingGeneration];
            }
          } else {
            self->_nativeProcessingActive = NO;
            [self clearLiveFrameCallbackAfterProcessingStopped];
            [self emitAsyncErrorWithCode:failure.userInfo[@"nosmaiCode"] ?:
                                             NosmaiIOSErrorCameraStart
                                 message:failure.localizedDescription ?:
                                             @"Unable to resume the camera after activation"
                                   error:failure
                              cameraView:YES];
            if (self->_recordingAuthorizationPendingResume &&
                self->_recordingState == NosmaiIOSRecordingStateStarting) {
              NSError *recordingError = failure ?: NosmaiIOSError(
                  NosmaiIOSErrorRecordingStart,
                  @"Unable to restore the camera before recording", nil);
              self->_recordingAwaitingMicrophonePermissionLifecycle = NO;
              self->_recordingAuthorizationPendingResume = NO;
              self->_recordingAuthorizationGeneration = 0;
              self->_recordingState = NosmaiIOSRecordingStateIdle;
              self->_recordingForceStopRequested = NO;
              [self finishCompletions:self->_recordingStartCompletions
                                value:nil
                                 code:NosmaiIOSErrorRecordingStart
                              message:recordingError.localizedDescription
                                error:recordingError];
              [self finishRecordingTeardownCompletions];
              [self applyPendingPreviewPresentationIfPossible];
            }
          }
        });
      });
    });
  });
}

#pragma mark - Internal helpers

- (BOOL)ensureRecordingIdleForCompletion:(NosmaiIOSCompletion)completion
                               operation:(NSString *)operation {
  if (_recordingState != NosmaiIOSRecordingStateIdle) {
    completion(nil, NosmaiIOSErrorRecordingInProgress,
               [NSString stringWithFormat:
                   @"Stop recording before attempting to %@", operation],
               nil);
    return NO;
  }
  return YES;
}

- (void)requestMicrophoneAuthorization:
    (void (^)(BOOL authorized))completion {
  NSAssert(NSThread.isMainThread,
           @"Microphone authorization must start on the main thread");
  id usageValue =
      [NSBundle.mainBundle objectForInfoDictionaryKey:
                               @"NSMicrophoneUsageDescription"];
  NSString *usageDescription = [usageValue isKindOfClass:NSString.class]
      ? [(NSString *)usageValue
            stringByTrimmingCharactersInSet:
                NSCharacterSet.whitespaceAndNewlineCharacterSet]
      : nil;
  if (usageDescription.length == 0) {
    completion(NO);
    return;
  }

  AVAuthorizationStatus status =
      [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeAudio];
  if (status == AVAuthorizationStatusAuthorized) {
    completion(YES);
  } else if (status == AVAuthorizationStatusNotDetermined) {
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeAudio
                             completionHandler:^(BOOL granted) {
      NosmaiIOSRunOnMain(^{ completion(granted); });
    }];
  } else {
    completion(NO);
  }
}

- (void)continueRecordingStartAfterAuthorizationForGeneration:
    (NSUInteger)generation {
  NSAssert(NSThread.isMainThread,
           @"Recording start must continue on the main thread");
  if (generation != _recordingGeneration ||
      _recordingState != NosmaiIOSRecordingStateStarting) {
    return;
  }

  _recordingAwaitingMicrophonePermissionLifecycle = NO;
  _recordingAuthorizationPendingResume = NO;
  _recordingAuthorizationGeneration = 0;
  if (_cleanupInProgress || _hostPaused || !_processing ||
      !_nativeProcessingActive || !_previewSink ||
      ![NosmaiCore shared].camera.isCapturing) {
    _recordingState = NosmaiIOSRecordingStateIdle;
    _recordingForceStopRequested = NO;
    [self finishCompletions:_recordingStartCompletions
                      value:nil
                       code:NosmaiIOSErrorOperationCancelled
                    message:@"Recording start was cancelled by a session transition"
                      error:nil];
    [self finishRecordingTeardownCompletions];
    [self applyPendingPreviewPresentationIfPossible];
    return;
  }

  _recordingNativeStartInFlight = YES;
  void (^nativeCompletion)(BOOL, NSError *) =
      ^(BOOL success, NSError *error) {
    NosmaiIOSRunOnMain(^{
      if (generation != self->_recordingGeneration) {
        if (success &&
            self->_recordingState == NosmaiIOSRecordingStateIdle &&
            [NosmaiCore shared].isRecording) {
          @try {
            [[NosmaiCore shared]
                stopRecordingWithCompletion:^(NSURL *lateURL,
                                              __unused NSError *lateError) {
              if (lateURL.isFileURL) {
                dispatch_async(self->_mediaQueue, ^{
                  [NSFileManager.defaultManager removeItemAtURL:lateURL
                                                          error:nil];
                });
              }
            }];
          } @catch (__unused NSException *exception) {
          }
        }
        return;
      }
      if (!NosmaiRecordingAcceptsSettlement(
              generation, self->_recordingGeneration,
              self->_recordingState == NosmaiIOSRecordingStateStarting,
              !self->_recordingNativeStartInFlight)) {
        return;
      }

      self->_recordingNativeStartInFlight = NO;
      if (!success || error || ![NosmaiCore shared].isRecording) {
        BOOL hadPublicCompletion =
            self->_recordingStartCompletions.count > 0;
        BOOL wasForced = self->_recordingForceStopRequested;
        self->_recordingState = NosmaiIOSRecordingStateIdle;
        self->_recordingForceStopRequested = NO;
        NSString *failureCode = NosmaiRecordingResolveErrorCode(
            NosmaiIOSErrorRecordingStart,
            NosmaiIOSErrorRecordingStorageFull,
            error.localizedDescription, error);
        [self finishCompletions:self->_recordingStartCompletions
                          value:nil
                           code:failureCode
                        message:error.localizedDescription ?:
                                @"Unable to start recording"
                          error:error];
        if (wasForced && !hadPublicCompletion) {
          [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingInterrupted
                               message:@"Recording start was interrupted by camera teardown"
                                 error:error
                            cameraView:NO];
        }
        [self finishRecordingTeardownCompletions];
        [self applyPendingPreviewPresentationIfPossible];
        return;
      }

      self->_recordingState = NosmaiIOSRecordingStateRecording;
      self->_recordingStartedAtSystemUptime =
          NSProcessInfo.processInfo.systemUptime;
      self->_lastReportedRecordingDuration = 0;
      self->_recordingDurationAtStop = 0;
      if (self->_recordingForceStopRequested || self->_hostPaused ||
          !self->_previewSink) {
        [self finishCompletions:self->_recordingStartCompletions
                          value:nil
                           code:NosmaiIOSErrorOperationCancelled
                        message:@"Recording start was cancelled by camera teardown"
                          error:nil];
        [self beginNativeRecordingStop];
        return;
      }

      [self startRecordingProgressTimer];
      [self finishCompletions:self->_recordingStartCompletions
                        value:nil
                         code:nil
                      message:nil
                        error:nil];
    });
  };

  dispatch_after(
      dispatch_time(DISPATCH_TIME_NOW,
                    (int64_t)(NosmaiIOSRecordingStartTimeoutSeconds *
                              NSEC_PER_SEC)),
      dispatch_get_main_queue(), ^{
        [self handleRecordingStartTimeoutForGeneration:generation];
      });
  @try {
    [[NosmaiCore shared] startRecordingWithCompletion:nativeCompletion];
  } @catch (NSException *exception) {
    nativeCompletion(
        NO, NosmaiIOSErrorFromException(exception,
                                         NosmaiIOSErrorRecordingStart,
                                         @"Unable to start recording"));
  }
}

- (NSTimeInterval)authoritativeRecordingDuration {
  NSAssert(NSThread.isMainThread,
           @"Recording duration must be read on the main thread");
  NSTimeInterval duration = 0;
  if (_recordingState == NosmaiIOSRecordingStateRecording) {
    NSTimeInterval nativeDuration =
        [[NosmaiCore shared] currentRecordingDuration];
    NSTimeInterval uptimeDuration = _recordingStartedAtSystemUptime > 0
        ? NSProcessInfo.processInfo.systemUptime -
              _recordingStartedAtSystemUptime
        : 0;
    if (!std::isfinite(nativeDuration) || nativeDuration < 0) {
      nativeDuration = 0;
    }
    if (!std::isfinite(uptimeDuration) || uptimeDuration < 0) {
      uptimeDuration = 0;
    }
    duration = std::max(nativeDuration, uptimeDuration);
    duration = std::max(duration, _lastReportedRecordingDuration);
    _lastReportedRecordingDuration = duration;
  } else if (_recordingState == NosmaiIOSRecordingStateStopping) {
    duration = _recordingDurationAtStop;
  }
  return std::isfinite(duration) && duration > 0 ? duration : 0;
}

- (void)startRecordingProgressTimer {
  NSAssert(NSThread.isMainThread,
           @"Recording progress must start on the main thread");
  [self stopRecordingProgressTimer];
  [_moduleSink nosmaiControllerDidUpdateRecordingProgress:
                   @{ @"durationSeconds" : @0 }];

  __weak NosmaiIOSController *weakSelf = self;
  _recordingProgressTimer =
      [NSTimer timerWithTimeInterval:NosmaiIOSRecordingProgressIntervalSeconds
                            repeats:YES
                              block:^(__unused NSTimer *timer) {
    NosmaiIOSController *strongSelf = weakSelf;
    if (!strongSelf ||
        strongSelf->_recordingState != NosmaiIOSRecordingStateRecording) {
      return;
    }
    [strongSelf->_moduleSink nosmaiControllerDidUpdateRecordingProgress:@{
      @"durationSeconds" : @([strongSelf authoritativeRecordingDuration])
    }];
  }];
  [NSRunLoop.mainRunLoop addTimer:_recordingProgressTimer
                          forMode:NSRunLoopCommonModes];
}

- (void)stopRecordingProgressTimer {
  NSAssert(NSThread.isMainThread,
           @"Recording progress must stop on the main thread");
  [_recordingProgressTimer invalidate];
  _recordingProgressTimer = nil;
}

- (void)handleRecordingStartTimeoutForGeneration:(NSUInteger)generation {
  NSAssert(NSThread.isMainThread,
           @"Recording start timeout must run on the main thread");
  if (!NosmaiRecordingAcceptsSettlement(
          generation, _recordingGeneration,
          _recordingState == NosmaiIOSRecordingStateStarting,
          !_recordingNativeStartInFlight)) {
    return;
  }

  BOOL hadPublicCompletion = _recordingStartCompletions.count > 0;
  BOOL wasForced = _recordingForceStopRequested;
  NSError *timeoutError = NosmaiIOSError(
      NosmaiIOSErrorRecordingStart,
      @"Recording start did not complete in time", nil);
  _recordingGeneration++;
  _recordingState = NosmaiIOSRecordingStateIdle;
  _recordingNativeStartInFlight = NO;
  _recordingAwaitingMicrophonePermissionLifecycle = NO;
  _recordingAuthorizationPendingResume = NO;
  _recordingAuthorizationGeneration = 0;
  _recordingStopCallbackReceived = NO;
  _recordingForceStopRequested = NO;
  _recordingStartedAtSystemUptime = 0;
  _lastReportedRecordingDuration = 0;
  _recordingDurationAtStop = 0;
  [self finishCompletions:_recordingStartCompletions
                    value:nil
                     code:NosmaiIOSErrorRecordingStart
                  message:timeoutError.localizedDescription
                    error:timeoutError];
  if (wasForced && !hadPublicCompletion) {
    [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingInterrupted
                         message:@"Recording start timed out during camera teardown"
                           error:timeoutError
                      cameraView:NO];
  } else if (!hadPublicCompletion) {
    [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingStart
                         message:timeoutError.localizedDescription
                           error:timeoutError
                      cameraView:NO];
  }
  [self finishRecordingTeardownCompletions];
  [self applyPendingPreviewPresentationIfPossible];
}

- (void)handleRecordingStopTimeoutForGeneration:(NSUInteger)generation
                                         message:(NSString *)message {
  NSAssert(NSThread.isMainThread,
           @"Recording stop timeout must run on the main thread");
  if (!NosmaiRecordingAcceptsSettlement(
          generation, _recordingGeneration,
          _recordingState == NosmaiIOSRecordingStateStopping, NO)) {
    return;
  }

  BOOL hadPublicCompletion = _recordingStopCompletions.count > 0;
  BOOL wasForced = _recordingForceStopRequested;
  NSError *timeoutError = NosmaiIOSError(
      NosmaiIOSErrorRecordingStop,
      message.length > 0 ? message
                         : @"Recording finalization did not complete in time",
      nil);
  _recordingGeneration++;
  _recordingState = NosmaiIOSRecordingStateIdle;
  _recordingNativeStartInFlight = NO;
  _recordingStopCallbackReceived = NO;
  _recordingForceStopRequested = NO;
  _recordingStartedAtSystemUptime = 0;
  _lastReportedRecordingDuration = 0;
  _recordingDurationAtStop = 0;
  [self finishCompletions:_recordingStopCompletions
                    value:nil
                     code:NosmaiIOSErrorRecordingStop
                  message:timeoutError.localizedDescription
                    error:timeoutError];
  if (wasForced && !hadPublicCompletion) {
    [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingInterrupted
                         message:@"Recording finalization timed out during camera teardown"
                           error:timeoutError
                      cameraView:NO];
  } else if (!hadPublicCompletion) {
    [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingStop
                         message:timeoutError.localizedDescription
                           error:timeoutError
                      cameraView:NO];
  }
  [self finishRecordingTeardownCompletions];
  [self applyPendingPreviewPresentationIfPossible];
}

- (void)beginNativeRecordingStop {
  NSAssert(NSThread.isMainThread,
           @"Recording stop must begin on the main thread");
  if (_recordingState != NosmaiIOSRecordingStateRecording) return;

  _recordingDurationAtStop = [self authoritativeRecordingDuration];
  [self stopRecordingProgressTimer];
  [_moduleSink nosmaiControllerDidUpdateRecordingProgress:@{
    @"durationSeconds" : @(_recordingDurationAtStop)
  }];
  _recordingState = NosmaiIOSRecordingStateStopping;
  _recordingStopCallbackReceived = NO;
  NSUInteger stopGeneration = _recordingGeneration;

  dispatch_after(
      dispatch_time(DISPATCH_TIME_NOW,
                    (int64_t)(NosmaiIOSRecordingStopTimeoutSeconds *
                              NSEC_PER_SEC)),
      dispatch_get_main_queue(), ^{
        [self handleRecordingStopTimeoutForGeneration:stopGeneration
                                               message:
            @"Recording finalization did not complete in time"];
      });

  __block BOOL nativeCallbackAccepted = NO;
  __block NSString *acceptedVideoPath = nil;
  void (^nativeCompletion)(NSURL *, NSError *) =
      ^(NSURL *videoURL, NSError *nativeError) {
    NosmaiIOSRunOnMain(^{
      if (nativeCallbackAccepted) {
        if (videoURL.isFileURL &&
            (acceptedVideoPath.length == 0 ||
             ![videoURL.path isEqualToString:acceptedVideoPath])) {
          dispatch_async(self->_mediaQueue, ^{
            [NSFileManager.defaultManager removeItemAtURL:videoURL error:nil];
          });
        }
        return;
      }
      if (!NosmaiRecordingAcceptsSettlement(
              stopGeneration, self->_recordingGeneration,
              self->_recordingState == NosmaiIOSRecordingStateStopping,
              self->_recordingStopCallbackReceived)) {
        if (videoURL.isFileURL) {
          dispatch_async(self->_mediaQueue, ^{
            [NSFileManager.defaultManager removeItemAtURL:videoURL error:nil];
          });
        }
        return;
      }
      nativeCallbackAccepted = YES;
      acceptedVideoPath = [videoURL.path copy];
      self->_recordingStopCallbackReceived = YES;
      NSTimeInterval frozenDuration = self->_recordingDurationAtStop;
      dispatch_async(self->_mediaQueue, ^{
        BOOL exists = videoURL.isFileURL && videoURL.path.length > 0 &&
            [NSFileManager.defaultManager fileExistsAtPath:videoURL.path];
        NSError *attributeError = nil;
        NSDictionary *attributes = exists
            ? [NSFileManager.defaultManager
                  attributesOfItemAtPath:videoURL.path
                                   error:&attributeError]
            : nil;
        NSNumber *fileSize = attributes[NSFileSize];
        AVURLAsset *asset = exists
            ? [AVURLAsset URLAssetWithURL:videoURL options:nil]
            : nil;
        NSArray<AVAssetTrack *> *videoTracks =
            [asset tracksWithMediaType:AVMediaTypeVideo];
        NSArray<AVAssetTrack *> *audioTracks =
            [asset tracksWithMediaType:AVMediaTypeAudio];
        BOOL validVideo =
            exists &&
            [videoURL.pathExtension.lowercaseString isEqualToString:@"mp4"] &&
            fileSize.unsignedLongLongValue > 0 && videoTracks.count > 0;
        NSTimeInterval assetDuration = asset
            ? CMTimeGetSeconds(asset.duration)
            : 0;
        NSTimeInterval duration =
            std::isfinite(assetDuration) && assetDuration > 0
                ? assetDuration
                : frozenDuration;
        NosmaiRecordingFinalizationDisposition disposition =
            NosmaiRecordingResolveFinalization(
                validVideo, audioTracks.count > 0, nativeError != nil);
        NSDictionary *result =
            disposition != NosmaiRecordingFinalizationDispositionFailure
            ? @{
                @"uri" : videoURL.absoluteString,
                @"durationSeconds" : @(duration),
                @"fileSizeBytes" : fileSize ?: @0,
                @"mimeType" : @"video/mp4",
                @"hasAudio" : @(
                    disposition == NosmaiRecordingFinalizationDispositionMuxed),
              }
            : nil;
        NSString *failureCode = nil;
        NSString *failureMessage = nil;
        NSError *failure = nativeError ?: attributeError;
        NSString *warningMessage = nil;
        if (NosmaiRecordingNeedsAudioMuxWarning(disposition)) {
          warningMessage = nativeError.localizedDescription ?:
              @"The recording was created but audio could not be finalized";
        } else if (!result) {
          failureCode = NosmaiRecordingResolveErrorCode(
              NosmaiIOSErrorRecordingStop,
              NosmaiIOSErrorRecordingStorageFull,
              failure.localizedDescription, failure);
          failureMessage = failure.localizedDescription ?:
              @"Unable to finalize the recording";
        }
        if (!validVideo && videoURL.isFileURL) {
          [NSFileManager.defaultManager removeItemAtURL:videoURL error:nil];
        }

        dispatch_async(dispatch_get_main_queue(), ^{
          if (stopGeneration != self->_recordingGeneration ||
              self->_recordingState != NosmaiIOSRecordingStateStopping) {
            if (validVideo && videoURL.isFileURL) {
              dispatch_async(self->_mediaQueue, ^{
                [NSFileManager.defaultManager removeItemAtURL:videoURL
                                                        error:nil];
              });
            }
            return;
          }
          BOOL hadPublicCompletion = self->_recordingStopCompletions.count > 0;
          BOOL wasForced = self->_recordingForceStopRequested;
          self->_recordingState = NosmaiIOSRecordingStateIdle;
          self->_recordingNativeStartInFlight = NO;
          self->_recordingStopCallbackReceived = NO;
          self->_recordingForceStopRequested = NO;
          self->_recordingStartedAtSystemUptime = 0;
          self->_lastReportedRecordingDuration = 0;
          self->_recordingDurationAtStop = 0;
          [self finishCompletions:self->_recordingStopCompletions
                            value:result
                             code:failureCode
                          message:failureMessage
                            error:failureCode ? failure : nil];
          if (warningMessage && (!wasForced || hadPublicCompletion)) {
            [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingAudioMux
                                 message:warningMessage
                                   error:nativeError
                              cameraView:NO];
          }
          if (wasForced && !hadPublicCompletion) {
            [self emitAsyncErrorWithCode:NosmaiIOSErrorRecordingInterrupted
                                 message:@"Recording stopped because the camera session was interrupted"
                                   error:failure
                              cameraView:NO];
          } else if (failureCode && !hadPublicCompletion) {
            [self emitAsyncErrorWithCode:failureCode
                                 message:failureMessage
                                   error:failure
                              cameraView:NO];
          }
          if (wasForced && !hadPublicCompletion && result &&
              videoURL.isFileURL) {
            dispatch_async(self->_mediaQueue, ^{
              [NSFileManager.defaultManager removeItemAtURL:videoURL error:nil];
            });
          }
          [self finishRecordingTeardownCompletions];
          [self applyPendingPreviewPresentationIfPossible];
        });
      });
    });
  };

  @try {
    [[NosmaiCore shared] stopRecordingWithCompletion:nativeCompletion];
  } @catch (NSException *exception) {
    nativeCompletion(nil, NosmaiIOSErrorFromException(
                              exception, NosmaiIOSErrorRecordingStop,
                              @"Unable to stop recording"));
  }
}

- (void)stopRecordingForTeardown:(dispatch_block_t)completion {
  NSAssert(NSThread.isMainThread,
           @"Recording teardown must begin on the main thread");
  [_recordingTeardownCompletions addObject:[completion copy]];
  switch (_recordingState) {
    case NosmaiIOSRecordingStateIdle:
      [self finishRecordingTeardownCompletions];
      return;
    case NosmaiIOSRecordingStateStarting:
      _recordingForceStopRequested = YES;
      _recordingAwaitingMicrophonePermissionLifecycle = NO;
      _recordingAuthorizationPendingResume = NO;
      _recordingAuthorizationGeneration = 0;
      [self finishCompletions:_recordingStartCompletions
                        value:nil
                         code:NosmaiIOSErrorOperationCancelled
                      message:@"Recording start was cancelled by camera teardown"
                        error:nil];
      if (!_recordingNativeStartInFlight) {
        _recordingGeneration++;
        _recordingState = NosmaiIOSRecordingStateIdle;
        _recordingForceStopRequested = NO;
        [self finishRecordingTeardownCompletions];
        [self applyPendingPreviewPresentationIfPossible];
      }
      return;
    case NosmaiIOSRecordingStateRecording:
      _recordingForceStopRequested = YES;
      [self beginNativeRecordingStop];
      return;
    case NosmaiIOSRecordingStateStopping:
      _recordingForceStopRequested = YES;
      return;
  }
}

- (void)finishRecordingTeardownCompletions {
  NSAssert(NSThread.isMainThread,
           @"Recording teardown must finish on the main thread");
  NSArray *completions = [_recordingTeardownCompletions copy];
  [_recordingTeardownCompletions removeAllObjects];
  for (dispatch_block_t completion in completions) {
    completion();
  }
}

- (void)cancelPendingCaptureWithMessage:(NSString *)message {
  NSAssert(NSThread.isMainThread,
           @"Photo capture cancellation must run on the main thread");
  if (!_captureInProgress) return;
  _captureGeneration++;
  _captureInProgress = NO;
  _captureNativeCallbackReceived = NO;
  NosmaiIOSCompletion completion = _captureCompletion;
  _captureCompletion = nil;
  completion(nil, NosmaiIOSErrorOperationCancelled, message, nil);
  [self applyPendingPreviewPresentationIfPossible];
}

- (void)applyPendingPreviewPresentationIfPossible {
  NSAssert(NSThread.isMainThread,
           @"Pending preview presentation must run on the main thread");
  if (!_hasPendingPresentation || _captureInProgress ||
      _recordingState != NosmaiIOSRecordingStateIdle) {
    return;
  }

  id<NosmaiIOSPreviewSink> sink = _pendingPresentationSink;
  NSString *position = [_pendingPresentationPosition copy];
  BOOL mirror = _pendingPresentationMirror;
  _hasPendingPresentation = NO;
  _pendingPresentationSink = nil;
  _pendingPresentationPosition = nil;
  if (!sink || sink != _previewSink || position.length == 0) return;
  [self updatePreviewSink:sink position:position mirror:mirror];
}

- (NSURL *)readableMediaURLFromInput:(NSString *)input {
  if (![input isKindOfClass:NSString.class]) return nil;
  NSString *value = [input stringByTrimmingCharactersInSet:
      NSCharacterSet.whitespaceAndNewlineCharacterSet];
  if (value.length == 0) return nil;

  if (![value.lowercaseString hasPrefix:@"file://"]) return nil;
  NSURL *url = [NSURL URLWithString:value];
  NSString *host = url.host;
  if (!url.isFileURL || url.path.length == 0 ||
      (host.length > 0 &&
       ![host.lowercaseString isEqualToString:@"localhost"])) {
    return nil;
  }
  NSString *path = url.path;
  path = path.stringByStandardizingPath;
  if (!path.isAbsolutePath) return nil;
  path = path.stringByResolvingSymlinksInPath;

  BOOL isDirectory = NO;
  BOOL exists = [NSFileManager.defaultManager fileExistsAtPath:path
                                                   isDirectory:&isDirectory];
  if (!exists || isDirectory ||
      ![NSFileManager.defaultManager isReadableFileAtPath:path]) {
    return nil;
  }
  NSDictionary *attributes = [NSFileManager.defaultManager
      attributesOfItemAtPath:path
                       error:nil];
  if ([attributes[NSFileSize] unsignedLongLongValue] == 0) return nil;
  return [NSURL fileURLWithPath:path];
}

- (void)requestPhotoLibraryAddAuthorization:
    (void (^)(BOOL authorized))completion {
  NSAssert(NSThread.isMainThread,
           @"Photo authorization must start on the main thread");
  id usageValue = [NSBundle.mainBundle
      objectForInfoDictionaryKey:@"NSPhotoLibraryAddUsageDescription"];
  NSString *usageDescription = [usageValue isKindOfClass:NSString.class]
      ? [(NSString *)usageValue
            stringByTrimmingCharactersInSet:
                NSCharacterSet.whitespaceAndNewlineCharacterSet]
      : nil;
  if (usageDescription.length == 0) {
    completion(NO);
    return;
  }

  PHAuthorizationStatus status =
      [PHPhotoLibrary authorizationStatusForAccessLevel:PHAccessLevelAddOnly];
  if (status == PHAuthorizationStatusAuthorized ||
      status == PHAuthorizationStatusLimited) {
    completion(YES);
  } else if (status == PHAuthorizationStatusNotDetermined) {
    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly
                                               handler:^(PHAuthorizationStatus value) {
      BOOL granted = value == PHAuthorizationStatusAuthorized ||
                     value == PHAuthorizationStatusLimited;
      NosmaiIOSRunOnMain(^{ completion(granted); });
    }];
  } else {
    completion(NO);
  }
}

- (void)saveMediaAtURI:(NSString *)mediaURI
                  name:(NSString *_Nullable)name
             mediaType:(NSString *)mediaType
            completion:(NosmaiIOSCompletion)completion {
  NosmaiIOSRunOnMain(^{
    NSURL *sourceURL = [self readableMediaURLFromInput:mediaURI];
    if (!sourceURL) {
      completion(nil, NosmaiIOSErrorMediaNotFound,
                 @"The media URI must reference a readable local file", nil);
      return;
    }
    BOOL isPhoto = [mediaType isEqualToString:@"photo"];
    if (!isPhoto && ![mediaType isEqualToString:@"video"]) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 @"Unsupported gallery media type", nil);
      return;
    }
    NSString *sourceExtension = sourceURL.pathExtension.lowercaseString;
    BOOL hasExpectedExtension = isPhoto
        ? ([sourceExtension isEqualToString:@"jpg"] ||
           [sourceExtension isEqualToString:@"jpeg"])
        : [sourceExtension isEqualToString:@"mp4"];
    if (!hasExpectedExtension) {
      completion(nil, NosmaiIOSErrorInvalidArgument,
                 isPhoto
                     ? @"Image URI must reference a .jpg or .jpeg file"
                     : @"Video URI must reference a .mp4 file",
                 nil);
      return;
    }

    [self requestPhotoLibraryAddAuthorization:^(BOOL authorized) {
      if (!authorized) {
        completion(nil, NosmaiIOSErrorGalleryPermission,
                   @"Photo library add permission is required", nil);
        return;
      }

      NSString *trimmedName = [name isKindOfClass:NSString.class]
          ? [name stringByTrimmingCharactersInSet:
                      NSCharacterSet.whitespaceAndNewlineCharacterSet]
          : nil;
      NSString *filenameBase = trimmedName.length > 0
          ? trimmedName.lastPathComponent.stringByDeletingPathExtension
          : sourceURL.lastPathComponent.stringByDeletingPathExtension;
      if (filenameBase.length == 0) {
        filenameBase = [NSString
            stringWithFormat:@"nosmai_%@_%@", mediaType,
                             NSUUID.UUID.UUIDString];
      }
      NSString *filename =
          [filenameBase stringByAppendingPathExtension:sourceExtension];

      __block NSString *assetIdentifier = nil;
      [PHPhotoLibrary.sharedPhotoLibrary performChanges:^{
        PHAssetCreationRequest *request =
            [PHAssetCreationRequest creationRequestForAsset];
        PHAssetResourceCreationOptions *options =
            [[PHAssetResourceCreationOptions alloc] init];
        options.originalFilename = filename;
        [request addResourceWithType:isPhoto ? PHAssetResourceTypePhoto
                                             : PHAssetResourceTypeVideo
                             fileURL:sourceURL
                             options:options];
        assetIdentifier = request.placeholderForCreatedAsset.localIdentifier;
      } completionHandler:^(BOOL success, NSError *error) {
        NosmaiIOSRunOnMain(^{
          if (!success || error || assetIdentifier.length == 0) {
            completion(nil, NosmaiIOSErrorGallerySave,
                       error.localizedDescription ?:
                           @"Unable to save media to the photo library",
                       error);
            return;
          }
          completion(@{
            @"uri" : [@"ph://" stringByAppendingString:assetIdentifier],
            @"mediaType" : mediaType,
          }, nil, nil, nil);
        });
      }];
    }];
  });
}

- (BOOL)ensureCloudEnabledForCompletion:(NosmaiIOSCompletion)completion {
  NSAssert(NSThread.isMainThread,
           @"Cloud availability must be checked on the main thread");
  @try {
    NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
    if (effects && [effects isCloudFilterEnabled]) return YES;
  } @catch (__unused NSException *exception) {
    // Convert framework exceptions into the stable bridge error below.
  }

  NSError *error = NosmaiIOSError(
      NosmaiIOSErrorCloudDisabled,
      @"Cloud filters are not enabled for this SDK license", nil);
  completion(nil, NosmaiIOSErrorCloudDisabled, error.localizedDescription,
             error);
  return NO;
}

- (NSDictionary *)cloudFilterMapFromDictionary:(NSDictionary *)raw
                            requestedPackageType:(NSString *)packageType
                                         effects:(NosmaiEffectsEngine *)effects {
  NSString *filterId =
      NosmaiIOSNormalizedCloudIdentifier(raw[@"filterId"]);
  if (!filterId) {
    filterId = NosmaiIOSNormalizedCloudIdentifier(raw[@"id"]);
  }
  if (!filterId) return nil;

  NSString *backendId = NosmaiIOSNormalizedCloudIdentifier(raw[@"id"]);
  NSString *name = NosmaiIOSNonEmptyString(raw[@"name"]);
  if (!name) name = NosmaiIOSNonEmptyString(raw[@"slug"]);
  NSString *displayName = NosmaiIOSNonEmptyString(raw[@"displayName"]);
  if (!name) name = displayName ?: filterId;
  if (!displayName) displayName = name;

  NSString *resolvedPackageType = packageType;
  if (!resolvedPackageType) {
    resolvedPackageType = NosmaiIOSCloudPackageType(raw[@"filterType"]);
  }
  if (!resolvedPackageType) {
    resolvedPackageType = NosmaiIOSCloudPackageType(raw[@"packageType"]);
  }
  if (!resolvedPackageType) {
    resolvedPackageType = NosmaiIOSCloudPackageType(raw[@"sourceType"]);
  }
  if (!resolvedPackageType) {
    resolvedPackageType = NosmaiIOSCloudPackageType(raw[@"filterCategory"]);
  }
  if (!resolvedPackageType) {
    resolvedPackageType = NosmaiIOSCloudPackageType(raw[@"category"]);
  }
  if (!resolvedPackageType) resolvedPackageType = @"filter";

  NSString *path = [self normalizedPackagePath:raw[@"path"]
                                requireReadable:YES];
  if (!path) {
    path = [self normalizedPackagePath:raw[@"localPath"]
                       requireReadable:YES];
  }
  if (!path) {
    @try {
      if ([effects isCloudFilterDownloaded:filterId]) {
        path = [self
            normalizedPackagePath:[effects getCloudFilterLocalPath:filterId]
                   requireReadable:YES];
      }
    } @catch (__unused NSException *exception) {
      path = nil;
    }
  }

  NSNumber *fileSize = @0;
  if (path) {
    NSDictionary *attributes =
        [NSFileManager.defaultManager attributesOfItemAtPath:path error:nil];
    NSNumber *diskSize = attributes[NSFileSize];
    if ([diskSize isKindOfClass:NSNumber.class]) fileSize = diskSize;
  } else {
    NSNumber *rawSize = NosmaiIOSFiniteNumber(raw[@"fileSize"]);
    if (rawSize.doubleValue >= 0.0) fileSize = rawSize ?: @0;
  }

  NSNumber *downloadCount = NosmaiIOSFiniteNumber(raw[@"downloadCount"]);
  if (!downloadCount || downloadCount.doubleValue < 0.0) downloadCount = @0;
  NSNumber *price = NosmaiIOSFiniteNumber(raw[@"price"]);
  if (!price || price.doubleValue < 0.0) price = @0;
  BOOL isFree = [raw[@"isFree"] isKindOfClass:NSNumber.class]
      ? [raw[@"isFree"] boolValue]
      : price.doubleValue == 0.0;

  NSMutableDictionary *map = [@{
    @"id" : filterId,
    @"filterId" : filterId,
    @"name" : name,
    @"displayName" : displayName,
    @"description" : NosmaiIOSNonEmptyString(raw[@"description"]) ?: @"",
    @"path" : path ?: @"",
    @"fileSize" : fileSize,
    @"type" : @"cloud",
    @"location" : @"cloud",
    @"filterType" : resolvedPackageType,
    @"packageType" : resolvedPackageType,
    @"isFree" : @(isFree),
    @"isDownloaded" : @(path != nil),
    @"downloadCount" : downloadCount,
    @"price" : price,
  } mutableCopy];

  if (backendId && ![backendId isEqualToString:filterId]) {
    map[@"backendId"] = backendId;
  }

  NSString *previewURL = NosmaiIOSSafeRemoteURL(raw[@"previewUrl"]);
  if (!previewURL) {
    previewURL = NosmaiIOSSafeRemoteURL(raw[@"mobilePreviewUrl"]);
  }
  if (!previewURL) {
    previewURL = NosmaiIOSSafeRemoteURL(raw[@"thumbnailUrl"]);
  }
  if (!previewURL) {
    previewURL = NosmaiIOSSafeRemoteURL(raw[@"filterPreview"]);
  }
  if (previewURL) map[@"previewUrl"] = previewURL;

  NSString *category = NosmaiIOSNonEmptyString(raw[@"category"]);
  if (!category) category = NosmaiIOSNonEmptyString(raw[@"filterCategory"]);
  if (category) map[@"category"] = category;

  NSString *metadataVersion = NosmaiIOSNonEmptyString(raw[@"version"]);
  NSString *author = NosmaiIOSNonEmptyString(raw[@"author"]);
  if (!author) author = NosmaiIOSNonEmptyString(raw[@"authorName"]);
  NSString *minSDKVersion = NosmaiIOSNonEmptyString(raw[@"minSDKVersion"]);
  if (!minSDKVersion) {
    minSDKVersion = NosmaiIOSNonEmptyString(raw[@"minSdkVersion"]);
  }
  NSString *created = NosmaiIOSNonEmptyString(raw[@"created"]);
  if (metadataVersion) map[@"version"] = metadataVersion;
  if (author) map[@"author"] = author;
  if (minSDKVersion) map[@"minSDKVersion"] = minSDKVersion;
  if (created) map[@"created"] = created;

  id rawTags = raw[@"tags"];
  if ([rawTags isKindOfClass:NSArray.class]) {
    NSMutableArray<NSString *> *tags = [NSMutableArray array];
    for (id rawTag in (NSArray *)rawTags) {
      NSString *tag = NosmaiIOSNonEmptyString(rawTag);
      if (tag) [tags addObject:tag];
    }
    map[@"tags"] = [tags copy];
  }
  return [map copy];
}

- (void)finishCloudDownloadForIdentifier:(NSString *)filterId
                               generation:(NSUInteger)generation
                                    value:(id)value
                                     code:(NSString *)code
                                  message:(NSString *)message
                                    error:(NSError *)error {
  NSAssert(NSThread.isMainThread,
           @"Cloud download promises must settle on the main thread");
  NSNumber *activeGeneration = _cloudDownloadGenerations[filterId];
  if (activeGeneration.unsignedIntegerValue != generation) return;

  NSArray *completions = [_cloudDownloadCompletions[filterId] copy];
  [_cloudDownloadCompletions removeObjectForKey:filterId];
  [_cloudDownloadGenerations removeObjectForKey:filterId];
  for (NosmaiIOSCompletion pendingCompletion in completions) {
    pendingCompletion(value, code, message, error);
  }
}

- (BOOL)ensureVisualMutationReadyForCompletion:
    (NosmaiIOSCompletion)completion {
  NSAssert(NSThread.isMainThread,
           @"Visual mutation state must be checked on the main thread");
  if (![self ensureInitializedForCompletion:completion]) return NO;
  if (!_processing || !_nativeProcessingActive || _starting || _stopping ||
      _resumeInProgress || _manualPaused || _hostPaused) {
    completion(nil, NosmaiIOSErrorInvalidState,
               @"Start processing and keep the app active before changing visual controls",
               nil);
    return NO;
  }
  if (![NosmaiCore shared].effects) {
    NSError *error = NosmaiIOSError(
        NosmaiIOSErrorVisualControl,
        @"The native visual-effects engine is unavailable", nil);
    completion(nil, NosmaiIOSErrorVisualControl, error.localizedDescription,
               error);
    return NO;
  }
  return YES;
}

- (BOOL)ensureVisualFeaturesRequiringBeauty:(BOOL)requiresBeauty
                                    advanced:(BOOL)requiresAdvanced
                                  completion:(NosmaiIOSCompletion)completion {
  NSAssert(NSThread.isMainThread,
           @"Visual feature checks must run on the main thread");
  @try {
    NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
    BOOL licenseValid = [_lastLicenseStatus isEqualToString:@"valid"];
    if (requiresBeauty &&
        (!licenseValid || ![effects isBeautyEffectEnabled])) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorBeautyDisabled,
          @"Beauty effects are not enabled for this SDK license", nil);
      completion(nil, NosmaiIOSErrorBeautyDisabled,
                 error.localizedDescription, error);
      return NO;
    }
    if (requiresAdvanced &&
        (!licenseValid || ![effects isAdvancedFiltersEnabled])) {
      NSError *error = NosmaiIOSError(
          NosmaiIOSErrorAdvancedFiltersDisabled,
          @"Advanced filters are not enabled for this SDK license", nil);
      completion(nil, NosmaiIOSErrorAdvancedFiltersDisabled,
                 error.localizedDescription, error);
      return NO;
    }
  } @catch (__unused NSException *exception) {
    NSError *error = NosmaiIOSError(
        NosmaiIOSErrorVisualControl,
        @"Unable to verify visual-control availability", nil);
    completion(nil, NosmaiIOSErrorVisualControl, error.localizedDescription,
               error);
    return NO;
  }
  return YES;
}

- (void)enqueueVisualMutationRequiringBeauty:(BOOL)requiresBeauty
                                     advanced:(BOOL)requiresAdvanced
                                     keepAlive:(id)keepAlive
                                      mutation:(NosmaiIOSVisualMutation)mutation
                                    afterFence:(dispatch_block_t)afterFence
                                    completion:(NosmaiIOSCompletion)completion {
  NSAssert(NSThread.isMainThread,
           @"Visual mutations must be enqueued on the main thread");
  if (![self ensureVisualMutationReadyForCompletion:completion] ||
      ![self ensureVisualFeaturesRequiringBeauty:requiresBeauty
                                        advanced:requiresAdvanced
                                      completion:completion]) {
    return;
  }

  [self enqueueEffectWork:^(NosmaiIOSCompletion finish) {
    [self performVisualMutationAtFIFOTurnRequiringBeauty:requiresBeauty
                                                advanced:requiresAdvanced
                                                keepAlive:keepAlive
                                                 mutation:mutation
                                               afterFence:afterFence
                                                   finish:finish];
  }
                  completion:completion];
}

- (void)performVisualMutationAtFIFOTurnRequiringBeauty:
            (BOOL)requiresBeauty
                                                   advanced:
                                                       (BOOL)requiresAdvanced
                                                   keepAlive:(id)keepAlive
                                                    mutation:
                                                        (NosmaiIOSVisualMutation)mutation
                                                  afterFence:
                                                      (dispatch_block_t)afterFence
                                                      finish:
                                                          (NosmaiIOSCompletion)finish {
  NSAssert(NSThread.isMainThread,
           @"Visual mutations must begin on the main thread");
  if (![self ensureVisualMutationReadyForCompletion:finish] ||
      ![self ensureVisualFeaturesRequiringBeauty:requiresBeauty
                                        advanced:requiresAdvanced
                                      completion:finish]) {
    return;
  }

  NosmaiEffectsEngine *effects = [NosmaiCore shared].effects;
  NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
  @try {
    mutation(effects, sdk);
  } @catch (__unused NSException *exception) {
    NSError *error = NosmaiIOSError(
        NosmaiIOSErrorVisualControl,
        @"The native visual control could not be changed", nil);
    finish(nil, NosmaiIOSErrorVisualControl, error.localizedDescription,
           error);
    return;
  }

  dispatch_async(_cameraQueue, ^{
    __block NSError *fenceError = nil;
    @try {
      (void)keepAlive;
      [effects performEffectQueueSync:^{}];
    } @catch (__unused NSException *exception) {
      fenceError = NosmaiIOSError(
          NosmaiIOSErrorVisualControl,
          @"Unable to confirm the native visual-control change", nil);
    }
    dispatch_async(dispatch_get_main_queue(), ^{
      if (fenceError) {
        finish(nil, NosmaiIOSErrorVisualControl,
               fenceError.localizedDescription, fenceError);
        return;
      }
      @try {
        if (afterFence) afterFence();
        [self emitCurrentPipelineState];
        finish(nil, nil, nil, nil);
      } @catch (__unused NSException *exception) {
        NSError *error = NosmaiIOSError(
            NosmaiIOSErrorVisualControl,
            @"Unable to finalize the visual-control change", nil);
        finish(nil, NosmaiIOSErrorVisualControl,
               error.localizedDescription, error);
      }
    });
  });
}

- (NSURL *)strictLocalFileURLFromInput:(NSString *)input {
  if (![input isKindOfClass:NSString.class]) return nil;
  NSString *value = [input stringByTrimmingCharactersInSet:
      NSCharacterSet.whitespaceAndNewlineCharacterSet];
  if (value.length == 0 ||
      ![value.lowercaseString hasPrefix:@"file://"]) {
    return nil;
  }
  NSURL *url = [NSURL URLWithString:value];
  NSString *host = url.host;
  if (!url.isFileURL || url.path.length == 0 || url.query.length > 0 ||
      url.fragment.length > 0 || url.user.length > 0 ||
      url.password.length > 0 || url.port != nil ||
      (host.length > 0 &&
       ![host.lowercaseString isEqualToString:@"localhost"])) {
    return nil;
  }
  NSString *path = url.path.stringByStandardizingPath;
  if (!path.isAbsolutePath || [path containsString:@"\0"]) return nil;
  return [NSURL fileURLWithPath:path];
}

- (UIImage *)downsampledBackgroundImageAtURL:(NSURL *)url {
  NSAssert(!NSThread.isMainThread,
           @"Background image decoding must not run on the main thread");
  NSString *path = url.path.stringByResolvingSymlinksInPath;
  BOOL isDirectory = NO;
  if (path.length == 0 ||
      ![NSFileManager.defaultManager fileExistsAtPath:path
                                          isDirectory:&isDirectory] ||
      isDirectory ||
      ![NSFileManager.defaultManager isReadableFileAtPath:path]) {
    return nil;
  }
  NSDictionary *attributes =
      [NSFileManager.defaultManager attributesOfItemAtPath:path error:nil];
  unsigned long long fileSize = [attributes[NSFileSize] unsignedLongLongValue];
  if (fileSize == 0 || fileSize > NosmaiIOSMaxBackgroundImageBytes) return nil;

  NSDictionary *sourceOptions = @{(id)kCGImageSourceShouldCache : @NO};
  CGImageSourceRef source = CGImageSourceCreateWithURL(
      (__bridge CFURLRef)[NSURL fileURLWithPath:path],
      (__bridge CFDictionaryRef)sourceOptions);
  if (!source || CGImageSourceGetCount(source) == 0) {
    if (source) CFRelease(source);
    return nil;
  }

  CFStringRef sourceIdentifier = CGImageSourceGetType(source);
  UTType *sourceType = sourceIdentifier
      ? [UTType typeWithIdentifier:(__bridge NSString *)sourceIdentifier]
      : nil;
  NSDictionary *properties = CFBridgingRelease(
      CGImageSourceCopyPropertiesAtIndex(source, 0, nil));
  double width = [properties[(id)kCGImagePropertyPixelWidth] doubleValue];
  double height = [properties[(id)kCGImagePropertyPixelHeight] doubleValue];
  BOOL validDimensions = std::isfinite(width) && std::isfinite(height) &&
      width > 0.0 && height > 0.0 &&
      width <= NosmaiIOSMaxBackgroundImageSourceDimension &&
      height <= NosmaiIOSMaxBackgroundImageSourceDimension &&
      width * height <= NosmaiIOSMaxBackgroundImageSourcePixels;
  if (!sourceType || ![sourceType conformsToType:UTTypeImage] ||
      !validDimensions) {
    CFRelease(source);
    return nil;
  }

  NSDictionary *thumbnailOptions = @{
    (id)kCGImageSourceCreateThumbnailFromImageAlways : @YES,
    (id)kCGImageSourceCreateThumbnailWithTransform : @YES,
    (id)kCGImageSourceShouldCacheImmediately : @YES,
    (id)kCGImageSourceThumbnailMaxPixelSize :
        @(NosmaiIOSBackgroundImageTargetDimension),
  };
  CGImageRef thumbnail = CGImageSourceCreateThumbnailAtIndex(
      source, 0, (__bridge CFDictionaryRef)thumbnailOptions);
  CFRelease(source);
  if (!thumbnail) return nil;
  UIImage *image = [UIImage imageWithCGImage:thumbnail
                                       scale:UIScreen.mainScreen.scale
                                 orientation:UIImageOrientationUp];
  CGImageRelease(thumbnail);
  return image.size.width > 0.0 && image.size.height > 0.0 ? image : nil;
}

- (BOOL)isSupportedBackgroundVideoAtURL:(NSURL *)url {
  NSAssert(!NSThread.isMainThread,
           @"Background video validation must not run on the main thread");
  NSString *path = url.path.stringByResolvingSymlinksInPath;
  BOOL isDirectory = NO;
  if (path.length == 0 ||
      ![NSFileManager.defaultManager fileExistsAtPath:path
                                          isDirectory:&isDirectory] ||
      isDirectory ||
      ![NSFileManager.defaultManager isReadableFileAtPath:path]) {
    return NO;
  }
  NSDictionary *attributes =
      [NSFileManager.defaultManager attributesOfItemAtPath:path error:nil];
  if ([attributes[NSFileSize] unsignedLongLongValue] == 0) return NO;
  NSSet<NSString *> *supportedExtensions = [NSSet setWithArray:@[
    @"mp4", @"mov", @"m4v"
  ]];
  if (![supportedExtensions containsObject:path.pathExtension.lowercaseString]) {
    return NO;
  }

  AVURLAsset *asset =
      [AVURLAsset URLAssetWithURL:[NSURL fileURLWithPath:path] options:nil];
  NSArray<AVAssetTrack *> *videoTracks =
      [asset tracksWithMediaType:AVMediaTypeVideo];
  double duration = CMTimeGetSeconds(asset.duration);
  return asset.playable && !asset.hasProtectedContent && videoTracks.count > 0 &&
      std::isfinite(duration) && duration > 0.0;
}

- (NSString *)normalizedPackagePath:(NSString *)input
                    requireReadable:(BOOL)requireReadable {
  if (![input isKindOfClass:NSString.class]) return nil;
  NSString *value =
      [input stringByTrimmingCharactersInSet:
                 NSCharacterSet.whitespaceAndNewlineCharacterSet];
  if (value.length == 0) return nil;

  NSString *path = value;
  NSString *sandboxRoot = nil;
  NSString *lowercaseValue = value.lowercaseString;
  NSString *documentsPrefix = @"nosmai-documents:///";
  if ([lowercaseValue hasPrefix:documentsPrefix]) {
    NSString *relativePath =
        [value substringFromIndex:documentsPrefix.length];
    NSString *decodedRelativePath =
        [relativePath stringByRemovingPercentEncoding];
    if (!decodedRelativePath) return nil;
    relativePath = decodedRelativePath;
    relativePath = relativePath.stringByStandardizingPath;
    if (relativePath.length == 0 || relativePath.isAbsolutePath ||
        [relativePath isEqualToString:@".."] ||
        [relativePath hasPrefix:@"../"]) {
      return nil;
    }
    sandboxRoot = [NSSearchPathForDirectoriesInDomains(
        NSDocumentDirectory, NSUserDomainMask, YES) firstObject];
    if (sandboxRoot.length == 0) return nil;
    sandboxRoot = sandboxRoot.stringByStandardizingPath;
    path = [[sandboxRoot stringByAppendingPathComponent:relativePath]
        stringByStandardizingPath];
  } else {
    if ([lowercaseValue hasPrefix:@"file://"]) {
      NSURL *url = [NSURL URLWithString:value];
      NSString *host = url.host;
      if (!url.isFileURL || url.path.length == 0 ||
          (host.length > 0 &&
           ![host.lowercaseString isEqualToString:@"localhost"])) {
        return nil;
      }
      // NSURL.path has already decoded the URL exactly once.
      path = url.path;
    }
    path = [path stringByStandardizingPath];
  }
  if (![path isAbsolutePath] ||
      ![path.pathExtension.lowercaseString isEqualToString:@"nosmai"]) {
    return nil;
  }

  BOOL isDirectory = NO;
  BOOL exists = [NSFileManager.defaultManager fileExistsAtPath:path
                                                   isDirectory:&isDirectory];
  if (exists) {
    path = path.stringByResolvingSymlinksInPath;
  }
  if (sandboxRoot) {
    sandboxRoot = sandboxRoot.stringByResolvingSymlinksInPath;
    NSString *sandboxPrefix = [sandboxRoot stringByAppendingString:@"/"];
    if (![path hasPrefix:sandboxPrefix]) return nil;
  }
  if (![path.pathExtension.lowercaseString isEqualToString:@"nosmai"]) {
    return nil;
  }
  if (requireReadable &&
      (!exists || isDirectory ||
       ![NSFileManager.defaultManager isReadableFileAtPath:path])) {
    return nil;
  }
  return path;
}

- (BOOL)pathsEqual:(NSString *)first other:(NSString *)second {
  if (first.length == 0 || second.length == 0) return NO;
  NSString *firstPath = [self normalizedPackagePath:first requireReadable:NO];
  NSString *secondPath = [self normalizedPackagePath:second requireReadable:NO];
  return firstPath && secondPath && [firstPath isEqualToString:secondPath];
}

- (NSDictionary *)filterInfoMap:(NosmaiFilterInfo *)info
                    fallbackPath:(NSString *)fallbackPath
                    fallbackType:(NSString *)fallbackType {
  NSString *path = info.path.length > 0 ? info.path : fallbackPath;
  if (path.length == 0) return nil;

  NSMutableDictionary *map = info
      ? [[info dictionaryRepresentation] mutableCopy]
      : [NSMutableDictionary dictionary];
  NSString *name = info.displayName.length > 0
      ? info.displayName
      : [[path lastPathComponent] stringByDeletingPathExtension];
  NSString *type = info.typeKey.length > 0 ? info.typeKey : fallbackType;
  if (name.length == 0) name = @"effect";
  if (type.length == 0) type = @"effect";

  NSString *identifier =
      [map[@"id"] isKindOfClass:NSString.class] &&
              [((NSString *)map[@"id"]) length] > 0
          ? map[@"id"]
          : path;
  map[@"id"] = identifier;
  map[@"name"] = map[@"name"] ?: name;
  map[@"displayName"] = map[@"displayName"] ?: name;
  map[@"path"] = path;
  map[@"effectPath"] = path;
  map[@"filterType"] = type;
  map[@"isDownloaded"] = map[@"isDownloaded"] ?: @YES;
  if (!map[@"type"]) map[@"type"] = @"local";
  return map;
}

- (void)enqueueEffectWork:(NosmaiIOSEffectWork)work
                completion:(NosmaiIOSCompletion)completion {
  NSAssert(NSThread.isMainThread,
           @"Effect operations must be enqueued on the main thread");
  dispatch_block_t operation = ^{
    __block BOOL finished = NO;
    NosmaiIOSCompletion finish =
        ^(id value, NSString *code, NSString *message, NSError *error) {
      NosmaiIOSRunOnMain(^{
        if (finished) return;
        finished = YES;
        @try {
          completion(value, code, message, error);
        } @finally {
          [self finishActiveEffectOperation];
        }
      });
    };

    @try {
      work(finish);
    } @catch (NSException *exception) {
      NSError *error = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorEffectState,
          @"The native effect operation failed");
      finish(nil, NosmaiIOSErrorEffectState, error.localizedDescription,
             error);
    }
  };
  [_effectOperations addObject:[operation copy]];
  [self runNextEffectOperation];
}

- (void)runNextEffectOperation {
  NSAssert(NSThread.isMainThread,
           @"Effect operations must run on the main thread");
  if (_effectOperationActive) return;

  if (_effectOperations.count > 0) {
    dispatch_block_t operation = _effectOperations.firstObject;
    [_effectOperations removeObjectAtIndex:0];
    _effectOperationActive = YES;
    operation();
    return;
  }

  NSArray *drainCompletions = [_effectDrainCompletions copy];
  [_effectDrainCompletions removeAllObjects];
  for (dispatch_block_t completion in drainCompletions) {
    completion();
  }
}

- (void)finishActiveEffectOperation {
  NSAssert(NSThread.isMainThread,
           @"Effect operations must finish on the main thread");
  if (!_effectOperationActive) return;
  _effectOperationActive = NO;
  [self runNextEffectOperation];
}

- (void)whenEffectOperationsDrained:(dispatch_block_t)completion {
  NSAssert(NSThread.isMainThread,
           @"Effect drain barriers must run on the main thread");
  if (!_effectOperationActive && _effectOperations.count == 0) {
    completion();
    return;
  }
  [_effectDrainCompletions addObject:[completion copy]];
}

- (void)waitForPipelineUntil:(NSTimeInterval)deadline
                   condition:(NosmaiIOSPipelineCondition)condition
                  completion:(NosmaiIOSCompletion)completion {

  @try {
    NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
    NosmaiPipelineState *state =
        [[NosmaiCore shared].effects currentPipelineState];
    if (sdk && state && condition(state, sdk)) {
      completion(nil, nil, nil, nil);
      return;
    }
  } @catch (NSException *exception) {
    NSError *error = NosmaiIOSErrorFromException(
        exception, NosmaiIOSErrorEffectClear,
        @"Unable to verify the native pipeline state");
    completion(nil, NosmaiIOSErrorEffectClear, error.localizedDescription,
               error);
    return;
  }

  if (NSProcessInfo.processInfo.systemUptime >= deadline) {
    completion(nil, NosmaiIOSErrorEffectClear,
               @"Timed out waiting for the native pipeline state", nil);
    return;
  }

  dispatch_after(dispatch_time(DISPATCH_TIME_NOW,
                               (int64_t)(0.025 * NSEC_PER_SEC)),
                 dispatch_get_main_queue(), ^{
    [self waitForPipelineUntil:deadline
                     condition:condition
                    completion:completion];
  });
}

- (BOOL)ensureNotCleaningForCompletion:(NosmaiIOSCompletion)completion {
  if (_cleanupInProgress) {
    completion(nil, NosmaiIOSErrorOperationCancelled,
               @"Session cleanup is in progress", nil);
    return NO;
  }
  return YES;
}

- (BOOL)ensureInitializedForCompletion:(NosmaiIOSCompletion)completion {
  if (![self ensureNotCleaningForCompletion:completion]) {
    return NO;
  }
  if (!_initialized || ![NosmaiCore shared].isInitialized ||
      ![NosmaiSDK sharedInstance]) {
    completion(nil, NosmaiIOSErrorNotInitialized,
               @"Call initialize before using the Nosmai SDK", nil);
    return NO;
  }
  return YES;
}

- (void)requestCameraAuthorization:(void (^)(BOOL authorized))completion {
  AVAuthorizationStatus status =
      [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
  if (status == AVAuthorizationStatusAuthorized) {
    completion(YES);
  } else if (status == AVAuthorizationStatusNotDetermined) {
    [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo
                             completionHandler:^(BOOL granted) {
      NosmaiIOSRunOnMain(^{ completion(granted); });
    }];
  } else {
    completion(NO);
  }
}

- (void)attachCurrentPreview {
  NSAssert(NSThread.isMainThread, @"Preview attachment must run on main");
  if (!_initialized || !_previewSink || !_previewContainer) {
    return;
  }
  @try {
    [[NosmaiCore shared].camera attachToView:_previewContainer];
    [self applyPreviewPresentation];
  } @catch (NSException *exception) {
    [self emitAsyncErrorWithCode:NosmaiIOSErrorNoPreview
                         message:exception.reason ?:
                                 @"Unable to attach the Nosmai preview"
                           error:nil
                      cameraView:YES];
  }
}

- (void)applyPreviewPresentation {
  UIView *container = _previewContainer;
  if (!container) return;
  BOOL nativeMirrored = [_desiredPosition isEqualToString:@"front"];
  BOOL requiresDisplayFlip = _desiredMirror != nativeMirrored;
  container.transform = requiresDisplayFlip
      ? CGAffineTransformMakeScale(-1.0, 1.0)
      : CGAffineTransformIdentity;
}

- (void)resetCameraLightModes {
  NSAssert(NSThread.isMainThread,
           @"Camera light state must be reset on the main thread");
  _currentFlashMode = AVCaptureFlashModeOff;
  _currentTorchMode = AVCaptureTorchModeOff;
  NosmaiCamera *camera = [NosmaiCore shared].camera;
  if (!camera) return;
  dispatch_async(_cameraQueue, ^{
    @try {
      [camera setTorchMode:AVCaptureTorchModeOff];
      [camera setFlashMode:AVCaptureFlashModeOff];
    } @catch (__unused NSException *exception) {
      // Camera stop/switch is authoritative and will also extinguish the unit.
    }
  });
}

- (void)configureNativeCameraForCurrentSettingsWithCompletion:
    (NosmaiIOSCompletion _Nullable)completion {
  if (!_initialized || ![NosmaiCore shared].camera) {
    if (completion) completion(nil, nil, nil, nil);
    return;
  }

  NSString *position = [_desiredPosition copy];
  NSString *preset = [_sessionPreset copy];
  BOOL mirror = _desiredMirror;
  NSUInteger operationSession = _sessionGeneration;
  NosmaiCamera *camera = [NosmaiCore shared].camera;
  dispatch_async(_cameraQueue, ^{
    __block NSError *failure = nil;
    @try {
      NosmaiCameraConfig *config = [[NosmaiCameraConfig alloc] init];
      config.position = [position isEqualToString:@"back"]
          ? NosmaiCameraPositionBack
          : NosmaiCameraPositionFront;
      config.sessionPreset = preset;
      config.frameRate = 30;
      config.orientation = NosmaiVideoOrientationPortrait;
      config.enableMirroring = mirror;
      [camera updateConfiguration:config];
    } @catch (NSException *exception) {
      failure = NosmaiIOSErrorFromException(
          exception, NosmaiIOSErrorCameraDevice,
          @"Unable to configure the camera");
    }
    dispatch_async(dispatch_get_main_queue(), ^{
      if (operationSession != self->_sessionGeneration) {
        if (completion) {
          completion(nil, NosmaiIOSErrorOperationCancelled,
                     @"Camera configuration was cancelled by cleanup", nil);
        }
        return;
      }
      if (failure) {
        if (completion) {
          completion(nil, NosmaiIOSErrorCameraDevice,
                     failure.localizedDescription, failure);
        } else {
          [self emitAsyncErrorWithCode:NosmaiIOSErrorCameraDevice
                               message:failure.localizedDescription
                                 error:failure
                            cameraView:YES];
        }
        return;
      }
      if (self->_processing && !self->_manualPaused && !self->_hostPaused) {
        [self armProcessedFrameReadiness];
      }
      if (completion) completion(nil, nil, nil, nil);
    });
  });
}

- (void)armProcessedFrameReadiness {
  NSAssert(NSThread.isMainThread, @"Readiness must be armed on main");
  if (!_initialized || !_processing || !_nativeProcessingActive ||
      !_previewSink) {
    return;
  }
  if (!_liveFrameDispatcherInstalled) {
    [self emitAsyncErrorWithCode:@"E_NATIVE_FAILURE"
                         message:@"The processed-frame dispatcher is unavailable"
                           error:nil
                      cameraView:YES];
    return;
  }
  _readyDelivered = NO;
  _processedFrameReadinessArmed = YES;
  ++_readyGeneration;
  _readyNotBeforeTimestampAtomic.store(
      NSProcessInfo.processInfo.systemUptime, std::memory_order_release);
  _readyGenerationAtomic.store((uint64_t)_readyGeneration,
                               std::memory_order_release);
  _processedFrameReadinessArmedAtomic.store(true,
                                            std::memory_order_release);
  [self updateLiveFrameOutputDemand];
}

- (void)updateLiveFrameCallback {
  NSAssert(NSThread.isMainThread, @"Frame callback ownership must run on main");
  if (_liveFrameDispatcherInstalled || !_initialized) return;
  if (_nativeProcessingActive) {
    // Swapping this SDK-owned block while frames are in flight is unsafe.
    // Every camera start/resume path installs it before native processing.
    return;
  }

  __weak NosmaiIOSController *weakSelf = self;
  [NosmaiCore shared].liveFrameStreamCallback =
      ^(CVPixelBufferRef pixelBuffer, double timestamp) {
    __strong NosmaiIOSController *callbackSelf = weakSelf;
    if (!callbackSelf) return;
    NSUInteger frameStreamGeneration = callbackSelf->_frameStream.generation;
    [callbackSelf->_frameStream acceptPixelBuffer:pixelBuffer
                                         timestamp:timestamp
                                callbackGeneration:frameStreamGeneration];
    if (!callbackSelf->_processedFrameReadinessArmedAtomic.load(
            std::memory_order_acquire)) {
      return;
    }
    uint64_t readyGeneration = callbackSelf->_readyGenerationAtomic.load(
        std::memory_order_acquire);
    double notBeforeTimestamp =
        callbackSelf->_readyNotBeforeTimestampAtomic.load(
            std::memory_order_acquire);
    if (!std::isfinite(timestamp) || timestamp < notBeforeTimestamp) {
      return;
    }
    dispatch_async(dispatch_get_main_queue(), ^{
      __strong NosmaiIOSController *strongSelf = weakSelf;
      if (!strongSelf || !strongSelf->_processedFrameReadinessArmed ||
          strongSelf->_readyDelivered ||
          readyGeneration != (uint64_t)strongSelf->_readyGeneration ||
          !strongSelf->_processing || strongSelf->_manualPaused ||
          strongSelf->_hostPaused || !strongSelf->_previewSink) {
        return;
      }
      strongSelf->_processedFrameReadinessArmed = NO;
      strongSelf->_processedFrameReadinessArmedAtomic.store(
          false, std::memory_order_release);
      strongSelf->_readyDelivered = YES;
      [strongSelf updateLiveFrameOutputDemand];
      [strongSelf->_previewSink nosmaiControllerDidRenderFirstFrame];
    });
  };
  _liveFrameDispatcherInstalled = YES;
  [self updateLiveFrameOutputDemand];
}

- (void)updateLiveFrameOutputDemand {
  NSAssert(NSThread.isMainThread,
           @"Frame output demand must be updated on main");
  if (!_initialized) return;
  BOOL enabled = _processedFrameReadinessArmed || _frameStream.isActive;
  @try {
    // Recording demand is ORed internally by NosmaiSDK, so disabling this
    // live-output request cannot interrupt an active native recording.
    [[NosmaiSDK sharedInstance] setLiveFrameOutputEnabled:enabled];
  } @catch (__unused NSException *exception) {
  }
}

- (void)invalidateProcessedFrameReadiness {
  _readyGeneration++;
  _processedFrameReadinessArmed = NO;
  _processedFrameReadinessArmedAtomic.store(false,
                                            std::memory_order_release);
  _readyGenerationAtomic.store((uint64_t)_readyGeneration,
                               std::memory_order_release);
  _readyNotBeforeTimestampAtomic.store(DBL_MAX, std::memory_order_release);
  _readyDelivered = NO;
  [self updateLiveFrameOutputDemand];
}

- (void)stopFrameStreamInternal {
  [_frameStream stop];
  [self updateLiveFrameOutputDemand];
}

- (void)clearLiveFrameCallbackAfterProcessingStopped {
  NSAssert(NSThread.isMainThread, @"Frame callback cleanup must run on main");
  if (!_liveFrameDispatcherInstalled) return;
  if (_nativeProcessingActive || _starting || _resumeInProgress) return;
  @try {
    [[NosmaiSDK sharedInstance] setLiveFrameOutputEnabled:NO];
  } @catch (__unused NSException *exception) {
  }
  [NosmaiCore shared].liveFrameStreamCallback = nil;
  _liveFrameDispatcherInstalled = NO;
}

- (NSDictionary *)currentPipelineStateMap {
  if (!_initialized || ![NosmaiCore shared].effects) {
    return nil;
  }
  @try {
    return [self pipelineStateMap:[[NosmaiCore shared].effects currentPipelineState]];
  } @catch (__unused NSException *exception) {
    return nil;
  }
}

- (NSDictionary *)pipelineStateMap:(NosmaiPipelineState *)state {
  if (!state) return nil;
  NSMutableDictionary *map = [[state dictionaryRepresentation] mutableCopy];
  id backgroundPath = map[@"activeBackgroundPackagePath"] ?: NSNull.null;
  map[@"activeBackgroundPath"] = backgroundPath;
  map[@"hasBackground"] = map[@"backgroundActive"] ?: @NO;
  map[@"hasManualBackground"] =
      map[@"hasManualBackgroundConfig"] ?: @NO;

  NosmaiSDK *sdk = [NosmaiSDK sharedInstance];
  NosmaiFilterInfo *filterInfo = [sdk activeFilterInfo];
  NosmaiFilterInfo *effectInfo = [sdk activeEffectInfo];
  NSString *effectType = [map[@"activeEffectType"] isKindOfClass:NSString.class]
      ? map[@"activeEffectType"]
      : @"effect";
  map[@"activeFilterInfo"] =
      [self filterInfoMap:filterInfo
             fallbackPath:state.activeFilterPath
             fallbackType:@"filter"] ?: NSNull.null;
  map[@"activeEffectInfo"] =
      [self filterInfoMap:effectInfo
             fallbackPath:state.activeEffectPath
             fallbackType:effectType] ?: NSNull.null;
  BOOL hasTrackedEyeColor = _eyeColorConfigured;
  map[@"hasBuiltInBeauty"] =
      @([sdk hasActiveBuiltInFilters] || hasTrackedEyeColor);
  if (hasTrackedEyeColor) {
    map[@"hasBeautyEffect"] = @YES;
  }
  if ([effectType.lowercaseString isEqualToString:@"beauty_effect"] ||
      [effectInfo.typeKey.lowercaseString isEqualToString:@"beauty_effect"]) {
    map[@"hasBeautyEffect"] = @YES;
  }
  return map;
}

- (void)emitCurrentPipelineState {
  NSDictionary *state = [self currentPipelineStateMap];
  if (state) {
    [_moduleSink nosmaiControllerDidChangeActiveEffects:state];
  }
}

- (void)emitLicenseStatus:(NSString *)status {
  _lastLicenseStatus = status;
  [_moduleSink nosmaiControllerDidChangeLicenseStatus:status];
}

- (void)emitAsyncErrorWithCode:(NSString *)code
                       message:(NSString *)message
                         error:(NSError *_Nullable)error
                    cameraView:(BOOL)cameraView {
  NSMutableDictionary *payload = [@{
    @"code" : code,
    @"message" : message,
  } mutableCopy];
  if (error) {
    payload[@"details"] = @{
      @"domain" : error.domain ?: @"",
      @"nativeCode" : @(error.code),
    };
  }
  [_moduleSink nosmaiControllerDidReceiveError:payload];
  if (cameraView) {
    [_previewSink nosmaiControllerDidReceiveCameraErrorWithCode:code
                                                        message:message];
  }
}

- (void)finishCompletions:(NSMutableArray *)completions
                     value:(id _Nullable)value
                      code:(NSString *_Nullable)code
                   message:(NSString *_Nullable)message
                     error:(NSError *_Nullable)error {
  NSArray *pending = [completions copy];
  [completions removeAllObjects];
  for (NosmaiIOSCompletion callback in pending) {
    callback(value, code, message, error);
  }
}

@end
