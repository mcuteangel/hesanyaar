package io.github.mojri.hesabyar.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mojri.hesabyar.R
import io.github.mojri.hesabyar.data.Loan
import io.github.mojri.hesabyar.data.LoanType
import io.github.mojri.hesabyar.domain.utils.LoanEditCalculator
import io.github.mojri.hesabyar.ui.CurrencyFormatter
import io.github.mojri.hesabyar.ui.LoanViewModel
import io.github.mojri.hesabyar.ui.SettingsViewModel
import io.github.mojri.hesabyar.ui.components.ButtonVariant
import io.github.mojri.hesabyar.ui.components.HesabyarButton
import io.github.mojri.hesabyar.ui.components.HesabyarCard
import io.github.mojri.hesabyar.ui.components.HesabyarChip
import io.github.mojri.hesabyar.ui.components.HesabyarInputField
import io.github.mojri.hesabyar.ui.components.IconCircle
import io.github.mojri.hesabyar.ui.components.JalaliDateTimePicker
import io.github.mojri.hesabyar.ui.designsystem.Dimens
import io.github.mojri.hesabyar.ui.designsystem.ShapeTokens
import io.github.mojri.hesabyar.ui.designsystem.SpacingTokens
import io.github.mojri.hesabyar.ui.utils.formatPersianDate
import java.util.*

private const val TOMAN_TO_RIAL_FACTOR = 10L

@Composable
private fun LoanTypeSelector(
  loanType: LoanType,
  onTypeChange: (LoanType) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(ShapeTokens.Small)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(SpacingTokens.xs)
  ) {
    HesabyarButton(
      onClick = { onTypeChange(LoanType.DEBTOR) },
      modifier = Modifier.weight(1f),
      text = stringResource(R.string.loan_type_debtor),
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (loanType == LoanType.DEBTOR) {
              MaterialTheme.colorScheme.primary
            } else {
              Color.Transparent
            },
          contentColor =
            if (loanType == LoanType.DEBTOR) {
              MaterialTheme.colorScheme.onPrimary
            } else {
              MaterialTheme.colorScheme.onSurface
            }
        )
    )
    HesabyarButton(
      onClick = { onTypeChange(LoanType.CREDITOR) },
      modifier = Modifier.weight(1f),
      text = stringResource(R.string.loan_type_creditor),
      colors =
        ButtonDefaults.buttonColors(
          containerColor =
            if (loanType == LoanType.CREDITOR) {
              MaterialTheme.colorScheme.error
            } else {
              Color.Transparent
            },
          contentColor =
            if (loanType == LoanType.CREDITOR) {
              MaterialTheme.colorScheme.onError
            } else {
              MaterialTheme.colorScheme.onSurface
            }
        )
    )
  }
}

