package io.github.mojri.hesabyar.rust

import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin mapper tests — no native library needed, so no
 * [io.github.mojri.hesabyar.RustTest] category.
 */
class RustMappersTest {
  private fun map(txType: io.github.mojri.hesabyar.rust.TransactionType): TransactionType =
    RustMappers
      .fromRustTransaction(
        io.github.mojri.hesabyar.rust.Transaction(
          id = 1L,
          txType = txType,
          categoryId = 0L,
          amount = 0L,
          description = "",
          personName = null,
          personId = null,
          date = 0L,
          dueDate = null,
          installmentId = null,
          accountId = 1L,
          destinationAccountId = null
        )
      ).type

  @Test
  fun fromRustTransactionExpenseMapsToExpense() {
    assertEquals(TransactionType.EXPENSE, map(io.github.mojri.hesabyar.rust.TransactionType.EXPENSE))
  }

  @Test
  fun fromRustTransactionIncomeMapsToIncome() {
    assertEquals(TransactionType.INCOME, map(io.github.mojri.hesabyar.rust.TransactionType.INCOME))
  }

  @Test
  fun fromRustTransactionLoanDebtorCollapsesToExpense() {
    assertEquals(
      TransactionType.EXPENSE,
      map(io.github.mojri.hesabyar.rust.TransactionType.LOAN_DEBTOR)
    )
  }

  @Test
  fun fromRustTransactionLoanCreditorCollapsesToIncome() {
    assertEquals(
      TransactionType.INCOME,
      map(io.github.mojri.hesabyar.rust.TransactionType.LOAN_CREDITOR)
    )
  }

  @Test
  fun fromRustTransactionInstallmentCollapsesToExpense() {
    assertEquals(
      TransactionType.EXPENSE,
      map(io.github.mojri.hesabyar.rust.TransactionType.INSTALLMENT)
    )
  }

  // --- mapAnalyticsData accountBreakdown colors -------------------------------

  /** Rust analytics result with a single account that has no category activity. */
  private fun analyticsWithOneAccount(): io.github.mojri.hesabyar.rust.AnalyticsData =
    io.github.mojri.hesabyar.rust.AnalyticsData(
      monthlySpending = emptyList(),
      monthlyIncome = emptyList(),
      categoryBreakdown = emptyList(),
      debtors = emptyList(),
      creditors = emptyList(),
      totalDebt = 0L,
      totalCredit = 0L,
      totalInstallments = 0,
      paidInstallments = 0,
      bankLoans = emptyList(),
      bankLoansTotalDebt = 0L,
      accounts =
        listOf(
          io.github.mojri.hesabyar.rust.AccountAnalytics(
            accountId = 7L,
            accountName = "حساب بانکی",
            monthlyData = emptyList(),
            categoryBreakdown = emptyList()
          )
        )
    )

  /**
   * Rust analytics where each account has one category entry and the top-level
   * category breakdown sums to the same totals (consistent with the core).
   */
  private fun analyticsWithAccountTotals(vararg totals: Long): io.github.mojri.hesabyar.rust.AnalyticsData {
    val entries =
      totals.mapIndexed { index, total ->
        io.github.mojri.hesabyar.rust.CategoryBreakdown(
          categoryId = index + 1L,
          categoryName = "cat ${index + 1}",
          color = 0L,
          total = total,
          percentage = 0f
        )
      }
    val accounts =
      totals.mapIndexed { index, total ->
        io.github.mojri.hesabyar.rust.AccountAnalytics(
          accountId = index + 1L,
          accountName = "account ${index + 1}",
          monthlyData = emptyList(),
          categoryBreakdown = listOf(entries[index])
        )
      }
    return io.github.mojri.hesabyar.rust.AnalyticsData(
      monthlySpending = emptyList(),
      monthlyIncome = emptyList(),
      categoryBreakdown = entries,
      debtors = emptyList(),
      creditors = emptyList(),
      totalDebt = 0L,
      totalCredit = 0L,
      totalInstallments = 0,
      paidInstallments = 0,
      bankLoans = emptyList(),
      bankLoansTotalDebt = 0L,
      accounts = accounts
    )
  }

  @Test
  fun mapAnalyticsDataResolvesAccountBreakdownColorFromAccounts() {
    val accounts =
      listOf(
        AccountEntity(
          id = 7L,
          name = "حساب بانکی",
          type = AccountType.BANK,
          color = 0xFF2196F3L // blue
        )
      )

    val result = RustMappers.mapAnalyticsData(analyticsWithOneAccount(), emptyList(), emptyList(), accounts)

    assertEquals("account segment must exist", 1, result.accountBreakdown.size)
    assertEquals("segment must carry the account's configured color", 0xFF2196F3L, result.accountBreakdown[0].color)
  }

