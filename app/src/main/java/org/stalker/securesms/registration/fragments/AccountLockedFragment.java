package org.stalker.securesms.registration.fragments;

import androidx.lifecycle.ViewModelProvider;

import org.stalker.securesms.R;
import org.stalker.securesms.registration.viewmodel.BaseRegistrationViewModel;
import org.stalker.securesms.registration.viewmodel.RegistrationViewModel;

public class AccountLockedFragment extends BaseAccountLockedFragment {

  public AccountLockedFragment() {
    super(R.layout.account_locked_fragment);
  }

  @Override
  protected BaseRegistrationViewModel getViewModel() {
    return new ViewModelProvider(requireActivity()).get(RegistrationViewModel.class);
  }

  @Override
  protected void onNext() {
    requireActivity().finish();
  }
}
