/*  Copyright (C) 2026

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.maps.MapsManager
import org.mapsforge.map.android.view.MapView
import org.slf4j.LoggerFactory
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Native trim UI for Huami/Zepp sports activity details. Uses Gadgetbridge's
 * offline Mapsforge maps ([MapsManager]) — same basemap as the GPS workout tab.
 * Range slider plus editable start/end times (snapped to nearest track point).
 */
class WorkoutTrimActivity : AbstractGBActivity() {
    private lateinit var mapView: MapView
    private lateinit var missingMapsWarning: TextView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var keptTime: TextView
    private lateinit var keptDistance: TextView
    private lateinit var cutTime: TextView
    private lateinit var trimRange: RangeSlider
    private lateinit var startTimeInput: TextInputEditText
    private lateinit var endTimeInput: TextInputEditText
    private lateinit var trimSave: MaterialButton

    private var mapsManager: MapsManager? = null
    private var workoutId: Long = -1L
    private var parsed: HuamiBinTrim.Parsed? = null
    private var startIndex: Int = 0
    private var endIndex: Int = 0
    private var updatingControls: Boolean = false

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workoutId = intent.getLongExtra(EXTRA_WORKOUT_ID, -1L)
        if (workoutId < 0) {
            GB.toast(this, "Missing workout id", Toast.LENGTH_SHORT, GB.ERROR)
            finish()
            return
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.activity_summary_trim_title)
        setContentView(R.layout.activity_workout_trim)

        mapView = findViewById(R.id.map_view)
        missingMapsWarning = findViewById(R.id.missing_maps_warning)
        loadingSpinner = findViewById(R.id.loading_spinner)
        keptTime = findViewById(R.id.kept_time)
        keptDistance = findViewById(R.id.kept_distance)
        cutTime = findViewById(R.id.cut_time)
        trimRange = findViewById(R.id.trim_range)
        startTimeInput = findViewById(R.id.start_time)
        endTimeInput = findViewById(R.id.end_time)
        trimSave = findViewById(R.id.trim_save)

        mapView.setBuiltInZoomControls(false)
        mapsManager = MapsManager(this, mapView).also { manager ->
            manager.loadMaps()
            missingMapsWarning.visibility =
                if (manager.isMapLoaded) View.GONE else View.VISIBLE
        }

        trimSave.setOnClickListener { saveTrim() }
        trimRange.addOnChangeListener { slider, _, _ ->
            if (updatingControls) return@addOnChangeListener
            val values = slider.values
            if (values.size < 2) return@addOnChangeListener
            startIndex = values[0].toInt()
            endIndex = values[1].toInt()
            updatePreview()
        }

