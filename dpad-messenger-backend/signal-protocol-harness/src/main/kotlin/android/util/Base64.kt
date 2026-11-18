package android.util
object Base64 {
    const val NO_WRAP = 2
    const val NO_PADDING = 1
    @JvmStatic fun encodeToString(b: ByteArray, f: Int): String = java.util.Base64.getEncoder().encodeToString(b)
    @JvmStatic fun decode(s: String, f: Int): ByteArray = java.util.Base64.getDecoder().decode(s)
}
