package io.github.mojri.hesabyar.domain.usecase

import io.github.mojri.hesabyar.HesabyarApp
import io.github.mojri.hesabyar.RustIsolationRule
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BackupJsonParserFallbackPersonTest {
  @Rule
  @JvmField
  val rustIsolationRule = RustIsolationRule()

  @Before
  fun forceKotlinParser() {
    HesabyarApp.setRustInitializedForTesting(false)
  }

  private val useCase = ManageBackupUseCase(FakeRepository())

  private fun buildBackupJson(block: JSONObject.() -> Unit): String = JSONObject().apply(block).toString()

  @Test
  fun fallbackParserNormalizesPersonNameAndPreservesRawName() {
    val rawName = "  علی  رضا  "
    val staleNormalized = "stale_value"
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("name", rawName)
              put("normalizedName", staleNormalized)
              put("createdAt", 1000L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertEquals(1, result!!.persons.size)
    val person = result.persons[0]
    assertEquals(rawName, person.name)
    assertEquals("علی رضا", person.normalizedName)
  }

  @Test
  fun fallbackParserRejectsZeroWidthOnlyPersonName() {
    val zeroWidthName = " \u200B \u200C "
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("name", zeroWidthName)
              put("normalizedName", "")
              put("createdAt", 0L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNull(result)
  }

  @Test
  fun fallbackParserRejectsBlankPersonName() {
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("name", "   ")
              put("normalizedName", "should_be_ignored")
              put("createdAt", 0L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNull(result)
  }

  @Test
  fun fallbackParserRejectsEmptyPersonName() {
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 2L)
              put("name", "")
              put("normalizedName", "")
              put("createdAt", 0L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNull(result)
  }

  @Test
  fun fallbackParserTransactionPersonIdAbsentParsesAsNull() {
    val json =
      buildBackupJson {
        put(
          "transactions",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("amount", 1000L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertEquals(1, result!!.transactions.size)
    assertNull(result.transactions[0].personId)
  }

  @Test
  fun fallbackParserTransactionPersonIdNullParsesAsNull() {
    val json =
      buildBackupJson {
        put(
          "transactions",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("amount", 1000L)
              put("personId", JSONObject.NULL)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertNull(result!!.transactions[0].personId)
  }

  @Test
  fun fallbackParserTransactionPersonIdZeroParsesAsNull() {
    val json =
      buildBackupJson {
        put(
          "transactions",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("amount", 1000L)
              put("personId", 0L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertNull(result!!.transactions[0].personId)
  }

  @Test
  fun fallbackParserTransactionPersonIdValidParsesCorrectly() {
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 5L)
              put("name", "علی رضا")
              put("normalizedName", "علی رضا")
              put("createdAt", 1000L)
            }
          )
        )
        put(
          "transactions",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("amount", 1000L)
              put("personId", 5L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertEquals(5L, result!!.transactions[0].personId)
  }

  @Test
  fun fallbackParserTransactionPersonIdVariantsCombined() {
    val json =
      buildBackupJson {
        put(
          "transactions",
          JSONArray().apply {
            put(
              JSONObject().apply {
                put("id", 1L)
                put("amount", 100L)
              }
            )
            put(
              JSONObject().apply {
                put("id", 2L)
                put("amount", 200L)
                put("personId", JSONObject.NULL)
              }
            )
            put(
              JSONObject().apply {
                put("id", 3L)
                put("amount", 300L)
                put("personId", 0L)
              }
            )
            put(
              JSONObject().apply {
                put("id", 4L)
                put("amount", 400L)
                put("personId", 5L)
              }
            )
          }
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertEquals(4, result!!.transactions.size)
    assertNull(result.transactions[0].personId)
    assertNull(result.transactions[1].personId)
    assertNull(result.transactions[2].personId)
    assertEquals(5L, result.transactions[3].personId)
  }

  @Test
  fun fallbackParserLoanPersonIdAbsentParsesAsNull() {
    val json =
      buildBackupJson {
        put(
          "loans",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("personName", "Ali")
              put("originalAmount", 1000L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertNull(result!!.loans[0].personId)
  }

  @Test
  fun fallbackParserLoanPersonIdNullParsesAsNull() {
    val json =
      buildBackupJson {
        put(
          "loans",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("personName", "Ali")
              put("originalAmount", 1000L)
              put("personId", JSONObject.NULL)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertNull(result!!.loans[0].personId)
  }

  @Test
  fun fallbackParserLoanPersonIdZeroParsesAsNull() {
    val json =
      buildBackupJson {
        put(
          "loans",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("personName", "Ali")
              put("originalAmount", 1000L)
              put("personId", 0L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertNull(result!!.loans[0].personId)
  }

  @Test
  fun fallbackParserLoanPersonIdValidParsesCorrectly() {
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 5L)
              put("name", "علی رضا")
              put("normalizedName", "علی رضا")
              put("createdAt", 1000L)
            }
          )
        )
        put(
          "loans",
          JSONArray().put(
            JSONObject().apply {
              put("id", 1L)
              put("personName", "Ali")
              put("originalAmount", 1000L)
              put("personId", 5L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertEquals(5L, result!!.loans[0].personId)
  }

  @Test
  fun fallbackParserLoanPersonIdVariantsCombined() {
    val json =
      buildBackupJson {
        put(
          "loans",
          JSONArray().apply {
            put(
              JSONObject().apply {
                put("id", 1L)
                put("personName", "A")
                put("originalAmount", 100L)
              }
            )
            put(
              JSONObject().apply {
                put("id", 2L)
                put("personName", "B")
                put("originalAmount", 200L)
                put("personId", JSONObject.NULL)
              }
            )
            put(
              JSONObject().apply {
                put("id", 3L)
                put("personName", "C")
                put("originalAmount", 300L)
                put("personId", 0L)
              }
            )
            put(
              JSONObject().apply {
                put("id", 4L)
                put("personName", "D")
                put("originalAmount", 400L)
                put("personId", 5L)
              }
            )
          }
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertEquals(4, result!!.loans.size)
    assertNull(result.loans[0].personId)
    assertNull(result.loans[1].personId)
    assertNull(result.loans[2].personId)
    assertEquals(5L, result.loans[3].personId)
  }

  @Test
  fun fallbackParserNormalizesStaleNormalizedNameIsIgnored() {
    val json =
      buildBackupJson {
        put(
          "persons",
          JSONArray().put(
            JSONObject().apply {
              put("id", 10L)
              put("name", "  علی  رضا  ")
              put("normalizedName", "wrong_stale")
              put("createdAt", 1000L)
            }
          )
        )
      }
    val result = runBlocking { useCase.parseBackupJson(json) }
    assertNotNull(result)
    assertTrue(result!!.persons.isNotEmpty())
    assertEquals("علی رضا", result.persons[0].normalizedName)
  }
}
