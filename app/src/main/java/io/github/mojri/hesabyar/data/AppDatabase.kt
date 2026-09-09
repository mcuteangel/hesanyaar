package io.github.mojri.hesabyar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.IOException

@Database(
  entities = [
    Transaction::class,
    Loan::class,
    Installment::class,
    PaymentHistory::class,
    Category::class,
    BankLoan::class,
    AccountEntity::class,
    Person::class
  ],
  version = 8,
  exportSchema = false
)
@TypeConverters(io.github.mojri.hesabyar.data.TypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun transactionDao(): TransactionDao

  abstract fun transactionLinkDao(): TransactionLinkDao

  abstract fun loanDao(): LoanDao

  abstract fun loanPersonOpsDao(): LoanPersonOpsDao

  abstract fun installmentDao(): InstallmentDao

  abstract fun paymentHistoryDao(): PaymentHistoryDao

  abstract fun categoryDao(): CategoryDao

  abstract fun bankLoanDao(): BankLoanDao

  abstract fun accountDao(): AccountDao

  abstract fun personDao(): PersonDao

  companion object {
    @Volatile
    private var instance: AppDatabase? = null
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // internal visibility: tested by AppDatabaseMigrationTest
    internal val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "CREATE TABLE transactions_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, category TEXT NOT NULL, amount INTEGER NOT NULL, description TEXT NOT NULL, personName TEXT, date INTEGER NOT NULL, dueDate INTEGER, installmentId INTEGER)"
          )
          db.execSQL(
            "INSERT INTO transactions_new (id, type, category, amount, description, personName, date, dueDate, installmentId) SELECT id, type, category, CAST(amount * 1000 AS INTEGER), description, personName, date, dueDate, installmentId FROM transactions"
          )
          db.execSQL("DROP TABLE transactions")
          db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")

          db.execSQL(
            "CREATE TABLE loans_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, personName TEXT NOT NULL, type TEXT NOT NULL, originalAmount INTEGER NOT NULL, remainingAmount INTEGER NOT NULL, description TEXT NOT NULL, date INTEGER NOT NULL, isSettled INTEGER NOT NULL)"
          )
          db.execSQL(
            "INSERT INTO loans_new (id, personName, type, originalAmount, remainingAmount, description, date, isSettled) SELECT id, personName, type, CAST(originalAmount * 1000 AS INTEGER), CAST(remainingAmount * 1000 AS INTEGER), description, date, isSettled FROM loans"
          )
          db.execSQL("DROP TABLE loans")
          db.execSQL("ALTER TABLE loans_new RENAME TO loans")

          db.execSQL(
            "CREATE TABLE installments_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, amount INTEGER NOT NULL, dueDate INTEGER NOT NULL, isPaid INTEGER NOT NULL, reminderEnabled INTEGER NOT NULL, notes TEXT NOT NULL)"
          )
          db.execSQL(
            "INSERT INTO installments_new (id, title, amount, dueDate, isPaid, reminderEnabled, notes) SELECT id, title, CAST(amount * 1000 AS INTEGER), dueDate, isPaid, reminderEnabled, notes FROM installments"
          )
          db.execSQL("DROP TABLE installments")
          db.execSQL("ALTER TABLE installments_new RENAME TO installments")

          db.execSQL(
            "CREATE TABLE payment_history_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, loanId INTEGER NOT NULL, amount INTEGER NOT NULL, date INTEGER NOT NULL, notes TEXT NOT NULL)"
          )
          db.execSQL(
            "INSERT INTO payment_history_new (id, loanId, amount, date, notes) SELECT id, loanId, CAST(amount * 1000 AS INTEGER), date, notes FROM payment_history"
          )
          db.execSQL("DROP TABLE payment_history")
          db.execSQL("ALTER TABLE payment_history_new RENAME TO payment_history")
        }
      }

    /**
     * Corrects amounts inflated by MIGRATION_1_2 (*1000).
     * Correct factor: 1 Toman = 10 Rials → values were 100x too big.
     * Only divides rows with amounts exceeding realistic thresholds to avoid
     * corrupting data that was never inflated by MIGRATION_1_2.
     * Room guarantees this runs exactly once per DB file via _room_master_table.
     * ponytail: one-shot data fix. Remove after all users migrated to v4+.
     */
    internal val MIGRATION_3_4 =
      object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
          // Only divide rows where amounts exceed thresholds that indicate
          // they were inflated by MIGRATION_1_2 (1000x factor, not 10x).
          db.execSQL("UPDATE transactions SET amount = amount / 100 WHERE amount > 1000000000")
          db.execSQL("UPDATE loans SET originalAmount = originalAmount / 100 WHERE originalAmount > 1000000000")
          db.execSQL(
            "UPDATE loans SET remainingAmount = remainingAmount / 100 WHERE remainingAmount > 1000000000"
          )
          db.execSQL("UPDATE installments SET amount = amount / 100 WHERE amount > 1000000000")
          db.execSQL("UPDATE payment_history SET amount = amount / 100 WHERE amount > 1000000000")
        }
      }

    internal val MIGRATION_4_5 =
      object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE bank_loans (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              bankName TEXT NOT NULL,
              loanName TEXT NOT NULL,
              receivedAmount INTEGER NOT NULL,
              monthlyInstallmentAmount INTEGER NOT NULL,
              numberOfInstallments INTEGER NOT NULL,
              totalRepayableAmount INTEGER NOT NULL,
              totalInterest INTEGER NOT NULL,
              startDate INTEGER NOT NULL,
              description TEXT NOT NULL,
              isSettled INTEGER NOT NULL
            )
            """.trimIndent()
          )
          db.execSQL("ALTER TABLE installments ADD COLUMN bankLoanId INTEGER")
        }
      }

    internal val MIGRATION_5_6 =
      object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
          // 1. Create accounts table
          // Shipped literals only — the DEFAULT_COLOR interpolation was replaced
          // with its compiled value (0xFF4CAF50 = 4283215696) so the historical
          // migration is fully frozen, like the DEFAULT_ACCOUNT seed below.
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accounts (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              name TEXT NOT NULL,
              type TEXT NOT NULL,
              bankName TEXT,
              cardNumber TEXT,
              accountNumber TEXT,
              iban TEXT,
              initialBalance INTEGER NOT NULL DEFAULT 0,
              color INTEGER NOT NULL DEFAULT 4283215696,
              icon TEXT,
              isArchived INTEGER NOT NULL DEFAULT 0,
              displayOrder INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
          )

          // 2. Insert default main bank account
          // Intentionally NOT referencing AccountEntity.DEFAULT_ACCOUNT —
          // migrations must stay historically deterministic and frozen
          // regardless of future changes to the constant. The onCreate
          // callback (DEFAULT_ACCOUNT_SEED_CALLBACK) is the live seeding path
          // for fresh installs and reads the constant.
          db.execSQL(
            "INSERT INTO accounts (id, name, type, initialBalance, displayOrder) VALUES (1, 'حساب اصلی', 'BANK', 0, 0)"
          )

          // 3. Add accountId and destinationAccountId to transactions
          db.execSQL("ALTER TABLE transactions ADD COLUMN accountId INTEGER NOT NULL DEFAULT 1")
          db.execSQL("ALTER TABLE transactions ADD COLUMN destinationAccountId INTEGER DEFAULT NULL")
        }
      }

    internal val MIGRATION_6_7 =
      object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
          // Use DEFAULT 0 to match AccountEntity's @ColumnInfo(defaultValue = "0").
          // Existing accounts get timestamp 0 (legacy sentinel); new accounts created
          // in Kotlin code use System.currentTimeMillis() at the app layer.
          db.execSQL("ALTER TABLE accounts ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
          db.execSQL("ALTER TABLE accounts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        }
      }

    /**
     * Phase 1 of the person-ledger redesign (plans/011): additive-only.
     *
     * Creates `persons` (unique index on normalizedName), adds nullable
     * personId to loans AND transactions, then backfills persons from
     * distinct loans.personName and stamps personId on both tables where
     * names normalize to the same key.
     *
     * The order is intentional: loans are processed first (the identity
     * source), then transactions are resolved lookup-only — a transaction
     * is stamped only when its normalized name already maps to a loan-backed
     * person; transaction-only names keep personId NULL and never spawn a
     * phantom Person row (see stampPersonIdsOnTransactions and plans/011
     * §D4 addendum). Display-name tiebreak on a normalized-key collision
     * goes to the loan that was processed first. See plans/011 for the
     * rationale.
     */
    internal val MIGRATION_7_8 =
      object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "CREATE TABLE IF NOT EXISTS persons (" +
              "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
              "name TEXT NOT NULL, normalizedName TEXT NOT NULL, " +
              "phone TEXT, notes TEXT, createdAt INTEGER NOT NULL, " +
              "isArchived INTEGER NOT NULL)"
          )
          db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_persons_normalizedName ON persons (normalizedName)")
          try {
            db.execSQL("ALTER TABLE loans ADD COLUMN personId INTEGER")
          } catch (e: SQLiteException) {
            if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
          }
          try {
            db.execSQL("ALTER TABLE transactions ADD COLUMN personId INTEGER")
          } catch (e: SQLiteException) {
            if (e.message?.contains("duplicate column", ignoreCase = true) != true) throw e
          }

          val idByNormalized = HashMap<String, Long>()
          preloadExistingPersons(db, idByNormalized)
          backfillPersonsFromLoans(db, idByNormalized)
          stampPersonIdsOnTransactions(db, idByNormalized)
        }

        /**
         * Seeds [idByNormalized] from any persons already in the table. This
         * covers the recovery path: when the `personId` columns already exist
         * (duplicate-column guard caught), the persons table may already hold
         * rows from a prior partial migration. Without this preload,
         * [personIdFor] would re-insert existing normalized names and the
         * unique index would abort the migration.
         */
        private fun preloadExistingPersons(
          db: SupportSQLiteDatabase,
          idByNormalized: MutableMap<String, Long>
        ) {
          db
            .query("SELECT id, normalizedName FROM persons")
            .use { cursor ->
              while (cursor.moveToNext()) {
                idByNormalized[cursor.getString(1)] = cursor.getLong(0)
              }
            }
        }

        /** Inserts (or reuses) a person row for [rawName]; returns its id or -1 for blank. */
        private fun personIdFor(
          db: SupportSQLiteDatabase,
          rawName: String,
          idByNormalized: MutableMap<String, Long>
        ): Long {
          val display = PersonNameNormalizer.displayForm(rawName)
          val key = PersonNameNormalizer.normalize(display)
          var result = -1L
          if (display.isNotEmpty() && key.isNotEmpty()) {
            val existing = idByNormalized[key]
            result =
              if (existing != null) {
                existing
              } else {
                val sql =
                  "INSERT INTO persons (name, normalizedName, phone, notes, createdAt, isArchived) " +
                    "VALUES (?, ?, NULL, NULL, ?, 0)"
                val statement = db.compileStatement(sql)
                val id =
                  statement.use { s ->
                    s.bindString(1, display)
                    s.bindString(2, key)
                    s.bindLong(3, System.currentTimeMillis())
                    s.executeInsert()
                  }
                if (id != -1L) idByNormalized[key] = id
                id
              }
          }
          return result
        }

        private fun backfillPersonsFromLoans(
          db: SupportSQLiteDatabase,
          idByNormalized: MutableMap<String, Long>
        ) {
          db.compileStatement("UPDATE loans SET personId = ? WHERE id = ?").use { update ->
            db
              .query("SELECT id, personName FROM loans WHERE personName IS NOT NULL ORDER BY date ASC, id ASC")
              .use { cursor ->
                while (cursor.moveToNext()) {
                  val loanId = cursor.getLong(0)
                  val personId = personIdFor(db, cursor.getString(1), idByNormalized)
                  if (personId == -1L) continue
                  update.bindLong(1, personId)
                  update.bindLong(2, loanId)
                  update.executeUpdateDelete()
                }
              }
          }
        }

        private fun stampPersonIdsOnTransactions(
          db: SupportSQLiteDatabase,
          idByNormalized: MutableMap<String, Long>
        ) {
          // Resolve each transaction's personId by looking up its normalized
          // name in the map pre-populated from loans (the identity source per
          // migration contract D3). Transactions whose name matches no loan
          // name keep personId NULL: we must NOT call the insert-capable
          // personIdFor here, or transaction-only names would spawn phantom
          // persons that pollute the persons table and surface in the person
          // ledger. A second pass then issues a single batched UPDATE per
          // distinct personId instead of one UPDATE per row.
          val updatesByPersonId = HashMap<Long, MutableList<Long>>()
          db
            .query("SELECT id, personName FROM transactions WHERE personName IS NOT NULL")
            .use { cursor ->
              while (cursor.moveToNext()) {
                val rawName = cursor.getString(1)
                val display = PersonNameNormalizer.displayForm(rawName)
                val key = if (display.isNotEmpty()) PersonNameNormalizer.normalize(display) else ""
                val personId = if (key.isNotEmpty()) idByNormalized[key] ?: -1L else -1L
                if (personId == -1L) continue
                updatesByPersonId.getOrPut(personId) { mutableListOf() }.add(cursor.getLong(0))
              }
            }
          if (updatesByPersonId.isEmpty()) return
          // Chunk per person: SQLite's SQLITE_MAX_VARIABLE_NUMBER is 999 on
          // pre-API-26 (32766 on API 26+). A person with >998 transactions would
          // overflow the bound-variable limit and abort the migration for that
          // user. The +1 reserve is for the personId bind.
          val chunkSize = 900
          for ((personId, txIds) in updatesByPersonId) {
            txIds.chunked(chunkSize).forEach { batch ->
              val placeholders = batch.joinToString(",") { "?" }
              db.compileStatement("UPDATE transactions SET personId = ? WHERE id IN ($placeholders)").use { stmt ->
                stmt.bindLong(1, personId)
                batch.forEachIndexed { i, id -> stmt.bindLong(i + 2, id) }
                stmt.executeUpdateDelete()
              }
            }
          }
        }
      }

    internal val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            "CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, key TEXT NOT NULL, icon TEXT NOT NULL, color INTEGER NOT NULL, type TEXT NOT NULL, isDefault INTEGER NOT NULL)"
          )

          val defaults =
            listOf(
              "('خوراک', 'Food', 'Restaurant', 808464432, 'EXPENSE', 1)",
              "('حمل و نقل', 'Transportation', 'DirectionsCar', 4294945536, 'EXPENSE', 1)",
              "('خرید', 'Shopping', 'ShoppingBag', 4283215591, 'EXPENSE', 1)",
              "('قبوض', 'Bills', 'ReceiptLong', 4278241576, 'EXPENSE', 1)",
              "('اقساط', 'Installments', 'CreditCard', 4294198070, 'EXPENSE', 1)",
              "('وام و قرض', 'Loans', 'HistoryEdu', 4286578688, 'BOTH', 1)",
              "('درآمد', 'Income', 'Paid', 808464432, 'INCOME', 1)",
              "('سایر', 'Other', 'Paid', 4285867125, 'BOTH', 1)"
            )
          defaults.forEach { values ->
            db.execSQL("INSERT INTO categories (name, key, icon, color, type, isDefault) VALUES $values")
          }

          db.execSQL(
            "CREATE TABLE transactions_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, categoryId INTEGER NOT NULL, amount INTEGER NOT NULL, description TEXT NOT NULL, personName TEXT, date INTEGER NOT NULL, dueDate INTEGER, installmentId INTEGER)"
          )

          db.execSQL(
            """
                    INSERT INTO transactions_new (id, type, categoryId, amount, description, personName, date, dueDate, installmentId)
                    SELECT t.id, t.type,
                        CASE t.category
                            WHEN 'Food' THEN (SELECT id FROM categories WHERE key = 'Food' LIMIT 1)
                            WHEN 'Transportation' THEN (SELECT id FROM categories WHERE key = 'Transportation' LIMIT 1)
                            WHEN 'Shopping' THEN (SELECT id FROM categories WHERE key = 'Shopping' LIMIT 1)
                            WHEN 'Bills' THEN (SELECT id FROM categories WHERE key = 'Bills' LIMIT 1)
                            WHEN 'Installments' THEN (SELECT id FROM categories WHERE key = 'Installments' LIMIT 1)
                            WHEN 'Loans' THEN (SELECT id FROM categories WHERE key = 'Loans' LIMIT 1)
                            WHEN 'Income' THEN (SELECT id FROM categories WHERE key = 'Income' LIMIT 1)
                            ELSE (SELECT id FROM categories WHERE key = 'Other' LIMIT 1)
                        END,
                        t.amount, t.description, t.personName, t.date, t.dueDate, t.installmentId
                    FROM transactions t
                """
          )

          db.execSQL("DROP TABLE transactions")
          db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")
        }
      }

    /**
     * Seeds the default account (id=1) on genuinely fresh database creation.
     * MIGRATION_5_6 seeds the same row on upgrades from v5, but fresh installs
     * start at the latest schema and never run migrations, so this callback is
     * their only seeding path. `INSERT OR IGNORE` keeps the row unique if id=1
     * already exists (e.g. [migratePlaintextToEncryptedIfNeeded] rebuilds the
     * encrypted database from an older install's data).
     *
     * The row is built from [AccountEntity.DEFAULT_ACCOUNT] — the single
     * source of truth for the default account — so fresh installs always
     * reflect the current definition. Unlike MIGRATION_5_6 (which is
     * intentionally frozen), this callback is allowed to track the constant.
     */
    internal val DEFAULT_ACCOUNT_SEED_CALLBACK =
      object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
          super.onCreate(db)
          val defaultAccount = AccountEntity.DEFAULT_ACCOUNT
          val values =
            ContentValues().apply {
              put("id", defaultAccount.id)
              put("name", defaultAccount.name)
              put("type", defaultAccount.type.name)
              put("bankName", defaultAccount.bankName)
              put("cardNumber", defaultAccount.cardNumber)
              put("accountNumber", defaultAccount.accountNumber)
              put("iban", defaultAccount.iban)
              put("initialBalance", defaultAccount.initialBalance)
              put("color", defaultAccount.color)
              put("icon", defaultAccount.icon)
              put("isArchived", defaultAccount.isArchived)
              put("displayOrder", defaultAccount.displayOrder)
              put("createdAt", defaultAccount.createdAt)
              put("updatedAt", defaultAccount.updatedAt)
            }
          db.insert("accounts", SQLiteDatabase.CONFLICT_IGNORE, values)
        }
      }

    /**
     * Single source of truth for the full migration chain. Used by every
     * `Room.databaseBuilder(...).addMigrations(...)` call so a new migration
     * is registered in one place and cannot be omitted from any path
     * (live DB, plaintext→encrypted transfer, future test factories).
     */
    internal val ALL_MIGRATIONS: Array<Migration> =
      arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8
      )

    fun getDatabase(context: Context): AppDatabase {
      instance?.let { return it }
      return synchronized(this) {
        instance?.let { return it }
        val appContext = context.applicationContext
        System.loadLibrary("sqlcipher")

        val passphrase = DatabaseKeyManager.getOrCreateKey(appContext)
        val factory = SupportOpenHelperFactory(passphrase)

        migratePlaintextToEncryptedIfNeeded(appContext)

        val db =
          Room
            .databaseBuilder(
              appContext,
              AppDatabase::class.java,
              "hesabyar_database"
            ).openHelperFactory(factory)
            .addMigrations(*ALL_MIGRATIONS)
            .addCallback(DEFAULT_ACCOUNT_SEED_CALLBACK)
            .build()
        instance = db
        db
      }
    }

    private fun isPlaintextDb(dbFile: File): Boolean {
      if (!dbFile.exists()) return false
      return try {
        val header = ByteArray(16)
        java.io.FileInputStream(dbFile).use { it.read(header) }
        String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
      } catch (e: Exception) {
        println("Failed to validate SQLite header for ${dbFile.path}: ${e.message}")
        false
      }
    }

    private fun migratePlaintextToEncryptedIfNeeded(context: Context) {
      val dbFile = context.getDatabasePath("hesabyar_database")
      if (!isPlaintextDb(dbFile)) return

      val tempName = "hesabyar_database_plaintext_backup"
      val tempDbFile = context.getDatabasePath(tempName)

      dbFile.copyTo(tempDbFile, overwrite = true)
      listOf("-wal", "-shm")
        .map { context.getDatabasePath("hesabyar_database$it") }
        .filter { it.exists() }
        .forEach {
          it.copyTo(
            context.getDatabasePath("$tempName${it.name.removePrefix("hesabyar_database")}"),
            overwrite = true
          )
        }

      val plaintextDb =
        Room
          .databaseBuilder(context, AppDatabase::class.java, tempName)
          .addMigrations(*ALL_MIGRATIONS)
          .build()

      val accounts = plaintextDb.accountDao().getAllAccountsBlocking()
      val categories = plaintextDb.categoryDao().getAllCategoriesBlocking()
      val transactions = plaintextDb.transactionDao().getAllTransactionsBlocking()
      val loans = plaintextDb.loanDao().getAllLoansBlocking()
      val bankLoans = plaintextDb.bankLoanDao().getAllBankLoansBlocking()
      val installments = plaintextDb.installmentDao().getAllInstallmentsBlocking()
      val payments = plaintextDb.paymentHistoryDao().getAllPaymentHistoriesBlocking()
      val persons = plaintextDb.personDao().getAllPersonsIncludingArchivedBlocking()

      plaintextDb.close()

      dbFile.delete()
      context.getDatabasePath("hesabyar_database-wal").delete()
      context.getDatabasePath("hesabyar_database-shm").delete()

      try {
        val passphrase = DatabaseKeyManager.getOrCreateKey(context)
        val factory = SupportOpenHelperFactory(passphrase)
        val encryptedDb =
          Room
            .databaseBuilder(context, AppDatabase::class.java, "hesabyar_database")
            .openHelperFactory(factory)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

        transferPlaintextData(
          accounts = accounts,
          categories = categories,
          transactions = transactions,
          loans = loans,
          bankLoans = bankLoans,
          installments = installments,
          payments = payments,
          persons = persons,
          encryptedDb = encryptedDb
        )

        encryptedDb.close()

        context.getDatabasePath(tempName).delete()
        context.getDatabasePath("$tempName-wal").delete()
        context.getDatabasePath("$tempName-shm").delete()
      } catch (e: IOException) {
        restoreTempBackupToLive(context, tempName, dbFile)
        throw e
      } catch (e: SQLiteException) {
        restoreTempBackupToLive(context, tempName, dbFile)
        throw e
      }
    }

    private fun restoreTempBackupToLive(
      context: Context,
      tempName: String,
      dbFile: File
    ) {
      context.getDatabasePath(tempName).copyTo(dbFile, overwrite = true)
      listOf("-wal", "-shm")
        .map { context.getDatabasePath("$tempName$it") }
        .filter { it.exists() }
        .forEach {
          it.copyTo(
            context.getDatabasePath("hesabyar_database${it.name.removePrefix(tempName)}"),
            overwrite = true
          )
        }
    }

    /**
     * Test seam for the plaintext→encrypted transfer. Copies all tables from
     * the read snapshot into [encryptedDb] in one transaction. Exposed as
     * `internal` so [AppDatabaseMigrationTest] can invoke the real transfer
     * path with in-memory DBs (no sqlcipher/header checks) and verify that
     * archived persons and every other table survive the round-trip.
     *
     * Persons are inserted first. [PersonDao.insertAllBlocking] uses
     * [OnConflictStrategy.IGNORE], so a `normalizedName` collision drops the
     * source row while its original id is stamped on loans and transactions.
     * The id remap below re-links those rows to the person id that actually
     * exists in [encryptedDb] (resolved by normalized key), so no loan or
     * transaction dangles after a collision.
     */
    internal fun transferPlaintextData(
      accounts: List<AccountEntity>,
      categories: List<Category>,
      transactions: List<Transaction>,
      loans: List<Loan>,
      bankLoans: List<BankLoan>,
      installments: List<Installment>,
      payments: List<PaymentHistory>,
      persons: List<Person>,
      encryptedDb: AppDatabase
    ) {
      encryptedDb.runInTransaction {
        if (persons.isNotEmpty()) encryptedDb.personDao().insertAllBlocking(persons)
        val personIdRemap = personIdRemapForTransfer(persons, encryptedDb)
        if (accounts.isNotEmpty()) encryptedDb.accountDao().insertAllBlocking(accounts)
        if (categories.isNotEmpty()) encryptedDb.categoryDao().insertAllBlocking(categories)
        if (transactions.isNotEmpty()) {
          encryptedDb.transactionDao().insertAllBlocking(
            transactions.map { it.copy(personId = remapPersonId(it.personId, personIdRemap)) }
          )
        }
        if (loans.isNotEmpty()) {
          encryptedDb.loanDao().insertAllBlocking(
            loans.map { it.copy(personId = remapPersonId(it.personId, personIdRemap)) }
          )
        }
        if (bankLoans.isNotEmpty()) encryptedDb.bankLoanDao().insertAllBlocking(bankLoans)
        if (installments.isNotEmpty()) encryptedDb.installmentDao().insertAllBlocking(installments)
        if (payments.isNotEmpty()) encryptedDb.paymentHistoryDao().insertAllBlocking(payments)
      }
    }

    /**
     * Builds a source-person-id → stored-person-id map for a transfer. A
     * mapping exists only when [PersonDao.insertAllBlocking] skipped the
     * source row (normalized-key collision) and a different row carries that
     * key in the target.
     */
    private fun personIdRemapForTransfer(
      persons: List<Person>,
      encryptedDb: AppDatabase
    ): Map<Long, Long> {
      if (persons.isEmpty()) return emptyMap()
      val idByKey =
        encryptedDb
          .personDao()
          .getAllPersonsIncludingArchivedBlocking()
          .associateBy({ it.normalizedName }, { it.id })
      return persons
        .mapNotNull { source ->
          val storedId = idByKey[source.normalizedName] ?: return@mapNotNull null
          if (storedId != source.id) source.id to storedId else null
        }.toMap()
    }

    private fun remapPersonId(
      personId: Long?,
      remap: Map<Long, Long>
    ): Long? = personId?.let { remap[it] ?: it }

    /** In-memory variant that reads from [plaintextDb] and writes to [encryptedDb]. */
    internal fun transferPlaintextData(
      plaintextDb: AppDatabase,
      encryptedDb: AppDatabase
    ) {
      transferPlaintextData(
        accounts = plaintextDb.accountDao().getAllAccountsBlocking(),
        categories = plaintextDb.categoryDao().getAllCategoriesBlocking(),
        transactions = plaintextDb.transactionDao().getAllTransactionsBlocking(),
        loans = plaintextDb.loanDao().getAllLoansBlocking(),
        bankLoans = plaintextDb.bankLoanDao().getAllBankLoansBlocking(),
        installments = plaintextDb.installmentDao().getAllInstallmentsBlocking(),
        payments = plaintextDb.paymentHistoryDao().getAllPaymentHistoriesBlocking(),
        persons = plaintextDb.personDao().getAllPersonsIncludingArchivedBlocking(),
        encryptedDb = encryptedDb
      )
    }
  }
}
