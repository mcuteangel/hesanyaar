package io.github.mojri.hesabyar.data

import kotlinx.coroutines.flow.Flow

/**
 * Narrow contracts for HesabyarRepository decomposition.
 * Each has <11 functions so detekt TooManyFunctions does not trigger.
 * HesabyarRepositoryInterface extends them all and delegates via `by`.
 */
interface AccountOps {
  val allAccounts: Flow<List<AccountEntity>>

  suspend fun getActiveAccounts(): List<AccountEntity>

  suspend fun getAllAccounts(): List<AccountEntity>

  suspend fun getAccountById(id: Long): AccountEntity?

  suspend fun insertAccount(account: AccountEntity): Long

  suspend fun updateAccount(account: AccountEntity)

  suspend fun deleteAccount(account: AccountEntity)

  suspend fun getTransactionCountForAccount(accountId: Long): Int

  suspend fun getMaxDisplayOrder(): Int
}

interface TransactionOps {
  val allTransactions: Flow<List<Transaction>>

  fun getTransactionsInRange(
    start: Long,
    end: Long
  ): Flow<List<Transaction>>

  suspend fun insertTransaction(transaction: Transaction): Long

  suspend fun deleteTransaction(transaction: Transaction)

  suspend fun updateTransaction(transaction: Transaction)
}

interface CategoryOps {
  val allCategories: Flow<List<Category>>

  fun getCategoriesByType(type: String): Flow<List<Category>>

  suspend fun getCategoryById(id: Long): Category?

  suspend fun getCategoryByKey(key: String): Category?

  suspend fun insertCategory(category: Category): Long

  suspend fun updateCategory(category: Category)

  suspend fun deleteCategory(category: Category)
}

interface LoanOps {
  val allLoans: Flow<List<Loan>>

  suspend fun insertLoan(loan: Loan): Long

  suspend fun updateLoan(loan: Loan)

  suspend fun deleteLoan(loan: Loan)

  fun getPaymentHistoryForLoan(loanId: Long): Flow<List<PaymentHistory>>

  suspend fun addPaymentToLoan(
    loanId: Long,
    amount: Long,
    notes: String,
    customDate: Long?
  ): Boolean
}

interface InstallmentOps {
  val allInstallments: Flow<List<Installment>>

  suspend fun insertInstallment(installment: Installment): Long

  suspend fun updateInstallment(installment: Installment)

  suspend fun deleteInstallment(installment: Installment)
}

interface BankLoanOps {
  val allBankLoans: Flow<List<BankLoan>>

  suspend fun getBankLoanById(id: Long): BankLoan?

  suspend fun insertBankLoan(bankLoan: BankLoan): Long

  suspend fun updateBankLoan(bankLoan: BankLoan)

  suspend fun deleteBankLoan(bankLoan: BankLoan)

  suspend fun getInstallmentsByBankLoanId(bankLoanId: Long): List<Installment>

  suspend fun addBankLoanWithInstallments(
    bankLoan: BankLoan,
    installments: List<Installment>
  ): Long
}

interface BackupOps {
  suspend fun importBackup(
    transactions: List<Transaction>,
    loans: List<Loan>,
    installments: List<Installment>,
    paymentHistories: List<PaymentHistory>,
    bankLoans: List<BankLoan>
  )

  suspend fun replaceAllFromBackup(backup: BackupPayload)

  suspend fun mergeFromBackup(backup: BackupPayload)

  suspend fun getAllPaymentHistories(): List<PaymentHistory>
}
