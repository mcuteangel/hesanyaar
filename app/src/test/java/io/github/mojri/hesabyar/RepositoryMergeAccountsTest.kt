package io.github.mojri.hesabyar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.AccountType
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.BackupPayload
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.Transaction
import io.github.mojri.hesabyar.data.TransactionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RepositoryMergeAccountsTest {
  private lateinit var database: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room
        .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    // Production seeds the default account (id=1) on fresh create
    // (AppDatabase onCreate) and on upgrade (MIGRATION_5_6); the in-memory
    // test DB bypasses both, so mirror the invariant here.
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

  private fun account(
    id: Long,
    name: String,
    type: AccountType = AccountType.BANK,
    color: Long = 0L
  ) = AccountEntity(id = id, name = name, type = type, color = color, icon = "", isArchived = false, displayOrder = 0)

  private fun transaction(
    id: Long,
    type: TransactionType,
    accountId: Long,
    amount: Long = 3000L
  ) = Transaction(
    id = id,
    type = type,
    categoryId = 0L,
    amount = amount,
    description = "tx",
    personName = null,
    date = System.currentTimeMillis(),
    accountId = accountId
  )

  private fun backupPayload(
    accounts: List<AccountEntity>,
    transactions: List<Transaction>
  ) = BackupPayload(
    version = 1,
    timestamp = System.currentTimeMillis(),
    appVersion = "1.0",
    accounts = accounts,
    transactions = transactions
  )

  @Test
  fun mergeFromBackupDeduplicatesBackupAccountsSharingTheSameName() =
    runTest {
      val repo = createRepository()
      val backup =
        backupPayload(
          accounts =
            listOf(
              account(id = 10L, name = "پس‌انداز", color = 0xFF4CAF50L),
              account(id = 20L, name = "پس‌انداز", color = 0xFFE91E63L)
            ),
          transactions =
            listOf(
              transaction(id = 1L, type = TransactionType.EXPENSE, accountId = 10L, amount = 1_000L),
              transaction(id = 2L, type = TransactionType.EXPENSE, accountId = 20L, amount = 2_000L)
            )
        )
      repo.mergeFromBackup(backup)

      val savings = database.accountDao().getAllAccountsBlocking().filter { it.name == "پس‌انداز" }
      assertEquals(
        "two same-name backup accounts must collapse into one local row",
        1,
        savings.size
      )
      val txs = database.transactionDao().getAllTransactionsBlocking()
      assertEquals("both backup transactions must be restored", 2, txs.size)
      assertEquals(
        "both backup account ids must map to the same local id",
        txs[0].accountId,
        txs[1].accountId
      )
      assertTrue(savings.any { it.id == txs[0].accountId })
    }

  @Test
  fun mergeFromBackupMergesBackupAccountWithExistingLocalName() =
    runTest {
      val repo = createRepository()
      repo.insertAccount(account(id = 5L, name = "Local Account", color = 0xFF4CAF50L))
      val backup =
        backupPayload(
          accounts = listOf(account(id = 10L, name = "Local Account", color = 0xFFE91E63L)),
          transactions =
            listOf(
              transaction(id = 1L, type = TransactionType.EXPENSE, accountId = 10L, amount = 1_000L)
            )
        )
      repo.mergeFromBackup(backup)

      val localAccounts = database.accountDao().getAllAccountsBlocking().filter { it.name == "Local Account" }
      assertEquals("backup account matching a local name must not create a second row", 1, localAccounts.size)
      val txs = database.transactionDao().getAllTransactionsBlocking()
      assertEquals("backup transaction must be restored", 1, txs.size)
      assertEquals("transaction must be remapped to the existing local account", 5L, txs.first().accountId)
    }

  @Test
  fun mergeFromBackupKeepsBackupAccountsWithDistinctNamesDistinct() =
    runTest {
      val repo = createRepository()
      val backup =
        backupPayload(
          accounts =
            listOf(
              account(id = 10L, name = "کیف پول", color = 0xFF4CAF50L),
              account(id = 20L, name = "بانک ملی", color = 0xFFE91E63L)
            ),
          transactions =
            listOf(
              transaction(id = 1L, type = TransactionType.EXPENSE, accountId = 10L, amount = 1_000L),
              transaction(id = 2L, type = TransactionType.EXPENSE, accountId = 20L, amount = 2_000L)
            )
        )
      repo.mergeFromBackup(backup)

      val merged = database.accountDao().getAllAccountsBlocking()
      assertEquals("default + two distinct backup accounts", 3, merged.size)
      val txs = database.transactionDao().getAllTransactionsBlocking()
      assertEquals("both distinct-name backup transactions must be restored", 2, txs.size)
      assertNotEquals(
        "distinct backup names must stay distinct (no cross-merging)",
        txs[0].accountId,
        txs[1].accountId
      )
    }
}
