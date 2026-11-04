package com.fujixfer.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Google Maps look (the 2024+ palette) on the OpenStreetMap style (OpenFreeMap "liberty"), recoloured after it
 * loads, kept flat (no edges, relief, textures or 3D): pale green countryside, blue-grey towns, light blue water, green parks, white streets, blue-grey motorways, dark grey labels; minor points of interest and 3D buildings hidden so the photo
 * circles stand out. In dark mode the colours of Google's dark map. Layers are recognised by their name (the
 * OpenMapTiles names: water, park, landcover_wood, landuse_residential, building, highway_motorway, poi_r20...);
 * unknown ones stay as they are.
 */
object MapLook {
    private class Palette(
        val land: String, val town: String, val green: String, val water: String, val waterText: String,
        val park: String, val wood: String, val building: String, val buildingEdge: String,
        val road: String, val roadEdge: String, val major: String, val majorEdge: String,
        val highway: String, val highwayEdge: String, val text: String, val textMinor: String,
        val halo: String, val rail: String, val boundary: String,
    )

    private val day = Palette(
        land = "#EEF1EA", town = "#E6E9ED", green = "#DCEFD3", water = "#9CC0F9", waterText = "#3E7BC4",
        park = "#C3EBB4", wood = "#CBE6BF", building = "#DDE1E6", buildingEdge = "#DDE1E6",
        road = "#FFFFFF", roadEdge = "#D9DDE2", major = "#FFFFFF", majorEdge = "#C5CCD5",
        highway = "#BFCBDE", highwayEdge = "#97A8C3", text = "#3C4043", textMinor = "#70757A",
        halo = "#FFFFFF", rail = "#C2C6CC", boundary = "#9AA0A6",
    )

    private val night = Palette(
        land = "#1F2328", town = "#262B31", green = "#1F2A24", water = "#13253A", waterText = "#6F8CB0",
        park = "#22362B", wood = "#203126", building = "#2C3239", buildingEdge = "#23282E",
        road = "#3A4048", roadEdge = "#23282E", major = "#454C55", majorEdge = "#23282E",
        highway = "#56637A", highwayEdge = "#2A3242", text = "#C4C7CC", textMinor = "#8E949B",
        halo = "#1F2328", rail = "#4A5058", boundary = "#6B7178",
    )

    /**
     * Everything that is not flat colour or plain text: small points of interest (shops, cafés...), road
     * shields, one-way arrows, textures, the shaded relief of the low zooms, the edges drawn along roads, parks
     * and bridges. The map stays a flat background for the photo circles.
     */
    private fun notFlat(id: String) = id.startsWith("poi_r20") || id.startsWith("poi_r7") || id == "poi" ||
        "shield" in id || "oneway" in id || "pattern" in id || "casing" in id || "outline" in id ||
        "hillshade" in id || "natural_earth" in id

    fun apply(context: Context, style: Style) {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val p = if (dark) night else day
        fun c(hex: String) = Color.parseColor(hex)
        for (layer in style.layers) {
            val id = layer.id.lowercase()
            try {
                if (layer is FillExtrusionLayer || layer is RasterLayer || layer is HillshadeLayer || notFlat(id)) {
                    layer.setProperties(PropertyFactory.visibility(Property.NONE))
                    continue
                }
                when (layer) {
                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(c(p.land)))
                    is FillLayer -> when {
                        "water" in id || "ocean" in id -> layer.setProperties(PropertyFactory.fillColor(c(p.water)))
                        "building" in id -> layer.setProperties(
                            PropertyFactory.fillColor(c(p.building)),
                            PropertyFactory.fillOutlineColor(c(p.building)),
                            PropertyFactory.fillOpacity(1f),
                        )
                        "wood" in id || "forest" in id -> layer.setProperties(PropertyFactory.fillColor(c(p.wood)))
                        "park" in id || "garden" in id || "cemetery" in id || "pitch" in id ->
                            layer.setProperties(PropertyFactory.fillColor(c(p.park)), PropertyFactory.fillOpacity(1f))
                        "grass" in id || "farm" in id || "wetland" in id || "scrub" in id || "natural" in id ->
                            layer.setProperties(PropertyFactory.fillColor(c(p.green)), PropertyFactory.fillOpacity(1f))
                        "ice" in id || "glacier" in id -> Unit
                        "residential" in id || "landuse" in id || "industrial" in id || "commercial" in id ->
                            layer.setProperties(PropertyFactory.fillColor(c(p.town)), PropertyFactory.fillOpacity(1f))
                        "aeroway" in id -> layer.setProperties(PropertyFactory.fillColor(c(p.town)))
                    }
                    is LineLayer -> when {
                        "water" in id || "river" in id || "stream" in id || "canal" in id ->
                            layer.setProperties(PropertyFactory.lineColor(c(p.water)))
                        "rail" in id || "transit" in id -> layer.setProperties(PropertyFactory.lineColor(c(p.rail)))
                        "boundary" in id || "admin" in id -> layer.setProperties(PropertyFactory.lineColor(c(p.boundary)))
                        "park" in id -> layer.setProperties(PropertyFactory.lineOpacity(0f))
                        "motorway" in id || "trunk" in id ->
                            layer.setProperties(PropertyFactory.lineColor(c(if ("casing" in id) p.highwayEdge else p.highway)))
                        "primary" in id || "secondary" in id ->
                            layer.setProperties(PropertyFactory.lineColor(c(if ("casing" in id) p.majorEdge else p.major)))
                        "highway" in id || "road" in id || "street" in id || "minor" in id || "tertiary" in id ||
                            "service" in id || "path" in id || "track" in id || "link" in id ->
                            layer.setProperties(PropertyFactory.lineColor(c(if ("casing" in id) p.roadEdge else p.road)))
                    }
                    is SymbolLayer -> {
                        val big = "city" in id || "town" in id || "capital" in id || "country" in id || "state" in id
                        layer.setProperties(
                            PropertyFactory.textColor(
                                c(if ("water" in id || "ocean" in id || "sea" in id) p.waterText else if (big) p.text else p.textMinor),
                            ),
                            PropertyFactory.textHaloColor(c(p.halo)),
                            PropertyFactory.textHaloWidth(1f),
                            PropertyFactory.textHaloBlur(0f),
                        )
                    }
                }
            } catch (e: RuntimeException) {
                // a layer that does not take this property: leave it as the style draws it
            }
        }
    }
}
