require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NosmaiReactNativeCameraSdk"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = { :type => "Commercial", :file => "LICENSE" }
  s.authors      = { "Nosmai" => "admin@nosmai.com" }

  s.platform     = :ios, "15.0"
  s.source       = {
    :git => "https://github.com/nosmai/nosmai_effects_sdk_react_native.git",
    :tag => "#{s.version}"
  }

  s.source_files = "ios/**/*.{h,m,mm,swift,cpp}"
  s.private_header_files = "ios/**/*.h"
  s.resource_bundles = {
    "NosmaiReactNativeCameraSdk_privacy" => ["ios/Resources/PrivacyInfo.xcprivacy"]
  }

  s.dependency "NosmaiCameraSDK", "~> 3.0.4"
  s.frameworks = "AVFoundation", "CoreMedia", "CoreVideo", "Foundation", "OpenGLES", "Photos", "QuartzCore", "UIKit"

  s.pod_target_xcconfig = {
    "DEFINES_MODULE" => "YES"
  }

  install_modules_dependencies(s)
end
