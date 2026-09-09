use serde::{Deserialize, Serialize};

/// Backup format/schema version. Bump ONLY on a breaking change to the
/// serialized backup structure. This is the single source of truth: the Kotlin
/// side derives `BuildConfig.BACKUP_SCHEMA_VERSION` from this const at build
/// time (app/build.gradle.kts), so the two sides cannot drift.
pub const BACKUP_SCHEMA_VERSION: i32 = 2;

/// Deserialize an i64 where 0 means None (sentinel for null from Kotlin exports).
/// Also accepts JSON null for compatibility with nullable exports.
fn deserialize_zero_as_none<'de, D>(deserializer: D) -> Result<Option<i64>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let val = Option::<i64>::deserialize(deserializer)?;
    Ok(val.filter(|&v| v != 0))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransactionType {
    #[default]
    #[serde(alias = "Expense")]
    Expense,
    #[serde(alias = "Income")]
    Income,
    #[serde(alias = "LoanDebtor")]
    LoanDebtor,
    #[serde(alias = "LoanCreditor")]
    LoanCreditor,
    #[serde(alias = "Installment")]
    Installment,
    #[serde(alias = "Transfer")]
    Transfer,
}

impl TransactionType {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Expense => "EXPENSE",
            Self::Income => "INCOME",
            Self::LoanDebtor => "LOAN_DEBTOR",
            Self::LoanCreditor => "LOAN_CREDITOR",
            Self::Installment => "INSTALLMENT",
            Self::Transfer => "TRANSFER",
        }
    }

    // Deliberately not `std::str::FromStr`: this parser is lenient (unknown
    // values map to `Expense`, no `Result`), and the method name is part of
    // the generated UniFFI bindings.
    #[allow(clippy::should_implement_trait)]
    pub fn from_str(s: &str) -> Self {
        match s {
            "INCOME" => Self::Income,
            "LOAN_DEBTOR" => Self::LoanDebtor,
            "LOAN_CREDITOR" => Self::LoanCreditor,
            "INSTALLMENT" => Self::Installment,
            "TRANSFER" => Self::Transfer,
            _ => Self::Expense,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Transaction {
    pub id: i64,
    #[serde(rename = "type", alias = "tx_type")]
    pub tx_type: TransactionType,
    #[serde(alias = "categoryId")]
    pub category_id: i64,
    pub amount: i64,
    pub description: String,
    #[serde(skip_serializing_if = "Option::is_none", alias = "personName")]
    pub person_name: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_zero_as_none",
        alias = "personId"
    )]
    pub person_id: Option<i64>,
    pub date: i64,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_zero_as_none",
        alias = "dueDate"
    )]
    pub due_date: Option<i64>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_zero_as_none",
        alias = "installmentId"
    )]
    pub installment_id: Option<i64>,
    #[serde(default = "default_account_id", alias = "accountId")]
    pub account_id: i64,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_zero_as_none",
        alias = "destinationAccountId"
    )]
    pub destination_account_id: Option<i64>,
}

/// The single account id that existed before multi-account support. Legacy
/// (pre-multi-account) backups omit the accounts list entirely and every
/// transaction must reference this id; validation enforces it via this shared
/// constant so the check cannot drift from the serde default.
pub const DEFAULT_ACCOUNT_ID: i64 = 1;

fn default_account_id() -> i64 {
    DEFAULT_ACCOUNT_ID
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Account {
    pub id: i64,
    pub name: String,
    #[serde(rename = "type", alias = "accountType")]
    pub account_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "bankName")]
    pub bank_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "cardNumber")]
    pub card_number: Option<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        alias = "accountNumber"
    )]
    pub account_number: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "iban")]
    pub iban: Option<String>,
    #[serde(default, alias = "initialBalance")]
    pub initial_balance: i64,
    #[serde(default = "default_color")]
    pub color: i64,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "icon")]
    pub icon: Option<String>,
    #[serde(default, alias = "isArchived")]
    pub is_archived: bool,
    #[serde(default, alias = "displayOrder")]
    pub display_order: i32,
    #[serde(default, alias = "createdAt")]
    pub created_at: i64,
    #[serde(default, alias = "updatedAt")]
    pub updated_at: i64,
}

