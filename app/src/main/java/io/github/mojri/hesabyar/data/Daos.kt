package io.github.mojri.hesabyar.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
  @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
  fun getAllCategories(): Flow<List<Category>>

  @Query("SELECT * FROM categories WHERE type = :type OR type = 'BOTH' ORDER BY isDefault DESC, name ASC")
  fun getCategoriesByType(type: String): Flow<List<Category>>

  @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
  suspend fun getCategoryById(id: Long): Category?

  @Query("SELECT * FROM categories WHERE key = :key LIMIT 1")
  suspend fun getCategoryByKey(key: String): Category?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertCategory(category: Category): Long

  @Update
  suspend fun updateCategory(category: Category)

  @Delete
  suspend fun deleteCategory(category: Category)

  @Query("SELECT COUNT(*) FROM categories")
  suspend fun getCategoryCount(): Int

  // REPLACE-restore mirror: the backup always carries the full category set,
  // so stale local categories (custom or renamed) must not survive it.
  @Query("DELETE FROM categories")
  suspend fun deleteAllCategories()

  @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
  fun getAllCategoriesBlocking(): List<Category>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(categories: List<Category>)
}

@Dao
interface TransactionDao {
  @Query("SELECT * FROM transactions ORDER BY date DESC")
  fun getAllTransactions(): Flow<List<Transaction>>

  @Query("SELECT * FROM transactions WHERE date BETWEEN :start AND :end ORDER BY date DESC")
  fun getTransactionsInRange(
    start: Long,
    end: Long
  ): Flow<List<Transaction>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTransaction(transaction: Transaction): Long

  @Delete
  suspend fun deleteTransaction(transaction: Transaction)

  @Update
  suspend fun updateTransaction(transaction: Transaction)

  @Query("DELETE FROM transactions")
  suspend fun deleteAllTransactions()

  @Query("SELECT * FROM transactions ORDER BY date DESC")
  fun getAllTransactionsBlocking(): List<Transaction>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(transactions: List<Transaction>)

  @Query("UPDATE transactions SET personName = :newName WHERE personId = :personId")
  suspend fun syncTransactionPersonNames(
    personId: Long,
    newName: String
  )

  @Query("UPDATE transactions SET personName = :newName WHERE personId IS NULL AND personName = :oldName")
  suspend fun syncTransactionPersonNamesForNullId(
    oldName: String,
    newName: String
  )

  // Person deletion keeps the denormalized personName but drops the dangling
  // id reference, so backups never export personId values without a person row.
  @Query("UPDATE transactions SET personId = NULL WHERE personId = :personId")
  suspend fun clearTransactionPersonIds(personId: Long)
}

/**
 * Linkage cleanup operations for transactions whose existence is tied to a
 * loan or installment. Split from [TransactionDao] to keep the main DAO
 * under the detekt TooManyFunctions threshold.
 */
@Dao
interface TransactionLinkDao {
  // Deletes the expense/income transaction created by LoanDelegate
  // .addPaymentToLoan for one payment (same person, Loans category, amount
  // and date identify the generated row).
  @Query(
    "DELETE FROM transactions WHERE personName = :personName AND categoryId = :categoryId " +
      "AND amount = :amount AND date = :date"
  )
  suspend fun deleteLoanPaymentTransaction(
    personName: String,
    categoryId: Long,
    amount: Long,
    date: Long
  )

  // Reversal link for the expense recorded when an installment was paid.
  @Query("DELETE FROM transactions WHERE installmentId = :installmentId AND categoryId = :categoryId")
  suspend fun deleteTransactionForInstallment(
    installmentId: Long,
    categoryId: Long
  )
}

@Dao
interface LoanDao {
  @Query("SELECT * FROM loans ORDER BY date DESC")
  fun getAllLoans(): Flow<List<Loan>>

  @Query("SELECT * FROM loans WHERE id = :id LIMIT 1")
  suspend fun getLoanById(id: Long): Loan?

  @Query("SELECT * FROM loans ORDER BY date DESC")
  suspend fun getAllLoansSync(): List<Loan>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertLoan(loan: Loan): Long

  @Update
  suspend fun updateLoan(loan: Loan)

  @Delete
  suspend fun deleteLoan(loan: Loan)

  @Query("DELETE FROM loans")
  suspend fun deleteAllLoans()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(loans: List<Loan>)

  @Query("SELECT * FROM loans ORDER BY date DESC")
  fun getAllLoansBlocking(): List<Loan>

  @Query("UPDATE loans SET personName = :newName WHERE personId = :personId")
  suspend fun syncLoanPersonNames(
    personId: Long,
    newName: String
  )

  @Query("UPDATE loans SET personName = :newName WHERE personId IS NULL AND personName = :oldName")
  suspend fun syncLoanPersonNamesForNullId(
    oldName: String,
    newName: String
  )
}

/**
 * Person-clearing side of loan updates. Split from [LoanDao] to keep the
 * main DAO under the detekt TooManyFunctions threshold.
 */
@Dao
interface LoanPersonOpsDao {
  @Query("UPDATE loans SET personId = NULL WHERE personId = :personId")
  suspend fun clearLoanPersonIds(personId: Long)
}

@Dao
interface BankLoanDao {
  @Query("SELECT * FROM bank_loans ORDER BY startDate DESC")
  fun getAllBankLoans(): Flow<List<BankLoan>>

  @Query("SELECT * FROM bank_loans WHERE id = :id LIMIT 1")
  suspend fun getBankLoanById(id: Long): BankLoan?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertBankLoan(bankLoan: BankLoan): Long

