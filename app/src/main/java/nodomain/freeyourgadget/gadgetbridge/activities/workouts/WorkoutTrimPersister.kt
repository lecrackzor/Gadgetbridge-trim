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

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.export.GPXExporter
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.proto.HuamiProtos
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Atomically applies a trimmed Huami/Zepp details `.bin` to both disk and the
 * [BaseActivitySummary] row so list/detail/export stay consistent.
 */
object WorkoutTrimPersister {
    private val LOG = LoggerFactory.getLogger(WorkoutTrimPersister::class.java)

    class Result(val ok: Boolean, val message: String? = null) {
        companion object {
            fun success() = Result(true)
            fun failure(message: String) = Result(false, message)
        }
    }

    fun apply(
        context: Context,
        summaryId: Long,
        trimmedBytes: ByteArray,
        startMs: Long,
        endMs: Long,
        keptDistanceMeters: Double,
    ): Result {
        if (endMs < startMs) {
            return Result.failure("End time is before start time")
        }
        if (trimmedBytes.isEmpty()) {
            return Result.failure("Trimmed details are empty")
        }

        val summary: BaseActivitySummary
        val gbDevice: GBDevice
        try {
            GBApplication.acquireDB().use { db ->
                summary = db.daoSession.baseActivitySummaryDao.load(summaryId)
                    ?: return Result.failure("Activity not found")
            }
            gbDevice = resolveGbDevice(summary)
                ?: return Result.failure("Device for this activity is not paired")
        } catch (e: Exception) {
            LOG.error("Failed to load activity {}", summaryId, e)
            return Result.failure(e.message ?: "Failed to load activity")
        }

        val detailsFile = FileUtils.tryFixPath(summary.rawDetailsPath)
            ?: return Result.failure("This activity has no raw details file to trim")

        val bakFile = File(detailsFile.path + ".bak")
        val originalBytes: ByteArray
        try {
            originalBytes = FileUtils.readAll(detailsFile)
            bakFile.writeBytes(originalBytes)
            detailsFile.writeBytes(trimmedBytes)
        } catch (e: IOException) {
            LOG.error("Failed writing trimmed details", e)
            return Result.failure(e.message ?: "Failed to write trimmed details")
        }

        return try {
            updateSummary(context, gbDevice, summary, startMs, endMs, keptDistanceMeters)
            Result.success()
        } catch (e: Exception) {
            LOG.error("Failed updating activity after trim; restoring bin", e)
            try {
                detailsFile.writeBytes(originalBytes)
            } catch (restoreError: IOException) {
                LOG.error("Failed restoring original details from backup", restoreError)
            }
            Result.failure(e.message ?: "Failed to update activity database")
        }
    }

    private fun updateSummary(
        context: Context,
        gbDevice: GBDevice,
        summary: BaseActivitySummary,
        startMs: Long,
        endMs: Long,
        keptDistanceMeters: Double,
    ) {
        val durationSeconds = max(1, ((endMs - startMs) / 1000.0).roundToInt())
        summary.startTime = Date(startMs)
        summary.endTime = Date(endMs)

        patchRawSummaryData(summary, startMs, endMs, durationSeconds, keptDistanceMeters)

        val parser = gbDevice.deviceCoordinator.getActivitySummaryParser(gbDevice, context)
        val workout = parser.parseWorkout(summary, true)
        if (workout != null) {
            // Re-assert times after parsers that derive end from raw duration.
            summary.startTime = Date(startMs)
            summary.endTime = Date(endMs)
            val data = workout.data ?: ActivitySummaryData.fromJson(summary.summaryData)
            data.add(ActivitySummaryEntries.ACTIVE_SECONDS, durationSeconds, ActivitySummaryEntries.UNIT_SECONDS)
            if (keptDistanceMeters > 0) {
                data.add(ActivitySummaryEntries.DISTANCE_METERS, keptDistanceMeters, ActivitySummaryEntries.UNIT_METERS)
            }
            summary.summaryData = data.toString()
        } else {
            val data = ActivitySummaryData.fromJson(summary.summaryData)
            data.add(ActivitySummaryEntries.ACTIVE_SECONDS, durationSeconds, ActivitySummaryEntries.UNIT_SECONDS)
            if (keptDistanceMeters > 0) {
                data.add(ActivitySummaryEntries.DISTANCE_METERS, keptDistanceMeters, ActivitySummaryEntries.UNIT_METERS)
            }
            summary.summaryData = data.toString()
        }

        refreshGpxIfPresent(context, gbDevice, summary)

        GBApplication.acquireDB().use { db ->
            db.daoSession.baseActivitySummaryDao.update(summary)
        }
    }

