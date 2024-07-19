/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.components.settings.app.subscription.donate.transfer.details

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import org.signal.core.ui.Buttons
import org.signal.core.ui.Scaffolds
import org.signal.core.ui.Texts
import org.signal.core.ui.theme.SignalTheme
import org.signal.core.util.getParcelableCompat
import org.stalker.securesms.R
import org.stalker.securesms.components.TemporaryScreenshotSecurity
import org.stalker.securesms.components.settings.app.subscription.DonationPaymentComponent
import org.stalker.securesms.components.settings.app.subscription.donate.DonateToSignalType
import org.stalker.securesms.components.settings.app.subscription.donate.DonationCheckoutDelegate
import org.stalker.securesms.components.settings.app.subscription.donate.DonationProcessorAction
import org.stalker.securesms.components.settings.app.subscription.donate.DonationProcessorActionResult
import org.stalker.securesms.components.settings.app.subscription.donate.gateway.GatewayRequest
import org.stalker.securesms.components.settings.app.subscription.donate.stripe.StripePaymentInProgressFragment
import org.stalker.securesms.components.settings.app.subscription.donate.stripe.StripePaymentInProgressViewModel
import org.stalker.securesms.components.settings.app.subscription.donate.transfer.BankTransferRequestKeys
import org.stalker.securesms.components.settings.app.subscription.donate.transfer.details.BankTransferDetailsViewModel.Field
import org.stalker.securesms.components.settings.app.subscription.errors.DonationErrorSource
import org.stalker.securesms.compose.ComposeFragment
import org.stalker.securesms.payments.FiatMoneyUtil
import org.stalker.securesms.util.SpanUtil
import org.stalker.securesms.util.fragments.requireListener
import org.stalker.securesms.util.navigation.safeNavigate

/**
 * Collects SEPA Debit bank transfer details from the user to proceed with donation.
 */
class BankTransferDetailsFragment : ComposeFragment(), DonationCheckoutDelegate.ErrorHandlerCallback {

  private val args: BankTransferDetailsFragmentArgs by navArgs()
  private val viewModel: BankTransferDetailsViewModel by viewModels()

  private val stripePaymentViewModel: StripePaymentInProgressViewModel by navGraphViewModels(
    R.id.donate_to_signal,
    factoryProducer = {
      StripePaymentInProgressViewModel.Factory(requireListener<DonationPaymentComponent>().stripeRepository)
    }
  )

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    TemporaryScreenshotSecurity.bindToViewLifecycleOwner(this)

    val errorSource: DonationErrorSource = when (args.request.donateToSignalType) {
      DonateToSignalType.ONE_TIME -> DonationErrorSource.ONE_TIME
      DonateToSignalType.MONTHLY -> DonationErrorSource.MONTHLY
      DonateToSignalType.GIFT -> DonationErrorSource.GIFT
    }

    DonationCheckoutDelegate.ErrorHandler().attach(this, this, args.request.uiSessionKey, errorSource)

