package io.github.mojri.hesabyar.domain.usecase

import io.github.mojri.hesabyar.R
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.BackupValidationResult
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.DEFAULT_ACCOUNT_ID
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [BackupJsonValidator.validateBackupKotlin] — the Kotlin-only
 * validation path — DIRECTLY, without the Rust core. This is the only way to
 * reach the fallback in unit tests: the host Rust library always loads in the
 * unit-test JVM, so [BackupJsonValidator.validateBackup] would take its Rust
 * branch and the kotlinFallback* tests in
 * [ManageBackupUseCaseValidationTest] never actually exercise this code.
 *
 * Every case mirrors a pinned Rust rule so the two validators cannot drift:
 * see validate_accounts_and_references / validate_backup_payload in
 * rust/hesabyar-core/src/validation.rs.
 */
class BackupJsonValidatorKotlinFallbackTest {
  private val validator = BackupJsonValidator()

  private fun validate(backup: BackupPayload): BackupValidationResult = validator.validateBackupKotlin(backup)

  private fun transferPayload(destinationAccountId: Long?): BackupPayload =
    BackupPayload(
      accounts = listOf(AccountEntity(id = 1L, name = "اصلی", type = AccountType.BANK)),
      transactions =
        listOf(
          Transaction(
            type = TransactionType.TRANSFER,
            categoryId = 1L,
            amount = 1_000L,
            description = "transfer",
            date = 1_700_000_000_000L,
            accountId = 1L,
            destinationAccountId = destinationAccountId
          )
        )
    )

  @Test
  fun validPayloadIsValid() {
    val payload =
      BackupPayload(
        accounts = listOf(AccountEntity(id = 1L, name = "اصلی", type = AccountType.BANK)),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              accountId = 1L
            )
          ),
        categories =
          listOf(
            Category(id = 1L, name = "خوراک", key = "food", icon = "i", color = 0xFF0000L, type = CategoryType.EXPENSE)
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Valid", result is BackupValidationResult.Valid)
  }

