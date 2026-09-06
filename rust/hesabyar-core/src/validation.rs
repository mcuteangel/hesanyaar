use crate::models::*;

// WARNING: Must match Kotlin AccountType enum exactly.
// Add new entries here when AccountType gains new variants in the app.
const VALID_ACCOUNT_TYPES: &[&str] = &["BANK", "CASH_WALLET", "SAVINGS_INVESTMENT", "OTHER"];

/// Result of batch validation — collects all errors.
#[derive(Debug, Clone, uniffi::Record)]
pub struct ValidationResult {
    pub is_valid: bool,
    pub errors: Vec<String>,
}

impl Default for ValidationResult {
    fn default() -> Self {
        Self {
            is_valid: true,
            errors: vec![],
        }
    }
}

// ===========================================================================
// Single-entity validators (return first error)
// ===========================================================================

/// Validate the shared transaction fields (amount, date, category_id) that
/// apply to every transaction type. Transfer-specific invariants (destination
/// present, destination != source) are checked by the shared
/// [check_transfer_structure] helper, called from both [validate_transaction]
/// and [validate_accounts_and_references], so they are not duplicated (or
/// forgotten) when `validate_backup_payload` runs both paths.
fn validate_transaction_fields(tx: &Transaction) -> Result<(), String> {
    if tx.amount <= 0 {
        return Err("Transaction amount must be positive".into());
    }
    if tx.date <= 0 {
        return Err("Transaction date must be positive".into());
    }
    // Note: empty descriptions and non-positive category_id are tolerated here
    // (instead of rejected) so that backups exported by older versions of the
    // app — which allowed blank descriptions and defaulted missing category ids
    // to 1 — can still be restored. A negative category_id is still rejected as
    // it can never reference a valid category.
    if tx.category_id < 0 {
        return Err("Transaction category_id must not be negative".into());
    }
    Ok(())
}

/// Violation of a Transfer's structural invariants, shared between
/// [`validate_transaction`] (fail-fast FFI path) and
/// [`validate_accounts_and_references`] (collect-all batch path) so the
/// destination/source rules cannot drift when one is modified.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TransferIssue {
    MissingDestination,
    SameSourceAndDestination,
}

/// Check Transfer-specific structural invariants (destination present,
/// destination != source) shared by both validation paths.
///
/// Returns `Some(issue)` for the first violation found, or `None` for a valid
/// transaction (including non-Transfer types, which are accepted here).
fn check_transfer_structure(tx: &Transaction) -> Option<TransferIssue> {
    if tx.tx_type != TransactionType::Transfer {
        return None;
    }
    if tx.destination_account_id.is_none() {
        return Some(TransferIssue::MissingDestination);
    }
    if tx.destination_account_id == Some(tx.account_id) {
        return Some(TransferIssue::SameSourceAndDestination);
    }
    None
}

/// Validate a single transaction.
///
/// Returns `Ok(())` if valid, or `Err(message)` describing the first violation.
pub fn validate_transaction(tx: &Transaction) -> Result<(), String> {
    validate_transaction_fields(tx)?;
    // The five non-Transfer types are accepted unconditionally; Transfer
    // requires a valid destination account.
    if let Some(issue) = check_transfer_structure(tx) {
        let msg = match issue {
            TransferIssue::MissingDestination => "Transfer must have a destination_account_id",
            TransferIssue::SameSourceAndDestination => {
                "Transfer source and destination accounts must differ"
            }
        };
        return Err(msg.into());
    }
    Ok(())
}

/// Validate a single loan.
///
/// Returns `Ok(())` if valid, or `Err(message)` describing the first violation.
pub fn validate_loan(loan: &Loan) -> Result<(), String> {
    if loan.person_name.is_empty() {
        return Err("Loan person_name must not be empty".into());
    }
    if loan.date <= 0 {
        return Err("Loan date must be positive".into());
    }
    if loan.original_amount <= 0 {
        return Err("Loan original_amount must be positive".into());
    }
    if loan.remaining_amount < 0 {
        return Err("Loan remaining_amount must be non-negative".into());
    }
    // Note: remaining_amount may exceed original_amount for backups created by
    // older app versions (e.g. after partial manual edits). Tolerated on import
    // rather than hard-rejected so those backups can still be restored.
    if loan.loan_type != "DEBTOR" && loan.loan_type != "CREDITOR" {
        return Err(format!(
            "Loan type must be DEBTOR or CREDITOR, got '{}'",
            loan.loan_type
        ));
    }
    Ok(())
}

/// Validate a single installment.
///
/// Returns `Ok(())` if valid, or `Err(message)` describing the first violation.
pub fn validate_installment(inst: &Installment) -> Result<(), String> {
    if inst.title.is_empty() {
        return Err("Installment title must not be empty".into());
    }
    if inst.amount <= 0 {
        return Err("Installment amount must be positive".into());
    }
    if inst.due_date <= 0 {
        return Err("Installment due_date must be positive".into());
    }
    Ok(())
}

/// Validate a single ParsedResult (AI parser output).
///
/// Returns `Ok(())` if valid, or `Err(message)` describing the first violation.
pub fn validate_parsed_result(result: &ParsedResult) -> Result<(), String> {
    if result.amount <= 0 {
        return Err("ParsedResult amount must be positive".into());
    }
    // All six TransactionType variants are valid.
    match result.tx_type {
        TransactionType::Expense
        | TransactionType::Income
        | TransactionType::LoanDebtor
        | TransactionType::LoanCreditor
        | TransactionType::Installment
        | TransactionType::Transfer => {}
    }
    if result.category.is_empty() {
        return Err("ParsedResult category must not be empty".into());
    }
    if let Some(hour) = result.hour {
        if !(0..=23).contains(&hour) {
            return Err(format!("ParsedResult hour must be 0-23, got {}", hour));
        }
    }
    if let Some(minute) = result.minute {
        if !(0..=59).contains(&minute) {
            return Err(format!("ParsedResult minute must be 0-59, got {}", minute));
        }
    }
    Ok(())
}

// ===========================================================================
// Batch validators (collect all errors)
// ===========================================================================

/// Validate a batch of transactions. Collects all errors.
///
/// Transfer-specific invariants (destination present, destination != source)
/// are intentionally NOT checked here — `validate_accounts_and_references`
/// (called first by `validate_backup_payload`) covers them via the shared
/// [check_transfer_structure] helper. Checking them again would produce
/// duplicate errors for the same violation.
pub fn validate_transaction_batch(transactions: &[Transaction]) -> ValidationResult {
    let mut errors = Vec::new();
    for (i, tx) in transactions.iter().enumerate() {
        if let Err(e) = validate_transaction_fields(tx) {
            errors.push(format!("Transaction[{}]: {}", i, e));
        }
    }
    ValidationResult {
        is_valid: errors.is_empty(),
        errors,
    }
}

/// Validate a batch of loans. Collects all errors.
pub fn validate_loan_batch(loans: &[Loan]) -> ValidationResult {
    let mut errors = Vec::new();
    for (i, loan) in loans.iter().enumerate() {
        if let Err(e) = validate_loan(loan) {
            errors.push(format!("Loan[{}]: {}", i, e));
        }
    }
    ValidationResult {
        is_valid: errors.is_empty(),
        errors,
    }
}

