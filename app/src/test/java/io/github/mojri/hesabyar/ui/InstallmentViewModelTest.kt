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
import io.github.mojri.hesabyar.domain.usecase.ManageInstallmentUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallmentViewModelTest {
  private lateinit var viewModel: InstallmentViewModel
  private val testDispatcher = StandardTestDispatcher()

  private val installments =
    listOf(
      Installment(id = 1, title = "a", amount = 100, dueDate = 0, bankLoanId = 10),
      Installment(id = 2, title = "b", amount = 200, dueDate = 0, bankLoanId = 20),
      Installment(id = 3, title = "c", amount = 300, dueDate = 0, bankLoanId = null)
    )

  @Before
  fun setup() {
    Dispatchers.setMain(testDispatcher)
    val useCase = ManageInstallmentUseCase(FakeRepository(installments))
    viewModel = InstallmentViewModel(useCase)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun setbankloanfilterFiltersVisibleinstallmentsToMatchingBankloanid() =
    runTest(testDispatcher) {
      collectForTest(viewModel.visibleInstallments)
      viewModel.setBankLoanFilter(10)
      advanceUntilIdle()

      assertEquals(listOf(1L), viewModel.visibleInstallments.value.map { it.id })
      assertEquals(10L, viewModel.bankLoanFilter.value)
    }

  @Test
  fun setbankloanfilterNullShowsAllInstallments() =
    runTest(testDispatcher) {
      collectForTest(viewModel.visibleInstallments)
      viewModel.setBankLoanFilter(10)
      advanceUntilIdle()
      viewModel.setBankLoanFilter(null)
      advanceUntilIdle()

      assertEquals(listOf(1L, 2L, 3L), viewModel.visibleInstallments.value.map { it.id })
      assertEquals(null, viewModel.bankLoanFilter.value)
    }

  private fun <T> TestScope.collectForTest(flow: StateFlow<T>) {
    backgroundScope.launch { flow.collect {} }
  }

  private class FakeRepository(
    private val installmentList: List<Installment>
  ) : HesabyarRepositoryInterface {
    override val allTransactions: Flow<List<Transaction>> = flowOf(emptyList())
    override val allLoans: Flow<List<Loan>> = flowOf(emptyList())
    override val allInstallments: Flow<List<Installment>> = flowOf(installmentList)
    override val allCategories: Flow<List<Category>> = flowOf(emptyList())
    override val allBankLoans: Flow<List<BankLoan>> = flowOf(emptyList())
    override val allAccounts: Flow<List<AccountEntity>> = flowOf(emptyList())

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
    ): Boolean = false

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
    ) {}

    override suspend fun replaceAllFromBackup(backup: BackupPayload) {}

    override suspend fun mergeFromBackup(backup: BackupPayload) {}

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
}
