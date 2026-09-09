package io.github.mojri.hesabyar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-database coverage for [io.github.mojri.hesabyar.data.InstallmentDelegate]
 * .updateInstallment: the paid transition records exactly one linked expense,
 * toggling never double-counts money, and a missing Installments category
 * aborts the whole update (rollback leaves the row unchanged).
 *
 * Split from RepositoryLogicTest to stay under the detekt class-size threshold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class InstallmentUpdateTransactionTest {
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

  private fun createRepository(): HesabyarRepository =
    HesabyarRepository(
      database.transactionDao(),
      database.loanDao(),
      database.installmentDao(),
      database.paymentHistoryDao(),
      database.categoryDao(),
      database.bankLoanDao(),
      database.accountDao(),
      database.personDao(),
      database
    )

  private suspend fun seedInstallmentsCategory(repo: HesabyarRepository) {
    repo.insertCategory(
      Category(
        name = "Installments",
        key = "Installments",
        icon = "CreditCard",
        color = 0xFF4CAF50L,
        type = CategoryType.EXPENSE
      )
    )
  }

  @Test
  fun updateinstallmentPaidCreatesExpenseTransaction() =
    runTest {
      val repo = createRepository()
      seedInstallmentsCategory(repo)
      val installmentId =
        repo.insertInstallment(
          Installment(title = "Car", amount = 2_000_000L, dueDate = 1_700_000_000_000L, isPaid = false)
        )
      val stored = database.installmentDao().getInstallmentById(installmentId)!!

      repo.updateInstallment(stored.copy(isPaid = true))

      val txs = database.transactionDao().getAllTransactionsBlocking()
      assertEquals("paid transition must record exactly one expense", 1, txs.size)
      val tx = txs.single()
      assertEquals(TransactionType.EXPENSE, tx.type)
      assertEquals(2_000_000L, tx.amount)
      assertEquals(installmentId, tx.installmentId)

      // Toggling paid → paid again must not insert a second expense.
      repo.updateInstallment(stored.copy(isPaid = true))
      assertEquals(
        "re-paying a paid installment must not double-count",
        1,
        database.transactionDao().getAllTransactionsBlocking().size
      )

      // Toggling paid → unpaid reverses the expense; unpaid → paid re-records
      // exactly one fresh expense, so the cycle cannot double-count money.
      repo.updateInstallment(stored.copy(isPaid = false))
      assertEquals(
        "unpay must reverse the recorded expense",
        0,
        database.transactionDao().getAllTransactionsBlocking().size
      )
      repo.updateInstallment(stored.copy(isPaid = true))
      assertEquals(
        "re-pay after unpay must record exactly one expense",
        1,
        database.transactionDao().getAllTransactionsBlocking().size
      )
    }

  @Test
  fun updateinstallmentWithoutCategoryThrowsAndRollsBack() =
    runTest {
      val repo = createRepository()
      // No "Installments" category seeded on purpose.
      val installmentId =
        repo.insertInstallment(
          Installment(title = "Car", amount = 1_000_000L, dueDate = 1_700_000_000_000L, isPaid = false)
        )
      val stored = database.installmentDao().getInstallmentById(installmentId)!!

      try {
        repo.updateInstallment(stored.copy(isPaid = true))
        fail("Missing Installments category must abort the paid transition")
      } catch (expected: IllegalStateException) {
      }

      // The whole update rolled back: the row keeps isPaid = false and no
      // expense was recorded.
      val unchanged = database.installmentDao().getInstallmentById(installmentId)!!
      assertFalse("rollback must keep the row unpaid", unchanged.isPaid)
      assertEquals(0, database.transactionDao().getAllTransactionsBlocking().size)
    }
}
