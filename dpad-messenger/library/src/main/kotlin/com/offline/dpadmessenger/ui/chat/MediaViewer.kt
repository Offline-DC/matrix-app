package com.offline.dpadmessenger.ui.chat

import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.offline.dpadmessenger.data.AttachmentKind
import com.offline.dpadmessenger.focus.DpadFireGate
import com.offline.dpadmessenger.focus.OkKeys
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys
import com.offline.dpadmessenger.ui.theme.SoftKeyFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fullscreen media viewer overlay — black background, the image or video centred,
 * and the soft keys driving everything the screen can do. Mounted on top of the
 * chat by [com.offline.dpadmessenger.ui.chat.ChatScreen].
 *
 * There used to be a floating back arrow in the top-left corner, DPAD-focusable
 * with OK to close. It's gone: the soft key does the same job without covering a
 * corner of the picture or taking the focus a photo has no other use for. Hardware
 * Back still closes too.
 *
 * ## The keys
 *
 * Three states, and the bar says which one you're in:
 *
 * | | left | centre | right |
 * |---|---|---|---|
 * | photo | `options` | — | `back` |
 * | video | `options` | `play` / `pause` | `back` |
 * | options open | — | `select` | `back` (closes the sheet) |
 * | zoom mode | `zoom out` | — | `zoom in` |
 *
 * Hardware Back unwinds them one layer at a time — zoom, then the sheet, then the
 * viewer — rather than dumping you back in the thread from three levels deep.
 *
 * ## Focus
 *
 * The viewer takes focus while it's up, which it never used to. It needs it: the
 * centre key for a video is `DPAD_CENTER`, and zoom mode pans on the arrows, and
 * neither arrives at a screen that holds no focus. It also fixes something that
 * was always wrong — the arrows used to fall through to the message list *behind*
 * the picture and scroll it, which is why closing the viewer has to cope with the
 * bubble it came from having drifted off-screen.
 */
@Composable
fun FullscreenMediaViewer(
    path: String,
    kind: AttachmentKind,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = kind == AttachmentKind.VIDEO

    var showOptions by remember { mutableStateOf(false) }
    var zoomMode by remember { mutableStateOf(false) }
    val zoom = remember { ZoomState() }
    val video = remember { VideoHandle() }
    val keyFocus = remember { FocusRequester() }

    // ONE handler, not three nested ones. The viewer has three closable layers and
    // the order they unwind in is the point: from inside a zoomed photo, Back is
    // how you get back OUT of the zoom — it must not close the viewer and drop you
    // in the thread. Nested BackHandlers would leave that order depending on
    // composition order, which is exactly the kind of thing that quietly inverts.
    BackHandler {
        when {
            zoomMode -> {
                zoomMode = false
                zoom.reset()
            }
            showOptions -> showOptions = false
            else -> onClose()
        }
    }

    // Take the keypad back when the sheet closes. A frame's wait lets the sheet's
    // own focus target go away first, otherwise the request lands on a node that
    // is about to be removed and focus ends up nowhere.
    LaunchedEffect(showOptions) {
        if (!showOptions) {
            withFrameNanos {}
            runCatching { keyFocus.requestFocus() }
        }
    }

    // A Column, not a Box: off the handsets the soft keys draw their own row and it
    // has to sit below the media rather than on top of it. On a TCL the row is the
    // real system bar and this is a full-bleed picture again.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(keyFocus)
            // Stand down while the sheet is up. The sheet's rows are siblings of
            // this Column, not children, so an arrow press at the top row would
            // otherwise find this as the next focus target — focus leaves the
            // sheet, and the sheet's keys go dead with it still on screen.
            .focusable(enabled = !showOptions)
            .onPreviewKeyEvent { event ->
                onViewerKey(event, isVideo, zoomMode, showOptions, zoom, video)
            },
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { zoom.boxSize = it },
            contentAlignment = Alignment.Center,
        ) {
            when {
                isVideo -> VideoPlayer(path, video)
                else -> ImageContent(path, zoom)
            }
        }

        // Released entirely while the sheet is up, rather than left underneath
        // for the sheet to stack on. The two claims would be correctly ordered at
        // the moment the sheet opens — but the host's binding is keyed on the
        // LABELS, and this one's centre label flips between "play" and "pause" on
        // its own as the video prepares or ends. That re-key is an unbind/bind,
        // which puts THIS claim back on top of the stack with the sheet still on
        // screen: the bar reverts to options/play/back and the right key closes
        // the whole viewer instead of the sheet. Nothing to race if it isn't held.
        if (!showOptions) SoftKeys(
            left = if (zoomMode) {
                // Labels stay put at the limits rather than blanking out: a key
                // that disappears when you reach maximum zoom reads as broken, and
                // the bar has no way to show a disabled key. The press no-ops.
                SoftKey("zoom out") { zoom.zoomOut() }
            } else {
                SoftKey("options") { showOptions = true }
            },
            // Label only, no action — even here, where the screen genuinely owns
            // DPAD_CENTER. If the bar carried the action too, a firmware that
            // reports the centre press through BOTH its own listener and
            // DPAD_CENTER would toggle playback twice from one press, which is the
            // same double-fire that deleted two grid tiles at a time in the
            // launcher. One press, one owner: the key handler below.
            center = if (isVideo && !zoomMode) {
                SoftKey(if (video.playing) "pause" else "play")
            } else {
                null
            },
            right = if (zoomMode) {
                SoftKey("zoom in") { zoom.zoomIn() }
            } else {
                SoftKey("back") { onClose() }
            },
        )
    }

    // Composed AFTER the viewer's SoftKeys so its claim lands on top of the stack.
    // It has to claim at all: this sheet is in the SAME window as the viewer, so
    // without one the viewer's "back" would stay live underneath and the right key
    // would close the whole viewer instead of the sheet.
    if (showOptions) {
        MediaOptionsSheet(
            // Zoom is photos only. A video has nothing to zoom into — it plays at
            // one size — so the option isn't offered rather than offered and inert.
            canZoom = !isVideo,
            onSave = {
                showOptions = false
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        saveMediaToGallery(context, path, kind)
                    }
                    // A toast, not a notification: this is the answer to something
                    // the user just did and it belongs on the screen they did it
                    // on, not in a shade they have to go and open.
                    Toast.makeText(
                        context,
                        if (saved) "Saved to gallery" else "Couldn't save to gallery",
                        if (saved) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onZoom = {
                showOptions = false
                zoomMode = true
            },
            onDismiss = { showOptions = false },
        )
    }
}

