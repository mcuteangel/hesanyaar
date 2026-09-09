package io.github.mojri.hesabyar.rust
import io.github.mojri.hesabyar.HesabyarApp
import io.github.mojri.hesabyar.RustIsolationRule
import io.github.mojri.hesabyar.RustTest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Exercises [RustBridge] against the real native core (the unit-test JVM loads
 * the hesabyar_core library, so [RustBridge.isAvailable] is true here).
 *
 * These tests raise coverage of the bridge's delegation paths and lock in that
 * the native-backed results are shaped and bounded as the app expects.
 *
 * The *unavailable* fallback branches (safe sentinels returned when the native
 * library fails to load) are not exercised here — the @Before forces the Rust
 * availability decision on. They can now be exercised from this JVM suite via
 * [HesabyarApp.setRustInitializedForTesting] (see [RustIsolationRule]), and are
 * additionally covered by an instrumentation test that runs without the native
 * library present.
 */
@Category(RustTest::class)
class RustBridgeTest {
  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun setUp() {
    HesabyarApp.setRustInitializedForTesting(true)
  }

  @After
  fun tearDown() {
    HesabyarApp.setRustInitializedForTesting(false)
  }

  @Test
  fun isavailableIsTrueWhenTheNativeCoreIsLoaded() {
    assertTrue(RustBridge.isAvailable)
  }

  // ---------------------------------------------------------------------------
  // Calendar
  // ---------------------------------------------------------------------------

  @Test
  fun calendarSyncCallsDelegateToTheNativeCore() {
    assertTrue(RustBridge.gregorianToJalaliSync(1_700_000_000_000L) != 0L)
    assertTrue(RustBridge.jalaliToGregorianSync(1403, 1, 1) != 0L)
    val days = RustBridge.getJalaliDaysInMonthSync(1403, 1)
    assertTrue(days in 29..31)
    // Leap-year detection is deterministic across calls.
    val leap = RustBridge.isJalaliLeapYearSync(1403)
    assertEquals(leap, RustBridge.isJalaliLeapYearSync(1403))
  }

  // ---------------------------------------------------------------------------
  // Currency
  // ---------------------------------------------------------------------------

  @Test
  fun currencySyncCallsDelegateToTheNativeCore() {
    // The numeric substring is produced by the Rust core (currency.rs
    // format_number), which emits ASCII digits ('0'..'9') and an ASCII ','
    // thousands separator unconditionally — it performs no locale-aware
    // formatting. The output is therefore independent of the JVM/OS locale this
    // test runs under, so contains1000000 is not locale-fragile. (If the
    // core ever switched to Persian/Arabic digits, these assertions SHOULD fail
    // as a regression signal — do not loosen them to accept alternate digits.)
    assertTrue(RustBridge.formatCurrencySync(1_000_000L, CurrencyUnit.RIAL).contains("1,000,000"))
    // 100 Toman = 1,000 Rial.
    assertEquals(1_000L, RustBridge.toRialSync(100L, CurrencyUnit.TOMAN))
    assertEquals(1_000_000L, RustBridge.fromRialSync(1_000_000L, CurrencyUnit.RIAL))
    assertTrue(RustBridge.formatNumberSync(1_234_567L).contains("1,234,567"))
  }

  // ---------------------------------------------------------------------------
  // Parser
  // ---------------------------------------------------------------------------

  @Test
  fun parserSyncCallsDelegateToTheNativeCore() {
    val parsed = RustBridge.parseSentenceOfflineSync("خرید نان 5000")
    assertNotNull(parsed)
    assertEquals(TransactionType.EXPENSE, parsed!!.txType)
    assertEquals(50_000L, parsed.amount)

    assertTrue(RustBridge.inferExpenseCategorySync("قبض برق").category.isNotEmpty())
    assertFalse(RustBridge.containsMoneySync("سلام"))
    assertTrue(RustBridge.normalizeMoneyTextSync("۱۲۳").isNotEmpty())
    // parsePersianAmount returns the parsed value in Toman (no Rial conversion).
    assertEquals(123L, RustBridge.parsePersianAmountSync("۱۲۳ تومان"))
    assertTrue(RustBridge.preprocessPersianTextSync("متن").isNotEmpty())
  }

  // ---------------------------------------------------------------------------
  // AI validation
  // ---------------------------------------------------------------------------

  @Test
  fun validateaiadviceDelegatesToTheNativeCoreWithoutThrowing() =
    runTest {
      val result = RustBridge.validateAiAdvice("پیشنهاد ساختگی برای صرفه جویی")
      assertNotNull(result)
      assertTrue(result.sanitizedText is String)
    }

  @Test
  fun parseaitransactionjsonsyncParsesAValidTransaction() {
    val parsed = RustBridge.parseAiTransactionJsonSync("""{"type":"EXPENSE","amount":1000}""")
    assertNotNull(parsed)
    assertEquals(TransactionType.EXPENSE, parsed!!.result.txType)
  }

  // ---------------------------------------------------------------------------
  // Validation (boolean)
  // ---------------------------------------------------------------------------

