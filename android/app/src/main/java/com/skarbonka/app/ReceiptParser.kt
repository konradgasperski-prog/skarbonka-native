package com.skarbonka.app

import com.google.mlkit.vision.text.Text
import java.text.Normalizer

data class ReceiptItem(val name: String, val price: Double, val discount: Double = 0.0)

data class ReceiptResult(
    val store: String,
    val total: Double?,
    val date: String?,
    val items: List<ReceiptItem>,
    val rawText: String
)

/**
 * Szybki, automatyczny odczyt paragonu (bez AI): sklada tekst ze zdjecia w wiersze
 * (nazwa po lewej + cena po prawej) i wyciaga sklep, sume, date i produkty z rabatami.
 * Dokladniejszy odczyt robi potem AI w apce - ten wynik to punkt startowy i zapas,
 * gdy AI nie jest dostepne. Pelny tekst (rawText) idzie do AI.
 */
object ReceiptParser {

    private val TOTAL_KEYS = listOf(
        "suma", "razem", "do zaplaty", "total", "is viso", "viso", "moketi", "moketina",
        "amount due", "итого", "к оплате"
    )
    private val EXCLUDE_STEMS = listOf("grynais", "kortele", "mokejim", "graza", "gotowk", "reszta", "apvalinim")
    private val EXCLUDE_WORDS = listOf("pvm", "vat", "ptu", "change", "karta", "card", "cash", "kvitas", "kvito", "cekis", "nr", "kasa", "kasininkas")
    private val DISCOUNT_KEYS = listOf("nuolaida", "rabat", "discount", "upust", "akcija", "promocja")

