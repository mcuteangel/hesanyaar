package io.github.mojri.hesabyar.data

/**
 * Facade over the decomposed repository delegates.
 *
 * Each narrow Ops interface has <11 functions so detekt TooManyFunctions
 * does not trigger. The previous monolithic class had 40+ functions and
 * was baselined; decomposition keeps transaction-scoped operations co-located
 * in their delegate while the facade stays thin and delegates via `by`.
 */
class HesabyarRepository(
  transactionDao: TransactionDao,
  loanDao: LoanDao,
  installmentDao: InstallmentDao,
  paymentHistoryDao: PaymentHistoryDao,
  categoryDao: CategoryDao,
  bankLoanDao: BankLoanDao,
  accountDao: AccountDao,
  personDao: PersonDao,
  database: AppDatabase
) : HesabyarRepositoryInterface,
  PersonRepositoryInterface by PersonDelegate(
    personDao,
    loanDao,
    database.loanPersonOpsDao(),
    transactionDao,
    database
  ),
  AccountOps by AccountDelegate(accountDao, database),
  TransactionOps by TransactionDelegate(transactionDao),
  CategoryOps by CategoryDelegate(categoryDao, database),
  LoanOps by LoanDelegate(
    loanDao,
    paymentHistoryDao,
    transactionDao,
    database.transactionLinkDao(),
    categoryDao,
    database
  ),
  InstallmentOps by InstallmentDelegate(
    installmentDao,
    transactionDao,
    database.transactionLinkDao(),
    categoryDao,
    database
  ),
  BankLoanOps by BankLoanDelegate(bankLoanDao, installmentDao, database),
  BackupOps by BackupDelegate(
    transactionDao,
    loanDao,
    installmentDao,
    paymentHistoryDao,
    bankLoanDao,
    categoryDao,
    accountDao,
    personDao,
    database
  )