  @Test
  fun mapAnalyticsDataFallsBackToDefaultAccountColorWhenAccountMissing() {
    val result = RustMappers.mapAnalyticsData(analyticsWithOneAccount(), emptyList(), emptyList())

    assertEquals("account segment must exist", 1, result.accountBreakdown.size)
    assertEquals(
      "missing account falls back to the canonical account color",
      AccountEntity.DEFAULT_COLOR,
      result.accountBreakdown[0].color
    )
  }

  // --- mapAnalyticsData accountBreakdown percentage math ----------------------

  @Test
  fun mapAnalyticsDataAccountBreakdownComputesPercentageShares() {
    val result =
      RustMappers.mapAnalyticsData(
        analyticsWithAccountTotals(500_000L, 300_000L, 200_000L),
        emptyList(),
        emptyList()
      )

    assertEquals("one entry per account", 3, result.accountBreakdown.size)
    assertEquals("first account total", 500_000L, result.accountBreakdown[0].total)
    assertEquals("first account percentage", 50.0, result.accountBreakdown[0].percentage.toDouble(), 0.1)
    assertEquals("second account percentage", 30.0, result.accountBreakdown[1].percentage.toDouble(), 0.1)
    assertEquals("third account percentage", 20.0, result.accountBreakdown[2].percentage.toDouble(), 0.1)
    assertEquals("categoryId carries the account id", 3L, result.accountBreakdown[2].categoryId)
    assertEquals("categoryName carries the account name", "account 3", result.accountBreakdown[2].categoryName)
  }

  @Test
  fun mapAnalyticsDataAccountBreakdownZeroTotalYieldsZeroPercentages() {
    val result = RustMappers.mapAnalyticsData(analyticsWithAccountTotals(0L, 0L, 0L), emptyList(), emptyList())

    assertEquals("one entry per account", 3, result.accountBreakdown.size)
    result.accountBreakdown.forEach { entry ->
      assertTrue("no NaN/Infinity: ${entry.percentage}", entry.percentage.isFinite())
      assertEquals("zero total yields a zero percentage", 0.0, entry.percentage.toDouble(), 0.0)
      assertEquals("zero total yields a zero total", 0L, entry.total)
    }
  }

  @Test
  fun mapAnalyticsDataAccountBreakdownEmptyAccountsReturnsEmptyList() {
    val result = RustMappers.mapAnalyticsData(analyticsWithAccountTotals(), emptyList(), emptyList())

    assertTrue("no accounts means no segments", result.accountBreakdown.isEmpty())
  }

  @Test
  fun mapAnalyticsDataAccountBreakdownSingleAccountIsOneHundredPercent() {
    val result = RustMappers.mapAnalyticsData(analyticsWithAccountTotals(100_000L), emptyList(), emptyList())

    assertEquals("one entry", 1, result.accountBreakdown.size)
    assertEquals("single account owns the whole total", 100.0, result.accountBreakdown[0].percentage.toDouble(), 0.1)
    assertEquals("total is preserved", 100_000L, result.accountBreakdown[0].total)
  }

  @Test
  fun mapAnalyticsDataAccountBreakdownLargeRialValuesStayFinite() {
    val result =
      RustMappers.mapAnalyticsData(
        analyticsWithAccountTotals(
          5_000_000_000_000_000L,
          3_000_000_000_000_000L,
          2_000_000_000_000_000L
        ),
        emptyList(),
        emptyList()
      )

    result.accountBreakdown.forEach { entry ->
      assertTrue("no overflow to NaN/Infinity: ${entry.percentage}", entry.percentage.isFinite())
    }
    assertEquals("large first account percentage", 50.0, result.accountBreakdown[0].percentage.toDouble(), 0.5)
    assertEquals("large second account percentage", 30.0, result.accountBreakdown[1].percentage.toDouble(), 0.5)
    assertEquals("large third account percentage", 20.0, result.accountBreakdown[2].percentage.toDouble(), 0.5)
    assertEquals("percentages sum to 100", 100.0, result.accountBreakdown.sumOf { it.percentage.toDouble() }, 0.5)
  }

  @Test
  fun mapAnalyticsDataAccountBreakdownLongMaxValueStaysFinite() {
    val result = RustMappers.mapAnalyticsData(analyticsWithAccountTotals(Long.MAX_VALUE), emptyList(), emptyList())

    assertEquals("one entry", 1, result.accountBreakdown.size)
    assertTrue("Long.MAX_VALUE percentage is finite", result.accountBreakdown[0].percentage.isFinite())
    assertEquals("Long.MAX_VALUE single account is 100%", 100.0, result.accountBreakdown[0].percentage.toDouble(), 1.0)
  }

  // --- fromRustInstallment bankLoanId preservation ---------------------------

  @Test
  fun fromRustInstallmentPreservesBankLoanId() {
    val result =
      RustMappers.fromRustInstallment(
        io.github.mojri.hesabyar.rust.Installment(
          id = 1L,
          title = "قسط",
          amount = 2_000_000L,
          dueDate = 1_700_000_000_000L,
          isPaid = false,
          reminderEnabled = true,
          notes = "",
          bankLoanId = 7L
        )
      )

    assertEquals("bankLoanId survives Rust→Kotlin mapping", 7L, result.bankLoanId)
  }

