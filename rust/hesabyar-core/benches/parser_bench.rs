// Benchmarks run fixed, known-good fixtures. A failed call must stop the
// benchmark loudly, so unwrap is the right tool here.
#![allow(clippy::unwrap_used)]

use criterion::{criterion_group, criterion_main, Criterion};
use hesabyar_core::advisory::{calculate_financial_health_score, get_offline_budget_advice};
use hesabyar_core::ai_validation::{parse_ai_transaction_json, validate_ai_advice};
use hesabyar_core::analytics::compute_analytics;
use hesabyar_core::calendar::{gregorian_to_jalali, jalali_to_gregorian};
use hesabyar_core::crypto::compute_checksum;
use hesabyar_core::currency::{format_currency, format_number, from_rial, to_rial};
use hesabyar_core::dashboard::compute_dashboard_data;
use hesabyar_core::excel::{generate_excel, Cell, SheetData, WorkbookData};
use hesabyar_core::models::CurrencyUnit;
use hesabyar_core::models::*;
use hesabyar_core::parser::amount::parse_amount;
use hesabyar_core::parser::money_detector::contains_money;
use hesabyar_core::search::{search_transactions, SearchQuery};
use hesabyar_core::validation::{validate_backup_payload, validate_transaction};
use std::hint::black_box;

fn bench_parse_amount(c: &mut Criterion) {
    c.bench_function("parse_500k_toman", |b| {
        b.iter(|| parse_amount("\u{06F5}\u{06F0}\u{06F0} \u{0647}\u{0632}\u{0627}\u{0631} \u{062A}\u{0648}\u{0645}\u{0646} \u{0628}\u{0627}\u{0628}\u{062A} \u{0628}\u{0631}\u{0642}", true))
    });
}

fn bench_money_detector(c: &mut Criterion) {
    c.bench_function("contains_money", |b| {
        b.iter(|| contains_money("\u{06F5}\u{06F0}\u{06F0}\u{06F0} \u{0647}\u{0632}\u{0627}\u{0631} \u{062A}\u{0648}\u{0645}\u{0646}"))
    });
}

fn bench_jalali_calendar(c: &mut Criterion) {
    c.bench_function("gregorian_to_jalali", |b| {
        b.iter(|| gregorian_to_jalali(1711000000000))
    });
    c.bench_function("jalali_to_gregorian", |b| {
        b.iter(|| jalali_to_gregorian(1403, 1, 1))
    });
}

fn bench_budget_advice(c: &mut Criterion) {
    let transactions: Vec<Transaction> = (0..100)
        .map(|i| Transaction {
            id: i,
            tx_type: if i % 3 == 0 {
                TransactionType::Income
            } else {
                TransactionType::Expense
            },
            category_id: (i % 8),
            amount: (i + 1) * 10000,
            description: format!("Transaction {}", i),
            person_name: None,
            person_id: None,
            date: 1711000000000 - (i * 86400000),
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        })
        .collect();

    let categories: Vec<Category> = (0..8)
        .map(|i| Category {
            id: i,
            name: format!("Category {}", i),
            key: format!("cat{}", i),
            icon: "Paid".to_string(),
            color: 0xFF000000 + i,
            category_type: "EXPENSE".to_string(),
            is_default: true,
        })
        .collect();

    c.bench_function("offline_budget_advice_100tx", |b| {
        b.iter(|| get_offline_budget_advice(&transactions, &categories))
    });

    c.bench_function("financial_health_score_100tx", |b| {
        b.iter(|| calculate_financial_health_score(&transactions, &[], &[], &[], &categories))
    });
}

fn bench_search(c: &mut Criterion) {
    let transactions: Vec<Transaction> = (0..1000)
        .map(|i| Transaction {
            id: i,
            tx_type: if i % 3 == 0 {
                TransactionType::Income
            } else {
                TransactionType::Expense
            },
            category_id: (i % 8),
            amount: (i + 1) * 10000,
            description: format!(
                "\u{062E}\u{0631}\u{06CC}\u{062F} \u{0628}\u{0631}\u{0642} {}",
                i
            ),
            person_name: if i % 5 == 0 {
                Some(format!("Person {}", i))
            } else {
                None
            },
            person_id: None,
            date: 1711000000000 - (i * 86400000),
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        })
        .collect();

    let text_query = SearchQuery {
        text: "\u{062E}\u{0631}\u{06CC}\u{062F}".to_string(), // "خرید"
        min_amount: 0,
        max_amount: 0,
        start_date: 0,
        end_date: 0,
        category_id: 0,
        tx_type: TransactionType::Expense,
        use_type_filter: false,
    };

    let filtered_query = SearchQuery {
        text: String::new(),
        min_amount: 500_000,
        max_amount: 5_000_000,
        start_date: 0,
        end_date: 0,
        category_id: 2,
        tx_type: TransactionType::Expense,
        use_type_filter: true,
    };

    c.bench_function("search_text_1000tx", |b| {
        b.iter(|| search_transactions(&transactions, &text_query))
    });

    c.bench_function("search_filtered_1000tx", |b| {
        b.iter(|| search_transactions(&transactions, &filtered_query))
    });
}

