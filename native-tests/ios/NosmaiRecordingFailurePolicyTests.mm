#import <Foundation/Foundation.h>

#import "../../ios/NosmaiRecordingFailurePolicy.h"

#include <errno.h>
#include <stdio.h>

static NSUInteger assertions = 0;

static void ExpectTrue(BOOL value, NSString *message) {
  assertions++;
  if (!value) {
    fprintf(stderr, "FAIL: %s\n", message.UTF8String);
    exit(1);
  }
}

static void ExpectInteger(NSInteger expected,
                          NSInteger actual,
                          NSString *message) {
  assertions++;
  if (expected != actual) {
    fprintf(stderr, "FAIL: %s (expected %ld, received %ld)\n",
            message.UTF8String, (long)expected, (long)actual);
    exit(1);
  }
}

static void ExpectString(NSString *expected,
                         NSString *actual,
                         NSString *message) {
  assertions++;
  if (![expected isEqualToString:actual]) {
    fprintf(stderr, "FAIL: %s (expected %s, received %s)\n",
            message.UTF8String, expected.UTF8String, actual.UTF8String);
    exit(1);
  }
}

static void TestRecordingWatchdogs(void) {
  // Start, stop, and finalization watchdogs all accept only their current generation.
  ExpectTrue(NosmaiRecordingAcceptsSettlement(7, 7, YES, NO),
             @"current start watchdog must settle");
  ExpectTrue(!NosmaiRecordingAcceptsSettlement(7, 8, YES, NO),
             @"stale start watchdog must be ignored");
  ExpectTrue(!NosmaiRecordingAcceptsSettlement(9, 9, NO, NO),
             @"stop watchdog in a non-stopping state must be ignored");
  ExpectTrue(!NosmaiRecordingAcceptsSettlement(10, 10, YES, YES),
             @"finalization watchdog must not double-settle");
}

static void TestLowStorageMapping(void) {
  NSError *cocoaOutOfSpace =
      [NSError errorWithDomain:NSCocoaErrorDomain
                          code:NSFileWriteOutOfSpaceError
                      userInfo:nil];
  ExpectString(@"E_RECORDING_STORAGE_FULL",
               NosmaiRecordingResolveErrorCode(
                   @"E_RECORDING_STOP", @"E_RECORDING_STORAGE_FULL",
                   @"Unable to write recording", cocoaOutOfSpace),
               @"NSFileWriteOutOfSpaceError must map to stable storage code");

  NSError *posixOutOfSpace =
      [NSError errorWithDomain:NSPOSIXErrorDomain code:ENOSPC userInfo:nil];
  NSError *wrapped = [NSError
      errorWithDomain:@"test.wrapper"
                 code:1
             userInfo:@{NSUnderlyingErrorKey : posixOutOfSpace}];
  ExpectString(@"E_RECORDING_STORAGE_FULL",
               NosmaiRecordingResolveErrorCode(
                   @"E_RECORDING_START", @"E_RECORDING_STORAGE_FULL",
                   @"start failed", wrapped),
               @"nested ENOSPC must map to stable storage code");
  ExpectString(@"E_RECORDING_STOP",
               NosmaiRecordingResolveErrorCode(
                   @"E_RECORDING_STOP", @"E_RECORDING_STORAGE_FULL",
                   @"codec unavailable", nil),
               @"unrelated errors must retain their original code");
  ExpectString(@"E_RECORDING_STOP",
               NosmaiRecordingResolveErrorCode(
                   @"E_RECORDING_STOP", @"E_RECORDING_STORAGE_FULL",
                   @"100 GB space left on device", nil),
               @"available capacity text must not be classified as full");
}

static void TestAudioMuxFallback(void) {
  ExpectInteger(NosmaiRecordingFinalizationDispositionMuxed,
                NosmaiRecordingResolveFinalization(YES, YES, NO),
                @"playable audio/video output must be accepted");
  NosmaiRecordingFinalizationDisposition silentFallback =
      NosmaiRecordingResolveFinalization(YES, NO, YES);
  ExpectInteger(NosmaiRecordingFinalizationDispositionVideoOnly,
                silentFallback,
                @"mux failure must preserve a playable video");
  ExpectTrue(NosmaiRecordingNeedsAudioMuxWarning(silentFallback),
             @"silent-video fallback must emit an audio-mux warning");
  ExpectInteger(NosmaiRecordingFinalizationDispositionFailure,
                NosmaiRecordingResolveFinalization(NO, NO, YES),
                @"mux failure without playable video must fail");
}

static void TestCleanupAndLifecycleRaces(void) {
  ExpectInteger(NosmaiRecordingSettlementAdmissionCleanupInProgress,
                NosmaiRecordingEvaluateAdmission(20, 20, YES, NO, YES, NO),
                @"new work must not enter during cleanup");
  ExpectInteger(NosmaiRecordingSettlementAdmissionAccept,
                NosmaiRecordingEvaluateAdmission(20, 20, YES, NO, YES, YES),
                @"in-flight teardown settlement must be admitted");
  ExpectInteger(NosmaiRecordingSettlementAdmissionStaleGeneration,
                NosmaiRecordingEvaluateAdmission(20, 21, YES, NO, YES, YES),
                @"late lifecycle callback must be rejected after generation change");
  ExpectInteger(NosmaiRecordingSettlementAdmissionAlreadySettled,
                NosmaiRecordingEvaluateAdmission(21, 21, YES, YES, NO, YES),
                @"duplicate lifecycle callback must not settle twice");
}

int main(void) {
  @autoreleasepool {
    TestRecordingWatchdogs();
    TestLowStorageMapping();
    TestAudioMuxFallback();
    TestCleanupAndLifecycleRaces();
    printf("iOS recording failure policies: %lu assertions passed\n",
           (unsigned long)assertions);
  }
  return 0;
}