/// Validate a batch of installments. Collects all errors.
pub fn validate_installment_batch(installments: &[Installment]) -> ValidationResult {
    let mut errors = Vec::new();
    for (i, inst) in installments.iter().enumerate() {
        if let Err(e) = validate_installment(inst) {
            errors.push(format!("Installment[{}]: {}", i, e));
        }
    }
    ValidationResult {
        is_valid: errors.is_empty(),
        errors,
    }
}

/// Validate a single bank loan.
///
/// Returns `Ok(())` if valid, or `Err(message)` describing the first violation.
pub fn validate_bank_loan(bl: &BankLoan) -> Result<(), String> {
    if bl.bank_name.trim().is_empty() {
        return Err("BankLoan bank_name must not be empty".into());
    }
    if bl.received_amount <= 0 {
        return Err("BankLoan received_amount must be positive".into());
    }
    if bl.monthly_installment_amount <= 0 {
        return Err("BankLoan monthly_installment_amount must be positive".into());
    }
    if bl.number_of_installments <= 0 {
        return Err("BankLoan number_of_installments must be positive".into());
    }
    if bl.start_date <= 0 {
        return Err("BankLoan start_date must be positive".into());
    }
    if bl.total_repayable_amount <= 0 {
        return Err("BankLoan total_repayable_amount must be positive".into());
    }
    // Repayment/interest relationships, using checked arithmetic to reject overflow.
    let expected_repayable = bl
        .monthly_installment_amount
        .checked_mul(bl.number_of_installments as i64)
        .ok_or("BankLoan repayable amount overflows (monthly_installment_amount * number_of_installments)")?;
    if bl.total_repayable_amount != expected_repayable {
        return Err(
            "BankLoan total_repayable_amount must equal monthly_installment_amount * number_of_installments".into(),
        );
    }
    let expected_interest = bl
        .total_repayable_amount
        .checked_sub(bl.received_amount)
        .ok_or("BankLoan interest calculation overflows")?;
    if expected_interest < 0 {
        return Err("BankLoan total_repayable_amount must not be less than received_amount".into());
    }
    if bl.total_interest != expected_interest {
        return Err(
            "BankLoan total_interest must equal total_repayable_amount - received_amount".into(),
        );
    }
    Ok(())
}

/// Validate a batch of bank loans. Collects all errors.
pub fn validate_bank_loan_batch(bank_loans: &[BankLoan]) -> ValidationResult {
    let mut errors = Vec::new();
    for (i, bl) in bank_loans.iter().enumerate() {
        if let Err(e) = validate_bank_loan(bl) {
            errors.push(format!("BankLoan[{}]: {}", i, e));
        }
    }
    ValidationResult {
        is_valid: errors.is_empty(),
        errors,
    }
}

/// Validate a single payment history entry.
///
/// Returns `Ok(())` if valid, or `Err(message)` describing the first violation.
pub fn validate_payment_history(ph: &PaymentHistory) -> Result<(), String> {
    if ph.amount <= 0 {
        return Err("PaymentHistory amount must be positive".into());
    }
    if ph.date <= 0 {
        return Err("PaymentHistory date must be positive".into());
    }
    if ph.loan_id <= 0 {
        return Err("PaymentHistory loan_id must be positive".into());
    }
    Ok(())
}

/// Validate a batch of payment histories. Collects all errors.
pub fn validate_payment_history_batch(payment_histories: &[PaymentHistory]) -> ValidationResult {
    let mut errors = Vec::new();
    for (i, ph) in payment_histories.iter().enumerate() {
        if let Err(e) = validate_payment_history(ph) {
            errors.push(format!("PaymentHistory[{}]: {}", i, e));
        }
    }
    ValidationResult {
        is_valid: errors.is_empty(),
        errors,
    }
}

/// Validate accounts and cross-reference transactions against them.
///
/// This is the single source of truth for account validation and
/// referential-integrity checks on transaction account fields. Both
/// `validate_backup_payload` (collect-all-errors path) and `validate_backup`
/// in `lib.rs` (fail-fast FFI path) call this function to avoid drift.
///
/// Legacy backups (pre-multi-account) omit the `accounts` list entirely.
/// When `accounts` is empty but transactions exist, every transaction must
/// reference the legacy default account ID (1). This prevents corrupted
/// backups from injecting arbitrary/non-existent account IDs that would
/// create orphaned balances after restore.
pub fn validate_accounts_and_references(payload: &BackupPayload) -> Vec<String> {
    let mut errors = Vec::new();

    // --- Account structural validation ---
    let mut seen_ids: std::collections::HashSet<i64> = std::collections::HashSet::new();
    for (i, acc) in payload.accounts.iter().enumerate() {
        if acc.name.trim().is_empty() {
            errors.push(format!("Account[{}] has empty name", i));
        }
        if !seen_ids.insert(acc.id) {
            errors.push(format!("Duplicate account id {}", acc.id));
        }
        if !VALID_ACCOUNT_TYPES.contains(&acc.account_type.as_str()) {
            errors.push(format!(
                "Account[{}] has invalid type '{}'",
                i, acc.account_type
            ));
        }
    }

    // --- Transaction account reference validation ---
    let account_ids: std::collections::HashSet<i64> =
        payload.accounts.iter().map(|a| a.id).collect();

    if !payload.accounts.is_empty() {
        // Modern backup: validate transactions against declared accounts.
        for (i, tx) in payload.transactions.iter().enumerate() {
            if !account_ids.contains(&tx.account_id) {
                errors.push(format!(
                    "Transaction[{}] references non-existent source account {}",
                    i, tx.account_id
                ));
            }
            if let Some(dest_id) = tx.destination_account_id {
                if !account_ids.contains(&dest_id) {
                    errors.push(format!(
                        "Transaction[{}] references non-existent destination account {}",
                        i, dest_id
                    ));
                }
            }
        }
    } else if !payload.transactions.is_empty() {
        // Legacy backup (no accounts list): every transaction must reference
        // the legacy default account ID. This is the single account that
        // existed before multi-account support. Rejecting arbitrary IDs here
        // prevents orphaned balances from tampered old backups.
        for (i, tx) in payload.transactions.iter().enumerate() {
            if tx.account_id != DEFAULT_ACCOUNT_ID {
                errors.push(format!(
                    "Transaction[{}] references non-legacy account {} (accounts list is empty; expected {})",
                    i, tx.account_id, DEFAULT_ACCOUNT_ID
                ));
            }
            if let Some(dest_id) = tx.destination_account_id {
                if dest_id != DEFAULT_ACCOUNT_ID {
                    errors.push(format!(
                        "Transaction[{}] references non-legacy destination account {} (accounts list is empty; expected {})",
                        i, dest_id, DEFAULT_ACCOUNT_ID
                    ));
                }
            }
        }
    }

    // --- Transfer structure validation ---
    // Uses the shared check_transfer_structure helper so these invariants
    // cannot drift from validate_transaction (the fail-fast FFI path). Each
    // caller formats its own indexed message.
    for (i, tx) in payload.transactions.iter().enumerate() {
        if let Some(issue) = check_transfer_structure(tx) {
            errors.push(format!(
                "Transaction[{}] {}",
                i,
                match issue {
                    TransferIssue::MissingDestination =>
                        "is a Transfer but has no destination_account_id",
                    TransferIssue::SameSourceAndDestination =>
                        "Transfer source and destination accounts must differ",
                }
            ));
        }
    }

    errors
}

