package io.github.mojri.hesabyar.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for MIGRATION_7_8 — the persons table + personId column
 * backfill. Covers the primary backfill path, the duplicate-column recovery
 * path, the error-rethrow guard, the transaction-only-name tiebreak policy,
 * and the real plaintext→encrypted transfer seam for persons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppDatabaseMigration7to8Test {
  private lateinit var sourceDb: AppDatabase
  private lateinit var targetDb: AppDatabase

  /** Raw SQL to create the v5 schema tables (all except accounts). */
  private fun createV5SchemaTables(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS categories (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "name TEXT NOT NULL, key TEXT NOT NULL, " +
        "icon TEXT NOT NULL, color INTEGER NOT NULL, " +
        "type TEXT NOT NULL, isDefault INTEGER NOT NULL)"
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS transactions (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "type TEXT NOT NULL, categoryId INTEGER NOT NULL, " +
        "amount INTEGER NOT NULL, description TEXT NOT NULL, " +
        "personName TEXT, date INTEGER NOT NULL, " +
        "dueDate INTEGER, installmentId INTEGER)"
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS loans (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "personName TEXT NOT NULL, type TEXT NOT NULL, " +
        "originalAmount INTEGER NOT NULL, " +
        "remainingAmount INTEGER NOT NULL, " +
        "description TEXT NOT NULL, date INTEGER NOT NULL, " +
        "isSettled INTEGER NOT NULL)"
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS installments (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "title TEXT NOT NULL, amount INTEGER NOT NULL, " +
        "dueDate INTEGER NOT NULL, isPaid INTEGER NOT NULL, " +
        "reminderEnabled INTEGER NOT NULL, " +
        "notes TEXT NOT NULL, bankLoanId INTEGER)"
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS payment_history (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "loanId INTEGER NOT NULL, amount INTEGER NOT NULL, " +
        "date INTEGER NOT NULL, notes TEXT NOT NULL)"
    )
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS bank_loans (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "bankName TEXT NOT NULL, loanName TEXT NOT NULL, " +
        "receivedAmount INTEGER NOT NULL, " +
        "monthlyInstallmentAmount INTEGER NOT NULL, " +
        "numberOfInstallments INTEGER NOT NULL, " +
        "totalRepayableAmount INTEGER NOT NULL, " +
        "totalInterest INTEGER NOT NULL, " +
        "startDate INTEGER NOT NULL, " +
        "description TEXT NOT NULL, " +
        "isSettled INTEGER NOT NULL)"
    )
  }

  /** Inserts Room metadata so Room recognizes the DB as version 5. */
  private fun createV5SchemaRoomMetadata(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS room_master_table (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "identity_hash TEXT)"
    )
    db.execSQL(
      "INSERT OR REPLACE INTO room_master_table (id, identity_hash)" +
        " VALUES (42, '')"
    )
  }

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    sourceDb =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    targetDb =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    sourceDb.close()
    targetDb.close()
  }

  /** Raw SQL creating the full v7 schema (v5 tables + accounts/timestamps + transaction account columns). */
  private fun createV7SchemaTables(db: SupportSQLiteDatabase) {
    createV5SchemaTables(db)
    db.execSQL(
      "CREATE TABLE IF NOT EXISTS accounts (" +
        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
        "name TEXT NOT NULL, type TEXT NOT NULL, bankName TEXT, " +
        "cardNumber TEXT, accountNumber TEXT, iban TEXT, " +
        "initialBalance INTEGER NOT NULL DEFAULT 0, " +
        "color INTEGER NOT NULL DEFAULT 4283215696, icon TEXT, " +
        "isArchived INTEGER NOT NULL DEFAULT 0, " +
        "displayOrder INTEGER NOT NULL DEFAULT 0, " +
        "createdAt INTEGER NOT NULL DEFAULT 0, " +
        "updatedAt INTEGER NOT NULL DEFAULT 0)"
    )
    db.execSQL("ALTER TABLE transactions ADD COLUMN accountId INTEGER NOT NULL DEFAULT 1")
    db.execSQL("ALTER TABLE transactions ADD COLUMN destinationAccountId INTEGER DEFAULT NULL")
  }

  private fun createAndSeedV7Database(
    context: Context,
    dbName: String,
    seed: (SupportSQLiteDatabase) -> Unit
  ) {
    val helper =
      FrameworkSQLiteOpenHelperFactory().create(
        androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
          .builder(context)
          .name(dbName)
          .callback(
            object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                createV7SchemaTables(db)
                createV5SchemaRoomMetadata(db)
              }

              override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
              ) {
              }
            }
          ).build()
      )
    val raw = helper.writableDatabase
    seed(raw)
    raw.close()
    helper.close()
  }

  /**
   * Phase 1 person-ledger migration (plans/011): creates the persons table,
   * adds nullable personId to BOTH loans and transactions, and backfills
   * persons from distinct loans.personName with normalization-based dedup.
   *
   * Seeded variants:
   * - loan1 'علی' (date=100) and loan2 'علي' (Arabic yeh, date=200) share one key;
   *   first-by-date original 'علی' becomes the display name.
   * - loan3 ' علی رضا ' (date=50) trims/collapses to key 'علی رضا'.
   * - tx1/tx2 stamp onto the shared 'علی' person; tx3 has a loan-less name and,
   *   per the lookup-only migration contract (loans are the identity source),
   *   keeps personId NULL and does NOT seed a person row.
   *
   *
   * Opening the DB through Room validates the produced schema against the
   * entities; any column/type mismatch throws before assertions run.
   */
  @Test
  fun migration7to8BackfillsPersonsAndStampsBothLoansAndTransactions() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_person_backfill_test"
    val dbFile = context.getDatabasePath(dbName)

    try {
      createAndSeedV7Database(context, dbName) { raw -> seedPersonBackfillData(raw) }

      val migratedDb =
        Room
          .databaseBuilder(context, AppDatabase::class.java, dbName)
          .allowMainThreadQueries()
          .addMigrations(AppDatabase.MIGRATION_7_8)
          .build()

      val persons = migratedDb.personDao().getAllPersonsIncludingArchivedBlocking()
      // loan1+loan2 share the 'علی' key; loan3 becomes 'علی رضا'. tx3 is
      // loan-less, so it does NOT seed a person (loans are the identity source).
      assertEquals("Duplicate spellings collapse into distinct persons by key", 2, persons.size)

      val aliPerson = requireNotNull(persons.firstOrNull { it.name == "علی" })
      val alirezaPerson = requireNotNull(persons.firstOrNull { it.name == "علی رضا" })

      val loans = migratedDb.loanDao().getAllLoansBlocking()
      assertEquals(3, loans.size)
      val loan1 = loans.first { it.description == "d1" }
      val loan2 = loans.first { it.description == "d2" }
      val loan3 = loans.first { it.description == "d3" }
      assertEquals("loan1 stamped with shared person", aliPerson.id, loan1.personId)
      assertEquals("Arabic-variant loan2 shares the same person", loan1.personId, loan2.personId)
      assertEquals("loan3 maps to its own person", alirezaPerson.id, loan3.personId)

      val transactions = migratedDb.transactionDao().getAllTransactionsBlocking()
      assertEquals(3, transactions.size)
      val tx1 = transactions.first { it.description == "t1" }
      val tx2 = transactions.first { it.description == "t2" }
      val tx3 = transactions.first { it.description == "t3" }
      assertEquals("tx1 stamped via normalized match", aliPerson.id, tx1.personId)
      assertEquals("tx2 stamped via normalized match", aliPerson.id, tx2.personId)
      assertNull(
        "Transaction-only name keeps personId NULL (loans are the identity source)",
        tx3.personId
      )

      migratedDb.close()
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
  }

  /**
   * Seeds loans and transactions with personName variants before the
   * MIGRATION_7_8 backfill runs. loan1/loan2 ('علی'/'علي') share one
   * normalized key; loan3 trims/collapses to 'علی رضا'; tx1/tx2 match the
   * shared key; tx3 has a loan-less name and, per the lookup-only contract,
   * keeps personId NULL and does not seed a Person row (loans are the
   * identity source).
   */
  private fun seedPersonBackfillData(db: SupportSQLiteDatabase) {
    db.execSQL(
      "INSERT INTO loans (personName, type, originalAmount, remainingAmount, description, date, isSettled) " +
        "VALUES ('علی', 'DEBTOR', 500000, 500000, 'd1', 100, 0)"
    )
    db.execSQL(
      "INSERT INTO loans (personName, type, originalAmount, remainingAmount, description, date, isSettled) " +
        "VALUES ('علي', 'CREDITOR', 300000, 300000, 'd2', 200, 0)"
    )
    db.execSQL(
      "INSERT INTO loans (personName, type, originalAmount, remainingAmount, description, date, isSettled) " +
        "VALUES (' علی رضا ', 'DEBTOR', 100000, 100000, 'd3', 50, 0)"
    )
    db.execSQL(
      "INSERT INTO transactions (type, categoryId, amount, description, personName, date) " +
        "VALUES ('EXPENSE', 1, 10000, 't1', 'علي', 300)"
    )
    db.execSQL(
      "INSERT INTO transactions (type, categoryId, amount, description, personName, date) " +
        "VALUES ('EXPENSE', 1, 20000, 't2', 'علی', 310)"
    )
    db.execSQL(
      "INSERT INTO transactions (type, categoryId, amount, description, personName, date) " +
        "VALUES ('EXPENSE', 1, 30000, 't3', 'نام بی‌وام', 320)"
    )
  }

  /**
   * Creates a v7 database whose schema already includes the persons table and
   * `personId` columns — simulating a partial MIGRATION_7_8 that was interrupted
   * after the DDL but before backfill completed. The [seed] lambda can populate
   * persons and loan/transaction rows before MIGRATION_7_8 is applied.
   */
  private fun createAndSeedV7WithPersonsDatabase(
    context: Context,
    dbName: String,
    seed: (SupportSQLiteDatabase) -> Unit
  ) {
    val helper =
      FrameworkSQLiteOpenHelperFactory().create(
        androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
          .builder(context)
          .name(dbName)
          .callback(
            object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                createV7SchemaTables(db)
                // Pre-create the persons infrastructure that MIGRATION_7_8 adds.
                db.execSQL(
                  "CREATE TABLE IF NOT EXISTS persons (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "name TEXT NOT NULL, normalizedName TEXT NOT NULL, " +
                    "phone TEXT, notes TEXT, createdAt INTEGER NOT NULL, " +
                    "isArchived INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_persons_normalizedName ON persons (normalizedName)")
                db.execSQL("ALTER TABLE loans ADD COLUMN personId INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN personId INTEGER")
                createV5SchemaRoomMetadata(db)
              }

              override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
              ) {}
            }
          ).build()
      )
    val raw = helper.writableDatabase
    seed(raw)
    raw.close()
    helper.close()
  }

  /**
   * Recovery path: the persons table and `personId` columns already exist (from
   * a prior partial migration), and some persons are already row-seeded. The
   * duplicate-column guard catches the ALTER TABLE errors, preloadExistingPersons
   * seeds the identity map from existing rows, and backfill completes without
   * tripping the unique index on normalizedName.
   */
  @Test
  fun migration7to8RecoversWhenPersonIdColumnsAlreadyExist() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_person_recovery_test"
    val dbFile = context.getDatabasePath(dbName)

    try {
      createAndSeedV7WithPersonsDatabase(context, dbName) { raw ->
        // Pre-existing person row — simulate a prior backfill that was interrupted
        // after inserting the person but before stamping all loans.
        raw.execSQL(
          "INSERT INTO persons (name, normalizedName, phone, notes, createdAt, isArchived) " +
            "VALUES ('علی', 'علی', NULL, NULL, 100, 0)"
        )
        // Loan with matching personName but personId = NULL.
        raw.execSQL(
          "INSERT INTO loans (" +
            "personName, type, originalAmount, remainingAmount, " +
            "description, date, isSettled, personId) " +
            "VALUES ('علی', 'DEBTOR', 500000, 500000, 'd1', 100, 0, NULL)"
        )
        // Transaction with matching personName but personId = NULL.
        raw.execSQL(
          "INSERT INTO transactions (type, categoryId, amount, description, personName, date, accountId, personId) " +
            "VALUES ('EXPENSE', 1, 10000, 't1', 'علی', 300, 1, NULL)"
        )
      }

      val migratedDb =
        Room
          .databaseBuilder(context, AppDatabase::class.java, dbName)
          .allowMainThreadQueries()
          .addMigrations(AppDatabase.MIGRATION_7_8)
          .build()

      // Backfill must complete without aborting on the unique-index violation
      // that would occur if preloadExistingPersons were absent.
      val persons = migratedDb.personDao().getAllPersonsIncludingArchivedBlocking()
      assertEquals("Pre-existing person is preserved", 1, persons.size)

      val loans = migratedDb.loanDao().getAllLoansBlocking()
      assertEquals("One loan after migration", 1, loans.size)
      assertEquals(
        "Loan stamped with the preloaded person id",
        persons.single().id,
        loans.single().personId
      )

      val transactions = migratedDb.transactionDao().getAllTransactionsBlocking()
      assertEquals("One transaction after migration", 1, transactions.size)
      assertEquals(
        "Transaction stamped with the preloaded person id",
        persons.single().id,
        transactions.single().personId
      )

      migratedDb.close()
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
  }

  /**
   * Unrelated SQLite errors (anything other than "duplicate column") must
   * propagate, not be swallowed by the recovery guard. Room wraps migration
   * failures in IllegalStateException, so we catch at that level.
   */
  @Test
  fun migration7to8RethrowsUnrelatedSqliteErrors() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_person_rethrow_test"
    val dbFile = context.getDatabasePath(dbName)

    try {
      // Create a v7 database WITHOUT a loans table — ALTER TABLE loans will
      // fail with "no such table", which is NOT a "duplicate column" error
      // and must be rethrown by the recovery guard.
      val helper =
        FrameworkSQLiteOpenHelperFactory().create(
          androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
            .builder(context)
            .name(dbName)
            .callback(
              object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(7) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                  createV7SchemaTables(db)
                  // Drop loans to force a non-duplicate-column SQLite error.
                  db.execSQL("DROP TABLE IF EXISTS loans")
                  createV5SchemaRoomMetadata(db)
                }

                override fun onUpgrade(
                  db: SupportSQLiteDatabase,
                  oldVersion: Int,
                  newVersion: Int
                ) {}
              }
            ).build()
        )
      helper.writableDatabase.close()
      helper.close()

      val db =
        Room
          .databaseBuilder(context, AppDatabase::class.java, dbName)
          .allowMainThreadQueries()
          .addMigrations(AppDatabase.MIGRATION_7_8)
          .build()

      // Opening the database triggers the migration; the missing loans table
      // must cause a failure rather than silently proceeding.
      try {
        db.openHelper.writableDatabase
        fail("Expected SQLiteException or IllegalStateException from missing loans table")
      } catch (e: Exception) {
        // Room wraps migration failures — accept SQLiteException from the
        // guard or the IllegalStateException Room wraps it in.
        assertTrue(
          "Expected SQLite/illegal migration failure, got: ${e::class.simpleName}: ${e.message}",
          e is SQLiteException || e is IllegalStateException
        )
      } finally {
        db.close()
      }
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
  }

  /**
   * Decision (plans/011 §D4 addendum): loans run first, transactions after.
   * A transaction whose personName has no matching loan keeps personId NULL
   * (lookup-only `stampPersonIdsOnTransactions` — loans are the identity
   * source). This test isolates that from the shared-key dedup case above by
   * giving the solo transaction a name no loan carries.
   */
  @Test
  fun migration7to8TransactionOnlyPersonNameStaysNull() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_person_tonly_test"
    val dbFile = context.getDatabasePath(dbName)

    try {
      createAndSeedV7Database(context, dbName) { raw ->
        raw.execSQL(
          "INSERT INTO transactions (type, categoryId, amount, description, personName, date) " +
            "VALUES ('EXPENSE', 1, 1000, 'solo-tx', 'نام صرفاً تراکنشی', 10)"
        )
      }

      val migratedDb =
        Room
          .databaseBuilder(context, AppDatabase::class.java, dbName)
          .allowMainThreadQueries()
          .addMigrations(AppDatabase.MIGRATION_7_8)
          .build()

      val persons = migratedDb.personDao().getAllPersonsIncludingArchivedBlocking()
      assertEquals("Transaction-only name does NOT seed a person row", 0, persons.size)

      val txs = migratedDb.transactionDao().getAllTransactionsBlocking()
      assertEquals(1, txs.size)
      assertNull(
        "Transaction keeps personId NULL (loans are the identity source)",
        txs.single().personId
      )

      migratedDb.close()
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
  }

  /**
   * Regression for the plaintext→encrypted transfer seam: archived persons must
   * survive the real [AppDatabase.transferPlaintextData] path, not just the
   * hand-simulated copy the earlier tests used. This test invokes the actual
   * production helper with in-memory DBs (no sqlcipher) and verifies every
   * table — including persons — reaches the target.
   */
  @Test
  fun personsSurvivePlaintextTransferViaRealSeam() {
    // Arrange: seed source with persons (including an archived one) plus a loan/transaction link
    val personActive =
      Person(
        id = 0,
        name = "علی رضایی",
        normalizedName = "علی رضایی",
        phone = "09120000000",
        notes = "active",
        createdAt = 1000L,
        isArchived = false
      )
    val personArchived =
      Person(
        id = 0,
        name = "سارا",
        normalizedName = "سارا",
        phone = null,
        notes = null,
        createdAt = 2000L,
        isArchived = true
      )
    sourceDb.personDao().insertAllBlocking(listOf(personActive, personArchived))
    val loan =
      Loan(
        id = 0,
        personName = "علی رضایی",
        type = LoanType.DEBTOR,
        originalAmount = 500_000L,
        remainingAmount = 500_000L,
        description = "loan",
        date = 100L,
        isSettled = false
      )
    sourceDb.loanDao().insertAllBlocking(listOf(loan))

    // Act: invoke the real production transfer path
    AppDatabase.transferPlaintextData(sourceDb, targetDb)

    // Assert: both persons (including archived) and the loan survived
    val migratedPersons = targetDb.personDao().getAllPersonsIncludingArchivedBlocking()
    assertEquals("Both persons must survive transfer", 2, migratedPersons.size)
    assertNotNull("Active person present", migratedPersons.find { it.name == "علی رضایی" })
    assertTrue("Archived person present", migratedPersons.any { it.isArchived && it.name == "سارا" })
    assertEquals("Loan survived", 1, targetDb.loanDao().getAllLoansBlocking().size)
  }

  /**
   * Display-name tiebreak policy (plans/011 §D4 addendum): loans-first-then-
   * transactions. A loan and a transaction share a normalized name but the
   * transaction's raw variant appears EARLIER (date=50 vs loan date=200).
   * The loan's displayForm still wins because loans backfill before transactions,
   * so the single Person row's name matches the loan, not the earlier tx.
   */
  @Test
  fun migration7to8DisplayNameTiebreakLoansFirstThenTransactions() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_person_tiebreak_test"
    val dbFile = context.getDatabasePath(dbName)

    try {
      createAndSeedV7Database(context, dbName) { raw ->
        // Loan at date=200 with Persian kaf 'کاظم'.
        raw.execSQL(
          "INSERT INTO loans (personName, type, originalAmount, remainingAmount, description, date, isSettled) " +
            "VALUES ('کاظم', 'DEBTOR', 100000, 100000, 'لیب', 200, 0)"
        )
        // Transaction at date=50 with Arabic kaf 'كاظم' (same key, earlier date).
        raw.execSQL(
          "INSERT INTO transactions (type, categoryId, amount, description, personName, date) " +
            "VALUES ('EXPENSE', 1, 5000, 'تیک', 'كاظم', 50)"
        )
      }

      val migratedDb =
        Room
          .databaseBuilder(context, AppDatabase::class.java, dbName)
          .allowMainThreadQueries()
          .addMigrations(AppDatabase.MIGRATION_7_8)
          .build()

      val persons = migratedDb.personDao().getAllPersonsIncludingArchivedBlocking()
      assertEquals("One person for the shared normalized key", 1, persons.size)
      // Loan's displayForm('کاظم') must win despite the earlier-dated transaction.
      assertEquals("Loan display name wins the tie", "کاظم", persons.single().name)

      val loans = migratedDb.loanDao().getAllLoansBlocking()
      val loan = requireNotNull(loans.firstOrNull { it.description == "لیب" })
      assertEquals("Loan stamped with the shared person", persons.single().id, loan.personId)

      val txs = migratedDb.transactionDao().getAllTransactionsBlocking()
      val tx = requireNotNull(txs.firstOrNull { it.description == "تیک" })
      assertEquals("Transaction stamped onto the loan-seeded person", persons.single().id, tx.personId)

      migratedDb.close()
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
  }

  /**
   * Regression: [AppDatabase.transferPlaintextData] inserts persons with
   * `OnConflictStrategy.IGNORE`. When a source person collides on
   * `normalizedName` with a row already present in the target, the source row
   * is silently skipped while loans/transactions keep its original id — a
   * dangling reference. The transfer must re-link those rows to the person id
   * that actually exists in the target.
   */
  @Test
  fun transferPlaintextDataRemapsPersonIdsOnNormalizedNameCollision() {
    // Arrange: target already holds a person with key 'علی' (id auto-assigns to 1).
    targetDb.personDao().insertAllBlocking(
      listOf(Person(id = 0, name = "علی", normalizedName = "علی", createdAt = 1L))
    )
    val existingTargetPerson = targetDb.personDao().getAllPersonsIncludingArchivedBlocking().single()

    // Source person 'علي' (Arabic yeh) normalizes to the same key but carries
    // a different id (99) that is stamped on a loan and a transaction.
    val collidingSourcePerson =
      Person(id = 99L, name = "علي", normalizedName = "علی", createdAt = 2L)
    val loan =
      Loan(
        id = 0,
        personName = "علي",
        personId = 99L,
        type = LoanType.DEBTOR,
        originalAmount = 100_000L,
        remainingAmount = 100_000L,
        description = "collision loan",
        date = 100L
      )
    val transaction =
      Transaction(
        id = 0,
        type = TransactionType.EXPENSE,
        categoryId = 1L,
        amount = 5_000L,
        description = "collision tx",
        personName = "علي",
        personId = 99L,
        date = 200L
      )
    sourceDb.personDao().insertAllBlocking(listOf(collidingSourcePerson))
    sourceDb.loanDao().insertAllBlocking(listOf(loan))
    sourceDb.transactionDao().insertAllBlocking(listOf(transaction))

    // Act: invoke the real production transfer path.
    AppDatabase.transferPlaintextData(sourceDb, targetDb)

    // Assert: the colliding source row was skipped, not duplicated...
    val persons = targetDb.personDao().getAllPersonsIncludingArchivedBlocking()
    assertEquals("Collision must not create a second person", 1, persons.size)

    // ...and no dangling personId survives: both rows re-link to the target person.
    val migratedLoan = targetDb.loanDao().getAllLoansBlocking().single()
    assertEquals(
      "Loan personId remapped to the stored person",
      existingTargetPerson.id,
      migratedLoan.personId
    )
    val migratedTx = targetDb.transactionDao().getAllTransactionsBlocking().single()
    assertEquals(
      "Transaction personId remapped to the stored person",
      existingTargetPerson.id,
      migratedTx.personId
    )
  }
}