        bindTimeField(startTimeInput, which = "start")
        bindTimeField(endTimeInput, which = "end")

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { loadAndParse() }
            loadingSpinner.visibility = View.GONE
            if (!ok) {
                finish()
                return@launch
            }
            setupControls()
        }
    }

    private fun bindTimeField(field: TextInputEditText, which: String) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applyTypedTime(which, field.text?.toString())
        }
        field.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done) {
                applyTypedTime(which, field.text?.toString())
                field.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun loadAndParse(): Boolean {
        return try {
            val detailsBytes: ByteArray
            GBApplication.acquireDB().use { db ->
                val summary = db.daoSession.baseActivitySummaryDao.load(workoutId)
                if (summary == null) {
                    runOnUiThread {
                        GB.toast(this, "Activity not found", Toast.LENGTH_LONG, GB.ERROR)
                    }
                    return false
                }
                val path = FileUtils.tryFixPath(summary.rawDetailsPath)
                if (path == null || !path.isFile) {
                    runOnUiThread {
                        GB.toast(
                            this,
                            getString(R.string.activity_summary_trim_no_details),
                            Toast.LENGTH_LONG,
                            GB.ERROR
                        )
                    }
                    return false
                }
                detailsBytes = FileUtils.readAll(path)
            }
            parsed = HuamiBinTrim.parseBin(detailsBytes)
            if (parsed!!.points.isEmpty()) {
                runOnUiThread {
                    GB.toast(
                        this,
                        getString(R.string.activity_summary_trim_no_points),
                        Toast.LENGTH_LONG,
                        GB.ERROR
                    )
                }
                return false
            }
            true
        } catch (e: Exception) {
            LOG.error("Failed loading details for trim", e)
            runOnUiThread {
                GB.toast(this, e.message ?: "Failed to load details", Toast.LENGTH_LONG, GB.ERROR, e)
            }
            false
        }
    }

    private fun setupControls() {
        val points = parsed?.points ?: return
        val max = (points.size - 1).coerceAtLeast(0).toFloat()
        startIndex = 0
        endIndex = points.size - 1
        updatingControls = true
        trimRange.valueFrom = 0f
        trimRange.valueTo = max.coerceAtLeast(1f)
        trimRange.values = listOf(0f, max.coerceAtLeast(1f))
        trimRange.isEnabled = points.size > 1
        updatingControls = false
        updatePreview()
    }

    private fun updatePreview() {
        val points = parsed?.points ?: return
        if (points.isEmpty()) return
        val start = startIndex.coerceIn(0, points.lastIndex)
        val end = endIndex.coerceIn(start, points.lastIndex)
        startIndex = start
        endIndex = end

        val slice = points.subList(start, end + 1)
        val durationMs = (slice.last().time - slice.first().time).coerceAtLeast(0)
        val distance = HuamiBinTrim.pathDistance(slice)
        keptTime.text = formatDuration(durationMs)
        keptDistance.text = formatDistance(distance)
        cutTime.text = cutLabel(
            points[start].time - points.first().time,
            points.last().time - points[end].time,
        )

        setTimeInput(startTimeInput, points[start].time)
        setTimeInput(endTimeInput, points[end].time)

        updatingControls = true
        trimRange.values = listOf(start.toFloat(), end.toFloat().coerceAtLeast(start.toFloat()))
        updatingControls = false

        val gpsPoints = slice.mapNotNull { p ->
            if (p.lat != null && p.lon != null) {
                GPSCoordinate(p.lon, p.lat, p.altitude ?: GPSCoordinate.UNKNOWN_ALTITUDE)
            } else {
                null
            }
        }
        if (gpsPoints.size >= 2) {
            mapView.post {
                try {
                    mapsManager?.setTrack(gpsPoints)
                } catch (e: Exception) {
                    LOG.warn("Failed drawing trim preview track", e)
                }
            }
        }
    }

    private fun setTimeInput(field: TextInputEditText, ms: Long) {
        if (field.hasFocus()) return
        field.setText(timeFormat.format(Date(ms)))
    }

    private fun nearestTime(ms: Long): Int {
        val points = parsed?.points ?: return 0
        var best = 0
        var bestD = Long.MAX_VALUE
        for (i in points.indices) {
            val d = abs(points[i].time - ms)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun applyTypedTime(which: String, value: String?) {
        val points = parsed?.points ?: return
        if (points.isEmpty() || value.isNullOrBlank()) {
            updatePreview()
            return
        }
        val ms = parseLocalTime(value)
        if (ms == null) {
            GB.toast(this, getString(R.string.activity_summary_trim_invalid_time), Toast.LENGTH_SHORT, GB.WARN)
            updatePreview()
            return
        }
        if (which == "start") {
            startIndex = nearestTime(ms).coerceAtMost(endIndex)
        } else {
            endIndex = nearestTime(ms).coerceAtLeast(startIndex)
        }
        updatePreview()
    }

    private fun parseLocalTime(value: String): Long? {
        val cleaned = value.trim().replace('T', ' ')
        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
        )
        for (pattern in patterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US)
                format.isLenient = false
                return format.parse(cleaned)?.time
            } catch (_: ParseException) {
                // try next
            }
        }
        return null
    }

    private fun saveTrim() {
        // Commit any in-progress typed times before saving.
        if (startTimeInput.hasFocus()) applyTypedTime("start", startTimeInput.text?.toString())
        if (endTimeInput.hasFocus()) applyTypedTime("end", endTimeInput.text?.toString())

        val data = parsed ?: return
        val points = data.points
        if (points.isEmpty()) return

        val start = startIndex.coerceIn(0, points.lastIndex)
        val end = endIndex.coerceIn(start, points.lastIndex)
        val startMs = points[start].time
        val endMs = points[end].time
        val slice = points.subList(start, end + 1)
        val distance = HuamiBinTrim.pathDistance(slice)

        trimSave.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val trimmed = HuamiBinTrim.trimBin(data.records, startMs, endMs, data.gpsTemplate)
                    WorkoutTrimPersister.apply(
                        this@WorkoutTrimActivity,
                        workoutId,
                        trimmed,
                        startMs,
                        endMs,
                        distance,
                    )
                } catch (e: Exception) {
                    LOG.error("Trim failed", e)
                    WorkoutTrimPersister.Result.failure(e.message ?: "Trim failed")
                }
            }
            if (result.ok) {
                GB.toast(
                    this@WorkoutTrimActivity,
                    getString(R.string.activity_summary_trim_saved),
                    Toast.LENGTH_SHORT,
                    GB.INFO
                )
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                trimSave.isEnabled = true
                GB.toast(
                    this@WorkoutTrimActivity,
                    result.message ?: getString(R.string.activity_summary_trim_failed),
                    Toast.LENGTH_LONG,
                    GB.ERROR
                )
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
            m > 0 -> String.format(Locale.US, "%dm %02ds", m, s)
            else -> String.format(Locale.US, "%ds", s)
        }
    }

    private fun formatDistance(meters: Double): String {
        return String.format(Locale.US, "%.2f km", meters / 1000.0)
    }

    private fun cutLabel(cutStartMs: Long, cutEndMs: Long): String {
        val parts = ArrayList<String>()
        if (cutStartMs >= 1000) {
            parts.add(getString(R.string.activity_summary_trim_cut_start, formatDuration(cutStartMs)))
        }
        if (cutEndMs >= 1000) {
            parts.add(getString(R.string.activity_summary_trim_cut_end, formatDuration(cutEndMs)))
        }
        return if (parts.isEmpty()) {
            getString(R.string.activity_summary_trim_cut_none)
        } else {
            parts.joinToString(", ")
        }
    }

    override fun onDestroy() {
        mapsManager?.onDestroy()
        mapsManager = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(WorkoutTrimActivity::class.java)
        const val EXTRA_WORKOUT_ID = "workout_id"

        fun createIntent(context: Context, workoutId: Long): Intent {
            return Intent(context, WorkoutTrimActivity::class.java).apply {
                putExtra(EXTRA_WORKOUT_ID, workoutId)
            }
        }
    }
}
