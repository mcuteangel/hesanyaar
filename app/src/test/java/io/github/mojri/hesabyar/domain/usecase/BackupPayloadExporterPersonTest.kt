package io.github.mojri.hesabyar.domain.usecase

import io.github.mojri.hesabyar.HesabyarApp
import io.github.mojri.hesabyar.RustIsolationRule
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@Suppress("LongMethod")
class BackupPayloadExporterPersonTest {
  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun forceKotlinParser() {
    HesabyarApp.setRustInitializedForTesting(false)
  }

  @Test
  fun plaintextPersonExportPreservesPhoneNotesIsArchivedAndCreatedAt() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val createdAt = 1_700_000_000_000L
    val phone = "09123456789"
    val notes = "VIP customer"
    val person =
      Person(
        id = 10L,
        name = "Ali",
        normalizedName = "ali",
        phone = phone,
        notes = notes,
        createdAt = createdAt,
        isArchived = false
      )
    val archivedPerson =
      Person(
        id = 11L,
        name = "Reza",
        normalizedName = "reza",
        phone = null,
        notes = null,
        createdAt = createdAt,
        isArchived = true
      )
    runBlocking {
      repo.addPerson(person)
      repo.addPerson(archivedPerson)
    }

    val rootJson = runBlocking { useCase.exportBackupJson() }

    assertFalse("plaintext backup must not have encryption metadata", rootJson.has("sensitiveFieldsEncryption"))
    val personsJson = rootJson.getJSONArray("persons")
    assertEquals("persons array size", 2, personsJson.length())
    val aliJson = (0 until personsJson.length()).map { personsJson.getJSONObject(it) }.first { it.getLong("id") == 10L }
    assertEquals("phone plaintext in json", phone, aliJson.getString("phone"))
    assertEquals("notes plaintext in json", notes, aliJson.getString("notes"))
    val rezaJson =
      (0 until personsJson.length())
        .map {
          personsJson.getJSONObject(
            it
          )
        }.first { it.getLong("id") == 11L }
    assertTrue("null phone must be NULL in json", rezaJson.isNull("phone"))
    assertTrue("null notes must be NULL in json", rezaJson.isNull("notes"))

