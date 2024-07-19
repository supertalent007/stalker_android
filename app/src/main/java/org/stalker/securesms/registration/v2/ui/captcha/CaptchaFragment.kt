/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.registration.v2.ui.captcha

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import org.stalker.securesms.BuildConfig
import org.stalker.securesms.LoggingFragment
import org.stalker.securesms.R
import org.stalker.securesms.components.ViewBinderDelegate
import org.stalker.securesms.databinding.FragmentRegistrationCaptchaV2Binding
import org.stalker.securesms.registration.fragments.RegistrationConstants
import org.stalker.securesms.registration.v2.ui.RegistrationV2ViewModel

class CaptchaFragment : LoggingFragment(R.layout.fragment_registration_captcha_v2) {

  private val sharedViewModel by activityViewModels<RegistrationV2ViewModel>()
  private val binding: FragmentRegistrationCaptchaV2Binding by ViewBinderDelegate(FragmentRegistrationCaptchaV2Binding::bind)

  @SuppressLint("SetJavaScriptEnabled")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    binding.registrationCaptchaWebView.settings.javaScriptEnabled = true
    binding.registrationCaptchaWebView.clearCache(true)

    binding.registrationCaptchaWebView.webViewClient = object : WebViewClient() {
      @Deprecated("Deprecated in Java")
      override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        if (url.startsWith(RegistrationConstants.SIGNAL_CAPTCHA_SCHEME)) {
          val token = url.substring(RegistrationConstants.SIGNAL_CAPTCHA_SCHEME.length)
          sharedViewModel.setCaptchaResponse(token)
          findNavController().navigateUp()
          return true
        }
        return false
      }
    }

    binding.registrationCaptchaWebView.loadUrl(BuildConfig.SIGNAL_CAPTCHA_URL)
  }
}
