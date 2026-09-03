package io.github.mojri.hesabyar.domain.usecase

import android.util.Log
import io.github.mojri.hesabyar.BuildConfig
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.BackupSettings
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.DEFAULT_ACCOUNT_ID
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer
import io.github.mojri.hesabyar.rust.RustBridge
import io.github.mojri.hesabyar.rust.RustMappers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "BackupJsonParser"

/**
 * Parses serialized backup JSON into [BackupPayload]s. Prefers the Rust parser
 * and falls back to the Kotlin parser when Rust is unavailable or a mapping
 * fails; all array-parsing helpers are file-private so the class stays small.
 */
class BackupJsonParser(
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
  suspend fun parseBackupJson(jsonString: String): BackupPayload? =
    withContext(dispatcher) {
      val rustResult = RustBridge.parseBackupJsonSync(jsonString)
      if (rustResult != null) {
        try {
          val rootJson = parseRawJson(jsonString)
          BackupPayload(
            version = rustResult.version,
            timestamp = rustResult.timestamp,
            appVersion = rustResult.appVersion,
            transactions = rustResult.transactions.map { RustMappers.fromRustTransaction(it) },
            loans = rustResult.loans.map { RustMappers.fromRustLoan(it) },
            installments = rustResult.installments.map { RustMappers.fromRustInstallment(it) },
            paymentHistories = rustResult.paymentHistories.map { RustMappers.fromRustPaymentHistory(it) },
            categories = rustResult.categories.map { RustMappers.fromRustCategory(it) },
            bankLoans = rustResult.bankLoans.map { RustMappers.fromRustBankLoan(it) },
            accounts = rustResult.accounts.map { RustMappers.fromRustAccount(it) },
            persons = RustMappers.fromRustPersons(rustResult.persons),
            settings = parseSettings(rootJson)
          )
        } catch (e: IllegalArgumentException) {
          // Malformed/outdated enum strings — fall back to Kotlin-only parsing
          Log.w(TAG, "Rust→Kotlin mapping failed, falling back to Kotlin parser", e)
          parseBackupJsonKotlin(jsonString)
        }
      } else {
        // Rust unavailable — fall back to Kotlin-only JSON parsing
        parseBackupJsonKotlin(jsonString)
      }
    }

  @Suppress("TooGenericExceptionCaught") // Safety net: opt* accessors can still NPE on malformed JSON; return null
  private fun parseBackupJsonKotlin(jsonString: String): BackupPayload? {
    val root = parseRawJson(jsonString) ?: return null
    return try {
      BackupPayload(
        version = root.optInt("version", BuildConfig.BACKUP_SCHEMA_VERSION),
        timestamp = root.optLong("timestamp", System.currentTimeMillis()),
        appVersion = root.optString("appVersion", BuildConfig.VERSION_NAME),
        transactions = parseTransactions(root),
        loans = parseLoans(root),
        installments = parseInstallmentsFromJson(root),
        paymentHistories = parsePaymentHistories(root),
        categories = parseCategories(root),
        bankLoans = parseBankLoansFromJson(root),
        accounts = parseAccountsFromJson(root),
        persons = parsePersons(root),
        settings = parseSettings(root)
      )
    } catch (e: NumberFormatException) {
      Log.w(TAG, "Kotlin backup parse: malformed number in backup JSON", e)
      null
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "Kotlin backup parse: invalid enum value", e)
      null
    } catch (e: org.json.JSONException) {
      Log.w(TAG, "Kotlin backup parse: malformed JSON structure", e)
      null
    } catch (e: NullPointerException) {
      // Safety net: opt* methods return defaults but constructor params may still NPE
      Log.w(TAG, "Kotlin backup parse: null field in backup JSON", e)
      null
    }
  }

  private fun parseTransactions(root: JSONObject): List<Transaction> =
    root.optJSONArray("transactions")?.let { arr ->
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val type = parseType(o, TransactionType.EXPENSE)
        Transaction(
          id = o.optLong("id", 0L),
          type = type,
          categoryId = o.optLong("categoryId", 0L),
          amount = o.optLong("amount", 0L),
          description = o.optString("description", ""),
          personName = o.optString("personName", "").ifBlank { null },
          personId =
            if (o.has("personId") && !o.isNull("personId")) o.optLong("personId").takeIf { it != 0L } else null,
          date = o.optLong("date", 0L),
          dueDate = o.optLong("dueDate", 0L).takeIf { it != 0L },
          installmentId = o.optLong("installmentId", 0L).takeIf { it != 0L },
          accountId = o.optLong("accountId", DEFAULT_ACCOUNT_ID),
          destinationAccountId =
            if (o.has("destinationAccountId") && !o.isNull("destinationAccountId")) {
              o.optLong("destinationAccountId")
            } else {
              null
            }
        )
      }
    } ?: emptyList()

  private fun parseLoans(root: JSONObject): List<Loan> =
    root.optJSONArray("loans")?.let { arr ->
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val type = parseType(o, LoanType.CREDITOR)
        Loan(
          id = o.optLong("id", 0L),
          personName = o.optString("personName", ""),
          personId =
            if (o.has("personId") && !o.isNull("personId")) o.optLong("personId").takeIf { it != 0L } else null,
          type = type,
          originalAmount = o.optLong("originalAmount", 0L),
          remainingAmount = o.optLong("remainingAmount", 0L),
          description = o.optString("description", ""),
          date = o.optLong("date", 0L),
          isSettled = o.optBoolean("isSettled", false)
        )
      }
    } ?: emptyList()

  private fun parseInstallmentsFromJson(root: JSONObject): List<Installment> =
    root.optJSONArray("installments")?.let { arr ->
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        Installment(
          id = o.optLong("id", 0L),
          title = o.optString("title", ""),
          amount = o.optLong("amount", 0L),
          dueDate = o.optLong("dueDate", 0L),
          isPaid = o.optBoolean("isPaid", false),
          reminderEnabled = o.optBoolean("reminderEnabled", true),
          notes = o.optString("notes", ""),
          bankLoanId =
            if (o.has("bankLoanId") && !o.isNull("bankLoanId")) o.optLong("bankLoanId") else null
        )
      }
    } ?: emptyList()

  private fun parseCategories(root: JSONObject): List<Category> =
    root.optJSONArray("categories")?.let { arr ->
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val type = parseType(o, CategoryType.EXPENSE)
        Category(
          id = o.optLong("id", 0L),
          name = o.optString("name", ""),
          key = o.optString("key", ""),
          icon = o.optString("icon", ""),
          color = o.optLong("color", 0L),
          type = type,
          isDefault = o.optBoolean("isDefault", false)
        )
      }
    } ?: emptyList()

  private fun parsePaymentHistories(rootJson: JSONObject?): List<PaymentHistory> {
    val arr = rootJson?.optJSONArray("paymentHistories") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
      val obj = arr.optJSONObject(i) ?: return@mapNotNull null
      PaymentHistory(
        id = obj.optLong("id", 0L),
        loanId = obj.optLong("loanId", 0L),
        amount = obj.optLong("amount", 0L),
        date = obj.optLong("date", System.currentTimeMillis()),
        notes = obj.optString("notes", "")
      )
    }
  }

  private fun parseBankLoansFromJson(root: JSONObject): List<BankLoan> =
    root.optJSONArray("bankLoans")?.let { arr ->
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        BankLoan(
          id = o.optLong("id", 0L),
          bankName = o.optString("bankName", ""),
          loanName = o.optString("loanName", ""),
          receivedAmount = o.optLong("receivedAmount", 0L),
          monthlyInstallmentAmount = o.optLong("monthlyInstallmentAmount", 0L),
          numberOfInstallments = o.optInt("numberOfInstallments", 0),
          totalRepayableAmount = o.optLong("totalRepayableAmount", 0L),
          totalInterest = o.optLong("totalInterest", 0L),
          startDate = o.optLong("startDate", 0L),
          description = o.optString("description", ""),
          isSettled = o.optBoolean("isSettled", false)
        )
      }
    } ?: emptyList()

  private fun parsePersons(root: JSONObject): List<Person> =
    root.optJSONArray("persons")?.let { arr ->
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        // Recompute normalizedName when the backup omits it or carries an
        // empty/stale value. The persons table has a UNIQUE NOT NULL index on
        // normalizedName (AppDatabase.kt:221-225, Entities.kt:91-98) and
        // PersonDao.insertPerson uses OnConflictStrategy.IGNORE — a blank
        // normalizedName would collapse every malformed person onto the same
        // unique key and be silently dropped. Mirrors the runtime invariant
        // (HesabyarRepository.upsertPerson requires a non-empty key); rows
        // whose name also normalizes to empty are skipped (defense in depth).
        val rawName = o.optString("name", "")
        // Always derive the dedup key from the canonical name form. Never trust a
        // supplied normalizedName: a mismatched value (name="Ali",
        // normalizedName="reza") would bind Ali's records to Reza's identity and
        // survive the round-trip because the restore path also derives the key
        // from name. The persons table UNIQUE index on normalizedName plus
        // PersonDao.IGNORE silently drops an empty key, so skip unnormalizable names.
        val display = PersonNameNormalizer.displayForm(rawName)
        val key = PersonNameNormalizer.normalize(display)
        require(key.isNotEmpty()) { "Person id=${o.optLong("id", 0L)} normalizes to empty (name=\"${rawName}\")" }
        Person(
          id = o.optLong("id", 0L),
          name = rawName,
          normalizedName = key,
          phone = o.nullableString("phone"),
          notes = o.nullableString("notes"),
          createdAt = o.optLong("createdAt", 0L).takeIf { it != 0L } ?: System.currentTimeMillis(),
          isArchived = o.optBoolean("isArchived", false)
        )
      }
    } ?: emptyList()

  private fun parseAccountsFromJson(root: JSONObject): List<AccountEntity> =
    root.optJSONArray("accounts")?.let { arr ->
      val now = System.currentTimeMillis()
      (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        AccountEntity(
          id = o.optLong("id", 0L),
          name = o.optString("name", ""),
          type = parseAccountType(o),
          bankName = o.nullableString("bankName"),
          cardNumber = o.nullableString("cardNumber"),
          accountNumber = o.nullableString("accountNumber"),
          iban = o.nullableString("iban"),
          initialBalance = o.optLong("initialBalance", 0L),
          color = o.optLong("color", AccountEntity.DEFAULT_COLOR),
          icon = o.nullableString("icon"),
          isArchived = o.optBoolean("isArchived", false),
          displayOrder = o.optInt("displayOrder", 0),
          createdAt = o.optLong("createdAt", now),
          updatedAt = o.optLong("updatedAt", now)
        )
      }
    } ?: emptyList()
}

private fun parseRawJson(jsonString: String): JSONObject? =
  try {
    JSONObject(jsonString)
  } catch (e: org.json.JSONException) {
    Log.w(TAG, "parseRawJson: malformed JSON input", e)
    null
  }

private fun parseSettings(rootJson: JSONObject?): BackupSettings {
  val obj = rootJson?.optJSONObject("settings") ?: return BackupSettings()
  return BackupSettings(darkMode = obj.optBoolean("darkMode", true))
}

/** Absent type → BANK (backward compat); present-but-unknown → OTHER via safeValueOf. */
private fun parseAccountType(obj: JSONObject): AccountType {
  val typeStr = obj.optString("type", "")
  return if (typeStr.isEmpty()) {
    AccountType.BANK
  } else {
    AccountType.safeValueOf(typeStr)
  }
}

/** Returns [JSONObject.NULL] as Kotlin null, or the string value if present and non-null. */
private fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

private inline fun <reified T : Enum<T>> parseType(
  obj: JSONObject,
  default: T
): T {
  val typeStr = obj.optString("type", "")
  if (typeStr.isEmpty()) return default
  return try {
    enumValueOf<T>(typeStr)
  } catch (_: IllegalArgumentException) {
    default
  }
}
