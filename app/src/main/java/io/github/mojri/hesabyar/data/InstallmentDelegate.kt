package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

internal class InstallmentDelegate(
  private val installmentDao: InstallmentDao,
  private val transactionDao: TransactionDao,
  private val categoryDao: CategoryDao,
  private val database: AppDatabase
) : InstallmentOps {
  override val allInstallments: Flow<List<Installment>> = installmentDao.getAllInstallments()

  override suspend fun insertInstallment(installment: Installment): Long = installmentDao.insertInstallment(installment)

  override suspend fun updateInstallment(installment: Installment) {
    database.withTransaction {
      val existing = installmentDao.getInstallmentById(installment.id)
      installmentDao.updateInstallment(installment)
      val justPaid = installment.isPaid && (existing == null || !existing.isPaid)
      if (justPaid) {
        val installmentsCategory = categoryDao.getCategoryByKey("Installments")
        if (installmentsCategory != null) {
          transactionDao.insertTransaction(
            Transaction(
              type = TransactionType.EXPENSE,
              categoryId = installmentsCategory.id,
              amount = installment.amount,
              description = "پرداخت قسط: ${installment.title} - ${installment.notes}"
            )
          )
        }
      }
    }
  }

  override suspend fun deleteInstallment(installment: Installment) {
    installmentDao.deleteInstallment(installment)
  }
}