  @Update
  suspend fun updateBankLoan(bankLoan: BankLoan)

  @Delete
  suspend fun deleteBankLoan(bankLoan: BankLoan)

  @Query("DELETE FROM bank_loans")
  suspend fun deleteAllBankLoans()

  @Query("SELECT * FROM installments WHERE bankLoanId = :bankLoanId ORDER BY dueDate ASC")
  fun getInstallmentsByBankLoanId(bankLoanId: Long): Flow<List<Installment>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(bankLoans: List<BankLoan>)

  @Query("SELECT * FROM bank_loans ORDER BY startDate DESC")
  fun getAllBankLoansBlocking(): List<BankLoan>
}

@Dao
interface InstallmentDao {
  @Query("SELECT * FROM installments ORDER BY dueDate ASC")
  fun getAllInstallments(): Flow<List<Installment>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertInstallment(installment: Installment): Long

  @Update
  suspend fun updateInstallment(installment: Installment)

  @Delete
  suspend fun deleteInstallment(installment: Installment)

  @Query("SELECT * FROM installments WHERE id = :id LIMIT 1")
  suspend fun getInstallmentById(id: Long): Installment?

  @Query("DELETE FROM installments WHERE bankLoanId = :bankLoanId")
  suspend fun deleteInstallmentsByBankLoanId(bankLoanId: Long)

  @Query("SELECT * FROM installments ORDER BY dueDate ASC")
  suspend fun getAllInstallmentsSync(): List<Installment>

  @Query("DELETE FROM installments")
  suspend fun deleteAllInstallments()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(installments: List<Installment>)

  @Query("SELECT * FROM installments ORDER BY dueDate ASC")
  fun getAllInstallmentsBlocking(): List<Installment>
}

@Dao
interface PaymentHistoryDao {
  @Query("SELECT * FROM payment_history WHERE loanId = :loanId ORDER BY date DESC")
  fun getPaymentHistoryForLoan(loanId: Long): Flow<List<PaymentHistory>>

  @Query("SELECT * FROM payment_history WHERE loanId = :loanId ORDER BY date DESC")
  suspend fun getPaymentHistoriesForLoanSync(loanId: Long): List<PaymentHistory>

  @Query("DELETE FROM payment_history WHERE loanId = :loanId")
  suspend fun deletePaymentHistoryForLoan(loanId: Long)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPayment(payment: PaymentHistory): Long

  @Delete
  suspend fun deletePayment(payment: PaymentHistory)

  @Query("DELETE FROM payment_history")
  suspend fun deleteAllPaymentHistory()

  @Query("SELECT * FROM payment_history ORDER BY date DESC")
  suspend fun getAllPaymentHistories(): List<PaymentHistory>

  @Query("SELECT * FROM payment_history ORDER BY date DESC")
  fun getAllPaymentHistoriesBlocking(): List<PaymentHistory>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(payments: List<PaymentHistory>)
}

@Dao
interface PersonDao {
  @Query("SELECT * FROM persons WHERE isArchived = 0 ORDER BY name")
  fun getAllPersons(): Flow<List<Person>>

  // Backup paths (export, plaintext→encrypted transfer, tests) need an
  // unfiltered blocking read so archived rows round-trip losslessly. Other
  // DAOs follow the same split: live-UI Flow/Blocking variants filter,
  // bulk/blocking for backup/export don't.
  @Query("SELECT * FROM persons ORDER BY name")
  fun getAllPersonsIncludingArchivedBlocking(): List<Person>

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  fun insertAllBlocking(persons: List<Person>)

  @Query("SELECT * FROM persons WHERE id = :id LIMIT 1")
  suspend fun getPersonById(id: Long): Person?

  @Query("SELECT * FROM persons WHERE normalizedName = :normalizedName LIMIT 1")
  suspend fun getPersonByNormalizedName(normalizedName: String): Person?

  // IGNORE + unique(normalizedName): a race that inserts the same dedup key
  // twice keeps the first row and returns -1; callers re-query on -1.
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertPerson(person: Person): Long

  @Query("DELETE FROM persons")
  suspend fun deleteAllPersons()

  @Update
  suspend fun updatePerson(person: Person)

  @Delete
  suspend fun deletePerson(person: Person)

  // D3 rename sync moved to LoanDao/TransactionDao — PersonDao now owns
  // only person persistence; repository coordinates the cross-table rename.
}

@Dao
interface AccountDao {
  @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY displayOrder, name")
  fun getActiveAccounts(): Flow<List<AccountEntity>>

  @Query("SELECT * FROM accounts ORDER BY displayOrder, name")
  fun getAllAccounts(): Flow<List<AccountEntity>>

  @Query("SELECT * FROM accounts WHERE id = :id")
  suspend fun getById(id: Long): AccountEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(account: AccountEntity): Long

  @Update
  suspend fun update(account: AccountEntity)

  @Delete
  suspend fun delete(account: AccountEntity)

  @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId OR destinationAccountId = :accountId")
  suspend fun getTransactionCountForAccount(accountId: Long): Int

  @Query("DELETE FROM accounts")
  suspend fun deleteAllAccounts()

  @Query("SELECT COALESCE(MAX(displayOrder), -1) FROM accounts")
  suspend fun getMaxDisplayOrder(): Int

  @Query("SELECT * FROM accounts ORDER BY displayOrder, name")
  fun getAllAccountsBlocking(): List<AccountEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  fun insertAllBlocking(accounts: List<AccountEntity>)
}
