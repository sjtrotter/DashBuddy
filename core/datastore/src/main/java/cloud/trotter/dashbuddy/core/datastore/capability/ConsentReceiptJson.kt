package cloud.trotter.dashbuddy.core.datastore.capability

import kotlinx.serialization.json.Json

/**
 * The ONE codec for persisted [cloud.trotter.dashbuddy.domain.capability.ConsentReceipt]s (#170). Lenient on
 * unknown keys so a downgrade reading a newer receipt keeps the record instead of dropping every receipt in the
 * blob; a newer field must carry a default (see the receipt's KDoc) so an upgrade keeps them too.
 */
internal val ConsentReceiptJson: Json = Json { ignoreUnknownKeys = true }
