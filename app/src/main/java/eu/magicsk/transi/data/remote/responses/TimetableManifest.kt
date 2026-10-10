package eu.magicsk.transi.data.remote.responses

data class TimetableManifest(
    val databases: TimetableDatabases
)

data class TimetableDatabases(
    val base: TimetableDatabaseInfo,
    val schedule: TimetableDatabaseInfo
)

data class TimetableDatabaseInfo(
    val url: String,
    val size_bytes_gz: Long,
    val sha256: String
)
