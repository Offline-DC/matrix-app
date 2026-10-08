package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.dpadmessenger.backend.smarttxt.R
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtRepository
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtStatus
import com.offline.dpadmessenger.backend.smarttxt.MacOSConfig
import com.offline.dpadmessenger.backend.smarttxt.RegistrationResult
import com.offline.dpadmessenger.ui.components.DpadButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Set up smart txt" onboarding flow. A multi-step state machine mirroring the
 * polish of `SignalLinkScreen` / the gmessages sign-in: the user enters an
 * Apple ID + password and (on the real path) answers a two-factor challenge;
 * registration runs through the active transport → relay → IDS register.
 *
 * With the in-process mock relay (blank URL) this "registers" straight through
 * — the 2FA provider is never invoked — so the chat UI unlocks for testing;
 * with a real relay it does the real interactive round-trip. The relay itself
 * is configured by the host app via [SmartTxtConfig]; there's no in-screen
 * relay entry.
 *
 * The entry point / file name is kept as [SmartTxtSetupScreen] so the gate
 * ([SmartTxtApp]) and any other callers don't break.
 */

/** Local UI steps for the sign-in flow. */
/**
 * INTRO ("Set up smart txt" / Get started) and PRIVACY ("Head's up" / I
 * understand) were removed: Smart Txt now opens straight on the sign-in form
 * (Figma "onboarding - 17"). The iCloud "a Mac is signing in" explanation
 * lives in the launcher's onboarding instead, on its "head's up" screen
 * before Smart Txt is launched.
 */
