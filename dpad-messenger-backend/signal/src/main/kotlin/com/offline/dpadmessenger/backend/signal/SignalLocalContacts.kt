package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads the device's own address book via ContactsContract.
 *
 * Signal addresses recipients by ACI, and this client doesn't run CDSI contact
 * discovery, so the repository only "knows" people it has synced or already
 * exchanged messages with — which leaves the new-message picker empty on a
 * fresh link. Reading ContactsContract here gives the picker the phone's full
 * address book to display and search. (Whether a tapped contact can actually
 * start a chat still depends on the number resolving to a known ACI — see
 * SignalMessageRepository.resolveServiceIdForNumber.)
 *
 * Mirrors gmessages' `LocalContacts`.
 */
internal object SignalLocalContacts {

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

    private const val TAG = "SignalRepo"
}
