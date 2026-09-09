package io.github.mojri.hesabyar.domain.usecase

import io.github.mojri.hesabyar.R
import io.github.mojri.hesabyar.data.Person
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer

/**
 * Validates the `persons` collection of a backup payload. Extracted from
 * [BackupJsonValidator] so that class stays under the TooManyFunctions limit
 * while the per-collection checks remain discoverable.
 */
object PersonBackupValidator {
  fun validate(
    persons: List<Person>,
    errors: MutableList<String>,
    message: (Int, Array<out Any>) -> String
  ) {
    val seen = mutableSetOf<String>()
    persons.forEachIndexed { i, p ->
      if (p.name.isBlank()) errors.add(message(R.string.backup_validation_person_name_blank, arrayOf(i)))
      // The dedup key is derived from the name, never from the backup-supplied
      // normalizedName. A mismatched pair (name="Ali", normalizedName="reza")
      // would otherwise bind Ali's records to Reza's identity, because the
      // restore path derives the key from the name too.
      val display = PersonNameNormalizer.displayForm(p.name)
      val key = PersonNameNormalizer.normalize(display)
      if (key.isEmpty()) {
        errors.add(message(R.string.backup_validation_person_normalized_blank, arrayOf(i)))
      } else if (!seen.add(key)) {
        errors.add(message(R.string.backup_validation_person_duplicate, arrayOf(i)))
      }
    }
    // Duplicate source IDs: the restore path maps source IDs to local rows with
    // `associate`, so a later entry silently overwrites the earlier mapping and
    // loans/transactions referencing that ID resolve to the wrong person.
    // Mirrors validate_backup_payload (rust/hesabyar-core/src/validation.rs).
    persons
      .groupingBy { it.id }
      .eachCount()
      .filter { it.value > 1 }
      .keys
      .forEach { id ->
        errors.add(message(R.string.backup_validation_person_duplicate_id, arrayOf<Any>(id.toString())))
      }
  }
}
