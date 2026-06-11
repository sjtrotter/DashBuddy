package cloud.trotter.dashbuddy.domain.model.accessibility

@kotlinx.serialization.Serializable
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)