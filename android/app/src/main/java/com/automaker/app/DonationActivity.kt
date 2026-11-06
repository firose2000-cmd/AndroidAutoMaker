package com.automaker.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Checkout belongs in the user's browser, without network or WebView privileges in Maker. */
class DonationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CHECKOUT_URL))) }
        finish()
    }

    companion object {
        const val CHECKOUT_URL = "https://buy.stripe.com/4gM14n7WA3dDaVq0yi8AE01"
    }
}
