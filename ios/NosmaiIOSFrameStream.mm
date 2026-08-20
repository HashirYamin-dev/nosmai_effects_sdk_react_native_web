#import "NosmaiIOSFrameStream.h"

#import <UIKit/UIKit.h>

#include <cfloat>
#include <climits>
#include <cmath>

static NSString *const NosmaiIOSFrameErrorInvalidState = @"E_INVALID_STATE";
static NSString *const NosmaiIOSFrameErrorCancelled = @"E_OPERATION_CANCELLED";
static NSString *const NosmaiIOSFrameErrorStream = @"E_FRAME_STREAM";
static const NSUInteger NosmaiIOSFrameMaximumBytes = 8 * 1024 * 1024;

@interface NosmaiIOSFrameStream ()
- (void)reportUnsupportedOnce:(NSString *)message;
@end

@implementation NosmaiIOSFrameStream {
  dispatch_queue_t _encodingQueue;
  BOOL _active;
  BOOL _readInProgress;
  BOOL _reportedUnsupportedFrame;
  NSUInteger _generation;
  NSUInteger _sequence;
  NSUInteger _droppedFrames;
  NSTimeInterval _minimumInterval;
  NSTimeInterval _lastAcceptedAt;
  NSTimeInterval _notBeforeTimestamp;
  NSData *_latestData;
  NSDictionary *_latestMetadata;
  NosmaiIOSFrameMetadataHandler _metadataHandler;
  NosmaiIOSFrameErrorHandler _errorHandler;
}

- (instancetype)init {
  self = [super init];
  if (self) {
    _encodingQueue = dispatch_queue_create(
        "com.nosmai.reactnative.frame-encoding.ios", DISPATCH_QUEUE_SERIAL);
    _lastAcceptedAt = -DBL_MAX;
  }
  return self;
}

- (BOOL)isActive {
  @synchronized(self) {
    return _active;
  }
}

- (NSUInteger)generation {
  @synchronized(self) {
    return _generation;
  }
}

- (void)startWithMaxFramesPerSecond:(NSUInteger)maxFramesPerSecond
                    metadataHandler:(NosmaiIOSFrameMetadataHandler)metadataHandler
                       errorHandler:(NosmaiIOSFrameErrorHandler)errorHandler {
  @synchronized(self) {
    if (_active) {
      @throw [NSException exceptionWithName:NSInternalInconsistencyException
                                     reason:@"The processed frame stream is already active"
                                   userInfo:nil];
    }
    _generation++;
    _active = YES;
    _readInProgress = NO;
    _reportedUnsupportedFrame = NO;
    _sequence = 0;
    _droppedFrames = 0;
    _minimumInterval = 1.0 / MAX((NSUInteger)1, maxFramesPerSecond);
    _lastAcceptedAt = -DBL_MAX;
    // The SDK timestamps processed frames on the host clock. This fence keeps
    // a delayed callback from a previous camera/session generation out of a
    // newly started logical stream.
    _notBeforeTimestamp = NSProcessInfo.processInfo.systemUptime;
    _latestData = nil;
    _latestMetadata = nil;
    _metadataHandler = [metadataHandler copy];
    _errorHandler = [errorHandler copy];
  }
}

- (void)stop {
  @synchronized(self) {
    _generation++;
    _active = NO;
    _readInProgress = NO;
    _latestData = nil;
    _latestMetadata = nil;
    _metadataHandler = nil;
    _errorHandler = nil;
    _lastAcceptedAt = -DBL_MAX;
    _notBeforeTimestamp = DBL_MAX;
  }
}

