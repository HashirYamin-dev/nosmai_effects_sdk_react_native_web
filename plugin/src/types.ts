export interface NosmaiExpoPluginProps {
  /**
   * Authorized Nosmai Android AAR. Relative paths resolve from the Expo app
   * root. An absolute path can be supplied by an EAS file environment variable.
   *
   * @default "vendor/nosmai-release.aar"
   */
  androidAarPath?: string;

  /** Optional lowercase or uppercase SHA-256 for the Android AAR. */
  androidAarSha256?: string;

  /** iOS camera permission text. Existing app text is preserved when omitted. */
  cameraPermission?: string;

  /**
   * iOS microphone permission text. Existing app text is preserved when
   * omitted.
   */
  microphonePermission?: string;

  /**
   * iOS add-only photo-library permission text. Existing app text is preserved
   * when omitted.
   */
  photoLibraryAddPermission?: string;
}
