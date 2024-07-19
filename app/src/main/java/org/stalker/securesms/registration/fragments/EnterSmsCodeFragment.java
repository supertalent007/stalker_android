package org.stalker.securesms.registration.fragments;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;

import org.signal.core.util.logging.Log;
import org.stalker.securesms.R;
import org.stalker.securesms.registration.viewmodel.RegistrationViewModel;
import org.stalker.securesms.util.FeatureFlags;
import org.signal.core.util.concurrent.SimpleTask;
import org.stalker.securesms.util.navigation.SafeNavigation;

import java.io.IOException;

public final class EnterSmsCodeFragment extends BaseEnterSmsCodeFragment<RegistrationViewModel> implements SignalStrengthPhoneStateListener.Callback {

  private static final String TAG = Log.tag(EnterSmsCodeFragment.class);

  public EnterSmsCodeFragment() {
    super(R.layout.fragment_registration_enter_code);
  }

  @Override
  protected @NonNull RegistrationViewModel getViewModel() {
    return new ViewModelProvider(requireActivity()).get(RegistrationViewModel.class);
  }

  @Override
  protected void handleSuccessfulVerify() {
    SimpleTask.run(() -> {
      long startTime = System.currentTimeMillis();
      try {
        FeatureFlags.refreshSync();
        Log.i(TAG, "Took " + (System.currentTimeMillis() - startTime) + " ms to get feature flags.");
      } catch (IOException e) {
        Log.w(TAG, "Failed to refresh flags after " + (System.currentTimeMillis() - startTime) + " ms.", e);
      }
      return null;
    }, none -> displaySuccess(() -> SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), EnterSmsCodeFragmentDirections.actionSuccessfulRegistration())));
  }

  @Override
  protected void navigateToRegistrationLock(long timeRemaining) {
    SafeNavigation.safeNavigate(Navigation.findNavController(requireView()),
                                EnterSmsCodeFragmentDirections.actionRequireKbsLockPin(timeRemaining));
  }

  @Override
  protected void navigateToCaptcha() {
    SafeNavigation.safeNavigate(NavHostFragment.findNavController(this), EnterSmsCodeFragmentDirections.actionRequestCaptcha());
  }

  @Override
  protected void navigateToKbsAccountLocked() {
    SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), RegistrationLockFragmentDirections.actionAccountLocked());
  }
}
