package io.github.mojri.hesabyar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.Person
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LoanRepaymentPersonSyncTest {
  private lateinit var database: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    // Mirror the production invariant that the default account (id=1) exists.
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

  @Test
  fun addPaymentToLoanStampsPersonIdSoRenameKeepsRepaymentInSync() =
    runTest {
      val repo = createRepository()
      repo.insertCategory(
        Category(name = "Loans", key = "Loans", icon = "HistoryEdu", color = 0xFF4CAF50L, type = CategoryType.BOTH)
      )

      // A person the loan is linked to.
      val person = repo.upsertPerson(Person(name = "Ali", normalizedName = "ali"))

      // Loan created with the person link (mirrors how the UI links a loan to a person).
      val loanId =
        repo.insertLoan(
          Loan(
            personName = person.name,
            personId = person.id,
            type = LoanType.DEBTOR,
            originalAmount = 5_000L,
            remainingAmount = 5_000L,
            description = "test"
          )
        )

      // Record a repayment. The repayment transaction must inherit the loan's personId.
      assertTrue(repo.addPaymentToLoan(loanId, 2_000L, "first installment", null))

      val repaymentTx = database.transactionDao().getAllTransactionsBlocking().single()
      assertEquals(person.id, repaymentTx.personId)
      assertEquals("Ali", repaymentTx.personName)

      // Rename the person. renamePerson runs syncTransactionPersonNames, which
      // must update the denormalized personName on the linked repayment.
      assertTrue(repo.renamePerson(person.id, "Ali Reza"))

      val updatedTx = database.transactionDao().getAllTransactionsBlocking().single()
      assertEquals(person.id, updatedTx.personId)
      assertEquals("Ali Reza", updatedTx.personName)
    }
}
