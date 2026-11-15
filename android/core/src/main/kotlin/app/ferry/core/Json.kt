package app.ferry.core

import kotlinx.serialization.json.Json

/** The one Json instance (plan §1.2): tolerant of new keys, omits nulls (e.g. `mtime` [B17]). */
val FerryJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
