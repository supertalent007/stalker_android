package org.stalker.securesms.registration.fragments;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.gms.auth.api.phone.SmsRetriever;
import com.google.android.gms.auth.api.phone.SmsRetrieverClient;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.concurrent.LifecycleDisposable;
import org.signal.core.util.logging.Log;
import org.stalker.securesms.LoggingFragment;
import org.stalker.securesms.R;
import org.stalker.securesms.keyvalue.SignalStore;
import org.stalker.securesms.registration.RegistrationSessionProcessor;
import org.stalker.securesms.registration.VerifyAccountRepository.Mode;
import org.stalker.securesms.registration.util.RegistrationNumberInputController;
import org.stalker.securesms.registration.viewmodel.NumberViewState;
import org.stalker.securesms.registration.viewmodel.RegistrationViewModel;
import org.stalker.securesms.util.CommunicationActions;
import org.stalker.securesms.util.Debouncer;
import org.stalker.securesms.util.Dialogs;
import org.stalker.securesms.util.PlayServicesUtil;
import org.stalker.securesms.util.SupportEmailUtil;
import org.stalker.securesms.util.ViewUtil;
import org.stalker.securesms.util.dualsim.MccMncProducer;
import org.stalker.securesms.util.navigation.SafeNavigation;
import org.stalker.securesms.util.views.CircularProgressMaterialButton;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;

import static org.stalker.securesms.registration.fragments.RegistrationViewDelegate.setDebugLogSubmitMultiTapView;
import static org.stalker.securesms.registration.fragments.RegistrationViewDelegate.showConfirmNumberDialogIfTranslated;

public final class EnterPhoneNumberFragment extends LoggingFragment implements RegistrationNumberInputController.Callbacks {

  private static final String TAG = Log.tag(EnterPhoneNumberFragment.class);

  private TextInputLayout                countryCode;
  private TextInputLayout                number;
  private CircularProgressMaterialButton register;
  private View                           cancel;
  private ScrollView                     scrollView;
  private RegistrationViewModel          viewModel;

  private final LifecycleDisposable disposables = new LifecycleDisposable();

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setHasOptionsMenu(true);
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_registration_enter_phone_number, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    setDebugLogSubmitMultiTapView(view.findViewById(R.id.verify_header));

    countryCode = view.findViewById(R.id.country_code);
    number      = view.findViewById(R.id.number);
    cancel      = view.findViewById(R.id.cancel_button);
    scrollView  = view.findViewById(R.id.scroll_view);
    register    = view.findViewById(R.id.registerButton);

    RegistrationNumberInputController controller = new RegistrationNumberInputController(requireContext(),
                                                                                         this,
                                                                                         Objects.requireNonNull(number.getEditText()),
                                                                                         countryCode);
    register.setOnClickListener(v -> handleRegister(requireContext()));

    disposables.bindTo(getViewLifecycleOwner().getLifecycle());
    viewModel = new ViewModelProvider(requireActivity()).get(RegistrationViewModel.class);

    if (viewModel.isReregister()) {
      cancel.setVisibility(View.VISIBLE);
      cancel.setOnClickListener(v -> requireActivity().finish());
    } else {
      cancel.setVisibility(View.GONE);
    }

    viewModel.getLiveNumber().observe(getViewLifecycleOwner(), controller::updateNumberFormatter);

    if (viewModel.hasCaptchaToken()) {
      ThreadUtil.runOnMainDelayed(() -> handleRegister(requireContext()), 250);
    }

    Toolbar toolbar = view.findViewById(R.id.toolbar);
    ((AppCompatActivity) requireActivity()).setSupportActionBar(toolbar);
    final ActionBar supportActionBar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
    if (supportActionBar != null) {
      supportActionBar.setTitle(null);
    }

    final NumberViewState viewModelNumber = viewModel.getNumber();
    if (viewModelNumber.getCountryCode() == 0) {
      controller.prepopulateCountryCode();
    }
    controller.setNumberAndCountryCode(viewModelNumber);

