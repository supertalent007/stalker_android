package org.stalker.securesms.components.settings.app.subscription.donate.stripe

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.navigation.fragment.navArgs
import com.google.android.material.button.MaterialButton
import org.signal.donations.PaymentSourceType
import org.signal.donations.StripeIntentAccessor
import org.stalker.securesms.R
import org.stalker.securesms.components.ViewBinderDelegate
import org.stalker.securesms.components.settings.app.subscription.donate.DonationWebViewOnBackPressedCallback
import org.stalker.securesms.databinding.DonationWebviewFragmentBinding
import org.stalker.securesms.keyvalue.SignalStore
import org.stalker.securesms.util.FeatureFlags
import org.stalker.securesms.util.visible

/**
 * Full-screen dialog for displaying Stripe 3DS confirmation.
 */
class Stripe3DSDialogFragment : DialogFragment(R.layout.donation_webview_fragment) {

  companion object {
    const val REQUEST_KEY = "stripe_3ds_dialog_fragment"
    const val LAUNCHED_EXTERNAL = "stripe_3ds_dialog_fragment.pending"
  }

  val binding by ViewBinderDelegate(DonationWebviewFragmentBinding::bind) {
    it.webView.webViewClient = WebViewClient()
    it.webView.clearCache(true)
    it.webView.clearHistory()
  }

  val args: Stripe3DSDialogFragmentArgs by navArgs()

  var result: Bundle? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setStyle(STYLE_NO_FRAME, R.style.Signal_DayNight_Dialog_FullScreen)
  }

  @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    dialog!!.window!!.setFlags(
      WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
      WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    )

    binding.webView.webViewClient = Stripe3DSWebClient()
    binding.webView.settings.javaScriptEnabled = true
    binding.webView.settings.domStorageEnabled = true
    binding.webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
    binding.webView.loadUrl(args.uri.toString())

    (requireDialog() as ComponentDialog).onBackPressedDispatcher.addCallback(
      viewLifecycleOwner,
      DonationWebViewOnBackPressedCallback(
        this::dismissAllowingStateLoss,
        binding.webView
      )
    )

    if (FeatureFlags.internalUser() && args.stripe3DSData.paymentSourceType == PaymentSourceType.Stripe.IDEAL) {
      val openApp = MaterialButton(requireContext()).apply {
        text = "Open App"
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        setOnClickListener {
          handleLaunchExternal(Intent(Intent.ACTION_VIEW, args.uri))
        }
      }
      binding.root.addView(openApp)
    }
  }

  override fun onDismiss(dialog: DialogInterface) {
    super.onDismiss(dialog)
    val result = this.result
    this.result = null
    setFragmentResult(REQUEST_KEY, result ?: Bundle())
  }

  private fun handleLaunchExternal(intent: Intent) {
    SignalStore.donationsValues().setPending3DSData(args.stripe3DSData)

    result = bundleOf(
      LAUNCHED_EXTERNAL to true
    )

    startActivity(intent)
    dismissAllowingStateLoss()
  }

  private inner class Stripe3DSWebClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
      return ExternalNavigationHelper.maybeLaunchExternalNavigationIntent(requireContext(), request?.url, this@Stripe3DSDialogFragment::handleLaunchExternal)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
      binding.progress.visible = true
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
      binding.progress.visible = false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
      if (url?.startsWith(args.returnUri.toString()) == true) {
        val stripeIntentAccessor = StripeIntentAccessor.fromUri(url)

        result = bundleOf(REQUEST_KEY to stripeIntentAccessor)
        dismissAllowingStateLoss()
      }
    }
  }
}