@Composable
fun LoanManagementScreen(
  loanViewModel: LoanViewModel,
  settingsViewModel: SettingsViewModel,
  modifier: Modifier = Modifier
) {
  val loans by loanViewModel.loans.collectAsState()

  var showAddDialog by remember { mutableStateOf(false) }
  var editingLoan by remember { mutableStateOf<Loan?>(null) }
  var deletingLoan by remember { mutableStateOf<Loan?>(null) }
  var termState by remember { mutableStateOf(LoanType.DEBTOR) } // DEBTOR = they owe me, CREDITOR = I owe them

  // Filtered lists
  val debtors = loans.filter { it.type == LoanType.DEBTOR }
  val creditors = loans.filter { it.type == LoanType.CREDITOR }

  Column(
    modifier =
      modifier
        .fillMaxSize()
        .padding(SpacingTokens.lg),
    verticalArrangement = Arrangement.spacedBy(SpacingTokens.lg)
  ) {
    // Stats and trigger row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "دفتر قرض و امور مالی اشخاص",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
      )

      HesabyarButton(
        onClick = { showAddDialog = true },
        text = "ثبت جدید",
        icon = Icons.Filled.Add,
        modifier = Modifier.testTag("add_loan_button")
      )
    }

    // Tab selection (Debtors vs Creditors)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)
    ) {
      HesabyarChip(
        selected = termState == LoanType.DEBTOR,
        onClick = { termState = LoanType.DEBTOR },
        label = "طلب‌های من (بدهکاران)",
        modifier = Modifier.weight(1f)
      )

      HesabyarChip(
        selected = termState == LoanType.CREDITOR,
        onClick = { termState = LoanType.CREDITOR },
        label = "بدهی‌های من (طلبکاران)",
        modifier = Modifier.weight(1f)
      )
    }

    val activeList = if (termState == LoanType.DEBTOR) debtors else creditors

    if (activeList.isEmpty()) {
      Box(
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
        ) {
          Icon(
            imageVector =
              if (termState ==
                LoanType.DEBTOR
              ) {
                Icons.Filled.ArrowCircleDown
              } else {
                Icons.Filled.ArrowCircleUp
              },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
          )
          Text(
            text = if (termState == LoanType.DEBTOR) "هیچ طلبی ثبت نشده است." else "هیچ بدهی‌ای ثبت نشده است.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
      ) {
        items(activeList) { loan ->
          LoanListItem(
            loan = loan,
            loanViewModel = loanViewModel,
            settingsViewModel = settingsViewModel,
            onDelete = { deletingLoan = loan },
            onEdit = { editingLoan = loan }
          )
        }
      }
    }
  }

  // Add Loan Dialog
  if (showAddDialog) {
    AddLoanDialog(
      initialType = termState,
      showMessage = settingsViewModel::showMessage,
      onConfirm = { personName, loanType, amountRial, description, customDate ->
        loanViewModel.addLoan(personName, loanType, amountRial, description, customDate)
        showAddDialog = false
      },
      onDismiss = { showAddDialog = false }
    )
  }

  // Edit Loan Dialog
  editingLoan?.let { loanToEdit ->
    EditLoanDialog(
      loan = loanToEdit,
      onUpdate = { updated ->
        loanViewModel.updateLoan(updated)
        editingLoan = null
      },
      showMessage = settingsViewModel::showMessage,
      onDismiss = { editingLoan = null }
    )
  }

  // Delete confirmation dialog
  deletingLoan?.let { loanToDelete ->
    DeleteLoanDialog(
      personName = loanToDelete.personName,
      onConfirm = {
        loanViewModel.deleteLoan(loanToDelete)
        deletingLoan = null
      },
      onDismiss = { deletingLoan = null }
    )
  }
}

@Composable
private fun DeleteLoanDialog(
  personName: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        "حذف قرض",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Right
      )
    },
    text = {
      val message =
        "قرض $personName و تمام تاریخچه بازپرداخت‌های آن حذف شود؟ " +
          "تراکنش‌هایی که قبلاً ثبت شده‌اند باقی می‌مانند."
      Text(message)
    },
    confirmButton = {
      HesabyarButton(
        onClick = onConfirm,
        text = "حذف",
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
          )
      )
    },
    dismissButton = {
      HesabyarButton(
        onClick = onDismiss,
        text = stringResource(R.string.cancel_label),
        variant = ButtonVariant.Text
      )
    }
  )
}

