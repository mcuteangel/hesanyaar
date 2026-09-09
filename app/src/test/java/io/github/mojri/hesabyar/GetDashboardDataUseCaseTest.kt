package io.github.mojri.hesabyar

import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import io.github.mojri.hesabyar.domain.usecase.GetDashboardDataUseCase
import io.github.mojri.hesabyar.ui.JalaliCalendarHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Tests the Kotlin fallback computation in [GetDashboardDataUseCase].
 *
 * The Rust bridge is available in the unit-test environment, so
 * [GetDashboardDataUseCase.computeDashboardData] always follows the Rust path.
 * These tests exercise [GetDashboardDataUseCase.computeFallbackDashboardData]
 * directly to verify the three fallback decision branches:
 *
 * 1. Rust returns null → Kotlin fallback runs
 * 2. Rust returns valid data → Rust path used (not testable without native mock)
 * 3. Rust returns blank placeholder while local data exists → Kotlin fallback runs
 *
 * Branches 2 and 3 require the native library and are covered by
 * instrumented/integration tests.
 */
@Category(RustTest::class)
class GetDashboardDataUseCaseTest {
  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun setUp() {
    HesabyarApp.setRustInitializedForTesting(true)
  }

  private fun tx(
    type: TransactionType,
    amount: Long,
    date: Long = System.currentTimeMillis()
  ) = Transaction(type = type, categoryId = 1L, amount = amount, description = "", date = date)

  private fun loan(
    type: LoanType,
    remaining: Long,
    settled: Boolean = false
  ) = Loan(
    personName = "test",
    personId = null,
    type = type,
    originalAmount = remaining,
    remainingAmount = remaining,
    description = "",
    isSettled = settled
  )

  private fun inst(
    amount: Long,
    paid: Boolean = false,
    dueDate: Long = System.currentTimeMillis()
  ) = Installment(title = "t", amount = amount, dueDate = dueDate, isPaid = paid)

  // -- Branch 1 & 3: Kotlin fallback computation -----------------------------

  @Test
  fun fallbackComputesMonthlyIncomeAndExpensesFromRecentTransactions() {
    val now = System.currentTimeMillis()
    val (jalaliMonthStart, jalaliMonthEndExclusive) =
      JalaliCalendarHelper.getUtcJalaliMonthBoundaries(now)
    // Place transaction safely inside the current Jalali month (1 day after start).
    val recent = jalaliMonthStart + 1L * 24 * 60 * 60 * 1000
    // Place transaction before the current Jalali month (excluded).
    val old = jalaliMonthStart - 1L

    val transactions =
      listOf(
        tx(TransactionType.INCOME, 5_000_000, recent),
        tx(TransactionType.EXPENSE, 2_000_000, recent),
        tx(TransactionType.INCOME, 10_000_000, old) // should be excluded
      )

    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        now = now
      )

