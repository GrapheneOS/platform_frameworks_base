/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.location.fudger;

import static com.android.internal.location.geometry.S2CellIdUtils.LAT_INDEX;
import static com.android.internal.location.geometry.S2CellIdUtils.LNG_INDEX;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationResult;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.location.geometry.S2CellIdUtils;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider.PopulationDensityUnavailableException;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Contains the logic to obfuscate (fudge) locations for coarse applications. The goal is just to
 * prevent applications with only the coarse location permission from receiving a fine location.
 */
public class LocationFudger {

    private static final String TAG = "LocationFudger";

    // Minimum scale for the random coarsening offset.
    private static final float MIN_ACCURACY_M = 200.0f;

    // how often random offsets are updated
    @VisibleForTesting
    static final long OFFSET_UPDATE_INTERVAL_MS = 60 * 60 * 1000;

    // the percentage that we change the random offset at every interval. 0.0 indicates the random
    // offset doesn't change. 1.0 indicates the random offset is completely replaced every interval
    private static final double CHANGE_PER_INTERVAL = 0.03;  // 3% change

    // weights used to move the random offset. the goal is to iterate on the previous offset, but
    // keep the resulting standard deviation the same. the variance of two gaussian distributions
    // summed together is equal to the sum of the variance of each distribution. so some quick
    // algebra results in the following sqrt calculation to weight in a new offset while keeping the
    // final standard deviation unchanged.
    private static final double NEW_WEIGHT = CHANGE_PER_INTERVAL;
    private static final double OLD_WEIGHT = Math.sqrt(1 - NEW_WEIGHT * NEW_WEIGHT);

    // this number actually varies because the earth is not round, but 111,000 meters is considered
    // generally acceptable
    private static final int APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;

    // we pick a value 1 meter away from 90.0 degrees in order to keep cosine(MAX_LATITUDE) to a
    // non-zero value, so that we avoid divide by zero errors
    private static final double MAX_LATITUDE =
            90.0 - (1.0 / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR);

    // The average edge length in km of an S2 cell, indexed by S2 levels 0 to
    // 12. Level 12 is the highest level used for coarsening.
    // This approximation assumes the S2 cells are squares.
    // For density-based coarsening, we use the edge to set the accuracy of the
    // coarsened location.
    // The values are from http://s2geometry.io/resources/s2cell_statistics.html
    // We take square root of the average area.
    private static final float[] S2_CELL_AVG_EDGE_PER_LEVEL = new float[] {
            9220.14f, 4610.07f, 2305.04f, 1152.52f, 576.26f, 288.13f, 144.06f,
            72.03f, 36.02f, 18.01f, 9f, 4.50f, 2.25f};

    // Also limits the location precision sent to the provider.
    private static final int MAX_COARSENING_S2_LEVEL = S2_CELL_AVG_EDGE_PER_LEVEL.length - 1;

    // Bound batch work before each location. One in-flight query may extend past this duration.
    @VisibleForTesting
    static final long MAX_BATCH_COARSENING_DURATION_MS = 100;

    // Rate-limit persistent provider faults.
    private static final long FAULT_LOG_INTERVAL_MS = 60 * 1000;

    // Re-query cached failures after transient faults that do not trigger a provider rebind.
    @VisibleForTesting
    static final long NEGATIVE_CACHE_TTL_MS = 2000;

    private final float mAccuracyM;
    private final Clock mClock;
    private final Random mRandom;

    @GuardedBy("this")
    private double mLatitudeOffsetM;
    @GuardedBy("this")
    private double mLongitudeOffsetM;
    @GuardedBy("this")
    private long mNextUpdateRealtimeMs;

    // Cache the latest input by identity to share one outcome across registrations.
    @GuardedBy("this")
    @Nullable private Location mCachedFineLocation;
    @GuardedBy("this")
    @Nullable private Location mCachedCoarseLocation;
    @GuardedBy("this")
    private long mCachedLocationGeneration;
    @GuardedBy("this")
    @Nullable private ProxyPopulationDensityProvider mCachedLocationProvider;
    @GuardedBy("this")
    private long mCachedLocationFailureRealtimeMs;

    @GuardedBy("this")
    @Nullable private LocationResult mCachedFineLocationResult;
    @GuardedBy("this")
    @Nullable private LocationResult mCachedCoarseLocationResult;
    @GuardedBy("this")
    private long mCachedLocationResultGeneration;
    @GuardedBy("this")
    @Nullable private ProxyPopulationDensityProvider mCachedLocationResultProvider;
    @GuardedBy("this")
    private long mCachedLocationResultFailureRealtimeMs;

    @GuardedBy("this")
    private long mNextFaultLogRealtimeMs;

    @GuardedBy("this")
    @Nullable private ProxyPopulationDensityProvider mPopulationDensityProvider = null;