@Composable
fun LoanListItem(
  loan: Loan,
  loanViewModel: LoanViewModel,
  settingsViewModel: SettingsViewModel,
  onDelete: () -> Unit,
  onEdit: () -> Unit
) {
  var expanded by remember { mutableStateOf(false) }
  var showRepayDialog by remember { mutableStateOf(false) }
  val paymentHistory by loanViewModel.getPaymentHistory(loan.id).collectAsState(initial = emptyList())

  val statusColor =
    if (loan.isSettled) {
      MaterialTheme.colorScheme.primary
    } else if (loan.type == LoanType.DEBTOR
    ) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.error
    }
  val statusText =
    if (loan.isSettled) {
      "تسویه شده"
    } else if (loan.type == LoanType.DEBTOR
    ) {
      "طلب وصول‌نشده"
    } else {
      "جای بازپرداخت باقی‌مانده"
    }

  HesabyarCard(
    modifier =
      Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded },
    shape = ShapeTokens.Large,
    cardColors =
      CardDefaults.cardColors(
        containerColor =
          if (loan.isSettled) {
            MaterialTheme.colorScheme.surfaceContainer
          } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
          }
      )
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconCircle(
            icon = if (loan.type == LoanType.DEBTOR) Icons.Filled.ArrowCircleDown else Icons.Filled.ArrowCircleUp,
            tint = statusColor,
            backgroundColor = statusColor,
            containerSize = 36.dp,
            iconSize = 20.dp
          )
          Spacer(modifier = Modifier.width(SpacingTokens.md))
          Column {
            Text(
              text = loan.personName,
              style = MaterialTheme.typography.bodyLarge,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface
            )
            Text(
              text = "بابت: ${loan.description}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = "مانده: " + CurrencyFormatter.format(loan.remainingAmount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = statusColor
          )
          Text(
            text = "کل: " + CurrencyFormatter.format(loan.originalAmount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      // Quick settlement indicator
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "تاریخ ثبت: ${formatPersianDate(loan.date)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
          modifier =
            Modifier
              .clip(ShapeTokens.Small)
              .background(statusColor.copy(alpha = 0.12f))
              .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs)
        ) {
          Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = statusColor
          )
        }
      }

      // Expanded view - Payments history list and custom repayment trigger
      AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
      ) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(top = SpacingTokens.md),
          verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)
        ) {
          HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

          Text(
            text = "📝 تاریخچه بازپرداخت‌ها:",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
          )

          if (paymentHistory.isEmpty()) {
            Text(
              text = "تاکنون هیچ برگی از بازپرداخت ثبت نشده است.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          } else {
            paymentHistory.forEach { pm ->
              Row(
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(ShapeTokens.Small)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(SpacingTokens.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column {
                  Text(
                    text = CurrencyFormatter.format(pm.amount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                  )
                  if (pm.notes.isNotBlank()) {
                    Text(
                      text = pm.notes,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                  }
                }

                Text(
                  text = formatPersianDate(pm.date),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(SpacingTokens.xs))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)
          ) {
            IconButton(
              onClick = onDelete,
              modifier =
                Modifier
                  .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), CircleShape)
                  .size(Dimens.AvatarMedium)
            ) {
              Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "حذف قرض",
                tint = MaterialTheme.colorScheme.error
              )
            }

            IconButton(
              onClick = onEdit,
              modifier =
                Modifier
                  .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), CircleShape)
                  .size(Dimens.AvatarMedium)
            ) {
              Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "ویرایش قرض",
                tint = MaterialTheme.colorScheme.primary
              )
            }

            if (!loan.isSettled) {
              HesabyarButton(
                onClick = { showRepayDialog = true },
                modifier = Modifier.weight(1f),
                text = "ثبت بازپرداخت جدید",
                icon = Icons.Filled.Payments,
                colors =
                  ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                  )
              )
            }
          }
        }
      }
    }
  }

  if (showRepayDialog) {
    LoanRepaymentDialog(
      remainingRial = loan.remainingAmount,
      showMessage = settingsViewModel::showMessage,
      onSubmit = { amountDisplay, notes, date, onResult ->
        loanViewModel.makeRepayment(loan.id, CurrencyFormatter.toRial(amountDisplay), notes, date, onResult)
      },
      onDismiss = { showRepayDialog = false }
    )
  }
}

private class RepaymentFormState {
  var amount by mutableStateOf("")
  var notes by mutableStateOf("")
  var date by mutableStateOf(System.currentTimeMillis())
}

