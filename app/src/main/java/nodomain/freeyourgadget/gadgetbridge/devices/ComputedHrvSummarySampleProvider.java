/*  Copyright (C) 2025 Gideon Zenz

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

package nodomain.freeyourgadget.gadgetbridge.devices;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.SleepSession;

/**
 * Generic provider that computes HRV summary data from per-minute HRV value samples.
 * This provider can be used by any device that stores per-minute HRV data but doesn't
 * receive summary statistics from the device firmware.
 * <p>
 * The provider calculates:
 * - Weekly average (7-day rolling average of all HRV values)
 * - Last night average and 5-min high (based on actual sleep sessions detected from activity data)
 * - Baseline values using Garmin's method: mean ± standard deviation of 28 overnight averages
 * <p>
 * Summaries are computed on-demand and cached in a static cache shared across all instances.
 * This allows computed summaries to be reused even when the provider is recreated.
 */
public class ComputedHrvSummarySampleProvider implements TimeSampleProvider<HrvSummarySample> {
    private static final int DAYS_FOR_WEEKLY_AVG = 7;
    private static final int DAYS_FOR_BASELINE = 28; // Use 28 days (4 weeks) for baseline calculation
    private static final int CACHE_SIZE = 500; // Cache up to 500 computed summaries per device

    // Static cache shared across all instances, keyed by "deviceId:timestamp"
    // Using synchronizedMap for thread-safety
    private static final Map<String, HrvSummarySample> SUMMARY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, HrvSummarySample>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, HrvSummarySample> eldest) {
                    return size() > CACHE_SIZE;
                }
            }
    );

    private final TimeSampleProvider<? extends HrvValueSample> valueProvider;
    private final GBDevice device;
    private final DaoSession session;


    public ComputedHrvSummarySampleProvider(final TimeSampleProvider<? extends HrvValueSample> valueProvider,
                                           final GBDevice device,
                                           final DaoSession session) {
        this.valueProvider = valueProvider;
        this.device = device;
        this.session = session;
    }

    @NonNull
    @Override
    public List<HrvSummarySample> getAllSamples(long timestampFrom, long timestampTo) {
        // Prefetch once for the whole requested range (+ baseline lookback). Calling
        // generateSummaryForDay per day used to re-query ~28 days of activity/HRV samples
        // for every night, holding the DB lock for tens of seconds and stalling other tabs.
        final Prefetch prefetch = prefetchForRange(timestampFrom, timestampTo);
        final List<HrvSummarySample> summaries = new ArrayList<>();

        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampFrom);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        final Calendar endCal = Calendar.getInstance();
        endCal.setTimeInMillis(timestampTo);

        while (cal.getTimeInMillis() <= endCal.getTimeInMillis()) {
            final Calendar dayEnd = (Calendar) cal.clone();
            dayEnd.set(Calendar.HOUR_OF_DAY, 23);
            dayEnd.set(Calendar.MINUTE, 59);
            dayEnd.set(Calendar.SECOND, 59);
            dayEnd.set(Calendar.MILLISECOND, 999);

            final HrvSummarySample summary = generateSummaryForDay(dayEnd.getTimeInMillis(), prefetch);
            if (summary != null) {
                summaries.add(summary);
            }

            cal.add(Calendar.DATE, 1);
        }

        return summaries;
    }

    /**
     * Generate a summary sample for a specific day.
     * The timestamp should be at the end of the day (23:59:59).
     */
    private HrvSummarySample generateSummaryForDay(long dayEndTimestamp) {
        return generateSummaryForDay(dayEndTimestamp, prefetchForRange(dayEndTimestamp, dayEndTimestamp));
    }

    private HrvSummarySample generateSummaryForDay(long dayEndTimestamp, Prefetch prefetch) {
        // Check cache first - use device MAC address as part of cache key to separate devices
        final String cacheKey = device.getAddress() + ":" + dayEndTimestamp;
        final HrvSummarySample cached = SUMMARY_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(dayEndTimestamp);

        // Get start of current day
        final Calendar dayStart = (Calendar) cal.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);

        final List<? extends HrvValueSample> todaySamples = prefetch.hrvBetween(
                dayStart.getTimeInMillis(),
                dayEndTimestamp
        );

        if (todaySamples.isEmpty()) {
            return null;
        }

        // Calculate weekly average (past 7 days including today)
        final Calendar weekStart = (Calendar) dayStart.clone();
        weekStart.add(Calendar.DATE, -DAYS_FOR_WEEKLY_AVG + 1);
        final int weeklyAvg = calculateAverageHrv(
                weekStart.getTimeInMillis(),
                dayEndTimestamp,
                prefetch
        );

        // Previous night: noon-to-noon window ending at today's noon
        final Calendar searchStart = (Calendar) dayStart.clone();
        searchStart.add(Calendar.DATE, -1);
        searchStart.set(Calendar.HOUR_OF_DAY, 12);
        searchStart.set(Calendar.MINUTE, 0);
        searchStart.set(Calendar.SECOND, 0);
        searchStart.set(Calendar.MILLISECOND, 0);

        final Calendar searchEnd = (Calendar) dayStart.clone();
        searchEnd.set(Calendar.HOUR_OF_DAY, 12);
        searchEnd.set(Calendar.MINUTE, 0);
        searchEnd.set(Calendar.SECOND, 0);
        searchEnd.set(Calendar.MILLISECOND, 0);

        int lastNightAvg = 0;
        int lastNight5MinHigh = 0;

        final SleepSession lastSession = lastSessionStartingBetween(
                prefetch.sessions,
                searchStart.getTimeInMillis(),
                searchEnd.getTimeInMillis()
        );
        if (lastSession != null) {
            final long sessionStart = lastSession.getStartTime();
            final long sessionEnd = lastSession.getEndTime();
            lastNightAvg = calculateAverageHrv(sessionStart, sessionEnd, prefetch);
            lastNight5MinHigh = calculate5MinHighHrv(sessionStart, sessionEnd, prefetch);
        }

        final BaselineValues baseline = calculateBaseline(dayEndTimestamp, prefetch);
        final HrvSummarySample.Status status = determineStatus(weeklyAvg, baseline);

        final HrvSummarySample summary = new ComputedHrvSummarySample(
                dayEndTimestamp,
                weeklyAvg > 0 ? weeklyAvg : null,
                lastNightAvg > 0 ? lastNightAvg : null,
                lastNight5MinHigh > 0 ? lastNight5MinHigh : null,
                baseline.lowUpper > 0 ? baseline.lowUpper : null,
                baseline.balancedLower > 0 ? baseline.balancedLower : null,
                baseline.balancedUpper > 0 ? baseline.balancedUpper : null,
                status
        );

        SUMMARY_CACHE.put(cacheKey, summary);
        return summary;
    }

    private Prefetch prefetchForRange(long timestampFrom, long timestampTo) {
        final Calendar latestWindowEnd = Calendar.getInstance();
        latestWindowEnd.setTimeInMillis(Math.max(timestampFrom, timestampTo));
        latestWindowEnd.set(Calendar.HOUR_OF_DAY, 12);
        latestWindowEnd.set(Calendar.MINUTE, 0);
        latestWindowEnd.set(Calendar.SECOND, 0);
        latestWindowEnd.set(Calendar.MILLISECOND, 0);
        // Ensure noon window is at least on the day of timestampTo
        if (latestWindowEnd.getTimeInMillis() < timestampTo) {
            latestWindowEnd.add(Calendar.DATE, 1);
            latestWindowEnd.set(Calendar.HOUR_OF_DAY, 12);
            latestWindowEnd.set(Calendar.MINUTE, 0);
            latestWindowEnd.set(Calendar.SECOND, 0);
            latestWindowEnd.set(Calendar.MILLISECOND, 0);
        }

        final Calendar earliestWindowStart = Calendar.getInstance();
        earliestWindowStart.setTimeInMillis(Math.min(timestampFrom, timestampTo));
        earliestWindowStart.set(Calendar.HOUR_OF_DAY, 12);
        earliestWindowStart.set(Calendar.MINUTE, 0);
        earliestWindowStart.set(Calendar.SECOND, 0);
        earliestWindowStart.set(Calendar.MILLISECOND, 0);
        earliestWindowStart.add(Calendar.DATE, -DAYS_FOR_BASELINE);

        final long sessionFromMs = earliestWindowStart.getTimeInMillis();
        final long sessionToMs = latestWindowEnd.getTimeInMillis();

        final List<SleepSession> sessions = device.getDeviceCoordinator()
                .getSleepSessionProvider(device, session)
                .getSleepSessions(
                        (int) (sessionFromMs / 1000L),
                        (int) (sessionToMs / 1000L)
                );

        // Cover weekly averages and overnight HRV for every night in the baseline window.
        final long hrvFrom = Math.min(timestampFrom, sessionFromMs) - TimeUnit.DAYS.toMillis(DAYS_FOR_WEEKLY_AVG);
        final long hrvTo = Math.max(timestampTo, sessionToMs);
        final List<? extends HrvValueSample> hrvValues = valueProvider.getAllSamples(hrvFrom, hrvTo);

        return new Prefetch(sessions, hrvValues);
    }

    private int calculateAverageHrv(long timestampFrom, long timestampTo, Prefetch prefetch) {
        final List<? extends HrvValueSample> samples = prefetch.hrvBetween(timestampFrom, timestampTo);
        if (samples.isEmpty()) {
            return 0;
        }

        long sum = 0;
        int count = 0;
        for (final HrvValueSample sample : samples) {
            final int value = sample.getValue();
            if (value > 0) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0 : (int) (sum / count);
    }

    private int calculate5MinHighHrv(long timestampFrom, long timestampTo, Prefetch prefetch) {
        final List<? extends HrvValueSample> samples = prefetch.hrvBetween(timestampFrom, timestampTo);
        if (samples.size() < 5) {
            return 0;
        }

        int maxAvg = 0;
        for (int i = 0; i <= samples.size() - 5; i++) {
            int sum = 0;
            for (int j = i; j < i + 5; j++) {
                sum += samples.get(j).getValue();
            }
            final int avg = sum / 5;
            if (avg > maxAvg) {
                maxAvg = avg;
            }
        }

        return maxAvg;
    }

    private BaselineValues calculateBaseline(long timestampTo, Prefetch prefetch) {
        final List<Integer> overnightAverages = new ArrayList<>();
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestampTo);

        final Calendar latestWindowEnd = (Calendar) cal.clone();
        latestWindowEnd.set(Calendar.HOUR_OF_DAY, 12);
        latestWindowEnd.set(Calendar.MINUTE, 0);
        latestWindowEnd.set(Calendar.SECOND, 0);
        latestWindowEnd.set(Calendar.MILLISECOND, 0);

        final Calendar windowEnd = (Calendar) latestWindowEnd.clone();
        for (int i = 0; i < DAYS_FOR_BASELINE; i++) {
            final long windowEndMs = windowEnd.getTimeInMillis();
            final long windowStartMs = windowEndMs - 24L * 60 * 60 * 1000;

            final SleepSession lastSession = lastSessionStartingBetween(
                    prefetch.sessions,
                    windowStartMs,
                    windowEndMs
            );
            if (lastSession != null) {
                final int nightAvg = calculateAverageHrv(
                        lastSession.getStartTime(),
                        lastSession.getEndTime(),
                        prefetch
                );
                if (nightAvg > 0) {
                    overnightAverages.add(nightAvg);
                }
            }

            windowEnd.add(Calendar.DATE, -1);
        }

        if (overnightAverages.size() < DAYS_FOR_WEEKLY_AVG) {
            return new BaselineValues(0, 0, 0);
        }

        double sum = 0;
        for (int value : overnightAverages) {
            sum += value;
        }
        final double mean = sum / overnightAverages.size();

        double varianceSum = 0;
        for (int value : overnightAverages) {
            final double diff = value - mean;
            varianceSum += diff * diff;
        }
        final double stdDev = Math.sqrt(varianceSum / overnightAverages.size());

        final int baselineLower = (int) Math.round(mean - stdDev);
        final int baselineUpper = (int) Math.round(mean + stdDev);
        final int poorThreshold = (int) Math.round(mean - 1.5 * stdDev);

        return new BaselineValues(poorThreshold, baselineLower, baselineUpper);
    }

    @Nullable
    static SleepSession lastSessionStartingBetween(
            final List<SleepSession> sessions,
            final long windowStartMs,
            final long windowEndMs
    ) {
        SleepSession lastSession = null;
        for (final SleepSession candidate : sessions) {
            if (candidate.getStartTime() >= windowStartMs && candidate.getStartTime() < windowEndMs) {
                if (lastSession == null || candidate.getStartTime() > lastSession.getStartTime()) {
                    lastSession = candidate;
                }
            }
        }
        return lastSession;
    }

    private static final class Prefetch {
        final List<SleepSession> sessions;
        final List<? extends HrvValueSample> hrvValues;

        Prefetch(final List<SleepSession> sessions, final List<? extends HrvValueSample> hrvValues) {
            this.sessions = sessions;
            this.hrvValues = hrvValues;
        }

        List<? extends HrvValueSample> hrvBetween(final long fromMs, final long toMs) {
            if (hrvValues.isEmpty()) {
                return Collections.emptyList();
            }
            final List<HrvValueSample> out = new ArrayList<>();
            for (final HrvValueSample sample : hrvValues) {
                final long ts = sample.getTimestamp();
                if (ts >= fromMs && ts <= toMs) {
                    out.add(sample);
                }
            }
            return out;
        }
    }

    private HrvSummarySample.Status determineStatus(int weeklyAvg, BaselineValues baseline) {
        if (weeklyAvg == 0 || baseline.balancedLower == 0 || baseline.balancedUpper == 0) {
            return HrvSummarySample.Status.NONE;
        }

        if (weeklyAvg < baseline.lowUpper) {
            // Very low HRV - indicates poor recovery, possible overtraining or illness
            return HrvSummarySample.Status.POOR;
        } else if (weeklyAvg < baseline.balancedLower) {
            // Below baseline range but not critically low
            return HrvSummarySample.Status.LOW;
        } else if (weeklyAvg <= baseline.balancedUpper) {
            // Within normal baseline range
            return HrvSummarySample.Status.BALANCED;
        } else {
            // Above baseline range - could indicate stress or unusual recovery pattern
            return HrvSummarySample.Status.UNBALANCED;
        }
    }

    @Override
    public void addSample(HrvSummarySample timeSample) {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @Override
    public void addSamples(List<HrvSummarySample> timeSamples) {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @NonNull
    @Override
    public HrvSummarySample createSample() {
        // This provider is read-only and computes samples on-demand
        return new ComputedHrvSummarySample(
                System.currentTimeMillis(),
                null,
                null,
                null,
                null,
                null,
                null,
                HrvSummarySample.Status.NONE
        );
    }

    @Nullable
    @Override
    public HrvSummarySample getLatestSample() {
        // Get the latest HRV value sample timestamp
        final HrvValueSample latestValue = valueProvider.getLatestSample();
        if (latestValue == null) {
            return null;
        }

        // Generate summary for that day
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(latestValue.getTimestamp());
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);

        return generateSummaryForDay(cal.getTimeInMillis());
    }

    @Nullable
    @Override
    public HrvSummarySample getLatestSample(long until) {
        // Get the latest HRV value sample until the specified timestamp
        final HrvValueSample latestValue = valueProvider.getLatestSample(until);
        if (latestValue == null) {
            return null;
        }

        // Generate summary for that day
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(latestValue.getTimestamp());
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);

        return generateSummaryForDay(cal.getTimeInMillis());
    }

    @Nullable
    @Override
    public HrvSummarySample getFirstSample() {
        // Get the first HRV value sample timestamp
        final HrvValueSample firstValue = valueProvider.getFirstSample();
        if (firstValue == null) {
            return null;
        }

        // Generate summary for that day
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(firstValue.getTimestamp());
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        cal.set(Calendar.MILLISECOND, 999);

        return generateSummaryForDay(cal.getTimeInMillis());
    }

    /**
     * Invalidate a specific cache entry for a device and day.
     *
     * @param deviceAddress The MAC address of the device
     * @param dayEndTimestamp The end timestamp of the day (23:59:59.999)
     */
    public static void invalidateCacheEntry(String deviceAddress, long dayEndTimestamp) {
        final String cacheKey = deviceAddress + ":" + dayEndTimestamp;
        synchronized (SUMMARY_CACHE) {
            SUMMARY_CACHE.remove(cacheKey);
        }
    }

    /**
     * Clear cached summaries for a specific device. This should be called when new HRV data
     * is synced to ensure cached values are recalculated with the latest data.
     *
     * @param deviceAddress The MAC address of the device to clear cache for
     */
    public static void clearCache(String deviceAddress) {
        synchronized (SUMMARY_CACHE) {
            SUMMARY_CACHE.entrySet().removeIf(entry -> entry.getKey().startsWith(deviceAddress + ":"));
        }
    }

    /**
     * Clear all cached summaries for all devices.
     */
    public static void clearAllCache() {
        SUMMARY_CACHE.clear();
    }

    private static class BaselineValues {
        final int lowUpper;          // Threshold between POOR and LOW
        final int balancedLower;     // Lower bound of balanced range (mean - stdDev)
        final int balancedUpper;     // Upper bound of balanced range (mean + stdDev)

        BaselineValues(int lowUpper, int balancedLower, int balancedUpper) {
            this.lowUpper = lowUpper;
            this.balancedLower = balancedLower;
            this.balancedUpper = balancedUpper;
        }
    }

    /**
     * Computed HRV summary sample.
     * This is not stored in the database but computed on-demand from HRV value samples.
     */
    public static class ComputedHrvSummarySample implements HrvSummarySample {
        private final long timestamp;
        private final Integer weeklyAverage;
        private final Integer lastNightAverage;
        private final Integer lastNight5MinHigh;
        private final Integer baselineLowUpper;
        private final Integer baselineBalancedLower;
        private final Integer baselineBalancedUpper;
        private final Status status;

        public ComputedHrvSummarySample(
                long timestamp,
                Integer weeklyAverage,
                Integer lastNightAverage,
                Integer lastNight5MinHigh,
                Integer baselineLowUpper,
                Integer baselineBalancedLower,
                Integer baselineBalancedUpper,
                Status status
        ) {
            this.timestamp = timestamp;
            this.weeklyAverage = weeklyAverage;
            this.lastNightAverage = lastNightAverage;
            this.lastNight5MinHigh = lastNight5MinHigh;
            this.baselineLowUpper = baselineLowUpper;
            this.baselineBalancedLower = baselineBalancedLower;
            this.baselineBalancedUpper = baselineBalancedUpper;
            this.status = status;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public Integer getWeeklyAverage() {
            return weeklyAverage;
        }

        @Override
        public Integer getLastNightAverage() {
            return lastNightAverage;
        }

        @Override
        public Integer getLastNight5MinHigh() {
            return lastNight5MinHigh;
        }

        @Override
        public Integer getBaselineLowUpper() {
            return baselineLowUpper;
        }

        @Override
        public Integer getBaselineBalancedLower() {
            return baselineBalancedLower;
        }

        @Override
        public Integer getBaselineBalancedUpper() {
            return baselineBalancedUpper;
        }

        @Override
        public Status getStatus() {
            return status;
        }
    }
}

