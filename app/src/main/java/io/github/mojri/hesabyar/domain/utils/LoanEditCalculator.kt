package io.github.mojri.hesabyar.domain.utils

import io.github.mojri.hesabyar.data.Loan

/**
 * Pure repayment-preserving recalculation for an edited loan principal.
 *
 * Keeps already-repaid money intact: the new remaining amount shrinks only by
 * the difference between the new and the old principal. No UI or persistence
 * state is touched here (extracted from `LoanManagementScreen.submitLoanEdit`).
 */
object LoanEditCalculator {
  data class Result(
    val originalAmount: Long,
    val remainingAmount: Long,
    val isSettled: Boolean
  )

  fun recompute(
    loan: Loan,
    newAmountRial: Long
  ): Result {
    val paidSoFar = (loan.originalAmount - loan.remainingAmount).coerceAtLeast(0L)
    val newRemaining = (newAmountRial - paidSoFar).coerceAtLeast(0L)
    return Result(newAmountRial, newRemaining, newRemaining == 0L)
  }
}
