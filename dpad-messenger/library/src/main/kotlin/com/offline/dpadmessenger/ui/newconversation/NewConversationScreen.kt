package com.offline.dpadmessenger.ui.newconversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offline.dpadmessenger.data.ContactEntry
import com.offline.dpadmessenger.data.ContactsSource
import com.offline.dpadmessenger.data.ConversationStarter
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.components.InitialsAvatar
import com.offline.dpadmessenger.ui.theme.LocalDpadMessengerColors
import kotlinx.coroutines.launch

/**
 * New-conversation screen.
 *
 * One search box drives everything: type letters to filter the phone's
 * contacts, or type a number to text someone unsaved. Below the box is a
 * DPAD list — when the input looks like a phone number, the first row is
 * "Send to <number>"; matching contacts follow. OK on any row resolves the
 * conversation and opens the chat.
 *
 * DPAD wiring (all explicit — text fields eat directional keys otherwise):
 *   field: Up → back button · Down → first result row
 *   rows:  Up from row 0 → field · OK → start chat
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    starter: ConversationStarter,
    contactsSource: ContactsSource?,
    onBack: () -> Unit,
    onConversationStarted: (roomId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(contactsSource != null) }
    val scope = rememberCoroutineScope()
    val fieldFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val colors = LocalDpadMessengerColors.current

    LaunchedEffect(Unit) {
        runCatching { fieldFocus.requestFocus() }
        contacts = contactsSource?.let { runCatching { it.listContacts() }.getOrNull() }.orEmpty()
        contactsLoading = false
    }

    // Filtered view of the address book, capped at MAX_RESULTS — a DPAD list
    // longer than that is unusable anyway; typing narrows it. Letters match
    // names; digits match numbers (formatting-insensitive).
    val filtered = remember(query, contacts) {
        val q = query.trim()
        val matches = if (q.isEmpty()) contacts
        else contacts.filter { c ->
            c.name.contains(q, ignoreCase = true) ||
                (q.any(Char::isDigit) &&
                    c.number.filter(Char::isDigit).contains(q.filter(Char::isDigit)))
        }
        matches.take(MAX_RESULTS)
    }
    // Does the query look like a dialable number? Then offer it directly.
    val dialable = remember(query) {
        val digits = query.filter(Char::isDigit)
        digits.length >= 3 && query.all { it.isDigit() || it in "+-() ." }
    }

    fun start(destination: String) {
        if (busy) return
        busy = true
        error = null
        // Drop focus so it doesn't snap to the back button while the result
        // list disappears behind the spinner — focus stays nowhere until we
        // navigate into the new chat.
        focusManager.clearFocus(force = true)
        scope.launch {
            val roomId = runCatching { starter.startConversation(destination) }.getOrNull()
            busy = false
            if (roomId != null) {
                onConversationStarted(roomId)
            } else {
                error = "Couldn't start a conversation. Check the number and try again."
                // Focus was cleared on submit; put it back on the field so the
                // user isn't stranded on a screen with no DPAD highlight.
                runCatching { fieldFocus.requestFocus() }
            }
        }
    }

    Scaffold(
        topBar = {
            CompactTopBar(
                title = "New message",
                navigationIcon = {
                    CompactBarButton(
                        onClick = onBack,
                        focusRequester = backFocus,
                        extraModifier = Modifier.onPreviewKeyEvent { event ->
                            // Down from Back returns to the search field.
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                runCatching { fieldFocus.requestFocus() }
                                true
                            } else false
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding())
                .padding(horizontal = 12.dp)
                .fillMaxSize(),
        ) {
            Spacer(Modifier.size(8.dp))

            // Search / number entry box.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .dpadFocusHighlight(shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = { if (dialable) start(query.trim()) },
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        // Text fields consume DPAD for cursor movement, so
                        // route the navigation keys explicitly — otherwise the
                        // back button and result rows are unreachable.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionUp -> {
                                    runCatching { backFocus.requestFocus() }; true
                                }
                                Key.DirectionDown -> {
                                    runCatching { firstRowFocus.requestFocus() }; true
                                }
                                else -> false
                            }
                        },
                    decorationBox = { innerField ->
                        if (query.isEmpty()) {
                            Text(
                                "Search contacts or enter a number",
                                color = colors.mutedText,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerField()
                    },
                )
            }

            if (error != null) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.size(6.dp))

            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // Result rows: optional "Send to <number>" first, then contacts.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (dialable) {
                    item(key = "dial") {
                        ResultRow(
                            title = "Send to ${query.trim()}",
                            subtitle = "New number",
                            avatarName = query.trim(),
                            avatarColor = "#607D8B",
                            focusRequester = firstRowFocus,
                            onUpToField = { runCatching { fieldFocus.requestFocus() } },
                            onClick = { start(query.trim()) },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                        )
                    }
                }
                itemsIndexed(
                    items = filtered,
                    key = { _, c -> c.number + c.name },
                ) { index, contact ->
                    val isFirstFocusable = !dialable && index == 0
                    ResultRow(
                        title = contact.name,
                        subtitle = contact.number,
                        avatarName = contact.name,
                        avatarColor = contact.avatarColor,
                        focusRequester = if (isFirstFocusable) firstRowFocus else null,
                        onUpToField = if (isFirstFocusable || (dialable && index == 0)) {
                            { runCatching { fieldFocus.requestFocus() } }
                        } else null,
                        onClick = { start(contact.number) },
                    )
                }
                if (contactsLoading && filtered.isEmpty()) {
                    item(key = "loading") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Loading contacts…",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.mutedText,
                            )
                        }
                    }
                } else if (!dialable && filtered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (contacts.isEmpty()) {
                                "Type a phone number to start a chat."
                            } else {
                                "No contacts match — type a number instead."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.mutedText,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    avatarName: String,
    avatarColor: String,
    focusRequester: FocusRequester?,
    onUpToField: (() -> Unit)?,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    // LazyColumn only composes rows near the viewport, so directional focus
    // can't reach an off-screen row on its own. Wire bringIntoView on focus so
    // DPAD-Down scrolls the next row into view (and thus into composition),
    // letting the user page through a long contact list.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusEvent { if (it.isFocused) scope.launch { bringIntoView.bringIntoView() } }
            .then(
                if (onUpToField != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            onUpToField(); true
                        } else false
                    }
                } else Modifier,
            )
            .dpadRow(onClick = onClick, focusRequester = focusRequester, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (leadingIcon != null) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leadingIcon() }
        } else {
            InitialsAvatar(name = avatarName, colorHex = avatarColor, size = 36.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LocalDpadMessengerColors.current.mutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Cap on visible picker rows — type to narrow instead of DPAD-paging forever. */
private const val MAX_RESULTS = 10
