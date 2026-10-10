package eu.magicsk.transi.data.remote.responses

data class TimetableManifest(
    val schema_version: Int,
    val generated_at: String,
    val databases: TimetableDatabases
)

data class TimetableDatabases(
    val base: TimetableDatabaseInfo,
    val schedule: TimetableDatabaseInfo,
    val planner: TimetableDatabaseInfo?
)

data class TimetableDatabaseInfo(
    val version: String,
    val url: String,
    val size_bytes: Long,
    val size_bytes_gz: Long,
    val sha256: String,
    val valid_from: String,
    val valid_until: String
)
