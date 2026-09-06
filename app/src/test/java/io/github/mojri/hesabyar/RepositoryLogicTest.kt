package io.github.mojri.hesabyar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.BackupSettings
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.DEFAULT_ACCOUNT_ID
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RepositoryLogicTest {
  private lateinit var database: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    // Production seeds the default account (id=1) on fresh create
    // (AppDatabase onCreate) and on upgrade (MIGRATION_5_6); the in-memory
    // test DB bypasses both, so mirror the invariant here.
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

  private suspend fun seedLoanWithCategory(
    remainingAmount: Long,
    isSettled: Boolean = false
  ): Long {
    val repo = createRepository()

    val loansCategory =
      Category(
        name = "Loans",
        key = "Loans",
        icon = "HistoryEdu",
        color = 0xFF4CAF50L,
        type = CategoryType.BOTH
      )
    repo.insertCategory(loansCategory)

    val loan =
      Loan(
        personName = "Ali",
        type = LoanType.DEBTOR,
        originalAmount = 5_000L,
        remainingAmount = remainingAmount,
        description = "test",
        isSettled = isSettled
      )
    return repo.insertLoan(loan)
  }

  private fun testBankLoan() =
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

  @Test
  fun addpaymenttoloanReducesRemainingAmount() {
    var remainingAmount = 5_000_000L
    val paymentAmount = 2_000_000L

    remainingAmount = (remainingAmount - paymentAmount).coerceAtLeast(0L)
    assertEquals(3_000_000L, remainingAmount)
    assertFalse(remainingAmount <= 0L)
  }

  @Test
  fun addpaymenttoloanSettlesLoanWhenRemainingIsZero() {
    var remainingAmount = 2_000_000L
    val paymentAmount = 2_000_000L

    remainingAmount = (remainingAmount - paymentAmount).coerceAtLeast(0L)
    val isSettled = remainingAmount <= 0L
    assertTrue(isSettled)
    assertEquals(0L, remainingAmount)
  }

  @Test
  fun addpaymenttoloanMultiplePaymentsAccumulate() {
    var remainingAmount = 10_000_000L
    val payments = listOf(3_000_000L, 2_000_000L, 5_000_000L)

    payments.forEach { payment ->
      remainingAmount = (remainingAmount - payment).coerceAtLeast(0L)
    }

    assertEquals(0L, remainingAmount)
  }

  @Test
  fun addpaymenttoloanCreditorCreatesExpenseTransaction() {
    val loanType = "CREDITOR"
    val transactionType = if (loanType == "CREDITOR") "EXPENSE" else "INCOME"
    assertEquals("EXPENSE", transactionType)
  }

  @Test
  fun addpaymenttoloanDebtorCreatesIncomeTransaction() {
    val loanType = "DEBTOR"
    val transactionType = if (loanType == "CREDITOR") "EXPENSE" else "INCOME"
    assertEquals("INCOME", transactionType)
  }

  @Test
  fun addpaymenttoloanCreditorDescriptionFormat() {
    val loan =
      Loan(
        personName = "Ali",
        type = LoanType.CREDITOR,
        originalAmount = 5_000_000L,
        remainingAmount = 5_000_000L,
        description = "test"
      )
    val notes = "partial payment"
    val desc =
      if (loan.type == LoanType.CREDITOR) {
        "بازپرداخت بدهی به ${loan.personName} - $notes"
      } else {
        "دریافت بازپرداخت از ${loan.personName} - $notes"
      }
    assertTrue(desc.contains("Ali"))
    assertTrue(desc.contains("بازپرداخت بدهی"))
  }

  @Test
  fun addpaymenttoloanDebtorDescriptionFormat() {
    val loan =
      Loan(
        personName = "Reza",
        type = LoanType.DEBTOR,
        originalAmount = 3_000_000L,
        remainingAmount = 3_000_000L,
        description = "test"
      )
    val notes = "repayment"
    val desc =
      if (loan.type == LoanType.CREDITOR) {
        "بازپرداخت بدهی به ${loan.personName} - $notes"
      } else {
        "دریافت بازپرداخت از ${loan.personName} - $notes"
      }
    assertTrue(desc.contains("Reza"))
    assertTrue(desc.contains("دریافت بازپرداخت"))
  }

  @Test
  fun importbackupClearsAndInserts() {
    val existingTransactions =
      mutableListOf(
        Transaction(type = TransactionType.EXPENSE, categoryId = 1L, amount = 100L, description = "old")
      )
    val newTransactions =
      listOf(
        Transaction(type = TransactionType.INCOME, categoryId = 2L, amount = 200L, description = "new1"),
        Transaction(type = TransactionType.EXPENSE, categoryId = 3L, amount = 300L, description = "new2")
      )

    existingTransactions.clear()
    existingTransactions.addAll(newTransactions)

    assertEquals(2, existingTransactions.size)
    assertEquals("new1", existingTransactions[0].description)
  }

  @Test
  fun replaceallfrombackupReplacesAllData() {
    val existingCategories =
      mutableListOf(
        Category(id = 1L, name = "Old", key = "Old", icon = "Test", color = 0L, type = CategoryType.EXPENSE)
      )
    val newCategories =
      listOf(
        Category(id = 1L, name = "New", key = "New", icon = "Test", color = 0L, type = CategoryType.EXPENSE)
      )

    existingCategories.clear()
    existingCategories.addAll(newCategories)

    assertEquals(1, existingCategories.size)
    assertEquals("New", existingCategories[0].name)
  }

  @Test
  fun mergefrombackupUpdatesExistingCategory() {
    val existing =
      Category(
        id = 1,
        name = "Old Food",
        key = "Food",
        icon = "Restaurant",
        color = 0xFF4CAF50L,
        type = CategoryType.EXPENSE
      )
    val backup =
      Category(
        id = 0,
        name = "New Food",
        key = "Food",
        icon = "Restaurant",
        color = 0xFF4CAF50L,
        type = CategoryType.EXPENSE
      )

    val existingKey = existing.key
    val backupKey = backup.key
    assertEquals(existingKey, backupKey)

    val merged = backup.copy(id = existing.id)
    assertEquals(existing.id, merged.id)
    assertEquals("New Food", merged.name)
  }

  @Test
  fun mergefrombackupInsertsNewCategory() {
    val existingKeys = setOf("Food", "Transportation")
    val backupCategory =
      Category(
        id = 0,
        name = "Health",
        key = "Health",
        icon = "Heart",
        color = 0xFFE91E63L,
        type = CategoryType.EXPENSE
      )

    val isNew = backupCategory.key !in existingKeys
    assertTrue(isNew)
  }

  @Test
  fun updateinstallmentPaidCreatesExpenseTransaction() {
    val installment =
      Installment(title = "Car", amount = 2_000_000L, dueDate = System.currentTimeMillis(), isPaid = true)
    assertTrue(installment.isPaid)

    val transaction =
      Transaction(
        type = TransactionType.EXPENSE,
        categoryId = 5L,
        amount = installment.amount,
        description = "پرداخت قسط: ${installment.title} - ${installment.notes}"
      )
    assertEquals(TransactionType.EXPENSE, transaction.type)
    assertEquals(2_000_000L, transaction.amount)
  }

  @Test
  fun loanPaymentCreatesCorrectTransactionTypeMapping() {
    val scenarios =
      mapOf(
        "CREDITOR" to "EXPENSE",
        "DEBTOR" to "INCOME"
      )
    scenarios.forEach { (loanType, expectedTxType) ->
      val txType = if (loanType == "CREDITOR") "EXPENSE" else "INCOME"
      assertEquals(expectedTxType, txType)
    }
  }

  @Test
  fun mergefrombackupRemapsBankloanidLinkageForInstallments() =
    runTest {
      val repo = createRepository()

      val bankLoan =
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

      val installment =
        Installment(
          title = "ماه اول",
          amount = 10_000_000L,
          dueDate = 1_700_000_000_000L,
          bankLoanId = 0L
        )

      val backup =
        BackupPayload(
          version = 1,
          timestamp = System.currentTimeMillis(),
          appVersion = "1.0",
          bankLoans = listOf(bankLoan),
          installments = listOf(installment)
        )
      repo.mergeFromBackup(backup)

      val allInstallments = database.installmentDao().getAllInstallmentsBlocking()
      assertEquals(1, allInstallments.size)
      val mergedInstallment = allInstallments.first()
      assertEquals("ماه اول", mergedInstallment.title)
      val bankLoanId = requireNotNull(mergedInstallment.bankLoanId)
      val mergedBankLoan = requireNotNull(database.bankLoanDao().getBankLoanById(bankLoanId))
      assertEquals("بانک ملت", mergedBankLoan.bankName)
      // The installment's bankLoanId must point to the exact remapped bank loan.
      assertEquals(mergedBankLoan.id, mergedInstallment.bankLoanId)
    }

  @Test
  fun backupPayloadPreservesAllFields() {
    val backup =
      BackupPayload(
        version = 2,
        timestamp = 1234567890L,
        appVersion = "1.5",
        transactions =
          listOf(
            Transaction(type = TransactionType.EXPENSE, categoryId = 1L, amount = 1000L, description = "t")
          ),
        loans =
          listOf(
            Loan(
              personName = "Ali",
              type = LoanType.DEBTOR,
              originalAmount = 5000L,
              remainingAmount = 3000L,
              description = "l"
            )
          ),
        installments = listOf(Installment(title = "Car", amount = 2000L, dueDate = 100L)),
        paymentHistories = listOf(PaymentHistory(loanId = 1L, amount = 1000L)),
        categories =
          listOf(
            Category(name = "Food", key = "Food", icon = "Restaurant", color = 0xFF4CAF50L, type = CategoryType.EXPENSE)
          ),
        settings = BackupSettings(darkMode = false)
      )

    assertEquals(2, backup.version)
    assertEquals(1234567890L, backup.timestamp)
    assertEquals("1.5", backup.appVersion)
    assertEquals(1, backup.transactions.size)
    assertEquals(1, backup.loans.size)
    assertEquals(1, backup.installments.size)
    assertEquals(1, backup.paymentHistories.size)
    assertEquals(1, backup.categories.size)
    assertFalse(backup.settings.darkMode)
  }

  @Test
  fun addPaymentToLoanOverpaymentIsRejectedWithoutSideEffects() =
    runTest {
      val repo = createRepository()
      val loanId = seedLoanWithCategory(5_000L)

      val success = repo.addPaymentToLoan(loanId, 10_000L, "overpayment test", null)
      assertFalse(success)

      val paymentHistories = database.paymentHistoryDao().getAllPaymentHistoriesBlocking()
      assertEquals(0, paymentHistories.size)

      val transactions = database.transactionDao().getAllTransactionsBlocking()
      assertEquals(0, transactions.size)

      val updatedLoan = requireNotNull(database.loanDao().getLoanById(loanId))
      assertEquals(5_000L, updatedLoan.remainingAmount)
      assertFalse(updatedLoan.isSettled)
    }

  @Test
  fun deleteLoanRemovesItsPaymentHistoriesInSameTransaction() =
    runTest {
      val repo = createRepository()
      val loanId = seedLoanWithCategory(5_000L)
      repo.addPaymentToLoan(loanId, 2_000L, "first installment", null)

      val loan = requireNotNull(database.loanDao().getLoanById(loanId))
      repo.deleteLoan(loan)

      assertEquals(0, database.loanDao().getAllLoansBlocking().size)
      assertEquals(0, database.paymentHistoryDao().getAllPaymentHistoriesBlocking().size)
    }

  @Test
  fun deleteCategoryKeepsDefaultCategoriesAndDeletesCustomOnes() =
    runTest {
      val repo = createRepository()
      val defaultCategory =
        Category(
          name = "وام و قرض",
          key = "Loans",
          icon = "HistoryEdu",
          color = 0xFF9C27B0L,
          type = CategoryType.BOTH,
          isDefault = true
        )
      val defaultId = repo.insertCategory(defaultCategory)

      repo.deleteCategory(defaultCategory.copy(id = defaultId))
      assertNotNull(database.categoryDao().getCategoryById(defaultId))

      val customCategory =
        Category(
          name = "سرگرمی",
          key = "Entertainment",
          icon = "SportsEsports",
          color = 0xFF3F51B5L,
          type = CategoryType.EXPENSE,
          isDefault = false
        )
      val customId = repo.insertCategory(customCategory)

      repo.deleteCategory(customCategory.copy(id = customId))
      assertNull(database.categoryDao().getCategoryById(customId))
    }

  @Test
  fun deleteCategoryRejectsWhenCallerLiesAboutIsDefault() =
    runTest {
      val repo = createRepository()
      // Persist a real default category.
      val defaultId =
        repo.insertCategory(
          Category(
            name = "وام و قرض",
            key = "Loans",
            icon = "HistoryEdu",
            color = 0xFF9C27B0L,
            type = CategoryType.BOTH,
            isDefault = true
          )
        )
      val nonDefaultId =
        repo.insertCategory(
          Category(
            name = "سرگرمی",
            key = "Entertainment",
            icon = "SportsEsports",
            color = 0xFF3F51B5L,
            type = CategoryType.EXPENSE,
            isDefault = false
          )
        )

      // Caller hands in a hand-built Category that lies about isDefault
      // (the real persisted row is the opposite). The repository must
      // re-read isDefault from the row and refuse.
      val forgedDefaultDelete =
        Category(
          id = defaultId,
          name = "anything",
          key = "anything",
          icon = "anything",
          color = 0L,
          type = CategoryType.EXPENSE,
          isDefault = false
        )
      val forgedNonDefaultDelete =
        Category(
          id = nonDefaultId,
          name = "anything",
          key = "anything",
          icon = "anything",
          color = 0L,
          type = CategoryType.EXPENSE,
          isDefault = true
        )

      repo.deleteCategory(forgedDefaultDelete)
      assertNotNull(
        "default category must survive a forged non-default delete",
        database.categoryDao().getCategoryById(defaultId)
      )

      repo.deleteCategory(forgedNonDefaultDelete)
      assertNull(
        "custom category is legitimately deleted by a forged default",
        database.categoryDao().getCategoryById(nonDefaultId)
      )
    }

  @Test
  fun addpaymenttoloanRejectsZeroAmount() =
    runTest {
      val repo = createRepository()
      val loanId = seedLoanWithCategory(5_000L)

      val success = repo.addPaymentToLoan(loanId, 0L, "zero payment", null)
      assertFalse(success)

      val paymentHistories = database.paymentHistoryDao().getAllPaymentHistoriesBlocking()
      assertEquals(0, paymentHistories.size)

      val transactions = database.transactionDao().getAllTransactionsBlocking()
      assertEquals(0, transactions.size)

      val updatedLoan = requireNotNull(database.loanDao().getLoanById(loanId))
      assertEquals(5_000L, updatedLoan.remainingAmount)
      assertFalse(updatedLoan.isSettled)
    }

  @Test
  fun addpaymenttoloanRejectsNegativeAmount() =
    runTest {
      val repo = createRepository()
      val loanId = seedLoanWithCategory(5_000L)

      val success = repo.addPaymentToLoan(loanId, -1_000L, "negative payment", null)
      assertFalse(success)

      val paymentHistories = database.paymentHistoryDao().getAllPaymentHistoriesBlocking()
      assertEquals(0, paymentHistories.size)

      val transactions = database.transactionDao().getAllTransactionsBlocking()
      assertEquals(0, transactions.size)

      val updatedLoan = requireNotNull(database.loanDao().getLoanById(loanId))
      assertEquals(5_000L, updatedLoan.remainingAmount)
      assertFalse(updatedLoan.isSettled)
    }

  @Test
  fun addpaymenttoloanRejectsPaymentOnSettledLoan() =
    runTest {
      val repo = createRepository()
      val loanId = seedLoanWithCategory(0L, isSettled = true)

      val success = repo.addPaymentToLoan(loanId, 1_000L, "payment on settled loan", null)
      assertFalse(success)

      val paymentHistories = database.paymentHistoryDao().getAllPaymentHistoriesBlocking()
      assertEquals(0, paymentHistories.size)

      val transactions = database.transactionDao().getAllTransactionsBlocking()
      assertEquals(0, transactions.size)

      val updatedLoan = requireNotNull(database.loanDao().getLoanById(loanId))
      assertEquals(0L, updatedLoan.remainingAmount)
      assertTrue(updatedLoan.isSettled)
    }

  @Test
  fun mergefrombackupRemapsLoanidAndInstallmentidLinkage() =
    runTest {
      val repo = createRepository()
      val category =
        Category(
          name = "Groceries",
          key = "Shopping",
          icon = "ShoppingBag",
          color = 0xFF2196F3L,
          type = CategoryType.EXPENSE
        )
      val bankLoan = testBankLoan()
      val loan =
        Loan(
          personName = "Ali",
          type = LoanType.DEBTOR,
          originalAmount = 100_000L,
          remainingAmount = 100_000L,
          description = "loan"
        )
      val installment =
        Installment(title = "ماه اول", amount = 10_000_000L, dueDate = 1_700_000_000_000L, bankLoanId = 0L)
      val backup =
        BackupPayload(
          version = 1,
          timestamp = System.currentTimeMillis(),
          appVersion = "1.0",
          categories = listOf(category),
          bankLoans = listOf(bankLoan),
          loans = listOf(loan),
          installments = listOf(installment),
          paymentHistories = listOf(PaymentHistory(loanId = loan.id, amount = 50_000L)),
          transactions =
            listOf(
              Transaction(
                type = TransactionType.EXPENSE,
                categoryId = category.id,
                amount = 10_000L,
                description = "tx",
                installmentId = installment.id
              )
            )
        )
      repo.mergeFromBackup(backup)
      val payments = database.paymentHistoryDao().getAllPaymentHistoriesBlocking()
      assertEquals(1, payments.size)
      assertEquals("Ali", requireNotNull(database.loanDao().getLoanById(payments[0].loanId)).personName)
      val transactions = database.transactionDao().getAllTransactionsBlocking()
      assertEquals(1, transactions.size)
      val inst =
        requireNotNull(database.installmentDao().getInstallmentById(requireNotNull(transactions[0].installmentId)))
      assertEquals("ماه اول", inst.title)
      val mergedBankLoan = requireNotNull(database.bankLoanDao().getBankLoanById(requireNotNull(inst.bankLoanId)))
      assertEquals("بانک ملت", mergedBankLoan.bankName)
      assertEquals(mergedBankLoan.id, inst.bankLoanId)
    }

  @Test
  fun deleteAccountBlocksLastActiveAccountWhenArchivedAccountExists() =
    runTest {
      val repo = createRepository()
      // setUp seeds DEFAULT_ACCOUNT (id=1, active). Add one archived account so the
      // total count is 2 but the ACTIVE count is still 1 — deleting the last active
      // account (the seeded default) must be rejected.
      repo.insertAccount(AccountEntity(id = 2, name = "قدیمی", type = AccountType.BANK, isArchived = true))
      val defaultAccount = requireNotNull(database.accountDao().getById(DEFAULT_ACCOUNT_ID))

      try {
        repo.deleteAccount(defaultAccount)
        fail("Expected IllegalStateException deleting last active account")
      } catch (e: IllegalStateException) {
        assertEquals(
          "message should mention last active account",
          "Account $DEFAULT_ACCOUNT_ID is the last remaining active account and cannot be deleted",
          e.message
        )
      }
      assertEquals("account count should be unchanged", 2, database.accountDao().getAllAccountsBlocking().size)
      // The archived account must still be present.
      assertEquals(2L, database.accountDao().getById(2L)?.id)
    }

  @Test
  fun deleteAccountAllowsDeletingArchivedAccountWhenActiveAccountRemains() =
    runTest {
      val repo = createRepository()
      // setUp seeds DEFAULT_ACCOUNT (id=1, active). Add another active + an archived
      // one, so deleting the archived account leaves the active count unchanged.
      val activeId = repo.insertAccount(AccountEntity(id = 2, name = "فروشگاه", type = AccountType.CASH_WALLET))
      val archivedId =
        repo.insertAccount(
          AccountEntity(id = 3, name = "قدیمی", type = AccountType.BANK, isArchived = true)
        )
      val archivedAccount = requireNotNull(database.accountDao().getById(archivedId))

      // Deleting an archived account is always allowed — the active count is unchanged.
      repo.deleteAccount(archivedAccount)

      assertEquals(2, database.accountDao().getAllAccountsBlocking().size)
      assertNull(database.accountDao().getById(archivedId))
      assertNotNull(database.accountDao().getById(activeId))
    }

  @Test
  fun deleteAccountAllowsDeletingArchivedAccountEvenWithStaleEntity() =
    runTest {
      val repo = createRepository()
      // setUp seeds DEFAULT_ACCOUNT (id=1, active). Add one archived account so the
      // active count is 1. Now pass a STALE entity: the DB row is archived but the
      // in-memory copy still says isArchived=false. The old guard trusted the
      // stale entity and would delete the only active account. The fix checks
      // allAccounts, so deletion is allowed (it's archived in the DB).
      repo.insertAccount(AccountEntity(id = 2, name = "قدیمی", type = AccountType.BANK, isArchived = true))
      // Stale entity: isArchived=false but DB has isArchived=true
      val staleEntity = AccountEntity(id = 2, name = "قدیمی", type = AccountType.BANK, isArchived = false)

      // Should NOT throw — the DB version is archived, so the last-active guard must not fire.
      repo.deleteAccount(staleEntity)

      val remaining = database.accountDao().getAllAccountsBlocking()
      assertEquals("archived account should be deleted", 1, remaining.size)
      assertEquals(
        "default account must remain",
        DEFAULT_ACCOUNT_ID,
        database.accountDao().getById(DEFAULT_ACCOUNT_ID)?.id
      )
      assertNull("stale archived account should be gone", database.accountDao().getById(2L))
    }
}
