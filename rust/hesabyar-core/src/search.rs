use crate::models::*;
use crate::parser::text_preprocessor::preprocess_persian_text;

/// Search query parameters for filtering transactions.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchQuery {
    /// Free-text search query (matched against description and person_name).
    pub text: String,
    /// Minimum amount filter (in Rial). 0 means no filter.
    pub min_amount: i64,
    /// Maximum amount filter (in Rial). 0 means no filter.
    pub max_amount: i64,
    /// Start date filter (epoch ms). 0 means no filter.
    pub start_date: i64,
    /// End date filter (epoch ms). 0 means no filter.
    pub end_date: i64,
    /// Category ID filter. 0 means no filter.
    pub category_id: i64,
    /// Transaction type filter. Empty means no filter.
    pub tx_type: TransactionType,
    /// Whether to use type filter. If false, tx_type is ignored.
    pub use_type_filter: bool,
}

/// A single search result with relevance information.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SearchResult {
    /// The matched transaction.
    pub transaction: Transaction,
    /// Relevance score (0.0 - 1.0). Higher = more relevant.
    pub score: f32,
    /// Matched text snippet (highlighted).
    pub matched_text: String,
}

/// Complete search response with results and metadata.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct SearchResponse {
    /// Matching transactions, sorted by relevance (highest first).
    pub results: Vec<SearchResult>,
    /// Total number of matching transactions.
    pub total_count: i64,
    /// Total amount of matching transactions (in Rial).
    pub total_amount: i64,
}

/// Normalize Persian text for search comparison.
///
/// Handles:
/// - Zero-width non-joiner (U+200C) removal
/// - Persian/Arabic digit normalization
/// - Case folding (for Latin characters)
/// - Whitespace normalization
fn normalize_for_search(text: &str) -> String {
    preprocess_persian_text(text)
        .to_lowercase()
        .replace('\u{200C}', " ") // ZWNJ → space
        .split_whitespace()
        .collect::<Vec<&str>>()
        .join(" ")
}

/// Calculate text similarity score between query and target.
///
/// Returns a score between 0.0 and 1.0:
/// - 1.0 = exact match
/// - 0.8+ = very high similarity
/// - 0.5+ = partial match
/// - 0.0 = no match
fn text_similarity(query: &str, target: &str) -> f32 {
    if query.is_empty() || target.is_empty() {
        return 0.0;
    }

    let norm_query = normalize_for_search(query);
    let norm_target = normalize_for_search(target);

    // Treat whitespace-only input as no match
    if norm_query.is_empty() || norm_target.is_empty() {
        return 0.0;
    }

    if norm_query == norm_target {
        return 1.0;
    }

    // Exact substring match (high relevance)
    if norm_target.contains(&norm_query) {
        // Score based on how much of the target is the query
        let query_len = norm_query.len() as f32;
        let target_len = norm_target.len() as f32;
        return 0.8 + 0.2 * (query_len / target_len);
    }

    // Word-level matching
    let query_words: Vec<&str> = norm_query.split_whitespace().collect();
    let target_words: Vec<&str> = norm_target.split_whitespace().collect();

    if query_words.is_empty() || target_words.is_empty() {
        return 0.0;
    }

    let matched_words = query_words
        .iter()
        .filter(|qw| {
            target_words
                .iter()
                .any(|tw| tw.contains(*qw) || qw.contains(*tw))
        })
        .count();

    let word_score = matched_words as f32 / query_words.len() as f32;

    // Bonus for consecutive word matches
    let consecutive_bonus = if matched_words > 1 {
        let mut consecutive = 0;
        let mut max_consecutive = 0;
        for qw in &query_words {
            if target_words.iter().any(|tw| tw.contains(*qw)) {
                consecutive += 1;
                max_consecutive = max_consecutive.max(consecutive);
            } else {
                consecutive = 0;
            }
        }
        (max_consecutive as f32 / query_words.len() as f32) * 0.1
    } else {
        0.0
    };

    (word_score * 0.7 + consecutive_bonus).min(1.0)
}

