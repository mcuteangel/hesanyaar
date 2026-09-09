package io.github.mojri.hesabyar.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.mojri.hesabyar.data.AccountDao
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.BankLoanDao
import io.github.mojri.hesabyar.data.CategoryDao
import io.github.mojri.hesabyar.data.InstallmentDao
import io.github.mojri.hesabyar.data.LoanDao
import io.github.mojri.hesabyar.data.PaymentHistoryDao
import io.github.mojri.hesabyar.data.PersonDao
import io.github.mojri.hesabyar.data.TransactionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
  @Provides
  @Singleton
  fun provideDatabase(
    @ApplicationContext context: Context
  ): AppDatabase = AppDatabase.getDatabase(context)

  @Provides
  fun provideTransactionDao(database: AppDatabase): TransactionDao = database.transactionDao()

  @Provides
  fun provideLoanDao(database: AppDatabase): LoanDao = database.loanDao()

  @Provides
  fun provideInstallmentDao(database: AppDatabase): InstallmentDao = database.installmentDao()

  @Provides
  fun providePaymentHistoryDao(database: AppDatabase): PaymentHistoryDao = database.paymentHistoryDao()

  @Provides
  fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

  @Provides
  fun provideBankLoanDao(database: AppDatabase): BankLoanDao = database.bankLoanDao()

  @Provides
  fun provideAccountDao(database: AppDatabase): AccountDao = database.accountDao()

  @Provides
  fun providePersonDao(database: AppDatabase): PersonDao = database.personDao()
}
