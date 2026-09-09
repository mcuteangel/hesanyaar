package io.github.mojri.hesabyar.data

import io.github.mojri.hesabyar.core.AppLogger
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer

internal suspend fun backupClearAllTables(
  transactionDao: TransactionDao,
  loanDao: LoanDao,
  installmentDao: InstallmentDao,
  paymentHistoryDao: PaymentHistoryDao,
  bankLoanDao: BankLoanDao,
  accountDao: AccountDao,
  personDao: PersonDao
) {
  transactionDao.deleteAllTransactions()
  loanDao.deleteAllLoans()
  installmentDao.deleteAllInstallments()
  paymentHistoryDao.deleteAllPaymentHistory()
  bankLoanDao.deleteAllBankLoans()
  accountDao.deleteAllAccounts()
  personDao.deleteAllPersons()
}

internal suspend fun backupReseedDefaultAccountIfNeeded(
  accountDao: AccountDao,
  isEmpty: Boolean
) {
  if (isEmpty) accountDao.insert(AccountEntity.DEFAULT_ACCOUNT)
}

internal fun recoverPersonsFromLoansAndTransactions(
  loans: List<Loan>,
  transactions: List<Transaction>
): List<Person> {
  val distinct = LinkedHashMap<String, String>()
  for (loan in loans) {
    val raw = loan.personName
    if (raw.isBlank()) continue
    val display = PersonNameNormalizer.displayForm(raw)
    val key = PersonNameNormalizer.normalize(display)
    if (key.isNotEmpty()) distinct.putIfAbsent(key, display)
  }
  for (tx in transactions) {
    val raw = tx.personName
    if (raw == null || raw.isBlank()) continue
    val display = PersonNameNormalizer.displayForm(raw)
    val key = PersonNameNormalizer.normalize(display)
    if (key.isNotEmpty()) distinct.putIfAbsent(key, display)
  }
  return distinct.map { (key, display) -> Person(name = display, normalizedName = key) }
}

internal suspend fun backupInsertPersonsForReplace(
  persons: List<Person>,
  personDao: PersonDao
): PersonKeyMaps {
  val sourceIdToKey = mutableMapOf<Long, String>()
  val keyToLocalId = mutableMapOf<String, Long>()
  for (raw in persons) {
    val survived = backupInsertOnePersonForReplace(raw, keyToLocalId, personDao)
    if (survived) {
      val key = PersonNameNormalizer.normalize(PersonNameNormalizer.displayForm(raw.name))
      if (key.isNotEmpty()) sourceIdToKey[raw.id] = key
    }
  }
  return PersonKeyMaps(sourceIdToKey, keyToLocalId)
}

internal suspend fun backupInsertOnePersonForReplace(
  raw: Person,
  keyToLocalId: MutableMap<String, Long>,
  personDao: PersonDao
): Boolean {
  val display = PersonNameNormalizer.displayForm(raw.name)
  val key = PersonNameNormalizer.normalize(display)
  val storedId =
    if (key.isEmpty() || keyToLocalId.containsKey(key)) {
      if (key.isNotEmpty() && keyToLocalId.containsKey(key)) {
        AppLogger.w(
          "HesabyarRepository",
          "insertOnePersonForReplace: person '${raw.name}' collides on normalized key " +
            "'$key' — reusing existing person id ${keyToLocalId[key]}"
        )
      }
      keyToLocalId[key] ?: -1L
    } else {
      val inserted = personDao.insertPerson(raw.copy(name = display, normalizedName = key, id = 0))
      if (inserted != -1L) inserted else personDao.getPersonByNormalizedName(key)?.id ?: -1L
    }
  if (storedId != -1L) keyToLocalId[key] = storedId
  return storedId != -1L
}

internal suspend fun backupInsertLoansWithPersonRemap(
  loans: List<Loan>,
  maps: PersonKeyMaps,
  loanDao: LoanDao
) {
  for (loan in loans) {
    val mappedPersonId = resolvePersonId(loan.personId, loan.personName, maps)
    loanDao.insertLoan(loan.copy(personId = mappedPersonId))
  }
}

internal suspend fun backupInsertTransactionsWithPersonRemap(
  transactions: List<Transaction>,
  maps: PersonKeyMaps,
  transactionDao: TransactionDao
) {
  for (tx in transactions) {
    val mappedPersonId = resolvePersonId(tx.personId, tx.personName, maps)
    transactionDao.insertTransaction(tx.copy(personId = mappedPersonId))
  }
}
