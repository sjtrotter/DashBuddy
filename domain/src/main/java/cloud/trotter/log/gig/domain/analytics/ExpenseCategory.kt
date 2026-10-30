package cloud.trotter.log.gig.domain.analytics

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** #1333: user-logged expense kinds; the HUD's chips. Stored by name. */
enum class ExpenseCategory {
    FOOD, PARKING, CAR_CARE, SUPPLIES, OTHER;

    companion object {
        fun fromStorage(name: String?): ExpenseCategory = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** Store enum names and preserve expenses with unknown categories as [ExpenseCategory.OTHER]. */
object ExpenseCategorySerializer : KSerializer<ExpenseCategory> {
    override val descriptor = PrimitiveSerialDescriptor("ExpenseCategory", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ExpenseCategory) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ExpenseCategory = ExpenseCategory.fromStorage(decoder.decodeString())
}
