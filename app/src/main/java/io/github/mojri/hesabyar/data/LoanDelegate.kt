package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

internal class LoanDelegate(
  private val loanDao: LoanDao,
  private val paymentHistoryDao: PaymentHistoryDao,
  private val transactionDao: TransactionDao,
  private val transactionLinkDao: TransactionLinkDao,
  private val categoryDao: CategoryDao,
  private val database: AppDatabase
) : LoanOps {
  override val allLoans: Flow<List<Loan>> = loanDao.getAllLoans()

  override suspend fun insertLoan(loan: Loan): Long = loanDao.insertLoan(loan)

  override suspend fun updateLoan(loan: Loan) {
    loanDao.updateLoan(loan)
  }

  override suspend fun deleteLoan(loan: Loan) {
    database.withTransaction {
      // Payments made through addPaymentToLoan each created a standalone
      // expense/income Transaction. Delete them together with the payment
      // history, or reports keep counting money for a loan that no longer
      // exists. Each generated row is identified by the same fields the
      // creator used: personName, Loans category, amount and date.
      val loansCategoryId = categoryDao.getCategoryByKey("Loans")?.id
      if (loansCategoryId != null) {
        paymentHistoryDao
          .getPaymentHistoriesForLoanSync(loan.id)
          .forEach { payment ->
            transactionLinkDao.deleteLoanPaymentTransaction(
              personName = loan.personName,
              categoryId = loansCategoryId,
              amount = payment.amount,
              date = payment.date
            )
          }
      }
      paymentHistoryDao.deletePaymentHistoryForLoan(loan.id)
      loanDao.deleteLoan(loan)
    }
  }

  override fun getPaymentHistoryForLoan(loanId: Long): Flow<List<PaymentHistory>> =
    paymentHistoryDao.getPaymentHistoryForLoan(loanId)

  override suspend fun addPaymentToLoan(
    loanId: Long,
    amount: Long,
    notes: String,
    customDate: Long?
  ): Boolean {
    if (amount <= 0L) return false
    return database.withTransaction {
      val loan = loanDao.getLoanById(loanId) ?: return@withTransaction false
      val loansCategory = categoryDao.getCategoryByKey("Loans") ?: return@withTransaction false
      // A settled loan must never accept further repayment: a positive
      // remainingAmount on a settled row is stale data, and paying it would
      // resurrect the loan by flipping isSettled back to false.
      if (loan.isSettled || loan.remainingAmount <= 0L) return@withTransaction false
      if (amount > loan.remainingAmount) return@withTransaction false
      val newRemaining = loan.remainingAmount - amount
      val isSettled = newRemaining == 0L
      val date = customDate ?: System.currentTimeMillis()
      val updatedLoan = loan.copy(remainingAmount = newRemaining, isSettled = isSettled)
      val desc =
        if (loan.type == LoanType.CREDITOR) {
          "بازپرداخت بدهی به ${loan.personName} - $notes"
        } else {
          "دریافت بازپرداخت از ${loan.personName} - $notes"
        }
      val tx =
        Transaction(
          type = if (loan.type == LoanType.CREDITOR) TransactionType.EXPENSE else TransactionType.INCOME,
          categoryId = loansCategory.id,
          amount = amount,
          description = desc,
          personName = loan.personName,
          personId = loan.personId,
          date = date
        )
      val payment = PaymentHistory(loanId = loanId, amount = amount, notes = notes, date = date)
      loanDao.updateLoan(updatedLoan)
      paymentHistoryDao.insertPayment(payment)
      transactionDao.insertTransaction(tx)
      true
    }
  }
}
