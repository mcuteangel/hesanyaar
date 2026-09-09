package io.github.mojri.hesabyar.data

import io.github.mojri.hesabyar.data.AccountEntity
import io.github.mojri.hesabyar.data.BankLoan

interface HesabyarRepositoryInterface :
  PersonRepositoryInterface,
  AccountOps,
  TransactionOps,
  CategoryOps,
  LoanOps,
  InstallmentOps,
  BankLoanOps,
  BackupOps
