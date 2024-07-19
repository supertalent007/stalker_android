package org.stalker.securesms.mediasend;

import android.Manifest;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.util.Size;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import org.signal.core.util.Stopwatch;
import org.signal.core.util.concurrent.SimpleTask;
import org.signal.core.util.logging.Log;
import org.signal.qr.QrProcessor;
import org.stalker.securesms.LoggingFragment;
import org.stalker.securesms.R;
import org.stalker.securesms.animation.AnimationCompleteListener;
import org.stalker.securesms.components.TooltipPopup;
import org.stalker.securesms.mediasend.camerax.CameraXController;
import org.stalker.securesms.mediasend.camerax.CameraXFlashToggleView;
import org.stalker.securesms.mediasend.camerax.CameraXModePolicy;
import org.stalker.securesms.mediasend.camerax.CameraXUtil;
import org.stalker.securesms.mediasend.camerax.PlatformCameraController;
import org.stalker.securesms.mediasend.camerax.SignalCameraController;
import org.stalker.securesms.mediasend.v2.MediaAnimations;
import org.stalker.securesms.mediasend.v2.MediaCountIndicatorButton;
import org.stalker.securesms.mms.DecryptableStreamUriLoader.DecryptableUri;
import org.stalker.securesms.mms.MediaConstraints;
import org.stalker.securesms.permissions.Permissions;
import org.stalker.securesms.util.BottomSheetUtil;
import org.stalker.securesms.util.FeatureFlags;
import org.stalker.securesms.util.MemoryFileDescriptor;
import org.stalker.securesms.util.TextSecurePreferences;
import org.stalker.securesms.util.ViewUtil;
import org.stalker.securesms.video.VideoUtil;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import kotlin.Unit;

import static org.stalker.securesms.permissions.PermissionDeniedBottomSheet.showPermissionFragment;

/**
 * Camera captured implemented using the CameraX SDK, which uses Camera2 under the hood. Should be
 * preferred whenever possible.
 */
public class CameraXFragment extends LoggingFragment implements CameraFragment {

  private static final String TAG                = Log.tag(CameraXFragment.class);
  private static final String IS_VIDEO_ENABLED   = "is_video_enabled";
  private static final String IS_QR_SCAN_ENABLED = "is_qr_scan_enabled";


  private static final Rational              ASPECT_RATIO_16_9  = new Rational(16, 9);
  private static final PreviewView.ScaleType PREVIEW_SCALE_TYPE = PreviewView.ScaleType.FILL_CENTER;

  private PreviewView                      previewView;
  private MaterialCardView                 cameraParent;
  private ViewGroup                        controlsContainer;
  private Controller                       controller;
  private View                             selfieFlash;
  private MemoryFileDescriptor             videoFileDescriptor;
  private CameraXController                cameraController;
  private CameraXOrientationListener       orientationListener;
  private Disposable                       mostRecentItemDisposable = Disposable.disposed();
  private CameraXModePolicy                cameraXModePolicy;
  private CameraScreenBrightnessController cameraScreenBrightnessController;
  private boolean                          isMediaSelected;
  private View                             missingPermissionsContainer;
  private TextView                         missingPermissionsText;
  private MaterialButton                   allowAccessButton;

  private final Executor    qrAnalysisExecutor = Executors.newSingleThreadExecutor();
  private final QrProcessor qrProcessor        = new QrProcessor();

  public static CameraXFragment newInstanceForAvatarCapture() {
    CameraXFragment fragment = new CameraXFragment();
    Bundle          args     = new Bundle();

    args.putBoolean(IS_VIDEO_ENABLED, false);
    args.putBoolean(IS_QR_SCAN_ENABLED, false);
    fragment.setArguments(args);

    return fragment;
  }

  public static CameraXFragment newInstance(boolean qrScanEnabled) {
    CameraXFragment fragment = new CameraXFragment();

    Bundle args = new Bundle();
    args.putBoolean(IS_QR_SCAN_ENABLED, qrScanEnabled);

    fragment.setArguments(args);

    return fragment;
  }

