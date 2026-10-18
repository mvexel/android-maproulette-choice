package lol.osm.streettally.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.customview.widget.ExploreByTouchHelper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import lol.osm.streettally.campaign.LatLon
import lol.osm.streettally.campaign.LocatedTask
import lol.osm.streettally.campaign.Places
import lol.osm.streettally.location.Locator
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point


/** A campaign's places on a MapLibre map with OpenFreeMap tiles, drawn as the brand pins ([Pin]).
 * Tapping a place calls [onPlace] with its task id; tapping elsewhere calls [onBackground].
 * TalkBack reaches each pin on screen, nearest [origin] first, read as [describe] says; a double tap
 * calls [onActivate] (A11y1). A campaign of more than [CLUSTER_ABOVE] places is clustered below
 * zoom 16 ([ClusterImage], L1-CLUSTER): tapping a cluster zooms in, and TalkBack reads it as
 * [describeCluster] says. Places that need another look are never clustered. */
@Composable
fun PlacesMap(
    styleUrl: String,
    places: List<LocatedTask>,
    done: Set<Long>,
    /** A guest's saved places (a subset of [done]): saved, not on the map yet. */
    pending: Set<Long> = emptySet(),
    /** Reported places: "Needs another look" ([Pin.LOOK]). */
    look: Set<Long> = emptySet(),
    /** A guest's answers still on this phone: "Waiting to send" ([Pin.WAITING]). */
    unsent: Set<Long> = emptySet(),
    /** Where moved places were reported to be: a small gray dot, joined to the pin by a dashed line. */
    movedTo: Map<Long, LatLon> = emptyMap(),
    locationPermitted: Boolean,
    onPlace: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onBackground: () -> Unit = {},
    origin: LatLon? = null,
    describe: (LocatedTask) -> String = { it.id.toString() },
    onActivate: (Long) -> Unit = onPlace,
    /** TalkBack's words for a cluster of `count` places, `done` of them checked or saved. */
    describeCluster: (count: Int, done: Int) -> String = { count, done -> "$count places, $done done" },
) {
    LocalMapPlaceholder.current?.let { placeholder ->
        placeholder(null, modifier, {})
        return
    }
    val context = LocalContext.current
    val holder = remember { PlacesMapHolder(context, styleUrl) }
    holder.onPlace = onPlace
    holder.onBackground = onBackground
    holder.onActivate = onActivate
    holder.describe = describe
    holder.describeCluster = describeCluster
    LaunchedEffect(locationPermitted) { holder.syncLocation() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> {
                    holder.view.onResume()
                    holder.syncLocation()
                }
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
    AndroidView(factory = { holder.view }, modifier = modifier, update = {
        holder.show(Shown(places, done, pending, look, unsent, movedTo, origin))
        holder.syncLocation()
    })
}

private class PlacesMapHolder(private val context: Context, styleUrl: String) {
    val view: MapView
    var onPlace: (Long) -> Unit = {}
    var onBackground: () -> Unit = {}
    var onActivate: (Long) -> Unit = {}
    var describe: (LocatedTask) -> String = { it.id.toString() }
    var describeCluster: (Int, Int) -> String = { count, done -> "$count places, $done done" }
    /** Every place with a position, nearest first. */
    private var pins: List<LocatedTask> = emptyList()
    /** What TalkBack can reach, nearest first; the index is the node id. Unclustered, every pin; clustered,
     * what the map drew on screen when the camera last stopped. */
    private var nodes: List<Node> = emptyList()
    private var clustered = false
    private val access: PinAccess
    private var map: MapLibreMap? = null
    private var source: GeoJsonSource? = null
    private var clusterSource: GeoJsonSource? = null
    private var movedSource: GeoJsonSource? = null
    private var waiting: Shown? = null
    /** What the map last drew: a recomposition with the same places redraws nothing (5,000 pins, AE6). */
    private var drawn: Shown? = null
    /** Pin builds started on the main thread; only the latest one is drawn. */
    private var builds = 0
    private var framed = false
    /** The origin of the last [show], for ordering what TalkBack reaches. */
    private var drawnOrigin: LatLng? = null

    init {
        MapLibre.getInstance(context)
        val dark = MapStyle.isDark(context)
        // MapLibre draws the pins as pixels: hover events go to [access], which gives TalkBack a node per pin.
        view = object : MapView(context) {
            override fun dispatchHoverEvent(event: MotionEvent): Boolean =
                access.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
        }
        access = PinAccess()
        ViewCompat.setAccessibilityDelegate(view, access)
        view.onCreate(null)
        view.addOnStyleImageMissingListener { id ->
            val (count, tenths) = ClusterImage.parse(id) ?: return@addOnStyleImageMissingListener
            map?.style?.addImage(id, ClusterImage.bitmap(count, tenths, view.resources.displayMetrics.density, dark))
        }
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromUri(MapStyle.url(styleUrl, dark))) { style ->
                if (dark) MapStyle.brightenLabels(style)
                // Under the pins: the dashed line from a moved place to its reported spot, then the dot.
                val moved = GeoJsonSource(MOVED_SOURCE)
                style.addSource(moved)
                style.addLayer(
                    LineLayer(MOVED_LINES, MOVED_SOURCE).withProperties(
                        PropertyFactory.lineColor(if (dark) MapStyle.LIGHT_GRAY else MOVED_GRAY),
                        PropertyFactory.lineWidth(1.5f),
                        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
                    ).apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString"))) },
                )
                style.addLayer(
                    CircleLayer(MOVED_DOTS, MOVED_SOURCE).withProperties(
                        PropertyFactory.circleColor(MOVED_GRAY),
                        PropertyFactory.circleRadius(4f),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(1f),
                    ).apply { setFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point"))) },
                )
                movedSource = moved
                val density = view.resources.displayMetrics.density
                Pin.entries.forEach { style.addImage(it.id, it.bitmap(density, dark)) }
                // Big campaigns: clusters and their unclustered pins, under the pins of [SOURCE].
                val clusters = GeoJsonSource(
                    CLUSTER_SOURCE,
                    GeoJsonOptions().withCluster(true).withClusterMaxZoom(CLUSTER_MAX_ZOOM).withClusterRadius(CLUSTER_RADIUS)
                        // How many in each cluster aren't to-check any more: its ring's green share.
                        .withClusterProperty(
                            "done", Expression.literal("+"),
                            Expression.switchCase(
                                Expression.eq(Expression.get("pin"), Expression.literal(Pin.TODO.id)),
                                Expression.literal(0), Expression.literal(1),
                            ),
                        ),
                )
                style.addSource(clusters)
                style.addLayer(
                    SymbolLayer(CLUSTER_PINS, CLUSTER_SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get("pin")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ).apply { setFilter(Expression.not(Expression.has("point_count"))) },
                )
                style.addLayer(
                    SymbolLayer(CLUSTER_LAYER, CLUSTER_SOURCE).withProperties(
                        // Drawn on demand ([ClusterImage]), so the count needs no map font.
                        PropertyFactory.iconImage(
                            Expression.concat(
                                Expression.literal(ClusterImage.PREFIX),
                                Expression.toString(Expression.get("point_count")),
                                Expression.literal("-"),
                                Expression.toString(
                                    Expression.round(
                                        Expression.product(
                                            Expression.literal(10),
                                            Expression.division(Expression.get("done"), Expression.get("point_count")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ).apply { setFilter(Expression.has("point_count")) },
                )
                clusterSource = clusters
                val source = GeoJsonSource(SOURCE)
                style.addSource(source)
                style.addLayer(
                    SymbolLayer(LAYER, SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get("pin")),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconIgnorePlacement(true),
                    ),
                )
                this.source = source
                syncLocation()
                waiting?.let { show(it) }
            }
            // Nodes follow the pins once the map stops moving; panning itself announces nothing.
            map.addOnCameraIdleListener {
                if (clustered) nodes = rendered()
                access.invalidateRoot()
            }
            // New places redraw the clusters without moving the camera.
            view.addOnDidBecomeIdleListener {
                if (clustered) {
                    nodes = rendered()
                    access.invalidateRoot()
                }
            }
            map.addOnMapClickListener { latLng ->
                val point = map.projection.toScreenLocation(latLng)
                val slop = 24 * view.resources.displayMetrics.density
                val box = RectF(point.x - slop, point.y - slop, point.x + slop, point.y + slop)
                // Pins first: a place that needs another look sits on top of any cluster.
                val hit = map.queryRenderedFeatures(box, LAYER, CLUSTER_PINS).firstOrNull()
                if (hit == null) {
                    map.queryRenderedFeatures(box, CLUSTER_LAYER).firstOrNull()?.let { cluster ->
                        zoomInto(cluster)
                        return@addOnMapClickListener true
                    }
                }
                val id = hit?.getNumberProperty("id")?.toLong()
                if (id == null) {
                    onBackground()
                    return@addOnMapClickListener false
                }
                onPlace(id)
                true
            }
        }
    }

    /** Zooms in on [cluster] until it splits. */
    private fun zoomInto(cluster: Feature) {
        val map = map ?: return
        val point = cluster.geometry() as? Point ?: return
        val zoom = clusterSource?.getClusterExpansionZoom(cluster)?.toDouble() ?: (map.cameraPosition.zoom + 2)
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude(), point.longitude()), zoom))
    }

    /** The clusters and pins the map drew on screen, nearest the origin (else the middle) first. */
    private fun rendered(): List<Node> {
        val map = map ?: return emptyList()
        val byId = pins.associateBy { it.id }
        val features = map.queryRenderedFeatures(
            RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()), CLUSTER_LAYER, CLUSTER_PINS, LAYER,
        )
        val drawn = features.mapNotNull { f ->
            val point = f.geometry() as? Point ?: return@mapNotNull null
            val at = LatLng(point.latitude(), point.longitude())
            val count = f.getNumberProperty("point_count")?.toInt()
            if (count != null) {
                Node.Cluster(f, count, f.getNumberProperty("done")?.toInt() ?: 0, at)
            } else {
                f.getNumberProperty("id")?.toLong()?.let(byId::get)?.let { Node.Place(it) }
            }
        }.distinctBy { node -> (node as? Node.Place)?.place?.id ?: node }
        val from = drawnOrigin ?: map.cameraPosition.target ?: return drawn
        return drawn.sortedBy { it.at.distanceTo(from) }
    }

    @SuppressLint("MissingPermission") // Rechecked before activating or enabling location.
    fun syncLocation() {
        val map = map ?: return
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val component = map.locationComponent
        val allowed = Locator.permitted(context)
        if (!allowed) {
            if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
            return
        }
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build(),
            )
            component.cameraMode = CameraMode.NONE
            component.renderMode = RenderMode.NORMAL
        }
        if (!component.isLocationComponentEnabled) component.isLocationComponentEnabled = true
    }

    fun show(shown: Shown) {
        val source = source
        if (source == null) {
            waiting = shown
            return
        }
        waiting = null
        val last = drawn
        if (shown == last) return
        drawn = shown
        val (places, done, pending, look, unsent, movedTo, origin) = shown
        val located = places.filter { it.position != null }
        pins = Places.sorted(located, origin)
        clustered = located.size > CLUSTER_ABOVE
        drawnOrigin = origin?.let { LatLng(it.lat, it.lon) }
        // Clustered, the nodes come from what the map draws, once the camera stops (rendered()).
        val ids = pins.mapTo(HashSet()) { it.id }
        nodes = if (clustered) nodes.filter { it !is Node.Place || it.place.id in ids } else pins.map { Node.Place(it) }
        access.invalidateRoot()
        // Walking only reorders TalkBack's pins; the map itself is unchanged.
        if (last != null && last.copy(origin = origin) == shown) return
        // The 5,000-feature collection is built off the main thread; only handing its JSON to MapLibre
        // happens here, and a newer build replaces one that finished late (AE6b).
        val build = ++builds
        val moved = movedSource
        val clusters = clusterSource
        val cluster = clustered
        BUILDER.execute {
            val movedJson = FeatureCollection.fromFeatures(
                located.filter { it.id in look }.flatMap { place ->
                    val spot = movedTo[place.id] ?: return@flatMap emptyList()
                    val from = Point.fromLngLat(place.position!!.lon, place.position.lat)
                    val to = Point.fromLngLat(spot.lon, spot.lat)
                    listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(from, to))), Feature.fromGeometry(to))
                },
            ).toJson()
            // Clustered, the places that need another look stay in [SOURCE], on top; the rest cluster.
            val (own, grouped) = if (cluster) located.partition { it.id in look } else located to emptyList()
            fun features(of: List<LocatedTask>) = FeatureCollection.fromFeatures(
                of.map { place ->
                    val p = place.position!!
                    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                        addNumberProperty("id", place.id)
                        val pin = when (place.id) {
                            in look -> Pin.LOOK
                            in unsent -> Pin.WAITING
                            in pending -> Pin.PENDING
                            in done -> Pin.DONE
                            else -> Pin.TODO
                        }
                        addStringProperty("pin", pin.id)
                    }
                },
            ).toJson()
            val pinJson = features(own)
            val clusterJson = features(grouped)
            view.post {
                if (build != builds) return@post
                moved?.setGeoJson(movedJson)
                clusters?.setGeoJson(clusterJson)
                source.setGeoJson(pinJson)
            }
        }
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
                // Once laid out: bounds fitted into no space land far off (after a recreation, the cached style can beat layout).
                view.doOnLayout { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding)) }
            }
        }
    }

    /** Where node [index] is on screen, or null when it's off the map's visible area. */
    private fun spot(index: Int): Rect? {
        val map = map ?: return null
        val node = nodes.getOrNull(index) ?: return null
        val point = map.projection.toScreenLocation(node.at)
        if (point.x < 0 || point.y < 0 || point.x > view.width || point.y > view.height) return null
        val dp = if (node is Node.Cluster) ClusterImage.diameter(node.count) / 2 else 24f
        val half = (dp * view.resources.displayMetrics.density).toInt()
        return Rect(point.x.toInt() - half, point.y.toInt() - half, point.x.toInt() + half, point.y.toInt() + half)
    }

    private inner class PinAccess : ExploreByTouchHelper(view) {
        override fun getVirtualViewAt(x: Float, y: Float): Int =
            nodes.indices.mapNotNull { i -> spot(i)?.takeIf { it.contains(x.toInt(), y.toInt()) }?.let { i to it } }
                .minByOrNull { (_, r) -> (r.exactCenterX() - x).let { dx -> dx * dx } + (r.exactCenterY() - y).let { dy -> dy * dy } }
                ?.first ?: INVALID_ID

        // The nearest on-screen pins only: thousands of nodes swamp TalkBack and the system (AE6).
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            nodes.indices.asSequence().filter { spot(it) != null }.take(MAX_PIN_NODES).toCollection(ids)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val shown = nodes.getOrNull(id)
            // A node must have bounds even if the pin moved off screen since the last refresh.
            @Suppress("DEPRECATION")
            node.setBoundsInParent(spot(id) ?: Rect(0, 0, 1, 1))
            node.contentDescription = when (shown) {
                is Node.Place -> describe(shown.place)
                is Node.Cluster -> describeCluster(shown.count, shown.done)
                null -> ""
            }
            // Clickable, or TalkBack's double-tap falls back to a tap on the map under the pin.
            node.isClickable = true
            // A stable id for PinAccessTest; TalkBack doesn't read it.
            node.viewIdResourceName = "${view.context.packageName}:id/${if (shown is Node.Cluster) "cluster" else "pin"}"
            // A cluster's double tap zooms in: TalkBack says "Double-tap to zoom in".
            node.addAction(
                if (shown is Node.Cluster) AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfoCompat.ACTION_CLICK, "zoom in")
                else AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            )
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            when (val shown = nodes.getOrNull(id) ?: return false) {
                is Node.Place -> onActivate(shown.place.id)
                is Node.Cluster -> zoomInto(shown.feature)
            }
            return true
        }
    }

    /** Something TalkBack can reach on the map. */
    private sealed interface Node {
        val at: LatLng

        data class Place(val place: LocatedTask) : Node {
            override val at get() = LatLng(place.position!!.lat, place.position.lon)
        }

        class Cluster(val feature: Feature, val count: Int, val done: Int, override val at: LatLng) : Node
    }

    private companion object {
        const val SOURCE = "places"
        const val LAYER = "places-pins"
        const val CLUSTER_SOURCE = "places-clustered"
        const val CLUSTER_PINS = "places-clustered-pins"
        const val CLUSTER_LAYER = "places-clusters"
        /** Clusters up to zoom 15; from 16 every place has its own pin (the same on iOS). */
        const val CLUSTER_MAX_ZOOM = 15
        /** In pixels, as MapLibre counts them (the same on iOS). */
        const val CLUSTER_RADIUS = 50
        const val MOVED_SOURCE = "moved"
        const val MOVED_LINES = "moved-lines"
        const val MOVED_DOTS = "moved-dots"
        const val MOVED_GRAY = "#8A8F98"
        /** TalkBack nodes for at most this many pins on screen, nearest first: enough for a whole
         * Woodbine survey (139 trees, which PinAccessTest walks), not thousands. */
        const val MAX_PIN_NODES = 200
    }
}

/** More places than this and the map clusters them (L1-CLUSTER); Woodbine's surveys (a few hundred) never do. */
internal const val CLUSTER_ABOVE = 500

/** Builds pin collections for every map, one at a time, off the main thread. */
private val BUILDER = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "map-pins").apply { isDaemon = true } }

private data class Shown(
    val places: List<LocatedTask>, val done: Set<Long>, val pending: Set<Long>, val look: Set<Long>, val unsent: Set<Long>,
    val movedTo: Map<Long, LatLon>, val origin: LatLon?,
)
