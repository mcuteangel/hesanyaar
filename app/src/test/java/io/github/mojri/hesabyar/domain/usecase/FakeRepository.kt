package io.github.mojri.hesabyar.domain.usecase

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
import io.github.mojri.hesabyar.domain.exception.CannotDeleteLastActiveAccountException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

internal class FakeRepository : HesabyarRepositoryInterface {
  private val bankLoans = mutableListOf<BankLoan>()
  private val installments = mutableListOf<Installment>()
  private val _allInstallments = MutableStateFlow<List<Installment>>(emptyList())
  private val _allBankLoans = MutableStateFlow<List<BankLoan>>(emptyList())
  private val _allTransactions = MutableStateFlow<List<Transaction>>(emptyList())
  private val _allLoans = MutableStateFlow<List<Loan>>(emptyList())
  private val _allAccounts = MutableStateFlow<List<AccountEntity>>(emptyList())
  val accountsList = mutableListOf<AccountEntity>()

  // --- Failure simulation (used by AccountViewModelTest error-path tests) ---
  var shouldThrowOnInsert = false
  var shouldThrowOnUpdate = false
  var shouldThrowOnDelete = false
  var shouldThrowOnTransactionCount = false

  /** Forces [deleteAccount] to throw CannotDeleteLastActiveAccountException, simulating a TOCTOU race. */
  var forceLastActiveAccountException = false

  /** Overrides the computed transaction count when non-null. */
  var transactionCountOverride: Int? = null

  /** When set, suspends [getTransactionCountForAccount] until completed — for race tests. */
  var txCountGate: CompletableDeferred<Unit>? = null

  private var nextId = 1L

  override val allTransactions: Flow<List<Transaction>> = _allTransactions.asStateFlow()
  override val allLoans: Flow<List<Loan>> = _allLoans.asStateFlow()
  override val allInstallments: Flow<List<Installment>> = _allInstallments.asStateFlow()
  override val allCategories: Flow<List<Category>> = flowOf(emptyList())
  override val allBankLoans: Flow<List<BankLoan>> = _allBankLoans.asStateFlow()
  override val allAccounts: Flow<List<AccountEntity>> = _allAccounts.asStateFlow()

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

  override suspend fun insertTransaction(transaction: Transaction): Long {
    val id = if (transaction.id != 0L) transaction.id else nextId++
    nextId = maxOf(nextId, id + 1)
    _allTransactions.value = _allTransactions.value + transaction.copy(id = id)
    return id
  }

  override suspend fun deleteTransaction(transaction: Transaction) {}

  override suspend fun updateTransaction(transaction: Transaction) {
    val current = _allTransactions.value
    val idx = current.indexOfFirst { it.id == transaction.id }
    if (idx >= 0) {
      _allTransactions.value = current.toMutableList().also { it[idx] = transaction }
    }
  }

  override suspend fun insertLoan(loan: Loan): Long {
    val id = if (loan.id != 0L) loan.id else nextId++
    nextId = maxOf(nextId, id + 1)
    _allLoans.value = _allLoans.value + loan.copy(id = id)
    return id
  }

  override suspend fun updateLoan(loan: Loan) {
    val current = _allLoans.value
    val idx = current.indexOfFirst { it.id == loan.id }
    if (idx >= 0) {
      _allLoans.value = current.toMutableList().also { it[idx] = loan }
    }
  }

  override suspend fun deleteLoan(loan: Loan) {}

  override fun getPaymentHistoryForLoan(loanId: Long): Flow<List<PaymentHistory>> = flowOf(emptyList())

  override suspend fun addPaymentToLoan(
    loanId: Long,
    amount: Long,
    notes: String,
    customDate: Long?
  ): Boolean = false

  override suspend fun insertInstallment(installment: Installment): Long {
    val id = nextId++
    installments.add(installment.copy(id = id))
    _allInstallments.value = installments.toList()
    return id
  }

