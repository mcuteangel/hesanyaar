package io.github.mojri.hesabyar.rust

import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer
import io.github.mojri.hesabyar.ui.AccountAnalytics
import io.github.mojri.hesabyar.ui.AccountDashboardSummary
import java.math.RoundingMode
import io.github.mojri.hesabyar.ui.AnalyticsData as KAnalyticsData
import io.github.mojri.hesabyar.ui.CategoryBreakdown as KCategoryBreakdown
import io.github.mojri.hesabyar.ui.DashboardData as KDashboardData
import io.github.mojri.hesabyar.ui.DebtSummary as KDebtSummary
import io.github.mojri.hesabyar.ui.InstallmentProgress as KInstallmentProgress
import io.github.mojri.hesabyar.ui.MonthlyData as KMonthlyData

/**
 * Mappers between Rust-generated UniFFI types and Kotlin UI types.
 */
object RustMappers {
  fun mapDashboardData(
    rust: DashboardData,
    installments: List<Installment>,
    accounts: List<AccountEntity> = emptyList(),
  ): KDashboardData {
    val upcomingIns = installments.filter { !it.isPaid }.sortedBy { it.dueDate }
    return KDashboardData(
      currentBalance = rust.currentBalance,
      monthlyExpenses = rust.monthlyExpenses,
      monthlyIncome = rust.monthlyIncome,
      debtorsTotal = rust.debtorsTotal,
      creditorsTotal = rust.creditorsTotal,
      upcomingInstallments = upcomingIns,
      savingsRate = rust.savingsRate,
      debtToIncomeRatio = rust.debtToIncomeRatio,
      bankLoans = rust.bankLoans,
      bankLoansTotal = rust.bankLoansTotal,
      accounts = rust.accounts.map { mapAccountDashboardSummary(it, accounts) },
      totalNetWorth = rust.totalNetWorth
    )
  }

  fun mapAnalyticsData(
    rust: AnalyticsData,
    loans: List<io.github.mojri.hesabyar.data.Loan>,
    installments: List<Installment>,
    accounts: List<AccountEntity> = emptyList(),
  ): KAnalyticsData {
    val unsettledLoans = loans.filter { !it.isSettled }
    val debtors =
      unsettledLoans
        .filter {
          it.type == io.github.mojri.hesabyar.data.LoanType.DEBTOR
        }.map { mapDebtSummary(it) }
    val creditors =
      unsettledLoans
        .filter {
          it.type == io.github.mojri.hesabyar.data.LoanType.CREDITOR
        }.map { mapDebtSummary(it) }
    val installmentProgress =
      installments.map { inst ->
        KInstallmentProgress(
          id = inst.id,
          title = inst.title,
          amount = inst.amount,
          dueDate = inst.dueDate,
          isPaid = inst.isPaid
        )
      }
    // Build the flat accountBreakdown (per-account total expenses) from the
    // per-account analytics.  Each entry reuses CategoryBreakdown but with
    // accountId as the categoryId so AccountBreakdownCard can render it.
    val totalExpenseForAccounts = rust.categoryBreakdown.sumOf { it.total }
    val accountBreakdown: List<KCategoryBreakdown> =
      rust.accounts.map { acct ->
        val acctTotalExpense = acct.categoryBreakdown.sumOf { it.total }
        KCategoryBreakdown(
          categoryId = acct.accountId,
          categoryName = acct.accountName,
          // The Rust core carries no account color — resolve it from the DB
          // entity here (same source as the Kotlin fallback's buildAccountBreakdown).
          color = accountColorFor(acct.accountId, accounts),
          total = acctTotalExpense,
          percentage =
            if (totalExpenseForAccounts > 0) {
              acctTotalExpense.toFloat() / totalExpenseForAccounts.toFloat() * 100f
            } else {
              0f
            }
        )
      }

    return KAnalyticsData(
      monthlySpending = rust.monthlySpending.map { mapMonthlyData(it) },
      monthlyIncome = rust.monthlyIncome.map { mapMonthlyData(it) },
      categoryBreakdown = rust.categoryBreakdown.map { mapCategoryBreakdown(it) },
      accountBreakdown = accountBreakdown,
      debtors = debtors,
      creditors = creditors,
      activeLoans = unsettledLoans,
      installmentProgress = installmentProgress,
      totalInstallments = rust.totalInstallments,
      paidInstallments = rust.paidInstallments,
      totalDebt = rust.totalDebt,
      totalCredit = rust.totalCredit,
      bankLoans = rust.bankLoans,
      bankLoansTotalDebt = rust.bankLoansTotalDebt,
      accounts = rust.accounts.map { mapAccountAnalytics(it) }
    )
  }

