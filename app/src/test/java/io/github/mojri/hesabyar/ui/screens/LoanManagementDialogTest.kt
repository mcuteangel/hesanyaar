package io.github.mojri.hesabyar.ui.screens

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.HesabyarApp
import io.github.mojri.hesabyar.R
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.ui.CurrencyFormatter
import io.github.mojri.hesabyar.ui.CurrencyUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the Toman overflow guard in [AddLoanDialog] and
 * [EditLoanDialog]. When the display-unit input exceeds `Long.MAX_VALUE / 10`,
 * the overflow error message must be shown and [onConfirm]/[onUpdate] must
 * stay uncalled. Valid inputs must reach the callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LoanManagementDialogTest {
  @get:Rule
  val composeRule = createComposeRule()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val maxTomanDisplay = Long.MAX_VALUE / 10L
  private val tooLargeAmount = (maxTomanDisplay + 1).toString()

  @Before
  fun setUp() {
    // Force the Kotlin fallback path so tests don't depend on the native lib.
    HesabyarApp.setRustInitializedForTesting(false)
    CurrencyFormatter.setUnit(CurrencyUnit.TOMAN)
  }

  @After
  fun tearDown() {
    HesabyarApp.setRustInitializedForTesting(null)
    CurrencyFormatter.setUnit(CurrencyUnit.TOMAN)
  }

  @Test
  fun addLoanDialogShowsOverflowErrorAndDoesNotConfirm() {
    val confirmed = mutableStateOf(false)

    composeRule.setContent {
      AddLoanDialog(
        initialType = LoanType.DEBTOR,
        showMessage = { msg ->
          assertEquals(
            "expected overflow error message",
            context.getString(R.string.loan_amount_too_large),
            msg
          )
        },
        onConfirm = { _, _, _, _, _ -> confirmed.value = true },
        onDismiss = {}
      )
    }

    // Person name is required for the valid path, but overflow takes priority.
    composeRule.onNode(hasText("نام شخص طرف حساب").and(hasSetTextAction())).performTextInput("Ali")
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextClearance()
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextInput(tooLargeAmount)
    composeRule.onNodeWithText("ثبت و ذخیره").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { !confirmed.value }
    assertFalse("onConfirm must not be called for overflow", confirmed.value)
  }

  @Test
  fun addLoanDialogAcceptsValidInputAndConfirms() {
    val confirmed = mutableStateOf(false)
    val capturedAmount = mutableStateOf<Long?>(null)

    composeRule.setContent {
      AddLoanDialog(
        initialType = LoanType.DEBTOR,
        showMessage = { /* no error expected */ },
        onConfirm = { _, _, amountRial, _, _ ->
          capturedAmount.value = amountRial
          confirmed.value = true
        },
        onDismiss = {}
      )
    }

    composeRule.onNode(hasText("نام شخص طرف حساب").and(hasSetTextAction())).performTextInput("Ali")
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextClearance()
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextInput("100")
    composeRule.onNodeWithText("ثبت و ذخیره").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { confirmed.value }
    assertTrue("onConfirm must be called for valid input", confirmed.value)
    assertEquals("100 Toman × 10 = 1000 Rial", 1000L, capturedAmount.value)
  }

  @Test
  fun editLoanDialogShowsOverflowErrorAndDoesNotUpdate() {
    val updated = mutableStateOf(false)

    val originalLoan =
      Loan(
        id = 1L,
        personName = "Ali",
        personId = null,
        type = LoanType.DEBTOR,
        originalAmount = 5_000L,
        remainingAmount = 5_000L,
        description = "test",
        date = 1_700_000_000_000L,
        isSettled = false
      )

    composeRule.setContent {
      EditLoanDialog(
        loan = originalLoan,
        onUpdate = { updated.value = true },
        showMessage = { msg ->
          assertEquals(
            "expected overflow error message",
            context.getString(R.string.loan_amount_too_large),
            msg
          )
        },
        onDismiss = {}
      )
    }

    // Clear the pre-filled amount and type an overflowing value.
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextClearance()
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextClearance()
    composeRule.onNode(hasText("مبلغ قرض (تومان)").and(hasSetTextAction())).performTextInput(tooLargeAmount)
    composeRule.onNodeWithText("ذخیره تغییرات").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { !updated.value }
    assertFalse("onUpdate must not be called for overflow", updated.value)
  }

  @Test
  fun editLoanDialogAcceptsValidInputAndUpdates() {
    val updated = mutableStateOf(false)

    val originalLoan =
      Loan(
        id = 1L,
        personName = "Ali",
        personId = null,
        type = LoanType.DEBTOR,
        originalAmount = 5_000L,
        remainingAmount = 5_000L,
        description = "test",
        date = 1_700_000_000_000L,
        isSettled = false
      )

    composeRule.setContent {
      EditLoanDialog(
        loan = originalLoan,
        onUpdate = { updated.value = true },
        showMessage = { /* no error expected */ },
        onDismiss = {}
      )
    }

    // Leave the pre-filled amount unchanged (it displays correctly within range).
    composeRule.onNodeWithText("ذخیره تغییرات").performClick()

    composeRule.waitUntil(timeoutMillis = 5_000) { updated.value }
    assertTrue("onUpdate must be called for valid input", updated.value)
  }
}
