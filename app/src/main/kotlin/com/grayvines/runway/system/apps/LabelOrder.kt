package com.grayvines.runway.system.apps

import java.text.Collator
import java.text.Normalizer

/**
 * How labels are ordered wherever they are listed by name: the drawer's grid, its index, the
 * folders atop it. As a file browser lists files: whatever does not start with a letter (digits,
 * symbols) comes first, under one '#'; then the letters, in the order the device's locale spells
 * them, so "Éclair" sits among the Es rather than after Z, and case does not count.
 */
object LabelOrder {
    const val NON_LETTER = '#'

    /** The combining marks NFD splits off a letter: the accents. */
    private val MARKS = Regex("\\p{M}+")

    /** The letter a label files under: its first letter, upper case and without accents, or '#'. */
    fun initial(label: String): Char {
        val first = label.trimStart().firstOrNull()?.takeIf { it.isLetter() } ?: return NON_LETTER
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first()
        return base.uppercaseChar()
    }

    /**
     * [text] as a search compares it: lower case, accents off, so "eclair" finds "Éclair" just as
     * the order files it among the Es.
     */
    fun folded(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    /** A comparator for the current locale; make one per sort, since the locale can change. */
    fun comparator(): Comparator<String> {
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
        return compareBy<String> { initial(it) != NON_LETTER }.thenComparator(collator::compare)
    }
}