  @Test
  fun fromRustInstallmentPreservesNullBankLoanId() {
    val result =
      RustMappers.fromRustInstallment(
        io.github.mojri.hesabyar.rust.Installment(
          id = 2L,
          title = "قسط بدون وام",
          amount = 1_000_000L,
          dueDate = 1_700_000_000_000L,
          isPaid = true,
          reminderEnabled = false,
          notes = "",
          bankLoanId = null
        )
      )

    assertEquals("null bankLoanId stays null", null, result.bankLoanId)
  }

  @Test
  fun mapInstallmentPreservesBankLoanIdRoundTrip() {
    val kotlin =
      io.github.mojri.hesabyar.data.Installment(
        id = 3L,
        title = "قسط",
        amount = 500_000L,
        dueDate = 1_700_000_000_000L,
        isPaid = false,
        reminderEnabled = true,
        notes = "",
        bankLoanId = 42L
      )

    val rust = RustMappers.mapInstallment(kotlin)
    val roundTripped = RustMappers.fromRustInstallment(rust)

    assertEquals("bankLoanId survives Kotlin→Rust→Kotlin round-trip", 42L, roundTripped.bankLoanId)
  }

  // --- fromRustPerson / mapPerson person mapper branches ----------------------

  @Test
  fun fromRustPersonNormalizesNameAndPreservesDisplayForm() {
    val rust =
      io.github.mojri.hesabyar.rust.Person(
        id = 1L,
        name = "  علی  رضا  ",
        normalizedName = "stale",
        phone = "0912",
        notes = "n",
        createdAt = 1000L,
        isArchived = false
      )
    val mapped = RustMappers.fromRustPerson(rust)
    assertEquals("علی  رضا", mapped.name)
    assertEquals("علی رضا", mapped.normalizedName)
  }

  @Test(expected = IllegalArgumentException::class)
  fun fromRustPersonEmptyNormalizedNameThrows() {
    val rust =
      io.github.mojri.hesabyar.rust.Person(
        id = 2L,
        name = "   \u200B \u200C  ",
        normalizedName = "",
        phone = null,
        notes = null,
        createdAt = 0L,
        isArchived = false
      )
    RustMappers.fromRustPerson(rust)
  }

  @Test
  fun fromRustPersonCreatedAtZeroFallbacksToNow() {
    val before = System.currentTimeMillis()
    val rust =
      io.github.mojri.hesabyar.rust.Person(
        id = 3L,
        name = "سارا",
        normalizedName = "sara",
        phone = null,
        notes = null,
        createdAt = 0L,
        isArchived = false
      )
    val mapped = RustMappers.fromRustPerson(rust)
    assertTrue("createdAt 0 must fallback to now: ${mapped.createdAt}", mapped.createdAt >= before)
  }

  @Test
  fun fromRustPersonsFiltersOnlyViaThrowSemantics() {
    val good =
      io.github.mojri.hesabyar.rust.Person(
        id = 10L,
        name = "حسن",
        normalizedName = "حسن",
        phone = null,
        notes = null,
        createdAt = 1000L,
        isArchived = false
      )
    val list = RustMappers.fromRustPersons(listOf(good))
    assertEquals(1, list.size)
    assertEquals("حسن", list[0].name)
  }

  @Test(expected = IllegalArgumentException::class)
  fun fromRustPersonsThrowsOnBlankEntry() {
    val good =
      io.github.mojri.hesabyar.rust.Person(
        id = 1L,
        name = "حسن",
        normalizedName = "حسن",
        phone = null,
        notes = null,
        createdAt = 1000L,
        isArchived = false
      )
    val bad =
      io.github.mojri.hesabyar.rust.Person(
        id = 2L,
        name = "   ",
        normalizedName = "",
        phone = null,
        notes = null,
        createdAt = 0L,
        isArchived = false
      )
    RustMappers.fromRustPersons(listOf(good, bad))
  }

  @Test(expected = IllegalArgumentException::class)
  fun mapPersonEmptyNormalizedNameThrowsRequireGuard() {
    val person =
      io.github.mojri.hesabyar.data.Person(
        id = 99L,
        name = "   \u200B",
        normalizedName = "",
        phone = null,
        notes = null,
        createdAt = 1000L,
        isArchived = false
      )
    RustMappers.mapPerson(person)
  }

  @Test
  fun mapPersonsRoundTripsPhoneAndNotes() {
    val person =
      io.github.mojri.hesabyar.data.Person(
        id = 0L,
        name = "رضا",
        normalizedName = "رضا",
        phone = "0912",
        notes = "note",
        createdAt = 1234L,
        isArchived = true
      )
    val rust = RustMappers.mapPerson(person)
    val back = RustMappers.fromRustPerson(rust)
    assertEquals("0912", back.phone)
    assertEquals("note", back.notes)
    assertEquals(true, back.isArchived)
  }
}
