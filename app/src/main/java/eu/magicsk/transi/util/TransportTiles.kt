package eu.magicsk.transi.util

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.UrlTileProvider
import eu.magicsk.transi.BuildConfig
import java.net.URL

/** Where the maps open before they know better: central Bratislava. */
val DEFAULT_MAP_CENTER = LatLng(48.145, 17.107)

/**
 * Thunderforest transport tiles instead of the Google basemap, as on iOS (dark at night), with [attribution] shown as
 * their terms require. False, and nothing changes, without an API key.
 */
fun GoogleMap.useTransportTiles(dark: Boolean, attribution: TextView): Boolean {
    if (BuildConfig.THUNDERFOREST_API_KEY.isEmpty()) return false
    mapType = GoogleMap.MAP_TYPE_NONE
    addTileOverlay(TileOverlayOptions().fadeIn(false).tileProvider(TransportTiles(if (dark) "transport-dark" else "transport")))
    setMaxZoomPreference(TransportTiles.MAX_ZOOM.toFloat())
    attribution.movementMethod = LinkMovementMethod.getInstance()
    attribution.isVisible = true
    return true
}

/** Thunderforest [style] tiles at 512 px (@2x), as iOS TransportTiles; zooms the server lacks get none. */
private class TransportTiles(private val style: String) : UrlTileProvider(TILE_SIZE, TILE_SIZE) {
    // Built from parts: a malformed URL's exception message would carry the API key.
    override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
        if (zoom !in 0..MAX_ZOOM || x !in 0 until (1 shl zoom) || y !in 0 until (1 shl zoom)) return null
        return URL("https", HOST, "/$style/$zoom/$x/$y@2x.png?apikey=${BuildConfig.THUNDERFOREST_API_KEY}")
    }

    companion object {
        const val MAX_ZOOM = 22
        const val TILE_SIZE = 512
        const val HOST = "tile.thunderforest.com"
    }
}