/**
 * The viewer's key handling, kept out of the composable so the whole policy reads
 * in one place.
 *
 * Returns true for every arrow and OK press, zoomed or not, on purpose. Unhandled
 * ones don't sit still — Compose treats an arrow as a focus search, so falling
 * through would walk focus out of the viewer and into the message list behind the
 * picture. Consuming them keeps the keypad inside the thing that's on screen.
 */
private fun onViewerKey(
    event: KeyEvent,
    isVideo: Boolean,
    zoomMode: Boolean,
    showOptions: Boolean,
    zoom: ZoomState,
    video: VideoHandle,
): Boolean {
    if (event.type == KeyEventType.KeyUp) {
        // Release the shared OK gate. A row in the options sheet acquires it on
        // key-DOWN (see onDpadAction) and is then disposed by its own action
        // before the key-UP lands — so the up arrives here instead, and without
        // this the gate stays held for its full 2s timeout, swallowing the next
        // OK press anywhere in the app.
        if (event.key in OkKeys) DpadFireGate.release(event.key)
        return false
    }
    if (event.type != KeyEventType.KeyDown) return false
    // Hold-to-repeat is not a second press. onDpadAction filters these as its
    // fast path; without the same filter, holding OK on a video toggles playback
    // at the repeat rate. Consumed rather than passed on, so a repeat still can't
    // walk focus out of the viewer.
    if (event.nativeKeyEvent.repeatCount != 0) return true
    // The sheet owns the keypad while it's up; its rows are the focus targets.
    if (showOptions) return false
    return when (event.key) {
        // Panning moves the VIEW, so the picture travels the opposite way: press
        // right to see what's off the right edge.
        Key.DirectionUp -> { if (zoomMode) zoom.pan(0f, PAN_STEP); true }
        Key.DirectionDown -> { if (zoomMode) zoom.pan(0f, -PAN_STEP); true }
        Key.DirectionLeft -> { if (zoomMode) zoom.pan(PAN_STEP, 0f); true }
        Key.DirectionRight -> { if (zoomMode) zoom.pan(-PAN_STEP, 0f); true }
        Key.DirectionCenter, Key.Enter -> { if (isVideo && !zoomMode) video.toggle(); true }
        else -> false
    }
}