    assertEquals(5_000_000L, result.monthlyIncome)
    assertEquals(2_000_000L, result.monthlyExpenses)
    // currentBalance uses ALL transactions (lifetime), not just the Jalali month:
    // total income = 5M (recent) + 10M (old) = 15M, total expense = 2M → 13M
    assertEquals(13_000_000L, result.currentBalance)
  }

  @Test
  fun fallbackAggregatesDebtorAndCreditorTotalsFromUnsettledLoans() {
    val transactions = listOf(tx(TransactionType.INCOME, 10_000_000))
    val loans =
      listOf(
        loan(LoanType.DEBTOR, 3_000_000),
        loan(LoanType.CREDITOR, 7_000_000),
        loan(LoanType.DEBTOR, 1_000_000, settled = true) // settled → excluded
      )

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(transactions, loans, emptyList())

    assertEquals(3_000_000L, result.debtorsTotal)
    assertEquals(7_000_000L, result.creditorsTotal)
  }

  @Test
  fun fallbackFiltersUnpaidInstallmentsAsUpcoming() {
    val now = System.currentTimeMillis()
    val upcoming = inst(1_000_000, paid = false, dueDate = now + 5L * 24 * 60 * 60 * 1000)
    val paid = inst(2_000_000, paid = true)

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(emptyList(), emptyList(), listOf(upcoming, paid))

    assertEquals(1, result.upcomingInstallments.size)
    assertEquals(1_000_000L, result.upcomingInstallments[0].amount)
  }

  @Test
  fun fallbackComputesSavingsRateCorrectly() {
    val now = System.currentTimeMillis()
    val transactions = listOf(tx(TransactionType.INCOME, 10_000_000, now), tx(TransactionType.EXPENSE, 3_000_000, now))

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(transactions, emptyList(), emptyList())

    // savingsRate = (10M - 3M) / 10M = 0.7
    assertEquals(0.7, result.savingsRate, 0.01)
  }

  @Test
  fun fallbackComputesDebtToIncomeRatioFromCreditorLoans() {
    val now = System.currentTimeMillis()
    val transactions = listOf(tx(TransactionType.INCOME, 12_000_000, now))
    // Creditor loan remaining 12M → monthly debt = 12M/12 = 1M
    // debtToIncome = 1M / 12M ≈ 0.0833
    val loans = listOf(loan(LoanType.CREDITOR, 12_000_000))

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(transactions, loans, emptyList())

    assertEquals(0.0833, result.debtToIncomeRatio, 0.01)
  }

  @Test
  fun fallbackDebtToIncomeSumsCurrentCycleInstallmentsWithProratedCreditorLoans() {
    val now = System.currentTimeMillis()
    val transactions = listOf(tx(TransactionType.INCOME, 12_000_000, now))
    // Creditor loan remaining 12M → prorated monthly = 1M
    val loans = listOf(loan(LoanType.CREDITOR, 12_000_000))
    // Unpaid installment due this cycle adds its full amount (2M) to monthly debt.
    val installments =
      listOf(
        inst(2_000_000, paid = false, dueDate = now),
        inst(5_000_000, paid = true, dueDate = now), // paid → excluded
        inst(9_000_000, paid = false, dueDate = now - 400L * 24 * 60 * 60 * 1000) // prior cycle → excluded
      )

    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(transactions, loans, installments)

    // monthlyDebt = 1M (loan/12) + 2M (current-cycle installment) = 3M
    // debtToIncome = 3M / 12M = 0.25
    assertEquals(0.25, result.debtToIncomeRatio, 0.01)
  }

  @Test
  fun fallbackReturnsAllZerosForEmptyInput() {
    val result = GetDashboardDataUseCase.computeFallbackDashboardData(emptyList(), emptyList(), emptyList())

    assertEquals(0L, result.currentBalance)
    assertEquals(0L, result.monthlyIncome)
    assertEquals(0L, result.monthlyExpenses)
    assertEquals(0L, result.debtorsTotal)
    assertEquals(0L, result.creditorsTotal)
    assertTrue(result.upcomingInstallments.isEmpty())
    assertEquals(0.0, result.savingsRate, 0.001)
    assertEquals(0.0, result.debtToIncomeRatio, 0.001)
  }

  @Test
  fun fallbackWithOnlyExpensesShowsNegativeBalance() {
    val now = System.currentTimeMillis()
    val transactions = listOf(tx(TransactionType.EXPENSE, 4_000_000, now))

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(transactions, emptyList(), emptyList())

    assertEquals(-4_000_000L, result.currentBalance)
    assertEquals(0.0, result.savingsRate, 0.001) // no income → 0
  }

  @Test
  fun fallbackWithOnlyIncomeShowsPositiveBalance() {
    val now = System.currentTimeMillis()
    val transactions = listOf(tx(TransactionType.INCOME, 8_000_000, now))

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(transactions, emptyList(), emptyList())

    assertEquals(8_000_000L, result.currentBalance)
    assertEquals(1.0, result.savingsRate, 0.01) // all income saved
  }

  // -- Edge cases requested by reviewer --------------------------------------

  @Test
  fun fallbackClampsSavingsRateToZeroWhenExpensesExceedIncome() {
    // Both transactions fall inside the current Jalali month (date defaults to now).
    val transactions =
      listOf(
        tx(TransactionType.INCOME, 5_000_000),
        tx(TransactionType.EXPENSE, 8_000_000) // expense > income
      )

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(transactions, emptyList(), emptyList())

    // savingsRate = (5M - 8M) / 5M = -0.6, clamped to 0.0 by coerceIn(0.0, 1.0).
    assertEquals(0.0, result.savingsRate, 0.0001)
    assertEquals(-3_000_000L, result.currentBalance) // lifetime balance stays negative
  }

  @Test
  fun fallbackReturnsDebtToIncomeRatioOfOneWhenIncomeIsZeroAndDebtExists() {
    // No income transactions -> monthlyIncome == 0.
    // An unpaid installment due within the current cycle creates monthly debt.
    val installments = listOf(inst(2_000_000, paid = false))

    val result = GetDashboardDataUseCase.computeFallbackDashboardData(emptyList(), emptyList(), installments)

    assertEquals(0L, result.monthlyIncome)
    // monthlyIncome <= 0 and monthlyDebt > 0 -> debtToIncomeRatio == 1.0
    assertEquals(1.0, result.debtToIncomeRatio, 0.0001)
  }

  @Test
  fun fallbackIncludesTransactionInsideUtcMonthBoundaryButExcludesNextMonthStart() {
    // The fallback uses UTC half-open [start, end) boundaries matching the Rust
    // core, so a transaction one ms before the exclusive end is included while
    // one exactly at the next-month start is excluded.
    val (_, jalaliMonthEndExclusive) =
      JalaliCalendarHelper.getUtcJalaliMonthBoundaries(System.currentTimeMillis())

    val inside = tx(TransactionType.INCOME, 4_000_000, jalaliMonthEndExclusive - 1L)
    val outside = tx(TransactionType.INCOME, 9_000_000, jalaliMonthEndExclusive)

    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        listOf(inside, outside),
        emptyList(),
        emptyList()
      )

    // Only the inside transaction is included in the monthly window.
    assertEquals(4_000_000L, result.monthlyIncome)
  }

  @Test
  fun fallbackDebtToIncomeFiltersInstallmentsOnUtcMonthBoundaryMatchingRust() {
    val now = System.currentTimeMillis()
    val (_, jalaliMonthEndExclusive) =
      JalaliCalendarHelper.getUtcJalaliMonthBoundaries(now)

    val transactions = listOf(tx(TransactionType.INCOME, 12_000_000, now))
    // Unpaid installment due one ms before the UTC month end (exclusive) is in
    // the current cycle; one exactly at the next-month start is not. This mirrors
    // Rust compute_dashboard_data's due_dateMonth_end_ms half-open filter.
    val inside = inst(2_000_000, paid = false, dueDate = jalaliMonthEndExclusive - 1L)
    val outside = inst(5_000_000, paid = false, dueDate = jalaliMonthEndExclusive)

    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions,
        emptyList(),
        listOf(inside, outside)
      )

    // monthlyDebt = 2M (inside only) / 12M income = 0.1667 — proving the
    // out-of-cycle installment is excluded, identical to the Rust path.
    assertEquals(0.1667, result.debtToIncomeRatio, 0.01)
  }

  // -- includeArchived parameter tests ----------------------------------------

  private fun account(
    id: Long,
    name: String,
    type: AccountType = AccountType.BANK,
    isArchived: Boolean = false,
    initialBalance: Long = 0L,
  ) = AccountEntity(
    id = id,
    name = name,
    type = type,
    isArchived = isArchived,
    initialBalance = initialBalance,
  )

  @Test
  fun fallbackIncludeArchivedFalseExcludesArchivedAccountTransactions() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active")
    val archivedAccount = account(2, "Archived", isArchived = true)

    val transactions =
      listOf(
        Transaction(
          type = TransactionType.INCOME,
          categoryId = 1L,
          amount = 1_000_000,
          description = "",
          date = now,
          accountId = 1
        ),
        Transaction(
          type = TransactionType.INCOME,
          categoryId = 1L,
          amount = 500_000,
          description = "",
          date = now,
          accountId = 2
        ),
      )

    // includeArchived=false: archived account's transaction excluded from totals
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = false,
      )

    assertEquals("currentBalance excludes archived account", 1_000_000L, result.currentBalance)
    assertEquals("monthlyIncome excludes archived account", 1_000_000L, result.monthlyIncome)
    // Only active account in summaries
    assertEquals("only active account in summaries", 1, result.accounts.size)
    assertEquals("active account id preserved", 1L, result.accounts[0].accountId)
  }

  @Test
  fun fallbackIncludeArchivedFalseWithTransferToArchivedAccount() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active")
    val archivedAccount = account(2, "Archived", isArchived = true)

    // Transfer FROM active account TO archived account
    val transactions =
      listOf(
        Transaction(
          type = TransactionType.TRANSFER,
          categoryId = 1L,
          amount = 500_000,
          description = "",
          date = now,
          accountId = 1,
          destinationAccountId = 2,
        ),
      )

    // includeArchived=false: transfer to archived account excluded
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = false,
      )

    assertEquals("transfer is balance-neutral anyway", 0L, result.currentBalance)
    assertEquals("only active account in summaries", 1, result.accounts.size)
  }

  @Test
  fun fallbackIncludeArchivedTrueIncludesArchivedInitialBalance() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active", initialBalance = 200_000L)
    val archivedAccount = account(2, "Archived", isArchived = true, initialBalance = 500_000L)

    val transactions =
      listOf(
        Transaction(
          type = TransactionType.INCOME,
          categoryId = 1L,
          amount = 1_000_000,
          description = "",
          date = now,
          accountId = 1
        ),
        Transaction(
          type = TransactionType.EXPENSE,
          categoryId = 2L,
          amount = 300_000,
          description = "",
          date = now,
          accountId = 2
        ),
      )

    // includeArchived=true: active tx (1M) + archived tx (-300k) + archived initialBalance (500k)
    // active initialBalance (200k) is always included
    // expected = 200k + 1M - 300k + 500k = 1_400_000
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = true,
      )

    assertEquals("currentBalance includes archived initialBalance", 1_400_000L, result.currentBalance)
  }

  @Test
  fun fallbackIncludeArchivedFalseExcludesArchivedInitialBalance() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active", initialBalance = 200_000L)
    val archivedAccount = account(2, "Archived", isArchived = true, initialBalance = 500_000L)

    val transactions =
      listOf(
        Transaction(
          type = TransactionType.INCOME,
          categoryId = 1L,
          amount = 1_000_000,
          description = "",
          date = now,
          accountId = 1
        ),
        Transaction(
          type = TransactionType.EXPENSE,
          categoryId = 2L,
          amount = 300_000,
          description = "",
          date = now,
          accountId = 2
        ),
      )

    // includeArchived=false: only active tx (1M) + active initialBalance (200k), no archived
    // expected = 200k + 1M = 1_200_000
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = false,
      )

    assertEquals("currentBalance excludes archived initialBalance", 1_200_000L, result.currentBalance)
  }

  // -- selected-account archive gate: a selected account's opening balance must
  //    respect includeArchived exactly like its transactions -----------------

  @Test
  fun fallbackSelectedArchivedAccountIncludeArchivedFalseExcludesInitialBalance() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active", initialBalance = 200_000L)
    val archivedAccount = account(2, "Archived", isArchived = true, initialBalance = 500_000L)

    val transactions =
      listOf(
        Transaction(
          type = TransactionType.INCOME,
          categoryId = 1L,
          amount = 1_000_000,
          description = "",
          date = now,
          accountId = 2 // archived
        ),
      )

    // includeArchived=false + archived selected: its transaction (1M) AND its
    // opening balance (500k) are both excluded — no half-visible account.
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = false,
        accountId = 2L,
      )

    assertEquals("selected archived currentBalance", 0L, result.currentBalance)
    assertEquals("selected archived monthlyIncome", 0L, result.monthlyIncome)
  }

  @Test
  fun fallbackSelectedArchivedAccountIncludeArchivedTrueIncludesInitialBalance() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active", initialBalance = 200_000L)
    val archivedAccount = account(2, "Archived", isArchived = true, initialBalance = 500_000L)

    // No transactions: currentBalance comes purely from the opening balance.
    // includeArchived=true + archived selected: opening balance included.
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = emptyList(),
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = true,
        accountId = 2L,
      )

    assertEquals("selected archived currentBalance", 500_000L, result.currentBalance)
  }

  @Test
  fun fallbackSelectedActiveAccountIncludeArchivedFalseIncludesInitialBalance() {
    val now = System.currentTimeMillis()
    val activeAccount = account(1, "Active", initialBalance = 200_000L)
    val archivedAccount = account(2, "Archived", isArchived = true, initialBalance = 500_000L)

    // No transactions: currentBalance comes purely from the opening balance.
    // includeArchived=false + active selected: opening balance still included.
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = emptyList(),
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(activeAccount, archivedAccount),
        now = now,
        includeArchived = false,
        accountId = 1L,
      )

    assertEquals("selected active currentBalance", 200_000L, result.currentBalance)
  }

  @Test
  fun fallbackDefaultIsIncludeArchivedFalse() {
    val now = System.currentTimeMillis()
    val archivedAccount = account(1, "Archived", isArchived = true)

    val transactions =
      listOf(
        Transaction(
          type = TransactionType.INCOME,
          categoryId = 1L,
          amount = 1_000_000,
          description = "",
          date = now,
          accountId = 1
        ),
      )

    // Default (no includeArchived param) should behave as false
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        transactions = transactions,
        loans = emptyList(),
        installments = emptyList(),
        accounts = listOf(archivedAccount),
        now = now,
      )

    assertEquals("archived transaction excluded by default", 0L, result.currentBalance)
    assertEquals("no accounts in summaries", 0, result.accounts.size)
  }

  // -- Cross-path consistency test (deterministic) ----------------------------

  private fun tx(
    type: TransactionType,
    amount: Long,
    date: Long,
    accountId: Long,
    destId: Long? = null,
  ) = Transaction(
    type = type,
    categoryId = 1L,
    amount = amount,
    description = "",
    date = date,
    accountId = accountId,
    destinationAccountId = destId
  )

  @Test
  fun rustAndKotlinFallbackProduceSameResultWithFixedNow() {
    // Fixed timestamp: 2025-07-15 12:00:00 UTC
    val fixedNowMs = 1752580800000L
    val twoMonthsAgo = fixedNowMs - 60L * 24 * 60 * 60 * 1000

    val activeAccount = account(1, "Active", AccountType.BANK)
    val archivedAccount = account(2, "Archived", AccountType.CASH_WALLET, isArchived = true)
    val accounts = listOf(activeAccount, archivedAccount)

    val txs =
      listOf(
        tx(TransactionType.INCOME, 1_000_000, fixedNowMs, accountId = 1),
        tx(TransactionType.EXPENSE, 300_000, fixedNowMs, accountId = 1),
        tx(TransactionType.INCOME, 5_000_000, twoMonthsAgo, accountId = 1),
        tx(TransactionType.INCOME, 2_000_000, fixedNowMs, accountId = 2), // archived
        tx(TransactionType.TRANSFER, 500_000, fixedNowMs, accountId = 1, destId = 2),
      )

    // Kotlin fallback with explicit now
    val kotlinResult =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = fixedNowMs,
        includeArchived = false,
      )

    // Rust path with the same nowMs
    val rustResult =
      io.github.mojri.hesabyar.rust.RustBridge.computeDashboardDataSync(
        io.github.mojri.hesabyar.rust.RustMappers
          .mapTransactions(txs),
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        accountId = null,
        includeArchived = false,
        nowMs = fixedNowMs,
      )!!

    // Top-level aggregates must be identical
    assertEquals("currentBalance", kotlinResult.currentBalance, rustResult.currentBalance)
    assertEquals("monthlyIncome", kotlinResult.monthlyIncome, rustResult.monthlyIncome)
    assertEquals("monthlyExpenses", kotlinResult.monthlyExpenses, rustResult.monthlyExpenses)
    assertEquals("accounts count", kotlinResult.accounts.size, rustResult.accounts.size)

    // Per-account summaries must be identical
    if (kotlinResult.accounts.isNotEmpty()) {
      val k = kotlinResult.accounts.first()
      val r = rustResult.accounts.first()
      assertEquals("accountId", k.accountId, r.accountId)
      assertEquals("balance", k.balance, r.balance)
      assertEquals("monthlyIncome", k.monthlyIncome, r.monthlyIncome)
      assertEquals("monthlyExpenses", k.monthlyExpenses, r.monthlyExpenses)
    }
  }

  // -- Cross-path consistency: monthlyDelta ------------------------------------

  @Test
  fun rustAndKotlinFallbackProduceSameMonthlyDelta() {
    // Fixed timestamp: 2025-07-15 12:00:00 UTC — ensures deterministic
    // Jalali month boundaries for both Rust and Kotlin.
    val fixedNowMs = 1752580800000L

    // Determine previous month midpoint using the same helper the Kotlin
    // fallback uses, so we place transactions at known-safe offsets.
    val (curStart, _) = JalaliCalendarHelper.getUtcJalaliMonthBoundaries(fixedNowMs)
    val (prevStart, prevEnd) = JalaliCalendarHelper.getUtcJalaliPreviousMonthBoundaries(curStart)
    val prevMid = prevStart + (prevEnd - prevStart) / 2

    val activeAccount = account(1, "Active", AccountType.BANK)
    val accounts = listOf(activeAccount)

    val txs =
      listOf(
        // Current month: income=500k, expense=200k → currentNet=300k
        tx(TransactionType.INCOME, 500_000, curStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 200_000, curStart + 2, accountId = 1),
        // Previous month: income=400k, expense=200k → prevNet=200k
        tx(TransactionType.INCOME, 400_000, prevMid, accountId = 1),
        tx(TransactionType.EXPENSE, 200_000, prevMid + 1, accountId = 1),
      )

    // Kotlin fallback with explicit now
    val kotlinResult =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = fixedNowMs,
        includeArchived = false,
      )

    // Rust path with the same nowMs
    val rustResult =
      io.github.mojri.hesabyar.rust.RustBridge.computeDashboardDataSync(
        io.github.mojri.hesabyar.rust.RustMappers
          .mapTransactions(txs),
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        accountId = null,
        includeArchived = false,
        nowMs = fixedNowMs,
      )!!

    val kAcc = kotlinResult.accounts.first { it.accountId == 1L }
    val rAcc = rustResult.accounts.first { it.accountId == 1L }

    // delta = (300k - 200k) / 200k = 0.5
    assertEquals(
      "monthlyDelta must match between Rust and Kotlin fallback",
      kAcc.monthlyDelta,
      rAcc.monthlyDelta,
      1e-10,
    )
    assertEquals("monthlyDelta value", 0.5, kAcc.monthlyDelta, 1e-10)
  }

  @Test
  fun monthlyDeltaZeroWhenPreviousNetBelowThreshold() {
    val fixedNowMs = 1752580800000L
    val activeAccount = account(1, "Active", AccountType.BANK)
    val accounts = listOf(activeAccount)

    // Only current month transactions — previous month has no activity
    // → prevNet=0 → delta=0.0
    val (curStart, _) = JalaliCalendarHelper.getUtcJalaliMonthBoundaries(fixedNowMs)
    val txs =
      listOf(
        tx(TransactionType.INCOME, 500_000, curStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 200_000, curStart + 2, accountId = 1),
      )

    val kotlinResult =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = fixedNowMs,
        includeArchived = false,
      )

    val rustResult =
      io.github.mojri.hesabyar.rust.RustBridge.computeDashboardDataSync(
        io.github.mojri.hesabyar.rust.RustMappers
          .mapTransactions(txs),
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        accountId = null,
        includeArchived = false,
        nowMs = fixedNowMs,
      )!!

    val kAcc = kotlinResult.accounts.first { it.accountId == 1L }
    val rAcc = rustResult.accounts.first { it.accountId == 1L }

    assertEquals("Kotlin delta=0.0 when no prev data", 0.0, kAcc.monthlyDelta, 1e-10)
    assertEquals("Rust delta=0.0 when no prev data", 0.0, rAcc.monthlyDelta, 1e-10)
  }

  // -- Invariant: sum of account balances == currentBalance --------------------

  @Test
  fun sumOfAccountBalancesEqualsCurrentBalance() {
    val now = System.currentTimeMillis()
    val (monthStart, monthEnd) =
      io.github.mojri.hesabyar.ui.JalaliCalendarHelper
        .getUtcJalaliMonthBoundaries(now)

    val activeAccount = account(1, "Active", AccountType.BANK)
    val activeAccount2 = account(2, "Active2", AccountType.CASH_WALLET)
    val archivedAccount = account(3, "Archived", AccountType.BANK, isArchived = true)
    val accounts = listOf(activeAccount, activeAccount2, archivedAccount)

    val txs =
      listOf(
        tx(TransactionType.INCOME, 1_000_000, now, accountId = 1),
        tx(TransactionType.EXPENSE, 300_000, now, accountId = 1),
        tx(TransactionType.INCOME, 2_000_000, now, accountId = 2),
        tx(TransactionType.EXPENSE, 100_000, now, accountId = 2),
        tx(TransactionType.TRANSFER, 500_000, now, accountId = 1, destId = 2),
        tx(TransactionType.INCOME, 500_000, now, accountId = 3), // archived — excluded
      )

    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = now,
        includeArchived = false,
      )

    val sumOfAccountBalances = result.accounts.sumOf { it.balance }
    assertEquals(
      "sum(accountBalances) must equal currentBalance",
      result.currentBalance,
      sumOfAccountBalances,
    )
  }

  // -- Phase 5: Archive/unarchive round-trip ----------------------------------

  @Test
  fun archiveThenUnarchiveRestoresDashboardBalance() {
    val now = System.currentTimeMillis()
    val accountA = account(1, "Active", AccountType.BANK)
    val accountB = account(2, "Secondary", AccountType.CASH_WALLET)
    val allActive = listOf(accountA, accountB)

    val txs =
      listOf(
        tx(TransactionType.INCOME, 3_000_000, now, accountId = 1),
        tx(TransactionType.EXPENSE, 500_000, now, accountId = 1),
        tx(TransactionType.INCOME, 1_000_000, now, accountId = 2),
        tx(TransactionType.EXPENSE, 200_000, now, accountId = 2),
      )

    // Step 1: Both active — total balance = (3M - 500k) + (1M - 200k) = 3_300_000
    val beforeArchive =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        allActive,
        now = now,
        includeArchived = false,
      )
    assertEquals("balance with both accounts active", 3_300_000L, beforeArchive.currentBalance)
    assertEquals("both accounts active", 2, beforeArchive.accounts.size)

    // Step 2: Archive accountB (mark isArchived=true)
    val archivedAccountB = account(2, "Secondary", AccountType.CASH_WALLET, isArchived = true)
    val withArchived = listOf(accountA, archivedAccountB)

    val afterArchive =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        withArchived,
        now = now,
        includeArchived = false,
      )
    // Balance must drop by accountB's net: (1M - 200k) = 800_000
    assertEquals("balance after archive excludes accountB", 2_500_000L, afterArchive.currentBalance)
    assertEquals("only accountA in summaries", 1, afterArchive.accounts.size)
    assertEquals("remaining account is accountA", 1L, afterArchive.accounts[0].accountId)

    // Step 3: Unarchive accountB — balance restored
    val unarchived = listOf(accountA, accountB)
    val afterUnarchive =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        unarchived,
        now = now,
        includeArchived = false,
      )
    assertEquals(
      "Balance must fully restore after unarchive",
      beforeArchive.currentBalance,
      afterUnarchive.currentBalance,
    )
    assertEquals("both accounts restored after unarchive", 2, afterUnarchive.accounts.size)
  }

  // -- Phase 5: "All accounts" sum invariant ----------------------------------

  @Test
  fun allAccountsSelectionSumsActiveNonArchivedAccounts() {
    val now = System.currentTimeMillis()
    val acc1 = account(1, "Bank", AccountType.BANK)
    val acc2 = account(2, "Wallet", AccountType.CASH_WALLET)
    val acc3 = account(3, "Archived", AccountType.OTHER, isArchived = true)
    val allAccounts = listOf(acc1, acc2, acc3)

    val txs =
      listOf(
        tx(TransactionType.INCOME, 2_000_000, now, accountId = 1),
        tx(TransactionType.EXPENSE, 400_000, now, accountId = 1),
        tx(TransactionType.INCOME, 700_000, now, accountId = 2),
        tx(TransactionType.TRANSFER, 300_000, now, accountId = 1, destId = 2),
        tx(TransactionType.INCOME, 1_000_000, now, accountId = 3), // archived
      )

    // selectedAccountId=null → "all accounts" mode → includeArchived=false
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        allAccounts,
        now = now,
        includeArchived = false,
      )

    // currentBalance = sum of active accounts' balances
    // acc1: +2M income, -400k expense, -300k transfer out = net +1_300_000
    // acc2: +700k income, +300k transfer in = net +1_000_000
    // acc3: archived → excluded from summaries by computeAccountSummaries
    val activeSum = result.accounts.sumOf { it.balance }
    assertEquals(
      "sum(active account balances) must equal currentBalance when selectedAccountId=null",
      result.currentBalance,
      activeSum,
    )
    // Also verify the expected total
    assertEquals("expected total balance for active accounts", 2_300_000L, result.currentBalance)
    // Verify only 2 active accounts in summaries
    assertEquals("only 2 active accounts in summaries", 2, result.accounts.size)
  }

  // -- Phase 5: Comprehensive Rust fallback comparison (all three fields) -----

  @Test
  fun rustFallbackFullDashboardDataMatchesRustPath() {
    val fixedNowMs = 1752580800000L
    val (curStart, _) = JalaliCalendarHelper.getUtcJalaliMonthBoundaries(fixedNowMs)
    val (prevStart, prevEnd) = JalaliCalendarHelper.getUtcJalaliPreviousMonthBoundaries(curStart)
    val prevMid = prevStart + (prevEnd - prevStart) / 2
    val accounts = listOf(account(1, "Bank", AccountType.BANK), account(2, "Wallet", AccountType.CASH_WALLET))
    val txs =
      listOf(
        tx(TransactionType.INCOME, 600_000, curStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 100_000, curStart + 2, accountId = 1),
        tx(TransactionType.INCOME, 400_000, prevMid, accountId = 1),
        tx(TransactionType.EXPENSE, 100_000, prevMid + 1, accountId = 1),
        tx(TransactionType.INCOME, 200_000, curStart + 3, accountId = 2),
        tx(TransactionType.EXPENSE, 50_000, curStart + 4, accountId = 2),
      )
    val kotlinResult =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = fixedNowMs,
        includeArchived = false,
      )
    val rustResult =
      io.github.mojri.hesabyar.rust.RustBridge.computeDashboardDataSync(
        io.github.mojri.hesabyar.rust.RustMappers
          .mapTransactions(txs),
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        accountId = null,
        includeArchived = false,
        nowMs = fixedNowMs,
      )!!
    // Top-level aggregates
    assertEquals("currentBalance", kotlinResult.currentBalance, rustResult.currentBalance)
    assertEquals("monthlyIncome", kotlinResult.monthlyIncome, rustResult.monthlyIncome)
    assertEquals("monthlyExpenses", kotlinResult.monthlyExpenses, rustResult.monthlyExpenses)
    assertEquals("accounts count", kotlinResult.accounts.size, rustResult.accounts.size)
    // Per-account balances and monthlyDelta
    for (kAcc in kotlinResult.accounts) {
      val rAcc = rustResult.accounts.first { it.accountId == kAcc.accountId }
      assertEquals("${kAcc.accountName}.balance", kAcc.balance, rAcc.balance)
      assertEquals("${kAcc.accountName}.monthlyIncome", kAcc.monthlyIncome, rAcc.monthlyIncome)
      assertEquals("${kAcc.accountName}.monthlyExpenses", kAcc.monthlyExpenses, rAcc.monthlyExpenses)
      assertEquals("${kAcc.accountName}.monthlyDelta", kAcc.monthlyDelta, rAcc.monthlyDelta, 1e-10)
    }

    // 3. sum(accountBalances) == currentBalance (both paths)
    assertEquals(
      "Kotlin: sum(accountBalances) == currentBalance",
      kotlinResult.currentBalance,
      kotlinResult.accounts.sumOf { it.balance },
    )
    assertEquals(
      "Rust: sum(accountBalances) == currentBalance",
      rustResult.currentBalance,
      rustResult.accounts.sumOf { it.balance },
    )
  }

  // -- Phase 5: NaN / Infinity guards for monthlyDelta -----------------------

  private fun computeDeltaForTxs(
    txs: List<Transaction>,
    accounts: List<AccountEntity>,
    now: Long,
  ): Double {
    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = now,
        includeArchived = false,
      )
    return result.accounts.first().monthlyDelta
  }

  private fun assertDeltaIsValid(
    delta: Double,
    label: String
  ) {
    assertFalse("$label: must not be NaN", delta.isNaN())
    assertFalse("$label: must not be Infinite", delta.isInfinite())
  }

  @Test
  fun monthlyDeltaNeverNanOrInfinity() {
    val fixedNowMs = 1752580800000L
    val (curStart, _) = JalaliCalendarHelper.getUtcJalaliMonthBoundaries(fixedNowMs)
    val (prevStart, prevEnd) = JalaliCalendarHelper.getUtcJalaliPreviousMonthBoundaries(curStart)

    val accounts = listOf(account(1, "Active", AccountType.BANK))

    // Case 1: Previous month has zero activity → delta=0.0
    val txsNoPrev =
      listOf(
        tx(TransactionType.INCOME, 1_000_000, curStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 500_000, curStart + 2, accountId = 1),
      )
    val deltaNoPrev = computeDeltaForTxs(txsNoPrev, accounts, fixedNowMs)
    assertDeltaIsValid(deltaNoPrev, "prevNet=0")
    assertEquals(0.0, deltaNoPrev, 1e-10)

    // Case 2: Previous month net below noise threshold (400 < 1000)
    val prevMid = prevStart + (prevEnd - prevStart) / 2
    val txsBelowThreshold =
      listOf(
        tx(TransactionType.INCOME, 1_000_000, curStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 200_000, curStart + 2, accountId = 1),
        tx(TransactionType.INCOME, 600, prevMid, accountId = 1),
        tx(TransactionType.EXPENSE, 200, prevMid + 1, accountId = 1),
      )
    val deltaBelow = computeDeltaForTxs(txsBelowThreshold, accounts, fixedNowMs)
    assertDeltaIsValid(deltaBelow, "prevNet below threshold")
    assertEquals("delta=0.0 when prevNet below threshold", 0.0, deltaBelow, 1e-10)

    // Case 3: Normal case — verify no absurd values
    val txsNormal =
      listOf(
        tx(TransactionType.INCOME, 1_000_000, curStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 500_000, curStart + 2, accountId = 1),
        tx(TransactionType.INCOME, 200_000, prevStart + 1, accountId = 1),
        tx(TransactionType.EXPENSE, 100_000, prevStart + 2, accountId = 1),
      )
    val deltaNormal = computeDeltaForTxs(txsNormal, accounts, fixedNowMs)
    assertDeltaIsValid(deltaNormal, "normal case")
    assertTrue(
      "delta must be in [-10.0, 10.0], got $deltaNormal",
      deltaNormal in -10.0..10.0,
    )
  }

  @Test
  fun monthlyDeltaExactZeroPrevNetWithCancellingTransactions() {
    val fixedNowMs = 1752580800000L
    val (prevStart, prevEnd) =
      JalaliCalendarHelper.getUtcJalaliPreviousMonthBoundaries(
        JalaliCalendarHelper.getUtcJalaliMonthBoundaries(fixedNowMs).first,
      )
    val prevMid = prevStart + (prevEnd - prevStart) / 2

    val accounts = listOf(account(1, "Active", AccountType.BANK))

    // Previous month: income == expense → prevNet = 0 (exact zero)
    val txs =
      listOf(
        tx(TransactionType.INCOME, 500_000, prevMid, accountId = 1),
        tx(TransactionType.EXPENSE, 500_000, prevMid + 1, accountId = 1),
        tx(TransactionType.INCOME, 1_000_000, fixedNowMs + 1, accountId = 1),
      )

    val delta = computeDeltaForTxs(txs, accounts, fixedNowMs)
    assertDeltaIsValid(delta, "exact zero prevNet with cancelling txs")
    assertEquals("delta=0.0 when prevNet is exact zero from cancelling txs", 0.0, delta, 1e-10)
  }

  // -- AccountId fallback filtering (T2-8) ------------------------------------

  @Test
  fun fallbackAccountIdFiltersToMatchingTransactionsOnly() {
    val now = System.currentTimeMillis()
    val acc1 = account(1, "Bank", AccountType.BANK)
    val acc2 = account(2, "Wallet", AccountType.CASH_WALLET)
    val accounts = listOf(acc1, acc2)

    val txs =
      listOf(
        tx(TransactionType.INCOME, 5_000_000, now, accountId = 1),
        tx(TransactionType.EXPENSE, 1_000_000, now, accountId = 1),
        tx(TransactionType.INCOME, 3_000_000, now, accountId = 2),
        tx(TransactionType.EXPENSE, 500_000, now, accountId = 2),
        tx(TransactionType.TRANSFER, 200_000, now, accountId = 1, destId = 2),
      )

    // accountId=1: only account 1's transactions + transfers where account 1 is source or dest
    val resultAcc1 =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = now,
        includeArchived = false,
        accountId = 1,
      )

    // Account 1: +5M income, -1M expense = net +4_000_000.
    // The transfer matches account 1 as source → it debits the balance
    // (money left the account) and counts as an expense.
    assertEquals("balance for account 1", 3_800_000L, resultAcc1.currentBalance)
    assertEquals("monthlyIncome for account 1", 5_000_000L, resultAcc1.monthlyIncome)
    assertEquals("monthlyExpenses for account 1", 1_200_000L, resultAcc1.monthlyExpenses)
    // Per-account summaries must reflect the transfer's source/destination
    // attribution: account 1's summary counts the transfer out as an expense,
    // while account 2's summary — inside account 1's view — sees only the
    // transfer credit, since account 2's own transactions were filtered out.
    val acc1SummaryInAcc1 = resultAcc1.accounts.first { it.accountId == 1L }
    assertEquals("acc1 summary balance includes transfer out", 3_800_000L, acc1SummaryInAcc1.balance)
    assertEquals("acc1 summary income has no transfer", 5_000_000L, acc1SummaryInAcc1.monthlyIncome)
    assertEquals("acc1 summary expenses include transfer out", 1_200_000L, acc1SummaryInAcc1.monthlyExpenses)
    val acc2SummaryInAcc1 = resultAcc1.accounts.first { it.accountId == 2L }
    assertEquals("acc2 summary inside acc1 view = transfer credit only", 200_000L, acc2SummaryInAcc1.balance)
    assertEquals("acc2 summary income inside acc1 view = transfer in", 200_000L, acc2SummaryInAcc1.monthlyIncome)
    assertEquals("acc2 summary expenses inside acc1 view = none", 0L, acc2SummaryInAcc1.monthlyExpenses)

    // accountId=2: only account 2's transactions + transfers where account 2 is source or dest
    val resultAcc2 =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = now,
        includeArchived = false,
        accountId = 2,
      )

    // Account 2: +3M income, -500k expense = net +2_500_000.
    // The transfer matches account 2 as destination → it credits the balance
    // (money arrived) and counts as income.
    assertEquals("balance for account 2", 2_700_000L, resultAcc2.currentBalance)
    assertEquals("monthlyIncome for account 2", 3_200_000L, resultAcc2.monthlyIncome)
    assertEquals("monthlyExpenses for account 2", 500_000L, resultAcc2.monthlyExpenses)
    // Mirror the per-account checks: account 1's summary inside account 2's
    // view sees only the transfer out, and account 2's own summary includes
    // the transfer in.
    val acc1SummaryInAcc2 = resultAcc2.accounts.first { it.accountId == 1L }
    assertEquals("acc1 summary inside acc2 view = transfer debit only", -200_000L, acc1SummaryInAcc2.balance)
    assertEquals("acc1 summary expenses inside acc2 view = transfer out", 200_000L, acc1SummaryInAcc2.monthlyExpenses)
    val acc2SummaryInAcc2 = resultAcc2.accounts.first { it.accountId == 2L }
    assertEquals("acc2 summary balance includes transfer in", 2_700_000L, acc2SummaryInAcc2.balance)
    assertEquals("acc2 summary income includes transfer in", 3_200_000L, acc2SummaryInAcc2.monthlyIncome)
  }

  // -- Selected account + transfer: Rust and Kotlin fallback must agree -------

  @Test
  fun rustAndKotlinFallbackMatchForSelectedAccountWithTransfer() {
    // Fixed timestamp: 2025-07-15 12:00:00 UTC — deterministic Jalali month.
    val fixedNowMs = 1752580800000L
    val accounts = listOf(account(1, "Bank", AccountType.BANK), account(2, "Wallet", AccountType.CASH_WALLET))
    val txs =
      listOf(
        tx(TransactionType.INCOME, 1_000_000, fixedNowMs, accountId = 1),
        tx(TransactionType.EXPENSE, 200_000, fixedNowMs, accountId = 1),
        tx(TransactionType.TRANSFER, 500_000, fixedNowMs, accountId = 1, destId = 2),
      )

    val kotlinResult =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = fixedNowMs,
        includeArchived = false,
        accountId = 1,
      )

    val rustResult =
      io.github.mojri.hesabyar.rust.RustBridge.computeDashboardDataSync(
        io.github.mojri.hesabyar.rust.RustMappers
          .mapTransactions(txs),
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        accountId = 1,
        includeArchived = false,
        nowMs = fixedNowMs,
      )!!

    // Account 1: +1M income, -200k expense, -500k transfer out → 300k net
    assertEquals("currentBalance", kotlinResult.currentBalance, rustResult.currentBalance)
    assertEquals("monthlyIncome", kotlinResult.monthlyIncome, rustResult.monthlyIncome)
    assertEquals("monthlyExpenses", kotlinResult.monthlyExpenses, rustResult.monthlyExpenses)
    assertEquals("kotlin currentBalance for account 1", 300_000L, kotlinResult.currentBalance)
    assertEquals("kotlin monthlyIncome for account 1", 1_000_000L, kotlinResult.monthlyIncome)
    assertEquals("kotlin monthlyExpenses for account 1", 700_000L, kotlinResult.monthlyExpenses)
  }

  @Test
  fun fallbackNullAccountIdIncludesAllTransactions() {
    val now = System.currentTimeMillis()
    val acc1 = account(1, "Bank", AccountType.BANK)
    val acc2 = account(2, "Wallet", AccountType.CASH_WALLET)
    val accounts = listOf(acc1, acc2)

    val txs =
      listOf(
        tx(TransactionType.INCOME, 5_000_000, now, accountId = 1),
        tx(TransactionType.EXPENSE, 1_000_000, now, accountId = 1),
        tx(TransactionType.INCOME, 3_000_000, now, accountId = 2),
      )

    val result =
      GetDashboardDataUseCase.computeFallbackDashboardData(
        txs,
        emptyList(),
        emptyList(),
        emptyList(),
        accounts,
        now = now,
        includeArchived = false,
        accountId = null,
      )

    // null accountId → all accounts: total income=8M, expense=1M, balance=7M
    assertEquals(7_000_000L, result.currentBalance)
    assertEquals(8_000_000L, result.monthlyIncome)
    assertEquals(1_000_000L, result.monthlyExpenses)
  }
}
