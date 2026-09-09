package io.github.mojri.hesabyar.domain.usecase

import android.content.Context
import io.github.mojri.hesabyar.auth.BackupCipher
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.BackupValidationResult
import io.github.mojri.hesabyar.data.HesabyarRepositoryInterface
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.RestoreMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/**
 * Facade over the backup domain. Parsing, validation, export serialization and
 * summary strings live in [BackupJsonParser], [BackupJsonValidator] and
 * [BackupPayloadExporter]; this class keeps the public API stable for callers
 * (BackupViewModel, tests) while delegating to those collaborators.
 */
class ManageBackupUseCase(
  private val repository: HesabyarRepositoryInterface,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val application: Context? = null
) {
  companion object {
    private const val TAG = "ManageBackupUseCase"

    /**
     * Returns true if the backup JSON indicates that sensitive banking fields
     * (cardNumber, accountNumber, iban) are encrypted with a passphrase.
     */
    fun isEncryptedBackup(rootJson: JSONObject): Boolean = rootJson.has(ENCRYPTION_KEY)

    /**
     * Extracts the PBKDF2 salt from the encryption metadata in the backup JSON.
     * @return the hex-encoded salt string, or null if no encryption metadata is
     *   present or the salt field is absent/empty — org.json's `optString` falls
     *   back to "" for an absent key, so an explicit empty check is needed for
     *   the `?:` guard at the decrypt call site to fire
     */
    fun getEncryptionSalt(rootJson: JSONObject): String? =
      rootJson
        .optJSONObject(ENCRYPTION_KEY)
        ?.optString(SALT_KEY)
        ?.takeIf { it.isNotEmpty() }

    /**
     * Extracts the PBKDF2 iteration count from the encryption metadata in the backup JSON.
     *
     * @return the declared iteration count, or [BackupCipher.PBKDF2_ITERATIONS] when the
     *   field is absent (defensive fallback — every encrypted backup written by this app
     *   includes it, but a foreign or hand-edited backup may not)
     * @throws IllegalArgumentException if the declared count is below [MIN_ITERATIONS_FLOOR]
     *   or above [MAX_ITERATIONS_CEILING], so a tampered backup cannot force a weak
     *   key derivation on the low end or a hang (DoS/ANR) on the high end
     */
    fun getEncryptionIterations(rootJson: JSONObject): Int {
      val iterations =
        rootJson
          .optJSONObject(ENCRYPTION_KEY)
          ?.optInt(ITERATIONS_KEY, BackupCipher.PBKDF2_ITERATIONS)
          ?: BackupCipher.PBKDF2_ITERATIONS
      require(iterations >= MIN_ITERATIONS_FLOOR) {
        "Backup declares PBKDF2 iteration count $iterations, below the minimum allowed floor $MIN_ITERATIONS_FLOOR"
      }
      require(iterations <= MAX_ITERATIONS_CEILING) {
        "Backup declares PBKDF2 iteration count $iterations, above the maximum allowed ceiling $MAX_ITERATIONS_CEILING"
      }
      return iterations
    }
  }

  private val parser = BackupJsonParser(dispatcher)
  private val validator = BackupJsonValidator(dispatcher, application)
  private val exporter = BackupPayloadExporter(repository)

  /**
   * Decrypts the passphrase-protected fields of a parsed [BackupPayload]: the
   * banking identifiers of every account (cardNumber, accountNumber, iban) and
   * the PII of every person (phone, notes). The raw JSON is re-read to obtain
   * the encrypted values and [passphrase] derives the decryption key.
   *
   * This method uses the same parsing path (Rust or Kotlin) that [parseBackupJson]
   * originally used — the parsed [backup] was produced by [parseBackupJson] and the
   * raw JSON is only re-read to obtain the encrypted field values, rather than
   * introducing a second independent parser.
   *
   * Raw JSON entries are matched to parsed rows by their stable `id` field
   * (present in both the serialized JSON and [io.github.mojri.hesabyar.data.AccountEntity]),
   * NOT by positional index. Index-based matching could attach ciphertext to the
   * wrong row if a raw entry is missing or the array is reordered; id matching
   * makes that impossible. Any malformed raw entry, duplicate id, or parsed row
   * with no raw counterpart fails loudly instead of returning misaligned data.
   *
   * A missing or empty `accounts`/`persons` array is rejected when the parsed
   * payload holds rows of that kind: the export path always writes both arrays,
   * so on an encrypted backup that can only mean the payload was stripped or
   * truncated, and passing the rows through would restore raw ciphertext.
   *
   * @throws GeneralSecurityException if the passphrase is wrong or the ciphertext is tampered
   * @throws IllegalArgumentException if the encrypted data is malformed
   * @throws IllegalStateException if a raw array cannot be matched 1:1 with the
   *   parsed rows by id, or is missing/empty while parsed rows of that kind exist
   */
  suspend fun decryptBackupWithPassphrase(
    backup: BackupPayload,
    rootJson: JSONObject,
    passphrase: String,
    dispatcher: CoroutineDispatcher = this.dispatcher
  ): BackupPayload =
    withContext(dispatcher) {
      val salt =
        getEncryptionSalt(rootJson)
          ?: throw IllegalArgumentException("Backup does not contain encryption metadata")
      val key = BackupCipher.deriveKey(passphrase, salt, getEncryptionIterations(rootJson))
      val decryptedAccounts = decryptAccounts(backup, rootJson, key)
      val decryptedPersons = decryptPersons(backup, rootJson, key)
      backup.copy(accounts = decryptedAccounts, persons = decryptedPersons)
    }

  /**
   * Decrypts cardNumber/accountNumber/iban for every account in [backup], reading
   * the ciphertext from the matching entry in [rootJson].
   *
   * Raw entries are matched by the stable `id` field, never by array position: a
   * reordered or partially stripped array must not attach one account's
   * ciphertext to another account. A malformed raw entry, a duplicate id, or a
   * parsed account with no raw counterpart therefore fails loudly.
   *
   * The export path always writes the `accounts` array, so on an encrypted backup
   * a missing or empty array can only mean the payload was stripped or truncated.
   * Returning the parsed accounts untouched there would restore raw ciphertext
   * into the accounts table, so that case is rejected instead.
   */
  private fun decryptAccounts(
    backup: BackupPayload,
    rootJson: JSONObject,
    key: SecretKey
  ): List<AccountEntity> {
    val accountsArray = rootJson.optJSONArray("accounts")
    if (accountsArray == null || accountsArray.length() == 0) {
      if (backup.accounts.isNotEmpty()) {
        throw IllegalStateException(
          "Parsed accounts have no counterpart in encrypted backup (missing or empty accounts array)"
        )
      }
      return backup.accounts
    }
    val encryptedById = indexRawEntriesById(accountsArray, "Account")
    return backup.accounts.map { account ->
      val raw =
        encryptedById[account.id]
          ?: throw IllegalStateException(
            "Parsed account ${account.id} has no counterpart in encrypted backup"
          )
      account.copy(
        cardNumber = decryptAccountField(account, raw, key, "cardNumber"),
        accountNumber = decryptAccountField(account, raw, key, "accountNumber"),
        iban = decryptAccountField(account, raw, key, "iban")
      )
    }
  }

  /**
   * Decrypts the PII fields (phone/notes) for every person in [backup], reading
   * the ciphertext from the matching entry in [rootJson].
   *
   * Mirrors [decryptAccounts]: matching is by the stable `id` field, every raw
   * entry is validated, and a parsed person with no raw counterpart fails loudly.
   *
   * The export path always writes the `persons` array, so on an encrypted backup
   * a missing or empty array means the payload was stripped or truncated. The
   * parsed persons hold the raw base64 ciphertext in `phone`/`notes`, so
   * returning them untouched would restore ciphertext as if it were plaintext.
   */
  private fun decryptPersons(
    backup: BackupPayload,
    rootJson: JSONObject,
    key: SecretKey
  ): List<Person> {
    val personsArray = rootJson.optJSONArray("persons")
    if (personsArray == null || personsArray.length() == 0) {
      if (backup.persons.isNotEmpty()) {
        throw IllegalStateException(
          "Parsed persons have no counterpart in encrypted backup (missing or empty persons array)"
        )
      }
      return backup.persons
    }
    val encryptedById = indexRawEntriesById(personsArray, "Person")
    return backup.persons.map { person ->
      val raw =
        encryptedById[person.id]
          ?: throw IllegalStateException(
            "Parsed person ${person.id} has no counterpart in encrypted backup"
          )
      // No `?: person.phone` / `?: person.notes` fallback here. In an encrypted
      // backup the parsed value is the ciphertext itself, so falling back to it
      // would write ciphertext into the column. A present value that fails the
      // AES-GCM tag check never reaches this point either — decryptOrNull lets
      // that exception propagate instead of returning null.
      person.copy(
        phone = decryptPersonField(person, raw, key, "phone"),
        notes = decryptPersonField(person, raw, key, "notes")
      )
    }
  }

  /**
   * Indexes raw JSON backup entries (accounts or persons) by their stable `id`.
   *
   * Every entry must be a JSON object with a positive `id` and no duplicate id;
   * anything else fails loudly instead of silently misaligning ciphertext onto
   * the wrong parsed row. The account and person decryption paths share this
   * exact validation, so it lives in one place.
   */
  @Suppress("LoopWithTooManyJumpStatements", "ThrowsCount")
  private fun indexRawEntriesById(
    array: JSONArray,
    kind: String
  ): Map<Long, JSONObject> {
    val byId = HashMap<Long, JSONObject>(array.length() * 2)
    for (i in 0 until array.length()) {
      val o =
        array.optJSONObject(i)
          ?: throw IllegalStateException(
            "$kind entry #$i in encrypted backup is not a JSON object"
          )
      if (!o.has("id")) {
        throw IllegalStateException("$kind entry #$i in encrypted backup has no id field")
      }
      val id = o.optLong("id", -1L)
      if (id <= 0L) {
        throw IllegalStateException(
          "$kind entry #$i in encrypted backup has invalid id: $id"
        )
      }
      if (byId.containsKey(id)) {
        throw IllegalStateException("Duplicate $kind id $id in encrypted backup")
      }
      byId[id] = o
    }
    return byId
  }

  /**
   * Decrypts one encrypted field of an account entry, keyed by [field] with a
   * per-account, per-field AAD.
   *
   * Returns null only when the raw entry carries no value (absent or JSON null).
   * A present empty or non-string value is malformed ciphertext and fails loudly
   * instead of returning null and silently keeping ciphertext as plaintext.
   */
  private fun decryptAccountField(
    account: AccountEntity,
    raw: JSONObject,
    key: SecretKey,
    field: String
  ): String? {
    if (!raw.has(field) || raw.isNull(field)) return null
    val v = raw.opt(field)
    if (v !is String) {
      throw IllegalArgumentException("Account ${account.id} field $field is not a string: $v")
    }
    if (v.isEmpty()) {
      throw IllegalArgumentException("Account ${account.id} field $field is empty ciphertext")
    }
    return BackupCipher.decrypt(v, key, BackupCipher.accountFieldAad(account.id, field))
  }

  /**
   * Decrypts one encrypted field of a person entry, keyed by [field] with a
   * per-person, per-field AAD. Mirrors [decryptAccountField].
   *
   * Returns null only when the raw entry carries no value (absent or JSON null).
   * A present value that is empty, non-string, or fails the AES-GCM tag check
   * is not swallowed — an exception propagates so a malformed ciphertext never
   * degrades into a silent null and erases PII.
   */
  private fun decryptPersonField(
    person: Person,
    raw: JSONObject,
    key: SecretKey,
    field: String
  ): String? {
    if (!raw.has(field) || raw.isNull(field)) return null
    val v = raw.opt(field)
    if (v !is String) {
      throw IllegalArgumentException("Person ${person.id} field $field is not a string: $v")
    }
    if (v.isEmpty()) {
      throw IllegalArgumentException("Person ${person.id} field $field is empty ciphertext")
    }
    return BackupCipher.decrypt(v, key, BackupCipher.personFieldAad(person.id, field))
  }

  suspend fun parseBackupJson(jsonString: String): BackupPayload? = parser.parseBackupJson(jsonString)

  suspend fun validateBackup(backup: BackupPayload): BackupValidationResult = validator.validateBackup(backup)

  suspend fun executeRestore(
    backup: BackupPayload,
    mode: RestoreMode
  ) {
    when (mode) {
      RestoreMode.REPLACE -> repository.replaceAllFromBackup(backup)
      RestoreMode.MERGE -> repository.mergeFromBackup(backup)
    }
  }

  suspend fun exportBackupJson(
    isDarkMode: Boolean = true,
    passphrase: String? = null
  ): JSONObject = exporter.exportBackupJson(isDarkMode, passphrase)
}
