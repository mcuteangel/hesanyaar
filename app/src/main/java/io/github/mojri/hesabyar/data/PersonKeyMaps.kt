package io.github.mojri.hesabyar.data

import io.github.mojri.hesabyar.domain.utils.PersonNameNormalizer

internal data class PersonKeyMaps(
  val sourceIdToKey: Map<Long, String>,
  val keyToLocalId: Map<String, Long>
)

internal fun resolvePersonId(
  sourcePersonId: Long?,
  fallbackName: String?,
  maps: PersonKeyMaps
): Long? {
  if (sourcePersonId != null) {
    val key = maps.sourceIdToKey[sourcePersonId]
    return key?.takeIf { it.isNotEmpty() }?.let { maps.keyToLocalId[it] }
  }
  val fallbackKey =
    fallbackName
      ?.let { PersonNameNormalizer.normalize(PersonNameNormalizer.displayForm(it)) }
      ?.takeIf { it.isNotEmpty() }
  return fallbackKey?.let { maps.keyToLocalId[it] }
}
