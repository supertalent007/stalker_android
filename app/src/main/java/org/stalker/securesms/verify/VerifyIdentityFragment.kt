package org.stalker.securesms.verify

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import org.signal.core.util.ThreadUtil
import org.signal.core.util.getParcelableCompat
import org.signal.qr.kitkat.ScanListener
import org.stalker.securesms.R
import org.stalker.securesms.components.WrapperDialogFragment
import org.stalker.securesms.crypto.IdentityKeyParcelable
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.permissions.Permissions
import org.stalker.securesms.recipients.Recipient
import org.stalker.securesms.recipients.RecipientId
import org.stalker.securesms.util.ServiceUtil

/**
 * Fragment to assist user in verifying recipient identity utilizing keys.
 */
class VerifyIdentityFragment : Fragment(R.layout.fragment_container), ScanListener, VerifyDisplayFragment.Callback {

  class Dialog : WrapperDialogFragment() {
    override fun getWrappedFragment(): Fragment {
      return VerifyIdentityFragment().apply {
        arguments = this@Dialog.requireArguments()
      }
    }
  }

  companion object {
    private const val EXTRA_RECIPIENT = "extra.recipient.id"
    private const val EXTRA_IDENTITY = "extra.recipient.identity"
    private const val EXTRA_VERIFIED = "extra.verified.state"

    @JvmStatic
    fun create(
      recipientId: RecipientId,
      remoteIdentity: IdentityKeyParcelable,
      verified: Boolean
    ): VerifyIdentityFragment {
      return VerifyIdentityFragment().apply {
        arguments = bundleOf(
          EXTRA_RECIPIENT to recipientId,
          EXTRA_IDENTITY to remoteIdentity,
          EXTRA_VERIFIED to verified
        )
      }
    }

    fun createDialog(
      recipientId: RecipientId,
      remoteIdentity: IdentityKeyParcelable,
      verified: Boolean
    ): Dialog {
      return Dialog().apply {
        arguments = bundleOf(
          EXTRA_RECIPIENT to recipientId,
          EXTRA_IDENTITY to remoteIdentity,
          EXTRA_VERIFIED to verified
        )
      }
    }
  }

  private val displayFragment by lazy {
    VerifyDisplayFragment.create(
      recipientId,
      remoteIdentity,
      IdentityKeyParcelable(SignalStore.account().aciIdentityKey.publicKey),
      Recipient.self().requireE164(),
      isVerified
    )
  }

  private val scanFragment = VerifyScanFragment()

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    childFragmentManager.beginTransaction()
      .replace(R.id.fragment_container, displayFragment)
      .commitAllowingStateLoss()
  }

  private val recipientId: RecipientId
    get() = requireArguments().getParcelableCompat(EXTRA_RECIPIENT, RecipientId::class.java)!!

  private val remoteIdentity: IdentityKeyParcelable
    get() = requireArguments().getParcelableCompat(EXTRA_IDENTITY, IdentityKeyParcelable::class.java)!!

  private val isVerified: Boolean
    get() = requireArguments().getBoolean(EXTRA_VERIFIED)

  override fun onQrDataFound(data: String) {
    ThreadUtil.runOnMain {
      ServiceUtil.getVibrator(context).vibrate(50)
      childFragmentManager.popBackStack()
      displayFragment.setScannedFingerprint(data)
    }
  }

  override fun onQrCodeContainerClicked() {
    Permissions.with(this)
      .request(Manifest.permission.CAMERA)
      .ifNecessary()
      .withRationaleDialog(getString(R.string.CameraXFragment_allow_access_camera), getString(R.string.CameraXFragment_to_scan_qr_code_allow_camera), R.drawable.ic_camera_24)
      .withPermanentDenialDialog(getString(R.string.VerifyIdentityActivity_signal_needs_the_camera_permission_in_order_to_scan_a_qr_code_but_it_has_been_permanently_denied), null, R.string.CameraXFragment_allow_access_camera, R.string.CameraXFragment_to_scan_qr_codes, getParentFragmentManager())
      .onAllGranted {
        childFragmentManager.beginTransaction()
          .setCustomAnimations(R.anim.slide_from_top, R.anim.slide_to_bottom, R.anim.slide_from_bottom, R.anim.slide_to_top)
          .replace(R.id.fragment_container, scanFragment)
          .addToBackStack(null)
          .commitAllowingStateLoss()
      }
      .onAnyDenied { Toast.makeText(requireContext(), R.string.CameraXFragment_signal_needs_camera_access_scan_qr_code, Toast.LENGTH_LONG).show() }
      .execute()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val callback = requireActivity().onBackPressedDispatcher.addCallback(this) {
      if (childFragmentManager.backStackEntryCount > 0) {
        childFragmentManager.popBackStack()
      } else {
        requireActivity().finish()
      }
    }
  }
}