- (void)acceptPixelBuffer:(CVPixelBufferRef)pixelBuffer
                 timestamp:(double)timestamp
        callbackGeneration:(NSUInteger)callbackGeneration {
  if (!pixelBuffer) return;
  if (!std::isfinite(timestamp) || timestamp <= 0.0) {
    [self reportUnsupportedOnce:@"The native frame timestamp is invalid"];
    return;
  }

  NSUInteger operationGeneration = 0;
  NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
  @synchronized(self) {
    if (!_active || _generation != callbackGeneration) return;
    if (timestamp < _notBeforeTimestamp) {
      _droppedFrames++;
      return;
    }
    if (_lastAcceptedAt > -DBL_MAX &&
        now - _lastAcceptedAt < _minimumInterval) {
      _droppedFrames++;
      return;
    }
    _lastAcceptedAt = now;
    operationGeneration = _generation;
  }

  size_t width = CVPixelBufferGetWidth(pixelBuffer);
  size_t height = CVPixelBufferGetHeight(pixelBuffer);
  if (width == 0 || height == 0 || width > INT_MAX || height > INT_MAX) {
    [self reportUnsupportedOnce:@"The native frame dimensions are invalid"];
    return;
  }

  OSType pixelFormat = CVPixelBufferGetPixelFormatType(pixelBuffer);
  NSString *format = nil;
  NSString *colorRange = @"unknown";
  NSUInteger expectedPlaneCount = 0;
  if (pixelFormat == kCVPixelFormatType_32BGRA) {
    format = @"bgra8888";
    colorRange = @"full";
    expectedPlaneCount = 1;
  } else if (pixelFormat == kCVPixelFormatType_420YpCbCr8BiPlanarFullRange) {
    format = @"nv12";
    colorRange = @"full";
    expectedPlaneCount = 2;
  } else if (pixelFormat == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange) {
    format = @"nv12";
    colorRange = @"video";
    expectedPlaneCount = 2;
  } else {
    [self reportUnsupportedOnce:@"The native CVPixelBuffer format is unsupported"];
    return;
  }

  CVReturn lockResult =
      CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
  if (lockResult != kCVReturnSuccess) {
    [self reportUnsupportedOnce:@"Unable to lock the native frame buffer"];
    return;
  }

  NSMutableArray *planes = [NSMutableArray arrayWithCapacity:expectedPlaneCount];
  NSMutableData *data = [NSMutableData data];
  BOOL valid = YES;
  NSString *failureMessage = nil;
  if (expectedPlaneCount == 1) {
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    if (bytesPerRow == 0 || width > NSUIntegerMax / 4 ||
        bytesPerRow < width * 4 || bytesPerRow > NSUIntegerMax / height) {
      valid = NO;
      failureMessage = @"The native BGRA frame stride is invalid";
    } else {
      size_t byteLength = bytesPerRow * height;
      void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
      if (!baseAddress || byteLength > NosmaiIOSFrameMaximumBytes) {
        valid = NO;
        failureMessage = @"The native frame exceeds the bridge safety limit";
      } else {
        [data appendBytes:baseAddress length:byteLength];
        [planes addObject:@{
          @"offset" : @0,
          @"byteLength" : @(byteLength),
          @"bytesPerRow" : @(bytesPerRow),
          @"width" : @(width),
          @"height" : @(height),
        }];
      }
    }
  } else {
    size_t nativePlaneCount = CVPixelBufferGetPlaneCount(pixelBuffer);
    if (nativePlaneCount != expectedPlaneCount) {
      valid = NO;
      failureMessage = @"The native NV12 frame does not contain two planes";
    } else {
      NSUInteger offset = 0;
      for (size_t index = 0; index < nativePlaneCount; index++) {
        size_t planeHeight = CVPixelBufferGetHeightOfPlane(pixelBuffer, index);
        size_t planeWidth = CVPixelBufferGetWidthOfPlane(pixelBuffer, index);
        size_t bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, index);
        size_t expectedPlaneWidth = index == 0 ? width : (width + 1) / 2;
        size_t expectedPlaneHeight = index == 0 ? height : (height + 1) / 2;
        size_t bytesPerPixel = index == 0 ? 1 : 2;
        if (planeHeight != expectedPlaneHeight ||
            planeWidth != expectedPlaneWidth || bytesPerRow == 0 ||
            planeWidth > NSUIntegerMax / bytesPerPixel ||
            bytesPerRow < planeWidth * bytesPerPixel ||
            bytesPerRow > NSUIntegerMax / planeHeight) {
          valid = NO;
          failureMessage = @"The native NV12 plane dimensions are invalid";
          break;
        }
        size_t byteLength = bytesPerRow * planeHeight;
        void *baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, index);
        if (!baseAddress || byteLength > NosmaiIOSFrameMaximumBytes - offset) {
          valid = NO;
          failureMessage = @"The native frame exceeds the bridge safety limit";
          break;
        }
        [data appendBytes:baseAddress length:byteLength];
        [planes addObject:@{
          @"offset" : @(offset),
          @"byteLength" : @(byteLength),
          @"bytesPerRow" : @(bytesPerRow),
          @"width" : @(planeWidth),
          @"height" : @(planeHeight),
        }];
        offset += byteLength;
      }
    }
  }
  CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);

  if (!valid) {
    [self reportUnsupportedOnce:failureMessage ?: @"The native frame is invalid"];
    return;
  }

  __block NSDictionary *metadata = nil;
  __block NosmaiIOSFrameMetadataHandler metadataHandler = nil;
  @synchronized(self) {
    if (!_active || _generation != operationGeneration) return;
    if (_latestData) _droppedFrames++;
    _sequence++;
    metadata = @{
      @"sequence" : @(_sequence),
      @"timestampSeconds" : @(timestamp),
      @"width" : @(width),
      @"height" : @(height),
      @"format" : format,
      @"colorRange" : colorRange,
      @"byteLength" : @(data.length),
      @"planes" : planes,
      @"droppedFrames" : @(_droppedFrames),
    };
    _latestData = [data copy];
    _latestMetadata = metadata;
    metadataHandler = [_metadataHandler copy];
  }

  if (metadataHandler) {
    dispatch_async(dispatch_get_main_queue(), ^{
      @synchronized(self) {
        if (!self->_active || self->_generation != operationGeneration) return;
      }
      metadataHandler(metadata);
    });
  }
}

