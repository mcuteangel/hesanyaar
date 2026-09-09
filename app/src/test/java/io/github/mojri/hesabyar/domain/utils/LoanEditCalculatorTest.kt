package io.github.mojri.hesabyar.domain.utils

import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanEditCalculatorTest {
  private fun loan(
    original: Long = 1_000_000L,
    remaining: Long = 600_000L
  ) = Loan(
    id = 1L,
    personName = "علی",
    personId = 1L,
    type = LoanType.DEBTOR,
    originalAmount = original,
    remainingAmount = remaining,
    description = "",
    date = 1L,
    isSettled = false
  )

  @Test
  fun recomputePreservesRepaidAmount() {
    // paidSoFar = 400_000, newAmount 2_000_000 → newRemaining 1_600_000
    val result = LoanEditCalculator.recompute(loan(), 2_000_000L)
    assertEquals(2_000_000L, result.originalAmount)
    assertEquals(1_600_000L, result.remainingAmount)
    assertFalse(result.isSettled)
  }

  @Test
  fun recomputeUnchangedAmountPreservesRemaining() {
    val l = loan(original = 1_000_000L, remaining = 600_000L)
    val result = LoanEditCalculator.recompute(l, 1_000_000L)
    assertEquals(600_000L, result.remainingAmount)
    assertEquals(1_000_000L, result.originalAmount)
  }

  @Test
  fun recomputeWhenFullyPaidAndAmountReducedToPaidSoFarSettles() {
    val l = loan(original = 1_000_000L, remaining = 600_000L) // paid 400k
    val result = LoanEditCalculator.recompute(l, 400_000L)
    assertEquals(0L, result.remainingAmount)
    assertTrue(result.isSettled)
  }

  @Test
  fun recomputeWhenNewAmountBelowPaidSoFarClampsToZero() {
    val l = loan(original = 1_000_000L, remaining = 200_000L) // paid 800k
    val result = LoanEditCalculator.recompute(l, 500_000L)
    assertEquals(0L, result.remainingAmount)
    assertTrue(result.isSettled)
  }

  @Test
  fun recomputeZeroRemainingReopensLoanByNewPrincipal() {
    val l = loan(original = 1_000_000L, remaining = 0L) // fully repaid
    val result = LoanEditCalculator.recompute(l, 1_500_000L)
    assertEquals(500_000L, result.remainingAmount)
    assertFalse(result.isSettled)
  }
}
