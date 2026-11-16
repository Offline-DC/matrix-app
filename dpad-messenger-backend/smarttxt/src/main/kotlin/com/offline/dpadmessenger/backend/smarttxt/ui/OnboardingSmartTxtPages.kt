package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.dpadmessenger.backend.smarttxt.R
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtAccountStore
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Smart Txt's pages during the launcher's device setup, redesigned to the
 * flip frames of "onboarding (post user testing)" (Figma "dumb product design",
 * section 2530:3949), 2026-11-04.
 *
 * Split with the launcher on purpose. The pages with nothing to press —
 * "set up smart txt" (dp 6), "logging into iMessage" (6.1), "smart txt set up!"
 * (6.6) — are drawn by the launcher over this library, from [SmartTxtOnboardingPage].
 * The two that take keys live here, next to the state they drive: the 2-factor
 * code (6.2) and the send-from choice (6.4 / 6.5). Opened outside setup, Smart
 * Txt keeps its own pages; these only replace them when the launcher provides
 * LocalOnboardingContinue.
 *
 * Drawn in phone pixels (1 dp = 1 sp = 1 px), as the frames are, for the same
 * reason the launcher's setup kit does: the phone runs density 120 and font
 * scale 1.4 during setup, which drew Figma's numbers at about 75 %. The
 * launcher's kit can't be used from here (this library doesn't depend on the
 * launcher), so its few tokens are copied below, as eaintm's earlier Smart Txt
 * revamp also copied them.
 */

/**
 * Which Smart Txt page is up during setup, for the launcher to cover with its
 * own Figma page (or not). Null outside setup.
 */
object SmartTxtOnboardingPage {
    /** Waiting for the smartphone: Smart Txt's waiting page (the login arrives
     *  as smart_txt_login), or, after "login manually", the form it fills over
     *  type sync. */
    const val CREDENTIALS = "credentials"
    /** Signing in or activating, nothing to press. */
    const val SIGNING_IN = "signing_in"
    /** Apple asked for a 2-factor code (this library draws 6.2). */
    const val TWO_FACTOR = "two_factor"
    /** A security key is needed (no frame; this library's own page). */
    const val SECURITY_KEY = "security_key"
    /** The sign-in failed (no frame; this library's own error page). */
    const val FAILED = "failed"
    /** Choosing the send-from handle (this library draws 6.4 / 6.5). */
    const val HANDLES = "handles"

    val current = MutableStateFlow<String?>(null)
}

/**
 * How full the launcher's setup progress bar is on the Smart Txt step, or null
 * outside device setup. Frames 6.2, 6.4 and 6.5 draw that bar at the top like
 * every setup frame, but its fill is the launcher's (OnboardingProgress, which
 * this library can't see), so the launcher's SmartTxtActivity provides it.
 */
val LocalOnboardingProgress = compositionLocalOf<Float?> { null }

/**
 * The setup progress bar as the launcher's kit draws it: 8 px at the very
 * top, the #262625 track and a #38ABFC fill with a rounded right end.
 */
@Composable
private fun ObProgressBar(modifier: Modifier = Modifier) {
    val fraction = LocalOnboardingProgress.current ?: return
    Box(modifier.fillMaxWidth().height(8.dp).background(ObTrack)) {
        val filled = fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(filled)
                    .height(8.dp)
                    .background(ObSky, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
            )
        }
    }
}

/**
 * Where a 28 / 28 heading's box goes: the frame's (12, 20), 1 px lower, and
 * 228 wide — the launcher kit's SetupHeading, for the same two reasons: the
 * phone draws this face's half-pixel baseline 1 px higher than Figma, and its
 * slightly wider hinted glyphs must not re-wrap a line whose break is written in.
 */
private val ObHeadingBox = Modifier.offset(x = 12.dp, y = 21.dp).width(228.dp)

private val OnboardingDensity = Density(density = 1f, fontScale = 1f)