  private fun mapDebtSummary(loan: io.github.mojri.hesabyar.data.Loan): KDebtSummary {
    val progress =
      if (loan.originalAmount > 0L) {
        // Ratio of paid amount over original; divide with BigDecimal to avoid
        // Float precision loss on large Rial values, then keep only the ratio as Float.
        val paid = (loan.originalAmount - loan.remainingAmount).toBigDecimal()
        val ratio = paid.divide(loan.originalAmount.toBigDecimal(), 6, RoundingMode.HALF_UP)
        ratio.toFloat().coerceIn(0f, 1f)
      } else {
        0f
      }
    return KDebtSummary(
      personName = loan.personName,
      originalAmount = loan.originalAmount,
      remainingAmount = loan.remainingAmount,
      type = loan.type.name,
      progress = progress
    )
  }

  private fun mapMonthlyData(rust: MonthlyData) =
    KMonthlyData(
      jalaliYear = rust.jalaliYear,
      jalaliMonth = rust.jalaliMonth,
      label = rust.label,
      income = rust.income,
      expense = rust.expense
    )

  private fun mapCategoryBreakdown(rust: CategoryBreakdown) =
    KCategoryBreakdown(
      categoryId = rust.categoryId,
      categoryName = rust.categoryName,
      color = rust.color,
      total = rust.total,
      percentage = rust.percentage
    )

  fun mapCategoryMap(categories: List<Category>): Map<Long, Category> = categories.associateBy { it.id }

  /**
   * Map a DB transaction type to the Rust [TransactionType].
   * Since [Transaction.type] is now a typed enum, this is a direct passthrough.
   */
  fun mapTransactionType(type: TransactionType): io.github.mojri.hesabyar.rust.TransactionType =
    when (type) {
      TransactionType.UNKNOWN -> io.github.mojri.hesabyar.rust.TransactionType.EXPENSE
      TransactionType.TRANSFER -> io.github.mojri.hesabyar.rust.TransactionType.TRANSFER
      else ->
        io.github.mojri.hesabyar.rust.TransactionType
          .valueOf(type.name)
    }

  fun mapTransaction(tx: Transaction): io.github.mojri.hesabyar.rust.Transaction =
    io.github.mojri.hesabyar.rust.Transaction(
      id = tx.id,
      txType = mapTransactionType(tx.type),
      categoryId = tx.categoryId,
      amount = tx.amount,
      description = tx.description,
      personName = tx.personName,
      personId = tx.personId,
      date = tx.date,
      dueDate = tx.dueDate,
      installmentId = tx.installmentId,
      accountId = tx.accountId,
      destinationAccountId = tx.destinationAccountId,
    )

  fun mapLoan(loan: Loan): io.github.mojri.hesabyar.rust.Loan =
    io.github.mojri.hesabyar.rust.Loan(
      id = loan.id,
      personName = loan.personName,
      personId = loan.personId,
      loanType = loan.type.name,
      originalAmount = loan.originalAmount,
      remainingAmount = loan.remainingAmount,
      description = loan.description,
      date = loan.date,
      isSettled = loan.isSettled
    )

  fun mapInstallment(inst: Installment): io.github.mojri.hesabyar.rust.Installment =
    io.github.mojri.hesabyar.rust.Installment(
      id = inst.id,
      title = inst.title,
      amount = inst.amount,
      dueDate = inst.dueDate,
      isPaid = inst.isPaid,
      reminderEnabled = inst.reminderEnabled,
      notes = inst.notes,
      bankLoanId = inst.bankLoanId
    )

  fun mapBankLoan(bankLoan: BankLoan): io.github.mojri.hesabyar.rust.BankLoan =
    io.github.mojri.hesabyar.rust.BankLoan(
      id = bankLoan.id,
      bankName = bankLoan.bankName,
      loanName = bankLoan.loanName,
      receivedAmount = bankLoan.receivedAmount,
      monthlyInstallmentAmount = bankLoan.monthlyInstallmentAmount,
      numberOfInstallments = bankLoan.numberOfInstallments,
      totalRepayableAmount = bankLoan.totalRepayableAmount,
      totalInterest = bankLoan.totalInterest,
      startDate = bankLoan.startDate,
      description = bankLoan.description,
      isSettled = bankLoan.isSettled
    )

  fun mapPaymentHistory(ph: PaymentHistory): io.github.mojri.hesabyar.rust.PaymentHistory =
    io.github.mojri.hesabyar.rust.PaymentHistory(
      id = ph.id,
      loanId = ph.loanId,
      amount = ph.amount,
      date = ph.date,
      notes = ph.notes
    )

