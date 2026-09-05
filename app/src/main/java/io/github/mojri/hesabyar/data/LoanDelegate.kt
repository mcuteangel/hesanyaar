package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

internal class LoanDelegate(
  private val loanDao: LoanDao,
  private val paymentHistoryDao: PaymentHistoryDao,
  private val transactionDao: TransactionDao,
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
      if (loan.remainingAmount <= 0L) return@withTransaction false
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