private enum class SignInStep { CREDENTIALS, TWO_FACTOR, FSA, REGISTERING, SUCCESS }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SmartTxtSetupScreen(
    modifier: Modifier = Modifier,
    /** Shown on the sign-in page, under the body. Non-null when the user was signed out for a reason
     *  they did not choose - currently only a terminal IDS 6005. Persisted, so it is
     *  still here after a cold start. */
    notice: String? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val status by SmartTxtRepository.status.collectAsState()

    var step by remember { mutableStateOf(SignInStep.CREDENTIALS) }

    // TEST-ONLY dev pre-fill so sign-in can be tapped through without retyping.
    // REMOVE before any real release — these are live credentials in the APK/source.
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var twoFactorCode by remember { mutableStateOf("") }
    // Set while the 2FA provider is awaiting a code from the UI; Verify/Back
    // complete it (code / null).
    var pending2fa by remember { mutableStateOf<CompletableDeferred<String?>?>(null) }

    // FSA (security-key) challenge relayed to the companion phone. `pendingFsa`
    // holds the challenge while on the FSA screen; the companion's response (or a
    // Back cancel) arrives via SmartTxtFsaBridge.
    var pendingFsa by remember { mutableStateOf<com.offline.dpadmessenger.backend.smarttxt.FsaChallenge?>(null) }

    var error by remember { mutableStateOf<String?>(null) }
    // Set when the user backs out of the 2FA page on purpose. Completing the
    // pending code with null makes the native login fail with "A verification
    // code is required to sign in." — true, but not an error the user needs a
    // page for: they chose to leave. The flag turns that one Failure into a
    // quiet return to the filled-in form.
    var cancelledByUser by remember { mutableStateOf(false) }
    // Set by "resend" on the 2FA page. Resending means cancelling the pending
    // code request and logging in again, which makes Apple push a fresh code;
    // the cancel surfaces as a Failure, which this flag turns into a restart
    // instead of the usual bounce back to the sign-in form.
    var resending by remember { mutableStateOf(false) }
    // Loading shown ON the buttons (Sign in / Verify) instead of a full-screen
    // "Registering…" page between steps.
    var signingIn by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }

    // DPAD focus handles so the hardware pad moves predictably down the form.
    val appleIdFr = remember { FocusRequester() }
    val passwordFr = remember { FocusRequester() }
    val twoFactorFr = remember { FocusRequester() }
    val fsaBackFr = remember { FocusRequester() }

    // The chat unlocks the instant the repository flips REGISTERED (the gate
    // swaps this screen out); mirror that here so the flow shows SUCCESS.
    if (status == SmartTxtStatus.REGISTERED && step != SignInStep.SUCCESS) {
        step = SignInStep.SUCCESS
    }

    fun startRegistration() {
        error = null
        cancelledByUser = false
        twoFactorCode = ""
        pending2fa = null
        // Loading shows on the Sign in button; we stay on the CREDENTIALS page.
        signingIn = true
        // Dispatchers.IO: the native login blocks the calling thread; keep it OFF
        // the main thread so the UI stays responsive (Compose state writes are
        // safe from a background thread).
        scope.launch(Dispatchers.IO) {
            val result = SmartTxtRepository.register(
                context = context,
                config = MacOSConfig.placeholder(),
                appleId = appleId.trim().ifBlank { "demo@icloud.com" },
                password = password,
                twoFactorProvider = {
                    // Login succeeded and Apple wants a code: go STRAIGHT to the 2FA
                    // page (no REGISTERING page), and suspend until the UI collects
                    // it. Verify completes it with the code; Back completes it with
                    // null (cancel).
                    val deferred = CompletableDeferred<String?>()
                    pending2fa = deferred
                    twoFactorCode = ""
                    signingIn = false
                    step = SignInStep.TWO_FACTOR
                    deferred.await()
                },
                fsaProvider = { challenge ->
                    // Apple wants a security-key verification, answered on the paired
                    // companion phone. Show the FSA screen (which relays the challenge
                    // over typesync) and suspend until the companion's assertion is
                    // delivered (launcher → SmartTxtFsaBridge), or the user backs out.
                    pendingFsa = challenge
                    signingIn = false
                    step = SignInStep.FSA
                    val response =
                        com.offline.dpadmessenger.backend.smarttxt.SmartTxtFsaBridge.awaitResponse()
                    if (response != null) step = SignInStep.REGISTERING
                    response
                },
            )
            pending2fa = null
            signingIn = false
            verifying = false
            when (result) {
                is RegistrationResult.Failure -> {
                    if (resending) {
                        resending = false
                        android.util.Log.i("SmartTxtSignIn", "2FA resend — logging in again for a new code")
                        startRegistration()
                    } else if (cancelledByUser) {
                        cancelledByUser = false
                        android.util.Log.i("SmartTxtSignIn", "2FA backed out by the user — no error page (${result.message})")
                        step = SignInStep.CREDENTIALS
                    } else {
                        error = result.message
                        step = SignInStep.CREDENTIALS
                    }
                }
                is RegistrationResult.Success -> {
                    // The gate flips to chat on REGISTERED; show a transient
                    // confirmation until it does.
                    step = SignInStep.SUCCESS
                }
            }
        }
    }

    // Use the launcher's Helvetica (Helvetica Now Text) across the whole sign-in
    // flow by overriding the typography styles this screen, DpadButton, and
    // OutlinedTextField read from the ambient MaterialTheme.
    val helvetica = remember { FontFamily(Font(R.font.helvetica_now_text_black)) }
    val typo = MaterialTheme.typography
    MaterialTheme(
        typography = typo.copy(
            headlineSmall = typo.headlineSmall.copy(fontFamily = helvetica),
            titleSmall = typo.titleSmall.copy(fontFamily = helvetica),
            bodyLarge = typo.bodyLarge.copy(fontFamily = helvetica),
            bodyMedium = typo.bodyMedium.copy(fontFamily = helvetica),
            bodySmall = typo.bodySmall.copy(fontFamily = helvetica),
            labelLarge = typo.labelLarge.copy(fontFamily = helvetica),
        ),
    ) {
    // The sign-in page paints its own white background edge to edge and uses
    // the launcher onboarding's 12dp gutter; every other step keeps this
    // screen's original 24/12dp padding until it is redesigned too.
    val outer = if (step == SignInStep.CREDENTIALS || step == SignInStep.TWO_FACTOR ||
        step == SignInStep.REGISTERING) modifier.fillMaxSize()
        else modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)
    Box(modifier = outer) {
        when (step) {
            // Figma "onboarding - 17" (dumb product design, node 50:26205):
            // white page, "sign in to smart txt", a line pointing the user at the
            // Dumb Down app (which types into these boxes over TypeSync), two
            // label-over-box fields, and the action on the navbar's centre key
            // ("sign in") rather than an on-screen button. See SignInPage.
            SignInStep.CREDENTIALS -> if (error != null) {
                // A failed sign-in gets its own page (Figma "onboarding - error
                // state", node 2315:3183) instead of a line under the boxes,
                // where a long message ran off the bottom of the screen behind
                // the soft keys. "retry" clears the error and puts the form
                // back. appleId / password live in this composable's state, not
                // the form's, so they are still in the boxes — the user fixes
                // what's wrong (or nothing) and presses "sign in" again.
                SignInErrorPage(
                    message = error.orEmpty(),
                    onRetry = { error = null },
                )
            } else {
                AutoFocus(appleIdFr)
                SignInPage(
                    appleId = appleId,
                    onAppleIdChange = { appleId = it; error = null },
                    password = password,
                    onPasswordChange = { password = it; error = null },
                    appleIdFr = appleIdFr,
                    passwordFr = passwordFr,
                    signingIn = signingIn,
                    notice = notice,
                    onSignIn = { if (!signingIn) startRegistration() },
                )
            }

            // Figma "onboarding - 18" (node 50:26238): white page, "enter ur
            // 2-factor code", six code cells on a black plate (the pairing-code
            // component), navbar "resend" / "verify". See TwoFactorPage.
            SignInStep.TWO_FACTOR -> {
                AutoFocus(twoFactorFr)
                TwoFactorPage(
                    code = twoFactorCode,
                    onCodeChange = { new ->
                        twoFactorCode = new.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    codeFr = twoFactorFr,
                    resending = resending || signingIn,
                    onVerify = {
                        if (twoFactorCode.length == 6 && pending2fa != null) {
                            val deferred = pending2fa
                            pending2fa = null
                            step = SignInStep.REGISTERING
                            deferred?.complete(twoFactorCode)
                        }
                    },
                    onResend = {
                        val deferred = pending2fa
                        if (deferred != null && !resending) {
                            resending = true
                            twoFactorCode = ""
                            pending2fa = null
                            deferred.complete(null)
                        }
                    },
                    onBack = {
                        val deferred = pending2fa
                        pending2fa = null
                        if (deferred != null) cancelledByUser = true
                        step = SignInStep.CREDENTIALS
                        // Cancel the registration coroutine's await.
                        deferred?.complete(null)
                    },
                )
            }

            SignInStep.FSA -> {
                // Relay the challenge to the companion for as long as this screen is
                // shown. The sender sends it now and re-sends on every relay/companion
                // (re)connection until stop() — and onDispose (leaving this step)
                // calls stop(), so retransmission is bounded to the FSA screen.
                val challenge = pendingFsa
                DisposableEffect(challenge) {
                    if (challenge != null) {
                        com.offline.dpadmessenger.backend.smarttxt.SmartTxtConfig
                            .fsaChallengeSender?.start(challenge)
                    }
                    onDispose {
                        com.offline.dpadmessenger.backend.smarttxt.SmartTxtConfig
                            .fsaChallengeSender?.stop()
                    }
                }
                AutoFocus(fsaBackFr)
                // Request a one-time code from the relay (minted only for this
                // authenticated phone) and show it; the user types it into the
                // web/desktop FSA client. Nobody who only knows the phone number
                // can produce this code.
                val fsaSender = com.offline.dpadmessenger.backend.smarttxt.SmartTxtConfig.fsaChallengeSender
                val companionLinked = remember { fsaSender?.isCompanionLinked() ?: false }
                var webCode by remember { mutableStateOf<String?>(null) }
                if (companionLinked) {
                    DisposableEffect(Unit) {
                        fsaSender?.requestWebCode { code -> webCode = code }
                        onDispose { fsaSender?.stopWebCode() }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Approve with your security key", style = MaterialTheme.typography.headlineSmall)
                    if (companionLinked) {
                        Text(
                            "Download desktop app from dumb.co/fsa " +
                                "and enter this code:",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            webCode ?: "Getting your code…",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        CircularProgressIndicator()
                    } else {
                        // No companion phone is linked, so the relay that delivers
                        // the security-key challenge (and mints the desktop code)
                        // can never connect. Point the user at the exact place to
                        // link a device instead of spinning on "Getting your code…".
                        Text(
                            "Smartphone pairing is required to use your security key. Open All Apps, choose " +
                                "\"device setup\" to link your smartphone, then try " +
                                "signing in again to approve with your security key.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    DpadButton(
                        text = "Back",
                        onClick = {
                            pendingFsa = null
                            com.offline.dpadmessenger.backend.smarttxt.SmartTxtFsaBridge.cancel()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = fsaBackFr,
                        primary = false,
                    )
                }
            }

            // Figma "onboarding - 19" (node 50:26265): "activating smart txt..."
            // over two hand-drawn speech bubbles. See RegisteringPage.
            SignInStep.REGISTERING -> RegisteringPage()

            SignInStep.SUCCESS -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("You're all set", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Opening your conversations…",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    }
}

/** Move DPAD focus onto [fr] once its node is attached, retrying a few frames
 *  since the target may not be in the focus tree the instant the step composes. */
@Composable
private fun AutoFocus(fr: FocusRequester) {
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

// ── Sign-in page (Figma "onboarding - 17") ──────────────────────────────────

/*
 * (Internal, not private: HandlePickerScreen uses the same tokens.)
 *
 * Colours and type are the launcher's onboarding tokens, copied rather than
 * shared: this module sits downstream of nothing in the launcher (the
 * dependency runs launcher → dpad-messenger-backend → dpad-messenger), so it
 * cannot see DarkMode. Values are Figma's "light" variables.
 */
internal val PageWhite = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
internal val InkStrong = androidx.compose.ui.graphics.Color(0xFF14140C)  // light/text-strong
internal val InkWeak = androidx.compose.ui.graphics.Color(0xFF575757)    // light/text-weak
private val StrokeStrong = androidx.compose.ui.graphics.Color(0xFF898985) // light/stroke-strong
internal val SkyLooking = androidx.compose.ui.graphics.Color(0xFF38ABFC)   // fixed/sky-looking — focused field

/**
 * Helvetica Now Display, bundled in this module's res/font as copies of the
 * launcher's files (Extra Bold for the title, Medium for everything else).
 */
internal val DisplayExtraBold = FontFamily(Font(R.font.helvetica_now_display_extrabold, FontWeight.ExtraBold))
private val DisplayMedium = FontFamily(Font(R.font.helvetica_now_display_medium, FontWeight.Medium))

/** dp2/sans heading at the launcher's onboarding size (34/34, raised from Figma's 28). */
internal val SignInTitle = androidx.compose.ui.text.TextStyle(
    fontFamily = DisplayExtraBold, fontWeight = FontWeight.ExtraBold,
    fontSize = 34.sp, lineHeight = 34.sp, color = InkStrong,
)
/** dp2/body: Medium 16/17.6, weak ink — the body line and both field labels. */
private val SignInBody = androidx.compose.ui.text.TextStyle(
    fontFamily = DisplayMedium, fontWeight = FontWeight.Medium,
    fontSize = 16.sp, lineHeight = 17.6.sp, color = InkWeak,
)
/**
 * Height of each sign-in box. 33sp ≈ 33dp at normal font scale; in sp so a
 * larger system font grows the box with the text. (Figma's box is 27dp, but
 * it has no text in it; 27dp can't hold 14sp type with descenders.)
 */
private val SIGN_IN_INPUT_LINE = 33.sp

/**
 * How far below the top of the text field the middle of a lowercase letter
 * sits, as a fraction of the font size. Used to place the field so lowercase
 * text is centred in the box.
 *
 * = baseline depth − half the x-height. Helvetica Now Display Medium's
 * x-height is 528/1000 (half = 0.264). For the baseline the font carries two
 * ascents: hhea 1110 and OS/2 typo 875. The first version used 1110 (0.846em)
 * and on the 4058W the text sat ~3dp high — exactly 1110 − 875 = 0.235em at
 * 14sp — so Android lays this font out with the typo ascent (the font sets
 * USE_TYPO_METRICS, and Compose has font padding off). 0.875 − 0.264 = 0.611.
 */
private const val SIGN_IN_X_MIDDLE_EM = 0.611f

/**
 * What the user types, inside the box: Medium 14sp, strong ink, and
 * deliberately *no* lineHeight / LineHeightStyle.
 *
 * History, because each attempt fixed one jump and exposed the next (all on
 * the 4058W):
 *  1. lineHeight 15.4sp: an empty field measures from the font's metrics but
 *     a non-empty one from lineHeight, so the box shrank on the first key.
 *  2. lineHeight unset, box sized by its content: the field's height still
 *     grew a pixel or two when a descender ("y", "p") appeared, so the box
 *     grew. → the box got a fixed height (SignInField).
 *  3. Fixed box, field centred in it: the field's height still changed with
 *     descenders, so centring moved the word up when "y" was typed.
 *  4. lineHeight 21sp + LineHeightStyle(Center): steady, but the tail of "y"
 *     was clipped. 5. lineHeight 33sp (the full box): the text sank further
 *     and every word was cut off at the bottom. A single-line BasicTextField
 *     doesn't honour lineHeight the way multi-line text does — the bigger the
 *     line, the lower the glyphs were drawn — so lineHeight is a dead end here.
 *
 * Now: natural font metrics, and the field is pinned by its *top* (not
 * centred) at a fixed offset in the box — see SignInField. Text is laid out
 * from the top of the field using the font's ascent, which doesn't depend on
 * which letters are present, so the baseline never moves; a descender only
 * makes the field taller downwards, into empty space at the bottom of the box.
 */
private val SignInInput = SignInBody.copy(
    fontSize = 14.sp,
    lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified,
    color = InkStrong,
)
/** Errors: the Extra Bold 14/18.2 result line the launcher's pairing screen uses. */
private val SignInError = androidx.compose.ui.text.TextStyle(
    fontFamily = DisplayExtraBold, fontWeight = FontWeight.ExtraBold,
    fontSize = 14.sp, lineHeight = 18.2.sp, color = InkStrong,
)

/**
 * The credentials form.
 *
 * Changes from the Material version it replaces, and why:
 *  - **No "Sign in" button.** Figma puts the action on the navbar's centre
 *    key. The bar is display-only, so OK is caught here, on the page, from
 *    whichever field has focus. While signing in the centre label reads
 *    "signing in…" and OK does nothing (no second registration).
 *  - **Show/hide (eye) icon** inside the password box, not in the Figma
 *    frame: Right from the password box lands on it, OK toggles (the
 *    navbar centre reads "show" / "hide" while it has focus), Left returns.
 *  - **BasicTextField, not OutlinedTextField.** Material's field has a 56dp
 *    minimum height; the design's box is 27dp.
 *  - **Focus is shown on the box** — the stroke turns the brand blue
 *    (fixed/sky-looking, #38ABFC, the same blue as the launcher's links and
 *    progress bar) and thickens to 1.5dp, so the D-pad user can always tell
 *    which box they are typing into.
 *
 * Caveat: if an on-device IME claims DPAD_CENTER while a field is being
 * edited (for a T9 candidate, say), that press won't reach this handler.
 * Typing is expected to come from the Dumb Down app over TypeSync, where no
 * IME is involved; Down from the password box is not needed to reach a
 * button any more.
 */
@Composable
private fun SignInPage(
    appleId: String,
    onAppleIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    appleIdFr: FocusRequester,
    passwordFr: FocusRequester,
    signingIn: Boolean,
    /** Why the user was signed out, if they didn't choose it — see SmartTxtSetupScreen. */
    notice: String?,
    onSignIn: () -> Unit,
) {
    val imeShowing = rememberImeShowing()
    var passwordVisible by remember { mutableStateOf(false) }
    val eyeFr = remember { FocusRequester() }
    var eyeFocused by remember { mutableStateOf(false) }

    // One place that decides what OK does, used by every path OK can arrive
    // on (see SignInField): on the eye it toggles the password, anywhere else
    // it signs in. Logged, because "OK did nothing" is otherwise invisible.
    val onOk: () -> Unit = {
        if (eyeFocused) {
            passwordVisible = !passwordVisible
        } else {
            android.util.Log.i("SmartTxtSignIn", "OK pressed — sign in (signingIn=$signingIn)")
            onSignIn()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageWhite)
            // OK while nothing editable has focus (or an IME let it through).
            // On the eye (not a text box) OK is never the IME's, even if its
            // window is still up: TCL's stock keyboard keeps one showing, and
            // deferring here left the eye's OK dead on phones without TT9.
            .onPreviewKeyEvent { e ->
                if (!e.isOk() || (imeShowing() && !eyeFocused)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onOk()
                true // swallow the KeyUp too, so it can't act on a field
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Text("sign in to smart txt", style = SignInTitle, modifier = Modifier.padding(top = 20.dp))
        Text(
            "use the Dumb Down app on ur smartphone to type ur account details into the text boxes below.",
            style = SignInBody,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )

        // Used to sit on the removed intro page. It now goes here, between
        // the body and the fields, so it's read before the user signs in again.
        if (notice != null) {
            Text(notice, style = SignInError, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }

        Spacer(Modifier.height(24.dp))

        SignInField(
            label = "phone number or Apple ID",
            value = appleId,
            onValueChange = onAppleIdChange,
            focusRequester = appleIdFr,
            keyboardType = KeyboardType.Email,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            onImeAction = { runCatching { passwordFr.requestFocus() } },
            hidden = false,
            onOk = onOk,
            imeShowing = imeShowing,
            // Down: Apple ID → password.
            onDown = { runCatching { passwordFr.requestFocus() } },
            onUp = null,
            onRight = null,
        )
        Spacer(Modifier.height(8.dp))
        SignInField(
            label = "password",
            value = password,
            onValueChange = onPasswordChange,
            focusRequester = passwordFr,
            keyboardType = KeyboardType.Password,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            onImeAction = onOk,
            hidden = !passwordVisible,
            onOk = onOk,
            imeShowing = imeShowing,
            onDown = null,
            // Up: password → Apple ID.
            onUp = { runCatching { appleIdFr.requestFocus() } },
            // Right: password → the eye.
            onRight = { runCatching { eyeFr.requestFocus() } },
            trailing = {
                // Show / hide the password. Reached with D-pad Right from the
                // password box; OK toggles it (see onOk), Left goes back.
                // Focus is shown the same way as the boxes: brand blue.
                androidx.compose.material3.Icon(
                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (passwordVisible) "hide password" else "show password",
                    tint = if (eyeFocused) SkyLooking else InkWeak,
                    modifier = Modifier
                        .size(18.dp)
                        .focusRequester(eyeFr)
                        .onFocusEvent { eyeFocused = it.isFocused }
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                                runCatching { passwordFr.requestFocus() }; true
                            } else false
                        },
                )
            },
        )

        Spacer(Modifier.height(12.dp))
    }

    // The way out sends a real Back press through the Activity's dispatcher,
    // so it does exactly what the hardware Back key does on this page --
    // whatever that is for the host. In the launcher's SmartTxtActivity there
    // is no handler on the sign-in page, so it closes Smart Txt.
    //  - During device setup (LocalOnboardingContinue is non-null only then)
    //    it is soft-right "skip": the launcher then shows "skip sign in?" to
    //    confirm, so leaving here skips the sign-in and moves setup forward.
    //  - Opened from All Apps it is soft-left "back": it just closes Smart Txt
    //    and returns to where the user came from, like every other app.
    // Hidden while signing in: leaving mid-request would drop a sign-in
    // that may already be registering with Apple.
    val inOnboarding = com.offline.dpadmessenger.ui.components.LocalOnboardingContinue.current != null
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
        ?.onBackPressedDispatcher
    com.offline.dpadmessenger.ui.navbar.SoftKeys(
        left = if (inOnboarding || signingIn || backDispatcher == null) null
        else com.offline.dpadmessenger.ui.navbar.SoftKey("back") { backDispatcher.onBackPressed() },
        center = when {
            signingIn -> com.offline.dpadmessenger.ui.navbar.SoftKey("signing in\u2026")
            eyeFocused -> com.offline.dpadmessenger.ui.navbar.SoftKey(if (passwordVisible) "hide" else "show") { onOk() }
            else -> com.offline.dpadmessenger.ui.navbar.SoftKey("sign in") { onOk() }
        },
        right = if (!inOnboarding || signingIn || backDispatcher == null) null
        else com.offline.dpadmessenger.ui.navbar.SoftKey("skip") { backDispatcher.onBackPressed() },
    )
}

/**
 * True while the input method has its own window up — on the Flip that is
 * the T9 keyboard's symbol / candidate row. OK then belongs to the IME (it
 * picks the highlighted "@", say), so none of our OK handlers may take it.
 * Read from the ROOT insets: this activity keeps traditional window fitting,
 * so the IME inset is consumed before Compose's own WindowInsets would see
 * it, but getRootWindowInsets still reports it.
 */
@Composable
private fun rememberImeShowing(): () -> Boolean {
    val view = androidx.compose.ui.platform.LocalView.current
    return remember(view) {
        {
            androidx.core.view.ViewCompat.getRootWindowInsets(view)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
    }
}

/**
 * How long an OK handed to the IME waits for the IME to use it (type a symbol
 * or candidate, or fire Next / Done) before it counts as a plain OK. TT9
 * commits a picked symbol on the key-down itself, well inside this; long
 * enough for a slow commit on a busy phone, short enough that sign-in still
 * feels like it answered the press.
 */
private const val IME_OK_GRACE_MS = 400L

/**
 * Makes an OK that a text box handed to the IME still count when the IME
 * does nothing with it.
 *
 * Why OK is handed over at all: while the IME has a window up, OK may be the
 * IME's -- TT9 picks the highlighted symbol with it, and acting on it here
 * once signed in with an "@" that was never typed. Why that is not enough:
 * "its window is up" is not "it is choosing something". TCL's stock iQQi
 * keyboard (what a phone has until Dumb TT9 is installed and made default)
 * keeps a window up the whole time a box is focused and ignores OK, so every
 * OK was handed over and lost -- sign-in and 2FA "verify" could not be
 * pressed at all (test 4058W, 2026-10-08).
 *
 * So [handOver] gives the IME [IME_OK_GRACE_MS]; if by then the text is
 * unchanged (no symbol or candidate committed) and nobody called [cancel]
 * (the box's onValueChange and its Next / Done action do), the OK runs here.
 * Rejected: dropping the hand-over, which brings the "@" bug back; and
 * detecting the keyboard by package name, which fixes iQQi and leaves the
 * next keyboard that behaves the same way broken.
 */
private class ImeOkFallback(private val scope: kotlinx.coroutines.CoroutineScope) {
    private var job: kotlinx.coroutines.Job? = null

    fun handOver(currentValue: () -> String, onOk: () -> Unit) {
        val before = currentValue()
        cancel()
        job = scope.launch {
            delay(IME_OK_GRACE_MS)
            if (currentValue() == before) {
                android.util.Log.i("SmartTxtSignIn", "IME did nothing with OK in ${IME_OK_GRACE_MS}ms — taking it as OK")
                job = null
                onOk()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

@Composable
private fun rememberImeOkFallback(): ImeOkFallback {
    val scope = rememberCoroutineScope()
    return remember(scope) { ImeOkFallback(scope) }
}

private fun androidx.compose.ui.input.key.KeyEvent.isOk(): Boolean =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Figma "text field" (1737:4138): label above a 27dp, 8dp-radius stroked box.
 *
 * Why OK is caught three ways. On device, pressing OK with a box focused did
 * nothing: an input method gets hardware keys *before* the Compose tree, and
 * with a text field focused it took DPAD_CENTER for itself, so the page's
 * onPreviewKeyEvent never saw it. So, in order of who gets the key first:
 *  1. onInterceptKeyBeforeSoftKeyboard — runs ahead of the IME, the only hook
 *     that can claim OK before it does;
 *  2. the IME action (Next / Done), for an IME that turns OK into an editor
 *     action instead of passing the key on;
 *  3. the page's onPreviewKeyEvent, for when nothing editable has focus.
 * All three call the same onOk, and only a fresh press (repeatCount 0) acts.
 *
 * Except while the IME has a window on screen (the T9 symbol / candidate
 * row): then OK is left alone for the IME to select with, and only the
 * editor action (2) can sign in. Without that rule (1) stole the OK that was
 * meant to pick "@" from the symbol row and signed in with half an address.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SignInField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    keyboardType: KeyboardType,
    imeAction: androidx.compose.ui.text.input.ImeAction,
    onImeAction: () -> Unit,
    hidden: Boolean,
    onOk: () -> Unit,
    /** See SignInPage: while the IME has a window up, OK is the IME's. */
    imeShowing: () -> Boolean,
    onDown: (() -> Unit)?,
    onUp: (() -> Unit)?,
    onRight: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }

    // An OK handed to the IME that it does nothing with still acts -- see
    // ImeOkFallback. Without it, a keyboard that keeps its window up (TCL's
    // stock iQQi, i.e. any phone without Dumb TT9) left sign-in unstartable.
    val currentValue by androidx.compose.runtime.rememberUpdatedState(value)
    val imeOkFallback = rememberImeOkFallback()
    // Fixed box height (sp, so it tracks font scale), and the offset from the
    // box's top to the field's top that puts the middle of lowercase letters
    // on the box's centre line. Both from sp, so they scale together.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val signInBoxHeight = with(density) { SIGN_IN_INPUT_LINE.toDp() }
    val signInTextTop = with(density) {
        maxOf(signInBoxHeight / 2 - (SignInInput.fontSize * SIGN_IN_X_MIDDLE_EM).toDp(), 0.dp)
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = SignInBody, modifier = Modifier.fillMaxWidth())
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = { imeOkFallback.cancel(); onValueChange(it) },
            singleLine = true,
            textStyle = SignInInput,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(InkStrong),
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onNext = { imeOkFallback.cancel(); onImeAction() },
                onDone = { imeOkFallback.cancel(); onImeAction() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusEvent { focused = it.isFocused }
                .onInterceptKeyBeforeSoftKeyboard { e ->
                    if (!e.isOk()) return@onInterceptKeyBeforeSoftKeyboard false
                    // The symbol row is up: OK must select the highlighted
                    // symbol, not sign in. Seen on device — picking "@" from
                    // the T9 symbols fired a sign-in with the "@" never typed.
                    if (imeShowing()) {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                            android.util.Log.i("SmartTxtSignIn", "OK left to the IME (its window is showing)")
                            imeOkFallback.handOver({ currentValue }, onOk)
                        }
                        return@onInterceptKeyBeforeSoftKeyboard false
                    }
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onOk()
                    true
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        e.key == Key.DirectionDown && onDown != null -> { onDown(); true }
                        e.key == Key.DirectionUp && onUp != null -> { onUp(); true }
                        // Right: password → eye. Moving the cursor inside the
                        // box isn't needed — text arrives from the Dumb Down app.
                        e.key == Key.DirectionRight && onRight != null -> { onRight(); true }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A fixed height, not one measured from the text.
                        // Measured, the box changed height with what was in
                        // it: first empty vs. non-empty (fixed in
                        // SignInInput), then — still, on the 4058W — by a
                        // pixel or two when the first letter had a descender
                        // ("y", "p") versus none, because the laid-out line's
                        // bounds follow the glyphs actually present. A fixed
                        // box can't follow anything. SIGN_IN_INPUT_LINE is
                        // sized to hold the font's full ascent + descent with
                        // room to spare, so "y"/"p"/"g" fit without clipping
                        // (the text is placed by its top, not centred — see
                        // the inner Box below). It is in sp, so a larger
                        // system font scale grows the box with the text
                        // instead of clipping it.
                        .height(signInBoxHeight)
                        .border(
                            if (focused) 1.5.dp else 1.dp,
                            if (focused) SkyLooking else StrokeStrong,
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        )
                        // Horizontal only; the text's vertical place comes
                        // from signInTextTop below.
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // TopStart + a fixed top padding, not CenterStart: the
                    // field's height changes with descenders, and centring
                    // turned that into the word hopping (see SignInInput).
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(top = signInTextTop),
                        contentAlignment = Alignment.TopStart,
                    ) { inner() }
                    if (trailing != null) {
                        Spacer(Modifier.width(4.dp))
                        trailing()
                    }
                }
            },
        )
    }
}

// ── Sign-in error page (Figma "onboarding - error state") ───────────────────

// Light-mode values of the Figma "feedback" component's danger tokens (the
// design context reports the dark-mode fallbacks; these are sampled from the
// light-mode frame): primary/background, danger fill / stroke / text.
private val ErrorPageBg = androidx.compose.ui.graphics.Color(0xFFFFFEF5)
private val DangerFill = androidx.compose.ui.graphics.Color(0xFFFAF3EB)
private val DangerStroke = androidx.compose.ui.graphics.Color(0xFFF2897E)
private val DangerText = androidx.compose.ui.graphics.Color(0xFF9E2D28)

/** dp2/body bold: Extra Bold 16 / 1.1. */
private val FeedbackTitle = androidx.compose.ui.text.TextStyle(
    fontFamily = DisplayExtraBold, fontWeight = FontWeight.ExtraBold,
    fontSize = 16.sp, lineHeight = 17.6.sp, color = DangerText,
)
/** dp2/body smaller: Medium 14 / 1.1. */
private val FeedbackBody = androidx.compose.ui.text.TextStyle(
    fontFamily = DisplayMedium, fontWeight = FontWeight.Medium,
    fontSize = 14.sp, lineHeight = 15.4.sp, color = InkStrong,
)

/**
 * Shown in place of the sign-in form when signing in fails (Figma "onboarding
 * - error state", node 2315:3183): the page in primary/background, the
 * "feedback" card centred on it — 12dp radius, danger fill with a 1dp danger
 * stroke, 12dp padding; the 18dp warning icon and "couldn't sign in" (dp2/body
 * bold, danger text) 4dp apart, then the message (dp2/body smaller) 8dp below
 * — and the navbar's centre key reading "retry".
 *
 * Why a page: the old inline error sat under the password box, and with the
 * title, body and both boxes above it a long message (several are four to six
 * lines) was cut off by the soft keys — and a D-pad user can't scroll to it.
 * Here the card has the whole screen and the page scrolls if a message is
 * ever taller than that.
 *
 * Figma's frame draws the card over the dimmed sign-in page (a full-screen
 * "cover"); here the page is simply the cover colour, which looks the same
 * and needs no second copy of the form underneath.
 *
 * Keys. OK (DPAD_CENTER) = retry, using the armed pattern: it fires on the
 * key-up of a press that went *down* on this page, so the OK that started the
 * sign-in — or one held across the failure — can't dismiss the error before
 * it's read. Back also retries. The centre soft key is display-only, as on
 * every screen. Left "report" sends the Smart Txt logs to support — the job
 * of the "Report error" button that used to sit under the inline error; not
 * in the Figma frame, but dropping it would remove the only way to send logs
 * from a failed sign-in.
 */
@Composable
private fun SignInErrorPage(message: String, onRetry: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val pageFr = remember { FocusRequester() }
    var armed by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    AutoFocus(pageFr)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ErrorPageBg)
            .focusRequester(pageFr)
            .focusable()
            .onPreviewKeyEvent { e ->
                when {
                    e.key == Key.Back && e.type == KeyEventType.KeyUp -> { onRetry(); true }
                    e.key == Key.Back -> true
                    e.isOk() && e.type == KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) armed = true
                        true
                    }
                    e.isOk() && e.type == KeyEventType.KeyUp -> {
                        if (armed) { armed = false; onRetry() }
                        true
                    }
                    else -> false
                }
            }
    ) {
        // Centred in the page, as in Figma (the card's middle is the frame's
        // middle), but inside a scroll so an unusually long message can still
        // be read: the inner Box is at least the page's height, which is what
        // makes Center centre — a scroll alone measures with unbounded height.
        val pageHeight = maxHeight
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = pageHeight)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DangerFill, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .border(1.dp, DangerStroke, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_feedback_warning),
                    contentDescription = null,
                    tint = DangerText,
                    modifier = Modifier.size(18.dp),
                )
                Text("couldn\u2019t sign in", style = FeedbackTitle, modifier = Modifier.weight(1f))
            }
            Text(message, style = FeedbackBody, modifier = Modifier.fillMaxWidth())
        }
        }
        }
    }

    com.offline.dpadmessenger.ui.navbar.SoftKeys(
        left = com.offline.dpadmessenger.ui.navbar.SoftKey(if (reporting) "sending\u2026" else "report") {
            if (!reporting) {
                reporting = true
                android.widget.Toast.makeText(context, "Reporting error to support...", android.widget.Toast.LENGTH_SHORT).show()
                scope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.offline.dpadmessenger.backend.smarttxt.SmartTxtLogExporter.export(context.applicationContext)
                    }
                    reporting = false
                    val msg = result.fold(
                        onSuccess = { ref -> "Report sent to support. Reference: $ref" },
                        onFailure = { e -> "Couldn't send report: ${e.message ?: "unknown error"}" },
                    )
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        },
        center = com.offline.dpadmessenger.ui.navbar.SoftKey("retry"),
    )
}

