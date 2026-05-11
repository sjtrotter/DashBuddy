package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.core.state.util.RuntimeTypeAdapterFactory

/**
 * Provides a [RuntimeTypeAdapterFactory] for the [ParsedFields] sealed hierarchy.
 *
 * Uses `getDelegateAdapter()` internally, which properly bypasses the factory
 * for each concrete subtype — no re-entry, no StackOverflow.
 *
 * The discriminator field is `"_type"` (matches the legacy adapter format).
 */
fun parsedFieldsAdapterFactory(): RuntimeTypeAdapterFactory<ParsedFields> =
    RuntimeTypeAdapterFactory.of(ParsedFields::class.java, "_type")
        .registerSubtype(ParsedFields.None::class.java, "None")
        .registerSubtype(ParsedFields.IdleFields::class.java, "IdleFields")
        .registerSubtype(ParsedFields.OfferFields::class.java, "OfferFields")
        .registerSubtype(ParsedFields.TaskFields::class.java, "TaskFields")
        .registerSubtype(ParsedFields.PostTaskFields::class.java, "PostTaskFields")
        .registerSubtype(ParsedFields.SessionEndedFields::class.java, "SessionEndedFields")
        .registerSubtype(ParsedFields.PausedFields::class.java, "PausedFields")
        .registerSubtype(ParsedFields.TimelineFields::class.java, "TimelineFields")
        .registerSubtype(ParsedFields.RatingsFields::class.java, "RatingsFields")
        .registerSubtype(ParsedFields.SensitiveFields::class.java, "SensitiveFields")
        .registerSubtype(ParsedFields.NoiseFields::class.java, "NoiseFields")
        .registerSubtype(ParsedFields.ClickFields::class.java, "ClickFields")
        .registerSubtype(ParsedFields.NotificationFields::class.java, "NotificationFields")