    /**
     * Creates a location fudger with the given random-offset scale.
     *
     * <p>The population density provider selects the S2 coarsening level.
     */
    public LocationFudger(float accuracyM) {
        this(accuracyM, SystemClock.elapsedRealtimeClock(), new SecureRandom());
    }

    @VisibleForTesting
    LocationFudger(float accuracyM, Clock clock, Random random) {
        mClock = clock;
        mRandom = random;
        mAccuracyM = Math.max(accuracyM, MIN_ACCURACY_M);

        resetOffsets();
    }

    /** Sets the population density provider, or null to make coarsening fail closed. */
    public void setPopulationDensityProvider(@Nullable ProxyPopulationDensityProvider provider) {
        synchronized (this) {
            if (mPopulationDensityProvider == provider) {
                return;
            }
            mPopulationDensityProvider = provider;
            mCachedFineLocation = null;
            mCachedCoarseLocation = null;
            mCachedLocationProvider = null;
            mCachedFineLocationResult = null;
            mCachedCoarseLocationResult = null;
            mCachedLocationResultProvider = null;
        }
    }

    /**
     * Resets the random offsets completely.
     */
    public void resetOffsets() {
        mLatitudeOffsetM = nextRandomOffset();
        mLongitudeOffsetM = nextRandomOffset();
        mNextUpdateRealtimeMs = mClock.millis() + OFFSET_UPDATE_INTERVAL_MS;
    }

    /**
     * Coarsens a location result from oldest to newest.
     *
     * <p>A provider fault suppresses the result. Reaching the batch deadline before a location
     * returns the completed prefix, or null before the first location. This deadline also applies
     * to a one-entry result; {@link #createCoarse(Location)} has no batch deadline.
     */
    public @Nullable LocationResult createCoarse(LocationResult fineLocationResult) {
        ProxyPopulationDensityProvider provider;
        long providerGeneration;
        synchronized (this) {
            provider = mPopulationDensityProvider;
            providerGeneration = currentBindingGeneration();
            if (isCurrentProviderLocked(provider, providerGeneration)
                    && (fineLocationResult == mCachedFineLocationResult
                            || fineLocationResult == mCachedCoarseLocationResult)) {
                if (mCachedLocationResultProvider == provider
                        && mCachedLocationResultGeneration == providerGeneration
                        && (mCachedCoarseLocationResult != null
                                || mClock.millis() - mCachedLocationResultFailureRealtimeMs
                                        < NEGATIVE_CACHE_TTL_MS)) {
                    return mCachedCoarseLocationResult;
                }
            }
        }

        List<Location> fineLocations = fineLocationResult.asList();
        ArrayList<Location> coarseLocations = new ArrayList<>(fineLocations.size());
        long batchDeadlineRealtimeMs = mClock.millis() + MAX_BATCH_COARSENING_DURATION_MS;
        for (Location fineLocation : fineLocations) {
            if (mClock.millis() >= batchDeadlineRealtimeMs) {
                logCoarseningFault("batch exceeded coarsening deadline");
                break;
            }
            Location coarseLocation = createCoarse(fineLocation, provider, providerGeneration);
            if (coarseLocation == null) {
                return recordBatchFailure(fineLocationResult, provider, providerGeneration);
            }
            coarseLocations.add(coarseLocation);
        }
        if (coarseLocations.isEmpty()) {
            return recordBatchFailure(fineLocationResult, provider, providerGeneration);
        }
        LocationResult coarseLocationResult = LocationResult.wrap(coarseLocations);

        synchronized (this) {
            if (!isCurrentProvider(provider, providerGeneration)) {
                return recordBatchFailure(fineLocationResult, provider, providerGeneration);
            }
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = coarseLocationResult;
            mCachedLocationResultProvider = provider;
            mCachedLocationResultGeneration = providerGeneration;
        }

        return coarseLocationResult;
    }

    /**
     * Creates a density-coarsened location, or returns null on a provider fault.
     *
     * <p>The provider sees the center of the offset point's finest accepted S2 cell. Only its
     * returned level is trusted; the output cell is derived locally.
     */
    public @Nullable Location createCoarse(Location fine) {
        ProxyPopulationDensityProvider provider;
        long providerGeneration;
        synchronized (this) {
            provider = mPopulationDensityProvider;
            providerGeneration = currentBindingGeneration();
        }
        return createCoarse(fine, provider, providerGeneration);
    }

