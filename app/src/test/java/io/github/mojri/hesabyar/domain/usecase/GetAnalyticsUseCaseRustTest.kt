package io.github.mojri.hesabyar.domain.usecase
import io.github.mojri.hesabyar.HesabyarApp
import io.github.mojri.hesabyar.RustIsolationRule
import io.github.mojri.hesabyar.RustTest
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.TransactionType
import io.github.mojri.hesabyar.rust.RustBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Exercises [GetAnalyticsUseCase.computeAnalytics] on its **native (Rust)**
 * path — the default in unit tests because the hesabyar_core library loads.
 *
 * The debtor/creditor/active-loan lists are recomputed in Kotlin from the
 * domain loans regardless of which engine produced the rest, so those mappings
 * are asserted deterministically here. The Rust-sourced aggregates
 * (totals, category breakdown, monthly series) are only checked structurally.
 */
@Category(RustTest::class)
class GetAnalyticsUseCaseRustTest {
  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun setUp() {
    // RustIsolationRule resets the flag to false; re-enable it so these tests
    // exercise the native Rust path rather than the Kotlin fallback.
    HesabyarApp.setRustInitializedForTesting(true)
  }

  private val useCase = GetAnalyticsUseCase()

  private fun loan(
    type: LoanType,
    original: Long,
    remaining: Long,
    person: String = "p",
    settled: Boolean = false
  ): Loan =
    Loan(
      personName = person,
      personId = null,
      type = type,
      originalAmount = original,
      remainingAmount = remaining,
      description = "l",
      isSettled = settled
    )

  @Test
  fun rustPathYieldsEmptyCollectionsAndZeroTotalsForEmptyInputs() {
    assertTrue(RustBridge.isAvailable)
    val result =
      useCase.computeAnalytics(emptyList(), emptyList(), emptyList(), emptyList())

    assertTrue(result.debtors.isEmpty())
    assertTrue(result.creditors.isEmpty())
    assertTrue(result.activeLoans.isEmpty())
    assertEquals(0, result.totalInstallments)
    assertEquals(0, result.paidInstallments)
    assertTrue(result.installmentProgress.isEmpty())
    assertTrue(result.categoryBreakdown.isEmpty())
    assertEquals(0L, result.totalDebt)
    assertEquals(0L, result.totalCredit)
  }

  @Test
  fun rustPathPartitionsDebtorsAndCreditorsFromKotlinLoans() {
    assertTrue(RustBridge.isAvailable)
    val loans =
      listOf(
        loan(LoanType.DEBTOR, 5_000_000, 3_000_000, "علی", settled = false),
        loan(LoanType.DEBTOR, 2_000_000, 1_000_000, "حسن", settled = true),
        loan(LoanType.CREDITOR, 10_000_000, 5_000_000, "محمد", settled = false),
        loan(LoanType.CREDITOR, 3_000_000, 0L, "رضا", settled = true)
      )
    val result =
      useCase.computeAnalytics(emptyList(), loans, emptyList(), emptyList())

    // Debtors: only unsettled DEBTOR.
    assertEquals(1, result.debtors.size)
    assertEquals("علی", result.debtors[0].personName)
    assertEquals(0.4f, result.debtors[0].progress, 0.001f)

    // Creditors: only unsettled CREDITOR.
    assertEquals(1, result.creditors.size)
    assertEquals("محمد", result.creditors[0].personName)

    // Active loans exclude settled.
    assertEquals(2, result.activeLoans.size)
    assertTrue(result.activeLoans.none { it.isSettled })
  }

  @Test
  fun rustPathMapsInstallmentProgress() {
    assertTrue(RustBridge.isAvailable)
    val insts =
      listOf(
        Installment(title = "a", amount = 1_000_000, dueDate = 0L, isPaid = true),
        Installment(title = "b", amount = 1_000_000, dueDate = 0L, isPaid = false)
      )
    val result =
      useCase.computeAnalytics(emptyList(), emptyList(), insts, emptyList())

    assertEquals(2, result.totalInstallments)
    assertEquals(1, result.paidInstallments)
    assertEquals(2, result.installmentProgress.size)
  }

