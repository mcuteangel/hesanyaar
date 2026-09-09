use crate::calendar::{get_jalali_days_in_month, gregorian_to_jalali};
use crate::models::*;

/// Compute dashboard data from transactions, loans, and installments.
///
/// Monthly income/expenses are calculated for the **current Jalali month**,
/// not a rolling 30-day window. This ensures correct behavior across months
/// of varying length (29–31 days).
///
/// If `account_id` is provided, only transactions linked to that account are
/// included. If `None`, all transactions are aggregated (total net worth view).
///
/// When `include_archived` is `false`, transactions belonging to archived
/// accounts are excluded from **all** aggregates (balance, monthly income,
/// monthly expenses) — not just from per-account summaries. This ensures
/// current-state views (dashboard, BalanceCard) never leak archived data.
/// Historical/report views should pass `true` to retain all transactions.
// The parameter list mirrors the `#[uniffi::export]` wrapper in ffi/mod.rs.
// Changing it would change the FFI surface, so the lint stays off here.
#[allow(clippy::too_many_arguments)]
pub fn compute_dashboard_data(
    transactions: &[Transaction],
    loans: &[Loan],
    installments: &[Installment],
    bank_loans: &[BankLoan],
    accounts: &[Account],
    account_id: Option<i64>,
    include_archived: bool,
    now_ms: i64,
) -> DashboardData {
    // --- Filter transactions first (before calendar check) ---
    // When include_archived is false, exclude transactions whose source or
    // destination account is archived. This must happen *before* the
    // account_id filter so archived accounts never leak into totals.
    let non_archived_txs: Vec<&Transaction> = if include_archived {
        transactions.iter().collect()
    } else {
        let archived_ids: std::collections::HashSet<i64> = accounts
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

    // Filter by account_id if provided. Use iter() to keep non_archived_txs
    // available for compute_account_summaries in the normal path.
    let filtered_txs: Vec<&Transaction> = if let Some(acc_id) = account_id {
        non_archived_txs
            .iter()
            .filter(|tx| tx.account_id == acc_id || tx.destination_account_id == Some(acc_id))
            .copied()
            .collect()
    } else {
        non_archived_txs.clone()
    };

    // Sum of initial balances for selected accounts (used by both paths).
    // Gate the selected account's opening balance by the same archive
    // condition as its transactions: when include_archived is false, selecting
    // an archived account yields no initial_balance (no half-visible account).
    let initial_balance_sum: i64 = if let Some(acc_id) = account_id {
        accounts
            .iter()
            .find(|a| a.id == acc_id && (include_archived || !a.is_archived))
            .map(|a| a.initial_balance)
            .unwrap_or(0)
    } else {
        accounts
            .iter()
            .filter(|a| include_archived || !a.is_archived)
            .map(|a| a.initial_balance)
            .sum()
    };

    // Lifetime balance across the filtered transactions plus opening balances.
    // total_net_worth mirrors this value (see the DashboardData construction
    // below), so both fields apply the same include_archived policy — matching
    // the Kotlin fallback, where totalNetWorth = currentBalance. Computed
    // before the calendar match so the calendar-error path uses the identical
    // formula.
    let current_balance = compute_all_time_balance(&filtered_txs, account_id) + initial_balance_sum;

    // --- Current Jalali month boundaries ---
    let (jy, jm) = match gregorian_to_jalali(now_ms) {
        Ok(jd) => (jd.year, jd.month),
        Err(_) => {
            // Fallback: return zeros for monthly aggregates, but preserve
            // account-linkage filtering and initial_balance for current_balance.
            return DashboardData {
                current_balance,
                monthly_expenses: 0,
                monthly_income: 0,
                debtors_total: compute_debtors_total(loans),
                creditors_total: compute_creditors_total(loans),
                savings_rate: 0.0,
                debt_to_income_ratio: 0.0,
                bank_loans_total: bank_loans
                    .iter()
                    .filter(|b| !b.is_settled)
                    .map(|b| b.total_repayable_amount)
                    .sum(),
                bank_loans: crate::analytics::build_bank_loan_summaries(bank_loans, installments),
                accounts: vec![],
                total_net_worth: current_balance,
            };
        }
    };

    let _days_in_month = get_jalali_days_in_month(jy, jm);

    // Build start-of-month and end-of-month timestamps for the current Jalali month.
    // start_ms = first moment of the Jalali month
    // end_ms   = first moment of the next Jalali month (or day 1 of next month)
    let month_start_ms = jalali_to_month_start_ms(jy, jm);
    let month_end_ms = if jm < 12 {
        jalali_to_month_start_ms(jy, jm + 1)
    } else {
        jalali_to_month_start_ms(jy + 1, 1)
    };

    // Previous Jalali month boundaries for delta computation
    let (prev_jy, prev_jm) = if jm == 1 { (jy - 1, 12) } else { (jy, jm - 1) };
    let prev_month_start_ms = jalali_to_month_start_ms(prev_jy, prev_jm);
    let prev_month_end_ms = month_start_ms;

    // Compute per-account summaries using non_archived_txs. Always exclude
    // archived accounts from the card list (current-state view).
    let account_summaries = compute_account_summaries(
        &non_archived_txs,
        accounts,
        month_start_ms,
        month_end_ms,
        prev_month_start_ms,
        prev_month_end_ms,
    );

    // --- Aggregate transactions ---
    let mut monthly_income: i64 = 0;
    let mut monthly_expenses: i64 = 0;

    for tx in &filtered_txs {
        let in_month = tx.date >= month_start_ms && tx.date < month_end_ms;
        match tx.tx_type {
            TransactionType::Income => {
                if in_month {
                    monthly_income += tx.amount;
                }
            }
            TransactionType::Expense => {
                if in_month {
                    monthly_expenses += tx.amount;
                }
            }
            // Transfer is balance-neutral only for the all-accounts view (money
            // stays within the system). When a single account is selected, a
            // transfer out is an expense for the source and a transfer in is
            // income for the destination — matching the per-account summaries.
            TransactionType::Transfer => {
                if let Some(acc_id) = account_id {
                    if in_month {
                        if tx.account_id == acc_id {
                            monthly_expenses += tx.amount;
                        }
                        if tx.destination_account_id == Some(acc_id) {
                            monthly_income += tx.amount;
                        }
                    }
                }
            }
            _ => {}
        }
    }

    // --- Loan aggregates ---
    let debtors_total = compute_debtors_total(loans);
    let creditors_total = compute_creditors_total(loans);

    // --- Rates ---
    let savings_rate = if monthly_income > 0 {
        ((monthly_income - monthly_expenses) as f64 / monthly_income as f64).clamp(0.0, 1.0)
    } else {
        0.0
    };

    // Filter installments to only those due in the current month (unpaid)
    let mut current_month_installments: Vec<Installment> = Vec::new();
    for inst in installments {
        if !inst.is_paid && inst.due_date >= month_start_ms && inst.due_date < month_end_ms {
            current_month_installments.push(inst.clone());
        }
    }

    let debt_to_income = crate::advisory::calculate_debt_to_income_ratio(
        loans,
        &current_month_installments,
        bank_loans,
        monthly_income,
    );

    // --- Installment summary ---
    let _upcoming_installments: Vec<&Installment> = installments
        .iter()
        .filter(|i| !i.is_paid && i.due_date >= month_start_ms && i.due_date < month_end_ms)
        .collect();

    let bank_loans_total: i64 = bank_loans
        .iter()
        .filter(|b| !b.is_settled)
        .map(|b| b.total_repayable_amount)
        .sum();

    let bank_loans = crate::analytics::build_bank_loan_summaries(bank_loans, installments);

    DashboardData {
        current_balance,
        monthly_expenses,
        monthly_income,
        debtors_total,
        creditors_total,
        savings_rate,
        debt_to_income_ratio: debt_to_income,
        bank_loans_total,
        bank_loans,
        accounts: account_summaries,
        // total_net_worth mirrors current_balance so both apply the same
        // include_archived policy (matches the Kotlin fallback). The account
        // card list above always excludes archived accounts, but the net-worth
        // figure follows the same aggregate rule as the balance header.
        total_net_worth: current_balance,
    }
}

/// Noise threshold for previous-month net: when `abs(previousNet)` is below
/// this value (1 000 Rial ≈ smallest meaningful unit in the app), the delta is
/// set to `0.0` to avoid misleading percentages near zero.
const DELTA_PREV_NET_THRESHOLD: i64 = 1_000;

/// Compute per-account balance, monthly income/expenses, and month-over-month
/// delta summaries.
///
/// Always excludes archived accounts — the card list is a current-state view.
fn compute_account_summaries(
    transactions: &[&Transaction],
    accounts: &[Account],
    month_start_ms: i64,
    month_end_ms: i64,
    prev_month_start_ms: i64,
    prev_month_end_ms: i64,
) -> Vec<AccountDashboardSummary> {
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

            let mut balance = account.initial_balance;
            let mut monthly_income = 0i64;
            let mut monthly_expenses = 0i64;
            let mut prev_income = 0i64;
            let mut prev_expenses = 0i64;

            for tx in account_txs {
                let in_current = tx.date >= month_start_ms && tx.date < month_end_ms;
                let in_prev = tx.date >= prev_month_start_ms && tx.date < prev_month_end_ms;

                match tx.tx_type {
                    TransactionType::Income => {
                        // Only credit when this account is the source (regular income)
                        if tx.account_id == account.id {
                            balance += tx.amount;
                            if in_current {
                                monthly_income += tx.amount;
                            }
                            if in_prev {
                                prev_income += tx.amount;
                            }
                        }
                    }
                    TransactionType::Expense => {
                        // Only debit when this account is the source (regular expense)
                        if tx.account_id == account.id {
                            balance -= tx.amount;
                            if in_current {
                                monthly_expenses += tx.amount;
                            }
                            if in_prev {
                                prev_expenses += tx.amount;
                            }
                        }
                    }
                    TransactionType::Transfer => {
                        // Source account: debit (money leaves)
                        if tx.account_id == account.id {
                            balance -= tx.amount;
                            if in_current {
                                monthly_expenses += tx.amount;
                            }
                            if in_prev {
                                prev_expenses += tx.amount;
                            }
                        }
                        // Destination account: credit (money arrives)
                        if tx.destination_account_id == Some(account.id) {
                            balance += tx.amount;
                            if in_current {
                                monthly_income += tx.amount;
                            }
                            if in_prev {
                                prev_income += tx.amount;
                            }
                        }
                    }
                    _ => {}
                }
            }

            // month-over-month delta: (currentNet - prevNet) / max(abs(prevNet), 1)
            // When prevNet is below the noise threshold, show 0.0 to avoid
            // misleading percentages near zero (e.g. +8000000%).
            let current_net = monthly_income - monthly_expenses;
            let prev_net = prev_income - prev_expenses;
            let monthly_delta = if prev_net.abs() < DELTA_PREV_NET_THRESHOLD {
                0.0
            } else {
                (current_net - prev_net) as f64 / prev_net.abs() as f64
            };

            AccountDashboardSummary {
                account_id: account.id,
                account_name: account.name.clone(),
                account_type: account.account_type.clone(),
                balance,
                monthly_income,
                monthly_expenses,
                monthly_delta,
            }
        })
        .collect()
}

