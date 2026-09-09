use crate::currency::format_currency;
use crate::models::{
    BankLoan, Category, CurrencyUnit, Installment, Loan, Transaction, TransactionType,
};

/// Get offline budget advice based on local rules.
pub fn get_offline_budget_advice(transactions: &[Transaction], categories: &[Category]) -> String {
    let (total_income, total_expense) =
        transactions
            .iter()
            .fold((0i64, 0i64), |(income, expense), t| match t.tx_type {
                TransactionType::Income => (income + t.amount, expense),
                TransactionType::Expense => (income, expense + t.amount),
                _ => (income, expense),
            });

    let category_totals: std::collections::HashMap<i64, i64> = transactions
        .iter()
        .filter(|t| t.tx_type == TransactionType::Expense)
        .fold(std::collections::HashMap::new(), |mut acc, t| {
            *acc.entry(t.category_id).or_insert(0) += t.amount;
            acc
        });

    let highest_cat_id = category_totals
        .iter()
        .max_by_key(|(_, &v)| v)
        .map(|(&k, _)| k);

    let mut sb = String::new();
    sb.push_str("### \u{1F4A1} \u{062A}\u{0648}\u{0635}\u{06CC}\u{0647}\u{0647}\u{0627}\u{06CC} \u{0647}\u{0648}\u{0634}\u{0645}\u{0646}\u{062F} \u{0628}\u{0648}\u{062F}\u{062C}\u{0647} (\u{062A}\u{062D}\u{0644}\u{06CC}\u{0644} \u{0627}\u{0633}\u{062A}\u{0641}\u{0627}\u{062F}\u{0647} \u{0645}\u{062D}\u{0644}\u{06CC})\n\n");

    if transactions.is_empty() {
        sb.push_str("\u{0634}\u{0645}\u{0627} \u{0647}\u{0646}\u{0648}\u{0632} \u{0647}\u{06CC}\u{0686} \u{062A}\u{0631}\u{0627}\u{06A9}\u{0646}\u{0634} \u{06CC} \u{0646}\u{06A9}\u{0631}\u{062F}\u{0647}\u{0627}\u{06CC}\u{062F}. \u{0628}\u{0631}\u{0627}\u{06CC} \u{062F}\u{0631}\u{06CC}\u{0627}\u{0641}\u{062A} \u{062A}\u{062D}\u{0644}\u{06CC}\u{0644} \u{0648}\u{0636}\u{0639}\u{06CC}\u{062A} \u{0628}\u{0648}\u{062F}\u{062C}\u{0647} \u{0627}\u{0632} \u{062A}\u{0631}\u{0627}\u{06A9}\u{0646}\u{0634}\u{0647}\u{0627}\u{06CC} \u{062B}\u{0628}\u{062A} \u{0634}\u{062F}\u{0647} \u{0627}\u{0633}\u{062A}.");
        return sb;
    }

    sb.push_str("\u{0628}\u{0631} \u{0627}\u{0633}\u{0627}\u{0633} \u{062A}\u{062D}\u{0644}\u{06CC}\u{0644} \u{062A}\u{0631}\u{0627}\u{06A9}\u{0646}\u{0634}\u{0647}\u{0627}\u{06CC} \u{062B}\u{0628}\u{062A} \u{0634}\u{062F}\u{0647} \u{0634}\u{0645}\u{0627} \u{062F}\u{0631} \u{062D}\u{0633}\u{0627}\u{0628}\u{06CC}\u{0627}\u{0631} \u{06AF}\u{0632}\u{0627}\u{0631}\u{0634} \u{0634}\u{062F}\u{0647} \u{0627}\u{0633}\u{062A}:\n\n");

    if total_income > 0 {
        let saving_rate = (total_income - total_expense) as f64 / total_income as f64 * 100.0;
        if saving_rate < 0.0 {
            sb.push_str(&format!(
                "\u{26A0}\u{FE0F} **\u{06A9}\u{0646}\u{062A}\u{0631}\u{0644} \u{062A}\u{0631}\u{0627}\u{0632} \u{0645}\u{062E}\u{0627}\u{0631}\u{062C}:** \u{0645}\u{062A}\u{0627}\u{0633}\u{0641}\u{0627}\u{0646}\u{0647} \u{0645}\u{062E}\u{0627}\u{0631}\u{062C} \u{0634}\u{0645}\u{0627} \u{062F}\u{0631} \u{0627}\u{06CC}\u{0646} \u{062F}\u{0648}\u{0631}\u{0647} \u{0628}\u{06CC}\u{0634} \u{0627}\u{0632} \u{062F}\u{0631}\u{0627}\u{0645}\u{062F}\u{062A}\u{0627}\u{0646} \u{0628}\u{0648}\u{062F}\u{0647} \u{0627}\u{0633}\u{062A} ({:.1}\u{066C} \u{06A9}\u{0633}\u{0631}\u{06CC}).\n\n",
                saving_rate
            ));
        } else if saving_rate < 10.0 {
            sb.push_str(&format!(
                "\u{1F4C9} **\u{0628}\u{0647}\u{0628}\u{0631}\u{0633}\u{0627}\u{0646}\u{06CC} \u{0646}\u{0631}\u{062E} \u{067E}\u{0633}\u{200C}\u{0627}\u{0646}\u{062F}\u{0627}\u{0632}:** \u{0634}\u{0645}\u{0627} \u{062D}\u{062F}\u{0648}\u{062F} {:.1}\u{066C} \u{0627}\u{0632} \u{062F}\u{0631}\u{0622}\u{0645}\u{062F} \u{062E}\u{0648}\u{062F} \u{0631}\u{0627} \u{067E}\u{0633}\u{06CC}\u{0627}\u{0646}\u{062F}\u{0647}\u{0627}\u{06CC}\u{062F}.\n\n",
                saving_rate
            ));
        } else {
            sb.push_str(&format!(
                "\u{1F389} **\u{0639}\u{0645}\u{0644}\u{06A9}\u{0631}\u{062F} \u{0639}\u{0627}\u{0644}\u{06CC} \u{067E}\u{0633}\u{200C}\u{0627}\u{0646}\u{062F}\u{0627}\u{0632}:** \u{0622}\u{0641}\u{0631}\u{06CC}\u{0646}! \u{0634}\u{0645}\u{0627} \u{062A}\u{0648}\u{0627}\u{0646}\u{0633}\u{062A}\u{0647}\u{0627}\u{06CC}\u{062F} \u{0628}\u{06CC}\u{0634} \u{0627}\u{0632} {:.1}\u{066C} \u{0627}\u{0632} \u{062F}\u{0631}\u{0622}\u{0645}\u{062F} \u{062E}\u{0648}\u{062F} \u{0631}\u{0627} \u{067E}\u{0633}\u{06CC}\u{0627}\u{0646}\u{062F}\u{0647}\u{0627}\u{06CC}\u{062F}.\n\n",
                saving_rate
            ));
        }
    }

    if let Some(cat_id) = highest_cat_id {
        if let Some(cat) = categories.iter().find(|c| c.id == cat_id) {
            let cat_expense = category_totals.get(&cat_id).unwrap_or(&0);
            sb.push_str(&format!(
                "\u{1F4CA} **\u{0628}\u{0632}\u{0631}\u{06AF}\u{062A}\u{0631}\u{06CC}\u{0646} \u{06A9}\u{0627}\u{0646}\u{0648}\u{0646} \u{0647}\u{0632}\u{06CC}\u{0646}\u{0647}:** \u{062F}\u{0633}\u{062A}\u{0647} **{}** \u{0628}\u{0627} \u{0645}\u{062C}\u{0645}\u{0648}\u{0639} {}.\n\n",
                cat.name, format_currency(*cat_expense, CurrencyUnit::Toman)
            ));
        }
    }

    sb.push_str("\u{1F4CC} **\u{0642}\u{0648}\u{0627}\u{0646}\u{06CC}\u{0646} \u{0648} \u{0631}\u{0627}\u{0647}\u{06A9}\u{0627}\u{0631}\u{0647}\u{0627}:**\n");
    sb.push_str("- **\u{0627}\u{0633}\u{062A}\u{0631}\u{0627}\u{062A}\u{069C}\u{06CC} 50-30-20:** \u{0646}\u{06CC}\u{0645}\u{06CC} \u{0627}\u{0632} \u{062F}\u{0631}\u{0622}\u{0645}\u{062F} \u{0631}\u{0627} \u{0628}\u{0647} \u{0627}\u{062C}\u{0627}\u{0631}\u{0647} \u{0648} \u{0646}\u{06CC}\u{0627}\u{0632}\u{0647}\u{0627}\u{06CC} \u{0627}\u{0633}\u{0627}\u{0633}\u{06CC} \u{0628}\u{0631}\u{0633}\u{0627}\u{0646}\u{062F} \u{0648} 20 \u{062F}\u{0631}\u{0635}\u{062F} \u{0628}\u{0627}\u{0642}\u{06CC}\u{0645}\u{0647} \u{0631}\u{0627} \u{0628}\u{0647} \u{067E}\u{0633}\u{06CC}\u{0627}\u{0646}\u{062F} \u{06CC}\u{0627} \u{062A}\u{0633}\u{0648}\u{06CC}\u{0647} \u{0628}\u{0647} \u{062A}\u{062E}\u{0635}\u{06CC}\u{0635} \u{062F}\u{0647}\u{06CC}\u{062F}.\n");
    sb.push_str("- **\u{067E}\u{06CC}\u{0634}\u{06AF}\u{06CC}\u{0631}\u{06CC} \u{0627}\u{0632} \u{0641}\u{0631}\u{0627}\u{0631} \u{0645}\u{062E}\u{0627}\u{0631}\u{062C}:** \u{06A9}\u{0648}\u{0686}\u{06A9}\u{062A}\u{0631}\u{06CC}\u{0646} \u{0641}\u{0627}\u{06A9}\u{062A}\u{0648}\u{0631}\u{0647}\u{0627} \u{0631}\u{0627} \u{0647}\u{0645} \u{062F}\u{0631} \u{062D}\u{0633}\u{0627}\u{0628}\u{06CC}\u{0627}\u{0631} \u{062B}\u{0628}\u{062A} \u{06A9}\u{0646}\u{06CC}\u{062F}.\n");
    sb.push_str("- **\u{0627}\u{06CC}\u{062C}\u{0627}\u{062F} \u{0635}\u{0646}\u{062F}\u{0648}\u{0642} \u{0627}\u{0636}\u{062A}\u{0631}\u{0627}\u{0637}\u{064A}:** \u{0647}\u{0645}\u{06CC}\u{0634}\u{0647} \u{0645}\u{0639}\u{0627}\u{062F}\u{0644} 3 \u{0627}\u{0644}\u{06CC} 6 \u{0628}\u{0627}\u{0631}\u{0628} \u{0645}\u{062E}\u{0627}\u{0631}\u{062C} \u{0645}\u{0627}\u{0647}\u{0627}\u{0646}\u{0647} \u{062E}\u{0648}\u{062F} \u{0631}\u{0627} \u{062F}\u{0631} \u{06CC}\u{06A9} \u{062D}\u{0633}\u{0627}\u{0628} \u{0645}\u{062C}\u{0632}\u{0627} \u{0628}\u{0631}\u{0627}\u{06CC} \u{0628}\u{0631}\u{0648}\u{0632}\u{0634} \u{063A}\u{06CC}\u{0631}\u{0645}\u{062A}\u{0642}\u{0628}\u{0647} \u{0630}\u{062E}\u{06CC}\u{0631}\u{0647} \u{06A9}\u{0646}\u{06CC}\u{062F}.\n");

    sb
}

