package cloud.trotter.dashbuddy.core.database.log.mapper

import cloud.trotter.dashbuddy.core.database.log.dto.BoundingBoxDto
import cloud.trotter.dashbuddy.core.database.log.dto.UiNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class UiNodeMapperTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sampleNode(
        text: String = "hello",
        id: String = "com.example:id/view",
        children: MutableList<UiNode> = mutableListOf()
    ) = UiNode(
        text = text,
        contentDescription = "desc",
        stateDescription = "state",
        viewIdResourceName = id,
        className = "android.widget.TextView",
        isClickable = true,
        isEnabled = true,
        isChecked = 1,
        boundsInScreen = BoundingBox(10, 20, 100, 200),
        children = children
    )

    private fun sampleDto(
        text: String = "hello",
        id: String = "com.example:id/view",
        children: List<UiNodeDto> = emptyList()
    ) = UiNodeDto(
        text = text,
        contentDescription = "desc",
        stateDescription = "state",
        viewIdResourceName = id,
        className = "android.widget.TextView",
        isClickable = true,
        isEnabled = true,
        isChecked = 1,
        boundsInScreen = BoundingBoxDto(10, 20, 100, 200),
        children = children
    )

    // -------------------------------------------------------------------------
    // toDto
    // -------------------------------------------------------------------------

    @Test
    fun `toDto preserves all scalar fields`() {
        val node = sampleNode()
        val dto = node.toDto()

        assertEquals(node.text, dto.text)
        assertEquals(node.contentDescription, dto.contentDescription)
        assertEquals(node.stateDescription, dto.stateDescription)
        assertEquals(node.viewIdResourceName, dto.viewIdResourceName)
        assertEquals(node.className, dto.className)
        assertEquals(node.isClickable, dto.isClickable)
        assertEquals(node.isEnabled, dto.isEnabled)
        assertEquals(node.isChecked, dto.isChecked)
    }

    @Test
    fun `toDto preserves bounds`() {
        val node = sampleNode()
        val dto = node.toDto()

        assertEquals(10, dto.boundsInScreen.left)
        assertEquals(20, dto.boundsInScreen.top)
        assertEquals(100, dto.boundsInScreen.right)
        assertEquals(200, dto.boundsInScreen.bottom)
    }

    @Test
    fun `toDto converts children recursively`() {
        val child = sampleNode(text = "child")
        val parent = sampleNode(text = "parent", children = mutableListOf(child))

        val dto = parent.toDto()

        assertEquals(1, dto.children.size)
        assertEquals("child", dto.children[0].text)
    }

    // -------------------------------------------------------------------------
    // toDomain
    // -------------------------------------------------------------------------

    @Test
    fun `toDomain preserves all scalar fields`() {
        val dto = sampleDto()
        val node = dto.toDomain()

        assertEquals(dto.text, node.text)
        assertEquals(dto.contentDescription, node.contentDescription)
        assertEquals(dto.stateDescription, node.stateDescription)
        assertEquals(dto.viewIdResourceName, node.viewIdResourceName)
        assertEquals(dto.className, node.className)
        assertEquals(dto.isClickable, node.isClickable)
        assertEquals(dto.isEnabled, node.isEnabled)
        assertEquals(dto.isChecked, node.isChecked)
    }

    @Test
    fun `toDomain with no parent sets parent to null`() {
        val node = sampleDto().toDomain(parentUiNode = null)
        assertNull(node.parent)
    }

    @Test
    fun `toDomain wires parent reference on child nodes`() {
        val dto = sampleDto(
            children = listOf(sampleDto(text = "child"))
        )
        val parent = dto.toDomain()

        assertEquals(1, parent.children.size)
        assertSame(parent, parent.children[0].parent)
    }

    @Test
    fun `toDomain wires parent references recursively`() {
        val grandchild = sampleDto(text = "grandchild")
        val child = sampleDto(text = "child", children = listOf(grandchild))
        val root = sampleDto(text = "root", children = listOf(child))

        val rootNode = root.toDomain()
        val childNode = rootNode.children[0]
        val grandchildNode = childNode.children[0]

        assertSame(rootNode, childNode.parent)
        assertSame(childNode, grandchildNode.parent)
    }

    // -------------------------------------------------------------------------
    // Round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `round-trip domain to dto and back preserves values`() {
        val original = sampleNode()
        val roundTripped = original.toDto().toDomain()

        assertEquals(original.text, roundTripped.text)
        assertEquals(original.viewIdResourceName, roundTripped.viewIdResourceName)
        assertEquals(original.boundsInScreen, roundTripped.boundsInScreen)
    }

    @Test
    fun `round-trip preserves child count`() {
        val parent = sampleNode(
            children = mutableListOf(
                sampleNode(text = "child1"),
                sampleNode(text = "child2")
            )
        )
        val roundTripped = parent.toDto().toDomain()
        assertEquals(2, roundTripped.children.size)
    }

    @Test
    fun `toDomain with explicit parent wires correctly`() {
        val parentNode = sampleNode(text = "parent")
        val childDto = sampleDto(text = "child")
        val childNode = childDto.toDomain(parentUiNode = parentNode)

        assertNotNull(childNode.parent)
        assertSame(parentNode, childNode.parent)
    }
}