/// Compute lifetime balance across the given transactions.
///
/// Transfers are balance-neutral only for the all-accounts view (`account_id`
/// is `None`): money stays within the system, so net worth is unchanged. When
/// a single account is selected, a transfer must debit the source account and
/// credit the destination account — otherwise the wallet balance silently
/// ignores money that left or arrived for that account.
fn compute_all_time_balance(transactions: &[&Transaction], account_id: Option<i64>) -> i64 {
    transactions
        .iter()
        .map(|t| match t.tx_type {
            TransactionType::Income => t.amount,
            TransactionType::Expense => -t.amount,
            TransactionType::Transfer => match account_id {
                Some(acc_id) => {
                    let mut delta = 0i64;
                    if t.account_id == acc_id {
                        delta -= t.amount;
                    }
                    if t.destination_account_id == Some(acc_id) {
                        delta += t.amount;
                    }
                    delta
                }
                None => 0,
            },
            _ => 0,
        })
        .sum()
}

fn compute_debtors_total(loans: &[Loan]) -> i64 {
    loans
        .iter()
        .filter(|l| l.loan_type == "DEBTOR" && !l.is_settled)
        .map(|l| l.remaining_amount)
        .sum()
}

fn compute_creditors_total(loans: &[Loan]) -> i64 {
    loans
        .iter()
        .filter(|l| l.loan_type == "CREDITOR" && !l.is_settled)
        .map(|l| l.remaining_amount)
        .sum()
}

