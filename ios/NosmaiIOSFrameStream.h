#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^NosmaiIOSFrameMetadataHandler)(NSDictionary *metadata);
typedef void (^NosmaiIOSFrameErrorHandler)(NSString *code, NSString *message);
typedef void (^NosmaiIOSFrameReadCompletion)(NSDictionary *_Nullable frame,
                                             NSString *_Nullable code,
                                             NSString *_Nullable message,
                                             NSError *_Nullable error);

/**
 * Latest-only processed-frame slot. Pixel bytes stay native until an explicit
 * read atomically consumes the slot, then base64 encoding runs off the main
 * queue.
 */
@interface NosmaiIOSFrameStream : NSObject

@property(nonatomic, readonly, getter=isActive) BOOL active;
@property(nonatomic, readonly) NSUInteger generation;

- (void)startWithMaxFramesPerSecond:(NSUInteger)maxFramesPerSecond
                    metadataHandler:(NosmaiIOSFrameMetadataHandler)metadataHandler
                       errorHandler:(NosmaiIOSFrameErrorHandler)errorHandler;
- (void)stop;
- (void)acceptPixelBuffer:(CVPixelBufferRef)pixelBuffer
                 timestamp:(double)timestamp
        callbackGeneration:(NSUInteger)callbackGeneration;
- (void)takeLatestWithCompletion:(NosmaiIOSFrameReadCompletion)completion;

@end

NS_ASSUME_NONNULL_END
