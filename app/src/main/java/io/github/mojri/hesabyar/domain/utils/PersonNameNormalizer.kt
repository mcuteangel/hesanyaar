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

  // Arabic-script variants fold to their Persian counterparts so visually
  // identical names share one dedup key. Keyed by code point so lookups in
  // [normalize] are safe for supplementary-plane chars.
  private val arabicToPersian: Map<Int, String> =
    mapOf(
      'ي'.code to "ی", // 064A arabic yeh
      'ى'.code to "ی", // 0649 alef maksura
      'ئ'.code to "ی", // 0626 yeh with hamza above
      'ك'.code to "ک", // 0643 arabic kaf
      'ة'.code to "ه", // 0629 teh marbuta
      'أ'.code to "ا", // 0623 alef with hamza above
      'إ'.code to "ا", // 0625 alef with hamza below
      'آ'.code to "ا", // 0622 alef with madda above
      'ٱ'.code to "ا", // 0671 alef wasla
      'ؤ'.code to "و", // 0624 waw with hamza
      // Lam-alef presentation-form ligatures (isolated and final forms) all
      // collapse to the canonical lam + alef sequence.
      'ﻻ'.code to "لا", // FEFB
      'ﻼ'.code to "لا", // FEFC
      'ﻹ'.code to "لا", // FEF9 (hamza folds away)
      'ﻺ'.code to "لا", // FEFA
      'ﻷ'.code to "لا", // FEF7
      'ﻸ'.code to "لا", // FEF8
      'ﻵ'.code to "لا", // FEF5
      'ﻶ'.code to "لا" // FEF6
    )

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
        when (val folded = arabicToPersian[cp]) {
          null -> {
            when {
              cp in zeroWidthCodes -> Unit
              Character.isWhitespace(cp) -> pendingSpace = length > 0
              else -> {
                if (pendingSpace && length > 0) append(' ')
                pendingSpace = false
                appendCodePoint(Character.toLowerCase(cp))
              }
            }
          }
          else -> {
            // Fold targets are already in canonical Persian form; append them
            // verbatim (ligature targets are two chars, e.g. lam-alef).
            if (pendingSpace && length > 0) append(' ')
            pendingSpace = false
            append(folded)
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