/** How far one arrow press pans, as a fraction of the visible box. */
private const val PAN_STEP = 0.25f

// ---------------------------------------------------------------- options sheet

/**
 * The `options` submenu — a bottom sheet in the style of the podcast app's overlay:
 * black, hugging its content so the picture stays visible above it, with a rule
 * along its top edge marking where the screen behind ends.
 *
 * Not a `ModalBottomSheet` like the two context sheets. Those are Dialogs in their
 * own window and take an empty bar claim; this one is deliberately in the viewer's
 * window so it can layer its own working keys over the viewer's, and so the photo
 * behind it doesn't dim.
 */
@Composable
private fun MediaOptionsSheet(
    canZoom: Boolean,
    onSave: () -> Unit,
    onZoom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(canZoom, onSave, onZoom) {
        buildList {
            add(OptionItem("save", onSave))
            if (canZoom) add(OptionItem("zoom", onZoom))
        }
    }
    // A roving index rather than Compose's own focus search — the same shape the
    // podcast overlay uses, and here it is load-bearing rather than cosmetic. This
    // sheet is a SIBLING of the viewer, and the chat timeline behind the picture is
    // still composed and still focusable: a plain focus search on UP from the top
    // row walks straight out of the sheet and lands on a message bubble nobody can
    // see, with the sheet still on screen and OK now activating that bubble.
    // Clamping the index at both ends is what keeps the keypad inside the sheet.
    var index by remember(rows.size) { mutableStateOf(0) }
    val requesters = remember(rows.size) { List(rows.size) { FocusRequester() } }

    LaunchedEffect(index, rows.size) {
        runCatching { requesters[index.coerceIn(0, rows.lastIndex)].requestFocus() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent true
                    when (event.key) {
                        Key.DirectionUp -> {
                            index = (index - 1).coerceAtLeast(0); true
                        }
                        Key.DirectionDown -> {
                            index = (index + 1).coerceAtMost(rows.lastIndex); true
                        }
                        // Nothing sideways to reach, and letting these through
                        // would find the timeline for the same reason UP does.
                        Key.DirectionLeft, Key.DirectionRight -> true
                        // Deliberately NOT DPAD_CENTER: the rows activate on it
                        // themselves, and intercepting it here would either kill
                        // that or fire the action a second time.
                        else -> false
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White),
            )
            Text(
                text = "options",
                color = Color.White,
                fontSize = 16.sp,
                // The bar's face, not the chat's — this sheet is opened by a soft
                // key and closed by one, so it reads as part of the bar rather
                // than as another screen. See [SoftKeyFontFamily] for why the
                // rest of the messenger deliberately stays on the system font.
                fontFamily = SoftKeyFontFamily,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 5.dp),
            )

            rows.forEachIndexed { i, row ->
                OptionRow(
                    label = row.label,
                    focusRequester = requesters[i],
                    onClick = row.onClick,
                )
            }

            // This sheet's claim. "select" is a label with no action, as everywhere
            // else in the app: the hardware centre is DPAD_CENTER and the rows
            // above already activate on it.
            SoftKeys(
                center = SoftKey("select"),
                right = SoftKey("back") { onDismiss() },
            )
        }
    }
}

/** One row of the options sheet: what it says, and what it does. */
private data class OptionItem(val label: String, val onClick: () -> Unit)

@Composable
private fun OptionRow(
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 14.sp,
        fontFamily = SoftKeyFontFamily,
        modifier = Modifier
            .fillMaxWidth()
            .dpadRow(
                onClick = onClick,
                focusRequester = focusRequester,
                // Square, thin, white: the theme's rounded accent ring is built
                // for a light surface and all but vanishes on the black sheet.
                shape = RoundedCornerShape(0.dp),
                focusBorderColor = Color.White,
                focusBorderWidth = 2.dp,
            )
            .padding(horizontal = 9.dp, vertical = 7.dp),
    )
}

// ------------------------------------------------------------------------ zoom