/** 1 dp = 1 sp = 1 phone pixel inside: Figma's numbers as written. */
@Composable
internal fun OnboardingPixels(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDensity provides OnboardingDensity, content = content)

private val ObPage = Color(0xFFFAF594)        // fixed/brand (sunscreen)
private val ObHeading = Color(0xFF14140C)     // fixed/text strong on light bg
private val ObBody = Color(0xFF575757)        // fixed/text weak on light bg
private val ObDark = Color(0xFF0F0F0F)        // primary/background
private val ObOnDark = Color(0xFFFAFAF0)      // primary/text strong
private val ObOnDarkWeak = Color(0xFFB4B4AD)  // primary/text weak
private val ObSky = Color(0xFF38ABFC)         // fixed/sky-looking
private val ObCardFill = Color(0xFFE3DE86)    // fixed/fill (#14140C at 10 %) over the page
private val ObTrack = Color(0xFF262625)       // progress track, as the launcher kit's

private val ObExtraBold = FontFamily(Font(R.font.helvetica_now_display_extrabold, FontWeight.ExtraBold))
private val ObMedium = FontFamily(Font(R.font.helvetica_now_display_medium, FontWeight.Medium))
private val ObSerif = FontFamily(Font(R.font.cheltenham_extra_condensed_bold))

/**
 * EXPERIMENT (owner, 2026-11-09, "Yes, try it"; keep or drop after comparing
 * flip screenshots with Figma): letters placed between pixels, as Figma places
 * them. With Compose's default (hinted, whole-pixel advances) single letters sat
 * 1–3 px off Figma on the flip, opening gaps like "c ontacts". The risk is softer
 * text on this low-density screen. One switch for every Smart Txt setup page;
 * the launcher's setup kit carries the same one.
 */
internal const val SUBPIXEL_SETUP_TEXT = true

private fun TextStyle.designed() = copy(
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    textMotion = if (SUBPIXEL_SETUP_TEXT) TextMotion.Animated else TextMotion.Static,
)

/** dp2/sans heading: Display Extra Bold 28 / 28. */
private val ObHeadingStyle = TextStyle(fontFamily = ObExtraBold, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 28.sp, color = ObHeading).designed()
/** dp2/body: Display Medium 16 / 17.6. */
private val ObBodyStyle = TextStyle(fontFamily = ObMedium, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 17.6.sp, color = ObBody).designed()
/** dp2/bttns/extra bold: the radio labels, 16 / 17.6. */
private val ObLabelStyle = TextStyle(fontFamily = ObExtraBold, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 17.6.sp).designed()
/** dp2/body smaller: the radio description, 14 / 15.4. */
private val ObSmallStyle = TextStyle(fontFamily = ObMedium, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 15.4.sp).designed()

