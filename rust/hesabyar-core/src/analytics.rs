use crate::calendar::{get_jalali_days_in_month, gregorian_to_jalali};
use crate::models::*;
use std::collections::{HashMap, HashSet};

/// Build per-bank-loan summary rows, computing the remaining outstanding debt.
///
/// Until per-loan installment linkage is tracked in Rust, `remaining_debt` is
/// `0` when the loan is settled and the full `total_repayable_amount` otherwise.
pub(crate) fn build_bank_loan_summaries(
    bank_loans: &[BankLoan],
    _installments: &[Installment],
) -> Vec<BankLoanSummary> {
    bank_loans
        .iter()
        .map(|b| BankLoanSummary {
            bank_name: b.bank_name.clone(),
            loan_name: b.loan_name.clone(),
            received_amount: b.received_amount,
            total_repayable_amount: b.total_repayable_amount,
            total_interest: b.total_interest,
            number_of_installments: b.number_of_installments,
            is_settled: b.is_settled,
            remaining_debt: if b.is_settled {
                0
            } else {
                b.total_repayable_amount
            },
        })
        .collect()
}

/// Compute analytics data from transactions, loans, installments, and categories.
///
/// - Monthly aggregates use the **Jalali calendar** (not Gregorian).
/// - Category breakdown includes percentage-based burn rates.
/// - Debt/credit summaries include progress toward settlement.
// The parameter list mirrors the `#[uniffi::export]` wrapper in ffi/mod.rs.
// Changing it would change the FFI surface, so the lint stays off here.
#[allow(clippy::too_many_arguments)]
pub fn compute_analytics(
    transactions: &[Transaction],
    loans: &[Loan],
    installments: &[Installment],
    categories: &[Category],
    bank_loans: &[BankLoan],
    accounts: &[Account],
    account_id: Option<i64>,
    include_archived: bool,
) -> AnalyticsData {
    let category_map: HashMap<i64, &Category> = categories.iter().map(|c| (c.id, c)).collect();

    // Filter transactions whose source or destination account is archived
    // first, so archived accounts never leak into the all-accounts totals.
    // Mirrors compute_dashboard_data's include_archived handling.
    let non_archived_txs: Vec<&Transaction> = if include_archived {
        transactions.iter().collect()
    } else {
        let archived_ids: HashSet<i64> = accounts
            .iter()
            .filter(|a| a.is_archived)
            .map(|a| a.id)
            .collect();
        transactions
            .iter()
            .filter(|tx| {
                !archived_ids.contains(&tx.account_id)
                    && !archived_ids.contains(&tx.destination_account_id.unwrap_or(-1))
            })
            .collect()
    };

    // Filter transactions by account_id if provided.
    // Include both source (account_id) and destination (destination_account_id) transactions
    // for per-account views so transfers show correctly from both sides.
    let filtered_txs: Vec<&Transaction> = if let Some(acc_id) = account_id {
        non_archived_txs
            .iter()
            .filter(|tx| tx.account_id == acc_id || tx.destination_account_id == Some(acc_id))
            .copied()
            .collect()
    } else {
        non_archived_txs.clone()
    };

    // --- Monthly income/expense aggregation ---
    let mut monthly_expense: HashMap<(i32, i32), i64> = HashMap::new();
    let mut monthly_income: HashMap<(i32, i32), i64> = HashMap::new();

    for tx in &filtered_txs {
        if let Ok(jdate) = gregorian_to_jalali(tx.date) {
            let key = (jdate.year, jdate.month);
            match tx.tx_type {
                TransactionType::Income => {
                    *monthly_income.entry(key).or_insert(0) += tx.amount;
                }
                TransactionType::Expense => {
                    *monthly_expense.entry(key).or_insert(0) += tx.amount;
                }
                TransactionType::Transfer => {
                    // For selected-account view, source-as-expense and
                    // destination-as-income aligns with the dashboard
                    // account summaries.  For all-accounts view, transfers
                    // are neutral (money moves between accounts, not real
                    // income/expense).
                    if let Some(acc_id) = account_id {
                        if tx.account_id == acc_id {
                            *monthly_expense.entry(key).or_insert(0) += tx.amount;
                        }
                        if tx.destination_account_id == Some(acc_id) {
                            *monthly_income.entry(key).or_insert(0) += tx.amount;
                        }
                    }
                }
                _ => {}
            }
        }
    }

    // Merge all Jalali months seen across income and expense
    let mut all_months: Vec<(i32, i32)> = monthly_expense
        .keys()
        .chain(monthly_income.keys())
        .copied()
        .collect();
    all_months.sort_unstable();
    all_months.dedup();

    let monthly_spending: Vec<MonthlyData> = all_months
        .iter()
        .map(|&(y, m)| {
            let expense = monthly_expense.get(&(y, m)).copied().unwrap_or(0);
            let income = monthly_income.get(&(y, m)).copied().unwrap_or(0);
            let days = get_jalali_days_in_month(y, m);
            MonthlyData {
                jalali_year: y,
                jalali_month: m,
                label: format!("{}/{} ({} days)", y, m, days),
                income,
                expense,
            }
        })
        .collect();

    let monthly_inc: Vec<MonthlyData> = all_months
        .iter()
        .map(|&(y, m)| {
            let income = monthly_income.get(&(y, m)).copied().unwrap_or(0);
            let expense = monthly_expense.get(&(y, m)).copied().unwrap_or(0);
            MonthlyData {
                jalali_year: y,
                jalali_month: m,
                label: format!("{}/{}", y, m),
                income,
                expense,
            }
        })
        .collect();

    // --- Category breakdown (expenses only) ---
    let total_expense: i64 = filtered_txs
        .iter()
        .filter(|t| t.tx_type == TransactionType::Expense)
        .map(|t| t.amount)
        .sum();

    let mut cat_totals: HashMap<i64, i64> = HashMap::new();
    for tx in filtered_txs
        .iter()
        .filter(|t| t.tx_type == TransactionType::Expense)
    {
        *cat_totals.entry(tx.category_id).or_insert(0) += tx.amount;
    }

    let mut category_breakdown: Vec<CategoryBreakdown> = cat_totals
        .iter()
        .map(|(&cat_id, &total)| {
            let cat = category_map.get(&cat_id);
            CategoryBreakdown {
                category_id: cat_id,
                category_name: cat.map(|c| c.name.clone()).unwrap_or_default(),
                color: cat.map(|c| c.color).unwrap_or(0),
                total,
                percentage: if total_expense > 0 {
                    (total as f32 / total_expense as f32) * 100.0
                } else {
                    0.0
                },
            }
        })
        .collect();
    // Sort by total descending for consistent UI ordering
    category_breakdown.sort_by_key(|c| std::cmp::Reverse(c.total));

    // --- Debt/credit summaries ---
    let debtors: Vec<DebtSummary> = loans
        .iter()
        .filter(|l| l.loan_type == "DEBTOR")
        .map(|l| DebtSummary {
            person_name: l.person_name.clone(),
            original_amount: l.original_amount,
            remaining_amount: l.remaining_amount,
            debt_type: "DEBTOR".to_string(),
            progress: if l.original_amount > 0 {
                (l.original_amount - l.remaining_amount) as f32 / l.original_amount as f32
            } else {
                0.0
            },
        })
        .collect();

    let creditors: Vec<DebtSummary> = loans
        .iter()
        .filter(|l| l.loan_type == "CREDITOR")
        .map(|l| DebtSummary {
            person_name: l.person_name.clone(),
            original_amount: l.original_amount,
            remaining_amount: l.remaining_amount,
            debt_type: "CREDITOR".to_string(),
            progress: if l.original_amount > 0 {
                (l.original_amount - l.remaining_amount) as f32 / l.original_amount as f32
            } else {
                0.0
            },
        })
        .collect();

    let total_debt: i64 = debtors.iter().map(|d| d.remaining_amount).sum();
    let total_credit: i64 = creditors.iter().map(|d| d.remaining_amount).sum();
    let total_installments = installments.len() as i32;
    let paid_installments = installments.iter().filter(|i| i.is_paid).count() as i32;

    let bank_loans = build_bank_loan_summaries(bank_loans, installments);
    let bank_loans_total_debt: i64 = bank_loans.iter().map(|b| b.remaining_debt).sum();

    // Compute per-account analytics. The all-accounts view reports every
    // active account from the non-archived transactions (so transfers touching
    // archived accounts never appear in an active account's breakdown either,
    // consistent with compute_dashboard_data). A selected-account view reports
    // only the selected account, from the account-filtered transactions —
    // mirroring the Kotlin fallback, whose account breakdown never includes
    // other accounts' segments. An account with no expense transactions
    // contributes no segment (the fallback's buildAccountBreakdown returns an
    // empty list when the filtered expense total is zero).
    let account_analytics: Vec<AccountAnalytics> = match account_id {
        Some(acc_id) => compute_account_analytics(
            &filtered_txs,
            &accounts
                .iter()
                .filter(|a| a.id == acc_id)
                .cloned()
                .collect::<Vec<_>>(),
            categories,
        )
        .into_iter()
        .filter(|a| a.category_breakdown.iter().map(|c| c.total).sum::<i64>() > 0)
        .collect(),
        None => compute_account_analytics(&non_archived_txs, accounts, categories),
    };

    AnalyticsData {
        monthly_spending,
        monthly_income: monthly_inc,
        category_breakdown,
        debtors,
        creditors,
        total_debt,
        total_credit,
        total_installments,
        paid_installments,
        bank_loans,
        bank_loans_total_debt,
        accounts: account_analytics,
    }
}

