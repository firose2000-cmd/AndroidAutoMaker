package com.automaker.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.automaker.app.databinding.ActivityDonationBinding

class DonationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDonationBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDonationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        val webView = binding.checkout
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return completeIfPaid(request.url.toString())
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                binding.progress.visibility = android.view.View.VISIBLE
                completeIfPaid(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                binding.progress.visibility = android.view.View.GONE
                completeIfPaid(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                if (completeIfPaid(request.url.toString())) return
                binding.progress.visibility = android.view.View.GONE
            }
        }
        if (savedInstanceState == null) webView.loadUrl(CHECKOUT_URL)
        else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.checkout.saveState(outState)
    }

    private fun completeIfPaid(url: String): Boolean {
        if (!url.startsWith(SUCCESS_URL)) return false
        setResult(RESULT_OK)
        finish()
        return true
    }

    companion object {
        const val CHECKOUT_URL = "https://buy.stripe.com/4gM14n7WA3dDaVq0yi8AE01"
        private const val SUCCESS_URL = "https://automaker.invalid/donation/success"
    }
}
