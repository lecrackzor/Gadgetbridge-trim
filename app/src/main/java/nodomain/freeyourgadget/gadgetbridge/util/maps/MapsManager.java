/*  Copyright (C) 2024-2025 José Rebelo

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.util.maps;

import android.content.Context;

import androidx.annotation.Nullable;

import org.mapsforge.core.graphics.Paint;
import org.mapsforge.core.graphics.Style;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Dimension;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.util.LatLongUtils;
import org.mapsforge.map.android.graphics.AndroidGraphicFactory;
import org.mapsforge.map.android.util.AndroidUtil;
import org.mapsforge.map.android.view.MapView;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.overlay.Polyline;
import org.mapsforge.map.layer.renderer.TileRendererLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.util.Accumulator;

public final class MapsManager {
    private static final Logger LOG = LoggerFactory.getLogger(MapsManager.class);

    public static final String PREF_MAPS_FOLDER = "maps_folder";
    public static final String PREF_TRACK_COLOR = "maps_track_color";
    public static final String PREF_MAP_THEME = "maps_theme";
    public static final String PREF_MAP_THEME_DARK = "maps_theme_dark";
    /** Theme value that uses the user-picked theme file instead of a bundled theme. */
    public static final String MAP_THEME_VALUE_CUSTOM = "custom";
    /** Content URI of the user-picked render theme, used when the theme is custom. */
    public static final String PREF_MAP_THEME_CUSTOM_URI = "maps_theme_custom_uri";
    /** Content URI of the user-picked render theme, used in dark mode when the dark theme is custom. */
    public static final String PREF_MAP_THEME_DARK_CUSTOM_URI = "maps_theme_dark_custom_uri";

    private final Context mContext;
    private final MapView mapView;
    private final MapDataLoader mapDataLoader;
    private Polyline polyline;

    private TileRendererLayer tileRendererLayer;
    private boolean tileRenderedAdded;

    private boolean isMapLoaded = false;

    public MapsManager(final Context context, final MapView mapView) {
        this.mContext = context;
        this.mapView = mapView;
        this.mapDataLoader = new MapDataLoader(context);
    }

    /**
     * Opens map files from the configured folder. Safe to call off the main thread —
     * pair with {@link #applyMapLoadResult(MapLoadResult)} on the UI thread.
     */
    public MapLoadResult loadMapData() {
        return mapDataLoader.loadMultiMapDataStore();
    }

    public void loadMaps() {
        applyMapLoadResult(loadMapData());
    }

    /**
     * Applies a previously loaded datastore to the MapView. Must run on the UI thread.
     */
    public void applyMapLoadResult(final MapLoadResult loadResult) {
        if (tileRendererLayer != null) {
            mapView.getLayerManager().getLayers().remove(tileRendererLayer);
            tileRendererLayer.onDestroy();
            tileRendererLayer.getTileCache().purge();
            tileRendererLayer = null;
        }

        mapView.getModel().displayModel.setBackgroundColor(GBApplication.getWindowBackgroundColor(mapView.getContext()));

        isMapLoaded = false;
        tileRenderedAdded = false;

        AndroidGraphicFactory.createInstance(GBApplication.app());

        isMapLoaded = loadResult.getAnyLoaded();

        final TileCache tileCache = AndroidUtil.createTileCache(
                mContext,
                "mapcache",
                mapView.getModel().displayModel.getTileSize(),
                1f,
                mapView.getModel().frameBufferModel.getOverdrawFactor()
        );

        tileRendererLayer = new TileRendererLayer(
                tileCache,
                loadResult.getDataStore(),
                mapView.getModel().mapViewPosition,
                true,
                false,
                false,
                AndroidGraphicFactory.INSTANCE
        );

        tileRendererLayer.setXmlRenderTheme(mapDataLoader.resolveTheme());
        // Do not add the tile renderer layer before setting the bounding box in setTrack,
        // otherwise we load the entire map to memory and might crash with OOM
    }

    public boolean isMapLoaded() {
        return isMapLoaded;
    }

    public void onDestroy() {
        if (polyline != null) {
            mapView.getLayerManager().getLayers().remove(polyline);
            polyline.onDestroy();
            polyline = null;
        }

        if (tileRendererLayer != null) {
            if (tileRenderedAdded) {
                mapView.getLayerManager().getLayers().remove(tileRendererLayer);
            }
            tileRendererLayer.onDestroy();
            tileRendererLayer.getTileCache().purge();
            tileRendererLayer = null;
        }

        isMapLoaded = false;
    }

    private BoundingBox minimalBoundingBox(double minLat, double minLon, double maxLat, double maxLon) {
        final LatLong center = new LatLong(minLat + (maxLat - minLat) / 2, minLon + (maxLon - minLon) / 2);
        final double minLatDistance = LatLongUtils.latitudeDistance(1000);
        final double minLonDistance = LatLongUtils.longitudeDistance(1000, center.latitude);
        if ((maxLat - minLat) < minLatDistance) {
            maxLat = center.latitude + minLatDistance/2;
            minLat = center.latitude - minLatDistance/2;
        }
        if ((maxLon - minLon) < minLonDistance) {
            maxLon = center.longitude + minLonDistance/2;
            minLon = center.longitude - minLonDistance/2;
        }

        return new BoundingBox(minLat, minLon, maxLat, maxLon);
    }

    public void setTrack(final List<? extends GPSCoordinate> trackPoints) {
        setTrack(trackPoints, true);
    }

    /**
     * Draws the track polyline. When {@code fitBounds} is false, only the path is updated —
     * the current center/zoom are left alone (needed for interactive trim previews).
     */
    public void setTrack(final List<? extends GPSCoordinate> trackPoints, final boolean fitBounds) {
        if (trackPoints == null || trackPoints.isEmpty()) {
            return;
        }

        final List<LatLong> points = trackPoints.stream()
                .map(p -> new LatLong(p.getLatitude(), p.getLongitude()))
                .collect(Collectors.toList());

        if (polyline == null) {
            final Paint paint = AndroidGraphicFactory.INSTANCE.createPaint();
            final int trackColor = mapDataLoader.resolveTrackColor();
            paint.setColor(trackColor);
            paint.setStrokeWidth(8);
            paint.setStyle(Style.STROKE);

            polyline = new Polyline(paint, AndroidGraphicFactory.INSTANCE);
            mapView.addLayer(polyline);
        }
        polyline.setPoints(points);

        if (!fitBounds) {
            // Keep an already-fitted view stable (trim slider); still attach tiles if needed.
            addTileRendererLayer();
            return;
        }

        final Accumulator latitudeAccumulator = new Accumulator();
        final Accumulator longitudeAccumulator = new Accumulator();
        for (GPSCoordinate trackPoint : trackPoints) {
            latitudeAccumulator.add(trackPoint.getLatitude());
            longitudeAccumulator.add(trackPoint.getLongitude());
        }
        setBoundingBox(minimalBoundingBox(
                latitudeAccumulator.getMin(),
                longitudeAccumulator.getMin(),
                latitudeAccumulator.getMax(),
                longitudeAccumulator.getMax()
        ));
    }

    /**
     * Moves the view to fit the bounding box.
     */
    public void setBoundingBox(final BoundingBox boundingBox) {
        mapView.setCenter(boundingBox.getCenterPoint());

        final byte zoom = LatLongUtils.zoomForBounds(
                new Dimension(mapView.getWidth(), mapView.getHeight()),
                boundingBox,
                mapView.getModel().displayModel.getTileSize()
        );
        mapView.setZoomLevel(zoom);

        addTileRendererLayer();
    }

    /**
     * Moves the view to the center at the zoom level.
     */
    public void setCenter(final LatLong center, final byte zoom) {
        mapView.setCenter(center);
        mapView.setZoomLevel(zoom);

        addTileRendererLayer();
    }

    /**
     * Returns the area that the loaded map files cover, or null if no map file is loaded.
     */
    @Nullable
    public BoundingBox getMapBounds() {
        if (!isMapLoaded || tileRendererLayer == null) {
            return null;
        }
        return tileRendererLayer.getMapDataStore().boundingBox();
    }

    private void addTileRendererLayer() {
        if (!tileRenderedAdded) {
            mapView.getLayerManager().getLayers().add(0, tileRendererLayer);
            tileRenderedAdded = true;
        }
    }

    public void reload() {
        if (polyline != null) {
            final int trackColor = mapDataLoader.resolveTrackColor();
            polyline.getPaintStroke().setColor(trackColor);
            polyline.requestRedraw();
        }

        loadMaps();
    }
}
