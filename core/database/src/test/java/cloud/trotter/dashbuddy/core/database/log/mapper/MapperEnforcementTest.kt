package cloud.trotter.dashbuddy.core.database.log.mapper

import cloud.trotter.dashbuddy.core.database.log.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.reflect.full.memberProperties

class MapperEnforcementTest {

    @Test
    fun `UiNode and UiNodeDto must have the exact same property names`() {
        val ignoredProperties = setOf(
            "parent",           // transient property
            "allText",          // lazy property
            "structuralHash",   // lazy property
            "contentHash",      // lazy property
        )
        // Get all property names from the Domain model
        val domainProps =
            UiNode::class.memberProperties.map { it.name }
                .filterNot { it in ignoredProperties }
                .toSortedSet()

        // Get all property names from the DTO
        val dtoProps = UiNodeDto::class.memberProperties.map { it.name }.toSortedSet()

        // If they don't match, the test fails and prints the exact missing properties!
        assertEquals(
            "Mismatch between Domain and DTO properties!",
            domainProps,
            dtoProps
        )
    }
}