/**
 * "onboarding - 6.2" (2913:3425): "enter ur 2-factor code", six 30×43 sky
 * cells on a 216×59 #0F0F0F plate (radius 12, padding 8, cells 4 px apart,
 * radius 4), the next cell outlined 1 px in sunscreen, soft-left "resend" only.
 *
 * The frame has no "verify" key, so the code goes in as soon as the sixth
 * digit lands, the way pairing's four digits do. Digits are typed on the
 * keypad into a field that stays focused but draws nothing of its own; the
 * cells show what it holds. Back deletes the last digit first; only on an
 * empty box (and a fresh press) does it return to the form.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun OnboardingTwoFactorPage(
    code: String,
    onCodeChange: (String) -> Unit,
    codeFr: FocusRequester,
    resending: Boolean,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(code) {
        if (code.length == 6 && !resending) onVerify()
    }
    // Back is the flip's delete key: it clears the last digit, and only on an
    // empty box (and a fresh press, so holding it to clear can't overshoot)
    // does it leave the page. Caught before the keyboard: Dumb TT9 otherwise
    // takes Back as its own delete, which never reached this box, so a wrong
    // digit could not be fixed (4058W, 6 Nov).
    val onBackKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { e ->
        if (e.key != Key.Back) false else {
            if (e.type == KeyEventType.KeyDown) {
                when (backKeyAction(code.length, e.nativeKeyEvent.repeatCount)) {
                    BackKeyAction.DELETE_DIGIT -> onCodeChange(code.dropLast(1))
                    BackKeyAction.LEAVE_PAGE -> onBack()
                    BackKeyAction.NOTHING -> Unit
                }
            }
            true
        }
    }
    OnboardingPixels {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObPage)
                .onPreviewKeyEvent(onBackKey),
        ) {
            ObProgressBar(Modifier.align(Alignment.TopStart))
            // Line breaks where the frame's render has them.
            BasicText("enter ur 2-factor\ncode", style = ObHeadingStyle, modifier = ObHeadingBox)
            BasicText(
                "use the 6-digit verification\ncode Apple sent to ur trusted\ndevices.",
                style = ObBodyStyle,
                modifier = Modifier.offset(x = 12.dp, y = 88.dp).width(228.dp),
            )
            BasicTextField(
                value = code,
                onValueChange = { onCodeChange(it) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier
                    .offset(x = 12.dp, y = 166.dp)
                    .focusRequester(codeFr)
                    .onInterceptKeyBeforeSoftKeyboard(onBackKey),
                decorationBox = { _ ->
                    Row(
                        modifier = Modifier
                            .background(ObDark, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val cell = RoundedCornerShape(4.dp)
                        for (i in 0 until 6) {
                            Box(
                                modifier = Modifier
                                    .size(width = 30.dp, height = 43.dp)
                                    .background(ObSky, cell)
                                    .then(if (i == code.length) Modifier.border(1.dp, ObPage, cell) else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                // 1 px up from centred, as the launcher's pairing cells do
                                // (F27, 2026-11-06). Figma's 28 px text box sits at the
                                // cell's top + 7 (baseline + 31). Compose pads this 28 px
                                // line 3 px above and below (the face's own line is 34), so
                                // the text measures 34 and centring it in 43 rounds 4.5 up
                                // to 5: line at + 8, baseline at + 32. On the flip the
                                // digit's ink covered rows 12-31 against Figma's 11-30.
                                BasicText(
                                    code.getOrNull(i)?.toString() ?: "",
                                    style = TextStyle(fontFamily = ObSerif, fontSize = 28.sp, lineHeight = 28.sp, color = ObOnDark, textAlign = TextAlign.Center).designed(),
                                    modifier = Modifier.offset(y = (-1).dp),
                                )
                            }
                        }
                    }
                },
            )
        }
    }
    SoftKeys(left = SoftKey(if (resending) "sending..." else "resend") { if (!resending) onResend() })
}

/**
 * "onboarding (dp) - 6.4" (2913:3488) and "6.5" (2951:4896): "new convos
 * settings" / "choose where new conversations start from", then the user's
 * handles as radio cards at (12, 148), 214 px wide, 4 px apart. The focused card
 * is #0F0F0F with its label in #FAFAF0; the phone number's card also carries
 * "ur smartphone number" in #B4B4AD; the others are #E3DE86 with dark labels.
 * Up / down move the focus; centre "select" puts the dot in the focused card's
 * radio (6.5), and a moment later the choice is saved and [onContinue] runs, as
 * the old picker's "continue" did.
 *
 * More addresses than fit (F25/F38, 2026-11-06): the frames draw three cards,
 * and a fourth was cut off by the soft keys, a fifth focused off screen. The
 * whole page (heading, body and cards together) now moves up just enough to
 * keep the focused card fully above y 281, where the frame's third card ends,
 * and back down when Up goes past the top card ([handlePickerScroll]). No
 * animation, so held or fast D-pad presses don't queue up. The owner chose this
 * over pinning the heading or showing only three, after seeing the mock.
 *
 * No addresses at all (F28/F37): select used to do nothing, a dead end. As the
 * frames' select always moves on, it now saves the empty choice and carries
 * on, as the old picker did; Smart Txt then sends from its first registered
 * handle. The page looks as drawn: no new text (owner: "as per figma").
 */
