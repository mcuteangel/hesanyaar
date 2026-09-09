package io.github.mojri.hesabyar.data

import kotlinx.coroutines.flow.Flow

/**
 * Focused contract for person-ledger persistence (plans/011).
 *
 * Extracted from [HesabyarRepositoryInterface] so person-related use cases
 * can depend on a narrow interface rather than the broad repository contract
 * that also owns transactions, loans, installments, and accounts. Existing
 * code continues to depend on [HesabyarRepositoryInterface] — this interface
 * is a supertype of that one, so no call site needs to change.
 */
interface PersonRepositoryInterface {
  val allPersons: Flow<List<Person>>

  suspend fun getAllPersonsIncludingArchived(): List<Person>

  suspend fun getPersonById(id: Long): Person?

  /** Match-or-create by normalized name; returns the stored row. */
  suspend fun upsertPerson(person: Person): Person

  /**
   * D3 sync-on-rename: updates the person row and the denormalized
   * personName on its loans and transactions in one transaction.
   * Returns false when the id is unknown or another person already owns
   * the new normalized name (merging is a separate flow).
   */
  suspend fun renamePerson(
    personId: Long,
    newName: String
  ): Boolean

  suspend fun deletePerson(person: Person)
}
