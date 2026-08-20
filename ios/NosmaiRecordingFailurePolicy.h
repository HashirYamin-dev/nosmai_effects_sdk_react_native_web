#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, NosmaiRecordingSettlementAdmission) {
  NosmaiRecordingSettlementAdmissionAccept = 0,
  NosmaiRecordingSettlementAdmissionStaleGeneration,
  NosmaiRecordingSettlementAdmissionWrongState,
  NosmaiRecordingSettlementAdmissionAlreadySettled,
  NosmaiRecordingSettlementAdmissionCleanupInProgress,
};

typedef NS_ENUM(NSInteger, NosmaiRecordingFinalizationDisposition) {
  NosmaiRecordingFinalizationDispositionMuxed = 0,
  NosmaiRecordingFinalizationDispositionVideoOnly,
  NosmaiRecordingFinalizationDispositionFailure,
};

FOUNDATION_EXPORT NosmaiRecordingSettlementAdmission
NosmaiRecordingEvaluateAdmission(NSUInteger operationGeneration,
                                 NSUInteger currentGeneration,
                                 BOOL expectedState,
                                 BOOL alreadySettled,
                                 BOOL cleanupInProgress,
                                 BOOL allowDuringCleanup);

FOUNDATION_EXPORT BOOL
NosmaiRecordingAcceptsSettlement(NSUInteger operationGeneration,
                                 NSUInteger currentGeneration,
                                 BOOL expectedState,
                                 BOOL alreadySettled);

FOUNDATION_EXPORT NosmaiRecordingFinalizationDisposition
NosmaiRecordingResolveFinalization(BOOL playableVideo,
                                   BOOL hasAudioTrack,
                                   BOOL muxFailed);

FOUNDATION_EXPORT BOOL NosmaiRecordingNeedsAudioMuxWarning(
    NosmaiRecordingFinalizationDisposition disposition);

FOUNDATION_EXPORT NSString *NosmaiRecordingResolveErrorCode(
    NSString *defaultCode,
    NSString *storageFullCode,
    NSString *_Nullable message,
    NSError *_Nullable error);

NS_ASSUME_NONNULL_END
