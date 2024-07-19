package org.stalker.securesms.mediasend.v2

import android.Manifest
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import org.stalker.securesms.R
import org.stalker.securesms.mediasend.camerax.CameraXUtil
import org.stalker.securesms.permissions.PermissionCompat
import org.stalker.securesms.permissions.Permissions
import org.stalker.securesms.util.navigation.safeNavigate

class MediaSelectionNavigator(
  private val toCamera: Int = -1,
  private val toGallery: Int = -1
) {
  fun goToReview(navController: NavController) {
    navController.popBackStack(R.id.mediaReviewFragment, false)
  }

  fun goToCamera(navController: NavController) {
    if (toCamera == -1) return

    navController.safeNavigate(toCamera)
  }

  fun goToGallery(navController: NavController) {
    if (toGallery == -1) return

    navController.safeNavigate(toGallery)
  }

  companion object {
    fun Fragment.requestPermissionsForCamera(
      onGranted: () -> Unit
    ) {
      if (CameraXUtil.isSupported()) {
        onGranted()
      } else {
        Permissions.with(this)
          .request(Manifest.permission.CAMERA)
          .ifNecessary()
          .withRationaleDialog(getString(R.string.CameraXFragment_allow_access_camera), getString(R.string.CameraXFragment_to_capture_photos_and_video_allow_camera), R.drawable.ic_camera_24)
          .withPermanentDenialDialog(getString(R.string.CameraXFragment_signal_needs_camera_access_capture_photos), null, R.string.CameraXFragment_allow_access_camera, R.string.CameraXFragment_to_capture_photos_videos, getParentFragmentManager())
          .onAllGranted(onGranted)
          .onAnyDenied { Toast.makeText(requireContext(), R.string.CameraXFragment_signal_needs_camera_access_capture_photos, Toast.LENGTH_LONG).show() }
          .execute()
      }
    }

    fun Fragment.requestPermissionsForGallery(
      onGranted: () -> Unit
    ) {
      Permissions.with(this)
        .request(*PermissionCompat.forImagesAndVideos())
        .ifNecessary()
        .withPermanentDenialDialog(getString(R.string.AttachmentKeyboard_Signal_needs_permission_to_show_your_photos_and_videos))
        .onAllGranted(onGranted)
        .onAnyDenied { Toast.makeText(this.requireContext(), R.string.AttachmentKeyboard_Signal_needs_permission_to_show_your_photos_and_videos, Toast.LENGTH_LONG).show() }
        .execute()
    }
  }
}
