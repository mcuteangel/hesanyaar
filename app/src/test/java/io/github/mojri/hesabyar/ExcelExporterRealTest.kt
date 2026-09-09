package io.github.mojri.hesabyar

import android.content.Context
import io.github.mojri.hesabyar.data.ExcelExporter
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import io.github.mojri.hesabyar.rust.RustBridge
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the real [ExcelExporter.export] against the loaded native core:
 * it must build every sheet, hand a [WorkbookData] to Rust, and return a
 * byte array plus correct summary counts. The unavailable-Rust fallback
 * (throws rather than producing a corrupt workbook) is covered by the
 * instrumentation suite; with [HesabyarApp.setRustInitializedForTesting] it
 * would be exercisable here too, but this class locks in the native path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@Category(RustTest::class)
class ExcelExporterRealTest {
  private lateinit var context: Context

  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    HesabyarApp.setRustInitializedForTesting(true)
  }

  @After
  fun tearDown() {
    HesabyarApp.setRustInitializedForTesting(false)
  }

  private fun makeData(): Triple<List<Transaction>, List<Loan>, List<Installment>> {
    val txs =
      listOf(
        Transaction(type = TransactionType.INCOME, categoryId = 1L, amount = 5_000_000L, description = "salary"),
        Transaction(type = TransactionType.EXPENSE, categoryId = 2L, amount = 1_000_000L, description = "food"),
        Transaction(type = TransactionType.EXPENSE, categoryId = 1L, amount = 500_000L, description = "snack")
      )
    val loans =
      listOf(
        Loan(
          personName = "علی",
          personId = null,
          type = LoanType.DEBTOR,
          originalAmount = 5_000_000L,
          remainingAmount = 3_000_000L,
          description = "l"
        ),
        Loan(
          personName = "رضا",
          personId = null,
          type = LoanType.CREDITOR,
          originalAmount = 2_000_000L,
          remainingAmount = 2_000_000L,
          description = "c"
        )
      )
    val insts =
      listOf(
        Installment(title = "قسط۱", amount = 1_000_000L, dueDate = System.currentTimeMillis(), isPaid = true),
        Installment(title = "قسط۲", amount = 1_000_000L, dueDate = System.currentTimeMillis(), isPaid = false)
      )
    return Triple(txs, loans, insts)
  }

  @Test
  fun exportReturnsByteArrayWithCorrectCountsWhenRustAvailable() =
    runTest {
      assertTrue(RustBridge.isAvailable)
      val (txs, loans, insts) = makeData()
      val result = ExcelExporter(context).export(txs, loans, insts, emptyList())

      // Counts reflect exactly the input collections.
      assertEquals(3, result.transactionCount)
      assertEquals(1, result.incomeCount)
      assertEquals(2, result.expenseCount)
      assertEquals(2, result.loanCount)
      assertEquals(2, result.installmentCount)

      // A real .xlsx is a ZIP archive beginning with the "PK" magic bytes.
      assertTrue("expected non-empty xlsx bytes", result.bytes.isNotEmpty())
      assertEquals('P'.code.toByte(), result.bytes[0])
      assertEquals('K'.code.toByte(), result.bytes[1])
    }

  @Test
  fun exportFilenameFollowsTheDocumentedPattern() =
    runTest {
      val (txs, loans, insts) = makeData()
      val result = ExcelExporter(context).export(txs, loans, insts, emptyList())
      assertTrue(
        "filename was '${result.filename}'",
        result.filename.matches(Regex("^hesabyar_report_\\d{8}_\\d{6}\\.xlsx$"))
      )
    }

  @Test
  fun exportHandlesEmptyInput() =
    runTest {
      val result = ExcelExporter(context).export(emptyList(), emptyList(), emptyList(), emptyList())
      assertEquals(0, result.transactionCount)
      assertEquals(0, result.loanCount)
      assertEquals(0, result.installmentCount)
      assertTrue(result.bytes.isNotEmpty())
    }

  @Test
  fun exportDelegatesToNativeCoreWhenAvailable() =
    runTest {
      // generateExcel() returns null only when the native core is unavailable
      // (see RustBridge). In unit tests the library is always loaded, so we
      // verify the contract indirectly: a successful export yields a valid,
      // non-empty workbook. The null/throw branch is exercised by the
      // instrumentation test that runs without the native library.
      val (txs, loans, insts) = makeData()
      val result = ExcelExporter(context).export(txs, loans, insts, emptyList())
      assertTrue(result.bytes.isNotEmpty())
    }
}
