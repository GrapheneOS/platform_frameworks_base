package com.android.server.location.provider.proxy;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.location.provider.IPopulationDensityProvider;
import android.location.provider.IS2CellIdsCallback;
import android.location.provider.IS2LevelCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider.PopulationDensityUnavailableException;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ProxyPopulationDensityProviderTest {

    private static final long BLOCKED_QUERY_TIMEOUT_SECONDS = 10;
    private static final long COORDINATION_QUERY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long EXPLICIT_QUERY_TIMEOUT_MILLIS = 100;

    private static final long ARBITRARY_CELL_ID = 0x1234_5678_9abc_def0L;
    private static final long OTHER_CELL_ID = 0x0fed_cba9_8765_4321L;

    @Test
    public void getCoarsenedS2CellId_notBound_throws() {
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();

        assertThrows(
                PopulationDensityUnavailableException.class,
                () -> proxy.getCoarsenedS2CellId(1.0, 2.0));
    }

    @Test
    public void getCoarsenedS2CellId_bound_returnsFirstCellAndForwardsQuery() throws Exception {
        double[] queriedCoordinates = new double[2];
        int[] queriedAdditionalCells = new int[1];
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback)
                            throws RemoteException {
                        queriedCoordinates[0] = latitudeDegrees;
                        queriedCoordinates[1] = longitudeDegrees;
                        queriedAdditionalCells[0] = numAdditionalCells;
                        callback.onResult(new long[] {ARBITRARY_CELL_ID, OTHER_CELL_ID});
                    }
                };
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
        proxy.onBind(provider.asBinder(), null);

        long cell = proxy.getCoarsenedS2CellId(12.5, -34.25);

        assertThat(cell).isEqualTo(ARBITRARY_CELL_ID);
        assertThat(queriedCoordinates[0]).isEqualTo(12.5);
        assertThat(queriedCoordinates[1]).isEqualTo(-34.25);
        assertThat(queriedAdditionalCells[0]).isEqualTo(0);
    }

    @Test
    public void getCoarsenedS2CellId_providerReportsError_throws() {
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback)
                            throws RemoteException {
                        callback.onError();
                    }
                };
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
        proxy.onBind(provider.asBinder(), null);

        PopulationDensityUnavailableException exception =
                assertThrows(
                        PopulationDensityUnavailableException.class,
                        () -> proxy.getCoarsenedS2CellId(1.0, 2.0));

        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void getCoarsenedS2CellId_providerReturnsNoCells_throws() {
        for (long[] returnedCells : new long[][] {null, new long[0]}) {
            IPopulationDensityProvider.Stub provider =
                    new CellProvider() {
                        @Override
                        public void getCoarsenedS2Cells(
                                double latitudeDegrees,
                                double longitudeDegrees,
                                int numAdditionalCells,
                                IS2CellIdsCallback callback)
                                throws RemoteException {
                            callback.onResult(returnedCells);
                        }
                    };
            ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
            proxy.onBind(provider.asBinder(), null);

            PopulationDensityUnavailableException exception =
                    assertThrows(
                            PopulationDensityUnavailableException.class,
                            () -> proxy.getCoarsenedS2CellId(1.0, 2.0));

            assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    public void getCoarsenedS2CellId_runtimeException_wrapsAsUnavailable() {
        RuntimeException providerFailure = new RuntimeException("provider failure");
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback) {
                        throw providerFailure;
                    }
                };
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
        proxy.onBind(provider.asBinder(), null);

        PopulationDensityUnavailableException exception =
                assertThrows(
                        PopulationDensityUnavailableException.class,
                        () -> proxy.getCoarsenedS2CellId(1.0, 2.0));

        assertThat(exception).hasCauseThat().isSameInstanceAs(providerFailure);
    }

    @Test
    public void getCoarsenedS2CellId_remoteException_wrapsAsUnavailable() {
        RemoteException providerFailure = new RemoteException("provider failure");
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback)
                            throws RemoteException {
                        throw providerFailure;
                    }
                };
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
        proxy.onBind(provider.asBinder(), null);

        PopulationDensityUnavailableException exception =
                assertThrows(
                        PopulationDensityUnavailableException.class,
                        () -> proxy.getCoarsenedS2CellId(1.0, 2.0));

        assertThat(exception).hasCauseThat().isSameInstanceAs(providerFailure);
    }

    @Test
    public void getCoarsenedS2CellId_providerTimesOut_nextQueryRetries() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback)
                            throws RemoteException {
                        if (providerCalls.incrementAndGet() > 1) {
                            callback.onResult(new long[] {ARBITRARY_CELL_ID});
                        }
                    }
                };
        ProxyPopulationDensityProvider proxy =
                new ProxyPopulationDensityProvider(EXPLICIT_QUERY_TIMEOUT_MILLIS);
        proxy.onBind(provider.asBinder(), null);

        assertThrows(
                PopulationDensityUnavailableException.class,
                () -> proxy.getCoarsenedS2CellId(1.0, 2.0));

        assertThat(proxy.getCoarsenedS2CellId(1.0, 2.0)).isEqualTo(ARBITRARY_CELL_ID);
        assertThat(providerCalls.get()).isEqualTo(2);
    }

    @Test
    public void getCoarsenedS2CellId_sequentialSamePoint_doesNotRetainAnswer() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback)
                            throws RemoteException {
                        long cell =
                                providerCalls.incrementAndGet() == 1
                                        ? ARBITRARY_CELL_ID
                                        : OTHER_CELL_ID;
                        callback.onResult(new long[] {cell});
                    }
                };
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
        proxy.onBind(provider.asBinder(), null);

        assertThat(proxy.getCoarsenedS2CellId(12.5, -34.25)).isEqualTo(ARBITRARY_CELL_ID);
        assertThat(proxy.getCoarsenedS2CellId(12.5, -34.25)).isEqualTo(OTHER_CELL_ID);
        assertThat(providerCalls.get()).isEqualTo(2);
    }

    @Test
    public void getCoarsenedS2CellId_queryCompletingAfterRebind_failsClosed() throws Exception {
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        AtomicReference<IS2CellIdsCallback> firstCallback = new AtomicReference<>();
        IPopulationDensityProvider.Stub firstProvider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback) {
                        firstCallback.set(callback);
                        firstQueryStarted.countDown();
                    }
                };
        ProxyPopulationDensityProvider proxy =
                new ProxyPopulationDensityProvider(COORDINATION_QUERY_TIMEOUT_MILLIS);
        proxy.onBind(firstProvider.asBinder(), null);
        FutureTask<Long> firstResult =
                new FutureTask<>(() -> proxy.getCoarsenedS2CellId(12.5, -34.25));
        Thread firstThread = startTask(firstResult, "first-binding");

        try {
            assertThat(firstQueryStarted.await(BLOCKED_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            proxy.onBind(constantProvider(OTHER_CELL_ID).asBinder(), null);
            assertThat(proxy.getCoarsenedS2CellId(12.5, -34.25)).isEqualTo(OTHER_CELL_ID);

            firstCallback.get().onResult(new long[] {ARBITRARY_CELL_ID});

            ExecutionException exception =
                    assertThrows(
                            ExecutionException.class,
                            () -> firstResult.get(BLOCKED_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertThat(exception)
                    .hasCauseThat()
                    .isInstanceOf(PopulationDensityUnavailableException.class);
        } finally {
            cancelAndJoinTask(firstResult, firstThread);
        }
    }

    @Test
    public void getCoarsenedS2CellId_interruptedQuery_canRetry() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        AtomicReference<IS2CellIdsCallback> firstCallback = new AtomicReference<>();
        IPopulationDensityProvider.Stub provider =
                new CellProvider() {
                    @Override
                    public void getCoarsenedS2Cells(
                            double latitudeDegrees,
                            double longitudeDegrees,
                            int numAdditionalCells,
                            IS2CellIdsCallback callback)
                            throws RemoteException {
                        if (providerCalls.incrementAndGet() == 1) {
                            firstCallback.set(callback);
                            firstQueryStarted.countDown();
                        } else {
                            callback.onResult(new long[] {ARBITRARY_CELL_ID});
                        }
                    }
                };
        ProxyPopulationDensityProvider proxy =
                new ProxyPopulationDensityProvider(COORDINATION_QUERY_TIMEOUT_MILLIS);
        proxy.onBind(provider.asBinder(), null);
        FutureTask<PopulationDensityUnavailableException> interruptedResult =
                new FutureTask<>(
                        () ->
                                assertThrows(
                                        PopulationDensityUnavailableException.class,
                                        () -> proxy.getCoarsenedS2CellId(1.0, 2.0)));
        Thread queryThread = startTask(interruptedResult, "interrupted-query");

        try {
            assertThat(firstQueryStarted.await(BLOCKED_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isTrue();
            queryThread.interrupt();
            PopulationDensityUnavailableException exception =
                    interruptedResult.get(BLOCKED_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(exception).hasMessageThat().isEqualTo("query interrupted");

            firstCallback.get().onResult(new long[] {OTHER_CELL_ID});
            assertThat(proxy.getCoarsenedS2CellId(1.0, 2.0)).isEqualTo(ARBITRARY_CELL_ID);
            assertThat(providerCalls.get()).isEqualTo(2);
        } finally {
            cancelAndJoinTask(interruptedResult, queryThread);
        }
    }

    @Test
    public void getBindingGeneration_advancesOnBindAndUnbind() {
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider();
        long initialGeneration = proxy.getBindingGeneration();

        proxy.onBind(constantProvider(ARBITRARY_CELL_ID).asBinder(), null);
        long boundGeneration = proxy.getBindingGeneration();
        proxy.onUnbind();

        assertThat(boundGeneration).isGreaterThan(initialGeneration);
        assertThat(proxy.getBindingGeneration()).isGreaterThan(boundGeneration);
    }

    private abstract static class CellProvider extends IPopulationDensityProvider.Stub {
        @Override
        public void getDefaultCoarseningLevel(IS2LevelCallback callback) throws RemoteException {
            callback.onError();
        }
    }

    private static IPopulationDensityProvider.Stub constantProvider(long cellId) {
        return new CellProvider() {
            @Override
            public void getCoarsenedS2Cells(
                    double latitudeDegrees,
                    double longitudeDegrees,
                    int numAdditionalCells,
                    IS2CellIdsCallback callback)
                    throws RemoteException {
                callback.onResult(new long[] {cellId});
            }
        };
    }

    private static Thread startTask(FutureTask<?> task, String threadName) {
        Thread thread = new Thread(task, threadName);
        thread.start();
        return thread;
    }

    private static void cancelAndJoinTask(FutureTask<?> task, Thread thread)
            throws InterruptedException {
        task.cancel(true);
        thread.join(TimeUnit.SECONDS.toMillis(BLOCKED_QUERY_TIMEOUT_SECONDS));
        assertThat(thread.isAlive()).isFalse();
    }
}