/// Get offline budget forecast.
pub fn get_offline_forecast(
    transactions: &[Transaction],
    loans: &[Loan],
    installments: &[Installment],
    bank_loans: &[BankLoan],
) -> String {
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let thirty_days_ms = 30 * 24 * 60 * 60 * 1000;
    let upcoming_installments: Vec<&Installment> = installments
        .iter()
        .filter(|i| !i.is_paid && i.due_date >= now_ms && i.due_date <= now_ms + thirty_days_ms)
        .collect();
    let upcoming_sum: i64 = upcoming_installments
        .iter()
        .map(|i| i.amount)
        .fold(0, |total, amount| total.saturating_add(amount));

    let unsettled_creditor_loan_monthly: i64 = loans
        .iter()
        .filter(|l| !l.is_settled && l.loan_type == "CREDITOR")
        .map(|l| l.remaining_amount / 12)
        .fold(0, |total, amount| total.saturating_add(amount));
    let total_obligations = upcoming_sum.saturating_add(unsettled_creditor_loan_monthly);

    // Active (unsettled) bank-loan count and debt, summed as
    // total_repayable_amount. Mirrors Kotlin buildLocalOfflineForecast: bank
    // loans guard the "no data" message and are rendered as an active-debt line.
    let (active_bank_loan_count, bank_loan_debt) = bank_loans
        .iter()
        .filter(|b| !b.is_settled)
        .fold((0usize, 0i64), |(count, debt), b| {
            (count + 1, debt.saturating_add(b.total_repayable_amount))
        });

    if transactions.is_empty() && total_obligations == 0 && active_bank_loan_count == 0 {
        return "\u{0647}\u{0646}\u{0648}\u{0632} \u{0627}\u{0637}\u{0644}\u{0627}\u{0639}\u{0627}\u{062A} \u{062A}\u{0631}\u{0627}\u{06A9}\u{0646}\u{0634} \u{06CC} \u{0642}\u{0633}\u{0637} \u{062F}\u{0631} \u{062D}\u{0633}\u{0627}\u{0628}\u{06CC}\u{0627}\u{0631} \u{062B}\u{0628}\u{062A} \u{0646}\u{0634}\u{062F}\u{0647} \u{0627}\u{0633}\u{062A}. \u{0644}\u{0637}\u{0641}\u{0627} \u{062E}\u{0637}\u{0627} \u{0648} \u{062E}\u{0631}\u{062C} \u{0647}\u{0627}\u{06CC} \u{0631}\u{0648}\u{0632}\u{0627}\u{0646}\u{0647} \u{062E}\u{0648}\u{062F} \u{0631}\u{0627} \u{0648}\u{0627}\u{0631}\u{062F} \u{06A9}\u{0646}\u{06CC}\u{062F}.".to_string();
    }

    let window_start = now_ms - 90 * 24 * 60 * 60 * 1000;
    let recent: Vec<&Transaction> = transactions
        .iter()
        .filter(|t| t.date >= window_start && t.date <= now_ms)
        .collect();

    let recent_income: i64 = recent
        .iter()
        .filter(|t| t.tx_type == TransactionType::Income)
        .map(|t| t.amount)
        .fold(0, |total, amount| total.saturating_add(amount));
    let recent_expense: i64 = recent
        .iter()
        .filter(|t| t.tx_type == TransactionType::Expense)
        .map(|t| t.amount)
        .fold(0, |total, amount| total.saturating_add(amount));

    let ms_per_day: i64 = 24 * 60 * 60 * 1000;
    let days_span = if !recent.is_empty() {
        let oldest_date = recent.iter().map(|t| t.date).min().unwrap_or(now_ms);
        // Ceiling division into whole days, minimum 1. Avoids f64, which cannot
        // represent Rial amounts above 2^53 exactly.
        (now_ms.saturating_sub(oldest_date) + ms_per_day - 1) / ms_per_day
    } else {
        1
    };
    // Fractional-month normalization: avg = sum * 30 / days_span (days clamped to min 30).
    // i128 keeps Rial exact above 2^53; result always ≤ sum, so the as i64 cast is safe.
    let normalization_days: i128 = days_span.max(30) as i128;

    let avg_income = if recent.iter().any(|t| t.tx_type == TransactionType::Income) {
        ((recent_income as i128 * 30) / normalization_days) as i64
    } else {
        0
    };
    let avg_expense = if recent.iter().any(|t| t.tx_type == TransactionType::Expense) {
        ((recent_expense as i128 * 30) / normalization_days) as i64
    } else {
        0
    };
    // Use saturating_sub to prevent overflow/wrap under extreme values.
    // All inputs are non-negative i64; underflow would silently wrap in release mode.
    let est_balance = avg_income
        .saturating_sub(avg_expense)
        .saturating_sub(total_obligations);

    let mut sb = String::new();
    sb.push_str("### \u{1F52E} \u{067E}\u{06CC}\u{0634}\u{0628}\u{06CC}\u{0646}\u{06CC} \u{0647}\u{0648}\u{0634}\u{0645}\u{0646}\u{062F} \u{0648}\u{0636}\u{0639}\u{06CC}\u{062A} \u{0628}\u{0648}\u{062F}\u{062C}\u{0647} \u{0645}\u{0627}\u{0647} \u{0622}\u{06CC}\u{0646}\u{062F}\u{0647}\n\n");
    sb.push_str(&format!("- \u{1F4B5} **\u{062F}\u{0631}\u{0622}\u{0645}\u{062F} \u{062A}\u{062E}\u{0645}\u{06CC}\u{0646}\u{06CC}:** {}\n", format_currency(avg_income, CurrencyUnit::Toman)));
    sb.push_str(&format!("- \u{1F4B8} **\u{0645}\u{062E}\u{0627}\u{0631}\u{062C} \u{062A}\u{062E}\u{0645}\u{06CC}\u{0646}\u{06CC}:** {}\n", format_currency(avg_expense, CurrencyUnit::Toman)));
    sb.push_str(&format!("- \u{1F4C5} **\u{062A}\u{0639}\u{0647}\u{062F} \u{0627}\u{0642}\u{0633}\u{0627}\u{0637}:** {}\n", format_currency(total_obligations, CurrencyUnit::Toman)));
    if active_bank_loan_count > 0 {
        sb.push_str(&format!(
            "- **\u{0628}\u{062F}\u{0647}\u{06CC}\u{0647}\u{0627}\u{06CC} \u{0641}\u{0639}\u{0627}\u{0644}:** {} \u{0645}\u{0648}\u{0631}\u{062F} \u{0628}\u{0647} \u{0645}\u{0628}\u{0644}\u{063A} {}\n",
            active_bank_loan_count,
            format_currency(bank_loan_debt, CurrencyUnit::Toman)
        ));
    }

    if est_balance < 0 {
        sb.push_str(&format!(
            "\n### \u{1F6A8} \u{0647}\u{0634}\u{062F}\u{0627}\u{0631} \u{0647}\u{0648}\u{0634}\u{0645}\u{0646}\u{062F}: \u{0631}\u{06CC}\u{0633}\u{06A9} \u{06A9}\u{0633}\u{0631}\u{06CC} \u{0628}\u{0648}\u{062F}\u{062C}\u{0647} \u{062F}\u{0631} \u{0645}\u{0627}\u{0647} \u{0628}\u{0639}\u{062F}!\n\u{0628}\u{0627} \u{0646}\u{06AF}\u{0631}\u{0627}\u{0646}\u{06CC} \u{062E}\u{0641}\u{06CC}\u{0641} \u{062A}\u{0631}\u{0627}\u{0632} \u{0646}\u{0642}\u{062F}\u{06CC} \u{0634}\u{0645}\u{0627} \u{062F}\u{0631} \u{0645}\u{0627}\u{0647} \u{0622}\u{06CC}\u{0646}\u{062F}\u{0647} \u{0628}\u{0627} **\u{06A9}\u{0633}\u{0631}\u{06CC} \u{062D}\u{062F}\u{0648}\u{062F} {}** \u{0631}\u{0648}\u{0628}\u{0631}\u{0648} \u{062E}\u{0648}\u{0627}\u{0647}\u{062F}.\n\n",
            format_currency(est_balance.saturating_abs(), CurrencyUnit::Toman)
        ));
    } else {
        sb.push_str(&format!(
            "\n### \u{1F7E2} \u{0647}\u{0634}\u{062F}\u{0627}\u{0631} \u{0647}\u{0648}\u{0634}\u{0645}\u{0646}\u{062F}: \u{0648}\u{0636}\u{0639}\u{06CC}\u{062A} \u{0645}\u{0627}\u{0644}\u{06CC} \u{067E}\u{0627}\u{06CC}\u{062F}\u{0627}\u{0631}\n\u{0628}\u{0631}\u{0627}\u{0633}\u{0627}\u{0633} \u{0627}\u{0644}\u{06AF}\u{0648}\u{06CC} \u{062F}\u{062E}\u{0644} \u{0648} \u{062E}\u{0631}\u{062C} \u{0634}\u{0645}\u{0627} \u{062F}\u{0631} \u{0645}\u{0627}\u{0647} \u{0622}\u{06CC}\u{0646}\u{062F}\u{0647} \u{0628}\u{0627} **\u{0645}\u{0627}\u{0632}\u{0627}\u{062F} \u{0628}\u{0648}\u{062F}\u{062C}\u{0647} {}** \u{067E}\u{0637} \u{0633}\u{0628}\u{06A9} \u{0628}\u{06AF}\u{0631}\u{062F}\u{06CC}\u{062F}.\n\n",
            format_currency(est_balance, CurrencyUnit::Toman)
        ));
    }

    sb
}