  override suspend fun updateInstallment(installment: Installment) {
    val idx = installments.indexOfFirst { it.id == installment.id }
    if (idx >= 0) {
      installments[idx] = installment
      _allInstallments.value = installments.toList()
    }
  }

  override suspend fun deleteInstallment(installment: Installment) {
    installments.removeIf { it.id == installment.id }
    _allInstallments.value = installments.toList()
  }

  override suspend fun getBankLoanById(id: Long): BankLoan? = bankLoans.firstOrNull { it.id == id }

  override suspend fun insertBankLoan(bankLoan: BankLoan): Long {
    val id = nextId++
    bankLoans.add(bankLoan.copy(id = id))
    _allBankLoans.value = bankLoans.toList()
    return id
  }

  override suspend fun updateBankLoan(bankLoan: BankLoan) {
    val idx = bankLoans.indexOfFirst { it.id == bankLoan.id }
    if (idx >= 0) {
      bankLoans[idx] = bankLoan
      _allBankLoans.value = bankLoans.toList()
    }
  }

  override suspend fun deleteBankLoan(bankLoan: BankLoan) {
    bankLoans.removeIf { it.id == bankLoan.id }
    _allBankLoans.value = bankLoans.toList()
    installments.removeIf { it.bankLoanId == bankLoan.id }
    _allInstallments.value = installments.toList()
  }

  override suspend fun getInstallmentsByBankLoanId(bankLoanId: Long): List<Installment> =
    installments.filter { it.bankLoanId == bankLoanId }

  override suspend fun addBankLoanWithInstallments(
    bankLoan: BankLoan,
    installmentsToAdd: List<Installment>
  ): Long {
    val id = nextId++
    bankLoans.add(bankLoan.copy(id = id))
    _allBankLoans.value = bankLoans.toList()
    installmentsToAdd.forEach { inst ->
      val instId = nextId++
      installments.add(inst.copy(id = instId, bankLoanId = id))
    }
    _allInstallments.value = installments.toList()
    return id
  }

  override suspend fun importBackup(
    transactions: List<Transaction>,
    loans: List<Loan>,
    installments: List<Installment>,
    paymentHistories: List<PaymentHistory>,
    bankLoans: List<BankLoan>
  ) {}

  override suspend fun replaceAllFromBackup(backup: BackupPayload) {}

  override suspend fun mergeFromBackup(backup: BackupPayload) {}

  override suspend fun getAllPaymentHistories(): List<PaymentHistory> = emptyList()

  // --- Account CRUD ---

  fun refreshAccounts() {
    _allAccounts.value = accountsList.toList()
  }

  override suspend fun getActiveAccounts(): List<AccountEntity> = accountsList.filter { !it.isArchived }

  override suspend fun getAllAccounts(): List<AccountEntity> = accountsList.toList()

  override suspend fun getAccountById(id: Long): AccountEntity? = accountsList.firstOrNull { it.id == id }

  override suspend fun insertAccount(account: AccountEntity): Long {
    if (shouldThrowOnInsert) throw IllegalStateException("Simulated DB failure")
    val id = if (account.id != 0L) account.id else nextId++
    nextId = maxOf(nextId, id + 1)
    accountsList.add(account.copy(id = id))
    refreshAccounts()
    return id
  }

  override suspend fun updateAccount(account: AccountEntity) {
    if (shouldThrowOnUpdate) throw IllegalStateException("Simulated DB failure")
    val idx = accountsList.indexOfFirst { it.id == account.id }
    if (idx >= 0) {
      accountsList[idx] = account
      refreshAccounts()
    }
  }

  override suspend fun deleteAccount(account: AccountEntity) {
    if (shouldThrowOnDelete) throw IllegalStateException("Simulated DB failure")
    // Mirror HesabyarRepository: only the last ACTIVE account is protected.
    val activeAccountCount = accountsList.count { !it.isArchived }
    val isLastActive =
      activeAccountCount == 1 && accountsList.any { it.id == account.id && !it.isArchived }
    if (forceLastActiveAccountException || isLastActive) {
      throw CannotDeleteLastActiveAccountException(account.id)
    }
    ensureDeletable(account)
    accountsList.removeIf { it.id == account.id }
    refreshAccounts()
  }

