package com.offline.dpadmessenger.backend.imessage

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the device's address book via ContactsContract, returning BOTH phone
 * numbers and emails as iMessage handles. Mirror of gmessages' `LocalContacts`,
 * extended for email handles (iMessage addresses can be either).
 *
 * Used to resolve incoming handles (tel:/mailto:) to display names and to power
 * the new-message contact picker. Requires READ_CONTACTS; returns empty (not an
 * error) when not granted.
 */
internal object IMessageContacts {

    data class Entry(val name: String, val handle: String)

    fun read(context: Context): List<Entry> {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "READ_CONTACTS not granted — skipping local contacts")
            return emptyList()
        }
        val out = ArrayList<Entry>()
        // Phone numbers → tel: handles
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                    val number = if (numIdx >= 0) c.getString(numIdx).orEmpty() else ""
                    if (number.isNotBlank()) {
                        out.add(Entry(name.ifBlank { number }, "tel:" + number.filter { it.isDigit() || it == '+' }))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "phone contacts read failed", it) }
        // Emails → mailto: handles
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null, null, null,
            )?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val addrIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (c.moveToNext()) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                    val addr = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                    if (addr.isNotBlank()) out.add(Entry(name.ifBlank { addr }, "mailto:$addr"))
                }
            }
        }.onFailure { Log.w(TAG, "email contacts read failed", it) }
        Log.d(TAG, "local contacts: ${out.size} handle rows")
        return out
    }

    private const val TAG = "IMsgContacts"
}
