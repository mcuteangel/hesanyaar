package io.github.mojri.hesabyar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.InstallmentDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rollback coverage for [io.github.mojri.hesabyar.data.BankLoanDelegate]'s
 * transactional operations. A failure in any statement inside
 * `database.withTransaction` must leave both the bank_loans and installments
 * tables untouched — a half-applied cascade would corrupt the ledger.
 *
 * Separate from RepositoryLogicTest to stay under the detekt class-size
 * threshold; the failing-DAO decorator mirrors PersonRepositoryTest.RacePersonDao.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class BankLoanDelegateRollbackTest {
  private lateinit var database: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    database.accountDao().insertAllBlocking(listOf(AccountEntity.DEFAULT_ACCOUNT))
  }

  @After
  fun tearDown() {
    database.close()
  }

  private fun createRepository(installmentDao: InstallmentDao = database.installmentDao()): HesabyarRepository =
    HesabyarRepository(
      database.transactionDao(),
      database.loanDao(),
      installmentDao,
      database.paymentHistoryDao(),
      database.categoryDao(),
      database.bankLoanDao(),
      database.accountDao(),
      database.personDao(),
      database
    )

  private fun testBankLoan() =
    BankLoan(
      bankName = "بانک ملت",
      loanName = "وام خودرو",
      receivedAmount = 100_000_000L,
      monthlyInstallmentAmount = 10_000_000L,
      numberOfInstallments = 2,
      totalRepayableAmount = 120_000_000L,
      totalInterest = 20_000_000L,
      startDate = 1_700_000_000_000L,
      description = "test"
    )

  private fun installment(bankLoanId: Long = 0L) =
    Installment(
      title = "قسط",
      amount = 10_000_000L,
      dueDate = 1_700_000_000_000L,
      bankLoanId = bankLoanId
    )

  /** Decorator that can force specific installment statements to throw. */
  private class FailingInstallmentDao(
    private val delegate: InstallmentDao
  ) : InstallmentDao by delegate {
    var failOnInsert = false
    var failOnDeleteByBankLoanId = false

    override suspend fun insertInstallment(installment: Installment): Long {
      if (failOnInsert) throw IllegalStateException("forced insert failure")
      return delegate.insertInstallment(installment)
    }

    override suspend fun deleteInstallmentsByBankLoanId(bankLoanId: Long) {
      if (failOnDeleteByBankLoanId) throw IllegalStateException("forced delete failure")
      delegate.deleteInstallmentsByBankLoanId(bankLoanId)
    }
  }

  @Test
  fun addbankloanwithinstallmentsRollsBackWhenInstallmentInsertFails() =
    runTest {
      val failingDao = FailingInstallmentDao(database.installmentDao()).apply { failOnInsert = true }
      val repo = createRepository(failingDao)

      val threw =
        try {
          repo.addBankLoanWithInstallments(testBankLoan(), listOf(installment(), installment()))
          false
        } catch (expected: IllegalStateException) {
          true
        }

      assertTrue("forced insert failure must propagate", threw)
      assertEquals(
        "bank loan insert must roll back with the failed installment",
        0,
        database.bankLoanDao().getAllBankLoansBlocking().size
      )
      assertEquals(
        "no installment may survive the failed insert",
        0,
        database.installmentDao().getAllInstallmentsBlocking().size
      )
    }

  @Test
  fun deletebankloanRollsBackWhenInstallmentDeleteFails() =
    runTest {
      // Seed with the real DAO so the cascade has something to undo.
      val repo = createRepository()
      val loanId =
        repo.addBankLoanWithInstallments(
          testBankLoan(),
          listOf(installment(), installment())
        )
      assertEquals(2, database.installmentDao().getAllInstallmentsBlocking().size)

      val failingRepo =
        createRepository(
          FailingInstallmentDao(database.installmentDao()).apply { failOnDeleteByBankLoanId = true }
        )
      val storedLoan = database.bankLoanDao().getAllBankLoansBlocking().single()

      val threw =
        try {
          failingRepo.deleteBankLoan(storedLoan)
          false
        } catch (expected: IllegalStateException) {
          true
        }

      assertTrue("forced delete failure must propagate", threw)
      assertEquals(
        "bank loan must survive the failed cascade",
        1,
        database.bankLoanDao().getAllBankLoansBlocking().size
      )
      assertEquals(
        "installments must survive the failed cascade",
        2,
        database.installmentDao().getAllInstallmentsBlocking().size
      )
      assertTrue(loanId > 0)
    }
}