    private fun patchRawSummaryData(
        summary: BaseActivitySummary,
        startMs: Long,
        endMs: Long,
        durationSeconds: Int,
        keptDistanceMeters: Double,
    ) {
        val raw = summary.rawSummaryData ?: return
        if (raw.size < 4) return

        val version = (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
        if (version == 0x8000) {
            patchZeppOsRawSummary(summary, raw, startMs, durationSeconds, keptDistanceMeters)
            return
        }
        patchClassicHuamiRawSummary(summary, raw, startMs, endMs, keptDistanceMeters)
    }

    private fun patchZeppOsRawSummary(
        summary: BaseActivitySummary,
        raw: ByteArray,
        startMs: Long,
        durationSeconds: Int,
        keptDistanceMeters: Double,
    ) {
        val protobufData = raw.copyOfRange(2, raw.size)
        val parsed = try {
            HuamiProtos.WorkoutSummary.parseFrom(protobufData)
        } catch (e: Exception) {
            LOG.warn("Could not parse Zepp OS summary protobuf for patching", e)
            return
        }

        val builder = parsed.toBuilder()
        if (parsed.hasTime()) {
            val timeBuilder = parsed.time.toBuilder()
                .setTotalDuration(durationSeconds)
                .setWorkoutDuration(durationSeconds)
            builder.setTime(timeBuilder)
        } else {
            builder.setTime(
                HuamiProtos.Time.newBuilder()
                    .setTotalDuration(durationSeconds)
                    .setWorkoutDuration(durationSeconds)
                    .setPauseDuration(0)
            )
        }

        if (keptDistanceMeters > 0) {
            builder.setDistance(
                HuamiProtos.Distance.newBuilder().setDistance(keptDistanceMeters.toFloat())
            )
        }

        if (parsed.hasLocation()) {
            builder.setLocation(
                parsed.location.toBuilder().setStartTimestamp((startMs / 1000L).toInt())
            )
        }

        if (parsed.hasPace() && keptDistanceMeters > 1) {
            val paceSecPerM = durationSeconds.toFloat() / keptDistanceMeters.toFloat()
            builder.setPace(
                parsed.pace.toBuilder()
                    .setAvg(paceSecPerM)
            )
        }

        val patched = ByteArray(2 + builder.build().toByteArray().size)
        patched[0] = raw[0]
        patched[1] = raw[1]
        val body = builder.build().toByteArray()
        System.arraycopy(body, 0, patched, 2, body.size)
        summary.rawSummaryData = patched
    }

    private fun patchClassicHuamiRawSummary(
        summary: BaseActivitySummary,
        raw: ByteArray,
        startMs: Long,
        endMs: Long,
        keptDistanceMeters: Double,
    ) {
        if (raw.size < 12) return
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        // short version, short kind, then uint32 start/end seconds
        buffer.position(4)
        buffer.putInt((startMs / 1000L).toInt())
        buffer.putInt((endMs / 1000L).toInt())
        // Distance float is not at a stable offset across versions; leave binary distance
        // and rely on summaryData / re-parse enrichment for display.
        summary.rawSummaryData = raw
        if (keptDistanceMeters <= 0) {
            // no-op, kept for symmetry
        }
    }

    private fun refreshGpxIfPresent(context: Context, gbDevice: GBDevice, summary: BaseActivitySummary) {
        val gpxPath = summary.gpxTrack ?: return
        val gpxFile = FileUtils.tryFixPath(File(gpxPath)) ?: return
        val track = try {
            gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, context)
                ?.getActivityTrack(summary)
        } catch (e: Exception) {
            LOG.warn("Could not rebuild activity track for GPX refresh", e)
            null
        } ?: return

        try {
            GPXExporter().performExport(track, gpxFile, summary)
        } catch (e: Exception) {
            LOG.warn("Could not rewrite GPX after trim", e)
        }
    }

    private fun resolveGbDevice(summary: BaseActivitySummary): GBDevice? {
        val identifier = summary.device?.identifier ?: return null
        return GBApplication.app().deviceManager.devices.firstOrNull {
            it.address.equals(identifier, ignoreCase = true)
        }
    }
}
