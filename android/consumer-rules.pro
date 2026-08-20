# React Native discovers these classes through generated package metadata.
-keep class com.nosmai.camerasdk.reactnative.** { *; }

# The proprietary AAR contains JNI entrypoints; its own consumer rules remain
# authoritative, while this rule protects native method names during merging.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes *Annotation*,Exceptions
# The current Android AAR has no public per-preview YUV readiness API. The
# bridge isolates the compatibility reflection in NosmaiPreviewReadinessAdapter.
-keepclassmembers class com.nosmai.effect.api.NosmaiPreviewView {
    private *** source;
    private com.nosmai.effect.internal.NosmaiGLView innerView;
}

# SDK 3.0.x exposes no public completion for scoped effect clears. The bridge
# reflects this callback-capable overload to serialize mutations and cleanup.
-keepclassmembers class com.nosmai.effect.NosmaiEffectsEngine {
    private static void clearSlot(com.nosmai.effect.NosmaiEffectsEngine$PackageSlot, com.nosmai.effect.NosmaiEffectsEngine$EffectCallback);
    private static java.util.concurrent.ExecutorService executor();
    private static java.util.concurrent.atomic.AtomicBoolean TRANSITION;
    private static java.lang.Object REQUEST_LOCK;
}
-keep class com.nosmai.effect.NosmaiEffectsEngine$PackageSlot { *; }
-keep interface com.nosmai.effect.NosmaiEffectsEngine$EffectCallback { *; }

# SDK 3.0.x exposes visual entitlements only on its internal facade. Keep the
# compatibility adapter's reflected Java methods as well as the private JNI
# symbol; -keepclasseswithmembernames for native methods alone does not protect
# the two Java method names used by reflection.
-keepclassmembers class com.nosmai.effect.internal.Nosmai {
    public static boolean isBeautyEnabled();
    public static java.lang.String getLicenseStatus();
    private static native boolean nativeIsAdvancedFiltersEnabled();
}
