// Unit tests assert with unwrap/expect/panic. These are loud by design in
// test code, so the restriction lints stay off for the test harness only.
// Production code keeps them on (see Cargo.toml `[workspace.lints]`).
#![cfg_attr(test, allow(clippy::unwrap_used, clippy::expect_used, clippy::panic))]

pub mod advisory;
pub mod ai_validation;
pub mod analytics;
pub mod calendar;
pub mod crypto;
pub mod currency;
pub mod dashboard;
pub mod excel;
pub mod ffi;
pub mod models;
pub mod parser;
pub mod search;
pub mod validation;

pub use advisory::*;
pub use ai_validation::*;
pub use analytics::*;
pub use calendar::*;
pub use crypto::*;
pub use currency::*;
pub use dashboard::*;
pub use excel::*;
pub use models::*;
pub use parser::*;
pub use search::*;
pub use validation::*;

uniffi::setup_scaffolding!();

/// Returns the bundled Rust core version (SemVer base + source build metadata).
///
/// The core is versioned independently from the Android app (root `VERSION`
/// file). The base `MAJOR.MINOR.PATCH` lives in `rust/Cargo.toml` and is bumped
/// manually per SemVer; the build metadata (`+<hash>`) is derived from the core
/// source tree by the Gradle `syncCoreVersion` task, so this reflects the exact
/// core build that is bundled into the app.
#[uniffi::export]
pub fn get_core_version() -> String {
    env!("CORE_VERSION").to_string()
}

/// Initialize the Rust core. Must be called once from Kotlin after loading the library.
///
/// Installs a panic hook that ensures Rust panics never cross the FFI boundary.
/// Safe to call multiple times (uses `Once` internally).
#[uniffi::export]
pub fn initialize() {
    crate::ffi::catch_unwind_safe(ffi::ensure_initialized).unwrap_or(())
}

/// Full offline sentence parser (ported from GeminiParser.parseSentenceOffline).
/// Uses the real wall clock — deterministic only if the caller supplies a
/// fixed `now_ms` via [parse_sentence_offline_at].
#[uniffi::export]
pub fn parse_sentence_offline(raw_sentence: &str) -> Result<ParsedResult, HesabyarError> {
    let now_ms = parser::nlp::real_now_ms();
    crate::ffi::catch_unwind_safe(|| parser::nlp::parse_sentence_offline_full(raw_sentence, now_ms))
}

/// Same as [parse_sentence_offline] but with an explicit "now" timestamp
/// (epoch ms), so callers can make the date-relative `daysFromNow` field
/// (installment due dates) deterministic in tests. `dateOffsetDays` is NOT
/// affected by `now_ms` — it is derived purely from relative words like
/// «دیروز»/«فردا» via `extract_date_offset`. Production code uses the
/// real-time default via [parse_sentence_offline].
///
/// Note: unlike the Kotlin fallback (GeminiParser.parseSentenceOffline), the
/// Rust parser's `extract_date_offset` handles only relative words. It does
/// NOT resolve explicit Jalali dates (e.g. "۲۵ تیر") to days-from-today, so
/// such input yields a 0 offset here. Production code uses the
/// real-time default via [parse_sentence_offline].
#[uniffi::export]
pub fn parse_sentence_offline_at(
    raw_sentence: &str,
    now_ms: i64,
) -> Result<ParsedResult, HesabyarError> {
    crate::ffi::catch_unwind_safe(|| parser::nlp::parse_sentence_offline_full(raw_sentence, now_ms))
}

/// Infer expense category from a Persian sentence (full 200+ keyword version).
#[uniffi::export]
pub fn infer_expense_category(sentence: &str) -> CategoryGuess {
    let r = crate::ffi::catch_unwind_safe(|| {
        let (cat, subcat) = parser::nlp::infer_expense_category_full(sentence);
        CategoryGuess {
            category: cat,
            subcategory: subcat,
        }
    });
    r.unwrap_or_default()
}

/// Convert Gregorian timestamp (ms) to Jalali date.
/// Returns packed i64: (year << 16) | (month << 8) | day.
/// Returns 0 on error (no panic).
#[uniffi::export]
pub fn gregorian_to_jalali(timestamp_ms: i64) -> i64 {
    crate::ffi::catch_unwind_safe(|| calendar::gregorian_to_jalali_packed(timestamp_ms))
        .unwrap_or(0)
}

/// Convert Jalali date to Gregorian timestamp (ms).
/// Returns i64::MIN on error (no panic) to match the Kotlin Long.MIN_VALUE sentinel.
#[uniffi::export]
pub fn jalali_to_gregorian(year: i32, month: i32, day: i32) -> i64 {
    crate::ffi::catch_unwind_safe(|| {
        calendar::jalali_to_gregorian(year, month, day).unwrap_or(i64::MIN)
    })
    .unwrap_or(i64::MIN)
}

