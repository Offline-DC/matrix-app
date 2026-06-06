package com.offline.dpadmessenger.backend.gmessages

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the device's own address book via ContactsContract.
 *
 * Google Messages' LIST_CONTACTS only surfaces the contacts its web client
 * knows about (often just recently-/frequently-texted people), so it isn't a
 * reliable way to "search all my contacts". The launcher already holds
 * READ_CONTACTS, and the flip phone syncs the user's full Google address book
 * locally — so reading ContactsContract here gives the complete list to merge
 * into the new-message picker.
 */
internal object LocalContacts {

    data class Entry(val name: String, val number: String)

    fun read(context: Context): List<Entry> {
        val ctx = context.applicationContext
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "READ_CONTACTS not granted — skipping local contacts")
            return emptyList()
        }
        val out = ArrayList<Entry>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                    if (number.isNotBlank()) out.add(Entry(name.ifBlank { number }, number))
                }
            }
        }.onFailure { Log.w(TAG, "local contacts read failed", it) }
        Log.d(TAG, "local contacts: read ${out.size} phone rows")
        return out
    }

    private const val TAG = "GMRepo"
}
