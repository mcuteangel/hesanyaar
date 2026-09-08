package io.github.mojri.hesabyar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.Category
import io.github.mojri.hesabyar.data.CategoryType
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PersonRepositoryTest {
  private lateinit var database: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    database.accountDao().insertAllBlocking(listOf(AccountEntity.DEFAULT_ACCOUNT))
  }

  @After
  fun tearDown() {
    database.close()
  }

  private fun createRepository(): HesabyarRepository =
    HesabyarRepository(
      database.transactionDao(),
      database.loanDao(),
      database.installmentDao(),
      database.paymentHistoryDao(),
      database.categoryDao(),
      database.bankLoanDao(),
      database.accountDao(),
      database.personDao(),
      database
    )

  private fun person(name: String) =
    Person(
      name = name,
      normalizedName =
        io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer
          .normalize(name)
    )

  @Test
  fun upsertPersonDeduplicatesNormalizedNamesAndKeepsFirstDisplayForm() =
    runTest {
      val repo = createRepository()

      val first = repo.upsertPerson(person("علی"))
      val arabicVariant = repo.upsertPerson(person("علي"))
      val spacedVariant = repo.upsertPerson(person("  علی  "))

      assertEquals("Variants must collapse to the first row", first.id, arabicVariant.id)
      assertEquals("Variants must collapse to the first row", first.id, spacedVariant.id)
      assertEquals("First display spelling wins", "علی", arabicVariant.name)

      val all = database.personDao().getAllPersonsIncludingArchivedBlocking()
      assertEquals("Only one stored row", 1, all.size)
      assertEquals("علی", all.single().normalizedName)
    }

  @Test
  fun upsertPersonFillsContactDetailsWithoutOverwritingName() =
    runTest {
      val repo = createRepository()
      val created = repo.upsertPerson(person("رضا"))

      val updated = repo.upsertPerson(person("رضا").copy(phone = "0912", notes = "همکار"))

      assertEquals(created.id, updated.id)
      assertEquals("0912", updated.phone)
      assertEquals("همکار", updated.notes)
      assertEquals("رضا", updated.name)
    }

  @Test
  fun renamePersonSyncsLoansAndTransactionsPersonNames() =
    runTest {
      val repo = createRepository()
      val person = repo.upsertPerson(person("علی"))

      val loanId =
        repo.insertLoan(
          Loan(
            personName = "علی",
            personId = person.id,
            type = LoanType.DEBTOR,
            originalAmount = 5_000L,
            remainingAmount = 5_000L,
            description = "test"
          )
        )
      repo.insertTransaction(
        Transaction(
          type = TransactionType.EXPENSE,
          categoryId = seedCategoryId(repo),
          amount = 1_000L,
          description = "test",
          personName = "علی",
          personId = person.id
        )
      )

      val renamed = repo.renamePerson(person.id, "علی رضایی")

      assertTrue(renamed)
      assertEquals("علی رضایی", database.loanDao().getLoanById(loanId)?.personName)
      assertEquals(
        "transactions.personName must sync too (D3)",
        "علی رضایی",
        database
          .transactionDao()
          .getAllTransactionsBlocking()
          .single()
          .personName
      )
      val stored = requireNotNull(database.personDao().getPersonById(person.id))
      assertEquals("علی رضایی", stored.name)
    }

  @Test
  fun renamePersonRejectsCollisionWithAnotherPersonsNormalizedName() =
    runTest {
      val repo = createRepository()
      val ali = repo.upsertPerson(person("علی"))
      val reza = repo.upsertPerson(person("رضا"))

      val renamed = repo.renamePerson(ali.id, "  رضا ")

      assertFalse("Rename onto another person's key must be refused", renamed)
      assertEquals("علی", requireNotNull(database.personDao().getPersonById(ali.id)).name)
      assertNotNull(database.personDao().getPersonById(reza.id))
    }

  @Test
  fun renamePersonRejectsBlankNameAndUnknownId() =
    runTest {
      val repo = createRepository()
      val person = repo.upsertPerson(person("علی"))

      try {
        repo.renamePerson(person.id, "   ")
        fail("expected IllegalArgumentException for blank rename name")
      } catch (e: IllegalArgumentException) {
        val msg = e.message ?: ""
        assertTrue(msg.contains("normalizes to empty") || msg.contains("Person name is blank"))
      }
      assertFalse("unknown id must be refused", repo.renamePerson(999L, "معتبر"))
    }

  @Test
  fun upsertPersonRejectsNameThatNormalizesToEmpty() =
    runTest {
      val repo = createRepository()
      // ZWSP-only name: displayForm strips zero-width, so display is empty
      // and either the blank-check or the empty-key check rejects it.
      try {
        repo.upsertPerson(person("\u200B\u200C\u200D"))
        fail("expected IllegalArgumentException for zero-width-only name")
      } catch (e: IllegalArgumentException) {
        val msg = e.message ?: ""
        assertTrue(msg.contains("normalizes to empty") || msg.contains("Person name is blank"))
      }
      val all = repo.getAllPersonsIncludingArchived()
      assertTrue("no row should be inserted for an empty-key name", all.isEmpty())
    }

  @Test
  fun deletePersonRemovesRowAndClearsPersonIdButKeepsDenormalizedNames() =
    runTest {
      val repo = createRepository()
      val person = repo.upsertPerson(person("علی"))
      val loanId =
        repo.insertLoan(
          Loan(
            personName = "علی",
            personId = person.id,
            type = LoanType.DEBTOR,
            originalAmount = 5_000L,
            remainingAmount = 5_000L,
            description = "test"
          )
        )

      repo.deletePerson(person.copy(id = person.id))

      assertNull(database.personDao().getPersonById(person.id))
      val storedLoan = database.loanDao().getLoanById(loanId)
      assertEquals("Display name survives on the loan row (D3)", "علی", storedLoan?.personName)
      assertNull("Dangling personId must be cleared", storedLoan?.personId)
    }

  private suspend fun seedCategoryId(repo: HesabyarRepository): Long =
    repo.insertCategory(
      Category(
        name = "Loans",
        key = "Loans",
        icon = "HistoryEdu",
        color = 0xFF9C27B0L,
        type = CategoryType.BOTH
      )
    )

  // =====================================================================
  // insertPerson race: -1 means another concurrent insert won; the loser
  // must look up the winner and merge contact details. Mirrors
  // PersonDelegate.upsertPerson lines 40-51.
  // =====================================================================

  /** Race-simulating [PersonDao] that returns null on the first name lookup and -1 for insertPerson. */
  private class RacePersonDao(
    private val delegate: io.github.mojri.hesabyar.data.PersonDao
  ) : io.github.mojri.hesabyar.data.PersonDao by delegate {
    var forceLookupNullOnFirst = false
    var forceInsertReturnNegative = false
    private var lookupCount = 0

    override suspend fun getPersonByNormalizedName(normalizedName: String): Person? {
      if (forceLookupNullOnFirst && lookupCount == 0) {
        lookupCount++
        return null
      }
      return delegate.getPersonByNormalizedName(normalizedName)
    }

    override suspend fun insertPerson(person: Person): Long =
      if (forceInsertReturnNegative) -1L else delegate.insertPerson(person)
  }

  @Test
  fun upsertPersonMergesContactDetailsWhenInsertPersonReturnsNegativeOne() =
    runTest {
      // Pre-insert a winner so the unique index is occupied.
      val realDao = database.personDao()
      val winnerId =
        realDao.insertPerson(
          Person(
            id = 0,
            name = "علی",
            normalizedName = "علی",
            phone = "09120000000",
            notes = "winner note",
            createdAt = 1L,
            isArchived = false
          )
        )
      val raceDao =
        RacePersonDao(realDao).apply {
          forceLookupNullOnFirst = true
          forceInsertReturnNegative = true
        }
      val delegate =
        io.github.mojri.hesabyar.data.PersonDelegate(
          raceDao,
          database.loanDao(),
          database.loanPersonOpsDao(),
          database.transactionDao(),
          database
        )

      // Upsert the same name with new contact details — a race that loses
      // the insert (insertPerson returns -1) must find the winner and merge.
      val result =
        delegate.upsertPerson(
          Person(
            name = "علی",
            normalizedName = "علی",
            phone = "09300000000",
            notes = "new note",
            createdAt = 2L,
            isArchived = false
          )
        )

      assertEquals("must return the winner's id", winnerId, result.id)
      assertEquals("new phone must win over the winner's phone", "09300000000", result.phone)
      assertEquals("new notes must win over the winner's notes", "new note", result.notes)

      // Winner retained and merged in the DB
      val stored = requireNotNull(database.personDao().getPersonById(winnerId))
      assertEquals("09300000000", stored.phone)
      assertEquals("new note", stored.notes)
    }

  @Test
  fun upsertPersonReturnsWinnerUnchangedWhenContactDetailsAreAbsent() =
    runTest {
      val realDao = database.personDao()
      val winnerId =
        realDao.insertPerson(
          Person(
            id = 0,
            name = "علی",
            normalizedName = "علی",
            phone = "09120000000",
            notes = "winner note",
            createdAt = 1L,
            isArchived = false
          )
        )
      val raceDao =
        RacePersonDao(realDao).apply {
          forceLookupNullOnFirst = true
          forceInsertReturnNegative = true
        }
      val delegate =
        io.github.mojri.hesabyar.data.PersonDelegate(
          raceDao,
          database.loanDao(),
          database.loanPersonOpsDao(),
          database.transactionDao(),
          database
        )

      // Upsert with no contact details — winner returned unchanged.
      val result =
        delegate.upsertPerson(
          Person(
            name = "علی",
            normalizedName = "علی",
            phone = null,
            notes = null,
            createdAt = 2L,
            isArchived = false
          )
        )

      assertEquals("must return the winner's id", winnerId, result.id)
      assertEquals("winner phone preserved when input phone is null", "09120000000", result.phone)
      assertEquals("winner notes preserved when input notes is null", "winner note", result.notes)
    }

  // =====================================================================
  // Null-personId rename fallback (Daos.kt syncLoan/PersonNamesForNullId)
  // =====================================================================

  @Test
  fun syncLoanPersonNamesForNullIdUpdatesMatchingLegacyLoansOnly() =
    runTest {
      val loanDao = database.loanDao()
      // loan1: legacy (personId == null) with matching name
      // loan2: legacy (personId == null) with a different name
      // loan3: linked (personId != null) — must NOT be touched
      val loan1Id =
        loanDao.insertLoan(
          Loan(
            personName = "علی",
            personId = null,
            type = LoanType.DEBTOR,
            originalAmount = 1000L,
            remainingAmount = 1000L,
            description = "legacy-match"
          )
        )
      val loan2Id =
        loanDao.insertLoan(
          Loan(
            personName = "رضا",
            personId = null,
            type = LoanType.DEBTOR,
            originalAmount = 1000L,
            remainingAmount = 1000L,
            description = "legacy-unrelated"
          )
        )
      val loan3Id =
        loanDao.insertLoan(
          Loan(
            personName = "علی",
            personId = 99L,
            type = LoanType.DEBTOR,
            originalAmount = 1000L,
            remainingAmount = 1000L,
            description = "linked"
          )
        )

      loanDao.syncLoanPersonNamesForNullId(oldName = "علی", newName = "علی نیا")

      val loan1 = requireNotNull(loanDao.getLoanById(loan1Id))
      val loan2 = requireNotNull(loanDao.getLoanById(loan2Id))
      val loan3 = requireNotNull(loanDao.getLoanById(loan3Id))
      assertEquals("legacy loan with matching name updates", "علی نیا", loan1.personName)
      assertEquals("legacy loan with different name unaffected", "رضا", loan2.personName)
      assertEquals("linked loan (personId != null) unaffected", "علی", loan3.personName)
    }

  @Test
  fun syncTransactionPersonNamesForNullIdUpdatesMatchingLegacyTransactionsOnly() =
    runTest {
      val txDao = database.transactionDao()
      // tx1: legacy (personId == null) with matching name
      // tx2: legacy (personId == null) with a different name
      // tx3: linked (personId != null) — must NOT be touched
      val tx1Id =
        txDao.insertTransaction(
          Transaction(
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            amount = 1000L,
            description = "legacy-match",
            personName = "علی",
            personId = null
          )
        )
      val tx2Id =
        txDao.insertTransaction(
          Transaction(
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            amount = 1000L,
            description = "legacy-unrelated",
            personName = "رضا",
            personId = null
          )
        )
      val tx3Id =
        txDao.insertTransaction(
          Transaction(
            type = TransactionType.EXPENSE,
            categoryId = 1L,
            amount = 1000L,
            description = "linked",
            personName = "علی",
            personId = 99L
          )
        )

      txDao.syncTransactionPersonNamesForNullId(oldName = "علی", newName = "علی نیا")

      val tx1 = requireNotNull(txDao.getAllTransactionsBlocking().find { it.id == tx1Id })
      val tx2 = requireNotNull(txDao.getAllTransactionsBlocking().find { it.id == tx2Id })
      val tx3 = requireNotNull(txDao.getAllTransactionsBlocking().find { it.id == tx3Id })
      assertEquals("legacy tx with matching name updates", "علی نیا", tx1.personName)
      assertEquals("legacy tx with different name unaffected", "رضا", tx2.personName)
      assertEquals("linked tx (personId != null) unaffected", "علی", tx3.personName)
    }
}
