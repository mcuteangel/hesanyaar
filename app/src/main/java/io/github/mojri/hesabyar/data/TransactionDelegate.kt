package io.github.mojri.hesabyar.data

import kotlinx.coroutines.flow.Flow

internal class TransactionDelegate(
  private val transactionDao: TransactionDao
) : TransactionOps {
  override val allTransactions: Flow<List<Transaction>> = transactionDao.getAllTransactions()

  override fun getTransactionsInRange(
    start: Long,
    end: Long
  ): Flow<List<Transaction>> = transactionDao.getTransactionsInRange(start, end)

  override suspend fun insertTransaction(transaction: Transaction): Long = transactionDao.insertTransaction(transaction)

  override suspend fun deleteTransaction(transaction: Transaction) {
    transactionDao.deleteTransaction(transaction)
  }

  override suspend fun updateTransaction(transaction: Transaction) {
    transactionDao.updateTransaction(transaction)
  }
}