    ViewUtil.focusAndShowKeyboard(number.getEditText());

    if (viewModel.hasUserSkippedReRegisterFlow() && viewModel.shouldAutoShowSmsConfirmDialog()) {
      viewModel.setAutoShowSmsConfirmDialog(false);
      ThreadUtil.runOnMainDelayed(() -> handleRegister(requireContext()), 250);
    }
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
    inflater.inflate(R.menu.enter_phone_number, menu);
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.phone_menu_use_proxy) {
      SafeNavigation.safeNavigate(Navigation.findNavController(requireView()), EnterPhoneNumberFragmentDirections.actionEditProxy());
      return true;
    } else {
      return false;
    }
  }

  private void handleRegister(@NonNull Context context) {
    if (viewModel.getNumber().getCountryCode() == 0) {
      showErrorDialog(context, getString(R.string.RegistrationActivity_you_must_specify_your_country_code));
      return;
    }

    if (TextUtils.isEmpty(viewModel.getNumber().getNationalNumber())) {
      showErrorDialog(context, getString(R.string.RegistrationActivity_please_enter_a_valid_phone_number_to_register));
      return;
    }

    final NumberViewState number     = viewModel.getNumber();
    final String          e164number = number.getE164Number();

    if (!number.isValid()) {
      Dialogs.showAlertDialog(context,
                              getString(R.string.RegistrationActivity_invalid_number),
                              String.format(getString(R.string.RegistrationActivity_the_number_you_specified_s_is_invalid), e164number));
      return;
    }

    PlayServicesUtil.PlayServicesStatus fcmStatus = PlayServicesUtil.getPlayServicesStatus(context);

    if (fcmStatus == PlayServicesUtil.PlayServicesStatus.SUCCESS) {
      confirmNumberPrompt(context, e164number, () -> onE164EnteredSuccessfully(context, true));
    } else if (fcmStatus == PlayServicesUtil.PlayServicesStatus.MISSING) {
      confirmNumberPrompt(context, e164number, () -> handlePromptForNoPlayServices(context));
    } else if (fcmStatus == PlayServicesUtil.PlayServicesStatus.NEEDS_UPDATE) {
      GoogleApiAvailability.getInstance().getErrorDialog(requireActivity(), ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, 0).show();
    } else {
      Dialogs.showAlertDialog(context,
                              getString(R.string.RegistrationActivity_play_services_error),
                              getString(R.string.RegistrationActivity_google_play_services_is_updating_or_unavailable));
    }
  }

  private void onE164EnteredSuccessfully(@NonNull Context context, boolean fcmSupported) {
    enterInProgressUiState();
    Log.d(TAG, "E164 entered successfully.");
    Disposable disposable = viewModel.canEnterSkipSmsFlow()
                                     .observeOn(AndroidSchedulers.mainThread())
                                     .onErrorReturnItem(false)
                                     .subscribe(canEnter -> {
                                       if (canEnter) {
                                         Log.i(TAG, "Entering skip flow.");
                                         SafeNavigation.safeNavigate(NavHostFragment.findNavController(this), EnterPhoneNumberFragmentDirections.actionReRegisterWithPinFragment());
                                       } else {
                                         Log.i(TAG, "Unable to collect necessary data to enter skip flow, returning to normal");
                                         handleRequestVerification(context, fcmSupported);
                                       }
                                     });
    disposables.add(disposable);
  }

  private void handleRequestVerification(@NonNull Context context, boolean fcmSupported) {
    if (fcmSupported) {
      SmsRetrieverClient client  = SmsRetriever.getClient(context);
      Task<Void>         task    = client.startSmsRetriever();
      AtomicBoolean      handled = new AtomicBoolean(false);

      Debouncer debouncer = new Debouncer(TimeUnit.SECONDS.toMillis(5));
      debouncer.publish(() -> {
        if (!handled.getAndSet(true)) {
          Log.w(TAG, "Timed out waiting for SMS listener!");
          requestVerificationCode(Mode.SMS_WITHOUT_LISTENER);
        }
      });

      task.addOnSuccessListener(none -> {
        if (!handled.getAndSet(true)) {
          Log.i(TAG, "Successfully registered SMS listener.");
          requestVerificationCode(Mode.SMS_WITH_LISTENER);
        } else {
          Log.w(TAG, "Successfully registered listener after timeout.");
        }
        debouncer.clear();
      });

      task.addOnFailureListener(e -> {
        if (!handled.getAndSet(true)) {
          Log.w(TAG, "Failed to register SMS listener.", e);
          requestVerificationCode(Mode.SMS_WITHOUT_LISTENER);
        } else {
          Log.w(TAG, "Failed to register listener after timeout.");
        }
        debouncer.clear();
      });

      task.addOnCanceledListener(() -> {
        if (!handled.getAndSet(true)) {
          Log.w(TAG, "SMS listener registration canceled.");
          requestVerificationCode(Mode.SMS_WITHOUT_LISTENER);
        } else {
          Log.w(TAG, "SMS listener registration canceled after timeout.");
        }
        debouncer.clear();
      });

    } else {
      Log.i(TAG, "FCM is not supported, using no SMS listener");
      requestVerificationCode(Mode.SMS_WITHOUT_LISTENER);
    }
  }

  private void enterInProgressUiState() {
    register.setSpinning();
    countryCode.setEnabled(false);
    number.setEnabled(false);
    cancel.setVisibility(View.GONE);
  }

  private void exitInProgressUiState() {
    register.cancelSpinning();
    countryCode.setEnabled(true);
    number.setEnabled(true);
    if (viewModel.isReregister()) {
      cancel.setVisibility(View.VISIBLE);
    }
  }

  private void requestVerificationCode(@NonNull Mode mode) {
    NavController                         navController       = NavHostFragment.findNavController(this);
    MccMncProducer                        mccMncProducer      = new MccMncProducer(requireContext());
    final DialogInterface.OnClickListener proceedToNextScreen = (dialog, which) -> SafeNavigation.safeNavigate(navController, EnterPhoneNumberFragmentDirections.actionEnterVerificationCode());
    Disposable request = viewModel.requestVerificationCode(mode, mccMncProducer.getMcc(), mccMncProducer.getMnc())
                                  .doOnSubscribe(unused -> SignalStore.account().setRegistered(false))
                                  .observeOn(AndroidSchedulers.mainThread())
                                  .subscribe((RegistrationSessionProcessor processor) -> {
                                    Context context = getContext();
                                    if (context == null) {
                                      Log.w(TAG, "[requestVerificationCode] Invalid context! Skipping.");
                                      return;
                                    }

                                    if (processor.verificationCodeRequestSuccess()) {
                                      disposables.add(updateFcmTokenValue());
                                      SafeNavigation.safeNavigate(navController, EnterPhoneNumberFragmentDirections.actionEnterVerificationCode());
                                    } else if (processor.captchaRequired(viewModel.getExcludedChallenges())) {
                                      Log.i(TAG, "Unable to request sms code due to captcha required");
                                      SafeNavigation.safeNavigate(navController, EnterPhoneNumberFragmentDirections.actionRequestCaptcha());
                                    } else if (processor.exhaustedVerificationCodeAttempts()) {
                                      Log.i(TAG, "Unable to request sms code due to exhausting attempts");
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_rate_limited_to_service));
                                    } else if (processor.rateLimit()) {
                                      Log.i(TAG, "Unable to request sms code due to rate limit");
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_rate_limited_to_try_again, formatMillisecondsToString(processor.getRateLimit())));
                                    } else if (processor.isImpossibleNumber()) {
                                      Log.w(TAG, "Impossible number", processor.getError());
                                      Dialogs.showAlertDialog(requireContext(),
                                                              context.getString(R.string.RegistrationActivity_invalid_number),
                                                              String.format(context.getString(R.string.RegistrationActivity_the_number_you_specified_s_is_invalid), viewModel.getNumber().getFullFormattedNumber()));
                                    } else if (processor.isNonNormalizedNumber()) {
                                      handleNonNormalizedNumberError(processor.getOriginalNumber(), processor.getNormalizedNumber(), mode);
                                    } else if (processor.isTokenRejected()) {
                                      Log.i(TAG, "The server did not accept the information.", processor.getError());
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_we_need_to_verify_that_youre_human));
                                    } else if (processor.externalServiceFailure()) {
                                      Log.w(TAG, "The server reported a failure with an external service.", processor.getError());
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_unable_to_connect_to_service), proceedToNextScreen);
                                    } else if (processor.invalidTransportModeFailure()) {
                                      Log.w(TAG, "The server reported an invalid transport mode failure.", processor.getError());
                                      new MaterialAlertDialogBuilder(context)
                                          .setMessage(R.string.RegistrationActivity_we_couldnt_send_you_a_verification_code)
                                          .setPositiveButton(R.string.RegistrationActivity_voice_call, (dialog, which) -> requestVerificationCode(Mode.PHONE_CALL))
                                          .setNegativeButton(R.string.RegistrationActivity_cancel, null)
                                          .show();
                                    } else if ( processor.isMalformedRequest()){
                                      Log.w(TAG, "The server reported a malformed request.", processor.getError());
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_unable_to_connect_to_service), proceedToNextScreen);

                                    } else if (processor.isRetryException()) {
                                      Log.w(TAG, "The server reported a failure that is retryable.", processor.getError());
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_unable_to_connect_to_service), proceedToNextScreen);
                                    } else {
                                      Log.i(TAG, "Unknown error during verification code request", processor.getError());
                                      showErrorDialog(context, context.getString(R.string.RegistrationActivity_unable_to_connect_to_service));
                                    }

                                    exitInProgressUiState();
                                  });

    disposables.add(request);
  }

  private Disposable updateFcmTokenValue() {
    return viewModel.updateFcmTokenValue().subscribe();
  }

  private String formatMillisecondsToString(long milliseconds) {
    long totalSeconds = milliseconds / 1000;
    long HH           = totalSeconds / 3600;
    long MM           = (totalSeconds % 3600) / 60;
    long SS           = totalSeconds % 60;
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", HH, MM, SS);
  }

  public void showErrorDialog(Context context, String msg) {
    showErrorDialog(context, msg, null);
  }

  public void showErrorDialog(Context context, String msg, DialogInterface.OnClickListener positiveButtonListener) {
    new MaterialAlertDialogBuilder(context).setMessage(msg).setPositiveButton(android.R.string.ok, positiveButtonListener).show();
  }

  @Override
  public void onNumberFocused() {
    scrollView.postDelayed(() -> scrollView.smoothScrollTo(0, register.getBottom()), 250);
  }

  @Override
  public void onNumberInputDone(@NonNull View view) {
    ViewUtil.hideKeyboard(requireContext(), view);
    handleRegister(requireContext());
  }

  @Override
  public void setNationalNumber(@NonNull String number) {
    viewModel.setNationalNumber(number);
  }

  @Override
  public void setCountry(int countryCode) {
    viewModel.onCountrySelected(null, countryCode);
  }

  @Override
  public void onStart() {
    super.onStart();
    String sessionE164 = viewModel.getSessionE164();
    if (sessionE164 != null && viewModel.getSessionId() != null && viewModel.getCaptchaToken() == null) {
      checkIfSessionIsInProgressAndAdvance(sessionE164);
    }
  }

  private void checkIfSessionIsInProgressAndAdvance(@NonNull String sessionE164) {
    NavController navController = NavHostFragment.findNavController(this);
    Disposable request = viewModel.validateSession(sessionE164)
                                  .observeOn(AndroidSchedulers.mainThread())
                                  .subscribe(processor -> {
                                    if (processor.hasResult() && processor.canSubmitProofImmediately()) {
                                      try {
                                        viewModel.restorePhoneNumberStateFromE164(sessionE164);
                                        SafeNavigation.safeNavigate(navController, EnterPhoneNumberFragmentDirections.actionEnterVerificationCode());
                                      } catch (NumberParseException numberParseException) {
                                        viewModel.resetSession();
                                      }
                                    } else {
                                      viewModel.resetSession();
                                    }
                                  });

    disposables.add(request);
  }

  private void handleNonNormalizedNumberError(@NonNull String originalNumber, @NonNull String normalizedNumber, @NonNull Mode mode) {
    try {
      Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse(normalizedNumber, null);

      new MaterialAlertDialogBuilder(requireContext())
          .setTitle(R.string.RegistrationActivity_non_standard_number_format)
          .setMessage(getString(R.string.RegistrationActivity_the_number_you_entered_appears_to_be_a_non_standard, originalNumber, normalizedNumber))
          .setNegativeButton(android.R.string.no, (d, i) -> d.dismiss())
          .setNeutralButton(R.string.RegistrationActivity_contact_signal_support, (d, i) -> {
            String subject = getString(R.string.RegistrationActivity_signal_android_phone_number_format);
            String body    = SupportEmailUtil.generateSupportEmailBody(requireContext(), R.string.RegistrationActivity_signal_android_phone_number_format, null, null);

            CommunicationActions.openEmail(requireContext(), SupportEmailUtil.getSupportEmailAddress(requireContext()), subject, body);
            d.dismiss();
          })
          .setPositiveButton(R.string.yes, (d, i) -> {
            countryCode.getEditText().setText(String.valueOf(phoneNumber.getCountryCode()));
            number.getEditText().setText(String.valueOf(phoneNumber.getNationalNumber()));
            requestVerificationCode(mode);
            d.dismiss();
          })
          .show();
    } catch (NumberParseException e) {
      Log.w(TAG, "Failed to parse number!", e);

      Dialogs.showAlertDialog(requireContext(),
                              getString(R.string.RegistrationActivity_invalid_number),
                              String.format(getString(R.string.RegistrationActivity_the_number_you_specified_s_is_invalid), viewModel.getNumber().getFullFormattedNumber()));
    }
  }

  private void handlePromptForNoPlayServices(@NonNull Context context) {
    Log.d(TAG, "Device does not have Play Services, showing consent dialog.");
    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.RegistrationActivity_missing_google_play_services)
        .setMessage(R.string.RegistrationActivity_this_device_is_missing_google_play_services)
        .setPositiveButton(R.string.RegistrationActivity_i_understand, (dialog1, which) -> onE164EnteredSuccessfully(context, false))
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void confirmNumberPrompt(@NonNull Context context,
                                   @NonNull String e164number,
                                   @NonNull Runnable onConfirmed)
  {
    enterInProgressUiState();

    disposables.add(
        viewModel.canEnterSkipSmsFlow()
                 .observeOn(AndroidSchedulers.mainThread())
                 .subscribe(canSkipSms -> {
                   Log.d(TAG, "Showing confirm number dialog. canSkipSms = " + canSkipSms + " hasUserSkipped = " + viewModel.hasUserSkippedReRegisterFlow());
                   final EditText editText = this.number.getEditText();
                   showConfirmNumberDialogIfTranslated(context,
                                                       viewModel.hasUserSkippedReRegisterFlow() ? R.string.RegistrationActivity_additional_verification_required
                                                                                                : R.string.RegistrationActivity_phone_number_verification_dialog_title,
                                                       canSkipSms ? null
                                                                  : R.string.RegistrationActivity_a_verification_code_will_be_sent_to_this_number,
                                                       e164number,
                                                       () -> {
                                                         Log.d(TAG, "User confirmed number.");
                                                         if (editText != null) {
                                                           ViewUtil.hideKeyboard(context, editText);
                                                         }
                                                         onConfirmed.run();
                                                       },
                                                       () -> {
                                                         Log.d(TAG, "User canceled confirm number, returning to edit number.");
                                                         exitInProgressUiState();
                                                         if (editText != null) {
                                                           ViewUtil.focusAndMoveCursorToEndAndOpenKeyboard(editText);
                                                         }
                                                       });
                            }
                 )
    );
  }
}
