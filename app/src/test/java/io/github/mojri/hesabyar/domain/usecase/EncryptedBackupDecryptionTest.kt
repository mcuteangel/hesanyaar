package io.github.mojri.hesabyar.domain.usecase

import io.github.mojri.hesabyar.HesabyarApp
import io.github.mojri.hesabyar.RustIsolationRule
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.Person
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Hardening tests for `ManageBackupUseCase.decryptBackupWithPassphrase`.
 *
 * Two behaviours are covered:
 * - person PII (phone/notes) is recovered from an encrypted backup, and the
 *   parsed payload still holds ciphertext before decryption runs;
 * - a backup whose `accounts`/`persons` array was stripped or emptied fails
 *   loudly instead of restoring raw ciphertext, because the export path always
 *   writes both arrays and a missing one can only mean tampering or truncation.
 *
 * This class is separate from `ManageBackupUseCaseTest` to stay under the detekt
 * class-size threshold; both exercise the same public API.
 */
class EncryptedBackupDecryptionTest {
  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun forceKotlinParser() {
    // The native library is on the test class path for every task, so without
    // forcing the Rust availability decision off, RustBridge would win and
    // these tests would silently run the native parser instead of the Kotlin
    // one. Mirrors ManageBackupUseCaseTest.
    HesabyarApp.setRustInitializedForTesting(false)
  }

  @Test
  fun exportWithPassphraseRecoversPersonPhoneAndNotes() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "person-pii-passphrase"
    val realPhone = "09121234567"
    val realNotes = "یادداشت"

