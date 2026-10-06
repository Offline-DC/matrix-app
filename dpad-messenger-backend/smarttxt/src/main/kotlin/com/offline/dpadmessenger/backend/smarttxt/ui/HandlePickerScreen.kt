package com.offline.dpadmessenger.backend.smarttxt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offline.dpadmessenger.backend.smarttxt.RustPushNative
import com.offline.dpadmessenger.backend.smarttxt.SmartTxtAccountStore
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys
import kotlinx.coroutines.delay

/** "tel:+1…" → "+1…", "mailto:x@y" → "x@y". */
private fun prettyHandle(h: String): String = h.removePrefix("tel:").removePrefix("mailto:")

/** The pill's text and the menu rows: Helvetica Now Display Extra Bold 14/18.2. */
private val HandleText = TextStyle(
    fontFamily = DisplayExtraBold,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 14.sp,
    lineHeight = 18.2.sp,
    color = InkStrong,
)

/**
 * "new conversations will start from" — choose which registered handle (the
 * phone number, or an iCloud email) new messages are sent from. Figma
 * "onboarding - 20" (node 50:26276); comes right after "activating smart txt...".
 *
 * White page, the title, one pill-shaped dropdown (216dp, 1dp black stroke,
 * 20dp radius, 16/12dp side padding, the handle in Extra Bold 14 and a
 * drop-down arrow), and a navbar whose only key is "continue".
 *
 * Keys. The old screen had a focused "Continue" button with the dropdown one
 * D-pad Up away; Figma drops the button and puts "continue" on the centre
 * soft key, so OK now means continue. Changing the handle therefore can't
 * also be OK. It is D-pad Down instead — the way the arrow points, and how a
 * native spinner opens on a keypad phone — which opens the list (see
 * HandleSheet); Up/Down move in it, OK or a digit picks and closes it, Back
 * closes it unchanged (the centre label reads "select" while it's open). With a single handle there is nothing to pick, so the arrow
 * and the Down behaviour are dropped and the pill is just the answer.
 *
 * The phone number stays the default selection, as before. Continue still
 * persists the choice and applies it to the native client straight away, so
 * the first message really is sent from it.
 *
 * Title is the launcher onboarding size (34/34) — see SignInTitle.
 */
