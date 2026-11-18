package com.offline.dpadmessenger.backend.signal
import android.content.Context
class SignalProtocolPrefs(context: Context) {
    private val d = disks.getOrPut(context) { Disk() }
    class Disk { val id = HashMap<String, ByteArray>(); val pre = HashMap<Int, ByteArray>(); val signed = HashMap<Int, ByteArray>(); val kyber = HashMap<Int, ByteArray>(); val sess = HashMap<String, ByteArray>(); val sk = HashMap<String, ByteArray>() }
    companion object { val disks = HashMap<Context, Disk>() }
    fun putIdentity(k: String, b: ByteArray) { d.id[k] = b }
    fun getIdentity(k: String) = d.id[k]
    fun hasIdentity(k: String) = k in d.id
    fun putPreKey(i: Int, b: ByteArray) { d.pre[i] = b }
    fun getPreKey(i: Int) = d.pre[i]
    fun hasPreKey(i: Int) = i in d.pre
    fun removePreKey(i: Int) { d.pre.remove(i) }
    fun allPreKeyIds() = d.pre.keys.toList()
    fun putSignedPreKey(i: Int, b: ByteArray) { d.signed[i] = b }
    fun getSignedPreKey(i: Int) = d.signed[i]
    fun hasSignedPreKey(i: Int) = i in d.signed
    fun removeSignedPreKey(i: Int) { d.signed.remove(i) }
    fun allSignedPreKeys(): Map<Int, ByteArray> = d.signed.toMap()
    fun putKyberPreKey(i: Int, b: ByteArray) { d.kyber[i] = b }
    fun getKyberPreKey(i: Int) = d.kyber[i]
    fun hasKyberPreKey(i: Int) = i in d.kyber
    fun allKyberPreKeys(): Map<Int, ByteArray> = d.kyber.toMap()
    fun putSession(k: String, b: ByteArray) { d.sess[k] = b }
    fun getSession(k: String) = d.sess[k]
    fun hasSession(k: String) = k in d.sess
    fun removeSession(k: String) { d.sess.remove(k) }
    fun removeAllSessionsFor(s: String) { d.sess.keys.filter { it.startsWith("$s.") }.forEach { d.sess.remove(it) } }
    fun deviceIdsFor(s: String) = d.sess.keys.filter { it.startsWith("$s.") }.mapNotNull { it.substringAfter(".").toIntOrNull() }
    fun putSenderKey(k: String, b: ByteArray) { d.sk[k] = b }
    fun getSenderKey(k: String) = d.sk[k]
}