    val parsed = runBlocking { useCase.parseBackupJson(rootJson.toString()) }
    assertTrue("parsed payload must not be null", parsed != null)
    assertEquals("parsed persons count", 2, parsed!!.persons.size)
    val parsedAli = parsed.persons.first { it.id == 10L }
    assertEquals("phone survives round-trip", phone, parsedAli.phone)
    assertEquals("notes survives round-trip", notes, parsedAli.notes)
    assertEquals("isArchived survives", false, parsedAli.isArchived)
    assertEquals("createdAt survives", createdAt, parsedAli.createdAt)
    val parsedReza = parsed.persons.first { it.id == 11L }
    assertNull("null phone stays null", parsedReza.phone)
    assertNull("null notes stays null", parsedReza.notes)
    assertEquals("archived flag survives", true, parsedReza.isArchived)
  }

  @Test
  fun encryptedPersonExportEncryptsPhoneNotesAndDecryptRecovers() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "test-passphrase-123"
    val phone = "09123456789"
    val notes = "secret note"
    val createdAt = 1_700_000_000_000L
    val person =
      Person(
        id = 20L,
        name = "Sara",
        normalizedName = "sara",
        phone = phone,
        notes = notes,
        createdAt = createdAt,
        isArchived = false
      )
    val nullPerson =
      Person(
        id = 21L,
        name = "Mina",
        normalizedName = "mina",
        phone = null,
        notes = null,
        createdAt = createdAt,
        isArchived = false
      )
    runBlocking {
      repo.addPerson(person)
      repo.addPerson(nullPerson)
    }

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }

    assertTrue("encrypted backup must have encryption metadata", rootJson.has("sensitiveFieldsEncryption"))
    val personsJson = rootJson.getJSONArray("persons")
    val saraJson =
      (0 until personsJson.length())
        .map {
          personsJson.getJSONObject(
            it
          )
        }.first { it.getLong("id") == 20L }
    val encPhone = saraJson.get("phone")
    val encNotes = saraJson.get("notes")
    assertTrue("phone must be encrypted string", encPhone is String)
    assertTrue("notes must be encrypted string", encNotes is String)
    assertNotEquals("phone ciphertext must differ from plaintext", phone, encPhone as String)
    assertNotEquals("notes ciphertext must differ from plaintext", notes, encNotes as String)
    val minaJson =
      (0 until personsJson.length())
        .map {
          personsJson.getJSONObject(
            it
          )
        }.first { it.getLong("id") == 21L }
    assertTrue("null phone stays NULL even when encrypted", minaJson.isNull("phone"))
    assertTrue("null notes stays NULL even when encrypted", minaJson.isNull("notes"))

    val parsed = runBlocking { useCase.parseBackupJson(rootJson.toString()) }
    assertTrue("parsed must not be null", parsed != null)
    val parsedSara = parsed!!.persons.first { it.id == 20L }
    assertNotEquals("parsed phone is ciphertext before decrypt", phone, parsedSara.phone)

    val decrypted = runBlocking { useCase.decryptBackupWithPassphrase(parsed, rootJson, passphrase) }
    val decSara = decrypted.persons.first { it.id == 20L }
    assertEquals("decrypted phone recovered", phone, decSara.phone)
    assertEquals("decrypted notes recovered", notes, decSara.notes)
    assertEquals("isArchived preserved after decrypt", false, decSara.isArchived)
    assertEquals("createdAt preserved after decrypt", createdAt, decSara.createdAt)
    val decMina = decrypted.persons.first { it.id == 21L }
    assertNull("null phone stays null after decrypt", decMina.phone)
    assertNull("null notes stays null after decrypt", decMina.notes)
  }

  @Test
  fun plaintextLinkedTransactionAndLoanPreservePersonIdAndPersonName() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val person =
      Person(
        id = 42L,
        name = "Sara",
        normalizedName = "sara",
        phone = "09111111111",
        notes = "note",
        createdAt = 1_700_000_000_000L,
        isArchived = false
      )
    runBlocking { repo.addPerson(person) }
    runBlocking {
      repo.insertTransaction(
        Transaction(
          id = 100L,
          type = TransactionType.EXPENSE,
          categoryId = 1L,
          amount = 50000L,
          description = "buy",
          personName = "Sara",
          personId = 42L,
          accountId = 1L
        )
      )
      repo.insertLoan(
        Loan(
          id = 200L,
          personName = "Sara",
          personId = 42L,
          type = LoanType.CREDITOR,
          originalAmount = 1000000L,
          remainingAmount = 500000L,
          description = "loan"
        )
      )
    }

    val rootJson = runBlocking { useCase.exportBackupJson() }

    val txJson = rootJson.getJSONArray("transactions").getJSONObject(0)
    assertFalse("transaction personId must not be null in json", txJson.isNull("personId"))
    assertEquals("transaction personId serialized", 42L, txJson.getLong("personId"))
    assertEquals("transaction personName serialized", "Sara", txJson.getString("personName"))
    val loanJson = rootJson.getJSONArray("loans").getJSONObject(0)
    assertFalse("loan personId must not be null in json", loanJson.isNull("personId"))
    assertEquals("loan personId serialized", 42L, loanJson.getLong("personId"))

    val parsed = runBlocking { useCase.parseBackupJson(rootJson.toString()) }
    assertTrue("parsed must not be null", parsed != null)
    assertEquals("transaction count", 1, parsed!!.transactions.size)
    assertEquals("transaction personId restored", 42L, parsed.transactions[0].personId)
    assertEquals("transaction personName restored", "Sara", parsed.transactions[0].personName)
    assertEquals("loan count", 1, parsed.loans.size)
    assertEquals("loan personId restored", 42L, parsed.loans[0].personId)
    assertEquals("loan personName restored", "Sara", parsed.loans[0].personName)
  }

  @Test
  fun encryptedLinkedTransactionAndLoanPreservePersonIdAndPersonName() {
    val repo = FakeRepository()
    val useCase = ManageBackupUseCase(repo)
    val passphrase = "another-secret"
    val person =
      Person(
        id = 55L,
        name = "Nima",
        normalizedName = "nima",
        phone = "09222222222",
        notes = "private",
        createdAt = 1_700_000_000_000L,
        isArchived = false
      )
    runBlocking { repo.addPerson(person) }
    runBlocking {
      repo.insertTransaction(
        Transaction(
          id = 101L,
          type = TransactionType.INCOME,
          categoryId = 2L,
          amount = 75000L,
          description = "income",
          personName = "Nima",
          personId = 55L,
          accountId = 1L
        )
      )
      repo.insertLoan(
        Loan(
          id = 201L,
          personName = "Nima",
          personId = 55L,
          type = LoanType.DEBTOR,
          originalAmount = 2000000L,
          remainingAmount = 1000000L,
          description = "debt"
        )
      )
    }

    val rootJson = runBlocking { useCase.exportBackupJson(passphrase = passphrase) }

    val txJson = rootJson.getJSONArray("transactions").getJSONObject(0)
    assertEquals("transaction personId still plaintext under encryption", 55L, txJson.getLong("personId"))
    val loanJson = rootJson.getJSONArray("loans").getJSONObject(0)
    assertEquals("loan personId still plaintext under encryption", 55L, loanJson.getLong("personId"))

    val parsed = runBlocking { useCase.parseBackupJson(rootJson.toString()) }
    assertTrue("parsed must not be null", parsed != null)
    val decrypted = runBlocking { useCase.decryptBackupWithPassphrase(parsed!!, rootJson, passphrase) }

    assertEquals("transaction personId after decrypt", 55L, decrypted.transactions[0].personId)
    assertEquals("transaction personName after decrypt", "Nima", decrypted.transactions[0].personName)
    assertEquals("loan personId after decrypt", 55L, decrypted.loans[0].personId)
    assertEquals("loan personName after decrypt", "Nima", decrypted.loans[0].personName)
    val decPerson = decrypted.persons.first { it.id == 55L }
    assertEquals("person phone recovered after decrypt", "09222222222", decPerson.phone)
    assertEquals("person notes recovered after decrypt", "private", decPerson.notes)
  }
}
