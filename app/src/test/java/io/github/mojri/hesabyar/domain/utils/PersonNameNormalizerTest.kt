package io.github.mojri.hesabyar.domain.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonNameNormalizerTest {
  @Test
  fun normalizeFoldsArabicYehKafAndTehMarbutaToPersian() {
    assertEquals("علی", PersonNameNormalizer.normalize("علي"))
    assertEquals("عکبر", PersonNameNormalizer.normalize("عكبر"))
    assertEquals("حوزه", PersonNameNormalizer.normalize("حوزة"))
  }

  @Test
  fun normalizeFoldsAlefVariantsToPlainAlef() {
    assertEquals("ابو", PersonNameNormalizer.normalize("أبو"))
    assertEquals("ایران", PersonNameNormalizer.normalize("إيران"))
    assertEquals("اب", PersonNameNormalizer.normalize("آب"))
    assertEquals("الاسلام", PersonNameNormalizer.normalize("ٱلاسلام"))
  }

  @Test
  fun normalizeFoldsAlefMaksuraYehHamzaAndWawHamza() {
    assertEquals("علی", PersonNameNormalizer.normalize("على"))
    assertEquals("بی", PersonNameNormalizer.normalize("بئ"))
    assertEquals("سو", PersonNameNormalizer.normalize("سؤ"))
  }

  @Test
  fun normalizeFoldsLamAlefLigaturePresentationFormsToLamAlef() {
    val isolated = String(Character.toChars(0xFEFB))
    val final = String(Character.toChars(0xFEFC))
    val lamAlef = "لا"
    assertEquals(lamAlef, PersonNameNormalizer.normalize(isolated))
    assertEquals(lamAlef, PersonNameNormalizer.normalize(final))
    // Hamza-bearing ligatures fold away the hamza to the canonical form.
    for (code in intArrayOf(0xFEF5, 0xFEF6, 0xFEF7, 0xFEF8, 0xFEF9, 0xFEFA)) {
      assertEquals(lamAlef, PersonNameNormalizer.normalize(String(Character.toChars(code))))
    }
  }

  @Test
  fun normalizeArabicVariantKeyEqualsPersianSpellingKey() {
    // The dedup invariant: each Arabic-script variant shares one key with the
    // visually equivalent Persian spelling.
    assertEquals(
      PersonNameNormalizer.normalize("علی"),
      PersonNameNormalizer.normalize("علي")
    )
    assertEquals(
      PersonNameNormalizer.normalize("فا"),
      PersonNameNormalizer.normalize("فأ")
    )
    assertEquals(
      PersonNameNormalizer.normalize("لا"),
      PersonNameNormalizer.normalize(String(Character.toChars(0xFEFB)))
    )
  }

  @Test
  fun normalizeTrimsAndCollapsesInternalWhitespace() {
    assertEquals("علی رضا", PersonNameNormalizer.normalize("  علی   رضا  "))
    assertEquals("علی رضا", PersonNameNormalizer.normalize("\tعلی\nرضا"))
  }

  @Test
  fun normalizeStripsZeroWidthCharacters() {
    assertEquals("علیرضا", PersonNameNormalizer.normalize("علی\u200Cرضا"))
    assertEquals("علیرضا", PersonNameNormalizer.normalize("علی\u200Bرضا"))
  }

  @Test
  fun normalizeLowercasesLatinPartOnly() {
    assertEquals("ali رضا", PersonNameNormalizer.normalize("ALI رضا"))
  }

  @Test
  fun normalizeReturnsEmptyStringForWhitespaceOnlyInput() {
    assertEquals("", PersonNameNormalizer.normalize("   "))
  }

  @Test
  fun normalizeReturnsEmptyStringForZeroWidthOnlyInput() {
    assertEquals("", PersonNameNormalizer.normalize("\u200B"))
    assertEquals("", PersonNameNormalizer.normalize("\u200C\u200D\uFEFF\u2060"))
  }

  @Test
  fun displayFormKeepsFirstTrimmedOriginalSpelling() {
    assertEquals("  علی  ".trim(), PersonNameNormalizer.displayForm("  علی  "))
    assertEquals("ALI", PersonNameNormalizer.displayForm("ALI"))
  }

  @Test
  fun displayFormPreservesZeroWidthCharacters() {
    // D4: display preserves ZWNJ/zero-width; only normalize strips them.
    // A zero-width-only input stays non-empty after trim but normalizes to empty.
    assertEquals("\u200B", PersonNameNormalizer.displayForm("\u200B"))
    assertEquals("\u200Bعلی\u200C", PersonNameNormalizer.displayForm("\u200Bعلی\u200C"))
    assertEquals("علی\u200Cرضا", PersonNameNormalizer.displayForm("علی\u200Cرضا"))
  }
}