  /** Mirrors HesabyarRepository: an account that still has transactions cannot be deleted. */
  private suspend fun ensureDeletable(account: AccountEntity) {
    val txCount = getTransactionCountForAccount(account.id)
    if (txCount > 0) {
      throw IllegalStateException("Account ${account.id} has $txCount transactions and cannot be deleted")
    }
  }

  override suspend fun getTransactionCountForAccount(accountId: Long): Int {
    txCountGate?.await()
    if (shouldThrowOnTransactionCount) throw IllegalStateException("Simulated DB failure")
    return transactionCountOverride
      ?: _allTransactions.value.count {
        it.accountId == accountId || it.destinationAccountId == accountId
      }
  }

  override suspend fun getMaxDisplayOrder(): Int = accountsList.maxOfOrNull { it.displayOrder } ?: -1

  private val _allPersons = MutableStateFlow<List<Person>>(emptyList())
  override val allPersons: Flow<List<Person>> = _allPersons.asStateFlow()

  private val personsList = mutableListOf<Person>()

  /** Publishes only non-archived persons, mirroring production [PersonDao.getAllPersons]. */
  private fun publishPersons() {
    _allPersons.value = personsList.filter { !it.isArchived }
  }

  /**
   * Adds a person to [getAllPersonsIncludingArchived] so encrypted-export tests
   * can carry person PII. The list starts empty, so tests that never call this
   * keep the previous "no persons" behaviour.
   */
  fun addPerson(person: Person) {
    personsList.add(person)
    publishPersons()
  }

  override suspend fun getAllPersonsIncludingArchived(): List<Person> = personsList.toList()

  override suspend fun getPersonById(id: Long): Person? = personsList.firstOrNull { it.id == id }

  /**
   * Mirrors [PersonDelegate.upsertPerson]: match-or-create by normalized name;
   * on collision, merge contact fields (phone/notes) into the existing row.
   */
  override suspend fun upsertPerson(person: Person): Person {
    val existingIdx = personsList.indexOfFirst { it.normalizedName == person.normalizedName }
    return if (existingIdx >= 0) {
      val existing = personsList[existingIdx]
      val merged =
        existing.copy(
          phone = person.phone ?: existing.phone,
          notes = person.notes ?: existing.notes
        )
      if (merged != existing) {
        personsList[existingIdx] = merged
        publishPersons()
      }
      merged
    } else {
      val withId = person.copy(id = nextId++)
      personsList.add(withId)
      publishPersons()
      withId
    }
  }

  /**
   * Mirrors [PersonDelegate.renamePerson]: update the person's display name and
   * normalized key, then sync denormalized personName on linked loans and
   * transactions.
   */
  override suspend fun renamePerson(
    personId: Long,
    newName: String
  ): Boolean {
    val idx = personsList.indexOfFirst { it.id == personId }
    val display = newName.trim()
    val key =
      io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer
        .normalize(display)
    if (idx < 0 || !canRename(display, key, personId)) return false
    personsList[idx] = personsList[idx].copy(name = display, normalizedName = key)
    publishPersons()
    _allLoans.value =
      _allLoans.value.map { tx ->
        if (tx.personId == personId) tx.copy(personName = display) else tx
      }
    _allTransactions.value =
      _allTransactions.value.map { tx ->
        if (tx.personId == personId) tx.copy(personName = display) else tx
      }
    return true
  }

  private fun canRename(
    display: String,
    key: String,
    personId: Long
  ): Boolean {
    if (display.isEmpty() || key.isEmpty()) return false
    val clash = personsList.firstOrNull { it.normalizedName == key && it.id != personId }
    return clash == null
  }

  override suspend fun deletePerson(person: Person) {
    personsList.removeIf { it.id == person.id }
    _allPersons.value = personsList.toList()
  }
}
