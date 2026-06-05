package com.offline.dpadmessenger.data

/**
 * Optional capability for repositories that can start a brand-new
 * conversation from a destination (phone number / address). The "new message"
 * compose flow uses it; repositories that don't support it (the mock) simply
 * don't implement it and the compose button is hidden.
 */
interface ConversationStarter {
    /**
     * Resolve or create a 1:1 conversation for [destination] (a phone number).
     * @return the conversation/room id to open, or null if it couldn't be
     *         created (e.g. invalid number, phone offline).
     */
    suspend fun startConversation(destination: String): String?
}

/** An address-book entry the new-message picker can offer. */
data class ContactEntry(
    val name: String,
    val number: String,
    val avatarColor: String = "#7E57C2",
)

/**
 * Optional capability: expose the user's contacts so the new-message screen
 * can offer a searchable picker instead of raw number entry only.
 */
interface ContactsSource {
    /** The address book, best-effort (may be empty if unavailable). */
    suspend fun listContacts(): List<ContactEntry>
}