  @Test
  fun rustPathProducesNonnegativeTotals() {
    assertTrue(RustBridge.isAvailable)
    val loans =
      listOf(
        loan(LoanType.DEBTOR, 5_000_000, 3_000_000, "علی"),
        loan(LoanType.CREDITOR, 10_000_000, 5_000_000, "محمد")
      )
    val result =
      useCase.computeAnalytics(emptyList(), loans, emptyList(), emptyList())

    assertTrue(result.totalDebt >= 0L)
    assertTrue(result.totalCredit >= 0L)
    assertTrue(result.categoryBreakdown.all { it.percentage >= 0f })
  }

  @Test
  fun rustPathReturnsNonemptyBankLoanSummaries() {
    assertTrue(RustBridge.isAvailable)
    val bankLoans =
      listOf(
        BankLoan(
          bankName = "بانک ملت",
          loanName = "وام خودرو",
          receivedAmount = 100_000_000L,
          monthlyInstallmentAmount = 10_000_000L,
          numberOfInstallments = 12,
          totalRepayableAmount = 120_000_000L,
          totalInterest = 20_000_000L,
          startDate = 1_700_000_000_000L,
          description = "test"
        )
      )
    // Directly invoke the Rust bridge and assert it produced a non-null result,
    // guaranteeing the native path executed successfully (no silent Kotlin fallback).
    val rustAnalytics =
      RustBridge.computeAnalyticsSync(emptyList(), emptyList(), emptyList(), emptyList(), bankLoans)
    assertTrue(rustAnalytics != null)
    val analytics = rustAnalytics!!

    // Assert bank loan summaries and total debt against the native Rust result.
    assertEquals(1, analytics.bankLoans.size)
    assertEquals("بانک ملت", analytics.bankLoans[0].bankName)
    assertEquals("وام خودرو", analytics.bankLoans[0].loanName)
    assertEquals(100_000_000L, analytics.bankLoans[0].receivedAmount)
    assertEquals(120_000_000L, analytics.bankLoans[0].totalRepayableAmount)
    assertEquals(20_000_000L, analytics.bankLoans[0].totalInterest)
    assertEquals(12, analytics.bankLoans[0].numberOfInstallments)
    assertEquals(120_000_000L, analytics.bankLoansTotalDebt)

    // Also verify the useCase integrates the Rust result correctly.
    val result = useCase.computeAnalytics(emptyList(), emptyList(), emptyList(), emptyList(), bankLoans)
    assertEquals(1, result.bankLoans.size)
    assertEquals("بانک ملت", result.bankLoans[0].bankName)
    assertEquals(120_000_000L, result.bankLoans[0].totalRepayableAmount)
    assertEquals(120_000_000L, result.bankLoansTotalDebt)
  }

  @Test
  fun rustPathAllAccountsExcludesArchivedAccountTransactions() {
    assertTrue(RustBridge.isAvailable)
    val categories = listOf(analyticsCat(1, "خوراک"))
    val accounts =
      listOf(
        AccountEntity(id = 1, name = "Active", type = AccountType.BANK),
        AccountEntity(id = 2, name = "Archived", type = AccountType.CASH_WALLET, isArchived = true),
      )
    val txs =
      listOf(
        analyticsTx(TransactionType.INCOME, 3_000_000, accountId = 1),
        analyticsTx(TransactionType.EXPENSE, 1_000_000, accountId = 1),
        analyticsTx(TransactionType.INCOME, 500_000, accountId = 2),
        analyticsTx(TransactionType.EXPENSE, 200_000, accountId = 2),
      )

    val result =
      useCase.computeAnalytics(txs, emptyList(), emptyList(), categories, accounts = accounts)
    // Archived-account transactions must not leak into all-accounts totals
    assertEquals(3_000_000L, result.monthlySpending.first().income)
    assertEquals(1_000_000L, result.monthlySpending.first().expense)
    assertEquals(1, result.categoryBreakdown.size)
    assertEquals(1_000_000L, result.categoryBreakdown.first().total)

    // includeArchived = true keeps them
    val withArchived =
      useCase.computeAnalytics(
        txs,
        emptyList(),
        emptyList(),
        categories,
        accounts = accounts,
        includeArchived = true
      )
    assertEquals(3_500_000L, withArchived.monthlySpending.first().income)
    assertEquals(1_200_000L, withArchived.monthlySpending.first().expense)
  }

