package com.skarbonka.app

import com.google.mlkit.vision.text.Text
import java.text.Normalizer

data class ReceiptItem(val name: String, val price: Double)

data class ReceiptResult(
    val store: String,
    val total: Double?,
    val date: String?,
    val items: List<ReceiptItem>,
    val rawText: String
)

/**
 * Zamienia odczytany tekst paragonu na: sklep, sume, date, liste produktow z cenami i pelny tekst.
 * Linie z ML Kit sa najpierw skladane w wiersze (nazwa produktu po lewej + cena po prawej),
 * bo na paragonie to czesto osobne kolumny.
 * Obsluguje paragony litewskie, polskie i angielskie.
 */
object ReceiptParser {

    private val TOTAL_KEYS = listOf(
        "suma", "razem", "do zaplaty", "total", "is viso", "viso", "moketi", "moketina",
        "amount due", "итого", "к оплате"
    )
    // Wiersze, ktore nie sa produktami ani suma (VAT, reszta, platnosc)
    private val EXCLUDE_STEMS = listOf("grynais", "kortele", "mokejim", "graza", "gotowk", "reszta")   // czesc slowa
    private val EXCLUDE_WORDS = listOf("pvm", "vat", "ptu", "change", "karta", "card", "cash", "kvitas", "cekis", "nr")  // cale slowo

    private fun isExcluded(n: String): Boolean =
        EXCLUDE_STEMS.any { n.contains(it) } || EXCLUDE_WORDS.any { hasWord(n, it) }
    private val DISCOUNT_KEYS = listOf("nuolaida", "rabat", "discount", "upust")

    private val AMOUNT = Regex("""(?<![\d.,])(\d{1,5})\s?[.,]\s?(\d{2})(?![.,]?\d)""")
    private val DATE_YMD = Regex("""(20\d{2})[-./](\d{1,2})[-./](\d{1,2})""")
    private val DATE_DMY = Regex("""(\d{1,2})[-./](\d{1,2})[-./](20\d{2})""")
    private val QTY = Regex("""\d+(?:[.,]\d+)?\s*(?:kg|g|l|vnt|szt|pcs)?\s*[x×*]\s*\d+[.,]\d{2}(?:\s*(?:eur|€)?\s*/?\s*(?:kg|vnt|szt|l)?)?""", RegexOption.IGNORE_CASE)

    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{Mn}+"), "").replace('ł', 'l')
    }

    private fun hasWord(text: String, key: String): Boolean =
        Regex("(^|[^\\p{L}])" + Regex.escape(key) + "([^\\p{L}]|$)").containsMatchIn(text)

    private fun lastAmountMatch(s: String): MatchResult? = AMOUNT.findAll(s).lastOrNull()

    private fun toPrice(m: MatchResult): Double? =
        (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()

    private fun letters(s: String) = s.count { it.isLetter() }

    /** Czysci nazwe produktu: bez ceny, ilosci "2 x 0,75", kodow kreskowych i klasy podatku (A/B/C). */
    private fun cleanName(s: String): String {
        var n = s
        n = QTY.replace(n, " ")
        n = n.replace(Regex("""\b\d{6,}\b"""), " ")                 // kody kreskowe / numery
        n = n.replace(Regex("""\s+[A-D]\s*\*?$"""), "")            // klasa PVM na koncu
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
        return rows.map { r -> r.sortedBy { it.boundingBox!!.left }.joinToString("   ") { it.text.trim() } }
    }

    fun parse(text: Text): ReceiptResult {
        val rows = buildRows(text)
        val raw = rows.joinToString("\n")

        // --- Suma ---
        var total: Double? = null
        var totalIdx = -1
        rows.forEachIndexed { i, r ->
            val n = norm(r)
            if (TOTAL_KEYS.none { hasWord(n, it) }) return@forEachIndexed
            if (isExcluded(n) || DISCOUNT_KEYS.any { n.contains(it) }) return@forEachIndexed
            val amt = lastAmountMatch(r)?.let { toPrice(it) } ?: return@forEachIndexed
            if (amt > 0 && (total == null || amt > total!!)) { total = amt; totalIdx = i }
        }

        // --- Sklep: pierwszy sensowny wiersz od gory ---
        var store = ""
        for (r in rows.take(5)) {
            if (letters(r) >= 3 && r.count { it.isDigit() } <= letters(r)) {
                store = r.replace(Regex("^\\s*(UAB|AB|Sp\\.?\\s*z\\s*o\\.?\\s*o\\.?)\\s+", RegexOption.IGNORE_CASE), "")
                    .replace("\"", "").replace("„", "").replace("“", "").replace("”", "")
                    .replace(Regex("\\s{2,}"), " ").trim()
                if (store.isNotEmpty()) break
            }
        }

        // --- Data ---
        var date: String? = null
        val ymd = DATE_YMD.find(raw)
        if (ymd != null) date = fmtDate(ymd.groupValues[1], ymd.groupValues[2], ymd.groupValues[3])
        if (date == null) {
            val dmy = DATE_DMY.find(raw)
            if (dmy != null) date = fmtDate(dmy.groupValues[3], dmy.groupValues[2], dmy.groupValues[1])
        }

        // --- Produkty: wiersze nad suma z cena na koncu ---
        val items = mutableListOf<ReceiptItem>()
        var pendingName: String? = null
        val end = if (totalIdx >= 0) totalIdx else rows.size
        for (i in 0 until end) {
            val r = rows[i]
            val n = norm(r)
            val m = lastAmountMatch(r)
            val price = m?.let { toPrice(it) }

            if (DISCOUNT_KEYS.any { n.contains(it) }) {
                // Rabat do poprzedniego produktu
                if (price != null && items.isNotEmpty()) {
                    val last = items.removeAt(items.size - 1)
                    items.add(last.copy(price = Math.max(0.0, Math.round((last.price - price) * 100) / 100.0)))
                }
                pendingName = null
                continue
            }
            if (isExcluded(n) || TOTAL_KEYS.any { hasWord(n, it) }) { pendingName = null; continue }

            if (m != null && price != null && price > 0 && price < 10000) {
                val name = cleanName(r.substring(0, m.range.first))
                when {
                    letters(name) >= 2 -> { items.add(ReceiptItem(name, price)); pendingName = null }
                    pendingName != null -> { items.add(ReceiptItem(pendingName!!, price)); pendingName = null }
                }
            } else if (letters(r) >= 3) {
                // Nazwa w jednym wierszu, cena (np. "0,845 kg x 1,49  1,26") w nastepnym
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