fn bench_crypto(c: &mut Criterion) {
    // Small backup (typical)
    let small_json = r#"{"version":1,"timestamp":1710000000000,"app_version":"1.0","transactions":[],"loans":[],"installments":[],"categories":[]}"#;

    // Large backup (1000 transactions)
    let large_json: String = format!(
        r#"{{"version":1,"timestamp":1710000000000,"app_version":"1.0","transactions":[{}],"loans":[],"installments":[],"categories":[]}}"#,
        (0..1000)
            .map(|i| format!(r#"{{"id":{},"type":"EXPENSE","categoryId":1,"amount":{},"description":"Transaction {}","date":1710000000000}}"#, i, i * 10000, i))
            .collect::<Vec<_>>()
            .join(",")
    );

    c.bench_function("checksum_small", |b| {
        b.iter(|| compute_checksum(small_json.as_bytes()))
    });

    c.bench_function("checksum_large", |b| {
        b.iter(|| compute_checksum(large_json.as_bytes()))
    });
}

fn bench_validation(c: &mut Criterion) {
    let tx = Transaction {
        id: 1,
        tx_type: TransactionType::Expense,
        category_id: 1,
        amount: 50000,
        description: "test transaction".to_string(),
        person_name: None,
        person_id: None,
        date: 1710000000000,
        due_date: None,
        installment_id: None,
        account_id: 1,
        destination_account_id: None,
    };

    c.bench_function("validate_transaction", |b| {
        b.iter(|| validate_transaction(&tx))
    });

    // Batch validation with 1000 transactions
    let transactions: Vec<Transaction> = (0..1000)
        .map(|i| Transaction {
            id: i,
            tx_type: TransactionType::Expense,
            category_id: 1,
            amount: 50000 + i,
            description: format!("transaction {}", i),
            person_name: None,
            person_id: None,
            date: 1710000000000,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        })
        .collect();

    let payload = BackupPayload {
        version: 1,
        timestamp: 1710000000000,
        app_version: "1.0".to_string(),
        transactions,
        loans: vec![],
        installments: vec![],
        bank_loans: vec![],
        payment_histories: vec![],
        categories: vec![],
        accounts: vec![],
        ..Default::default()
    };

    c.bench_function("validate_backup_payload_1000_tx", |b| {
        b.iter(|| validate_backup_payload(&payload))
    });
}

fn bench_ai_validation(c: &mut Criterion) {
    let valid_json = r#"{
        "type": "EXPENSE",
        "amount": 500000,
        "category": "Food",
        "personName": "علی",
        "description": "پرداخت قبض برق",
        "daysFromNow": 0,
        "dateOffsetDays": 0,
        "hour": 14,
        "minute": 30,
        "confidence": 0.9,
        "notes": null
    }"#;

    let minimal_json = r#"{"type": "INCOME", "amount": 20000000, "category": "Income"}"#;

    c.bench_function("parse_ai_json_full", |b| {
        b.iter(|| parse_ai_transaction_json(valid_json))
    });

    c.bench_function("parse_ai_json_minimal", |b| {
        b.iter(|| parse_ai_transaction_json(minimal_json))
    });

    let short_advice = "شما در ماه گذشته ۲۰٪ از درآمد خود را پس‌انداز کرده‌اید. این عملکرد عالی است.";
    let long_advice = "س".repeat(5000);

    c.bench_function("validate_ai_advice_short", |b| {
        b.iter(|| validate_ai_advice(short_advice))
    });

    c.bench_function("validate_ai_advice_long", |b| {
        b.iter(|| validate_ai_advice(&long_advice))
    });
}

// =====================================================================
// Helpers for building realistic datasets for the dashboard/analytics/excel
// benchmarks (mirrors the structure of the in-module #[cfg(test)] fixtures).
// =====================================================================

fn make_tx(
    id: i64,
    tx_type: TransactionType,
    amount: i64,
    date_ms: i64,
    cat_id: i64,
) -> Transaction {
    Transaction {
        id,
        tx_type,
        category_id: cat_id,
        amount,
        description: format!("Transaction {}", id),
        person_name: None,
        person_id: None,
        date: date_ms,
        due_date: None,
        installment_id: None,
        account_id: 1,
        destination_account_id: None,
    }
}

fn make_category(id: i64) -> Category {
    Category {
        id,
        name: format!("Category {}", id),
        key: format!("cat{}", id),
        icon: "Paid".to_string(),
        color: 0xFF000000 + id,
        category_type: "EXPENSE".to_string(),
        is_default: true,
    }
}

fn make_loan(id: i64, loan_type: &str, original: i64, remaining: i64, settled: bool) -> Loan {
    Loan {
        id,
        person_name: format!("Person {}", id),
        person_id: None,
        loan_type: loan_type.to_string(),
        original_amount: original,
        remaining_amount: remaining,
        description: String::new(),
        date: 1_710_000_000_000,
        is_settled: settled,
    }
}

fn make_installment(id: i64, amount: i64, due_ms: i64, paid: bool) -> Installment {
    Installment {
        id,
        title: format!("Installment {}", id),
        amount,
        due_date: due_ms,
        is_paid: paid,
        reminder_enabled: false,
        notes: String::new(),
        bank_loan_id: None,
    }
}

fn make_bank_loan(id: i64, total_repayable: i64, settled: bool) -> BankLoan {
    BankLoan {
        id,
        bank_name: "Bank".to_string(),
        loan_name: format!("Loan {}", id),
        received_amount: 0,
        monthly_installment_amount: 0,
        number_of_installments: 12,
        total_repayable_amount: total_repayable,
        total_interest: 0,
        start_date: 0,
        description: String::new(),
        is_settled: settled,
    }
}

fn make_cell(value: &str) -> Cell {
    Cell {
        value: value.to_string(),
        bold: false,
    }
}

fn make_sheet(name: &str, row_count: usize) -> SheetData {
    let rows: Vec<Vec<Cell>> = (0..row_count)
        .map(|i| vec![make_cell(&i.to_string()), make_cell("value")])
        .collect();
    SheetData {
        name: name.to_string(),
        headers: vec!["ID".to_string(), "Value".to_string()],
        rows,
        summary_row: None,
    }
}

// =====================================================================
// Dashboard aggregation (the hot path behind the main screen)
// =====================================================================

fn bench_dashboard(c: &mut Criterion) {
    let now_ms = 1_711_000_000_000;

    let small_tx: Vec<Transaction> = (0..100)
        .map(|i| {
            make_tx(
                i,
                if i % 3 == 0 {
                    TransactionType::Income
                } else {
                    TransactionType::Expense
                },
                (i + 1) * 10_000,
                now_ms,
                i % 8,
            )
        })
        .collect();
    let small_loans = vec![
        make_loan(1, "DEBTOR", 1_000_000, 500_000, false),
        make_loan(2, "CREDITOR", 2_000_000, 1_000_000, false),
    ];
    let small_installments = vec![make_installment(1, 100_000, now_ms, false)];
    let small_bank_loans = vec![make_bank_loan(1, 1_000_000, false)];

    let large_tx: Vec<Transaction> = (0..10_000)
        .map(|i| {
            make_tx(
                i,
                if i % 3 == 0 {
                    TransactionType::Income
                } else {
                    TransactionType::Expense
                },
                (i + 1) * 10_000,
                now_ms,
                i % 8,
            )
        })
        .collect();
    let large_loans: Vec<Loan> = (0..500)
        .map(|i| {
            make_loan(
                i,
                if i % 2 == 0 { "DEBTOR" } else { "CREDITOR" },
                1_000_000,
                500_000,
                i % 5 == 0,
            )
        })
        .collect();
    let large_installments: Vec<Installment> = (0..500)
        .map(|i| make_installment(i, 100_000, now_ms, i % 3 == 0))
        .collect();
    let large_bank_loans: Vec<BankLoan> = (0..50)
        .map(|i| make_bank_loan(i, 1_000_000, i % 7 == 0))
        .collect();
    let no_accounts: Vec<Account> = vec![];

    c.bench_function("dashboard_100_tx", |b| {
        b.iter(|| {
            compute_dashboard_data(
                black_box(&small_tx),
                black_box(&small_loans),
                black_box(&small_installments),
                black_box(&small_bank_loans),
                black_box(&no_accounts),
                black_box(None),
                black_box(false),
                black_box(now_ms),
            )
        })
    });

    c.bench_function("dashboard_10k_tx", |b| {
        b.iter(|| {
            compute_dashboard_data(
                black_box(&large_tx),
                black_box(&large_loans),
                black_box(&large_installments),
                black_box(&large_bank_loans),
                black_box(&no_accounts),
                black_box(None),
                black_box(false),
                black_box(now_ms),
            )
        })
    });
}

// =====================================================================
// Analytics aggregation (monthly + category breakdown + debt summaries)
// =====================================================================

fn bench_analytics(c: &mut Criterion) {
    let now_ms = 1_711_000_000_000;
    let categories: Vec<Category> = (0..8).map(make_category).collect();
    let no_loans: Vec<Loan> = vec![];
    let no_installments: Vec<Installment> = vec![];
    let no_bank_loans: Vec<BankLoan> = vec![];
    let no_accounts: Vec<Account> = vec![];

    let small_tx: Vec<Transaction> = (0..100)
        .map(|i| {
            make_tx(
                i,
                if i % 3 == 0 {
                    TransactionType::Income
                } else {
                    TransactionType::Expense
                },
                (i + 1) * 10_000,
                now_ms,
                i % 8,
            )
        })
        .collect();

    let large_tx: Vec<Transaction> = (0..10_000)
        .map(|i| {
            make_tx(
                i,
                if i % 3 == 0 {
                    TransactionType::Income
                } else {
                    TransactionType::Expense
                },
                (i + 1) * 10_000,
                now_ms,
                i % 8,
            )
        })
        .collect();
    let large_loans: Vec<Loan> = (0..500)
        .map(|i| {
            make_loan(
                i,
                if i % 2 == 0 { "DEBTOR" } else { "CREDITOR" },
                1_000_000,
                500_000,
                i % 5 == 0,
            )
        })
        .collect();
    let large_installments: Vec<Installment> = (0..500)
        .map(|i| make_installment(i, 100_000, now_ms, i % 3 == 0))
        .collect();

    c.bench_function("analytics_100_tx", |b| {
        b.iter(|| {
            compute_analytics(
                black_box(&small_tx),
                black_box(&no_loans),
                black_box(&no_installments),
                black_box(&categories),
                black_box(&no_bank_loans),
                black_box(&no_accounts),
                black_box(None),
                black_box(false),
            )
        })
    });

    c.bench_function("analytics_10k_tx", |b| {
        b.iter(|| {
            compute_analytics(
                black_box(&large_tx),
                black_box(&large_loans),
                black_box(&large_installments),
                black_box(&categories),
                black_box(&no_bank_loans),
                black_box(&no_accounts),
                black_box(None),
                black_box(false),
            )
        })
    });

    let bank_loan_tx: Vec<Transaction> = (0..100)
        .map(|i| {
            make_tx(
                i,
                if i % 3 == 0 {
                    TransactionType::Income
                } else {
                    TransactionType::Expense
                },
                (i + 1) * 10_000,
                now_ms,
                i % 8,
            )
        })
        .collect();
    let bench_bank_loans: Vec<BankLoan> = (0..50)
        .map(|i| make_bank_loan(i, 1_000_000, i % 7 == 0))
        .collect();

    c.bench_function("analytics_100_tx_with_bank_loans", |b| {
        b.iter(|| {
            compute_analytics(
                black_box(&bank_loan_tx),
                black_box(&no_loans),
                black_box(&no_installments),
                black_box(&categories),
                black_box(&bench_bank_loans),
                black_box(&no_accounts),
                black_box(None),
                black_box(false),
            )
        })
    });
}

// =====================================================================
// Currency formatting & conversion (called on every amount render)
// =====================================================================

fn bench_currency(c: &mut Criterion) {
    c.bench_function("format_number_1e9", |b| {
        b.iter(|| format_number(black_box(1_234_567_890)))
    });

    c.bench_function("format_currency_rial", |b| {
        b.iter(|| format_currency(black_box(1_234_567_890), CurrencyUnit::Rial))
    });

    c.bench_function("format_currency_toman", |b| {
        b.iter(|| format_currency(black_box(1_234_567_890), CurrencyUnit::Toman))
    });

    c.bench_function("to_rial_toman", |b| {
        b.iter(|| to_rial(black_box(500_000), CurrencyUnit::Toman))
    });

    c.bench_function("from_rial_toman", |b| {
        b.iter(|| from_rial(black_box(5_000_000), CurrencyUnit::Toman))
    });
}

// =====================================================================
// Excel export (XLSX serialization of a sheet)
// =====================================================================

fn bench_excel(c: &mut Criterion) {
    let small = WorkbookData {
        sheets: vec![make_sheet("Transactions", 100)],
    };
    let large = WorkbookData {
        sheets: vec![make_sheet("Sheet1", 5_000), make_sheet("Sheet2", 5_000)],
    };

    c.bench_function("generate_excel_100_rows", |b| {
        b.iter(|| generate_excel(black_box(&small)).unwrap())
    });

    c.bench_function("generate_excel_10k_rows", |b| {
        b.iter(|| generate_excel(black_box(&large)).unwrap())
    });
}

criterion_group!(
    benches,
    bench_parse_amount,
    bench_money_detector,
    bench_jalali_calendar,
    bench_budget_advice,
    bench_search,
    bench_crypto,
    bench_validation,
    bench_ai_validation,
    bench_dashboard,
    bench_analytics,
    bench_currency,
    bench_excel
);
criterion_main!(benches);