  @Test
  fun rustPathAccountBreakdownCarriesAccountColor() {
    assertTrue(RustBridge.isAvailable)
    val categories = listOf(analyticsCat(1, "خوراک"))
    val accounts =
      listOf(
        AccountEntity(
          id = 1,
          name = "Active",
          type = AccountType.BANK,
          color = 0xFF2196F3L
        )
      )
    val txs =
      listOf(
        analyticsTx(TransactionType.EXPENSE, 1_000_000, accountId = 1),
      )

    val result =
      useCase.computeAnalytics(txs, emptyList(), emptyList(), categories, accounts = accounts)

    // The Rust core carries no account color; the mapper must resolve it from
    // the AccountEntity so the donut chart segments render in the account color.
    assertEquals(1, result.accountBreakdown.size)
    assertEquals("segment color must come from the AccountEntity", 0xFF2196F3L, result.accountBreakdown[0].color)
  }

  @Test
  fun rustPathAccountBreakdownContainsOnlySelectedAccountWhenAccountIdSet() {
    assertTrue(RustBridge.isAvailable)
    val categories = listOf(analyticsCat(1, "خوراک"))
    val accounts =
      listOf(
        AccountEntity(id = 1, name = "A", type = AccountType.BANK),
        AccountEntity(id = 2, name = "B", type = AccountType.BANK),
      )
    val txs =
      listOf(
        analyticsTx(TransactionType.EXPENSE, 1_000_000, accountId = 1),
        analyticsTx(TransactionType.EXPENSE, 500_000, accountId = 2),
      )

    // Selecting account 1 must restrict the breakdown to account 1's segment —
    // the Kotlin fallback produces exactly this for the same input.
    val result =
      useCase.computeAnalytics(
        txs,
        emptyList(),
        emptyList(),
        categories,
        accounts = accounts,
        accountId = 1
      )
    assertEquals("only the selected account may appear", 1, result.accountBreakdown.size)
    assertEquals("segment carries the selected account id", 1L, result.accountBreakdown[0].categoryId)
    assertEquals("segment total matches the selected account's expenses", 1_000_000L, result.accountBreakdown[0].total)
    assertEquals("single segment owns 100%", 100f, result.accountBreakdown[0].percentage, 0.001f)
  }

  @Test
  fun rustPathAccountBreakdownEmptyWhenSelectedAccountHasNoExpenses() {
    assertTrue(RustBridge.isAvailable)
    val categories = listOf(analyticsCat(1, "خوراک"))
    val accounts =
      listOf(
        AccountEntity(id = 1, name = "A", type = AccountType.BANK),
        AccountEntity(id = 2, name = "B", type = AccountType.BANK),
      )
    val txs =
      listOf(
        analyticsTx(TransactionType.INCOME, 1_000_000, accountId = 1),
        analyticsTx(TransactionType.EXPENSE, 200_000, accountId = 2),
      )

    val result =
      useCase.computeAnalytics(
        txs,
        emptyList(),
        emptyList(),
        categories,
        accounts = accounts,
        accountId = 1
      )
    // Selected account has no expenses → no segments (the Kotlin fallback's
    // buildAccountBreakdown returns an empty list for the same input).
    assertTrue("no expenses means no account-breakdown segments", result.accountBreakdown.isEmpty())
  }
}