    setFragmentResultListener(StripePaymentInProgressFragment.REQUEST_KEY) { _, bundle ->
      val result: DonationProcessorActionResult = bundle.getParcelableCompat(StripePaymentInProgressFragment.REQUEST_KEY, DonationProcessorActionResult::class.java)!!
      if (result.status == DonationProcessorActionResult.Status.SUCCESS) {
        findNavController().popBackStack(R.id.donateToSignalFragment, false)
        setFragmentResult(BankTransferRequestKeys.REQUEST_KEY, bundle)
      }
    }
  }

  @Composable
  override fun FragmentContent() {
    val state: BankTransferDetailsState by viewModel.state

    val donateLabel = remember(args.request) {
      if (args.request.donateToSignalType == DonateToSignalType.MONTHLY) {
        getString(
          R.string.BankTransferDetailsFragment__donate_s_month,
          FiatMoneyUtil.format(resources, args.request.fiat, FiatMoneyUtil.formatOptions().trimZerosAfterDecimal())
        )
      } else {
        getString(
          R.string.BankTransferDetailsFragment__donate_s,
          FiatMoneyUtil.format(resources, args.request.fiat)
        )
      }
    }

    BankTransferDetailsContent(
      state = state,
      onNavigationClick = this::onNavigationClick,
      onNameChanged = viewModel::onNameChanged,
      onIBANChanged = viewModel::onIBANChanged,
      onEmailChanged = viewModel::onEmailChanged,
      setDisplayFindAccountInfoSheet = viewModel::setDisplayFindAccountInfoSheet,
      onLearnMoreClick = this::onLearnMoreClick,
      onDonateClick = this::onDonateClick,
      onFocusChanged = viewModel::onFocusChanged,
      donateLabel = donateLabel
    )
  }

  private fun onNavigationClick() {
    findNavController().popBackStack()
  }

  private fun onLearnMoreClick() {
    findNavController().safeNavigate(
      BankTransferDetailsFragmentDirections.actionBankTransferDetailsFragmentToYourInformationIsPrivateBottomSheet()
    )
  }

  private fun onDonateClick() {
    stripePaymentViewModel.provideSEPADebitData(viewModel.state.value.asSEPADebitData())
    findNavController().safeNavigate(
      BankTransferDetailsFragmentDirections.actionBankTransferDetailsFragmentToStripePaymentInProgressFragment(
        DonationProcessorAction.PROCESS_NEW_DONATION,
        args.request
      )
    )
  }

  override fun onUserLaunchedAnExternalApplication() = Unit

  override fun navigateToDonationPending(gatewayRequest: GatewayRequest) {
    setFragmentResult(BankTransferRequestKeys.PENDING_KEY, bundleOf(BankTransferRequestKeys.PENDING_KEY to gatewayRequest))
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
      override fun onResume(owner: LifecycleOwner) {
        findNavController().popBackStack(R.id.donateToSignalFragment, false)
      }
    })
  }
}

@Preview
@Composable
private fun BankTransferDetailsContentPreview() {
  SignalTheme {
    BankTransferDetailsContent(
      state = BankTransferDetailsState(
        name = "Miles Morales",
        displayFindAccountInfoSheet = true
      ),
      onNavigationClick = {},
      onNameChanged = {},
      onIBANChanged = {},
      onEmailChanged = {},
      setDisplayFindAccountInfoSheet = {},
      onLearnMoreClick = {},
      onDonateClick = {},
      onFocusChanged = { _, _ -> },
      donateLabel = "Donate $5/month"
    )
  }
}

