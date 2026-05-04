package android.net

/**
 * Test-source-set stub for `android.net.Uri`. The Android JAR shipped to JVM unit tests
 * has `Uri.EMPTY = null` (because the real initializer calls `Uri.parse` which throws
 * `RuntimeException("Stub!")`), so any test that constructs a [PendingStrip] hits a NPE.
 *
 * The classes the production code holds against `Uri` are only `equals`/`hashCode` /
 * `toString` — never the parsed-URI accessors — so a thin stub that supports these is enough
 * for the filter tests. Production builds resolve `android.net.Uri` from the platform, not
 * this file.
 */
class Uri private constructor(private val s: String) {
    override fun toString(): String = s
    override fun equals(other: Any?): Boolean = other is Uri && other.s == s
    override fun hashCode(): Int = s.hashCode()

    companion object {
        @JvmField
        val EMPTY: Uri = Uri("")

        @JvmStatic
        fun parse(s: String?): Uri = Uri(s ?: "")
    }
}
