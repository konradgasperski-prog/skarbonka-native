package com.skarbonka.app

import java.text.Normalizer

/**
 * Pomocnicze funkcje tekstowe. (Odczyt tekstu ze zdjecia paragonu zostal usuniety -
 * skaner czyta teraz tylko kod QR, a dane bierze ze strony VMI.)
 */
object ReceiptParser {
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        return n.replace(Regex("\\p{Mn}+"), "").replace('ł', 'l')
    }
}
