package eu.magicsk.transi.data.remote.responses

// Only the fields the updater reads; GitHub may send null for any of them.
data class ReleaseInfo(
    val tag_name: String?,
    val body: String?,
    val assets: List<Asset>?
)