    private @Nullable Location createCoarse(Location fine,
            @Nullable ProxyPopulationDensityProvider provider,
            long providerGeneration) {
        synchronized (this) {
            if ((fine == mCachedFineLocation || fine == mCachedCoarseLocation)
                    && mCachedLocationProvider == provider
                    && mCachedLocationGeneration == providerGeneration) {
                if (mCachedCoarseLocation != null) {
                    return mCachedCoarseLocation;
                }
                if (mClock.millis() - mCachedLocationFailureRealtimeMs
                        < NEGATIVE_CACHE_TTL_MS) {
                    return null;
                }
            }
        }

        // update the offsets in use
        updateOffsets();

        // Build the coarse location from an allowlist: start from a fresh location and copy only
        // non-sensitive fields, so no present or future Location field can leak fine-grained data
        // to coarse-only apps.
        Location coarse = new Location(fine.getProvider());
        coarse.setTime(fine.getTime());
        coarse.setElapsedRealtimeNanos(fine.getElapsedRealtimeNanos());
        if (fine.hasElapsedRealtimeUncertaintyNanos()) {
            coarse.setElapsedRealtimeUncertaintyNanos(fine.getElapsedRealtimeUncertaintyNanos());
        }
        // The mock flag is not location data and must survive so mock locations stay identifiable
        // (isMock()) and are cleared correctly when a test provider is removed.
        coarse.setMock(fine.isMock());

        double latitude = wrapLatitude(fine.getLatitude());
        double longitude = wrapLongitude(fine.getLongitude());

        // add offsets - update longitude first using the non-offset latitude
        longitude += wrapLongitude(metersToDegreesLongitude(mLongitudeOffsetM, latitude));
        latitude += wrapLatitude(metersToDegreesLatitude(mLatitudeOffsetM));

        // The sums can leave the valid coordinate ranges (only the base coordinates and the
        // offsets were normalized individually), so re-normalize before deriving cells.
        latitude = wrapLatitude(latitude);
        longitude = wrapLongitude(longitude);

        // Limit provider input precision without changing any accepted parent cell.
        long queryS2CellId = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(latitude, longitude), MAX_COARSENING_S2_LEVEL);
        double[] queryPoint = new double[] {0.0, 0.0};
        S2CellIdUtils.toLatLngDegrees(queryS2CellId, queryPoint);

        if (provider == null) {
            logCoarseningFault("no population density provider configured");
            return recordFailure(fine, provider, providerGeneration);
        }
        if (!isCurrentProvider(provider, providerGeneration)) {
            return recordFailure(fine, provider, providerGeneration);
        }

        long s2CellId;
        try {
            s2CellId = provider.getCoarsenedS2CellId(
                    queryPoint[LAT_INDEX], queryPoint[LNG_INDEX]);
        } catch (PopulationDensityUnavailableException e) {
            logCoarseningFault("density query failed: " + e.getMessage());
            return recordFailure(fine, provider, providerGeneration);
        }
        if (!isCurrentProvider(provider, providerGeneration)) {
            return recordFailure(fine, provider, providerGeneration);
        }
        // Trust only the returned level; derive the cell locally from the query point.
        int level = S2CellIdUtils.getLevel(s2CellId);
        if (level < 0 || level > MAX_COARSENING_S2_LEVEL) {
            logCoarseningFault("provider returned invalid coarsening level " + level);
            return recordFailure(fine, provider, providerGeneration);
        }
        snapToCenterOfS2Cell(queryPoint[LAT_INDEX], queryPoint[LNG_INDEX], level, queryPoint);
        float accuracy = getS2CellApproximateEdge(level);

        coarse.setLatitude(queryPoint[LAT_INDEX]);
        coarse.setLongitude(queryPoint[LNG_INDEX]);
        coarse.setAccuracy(Math.max(accuracy, fine.getAccuracy()));

        synchronized (this) {
            if (!isCurrentProvider(provider, providerGeneration)) {
                return recordFailure(fine, provider, providerGeneration);
            }
            mCachedFineLocation = fine;
            mCachedCoarseLocation = coarse;
            mCachedLocationProvider = provider;
            mCachedLocationGeneration = providerGeneration;
        }