  @Test
  fun rejectsTransactionWithNonexistentAccountId() {
    val payload =
      BackupPayload(
        accounts = listOf(AccountEntity(id = 1L, name = "اصلی", type = AccountType.BANK)),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              accountId = 99L
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for non-existent account", result is BackupValidationResult.Invalid)
  }

  @Test
  fun rejectsTransferWithNonexistentDestinationAccount() {
    val result = validate(transferPayload(destinationAccountId = 99L))
    assertTrue(
      "expected $result to be Invalid for non-existent destination account",
      result is BackupValidationResult.Invalid
    )
  }

  @Test
  fun rejectsTransferWithNullDestination() {
    // Destination is null — transfer structure validation must reject it.
    val result = validate(transferPayload(destinationAccountId = null))
    assertTrue(
      "expected $result to be Invalid for null destination account",
      result is BackupValidationResult.Invalid
    )
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected the null-destination error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_transfer_no_destination}>" }
    )
  }

  @Test
  fun rejectsTransferWithSameSourceAndDestination() {
    // Source account equals destination — transfer structure validation must reject it.
    val result = validate(transferPayload(destinationAccountId = 1L))
    assertTrue(
      "expected $result to be Invalid for same source and destination",
      result is BackupValidationResult.Invalid
    )
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected the same-source-destination error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_transfer_same_source_destination}>" }
    )
  }

  @Test
  fun rejectsTransactionWithNonexistentPositiveCategory() {
    val payload =
      BackupPayload(
        categories =
          listOf(
            Category(id = 1L, name = "خوراک", key = "food", icon = "i", color = 0xFF0000L, type = CategoryType.EXPENSE)
          ),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 99L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Invalid for non-existent category",
      result is BackupValidationResult.Invalid
    )
  }

  @Test
  fun toleratesTransactionWithZeroCategoryId() {
    val payload =
      BackupPayload(
        categories =
          listOf(
            Category(id = 1L, name = "خوراک", key = "food", icon = "i", color = 0xFF0000L, type = CategoryType.EXPENSE)
          ),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 0L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Valid for legacy category_id=0", result is BackupValidationResult.Valid)
  }

  @Test
  fun rejectsPaymentHistoryWithNonexistentPositiveLoan() {
    val payload =
      BackupPayload(
        paymentHistories =
          listOf(PaymentHistory(id = 1L, loanId = 99L, amount = 100_000L, date = 1_700_000_000_000L))
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for non-existent loan", result is BackupValidationResult.Invalid)
  }

  @Test
  fun rejectsPaymentHistoryWithZeroLoanId() {
    // Field-level rule mirroring Rust validate_payment_history: loan_id must be
    // positive (test_payment_history_zero_loan_id_rejected). The zero tolerance
    // only applies to the cross-reference lookup itself.
    val payload =
      BackupPayload(
        paymentHistories =
          listOf(PaymentHistory(id = 1L, loanId = 0L, amount = 100_000L, date = 1_700_000_000_000L))
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for zero loan id", result is BackupValidationResult.Invalid)
  }

  @Test
  fun toleratesLegacyAccountIdWhenAccountsEmpty() {
    val payload =
      BackupPayload(
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              accountId = DEFAULT_ACCOUNT_ID
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Valid for legacy default account", result is BackupValidationResult.Valid)
  }

  @Test
  fun rejectsLegacyTransferToNonLegacyDestination() {
    // Legacy backup (no accounts list): the destination check must run
    // independently of the source check. Even with the legacy default source
    // account, a non-legacy destination is an orphan — mirroring Rust
    // test_backup_rejects_non_legacy_dest_account_when_accounts_empty.
    val payload =
      BackupPayload(
        transactions =
          listOf(
            Transaction(
              type = TransactionType.TRANSFER,
              categoryId = 1L,
              amount = 1_000L,
              description = "transfer",
              date = 1_700_000_000_000L,
              accountId = DEFAULT_ACCOUNT_ID,
              destinationAccountId = 99L
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Invalid for non-legacy destination account",
      result is BackupValidationResult.Invalid
    )
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected a destination-account error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_transaction_invalid_legacy_destination_account}>" }
    )
  }

  @Test
  fun rejectsTransactionWithNegativeCategoryId() {
    // Field-level rule mirroring Rust validate_transaction: category_id must
    // not be negative (test_transaction_negative_category_rejected).
    val payload =
      BackupPayload(
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = -1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for negative category id", result is BackupValidationResult.Invalid)
  }

  @Test
  fun acceptsConsistentBankLoan() {
    // Mirrors Rust test_valid_bank_loan (validation.rs): monthly 1M x 12 =
    // 12M repayable, interest = 12M - 10M = 2M.
    val payload =
      BackupPayload(
        bankLoans =
          listOf(
            BankLoan(
              bankName = "بانک ملی",
              loanName = "x",
              receivedAmount = 10_000_000L,
              monthlyInstallmentAmount = 1_000_000L,
              numberOfInstallments = 12,
              totalRepayableAmount = 12_000_000L,
              totalInterest = 2_000_000L,
              startDate = 1_700_000_000_000L,
              description = ""
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Valid for a consistent bank loan", result is BackupValidationResult.Valid)
  }

  @Test
  fun rejectsBankLoanWithRepayableMismatch() {
    // Mirrors Rust test_bank_loan_repayable_mismatch_rejected: total_repayable
    // must equal monthly_installment_amount * number_of_installments.
    val payload =
      BackupPayload(
        bankLoans =
          listOf(
            BankLoan(
              bankName = "بانک ملی",
              loanName = "x",
              receivedAmount = 10_000_000L,
              monthlyInstallmentAmount = 1_000_000L,
              numberOfInstallments = 12,
              totalRepayableAmount = 12_000_001L, // 1 off from 12 * 1_000_000
              totalInterest = 2_000_000L,
              startDate = 1_700_000_000_000L,
              description = ""
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for a repayable mismatch", result is BackupValidationResult.Invalid)
  }

  @Test
  fun rejectsBankLoanWithInterestMismatch() {
    // Mirrors Rust test_bank_loan_interest_mismatch_rejected: total_interest
    // must equal total_repayable_amount - received_amount.
    val payload =
      BackupPayload(
        bankLoans =
          listOf(
            BankLoan(
              bankName = "بانک ملی",
              loanName = "x",
              receivedAmount = 10_000_000L,
              monthlyInstallmentAmount = 1_000_000L,
              numberOfInstallments = 12,
              totalRepayableAmount = 12_000_000L,
              totalInterest = 2_000_001L, // 1 off from 12_000_000 - 10_000_000
              startDate = 1_700_000_000_000L,
              description = ""
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for an interest mismatch", result is BackupValidationResult.Invalid)
  }

  @Test
  fun rejectsBankLoanReceivedExceedsRepayable() {
    // Mirrors Rust test_bank_loan_received_exceeds_repayable_rejected:
    // total_repayable_amount must not be less than received_amount.
    val payload =
      BackupPayload(
        bankLoans =
          listOf(
            BankLoan(
              bankName = "بانک ملی",
              loanName = "x",
              receivedAmount = 20_000_000L, // exceeds the 12M repayable
              monthlyInstallmentAmount = 1_000_000L,
              numberOfInstallments = 12,
              totalRepayableAmount = 12_000_000L,
              totalInterest = 0L,
              startDate = 1_700_000_000_000L,
              description = ""
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Invalid when received exceeds repayable",
      result is BackupValidationResult.Invalid
    )
  }

  @Test
  fun rejectsBankLoanRepayableOverflow() {
    // Mirrors Rust checked_mul overflow rejection in validate_bank_loan
    // (test_bank_loan_repayable_overflow_rejected): the product
    // monthly_installment_amount * number_of_installments must not overflow i64.
    val payload =
      BackupPayload(
        bankLoans =
          listOf(
            BankLoan(
              bankName = "بانک ملی",
              loanName = "x",
              receivedAmount = 1L,
              monthlyInstallmentAmount = Long.MAX_VALUE,
              numberOfInstallments = 2, // Long.MAX_VALUE * 2 overflows
              totalRepayableAmount = 1L,
              totalInterest = 0L,
              startDate = 1_700_000_000_000L,
              description = ""
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for a repayable overflow", result is BackupValidationResult.Invalid)
  }

  @Test
  fun rejectsDuplicateAccountIds() {
    // Mirrors Rust test_backup_rejects_duplicate_account_ids (validation.rs:1119):
    // duplicate account IDs must be detected before toSet() collapses them.
    val payload =
      BackupPayload(
        accounts =
          listOf(
            AccountEntity(id = 1L, name = "Main", type = AccountType.BANK),
            AccountEntity(id = 1L, name = "Duplicate", type = AccountType.CASH_WALLET)
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Invalid for duplicate account ids", result is BackupValidationResult.Invalid)
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected a duplicate-account-id error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_duplicate_account_id}>" }
    )
  }

  @Test
  fun rejectsBothDuplicateIdsAndInvalidReferences() {
    // Mirrors the Rust behavior in validate_accounts_and_references (validation.rs:322-406)
    // where duplicate-ID errors are recorded but validation continues, producing
    // combined error output. After the early-return removal, the Kotlin validator
    // must also check transaction account references even when duplicate IDs exist.
    val payload =
      BackupPayload(
        accounts =
          listOf(
            AccountEntity(id = 1L, name = "Main", type = AccountType.BANK),
            AccountEntity(id = 1L, name = "Duplicate", type = AccountType.CASH_WALLET)
          ),
        transactions =
          listOf(
            // accountId 99 does not resolve to any declared account.
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              accountId = 99L
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Invalid for both duplicate IDs and invalid references",
      result is BackupValidationResult.Invalid
    )
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected a duplicate-account-id error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_duplicate_account_id}>" }
    )
    assertTrue(
      "expected an invalid-account-reference error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_transaction_invalid_account}>" }
    )
  }

  // =====================================================================
  // Person cross-reference validation (mirrors Rust validate_persons)
  // =====================================================================

  @Test
  fun acceptsDeclaredPersonIdOnLoan() {
    val payload =
      BackupPayload(
        persons =
          listOf(Person(id = 1L, name = "Ali", normalizedName = "ali")),
        loans =
          listOf(
            Loan(
              personName = "Ali",
              personId = 1L,
              type = LoanType.DEBTOR,
              originalAmount = 1_000L,
              remainingAmount = 1_000L,
              description = "test"
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Valid for declared personId on loan", result is BackupValidationResult.Valid)
  }

  @Test
  fun acceptsDeclaredPersonIdOnTransaction() {
    val payload =
      BackupPayload(
        persons =
          listOf(Person(id = 1L, name = "Ali", normalizedName = "ali")),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              personId = 1L
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Valid for declared personId on transaction",
      result is BackupValidationResult.Valid
    )
  }

  @Test
  fun rejectsUnresolvedPositivePersonIdOnLoan() {
    val payload =
      BackupPayload(
        persons =
          listOf(Person(id = 1L, name = "Ali", normalizedName = "ali")),
        loans =
          listOf(
            Loan(
              personName = "Unknown",
              personId = 99L, // references a non-existent person
              type = LoanType.DEBTOR,
              originalAmount = 1_000L,
              remainingAmount = 1_000L,
              description = "test"
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Invalid for unresolved personId on loan",
      result is BackupValidationResult.Invalid
    )
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected loan-invalid-person error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_loan_invalid_person}>" }
    )
  }

  @Test
  fun rejectsUnresolvedPositivePersonIdOnTransaction() {
    val payload =
      BackupPayload(
        persons =
          listOf(Person(id = 1L, name = "Ali", normalizedName = "ali")),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              personId = 99L // references a non-existent person
            )
          )
      )

    val result = validate(payload)
    assertTrue(
      "expected $result to be Invalid for unresolved personId on transaction",
      result is BackupValidationResult.Invalid
    )
    val errors = (result as BackupValidationResult.Invalid).errors
    assertTrue(
      "expected transaction-invalid-person error, got: $errors",
      errors.any { it == "<string-res-${R.string.backup_error_transaction_invalid_person}>" }
    )
  }

  @Test
  fun toleratesNullPersonIdOnLoanAndTransaction() {
    val payload =
      BackupPayload(
        persons = listOf(Person(id = 1L, name = "Ali", normalizedName = "ali")),
        loans =
          listOf(
            Loan(
              personName = "Ali",
              personId = null, // null is a legacy default, tolerated
              type = LoanType.DEBTOR,
              originalAmount = 1_000L,
              remainingAmount = 1_000L,
              description = "test"
            )
          ),
        transactions =
          listOf(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = 1L,
              amount = 1_000L,
              description = "t",
              date = 1_700_000_000_000L,
              personId = null // null is a legacy default, tolerated
            )
          )
      )

    val result = validate(payload)
    assertTrue("expected $result to be Valid for null personId", result is BackupValidationResult.Valid)
  }
}