@Composable
internal fun OnboardingHandlePicker(onContinue: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SmartTxtAccountStore(context) }
    val handles = remember { store.loadAccount()?.handles.orEmpty().sortedByDescending { it.startsWith("tel:") } }
    var focused by remember { mutableStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var scroll by remember { mutableStateOf(0) }
    val pageFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { SmartTxtOnboardingPage.current.value = SmartTxtOnboardingPage.HANDLES }
    // HANDLES stays set when this page goes, unlike SmartTxtSetupScreen's pages.
    // After an OpenBubbles migration the page shows for a moment and then gives
    // way to the chats on its own; null there would put the launcher's "set up
    // smart txt" over the chats. SmartTxtApp clears it instead, when its
    // not-signed-in branch comes up (10 Nov review).
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { pageFr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    val select: () -> Unit = {
        // Also with no addresses: select moves on instead of doing nothing (F28/F37).
        if (picked == null) picked = focused
    }
    val moveFocus: (Int) -> Unit = { delta ->
        if (picked == null) {
            focused = (focused + delta).coerceIn(0, handles.lastIndex.coerceAtLeast(0))
            scroll = handlePickerScroll(handles, focused, scroll)
        }
    }
    LaunchedEffect(picked) {
        val i = picked ?: return@LaunchedEffect
        val choice = sendFromChoice(handles, i)
        // Long enough to see the dot land (frame 6.5) before setup moves on;
        // with no card there is no dot to see.
        if (choice.isNotEmpty()) delay(SELECT_BEAT_MS)
        store.saveHandleSelection(enabled = handles, default = choice)
        if (choice.isNotEmpty()) RustPushNative.runCatchingNativeSetSendHandle(choice)
        onContinue()
    }
    OnboardingPixels {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObPage)
                .focusRequester(pageFr)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { moveFocus(1); true }
                        Key.DirectionUp -> { moveFocus(-1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (e.nativeKeyEvent.repeatCount == 0) select(); true
                        }
                        else -> false
                    }
                },
        ) {
            // Heading, body and cards scroll as one page (F25/F38). 1 dp = 1 px
            // here, so [scroll] is in the frame's own pixels.
            Box(Modifier.fillMaxSize().offset { IntOffset(0, -scroll) }) {
                BasicText("new convos\nsettings", style = ObHeadingStyle, modifier = ObHeadingBox)
                BasicText("choose where new\nconversations start from", style = ObBodyStyle, modifier = Modifier.offset(x = 12.dp, y = 88.dp).width(216.dp))
                Column(
                    modifier = Modifier
                        .offset(x = SEND_FROM_LEFT.dp, y = SEND_FROM_TOP.dp)
                        .width(214.dp)
                        // Measured at full height, not capped at the page's 290 px:
                        // capped, an 8th card got squashed and a 9th got nothing,
                        // and scrolling would bring a squashed card into view.
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                    verticalArrangement = Arrangement.spacedBy(SEND_FROM_GAP.dp),
                ) {
                    handles.forEachIndexed { i, handle ->
                        HandleCard(
                            label = setupHandleLabel(handle),
                            caption = if (handle.startsWith("tel:")) "ur smartphone number" else null,
                            focused = i == focused,
                            picked = i == picked,
                        )
                    }
                }
            }
            // Drawn last, so a heading scrolled up goes under the bar.
            ObProgressBar(Modifier.align(Alignment.TopStart))
        }
    }
    SoftKeys(center = SoftKey("select") { select() })
}

