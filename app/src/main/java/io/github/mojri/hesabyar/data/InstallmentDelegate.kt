package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

internal class InstallmentDelegate(
  private val installmentDao: InstallmentDao,
  private val transactionDao: TransactionDao,
  private val transactionLinkDao: TransactionLinkDao,
  private val categoryDao: CategoryDao,
  private val database: AppDatabase
) : InstallmentOps {
  override val allInstallments: Flow<List<Installment>> = installmentDao.getAllInstallments()

  override suspend fun insertInstallment(installment: Installment): Long = installmentDao.insertInstallment(installment)

  override suspend fun updateInstallment(installment: Installment) {
    database.withTransaction {
      // A stale or already-deleted installment updates zero rows and must not
      // trigger a payment transition for a row that does not exist.
      val existing =
        installmentDao.getInstallmentById(installment.id)
          ?: return@withTransaction
      installmentDao.updateInstallment(installment)
      val justPaid = installment.isPaid && !existing.isPaid
      val justUnpaid = !installment.isPaid && existing.isPaid
      val installmentsCategory = categoryDao.getCategoryByKey("Installments")
      if (justPaid) {
        val category =
          installmentsCategory
            ?: throw IllegalStateException(
              "Installments category is missing; cannot record the paid installment expense"
            )
        transactionDao.insertTransaction(
          Transaction(
            type = TransactionType.EXPENSE,
            categoryId = category.id,
            amount = installment.amount,
            description = "پرداخت قسط: ${installment.title} - ${installment.notes}",
            installmentId = installment.id
          )
        )
      } else if (justUnpaid) {
        // Reverse the expense recorded when the installment was first paid, so
        // toggling paid → unpaid → paid never double-counts the money.
        val category = installmentsCategory ?: return@withTransaction
        transactionLinkDao.deleteTransactionForInstallment(
          installmentId = installment.id,
          categoryId = category.id
        )
      }
    }
  }

  override suspend fun deleteInstallment(installment: Installment) {
    installmentDao.deleteInstallment(installment)
  }
}
