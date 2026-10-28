package app.anylap.i18n

import app.anylap.update.UpdatePlatform

/**
 * App texts in the device language.
 *
 * The English text is the key: `tr("Start race")`, with `{0}`, `{1}` … for values:
 * `tr("Lap {0} · {1}", n, time)`. Plurals: `trn(n, "{0} lap", "{0} laps")` – `{0}` is the count,
 * extra values follow as `{1}` …
 *
 * Translations live in `composeApp/i18n/<lang>.json` (English key → text) and are compiled into
 * [I18nData] by `scripts/i18n/gen.py`; a missing key falls back to English. Plural values are the
 * language's forms joined with `|`, in the order of [Plural.forms].
 */
object I18n {
    /** Language in use, e.g. "sv": the device language, or the per-app language chosen in Android settings. */
    val lang: String get() = pick(UpdatePlatform.language)

    // Last language and its table; replaced as a whole, so a race between threads only means parsing twice.
    private var cache: Pair<String, Map<String, String>> = "" to emptyMap()
    private val table: Map<String, String>
        get() {
            val l = lang
            val c = cache
            if (c.first == l) return c.second
            return I18nData.table(l).also { cache = l to it }
        }

    fun pick(device: String): String {
        val l = device.lowercase().replace('_', '-')
        return I18nData.LANGS.firstOrNull { l == it } ?: I18nData.LANGS.firstOrNull { l.substringBefore('-') == it } ?: "en"
    }

    internal fun lookup(key: String): String? = table[key]

    private val COMMA = setOf("sv", "de", "fr", "es", "pt", "it", "pl", "nl", "ru", "tr", "id", "vi")

    /** "17.4" -> "17,4" where the language writes a decimal comma (for speech; on-screen lap times keep the dot). */
    fun decimal(s: String): String = if (lang.substringBefore('-') in COMMA) s.replace('.', ',') else s
}

fun tr(en: String, vararg args: Any?): String = fill(I18n.lookup(en) ?: en, args)

fun trn(n: Int, one: String, other: String, vararg args: Any?): String {
    val t = I18n.lookup("$one|$other")
    val forms = t?.split('|') ?: listOf(one, other)
    val i = Plural.index(if (t == null) "en" else I18n.lang, n)
    return fill(forms[i.coerceAtMost(forms.size - 1)], arrayOf(n, *args))
}

private fun fill(s: String, args: Array<out Any?>): String {
    if (args.isEmpty() || '{' !in s) return s
    val sb = StringBuilder(s.length + 16)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '{') {
            val end = s.indexOf('}', i)
            val k = if (end > i) s.substring(i + 1, end).toIntOrNull() else null
            if (k != null && k in args.indices) {
                sb.append(args[k]?.toString() ?: ""); i = end + 1; continue
            }
        }
        sb.append(c); i++
    }
    return sb.toString()
}

/** Plural form index per language (CLDR cardinal rules for integers). */
object Plural {
    fun index(lang: String, n: Int): Int = when (lang.substringBefore('-')) {
        // one / other
        "en", "sv", "de", "nl", "it", "es", "pt", "hi", "tr" -> if (n == 1) 0 else 1
        // French also treats 0 as singular
        "fr" -> if (n == 0 || n == 1) 0 else 1
        // one form
        "zh", "ja", "ko", "id", "th", "vi" -> 0
        // one / few / many
        "ru" -> when {
            n % 10 == 1 && n % 100 != 11 -> 0
            n % 10 in 2..4 && n % 100 !in 12..14 -> 1
            else -> 2
        }
        "pl" -> when {
            n == 1 -> 0
            n % 10 in 2..4 && n % 100 !in 12..14 -> 1
            else -> 2
        }
        // zero / one / two / few / many / other
        "ar" -> when {
            n == 0 -> 0
            n == 1 -> 1
            n == 2 -> 2
            n % 100 in 3..10 -> 3
            n % 100 in 11..99 -> 4
            else -> 5
        }
        else -> if (n == 1) 0 else 1
    }
}
