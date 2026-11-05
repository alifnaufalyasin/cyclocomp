package com.aliven.cyclocomp.map

import android.content.Context
import com.aliven.cyclocomp.core.LatLon
import com.aliven.cyclocomp.core.TileTemplate
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.File

/** Map preferences: whether the ride screen has a map page, and an optional own tile server. */
object MapSettings {
    private const val PREFS = "cyclocomp_map"

    fun showMapPage(context: Context): Boolean = prefs(context).getBoolean("show_map", true)

    /** Empty means the standard OpenStreetMap tiles. */
    fun tileTemplate(context: Context): String = prefs(context).getString("tile_template", "") ?: ""

    fun save(context: Context, showMap: Boolean, tileTemplate: String) {
        prefs(context).edit().putBoolean("show_map", showMap).putString("tile_template", tileTemplate.trim()).apply()
    }

    /** Where the map opens before there is a GPS fix: the end of the last ride. */
    fun lastPosition(context: Context): LatLon? {
        val p = prefs(context)
        if (!p.contains("last_lat")) return null
        return LatLon(Double.fromBits(p.getLong("last_lat", 0)), Double.fromBits(p.getLong("last_lon", 0)))
    }

    fun saveLastPosition(context: Context, pos: LatLon) {
        prefs(context).edit()
            .putLong("last_lat", pos.lat.toRawBits()).putLong("last_lon", pos.lon.toRawBits()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

object MapSupport {
    /** Where the map first looks when there is neither a GPS fix nor a previous ride (central Java). */
    val DEFAULT_CENTER = LatLon(-7.3, 108.2)

    /**
     * Must run before the first MapView. Tiles are cached under the app's cache folder, so no
     * storage permission is needed, and the tile servers get a clear user agent as their usage
     * policies ask for.
     */
    fun init(context: Context) {
        val c = Configuration.getInstance()
        c.userAgentValue = "cyclocomp-android"
        val base = File(context.cacheDir, "osmdroid")
        c.osmdroidBasePath = base
        c.osmdroidTileCache = File(base, "tiles")
        c.tileFileSystemCacheMaxBytes = 150L * 1024 * 1024
        c.tileFileSystemCacheTrimBytes = 120L * 1024 * 1024
    }

    fun tileSource(context: Context): ITileSource {
        val template = MapSettings.tileTemplate(context)
        if (!TileTemplate.isValid(template)) return TileSourceFactory.MAPNIK
        return object : OnlineTileSourceBase("custom", 0, 19, 256, ".png", arrayOf(template)) {
            override fun getTileURLString(pMapTileIndex: Long): String = TileTemplate.expand(
                template, MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex),
            )
        }
    }
}