/**
 * Scale and pan for the photo, and the arithmetic that stops you dragging it off
 * into the black.
 *
 * Held outside the composition (a plain remembered object) because the soft keys,
 * the key handler and the layer that draws all read and write the same numbers.
 */
private class ZoomState {
    var scale by mutableStateOf(1f)
        private set
    var offsetX by mutableStateOf(0f)
        private set
    var offsetY by mutableStateOf(0f)
        private set

    /** The box the media is drawn into, px. Set by the parent's onSizeChanged. */
    var boxSize by mutableStateOf(IntSize.Zero)

    /** Intrinsic size of the decoded media, px. Zero until it has decoded. */
    var contentSize by mutableStateOf(IntSize.Zero)

    fun zoomIn() = applyScale(scale * SCALE_STEP)

    fun zoomOut() = applyScale(scale / SCALE_STEP)

    fun reset() {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    // NOT setScale(): `var scale`'s generated JVM setter is already setScale(F)V
    // and the two clash at the bytecode level.
    private fun applyScale(target: Float) {
        val next = target.coerceIn(1f, MAX_SCALE)
        if (next == scale) return
        // Offsets are in screen px, so they have to grow with the picture — this
        // keeps whatever is in the middle of the screen in the middle of the
        // screen as the zoom changes, instead of sliding away under you.
        val ratio = next / scale
        scale = next
        offsetX *= ratio
        offsetY *= ratio
        clamp()
    }

    fun pan(dxFraction: Float, dyFraction: Float) {
        offsetX += dxFraction * boxSize.width
        offsetY += dyFraction * boxSize.height
        clamp()
    }

    private fun clamp() {
        val (maxX, maxY) = maxOffsets()
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    /**
     * How far the picture may travel before its own edge would come inside the
     * box — zero on an axis where the zoomed picture still fits, which is what
     * stops a portrait photo panning sideways into empty black.
     *
     * The picture is drawn `ContentScale.Fit`, so it's already at
     * `min(box/content)` before the zoom multiplies it; that fit factor is what
     * makes this the drawn size rather than the decoded one.
     */
    private fun maxOffsets(): Pair<Float, Float> {
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        val cw = contentSize.width.toFloat()
        val ch = contentSize.height.toFloat()
        if (bw <= 0f || bh <= 0f || cw <= 0f || ch <= 0f) return 0f to 0f
        val fit = minOf(bw / cw, bh / ch)
        val drawnW = cw * fit * scale
        val drawnH = ch * fit * scale
        return maxOf(0f, (drawnW - bw) / 2f) to maxOf(0f, (drawnH - bh) / 2f)
    }

    private companion object {
        const val SCALE_STEP = 1.5f

        /**
         * Deliberately modest. The viewer decodes to the screen's longer edge
         * (see [ImageContent]) rather than full size, so past roughly 3x there is
         * no more detail in the bitmap to reveal — only bigger soft pixels. The
         * alternative, re-decoding at a higher budget on entering zoom, is what
         * the comment in [ImageContent] warns about: a 2048px ARGB bitmap is
         * ~16MB on a handset that OOMs on back-to-back opens at 1280.
         */
        const val MAX_SCALE = 3f
    }
}

// ------------------------------------------------------------------ image/video

@Composable
private fun ImageContent(path: String, zoom: ZoomState) {
    // Decode only to the screen's longer edge (clamped) instead of a fixed
    // 1280 — on a 240–480px panel a 1280px ARGB bitmap is ~5MB wasted and an
    // OOM risk on back-to-back opens. Routed through the shared LRU cache.
    val dm = LocalContext.current.resources.displayMetrics
    val maxEdge = maxOf(dm.widthPixels, dm.heightPixels).coerceIn(480, 1080)
    // A GIF / animated WebP plays as a moving drawable at full screen; a still
    // image keeps the decoded-bitmap path. Detected by the downloaded file's
    // extension (our media cache names files by their mime type).
    val maybeAnimated = com.offline.dpadmessenger.ui.util.isMaybeAnimatedImage(path, null)
    // null = decoding; ImageResult(all null) = decode failed (e.g. HEIC with no codec).
    val result by produceState<ImageResult?>(
        initialValue = if (maybeAnimated) null
            else com.offline.dpadmessenger.ui.util.cachedBitmap(path, maxEdge)?.let { ImageResult(bitmap = it) },
        path, maxEdge,
    ) {
        value = withContext(Dispatchers.IO) {
            if (maybeAnimated) {
                com.offline.dpadmessenger.ui.util.decodeAnimatedDrawable(path, maxEdge)
                    ?.let { ImageResult(animated = it) }
                    ?: ImageResult(bitmap = com.offline.dpadmessenger.ui.util.decodeDownscaledCached(path, maxEdge))
            } else {
                ImageResult(bitmap = com.offline.dpadmessenger.ui.util.decodeDownscaledCached(path, maxEdge))
            }
        }
    }

    // The zoom clamp needs the picture's real proportions, which aren't known
    // until it has decoded — before that, panning is pinned at zero.
    val decoded = result
    LaunchedEffect(decoded) {
        val bmp = decoded?.bitmap
        val anim = decoded?.animated
        zoom.contentSize = when {
            bmp != null -> IntSize(bmp.width, bmp.height)
            anim != null -> IntSize(anim.intrinsicWidth, anim.intrinsicHeight)
            else -> IntSize.Zero
        }
    }

    // One layer over whichever renderer runs, so the still and animated paths
    // zoom identically and neither has to know about it.
    val zoomed = Modifier
        .fillMaxSize()
        .graphicsLayer(
            scaleX = zoom.scale,
            scaleY = zoom.scale,
            translationX = zoom.offsetX,
            translationY = zoom.offsetY,
        )

    when (val r = result) {
        null -> CircularProgressIndicator(color = Color.White)
        else -> {
            val anim = r.animated
            val bmp = r.bitmap
            when {
                anim != null -> com.offline.dpadmessenger.ui.util.AnimatedImage(
                    drawable = anim,
                    modifier = zoomed,
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER,
                )
                bmp != null -> Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = zoomed,
                )
                else -> Text(
                    text = "Can't preview this photo on this device.",
                    color = Color.White,
                )
            }
        }
    }
}

/** Distinguishes a finished-but-failed decode from "still decoding", and carries
 *  an animated drawable when the source is a GIF/WebP that actually animates. */
private data class ImageResult(
    val bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    val animated: android.graphics.drawable.AnimatedImageDrawable? = null,
)

/**
 * Playback state, hoisted out of the [VideoView] so the soft-key bar can drive it
 * and its label can say which way the key will go.
 *
 * [playing] is Compose state and the label reads it; the VideoView stays the
 * source of truth for what's actually happening, so a stream that stalls or ends
 * on its own can't leave the bar claiming to be playing.
 */
private class VideoHandle {
    var playing by mutableStateOf(false)
        private set

