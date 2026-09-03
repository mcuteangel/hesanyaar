package io.github.mojri.hesabyar.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the plaintext→encrypted transfer body
 * (the read+insert half of [AppDatabase.migratePlaintextToEncryptedIfNeeded])
 * and Room schema migrations (MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).
 *
 * The 3 transfer tests below simulate the read+insert by hand against
 * in-memory DBs; the actual `migratePlaintextToEncryptedIfNeeded` is not
 * invoked here because the real function calls
 * `System.loadLibrary("sqlcipher")` and depends on the encrypted factory
 * (a test-seam refactor is tracked separately — see follow-up issue).
 * If the function's body grows, mirror the additions in the
 * simulated-transfer tests so a code-reviewer can still verify the
 * round-trip is intact.
 *
 * Migration tests verify that Room's schema validation passes after running
 * MIGRATION_5_6 (accounts table) and MIGRATION_6_7 (timestamps) and
 * MIGRATION_7_8 (persons + personId columns), and that AccountEntity
 * and Person data survives the round-trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AppDatabaseMigrationTest {
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

  @Test
  fun accountsSurviveConversionToEncryptedDb() =
    runTest {
      // Arrange: insert accounts into source (simulating plaintext DB)
      val account1 =
        AccountEntity(
          id = 1,
          name = "حساب اصلی",
          type = AccountType.BANK,
          initialBalance = 5_000_000,
          createdAt = 1000L,
          updatedAt = 2000L,
        )
      val account2 =
        AccountEntity(
          id = 2,
          name = "کیف پول",
          type = AccountType.CASH_WALLET,
          initialBalance = 1_000_000,
          createdAt = 3000L,
          updatedAt = 4000L,
        )
      sourceDb.accountDao().insertAllBlocking(listOf(account1, account2))

      // Act: read from source, insert into target (same pattern as migration)
      val accounts = sourceDb.accountDao().getAllAccountsBlocking()
      assertTrue("Source must have accounts", accounts.isNotEmpty())
      targetDb.accountDao().insertAllBlocking(accounts)

      // Assert: accounts survived the round-trip
      val migrated = targetDb.accountDao().getAllAccountsBlocking()
      assertEquals("Should have 2 accounts after migration", 2, migrated.size)

      val migrated1 = migrated.find { it.id == 1L }
      assertEquals("Account 1 name preserved", "حساب اصلی", migrated1?.name)
      assertEquals("Account 1 balance preserved", 5_000_000L, migrated1?.initialBalance)
      assertEquals("Account 1 createdAt preserved", 1000L, migrated1?.createdAt)
      assertEquals("Account 1 updatedAt preserved", 2000L, migrated1?.updatedAt)

      val migrated2 = migrated.find { it.id == 2L }
      assertEquals("Account 2 name preserved", "کیف پول", migrated2?.name)
      assertEquals("Account 2 type preserved", AccountType.CASH_WALLET, migrated2?.type)
    }

  @Test
  fun allEntityTypesSurviveConversion() =
    runTest {
      // Arrange: insert all entity types into source
      val account =
        AccountEntity(
          id = 1,
          name = "حساب اصلی",
          type = AccountType.BANK,
          createdAt = 1000L,
          updatedAt = 2000L,
        )
      sourceDb.accountDao().insertAllBlocking(listOf(account))

      val category =
        Category(
          id = 1,
          name = "خوراک",
          key = "Food",
          icon = "Restaurant",
          color = 808464432,
          type = CategoryType.EXPENSE,
          isDefault = true,
        )
      sourceDb.categoryDao().insertAllBlocking(listOf(category))

      val transaction =
        Transaction(
          id = 1,
          type = TransactionType.EXPENSE,
          categoryId = 1L,
          amount = 100_000L,
          description = "ناهار",
          date = System.currentTimeMillis(),
          accountId = 1,
        )
      sourceDb.transactionDao().insertAllBlocking(listOf(transaction))

      // Act: read all from source, insert into target
      val accounts = sourceDb.accountDao().getAllAccountsBlocking()
      val categories = sourceDb.categoryDao().getAllCategoriesBlocking()
      val transactions = sourceDb.transactionDao().getAllTransactionsBlocking()

      targetDb.accountDao().insertAllBlocking(accounts)
      targetDb.categoryDao().insertAllBlocking(categories)
      targetDb.transactionDao().insertAllBlocking(transactions)

      // Assert: all entity types survived
      assertEquals("Accounts survived", 1, targetDb.accountDao().getAllAccountsBlocking().size)
      assertEquals("Categories survived", 1, targetDb.categoryDao().getAllCategoriesBlocking().size)
      assertEquals("Transactions survived", 1, targetDb.transactionDao().getAllTransactionsBlocking().size)
    }

  @Test
  fun emptyAccountsListDoesNotFail() =
    runTest {
      // Act: read empty accounts from source, insert into target
      val accounts = sourceDb.accountDao().getAllAccountsBlocking()
      assertTrue("Source should have no accounts", accounts.isEmpty())
      targetDb.accountDao().insertAllBlocking(accounts)

      // Assert: target also has no accounts (no crash)
      assertEquals("Target should have no accounts", 0, targetDb.accountDao().getAllAccountsBlocking().size)
    }

  /**
   * Creates a v5 database schema using raw SQL, runs MIGRATION_5_6 and
   * MIGRATION_6_7, then opens the result with Room at version 7. Room's
   * schema validation will fail if the migration-produced schema doesn't
   * match AccountEntity's column definitions.
   *
   * This is the P0 regression test: previously, SQL DEFAULT clauses in the
   * migration didn't match AccountEntity because the entity lacked
   * @ColumnInfo(defaultValue=...) annotations, causing schema validation
   * failures on Android 9+.
   */
  @Test
  fun migration5to7SchemaMatchesAccountEntity() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_test_schema_v5"
    val dbFile = context.getDatabasePath(dbName)

    try {
      assertMigration5to7SchemaMatchesAccountEntity(context, dbName)
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
  }

  /**
   * Creates a v5 database, runs MIGRATION_5_6 and MIGRATION_6_7,
   * then verifies that AccountEntity data survives the round-trip
   * and Room schema validation passes.
   */
  private fun assertMigration5to7SchemaMatchesAccountEntity(
    context: Context,
    dbName: String
  ) {
    // Step 1: Create a raw v5 database using FrameworkSQLiteOpenHelper.
    // This creates the exact v5 schema (no accounts table, no timestamps).
    createRawV5Database(context, dbName)

    // Step 2: Run MIGRATION_5_6, MIGRATION_6_7 and MIGRATION_7_8 on the raw
    // database. We open the database via Room with all migrations; Room will
    // detect the version is 5 and apply 5→6→7→8 (the person-ledger migration).
    val migratedDb =
      Room
        .databaseBuilder(context, AppDatabase::class.java, dbName)
        .allowMainThreadQueries()
        .addMigrations(
          AppDatabase.MIGRATION_5_6,
          AppDatabase.MIGRATION_6_7,
          AppDatabase.MIGRATION_7_8
        ).build()

    // Step 3: Insert and query AccountEntity — if the migration-produced
    // schema doesn't match the entity definition, Room will throw.
    val testAccount =
      AccountEntity(
        id = 1,
        name = "حساب تستی",
        type = AccountType.BANK,
        initialBalance = 2_500_000L,
        color = AccountEntity.DEFAULT_COLOR,
        isArchived = false,
        displayOrder = 0,
        createdAt = 100L,
        updatedAt = 200L,
      )
    migratedDb.accountDao().insertAllBlocking(listOf(testAccount))

    val accounts = migratedDb.accountDao().getAllAccountsBlocking()
    assertEquals("Should have 1 account after migration", 1, accounts.size)

    val saved = accounts.first()
    assertEquals("Account id preserved", 1L, saved.id)
    assertEquals("Account name preserved", "حساب تستی", saved.name)
    assertEquals("Account type preserved", AccountType.BANK, saved.type)
    assertEquals("Account initialBalance preserved", 2_500_000L, saved.initialBalance)
    assertEquals("Account color preserved", AccountEntity.DEFAULT_COLOR, saved.color)
    assertEquals("Account isArchived preserved", false, saved.isArchived)
    assertEquals("Account displayOrder preserved", 0, saved.displayOrder)
    assertEquals("Account createdAt preserved", 100L, saved.createdAt)
    assertEquals("Account updatedAt preserved", 200L, saved.updatedAt)

    // Verify nullable fields with non-default values survive the round-trip
    val accountWithOptionals =
      AccountEntity(
        id = 2,
        name = "حساب با جزئیات",
        type = AccountType.CASH_WALLET,
        bankName = "ملی",
        cardNumber = "6104-XXXX",
        accountNumber = "0123456789",
        iban = "IR123456789012345678901234",
        initialBalance = 500_000L,
        color = 0xFFFF0000L,
        icon = "Wallet",
        isArchived = true,
        displayOrder = 3,
        createdAt = 500L,
        updatedAt = 600L,
      )
    migratedDb.accountDao().insertAllBlocking(listOf(accountWithOptionals))
    assertAccountsWithOptionalsSurvive(migratedDb)
  }

  private fun assertAccountsWithOptionalsSurvive(migratedDb: AppDatabase) {
    val allAccounts = migratedDb.accountDao().getAllAccountsBlocking()
    assertEquals("Should have 2 accounts total", 2, allAccounts.size)

    val saved2 = allAccounts.find { it.id == 2L }
    assertNotNull("Account 2 exists", saved2)
    assertEquals("Account 2 bankName preserved", "ملی", saved2?.bankName)
    assertEquals("Account 2 cardNumber preserved", "6104-XXXX", saved2?.cardNumber)
    assertEquals("Account 2 accountNumber preserved", "0123456789", saved2?.accountNumber)
    assertEquals("Account 2 iban preserved", "IR123456789012345678901234", saved2?.iban)
    assertEquals("Account 2 color preserved", 0xFFFF0000L, saved2?.color)
    assertEquals("Account 2 icon preserved", "Wallet", saved2?.icon)
    assertEquals("Account 2 isArchived preserved", true, saved2?.isArchived)
    assertEquals("Account 2 displayOrder preserved", 3, saved2?.displayOrder)

    migratedDb.close()
  }

  /**
   * Creates a raw v5 database using FrameworkSQLiteOpenHelper, then
   * runs MIGRATION_5_6 and MIGRATION_6_7 via Room. Returns the
   * migrated database for further assertions.
   */
  private fun createRawV5Database(
    context: Context,
    dbName: String
  ): AppDatabase {
    val helper =
      FrameworkSQLiteOpenHelperFactory().create(
        androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration
          .builder(context)
          .name(dbName)
          .callback(
            object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(5) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                createV5SchemaTables(db)
                createV5SchemaRoomMetadata(db)
              }

              override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int
              ) {
                // Not needed — we apply migrations manually via Room
              }
            }
          ).build()
      )
    helper.writableDatabase.close()
    helper.close()

    return Room
      .databaseBuilder(context, AppDatabase::class.java, dbName)
      .allowMainThreadQueries()
      .addMigrations(
        AppDatabase.MIGRATION_5_6,
        AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8
      ).build()
  }

  /**
   * Verifies that the default account (id=1) seeded by MIGRATION_5_6 survives
   * through to MIGRATION_6_7 and can be queried at version 7.
   */
  @Test
  fun defaultAccountFromMigration5SurvivesToVersion7() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_default_acct_test"
    val dbFile = context.getDatabasePath(dbName)

    try {
      // Create a v5 database using raw SQL, then run migrations to v7
      val migratedDb = createRawV5Database(context, dbName)

      // MIGRATION_5_6 inserts a default account: id=1, name='حساب اصلی', type='BANK'
      val accounts = migratedDb.accountDao().getAllAccountsBlocking()
      assertTrue("Default account exists after migration", accounts.isNotEmpty())

      val defaultAccount = accounts.find { it.id == 1L }
      assertNotNull("Default account with id=1 exists", defaultAccount)
      assertEquals("Default account name", "حساب اصلی", defaultAccount?.name)
      assertEquals("Default account type", AccountType.BANK, defaultAccount?.type)
      assertEquals("Default account initialBalance", 0L, defaultAccount?.initialBalance)
      assertEquals("Default account displayOrder", 0, defaultAccount?.displayOrder)
      // Timestamps should be 0 (the DEFAULT 0 from MIGRATION_6_7)
      assertEquals("Default account createdAt defaults to 0", 0L, defaultAccount?.createdAt)
      assertEquals("Default account updatedAt defaults to 0", 0L, defaultAccount?.updatedAt)

      migratedDb.close()
    } finally {
      dbFile.delete()
      context.getDatabasePath("$dbName-wal").delete()
      context.getDatabasePath("$dbName-shm").delete()
    }
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
    sourceDb.loanDao().insertLoan(loan)

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
}
