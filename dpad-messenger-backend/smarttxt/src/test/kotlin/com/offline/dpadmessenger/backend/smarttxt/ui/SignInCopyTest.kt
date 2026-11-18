package com.offline.dpadmessenger.backend.smarttxt.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The sign-in form's words, as the owner gave them on 18 Nov 2026: "change "Apple
 * ID" to "email"", and under "sign in to smart txt": "check the dumb down app on
 * ur smartphone". The paired iPhone's prompt titles itself with the box labels
 * (its DumbphoneTextFieldPromptCopy), so a change here is a change there too.
 */
class SignInCopyTest {

    @Test fun `the owner's words`() {
        assertEquals("sign in to smart txt", SignInCopy.TITLE)
        assertEquals("check the dumb down app on ur smartphone", SignInCopy.BODY)
        assertEquals("phone number or email", SignInCopy.APPLE_ID_LABEL)
        assertEquals("password", SignInCopy.PASSWORD_LABEL)
    }

    @Test fun `setup's heading is the same words, broken where the frames' 228 px box needs it`() {
        assertEquals(SignInCopy.TITLE, SignInCopy.SETUP_TITLE.replace("\n", " "))
        assertEquals(listOf("sign in to", "smart txt"), SignInCopy.SETUP_TITLE.lines())
    }

    /** Read from the source: the form must not spell its words out again beside SignInCopy. */
    @Test fun `the form takes its words from SignInCopy`() {
        val source = listOf(
            File("src/main/kotlin/com/offline/dpadmessenger/backend/smarttxt/ui/SmartTxtSetupScreen.kt"),
            File("smarttxt/src/main/kotlin/com/offline/dpadmessenger/backend/smarttxt/ui/SmartTxtSetupScreen.kt"),
        ).first { it.exists() }.readText()
        val code = source.lines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
        assertFalse("the old \"Apple ID\" label is back on the form", "label = \"phone number or Apple ID\"" in code)
        assertFalse("the old description is back on the form", "to type ur account details into the text boxes below" in code)
    }
}
