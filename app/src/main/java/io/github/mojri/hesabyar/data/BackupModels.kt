package io.github.mojri.hesabyar.data

import io.github.mojri.hesabyar.BuildConfig

/**
 * Backup format/schema version. Bump ONLY on a breaking change to the serialized
 * backup structure (fields added/removed/renamed or a semantics change).
 * Single source of truth is the Rust const `BACKUP_SCHEMA_VERSION` in
 * `rust/hesabyar-core/src/models/mod.rs`; this value is generated into
 * `BuildConfig.BACKUP_SCHEMA_VERSION` at build time so the two sides cannot drift.
 */
enum class RestoreMode {
  REPLACE,
  MERGE
}

data class BackupPayload(
  val version: Int = BuildConfig.BACKUP_SCHEMA_VERSION,
  val timestamp: Long = System.currentTimeMillis(),
  val appVersion: String = BuildConfig.VERSION_NAME,
  val transactions: List<Transaction> = emptyList(),
  val loans: List<Loan> = emptyList(),
  val installments: List<Installment> = emptyList(),
  val paymentHistories: List<PaymentHistory> = emptyList(),
  val categories: List<Category> = emptyList(),
  val bankLoans: List<BankLoan> = emptyList(),
  val accounts: List<AccountEntity> = emptyList(),
  val persons: List<Person> = emptyList(),
  val settings: BackupSettings = BackupSettings()
)

data class BackupSettings(
  val darkMode: Boolean = true
)

sealed interface BackupValidationResult {
  object Valid : BackupValidationResult

  data class Invalid(
    val errors: List<String>
  ) : BackupValidationResult
}
