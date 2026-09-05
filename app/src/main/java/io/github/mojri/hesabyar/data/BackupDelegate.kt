package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer

internal class BackupDelegate(
  private val transactionDao: TransactionDao,
  private val loanDao: LoanDao,
  private val installmentDao: InstallmentDao,
  private val paymentHistoryDao: PaymentHistoryDao,
  private val bankLoanDao: BankLoanDao,
  private val categoryDao: CategoryDao,
  private val accountDao: AccountDao,
  private val personDao: PersonDao,
  private val database: AppDatabase
) : BackupOps {
  override suspend fun importBackup(
    transactions: List<Transaction>,
    loans: List<Loan>,
    installments: List<Installment>,
    paymentHistories: List<PaymentHistory>,
    bankLoans: List<BankLoan>
  ) = database.withTransaction {
    transactionDao.deleteAllTransactions()
    loanDao.deleteAllLoans()
    installmentDao.deleteAllInstallments()
    paymentHistoryDao.deleteAllPaymentHistory()
    bankLoanDao.deleteAllBankLoans()
    transactions.forEach { transactionDao.insertTransaction(it) }
    loans.forEach { loanDao.insertLoan(it) }
    installments.forEach { installmentDao.insertInstallment(it) }
    paymentHistories.forEach { paymentHistoryDao.insertPayment(it) }
    bankLoans.forEach { bankLoanDao.insertBankLoan(it) }
  }

  override suspend fun getAllPaymentHistories(): List<PaymentHistory> = paymentHistoryDao.getAllPaymentHistories()

  override suspend fun replaceAllFromBackup(backup: BackupPayload) =
    database.withTransaction {
      backupClearAllTables(
        transactionDao,
        loanDao,
        installmentDao,
        paymentHistoryDao,
        bankLoanDao,
        accountDao,
        personDao
      )
      backupReseedDefaultAccountIfNeeded(accountDao, backup.accounts.isEmpty())
      backup.categories.forEach { categoryDao.insertCategory(it) }
      val personsToInsert =
        if (backup.persons.isEmpty()) {
          recoverPersonsFromLoansAndTransactions(backup.loans, backup.transactions)
        } else {
          backup.persons
        }
      val personMaps = backupInsertPersonsForReplace(personsToInsert, personDao)
      backupInsertLoansWithPersonRemap(backup.loans, personMaps, loanDao)
      backupInsertTransactionsWithPersonRemap(backup.transactions, personMaps, transactionDao)
      backup.installments.forEach { installmentDao.insertInstallment(it) }
      backup.paymentHistories.forEach { paymentHistoryDao.insertPayment(it) }
      backup.bankLoans.forEach { bankLoanDao.insertBankLoan(it) }
      backup.accounts.forEach { accountDao.insert(it) }
    }

  override suspend fun mergeFromBackup(backup: BackupPayload) =
    database.withTransaction {
      val categoryIdMap = backupMergeCategories(backup.categories, categoryDao)
      val personKeyToId = backupMergePersons(backup.persons, personDao)
      val personMaps =
        PersonKeyMaps(
          sourceIdToKey =
            backup.persons.associate {
              it.id to PersonNameNormalizer.normalize(PersonNameNormalizer.displayForm(it.name))
            },
          keyToLocalId = personKeyToId
        )

      fun resolveForMerge(
        sourcePersonId: Long?,
        fallbackName: String?
      ): Long? = resolvePersonId(sourcePersonId, fallbackName, personMaps)
      val loanIdMap = backupMergeLoans(backup.loans, loanDao, ::resolveForMerge)
      val bankLoanIdMap = backupMergeBankLoans(backup.bankLoans, bankLoanDao)
      val installmentIdMap = backupMergeInstallments(backup.installments, installmentDao, bankLoanIdMap)
      val accountIdMap = backupMergeAccounts(backup.accounts, accountDao)
      backupMergeTransactions(
        backup.transactions,
        categoryIdMap,
        installmentIdMap,
        accountIdMap,
        categoryDao,
        accountDao,
        transactionDao,
        ::resolveForMerge
      )
      backupMergePaymentHistories(backup.paymentHistories, loanIdMap, paymentHistoryDao)
    }
}