        return coarse;
    }

    private @Nullable Location recordFailure(Location fine,
            @Nullable ProxyPopulationDensityProvider provider, long generation) {
        synchronized (this) {
            if (!isCurrentProviderLocked(provider, generation)) {
                return null;
            }
            mCachedFineLocation = fine;
            mCachedCoarseLocation = null;
            mCachedLocationProvider = provider;
            mCachedLocationGeneration = generation;
            mCachedLocationFailureRealtimeMs = mClock.millis();
        }
        return null;
    }

    @GuardedBy("this")
    private long currentBindingGeneration() {
        return mPopulationDensityProvider == null
                ? 0 : mPopulationDensityProvider.getBindingGeneration();
    }

    private boolean isCurrentProvider(@Nullable ProxyPopulationDensityProvider provider,
            long generation) {
        synchronized (this) {
            return isCurrentProviderLocked(provider, generation);
        }
    }

    @GuardedBy("this")
    private boolean isCurrentProviderLocked(@Nullable ProxyPopulationDensityProvider provider,
            long generation) {
        return mPopulationDensityProvider == provider
                && (provider == null || provider.getBindingGeneration() == generation);
    }

    private @Nullable LocationResult recordBatchFailure(LocationResult fineLocationResult,
            @Nullable ProxyPopulationDensityProvider provider, long generation) {
        synchronized (this) {
            if (!isCurrentProviderLocked(provider, generation)) {
                return null;
            }
            mCachedFineLocationResult = fineLocationResult;
            mCachedCoarseLocationResult = null;
            mCachedLocationResultProvider = provider;
            mCachedLocationResultGeneration = generation;
            mCachedLocationResultFailureRealtimeMs = mClock.millis();
        }
        return null;
    }

    // Rate-limit faults because each suppressed fix can reach this path.
    private void logCoarseningFault(String reason) {
        synchronized (this) {
            long nowMs = mClock.millis();
            if (nowMs < mNextFaultLogRealtimeMs) {
                return;
            }
            mNextFaultLogRealtimeMs = nowMs + FAULT_LOG_INTERVAL_MS;
        }
        Log.w(TAG, "coarse location suppressed: " + reason);
    }

    // Returns the average edge length in meters of an S2 cell at the given
    // level. This is computed as if the S2 cell were a square. We do not need
    // an exact value, only a rough approximation.
    @VisibleForTesting
    protected float getS2CellApproximateEdge(int level) {
        if (level < 0) {
            level = 0;
        } else if (level >= S2_CELL_AVG_EDGE_PER_LEVEL.length) {
            level = S2_CELL_AVG_EDGE_PER_LEVEL.length - 1;
        }
        return S2_CELL_AVG_EDGE_PER_LEVEL[level] * 1000;
    }

    // Derive the cell locally; accept only the provider-supplied level.
    @VisibleForTesting
    protected double[] snapToCenterOfS2Cell(double latDegrees, double lngDegrees, int level) {
        double[] center = new double[] {0.0, 0.0};
        snapToCenterOfS2Cell(latDegrees, lngDegrees, level, center);
        return center;
    }

    private void snapToCenterOfS2Cell(double latDegrees, double lngDegrees, int level,
            double[] center) {
        long leafCell = S2CellIdUtils.fromLatLngDegrees(latDegrees, lngDegrees);
        long coarsenedCell = S2CellIdUtils.getParent(leafCell, level);
        S2CellIdUtils.toLatLngDegrees(coarsenedCell, center);
    }

    /**
     * Update the random offsets over time.
     *
     * If the random offset was reset for every location fix then an application could more easily
     * average location results over time, especially when the location is near a grid boundary. On
     * the other hand if the random offset is constant then if an application finds a way to reverse
     * engineer the offset they would be able to detect location at grid boundaries very accurately.
     * So we choose a random offset and then very slowly move it, to make both approaches very hard.
     * The random offset does not need to be large, because snap-to-grid is the primary obfuscation
     * mechanism. It just needs to be large enough to stop information leakage as we cross grid
     * boundaries.
     */
    private synchronized void updateOffsets() {
        long now = mClock.millis();
        if (now < mNextUpdateRealtimeMs) {
            return;
        }

        mLatitudeOffsetM = (OLD_WEIGHT * mLatitudeOffsetM) + (NEW_WEIGHT * nextRandomOffset());
        mLongitudeOffsetM = (OLD_WEIGHT * mLongitudeOffsetM) + (NEW_WEIGHT * nextRandomOffset());
        mNextUpdateRealtimeMs = now + OFFSET_UPDATE_INTERVAL_MS;
    }

    private double nextRandomOffset() {
        return mRandom.nextGaussian() * (mAccuracyM / 4.0);
    }

    private static double wrapLatitude(double lat) {
        if (lat > MAX_LATITUDE) {
            lat = MAX_LATITUDE;
        }
        if (lat < -MAX_LATITUDE) {
            lat = -MAX_LATITUDE;
        }
        return lat;
    }

    private static double wrapLongitude(double lon) {
        lon %= 360.0;  // wraps into range (-360.0, +360.0)
        if (lon >= 180.0) {
            lon -= 360.0;
        }
        if (lon < -180.0) {
            lon += 360.0;
        }
        return lon;
    }

    private static double metersToDegreesLatitude(double distance) {
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR;
    }

    // requires latitude since longitudinal distances change with distance from equator.
    private static double metersToDegreesLongitude(double distance, double lat) {
        // Needed to convert from longitude distance to longitude degree.
        // X meters near the poles is more degrees than at the equator.
        double cosLat = Math.cos(Math.toRadians(lat));
        // If we are right on top of the pole, the degree is always 0.
        // We return a very small value instead to avoid divide by zero errors
        // later on.
        if (cosLat == 0.0) {
            return 0.0001;
        }
        return distance / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR / cosLat;
    }
}