@Composable
internal fun AddLoanDialog(
  initialType: LoanType,
  showMessage: (String) -> Unit,
  onConfirm: (personName: String, type: LoanType, amountRial: Long, description: String, date: Long) -> Unit,
  onDismiss: () -> Unit
) {
  val form = remember { LoanFormState(initialType) }
  val tooLargeMessage = stringResource(R.string.loan_amount_too_large)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        "ثبت قرض جدید",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Right
      )
    },
    text = { LoanFormFields(form) },
    confirmButton = {
      HesabyarButton(
        onClick = {
          val maxTomanDisplay = Long.MAX_VALUE / TOMAN_TO_RIAL_FACTOR
          val amountDisplay = form.amountText.toLongOrNull() ?: 0L
          if (
            CurrencyFormatter.currentUnit == io.github.mojri.hesabyar.ui.CurrencyUnit.TOMAN &&
            amountDisplay > maxTomanDisplay
          ) {
            showMessage(tooLargeMessage)
          } else if (form.personName.isNotBlank() && amountDisplay > 0L) {
            onConfirm(
              form.personName,
              form.loanType,
              CurrencyFormatter.toRial(amountDisplay),
              form.description,
              form.customDate
            )
          } else {
            showMessage("لطفا اطلاعات را کامل و صحیح پر کنید")
          }
        },
        text = "ثبت و ذخیره"
      )
    },
    dismissButton = {
      HesabyarButton(
        onClick = onDismiss,
        text = stringResource(R.string.cancel_label),
        variant = ButtonVariant.Text
      )
    }
  )
}

internal class LoanFormState(
  initialType: LoanType,
  initialPersonName: String = "",
  initialAmountRial: Long = 0L,
  initialDescription: String = "",
  initialDate: Long = System.currentTimeMillis()
) {
  var personName by mutableStateOf(initialPersonName)
  var loanType by mutableStateOf(initialType)
  var amountText by mutableStateOf(CurrencyFormatter.fromRial(initialAmountRial).toString())
  var description by mutableStateOf(initialDescription)
  var customDate by mutableStateOf(initialDate)
}

@Composable
internal fun EditLoanDialog(
  loan: Loan,
  onUpdate: (Loan) -> Unit,
  showMessage: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val form =
    remember {
      LoanFormState(loan.type, loan.personName, loan.originalAmount, loan.description, loan.date)
    }
  // Capture the amount shown when the dialog opened once. Reading form.amountText
  // here would re-read the live value on every recomposition, so the
  // "amount unchanged" branch would never trigger and repayment preservation
  // would be skipped (silent financial-data loss).
  val initialAmountText =
    remember(loan.originalAmount) {
      CurrencyFormatter.fromRial(loan.originalAmount).toString()
    }
  val tooLargeMessage = stringResource(R.string.loan_amount_too_large)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        "ویرایش قرض",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Right
      )
    },
    text = { LoanFormFields(form) },
    confirmButton = {
      HesabyarButton(
        onClick = {
          submitLoanEdit(
            form = form,
            loan = loan,
            initialAmountText = initialAmountText,
            onUpdate = onUpdate,
            showMessage = showMessage,
            tooLargeMessage = tooLargeMessage
          )
        },
        text = "ذخیره تغییرات"
      )
    },
    dismissButton = {
      HesabyarButton(
        onClick = onDismiss,
        text = stringResource(R.string.cancel_label),
        variant = ButtonVariant.Text
      )
    }
  )
}

internal fun submitLoanEdit(
  form: LoanFormState,
  loan: Loan,
  initialAmountText: String,
  onUpdate: (Loan) -> Unit,
  showMessage: (String) -> Unit,
  tooLargeMessage: String
) {
  val maxTomanDisplay = Long.MAX_VALUE / TOMAN_TO_RIAL_FACTOR
  val amountDisplay = form.amountText.toLongOrNull() ?: 0L
  when {
    form.personName.isBlank() || amountDisplay <= 0L ->
      showMessage("لطفا اطلاعات را کامل و صحیح پر کنید")

    CurrencyFormatter.currentUnit == io.github.mojri.hesabyar.ui.CurrencyUnit.TOMAN &&
      amountDisplay > maxTomanDisplay ->
      showMessage(tooLargeMessage)

    // Display-unit round trips truncate odd Rials in Toman mode; when the
    // amount field was left untouched, keep the stored amounts as-is.
    form.amountText == initialAmountText ->
      onUpdate(
        loan.copy(
          personName = form.personName,
          type = form.loanType,
          description = form.description,
          date = form.customDate
        )
      )

    else -> {
      // Keep already-repaid money intact: remaining shrinks only by the
      // difference between the new and the old principal.
      val amountRial = CurrencyFormatter.toRial(amountDisplay)
      val paidSoFar = (loan.originalAmount - loan.remainingAmount).coerceAtLeast(0L)
      if (amountRial < paidSoFar) {
        showMessage("مبلغ جدید نمی‌تواند کمتر از بازپرداخت‌های ثبت‌شده باشد")
      } else {
        val r = LoanEditCalculator.recompute(loan, amountRial)
        onUpdate(
          loan.copy(
            personName = form.personName,
            type = form.loanType,
            originalAmount = r.originalAmount,
            remainingAmount = r.remainingAmount,
            isSettled = r.isSettled,
            description = form.description,
            date = form.customDate
          )
        )
      }
    }
  }
}

