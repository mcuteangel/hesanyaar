package io.github.mojri.hesabyar.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.mojri.hesabyar.data.AccountDao
import io.github.mojri.hesabyar.data.AppDatabase
import io.github.mojri.hesabyar.data.BankLoanDao
import io.github.mojri.hesabyar.data.CategoryDao
import io.github.mojri.hesabyar.data.HesabyarRepository
import io.github.mojri.hesabyar.data.HesabyarRepositoryInterface
import io.github.mojri.hesabyar.data.InstallmentDao
import io.github.mojri.hesabyar.data.LoanDao
import io.github.mojri.hesabyar.data.PaymentHistoryDao
import io.github.mojri.hesabyar.data.PersonDao
import io.github.mojri.hesabyar.data.PersonRepositoryInterface
import io.github.mojri.hesabyar.data.TransactionDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
  // Returns the concrete type, not an interface: Dagger registers a binding for
  // the declared return type only, and HesabyarRepository has no @Inject
  // constructor. Both interface bindings are derived from the concrete one
  // below. Returning HesabyarRepositoryInterface here would leave
  // PersonRepositoryInterface without a source binding.
  @Provides
  @Singleton
  fun provideRepository(
    transactionDao: TransactionDao,
    loanDao: LoanDao,
    installmentDao: InstallmentDao,
    paymentHistoryDao: PaymentHistoryDao,
    categoryDao: CategoryDao,
    bankLoanDao: BankLoanDao,
    accountDao: AccountDao,
    personDao: PersonDao,
    database: AppDatabase
  ): HesabyarRepository =
    HesabyarRepository(
      transactionDao,
      loanDao,
      installmentDao,
      paymentHistoryDao,
      categoryDao,
      bankLoanDao,
      accountDao,
      personDao,
      database
    )

  @Provides
  fun provideHesabyarRepository(repository: HesabyarRepository): HesabyarRepositoryInterface = repository

  @Provides
  fun providePersonRepository(repository: HesabyarRepository): PersonRepositoryInterface = repository
}