  fun mapCategory(cat: Category): io.github.mojri.hesabyar.rust.Category =
    io.github.mojri.hesabyar.rust.Category(
      id = cat.id,
      name = cat.name,
      key = cat.key,
      icon = cat.icon,
      color = cat.color,
      categoryType = cat.type.name,
      isDefault = cat.isDefault
    )

  // ===========================================================================
  // Batch mappers: lists of Kotlin domain → Rust types
  // ===========================================================================

  fun mapTransactions(list: List<Transaction>): List<io.github.mojri.hesabyar.rust.Transaction> =
    list.map { mapTransaction(it) }

  fun mapLoans(list: List<Loan>): List<io.github.mojri.hesabyar.rust.Loan> = list.map { mapLoan(it) }

  fun mapInstallments(list: List<Installment>): List<io.github.mojri.hesabyar.rust.Installment> =
    list.map { mapInstallment(it) }

  fun mapBankLoans(list: List<BankLoan>): List<io.github.mojri.hesabyar.rust.BankLoan> = list.map { mapBankLoan(it) }

  fun mapPaymentHistories(list: List<PaymentHistory>): List<io.github.mojri.hesabyar.rust.PaymentHistory> =
    list.map { mapPaymentHistory(it) }

  fun mapCategories(list: List<Category>): List<io.github.mojri.hesabyar.rust.Category> = list.map { mapCategory(it) }

  // ===========================================================================
  // Reverse mappers: Rust → Kotlin domain types
  // ===========================================================================

  private fun toKotlinTransactionType(rustName: String): TransactionType =
    when (rustName) {
      "INCOME", "LOAN_CREDITOR" -> TransactionType.INCOME
      "Transfer", "TRANSFER" -> TransactionType.TRANSFER
      else -> TransactionType.EXPENSE
    }

  fun fromRustTransaction(tx: io.github.mojri.hesabyar.rust.Transaction): Transaction =
    Transaction(
      id = tx.id,
      type = toKotlinTransactionType(tx.txType.name),
      categoryId = tx.categoryId,
      amount = tx.amount,
      description = tx.description,
      personName = tx.personName,
      personId = tx.personId,
      date = tx.date,
      dueDate = tx.dueDate,
      installmentId = tx.installmentId,
      accountId = tx.accountId,
      destinationAccountId = tx.destinationAccountId,
    )

  fun fromRustLoan(loan: io.github.mojri.hesabyar.rust.Loan): Loan =
    Loan(
      id = loan.id,
      personName = loan.personName,
      personId = loan.personId,
      type = LoanType.valueOf(loan.loanType),
      originalAmount = loan.originalAmount,
      remainingAmount = loan.remainingAmount,
      description = loan.description,
      date = loan.date,
      isSettled = loan.isSettled
    )

  fun fromRustInstallment(inst: io.github.mojri.hesabyar.rust.Installment): Installment =
    Installment(
      id = inst.id,
      title = inst.title,
      amount = inst.amount,
      dueDate = inst.dueDate,
      isPaid = inst.isPaid,
      reminderEnabled = inst.reminderEnabled,
      notes = inst.notes,
      bankLoanId = inst.bankLoanId
    )

  fun fromRustBankLoan(bankLoan: io.github.mojri.hesabyar.rust.BankLoan): BankLoan =
    BankLoan(
      id = bankLoan.id,
      bankName = bankLoan.bankName,
      loanName = bankLoan.loanName,
      receivedAmount = bankLoan.receivedAmount,
      monthlyInstallmentAmount = bankLoan.monthlyInstallmentAmount,
      numberOfInstallments = bankLoan.numberOfInstallments,
      totalRepayableAmount = bankLoan.totalRepayableAmount,
      totalInterest = bankLoan.totalInterest,
      startDate = bankLoan.startDate,
      description = bankLoan.description,
      isSettled = bankLoan.isSettled
    )

  fun fromRustCategory(cat: io.github.mojri.hesabyar.rust.Category): Category =
    Category(
      id = cat.id,
      name = cat.name,
      key = cat.key,
      icon = cat.icon,
      color = cat.color,
      type = CategoryType.valueOf(cat.categoryType),
      isDefault = cat.isDefault
    )

  fun fromRustPaymentHistory(ph: io.github.mojri.hesabyar.rust.PaymentHistory): PaymentHistory =
    PaymentHistory(
      id = ph.id,
      loanId = ph.loanId,
      amount = ph.amount,
      date = ph.date,
      notes = ph.notes ?: ""
    )

  fun fromRustPaymentHistories(list: List<io.github.mojri.hesabyar.rust.PaymentHistory>): List<PaymentHistory> =
    list.map { fromRustPaymentHistory(it) }