  @Override
  public void onAttach(@NonNull Context context) {
    super.onAttach(context);

    if (getActivity() instanceof Controller) {
      this.controller = (Controller) getActivity();
    } else if (getParentFragment() instanceof Controller) {
      this.controller = (Controller) getParentFragment();
    }

    if (controller == null) {
      throw new IllegalStateException("Parent must implement controller interface.");
    }

    this.orientationListener = new CameraXOrientationListener(context);
  }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.camerax_fragment, container, false);
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    this.cameraParent                = view.findViewById(R.id.camerax_camera_parent);

    this.previewView                 = view.findViewById(R.id.camerax_camera);
    this.controlsContainer           = view.findViewById(R.id.camerax_controls_container);
    this.cameraXModePolicy           = CameraXModePolicy.acquire(requireContext(),
                                                        controller.getMediaConstraints(),
                                                        requireArguments().getBoolean(IS_VIDEO_ENABLED, true));
    this.missingPermissionsContainer = view.findViewById(R.id.missing_permissions_container);
    this.missingPermissionsText      = view.findViewById(R.id.missing_permissions_text);
    this.allowAccessButton           = view.findViewById(R.id.allow_access_button);

    checkPermissions(requireArguments().getBoolean(IS_VIDEO_ENABLED, true));

    Log.d(TAG, "Starting CameraX with mode policy " + cameraXModePolicy.getClass().getSimpleName());


    previewView.setScaleType(PREVIEW_SCALE_TYPE);
    if (FeatureFlags.customCameraXController()) {
      View focusIndicator = view.findViewById(R.id.camerax_focus_indicator);
      cameraController = new SignalCameraController(requireContext(), getViewLifecycleOwner(), previewView, focusIndicator);
    } else {
      PlatformCameraController platformController = new PlatformCameraController(requireContext());
      platformController.initializeAndBind(requireContext(), getViewLifecycleOwner());
      previewView.setController(platformController.getDelegate());
      cameraController = platformController;
    }

    cameraXModePolicy.initialize(cameraController);

    cameraScreenBrightnessController = new CameraScreenBrightnessController(
        requireActivity().getWindow(),
        new CameraStateProvider(cameraController)
    );

    previewView.setScaleType(PREVIEW_SCALE_TYPE);

    onOrientationChanged();

    if (FeatureFlags.customCameraXController()) {
      cameraController.initializeAndBind(requireContext(), getViewLifecycleOwner());
    }

    if (requireArguments().getBoolean(IS_QR_SCAN_ENABLED, false)) {
      cameraController.setImageAnalysisAnalyzer(qrAnalysisExecutor, imageProxy -> {
        try {
          String data = qrProcessor.getScannedData(imageProxy);
          if (data != null) {
            controller.onQrCodeFound(data);
          }
        } finally {
          imageProxy.close();
        }
      });
    }
  }

  @Override
  public void onStart() {
    super.onStart();
    orientationListener.enable();
  }

  @Override
  public void onStop() {
    super.onStop();
    orientationListener.disable();
  }

  @Override
  public void onResume() {
    super.onResume();

    cameraController.bindToLifecycle(getViewLifecycleOwner(), () -> Log.d(TAG, "Camera init complete from onResume"));
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    if (hasCameraPermission()) {
      missingPermissionsContainer.setVisibility(View.GONE);
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    mostRecentItemDisposable.dispose();
    closeVideoFileDescriptor();
    requireActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
  }

  @Override
  public void fadeOutControls(@NonNull Runnable onEndAction) {
    controlsContainer.setEnabled(false);
    controlsContainer.animate()
                     .setDuration(250)
                     .alpha(0f)
                     .setInterpolator(MediaAnimations.getInterpolator())
                     .setListener(new AnimationCompleteListener() {
                       @Override
                       public void onAnimationEnd(Animator animation) {
                         controlsContainer.setEnabled(true);
                         onEndAction.run();
                       }
                     });
  }

  @Override
  public void fadeInControls() {
    controlsContainer.setEnabled(false);
    controlsContainer.animate()
                     .setDuration(250)
                     .alpha(1f)
                     .setInterpolator(MediaAnimations.getInterpolator())
                     .setListener(new AnimationCompleteListener() {
                       @Override
                       public void onAnimationEnd(Animator animation) {
                         controlsContainer.setEnabled(true);
                       }
                     });
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void checkPermissions(boolean includeAudio) {
    if (hasCameraPermission()) {
      missingPermissionsContainer.setVisibility(View.GONE);
    } else {
      boolean hasAudioPermission = Permissions.hasAll(requireContext(), Manifest.permission.RECORD_AUDIO);
      missingPermissionsContainer.setVisibility(View.VISIBLE);
      int textResId = (!includeAudio || hasAudioPermission) ? R.string.CameraXFragment_to_capture_photos_and_video_allow_camera : R.string.CameraXFragment_to_capture_photos_and_video_allow_camera_microphone;
      missingPermissionsText.setText(textResId);
      allowAccessButton.setOnClickListener(v -> requestPermissions(includeAudio));
    }
  }

  private void requestPermissions(boolean includeAudio) {
    if (includeAudio) {
      Permissions.with(this)
                 .request(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                 .ifNecessary()
                 .onSomeGranted(permissions -> {
                   if (permissions.contains(Manifest.permission.CAMERA)) {
                     missingPermissionsContainer.setVisibility(View.GONE);
                   }
                 })
                 .onSomePermanentlyDenied(deniedPermissions -> {
                   if (deniedPermissions.containsAll(List.of(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))) {
                     showPermissionFragment(R.string.CameraXFragment_allow_access_camera_microphone, R.string.CameraXFragment_to_capture_photos_videos).show(getParentFragmentManager(), BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
                   } else if (deniedPermissions.contains(Manifest.permission.CAMERA)) {
                     showPermissionFragment(R.string.CameraXFragment_allow_access_camera, R.string.CameraXFragment_to_capture_photos_videos).show(getParentFragmentManager(), BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
                   }
                 })
                 .onSomeDenied(deniedPermissions -> {
                   if (deniedPermissions.contains(Manifest.permission.CAMERA)) {
                     Toast.makeText(requireContext(), R.string.CameraXFragment_signal_needs_camera_access_capture_photos, Toast.LENGTH_LONG).show();
                   }
                 })
                 .execute();
    } else {
      Permissions.with(this)
                 .request(Manifest.permission.CAMERA)
                 .ifNecessary()
                 .onAllGranted (() -> missingPermissionsContainer.setVisibility(View.GONE))
                 .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.CameraXFragment_signal_needs_camera_access_capture_photos, Toast.LENGTH_LONG).show())
                 .withPermanentDenialDialog(getString(R.string.CameraXFragment_signal_needs_camera_access_capture_photos), null, R.string.CameraXFragment_allow_access_camera, R.string.CameraXFragment_to_capture_photos, getParentFragmentManager())
                 .execute();
    }
  }

  private boolean hasCameraPermission() {
    return Permissions.hasAll(requireContext(), Manifest.permission.CAMERA);
  }

  private void onOrientationChanged() {
    int layout = R.layout.camera_controls_portrait;

    int  resolution = CameraXUtil.getIdealResolution(Resources.getSystem().getDisplayMetrics().widthPixels, Resources.getSystem().getDisplayMetrics().heightPixels);
    Size size       = CameraXUtil.buildResolutionForRatio(resolution, ASPECT_RATIO_16_9, true);

    cameraController.setImageCaptureTargetSize(size);

    controlsContainer.removeAllViews();
    controlsContainer.addView(LayoutInflater.from(getContext()).inflate(layout, controlsContainer, false));
    initControls();
  }

  private void presentRecentItemThumbnail(@Nullable Media media) {
    View      thumbBackground = controlsContainer.findViewById(R.id.camera_gallery_button_background);
    ImageView thumbnail       = controlsContainer.findViewById(R.id.camera_gallery_button);

    if (media != null) {
      thumbBackground.setBackgroundResource(R.drawable.circle_tintable);
      thumbnail.clearColorFilter();
      thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
      Glide.with(this)
           .load(new DecryptableUri(media.getUri()))
           .centerCrop()
           .into(thumbnail);
    } else {
      thumbBackground.setBackgroundResource(R.drawable.media_selection_camera_switch_background);
      thumbnail.setImageResource(R.drawable.symbol_album_tilt_24);
      thumbnail.setColorFilter(Color.WHITE);
      thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    }
  }

  @Override
  public void presentHud(int selectedMediaCount) {
    MediaCountIndicatorButton countButton = controlsContainer.findViewById(R.id.camera_review_button);

    if (selectedMediaCount > 0) {
      countButton.setVisibility(View.VISIBLE);
      countButton.setCount(selectedMediaCount);
    } else {
      countButton.setVisibility(View.GONE);
    }

    isMediaSelected = selectedMediaCount > 0;
    updateGalleryVisibility();
  }

  private void updateGalleryVisibility() {
    View cameraGalleryContainer = controlsContainer.findViewById(R.id.camera_gallery_button_background);

    if (isMediaSelected) {
      cameraGalleryContainer.setVisibility(View.GONE);
    } else {
      cameraGalleryContainer.setVisibility(View.VISIBLE);
    }
  }

  private void initializeViewFinderAndControlsPositioning() {
    MaterialCardView cameraCard    = requireView().findViewById(R.id.camerax_camera_parent);
    View             controls      = requireView().findViewById(R.id.camerax_controls_container);
    CameraDisplay    cameraDisplay = CameraDisplay.getDisplay(requireActivity());

    if (!cameraDisplay.getRoundViewFinderCorners()) {
      cameraCard.setRadius(0f);
    }

    ViewUtil.setBottomMargin(controls, cameraDisplay.getCameraCaptureMarginBottom(getResources()));

    if (cameraDisplay.getCameraViewportGravity() == CameraDisplay.CameraViewportGravity.CENTER) {
      ConstraintSet constraintSet = new ConstraintSet();
      constraintSet.clone((ConstraintLayout) requireView());
      constraintSet.connect(R.id.camerax_camera_parent, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
      constraintSet.applyTo((ConstraintLayout) requireView());
    } else {
      ViewUtil.setBottomMargin(cameraCard, cameraDisplay.getCameraViewportMarginBottom());
    }
  }

  @SuppressLint({ "ClickableViewAccessibility", "MissingPermission" })
  private void initControls() {
    View                   flipButton    = requireView().findViewById(R.id.camera_flip_button);
    CameraButtonView       captureButton = requireView().findViewById(R.id.camera_capture_button);
    View                   galleryButton = requireView().findViewById(R.id.camera_gallery_button);
    View                   countButton   = requireView().findViewById(R.id.camera_review_button);
    CameraXFlashToggleView flashButton   = requireView().findViewById(R.id.camera_flash_button);

    initializeViewFinderAndControlsPositioning();

    mostRecentItemDisposable.dispose();
    mostRecentItemDisposable = controller.getMostRecentMediaItem()
                                         .observeOn(AndroidSchedulers.mainThread())
                                         .subscribe(item -> presentRecentItemThumbnail(item.orElse(null)));

    selfieFlash = requireView().findViewById(R.id.camera_selfie_flash);

    captureButton.setOnClickListener(v -> {
      if (hasCameraPermission() && cameraController.isInitialized()) {
        captureButton.setEnabled(false);
        flipButton.setEnabled(false);
        flashButton.setEnabled(false);
        onCaptureClicked();
      } else {
        Log.i(TAG, "Camera capture button clicked but the camera controller is not yet initialized.");
      }
    });

    previewView.setScaleType(PREVIEW_SCALE_TYPE);

    cameraController.addInitializationCompletedListener(ContextCompat.getMainExecutor(requireContext()), () -> initializeFlipButton(flipButton, flashButton));

    flashButton.setAutoFlashEnabled(cameraController.getImageCaptureFlashMode() >= ImageCapture.FLASH_MODE_AUTO);
    flashButton.setFlash(cameraController.getImageCaptureFlashMode());
    flashButton.setOnFlashModeChangedListener(mode -> {
      cameraController.setImageCaptureFlashMode(mode);
      cameraScreenBrightnessController.onCameraFlashChanged(mode == ImageCapture.FLASH_MODE_ON);
    });

    galleryButton.setOnClickListener(v -> controller.onGalleryClicked());
    countButton.setOnClickListener(v -> controller.onCameraCountButtonClicked());

    if (Build.VERSION.SDK_INT >= 26 && cameraXModePolicy.isVideoSupported()) {
      try {
        closeVideoFileDescriptor();
        videoFileDescriptor = CameraXVideoCaptureHelper.createFileDescriptor(requireContext());

        Animation inAnimation  = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in);
        Animation outAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out);

        int maxDuration = VideoUtil.getMaxVideoRecordDurationInSeconds(requireContext(), controller.getMediaConstraints());
        if (controller.getMaxVideoDuration() > 0) {
          maxDuration = controller.getMaxVideoDuration();
        }

        Log.d(TAG, "Max duration: " + maxDuration + " sec");

        captureButton.setVideoCaptureListener(new CameraXVideoCaptureHelper(
            this,
            captureButton,
            cameraController,
            previewView,
            videoFileDescriptor,
            cameraXModePolicy,
            maxDuration,
            new CameraXVideoCaptureHelper.Callback() {
              @Override
              public void onVideoRecordStarted() {
                hideAndDisableControlsForVideoRecording(captureButton, flashButton, flipButton, outAnimation);
              }

              @Override
              public void onVideoSaved(@NonNull FileDescriptor fd) {
                showAndEnableControlsAfterVideoRecording(captureButton, flashButton, flipButton, inAnimation);
                controller.onVideoCaptured(fd);
              }

              @Override
              public void onVideoError(@Nullable Throwable cause) {
                showAndEnableControlsAfterVideoRecording(captureButton, flashButton, flipButton, inAnimation);
                controller.onVideoCaptureError();
              }
            }
        ));
        displayVideoRecordingTooltipIfNecessary(captureButton);
      } catch (IOException e) {
        Log.w(TAG, "Video capture is not supported on this device.", e);
      }
    } else {
      captureButton.setOnLongClickListener(unused -> {
        CameraFragment.toastVideoRecordingNotAvailable(requireContext());
        return true;
      });

      Log.i(TAG, "Video capture not supported. " +
                 "API: " + Build.VERSION.SDK_INT + ", " +
                 "MFD: " + MemoryFileDescriptor.supported() + ", " +
                 "Camera: " + CameraXUtil.getLowestSupportedHardwareLevel(requireContext()) + ", " +
                 "MaxDuration: " + VideoUtil.getMaxVideoRecordDurationInSeconds(requireContext(), controller.getMediaConstraints()) + " sec");
    }
  }

  private void displayVideoRecordingTooltipIfNecessary(CameraButtonView captureButton) {
    if (shouldDisplayVideoRecordingTooltip()) {
      int displayRotation = requireActivity().getWindowManager().getDefaultDisplay().getRotation();

      TooltipPopup.forTarget(captureButton)
                  .setOnDismissListener(this::neverDisplayVideoRecordingTooltipAgain)
                  .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.core_ultramarine))
                  .setTextColor(ContextCompat.getColor(requireContext(), R.color.signal_text_toolbar_title))
                  .setText(R.string.CameraXFragment_tap_for_photo_hold_for_video)
                  .show(displayRotation == Surface.ROTATION_0 || displayRotation == Surface.ROTATION_180 ? TooltipPopup.POSITION_ABOVE : TooltipPopup.POSITION_START);
    }
  }

  private boolean shouldDisplayVideoRecordingTooltip() {
    return !TextSecurePreferences.hasSeenVideoRecordingTooltip(requireContext()) && MediaConstraints.isVideoTranscodeAvailable();
  }

  private void neverDisplayVideoRecordingTooltipAgain() {
    Context context = getContext();
    if (context != null) {
      TextSecurePreferences.setHasSeenVideoRecordingTooltip(requireContext(), true);
    }
  }

  private void hideAndDisableControlsForVideoRecording(@NonNull View captureButton,
                                                       @NonNull View flashButton,
                                                       @NonNull View flipButton,
                                                       @NonNull Animation outAnimation)
  {
    captureButton.setEnabled(false);
    flashButton.startAnimation(outAnimation);
    flashButton.setVisibility(View.INVISIBLE);
    flipButton.startAnimation(outAnimation);
    flipButton.setVisibility(View.INVISIBLE);
  }

  private void showAndEnableControlsAfterVideoRecording(@NonNull View captureButton,
                                                        @NonNull View flashButton,
                                                        @NonNull View flipButton,
                                                        @NonNull Animation inAnimation)
  {
    Activity activity = getActivity();

    if (activity != null) {
      activity.runOnUiThread(() -> {
        captureButton.setEnabled(true);
        flashButton.startAnimation(inAnimation);
        flashButton.setVisibility(View.VISIBLE);
        flipButton.startAnimation(inAnimation);
        flipButton.setVisibility(View.VISIBLE);
      });
    }
  }

  private void onCaptureClicked() {
    Stopwatch stopwatch = new Stopwatch("Capture");

    CameraXSelfieFlashHelper flashHelper = new CameraXSelfieFlashHelper(
        requireActivity().getWindow(),
        cameraController,
        selfieFlash
    );

    flashHelper.onWillTakePicture();
    cameraController.takePicture(ContextCompat.getMainExecutor(requireContext()), new ImageCapture.OnImageCapturedCallback() {
      @Override
      public void onCaptureSuccess(@NonNull ImageProxy image) {
        flashHelper.endFlash();

        final boolean flip = cameraController.getCameraSelector() == CameraSelector.DEFAULT_FRONT_CAMERA;
        SimpleTask.run(CameraXFragment.this.getViewLifecycleOwner().getLifecycle(), () -> {
          stopwatch.split("captured");
          try {
            return CameraXUtil.toJpeg(image, flip);
          } catch (IOException e) {
            Log.w(TAG, "Failed to encode captured image.", e);
            return null;
          } finally {
            image.close();
          }
        }, result -> {
          stopwatch.split("transformed");
          stopwatch.stop(TAG);

          if (result != null) {
            controller.onImageCaptured(result.getData(), result.getWidth(), result.getHeight());
          } else {
            controller.onCameraError();
          }
        });
      }

      @Override
      public void onError(@NonNull ImageCaptureException exception) {
        Log.w(TAG, "Failed to capture image due to error " + exception.getImageCaptureError(), exception.getCause());
        flashHelper.endFlash();
        controller.onCameraError();
      }
    });

    flashHelper.startFlash();
  }

  private void closeVideoFileDescriptor() {
    if (videoFileDescriptor != null) {
      try {
        videoFileDescriptor.close();
        videoFileDescriptor = null;
      } catch (IOException e) {
        Log.w(TAG, "Failed to close video file descriptor", e);
      }
    }
  }

  @SuppressLint({ "MissingPermission" })
  private Unit initializeFlipButton(@NonNull View flipButton, @NonNull CameraXFlashToggleView flashButton) {
    if (getContext() == null) {
      Log.w(TAG, "initializeFlipButton called either before or after fragment was attached.");
      return Unit.INSTANCE;
    }

    if (!getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
      return Unit.INSTANCE;
    }

    getViewLifecycleOwner().getLifecycle().addObserver(cameraScreenBrightnessController);
    if (cameraController.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) && cameraController.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
      flipButton.setVisibility(View.VISIBLE);
      flipButton.setOnClickListener(v -> {
        CameraSelector cameraSelector = cameraController.getCameraSelector() == CameraSelector.DEFAULT_FRONT_CAMERA
                                        ? CameraSelector.DEFAULT_BACK_CAMERA
                                        : CameraSelector.DEFAULT_FRONT_CAMERA;
        cameraController.setCameraSelector(cameraSelector);
        TextSecurePreferences.setDirectCaptureCameraId(getContext(), CameraXUtil.toCameraDirectionInt(cameraController.getCameraSelector()));

        Animation animation = new RotateAnimation(0, -180, RotateAnimation.RELATIVE_TO_SELF, 0.5f, RotateAnimation.RELATIVE_TO_SELF, 0.5f);
        animation.setDuration(200);
        animation.setInterpolator(new DecelerateInterpolator());
        flipButton.startAnimation(animation);
        flashButton.setAutoFlashEnabled(cameraController.getImageCaptureFlashMode() >= ImageCapture.FLASH_MODE_AUTO);
        flashButton.setFlash(cameraController.getImageCaptureFlashMode());
        cameraScreenBrightnessController.onCameraDirectionChanged(cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA);
      });

      GestureDetector gestureDetector = new GestureDetector(requireContext(), new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
          if (flipButton.isEnabled()) {
            flipButton.performClick();
          }
          return true;
        }
      });

      previewView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

    } else {
      flipButton.setVisibility(View.GONE);
    }
    return Unit.INSTANCE;
  }

  private static class CameraStateProvider implements CameraScreenBrightnessController.CameraStateProvider {

    private final CameraXController cameraController;

    private CameraStateProvider(CameraXController cameraController) {
      this.cameraController = cameraController;
    }

    @Override
    public boolean isFrontFacingCameraSelected() {
      return cameraController.getCameraSelector() == CameraSelector.DEFAULT_FRONT_CAMERA;
    }

    @Override
    public boolean isFlashEnabled() {
      return cameraController.getImageCaptureFlashMode() == ImageCapture.FLASH_MODE_ON;
    }
  }

  private class CameraXOrientationListener extends OrientationEventListener {

    public CameraXOrientationListener(Context context) {
      super(context);
    }

    @Override
    public void onOrientationChanged(int orientation) {
      if (cameraController != null) {
        if (FeatureFlags.customCameraXController()) {
          cameraController.setImageRotation(orientation);
        }
      }
    }
  }
}
