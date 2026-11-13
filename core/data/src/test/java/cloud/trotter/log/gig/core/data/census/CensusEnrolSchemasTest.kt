package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.CensusSkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.SkeletonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1522: a pending identity declares only the schemas the server's policy accepts. */
class CensusEnrolSchemasTest {
    private fun policy(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test fun `no policy declares screens only`() {
        assertEquals(listOf(SkeletonSchema.SCHEMA_ID), CensusEnrolSchemas.declared(null))
    }

    @Test fun `a screen only policy declares screens only`() {
        assertEquals(listOf(SkeletonSchema.SCHEMA_ID),
            CensusEnrolSchemas.declared(policy("""{"acceptedSchemaIds":["uinode.skeleton.v1"],"k":10}""")))
    }

    @Test fun `a two schema policy declares both in the supported order`() {
        assertEquals(listOf(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID),
            CensusEnrolSchemas.declared(policy("""{"acceptedSchemaIds":["notification.skeleton.v1","uinode.skeleton.v1"]}""")))
    }

    @Test fun `a v2 policy declares all four in the supported order, never an id it does not list`() {
        assertEquals(
            listOf(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID, SkeletonSchema.SCHEMA_ID_V2, NotificationSkeletonSchema.SCHEMA_ID_V2),
            CensusEnrolSchemas.declared(policy(
                """{"acceptedSchemaIds":["notification.skeleton.v2","uinode.skeleton.v2","notification.skeleton.v1","uinode.skeleton.v1"]}""",
            )),
        )
        assertEquals(CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS, CensusEnrolSchemas.declared(policy(
            """{"acceptedSchemaIds":${CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.joinToString(",", "[", "]") { "\"$it\"" }}}""",
        )))
        assertEquals(listOf(SkeletonSchema.SCHEMA_ID, SkeletonSchema.SCHEMA_ID_V2),
            CensusEnrolSchemas.declared(policy("""{"acceptedSchemaIds":["uinode.skeleton.v2","uinode.skeleton.v1"]}""")))
    }

    @Test fun `an empty policy declares nothing`() {
        assertEquals(emptyList<String>(), CensusEnrolSchemas.declared(policy("""{"acceptedSchemaIds":[]}""")))
        assertEquals(emptyList<String>(), CensusEnrolSchemas.declared(policy("""{"k":10}""")))
    }

    @Test fun `a policy listing only an unknown id declares nothing`() {
        assertEquals(emptyList<String>(), CensusEnrolSchemas.declared(policy("""{"acceptedSchemaIds":["future.skeleton.v9"]}""")))
    }

    @Test fun `accepted ids keep string entries only`() {
        assertEquals(setOf(NotificationSkeletonSchema.SCHEMA_ID),
            CensusEnrolSchemas.acceptedIn(policy("""{"acceptedSchemaIds":[null,1,{},"notification.skeleton.v1"]}""")))
        assertEquals(emptySet<String>(), CensusEnrolSchemas.acceptedIn(policy("""{"acceptedSchemaIds":"uinode.skeleton.v1"}""")))
    }
}
