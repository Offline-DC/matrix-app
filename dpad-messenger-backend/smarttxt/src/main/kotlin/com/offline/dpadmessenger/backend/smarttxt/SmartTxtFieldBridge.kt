package com.offline.dpadmessenger.backend.smarttxt

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two boxes on Smart Txt's sign-in form that the paired iPhone can fill in.
 * [wire] is the name both phones use for the box, so change it only together
 * with the iOS side.
 */
enum class SmartTxtTextField(val wire: String) {
    APPLE_ID("apple_id"),
    PASSWORD("password");

    companion object {
        fun fromWire(name: String): SmartTxtTextField? = entries.firstOrNull { it.wire == name }
    }
}

/**
 * A value typed into the iPhone's prompt for [field].
 *
 * Not a data class: a data class's toString would print the password into any
 * log line it ends up in, and the Smart Txt log is emailed to the team (the
 * same reason as [SmartTxtLogin]).
 */
class SmartTxtFieldValue(val field: SmartTxtTextField, val value: String) {
    override fun toString(): String = "SmartTxtFieldValue(${field.wire}, <redacted>)"
}

/**
 * Lets the paired iPhone type into Smart Txt's sign-in boxes (owner, 17 Nov):
 * while the person is on a box here, the iPhone shows a prompt with that box's
 * label — "phone number or email" (it was "phone number or Apple ID" until 18
 * Nov) or "password", ui/SignInCopy — and a text field, and what's submitted
 * there lands in the box here.
 *
 * Why: the iMessage step moved back onto the flip the same day, so the
 * sign-in is typed here, on a keypad. An Apple ID and an Apple-strength
 * password are slow and error-prone on T9; the iPhone's keyboard is not.
 *
 * Two directions, both through the host (the launcher owns the relay socket):
 *  - flip → iPhone, [focused]: which box has focus, sent on every change by
 *    [setFocused] through [SmartTxtCompanionSender.sendTextFieldFocus], and
 *    sent again by the host whenever the iPhone joins the relay, so an iPhone
 *    opened after the box was focused still gets its prompt;
 *  - iPhone → flip, [values]: the host hands each value in through
 *    [deliverValue], and the sign-in form, collecting while it is up, SETS the
 *    box to it. Set, not pasted: Type Sync's ordinary text is a blind paste at
 *    the cursor, which appends — a corrected Apple ID sent again would have
 *    landed on the end of the first one.
 *
 * Same shape as [SmartTxtLoginBridge] and [SmartTxtFsaBridge], the other
 * things that cross this boundary.
 */
object SmartTxtFieldBridge {
    private const val TAG = "SmartTxtField"

    private val _focused = MutableStateFlow<SmartTxtTextField?>(null)

    /** The sign-in box that has focus on the flip, or null for none. */
    val focused: StateFlow<SmartTxtTextField?> = _focused.asStateFlow()

    /**
     * Called by the sign-in form as focus settles. Tells the iPhone on a
     * change only: the form calls it on every focus event, and an unchanged
     * box would only make the iPhone redraw its prompt.
     */
    fun setFocused(field: SmartTxtTextField?) {
        if (_focused.value == field) return
        _focused.value = field
        Log.i(TAG, "focused box → ${field?.wire ?: "none"}")
        SmartTxtConfig.companionSender?.sendTextFieldFocus(field)
    }

    private val _values = MutableSharedFlow<SmartTxtFieldValue>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Values from the iPhone's prompt. Collected by the sign-in form while it is up. */
    val values: SharedFlow<SmartTxtFieldValue> = _values.asSharedFlow()

    /**
     * Called by the host when the iPhone sends a value. Safe from any thread.
     * Never logs the value, nor its length (that would say how long the
     * password is). Returns whether a sign-in form was there to take it:
     * with none up (Smart Txt closed, or on another page), it is dropped — the
     * iPhone only shows its prompt while a box here has focus, so a value
     * arriving with no form is a late one, and keeping it to fill a later form
     * would type an old value into it.
     */
    fun deliverValue(field: String, value: String): Boolean {
        val box = SmartTxtTextField.fromWire(field)
        if (box == null) {
            Log.w(TAG, "value for an unknown box '$field' — dropped")
            return false
        }
        if (_values.subscriptionCount.value == 0) {
            Log.i(TAG, "value for ${box.wire} arrived with no sign-in form up — dropped")
            return false
        }
        Log.i(TAG, "value for ${box.wire} arrived; handing it to the sign-in form")
        return _values.tryEmit(SmartTxtFieldValue(box, value))
    }
}
