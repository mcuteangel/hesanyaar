package io.github.mojri.hesabyar.data

import androidx.room.withTransaction
import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer
import kotlinx.coroutines.flow.Flow

internal class PersonDelegate(
  private val personDao: PersonDao,
  private val loanDao: LoanDao,
  private val transactionDao: TransactionDao,
  private val database: AppDatabase
) : PersonRepositoryInterface {
  override val allPersons: Flow<List<Person>> = personDao.getAllPersons()

  override suspend fun getAllPersonsIncludingArchived(): List<Person> =
    personDao.getAllPersonsIncludingArchivedBlocking()

  override suspend fun getPersonById(id: Long): Person? = personDao.getPersonById(id)

  override suspend fun upsertPerson(person: Person): Person {
    val display = PersonNameNormalizer.displayForm(person.name)
    require(display.isNotEmpty()) { "Person name is blank" }
    val key = PersonNameNormalizer.normalize(display)
    require(key.isNotEmpty()) { "Person name normalizes to empty" }
    val existing = personDao.getPersonByNormalizedName(key)
    val result =
      if (existing != null) {
        val merged = existing.copy(phone = person.phone ?: existing.phone, notes = person.notes ?: existing.notes)
        personDao.updatePerson(merged)
        merged
      } else {
        val candidate =
          person.copy(
            id = 0,
            name = display,
            normalizedName = key,
            createdAt = person.createdAt.takeIf { it != 0L } ?: System.currentTimeMillis()
          )
        val id = personDao.insertPerson(candidate)
        if (id != -1L) {
          candidate.copy(id = id)
        } else {
          val winner = requireNotNull(personDao.getPersonByNormalizedName(key))
          if (person.phone != null || person.notes != null) {
            val mergedWinner = winner.copy(phone = person.phone ?: winner.phone, notes = person.notes ?: winner.notes)
            if (mergedWinner != winner) personDao.updatePerson(mergedWinner)
            mergedWinner
          } else {
            winner
          }
        }
      }
    return result
  }

  override suspend fun renamePerson(
    personId: Long,
    newName: String
  ): Boolean {
    val display = PersonNameNormalizer.displayForm(newName)
    require(display.isNotEmpty()) { "Person name is blank" }
    return database.withTransaction {
      val person = personDao.getPersonById(personId) ?: return@withTransaction false
      val key = PersonNameNormalizer.normalize(display)
      require(key.isNotEmpty()) { "Person name normalizes to empty" }
      val clash = personDao.getPersonByNormalizedName(key)
      if (clash != null && clash.id != personId) return@withTransaction false
      personDao.updatePerson(person.copy(name = display, normalizedName = key))
      loanDao.syncLoanPersonNames(personId, display)
      transactionDao.syncTransactionPersonNames(personId, display)
      true
    }
  }

  override suspend fun deletePerson(person: Person) {
    database.withTransaction { personDao.deletePerson(person) }
  }
}