/// Validate person records and cross-reference loans/transactions against them.
///
/// Both `validate_backup_payload` (collect-all path) and `validate_backup`
/// (fail-fast FFI path) call this so person validation cannot be skipped by
/// either entry point. Field checks: blank name, blank derived key, duplicate
/// derived key, duplicate source ID. Cross-reference checks: positive
/// `person_id` on loans and transactions must point to a declared person.
pub fn validate_persons(payload: &BackupPayload) -> Vec<String> {
    let mut errors = Vec::new();

    // Field checks: blank name, blank derived key, duplicate derived key,
    // and duplicate source IDs. The key is derived from `name` and never
    // read from the backup-supplied `normalized_name` (mirrors the Kotlin
    // fallback in BackupJsonValidator so both paths agree).
    let mut seen_person_keys = std::collections::HashSet::new();
    for (i, p) in payload.persons.iter().enumerate() {
        // Mirror Kotlin `p.name.isBlank()` (Character.isWhitespace || isSpaceChar) —
        // see `is_java_whitespace`. `str::trim` uses Unicode White_Space, which
        // would diverge, so check every char explicitly.
        let name_is_blank = p.name.is_empty()
            || p.name.chars().all(|c| {
                is_java_whitespace(c)
                    || matches!(
                        c,
                        '\u{200B}' | '\u{200C}' | '\u{200D}' | '\u{2060}' | '\u{FEFF}'
                    )
            });
        if name_is_blank {
            errors.push(format!("Person[{}] has a blank name", i));
        }
        let key = normalize_person_name(&p.name);
        if key.is_empty() {
            errors.push(format!("Person[{}] has a blank normalizedName", i));
        } else if !seen_person_keys.insert(key) {
            errors.push(format!("Person[{}] has a duplicate normalizedName", i));
        }
    }
    // Duplicate source IDs: the restore path maps source IDs to local rows with
    // `associate`, so a later entry silently overwrites the earlier mapping and
    // loans/transactions referencing that ID resolve to the wrong person.
    let mut person_id_counts: std::collections::HashMap<i64, usize> =
        std::collections::HashMap::new();
    for p in payload.persons.iter() {
        *person_id_counts.entry(p.id).or_insert(0) += 1;
    }
    let mut duplicate_person_ids: Vec<i64> = person_id_counts
        .into_iter()
        .filter(|(_, count)| *count > 1)
        .map(|(id, _)| id)
        .collect();
    // Sort so the reported errors are deterministic across runs.
    duplicate_person_ids.sort_unstable();
    for id in duplicate_person_ids {
        errors.push(format!("Person has a duplicate id {}", id));
    }
    // Cross-reference: positive person_id must point to a declared person.
    // Zero is a legacy default tolerated in all cases.
    let person_ids: std::collections::HashSet<_> = payload.persons.iter().map(|p| p.id).collect();
    for (i, loan) in payload.loans.iter().enumerate() {
        if let Some(pid) = loan.person_id {
            if pid > 0 && !person_ids.contains(&pid) {
                errors.push(format!(
                    "Loan[{}] references non-existent person {}",
                    i, pid
                ));
            }
        }
    }
    for (i, tx) in payload.transactions.iter().enumerate() {
        if let Some(pid) = tx.person_id {
            if pid > 0 && !person_ids.contains(&pid) {
                errors.push(format!(
                    "Transaction[{}] references non-existent person {}",
                    i, pid
                ));
            }
        }
    }
    errors
}

/// Mirrors the Kotlin `PersonNameNormalizer` (app/…/domain/utils/PersonNameNormalizer.kt)
/// for validation purposes only.
///
/// The stored dedup key is produced by the Kotlin util, which ADR-001 lists as a
/// permanent Kotlin fallback. This function exists so the Rust validation path
/// judges a payload by the same key the restore path will derive from `name`,
/// instead of trusting the backup-supplied `normalized_name`. A tampered pair
/// such as `name = "Ali", normalized_name = "reza"` would otherwise either bind
/// Ali's records to Reza's identity or trip a false duplicate error.
///
/// It must EXACTLY mirror `PersonNameNormalizer.normalize` (Kotlin):
/// - whitespace uses Java `Character.isWhitespace` (see `is_java_whitespace`),
///   which EXCLUDES NBSP/NNBSP/NARROW-NBSP — Rust's `char::is_whitespace`
///   (Unicode White_Space) would fold NBSP to a space and diverge from Kotlin;
/// - case folding mirrors Kotlin `Char.lowercaseChar` (Java
///   `Character.toLowerCase`): the Unicode SIMPLE lowercase (a single code
///   point) is applied, or the char is kept unchanged when no single-char
///   lowercase exists. `İ` (U+0130) is the common char whose simple
///   lowercase is a single `i` while Rust's full `to_lowercase` expands to
///   two code points, so it is mapped explicitly to stay in parity.
///
/// Used only to reject or accept a payload. It never writes a key, so any drift
/// from the Kotlin util costs a wrong accept/reject, never data corruption.
fn normalize_person_name(name: &str) -> String {
    let mut out = String::with_capacity(name.len());
    let mut pending_space = false;
    for raw in name.chars() {
        // Fold Arabic variants to their Persian counterparts.
        let folded = match raw {
            '\u{064A}' => '\u{06CC}', // Arabic yeh -> Persian yeh
            '\u{0643}' => '\u{06A9}', // Arabic kaf -> Persian keheh
            '\u{0629}' => '\u{0647}', // Arabic teh marbuta -> heh
            other => other,
        };
        match folded {
            // Zero width: ZWSP, ZWNJ, ZWJ, word joiner, BOM.
            '\u{200B}' | '\u{200C}' | '\u{200D}' | '\u{2060}' | '\u{FEFF}' => {}
            c if is_java_whitespace(c) => {
                pending_space = !out.is_empty();
            }
            c => {
                if pending_space && !out.is_empty() {
                    out.push(' ');
                }
                pending_space = false;
                // Simple (single-codepoint) case fold to mirror Kotlin
                // `Char.lowercaseChar()` (Java `Character.toLowerCase`): the
                // Unicode SIMPLE lowercase is used when it is a single code
                // point, otherwise the original char is kept unchanged.
                // Rust `char::to_lowercase()` is the FULL mapping and can yield
                // several code points; for `İ` (U+0130) it yields "i\u{307}"
                // while Kotlin's simple mapping yields a single `i`. The general
                // `count() == 1` guard below would wrongly keep `İ`, so map it
                // explicitly to stay in parity with the Kotlin util.
                if c == '\u{0130}' {
                    out.push('i');
                } else {
                    let lowered: String = c.to_lowercase().collect();
                    if lowered.chars().count() == 1 {
                        out.push_str(&lowered);
                    } else {
                        out.push(c);
                    }
                }
            }
        }
    }
    out
}

/// Mirrors Kotlin `Char.isWhitespace` on JVM (`Character.isWhitespace`), which the
/// Kotlin normalizer delegates to. Java/Kotlin `isWhitespace` EXCLUDES NBSP variants
/// (`U+00A0`, `U+2007`, `U+202F`) and NEL `U+0085`; Rust `char::is_whitespace` (Unicode
/// White_Space) includes them, so they are excluded here to keep Rust validation
/// and Kotlin runtime dedup keys in parity (see test_normalize_person_name_matches_kotlin_contract).
fn is_java_whitespace(c: char) -> bool {
    matches!(
        c,
        '\u{0009}'
            | '\u{000A}'
            | '\u{000B}'
            | '\u{000C}'
            | '\u{000D}'
            | '\u{001C}'
            | '\u{001D}'
            | '\u{001E}'
            | '\u{001F}'
    ) || (c.is_whitespace()
        && c != '\u{00A0}'
        && c != '\u{2007}'
        && c != '\u{202F}'
        && c != '\u{0085}')
}