@Composable
fun HandlePickerScreen(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SmartTxtAccountStore(context) }
    val handles = remember { store.loadAccount()?.handles.orEmpty() }

    // Default the sending handle to the phone number, else the first handle.
    var selected by remember {
        mutableStateOf(handles.firstOrNull { it.startsWith("tel:") } ?: handles.firstOrNull() ?: "")
    }
    var expanded by remember { mutableStateOf(false) }
    val canChange = handles.size > 1
    val pageFr = remember { FocusRequester() }

    // Focus the page (retry a few frames until it's attached) so OK/Down land here.
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { pageFr.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }

    val continueNow: () -> Unit = {
        store.saveHandleSelection(enabled = handles, default = selected)
        // Apply immediately so the very first outgoing text sends from
        // the chosen number/email (not just handles.first()).
        RustPushNative.runCatchingNativeSetSendHandle(selected)
        onContinue()
    }

    // Row under the cursor while the list is open. Starts on the current
    // choice, so opening and pressing OK straight away changes nothing.
    var menuIndex by remember { mutableStateOf(0) }
    val openList: () -> Unit = {
        menuIndex = handles.indexOf(selected).coerceAtLeast(0)
        expanded = true
    }
    val pick: (Int) -> Unit = { i ->
        handles.getOrNull(i)?.let { selected = it }
        expanded = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PageWhite)
            .focusRequester(pageFr)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (expanded) {
                    // The list is drawn on this page (not a popup), so its keys
                    // come through here: Up/Down move, OK picks, a digit picks
                    // that numbered row directly, Back closes without changing.
                    val digit = e.key.digitOrNull()
                    when {
                        e.key == Key.DirectionDown -> menuIndex = (menuIndex + 1).coerceAtMost(handles.lastIndex)
                        e.key == Key.DirectionUp -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                        isOk -> if (e.nativeKeyEvent.repeatCount == 0) pick(menuIndex)
                        digit != null && digit in 1..handles.size -> pick(digit - 1)
                        e.key == Key.Back -> expanded = false
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                when {
                    isOk -> {
                        if (e.nativeKeyEvent.repeatCount == 0) continueNow()
                        true
                    }
                    e.key == Key.DirectionDown -> {
                        if (canChange) openList()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // Figma 50:26348: dp2/sans heading in a 216dp box, 21dp from the
            // top, breaking "new / conversations / will start from". Figma sets
            // it at 28/28; it uses the shared 34sp SignInTitle instead so every
            // Smart Txt setup title is the same size. It was 28sp for a while
            // because "conversations" (226dp at 34sp) overflows Figma's 216dp
            // box — but the handset's screen is wider than Figma's 240dp frame,
            // so it fits there, and the explicit breaks below stop it rewrapping.
            // Explicit breaks: left to wrap, the handset's wider screen put
            // "new conversations / will start from" on two lines.
            Text(
                "new\nconversations\nwill start from",
                style = SignInTitle,
                modifier = Modifier.fillMaxWidth().padding(top = 21.dp),
            )

            if (handles.isNotEmpty()) {
                // Open state (Figma "onboarding - 25", node 50:26347): while the
                // list is open the pill turns sky blue at 50% — one flat light
                // blue, no darker rim (drawing a 50% stroke over a 50% fill
                // doubled up at the edge), 32dp tall, 20dp radius.
                //
                // Same size and place as the closed white pill. Figma insets the
                // open pill 8dp each side (200dp instead of 216), and that was
                // first taken as drawn — but on the 4058W it read as the pill
                // shrinking and its text jumping 8dp right when the list opened.
                // Only the colour changes now.
                val pillShape = RoundedCornerShape(20.dp)
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp) // Figma: 12dp under the title
                        .fillMaxWidth()
                        .then(
                            if (expanded) Modifier.background(PillOpen, pillShape)
                            else Modifier.border(1.dp, InkStrong, pillShape)
                        )
                        .height(32.dp)
                        .padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        prettyHandle(selected),
                        style = HandleText,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (canChange) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "change",
                            tint = InkStrong,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (expanded) {
            HandleSheet(
                handles = handles,
                highlighted = menuIndex,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    SoftKeys(
        center = if (expanded) SoftKey("select") { pick(menuIndex) } else SoftKey("continue") { continueNow() },
    )
}

private val SheetCream = Color(0xFFFEFDEA)    // the sheet's resting rows
private val Sunscreen = Color(0xFFFAF594)     // fixed/brand (sunscreen) — the highlighted row
private val RowDivider = Color.Black.copy(alpha = 0.1f)
/** SkyLooking (#38ABFC) at 50% over the white page, flattened: #9BD5FE. */
private val PillOpen = Color(0xFF9BD5FE)
/** Handle-list row: roomier than Figma's 37dp per review — 12dp above/below the text. */
private val SheetRowHeight = 42.dp

/**
 * The handle list (Figma "onboarding - 26", node 50:26361): a full-width
 * bottom sheet over a 40%-black scrim, one 37dp row per handle, numbered
 * "1." "2." …, a 10%-black hairline under every row, and the row under the
 * cursor filled sunscreen yellow.
 *
 * Drawn on the page rather than with Material's DropdownMenu. The design is
 * a sheet pinned to the bottom of the screen, not a menu hanging off the
 * pill, and keeping it in-page means the page's own key handler drives it —
 * no popup taking focus and handing it back (which is what made the next OK
 * after a pick unreliable). The numbers aren't decoration: pressing a digit
 * picks that row, the quickest way on a keypad phone.
 *
 * Long lists scroll; the scroll follows the highlighted row.
 */
@Composable
private fun HandleSheet(
    handles: List<String>,
    highlighted: Int,
    modifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
    val scroll = rememberScrollState()
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { (SheetRowHeight + 1.dp).roundToPx() }
    LaunchedEffect(highlighted) { scroll.animateScrollTo(highlighted * rowHeightPx) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = (SheetRowHeight + 1.dp) * 5)
            .background(SheetCream)
            .verticalScroll(scroll),
    ) {
        handles.forEachIndexed { i, h ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SheetRowHeight)
                    .background(if (i == highlighted) Sunscreen else SheetCream)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "1." then the handle, Figma's 21dp list indent between them.
                Text("${i + 1}.", style = HandleText.copy(color = Color.Black), modifier = Modifier.width(21.dp))
                Text(prettyHandle(h), style = HandleText.copy(color = Color.Black), maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(RowDivider))
        }
    }
}

private fun Key.digitOrNull(): Int? = when (this) {
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}
