package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import io.github.mojri.hesabyar.core.AppLogger
import kotlinx.coroutines.flow.Flow

internal class CategoryDelegate(
  private val categoryDao: CategoryDao,
  private val database: AppDatabase
) : CategoryOps {
  override val allCategories: Flow<List<Category>> = categoryDao.getAllCategories()

  override fun getCategoriesByType(type: String): Flow<List<Category>> = categoryDao.getCategoriesByType(type)

  override suspend fun getCategoryById(id: Long): Category? = categoryDao.getCategoryById(id)

  override suspend fun getCategoryByKey(key: String): Category? = categoryDao.getCategoryByKey(key)

  override suspend fun insertCategory(category: Category): Long = categoryDao.insertCategory(category)

  override suspend fun updateCategory(category: Category) {
    categoryDao.updateCategory(category)
  }

  override suspend fun deleteCategory(category: Category) {
    database.withTransaction {
      val persisted = categoryDao.getCategoryById(category.id)
      if (persisted == null) {
        AppLogger.w("HesabyarRepository", "deleteCategory: category id=${category.id} not found; nothing to delete")
        return@withTransaction
      }
      if (persisted.isDefault) {
        AppLogger.w(
          "HesabyarRepository",
          "deleteCategory: refusing to delete default category id=${persisted.id} key=${persisted.key} " +
            "(caller-provided isDefault=${category.isDefault})"
        )
        return@withTransaction
      }
      categoryDao.deleteCategory(persisted)
    }
  }
}
