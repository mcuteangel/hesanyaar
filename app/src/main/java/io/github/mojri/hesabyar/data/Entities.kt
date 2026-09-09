package io.github.mojri.hesabyar.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "categories")
data class Category(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val key: String,
  val icon: String,
  val color: Long,
  val type: CategoryType,
  val isDefault: Boolean = false
) : Serializable {
  companion object {
    val DEFAULTS =
      listOf(
        Category(
          name = "خوراک",
          key = "Food",
          icon = "Restaurant",
          color = 0xFF4CAF50L,
          type = CategoryType.EXPENSE,
          isDefault = true
        ),
        Category(
          name = "حمل و نقل",
          key = "Transportation",
          icon = "DirectionsCar",
          color = 0xFFFF9800L,
          type = CategoryType.EXPENSE,
          isDefault = true
        ),
        Category(
          name = "خرید",
          key = "Shopping",
          icon = "ShoppingBag",
          color = 0xFF2196F3L,
          type = CategoryType.EXPENSE,
          isDefault = true
        ),
        Category(
          name = "قبوض",
          key = "Bills",
          icon = "ReceiptLong",
          color = 0xFF009688L,
          type = CategoryType.EXPENSE,
          isDefault = true
        ),
        Category(
          name = "اقساط",
          key = "Installments",
          icon = "CreditCard",
          color = 0xFFF44336L,
          type = CategoryType.EXPENSE,
          isDefault = true
        ),
        Category(
          name = "وام و قرض",
          key = "Loans",
          icon = "HistoryEdu",
          color = 0xFF9C27B0L,
          type = CategoryType.BOTH,
          isDefault = true
        ),
        Category(
          name = "درآمد",
          key = "Income",
          icon = "Paid",
          color = 0xFF4CAF50L,
          type = CategoryType.INCOME,
          isDefault = true
        ),
        Category(
          name = "سایر",
          key = "Other",
          icon = "Paid",
          color = 0xFF757575L,
          type = CategoryType.BOTH,
          isDefault = true
        )
      )
  }
}

@Entity(
  tableName = "persons",
  indices = [Index(value = ["normalizedName"], unique = true)]
)
data class Person(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  // First trimmed original spelling seen; never normalized for display.
  val name: String,
  // Dedup key from PersonNameNormalizer; unique across the table.
  val normalizedName: String,
  val phone: String? = null,
  val notes: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val isArchived: Boolean = false
)

@Entity(tableName = "transactions")
data class Transaction(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val type: TransactionType,
  val categoryId: Long,
  // Rial
  val amount: Long,
  val description: String,
  val personName: String? = null,
  // Nullable reference to persons (NOT an enforced Room FK — the migration
  // creates a plain INTEGER column). Denormalized personName stays the
  // display source (D3). Nullable: legacy rows and rows whose name predates
  // the persons table.
  val personId: Long? = null,
  val date: Long = System.currentTimeMillis(),
  val dueDate: Long? = null,
  val installmentId: Long? = null,
  // FK to accounts (default: main account).
  // No @ForeignKey constraint here — requires a Room migration to ensure the default
  // account exists before the constraint is applied. Tracked as tech-debt in #151.
  // Referential integrity is enforced at the application layer
  // (SubmitManualTransactionUseCase validates accountId before insert).
  val accountId: Long = DEFAULT_ACCOUNT_ID,
  // For internal transfers
  val destinationAccountId: Long? = null
) : Serializable

/** Default account ID used for backward-compatible data and new-transaction defaults.
 *  Must match AccountEntity.DEFAULT_ACCOUNT.id and the migration INSERT. */
const val DEFAULT_ACCOUNT_ID = 1L

@Entity(tableName = "loans")
data class Loan(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val personName: String,
  // Nullable reference to persons (NOT an enforced Room FK — the migration
  // creates a plain INTEGER column). Denormalized personName stays the
  // display source (D3). Nullable: legacy rows created before the persons table.
  val personId: Long? = null,
  val type: LoanType,
  // Rial
  val originalAmount: Long,
  // Rial
  val remainingAmount: Long,
  val description: String,
  val date: Long = System.currentTimeMillis(),
  val isSettled: Boolean = false
) : Serializable

@Entity(tableName = "installments")
data class Installment(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val title: String,
  // Rial
  val amount: Long,
  val dueDate: Long,
  val isPaid: Boolean = false,
  val reminderEnabled: Boolean = true,
  val notes: String = "",
  val bankLoanId: Long? = null
) : Serializable

@Entity(tableName = "payment_history")
data class PaymentHistory(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val loanId: Long,
  val amount: Long, // Rial
  val date: Long = System.currentTimeMillis(),
  val notes: String = ""
) : Serializable

@Entity(tableName = "bank_loans")
data class BankLoan(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val bankName: String,
  val loanName: String,
  val receivedAmount: Long, // Rial (actual disbursed amount)
  val monthlyInstallmentAmount: Long, // Rial
  val numberOfInstallments: Int,
  val totalRepayableAmount: Long, // Rial
  val totalInterest: Long, // Rial
  val startDate: Long, // epoch millis
  val description: String,
  val isSettled: Boolean = false
) : Serializable {
  companion object {
    private const val serialVersionUID: Long = 1L
  }
}
