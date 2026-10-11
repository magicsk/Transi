package eu.magicsk.transi.util

import com.google.android.gms.maps.model.TileProvider
import com.google.android.gms.maps.model.UrlTileProvider
import eu.magicsk.transi.BuildConfig
import java.net.URL

/** Thunderforest transport tiles as on iOS: dark at night, 512 px (@2x), zoom 0–22. Null without an API key. */
fun transportTileProvider(dark: Boolean): TileProvider? {
    val key = BuildConfig.THUNDERFOREST_API_KEY.takeIf { it.isNotEmpty() } ?: return null
    val style = if (dark) "transport-dark" else "transport"
    return object : UrlTileProvider(512, 512) {
        override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? =
            if (zoom in 0..22) URL("https://tile.thunderforest.com/$style/$zoom/$x/$y@2x.png?apikey=$key") else null
    }
}