/// Parse a Persian amount sentence and return the amount in Toman.
#[uniffi::export]
pub fn parse_persian_amount(sentence: &str) -> i64 {
    crate::ffi::catch_unwind_safe(|| parser::amount::parse_amount(sentence, true)).unwrap_or(0)
}

/// Parse a backup JSON string into a BackupPayload.
#[uniffi::export]
pub fn parse_backup_json(json: &str) -> Result<BackupPayload, HesabyarError> {
    let r = crate::ffi::catch_unwind_safe(|| {
        serde_json::from_str(json).map_err(|e| HesabyarError::BackupValidation {
            detail: format!("Invalid backup JSON: {}", e),
        })
    })?;
    r
}

/// Validate a backup payload.
#[uniffi::export]
pub fn validate_backup(payload: &BackupPayload) -> Result<(), HesabyarError> {
    let r = crate::ffi::catch_unwind_safe(|| {
        if payload.version < 1 {
            return Err(HesabyarError::BackupValidation {
                detail: "Invalid backup version".to_string(),
            });
        }

        // Full structural validation
        if payload.transactions.is_empty()
            && payload.loans.is_empty()
            && payload.installments.is_empty()
            && payload.bank_loans.is_empty()
            && payload.categories.is_empty()
            && payload.accounts.is_empty()
            && payload.persons.is_empty()
        {
            return Err(HesabyarError::BackupValidation {
                detail: "Backup contains no data".to_string(),
            });
        }

        // Validate transactions
        let category_ids: std::collections::HashSet<_> =
            payload.categories.iter().map(|c| c.id).collect();
        for tx in &payload.transactions {
            if tx.amount <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Transaction {} has invalid amount", tx.id),
                });
            }
            if tx.date <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Transaction {} has invalid date", tx.id),
                });
            }
            if tx.category_id <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Transaction {} has invalid category_id", tx.id),
                });
            }
            if !payload.categories.is_empty() && !category_ids.contains(&tx.category_id) {
                return Err(HesabyarError::BackupValidation {
                    detail: format!(
                        "Transaction {} references non-existent category {}",
                        tx.id, tx.category_id
                    ),
                });
            }
        }

        // Validate loans
        for loan in &payload.loans {
            if loan.original_amount <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Loan {} has invalid original_amount", loan.id),
                });
            }
            if loan.remaining_amount < 0 || loan.remaining_amount > loan.original_amount {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Loan {} has invalid remaining_amount", loan.id),
                });
            }
            if loan.date <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Loan {} has invalid date", loan.id),
                });
            }
        }

        // Validate installments
        for inst in &payload.installments {
            if inst.amount <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Installment {} has invalid amount", inst.id),
                });
            }
            if inst.due_date <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Installment {} has invalid due_date", inst.id),
                });
            }
        }

        // Validate bank loans via the canonical validator so all entry paths
        // enforce the same financial invariants (incl. checked repayment/interest).
        for bl in &payload.bank_loans {
            if let Err(detail) = crate::validation::validate_bank_loan(bl) {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("BankLoan {}: {}", bl.id, detail),
                });
            }
        }

        // Validate categories
        for cat in &payload.categories {
            if cat.id <= 0 {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Category {} has invalid id", cat.id),
                });
            }
            if cat.name.trim().is_empty() {
                return Err(HesabyarError::BackupValidation {
                    detail: format!("Category {} has empty name", cat.id),
                });
            }
        }

        // Validate accounts and transaction account references via the
        // shared helper so both FFI and internal paths enforce identical rules.
        if let Some(first_err) =
            crate::validation::validate_accounts_and_references(payload).first()
        {
            return Err(HesabyarError::BackupValidation {
                detail: first_err.clone(),
            });
        }

        // Validate persons: field checks (blank name, duplicate key, duplicate id)
        // and cross-references (positive person_id must point to a declared person).
        // The same [validate_persons] function runs in validate_backup_payload, so
        // malformed person-only payloads cannot slip through the FFI path.
        if let Some(first_err) = crate::validation::validate_persons(payload).first() {
            return Err(HesabyarError::BackupValidation {
                detail: first_err.clone(),
            });
        }

        Ok(())
    })?;
    r
}

/// Export a backup payload to JSON.
#[uniffi::export]
pub fn export_backup_json(payload: &BackupPayload) -> Result<String, HesabyarError> {
    let r = crate::ffi::catch_unwind_safe(|| {
        serde_json::to_string_pretty(payload).map_err(|e| HesabyarError::BackupValidation {
            detail: format!("Failed to serialize backup: {}", e),
        })
    })?;
    r
}