/// Compute per-account analytics (monthly data and category breakdown).
fn compute_account_analytics(
    transactions: &[&Transaction],
    accounts: &[Account],
    categories: &[Category],
) -> Vec<AccountAnalytics> {
    let category_map: HashMap<i64, &Category> = categories.iter().map(|c| (c.id, c)).collect();

    accounts
        .iter()
        .filter(|a| !a.is_archived)
        .map(|account| {
            // Collect transactions where this account is the source OR the destination
            let account_txs: Vec<&&Transaction> = transactions
                .iter()
                .filter(|tx| {
                    tx.account_id == account.id || tx.destination_account_id == Some(account.id)
                })
                .collect();

            // Monthly aggregation
            let mut monthly_expense: HashMap<(i32, i32), i64> = HashMap::new();
            let mut monthly_income: HashMap<(i32, i32), i64> = HashMap::new();

            for tx in &account_txs {
                if let Ok(jdate) = gregorian_to_jalali(tx.date) {
                    let key = (jdate.year, jdate.month);
                    match tx.tx_type {
                        TransactionType::Income => {
                            if tx.account_id == account.id {
                                *monthly_income.entry(key).or_insert(0) += tx.amount;
                            }
                        }
                        TransactionType::Expense => {
                            if tx.account_id == account.id {
                                *monthly_expense.entry(key).or_insert(0) += tx.amount;
                            }
                        }
                        TransactionType::Transfer => {
                            // Source: debit (expense-like outflow)
                            if tx.account_id == account.id {
                                *monthly_expense.entry(key).or_insert(0) += tx.amount;
                            }
                            // Destination: credit (income-like inflow)
                            if tx.destination_account_id == Some(account.id) {
                                *monthly_income.entry(key).or_insert(0) += tx.amount;
                            }
                        }
                        _ => {}
                    }
                }
            }

            let mut all_months: Vec<(i32, i32)> = monthly_expense
                .keys()
                .chain(monthly_income.keys())
                .copied()
                .collect();
            all_months.sort_unstable();
            all_months.dedup();

            let monthly_data: Vec<MonthlyData> = all_months
                .iter()
                .map(|&(y, m)| {
                    let expense = monthly_expense.get(&(y, m)).copied().unwrap_or(0);
                    let income = monthly_income.get(&(y, m)).copied().unwrap_or(0);
                    let days = get_jalali_days_in_month(y, m);
                    MonthlyData {
                        jalali_year: y,
                        jalali_month: m,
                        label: format!("{}/{} ({} days)", y, m, days),
                        income,
                        expense,
                    }
                })
                .collect();

            // Category breakdown for this account (expenses only, excluding transfers)
            let total_expense: i64 = account_txs
                .iter()
                .filter(|t| t.tx_type == TransactionType::Expense && t.account_id == account.id)
                .map(|t| t.amount)
                .sum();

            let mut cat_totals: HashMap<i64, i64> = HashMap::new();
            for tx in account_txs
                .iter()
                .filter(|t| t.tx_type == TransactionType::Expense && t.account_id == account.id)
            {
                *cat_totals.entry(tx.category_id).or_insert(0) += tx.amount;
            }

            let mut category_breakdown: Vec<CategoryBreakdown> = cat_totals
                .iter()
                .map(|(&cat_id, &total)| {
                    let cat = category_map.get(&cat_id);
                    CategoryBreakdown {
                        category_id: cat_id,
                        category_name: cat.map(|c| c.name.clone()).unwrap_or_default(),
                        color: cat.map(|c| c.color).unwrap_or(0),
                        total,
                        percentage: if total_expense > 0 {
                            (total as f32 / total_expense as f32) * 100.0
                        } else {
                            0.0
                        },
                    }
                })
                .collect();
            category_breakdown.sort_by_key(|c| std::cmp::Reverse(c.total));

            AccountAnalytics {
                account_id: account.id,
                account_name: account.name.clone(),
                monthly_data,
                category_breakdown,
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tx(
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
            description: String::new(),
            person_name: None,
            person_id: None,
            date: date_ms,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        }
    }

    fn account(id: i64, name: &str, account_type: &str) -> Account {
        Account {
            id,
            name: name.to_string(),
            account_type: account_type.to_string(),
            bank_name: None,
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
        }
    }

    fn archived_account(id: i64, name: &str) -> Account {
        Account {
            is_archived: true,
            ..account(id, name, "CASH_WALLET")
        }
    }

    /// Like [tx] but on a specific source account (tx hardcodes account 1).
    fn tx_on(
        id: i64,
        tx_type: TransactionType,
        amount: i64,
        date_ms: i64,
        cat_id: i64,
        account_id: i64,
    ) -> Transaction {
        Transaction {
            account_id,
            ..tx(id, tx_type, amount, date_ms, cat_id)
        }
    }

    fn category(id: i64, name: &str) -> Category {
        Category {
            id,
            name: name.to_string(),
            key: name.to_lowercase().replace(' ', "_"),
            icon: String::new(),
            color: 0xFF0000 + id,
            category_type: "expense".to_string(),
            is_default: false,
        }
    }

    fn loan(id: i64, loan_type: &str, person: &str, original: i64, remaining: i64) -> Loan {
        Loan {
            id,
            person_name: person.to_string(),
            person_id: None,
            loan_type: loan_type.to_string(),
            original_amount: original,
            remaining_amount: remaining,
            description: String::new(),
            date: 0,
            is_settled: false,
        }
    }

    fn installment(id: i64, amount: i64, paid: bool) -> Installment {
        Installment {
            id,
            title: format!("Installment {}", id),
            amount,
            due_date: 0,
            is_paid: paid,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        }
    }

    fn now_ms() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64
    }

    // =====================================================================
    // Empty input — should not panic
    // =====================================================================

    #[test]
    fn test_empty_all() {
        let result = compute_analytics(&[], &[], &[], &[], &[], &[], None, false);
        assert!(result.monthly_spending.is_empty());
        assert!(result.monthly_income.is_empty());
        assert!(result.category_breakdown.is_empty());
        assert!(result.debtors.is_empty());
        assert!(result.creditors.is_empty());
        assert_eq!(result.total_debt, 0);
        assert_eq!(result.total_credit, 0);
        assert_eq!(result.total_installments, 0);
        assert_eq!(result.paid_installments, 0);
        assert!(result.accounts.is_empty());
    }

    // =====================================================================
    // Monthly aggregation
    // =====================================================================

    #[test]
    fn test_monthly_expense_grouping() {
        let now = now_ms();
        let txs = vec![
            tx(1, TransactionType::Expense, 100_000, now, 1),
            tx(2, TransactionType::Expense, 200_000, now, 1),
        ];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &[], None, false);
        // Both transactions are in the current Jalali month → single MonthlyData
        assert_eq!(result.monthly_spending.len(), 1);
        assert_eq!(result.monthly_spending[0].expense, 300_000);
    }

    #[test]
    fn test_monthly_income_and_expense_separate() {
        let now = now_ms();
        let txs = vec![
            tx(1, TransactionType::Income, 1_000_000, now, 1),
            tx(2, TransactionType::Expense, 400_000, now, 1),
        ];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &[], None, false);
        assert_eq!(result.monthly_spending.len(), 1);
        assert_eq!(result.monthly_spending[0].income, 1_000_000);
        assert_eq!(result.monthly_spending[0].expense, 400_000);
    }

    #[test]
    fn test_loan_types_excluded_from_monthly() {
        let now = now_ms();
        let txs = vec![
            tx(1, TransactionType::Income, 500_000, now, 1),
            tx(2, TransactionType::LoanDebtor, 300_000, now, 1),
            tx(3, TransactionType::LoanCreditor, 200_000, now, 1),
            tx(4, TransactionType::Installment, 100_000, now, 1),
        ];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &[], None, false);
        // Only Income contributes to monthly_income
        assert_eq!(result.monthly_spending[0].income, 500_000);
        assert_eq!(result.monthly_spending[0].expense, 0);
    }

    #[test]
    fn test_monthly_label_includes_jalali_month_days() {
        let now = now_ms();
        let txs = vec![tx(1, TransactionType::Expense, 100, now, 1)];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &[], None, false);
        let label = &result.monthly_spending[0].label;
        // Label should be like "1404/4 (31 days)"
        assert!(
            label.contains("days)"),
            "Label should include days: {}",
            label
        );
    }

    // =====================================================================
    // Category breakdown
    // =====================================================================

    #[test]
    fn test_category_breakdown_expenses_only() {
        let now = now_ms();
        let cats = vec![category(1, "Food"), category(2, "Transport")];
        let txs = vec![
            tx(1, TransactionType::Expense, 300_000, now, 1),
            tx(2, TransactionType::Expense, 100_000, now, 1),
            tx(3, TransactionType::Expense, 200_000, now, 2),
            tx(4, TransactionType::Income, 500_000, now, 1), // income — excluded
        ];
        let result = compute_analytics(&txs, &[], &[], &cats, &[], &[], None, false);
        assert_eq!(result.category_breakdown.len(), 2);

        // Food: 300k + 100k = 400k, Transport: 200k
        let food = result
            .category_breakdown
            .iter()
            .find(|c| c.category_id == 1)
            .unwrap();
        let transport = result
            .category_breakdown
            .iter()
            .find(|c| c.category_id == 2)
            .unwrap();
        assert_eq!(food.total, 400_000);
        assert_eq!(transport.total, 200_000);
    }

    #[test]
    fn test_category_percentages_sum_to_100() {
        let now = now_ms();
        let cats = vec![category(1, "A"), category(2, "B"), category(3, "C")];
        let txs = vec![
            tx(1, TransactionType::Expense, 500, now, 1),
            tx(2, TransactionType::Expense, 300, now, 2),
            tx(3, TransactionType::Expense, 200, now, 3),
        ];
        let result = compute_analytics(&txs, &[], &[], &cats, &[], &[], None, false);
        let total_pct: f32 = result.category_breakdown.iter().map(|c| c.percentage).sum();
        assert!(
            (total_pct - 100.0).abs() < 0.01,
            "Percentages should sum to ~100, got {}",
            total_pct
        );
    }

    #[test]
    fn test_category_unknown_id_gets_empty_name() {
        let now = now_ms();
        let txs = vec![tx(1, TransactionType::Expense, 500, now, 999)];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &[], None, false);
        assert_eq!(result.category_breakdown.len(), 1);
        assert_eq!(result.category_breakdown[0].category_name, "");
        assert_eq!(result.category_breakdown[0].category_id, 999);
    }

    #[test]
    fn test_category_breakdown_sorted_by_total_desc() {
        let now = now_ms();
        let cats = vec![category(1, "A"), category(2, "B")];
        let txs = vec![
            tx(1, TransactionType::Expense, 100, now, 2),
            tx(2, TransactionType::Expense, 500, now, 1),
        ];
        let result = compute_analytics(&txs, &[], &[], &cats, &[], &[], None, false);
        assert_eq!(result.category_breakdown[0].total, 500);
        assert_eq!(result.category_breakdown[1].total, 100);
    }

    #[test]
    fn test_zero_total_expense_gives_zero_percentages() {
        let txs = vec![tx(1, TransactionType::Income, 1000, now_ms(), 1)];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &[], None, false);
        // No expenses → category_breakdown is empty
        assert!(result.category_breakdown.is_empty());
    }

    // =====================================================================
    // Debt/Credit summaries
    // =====================================================================

    #[test]
    fn test_debtors_and_creditors() {
        let loans = vec![
            loan(1, "DEBTOR", "Ali", 1_000_000, 400_000),
            loan(2, "CREDITOR", "Reza", 2_000_000, 1_000_000),
        ];
        let result = compute_analytics(&[], &loans, &[], &[], &[], &[], None, false);
        assert_eq!(result.debtors.len(), 1);
        assert_eq!(result.creditors.len(), 1);
        assert_eq!(result.total_debt, 400_000);
        assert_eq!(result.total_credit, 1_000_000);
    }

    #[test]
    fn test_debt_progress_calculation() {
        // Original 1M, remaining 400k → paid 600k → progress = 60%
        let loans = vec![loan(1, "DEBTOR", "Ali", 1_000_000, 400_000)];
        let result = compute_analytics(&[], &loans, &[], &[], &[], &[], None, false);
        let d = &result.debtors[0];
        assert!((d.progress - 0.6).abs() < 1e-5);
    }

    #[test]
    fn test_zero_original_amount_gives_zero_progress() {
        let loans = vec![loan(1, "DEBTOR", "Ali", 0, 0)];
        let result = compute_analytics(&[], &loans, &[], &[], &[], &[], None, false);
        assert_eq!(result.debtors[0].progress, 0.0);
    }

    // =====================================================================
    // Installment tracking
    // =====================================================================

    #[test]
    fn test_installment_counts() {
        let installments = vec![
            installment(1, 100_000, true),
            installment(2, 200_000, false),
            installment(3, 300_000, true),
        ];
        let result = compute_analytics(&[], &[], &installments, &[], &[], &[], None, false);
        assert_eq!(result.total_installments, 3);
        assert_eq!(result.paid_installments, 2);
    }

    #[test]
    fn test_empty_installments() {
        let result = compute_analytics(&[], &[], &[], &[], &[], &[], None, false);
        assert_eq!(result.total_installments, 0);
        assert_eq!(result.paid_installments, 0);
    }

    // =====================================================================
    // Cross-module correctness: currency rule
    // =====================================================================

    #[test]
    fn test_amounts_preserved_in_rial() {
        // All amounts are stored in Rial. Analytics must not convert them.
        let now = now_ms();
        let txs = vec![tx(1, TransactionType::Expense, 42, now, 1)];
        let cats = vec![category(1, "Test")];
        let result = compute_analytics(&txs, &[], &[], &cats, &[], &[], None, false);
        assert_eq!(result.category_breakdown[0].total, 42);
        assert_eq!(result.monthly_spending[0].expense, 42);
    }

    // =====================================================================
    // Large dataset stress test
    // =====================================================================

    #[test]
    fn test_large_dataset_no_panic() {
        let now = now_ms();
        let mut txs = Vec::new();
        for i in 0..10_000 {
            let tx_type = if i % 3 == 0 {
                TransactionType::Income
            } else {
                TransactionType::Expense
            };
            let cat_id = (i % 5) as i64 + 1;
            txs.push(tx(i as i64, tx_type, (i as i64) * 100, now, cat_id));
        }
        let cats: Vec<Category> = (1..=5)
            .map(|id| category(id, &format!("Cat{}", id)))
            .collect();
        let result = compute_analytics(&txs, &[], &[], &cats, &[], &[], None, false);
        assert_eq!(result.category_breakdown.len(), 5);
        // Percentages should still sum to ~100
        let total_pct: f32 = result.category_breakdown.iter().map(|c| c.percentage).sum();
        assert!((total_pct - 100.0).abs() < 0.1);
    }

    // =====================================================================
    // Archived-account exclusion (all-accounts analytics)
    // =====================================================================

    #[test]
    fn test_all_accounts_excludes_archived_source_transactions() {
        let now = now_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived"),
        ];
        let txs = vec![
            tx(1, TransactionType::Expense, 100_000, now, 1),
            tx_on(2, TransactionType::Expense, 50_000, now, 1, 2),
            tx(3, TransactionType::Income, 200_000, now, 1),
            tx_on(4, TransactionType::Income, 30_000, now, 1, 2),
        ];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, None, false);
        // Archived account transactions must not leak into all-accounts totals
        assert_eq!(result.monthly_spending[0].expense, 100_000);
        assert_eq!(result.monthly_spending[0].income, 200_000);
        // Category breakdown excludes the archived expense
        assert_eq!(result.category_breakdown.len(), 1);
        assert_eq!(result.category_breakdown[0].total, 100_000);
        // Per-account analytics only lists active accounts
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
    }

    #[test]
    fn test_transfer_to_archived_destination_excluded() {
        let now = now_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived"),
        ];
        // Transfer from the active account 1 to archived account 2
        let transfer = Transaction {
            id: 5,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 400_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        };
        let txs = vec![transfer, tx(6, TransactionType::Expense, 100_000, now, 1)];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, None, false);
        // Transfers are neutral in all-accounts totals
        assert_eq!(result.monthly_spending[0].expense, 100_000);
        // The active account must not count the transfer-out either
        let acc = &result.accounts[0];
        assert_eq!(acc.account_id, 1);
        assert_eq!(acc.monthly_data[0].expense, 100_000);
    }

    /// When a specific active account is selected, a transfer touching an
    /// archived account is still excluded — the archived-account filter runs
    /// *before* the account_id filter, consistent with compute_dashboard_data
    /// and the Kotlin fallback. This complements
    /// `test_transfer_to_archived_destination_excluded` (which uses
    /// account_id=None) by confirming the same exclusion holds for a
    /// single-account selection.
    #[test]
    fn test_selected_active_account_transfer_to_archived_destination_excluded() {
        let now = now_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived"),
        ];
        // Transfer from active account 1 to archived account 2
        let transfer = Transaction {
            id: 5,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 400_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        };
        // Plus a regular expense on account 1 that survives filtering
        let txs = vec![transfer, tx(6, TransactionType::Expense, 100_000, now, 1)];

        // account_id=Some(1), include_archived=false: the archived-destination
        // filter runs first, removing the transfer before the account_id filter.
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(1), false);

        // Account 1's view shows only the 100k expense — the 400k transfer-out
        // was dropped because its destination (account 2) is archived.
        assert_eq!(result.monthly_spending[0].income, 0);
        assert_eq!(result.monthly_spending[0].expense, 100_000);
    }

    #[test]
    fn test_include_archived_true_keeps_archived_transactions() {
        let now = now_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived"),
        ];
        let txs = vec![
            tx(1, TransactionType::Expense, 100_000, now, 1),
            tx_on(2, TransactionType::Expense, 50_000, now, 1, 2),
        ];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, None, true);
        assert_eq!(result.monthly_spending[0].expense, 150_000);
        // Per-account analytics still never lists archived accounts
        assert_eq!(result.accounts.len(), 1);
    }

    // =====================================================================
    // Transfer semantics: account-filtered analytics
    // =====================================================================

    /// All-accounts view: transfers are neutral (no income/expense impact).
    #[test]
    fn test_transfer_neutral_when_account_id_none() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK"), account(2, "B", "BANK")];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        }];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, None, false);
        assert!(result.monthly_spending.is_empty());
    }

    /// Selected account is the transfer source → counted as expense.
    #[test]
    fn test_transfer_source_is_selected_account_counted_as_expense() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK"), account(2, "B", "BANK")];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        }];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(1), false);
        assert_eq!(result.monthly_spending[0].expense, 500_000);
        assert_eq!(result.monthly_spending[0].income, 0);
    }

    /// Selected account is the transfer destination → counted as income.
    #[test]
    fn test_transfer_dest_is_selected_account_counted_as_income() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK"), account(2, "B", "BANK")];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        }];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(2), false);
        assert_eq!(result.monthly_spending[0].income, 500_000);
        assert_eq!(result.monthly_spending[0].expense, 0);
    }

    /// Selected account is not involved in the transfer → neutral.
    #[test]
    fn test_transfer_uninvolved_account_neutral() {
        let now = now_ms();
        let accounts = vec![
            account(1, "A", "BANK"),
            account(2, "B", "BANK"),
            account(3, "C", "BANK"),
        ];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        }];
        let result = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(3), false);
        assert!(result.monthly_spending.is_empty());
    }

    // =====================================================================
    // Account-breakdown filtering for selected-account views
    // =====================================================================

    /// A selected-account view must report only the selected account's
    /// breakdown, never other active accounts' segments — the Kotlin fallback
    /// filters its account breakdown by the selected accountId.
    #[test]
    fn test_account_analytics_limited_to_selected_account() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK"), account(2, "B", "BANK")];
        let txs = vec![
            tx_on(1, TransactionType::Expense, 100_000, now, 1, 1),
            tx_on(2, TransactionType::Expense, 200_000, now, 1, 2),
        ];

        // All-accounts view reports both active accounts.
        let all = compute_analytics(&txs, &[], &[], &[], &[], &accounts, None, false);
        assert_eq!(all.accounts.len(), 2);

        // Selected-account view reports only the selected account.
        let selected = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(1), false);
        assert_eq!(
            selected.accounts.len(),
            1,
            "only the selected account may appear"
        );
        assert_eq!(selected.accounts[0].account_id, 1);
        assert_eq!(selected.accounts[0].category_breakdown[0].total, 100_000);
    }

    /// A selected account with no expense transactions contributes no segment —
    /// the Kotlin fallback's buildAccountBreakdown returns an empty list when
    /// the filtered expense total is zero.
    #[test]
    fn test_selected_account_without_expenses_has_no_account_segments() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK"), account(2, "B", "BANK")];
        let txs = vec![
            tx_on(1, TransactionType::Income, 1_000_000, now, 1, 1),
            tx_on(2, TransactionType::Expense, 200_000, now, 1, 2),
        ];

        let selected = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(1), false);
        assert!(
            selected.accounts.is_empty(),
            "no expenses → no account segments"
        );
    }

    /// A selected account whose only expense transactions total zero must be
    /// filtered out — the Kotlin fallback's buildAccountBreakdown returns an
    /// empty list when the filtered expense total is zero. Previously, Rust
    /// kept the account because `category_breakdown` was non-empty (it had a
    /// single zero-total entry). The fix filters on the sum of category totals
    /// instead.
    #[test]
    fn test_selected_account_with_only_zero_expenses_filtered_out() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK")];
        // Single expense with amount = 0 — cat_totals = {1: 0}, non-empty but
        // sum is zero. Rust must filter it out to match the Kotlin fallback.
        let txs = vec![tx_on(1, TransactionType::Expense, 0, now, 1, 1)];
        let selected = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(1), false);
        assert!(
            selected.accounts.is_empty(),
            "zero-total expenses should filter out the account, got: {:?}",
            selected.accounts
        );
    }

    /// An account with expenses that sum to > 0 is kept (positive case).
    #[test]
    fn test_selected_account_with_positive_expenses_kept() {
        let now = now_ms();
        let accounts = vec![account(1, "A", "BANK")];
        let txs = vec![tx_on(1, TransactionType::Expense, 100_000, now, 1, 1)];
        let selected = compute_analytics(&txs, &[], &[], &[], &[], &accounts, Some(1), false);
        assert_eq!(
            selected.accounts.len(),
            1,
            "positive expenses must keep the account"
        );
    }
}