  fun fromRustAccount(rust: io.github.mojri.hesabyar.rust.Account): AccountEntity {
    val now = System.currentTimeMillis()
    return AccountEntity(
      id = rust.id,
      name = rust.name,
      type = AccountType.safeValueOf(rust.accountType),
      bankName = rust.bankName,
      cardNumber = rust.cardNumber,
      accountNumber = rust.accountNumber,
      iban = rust.iban,
      initialBalance = rust.initialBalance,
      color = rust.color,
      icon = rust.icon,
      isArchived = rust.isArchived,
      displayOrder = rust.displayOrder,
      createdAt = if (rust.createdAt != 0L) rust.createdAt else now,
      updatedAt = if (rust.updatedAt != 0L) rust.updatedAt else now,
    )
  }

  fun fromRustAccounts(list: List<io.github.mojri.hesabyar.rust.Account>): List<AccountEntity> =
    list.map { fromRustAccount(it) }

  fun fromRustPerson(rust: io.github.mojri.hesabyar.rust.Person): Person {
    val display = PersonNameNormalizer.displayForm(rust.name)
    val normalizedName = PersonNameNormalizer.normalize(display)
    require(normalizedName.isNotEmpty()) { "Person ${rust.id} normalizes to empty (name=\"${rust.name}\")" }
    return Person(
      id = rust.id,
      name = rust.name,
      normalizedName = normalizedName,
      phone = rust.phone,
      notes = rust.notes,
      createdAt = if (rust.createdAt != 0L) rust.createdAt else System.currentTimeMillis(),
      isArchived = rust.isArchived
    )
  }

  fun fromRustPersons(list: List<io.github.mojri.hesabyar.rust.Person>): List<Person> = list.map { fromRustPerson(it) }

  fun mapPerson(person: Person): io.github.mojri.hesabyar.rust.Person {
    val normalizedName =
      PersonNameNormalizer.normalize(PersonNameNormalizer.displayForm(person.name))
    require(normalizedName.isNotEmpty()) {
      "Person ${person.id} has empty normalizedName after re-derivation"
    }
    return io.github.mojri.hesabyar.rust.Person(
      id = person.id,
      name = person.name,
      normalizedName = normalizedName,
      phone = person.phone,
      notes = person.notes,
      createdAt = person.createdAt,
      isArchived = person.isArchived
    )
  }

  fun mapPersons(list: List<Person>): List<io.github.mojri.hesabyar.rust.Person> = list.map { mapPerson(it) }

  fun mapAccount(account: AccountEntity): io.github.mojri.hesabyar.rust.Account =
    io.github.mojri.hesabyar.rust.Account(
      id = account.id,
      name = account.name,
      accountType = account.type.name,
      bankName = account.bankName,
      cardNumber = account.cardNumber,
      accountNumber = account.accountNumber,
      iban = account.iban,
      initialBalance = account.initialBalance,
      color = account.color,
      icon = account.icon,
      isArchived = account.isArchived,
      displayOrder = account.displayOrder,
      createdAt = account.createdAt,
      updatedAt = account.updatedAt,
    )

  fun mapAccounts(accounts: List<AccountEntity>): List<io.github.mojri.hesabyar.rust.Account> =
    accounts.map { mapAccount(it) }

  /** Resolve an account's configured color from the DB entities, falling back
   *  to the canonical default account color when the account is absent. */
  private fun accountColorFor(
    accountId: Long,
    accounts: List<AccountEntity>
  ): Long = accounts.firstOrNull { it.id == accountId }?.color ?: AccountEntity.DEFAULT_COLOR

  fun mapAccountDashboardSummary(
    rust: io.github.mojri.hesabyar.rust.AccountDashboardSummary,
    accounts: List<AccountEntity> = emptyList(),
  ): AccountDashboardSummary {
    val accountColor = accountColorFor(rust.accountId, accounts)
    return AccountDashboardSummary(
      accountId = rust.accountId,
      accountName = rust.accountName,
      accountType = AccountType.safeValueOf(rust.accountType),
      balance = rust.balance,
      monthlyIncome = rust.monthlyIncome,
      monthlyExpenses = rust.monthlyExpenses,
      accountColor = accountColor,
      monthlyDelta = rust.monthlyDelta,
    )
  }

  fun mapAccountAnalytics(rust: io.github.mojri.hesabyar.rust.AccountAnalytics): AccountAnalytics =
    AccountAnalytics(
      accountId = rust.accountId,
      accountName = rust.accountName,
      monthlyData = rust.monthlyData.map { mapMonthlyData(it) },
      categoryBreakdown = rust.categoryBreakdown.map { mapCategoryBreakdown(it) }
    )
}