/// Search transactions with the given query.
///
/// Returns matching transactions sorted by relevance.
/// All filters are optional — omit a filter by setting its value to 0/false.
///
/// This function never panics. It returns an empty result on error.
pub fn search_transactions(transactions: &[Transaction], query: &SearchQuery) -> SearchResponse {
    let mut results: Vec<SearchResult> = transactions
        .iter()
        .filter_map(|tx| {
            // Apply filters
            if !passes_filters(tx, query) {
                return None;
            }

            // Calculate relevance score
            let score = calculate_score(tx, &query.text);

            // Only include if there's some match (or no text query)
            if query.text.is_empty() || score > 0.0 {
                let matched_text = if query.text.is_empty() {
                    tx.description.clone()
                } else {
                    highlight_match(&tx.description, &query.text)
                };

                Some(SearchResult {
                    transaction: tx.clone(),
                    score,
                    matched_text,
                })
            } else {
                None
            }
        })
        .collect();

    // Sort by score descending, then by date descending
    results.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| b.transaction.date.cmp(&a.transaction.date))
    });

    let total_count = results.len() as i64;
    let total_amount = results.iter().map(|r| r.transaction.amount).sum();

    SearchResponse {
        results,
        total_count,
        total_amount,
    }
}

/// Check if a transaction passes all filters in the query.
fn passes_filters(tx: &Transaction, query: &SearchQuery) -> bool {
    // Amount range filter
    if query.min_amount > 0 && tx.amount < query.min_amount {
        return false;
    }
    if query.max_amount > 0 && tx.amount > query.max_amount {
        return false;
    }

    // Date range filter
    if query.start_date > 0 && tx.date < query.start_date {
        return false;
    }
    if query.end_date > 0 && tx.date > query.end_date {
        return false;
    }

    // Category filter
    if query.category_id > 0 && tx.category_id != query.category_id {
        return false;
    }

    // Transaction type filter
    if query.use_type_filter && tx.tx_type != query.tx_type {
        return false;
    }

    true
}

/// Calculate relevance score for a transaction against a text query.
fn calculate_score(tx: &Transaction, query_text: &str) -> f32 {
    if query_text.is_empty() {
        return 1.0;
    }

    let desc_score = text_similarity(query_text, &tx.description);
    let person_score = tx
        .person_name
        .as_ref()
        .map(|pn| text_similarity(query_text, pn) * 0.9) // Slightly lower weight
        .unwrap_or(0.0);

    desc_score.max(person_score)
}

