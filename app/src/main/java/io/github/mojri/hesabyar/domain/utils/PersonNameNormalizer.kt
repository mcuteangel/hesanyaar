package io.github.mojri.hesabyar.domain.utils

/**
 * Normalizes person names into dedup keys (plans/011 §D4).
 *
 * ADR-001 permanent fallback ("Person-name normalization"): Room migrations
 * cannot load the native library, so backfill runs this Kotlin util inside
 * the migration. Every runtime create/rename path reuses it, so dedup
 * semantics never drift between migration and runtime.
 *
 * **Case folding contract:** [Char.lowercaseChar] is applied to every
 * retained code point, not only Latin letters. The Persian/Arabic script
 * has no case, so the fold is a no-op for the dominant script in this
 * app. Any future script with case (Cyrillic, Greek, etc.) will be
 * lowercased the same way. Callers that need to preserve case in
 * non-Latin scripts must do so before calling this function.
 */
object PersonNameNormalizer {
  // Code points: ZWSP(200B), ZWNJ(200C), ZWJ(200D), word joiner(2060), BOM(FEFF).
  private val zeroWidthCodes = setOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF)

  // Arabic yeh/kaf/teh-marbuta fold to their Persian counterparts. Keyed by
  // code point so lookups in [normalize] are safe for supplementary-plane chars.
  private val arabicToPersian = mapOf('ي'.code to 'ی', 'ك'.code to 'ک', 'ة'.code to 'ه')

  /**
   * Collapses spelling variants to one dedup key: trims, collapses internal
   * whitespace to single spaces, strips zero-width characters, folds Arabic
   * variants to Persian, lowercases the retained code points (see contract
   * note on the class). Iterates by code point so supplementary-plane
   * case variants (e.g. Deseret) fold correctly.
   */
  fun normalize(name: String): String {
    var pendingSpace = false
    return buildString(name.length) {
      var i = 0
      while (i < name.length) {
        val cp = name.codePointAt(i)
        val foldedCp = arabicToPersian[cp]?.code ?: cp
        when {
          foldedCp in zeroWidthCodes -> Unit
          Character.isWhitespace(foldedCp) -> pendingSpace = length > 0
          else -> {
            if (pendingSpace && length > 0) append(' ')
            pendingSpace = false
            appendCodePoint(Character.toLowerCase(foldedCp))
          }
        }
        i += Character.charCount(cp)
      }
    }
  }

  /**
   * Display form of a raw input: trims outer whitespace only. Zero-width
   * characters are preserved so Persian ZWNJ spelling (e.g. "می‌روم") is
   * not altered; they are stripped only for the dedup key in [normalize]
   * (plans/011 §D4 — first trimmed original is the display name).
   * A name consisting only of zero-width characters remains non-empty here
   * but normalizes to empty and is rejected by callers; see
   * [HesabyarRepository.upsertPerson] and [HesabyarRepository.renamePerson].
   */
  fun displayForm(name: String): String = name.trim()
}
