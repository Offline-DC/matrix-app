package com.offline.dpadmessenger.ui.newconversation

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
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
import com.offline.dpadmessenger.data.GroupConversationStarter
import com.offline.dpadmessenger.focus.dpadFocusHighlight
import com.offline.dpadmessenger.focus.dpadRow
import com.offline.dpadmessenger.focus.handOffFocus
import com.offline.dpadmessenger.ui.components.CompactBarButton
import com.offline.dpadmessenger.ui.components.CompactTopBar
import com.offline.dpadmessenger.ui.components.InitialsAvatar
import com.offline.dpadmessenger.ui.navbar.SoftKey
import com.offline.dpadmessenger.ui.navbar.SoftKeys
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
    /** When non-null, a "New group" multi-select mode is offered. */
    groupStarter: GroupConversationStarter? = null,
) {
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var contactsLoading by remember { mutableStateOf(contactsSource != null) }
    // Group multi-select: when true, OK on a row toggles selection instead of
    // opening a 1:1, and the top row creates the group.
    var groupMode by remember { mutableStateOf(false) }
    val selectedNumbers = remember { mutableStateListOf<String>() }
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

    // The address book, presented names-first and alphabetically. A contact
    // saved with a real name sorts A→Z at the top; an unsaved contact whose
    // "name" is really just its phone number has no alphabetic label and sinks
    // below the named ones. Without this, digits and "+" sort BEFORE letters in
    // Unicode, so bare-number entries jumped to the top and buried real names
    // past the MAX_RESULTS cap — the picker looked like "numbers only" on open
    // until you started typing a name.
    val orderedContacts = remember(contacts) {
        contacts.sortedWith(
            // true (name contains a letter) sorts ahead of false → named first.
            compareByDescending<ContactEntry> { it.name.any(Char::isLetter) }
                .thenBy { it.name.trim().lowercase() }
                .thenBy { it.number },
        )
    }

    // Filtered view of the address book, capped at MAX_RESULTS — a DPAD list
    // longer than that is unusable anyway; typing narrows it. Letters match
    // names; digits match numbers (formatting-insensitive). The names-first,
    // A→Z order is inherited from [orderedContacts].
    val filtered = remember(query, orderedContacts) {
        val q = query.trim()
        val matches = if (q.isEmpty()) orderedContacts
        else orderedContacts.filter { c ->
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

    fun toggleSelected(number: String) {
        val n = number.trim()
        if (n.isEmpty()) return
        if (selectedNumbers.contains(n)) selectedNumbers.remove(n) else selectedNumbers.add(n)
    }

    fun createGroup() {
        if (busy || groupStarter == null || selectedNumbers.size < 2) return
        busy = true
        error = null
        val numbers = selectedNumbers.toList()
        focusManager.clearFocus(force = true)
        scope.launch {
            val roomId = runCatching { groupStarter.startGroupConversation(numbers, null) }.getOrNull()
            busy = false
            if (roomId != null) {
                onConversationStarted(roomId)
            } else {
                error = "Couldn't create the group. Try again."
                runCatching { firstRowFocus.requestFocus() }
            }
        }
    }

    // Back exits group mode first (clearing the selection), then leaves.
    BackHandler(enabled = groupMode) {
        groupMode = false
        selectedNumbers.clear()
        runCatching { fieldFocus.requestFocus() }
    }

    Scaffold(
        // Right key only. The left one stays blank: the keypad belongs to the
        // search field here, so there is nothing for it to do.
        //
        // Deliberately routed through the same two steps as hardware Back above
        // rather than straight to onBack() — in group mode "back" drops the
        // selection first. A soft key labelled "back" that skipped that step
        // would leave the label saying one thing and doing another.
        bottomBar = {
            SoftKeys(
                right = SoftKey("back") {
                    if (groupMode) {
                        groupMode = false
                        selectedNumbers.clear()
                        runCatching { fieldFocus.requestFocus() }
                    } else {
                        onBack()
                    }
                },
            )
        },
        topBar = {
            CompactTopBar(
                title = if (groupMode) "New group" else "New message",
                navigationIcon = {
                    CompactBarButton(
                        onClick = onBack,
                        focusRequester = backFocus,
                        extraModifier = Modifier.onPreviewKeyEvent { event ->
                            // Down from Back returns to the search field. Consumes
                            // the key ONLY if focus actually moved — the old code threw
                            // the requestFocus result away and consumed regardless, so a
                            // field that was not yet attached left the user stuck on
                            // Back with no way down. See handOffFocus.
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                scope.handOffFocus(
                                    target = fieldFocus,
                                    focusManager = focusManager,
                                    fallback = FocusDirection.Down,
                                )
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

            // Result rows. Topmost focusable owns `firstRowFocus` (DPAD-Up from
            // it returns to the field). With a group control present, that's the
            // group row; otherwise the dial row or the first contact.
            val groupRowPresent = groupStarter != null
            val toField = { runCatching { fieldFocus.requestFocus() }; Unit }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (groupRowPresent) {
                    item(key = "group") {
                        val canCreate = selectedNumbers.size >= 2
                        ResultRow(
                            title = if (groupMode) "Create group" else "New group",
                            subtitle = when {
                                !groupMode -> "Message several people at once"
                                canCreate -> "${selectedNumbers.size} selected — OK to create"
                                else -> "Pick at least 2 people"
                            },
                            avatarName = "+",
                            avatarColor = "#3F51B5",
                            focusRequester = firstRowFocus,
                            onUpToField = toField,
                            onClick = { if (!groupMode) groupMode = true else createGroup() },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Group,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                        )
                    }
                }
                if (dialable) {
                    item(key = "dial") {
                        val number = query.trim()
                        val isFirst = !groupRowPresent
                        ResultRow(
                            title = if (groupMode) "Add $number" else "Send to $number",
                            subtitle = "New number",
                            avatarName = number,
                            avatarColor = "#607D8B",
                            focusRequester = if (isFirst) firstRowFocus else null,
                            onUpToField = if (isFirst) toField else null,
                            onClick = { if (groupMode) toggleSelected(number) else start(number) },
                            trailing = if (groupMode) {
                                { SelectionCheck(selected = selectedNumbers.contains(number)) }
                            } else null,
                            leadingIcon = if (groupMode) null else {
                                {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                    }
                }
                itemsIndexed(
                    items = filtered,
                    key = { _, c -> c.number + c.name },
                ) { index, contact ->
                    val isFirstFocusable = !groupRowPresent && !dialable && index == 0
                    ResultRow(
                        title = contact.name,
                        subtitle = contact.number,
                        avatarName = contact.name,
                        avatarColor = contact.avatarColor,
                        focusRequester = if (isFirstFocusable) firstRowFocus else null,
                        onUpToField = if (isFirstFocusable) toField else null,
                        onClick = {
                            if (groupMode) toggleSelected(contact.number) else start(contact.number)
                        },
                        trailing = if (groupMode) {
                            { SelectionCheck(selected = selectedNumbers.contains(contact.number.trim())) }
                        } else null,
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
    trailing: (@Composable () -> Unit)? = null,
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
        Column(modifier = Modifier.weight(1f)) {
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
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Round check used in group multi-select to show whether a contact is picked. */
@Composable
private fun SelectionCheck(selected: Boolean) {
    if (selected) {
        Icon(
            Icons.Filled.Check,
            contentDescription = "Selected",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    } else {
        Icon(
            Icons.Filled.RadioButtonUnchecked,
            contentDescription = "Not selected",
            tint = LocalDpadMessengerColors.current.mutedText,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Cap on visible picker rows — type to narrow instead of DPAD-paging forever. */
private const val MAX_RESULTS = 10
