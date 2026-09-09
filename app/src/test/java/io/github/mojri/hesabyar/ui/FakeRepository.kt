package io.github.mojri.hesabyar.ui

import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.HesabyarRepositoryInterface
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Shared [HesabyarRepositoryInterface] fake for backup-flow tests. Extracted from
 * BackupViewModelTest so multiple test classes exercise the important coordinator
 * without duplicating the ~35-method contract.
 */
internal class FakeRepository : HesabyarRepositoryInterface {
  var importShouldThrow: Exception? = null
  var exportShouldThrow: Exception? = null

  /** Test hook: overrides repayment outcomes — return false or throw. */
  var addPaymentBehavior: (() -> Boolean)? = null

  /** Counts how many times a restore actually executed — for duplicate-submission tests. */
  var executeRestoreCount = 0

  /** When set, the first repository flow collected by exportBackupJson suspends until released. */
  var exportGate: CompletableDeferred<Unit>? = null
  val accountsList = mutableListOf<AccountEntity>()

  /** Counts how many times exportBackupJson read the categories flow — for duplicate-submission tests. */
  var exportCategoryReadCount = 0

  override val allTransactions: Flow<List<Transaction>> = flowOf(emptyList())
  override val allLoans: Flow<List<Loan>> = flowOf(emptyList())
  override val allInstallments: Flow<List<Installment>> = flowOf(emptyList())
  override val allCategories: Flow<List<Category>> =
    flow {
      exportCategoryReadCount++
      val gate = exportGate
      exportGate = null
      gate?.await()
      exportShouldThrow?.let { throw it }
      emit(emptyList())
    }
  override val allBankLoans: Flow<List<BankLoan>> = flowOf(emptyList())

  // Cold flow: must read the live list at collection time, not snapshot it at
  // construction — tests populate accountsList after the repo is created.
  override val allAccounts: Flow<List<AccountEntity>> = flow { emit(accountsList.toList()) }

  override fun getTransactionsInRange(
    start: Long,
    end: Long
  ): Flow<List<Transaction>> = flowOf(emptyList())

  override fun getCategoriesByType(type: String): Flow<List<Category>> = flowOf(emptyList())

  override suspend fun getCategoryById(id: Long): Category? = null

  override suspend fun getCategoryByKey(key: String): Category? = null

  override suspend fun insertCategory(category: Category): Long = 0L

  override suspend fun updateCategory(category: Category) {}

  override suspend fun deleteCategory(category: Category) {}

  override suspend fun insertTransaction(transaction: Transaction): Long = 0L

  override suspend fun deleteTransaction(transaction: Transaction) {}

  override suspend fun updateTransaction(transaction: Transaction) {}

  override suspend fun insertLoan(loan: Loan): Long = 0L

  override suspend fun updateLoan(loan: Loan) {}

  override suspend fun deleteLoan(loan: Loan) {}

  override fun getPaymentHistoryForLoan(loanId: Long): Flow<List<PaymentHistory>> = flowOf(emptyList())

  override suspend fun addPaymentToLoan(
    loanId: Long,
    amount: Long,
    notes: String,
    customDate: Long?
  ): Boolean = addPaymentBehavior?.invoke() ?: false

  override suspend fun insertInstallment(installment: Installment): Long = 0L

  override suspend fun updateInstallment(installment: Installment) {}

  override suspend fun deleteInstallment(installment: Installment) {}

  override suspend fun getBankLoanById(id: Long): BankLoan? = null

  override suspend fun insertBankLoan(bankLoan: BankLoan): Long = 0L

  override suspend fun updateBankLoan(bankLoan: BankLoan) {}

  override suspend fun deleteBankLoan(bankLoan: BankLoan) {}

  override suspend fun getInstallmentsByBankLoanId(bankLoanId: Long): List<Installment> = emptyList()

  override suspend fun addBankLoanWithInstallments(
    bankLoan: BankLoan,
    installments: List<Installment>
  ): Long = 0L

  override suspend fun importBackup(
    transactions: List<Transaction>,
    loans: List<Loan>,
    installments: List<Installment>,
    paymentHistories: List<PaymentHistory>,
    bankLoans: List<BankLoan>
  ) {
    importShouldThrow?.let { throw it }
  }

  override suspend fun replaceAllFromBackup(backup: BackupPayload) {
    executeRestoreCount++
    importShouldThrow?.let { throw it }
  }

  override suspend fun mergeFromBackup(backup: BackupPayload) {
    executeRestoreCount++
  }

  override suspend fun getAllPaymentHistories(): List<PaymentHistory> = emptyList()

  override suspend fun getActiveAccounts(): List<AccountEntity> = emptyList()

  override suspend fun getAllAccounts(): List<AccountEntity> = emptyList()

  override suspend fun getAccountById(id: Long): AccountEntity? = null

  override suspend fun insertAccount(account: AccountEntity): Long = 0L

  override suspend fun updateAccount(account: AccountEntity) {}

  override suspend fun deleteAccount(account: AccountEntity) {}

  override suspend fun getTransactionCountForAccount(accountId: Long): Int = 0

  override suspend fun getMaxDisplayOrder(): Int = -1

  override val allPersons: Flow<List<Person>> = flowOf(emptyList())

  override suspend fun getAllPersonsIncludingArchived(): List<Person> = emptyList()

  override suspend fun getPersonById(id: Long): Person? = null

  override suspend fun upsertPerson(person: Person): Person = person.copy(id = 1L)

  override suspend fun renamePerson(
    personId: Long,
    newName: String
  ): Boolean = true

  override suspend fun deletePerson(person: Person) {}
}
