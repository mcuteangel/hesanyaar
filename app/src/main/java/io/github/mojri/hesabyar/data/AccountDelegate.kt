package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import io.github.mojri.hesabyar.domain.exception.CannotDeleteLastActiveAccountException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal class AccountDelegate(
  private val accountDao: AccountDao,
  private val database: AppDatabase
) : AccountOps {
  override val allAccounts: Flow<List<AccountEntity>> = accountDao.getAllAccounts()

  override suspend fun getActiveAccounts(): List<AccountEntity> = accountDao.getActiveAccounts().first()

  override suspend fun getAllAccounts(): List<AccountEntity> = accountDao.getAllAccountsBlocking()

  override suspend fun getAccountById(id: Long): AccountEntity? = accountDao.getById(id)

  override suspend fun insertAccount(account: AccountEntity): Long = accountDao.insert(account)

  override suspend fun updateAccount(account: AccountEntity) = accountDao.update(account)

  override suspend fun deleteAccount(account: AccountEntity) =
    database.withTransaction {
      val allAccounts = accountDao.getAllAccountsBlocking()
      val activeAccountCount = allAccounts.count { !it.isArchived }
      if (activeAccountCount == 1 && allAccounts.any { it.id == account.id && !it.isArchived }) {
        throw CannotDeleteLastActiveAccountException(account.id)
      }
      val count = accountDao.getTransactionCountForAccount(account.id)
      if (count > 0) {
        throw IllegalStateException("Account ${account.id} has $count transactions and cannot be deleted")
      }
      accountDao.delete(account)
    }

  override suspend fun getTransactionCountForAccount(accountId: Long): Int =
    accountDao.getTransactionCountForAccount(accountId)

  override suspend fun getMaxDisplayOrder(): Int = accountDao.getMaxDisplayOrder()
}
