package android.util
object Log {
    val lines = mutableListOf<String>()
    private fun r(l: String, t: String, m: String, e: Throwable? = null): Int { val s = "$l/$t: $m" + (e?.let { " [${it.javaClass.simpleName}]" } ?: ""); lines += s; println("    log " + s); return 0 }
    @JvmStatic fun i(t: String, m: String) = r("I", t, m)
    @JvmStatic fun d(t: String, m: String) = r("D", t, m)
    @JvmStatic fun w(t: String, m: String) = r("W", t, m)
    @JvmStatic fun w(t: String, m: String, e: Throwable?) = r("W", t, m, e)
    @JvmStatic fun e(t: String, m: String, e: Throwable?) = r("E", t, m, e)
}
