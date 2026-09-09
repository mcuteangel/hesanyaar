package io.github.mojri.hesabyar.domain.usecase

import io.github.mojri.hesabyar.BuildConfig
import io.github.mojri.hesabyar.auth.BackupCipher
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.BankLoan
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.HesabyarRepositoryInterface
import io.github.mojri.hesabyar.data.Installment
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.PaymentHistory
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import kotlinx.coroutines.flow.firstOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey

/** JSON key for the passphrase-encryption metadata block in a backup envelope. */
internal const val ENCRYPTION_KEY = "sensitiveFieldsEncryption"

/** JSON key for the PBKDF2 salt inside the encryption metadata block. */
internal const val SALT_KEY = "salt"

/** JSON key for the PBKDF2 iteration count inside the encryption metadata block. */
internal const val ITERATIONS_KEY = "iterations"

/**
 * Restore-side floor for the declared PBKDF2 work factor. The export side always
 * writes [BackupCipher.PBKDF2_ITERATIONS] (600k), but a tampered/attacker-crafted
 * backup could declare a tiny count (e.g. 1) to force a fast brute-force of the
 * passphrase — reject anything below this floor instead of deriving a weak key.
 */
internal const val MIN_ITERATIONS_FLOOR = 100_000

/**
 * Restore-side ceiling for the declared PBKDF2 work factor, mirroring [MIN_ITERATIONS_FLOOR].
 * The export side always writes [BackupCipher.PBKDF2_ITERATIONS] (600k), so a
 * tampered/attacker-crafted backup could declare an absurd count (e.g. Int.MAX_VALUE)
 * to make PBKDF2 block the crypto thread for minutes/hours on decrypt (DoS/ANR).
 * Reject anything above this ceiling so derivation fails fast instead of hanging.
 * The value is well above the app's own 600k, leaving headroom for legitimate
 * future increases of the app-side work factor.
 */
internal const val MAX_ITERATIONS_CEILING = 5_000_000

/**
 * Serializes the repository contents into the backup JSON envelope, optionally
 * encrypting sensitive banking identifiers with a passphrase-derived key, and
 * builds the human-readable export/restore summary strings.
 */