fn default_color() -> i64 {
    0xFF4CAF50
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Loan {
    pub id: i64,
    #[serde(alias = "personName")]
    pub person_name: String,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        deserialize_with = "deserialize_zero_as_none",
        alias = "personId"
    )]
    pub person_id: Option<i64>,
    #[serde(rename = "type", alias = "loanType")]
    pub loan_type: String,
    #[serde(alias = "originalAmount")]
    pub original_amount: i64,
    #[serde(alias = "remainingAmount")]
    pub remaining_amount: i64,
    pub description: String,
    pub date: i64,
    #[serde(alias = "isSettled")]
    pub is_settled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Installment {
    pub id: i64,
    pub title: String,
    pub amount: i64,
    #[serde(alias = "dueDate")]
    pub due_date: i64,
    #[serde(alias = "isPaid")]
    pub is_paid: bool,
    #[serde(alias = "reminderEnabled")]
    pub reminder_enabled: bool,
    pub notes: String,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "bankLoanId")]
    pub bank_loan_id: Option<i64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct BankLoan {
    pub id: i64,
    #[serde(alias = "bankName")]
    pub bank_name: String,
    #[serde(alias = "loanName")]
    pub loan_name: String,
    #[serde(alias = "receivedAmount")]
    pub received_amount: i64,
    #[serde(alias = "monthlyInstallmentAmount")]
    pub monthly_installment_amount: i64,
    #[serde(alias = "numberOfInstallments")]
    pub number_of_installments: i32,
    #[serde(alias = "totalRepayableAmount")]
    pub total_repayable_amount: i64,
    #[serde(alias = "totalInterest")]
    pub total_interest: i64,
    #[serde(alias = "startDate")]
    pub start_date: i64,
    pub description: String,
    #[serde(alias = "isSettled")]
    pub is_settled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct PaymentHistory {
    pub id: i64,
    pub loan_id: i64,
    pub amount: i64,
    pub date: i64,
    #[serde(default)]
    pub notes: Option<String>,
}

/// A person ledger identity (person-ledger redesign, plans/011). Display name
/// is the first trimmed original spelling; `normalized_name` is the dedup key
/// (see `PersonNameNormalizer` on the Kotlin side, an ADR-001 permanent
/// fallback because Room migrations cannot load the native library).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Person {
    pub id: i64,
    pub name: String,
    pub normalized_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "phone")]
    pub phone: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none", alias = "notes")]
    pub notes: Option<String>,
    #[serde(default, alias = "createdAt")]
    pub created_at: i64,
    #[serde(default, alias = "isArchived")]
    pub is_archived: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct BankLoanSummary {
    pub bank_name: String,
    pub loan_name: String,
    pub received_amount: i64,
    pub total_repayable_amount: i64,
    pub total_interest: i64,
    pub number_of_installments: i32,
    pub is_settled: bool,
    pub remaining_debt: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Category {
    pub id: i64,
    pub name: String,
    pub key: String,
    pub icon: String,
    pub color: i64,
    #[serde(rename = "type", alias = "categoryType")]
    pub category_type: String,
    #[serde(alias = "isDefault")]
    pub is_default: bool,
}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct ParsedResult {
    pub tx_type: TransactionType,
    pub amount: i64,
    pub category: String,
    pub person_name: Option<String>,
    pub description: String,
    pub days_from_now: Option<i32>,
    pub title: Option<String>,
    pub date_offset_days: Option<i32>,
    pub hour: Option<i32>,
    pub minute: Option<i32>,
    pub confidence: f32,
    pub notes: Option<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct JalaliDate {
    pub year: i32,
    pub month: i32,
    pub day: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CurrencyUnit {
    Rial,
    Toman,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, uniffi::Record)]
pub struct DashboardData {
    pub current_balance: i64,
    pub monthly_expenses: i64,
    pub monthly_income: i64,
    pub debtors_total: i64,
    pub creditors_total: i64,
    pub savings_rate: f64,
    pub debt_to_income_ratio: f64,
    pub bank_loans_total: i64,
    pub bank_loans: Vec<BankLoanSummary>,
    #[serde(default)]
    pub accounts: Vec<AccountDashboardSummary>,
    #[serde(default)]
    pub total_net_worth: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, uniffi::Record)]
pub struct AccountDashboardSummary {
    pub account_id: i64,
    pub account_name: String,
    pub account_type: String,
    pub balance: i64,
    pub monthly_income: i64,
    pub monthly_expenses: i64,
    /// Month-over-month net change ratio.
    ///
    /// `monthly_delta = (currentNet - previousNet) / max(abs(previousNet), 1)`
    ///
    /// Set to `0.0` when the previous month's net (abs) is below a noise
    /// threshold (1 000 Rial ≈ smallest meaningful currency unit in the app) to
    /// avoid misleading percentages near zero.
    #[serde(default)]
    pub monthly_delta: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct MonthlyData {
    pub jalali_year: i32,
    pub jalali_month: i32,
    pub label: String,
    pub income: i64,
    pub expense: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct CategoryBreakdown {
    pub category_id: i64,
    pub category_name: String,
    pub color: i64,
    pub total: i64,
    pub percentage: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct DebtSummary {
    pub person_name: String,
    pub original_amount: i64,
    pub remaining_amount: i64,
    pub debt_type: String,
    pub progress: f32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct InstallmentProgress {
    pub id: i64,
    pub title: String,
    pub amount: i64,
    pub due_date: i64,
    pub is_paid: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, uniffi::Record)]
pub struct AnalyticsData {
    pub monthly_spending: Vec<MonthlyData>,
    pub monthly_income: Vec<MonthlyData>,
    pub category_breakdown: Vec<CategoryBreakdown>,
    pub debtors: Vec<DebtSummary>,
    pub creditors: Vec<DebtSummary>,
    pub total_debt: i64,
    pub total_credit: i64,
    pub total_installments: i32,
    pub paid_installments: i32,
    pub bank_loans: Vec<BankLoanSummary>,
    pub bank_loans_total_debt: i64,
    #[serde(default)]
    pub accounts: Vec<AccountAnalytics>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, uniffi::Record)]
pub struct AccountAnalytics {
    pub account_id: i64,
    pub account_name: String,
    pub monthly_data: Vec<MonthlyData>,
    pub category_breakdown: Vec<CategoryBreakdown>,
}

/// Backup payload for JSON export/import.
///
/// Serde's default behavior silently ignores unknown fields during
/// deserialization. This means Kotlin can pass a JSON containing extra keys
/// (e.g. `paymentHistories`, `budgets`, or any future field) and Rust will
/// parse it without error — the extra fields are simply discarded.
///
/// Missing fields default to empty collections via `#[serde(default)]`.
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct BackupPayload {
    pub version: i32,
    pub timestamp: i64,
    #[serde(alias = "appVersion")]
    pub app_version: String,
    #[serde(default)]
    pub transactions: Vec<Transaction>,
    #[serde(default)]
    pub loans: Vec<Loan>,
    #[serde(default)]
    pub installments: Vec<Installment>,
    #[serde(default)]
    pub bank_loans: Vec<BankLoan>,
    #[serde(default)]
    pub payment_histories: Vec<PaymentHistory>,
    #[serde(default)]
    pub categories: Vec<Category>,
    #[serde(default)]
    pub accounts: Vec<Account>,
    #[serde(default)]
    pub persons: Vec<Person>,
}

impl Default for BackupPayload {
    fn default() -> Self {
        Self {
            version: BACKUP_SCHEMA_VERSION,
            timestamp: 0,
            app_version: env!("CORE_VERSION").to_string(),
            transactions: Vec::new(),
            loans: Vec::new(),
            installments: Vec::new(),
            bank_loans: Vec::new(),
            payment_histories: Vec::new(),
            categories: Vec::new(),
            accounts: Vec::new(),
            persons: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum HesabyarError {
    ParseError { detail: String },
    InvalidAmount { amount: String },
    InvalidDate { detail: String },
    BackupValidation { detail: String },
    CalendarError { detail: String },
    CryptoError { detail: String },
    ValidationError { detail: String },
}

impl std::fmt::Display for HesabyarError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::ParseError { detail } => write!(f, "Parse error: {}", detail),
            Self::InvalidAmount { amount } => write!(f, "Invalid amount: {}", amount),
            Self::InvalidDate { detail } => write!(f, "Invalid date: {}", detail),
            Self::BackupValidation { detail } => write!(f, "Backup validation: {}", detail),
            Self::CalendarError { detail } => write!(f, "Calendar error: {}", detail),
            Self::CryptoError { detail } => write!(f, "Crypto error: {}", detail),
            Self::ValidationError { detail } => write!(f, "Validation error: {}", detail),
        }
    }
}

impl std::error::Error for HesabyarError {}

#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct CategoryGuess {
    pub category: String,
    pub subcategory: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_backup_payload_ignores_unknown_fields() {
        // Kotlin may include future fields. Rust must parse without error;
        // extra fields are silently discarded.
        let json = r#"{
            "version": 1,
            "timestamp": 1710000000000,
            "appVersion": "1.0.0",
            "transactions": [],
            "loans": [],
            "installments": [],
            "categories": [],
            "paymentHistories": [{"id": 1, "loanId": 1, "amount": 50000, "date": 1710000000000, "notes": "x"}],
            "budgets": [{"monthly_limit": 1000000}],
            "futureField": "hello"
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert_eq!(payload.version, 1);
        assert_eq!(payload.app_version, "1.0.0");
        assert!(payload.transactions.is_empty());
        assert_eq!(payload.payment_histories.len(), 1);
    }

    #[test]
    fn test_backup_payload_defaults_missing_collections() {
        // If Kotlin omits collection fields entirely, they default to empty Vecs.
        let json = r#"{
            "version": 2,
            "timestamp": 1710000000000,
            "appVersion": "2.0.0"
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert_eq!(payload.version, 2);
        assert!(payload.transactions.is_empty());
        assert!(payload.loans.is_empty());
        assert!(payload.installments.is_empty());
        assert!(payload.bank_loans.is_empty());
        assert!(payload.payment_histories.is_empty());
        assert!(payload.categories.is_empty());
    }

    #[test]
    fn test_backup_payload_valid_round_trip() {
        let original = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 10,
                amount: 50000,
                description: "Test".to_string(),
                person_name: None,
                person_id: None,
                date: 1710000000000,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            }],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![PaymentHistory {
                id: 1,
                loan_id: 10,
                amount: 200000,
                date: 1710000000000,
                notes: Some("Paid".to_string()),
            }],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let json = serde_json::to_string(&original).unwrap();
        let restored: BackupPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.version, original.version);
        assert_eq!(restored.transactions.len(), 1);
        assert_eq!(restored.transactions[0].amount, 50000);
        assert_eq!(restored.transactions[0].account_id, 1);
        assert_eq!(restored.payment_histories.len(), 1);
        assert_eq!(restored.payment_histories[0].amount, 200000);
    }

    #[test]
    fn test_transfer_type_round_trip() {
        // Verify TransactionType::Transfer survives serde round-trip
        // with both account_id and destination_account_id set.
        let original = BackupPayload {
            version: 2,
            timestamp: 1710000000000,
            app_version: "1.0.0".to_string(),
            transactions: vec![Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 0,
                amount: 300000,
                description: "Transfer between accounts".to_string(),
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
            accounts: vec![],
            ..Default::default()
        };
        let json = serde_json::to_string(&original).unwrap();
        let restored: BackupPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.transactions.len(), 1);
        assert_eq!(restored.transactions[0].tx_type, TransactionType::Transfer);
        assert_eq!(restored.transactions[0].account_id, 1);
        assert_eq!(restored.transactions[0].destination_account_id, Some(2));
        // Verify the serialized form uses "TRANSFER"
        assert!(json.contains("\"TRANSFER\""));
    }

    #[test]
    fn test_backup_payload_parses_camel_case_export() {
        // The app's Kotlin exporter (ManageBackupUseCase.exportBackupJson) writes
        // camelCase keys. Rust must parse that exact shape, not just its own output.
        let json = r#"{
            "version": 1,
            "timestamp": 1710000000000,
            "appVersion": "1.0",
            "categories": [
                {"id": 1, "name": "Food", "key": "food", "icon": "ic", "color": 0, "type": "EXPENSE", "isDefault": true}
            ],
            "transactions": [
                {"id": 1, "type": "EXPENSE", "categoryId": 10, "amount": 50000, "description": "Lunch", "personName": "", "date": 1710000000000, "dueDate": 0, "installmentId": 0}
            ],
            "loans": [
                {"id": 2, "personName": "Bob", "type": "DEBTOR", "originalAmount": 100000, "remainingAmount": 40000, "description": "Loan", "date": 1710000000000, "isSettled": false}
            ],
            "installments": [
                {"id": 3, "title": "Rent", "amount": 2000000, "dueDate": 1710000000000, "isPaid": false, "reminderEnabled": true, "notes": ""}
            ],
            "paymentHistories": [
                {"id": 5, "loanId": 2, "amount": 30000, "date": 1710000000000, "notes": "First"}
            ]
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert_eq!(payload.categories[0].category_type, "EXPENSE");
        assert_eq!(payload.transactions[0].category_id, 10);
        assert_eq!(payload.loans[0].loan_type, "DEBTOR");
        assert_eq!(payload.loans[0].original_amount, 100000);
        assert!(!payload.installments[0].is_paid);
        assert_eq!(payload.payment_histories.len(), 1);
        assert_eq!(payload.payment_histories[0].loan_id, 2);
    }

    #[test]
    fn test_backup_payload_parses_persons_camel_case() {
        // Kotlin exporter writes camelCase keys; Rust must parse that shape.
        let json = r#"{
            "version": 1,
            "timestamp": 1710000000000,
            "appVersion": "1.0",
            "persons": [
                {
                    "id": 1,
                    "name": "علی رضایی",
                    "normalizedName": "علی رضایی",
                    "phone": "09120000000",
                    "notes": "همکار قدیمی",
                    "createdAt": 1000,
                    "isArchived": false
                },
                {
                    "id": 2,
                    "name": "سارا",
                    "normalizedName": "سارا",
                    "createdAt": 2000,
                    "isArchived": true
                }
            ]
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert_eq!(payload.persons.len(), 2);
        let ali = &payload.persons[0];
        assert_eq!(ali.id, 1);
        assert_eq!(ali.name, "علی رضایی");
        assert_eq!(ali.normalized_name, "علی رضایی");
        assert_eq!(ali.phone.as_deref(), Some("09120000000"));
        assert_eq!(ali.notes.as_deref(), Some("همکار قدیمی"));
        assert_eq!(ali.created_at, 1000);
        assert!(!ali.is_archived);
        let sara = &payload.persons[1];
        assert_eq!(sara.id, 2);
        assert_eq!(sara.phone, None);
        assert_eq!(sara.notes, None);
        assert_eq!(sara.created_at, 2000);
        assert!(sara.is_archived);
    }

    #[test]
    fn test_backup_payload_round_trips_persons() {
        let original = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0.0".to_string(),
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
                    name: "علی رضایی".to_string(),
                    normalized_name: "علی رضایی".to_string(),
                    phone: Some("09120000000".to_string()),
                    notes: Some("همکار قدیمی".to_string()),
                    created_at: 1000,
                    is_archived: false,
                },
                Person {
                    id: 2,
                    name: "سارا".to_string(),
                    normalized_name: "سارا".to_string(),
                    phone: None,
                    notes: None,
                    created_at: 2000,
                    is_archived: true,
                },
            ],
        };
        let json = serde_json::to_string(&original).unwrap();
        let restored: BackupPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.persons.len(), 2);
        assert_eq!(restored.persons[0], original.persons[0]);
        assert_eq!(restored.persons[1], original.persons[1]);
    }

    #[test]
    fn test_backup_payload_persons_field_defaults_missing() {
        // Old backups without a `persons` key default to empty (backward-compatible).
        let json = r#"{
            "version": 1,
            "timestamp": 1710000000000,
            "appVersion": "1.0"
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert!(payload.persons.is_empty());
    }

    #[test]
    fn test_backup_payload_rejects_invalid_version() {
        let json = r#"{
            "version": 0,
            "timestamp": 0,
            "appVersion": "0.0.1"
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert_eq!(
            crate::validate_backup(&payload).unwrap_err().to_string(),
            "Backup validation: Invalid backup version"
        );
    }

    #[test]
    fn test_backup_payload_round_trips_bank_loans() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![BankLoan {
                id: 1,
                bank_name: "بانک ملت".to_string(),
                loan_name: "وام خودرو".to_string(),
                received_amount: 100_000_000,
                monthly_installment_amount: 10_000_000,
                number_of_installments: 12,
                total_repayable_amount: 120_000_000,
                total_interest: 20_000_000,
                start_date: 1710000000000,
                description: "".to_string(),
                is_settled: false,
            }],
            payment_histories: vec![PaymentHistory {
                id: 7,
                loan_id: 1,
                amount: 1500000,
                date: 1710000000000,
                notes: Some("Installment".to_string()),
            }],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let json = serde_json::to_string(&payload).unwrap();
        let restored: BackupPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.bank_loans.len(), 1);
        assert_eq!(restored.bank_loans[0].bank_name, "بانک ملت");
        assert_eq!(restored.bank_loans[0].total_repayable_amount, 120_000_000);
        assert_eq!(restored.payment_histories.len(), 1);
        assert_eq!(restored.payment_histories[0].amount, 1500000);
    }

    #[test]
    fn test_backup_payload_old_backup_no_bank_loans_defaults_empty() {
        let json = r#"{
            "version": 1,
            "timestamp": 1710000000000,
            "appVersion": "1.0",
            "transactions": [],
            "loans": [],
            "installments": [],
            "categories": []
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert!(payload.bank_loans.is_empty());
        assert!(payload.payment_histories.is_empty());
    }

    #[test]
    fn test_backup_payload_round_trips_payment_histories() {
        let payload = BackupPayload {
            version: 1,
            timestamp: 1710000000000,
            app_version: "1.0.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![
                PaymentHistory {
                    id: 1,
                    loan_id: 10,
                    amount: 50000,
                    date: 1710000000000,
                    notes: Some("First".to_string()),
                },
                PaymentHistory {
                    id: 2,
                    loan_id: 10,
                    amount: 75000,
                    date: 1710001000000,
                    notes: Some("Second".to_string()),
                },
            ],
            categories: vec![],
            accounts: vec![],
            ..Default::default()
        };
        let json = serde_json::to_string(&payload).unwrap();
        let restored: BackupPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.payment_histories.len(), 2);
        assert_eq!(restored.payment_histories[0].amount, 50000);
        assert_eq!(
            restored.payment_histories[1].notes,
            Some("Second".to_string())
        );
        assert_eq!(restored.payment_histories[1].loan_id, 10);
    }

    #[test]
    fn test_account_struct_round_trip() {
        let account = Account {
            id: 1,
            name: "حساب اصلی".to_string(),
            account_type: "BANK".to_string(),
            bank_name: Some("ملی".to_string()),
            card_number: Some("6104-3378-1234-5678".to_string()),
            account_number: Some("1234567890".to_string()),
            iban: Some("IR123456789012345678901234".to_string()),
            initial_balance: 1000000,
            color: 0xFF4CAF50,
            icon: Some("AccountBalance".to_string()),
            is_archived: false,
            display_order: 0,
            created_at: 0,
            updated_at: 0,
        };
        let json = serde_json::to_string(&account).unwrap();
        let restored: Account = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.id, 1);
        assert_eq!(restored.name, "حساب اصلی");
        assert_eq!(restored.account_type, "BANK");
        assert_eq!(restored.bank_name, Some("ملی".to_string()));
        assert_eq!(restored.initial_balance, 1000000);
    }

    #[test]
    fn test_backup_payload_with_accounts() {
        let payload = BackupPayload {
            version: 2,
            timestamp: 1710000000000,
            app_version: "2.0.0".to_string(),
            transactions: vec![],
            loans: vec![],
            installments: vec![],
            bank_loans: vec![],
            payment_histories: vec![],
            categories: vec![],
            accounts: vec![
                Account {
                    id: 1,
                    name: "حساب اصلی".to_string(),
                    account_type: "BANK".to_string(),
                    bank_name: Some("ملی".to_string()),
                    card_number: None,
                    account_number: None,
                    iban: None,
                    initial_balance: 0,
                    color: 0xFF4CAF50,
                    icon: None,
                    is_archived: false,
                    display_order: 0,
                    created_at: 0,
                    updated_at: 0,
                },
                Account {
                    id: 2,
                    name: "کیف پول نقدی".to_string(),
                    account_type: "CASH_WALLET".to_string(),
                    bank_name: None,
                    card_number: None,
                    account_number: None,
                    iban: None,
                    initial_balance: 500000,
                    color: 0xFFFF9800,
                    icon: None,
                    is_archived: false,
                    display_order: 1,
                    created_at: 0,
                    updated_at: 0,
                },
            ],
            ..Default::default()
        };
        let json = serde_json::to_string(&payload).unwrap();
        let restored: BackupPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.accounts.len(), 2);
        assert_eq!(restored.accounts[0].account_type, "BANK");
        assert_eq!(restored.accounts[1].account_type, "CASH_WALLET");
        assert_eq!(restored.accounts[1].initial_balance, 500000);
    }

    #[test]
    fn test_backup_v1_without_accounts_defaults_empty() {
        let json = r#"{
            "version": 1,
            "timestamp": 1710000000000,
            "appVersion": "1.0.0",
            "transactions": [],
            "loans": [],
            "installments": [],
            "categories": []
        }"#;
        let payload: BackupPayload = serde_json::from_str(json).unwrap();
        assert!(payload.accounts.is_empty());
    }

    #[test]
    fn test_transaction_with_account_fields() {
        let tx = Transaction {
            id: 1,
            tx_type: TransactionType::Expense,
            category_id: 10,
            amount: 50000,
            description: "Test".to_string(),
            person_name: None,
            person_id: None,
            date: 1710000000000,
            due_date: None,
            installment_id: None,
            account_id: 2,
            destination_account_id: Some(3),
        };
        let json = serde_json::to_string(&tx).unwrap();
        let restored: Transaction = serde_json::from_str(&json).unwrap();
        assert_eq!(restored.account_id, 2);
        assert_eq!(restored.destination_account_id, Some(3));
    }

    #[test]
    fn test_transaction_defaults_account_id_to_1() {
        let json = r#"{
            "id": 1,
            "type": "EXPENSE",
            "categoryId": 10,
            "amount": 50000,
            "description": "Test",
            "date": 1710000000000
        }"#;
        let tx: Transaction = serde_json::from_str(json).unwrap();
        assert_eq!(tx.account_id, 1);
        assert!(tx.destination_account_id.is_none());
    }

    #[test]
    fn test_loan_person_id_none_omits_field_and_roundtrips() {
        let loan = Loan {
            id: 7,
            person_name: "Ali".to_string(),
            person_id: None,
            loan_type: "DEBTOR".to_string(),
            original_amount: 5000000,
            remaining_amount: 3000000,
            description: "test".to_string(),
            date: 1710000000000,
            is_settled: false,
        };
        let json = serde_json::to_string(&loan).unwrap();
        // The None id must be omitted entirely, never serialized as a sentinel
        // or null, so backups cannot regress on the person link.
        assert!(!json.contains("personId"));
        let restored: Loan = serde_json::from_str(&json).unwrap();
        assert!(restored.person_id.is_none());
        assert_eq!(restored.person_name, "Ali");
    }

    #[test]
    fn test_transaction_person_id_zero_sentinel_deserializes_to_none() {
        let json = r#"{
            "id": 1,
            "type": "EXPENSE",
            "categoryId": 1,
            "amount": 1000,
            "description": "x",
            "date": 0,
            "personId": 0
        }"#;
        let tx: Transaction = serde_json::from_str(json).unwrap();
        assert_eq!(tx.person_id, None);
    }

    #[test]
    fn test_loan_person_id_zero_sentinel_deserializes_to_none() {
        let json = r#"{
            "id": 1,
            "personName": "Ali",
            "type": "DEBTOR",
            "originalAmount": 1000,
            "remainingAmount": 500,
            "description": "x",
            "date": 0,
            "isSettled": false,
            "personId": 0
        }"#;
        let loan: Loan = serde_json::from_str(json).unwrap();
        assert_eq!(loan.person_id, None);
    }

    #[test]
    fn test_person_id_absent_deserializes_to_none() {
        let tx_json = r#"{
            "id": 1,
            "type": "EXPENSE",
            "categoryId": 1,
            "amount": 1000,
            "description": "x",
            "date": 0
        }"#;
        let tx: Transaction = serde_json::from_str(tx_json).unwrap();
        assert_eq!(tx.person_id, None);
        let loan_json = r#"{
            "id": 1,
            "personName": "Ali",
            "type": "DEBTOR",
            "originalAmount": 1000,
            "remainingAmount": 500,
            "description": "x",
            "date": 0,
            "isSettled": false
        }"#;
        let loan: Loan = serde_json::from_str(loan_json).unwrap();
        assert_eq!(loan.person_id, None);
    }

    #[test]
    fn test_person_id_null_deserializes_to_none() {
        let tx_json = r#"{
            "id": 1,
            "type": "EXPENSE",
            "categoryId": 1,
            "amount": 1000,
            "description": "x",
            "date": 0,
            "personId": null
        }"#;
        let tx: Transaction = serde_json::from_str(tx_json).unwrap();
        assert_eq!(tx.person_id, None);
        let loan_json = r#"{
            "id": 1,
            "personName": "Ali",
            "type": "DEBTOR",
            "originalAmount": 1000,
            "remainingAmount": 500,
            "description": "x",
            "date": 0,
            "isSettled": false,
            "personId": null
        }"#;
        let loan: Loan = serde_json::from_str(loan_json).unwrap();
        assert_eq!(loan.person_id, None);
    }

    #[test]
    fn test_person_id_valid_round_trip() {
        let tx_json = r#"{
            "id": 1,
            "type": "EXPENSE",
            "categoryId": 1,
            "amount": 1000,
            "description": "x",
            "date": 0,
            "personId": 42
        }"#;
        let tx: Transaction = serde_json::from_str(tx_json).unwrap();
        assert_eq!(tx.person_id, Some(42));
        let serialized = serde_json::to_string(&tx).unwrap();
        let restored: Transaction = serde_json::from_str(&serialized).unwrap();
        assert_eq!(restored.person_id, Some(42));
        let loan_json = r#"{
            "id": 1,
            "personName": "Ali",
            "type": "DEBTOR",
            "originalAmount": 1000,
            "remainingAmount": 500,
            "description": "x",
            "date": 0,
            "isSettled": false,
            "personId": 42
        }"#;
        let loan: Loan = serde_json::from_str(loan_json).unwrap();
        assert_eq!(loan.person_id, Some(42));
        let serialized = serde_json::to_string(&loan).unwrap();
        let restored: Loan = serde_json::from_str(&serialized).unwrap();
        assert_eq!(restored.person_id, Some(42));
    }

    #[test]
    fn test_transaction_person_id_none_skips_serializing() {
        let tx = Transaction {
            id: 1,
            tx_type: TransactionType::Expense,
            category_id: 1,
            amount: 1000,
            description: "x".to_string(),
            person_name: None,
            person_id: None,
            date: 0,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        };
        let json = serde_json::to_string(&tx).unwrap();
        assert!(!json.contains("personId"));
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v.get("personId"), None);
    }

    #[test]
    fn test_loan_person_id_some_serializes() {
        let loan = Loan {
            id: 1,
            person_name: "Ali".to_string(),
            person_id: Some(7),
            loan_type: "DEBTOR".to_string(),
            original_amount: 1000,
            remaining_amount: 500,
            description: "x".to_string(),
            date: 0,
            is_settled: false,
        };
        let json = serde_json::to_string(&loan).unwrap();
        assert!(json.contains("\"personId\":7"));
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v.get("personId").and_then(|v| v.as_i64()), Some(7));
    }
}