  @Test
  fun booleanValidatorsAcceptValidEntities() {
    val txn =
      Transaction(
        id = 1L,
        txType = TransactionType.EXPENSE,
        categoryId = 1L,
        amount = 5_000_000L,
        description = "test",
        personName = null,
        personId = null,
        date = 1_700_000_000_000L,
        dueDate = null,
        installmentId = null,
        accountId = 1L,
        destinationAccountId = null
      )
    val loan =
      Loan(
        id = 1L,
        personName = "علی",
        personId = null,
        loanType = "DEBTOR",
        originalAmount = 1_000_000L,
        remainingAmount = 1_000_000L,
        description = "test",
        date = 1_700_000_000_000L,
        isSettled = false
      )
    val installment =
      Installment(
        id = 1L,
        title = "قسط",
        amount = 500_000L,
        dueDate = 1_700_000_000_000L,
        isPaid = false,
        reminderEnabled = true,
        notes = "",
        bankLoanId = null
      )
    assertTrue(RustBridge.validateTransactionSync(txn))
    assertTrue(RustBridge.validateLoanSync(loan))
    assertTrue(RustBridge.validateInstallmentSync(installment))
  }

  // ---------------------------------------------------------------------------
  // Budget / analytics / search / backup
  // ---------------------------------------------------------------------------

  @Test
  fun budgetAndForecastSyncCallsProduceAdviceText() {
    assertTrue(RustBridge.getOfflineBudgetAdviceSync(emptyList(), emptyList()).isNotEmpty())
    assertTrue(RustBridge.getOfflineForecastSync(emptyList(), emptyList(), emptyList()).isNotEmpty())
  }

  @Test
  fun budgetNumericSyncCallsComputeExpectedValues() {
    assertEquals(0.0, RustBridge.calculateDebtToIncomeRatioSync(emptyList(), emptyList(), 1_000_000L), 0.0)
    assertEquals(10, RustBridge.predictTimeToGoalSync(0L, 100_000L, 1_000_000L))
    assertEquals(0, RustBridge.calculateFinancialHealthScoreSync(emptyList(), emptyList(), emptyList(), emptyList()))
  }

  @Test
  fun analyticsAndDashboardSyncCallsReturnDataStructures() {
    assertNotNull(
      RustBridge.computeAnalyticsSync(emptyList(), emptyList(), emptyList(), emptyList(), accounts = emptyList())
    )
    assertNotNull(RustBridge.computeDashboardDataSync(emptyList(), emptyList(), emptyList()))
  }

  @Test
  fun searchtransactionssyncReturnsAnEmptyResultForNoData() {
    val query =
      SearchQuery(
        text = "",
        minAmount = 0L,
        maxAmount = 0L,
        startDate = 0L,
        endDate = 0L,
        categoryId = 0L,
        txType = TransactionType.EXPENSE,
        useTypeFilter = false
      )
    val response = RustBridge.searchTransactionsSync(emptyList(), query)
    assertTrue(response.results.isEmpty())
    assertEquals(0L, response.totalCount)
    assertEquals(0L, response.totalAmount)
  }

  // ---------------------------------------------------------------------------
  // Exception rethrowing
  // ---------------------------------------------------------------------------

  @Test
  fun rustcallsyncRethrowsRuntimeexceptionnullpointerexception() {
    assertThrows(NullPointerException::class.java) {
      RustBridge.rustCallSync("fallback") {
        throw NullPointerException("boom")
      }
    }
  }

  @Test
  fun rustcallsyncRethrowsRuntimeexceptionillegalstateexception() {
    assertThrows(IllegalStateException::class.java) {
      RustBridge.rustCallSync("fallback") {
        throw IllegalStateException("boom")
      }
    }
  }

  @Test
  fun rustcallsyncRethrowsInterruptedexceptionAndRestoresInterruptedFlag() {
    try {
      assertThrows(InterruptedException::class.java) {
        RustBridge.rustCallSync("fallback") {
          throw InterruptedException("interrupted")
        }
      }
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun rustcallsyncReturnsFallbackForNoncriticalExceptions() {
    val result =
      RustBridge.rustCallSync("fallback") {
        throw java.io.IOException("transient network failure")
      }
    assertEquals("fallback", result)
  }

  // ---------------------------------------------------------------------------
  // Backup
  // ---------------------------------------------------------------------------

  @Test
  fun backupSyncCallsBehaveOnValidAndInvalidInput() {
    // Invalid JSON is handled gracefully (null) rather than throwing.
    assertNull(RustBridge.parseBackupJsonSync("this is not json"))
    val result = RustBridge.validateBackupPayloadSync(emptyBackupPayload())
    assertNotNull(result)
  }

  @Test
  fun validatebackupCompletesWithoutThrowing() =
    runTest {
      RustBridge.validateBackup(emptyBackupPayload())
    }

  // ---------------------------------------------------------------------------
  // Checksum / Excel
  // ---------------------------------------------------------------------------

  @Test
  fun checksumAndExcelSyncCallsDelegateToTheNativeCore() {
    assertTrue(RustBridge.computeChecksumSync(byteArrayOf(1, 2, 3)).isNotEmpty())
    assertFalse(RustBridge.verifyChecksumSync(byteArrayOf(1), "abc"))
    assertNotNull(RustBridge.generateExcel(WorkbookData(emptyList())))
  }

  private fun emptyBackupPayload(): BackupPayload =
    BackupPayload(
      version = 1,
      timestamp = 0L,
      appVersion = "test",
      transactions = emptyList(),
      loans = emptyList(),
      installments = emptyList(),
      bankLoans = emptyList(),
      paymentHistories = emptyList(),
      categories = emptyList(),
      accounts = emptyList(),
      persons = emptyList()
    )
}