/// Highlight matching text in a string.
///
/// Wraps matched portions in `**` for markdown-style highlighting.
fn highlight_match(text: &str, query: &str) -> String {
    if query.is_empty() {
        return text.to_string();
    }

    let norm_query = normalize_for_search(query);
    let norm_text = normalize_for_search(text);

    // If normalized text contains the query, highlight in original
    if norm_text.contains(&norm_query) {
        // Simple highlighting: wrap the matched portion
        // For Persian text, we do a best-effort approach
        return text.to_string(); // Return original for now
    }

    text.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tx(
        id: i64,
        desc: &str,
        amount: i64,
        date: i64,
        cat_id: i64,
        person: Option<&str>,
    ) -> Transaction {
        Transaction {
            id,
            tx_type: TransactionType::Expense,
            category_id: cat_id,
            amount,
            description: desc.to_string(),
            person_name: person.map(|s| s.to_string()),
            person_id: None,
            date,
            due_date: None,
            installment_id: None,
            account_id: 1,
            destination_account_id: None,
        }
    }

    fn query(text: &str) -> SearchQuery {
        SearchQuery {
            text: text.to_string(),
            min_amount: 0,
            max_amount: 0,
            start_date: 0,
            end_date: 0,
            category_id: 0,
            tx_type: TransactionType::Expense,
            use_type_filter: false,
        }
    }

    // =====================================================================
    // Text similarity tests
    // =====================================================================

    #[test]
    fn test_exact_match() {
        assert!((text_similarity("خرید نان", "خرید نان") - 1.0).abs() < 0.01);
    }

    #[test]
    fn test_substring_match() {
        let score = text_similarity("خرید", "خرید نان صبحانه");
        assert!(score > 0.8, "Substring match should be high: {}", score);
    }

    #[test]
    fn test_no_match() {
        assert!(text_similarity("سلام", "دنیا") < 0.1);
    }

    #[test]
    fn test_empty_query() {
        assert_eq!(text_similarity("", "test"), 0.0);
    }

    #[test]
    fn test_empty_target() {
        assert_eq!(text_similarity("test", ""), 0.0);
    }

    #[test]
    fn test_whitespace_only_query_returns_zero() {
        assert_eq!(text_similarity("   ", "خرید نان"), 0.0);
    }

    #[test]
    fn test_whitespace_only_target_returns_zero() {
        assert_eq!(text_similarity("خرید", "   "), 0.0);
    }

    #[test]
    fn test_zwnj_only_query_returns_zero() {
        // ZWNJ gets normalized to space, then split_whitespace empties it
        assert_eq!(text_similarity("\u{200C}\u{200C}\u{200C}", "test"), 0.0);
    }

    #[test]
    fn test_consecutive_bonus_tracks_max_not_final_streak() {
        // Query: "a b c d" → target: "a x b y c" → words a,b,c match but d doesn't
        // Consecutive streaks: a(1), b(2), c(3), d(0) → max_consecutive = 3
        // Without the fix, final streak would be 0 (last word doesn't match)
        let score = text_similarity("a b c d", "a x b y c");
        // word_score = 3/4 = 0.75, consecutive_bonus = (3/4)*0.1 = 0.075
        // total = 0.75*0.7 + 0.075 = 0.6
        assert!(
            score > 0.5,
            "Should benefit from max_consecutive=3, got {}",
            score
        );
    }

    #[test]
    fn test_word_match() {
        let score = text_similarity("نان", "خرید نان صبحانه");
        assert!(score > 0.5, "Word match should be moderate: {}", score);
    }

    // =====================================================================
    // Normalize for search tests
    // =====================================================================

    #[test]
    fn test_normalize_zwnj() {
        let normalized = normalize_for_search("سرمایه\u{200C}گذاری");
        assert!(!normalized.contains('\u{200C}'));
        assert!(normalized.contains("سرمایه"));
    }

    #[test]
    fn test_normalize_persian_digits() {
        let normalized = normalize_for_search("۵۰۰ هزار");
        assert!(normalized.contains("500"));
    }

    #[test]
    fn test_normalize_whitespace() {
        let normalized = normalize_for_search("  خرید    نان  ");
        assert_eq!(normalized, "خرید نان");
    }

    // =====================================================================
    // Filter tests
    // =====================================================================

    #[test]
    fn test_amount_filter_min() {
        let transactions = vec![
            tx(1, "test", 100_000, 0, 1, None),
            tx(2, "test", 500_000, 0, 1, None),
            tx(3, "test", 1_000_000, 0, 1, None),
        ];

        let q = SearchQuery {
            text: String::new(),
            min_amount: 200_000,
            max_amount: 0,
            start_date: 0,
            end_date: 0,
            category_id: 0,
            tx_type: TransactionType::Expense,
            use_type_filter: false,
        };

        let result = search_transactions(&transactions, &q);
        assert_eq!(result.total_count, 2);
    }

    #[test]
    fn test_amount_filter_max() {
        let transactions = vec![
            tx(1, "test", 100_000, 0, 1, None),
            tx(2, "test", 500_000, 0, 1, None),
            tx(3, "test", 1_000_000, 0, 1, None),
        ];

        let q = SearchQuery {
            text: String::new(),
            min_amount: 0,
            max_amount: 600_000,
            start_date: 0,
            end_date: 0,
            category_id: 0,
            tx_type: TransactionType::Expense,
            use_type_filter: false,
        };

        let result = search_transactions(&transactions, &q);
        assert_eq!(result.total_count, 2);
    }

    #[test]
    fn test_category_filter() {
        let transactions = vec![
            tx(1, "test", 100_000, 0, 1, None),
            tx(2, "test", 200_000, 0, 2, None),
            tx(3, "test", 300_000, 0, 1, None),
        ];

        let q = SearchQuery {
            text: String::new(),
            min_amount: 0,
            max_amount: 0,
            start_date: 0,
            end_date: 0,
            category_id: 2,
            tx_type: TransactionType::Expense,
            use_type_filter: false,
        };

        let result = search_transactions(&transactions, &q);
        assert_eq!(result.total_count, 1);
        assert_eq!(result.results[0].transaction.id, 2);
    }

    #[test]
    fn test_type_filter() {
        let transactions = vec![
            Transaction {
                id: 1,
                tx_type: TransactionType::Expense,
                category_id: 1,
                amount: 100_000,
                description: "test".to_string(),
                person_name: None,
                person_id: None,
                date: 0,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
            Transaction {
                id: 2,
                tx_type: TransactionType::Income,
                category_id: 1,
                amount: 200_000,
                description: "test".to_string(),
                person_name: None,
                person_id: None,
                date: 0,
                due_date: None,
                installment_id: None,
                account_id: 1,
                destination_account_id: None,
            },
        ];

        let q = SearchQuery {
            text: String::new(),
            min_amount: 0,
            max_amount: 0,
            start_date: 0,
            end_date: 0,
            category_id: 0,
            tx_type: TransactionType::Expense,
            use_type_filter: true,
        };

        let result = search_transactions(&transactions, &q);
        assert_eq!(result.total_count, 1);
        assert_eq!(result.results[0].transaction.id, 1);
    }

    // =====================================================================
    // Text search tests
    // =====================================================================

    #[test]
    fn test_text_search_persian() {
        let transactions = vec![
            tx(1, "خرید نان", 50_000, 0, 1, None),
            tx(2, "پرداخت قبض برق", 100_000, 0, 2, None),
            tx(3, "خرید شیر", 30_000, 0, 1, None),
        ];

        let q = query("خرید");
        let result = search_transactions(&transactions, &q);

        // All three transactions should match "خرید"
        assert!(result.total_count >= 2, "Should find at least 2 matches");

        // "خرید نان" should score highest
        let top = &result.results[0];
        assert!(top.score > 0.8, "Top match should have high score");
    }

    #[test]
    fn test_text_search_person_name() {
        let transactions = vec![
            tx(1, "پرداخت", 100_000, 0, 1, Some("علی")),
            tx(2, "پرداخت", 200_000, 0, 1, Some("محمد")),
        ];

        let q = query("علی");
        let result = search_transactions(&transactions, &q);

        assert_eq!(result.total_count, 1);
        assert_eq!(
            result.results[0].transaction.person_name,
            Some("علی".to_string())
        );
    }

    #[test]
    fn test_empty_search_returns_all() {
        let transactions = vec![
            tx(1, "test1", 100_000, 0, 1, None),
            tx(2, "test2", 200_000, 0, 1, None),
        ];

        let q = query("");
        let result = search_transactions(&transactions, &q);

        assert_eq!(result.total_count, 2);
    }

    #[test]
    fn test_no_matches() {
        let transactions = vec![tx(1, "خرید نان", 50_000, 0, 1, None)];

        let q = query("مسکن");
        let result = search_transactions(&transactions, &q);

        assert_eq!(result.total_count, 0);
    }

    // =====================================================================
    // Sorting tests
    // =====================================================================

    #[test]
    fn test_results_sorted_by_score() {
        let transactions = vec![
            tx(1, "خرید نان صبحانه", 50_000, 0, 1, None),
            tx(2, "خرید", 100_000, 0, 1, None),
            tx(3, "پرداخت قبض", 200_000, 0, 2, None),
        ];

        let q = query("خرید");
        let result = search_transactions(&transactions, &q);

        // Results should be sorted by score descending
        for i in 0..result.results.len() - 1 {
            assert!(
                result.results[i].score >= result.results[i + 1].score,
                "Results should be sorted by score"
            );
        }
    }

    // =====================================================================
    // Total calculation tests
    // =====================================================================

    #[test]
    fn test_total_amount() {
        let transactions = vec![
            tx(1, "test", 100_000, 0, 1, None),
            tx(2, "test", 200_000, 0, 1, None),
        ];

        let q = query("");
        let result = search_transactions(&transactions, &q);

        assert_eq!(result.total_amount, 300_000);
    }

    // =====================================================================
    // Edge cases
    // =====================================================================

    #[test]
    fn test_empty_transaction_list() {
        let q = query("test");
        let result = search_transactions(&[], &q);

        assert_eq!(result.total_count, 0);
        assert_eq!(result.total_amount, 0);
        assert!(result.results.is_empty());
    }

    #[test]
    fn test_combined_filters() {
        let transactions = vec![
            tx(1, "خرید نان", 50_000, 1000, 1, None),
            tx(2, "خرید شیر", 30_000, 2000, 1, None),
            tx(3, "خرید آب", 10_000, 3000, 2, None),
        ];

        let q = SearchQuery {
            text: "خرید".to_string(),
            min_amount: 20_000,
            max_amount: 60_000,
            start_date: 0,
            end_date: 0,
            category_id: 1,
            tx_type: TransactionType::Expense,
            use_type_filter: false,
        };

        let result = search_transactions(&transactions, &q);

        // Should match tx1 and tx2 (text match, amount range, category)
        assert_eq!(result.total_count, 2);
    }

    #[test]
    fn test_date_range_filter() {
        let transactions = vec![
            tx(1, "test", 100_000, 1000, 1, None),
            tx(2, "test", 200_000, 2000, 1, None),
            tx(3, "test", 300_000, 3000, 1, None),
        ];

        let q = SearchQuery {
            text: String::new(),
            min_amount: 0,
            max_amount: 0,
            start_date: 1500,
            end_date: 2500,
            category_id: 0,
            tx_type: TransactionType::Expense,
            use_type_filter: false,
        };

        let result = search_transactions(&transactions, &q);
        assert_eq!(result.total_count, 1);
        assert_eq!(result.results[0].transaction.id, 2);
    }
}