// ── Two-factor page (Figma "onboarding - 18") ───────────────────────────────

/**
 * Black plate's padding around the six code cells. 10dp, up from the pairing
 * component's (and Figma's) 8dp, for a little more black around the blue.
 * The cells are sized from what's left, so they shrink slightly to make room.
 */
private val PLATE_PADDING = 10.dp

/**
 * 2FA cells as a fraction of the full column width the plate could give them.
 * 0.85: on a ~320dp screen that is ~36 × 52dp instead of ~42 × 61dp (Figma's
 * cells are 30 × 43 in its 240dp frame). 38sp digits still fit with room.
 */
private const val CODE_CELL_SCALE = 0.85f

private val Cheltenham = FontFamily(Font(R.font.cheltenham_extra_condensed_bold, FontWeight.Bold))
private val PlateBlack = androidx.compose.ui.graphics.Color(0xFF0F0F0F)   // fixed/background
private val CellText = androidx.compose.ui.graphics.Color(0xFFFAFAF0)     // fixed/text-strong-on-dark-bg
private val Sunscreen = androidx.compose.ui.graphics.Color(0xFFFAF594)    // fixed/brand (sunscreen)

/** "verification code" label: dp2/body smaller (Medium 14/15.4), weak ink at 50%. */
private val CodeLabel = SignInBody.copy(fontSize = 14.sp, lineHeight = 15.4.sp)

