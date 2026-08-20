package com.nosmai.camerasdk.reactnative;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Platform-free recording failure decisions shared by the Android recorder and
 * its JVM fault-injection runner. Keep Android framework objects outside this
 * class so these safety rules can be exercised without the proprietary SDK,
 * an emulator, or Robolectric.
 */
final class NosmaiRecordingFailurePolicy {
  enum Admission {
    ACCEPT,
    STALE_GENERATION,
    WRONG_STATE,
    ALREADY_SETTLED,
    CLEANUP_IN_PROGRESS
  }

  enum FinalizationDisposition {
    MUXED,
    VIDEO_ONLY,
    FAILURE
  }

  private NosmaiRecordingFailurePolicy() {}

  static Admission evaluateAdmission(
      long operationGeneration,
      long currentGeneration,
      boolean expectedState,
      boolean alreadySettled,
      boolean cleanupInProgress,
      boolean allowDuringCleanup) {
    if (operationGeneration != currentGeneration) {
      return Admission.STALE_GENERATION;
    }
    if (!expectedState) {
      return Admission.WRONG_STATE;
    }
    if (alreadySettled) {
      return Admission.ALREADY_SETTLED;
    }
    if (cleanupInProgress && !allowDuringCleanup) {
      return Admission.CLEANUP_IN_PROGRESS;
    }
    return Admission.ACCEPT;
  }

  static boolean acceptsSettlement(
      long operationGeneration,
      long currentGeneration,
      boolean expectedState,
      boolean alreadySettled) {
    return evaluateAdmission(
            operationGeneration,
            currentGeneration,
            expectedState,
            alreadySettled,
            false,
            true)
        == Admission.ACCEPT;
  }

  static FinalizationDisposition resolveFinalization(
      boolean playableVideo,
      boolean muxRequested,
      boolean muxCompleted) {
    if (!playableVideo) return FinalizationDisposition.FAILURE;
    if (muxRequested && muxCompleted) return FinalizationDisposition.MUXED;
    return FinalizationDisposition.VIDEO_ONLY;
  }

  static String resolveErrorCode(
      String defaultCode,
      String storageFullCode,
      String message,
      List<String> causeMessages,
      boolean errnoNoSpace) {
    if (errnoNoSpace || containsStorageMarker(message)) return storageFullCode;
    for (String causeMessage :
        causeMessages == null ? Collections.<String>emptyList() : causeMessages) {
      if (containsStorageMarker(causeMessage)) return storageFullCode;
    }
    return defaultCode;
  }

  private static boolean containsStorageMarker(String message) {
    if (message == null) return false;
    String normalized = message.toLowerCase(Locale.ROOT);
    return normalized.contains("no space")
        || normalized.contains("enospc")
        || normalized.contains("disk full")
        || normalized.contains("not enough space");
  }
}