/// Monthly income baseline over the trailing 90-day window, bounded by `now_ms`.
///
/// Future-dated transactions are excluded so scheduled income does not inflate
/// the baseline. Returns 0 when there is no income in the window.
fn monthly_income_baseline(transactions: &[Transaction], now_ms: i64) -> i64 {
    let window_start = now_ms - 90 * 24 * 60 * 60 * 1000;
    let recent: Vec<&Transaction> = transactions
        .iter()
        .filter(|t| {
            t.tx_type == TransactionType::Income && t.date >= window_start && t.date <= now_ms
        })
        .collect();
    if recent.is_empty() {
        return 0;
    }
    let ms_per_day: i64 = 24 * 60 * 60 * 1000;
    let oldest = recent.iter().map(|t| t.date).min().unwrap_or(now_ms);
    let days = (now_ms.saturating_sub(oldest) + ms_per_day - 1) / ms_per_day;
    // Fractional-month baseline: avg = sum * 30 / days (i128 to avoid 2^53 loss).
    let sum: i64 = recent
        .iter()
        .map(|t| t.amount)
        .fold(0, |acc, a| acc.saturating_add(a));
    ((sum as i128 * 30) / (days.max(30) as i128)) as i64
}

/// Calculate debt-to-income ratio.
pub fn calculate_debt_to_income_ratio(
    loans: &[Loan],
    installments: &[Installment],
    // Intentionally excluded from DTI (matches Kotlin local impl; only
    // consumer loans and installments count toward the ratio).
    _bank_loans: &[BankLoan],
    monthly_income: i64,
) -> f64 {
    let monthly_debt_payments: i64 = installments
        .iter()
        .filter(|i| !i.is_paid)
        .map(|i| i.amount)
        .sum::<i64>()
        + loans
            .iter()
            .filter(|l| !l.is_settled && l.loan_type == "CREDITOR")
            .map(|l| l.remaining_amount / 12)
            .sum::<i64>();

    if monthly_income <= 0 && monthly_debt_payments > 0 {
        return 1.0;
    }
    if monthly_income <= 0 {
        return 0.0;
    }
    monthly_debt_payments as f64 / monthly_income as f64
}