/// Validate an entire backup payload. Collects all errors from all entities.
pub fn validate_backup_payload(payload: &BackupPayload) -> ValidationResult {
    let mut errors = Vec::new();
    if payload.version < 1 {
        errors.push("Invalid backup version".into());
    }

    // Account validation + transaction account reference checks.
    // Single shared helper — both FFI and internal paths enforce the same rules.
    errors.extend(validate_accounts_and_references(payload));

    // Category cross-reference check (mirrors FFI validate_backup).
    // Only check positive IDs — zero is a legacy default tolerated by
    // validate_transaction, so treating it as missing would break old backups.
    if !payload.categories.is_empty() {
        let category_ids: std::collections::HashSet<_> =
            payload.categories.iter().map(|c| c.id).collect();
        for (i, tx) in payload.transactions.iter().enumerate() {
            if tx.category_id > 0 && !category_ids.contains(&tx.category_id) {
                errors.push(format!(
                    "Transaction[{}] references non-existent category {}",
                    i, tx.category_id
                ));
            }
        }
    }
    errors.extend(validate_transaction_batch(&payload.transactions).errors);
    errors.extend(validate_loan_batch(&payload.loans).errors);
    errors.extend(validate_installment_batch(&payload.installments).errors);
    errors.extend(validate_bank_loan_batch(&payload.bank_loans).errors);
    errors.extend(validate_payment_history_batch(&payload.payment_histories).errors);
    // Person validation: field checks + cross-references. Single shared
    // function so both FFI and internal paths enforce identical rules.
    errors.extend(validate_persons(payload));
    // PaymentHistory cross-reference: positive loan_id must point to an existing loan.
    // Zero is a legacy default tolerated in all cases.
    let loan_ids: std::collections::HashSet<_> = payload.loans.iter().map(|l| l.id).collect();
    for (i, ph) in payload.payment_histories.iter().enumerate() {
        if ph.loan_id > 0 && !loan_ids.contains(&ph.loan_id) {
            errors.push(format!(
                "PaymentHistory[{}] references non-existent loan {}",
                i, ph.loan_id
            ));
        }
    }
    ValidationResult {
        is_valid: errors.is_empty(),
        errors,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_tx(amount: i64, desc: &str, category_id: i64) -> Transaction {
        Transaction {
            id: 1,
            tx_type: TransactionType::Expense,
            category_id,
            amount,
            description: desc.to_string(),
            person_name: None,
            person_id: None,
            date: 1710000000000,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        }
    }

    fn make_loan(amount: i64, remaining: i64, loan_type: &str) -> Loan {
        Loan {
            id: 1,
            person_name: "Ali".to_string(),
            person_id: None,
            loan_type: loan_type.to_string(),
            original_amount: amount,
            remaining_amount: remaining,
            description: "test".to_string(),
            date: 1710000000000,
            is_settled: false,
        }
    }

    fn make_inst(amount: i64, title: &str) -> Installment {
        Installment {
            id: 1,
            title: title.to_string(),
            amount,
            due_date: 1710000000000,
            is_paid: false,
            reminder_enabled: true,
            notes: String::new(),
            bank_loan_id: None,
        }
    }

    fn make_payment_history(amount: i64, loan_id: i64) -> PaymentHistory {
        PaymentHistory {
            id: 1,
            loan_id,
            amount,
            date: 1710000000000,
            notes: None,
        }
    }

    fn make_parsed(amount: i64, category: &str) -> ParsedResult {
        ParsedResult {
            tx_type: TransactionType::Expense,
            amount,
            category: category.to_string(),
            person_name: None,
            description: "test".to_string(),
            days_from_now: None,
            title: None,
            date_offset_days: None,
            hour: None,
            minute: None,
            confidence: 0.9,
            notes: None,
        }
    }

    // =====================================================================
    // Transaction validation
    // =====================================================================

    #[test]
    fn test_valid_transaction() {
        assert!(validate_transaction(&make_tx(50000, "coffee", 1)).is_ok());
    }

    #[test]
    fn test_transaction_zero_amount_rejected() {
        let err = validate_transaction(&make_tx(0, "coffee", 1)).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_transaction_negative_amount_rejected() {
        let err = validate_transaction(&make_tx(-500, "coffee", 1)).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_transaction_empty_description_allowed() {
        // Older backups allowed blank descriptions; tolerate on restore.
        assert!(validate_transaction(&make_tx(50000, "", 1)).is_ok());
    }

    #[test]
    fn test_transaction_zero_date_rejected() {
        let mut tx = make_tx(50000, "coffee", 1);
        tx.date = 0;
        let err = validate_transaction(&tx).unwrap_err();
        assert!(err.contains("date"));
    }

    #[test]
    fn test_transaction_zero_category_allowed() {
        // Older backups defaulted missing category ids to 1; 0 is tolerated.
        assert!(validate_transaction(&make_tx(50000, "coffee", 0)).is_ok());
    }

    #[test]
    fn test_transaction_negative_category_rejected() {
        let err = validate_transaction(&make_tx(50000, "coffee", -1)).unwrap_err();
        assert!(err.contains("category"));
    }

    #[test]
    fn test_transfer_missing_destination_rejected() {
        let tx = Transaction {
            tx_type: TransactionType::Transfer,
            ..make_tx(50000, "transfer", 1)
        };
        // destination_account_id defaults to None via make_tx
        let err = validate_transaction(&tx).unwrap_err();
        assert!(err.contains("destination_account_id"));
    }

    #[test]
    fn test_transfer_same_source_and_destination_rejected() {
        let tx = Transaction {
            tx_type: TransactionType::Transfer,
            account_id: 1,
            destination_account_id: Some(1),
            ..make_tx(50000, "transfer", 1)
        };
        let err = validate_transaction(&tx).unwrap_err();
        assert!(err.contains("differ"));
    }

    #[test]
    fn test_transfer_valid_different_accounts() {
        let tx = Transaction {
            tx_type: TransactionType::Transfer,
            account_id: 1,
            destination_account_id: Some(2),
            ..make_tx(50000, "transfer", 1)
        };
        assert!(validate_transaction(&tx).is_ok());
    }

    #[test]
    fn test_transaction_all_types_valid() {
        for tx_type in [
            TransactionType::Expense,
            TransactionType::Income,
            TransactionType::LoanDebtor,
            TransactionType::LoanCreditor,
            TransactionType::Installment,
        ] {
            let mut tx = make_tx(50000, "test", 1);
            tx.tx_type = tx_type;
            assert!(validate_transaction(&tx).is_ok());
        }
        // Transfer requires a different destination account
        let mut tx = make_tx(50000, "test", 1);
        tx.tx_type = TransactionType::Transfer;
        tx.destination_account_id = Some(2);
        assert!(validate_transaction(&tx).is_ok());
    }

    // =====================================================================
    // Loan validation
    // =====================================================================

    #[test]
    fn test_valid_loan_debtor() {
        assert!(validate_loan(&make_loan(5000000, 3000000, "DEBTOR")).is_ok());
    }

    #[test]
    fn test_valid_loan_creditor() {
        assert!(validate_loan(&make_loan(5000000, 5000000, "CREDITOR")).is_ok());
    }

    #[test]
    fn test_valid_loan_settled() {
        assert!(validate_loan(&make_loan(5000000, 0, "DEBTOR")).is_ok());
    }

    #[test]
    fn test_loan_empty_person_rejected() {
        let mut loan = make_loan(5000000, 3000000, "DEBTOR");
        loan.person_name = String::new();
        let err = validate_loan(&loan).unwrap_err();
        assert!(err.contains("person_name"));
    }

    #[test]
    fn test_loan_zero_amount_rejected() {
        let err = validate_loan(&make_loan(0, 0, "DEBTOR")).unwrap_err();
        assert!(err.contains("original_amount"));
    }

    #[test]
    fn test_loan_negative_amount_rejected() {
        let err = validate_loan(&make_loan(-1000, 0, "DEBTOR")).unwrap_err();
        assert!(err.contains("original_amount"));
    }

    #[test]
    fn test_loan_negative_remaining_rejected() {
        let err = validate_loan(&make_loan(5000000, -1, "DEBTOR")).unwrap_err();
        assert!(err.contains("remaining_amount"));
    }

    #[test]
    fn test_loan_remaining_exceeds_original_allowed() {
        // Older backups could have remaining > original after manual edits.
        assert!(validate_loan(&make_loan(5000000, 6000000, "DEBTOR")).is_ok());
    }

    #[test]
    fn test_loan_invalid_type_rejected() {
        let err = validate_loan(&make_loan(5000000, 3000000, "INVALID")).unwrap_err();
        assert!(err.contains("DEBTOR"));
    }

    // =====================================================================
    // Installment validation
    // =====================================================================

    #[test]
    fn test_valid_installment() {
        assert!(validate_installment(&make_inst(2000000, "Car loan")).is_ok());
    }

    #[test]
    fn test_installment_zero_amount_rejected() {
        let err = validate_installment(&make_inst(0, "Car loan")).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_installment_negative_amount_rejected() {
        let err = validate_installment(&make_inst(-500, "Car loan")).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_installment_empty_title_rejected() {
        let err = validate_installment(&make_inst(2000000, "")).unwrap_err();
        assert!(err.contains("title"));
    }

    #[test]
    fn test_installment_zero_due_date_rejected() {
        let mut inst = make_inst(2000000, "Car loan");
        inst.due_date = 0;
        let err = validate_installment(&inst).unwrap_err();
        assert!(err.contains("due_date"));
    }

    // =====================================================================
    // ParsedResult validation
    // =====================================================================

    #[test]
    fn test_valid_parsed_result() {
        assert!(validate_parsed_result(&make_parsed(50000, "Food")).is_ok());
    }

    #[test]
    fn test_parsed_result_zero_amount_rejected() {
        let err = validate_parsed_result(&make_parsed(0, "Food")).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_parsed_result_negative_amount_rejected() {
        let err = validate_parsed_result(&make_parsed(-100, "Food")).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_parsed_result_empty_category_rejected() {
        let err = validate_parsed_result(&make_parsed(50000, "")).unwrap_err();
        assert!(err.contains("category"));
    }

    #[test]
    fn test_parsed_result_hour_out_of_range() {
        let mut pr = make_parsed(50000, "Food");
        pr.hour = Some(25);
        let err = validate_parsed_result(&pr).unwrap_err();
        assert!(err.contains("hour"));
    }

    #[test]
    fn test_parsed_result_hour_negative() {
        let mut pr = make_parsed(50000, "Food");
        pr.hour = Some(-1);
        let err = validate_parsed_result(&pr).unwrap_err();
        assert!(err.contains("hour"));
    }

    #[test]
    fn test_parsed_result_minute_out_of_range() {
        let mut pr = make_parsed(50000, "Food");
        pr.minute = Some(60);
        let err = validate_parsed_result(&pr).unwrap_err();
        assert!(err.contains("minute"));
    }

    #[test]
    fn test_parsed_result_minute_negative() {
        let mut pr = make_parsed(50000, "Food");
        pr.minute = Some(-1);
        let err = validate_parsed_result(&pr).unwrap_err();
        assert!(err.contains("minute"));
    }

    #[test]
    fn test_parsed_result_valid_hour_minute() {
        let mut pr = make_parsed(50000, "Food");
        pr.hour = Some(14);
        pr.minute = Some(30);
        assert!(validate_parsed_result(&pr).is_ok());
    }

    #[test]
    fn test_parsed_result_boundary_hour_zero() {
        let mut pr = make_parsed(50000, "Food");
        pr.hour = Some(0);
        assert!(validate_parsed_result(&pr).is_ok());
    }

    #[test]
    fn test_parsed_result_boundary_hour_23() {
        let mut pr = make_parsed(50000, "Food");
        pr.hour = Some(23);
        assert!(validate_parsed_result(&pr).is_ok());
    }

    #[test]
    fn test_parsed_result_boundary_minute_59() {
        let mut pr = make_parsed(50000, "Food");
        pr.minute = Some(59);
        assert!(validate_parsed_result(&pr).is_ok());
    }

    // =====================================================================
    // Batch validation
    // =====================================================================

    #[test]
    fn test_batch_valid_transactions() {
        let txs = vec![
            make_tx(50000, "coffee", 1),
            make_tx(100000, "lunch", 2),
            make_tx(200000, "dinner", 3),
        ];
        let result = validate_transaction_batch(&txs);
        assert!(result.is_valid);
        assert!(result.errors.is_empty());
    }

    #[test]
    fn test_batch_collects_all_errors() {
        let txs = vec![
            make_tx(0, "bad1", 1),   // zero amount
            make_tx(50000, "", 1),   // empty desc (tolerated)
            make_tx(-1, "bad3", 1),  // negative amount
            make_tx(50000, "ok", 1), // valid
        ];
        let result = validate_transaction_batch(&txs);
        assert!(!result.is_valid);
        assert_eq!(result.errors.len(), 2);
    }

    #[test]
    fn test_batch_empty_is_valid() {
        let result = validate_transaction_batch(&[]);
        assert!(result.is_valid);
        assert!(result.errors.is_empty());
    }

    #[test]
    fn test_batch_single_error() {
        let txs = vec![make_tx(0, "bad", 1)];
        let result = validate_transaction_batch(&txs);
        assert!(!result.is_valid);
        assert_eq!(result.errors.len(), 1);
        assert!(result.errors[0].contains("Transaction[0]"));
    }

    // =====================================================================
    // Backup payload validation
    // =====================================================================

    #[test]
    fn test_backup_valid() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![make_tx(50000, "coffee", 1)],
            loans: vec![make_loan(5000000, 3000000, "DEBTOR")],
            installments: vec![make_inst(2000000, "Car loan")],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(result.is_valid);
    }

    #[test]
    fn test_backup_invalid_version() {
        let payload = BackupPayload {
            version: 0,
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result.errors.iter().any(|e| e.contains("version")));
    }

    #[test]
    fn test_backup_collects_all_entity_errors() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![make_tx(0, "bad", 1)],
            loans: vec![make_loan(0, 0, "INVALID")],
            installments: vec![make_inst(0, "")],
            bank_loans: vec![],
            payment_histories: vec![make_payment_history(0, 1)],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        // At least one error from each entity type
        assert!(result.errors.len() >= 4);
    }

    #[test]
    fn test_backup_empty_is_valid() {
        let payload = BackupPayload::default();
        let result = validate_backup_payload(&payload);
        assert!(result.is_valid);
    }

    #[test]
    fn test_backup_mixed_valid_and_invalid() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![make_tx(50000, "good", 1), make_tx(0, "bad", 1)],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert_eq!(result.errors.len(), 1);
    }

    #[test]
    fn test_backup_legacy_category_id_zero_not_rejected() {
        // category_id == 0 is a legacy default tolerated by validate_transaction;
        // backup validation must not treat it as a missing category reference.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![make_tx(50000, "groceries", 0)],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![Category {
                id: 1,
                name: "Food".into(),
                key: "food".into(),
                icon: "".into(),
                color: 0,
                category_type: "EXPENSE".into(),
                is_default: false,
            }],
            accounts: vec![],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "Legacy category_id=0 should be tolerated, got: {:?}",
            result.errors
        );
    }

    fn make_bank_loan(monthly: i64, count: i32, received: i64) -> BankLoan {
        let repayable = monthly * count as i64;
        BankLoan {
            id: 1,
            bank_name: "Bank".into(),
            loan_name: "Loan".into(),
            received_amount: received,
            monthly_installment_amount: monthly,
            number_of_installments: count,
            total_repayable_amount: repayable,
            total_interest: repayable - received,
            start_date: 1710000000000,
            description: "test".into(),
            is_settled: false,
        }
    }

    #[test]
    fn test_valid_bank_loan() {
        assert!(validate_bank_loan(&make_bank_loan(1_000_000, 12, 10_000_000)).is_ok());
    }

    #[test]
    fn test_bank_loan_repayable_mismatch_rejected() {
        let mut bl = make_bank_loan(1_000_000, 12, 10_000_000);
        bl.total_repayable_amount += 1;
        assert!(validate_bank_loan(&bl).is_err());
    }

    #[test]
    fn test_bank_loan_interest_mismatch_rejected() {
        let mut bl = make_bank_loan(1_000_000, 12, 10_000_000);
        bl.total_interest += 1;
        assert!(validate_bank_loan(&bl).is_err());
    }

    #[test]
    fn test_bank_loan_repayable_overflow_rejected() {
        let mut bl = make_bank_loan(1, 1, 1);
        bl.monthly_installment_amount = i64::MAX;
        bl.number_of_installments = 2;
        assert!(validate_bank_loan(&bl).is_err());
    }

    #[test]
    fn test_bank_loan_received_exceeds_repayable_rejected() {
        // received > repayable -> negative interest
        let mut bl = make_bank_loan(1_000_000, 12, 12_000_000);
        bl.received_amount = 20_000_000;
        bl.total_interest = bl.total_repayable_amount - bl.received_amount;
        assert!(validate_bank_loan(&bl).is_err());
    }

    // ====================================================================
    // PaymentHistory validation
    // ====================================================================

    #[test]
    fn test_valid_payment_history() {
        assert!(validate_payment_history(&make_payment_history(50000, 1)).is_ok());
    }

    #[test]
    fn test_payment_history_zero_amount_rejected() {
        let err = validate_payment_history(&make_payment_history(0, 1)).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_payment_history_negative_amount_rejected() {
        let err = validate_payment_history(&make_payment_history(-500, 1)).unwrap_err();
        assert!(err.contains("amount"));
    }

    #[test]
    fn test_payment_history_zero_date_rejected() {
        let mut ph = make_payment_history(50000, 1);
        ph.date = 0;
        let err = validate_payment_history(&ph).unwrap_err();
        assert!(err.contains("date"));
    }

    #[test]
    fn test_payment_history_zero_loan_id_rejected() {
        let mut ph = make_payment_history(50000, 1);
        ph.loan_id = 0;
        let err = validate_payment_history(&ph).unwrap_err();
        assert!(err.contains("loan_id"));
    }

    #[test]
    fn test_payment_history_batch_collects_errors() {
        let histories = vec![
            make_payment_history(50000, 1),
            make_payment_history(0, 1),
            make_payment_history(50000, 0),
        ];
        let result = validate_payment_history_batch(&histories);
        assert!(!result.is_valid);
        assert_eq!(result.errors.len(), 2);
    }

    #[test]
    fn test_backup_payment_history_loan_cross_reference() {
        // paymentHistory referencing a loan that does not exist in the backup
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![make_loan(100000, 40000, "DEBTOR")],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![
                make_payment_history(50000, 99), // loan 99 does not exist
            ],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("non-existent loan")));
    }

    #[test]
    fn test_backup_payment_history_loan_cross_reference_rejects_orphan_when_no_loans() {
        // Even with no loans in the backup, a positive loan_id in payment_histories
        // is always an orphan and must be rejected.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![make_payment_history(50000, 99)],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("non-existent loan")));
    }

    // =====================================================================
    // Account validation
    // =====================================================================

    #[test]
    fn test_backup_rejects_empty_account_name() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: String::new(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 1710000000000,
                updated_at: 1710000000000,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result.errors.iter().any(|e| e.contains("empty name")));
    }

    #[test]
    fn test_backup_rejects_duplicate_account_ids() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![
                Account {
                    id: 1,
                    name: "Main".to_string(),
                    account_type: "BANK".to_string(),
                    bank_name: None,
                    card_number: None,
                    account_number: None,
                    iban: None,
                    initial_balance: 0,
                    color: 0xFF4CAF50,
                    icon: None,
                    is_archived: false,
                    display_order: 0,
                    created_at: 1710000000000,
                    updated_at: 1710000000000,
                },
                Account {
                    id: 1,
                    name: "Duplicate".to_string(),
                    account_type: "CASH_WALLET".to_string(),
                    bank_name: None,
                    card_number: None,
                    account_number: None,
                    iban: None,
                    initial_balance: 0,
                    color: 0xFF4CAF50,
                    icon: None,
                    is_archived: false,
                    display_order: 0,
                    created_at: 1710000000000,
                    updated_at: 1710000000000,
                },
            ],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("Duplicate account id")));
    }

    #[test]
    fn test_backup_rejects_invalid_account_type() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "Main".to_string(),
                account_type: "INVALID_TYPE".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 1710000000000,
                updated_at: 1710000000000,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result.errors.iter().any(|e| e.contains("invalid type")));
    }

    #[test]
    fn test_backup_accepts_other_account_type() {
        // Kotlin AccountType has an OTHER variant; backups carrying it must
        // validate rather than being rejected as an unknown type.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "سایر".to_string(),
                account_type: "OTHER".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 1710000000000,
                updated_at: 1710000000000,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "OTHER account type should be accepted, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_backup_accepts_account_only_backup() {
        // A backup holding accounts but no transactions/loans/etc. is NOT "empty":
        // the FFI empty-guard must let it through so the accounts reach
        // validate_accounts_and_references (Kotlin's fallback has no empty-guard,
        // so this also keeps the two validators in parity).
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "حساب اصلی".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 1710000000000,
                updated_at: 1710000000000,
            }],
            ..Default::default()
        };
        crate::validate_backup(&payload)
            .unwrap_or_else(|e| panic!("Account-only backup should be accepted, got: {}", e));
    }

    #[test]
    fn test_backup_rejects_transfer_without_destination_via_fail_fast_ffi() {
        // The fail-fast FFI path (validate_backup) only runs
        // validate_accounts_and_references; a Transfer without a destination
        // must be rejected there too, consistently with validate_backup_payload.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 50000,
                description: "transfer".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: DEFAULT_ACCOUNT_ID,
                destination_account_id: None,
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![Category {
                id: 1,
                name: "Food".into(),
                key: "food".into(),
                icon: "".into(),
                color: 0,
                category_type: "EXPENSE".into(),
                is_default: false,
            }],
            accounts: vec![],
            ..Default::default()
        };
        let err = crate::validate_backup(&payload).unwrap_err().to_string();
        assert!(err.contains("destination_account_id"), "got: {}", err);
    }

    #[test]
    fn test_backup_rejects_transfer_with_same_source_and_destination_via_fail_fast_ffi() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 50000,
                description: "transfer".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: DEFAULT_ACCOUNT_ID,
                destination_account_id: Some(DEFAULT_ACCOUNT_ID),
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![Category {
                id: 1,
                name: "Food".into(),
                key: "food".into(),
                icon: "".into(),
                color: 0,
                category_type: "EXPENSE".into(),
                is_default: false,
            }],
            accounts: vec![],
            ..Default::default()
        };
        let err = crate::validate_backup(&payload).unwrap_err().to_string();
        assert!(err.contains("must differ"), "got: {}", err);
    }

    #[test]
    fn test_backup_transfer_missing_destination_not_duplicated() {
        // validate_backup_payload calls BOTH validate_accounts_and_references
        // (which checks Transfer structure) AND validate_transaction_batch
        // (which previously also checked Transfer invariants). After the fix,
        // validate_transaction_batch skips Transfer checks, so a malformed
        // Transfer yields exactly ONE error — not two.
        let tx = Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 50_000,
            description: "transfer".to_string(),
            person_name: None,
            person_id: None,
            date: 1710000000000,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        };
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "0.2.0".to_string(),
            transactions: vec![tx],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "Main".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        let transfer_errors: Vec<&String> = result
            .errors
            .iter()
            .filter(|e| e.contains("destination_account_id"))
            .collect();
        assert_eq!(
            transfer_errors.len(),
            1,
            "expected exactly one destination_account_id error, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_backup_transfer_same_source_dest_not_duplicated() {
        // Same as above but for source == destination. Also must produce
        // exactly ONE error (no duplicates from validate_transaction_batch).
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "0.2.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 50_000,
                description: "transfer".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: Some(1),
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "Main".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        let transfer_errors: Vec<&String> = result
            .errors
            .iter()
            .filter(|e| e.contains("must differ"))
            .collect();
        assert_eq!(
            transfer_errors.len(),
            1,
            "expected exactly one must-differ error, got: {:?}",
            result.errors
        );
    }

    // =====================================================================
    // Transaction account reference validation
    // =====================================================================

    #[test]
    fn test_backup_rejects_tx_with_nonexistent_source_account() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 50000,
                description: "coffee".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 99, // no such account
                destination_account_id: None,
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "Main".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 1710000000000,
                updated_at: 1710000000000,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("non-existent source account")));
    }

    #[test]
    fn test_backup_rejects_tx_with_nonexistent_destination_account() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 50000,
                description: "transfer".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: Some(99), // no such account
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![Account {
                id: 1,
                name: "Main".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 1710000000000,
                updated_at: 1710000000000,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("non-existent destination account")));
    }

    #[test]
    fn test_backup_valid_with_all_accounts_referenced() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 50000,
                description: "transfer".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: Some(2),
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![
                Account {
                    id: 1,
                    name: "Main".to_string(),
                    account_type: "BANK".to_string(),
                    bank_name: None,
                    card_number: None,
                    account_number: None,
                    iban: None,
                    initial_balance: 0,
                    color: 0xFF4CAF50,
                    icon: None,
                    is_archived: false,
                    display_order: 0,
                    created_at: 1710000000000,
                    updated_at: 1710000000000,
                },
                Account {
                    id: 2,
                    name: "Savings".to_string(),
                    account_type: "SAVINGS_INVESTMENT".to_string(),
                    bank_name: None,
                    card_number: None,
                    account_number: None,
                    iban: None,
                    initial_balance: 0,
                    color: 0xFF4CAF50,
                    icon: None,
                    is_archived: false,
                    display_order: 0,
                    created_at: 1710000000000,
                    updated_at: 1710000000000,
                },
            ],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "Expected valid payload, got errors: {:?}",
            result.errors
        );
    }

    // =====================================================================
    // Legacy backup: accounts list empty, transactions must use legacy ID=1
    // =====================================================================

    #[test]
    fn test_backup_rejects_non_legacy_account_id_when_accounts_empty() {
        // Corrupted old backup: accounts=[], tx references account_id=999.
        // Must fail fast to prevent orphaned balances.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 50000,
                description: "coffee".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 999, // non-legacy, no accounts list to reference
                destination_account_id: None,
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![], // old backup format — no accounts,
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("non-legacy account")));
    }

    #[test]
    fn test_backup_accepts_legacy_account_id_when_accounts_empty() {
        // Genuine old backup: accounts=[], tx references account_id=1.
        // This is the single legacy account that existed before multi-account.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 50000,
                description: "coffee".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1, // legacy default
                destination_account_id: None,
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![], // old backup format,
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "Expected valid legacy payload, got errors: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_backup_rejects_non_legacy_dest_account_when_accounts_empty() {
        // Old backup with transfer to a non-legacy destination account.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 50000,
                description: "transfer".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: Some(99), // non-legacy
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![], // old backup format,
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(result
            .errors
            .iter()
            .any(|e| e.contains("non-legacy destination account")));
    }

    #[test]
    fn test_validate_backup_rejects_blank_and_duplicate_persons() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1,
            app_version: "1.0".into(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![],
            persons: vec![
                Person {
                    id: 1,
                    name: "".into(),
                    normalized_name: "ali".into(),
                    phone: None,
                    notes: None,
                    created_at: 0,
                    is_archived: false,
                },
                Person {
                    id: 2,
                    name: "Ali".into(),
                    normalized_name: "".into(),
                    phone: None,
                    notes: None,
                    created_at: 0,
                    is_archived: false,
                },
                Person {
                    id: 3,
                    name: "Sara".into(),
                    normalized_name: "sara".into(),
                    phone: None,
                    notes: None,
                    created_at: 0,
                    is_archived: false,
                },
                Person {
                    id: 4,
                    // Differs from "Sara" only by case: both names normalize to
                    // "sara", so the duplicate must be caught from the derived key.
                    name: "SARA".into(),
                    normalized_name: "sara2".into(),
                    phone: None,
                    notes: None,
                    created_at: 0,
                    is_archived: false,
                },
            ],
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        let joined = result.errors.join(" | ");
        assert!(
            joined.contains("blank name"),
            "expected blank name error: {joined}"
        );
        assert!(
            joined.contains("blank normalizedName"),
            "expected blank normalizedName error: {joined}"
        );
        assert!(
            joined.contains("duplicate normalizedName"),
            "expected duplicate normalizedName error: {joined}"
        );
    }

    #[test]
    fn test_validate_backup_derives_person_key_from_name_not_supplied_field() {
        // A tampered normalizedName must not decide identity. Both entries carry
        // the same supplied key, but their names are distinct people, so the
        // payload is valid. The restore path re-derives the key from the name.
        let payload = BackupPayload {
            persons: vec![
                Person {
                    id: 1,
                    name: "Ali".into(),
                    normalized_name: "reza".into(),
                    ..person_default()
                },
                Person {
                    id: 2,
                    name: "Reza".into(),
                    normalized_name: "reza".into(),
                    ..person_default()
                },
            ],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "names are distinct so the payload must pass: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_rejects_duplicate_person_ids() {
        let payload = BackupPayload {
            persons: vec![
                Person {
                    id: 7,
                    name: "Ali".into(),
                    normalized_name: "ali".into(),
                    ..person_default()
                },
                Person {
                    id: 7,
                    name: "Sara".into(),
                    normalized_name: "sara".into(),
                    ..person_default()
                },
            ],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(
            result.errors.iter().any(|e| e.contains("duplicate id 7")),
            "expected duplicate id error: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_normalize_person_name_matches_kotlin_contract() {
        // Parity with PersonNameNormalizer.kt: trim, collapse whitespace, strip
        // zero-width, fold Arabic variants to Persian, lowercase.
        assert_eq!(normalize_person_name("  Ali  "), "ali");
        assert_eq!(normalize_person_name("Ali   Reza"), "ali reza");
        assert_eq!(normalize_person_name("علی\u{200B}رضا"), "علیرضا");
        assert_eq!(normalize_person_name("يک"), "یک");
        assert_eq!(normalize_person_name("ة"), "ه");
        assert_eq!(normalize_person_name("\u{200C}\u{200B}"), "");

        // NBSP parity: Java `Character.isWhitespace` EXCLUDES NBSP (`U+00A0`),
        // NNBSP (`U+2007`) and NARROW NBSP (`U+202F`). The Kotlin normalizer
        // therefore treats NBSP as a literal character, never as a space to
        // trim or collapse. Rust `char::is_whitespace` would wrongly fold it,
        // so `is_java_whitespace` must keep this divergence.
        // NBSP is preserved verbatim (not collapsed to a normal space).
        assert_eq!(normalize_person_name("محمد\u{00A0}رضا"), "محمد\u{00A0}رضا");
        // A normal ASCII space path collapses to a single space — proving NBSP
        // does NOT take the whitespace branch.
        assert_eq!(normalize_person_name("محمد رضا"), "محمد رضا");
        // NBSP at the edges is NOT trimmed (it is not whitespace).
        assert_eq!(normalize_person_name("\u{00A0}ali"), "\u{00A0}ali");
        assert_eq!(normalize_person_name("ali\u{00A0}"), "ali\u{00A0}");
        // NNBSP and NARROW NBSP are likewise preserved, not folded.
        assert_eq!(normalize_person_name("a\u{2007}b"), "a\u{2007}b");
        assert_eq!(normalize_person_name("a\u{202F}b"), "a\u{202F}b");

        // Case-fold parity: Kotlin `Char.lowercaseChar()` (Java
        // `Character.toLowerCase`) applies the Unicode SIMPLE lowercase. For
        // `İ` (U+0130) that simple mapping is a single `i`, so Kotlin yields
        // "istanbul"; Rust's full `to_lowercase` would expand `İ` to "i\u{307}"
        // and our `count() == 1` guard would wrongly keep it, so we map it
        // explicitly. The result must match Kotlin's `PersonNameNormalizer`.
        assert_eq!(normalize_person_name("İ"), "i");
        assert_eq!(normalize_person_name("İstanbul"), "istanbul");
        // Latin simple fold still works for single-codepoint mappings.
        assert_eq!(normalize_person_name("ALI"), "ali");
    }

    #[test]
    fn test_validate_backup_rejects_zero_width_only_person_name() {
        let payload = BackupPayload {
            persons: vec![Person {
                id: 1,
                name: "\u{200C}".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(
            result
                .errors
                .iter()
                .any(|e| e.contains("blank normalizedName")),
            "a zero-width-only name must normalize to empty: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_person_reference_accepts_declared_person_on_loan() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            loans: vec![Loan {
                id: 1,
                person_name: "Ali".into(),
                person_id: Some(1),
                loan_type: "DEBTOR".into(),
                original_amount: 5_000_000,
                remaining_amount: 3_000_000,
                description: "test".into(),
                date: 1710000000000,
                is_settled: false,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "declared person_id on loan must be accepted, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_person_reference_accepts_declared_person_on_transaction() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 50_000,
                description: "coffee".into(),
                person_name: None,
                person_id: Some(1),
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "declared person_id on transaction must be accepted, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_person_reference_rejects_orphan_loan_person_id() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            loans: vec![Loan {
                id: 1,
                person_name: "Unknown".into(),
                person_id: Some(99), // positive but not declared
                loan_type: "DEBTOR".into(),
                original_amount: 5_000_000,
                remaining_amount: 3_000_000,
                description: "test".into(),
                date: 1710000000000,
                is_settled: false,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(
            result
                .errors
                .iter()
                .any(|e| e.contains("non-existent person")),
            "expected orphan person_id error on loan, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_person_reference_rejects_orphan_transaction_person_id() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 50_000,
                description: "coffee".into(),
                person_name: None,
                person_id: Some(99), // positive but not declared
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(!result.is_valid);
        assert!(
            result
                .errors
                .iter()
                .any(|e| e.contains("non-existent person")),
            "expected orphan person_id error on transaction, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_person_reference_tolerates_null_person_id() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            loans: vec![Loan {
                id: 1,
                person_name: "Ali".into(),
                person_id: None, // null is a legacy default, tolerated
                loan_type: "DEBTOR".into(),
                original_amount: 5_000_000,
                remaining_amount: 3_000_000,
                description: "test".into(),
                date: 1710000000000,
                is_settled: false,
            }],
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 50_000,
                description: "coffee".into(),
                person_name: None,
                person_id: None, // null is a legacy default, tolerated
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            }],
            ..Default::default()
        };
        let result = validate_backup_payload(&payload);
        assert!(
            result.is_valid,
            "null person_id must be tolerated, got: {:?}",
            result.errors
        );
    }

    #[test]
    fn test_validate_backup_validates_persons_in_fail_fast_ffi_path() {
        // validate_backup (FFI path) must also reject a person with a blank name.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            ..Default::default()
        };
        let err = crate::validate_backup(&payload).unwrap_err().to_string();
        assert!(err.contains("blank name"), "got: {}", err);
    }

    #[test]
    fn test_validate_backup_fail_fast_rejects_orphan_person_reference() {
        // validate_backup (FFI path) must also reject a loan referencing a
        // non-existent positive person_id.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            loans: vec![Loan {
                id: 1,
                person_name: "Unknown".into(),
                person_id: Some(99),
                loan_type: "DEBTOR".into(),
                original_amount: 5_000_000,
                remaining_amount: 3_000_000,
                description: "test".into(),
                date: 1710000000000,
                is_settled: false,
            }],
            ..Default::default()
        };
        let err = crate::validate_backup(&payload).unwrap_err().to_string();
        assert!(err.contains("non-existent person"), "got: {}", err);
    }

    #[test]
    fn test_validate_backup_fail_fast_accepts_person_only_payload() {
        // A backup with only well-formed persons must pass both the empty guard
        // and the person validation in the FFI path.
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0".to_string(),
            persons: vec![Person {
                id: 1,
                name: "Ali".into(),
                normalized_name: "ali".into(),
                ..person_default()
            }],
            ..Default::default()
        };
        crate::validate_backup(&payload).unwrap_or_else(|e| {
            panic!(
                "person-only payload should be accepted by FFI path, got: {}",
                e
            )
        });
    }

    fn person_default() -> Person {
        Person {
            id: 0,
            name: "".into(),
            normalized_name: "".into(),
            phone: None,
            notes: None,
            created_at: 0,
            is_archived: false,
        }
    }
}
