package io.github.mojri.hesabyar.domain.usecase

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import io.github.mojri.hesabyar.R
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.BackupValidationResult
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.rust.RustBridge
import io.github.mojri.hesabyar.rust.RustMappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "BackupJsonValidator"

/**
 * Validates parsed backup payloads (Rust-first with a Kotlin fallback, matching
 * the parser's strategy). Field-level checks live in per-collection private
 * helpers so the class stays small.
 */
class BackupJsonValidator(
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val application: Context? = null
) {
  /**
   * Resolves a user-facing validation message from `strings.xml`. The application
   * [Context] is injected by the DI layer (via [ManageBackupUseCase]); plain-JVM
   * unit tests construct the validator without one, in which case the message
   * degrades to a stable resource-id sentinel that no test asserts — production
   * always wires the Context, so users always see the localized string. A
   * missing Context in any non-test path is a DI wiring bug: warn once so it
   * surfaces in logcat instead of silently shipping sentinel text to users.
   */
  private fun message(
    @StringRes key: Int,
    vararg args: Any
  ): String {
    val context = application
    if (context == null) {
      if (!warnedMissingContext) {
        warnedMissingContext = true
        Log.w(
          TAG,
          "BackupJsonValidator constructed without an application Context (DI wiring bug); " +
            "validation messages will surface as resource-id sentinels"
        )
      }
      return "<string-res-$key>"
    }
    return context.getString(key, *args)
  }

  private var warnedMissingContext = false

  suspend fun validateBackup(backup: BackupPayload): BackupValidationResult =
    withContext(dispatcher) {
      if (RustBridge.isAvailable) {
        try {
          val rustResult = RustBridge.validateBackupPayloadSync(backup.toRustPayload())

          if (rustResult.isValid) {
            BackupValidationResult.Valid
          } else {
            BackupValidationResult.Invalid(rustResult.errors)
          }
        } catch (e: IllegalArgumentException) {
          // Mapping to Rust payload failed (e.g. from mapAccounts/mapCategories);
          // fall back to Kotlin validation instead of escaping as an unhandled exception.
          Log.w(TAG, "Rust→Kotlin mapping failed during validation, falling back to Kotlin", e)
          validateBackupKotlin(backup)
        }
      } else {
        // Rust unavailable — fall back to local Kotlin validation
        validateBackupKotlin(backup)
      }
    }

  /**
   * Kotlin-only validation path (Rust unavailable or mapping failed).
   *
   * `internal` (not private) so unit tests can exercise the fallback directly:
   * the host Rust library always loads in the unit-test JVM, so
   * [validateBackup]'s Rust branch is the only one reachable through the public
   * entry point, and the kotlinFallback* tests would otherwise silently test
   * the Rust core instead of this code.
   */
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun validateBackupKotlin(backup: BackupPayload): BackupValidationResult {
    val errors = mutableListOf<String>()

    if (backup.version <= 0) errors.add(message(R.string.backup_validation_version_invalid))
    if (backup.appVersion.isBlank()) errors.add(message(R.string.backup_validation_app_version_invalid))
    if (backup.timestamp <= 0) errors.add(message(R.string.backup_validation_timestamp_invalid))

    validateBackupTransactions(backup.transactions, errors)
    validateBackupLoans(backup.loans, errors)
    validateBackupInstallments(backup.installments, errors)
    validateBackupCategories(backup.categories, errors)
    validateBackupPaymentHistories(backup.paymentHistories, errors)
    validateBackupBankLoans(backup.bankLoans, errors)
    validateBackupAccounts(backup.accounts, errors)
    PersonBackupValidator.validate(backup.persons, errors) { resId, args -> message(resId, *args) }
    BackupReferenceValidator { resId, args -> message(resId, *args) }.validate(backup, errors)

    return if (errors.isEmpty()) {
      BackupValidationResult.Valid
    } else {
      BackupValidationResult.Invalid(errors)
    }
  }

  private fun validateBackupTransactions(
    transactions: List<Transaction>,
    errors: MutableList<String>
  ) {
    transactions.forEachIndexed { i, t ->
      if (t.amount <= 0) errors.add(message(R.string.backup_validation_tx_amount_invalid, i))
      if (t.date <= 0) errors.add(message(R.string.backup_validation_tx_date_invalid, i))
      // Mirrors Rust validate_transaction: negative category_id can never
      // reference a valid category; zero and positive are left to the
      // cross-reference checks (legacy zero is tolerated).
      if (t.categoryId < 0) errors.add(message(R.string.backup_validation_tx_category_invalid, i))
    }
  }

  private fun validateBackupLoans(
    loans: List<Loan>,
    errors: MutableList<String>
  ) {
    loans.forEachIndexed { i, l ->
      if (l.personName.isBlank()) errors.add(message(R.string.backup_validation_loan_person_blank, i))
      if (l.date <= 0) errors.add(message(R.string.backup_validation_loan_date_invalid, i))
      if (l.originalAmount <= 0) errors.add(message(R.string.backup_validation_loan_original_invalid, i))
      if (l.remainingAmount < 0) errors.add(message(R.string.backup_validation_loan_remaining_invalid, i))
    }
  }

  private fun validateBackupInstallments(
    installments: List<Installment>,
    errors: MutableList<String>
  ) {
    installments.forEachIndexed { i, installment ->
      if (installment.title.isBlank()) errors.add(message(R.string.backup_validation_installment_title_blank, i))
      if (installment.amount <= 0) errors.add(message(R.string.backup_validation_installment_amount_invalid, i))
      if (installment.dueDate <= 0) errors.add(message(R.string.backup_validation_installment_due_invalid, i))
    }
  }

  private fun validateBackupCategories(
    categories: List<Category>,
    errors: MutableList<String>
  ) {
    categories.forEachIndexed { i, category ->
      if (category.name.isBlank()) errors.add(message(R.string.backup_validation_category_name_blank, i))
    }
  }

  private fun validateBackupPaymentHistories(
    payments: List<PaymentHistory>,
    errors: MutableList<String>
  ) {
    payments.forEachIndexed { i, payment ->
      if (payment.amount <= 0) errors.add(message(R.string.backup_validation_payment_amount_invalid, i))
      if (payment.date <= 0) errors.add(message(R.string.backup_validation_payment_date_invalid, i))
      // Mirrors Rust validate_payment_history: loan_id must be positive. The
      // zero tolerance in the loan cross-reference only applies to the lookup
      // itself, not to the field rule.
      if (payment.loanId <= 0) errors.add(message(R.string.backup_validation_payment_loan_id_invalid, i))
    }
  }

  private fun validateBackupBankLoans(
    bankLoans: List<BankLoan>,
    errors: MutableList<String>
  ) {
    bankLoans.forEachIndexed { i, bankLoan ->
      if (bankLoan.bankName.isBlank()) errors.add(message(R.string.backup_validation_bankloan_name_blank, i))
      if (bankLoan.receivedAmount <= 0) errors.add(message(R.string.backup_validation_bankloan_received_invalid, i))
      if (bankLoan.numberOfInstallments <= 0) {
        errors.add(message(R.string.backup_validation_bankloan_installment_count_invalid, i))
      }
      if (bankLoan.monthlyInstallmentAmount <= 0) {
        errors.add(message(R.string.backup_validation_bankloan_monthly_invalid, i))
      }
      if (bankLoan.startDate <= 0) errors.add(message(R.string.backup_validation_bankloan_start_invalid, i))
      // The remaining checks mirror Rust validate_bank_loan (validation.rs:205-226):
      // repayment/interest cross-field invariants. multiplyExact/subtractExact
      // reject overflow instead of wrapping, matching checked_mul/checked_sub.
      if (bankLoan.totalRepayableAmount <= 0) {
        errors.add(message(R.string.backup_validation_bankloan_repayable_invalid, i))
      }
      val expectedRepayments =
        try {
          Math.multiplyExact(bankLoan.monthlyInstallmentAmount, bankLoan.numberOfInstallments.toLong())
        } catch (_: ArithmeticException) {
          null
        }
      if (expectedRepayments == null) {
        errors.add(message(R.string.backup_validation_bankloan_repayable_overflow, i))
      } else if (bankLoan.totalRepayableAmount != expectedRepayments) {
        errors.add(message(R.string.backup_validation_bankloan_repayable_mismatch, i))
      }
      if (bankLoan.totalRepayableAmount < bankLoan.receivedAmount) {
        errors.add(message(R.string.backup_validation_bankloan_repayable_below_received, i))
      } else {
        val expectedInterest = bankLoan.totalRepayableAmount - bankLoan.receivedAmount
        if (bankLoan.totalInterest != expectedInterest) {
          errors.add(message(R.string.backup_validation_bankloan_interest_mismatch, i))
        }
      }
    }
  }

  private fun validateBackupAccounts(
    accounts: List<AccountEntity>,
    errors: MutableList<String>
  ) {
    accounts.forEachIndexed { i, account ->
      if (account.name.isBlank()) errors.add(message(R.string.backup_validation_account_name_blank, i))
      // createdAt == 0 is a legacy sentinel from the v6→v7 migration
      // (MIGRATION_6_7 used DEFAULT 0 for accounts that existed before
      // timestamps were tracked). Accept it as valid.
    }
  }

  private fun BackupPayload.toRustPayload(): io.github.mojri.hesabyar.rust.BackupPayload =
    io.github.mojri.hesabyar.rust.BackupPayload(
      version = version,
      timestamp = timestamp,
      appVersion = appVersion,
      transactions = RustMappers.mapTransactions(transactions),
      loans = RustMappers.mapLoans(loans),
      installments = RustMappers.mapInstallments(installments),
      paymentHistories = RustMappers.mapPaymentHistories(paymentHistories),
      bankLoans = RustMappers.mapBankLoans(bankLoans),
      categories = RustMappers.mapCategories(categories),
      accounts = RustMappers.mapAccounts(accounts),
      persons = RustMappers.mapPersons(persons)
    )
}
