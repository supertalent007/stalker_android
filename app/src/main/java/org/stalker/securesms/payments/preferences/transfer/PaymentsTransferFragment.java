package org.stalker.securesms.payments.preferences.transfer;

import android.Manifest;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavDirections;
import androidx.navigation.Navigation;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.LoggingFragment;
import org.stalker.securesms.R;
import org.stalker.securesms.payments.MobileCoinPublicAddress;
import org.stalker.securesms.payments.preferences.model.PayeeParcelable;
import org.stalker.securesms.permissions.Permissions;
import org.stalker.securesms.util.ViewUtil;
import org.stalker.securesms.util.navigation.SafeNavigation;

public final class PaymentsTransferFragment extends LoggingFragment {

  private static final String TAG = Log.tag(PaymentsTransferFragment.class);

  private EditText address;

  public PaymentsTransferFragment() {
    super(R.layout.payments_transfer_fragment);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    PaymentsTransferViewModel viewModel = new ViewModelProvider(Navigation.findNavController(view).getViewModelStoreOwner(R.id.payments_transfer), new PaymentsTransferViewModel.Factory()).get(PaymentsTransferViewModel.class);

    Toolbar toolbar = view.findViewById(R.id.payments_transfer_toolbar);

    view.findViewById(R.id.payments_transfer_scan_qr).setOnClickListener(v -> scanQrCode());
    view.findViewById(R.id.payments_transfer_next).setOnClickListener(v -> next(viewModel.getOwnAddress()));

    address = view.findViewById(R.id.payments_transfer_to_address);
    address.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_DONE) {
        return next(viewModel.getOwnAddress());
      }
      return false;
    });

    viewModel.getAddress().observe(getViewLifecycleOwner(), address::setText);

    toolbar.setNavigationOnClickListener(v -> {
      ViewUtil.hideKeyboard(requireContext(), v);
      Navigation.findNavController(v).popBackStack();
    });
  }

  private boolean next(@NonNull MobileCoinPublicAddress ownAddress) {
    try {
      String                  base58Address = address.getText().toString();
      MobileCoinPublicAddress publicAddress = MobileCoinPublicAddress.fromBase58(base58Address);

      if (ownAddress.equals(publicAddress)) {
        new MaterialAlertDialogBuilder(requireContext())
                       .setTitle(R.string.PaymentsTransferFragment__invalid_address)
                       .setMessage(R.string.PaymentsTransferFragment__you_cant_transfer_to_your_own_signal_wallet_address)
                       .setPositiveButton(android.R.string.ok, null)
                       .show();
        return false;
      }

      NavDirections action = PaymentsTransferFragmentDirections.actionPaymentsTransferToCreatePayment(new PayeeParcelable(publicAddress))
                                                               .setFinishOnConfirm(PaymentsTransferFragmentArgs.fromBundle(requireArguments()).getFinishOnConfirm());

      SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), action);
      return true;
    } catch (MobileCoinPublicAddress.AddressException e) {
      Log.w(TAG, "Address is not valid", e);
      new MaterialAlertDialogBuilder(requireContext())
                     .setTitle(R.string.PaymentsTransferFragment__invalid_address)
                     .setMessage(R.string.PaymentsTransferFragment__check_the_wallet_address)
                     .setPositiveButton(android.R.string.ok, null)
                     .show();
      return false;
    }
  }

  private void scanQrCode() {
    Permissions.with(this)
               .request(Manifest.permission.CAMERA)
               .ifNecessary()
               .withRationaleDialog(getString(R.string.CameraXFragment_allow_access_camera), getString(R.string.PaymentsTransferFragment__to_scan_a_qr_code_signal_needs), R.drawable.ic_camera_24)
               .withPermanentDenialDialog(getString(R.string.PaymentsTransferFragment__to_scan_a_qr_code_signal_needs_access_to_the_camera), null, R.string.CameraXFragment_allow_access_camera, R.string.CameraXFragment_to_scan_qr_codes, getParentFragmentManager())
               .onAllGranted(() -> SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), R.id.action_paymentsTransfer_to_paymentsScanQr))
               .onAnyDenied(() -> Toast.makeText(requireContext(), R.string.PaymentsTransferFragment__to_scan_a_qr_code_signal_needs_access_to_the_camera, Toast.LENGTH_LONG).show())
               .execute();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }
}