@Composable
private fun HandleCard(label: String, caption: String?, focused: Boolean, picked: Boolean) {
    val ink = if (focused) ObOnDark else ObHeading
    // Figma "radio button" (2951:3714): 214 wide, 34 tall with one line and
    // 57 with the caption. The 12 px ring sits in an 18 px slot level with the
    // FIRST line (3 px down), not centred on the card: centred, it dropped
    // ~10 px on the two-line card. The caption has a 19 px slot 4 px below the
    // label, which is what makes the card 57, not 54 (F23/F36, 2026-11-06).
    Row(
        modifier = Modifier
            .width(214.dp)
            .background(if (focused) ObDark else ObCardFill, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 12 px radio ring; the 6 px dot once picked.
        Box(
            modifier = Modifier.padding(top = 3.dp).size(12.dp).border(1.dp, ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) Box(Modifier.size(6.dp).background(ink, CircleShape))
        }
        Spacer(Modifier.width(8.dp))
        Column {
            BasicText(label, style = ObLabelStyle.copy(color = ink), maxLines = 1)
            if (caption != null && focused) {
                BasicText(
                    caption,
                    style = ObSmallStyle.copy(color = ObOnDarkWeak),
                    modifier = Modifier.padding(top = 4.dp).heightIn(min = 19.dp),
                )
            }
        }
    }
}

private const val SELECT_BEAT_MS = 600L

/** Where frame 6.4 puts its cards: from (12, 148), 4 px apart; its third card ends at y 281. */
private const val SEND_FROM_LEFT = 12
internal const val SEND_FROM_TOP = 148
internal const val SEND_FROM_GAP = 4
internal const val SEND_FROM_BOTTOM = 281

/** A card's height in frame 6.4: 34, or 57 for the focused phone number with its caption. */
internal fun handleCardHeight(handle: String, focused: Boolean): Int =
    if (focused && handle.startsWith("tel:")) 57 else 34

/**
 * How far the "send from" page is scrolled up once [focused] has the focus,
 * coming from [current]. It moves only by the overshoot: just enough to keep
 * the focused card between [SEND_FROM_TOP] and [SEND_FROM_BOTTOM] (where the
 * frame's three cards sit), and back to 0 on the first card (F25/F38).
 */
internal fun handlePickerScroll(handles: List<String>, focused: Int, current: Int): Int {
    if (handles.isEmpty() || focused <= 0) return 0
    val i = focused.coerceAtMost(handles.lastIndex)
    var top = SEND_FROM_TOP
    for (j in 0 until i) top += handleCardHeight(handles[j], focused = false) + SEND_FROM_GAP
    val bottom = top + handleCardHeight(handles[i], focused = true)
    return when {
        bottom - current > SEND_FROM_BOTTOM -> bottom - SEND_FROM_BOTTOM
        top - current < SEND_FROM_TOP -> top - SEND_FROM_TOP
        else -> current
    }
}

/** The handle select saves: the picked card's, or "" when there are none (F28/F37). */
internal fun sendFromChoice(handles: List<String>, picked: Int): String = handles.getOrNull(picked).orEmpty()

/**
 * The handle as setup's "send from" page shows it: a North American number
 * reads "+1 (404) 820-8597" rather than "+14048208597" (F26/F41). The dash
 * follows the incoming-call frames (5.2), so numbers look the same everywhere
 * on the flip; the owner chose it over this page's own sample, which has none
 * (2026-11-06).
 * Anything else (emails, other countries) is shown as [prettyHandle] gives it.
 * Only this page: the older pages keep prettyHandle unchanged.
 */
internal fun setupHandleLabel(handle: String): String {
    val plain = prettyHandle(handle)
    if (!handle.startsWith("tel:")) return plain
    val digits = plain.filter { it.isDigit() }
    val national = when {
        plain.startsWith("+1") && digits.length == 11 -> digits.substring(1)
        !plain.startsWith("+") && digits.length == 10 -> digits
        else -> return plain
    }
    return "+1 (${national.substring(0, 3)}) ${national.substring(3, 6)}-${national.substring(6)}"
}
