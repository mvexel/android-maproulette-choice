package lol.osm.streettally.ui

import android.content.Context
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import lol.osm.streettally.campaign.LocatedTask
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Brand pins (brand/pins/svg PinTodo, PinDone): amber with a navy ring to check, green with a
 * white ring once checked. Drawn as circles at the pins' 28 px size. */
private const val TODO_COLOR = "#F5A524"
private const val TODO_RING = "#14213D"
private const val DONE_COLOR = "#1F7A4D"
private const val DONE_RING = "#FFFFFF"

/** A campaign's places on a MapLibre map with OpenFreeMap tiles. Checked places are green, the
 * rest orange. Tapping a place calls [onPlace] with its task id. */
@Composable
fun PlacesMap(
    styleUrl: String,
    places: List<LocatedTask>,
    done: Set<Long>,
    onPlace: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holder = remember { PlacesMapHolder(context, styleUrl) }
    holder.onPlace = onPlace
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.view.onDestroy()
        }
    }
    AndroidView(factory = { holder.view }, modifier = modifier, update = { holder.show(places, done) })
}

private class PlacesMapHolder(context: Context, styleUrl: String) {
    val view: MapView
    var onPlace: (Long) -> Unit = {}
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var pending: Pair<List<LocatedTask>, Set<Long>>? = null
    private var framed = false

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                val source = GeoJsonSource(SOURCE)
                style.addSource(source)
                style.addLayer(
                    CircleLayer(LAYER, SOURCE).withProperties(
                        PropertyFactory.circleRadius(Expression.switchCase(Expression.get("done"), Expression.literal(11f), Expression.literal(10f))),
                        PropertyFactory.circleColor(byDone(DONE_COLOR, TODO_COLOR)),
                        PropertyFactory.circleStrokeWidth(Expression.switchCase(Expression.get("done"), Expression.literal(2f), Expression.literal(3f))),
                        PropertyFactory.circleStrokeColor(byDone(DONE_RING, TODO_RING)),
                    ),
                )
                this.source = source
                pending?.let { (places, done) -> show(places, done) }
            }
            map.addOnMapClickListener { latLng ->
                val point = map.projection.toScreenLocation(latLng)
                val slop = 24 * view.resources.displayMetrics.density
                val hit = map.queryRenderedFeatures(
                    RectF(point.x - slop, point.y - slop, point.x + slop, point.y + slop), LAYER,
                ).firstOrNull()
                val id = hit?.getNumberProperty("id")?.toLong() ?: return@addOnMapClickListener false
                onPlace(id)
                true
            }
        }
    }

    fun show(places: List<LocatedTask>, done: Set<Long>) {
        val source = source
        if (source == null) {
            pending = places to done
            return
        }
        pending = null
        val located = places.filter { it.position != null }
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                located.map { place ->
                    val p = place.position!!
                    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                        addNumberProperty("id", place.id)
                        addBooleanProperty("done", place.id in done)
                    }
                },
            ),
        )
        if (!framed && located.isNotEmpty()) {
            framed = true
            val map = map ?: return
            val padding = (48 * view.resources.displayMetrics.density).toInt()
            if (located.size == 1) {
                val p = located.first().position!!
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 16.0))
            } else {
                val bounds = LatLngBounds.Builder().apply {
                    located.forEach { include(LatLng(it.position!!.lat, it.position.lon)) }
                }.build()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            }
        }
    }

    private companion object {
        const val SOURCE = "places"
        const val LAYER = "places-circles"
    }
}

private fun byDone(done: String, todo: String): Expression = Expression.switchCase(
    Expression.get("done"), Expression.color(android.graphics.Color.parseColor(done)),
    Expression.color(android.graphics.Color.parseColor(todo)),
)
