#import "NosmaiCameraView.h"

#import "NosmaiIOSController.h"

#import <react/renderer/components/NosmaiCameraSdkSpec/ComponentDescriptors.h>
#import <react/renderer/components/NosmaiCameraSdkSpec/EventEmitters.h>
#import <react/renderer/components/NosmaiCameraSdkSpec/Props.h>
#import <react/renderer/components/NosmaiCameraSdkSpec/RCTComponentViewHelpers.h>

#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

@interface NosmaiCameraView () <NosmaiIOSPreviewSink>
@end

@implementation NosmaiCameraView {
  UIView *_hostView;
  UIView *_previewContainer;
  UIView *_transitionOverlay;
  NSString *_cameraPosition;
  BOOL _mirrorPreview;
  BOOL _controllerAttached;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider {
  return concreteComponentDescriptorProvider<NosmaiCameraViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame {
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps =
        std::make_shared<const NosmaiCameraViewProps>();
    _props = defaultProps;

    _cameraPosition = @"front";
    _mirrorPreview = NO;

    _hostView = [[UIView alloc] initWithFrame:CGRectZero];
    _hostView.backgroundColor = UIColor.blackColor;
    _hostView.clipsToBounds = YES;

    _previewContainer = [[UIView alloc] initWithFrame:CGRectZero];
    _previewContainer.backgroundColor = UIColor.blackColor;
    _previewContainer.clipsToBounds = YES;
    _previewContainer.contentMode = UIViewContentModeScaleAspectFill;
    [_hostView addSubview:_previewContainer];

    _transitionOverlay = [[UIView alloc] initWithFrame:CGRectZero];
    _transitionOverlay.backgroundColor = UIColor.blackColor;
    _transitionOverlay.userInteractionEnabled = NO;
    [_hostView addSubview:_transitionOverlay];

    self.contentView = _hostView;
  }

  return self;
}

- (void)didMoveToWindow {
  [super didMoveToWindow];
  if (self.window && !_controllerAttached) {
    _controllerAttached = YES;
    [[NosmaiIOSController shared] attachPreviewSink:self
                                          container:_previewContainer
                                            position:_cameraPosition
                                              mirror:_mirrorPreview];
  } else if (!self.window && _controllerAttached) {
    _controllerAttached = NO;
    [[NosmaiIOSController shared] detachPreviewSink:self];
  }
}

- (void)layoutSubviews {
  [super layoutSubviews];
  _hostView.frame = self.bounds;
  _previewContainer.bounds = _hostView.bounds;
  _previewContainer.center =
      CGPointMake(CGRectGetMidX(_hostView.bounds), CGRectGetMidY(_hostView.bounds));
  _transitionOverlay.frame = _hostView.bounds;

  for (UIView *subview in _previewContainer.subviews) {
    subview.frame = _previewContainer.bounds;
    subview.contentMode = UIViewContentModeScaleAspectFill;
    subview.layer.contentsGravity = kCAGravityResizeAspectFill;
    subview.layer.masksToBounds = YES;
    for (CALayer *layer in subview.layer.sublayers) {
      layer.frame = subview.bounds;
      layer.contentsGravity = kCAGravityResizeAspectFill;
    }
  }
  for (CALayer *layer in _previewContainer.layer.sublayers) {
    layer.frame = _previewContainer.bounds;
    layer.contentsGravity = kCAGravityResizeAspectFill;
  }
  [_hostView bringSubviewToFront:_transitionOverlay];
}

- (void)updateProps:(Props::Shared const &)props
            oldProps:(Props::Shared const &)oldProps {
  const auto &newProps =
      *std::static_pointer_cast<const NosmaiCameraViewProps>(props);
  NSString *position = newProps.cameraPosition.empty()
      ? @"front"
      : [NSString stringWithUTF8String:newProps.cameraPosition.c_str()];
  BOOL mirror = newProps.mirror;
  BOOL changed = ![_cameraPosition isEqualToString:position] ||
                 _mirrorPreview != mirror;
  _cameraPosition = position;
  _mirrorPreview = mirror;

  [super updateProps:props oldProps:oldProps];
  if (changed && _controllerAttached) {
    [[NosmaiIOSController shared] updatePreviewSink:self
                                           position:_cameraPosition
                                             mirror:_mirrorPreview];
  }
}

- (void)prepareForRecycle {
  if (_controllerAttached) {
    _controllerAttached = NO;
    [[NosmaiIOSController shared] detachPreviewSink:self];
  }
  _cameraPosition = @"front";
  _mirrorPreview = NO;
  _previewContainer.transform = CGAffineTransformIdentity;
  _transitionOverlay.alpha = 1.0;
  _transitionOverlay.hidden = NO;
  [super prepareForRecycle];
}

- (void)dealloc {
  if (_controllerAttached) {
    [[NosmaiIOSController shared] detachPreviewSink:self];
  }
}

#pragma mark - Controller sink

- (void)nosmaiControllerShowTransition {
  NSAssert(NSThread.isMainThread, @"Camera view events must run on main");
  [_transitionOverlay.layer removeAllAnimations];
  _transitionOverlay.hidden = NO;
  _transitionOverlay.alpha = 1.0;
  [_hostView bringSubviewToFront:_transitionOverlay];
}

- (void)nosmaiControllerDidRenderFirstFrame {
  NSAssert(NSThread.isMainThread, @"Camera view events must run on main");
  [self setNeedsLayout];
  [self layoutIfNeeded];
  [_hostView bringSubviewToFront:_transitionOverlay];
  [UIView animateWithDuration:0.12
      animations:^{
        self->_transitionOverlay.alpha = 0.0;
      }
      completion:^(__unused BOOL finished) {
        self->_transitionOverlay.hidden = YES;
      }];

  auto eventEmitter =
      std::static_pointer_cast<const NosmaiCameraViewEventEmitter>(_eventEmitter);
  if (eventEmitter) {
    eventEmitter->onCameraReady(
        NosmaiCameraViewEventEmitter::OnCameraReady{.platform = "ios"});
  }
}

- (void)nosmaiControllerDidReceiveCameraErrorWithCode:(NSString *)code
                                               message:(NSString *)message {
  NSAssert(NSThread.isMainThread, @"Camera view events must run on main");
  auto eventEmitter =
      std::static_pointer_cast<const NosmaiCameraViewEventEmitter>(_eventEmitter);
  if (eventEmitter) {
    eventEmitter->onCameraError(NosmaiCameraViewEventEmitter::OnCameraError{
        .code = code.UTF8String ?: "E_NATIVE_FAILURE",
        .message = message.UTF8String ?: "The native camera failed",
    });
  }
}

@end