- (void)takeLatestWithCompletion:(NosmaiIOSFrameReadCompletion)completion {
  __block NSData *data = nil;
  __block NSDictionary *metadata = nil;
  __block NSUInteger operationGeneration = 0;
  @synchronized(self) {
    if (!_active) {
      completion(nil, NosmaiIOSFrameErrorInvalidState,
                 @"Start the processed frame stream before reading frames", nil);
      return;
    }
    if (_readInProgress) {
      completion(nil, NosmaiIOSFrameErrorInvalidState,
                 @"A processed frame read is already in progress", nil);
      return;
    }
    if (!_latestData || !_latestMetadata) {
      completion(nil, nil, nil, nil);
      return;
    }
    data = _latestData;
    metadata = _latestMetadata;
    _latestData = nil;
    _latestMetadata = nil;
    _readInProgress = YES;
    operationGeneration = _generation;
  }

  dispatch_async(_encodingQueue, ^{
    NSString *encoded = [data base64EncodedStringWithOptions:0];
    dispatch_async(dispatch_get_main_queue(), ^{
      @synchronized(self) {
        if (!self->_active || self->_generation != operationGeneration) {
          completion(nil, NosmaiIOSFrameErrorCancelled,
                     @"The processed frame read was cancelled because the stream stopped",
                     nil);
          return;
        }
        self->_readInProgress = NO;
      }
      if (!encoded) {
        completion(nil, NosmaiIOSFrameErrorStream,
                   @"Unable to encode the processed frame", nil);
        return;
      }
      NSMutableDictionary *frame = [metadata mutableCopy];
      frame[@"dataBase64"] = encoded;
      completion(frame, nil, nil, nil);
    });
  });
}

- (void)reportUnsupportedOnce:(NSString *)message {
  __block NosmaiIOSFrameErrorHandler handler = nil;
  __block NSUInteger operationGeneration = 0;
  @synchronized(self) {
    if (!_active || _reportedUnsupportedFrame) return;
    _reportedUnsupportedFrame = YES;
    handler = [_errorHandler copy];
    operationGeneration = _generation;
  }
  if (handler) {
    dispatch_async(dispatch_get_main_queue(), ^{
      @synchronized(self) {
        if (!self->_active || self->_generation != operationGeneration) return;
      }
      handler(NosmaiIOSFrameErrorStream, message);
    });
  }
}

@end