    private var view: VideoView? = null
    private var finished = false

    fun attach(v: VideoView) {
        view = v
    }

    fun detach() {
        view = null
        playing = false
    }

    fun toggle() {
        val v = view ?: return
        if (v.isPlaying) {
            v.pause()
            playing = false
        } else {
            // A video sitting on its last frame restarts rather than resuming
            // from an end position it's already at.
            if (finished) {
                v.seekTo(0)
                finished = false
            }
            v.start()
            playing = true
        }
    }

    fun onStarted() {
        finished = false
        playing = true
    }

    fun onFinished() {
        finished = true
        playing = false
    }
}

@Composable
private fun VideoPlayer(path: String, handle: VideoHandle) {
    var failed by remember { mutableStateOf(false) }
    if (failed) {
        Text(text = "Can't play this video on this device.", color = Color.White)
        return
    }
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = {
            VideoView(context).apply {
                setVideoPath(path)
                // No MediaController. The stock seek-bar overlay wants a touch
                // drag on a handset that has no touch to give it, and it sat over
                // the bottom of the picture duplicating a control the nav bar now
                // carries properly. play/pause is the centre soft key.
                //
                // Don't leave a black screen on an undecodable/corrupt video —
                // show a clear fallback (mirrors the image path).
                setOnErrorListener { _, _, _ -> failed = true; handle.detach(); true }
                setOnPreparedListener { it.isLooping = false; start(); handle.onStarted() }
                setOnCompletionListener { handle.onFinished() }
                handle.attach(this)
            }
        },
    )
    DisposableEffect(Unit) { onDispose { handle.detach() } }
}
