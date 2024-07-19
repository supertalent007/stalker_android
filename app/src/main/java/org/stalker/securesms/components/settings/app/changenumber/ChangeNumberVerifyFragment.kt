package org.stalker.securesms.components.settings.app.changenumber

import android.content.Context
import android.content.DialogInterface.OnClickListener
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import org.signal.core.util.concurrent.LifecycleDisposable
import org.signal.core.util.logging.Log
import org.stalker.securesms.LoggingFragment
import org.stalker.securesms.R
import org.stalker.securesms.components.settings.app.changenumber.ChangeNumberUtil.changeNumberSuccess
import org.stalker.securesms.components.settings.app.changenumber.ChangeNumberUtil.getCaptchaArguments
import org.stalker.securesms.components.settings.app.changenumber.ChangeNumberUtil.getViewModel
import org.stalker.securesms.registration.RegistrationSessionProcessor
import org.stalker.securesms.registration.VerifyAccountRepository
import org.stalker.securesms.util.dualsim.MccMncProducer
import org.stalker.securesms.util.navigation.safeNavigate

private val TAG: String = Log.tag(ChangeNumberVerifyFragment::class.java)

class ChangeNumberVerifyFragment : LoggingFragment(R.layout.fragment_change_phone_number_verify) {
  private lateinit var viewModel: ChangeNumberViewModel

  private var requestingCaptcha: Boolean = false

  private val lifecycleDisposable: LifecycleDisposable = LifecycleDisposable()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleDisposable.bindTo(lifecycle)
    viewModel = getViewModel(this)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    val toolbar: Toolbar = view.findViewById(R.id.toolbar)
    toolbar.setTitle(R.string.ChangeNumberVerifyFragment__change_number)
    toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

    val status: TextView = view.findViewById(R.id.change_phone_number_verify_status)
    status.text = getString(R.string.ChangeNumberVerifyFragment__verifying_s, viewModel.number.fullFormattedNumber)

    if (!requestingCaptcha || viewModel.hasCaptchaToken()) {
      requestCode()
    } else {
      Log.d(TAG, "Captcha required.")
      Toast.makeText(requireContext(), R.string.ChangeNumberVerifyFragment__captcha_required, Toast.LENGTH_SHORT).show()
      findNavController().navigateUp()
    }
  }

  private fun requestCode() {
    val mode = if (ChangeNumberVerifyFragmentArgs.fromBundle(requireArguments()).smsListenerEnabled) VerifyAccountRepository.Mode.SMS_WITH_LISTENER else VerifyAccountRepository.Mode.SMS_WITHOUT_LISTENER
    val mccMncProducer = MccMncProducer(requireContext())
    lifecycleDisposable += viewModel
      .ensureDecryptionsDrained()
      .onErrorComplete()
      .andThen(viewModel.changeNumberWithRecoveryPassword())
      .flatMap { changed ->
        if (changed) {
          Log.d(TAG, "Successfully changed number using recovery password.")
          Single.just(RequestCodeResult.RecoveryPasswordWorked)
        } else {
          viewModel.requestVerificationCode(mode, mccMncProducer.mcc, mccMncProducer.mnc)
            .map { p -> RequestCodeResult.RequestedVerificationCode(p) }
        }
      }
      .observeOn(AndroidSchedulers.mainThread())
      .subscribe { result ->
        if (result is RequestCodeResult.RecoveryPasswordWorked) {
          changeNumberSuccess()
          return@subscribe
        }

        val processor: RegistrationSessionProcessor = (result as RequestCodeResult.RequestedVerificationCode).processor

        if (processor.verificationCodeRequestSuccess()) {
          Log.i(TAG, "Successfully requested SMS code.")
          findNavController().safeNavigate(R.id.action_changePhoneNumberVerifyFragment_to_changeNumberEnterCodeFragment)
        } else if (processor.captchaRequired(viewModel.excludedChallenges)) {
          Log.i(TAG, "Unable to request sms code due to captcha required")
          findNavController().safeNavigate(R.id.action_changePhoneNumberVerifyFragment_to_captchaFragment, getCaptchaArguments())
          requestingCaptcha = true
        } else if (processor.rateLimit()) {
          Log.i(TAG, "Unable to request sms code due to rate limit")
          showErrorDialog(requireContext(), R.string.RegistrationActivity_rate_limited_to_service) { _, _ -> findNavController().navigateUp() }
        } else {
          Log.w(TAG, "Unable to request sms code", processor.error)
          showErrorDialog(requireContext(), R.string.RegistrationActivity_unable_to_request_verification_code) { _, _ -> findNavController().navigateUp() }
        }
      }
  }

  private fun showErrorDialog(context: Context, @StringRes message: Int, onPositiveButtonClickListener: OnClickListener?) {
    MaterialAlertDialogBuilder(context).setMessage(message).setPositiveButton(android.R.string.ok, onPositiveButtonClickListener).show()
  }

  private sealed interface RequestCodeResult {
    object RecoveryPasswordWorked : RequestCodeResult
    class RequestedVerificationCode(val processor: RegistrationSessionProcessor) : RequestCodeResult
  }
}