/**
 * The 2FA code page.
 *
 * The six cells are the launcher's pairing-code component (4dp gaps, 10dp
 * padding — PLATE_PADDING — on a 12dp-radius black plate) in Figma's 30×43 proportions, but
 * scaled so the plate fills the full gutter-to-gutter width — Figma's 216dp
 * plate fills its 240dp frame the same way, and on the handset's wider screen
 * a fixed 216dp plate looked small. The cell waiting for the next digit gets
 * the 1dp sunscreen border.
 *
 * Under the cells is a real BasicTextField drawn through decorationBox, not a
 * key handler on a Box: digits can then come from the T9 keypad *or* from the
 * Dumb Down app over TypeSync, which types into the focused text field. OK
 * handling is the sign-in page's (intercept before the IME unless the IME has
 * a window up, plus the IME's Done action).
 *
 * Soft keys per Figma: left "resend" (cancels the pending request and logs in
 * again, so Apple sends a new code — the old "Resend" button only cleared the
 * boxes), centre "verify" (only once all six digits are in; until then the
 * centre is blank so it never promises a press that does nothing). The old
 * "Back" button is gone; hardware Back still returns to the sign-in form.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TwoFactorPage(
    code: String,
    onCodeChange: (String) -> Unit,
    codeFr: FocusRequester,
    /** A new code is being requested — keys are ignored and the bar says so. */
    resending: Boolean,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    val imeShowing = rememberImeShowing()
    val complete = code.length == 6
    val onOk: () -> Unit = { if (complete && !resending) onVerify() }
    // Same fallback as the sign-in boxes (ImeOkFallback): with a keyboard
    // that keeps its window up, "verify" was otherwise unreachable.
    val currentCode by androidx.compose.runtime.rememberUpdatedState(code)
    val imeOkFallback = rememberImeOkFallback()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageWhite)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Back) {
                    onBack(); return@onPreviewKeyEvent true
                }
                if (!e.isOk() || imeShowing()) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onOk()
                true
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        Text("enter ur 2-factor code", style = SignInTitle, modifier = Modifier.padding(top = 20.dp))
        Text(
            "use the 6-digit verification code Apple sent to ur trusted devices.",
            style = SignInBody,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )

        Spacer(Modifier.height(24.dp))

        // Label left-aligned on the 12dp gutter and the plate stretched to
        // fill the gutter-to-gutter width, as in Figma (whose 216dp plate
        // exactly fills its 240dp frame minus the gutters). An earlier version
        // kept Figma's fixed 216dp and centred label + plate as a pair, because
        // on the handset's wider screen a fixed plate pinned left sat visibly
        // off to one side — but that left the label floating mid-screen and
        // the code cells small. Filling the width keeps both edges on the
        // gutter like every other element on the page, and makes the cells
        // bigger (see the decorationBox below).
        Text(
            "verification code",
            style = CodeLabel,
            modifier = Modifier.fillMaxWidth().alpha(0.5f),
        )
        Spacer(Modifier.height(8.dp))

        androidx.compose.foundation.text.BasicTextField(
            value = code,
            onValueChange = { imeOkFallback.cancel(); if (!resending) onCodeChange(it) },
            singleLine = true,
            // Not disabled while resending: a disabled field drops focus, and
            // nothing would put it back when the new code request lands.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { imeOkFallback.cancel(); onOk() }),
            // The cells draw the digits; the field's own text is never shown.
            textStyle = SignInInput.copy(color = androidx.compose.ui.graphics.Color.Transparent),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(codeFr)
                .onInterceptKeyBeforeSoftKeyboard { e ->
                    if (!e.isOk()) return@onInterceptKeyBeforeSoftKeyboard false
                    if (imeShowing()) {
                        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                            android.util.Log.i("SmartTxtSignIn", "2FA: OK left to the IME (its window is showing)")
                            imeOkFallback.handOver({ currentCode }, onOk)
                        }
                        return@onInterceptKeyBeforeSoftKeyboard false
                    }
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onOk()
                    true
                },
            decorationBox = { inner ->
                Box {
                    // Keep the real text node composed (it carries the input
                    // connection) but out of sight behind the plate.
                    Box(Modifier.size(1.dp).alpha(0f)) { inner() }
                    // Cells scale with the plate: the plate fills the full
                    // width, and each cell is CODE_CELL_SCALE of the width six
                    // equal columns would get (after the plate's padding and
                    // five 4dp gaps), in Figma's 30×43 proportions. The cells
                    // are then spread edge to edge across the plate, so the
                    // leftover width goes into the gaps. They used to take the
                    // full column width; on review they read a touch too big,
                    // and shrinking them inside a full-width plate keeps the
                    // plate's edges on the gutter like the label above it.
                    // The digits are a fixed 38sp (Figma: 28sp in a 43dp cell),
                    // asked for so the code reads at a known size.
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cellW = (maxWidth - PLATE_PADDING * 2 - 20.dp) / 6 * CODE_CELL_SCALE
                    val cellH = cellW * (43f / 30f)
                    val digitSize = 38.sp
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(PlateBlack, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                            .padding(PLATE_PADDING),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val cellShape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        for (i in 0 until 6) {
                            val isCurrent = !resending && i == code.length
                            Box(
                                modifier = Modifier
                                    .size(width = cellW, height = cellH)
                                    .background(SkyLooking, cellShape)
                                    .then(
                                        if (isCurrent) Modifier.border(1.dp, Sunscreen, cellShape)
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    code.getOrNull(i)?.toString() ?: "",
                                    style = androidx.compose.ui.text.TextStyle(
                                        fontFamily = Cheltenham,
                                        fontSize = digitSize,
                                        lineHeight = digitSize,
                                        color = CellText,
                                        textAlign = TextAlign.Center,
                                    ),
                                )
                            }
                        }
                    }
                    }
                }
            },
        )

        if (resending) {
            Text(
                "sending a new code\u2026",
                style = SignInError,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    com.offline.dpadmessenger.ui.navbar.SoftKeys(
        left = if (resending) null else com.offline.dpadmessenger.ui.navbar.SoftKey("resend") { onResend() },
        center = when {
            resending -> com.offline.dpadmessenger.ui.navbar.SoftKey("sending\u2026")
            complete -> com.offline.dpadmessenger.ui.navbar.SoftKey("verify") { onOk() }
            else -> null
        },
    )
}

// ── Activating page (Figma "onboarding - 19") ───────────────────────────────

/** Gap kept between the lower bubble and the bottom of the page (the soft-key bar). */
private val BUBBLE_BOTTOM_MARGIN = 8.dp

/**
 * Shown while registration runs — after the 2FA code is accepted, or after a
 * security-key approval. White page, "activating smart txt...", and two copies
 * of the hand-drawn speech bubble: one at Figma's (-28, 73), one mirrored left
 * to right at (68, 172), both 200×167dp and both deliberately bleeding off the
 * screen edges — scaled to fit the space under the title (see the comment in
 * the body). Positioned absolutely (offset from the top-left, unbounded)
 * because the overlap and the bleed are the composition; a Column would pull
 * them back inside the screen.
 *
 * The bubble is ic_speech_bubble in drawable-nodpi, downscaled from Figma's
 * 1800×1500 export to 600×500 (3× the slot) — the full-size PNG would decode
 * to ~10MB on a phone with very little memory. The mirror is a graphicsLayer
 * scaleX = -1, which is what Figma's rotate(180) + scaleY(-1) amounts to.
 *
 * No soft keys (the bar is hidden) and no progress indicator in the design; the "..." in the title
 * is the only sign of work. Static on purpose until a motion spec exists.
 */
@Composable
private fun RegisteringPage() {
    Column(modifier = Modifier.fillMaxSize().background(PageWhite)) {
        Text(
            "activating smart txt...",
            style = SignInTitle,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 20.dp),
        )
        // The bubbles get whatever space is left under the title, and are
        // scaled to fit it in *both* directions.
        //
        // Figma's composition, in its 240dp frame: bubble 1 at x −28 (bleeding
        // 28 off the left), bubble 2 mirrored with its right edge at 268
        // (bleeding 28 off the right), both 200×167, bubble 2 starting 99dp
        // below bubble 1 — 266dp tall overall. The first port drew that at
        // fixed dp, which on the handset's wider screen left a gap at the
        // right. The second scaled everything by screen width / 240, which
        // reached both edges but made the composition ~355dp tall, and on the
        // 4058W the bottom of bubble 2 was cut off by the soft-key bar.
        //
        // Now the scale is the smaller of "fill the width" and "fit the height
        // left under the title", and each bubble is anchored to its own edge
        // (x = −28k for the left one, width − 172k for the right one), so both
        // still bleed off their sides by Figma's proportion even when the
        // height is what limits the size. The 34sp title is laid out first,
        // so a two-line title or a large font setting just shrinks the bubbles.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            val k = minOf(
                maxWidth / 240.dp,
                (maxHeight - BUBBLE_BOTTOM_MARGIN) / 266.dp,
            ).coerceAtLeast(0f)
            SpeechBubble(x = (-28).dp * k, y = 0.dp, scale = k, mirrored = false)
            SpeechBubble(x = maxWidth - 172.dp * k, y = 99.dp * k, scale = k, mirrored = true)
        }
    }
    // No soft-key bar at all on this page: there is nothing to press while
    // registration runs, and the empty black strip only took screen space from
    // the bubbles. HideSoftKeys also claims the bar empty first, so the sign-in
    // page's "sign in" can't linger, and puts the bar back when this page goes.
    com.offline.dpadmessenger.ui.navbar.HideSoftKeys()
}

@Composable
private fun SpeechBubble(
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    /** Multiplier on Figma's 200×167dp bubble; see RegisteringPage. */
    scale: Float,
    mirrored: Boolean,
) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_speech_bubble),
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier
            .wrapContentSize(align = Alignment.TopStart, unbounded = true)
            .offset(x = x, y = y)
            .size(width = 200.dp * scale, height = 167.dp * scale)
            .then(
                if (mirrored) Modifier.graphicsLayer(scaleX = -1f) else Modifier
            ),
    )
}
