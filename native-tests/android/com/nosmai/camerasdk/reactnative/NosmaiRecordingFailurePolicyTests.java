package com.nosmai.camerasdk.reactnative;

import java.util.Arrays;
import java.util.Collections;

/** Deterministic fault-injection runner; intentionally has no Android/JUnit dependency. */
public final class NosmaiRecordingFailurePolicyTests {
  private static int assertions;

  public static void main(String[] args) {
    testRecordingWatchdogs();
    testLowStorageMapping();
    testAudioMuxFallback();
    testCleanupAndLifecycleRaces();
    System.out.println("Android recording failure policies: " + assertions + " assertions passed");
  }

  private static void testRecordingWatchdogs() {
    // A current start watchdog may settle STARTING exactly once.
    expectTrue(NosmaiRecordingFailurePolicy.acceptsSettlement(7, 7, true, false));
    expectFalse(NosmaiRecordingFailurePolicy.acceptsSettlement(7, 8, true, false));
    expectFalse(NosmaiRecordingFailurePolicy.acceptsSettlement(7, 7, false, false));
    expectFalse(NosmaiRecordingFailurePolicy.acceptsSettlement(7, 7, true, true));

    // The same gate protects native-stop and finalization watchdogs from late work.
    expectEquals(
        NosmaiRecordingFailurePolicy.Admission.STALE_GENERATION,
        NosmaiRecordingFailurePolicy.evaluateAdmission(11, 12, true, false, false, true));
    expectEquals(
        NosmaiRecordingFailurePolicy.Admission.ALREADY_SETTLED,
        NosmaiRecordingFailurePolicy.evaluateAdmission(12, 12, true, true, false, true));
  }

  private static void testLowStorageMapping() {
    expectEquals(
        "E_RECORDING_STORAGE_FULL",
        NosmaiRecordingFailurePolicy.resolveErrorCode(
            "E_RECORDING_WRITE",
            "E_RECORDING_STORAGE_FULL",
            "write failed",
            Collections.<String>emptyList(),
            true));
    expectEquals(
        "E_RECORDING_STORAGE_FULL",
        NosmaiRecordingFailurePolicy.resolveErrorCode(
            "E_RECORDING_STOP",
            "E_RECORDING_STORAGE_FULL",
            "native stop failed",
            Arrays.asList("wrapper", "ENOSPC: no space left on device"),
            false));
    expectEquals(
        "E_RECORDING_WRITE",
        NosmaiRecordingFailurePolicy.resolveErrorCode(
            "E_RECORDING_WRITE",
            "E_RECORDING_STORAGE_FULL",
            "codec failed",
            Arrays.asList("unrelated failure"),
            false));
    expectEquals(
        "E_RECORDING_WRITE",
        NosmaiRecordingFailurePolicy.resolveErrorCode(
            "E_RECORDING_WRITE",
            "E_RECORDING_STORAGE_FULL",
            "100 GB space left on device",
            Collections.<String>emptyList(),
            false));
  }

  private static void testAudioMuxFallback() {
    expectEquals(
        NosmaiRecordingFailurePolicy.FinalizationDisposition.MUXED,
        NosmaiRecordingFailurePolicy.resolveFinalization(true, true, true));
    expectEquals(
        NosmaiRecordingFailurePolicy.FinalizationDisposition.VIDEO_ONLY,
        NosmaiRecordingFailurePolicy.resolveFinalization(true, true, false));
    expectEquals(
        NosmaiRecordingFailurePolicy.FinalizationDisposition.VIDEO_ONLY,
        NosmaiRecordingFailurePolicy.resolveFinalization(true, false, false));
    expectEquals(
        NosmaiRecordingFailurePolicy.FinalizationDisposition.FAILURE,
        NosmaiRecordingFailurePolicy.resolveFinalization(false, true, false));
  }

  private static void testCleanupAndLifecycleRaces() {
    expectEquals(
        NosmaiRecordingFailurePolicy.Admission.CLEANUP_IN_PROGRESS,
        NosmaiRecordingFailurePolicy.evaluateAdmission(20, 20, true, false, true, false));
    expectEquals(
        NosmaiRecordingFailurePolicy.Admission.ACCEPT,
        NosmaiRecordingFailurePolicy.evaluateAdmission(20, 20, true, false, true, true));
    expectEquals(
        NosmaiRecordingFailurePolicy.Admission.STALE_GENERATION,
        NosmaiRecordingFailurePolicy.evaluateAdmission(20, 21, true, false, true, true));
    expectEquals(
        NosmaiRecordingFailurePolicy.Admission.WRONG_STATE,
        NosmaiRecordingFailurePolicy.evaluateAdmission(21, 21, false, false, false, true));
  }

  private static void expectTrue(boolean value) {
    assertions++;
    if (!value) throw new AssertionError("Expected true");
  }

  private static void expectFalse(boolean value) {
    assertions++;
    if (value) throw new AssertionError("Expected false");
  }

  private static void expectEquals(Object expected, Object actual) {
    assertions++;
    if (!expected.equals(actual)) {
      throw new AssertionError("Expected " + expected + " but received " + actual);
    }
  }
}
