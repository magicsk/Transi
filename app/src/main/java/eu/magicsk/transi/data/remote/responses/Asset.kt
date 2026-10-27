package eu.magicsk.transi.data.remote.responses

data class Asset(
    val name: String?,
    val size: Long?,
    // "sha256:<hex>"; missing on assets uploaded before GitHub started publishing digests.
    val digest: String?,
    val browser_download_url: String?
)
