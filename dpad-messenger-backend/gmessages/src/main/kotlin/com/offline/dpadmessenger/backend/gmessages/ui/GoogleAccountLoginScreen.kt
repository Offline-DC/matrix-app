package com.offline.dpadmessenger.backend.gmessages.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.offline.dpadmessenger.backend.gmessages.GMCookieAuth
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.onDpadAction

/**
 * Phase 1 of the GAIA / cookie-auth port: sign in to a Google account in a
 * WebView and harvest the Messages-for-web cookies (SID/HSID/OSID/SSID/APISID/
 * SAPISID, + __Secure-1PSIDTS if present). Those cookies authenticate the
 * relay calls in the cookie-auth flow (see GMESSAGES_GAIA_PORT.md).
 *
 * NOTE / KNOWN RISK: Google blocks sign-in inside embedded WebViews on some
 * flows ("this browser may not be secure" / disallowed_useragent). We set a
 * desktop Chrome user-agent to reduce that, but if Google still blocks it we'll
 * need a computer-companion cookie transfer instead. This screen is exactly the
 * thing to validate that on-device.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleAccountLoginScreen(
    onCookies: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                WebView(ctx).apply {
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Desktop UA to dodge Google's embedded-WebView login block.
                    settings.userAgentString =
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            cookieManager.flush()
                            // Once we land on the Messages web app, the account
                            // cookies are set — harvest and check completeness.
                            if (url != null && url.contains("messages.google.com")) {
                                val merged = HashMap<String, String>()
                                merged += GMCookieAuth.parseCookieHeader(
                                    cookieManager.getCookie("https://google.com"),
                                )
                                merged += GMCookieAuth.parseCookieHeader(
                                    cookieManager.getCookie("https://messages.google.com"),
                                )
                                if (GMCookieAuth.hasRequiredCookies(merged)) {
                                    onCookies(merged)
                                }
                            }
                        }
                    }
                    // continue=/web/config keeps this an account login, not a
                    // browser pair (matches the mautrix cookie-extraction URL).
                    loadUrl(
                        "https://accounts.google.com/AccountChooser?continue=" +
                            "https://messages.google.com/web/config",
                    )
                }
            },
        )

        // Back/cancel affordance (DPAD-focusable; Back key also cancels).
        val backFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .focusRequester(backFocus)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .dpadFocusHighlight(shape = CircleShape)
                    .focusable()
                    .onDpadAction { onCancel(); true },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = "Sign in to Google",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
