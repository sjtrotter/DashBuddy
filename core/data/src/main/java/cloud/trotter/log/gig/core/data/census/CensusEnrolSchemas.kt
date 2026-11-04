package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The one owner of which schema ids a server policy accepts and which ones a pending identity declares (#1522). */
object CensusEnrolSchemas {
    /** The string entries of the policy's `acceptedSchemaIds`; a missing key or a non-string entry adds nothing. */
    fun acceptedIn(policy: JsonObject): Set<String> = (policy["acceptedSchemaIds"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.takeIf { id -> id.isString }?.content
    }.toSet()

    /**
     * The schema ids an enrolment declares: the ones this app can build that [policy] accepts, in
     * [CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS] order. A null policy (the fetch failed) declares screens only.
     * An empty result means the server accepts nothing this app builds, and the caller must not enrol.
     */
    fun declared(policy: JsonObject?): List<String> {
        if (policy == null) return listOf(SkeletonSchema.SCHEMA_ID)
        val accepted = acceptedIn(policy)
        return CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.filter { it in accepted }
    }
}
