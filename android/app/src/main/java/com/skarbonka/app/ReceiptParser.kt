package com.skarbonka.app

import com.google.mlkit.vision.text.Text
import java.text.Normalizer

data class ReceiptResult(
    val store: String,
    val total: Double?,
    val date: String?,
    val items: Int,
    val rawText: String
)

/**
 * Wyciaga z odczytanego tekstu paragonu: sklep, sume, date i liczbe pozycji.
 * Obsluguje paragony litewskie, polskie i angielskie (LT: "Iš viso", "Mokėti", PL: "Suma", "Razem", EN: "Total").
 */
object ReceiptParser {

    private val TOTAL_KEYS = listOf(
        "suma", "razem", "do zaplaty", "total", "is viso", "viso", "moketi", "moketina",
        "amount due", "итого", "к оплате"
    )
    // Linie, ktore wygladaja jak suma, ale nia nie sa (VAT, reszta, rabat, platnosc)
    private val EXCLUDE_KEYS = listOf(
        "pvm", "vat", "ptu", "graza", "reszta", "change", "nuolaida", "rabat", "discount",
        "grynais", "kortele", "gotowka", "karta", "card", "cash", "be pvm"
    )
    private val AMOUNT = Regex("""(?<![\d.,])(\d{1,5})\s?[.,]\s?(\d{2})(?![.,]?\d)""")
    private val DATE_YMD = Regex("""(20\d{2})[-./](\d{1,2})[-./](\d{1,2})""")
    private val DATE_DMY = Regex("""(\d{1,2})[-./](\d{1,2})[-./](20\d{2})""")

    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{Mn}+"), "").replace('ł', 'l')
    }

    private fun hasWord(text: String, key: String): Boolean =
        Regex("(^|[^\\p{L}])" + Regex.escape(key) + "([^\\p{L}]|$)").containsMatchIn(text)

    private fun lastAmount(s: String): Double? {
        val m = AMOUNT.findAll(s).lastOrNull() ?: return null
        return (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()
    }

    fun parse(text: Text): ReceiptResult {
        val lines = text.textBlocks.flatMap { it.lines }
            .filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.top }
        val raw = lines.joinToString("\n") { it.text }

        // --- Suma: najwieksza kwota z linii ze slowem "suma/iš viso/total...", z tej samej linii albo z tego samego wiersza obok ---
        var total: Double? = null
        var totalTop = Int.MAX_VALUE
        for (ln in lines) {
            val n = norm(ln.text)
            if (TOTAL_KEYS.none { hasWord(n, it) }) continue
            if (EXCLUDE_KEYS.any { n.contains(it) }) continue
            val amt = lastAmount(ln.text) ?: amountOnSameRow(ln, lines)
            if (amt != null && amt > 0 && (total == null || amt > total)) {
                total = amt
                totalTop = ln.boundingBox!!.top
            }
        }

        // --- Sklep: pierwsza sensowna linia od gory ---
        var store = ""
        for (ln in lines.take(5)) {
            val letters = ln.text.count { it.isLetter() }
            val digits = ln.text.count { it.isDigit() }
            if (letters >= 3 && digits <= letters) {
                store = ln.text
                    .replace(Regex("^\\s*(UAB|AB|Sp\\.?\\s*z\\s*o\\.?\\s*o\\.?)\\s+", RegexOption.IGNORE_CASE), "")
                    .replace("\"", "").replace("„", "").replace("“", "").replace("”", "")
                    .trim()
                if (store.isNotEmpty()) break
            }
        }

        // --- Data ---
        var date: String? = null
        DATE_YMD.find(raw)?.let { m ->
            date = fmtDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        if (date == null) DATE_DMY.find(raw)?.let { m ->
            date = fmtDate(m.groupValues[3], m.groupValues[2], m.groupValues[1])
        }

        // --- Liczba pozycji: linie z kwota powyzej linii sumy ---
        val items = if (total != null) {
            lines.count { it.boundingBox!!.top < totalTop && AMOUNT.containsMatchIn(it.text) }
        } else 0

        return ReceiptResult(store, total, date, items, raw)
    }

    private fun amountOnSameRow(ln: Text.Line, all: List<Text.Line>): Double? {
        val b = ln.boundingBox ?: return null
        val cy = b.centerY()
        val tol = maxOf(b.height(), 12) * 0.7
        return all.filter {
            it !== ln && it.boundingBox != null &&
                Math.abs(it.boundingBox!!.centerY() - cy) < tol &&
                it.boundingBox!!.left > b.left
        }.sortedByDescending { it.boundingBox!!.left }
            .firstNotNullOfOrNull { lastAmount(it.text) }
    }

    private fun fmtDate(y: String, m: String, d: String): String? {
        val mi = m.toIntOrNull() ?: return null
        val di = d.toIntOrNull() ?: return null
        if (mi !in 1..12 || di !in 1..31) return null
        return "%s-%02d-%02d".format(y, mi, di)
    }
}