/// Convert a Jalali year/month to Gregorian epoch-ms (start of month).
/// Falls back to a 30-day approximation if the reverse conversion fails.
fn jalali_to_month_start_ms(jy: i32, jm: i32) -> i64 {
    crate::calendar::jalali_to_gregorian(jy, jm, 1).unwrap_or(0)
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

    fn archived_account(id: i64, name: &str, account_type: &str, initial_balance: i64) -> Account {
        Account {
            id,
            name: name.to_string(),
            account_type: account_type.to_string(),
            bank_name: None,
            card_number: None,
            account_number: None,
            iban: None,
            initial_balance,
            color: 0xFF757575,
            icon: None,
            is_archived: true,
            display_order: 0,
            created_at: 0,
            updated_at: 0,
        }
    }

    fn loan(id: i64, loan_type: &str, original: i64, remaining: i64, settled: bool) -> Loan {
        Loan {
            id,
            person_name: "Test".to_string(),
            person_id: None,
            loan_type: loan_type.to_string(),
            original_amount: original,
            remaining_amount: remaining,
            description: String::new(),
            date: 0,
            is_settled: settled,
        }
    }

    fn installment(id: i64, amount: i64, due_ms: i64, paid: bool) -> Installment {
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

    // =====================================================================
    // Empty input — should not panic, returns zeros
    // =====================================================================

    #[test]
    fn test_empty_transactions() {
        let result = compute_dashboard_data(&[], &[], &[], &[], &[], None, true, 0);
        assert_eq!(result.current_balance, 0);
        assert_eq!(result.monthly_income, 0);
        assert_eq!(result.monthly_expenses, 0);
        assert_eq!(result.debtors_total, 0);
        assert_eq!(result.creditors_total, 0);
        assert_eq!(result.savings_rate, 0.0);
        assert!(result.accounts.is_empty());
        assert_eq!(result.total_net_worth, 0);
    }

    // =====================================================================
    // Balance computation
    // =====================================================================

    #[test]
    fn test_balance_income_minus_expense() {
        let now_ms = now_jalali_month_ms();
        let txs = vec![
            tx(1, TransactionType::Income, 1_000_000, now_ms, 1),
            tx(2, TransactionType::Expense, 300_000, now_ms, 2),
            tx(3, TransactionType::Income, 500_000, now_ms, 1),
        ];
        let result = compute_dashboard_data(&txs, &[], &[], &[], &[], None, true, now_ms);
        // Balance = +1,000,000 - 300,000 + 500,000 = 1,200,000
        assert_eq!(result.current_balance, 1_200_000);
    }

    #[test]
    fn test_only_loan_types_ignored_in_balance() {
        let now_ms = now_jalali_month_ms();
        let txs = vec![
            tx(1, TransactionType::Income, 1_000_000, now_ms, 1),
            tx(2, TransactionType::LoanDebtor, 500_000, now_ms, 1),
            tx(3, TransactionType::LoanCreditor, 200_000, now_ms, 1),
            tx(4, TransactionType::Installment, 100_000, now_ms, 1),
        ];
        let result = compute_dashboard_data(&txs, &[], &[], &[], &[], None, true, now_ms);
        // Only Income contributes: +1,000,000
        assert_eq!(result.current_balance, 1_000_000);
    }

    // =====================================================================
    // Monthly income/expenses (current Jalali month only)
    // =====================================================================

    #[test]
    fn test_monthly_income_only_current_month() {
        let now_ms = now_jalali_month_ms();
        let old_ms = now_ms - 60 * 24 * 60 * 60 * 1000; // 2 months ago
        let txs = vec![
            tx(1, TransactionType::Income, 1_000_000, now_ms, 1),
            tx(2, TransactionType::Income, 2_000_000, old_ms, 1),
        ];
        let result = compute_dashboard_data(&txs, &[], &[], &[], &[], None, true, now_ms);
        // Only current month income counted
        assert_eq!(result.monthly_income, 1_000_000);
    }

    #[test]
    fn test_monthly_expenses_only_current_month() {
        let now_ms = now_jalali_month_ms();
        let old_ms = now_ms - 60 * 24 * 60 * 60 * 1000;
        let txs = vec![
            tx(1, TransactionType::Expense, 500_000, now_ms, 1),
            tx(2, TransactionType::Expense, 300_000, old_ms, 1),
        ];
        let result = compute_dashboard_data(&txs, &[], &[], &[], &[], None, true, now_ms);
        assert_eq!(result.monthly_expenses, 500_000);
    }

    #[test]
    fn test_savings_rate_computed_correctly() {
        let now_ms = now_jalali_month_ms();
        let txs = vec![
            tx(1, TransactionType::Income, 1_000_000, now_ms, 1),
            tx(2, TransactionType::Expense, 400_000, now_ms, 1),
        ];
        let result = compute_dashboard_data(&txs, &[], &[], &[], &[], None, true, now_ms);
        // savings_rate = (1,000,000 - 400,000) / 1,000,000 = 0.6
        assert!((result.savings_rate - 0.6).abs() < 1e-10);
    }

    #[test]
    fn test_savings_rate_zero_income() {
        let result = compute_dashboard_data(&[], &[], &[], &[], &[], None, true, 0);
        assert_eq!(result.savings_rate, 0.0);
    }

    // =====================================================================
    // Loan aggregates
    // =====================================================================

    #[test]
    fn test_debtors_and_creditors() {
        let loans = vec![
            loan(1, "DEBTOR", 1_000_000, 500_000, false),
            loan(2, "DEBTOR", 2_000_000, 2_000_000, false),
            loan(3, "CREDITOR", 3_000_000, 1_000_000, false),
            loan(4, "DEBTOR", 500_000, 100_000, true), // settled — excluded
        ];
        let result = compute_dashboard_data(&[], &loans, &[], &[], &[], None, true, 0);
        assert_eq!(result.debtors_total, 2_500_000); // 500k + 2M
        assert_eq!(result.creditors_total, 1_000_000);
    }

    #[test]
    fn test_settled_loans_excluded() {
        let loans = vec![
            loan(1, "DEBTOR", 1_000_000, 500_000, true),
            loan(2, "CREDITOR", 2_000_000, 1_000_000, true),
        ];
        let result = compute_dashboard_data(&[], &loans, &[], &[], &[], None, true, 0);
        assert_eq!(result.debtors_total, 0);
        assert_eq!(result.creditors_total, 0);
    }

    // =====================================================================
    // Debt-to-income ratio
    // =====================================================================

    #[test]
    fn test_debt_to_income_ratio() {
        let now_ms = now_jalali_month_ms();
        let txs = vec![tx(1, TransactionType::Income, 1_000_000, now_ms, 1)];
        let loans = vec![loan(1, "CREDITOR", 1_200_000, 600_000, false)];
        let installments = vec![installment(1, 100_000, now_ms, false)];
        let result =
            compute_dashboard_data(&txs, &loans, &installments, &[], &[], None, true, now_ms);
        // monthly_debt_payments = 100k (installment) + 600k/12 ≈ 50k = 150k
        // ratio = 150k / 1_000_000 = 0.15
        assert!(result.debt_to_income_ratio > 0.0);
        assert!(result.debt_to_income_ratio < 1.0);
    }

    // =====================================================================
    // Bank loans
    // =====================================================================

    fn bank_loan(id: i64, total_repayable: i64, settled: bool) -> BankLoan {
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

    #[test]
    fn test_bank_loans_total_excludes_settled() {
        let bank_loans = vec![
            bank_loan(1, 1_000_000, false),
            bank_loan(2, 2_000_000, false),
            bank_loan(3, 500_000, true), // settled — excluded
        ];
        let result = compute_dashboard_data(&[], &[], &[], &bank_loans, &[], None, true, 0);
        assert_eq!(result.bank_loans_total, 3_000_000);
        assert_eq!(result.bank_loans.len(), 3);
    }

    // =====================================================================
    // Cross-module: currency rule respected
    // =====================================================================

    #[test]
    fn test_amounts_are_in_rial() {
        // All amounts in the system are stored in Rial.
        // The dashboard must not convert or interpret them.
        let now_ms = now_jalali_month_ms();
        let txs = vec![tx(1, TransactionType::Income, 100, now_ms, 1)];
        let result = compute_dashboard_data(&txs, &[], &[], &[], &[], None, true, now_ms);
        assert_eq!(result.monthly_income, 100);
        assert_eq!(result.current_balance, 100);
    }

    // =====================================================================
    // Account filtering tests
    // =====================================================================

    #[test]
    fn test_account_filtering_by_account_id() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "حساب اصلی", "BANK"),
            account(2, "کیف پول", "CASH_WALLET"),
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2,
                destination_account_id: None,
            },
        ];

        // Filter by account 1
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(1), true, now_ms);
        assert_eq!(result.current_balance, 1_000_000);
        assert_eq!(result.monthly_income, 1_000_000);

        // Filter by account 2
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(2), true, now_ms);
        assert_eq!(result.current_balance, 500_000);
        assert_eq!(result.monthly_income, 500_000);

        // No filter (all accounts)
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(result.current_balance, 1_500_000);
        assert_eq!(result.monthly_income, 1_500_000);
    }

    #[test]
    fn test_account_summaries_computed() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "حساب اصلی", "BANK"),
            account(2, "کیف پول", "CASH_WALLET"),
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Expense,
                category_id: 2,
                amount: 200_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 3,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2,
                destination_account_id: None,
            },
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(result.accounts.len(), 2);

        // Account 1: +1,000,000 - 200,000 = 800,000
        let acc1 = result.accounts.iter().find(|a| a.account_id == 1).unwrap();
        assert_eq!(acc1.balance, 800_000);
        assert_eq!(acc1.monthly_income, 1_000_000);
        assert_eq!(acc1.monthly_expenses, 200_000);

        // Account 2: +500,000
        let acc2 = result.accounts.iter().find(|a| a.account_id == 2).unwrap();
        assert_eq!(acc2.balance, 500_000);
        assert_eq!(acc2.monthly_income, 500_000);
        assert_eq!(acc2.monthly_expenses, 0);

        // Total net worth = 800,000 + 500,000 = 1,300,000
        assert_eq!(result.total_net_worth, 1_300_000);
    }

    #[test]
    fn test_archived_accounts_excluded_from_summaries() {
        let accounts = vec![
            account(1, "حساب اصلی", "BANK"),
            Account {
                id: 2,
                name: "آرشیو شده".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];

        let result = compute_dashboard_data(&[], &[], &[], &[], &accounts, None, false, 0);
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
    }

    /// Bug regression: archived-account transactions must NOT leak into
    /// active account balance via compute_account_summaries.
    #[test]
    fn test_archived_txs_do_not_leak_into_active_account_balance() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            Account {
                id: 2,
                name: "Archived".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Expense,
                category_id: 2,
                amount: 300_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 3,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: Some(2), // to archived
            },
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, false, now_ms);

        // current_balance: +1M - 300K = 700K (transfer excluded)
        assert_eq!(result.current_balance, 700_000);
        // Active account balance: +1M - 300K = 700K (transfer to archived excluded)
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
        assert_eq!(result.accounts[0].balance, 700_000);
        // total_net_worth must equal sum of account balances
        assert_eq!(
            result.total_net_worth,
            result.accounts.iter().map(|a| a.balance).sum::<i64>()
        );
    }

    // =====================================================================
    // include_archived parameter tests
    // =====================================================================

    #[test]
    fn test_include_archived_false_excludes_archived_txs_from_totals() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            Account {
                id: 2,
                name: "Archived".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2, // archived account
                destination_account_id: None,
            },
        ];

        // include_archived=false: archived account's transaction excluded from totals
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, false, now_ms);
        assert_eq!(result.current_balance, 1_000_000);
        assert_eq!(result.monthly_income, 1_000_000);
        // Only 1 account summary (archived excluded)
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
    }

    #[test]
    fn test_include_archived_true_includes_archived_txs_in_totals() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            Account {
                id: 2,
                name: "Archived".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2, // archived account
                destination_account_id: None,
            },
        ];

        // include_archived=true: archived account's transaction included in totals
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(result.current_balance, 1_500_000);
        assert_eq!(result.monthly_income, 1_500_000);
        // Account summaries always exclude archived (current-state view)
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
    }

    #[test]
    fn test_include_archived_false_with_account_id_filter() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            Account {
                id: 2,
                name: "Archived".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2, // archived account
                destination_account_id: None,
            },
        ];

        // Filter by active account 1 with include_archived=false
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(1), false, now_ms);
        assert_eq!(result.current_balance, 1_000_000);
        assert_eq!(result.monthly_income, 1_000_000);

        // Filter by archived account 2 with include_archived=false: excluded
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(2), false, now_ms);
        assert_eq!(result.current_balance, 0);
        assert_eq!(result.monthly_income, 0);
    }

    // =====================================================================
    // Bug regression: selecting an archived account with a non-zero opening
    // balance must not leak that initial_balance when include_archived=false.
    // Its transactions are filtered, so its opening balance must be too —
    // otherwise the dashboard shows a half-visible account. Defense-in-depth:
    // the UI avoids selecting archived accounts, but the computation layer
    // must stay consistent regardless.
    // =====================================================================

    #[test]
    fn test_selected_archived_account_include_archived_false_excludes_initial_balance() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived", "CASH_WALLET", 500_000),
        ];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Income,
            category_id: 1,
            amount: 1_000_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now_ms,
            due_date: None,
            installment_id: None,
            account_id: 2, // archived account
            destination_account_id: None,
        }];

        // include_archived=false + archived selected: neither the archived
        // transaction (1M) nor its opening balance (500k) may appear.
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(2), false, now_ms);
        assert_eq!(result.current_balance, 0);
        assert_eq!(result.monthly_income, 0);
    }

    #[test]
    fn test_selected_archived_account_include_archived_true_includes_initial_balance() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived", "CASH_WALLET", 500_000),
        ];

        // No transactions: current_balance comes purely from the opening balance.
        // include_archived=true + archived selected: opening balance included.
        let result = compute_dashboard_data(&[], &[], &[], &[], &accounts, Some(2), true, now_ms);
        assert_eq!(result.current_balance, 500_000);
    }

    #[test]
    fn test_selected_active_account_include_archived_false_includes_initial_balance() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            Account {
                initial_balance: 200_000,
                ..account(1, "Active", "BANK")
            },
            archived_account(2, "Archived", "CASH_WALLET", 500_000),
        ];

        // No transactions: current_balance comes purely from the opening balance.
        // include_archived=false + active selected: opening balance still included.
        let result = compute_dashboard_data(&[], &[], &[], &[], &accounts, Some(1), false, now_ms);
        assert_eq!(result.current_balance, 200_000);
    }

    #[test]
    fn test_include_archived_false_transfer_destination_archived() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            Account {
                id: 2,
                name: "Archived".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];
        // Transfer FROM active account TO archived account
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now_ms,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2), // archived
        }];

        // include_archived=false: transfer to archived account excluded
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, false, now_ms);
        assert_eq!(result.current_balance, 0); // transfer is balance-neutral anyway

        // include_archived=true: transfer included in totals
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        // But account summaries always exclude archived (current-state view)
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
    }

    // =====================================================================
    // Transfers are balance-neutral only for the all-accounts view; when a
    // single account is selected they debit the source and credit the
    // destination (both lifetime balance and monthly aggregates).
    // =====================================================================

    #[test]
    fn test_transfer_debits_source_and_credits_destination_when_account_selected() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "حساب اصلی", "BANK"),
            account(2, "کیف پول", "CASH_WALLET"),
        ];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now_ms,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        }];

        // Selecting the source account: the transfer must debit its balance
        // (money left the account) instead of being discarded as neutral.
        let source = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(1), true, now_ms);
        assert_eq!(source.current_balance, -500_000);
        assert_eq!(source.monthly_expenses, 500_000);
        assert_eq!(source.monthly_income, 0);

        // Selecting the destination account: the transfer must credit it.
        let dest = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(2), true, now_ms);
        assert_eq!(dest.current_balance, 500_000);
        assert_eq!(dest.monthly_income, 500_000);
        assert_eq!(dest.monthly_expenses, 0);

        // All-accounts view: transfers remain balance-neutral.
        let all = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(all.current_balance, 0);
        assert_eq!(all.monthly_income, 0);
        assert_eq!(all.monthly_expenses, 0);
    }

    // =====================================================================
    // Transfer edge cases: out-of-month transfers and archived counterparties
    // =====================================================================

    /// A transfer dated outside the current Jalali month must still affect the
    /// all-time current_balance of a selected account, but must NOT appear in
    /// monthly income/expense aggregates (which are scoped to the current month).
    #[test]
    fn test_transfer_outside_current_month_reflected_in_balance_not_monthly() {
        let now_ms = now_jalali_month_ms();
        // Compute a timestamp at the start of the previous Jalali month
        let jd = gregorian_to_jalali(now_ms).unwrap();
        let (prev_jy, prev_jm) = if jd.month == 1 {
            (jd.year - 1, 12)
        } else {
            (jd.year, jd.month - 1)
        };
        let prev_month_ts = jalali_to_month_start_ms(prev_jy, prev_jm);

        let accounts = vec![
            account(1, "Active", "BANK"),
            account(2, "کیف پول", "CASH_WALLET"),
        ];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 500_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: prev_month_ts, // outside current month
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2),
        }];

        // Selecting source account: the transfer debits balance (−500k)
        // and is reflected in current_balance, but monthly aggregates
        // exclude it because it is dated outside the current Jalali month.
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(1), true, now_ms);
        assert_eq!(result.current_balance, -500_000);
        assert_eq!(result.monthly_expenses, 0);
        assert_eq!(result.monthly_income, 0);
    }

    /// When a selected active account participates in a transfer with an archived
    /// counterparty and include_archived is false, the transfer must be excluded
    /// from both the all-time balance and monthly aggregates — archived-account
    /// transactions are filtered before the account_id selection.
    #[test]
    fn test_transfer_to_archived_counterparty_excluded_for_selected_active_account() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived", "CASH_WALLET", 500_000),
        ];
        let txs = vec![Transaction {
            id: 1,
            tx_type: TransactionType::Transfer,
            category_id: 1,
            amount: 300_000,
            description: String::new(),
            person_name: None,
            person_id: None,
            date: now_ms,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: Some(2), // to archived
        }];

        // include_archived=false + selected active account: transfer to archived
        // counterpart is excluded entirely.
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(1), false, now_ms);
        assert_eq!(result.current_balance, 0);
        assert_eq!(result.monthly_expenses, 0);
        assert_eq!(result.monthly_income, 0);
        assert_eq!(result.accounts.len(), 1);
        assert_eq!(result.accounts[0].account_id, 1);
        assert_eq!(result.accounts[0].balance, 0);
    }

    // =====================================================================
    // Calendar-error fallback: balance computation uses filtered_txs +
    // initial_balance_sum, not the raw transaction list.
    // =====================================================================

    /// Regression test: current_balance must be computed from the
    /// filtered transaction set (account_id + include_archived) plus
    /// the selected account's initial_balance — the same computation
    /// used by the calendar-error fallback path in compute_dashboard_data.
    ///
    /// gregorian_to_jalali(now_ms) cannot deterministically fail for
    /// any i64 input (timestamp_to_gregorian always produces valid
    /// month/day), so this test exercises the identical balance
    /// formula through the normal path, proving the filtering logic
    /// that the error path also relies on.
    #[test]
    fn test_current_balance_uses_filtered_transactions_not_raw() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            Account {
                id: 2,
                name: "Archived".to_string(),
                account_type: "BANK".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 0,
                color: 0xFF757575,
                icon: None,
                is_archived: true,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
            Account {
                id: 3,
                name: "Other".to_string(),
                account_type: "CASH_WALLET".to_string(),
                bank_name: None,
                card_number: None,
                account_number: None,
                iban: None,
                initial_balance: 200_000,
                color: 0xFF4CAF50,
                icon: None,
                is_archived: false,
                display_order: 0,
                created_at: 0,
                updated_at: 0,
            },
        ];
        let txs = vec![
            // Belongs to account 1 (selected) — included in filtered set
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            // Belongs to account 2 (archived) — excluded by include_archived=false
            Transaction {
                id: 2,
                tx_type: TransactionType::Expense,
                category_id: 2,
                amount: 300_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2,
                destination_account_id: None,
            },
            // Belongs to account 3 (different account) — excluded by account_id filter
            Transaction {
                id: 3,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 3,
                destination_account_id: None,
            },
        ];

        // Filter by account 1 with include_archived=false
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, Some(1), false, now_ms);

        // filtered_txs = only tx 1 (account 1, non-archived)
        // compute_all_time_balance(filtered_txs) = +1_000_000
        // initial_balance_sum = accounts[0].initial_balance = 0
        // expected current_balance = 1_000_000
        assert_eq!(result.current_balance, 1_000_000);

        // Prove the wrong alternatives are NOT the result:
        // Raw list (all 3 txs): +1M - 300K + 500K = 1_200_000
        // With archived tx included: +1M - 300K = 700_000
        // With other-account tx included: +1M + 500K = 1_500_000
        assert_ne!(result.current_balance, 1_200_000);
        assert_ne!(result.current_balance, 700_000);
        assert_ne!(result.current_balance, 1_500_000);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /// Get the current time in ms — used for "now" in tests.
    fn now_jalali_month_ms() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64
    }

    /// Get a timestamp in the middle of a Jalali month (15th day) given (jy, jm).
    fn jalali_month_mid_ms(jy: i32, jm: i32) -> i64 {
        let month_start = jalali_to_month_start_ms(jy, jm);
        let next_month_start = if jm < 12 {
            jalali_to_month_start_ms(jy, jm + 1)
        } else {
            jalali_to_month_start_ms(jy + 1, 1)
        };
        // Midpoint of the month (15th day ≈ halfway)
        month_start + (next_month_start - month_start) / 2
    }

    // =====================================================================
    // monthly_delta tests — cross-path consistency
    // =====================================================================

    /// Basic delta: currentNet=300k, prevNet=200k → delta = +0.5 (50%)
    #[test]
    fn test_monthly_delta_basic_computation() {
        let now_ms = now_jalali_month_ms();
        let prev_ms = {
            let jd = gregorian_to_jalali(now_ms).unwrap();
            let (pjy, pjm) = if jd.month == 1 {
                (jd.year - 1, 12)
            } else {
                (jd.year, jd.month - 1)
            };
            jalali_month_mid_ms(pjy, pjm)
        };

        let accounts = vec![account(1, "حساب اصلی", "BANK")];
        let txs = vec![
            // Current month: income=500k, expense=200k → currentNet=300k
            tx(1, TransactionType::Income, 500_000, now_ms, 1),
            tx(2, TransactionType::Expense, 200_000, now_ms, 1),
            // Previous month: income=400k, expense=200k → prevNet=200k
            tx(3, TransactionType::Income, 400_000, prev_ms, 1),
            tx(4, TransactionType::Expense, 200_000, prev_ms, 1),
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(result.accounts.len(), 1);
        let acc = &result.accounts[0];
        assert_eq!(acc.monthly_income, 500_000);
        assert_eq!(acc.monthly_expenses, 200_000);
        // delta = (300000 - 200000) / 200000 = 0.5
        assert!((acc.monthly_delta - 0.5).abs() < 1e-10);
    }

    /// Previous month had no activity → prevNet=0 → delta=0.0 (no misleading %)
    #[test]
    fn test_monthly_delta_zero_previous() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![account(1, "حساب اصلی", "BANK")];
        let txs = vec![
            // Current month only
            tx(1, TransactionType::Income, 500_000, now_ms, 1),
            tx(2, TransactionType::Expense, 200_000, now_ms, 1),
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        let acc = &result.accounts[0];
        // prevNet=0 (below threshold) → delta=0.0
        assert_eq!(acc.monthly_delta, 0.0);
    }

    /// Previous net is very small (< 1000 Rial threshold) → delta=0.0
    #[test]
    fn test_monthly_delta_small_previous_net() {
        let now_ms = now_jalali_month_ms();
        let prev_ms = {
            let jd = gregorian_to_jalali(now_ms).unwrap();
            let (pjy, pjm) = if jd.month == 1 {
                (jd.year - 1, 12)
            } else {
                (jd.year, jd.month - 1)
            };
            jalali_month_mid_ms(pjy, pjm)
        };

        let accounts = vec![account(1, "حساب اصلی", "BANK")];
        let txs = vec![
            // Current month: income=500k → currentNet=500k
            tx(1, TransactionType::Income, 500_000, now_ms, 1),
            // Previous month: expense=500 Rial → prevNet=-500 (abs < 1000)
            tx(2, TransactionType::Expense, 500, prev_ms, 1),
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        let acc = &result.accounts[0];
        // prevNet=-500, abs(500) < 1000 threshold → delta=0.0
        assert_eq!(acc.monthly_delta, 0.0);
    }

    /// Delta negative: currentNet < prevNet
    #[test]
    fn test_monthly_delta_negative() {
        let now_ms = now_jalali_month_ms();
        let prev_ms = {
            let jd = gregorian_to_jalali(now_ms).unwrap();
            let (pjy, pjm) = if jd.month == 1 {
                (jd.year - 1, 12)
            } else {
                (jd.year, jd.month - 1)
            };
            jalali_month_mid_ms(pjy, pjm)
        };

        let accounts = vec![account(1, "حساب اصلی", "BANK")];
        let txs = vec![
            // Current month: expense > income → currentNet = -100k
            tx(1, TransactionType::Income, 300_000, now_ms, 1),
            tx(2, TransactionType::Expense, 400_000, now_ms, 1),
            // Previous month: income=500k, expense=200k → prevNet=300k
            tx(3, TransactionType::Income, 500_000, prev_ms, 1),
            tx(4, TransactionType::Expense, 200_000, prev_ms, 1),
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        let acc = &result.accounts[0];
        // delta = (-100000 - 300000) / 300000 = -400000/300000 ≈ -1.333...
        assert!((acc.monthly_delta - (-4.0 / 3.0)).abs() < 1e-10);
    }

    /// Delta with transfers: previous month transfer in, current month transfer out
    #[test]
    fn test_monthly_delta_with_transfers() {
        let now_ms = now_jalali_month_ms();
        let prev_ms = {
            let jd = gregorian_to_jalali(now_ms).unwrap();
            let (pjy, pjm) = if jd.month == 1 {
                (jd.year - 1, 12)
            } else {
                (jd.year, jd.month - 1)
            };
            jalali_month_mid_ms(pjy, pjm)
        };

        let accounts = vec![
            account(1, "Account A", "BANK"),
            account(2, "Account B", "CASH_WALLET"),
        ];
        let txs = vec![
            // Current month: transfer FROM account 1 TO account 2 (500k)
            // → account 1: current income=0, current expenses=500k
            // → account 2: current income=500k
            Transaction {
                id: 1,
                tx_type: TransactionType::Transfer,
                category_id: 1,
                amount: 500_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: Some(2),
            },
            // Previous month: account 1 income=200k
            tx(2, TransactionType::Income, 200_000, prev_ms, 1),
        ];

        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        let acc1 = result.accounts.iter().find(|a| a.account_id == 1).unwrap();
        // Account 1 current: income=0, expenses=500k → currentNet=-500k
        // Account 1 prev: income=200k, expenses=0 → prevNet=200k
        // delta = (-500000 - 200000) / 200000 = -3.5
        assert!((acc1.monthly_delta - (-3.5)).abs() < 1e-10);
    }

    // =====================================================================
    // include_archived + initial_balance interaction
    // =====================================================================

    /// Regression: when include_archived=true, the total balance must include
    /// the initial_balance of archived accounts (not just their transactions).
    /// Previously, archived accounts' initial_balance was excluded even when
    /// their transactions were included, producing a partial balance.
    #[test]
    fn test_include_archived_true_includes_archived_initial_balance() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived", "BANK", 500_000),
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Expense,
                category_id: 2,
                amount: 300_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2, // archived account
                destination_account_id: None,
            },
        ];

        // include_archived=true: active tx (+1M) + archived tx (-300k) + archived initial_balance (500k)
        // expected = 1_000_000 - 300_000 + 500_000 = 1_200_000
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(result.current_balance, 1_200_000);
    }

    /// Regression: when include_archived=false, the total balance must exclude
    /// both the transactions AND the initial_balance of archived accounts.
    #[test]
    fn test_include_archived_false_excludes_archived_initial_balance() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived", "BANK", 500_000),
        ];
        let txs = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 1_000_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Expense,
                category_id: 2,
                amount: 300_000,
                description: String::new(),
                person_name: None,
                person_id: None,
                date: now_ms,
                due_date: None,
                installment_id: None,
                account_id: 2, // archived account
                destination_account_id: None,
            },
        ];

        // include_archived=false: only active tx (+1M), no initial_balance from archived
        // expected = 1_000_000
        let result = compute_dashboard_data(&txs, &[], &[], &[], &accounts, None, false, now_ms);
        assert_eq!(result.current_balance, 1_000_000);
    }

    /// Regression: total_net_worth must apply the same include_archived policy
    /// as current_balance (matching the Kotlin fallback, where
    /// totalNetWorth = currentBalance). Previously total_net_worth summed the
    /// account cards, which always exclude archived accounts — so the two
    /// figures disagreed whenever include_archived=true and an archived
    /// account had a non-zero opening balance.
    #[test]
    fn test_total_net_worth_matches_current_balance_archive_policy() {
        let now_ms = now_jalali_month_ms();
        let accounts = vec![
            account(1, "Active", "BANK"),
            archived_account(2, "Archived", "CASH_WALLET", 500_000),
        ];

        // include_archived=true: both figures include the archived opening balance.
        let result = compute_dashboard_data(&[], &[], &[], &[], &accounts, None, true, now_ms);
        assert_eq!(result.current_balance, 500_000);
        assert_eq!(result.total_net_worth, 500_000);

        // include_archived=false: both figures exclude the archived opening balance.
        let result = compute_dashboard_data(&[], &[], &[], &[], &accounts, None, false, now_ms);
        assert_eq!(result.current_balance, 0);
        assert_eq!(result.total_net_worth, 0);
    }
}
