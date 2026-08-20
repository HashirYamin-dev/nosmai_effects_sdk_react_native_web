#import "NosmaiRecordingFailurePolicy.h"

#include <errno.h>

NosmaiRecordingSettlementAdmission
NosmaiRecordingEvaluateAdmission(NSUInteger operationGeneration,
                                 NSUInteger currentGeneration,
                                 BOOL expectedState,
                                 BOOL alreadySettled,
                                 BOOL cleanupInProgress,
                                 BOOL allowDuringCleanup) {
  if (operationGeneration != currentGeneration) {
    return NosmaiRecordingSettlementAdmissionStaleGeneration;
  }
  if (!expectedState) return NosmaiRecordingSettlementAdmissionWrongState;
  if (alreadySettled) {
    return NosmaiRecordingSettlementAdmissionAlreadySettled;
  }
  if (cleanupInProgress && !allowDuringCleanup) {
    return NosmaiRecordingSettlementAdmissionCleanupInProgress;
  }
  return NosmaiRecordingSettlementAdmissionAccept;
}

BOOL NosmaiRecordingAcceptsSettlement(NSUInteger operationGeneration,
                                      NSUInteger currentGeneration,
                                      BOOL expectedState,
                                      BOOL alreadySettled) {
  return NosmaiRecordingEvaluateAdmission(
             operationGeneration, currentGeneration, expectedState,
             alreadySettled, NO, YES) ==
      NosmaiRecordingSettlementAdmissionAccept;
}

NosmaiRecordingFinalizationDisposition
NosmaiRecordingResolveFinalization(BOOL playableVideo,
                                   BOOL hasAudioTrack,
                                   BOOL muxFailed) {
  if (!playableVideo) return NosmaiRecordingFinalizationDispositionFailure;
  if (muxFailed || !hasAudioTrack) {
    return NosmaiRecordingFinalizationDispositionVideoOnly;
  }
  return NosmaiRecordingFinalizationDispositionMuxed;
}

BOOL NosmaiRecordingNeedsAudioMuxWarning(
    NosmaiRecordingFinalizationDisposition disposition) {
  return disposition == NosmaiRecordingFinalizationDispositionVideoOnly;
}

static BOOL NosmaiRecordingContainsStorageMarker(NSString *message) {
  NSString *normalized = message.lowercaseString;
  if (normalized.length == 0) return NO;
  return [normalized containsString:@"no space"] ||
      [normalized containsString:@"enospc"] ||
      [normalized containsString:@"disk full"] ||
      [normalized containsString:@"not enough space"];
}

NSString *NosmaiRecordingResolveErrorCode(NSString *defaultCode,
                                          NSString *storageFullCode,
                                          NSString *message,
                                          NSError *error) {
  if (NosmaiRecordingContainsStorageMarker(message)) return storageFullCode;

  NSError *current = error;
  for (NSUInteger depth = 0; current && depth < 8; depth++) {
    if (([current.domain isEqualToString:NSPOSIXErrorDomain] &&
         current.code == ENOSPC) ||
        ([current.domain isEqualToString:NSCocoaErrorDomain] &&
         current.code == NSFileWriteOutOfSpaceError) ||
        NosmaiRecordingContainsStorageMarker(current.localizedDescription)) {
      return storageFullCode;
    }
    NSError *next = current.userInfo[NSUnderlyingErrorKey];
    if (next == current) break;
    current = next;
  }
  return defaultCode;
}
