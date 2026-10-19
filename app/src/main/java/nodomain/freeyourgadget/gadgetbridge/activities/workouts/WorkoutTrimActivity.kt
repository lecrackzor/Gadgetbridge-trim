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
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream

/**
 * WebView trim UI for Huami/Zepp sports activity details. Persistence (bin + DB + GPX)
 * is owned by [WorkoutTrimPersister], not by the page itself.
 */
class WorkoutTrimActivity : AbstractGBActivity() {
    private lateinit var webView: WebView
    private var workoutId: Long = -1L
    private var detailsBytes: ByteArray = ByteArray(0)
    private var detailsFileName: String = "workout.bin"

    private var saveStartMs: Long = 0L
    private var saveEndMs: Long = 0L
    private var saveDistanceMeters: Double = 0.0
    private val saveBuffer = ByteArrayOutputStream()

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

        val root = FrameLayout(this)
        webView = WebView(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadDetails() }
            if (!loaded) {
                finish()
                return@launch
            }
            setupWebView()
        }
    }

    private fun loadDetails(): Boolean {
        return try {
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
                detailsFileName = path.name
                true
            }
        } catch (e: Exception) {
            LOG.error("Failed loading details for trim", e)
            runOnUiThread {
                GB.toast(this, e.message ?: "Failed to load details", Toast.LENGTH_LONG, GB.ERROR, e)
            }
            false
        }
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // app.js auto-loads when Android.getBinLength is present
            }
        }
        webView.loadUrl("file:///android_asset/trim_workout/index.html")
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getBinLength(): Int = detailsBytes.size

        @JavascriptInterface
        fun getFileName(): String = detailsFileName

        @JavascriptInterface
        fun getBinChunk(offset: Int, length: Int): String {
            val start = offset.coerceAtLeast(0)
            val end = (start + length).coerceAtMost(detailsBytes.size)
            if (start >= end) return ""
            return Base64.encodeToString(detailsBytes, start, end - start, Base64.NO_WRAP)
        }

        @JavascriptInterface
        fun beginSave(startMs: Long, endMs: Long, distanceMeters: Double) {
            saveStartMs = startMs
            saveEndMs = endMs
            saveDistanceMeters = distanceMeters
            saveBuffer.reset()
        }

        @JavascriptInterface
        fun appendSaveChunk(base64: String) {
            val chunk = Base64.decode(base64, Base64.DEFAULT)
            saveBuffer.write(chunk)
        }

        @JavascriptInterface
        fun commitSave() {
            val bytes = saveBuffer.toByteArray()
            saveBuffer.reset()
            runOnUiThread {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        WorkoutTrimPersister.apply(
                            this@WorkoutTrimActivity,
                            workoutId,
                            bytes,
                            saveStartMs,
                            saveEndMs,
                            saveDistanceMeters,
                        )
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
                        GB.toast(
                            this@WorkoutTrimActivity,
                            result.message ?: getString(R.string.activity_summary_trim_failed),
                            Toast.LENGTH_LONG,
                            GB.ERROR
                        )
                        webView.evaluateJavascript(
                            "(function(){var b=document.getElementById('saveBtn');if(b){b.disabled=false;b.textContent='Save trim';}})();",
                            null
                        )
                    }
                }
            }
        }
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
