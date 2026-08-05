package com.offline.dpadmessenger.backend.smarttxt

import com.offline.dpadmessenger.data.DefaultReactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the OpenBubbles history import. The reader itself
 * ([ObjectBoxStore]) is verified out-of-band against a real device store — it needs
 * a multi-megabyte binary fixture full of somebody's actual messages, which is not
 * something to commit. What IS worth pinning here is every rule that has to stay in
 * lockstep with code living somewhere else, because those are what break silently.
 */
class ObHistoryImporterTest {

    @Test
    fun `classic tapbacks map to the emoji the UI keys reactions by`() {
        assertEquals("❤️", ObHistoryImporter.tapbackEmoji("love", ""))
        assertEquals("👍", ObHistoryImporter.tapbackEmoji("like", ""))
        assertEquals("👎", ObHistoryImporter.tapbackEmoji("dislike", ""))
        assertEquals("😂", ObHistoryImporter.tapbackEmoji("laugh", ""))
        assertEquals("‼️", ObHistoryImporter.tapbackEmoji("emphasize", ""))
        assertEquals("❓", ObHistoryImporter.tapbackEmoji("question", ""))
    }

    /**
     * The load-bearing one. An imported tapback and a live one must land on the SAME
     * map key or the same reaction shows twice on the same bubble — so these strings
     * have to be byte-identical to the picker's, variation selectors and all.
     */
    @Test
    fun `every emoji the import can produce is one the picker uses`() {
        val produced = listOf("love", "like", "dislike", "laugh", "emphasize", "question")
            .mapNotNull { ObHistoryImporter.tapbackEmoji(it, "") }
        assertEquals(6, produced.size)
        produced.forEach { assertTrue("$it is not in DefaultReactions", it in DefaultReactions.emojis) }
    }

    @Test
    fun `removed tapbacks and unknown types are dropped`() {
        // OpenBubbles records a REMOVED tapback as the type with a leading "-".
        assertNull(ObHistoryImporter.tapbackEmoji("-love", ""))
        assertNull(ObHistoryImporter.tapbackEmoji("-like", ""))
        assertNull(ObHistoryImporter.tapbackEmoji("", ""))
        assertNull(ObHistoryImporter.tapbackEmoji("sticker", ""))
        // A non-classic tapback carries its emoji in its own column.
        assertEquals("🦆", ObHistoryImporter.tapbackEmoji("emoji", "🦆"))
        assertNull(ObHistoryImporter.tapbackEmoji("emoji", "   "))
    }

    @Test
    fun `associated message guids lose their message-part prefix`() {
        val guid = "4E2C21C9-77A4-4573-BCF5-C5C76E9A2A85"
        assertEquals(guid, ObHistoryImporter.stripPartPrefix(guid))
        assertEquals(guid, ObHistoryImporter.stripPartPrefix("p:0/$guid"))
        assertEquals(guid, ObHistoryImporter.stripPartPrefix("p:12/$guid"))
        assertEquals(guid, ObHistoryImporter.stripPartPrefix("bp:$guid"))
        // A bare guid containing no prefix is never truncated at a stray slash.
        assertEquals("a/b", ObHistoryImporter.stripPartPrefix("a/b"))
    }

    @Test
    fun `body loses the inline-attachment placeholder`() {
        // U+FFFC is what iMessage puts where an attachment sits; it renders as tofu.
        assertEquals("look at this", ObHistoryImporter.sanitizeBody("￼ look at this "))
        assertEquals("", ObHistoryImporter.sanitizeBody("￼"))
        assertEquals("", ObHistoryImporter.sanitizeBody("   "))
        assertEquals("hi", ObHistoryImporter.sanitizeBody("hi"))
    }

    /** Must agree with `SmartTxtMessageRepository.colorFor`: same palette, same hash,
     *  or an imported contact is tinted differently from one the live path created. */
    @Test
    fun `avatar colours come from the repository palette and are stable`() {
        val palette = listOf("#0A84FF", "#FF375F", "#30D158", "#5E5CE6", "#FF9F0A", "#BF5AF2", "#64D2FF")
        listOf("+18045551234", "me@icloud.com", "iMessage;-;+15551112222").forEach { id ->
            val c = ObHistoryImporter.avatarColor(id)
            assertTrue("$c not in palette", c in palette)
            assertEquals(palette[(id.hashCode() and 0x7fffffff) % palette.size], c)
            assertEquals(c, ObHistoryImporter.avatarColor(id))
        }
    }
}