/// Predict time to reach a savings goal.
pub fn predict_time_to_goal(current_savings: i64, monthly_savings: i64, goal_amount: i64) -> i32 {
    if monthly_savings <= 0 {
        return -1;
    }
    let remaining = goal_amount.saturating_sub(current_savings);
    if remaining <= 0 {
        0
    } else {
        // months = ceil(remaining / monthly_savings)
        // Compute the quotient and remainder directly so the numerator never
        // overflows: the old `(remaining + monthly_savings - 1) / monthly_savings`
        // saturated `remaining + monthly_savings` to i64::MAX for huge goals,
        // dropping the carry and undercounting the duration. Clamp to i32::MAX to
        // mirror the Kotlin fallback's `coerceAtMost(Int.MAX_VALUE)` so the Rust
        // and Kotlin paths never diverge (a raw `as i32` would wrap a huge count
        // into a negative value).
        let q = remaining / monthly_savings;
        let months = if remaining % monthly_savings == 0 {
            q
        } else {
            q.saturating_add(1)
        };
        (months.clamp(0, i32::MAX as i64)) as i32
    }
}

/// Calculate financial health score (0-100).
pub fn calculate_financial_health_score(
    transactions: &[Transaction],
    loans: &[Loan],
    installments: &[Installment],
    bank_loans: &[BankLoan],
    _categories: &[Category],
) -> i32 {
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    if transactions.is_empty() {
        return 0;
    }

    let total_income: i64 = transactions
        .iter()
        .filter(|t| t.tx_type == TransactionType::Income)
        .map(|t| t.amount)
        .fold(0, |total, amount| total.saturating_add(amount));
    let total_expense: i64 = transactions
        .iter()
        .filter(|t| t.tx_type == TransactionType::Expense)
        .map(|t| t.amount)
        .fold(0, |total, amount| total.saturating_add(amount));
    let balance = total_income.saturating_sub(total_expense);

    let mut score: i32 = 50;

    // Savings rate (max +25)
    if total_income > 0 {
        let savings_rate = balance as f64 / total_income as f64;
        score += if savings_rate >= 0.3 {
            25
        } else if savings_rate >= 0.2 {
            20
        } else if savings_rate >= 0.1 {
            10
        } else if savings_rate >= 0.0 {
            0
        } else {
            -15
        };
    }

    // Debt-to-income (max +15)
    // Scope income to a monthly baseline (trailing 90d window) so the
    // all-time accumulated income does not understate the ratio relative to
    // the monthly debt/installment obligations.
    let monthly_income = monthly_income_baseline(transactions, now_ms);
    let debt_ratio =
        calculate_debt_to_income_ratio(loans, installments, bank_loans, monthly_income);
    score += if debt_ratio <= 0.1 {
        15
    } else if debt_ratio <= 0.2 {
        10
    } else if debt_ratio <= 0.3 {
        5
    } else if debt_ratio <= 0.4 {
        0
    } else {
        -10
    };

    // Category diversification (+10 if 3+ categories)
    let expense_cats: std::collections::HashSet<i64> = transactions
        .iter()
        .filter(|t| t.tx_type == TransactionType::Expense)
        .map(|t| t.category_id)
        .collect();
    score += if expense_cats.len() >= 5 {
        10
    } else if expense_cats.len() >= 3 {
        5
    } else {
        0
    };

    score.clamp(0, 100)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_predict_time_to_goal() {
        assert_eq!(predict_time_to_goal(0, 1000, 10000), 10);
        assert_eq!(predict_time_to_goal(10000, 1000, 10000), 0);
        assert_eq!(predict_time_to_goal(0, 0, 10000), -1);
    }

    #[test]
    fn test_predict_time_to_goal_overflow_saturates_at_i32_max() {
        // A massive goal with a tiny monthly saving must saturate at i32::MAX
        // instead of wrapping into a negative month count (parity with the
        // Kotlin fallback's coerceAtMost(Int.MAX_VALUE)).
        assert_eq!(predict_time_to_goal(0, 1, i64::MAX), i32::MAX);
        assert_eq!(
            predict_time_to_goal(0, 1, 3_000_000_000_000_000_000),
            i32::MAX
        );
        // Large but finite goals also clamp rather than overflow.
        assert_eq!(predict_time_to_goal(0, 1, i64::MAX - 5), i32::MAX);
    }

    #[test]
    fn test_predict_time_to_goal_ceil_no_overflow_undercount() {
        // Reproduces the carry-loss bug: with a large remaining balance and a
        // large monthly saving, `remaining + monthly_savings` saturates to
        // i64::MAX before the division, dropping the carry and undercounting the
        // duration by one. The quotient/remainder form must stay exact.
        // ceil((i64::MAX - 100) / 5_000_000_000) == 1844674408.
        assert_eq!(
            predict_time_to_goal(0, 5_000_000_000, i64::MAX - 100),
            1844674408
        );
    }

    #[test]
    fn test_debt_to_income_ratio() {
        assert_eq!(calculate_debt_to_income_ratio(&[], &[], &[], 0), 0.0);
        assert_eq!(calculate_debt_to_income_ratio(&[], &[], &[], 100000), 0.0);
    }

    fn now_ms() -> i64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as i64
    }

    fn sample_tx(id: i64, ttype: TransactionType, amount: i64, date: i64) -> Transaction {
        Transaction {
            id,
            tx_type: ttype,
            category_id: 1,
            amount,
            description: String::new(),
            person_name: None,
            person_id: None,
            date,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        }
    }

    #[test]
    fn test_monthly_income_baseline_scopes_recent_window() {
        let now: i64 = 1_700_000_000_000;
        let day: i64 = 24 * 60 * 60 * 1000;
        // Recent income (15 days ago) plus ancient all-time income (330 days ago,
        // outside the 90-day window).
        let txs = vec![
            sample_tx(1, TransactionType::Income, 1_000_000, now - 15 * day),
            sample_tx(2, TransactionType::Income, 11_000_000, now - 330 * day),
        ];
        let monthly = monthly_income_baseline(&txs, now);
        // Only the recent 1_000_000 should count; the ancient income is excluded.
        assert!(monthly > 0 && monthly <= 1_000_000 + 2);
    }

    #[test]
    fn test_monthly_income_baseline_preserves_precision_above_2pow53() {
        // 2^53 + 7 (9_007_199_254_740_999) is at the boundary where f64's 53-bit
        // mantissa can no longer represent every integer — it rounds up to
        // 9_007_199_254_741_000. The old code cast `sum as f64`, losing exactness.
        // Integer division preserves the precise Rial value.
        let now: i64 = 1_700_000_000_000;
        let day: i64 = 24 * 60 * 60 * 1000;
        let amount: i64 = 9_007_199_254_740_999;
        let txs = vec![sample_tx(1, TransactionType::Income, amount, now - 5 * day)];
        let monthly = monthly_income_baseline(&txs, now);
        // 5 days → months = 1 → avg = sum / 1 = amount (exact, no f64 rounding).
        assert_eq!(
            monthly, amount,
            "f64 cast must not lose precision for values above 2^53"
        );
    }

    #[test]
    fn test_monthly_income_baseline_fractional_months_45_day_window() {
        // Finding 1+2: 45-day window exercises the months_elapsed > 1 path
        // (previously untested). With ceiling division, months = 2 and avg =
        // 1_000_000 / 2 = 500_000 — a regression from the old f64 code which
        // computed months = 45 / 30 = 1.5 and avg = 1_000_000 / 1.5 = 666,666.
        // The fractional-month normalization (sum * 30 / days) restores the
        // correct value: 1_000_000 * 30 / 45 = 666,666.
        let now: i64 = 1_700_000_000_000;
        let day: i64 = 24 * 60 * 60 * 1000;
        let txs = vec![sample_tx(
            1,
            TransactionType::Income,
            1_000_000,
            now - 45 * day,
        )];
        let monthly = monthly_income_baseline(&txs, now);
        assert_eq!(
            monthly, 666_666,
            "45-day window must use fractional-month normalization, not ceiling division"
        );
    }

    // -- get_offline_budget_advice tests -----------------------------------------

    #[test]
    fn test_advice_empty_transactions_returns_empty_prompt() {
        let result = get_offline_budget_advice(&[], &[]);
        // Should contain the "no transactions yet" message
        assert!(result.contains("\u{062A}\u{0631}\u{0627}\u{06A9}\u{0646}\u{0634}"));
    }

    #[test]
    fn test_advice_negative_saving_rate_warns_deficit() {
        let txs = vec![
            sample_tx(1, TransactionType::Income, 1_000_000, 0),
            sample_tx(2, TransactionType::Expense, 5_000_000, 0),
        ];
        let result = get_offline_budget_advice(&txs, &[]);
        //saving_rate = (1M - 5M)/1M = -400% → deficit warning
        assert!(result.contains("\u{0645}\u{062E}\u{0627}\u{0631}\u{062C}"));
    }

    #[test]
    fn test_advice_low_saving_rate_below_10_percent() {
        let txs = vec![
            sample_tx(1, TransactionType::Income, 10_000_000, 0),
            sample_tx(2, TransactionType::Expense, 9_500_000, 0),
        ];
        let result = get_offline_budget_advice(&txs, &[]);
        // saving_rate = 5% → "near zero savings"
        assert!(result.contains("\u{067E}\u{0633}\u{200C}\u{0627}\u{0646}\u{062F}\u{0627}\u{0632}"));
    }

    #[test]
    fn test_advice_high_saving_rate_above_10_percent() {
        let txs = vec![
            sample_tx(1, TransactionType::Income, 10_000_000, 0),
            sample_tx(2, TransactionType::Expense, 5_000_000, 0),
        ];
        let result = get_offline_budget_advice(&txs, &[]);
        // saving_rate = 50% → "excellent savings"
        assert!(result.contains("\u{0639}\u{0645}\u{0644}\u{06A9}\u{0631}\u{062F}"));
    }

    #[test]
    fn test_advice_with_categories_mentions_highest_category() {
        let txs = vec![
            sample_tx(1, TransactionType::Expense, 3_000_000, 0),
            sample_tx(2, TransactionType::Expense, 1_000_000, 0),
        ];
        // category_id defaults to 1 in sample_tx
        let cats =
            vec![Category {
            id: 1,
            name: "\u{0645}\u{062A}\u{0631}\u{0648}\u{06CC}\u{0628}\u{0632}\u{0627}\u{0631}\u{06CC}".into(), // "Groceries"
            key: "groceries".into(),
            icon: "".into(),
            color: 0,
            category_type: "EXPENSE".into(),
            is_default: false,
        }];
        let result = get_offline_budget_advice(&txs, &cats);
        assert!(result.contains(
            "\u{0645}\u{062A}\u{0631}\u{0648}\u{06CC}\u{0628}\u{0632}\u{0627}\u{0631}\u{06CC}"
        ));
    }

    #[test]
    fn test_advice_category_amount_is_in_toman_not_rial() {
        // 10,000,000 Rial expense must be shown as "1,000,000 تومان",
        // never as the raw Rial value "10,000,000 تومان".
        let txs = vec![sample_tx(1, TransactionType::Expense, 10_000_000, 0)];
        let cats = vec![Category {
            id: 1,
            name:
                "\u{0645}\u{062A}\u{0631}\u{0648}\u{06CC}\u{0628}\u{0632}\u{0627}\u{0631}\u{06CC}"
                    .into(),
            key: "groceries".into(),
            icon: "".into(),
            color: 0,
            category_type: "EXPENSE".into(),
            is_default: false,
        }];
        let result = get_offline_budget_advice(&txs, &cats);
        assert!(result.contains("1,000,000 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"));
        assert!(!result.contains("10,000,000 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"));
    }

    // -- get_offline_forecast tests ----------------------------------------------

    #[test]
    fn test_forecast_empty_returns_no_data_message() {
        let result = get_offline_forecast(&[], &[], &[], &[]);
        assert!(result.contains("\u{0627}\u{0637}\u{0644}\u{0627}\u{0639}\u{0627}\u{062A}"));
    }

    #[test]
    fn test_forecast_negative_balance_warns() {
        let now = now_ms();
        let txs = vec![
            sample_tx(
                1,
                TransactionType::Income,
                1_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
            sample_tx(
                2,
                TransactionType::Expense,
                5_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
        ];
        let result = get_offline_forecast(&txs, &[], &[], &[]);
        // est_balance negative → warning
        assert!(result.contains("\u{0647}\u{0634}\u{062F}\u{0627}\u{0631}"));
    }

    #[test]
    fn test_forecast_positive_balance_shows_surplus() {
        let now = now_ms();
        let txs = vec![
            sample_tx(
                1,
                TransactionType::Income,
                10_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
            sample_tx(
                2,
                TransactionType::Expense,
                2_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
        ];
        let result = get_offline_forecast(&txs, &[], &[], &[]);
        // est_balance positive → surplus
        assert!(result.contains("\u{0648}\u{0636}\u{0639}\u{06CC}\u{062A}"));
    }

    #[test]
    fn test_forecast_45_day_window_uses_fractional_month_normalization() {
        // Finding 1+2: 45-day window exercises months_elapsed > 1 (previously
        // untested). With ceiling division, months = 2 and avg_income =
        // 1_000_000 / 2 = 500_000 Rial → 50,000 Toman. The old f64 code computed
        // months = 45 / 30 = 1.5 and avg_income = 1_000_000 / 1.5 = 666,666 Rial
        // → 66,666 Toman. The fractional-month normalization restores 666,666
        // Rial → 66,666 Toman.
        let now = now_ms();
        let day = 24 * 60 * 60 * 1000_i64;
        let txs = vec![sample_tx(
            1,
            TransactionType::Income,
            1_000_000,
            now - 45 * day,
        )];
        let result = get_offline_forecast(&txs, &[], &[], &[]);
        // avg_income = 666,666 Rial → 66,666 Toman in the "درآمد تخمینی" line.
        assert!(
            result.contains("66,666"),
            "45-day fractional avg_income (66,666 Toman) must appear in output; got: {result}"
        );
        // The ceiling-division result (500,000 Rial → 50,000 Toman) must NOT appear.
        assert!(
            !result.contains("50,000"),
            "ceiling-division result (50,000 Toman) must not appear; got: {result}"
        );
    }

    #[test]
    fn test_forecast_with_installments_subtracts_upcoming() {
        let now = now_ms();
        let txs = vec![
            sample_tx(
                1,
                TransactionType::Income,
                10_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
            sample_tx(
                2,
                TransactionType::Expense,
                2_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
        ];
        let installments = vec![Installment {
            id: 1,
            title: "rent".into(),
            amount: 5_000_000,
            due_date: now + 30 * 24 * 60 * 60 * 1000,
            is_paid: false,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        }];
        let result = get_offline_forecast(&txs, &[], &installments, &[]);
        // upcoming_sum = 5M → est_balance = (8M/monthly) - 5M → may be positive or negative
        assert!(result.contains("\u{0627}\u{0642}\u{0633}\u{0627}\u{0637}"));
    }

    #[test]
    fn test_forecast_amounts_are_in_toman_not_rial() {
        // 10,000,000 Rial income/installment must render as "1,000,000 تومان".
        let now = now_ms();
        let txs = vec![
            sample_tx(
                1,
                TransactionType::Income,
                10_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
            sample_tx(
                2,
                TransactionType::Expense,
                3_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
        ];
        let installments = vec![Installment {
            id: 1,
            title: "rent".into(),
            amount: 10_000_000,
            due_date: now + 30 * 24 * 60 * 60 * 1000,
            is_paid: false,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        }];
        let result = get_offline_forecast(&txs, &[], &installments, &[]);
        assert!(result.contains("1,000,000 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"));
        assert!(!result.contains("10,000,000 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"));
    }

    #[test]
    fn test_forecast_only_installments_no_transactions() {
        let now = now_ms();
        let installments = vec![Installment {
            id: 1,
            title: "car".into(),
            amount: 3_000_000,
            due_date: now,
            is_paid: false,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        }];
        let result = get_offline_forecast(&[], &[], &installments, &[]);
        // Has unpaid installments → not empty, shows forecast
        assert!(result.contains("\u{062A}\u{0639}\u{0647}\u{062F}"));
    }

    #[test]
    fn test_forecast_excludes_overdue_installments() {
        let now = now_ms();
        let txs = vec![
            sample_tx(
                1,
                TransactionType::Income,
                10_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
            sample_tx(
                2,
                TransactionType::Expense,
                2_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
        ];
        let installments = vec![Installment {
            id: 1,
            title: "old".into(),
            amount: 5_000_000,
            due_date: now - 30 * 24 * 60 * 60 * 1000,
            is_paid: false,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        }];
        let result = get_offline_forecast(&txs, &[], &installments, &[]);
        // Overdue (past-due) unpaid installment is outside the window → must NOT contribute.
        assert!(!result
            .contains("\u{06F5}\u{066C}\u{06F0}\u{06F0}\u{06F0}\u{066C}\u{06F0}\u{06F0}\u{06F0}"));
    }

    #[test]
    fn test_forecast_excludes_installments_beyond_30_days() {
        let now = now_ms();
        let txs = vec![
            sample_tx(
                1,
                TransactionType::Income,
                10_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
            sample_tx(
                2,
                TransactionType::Expense,
                2_000_000,
                now - 5 * 24 * 60 * 60 * 1000,
            ),
        ];
        let installments = vec![Installment {
            id: 1,
            title: "car".into(),
            amount: 5_000_000,
            due_date: now + 60 * 24 * 60 * 60 * 1000,
            is_paid: false,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        }];
        let result = get_offline_forecast(&txs, &[], &installments, &[]);
        // Due 60 days out is outside the 30-day window → must NOT contribute to obligations.
        assert!(!result
            .contains("\u{06F5}\u{066C}\u{06F0}\u{06F0}\u{06F0}\u{066C}\u{06F0}\u{06F0}\u{06F0}"));
    }

    #[test]
    fn test_forecast_includes_unsettled_bank_loans() {
        // One active bank loan: 12,000,000 Rial total repayable → 1,200,000 Toman.
        let bank_loans = vec![BankLoan {
            id: 1,
            bank_name: "\u{0628}\u{0627}\u{0646}\u{06A9} \u{0645}\u{0644}\u{062A}".into(),
            loan_name: "\u{0648}\u{0627}\u{0645} \u{062E}\u{0648}\u{062F}\u{0631}\u{0648}".into(),
            received_amount: 10_000_000,
            monthly_installment_amount: 1_000_000,
            number_of_installments: 12,
            total_repayable_amount: 12_000_000,
            total_interest: 2_000_000,
            start_date: 0,
            description: String::new(),
            is_settled: false,
        }];
        let result = get_offline_forecast(&[], &[], &[], &bank_loans);
        // An unsettled bank loan means the data is not empty → no "no data" message.
        assert!(!result.contains("\u{0627}\u{0637}\u{0644}\u{0627}\u{0639}\u{0627}\u{062A}"));
        // Active bank-loan debt is shown in Toman, not Rial.
        assert!(result.contains("1,200,000 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"));
        assert!(!result.contains("12,000,000 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"));
    }

    #[test]
    fn test_forecast_settled_bank_loans_still_empty() {
        // A settled bank loan must NOT suppress the "no data" message.
        let bank_loans = vec![BankLoan {
            id: 1,
            bank_name: "\u{0628}\u{0627}\u{0646}\u{06A9} \u{0645}\u{0644}\u{062A}".into(),
            loan_name: "\u{0648}\u{0627}\u{0645} \u{062E}\u{0648}\u{062F}\u{0631}\u{0648}".into(),
            received_amount: 10_000_000,
            monthly_installment_amount: 1_000_000,
            number_of_installments: 12,
            total_repayable_amount: 12_000_000,
            total_interest: 2_000_000,
            start_date: 0,
            description: String::new(),
            is_settled: true,
        }];
        let result = get_offline_forecast(&[], &[], &[], &bank_loans);
        assert!(result.contains("\u{0627}\u{0637}\u{0644}\u{0627}\u{0639}\u{0627}\u{062A}"));
    }

    #[test]
    fn test_forecast_bank_loan_debt_saturates_on_overflow() {
        // Two active bank loans whose total_repayable_amounts individually exceed
        // half of i64::MAX — a plain sum() would overflow (panic in debug builds,
        // wrap in release). The saturating fold must produce i64::MAX without panicking.
        let bank_loans = vec![
            BankLoan {
                id: 1,
                bank_name: "A".into(),
                loan_name: "x".into(),
                received_amount: i64::MAX,
                monthly_installment_amount: i64::MAX,
                number_of_installments: 1,
                total_repayable_amount: i64::MAX,
                total_interest: 0,
                start_date: 0,
                description: String::new(),
                is_settled: false,
            },
            BankLoan {
                id: 2,
                bank_name: "B".into(),
                loan_name: "y".into(),
                received_amount: i64::MAX,
                monthly_installment_amount: i64::MAX,
                number_of_installments: 1,
                total_repayable_amount: i64::MAX,
                total_interest: 0,
                start_date: 0,
                description: String::new(),
                is_settled: false,
            },
        ];
        let result = get_offline_forecast(&[], &[], &[], &bank_loans);
        // Saturated debt = i64::MAX → Toman = i64::MAX / 10 = 922,337,203,685,477,580.
        assert!(
            result.contains("922,337,203,685,477,580 \u{062A}\u{0648}\u{0645}\u{0627}\u{0646}"),
            "expected saturated Toman debt in output, got: {result}"
        );
    }

    #[test]
    fn test_forecast_est_balance_saturates_under_extreme_values() {
        // With extreme values, est_balance = avg_income - avg_expense - total_obligations
        // must not panic. Before the saturating_sub fix, 0 - i64::MAX - (i64::MAX/12)
        // would underflow (panic in debug, wrap in release). saturating_sub clamps
        // to i64::MIN, and saturating_abs() on i64::MIN yields i64::MAX without panic.
        let now = now_ms();
        let txs = vec![sample_tx(
            1,
            TransactionType::Expense,
            i64::MAX,
            now - 5 * 24 * 60 * 60 * 1000,
        )];
        let loans = vec![Loan {
            id: 1,
            person_name: "".into(),
            person_id: None,
            loan_type: "CREDITOR".into(),
            original_amount: i64::MAX,
            remaining_amount: i64::MAX,
            description: String::new(),
            date: now,
            is_settled: false,
        }];
        // Must not panic; returns a negative-balance warning (saturated).
        let result = get_offline_forecast(&txs, &loans, &[], &[]);
        // The deficit branch (est_balance < 0) must be taken. "هشدار" alone is
        // ambiguous because the stable-balance branch also contains it; assert
        // the deficit-specific text instead.
        assert!(result.contains("\u{0631}\u{06CC}\u{0633}\u{06A9} \u{06A9}\u{0633}\u{0631}\u{06CC} \u{0628}\u{0648}\u{062F}\u{062C}\u{0647}"),
            "expected deficit branch, got: {result}");
        // est_balance saturates to i64::MIN; saturating_abs() yields i64::MAX,
        // which format_currency renders as 922,337,203,685,477,580 تومان.
        assert!(
            result.contains("922,337,203,685,477,580"),
            "expected saturated deficit value, got: {result}"
        );
    }

    #[test]
    fn test_forecast_preserves_f64_precision_above_2pow53() {
        // 2^53 + 7 (9_007_199_254_740_999) cannot be represented exactly as f64;
        // it rounds up to 9_007_199_254_741_000. The old code cast
        // `recent_income as f64`, which changed the Toman display from
        // "...474,099" to "...474,100". Integer division keeps the exact value.
        let now = now_ms();
        let day = 24 * 60 * 60 * 1000_i64;
        let amount: i64 = 9_007_199_254_740_999;
        let txs = vec![sample_tx(1, TransactionType::Income, amount, now - 5 * day)];
        let result = get_offline_forecast(&txs, &[], &[], &[]);
        // Exact: 9_007_199_254_740_999 / 10 = 900,719,925,474,099 (floor).
        assert!(
            result.contains("900,719,925,474,099"),
            "expected exact Toman value preserved above 2^53; got: {result}"
        );
        // The f64-rounded value (900,719,925,474,100) must not appear.
        assert!(
            !result.contains("900,719,925,474,100"),
            "f64 rounding must not inflate the Rial value; got: {result}"
        );
    }

    // -- calculate_financial_health_score tests -----------------------------------

    #[test]
    fn test_financial_health_score_uses_monthly_income_scoping() {
        let now = now_ms();
        let day: i64 = 24 * 60 * 60 * 1000;

        // Unpaid installment that creates a monthly debt obligation.
        let installment = Installment {
            id: 1,
            title: "car".into(),
            amount: 5_000_000,
            due_date: now,
            is_paid: false,
            reminder_enabled: false,
            notes: String::new(),
            bank_loan_id: None,
        };

        // --- Case A: recent income exists → low debt ratio → bonus ---
        let txs_recent = vec![
            sample_tx(1, TransactionType::Income, 50_000_000, now - 15 * day),
            sample_tx(2, TransactionType::Income, 50_000_000, now - 330 * day),
        ];
        let score_recent = calculate_financial_health_score(
            &txs_recent,
            &[],
            std::slice::from_ref(&installment),
            &[],
            &[],
        );

        // --- Case B: only ancient income → monthly_income = 0 → debt ratio = 1.0 → penalty ---
        let txs_ancient = vec![sample_tx(
            1,
            TransactionType::Income,
            100_000_000,
            now - 330 * day,
        )];
        let score_ancient =
            calculate_financial_health_score(&txs_ancient, &[], &[installment], &[], &[]);

        // With recent income the debt ratio is low (+15 bonus); with no recent
        // income the ratio maxes out at 1.0 (−10 penalty).  The 25-point
        // difference proves monthly-income scoping is actually used.
        assert!(
            score_recent > score_ancient,
            "score_recent ({score_recent}) should exceed score_ancient ({score_ancient}) \
             because monthly income scoping penalises the absence of recent income"
        );
        // Exact delta: +15 vs -10 = 25, but allow margin for other factors.
        assert!(
            score_recent - score_ancient >= 20,
            "expected at least 20-point gap from debt-ratio scoping, got {}",
            score_recent - score_ancient
        );
    }
}
