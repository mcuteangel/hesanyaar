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