@Composable
private fun LoanFormFields(form: LoanFormState) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
    horizontalAlignment = Alignment.End
  ) {
    // Type selector Inside Dialog
    LoanTypeSelector(loanType = form.loanType, onTypeChange = { form.loanType = it })

    HesabyarInputField(
      value = form.personName,
      onValueChange = { form.personName = it },
      label = "نام شخص طرف حساب"
    )

    HesabyarInputField(
      value = form.amountText,
      onValueChange = { form.amountText = it },
      label = "مبلغ قرض (${CurrencyFormatter.unitLabel})"
    )

    HesabyarInputField(
      value = form.description,
      onValueChange = { form.description = it },
      label = "توضیحات و بابت چی...",
      singleLine = false
    )

    JalaliDateTimePicker(
      initialTimestamp = form.customDate,
      onTimestampChanged = { form.customDate = it }
    )
  }
}

@Composable
private fun RepaymentFormFields(form: RepaymentFormState) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)
  ) {
    HesabyarInputField(
      value = form.amount,
      onValueChange = { form.amount = it },
      label = "مبلغ پرداختی (${CurrencyFormatter.unitLabel})"
    )

    HesabyarInputField(
      value = form.notes,
      onValueChange = { form.notes = it },
      label = "توضیحات (مثلا نقدی یا کارت به کارت)",
      singleLine = false
    )

    JalaliDateTimePicker(
      initialTimestamp = form.date,
      onTimestampChanged = { form.date = it }
    )
  }
}

@Composable
private fun LoanRepaymentDialog(
  remainingRial: Long,
  showMessage: (String) -> Unit,
  onSubmit: (amountDisplay: Long, notes: String, date: Long, onResult: (Boolean) -> Unit) -> Unit,
  onDismiss: () -> Unit
) {
  val form = remember { RepaymentFormState() }
  var submitting by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = { if (!submitting) onDismiss() },
    title = {
      Text(
        "ثبت بازپرداخت",
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Right
      )
    },
    text = { RepaymentFormFields(form) },
    confirmButton = {
      HesabyarButton(
        enabled = !submitting,
        loading = submitting,
        onClick = {
          val amountDisplay = form.amount.toLongOrNull() ?: 0L
          // Compare in Rial: converting the remainder to display units
          // truncates and would block settling a sub-unit balance.
          val amountRial = CurrencyFormatter.toRial(amountDisplay)
          when {
            amountDisplay <= 0L -> showMessage("لطفا مبلغ صحیح وارد کنید")
            amountRial > remainingRial -> {
              val remainingText = CurrencyFormatter.format(remainingRial)
              showMessage("مبلغ بیشتر از مانده ($remainingText) است")
            }
            else -> {
              submitting = true
              onSubmit(amountDisplay, form.notes, form.date) { ok ->
                submitting = false
                if (ok) {
                  onDismiss()
                } else {
                  showMessage("ثبت بازپرداخت انجام نشد؛ لطفا دوباره تلاش کنید")
                }
              }
            }
          }
        },
        text = "پرداخت شد"
      )
    },
    dismissButton = {
      HesabyarButton(
        onClick = onDismiss,
        text = stringResource(R.string.cancel_label),
        variant = ButtonVariant.Text
      )
    }
  )
}