    repo.addPerson(
      Person(
        id = 1L,
        name = "Ali",
        normalizedName = "ali",
        phone = realPhone,
        notes = realNotes
      )
    )

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }
    val jsonString = rootJson.toString()
    val parsed = runBlocking { useCase.parseBackupJson(jsonString) }!!
    assertEquals("sanity: one person must be parsed", 1, parsed.persons.size)
    assertFalse(
      "phone must still be ciphertext before decryption",
      realPhone == parsed.persons[0].phone
    )

    val decrypted =
      runBlocking {
        useCase.decryptBackupWithPassphrase(parsed, JSONObject(jsonString), passphrase)
      }

    assertEquals("phone must be recovered", realPhone, decrypted.persons[0].phone)
    assertEquals("notes must be recovered", realNotes, decrypted.persons[0].notes)
  }

  @Test
  fun decryptStrippedAccountsArrayThrowsInsteadOfRestoringCiphertext() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "correct-passphrase"

    runBlocking {
      repo.insertAccount(
        AccountEntity(
          id = 1,
          name = "حساب اصلی",
          type = AccountType.BANK,
          cardNumber = "6219861012345678",
          iban = "IR12345"
        )
      )
    }

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }
    val jsonString = rootJson.toString()
    val parsed = runBlocking { useCase.parseBackupJson(jsonString) }!!
    assertEquals("sanity: one account must be parsed", 1, parsed.accounts.size)

    // The export path always writes the accounts array, so on an encrypted
    // backup a missing or empty array means the payload was stripped. Passing
    // the parsed accounts through there would restore raw ciphertext.
    val strippedRoot = JSONObject(jsonString)
    strippedRoot.remove("accounts")
    assertThrows(
      "Stripped accounts array must fail loudly",
      IllegalStateException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, strippedRoot, passphrase) }
    }

    val emptiedRoot = JSONObject(jsonString)
    emptiedRoot.put("accounts", JSONArray())
    assertThrows(
      "Emptied accounts array must fail loudly",
      IllegalStateException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, emptiedRoot, passphrase) }
    }
  }

  @Test
  fun decryptStrippedPersonsArrayThrowsInsteadOfRestoringCiphertext() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "correct-passphrase"

    repo.addPerson(
      Person(
        id = 1L,
        name = "Ali",
        normalizedName = "ali",
        phone = "09121234567",
        notes = "یادداشت"
      )
    )

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }
    val jsonString = rootJson.toString()
    val parsed = runBlocking { useCase.parseBackupJson(jsonString) }!!
    assertEquals("sanity: one person must be parsed", 1, parsed.persons.size)

    val strippedRoot = JSONObject(jsonString)
    strippedRoot.remove("persons")
    assertThrows(
      "Stripped persons array must fail loudly",
      IllegalStateException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, strippedRoot, passphrase) }
    }

    val emptiedRoot = JSONObject(jsonString)
    emptiedRoot.put("persons", JSONArray())
    assertThrows(
      "Emptied persons array must fail loudly",
      IllegalStateException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, emptiedRoot, passphrase) }
    }
  }

  @Test
  fun decryptPersonWithAbsentPhoneLeavesItNullInsteadOfCiphertext() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "correct-passphrase"

    // A person with no phone: the exporter writes JSON null, so decryption has
    // nothing to do. The parsed value is already null and must stay null.
    repo.addPerson(
      Person(
        id = 1L,
        name = "Ali",
        normalizedName = "ali",
        phone = null,
        notes = "یادداشت"
      )
    )

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }
    val jsonString = rootJson.toString()
    val parsed = runBlocking { useCase.parseBackupJson(jsonString) }!!

    val decrypted =
      runBlocking {
        useCase.decryptBackupWithPassphrase(parsed, JSONObject(jsonString), passphrase)
      }

    assertEquals("absent phone must stay null", null, decrypted.persons[0].phone)
    assertEquals("notes must still be recovered", "یادداشت", decrypted.persons[0].notes)
  }

  @Test
  fun decryptAccountFieldRejectsEmptyAndNonStringCiphertext() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "correct-passphrase"

    runBlocking {
      repo.insertAccount(
        AccountEntity(
          id = 1,
          name = "حساب اصلی",
          type = AccountType.BANK,
          cardNumber = "6219861012345678",
          iban = "IR12345"
        )
      )
    }

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }
    val jsonString = rootJson.toString()
    val parsed = runBlocking { useCase.parseBackupJson(jsonString) }!!

    // Empty ciphertext: present but blank fails loudly rather than decrypting
    // to nothing and silently clearing the field.
    val emptyRoot = JSONObject(jsonString)
    emptyRoot.getJSONArray("accounts").getJSONObject(0).put("cardNumber", "")
    assertThrows(
      "Empty ciphertext must fail loudly",
      IllegalArgumentException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, emptyRoot, passphrase) }
    }

    // Non-string value: a number in place of the base64 ciphertext is
    // malformed data, not a decryptable field.
    val nonStringRoot = JSONObject(jsonString)
    nonStringRoot.getJSONArray("accounts").getJSONObject(0).put("iban", 5)
    assertThrows(
      "Non-string ciphertext must fail loudly",
      IllegalArgumentException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, nonStringRoot, passphrase) }
    }
  }

  @Test
  fun decryptPersonFieldRejectsEmptyAndNonStringCiphertext() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "correct-passphrase"

    repo.addPerson(
      Person(
        id = 1L,
        name = "Ali",
        normalizedName = "ali",
        phone = "09121234567",
        notes = "یادداشت"
      )
    )

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }
    val jsonString = rootJson.toString()
    val parsed = runBlocking { useCase.parseBackupJson(jsonString) }!!

    val emptyRoot = JSONObject(jsonString)
    emptyRoot.getJSONArray("persons").getJSONObject(0).put("notes", "")
    assertThrows(
      "Empty ciphertext must fail loudly",
      IllegalArgumentException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, emptyRoot, passphrase) }
    }

    val nonStringRoot = JSONObject(jsonString)
    nonStringRoot.getJSONArray("persons").getJSONObject(0).put("phone", 123)
    assertThrows(
      "Non-string ciphertext must fail loudly",
      IllegalArgumentException::class.java
    ) {
      runBlocking { useCase.decryptBackupWithPassphrase(parsed, nonStringRoot, passphrase) }
    }
  }
}