class BackupPayloadExporter(
  private val repository: HesabyarRepositoryInterface
) {
  // TODO(automatic-backups): Background/automatic backups cannot prompt for a passphrase
  // interactively. When automatic backups are implemented, they will need either a persisted
  // passphrase (protected via EncryptedSharedPreferences) or a default no-encryption fallback,
  // since the user cannot be prompted during a headless export.
  suspend fun exportBackupJson(
    isDarkMode: Boolean = true,
    passphrase: String? = null
  ): JSONObject {
    val rootJson = JSONObject()
    rootJson.put("version", BuildConfig.BACKUP_SCHEMA_VERSION)
    rootJson.put("timestamp", System.currentTimeMillis())
    rootJson.put("appVersion", BuildConfig.VERSION_NAME)

    // Derive encryption key if passphrase is provided
    val encryptionKey =
      if (passphrase != null) {
        val salt = BackupCipher.generateSalt()
        rootJson.put(
          ENCRYPTION_KEY,
          JSONObject().apply {
            put(SALT_KEY, salt)
            put(ITERATIONS_KEY, BackupCipher.PBKDF2_ITERATIONS)
          }
        )
        BackupCipher.deriveKey(passphrase, salt)
      } else {
        null
      }

    rootJson.put(
      "settings",
      JSONObject().apply {
        put("darkMode", isDarkMode)
      }
    )

    val curCategories = repository.allCategories.firstOrNull() ?: emptyList()
    val curTrans = repository.allTransactions.firstOrNull() ?: emptyList()
    val curLoans = repository.allLoans.firstOrNull() ?: emptyList()
    val curInstallments = repository.allInstallments.firstOrNull() ?: emptyList()
    val curBankLoans = repository.allBankLoans.firstOrNull() ?: emptyList()
    val allPayments = repository.getAllPaymentHistories()
    val curAccounts = repository.allAccounts.firstOrNull() ?: emptyList()
    val curPersons = repository.getAllPersonsIncludingArchived()

    rootJson.put("categories", buildCategoriesArray(curCategories))

    rootJson.put("transactions", buildTransactionsArray(curTrans))

    rootJson.put("loans", buildLoansArray(curLoans))

    rootJson.put("installments", buildInstallmentsArray(curInstallments))

    rootJson.put("bankLoans", buildBankLoansArray(curBankLoans))

    rootJson.put("paymentHistories", buildPaymentHistoriesArray(allPayments))

    rootJson.put("accounts", buildAccountsArray(curAccounts, encryptionKey))

    rootJson.put("persons", buildPersonsArray(curPersons, encryptionKey))

    return rootJson
  }

  private fun buildCategoriesArray(categories: List<Category>): JSONArray {
    val catArray = JSONArray()
    categories.forEach {
      catArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("name", it.name)
          put("key", it.key)
          put("icon", it.icon)
          put("color", it.color)
          put("type", it.type.name)
          put("isDefault", it.isDefault)
        }
      )
    }
    return catArray
  }

  private fun buildTransactionsArray(transactions: List<Transaction>): JSONArray {
    val transArray = JSONArray()
    transactions.forEach {
      transArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("type", it.type.name)
          put("categoryId", it.categoryId)
          put("amount", it.amount)
          put("description", it.description)
          put("personName", it.personName ?: "")
          put("personId", it.personId ?: JSONObject.NULL)
          put("date", it.date)
          put("dueDate", it.dueDate ?: 0L)
          put("installmentId", it.installmentId ?: 0L)
          put("accountId", it.accountId)
          put("destinationAccountId", it.destinationAccountId ?: JSONObject.NULL)
        }
      )
    }
    return transArray
  }

  private fun buildLoansArray(loans: List<Loan>): JSONArray {
    val loansArray = JSONArray()
    loans.forEach {
      loansArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("personName", it.personName)
          put("personId", it.personId ?: JSONObject.NULL)
          put("type", it.type.name)
          put("originalAmount", it.originalAmount)
          put("remainingAmount", it.remainingAmount)
          put("description", it.description)
          put("date", it.date)
          put("isSettled", it.isSettled)
        }
      )
    }
    return loansArray
  }

  private fun buildInstallmentsArray(installments: List<Installment>): JSONArray {
    val instArray = JSONArray()
    installments.forEach {
      instArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("title", it.title)
          put("amount", it.amount)
          put("dueDate", it.dueDate)
          put("isPaid", it.isPaid)
          put("reminderEnabled", it.reminderEnabled)
          put("notes", it.notes)
          put("bankLoanId", it.bankLoanId ?: JSONObject.NULL)
        }
      )
    }
    return instArray
  }

  private fun buildBankLoansArray(bankLoans: List<BankLoan>): JSONArray {
    val bankLoansArray = JSONArray()
    bankLoans.forEach {
      bankLoansArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("bankName", it.bankName)
          put("loanName", it.loanName)
          put("receivedAmount", it.receivedAmount)
          put("monthlyInstallmentAmount", it.monthlyInstallmentAmount)
          put("numberOfInstallments", it.numberOfInstallments)
          put("totalRepayableAmount", it.totalRepayableAmount)
          put("totalInterest", it.totalInterest)
          put("startDate", it.startDate)
          put("description", it.description)
          put("isSettled", it.isSettled)
        }
      )
    }
    return bankLoansArray
  }

  private fun buildPaymentHistoriesArray(payments: List<PaymentHistory>): JSONArray {
    val paymentsArray = JSONArray()
    payments.forEach {
      paymentsArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("loanId", it.loanId)
          put("amount", it.amount)
          put("date", it.date)
          put("notes", it.notes)
        }
      )
    }
    return paymentsArray
  }

  private fun buildPersonsArray(
    persons: List<Person>,
    encryptionKey: SecretKey? = null
  ): JSONArray {
    val personsArray = JSONArray()
    persons.forEach {
      personsArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("name", it.name)
          put("normalizedName", it.normalizedName)
          if (encryptionKey != null) {
            put(
              "phone",
              BackupCipher.encryptOrNull(
                it.phone,
                encryptionKey,
                BackupCipher.personFieldAad(it.id, "phone")
              )
            )
            put(
              "notes",
              BackupCipher.encryptOrNull(
                it.notes,
                encryptionKey,
                BackupCipher.personFieldAad(it.id, "notes")
              )
            )
          } else {
            put("phone", it.phone ?: JSONObject.NULL)
            put("notes", it.notes ?: JSONObject.NULL)
          }
          put("createdAt", it.createdAt)
          put("isArchived", it.isArchived)
        }
      )
    }
    return personsArray
  }

  private fun buildAccountsArray(
    accounts: List<AccountEntity>,
    encryptionKey: SecretKey?
  ): JSONArray {
    val accountsArray = JSONArray()
    accounts.forEach {
      accountsArray.put(
        JSONObject().apply {
          put("id", it.id)
          put("name", it.name)
          put("type", it.type.name)
          put("bankName", it.bankName ?: JSONObject.NULL)
          // When a passphrase is provided, encrypt sensitive banking identifiers;
          // otherwise store them as plaintext.  Encrypted values are base64-encoded
          // AES-GCM ciphertext and pass through Rust/serde deserialization as-is
          // (they're still Option<String>).
          if (encryptionKey != null) {
            put(
              "cardNumber",
              BackupCipher.encryptOrNull(
                it.cardNumber,
                encryptionKey,
                BackupCipher.accountFieldAad(it.id, "cardNumber")
              )
            )
            put(
              "accountNumber",
              BackupCipher.encryptOrNull(
                it.accountNumber,
                encryptionKey,
                BackupCipher.accountFieldAad(it.id, "accountNumber")
              )
            )
            put(
              "iban",
              BackupCipher.encryptOrNull(
                it.iban,
                encryptionKey,
                BackupCipher.accountFieldAad(it.id, "iban")
              )
            )
          } else {
            put("cardNumber", it.cardNumber ?: JSONObject.NULL)
            put("accountNumber", it.accountNumber ?: JSONObject.NULL)
            put("iban", it.iban ?: JSONObject.NULL)
          }
          put("initialBalance", it.initialBalance)
          put("color", it.color)
          put("icon", it.icon ?: JSONObject.NULL)
          put("isArchived", it.isArchived)
          put("displayOrder", it.displayOrder)
          put("createdAt", it.createdAt)
          put("updatedAt", it.updatedAt)
        }
      )
    }
    return accountsArray
  }
}