@Composable
private fun BankTransferDetailsContent(
  state: BankTransferDetailsState,
  onNavigationClick: () -> Unit,
  onNameChanged: (String) -> Unit,
  onIBANChanged: (String) -> Unit,
  onEmailChanged: (String) -> Unit,
  setDisplayFindAccountInfoSheet: (Boolean) -> Unit,
  onLearnMoreClick: () -> Unit,
  onDonateClick: () -> Unit,
  onFocusChanged: (Field, Boolean) -> Unit,
  donateLabel: String
) {
  Scaffolds.Settings(
    title = "Bank transfer",
    onNavigationClick = onNavigationClick,
    navigationIconPainter = painterResource(id = R.drawable.symbol_arrow_left_24)
  ) {
    Column(
      horizontalAlignment = CenterHorizontally,
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight()
        .padding(it)
    ) {
      val focusManager = LocalFocusManager.current
      val focusRequester = remember { FocusRequester() }

      LazyColumn(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 24.dp)
      ) {
        item {
          val learnMore = stringResource(id = R.string.BankTransferDetailsFragment__learn_more)
          val fullString = stringResource(id = R.string.BankTransferDetailsFragment__enter_your_bank_details, learnMore)

          Texts.LinkifiedText(
            textWithUrlSpans = SpanUtil.urlSubsequence(fullString, learnMore, stringResource(id = R.string.donate_faq_url)),
            onUrlClick = {
              onLearnMoreClick()
            },
            style = MaterialTheme.typography.bodyMedium.copy(
              color = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.padding(vertical = 12.dp)
          )
        }

        item {
          TextField(
            value = state.iban,
            onValueChange = onIBANChanged,
            label = {
              Text(text = stringResource(id = R.string.BankTransferDetailsFragment__iban))
            },
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.Characters,
              imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
              onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            isError = state.ibanValidity.isError,
            supportingText = {
              if (state.ibanValidity.isError) {
                Text(
                  text = when (state.ibanValidity) {
                    IBANValidator.Validity.TOO_SHORT -> stringResource(id = R.string.BankTransferDetailsFragment__iban_is_too_short)
                    IBANValidator.Validity.TOO_LONG -> stringResource(id = R.string.BankTransferDetailsFragment__iban_is_too_long)
                    IBANValidator.Validity.INVALID_COUNTRY -> stringResource(id = R.string.BankTransferDetailsFragment__iban_country_code_is_not_supported)
                    IBANValidator.Validity.INVALID_CHARACTERS -> stringResource(id = R.string.BankTransferDetailsFragment__invalid_iban)
                    IBANValidator.Validity.INVALID_MOD_97 -> stringResource(id = R.string.BankTransferDetailsFragment__invalid_iban)
                    else -> error("Unexpected error.")
                  }
                )
              }
            },
            visualTransformation = IBANVisualTransformation,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 12.dp)
              .defaultMinSize(minHeight = 78.dp)
              .onFocusChanged { onFocusChanged(Field.IBAN, it.hasFocus) }
              .focusRequester(focusRequester)
          )
        }

        item {
          TextField(
            value = state.name,
            onValueChange = onNameChanged,
            label = {
              Text(text = stringResource(id = R.string.BankTransferDetailsFragment__name_on_bank_account))
            },
            keyboardOptions = KeyboardOptions(
              capitalization = KeyboardCapitalization.Words,
              imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
              onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            isError = state.showNameError(),
            supportingText = {
              if (state.showNameError()) {
                Text(text = stringResource(id = R.string.BankTransferDetailsFragment__minimum_2_characters))
              }
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 16.dp)
              .defaultMinSize(minHeight = 78.dp)
              .onFocusChanged { onFocusChanged(Field.NAME, it.hasFocus) }
          )
        }

        item {
          TextField(
            value = state.email,
            onValueChange = onEmailChanged,
            label = {
              Text(text = stringResource(id = R.string.BankTransferDetailsFragment__email))
            },
            keyboardOptions = KeyboardOptions(
              keyboardType = KeyboardType.Email,
              imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
              onDone = { onDonateClick() }
            ),
            isError = state.showEmailError(),
            supportingText = {
              if (state.showEmailError()) {
                Text(text = stringResource(id = R.string.BankTransferDetailsFragment__invalid_email_address))
              }
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 16.dp)
              .defaultMinSize(minHeight = 78.dp)
              .onFocusChanged { onFocusChanged(Field.EMAIL, it.hasFocus) }
          )
        }

        item {
          Box(
            contentAlignment = Center,
            modifier = Modifier
              .fillMaxWidth()
              .padding(top = 8.dp)
          ) {
            TextButton(
              onClick = { setDisplayFindAccountInfoSheet(true) }
            ) {
              Text(text = stringResource(id = R.string.BankTransferDetailsFragment__find_account_info))
            }
          }
        }
      }

      Buttons.LargeTonal(
        enabled = state.canProceed,
        onClick = onDonateClick,
        modifier = Modifier
          .defaultMinSize(minWidth = 220.dp)
          .padding(vertical = 16.dp)
      ) {
        Text(text = donateLabel)
      }

      if (state.displayFindAccountInfoSheet) {
        FindAccountInfoSheet { setDisplayFindAccountInfoSheet(false) }
      }

      LaunchedEffect(Unit) {
        focusRequester.requestFocus()
      }
    }
  }
}