    private val AMOUNT = Regex("""(?<![\d.,])(-?\d{1,5})\s?[.,]\s?(\d{2})(?![.,]?\d)""")
    private val DATE_YMD = Regex("""(20\d{2})[-./](\d{1,2})[-./](\d{1,2})""")
    private val DATE_DMY = Regex("""(\d{1,2})[-./](\d{1,2})[-./](20\d{2})""")
    private val QTY = Regex("""\d+(?:[.,]\d+)?\s*(?:kg|g|l|vnt|szt|pcs)?\s*[x×*]\s*\d+[.,]\d{2}(?:\s*(?:eur|€)?\s*/?\s*(?:kg|vnt|szt|l)?)?""", RegexOption.IGNORE_CASE)

    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{Mn}+"), "").replace('ł', 'l')
    }

    private fun hasWord(text: String, key: String): Boolean =
        Regex("(^|[^\\p{L}])" + Regex.escape(key) + "([^\\p{L}]|$)").containsMatchIn(text)

    private fun isExcluded(n: String): Boolean =
        EXCLUDE_STEMS.any { n.contains(it) } || EXCLUDE_WORDS.any { hasWord(n, it) }

    private fun lastAmountMatch(s: String): MatchResult? = AMOUNT.findAll(s).lastOrNull()

    private fun toPrice(m: MatchResult): Double? =
        (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()?.let { Math.abs(it) }

    private fun letters(s: String) = s.count { it.isLetter() }

    private fun cleanName(s: String): String {
        var n = s
        n = QTY.replace(n, " ")
        n = n.replace(Regex("""\b\d{6,}\b"""), " ")
        n = n.replace(Regex("""\s+[A-D]\s*\*?$"""), "")
        n = n.replace(Regex("""[*#]+"""), " ")
        n = n.replace(Regex("""\s{2,}"""), " ").trim().trim('-', ':', '.', ',')
        return n.take(60).trim()
    }

    /** Sklada linie w wiersze wedlug polozenia w pionie, w kazdym wierszu od lewej do prawej. */
    private fun buildRows(text: Text): List<String> {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.centerY() }
        val rows = mutableListOf<MutableList<Text.Line>>()
        for (ln in lines) {
            val b = ln.boundingBox!!
            val row = rows.lastOrNull()
            if (row != null) {
                val ref = row.first().boundingBox!!
                val tol = maxOf(minOf(ref.height(), b.height()), 8) * 0.55
                if (Math.abs(ref.centerY() - b.centerY()) < tol) { row.add(ln); continue }
            }
            rows.add(mutableListOf(ln))
        }
        return rows.map { r -> r.sortedBy { it.boundingBox!!.left }.joinToString("   ") { joinWords(it) } }
    }

    // Google ML Kit sometimes splits ONE word into several "elements" (e.g. "Duona" -> "Du" + "ona") and
    // joins them with a space of its own, because letter spacing on a thermal-printer receipt can be uneven.
    // We ignore that guess and decide it ourselves from the actual pixel gap between the pieces: a small gap
    // means it is really the same word (glue it back together, no space); a wider gap is a genuine word break.
    private fun joinWords(line: Text.Line): String {
        val els = line.elements.filter { it.boundingBox != null }.sortedBy { it.boundingBox!!.left }
        if (els.isEmpty()) return line.text.trim()
        val sb = StringBuilder(els.first().text)
        for (i in 1 until els.size) {
            val prev = els[i - 1].boundingBox!!
            val cur = els[i].boundingBox!!
            val gap = cur.left - prev.right
            val charW = maxOf(1f, prev.height() * 0.55f)   // rough width of one character at this line's size
            if (gap < charW * 0.9f) sb.append(els[i].text) else { sb.append(' '); sb.append(els[i].text) }
        }
        return sb.toString().trim()
    }

    fun parse(text: Text): ReceiptResult {
        val rows = buildRows(text)
        val raw = rows.joinToString("\n")

        var total: Double? = null
        var totalIdx = -1
        rows.forEachIndexed { i, r ->
            val n = norm(r)
            if (TOTAL_KEYS.none { hasWord(n, it) }) return@forEachIndexed
            if (isExcluded(n) || DISCOUNT_KEYS.any { n.contains(it) }) return@forEachIndexed
            val amt = lastAmountMatch(r)?.let { toPrice(it) } ?: return@forEachIndexed
            if (amt > 0 && (total == null || amt > total!!)) { total = amt; totalIdx = i }
        }

        var store = ""
        for (r in rows.take(6)) {
            if (letters(r) >= 3 && r.count { it.isDigit() } <= letters(r)) {
                store = r.replace(Regex("^\\s*(UAB|AB|MB|Sp\\.?\\s*z\\s*o\\.?\\s*o\\.?)\\s+", RegexOption.IGNORE_CASE), "")
                    .replace("\"", "").replace("„", "").replace("“", "").replace("”", "")
                    .replace(Regex("\\s{2,}"), " ").trim()
                if (store.isNotEmpty()) break
            }
        }

        var date: String? = null
        val ymd = DATE_YMD.find(raw)
        if (ymd != null) date = fmtDate(ymd.groupValues[1], ymd.groupValues[2], ymd.groupValues[3])
        if (date == null) {
            val dmy = DATE_DMY.find(raw)
            if (dmy != null) date = fmtDate(dmy.groupValues[3], dmy.groupValues[2], dmy.groupValues[1])
        }

        val items = mutableListOf<ReceiptItem>()
        var pendingName: String? = null
        val end = if (totalIdx >= 0) totalIdx else rows.size
        for (i in 0 until end) {
            val r = rows[i]
            val n = norm(r)
            val m = lastAmountMatch(r)
            val price = m?.let { toPrice(it) }

            if (DISCOUNT_KEYS.any { n.contains(it) }) {
                // Rabat zapisujemy przy poprzednim produkcie (cena zostaje, rabat osobno)
                if (price != null && items.isNotEmpty()) {
                    val last = items.removeAt(items.size - 1)
                    items.add(last.copy(discount = Math.round((last.discount + price) * 100) / 100.0))
                }
                pendingName = null
                continue
            }
            if (isExcluded(n) || TOTAL_KEYS.any { hasWord(n, it) }) { pendingName = null; continue }

            if (m != null && price != null && price > 0 && price < 10000) {
                val name = cleanName(r.substring(0, m.range.first))
                when {
                    letters(name) >= 2 -> { items.add(ReceiptItem(name, price)); pendingName = null }
                    pendingName != null -> { items.add(ReceiptItem(pendingName, price)); pendingName = null }
                }
            } else if (letters(r) >= 3) {
                pendingName = cleanName(r)
            }
            if (items.size >= 150) break
        }

        return ReceiptResult(store, total, date, items, raw)
    }

    private fun fmtDate(y: String, m: String, d: String): String? {
        val mi = m.toIntOrNull() ?: return null
        val di = d.toIntOrNull() ?: return null
        if (mi !in 1..12 || di !in 1..31) return null
        return "%s-%02d-%02d".format(y, mi, di)
    }
}
