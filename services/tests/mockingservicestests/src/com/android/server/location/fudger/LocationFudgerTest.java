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

import static androidx.test.ext.truth.location.LocationSubject.assertThat;

import static com.android.server.location.LocationUtils.createLocation;
import static com.android.server.location.LocationUtils.createLocationResult;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.location.Location;
import android.location.LocationResult;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.location.geometry.S2CellIdUtils;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider.PopulationDensityUnavailableException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Random;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocationFudgerTest {

    private static final String TAG = "LocationFudgerTest";

    private static final double APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR = 111_000;
    private static final float ACCURACY_M = 2000;
    private static final float MAX_COARSE_FUDGE_DISTANCE_M =
            (float) Math.sqrt(2 * ACCURACY_M * ACCURACY_M) + ACCURACY_M / 4f;

    private static final int TEST_COARSENING_LEVEL = 12;

    private Random mRandom;

    private LocationFudger mFudger;

    @Before
    public void setUp() {
        long seed = System.currentTimeMillis();
        Log.i(TAG, "location random seed: " + seed);

        mRandom = new Random(seed);
        mFudger = new LocationFudger(
                ACCURACY_M,
                Clock.fixed(Instant.ofEpochMilli(0), ZoneId.systemDefault()),
                mRandom);
    }

    @Test
    public void testCoarsen() {
        // Coarsening must drop every position-sensitive field while carrying only the
        // non-sensitive fields that downstream code and LocationResult.validate() rely on. Every
        // sensitive field is set on the fine input, so a regression that copies any of them is
        // caught here; fields Location gains in the future are covered by the allowlist
        // construction itself rather than by this test.
        for (int i = 0; i < 100; i++) {
            Location fine = createLocation("test", mRandom);
            fine.setElapsedRealtimeUncertaintyNanos(1);
            fine.setBearing(1);
            fine.setBearingAccuracyDegrees(1);
            fine.setSpeed(1);
            fine.setSpeedAccuracyMetersPerSecond(1);
            fine.setAltitude(1);
            fine.setVerticalAccuracyMeters(1);
            fine.setMslAltitudeMeters(1);
            fine.setMslAltitudeAccuracyMeters(1);
            fine.setMock(true);
            Bundle extras = new Bundle();
            extras.putString("secret", "leak");
            fine.setExtras(extras);

            Location coarse = mFudger.createCoarse(fine);

            assertThat(coarse).isNotNull();
            assertThat(coarse).isNotSameInstanceAs(fine);

            // No position-sensitive field may leak to coarse-only apps.
            assertThat(coarse.hasBearing()).isFalse();
            assertThat(coarse.hasBearingAccuracy()).isFalse();
            assertThat(coarse.hasSpeed()).isFalse();
            assertThat(coarse.hasSpeedAccuracy()).isFalse();
            assertThat(coarse.hasAltitude()).isFalse();
            assertThat(coarse.hasVerticalAccuracy()).isFalse();
            assertThat(coarse.hasMslAltitude()).isFalse();
            assertThat(coarse.hasMslAltitudeAccuracy()).isFalse();
            assertThat(coarse.getExtras()).isNull();

            // Non-sensitive fields required for correctness must survive.
            assertThat(coarse.getProvider()).isEqualTo(fine.getProvider());
            assertThat(coarse.getTime()).isEqualTo(fine.getTime());
            assertThat(coarse.getElapsedRealtimeNanos()).isEqualTo(fine.getElapsedRealtimeNanos());
            assertThat(coarse.hasElapsedRealtimeUncertaintyNanos()).isTrue();
            assertThat(coarse.getElapsedRealtimeUncertaintyNanos())
                    .isEqualTo(fine.getElapsedRealtimeUncertaintyNanos());
            assertThat(coarse.isMock()).isTrue();

            assertThat(coarse.getAccuracy()).isEqualTo(ACCURACY_M);
            assertThat(coarse.distanceTo(fine)).isGreaterThan(1F);
            assertThat(coarse).isNearby(fine, MAX_COARSE_FUDGE_DISTANCE_M);
        }
    }

    @Test
    public void testCoarsen_Consistent() {
        // test that coarsening the same location will always return the same coarse location
        // (and thus that averaging to eliminate random noise won't work)
        for (int i = 0; i < 100; i++) {
            Location fine = createLocation("test", mRandom);
            Location coarse = mFudger.createCoarse(fine);
            assertThat(mFudger.createCoarse(new Location(fine))).isEqualTo(coarse);
            assertThat(mFudger.createCoarse(new Location(fine))).isEqualTo(coarse);
        }
    }

    @Test
    public void testCoarsen_AvgMany() {
        // test that a set of locations normally distributed around the user's real location still
        // cannot be easily average to reveal the user's real location

        int passed = 0;
        int iterations = 100;
        for (int j = 0; j < iterations; j++) {
            Location fine = createLocation("test", mRandom);

            // generate a point cloud around a single location
            ArrayList<Location> finePoints = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                finePoints.add(step(fine, mRandom.nextGaussian() * ACCURACY_M));
            }

            // generate the coarsened version of that point cloud
            ArrayList<Location> coarsePoints = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                coarsePoints.add(mFudger.createCoarse(finePoints.get(i)));
            }

            double avgFineLatitude = finePoints.stream().mapToDouble(
                    Location::getLatitude).average()
                    .orElseThrow(IllegalStateException::new);
            double avgFineLongitude = finePoints.stream().mapToDouble(
                    Location::getLongitude).average()
                    .orElseThrow(IllegalStateException::new);
            Location fineAvg = createLocation("test", avgFineLatitude, avgFineLongitude, 0);

            double avgCoarseLatitude = coarsePoints.stream().mapToDouble(
                    Location::getLatitude).average()
                    .orElseThrow(IllegalStateException::new);
            double avgCoarseLongitude = coarsePoints.stream().mapToDouble(
                    Location::getLongitude).average()
                    .orElseThrow(IllegalStateException::new);
            Location coarseAvg = createLocation("test", avgCoarseLatitude, avgCoarseLongitude, 0);

            if (coarseAvg.distanceTo(fine) > fineAvg.distanceTo(fine)) {
                passed++;
            }
        }

        // very generally speaking, the closer the initial fine point is to a grid point, the more
        // accurate the coarsened average will be. we use 70% as a lower bound by -very- roughly
        // taking the area within a grid where we expect a reasonable percentage of points generated
        // by step() to fall in another grid square. this likely doesn't have much mathematical
        // validity, but it serves as a validity test as least.
        assertThat(passed / (double) iterations).isGreaterThan(.70);
    }

    // step in a random direction by distance - assume cartesian
    private Location step(Location input, double distanceM) {
        double radians = mRandom.nextDouble() * 2 * Math.PI;
        double deltaXM = Math.cos(radians) * distanceM;
        double deltaYM = Math.sin(radians) * distanceM;
        return createLocation("test",
                input.getLatitude() + deltaXM / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR,
                input.getLongitude() + deltaYM / APPROXIMATE_METERS_PER_DEGREE_AT_EQUATOR,
                0);
    }

    @Test
    public void testCoarsen_nearAntimeridianAndPoles_normalizesAndCoarsens() throws Exception {
        long cell = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(0.0, 0.0), TEST_COARSENING_LEVEL);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        ArrayList<double[]> queriedPoints = new ArrayList<>();
        doAnswer(invocation -> {
            queriedPoints.add(
                    new double[] {invocation.getArgument(0), invocation.getArgument(1)});
            return cell;
        }).when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        mFudger.setPopulationDensityProvider(provider);

        // Both anti-meridian sides and both poles, so whichever sign the random offsets have,
        // some raw offset sum falls outside the valid coordinate ranges.
        assertThat(mFudger.createCoarse(createLocation("test", 89.9999, 179.9999, 1f)))
                .isNotNull();
        assertThat(mFudger.createCoarse(createLocation("test", -89.9999, -179.9999, 1f)))
                .isNotNull();

        for (double[] queriedPoint : queriedPoints) {
            assertThat(queriedPoint[0]).isAtLeast(-90.0);
            assertThat(queriedPoint[0]).isAtMost(90.0);
            assertThat(queriedPoint[1]).isAtLeast(-180.0);
            assertThat(queriedPoint[1]).isAtMost(180.0);
        }
    }

    @Test
    public void testDensityBasedCoarsening_providerCellPositionIgnored() throws Exception {
        int level = 12;
        long s2CellId = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(40.758896, -73.985130), level);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        double[] queried = new double[2];
        doAnswer(invocation -> {
            queried[0] = invocation.getArgument(0);
            queried[1] = invocation.getArgument(1);
            return s2CellId;
        }).when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());

        mFudger.setPopulationDensityProvider(provider);

        Location fine = createLocation("test", 1.0, 1.0, /* accuracy= */ 1f);
        Location coarse = mFudger.createCoarse(fine);

        verify(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        double[] expectedCenter = mFudger.snapToCenterOfS2Cell(queried[0], queried[1], level);
        assertThat(coarse).isNotNull();
        assertThat(coarse.getLatitude()).isEqualTo(expectedCenter[0]);
        assertThat(coarse.getLongitude()).isEqualTo(expectedCenter[1]);
        assertThat(coarse.getAccuracy()).isEqualTo(mFudger.getS2CellApproximateEdge(level));
    }

    @Test
    public void testDensityBasedCoarsening_queryQuantizedToFinestAcceptedLevel()
            throws Exception {
        long cell = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(0.0, 0.0), TEST_COARSENING_LEVEL);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        double[] queried = new double[2];
        doAnswer(invocation -> {
            queried[0] = invocation.getArgument(0);
            queried[1] = invocation.getArgument(1);
            return cell;
        }).when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        mFudger.setPopulationDensityProvider(provider);

        Location coarse = mFudger.createCoarse(createLocation("test", 40.758896, -73.985130, 1f));

        assertThat(coarse).isNotNull();
        double[] requantized =
                mFudger.snapToCenterOfS2Cell(queried[0], queried[1], TEST_COARSENING_LEVEL);
        assertThat(requantized[0]).isEqualTo(queried[0]);
        assertThat(requantized[1]).isEqualTo(queried[1]);
    }

    @Test
    public void testDensityBasedCoarsening_malformedCell_suppressesFix() throws Exception {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doReturn(0L).when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());

        mFudger.setPopulationDensityProvider(provider);

        Location coarse = mFudger.createCoarse(createLocation("test", mRandom));

        assertThat(coarse).isNull();
    }

    @Test
    public void testDensityBasedCoarsening_tooFineLevel_suppressesFix() throws Exception {
        long s2CellId = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(40.758896, -73.985130), 13);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doReturn(s2CellId).when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());

        mFudger.setPopulationDensityProvider(provider);

        Location coarse = mFudger.createCoarse(createLocation("test", mRandom));

        assertThat(coarse).isNull();
    }

    @Test
    public void testDensityBasedCoarsening_finestSupportedLevel_coarsens() throws Exception {
        int level = 12;
        long s2CellId = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(40.758896, -73.985130), level);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doReturn(s2CellId).when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());

        mFudger.setPopulationDensityProvider(provider);

        Location fine = createLocation("test", 1.0, 1.0, /* accuracy= */ 1f);
        Location coarse = mFudger.createCoarse(fine);

        assertThat(coarse).isNotNull();
        assertThat(coarse.getAccuracy()).isEqualTo(mFudger.getS2CellApproximateEdge(level));
    }

    @Test
    public void testDensityBasedCoarsening_providerFault_suppressesFix() throws Exception {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doThrow(new PopulationDensityUnavailableException("test"))
                .when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());

        mFudger.setPopulationDensityProvider(provider);

        Location coarse = mFudger.createCoarse(createLocation("test", mRandom));

        assertThat(coarse).isNull();
    }

    @Test
    public void testDensityBasedCoarsening_providerFault_notRetried() throws Exception {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doThrow(new PopulationDensityUnavailableException("test"))
                .when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        mFudger.setPopulationDensityProvider(provider);
        Location fine = createLocation("test", mRandom);

        assertThat(mFudger.createCoarse(fine)).isNull();
        assertThat(mFudger.createCoarse(fine)).isNull();
        assertThat(mFudger.createCoarse(fine)).isNull();

        verify(provider, times(1)).getCoarsenedS2CellId(anyDouble(), anyDouble());
    }

    @Test
    public void testDensityBasedCoarsening_faultThenRecovery_nextFixCoarsens() throws Exception {
        long cell = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(0.0, 0.0), TEST_COARSENING_LEVEL);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doThrow(new PopulationDensityUnavailableException("test"))
                .doReturn(cell)
                .when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        mFudger.setPopulationDensityProvider(provider);

        assertThat(mFudger.createCoarse(createLocation("test", mRandom))).isNull();
        assertThat(mFudger.createCoarse(createLocation("test", mRandom))).isNotNull();
    }

    @Test
    public void testDensityBasedCoarsening_failureMemoryCoversOnlyLatestInput() throws Exception {
        long cell = S2CellIdUtils.getParent(
                S2CellIdUtils.fromLatLngDegrees(0.0, 0.0), TEST_COARSENING_LEVEL);
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doThrow(new PopulationDensityUnavailableException("test"))
                .doReturn(cell)
                .when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        mFudger.setPopulationDensityProvider(provider);
        Location failedFine = createLocation("test", mRandom);

        assertThat(mFudger.createCoarse(failedFine)).isNull();
        assertThat(mFudger.createCoarse(createLocation("test", mRandom))).isNotNull();
        assertThat(mFudger.createCoarse(failedFine)).isNotNull();

        verify(provider, times(3)).getCoarsenedS2CellId(anyDouble(), anyDouble());
    }

    @Test
    public void testCoarsenLocationResult_failedResult_notRetried() throws Exception {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        doThrow(new PopulationDensityUnavailableException("test"))
                .when(provider).getCoarsenedS2CellId(anyDouble(), anyDouble());
        mFudger.setPopulationDensityProvider(provider);
        LocationResult fine = createLocationResult("test", mRandom, 3);

        assertThat(mFudger.createCoarse(fine)).isNull();
        assertThat(mFudger.createCoarse(fine)).isNull();
        assertThat(mFudger.createCoarse(fine)).isNull();

        verify(provider, times(1)).getCoarsenedS2CellId(anyDouble(), anyDouble());
    }

    @Test
    public void getS2CellApproximateEdge_returnsCorrectRadius() {
        int level = 10;

        float radius = mFudger.getS2CellApproximateEdge(level);

        assertThat(radius).isEqualTo(9000);  // in meters
    }

    @Test
    public void getS2CellApproximateEdge_doesNotThrow() {
        int level = -1;

        mFudger.getS2CellApproximateEdge(level);

        // No exception thrown.
    }

    @Test
    public void getS2CellApproximateEdge_doesNotThrow2() {
        int level = 14;

        mFudger.getS2CellApproximateEdge(level);

        // No exception thrown.
    }
}
