package cloud.trotter.dashbuddy.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.*
import org.junit.Test

class GlanceScalingTest {
    @Test fun scaledByGlance_scalesEveryAppTextStyle() {
        val base = dashTextStyles()
        val glance = base.scaledByGlance(1.12f)
        val fields = AppTextStyles::class.java.declaredFields.filter { it.type == TextStyle::class.java }
        assertTrue(fields.isNotEmpty())
        fields.forEach { field ->
            field.isAccessible = true
            val before = field.get(base) as TextStyle
            val after = field.get(glance) as TextStyle
            assertEquals(field.name, before.fontSize.value * 1.12f, after.fontSize.value, .001f)
        }
    }
}
