/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.provider.proxy;

import static android.location.provider.PopulationDensityProviderBase.ACTION_POPULATION_DENSITY_PROVIDER;

import android.annotation.Nullable;
import android.content.Context;
import android.location.provider.IPopulationDensityProvider;
import android.location.provider.IS2CellIdsCallback;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.servicewatcher.CurrentUserServiceSupplier;
import com.android.server.servicewatcher.CurrentUserServiceSupplier.BoundServiceInfo;
import com.android.server.servicewatcher.ServiceWatcher;
import com.android.server.servicewatcher.ServiceWatcher.ServiceListener;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Proxy for {@link IPopulationDensityProvider} implementations.
 *
 * <p>Each query waits at most {@link #QUERY_TIMEOUT_MILLIS} for the asynchronous provider callback.
 */
public class ProxyPopulationDensityProvider implements ServiceListener<BoundServiceInfo> {

    private static final String TAG = "ProxyPopulationDensityProvider";
    private static final long QUERY_TIMEOUT_MILLIS = 100;

    @Nullable private final ServiceWatcher mServiceWatcher;
    private final long mQueryTimeoutMillis;

    private final Object mBindingLock = new Object();

    @GuardedBy("mBindingLock")
    private long mNextBindingGeneration;

    private volatile BindingToken mBindingToken =
            new BindingToken(/* provider= */ null, /* generation= */ 0);

    /** Creates, registers, and returns the proxy. */
    public static ProxyPopulationDensityProvider createAndRegister(Context context) {
        ProxyPopulationDensityProvider proxy = new ProxyPopulationDensityProvider(context);
        proxy.register();
        return proxy;
    }

    private ProxyPopulationDensityProvider(Context context) {
        mQueryTimeoutMillis = QUERY_TIMEOUT_MILLIS;
        mServiceWatcher =
                ServiceWatcher.create(
                        context,
                        "PopulationDensityProxy",
                        CurrentUserServiceSupplier.createFromConfig(
                                context,
                                ACTION_POPULATION_DENSITY_PROVIDER,
                                com.android.internal.R.bool
                                        .config_enablePopulationDensityProviderOverlay,
                                com.android.internal.R.string
                                        .config_populationDensityProviderPackageName),
                        this);
    }

    @VisibleForTesting
    ProxyPopulationDensityProvider() {
        this(QUERY_TIMEOUT_MILLIS);
    }

    @VisibleForTesting
    ProxyPopulationDensityProvider(long queryTimeoutMillis) {
        if (queryTimeoutMillis <= 0) {
            throw new IllegalArgumentException("query timeout must be positive");
        }
        mQueryTimeoutMillis = queryTimeoutMillis;
        mServiceWatcher = null;
    }

    private void register() {
        ServiceWatcher serviceWatcher =
                Objects.requireNonNull(mServiceWatcher, "no service watcher on test instance");
        if (!serviceWatcher.checkServiceResolves()) {
            Log.e(
                    TAG,
                    "no population density provider currently resolves; coarse location is"
                            + " suppressed until one becomes available");
        }
        serviceWatcher.register();
    }

    /** Returns whether a population density provider service currently resolves. */
    public boolean isServiceResolved() {
        return Objects.requireNonNull(mServiceWatcher, "no service watcher on test instance")
                .checkServiceResolves();
    }

    /** Returns the generation of the current bound or unbound provider state. */
    public long getBindingGeneration() {
        return mBindingToken.mGeneration;
    }

    @Override
    public void onBind(IBinder binder, BoundServiceInfo boundServiceInfo) {
        IPopulationDensityProvider provider = IPopulationDensityProvider.Stub.asInterface(binder);
        synchronized (mBindingLock) {
            mBindingToken = new BindingToken(provider, ++mNextBindingGeneration);
        }
    }

    @Override
    public void onUnbind() {
        synchronized (mBindingLock) {
            mBindingToken = new BindingToken(/* provider= */ null, ++mNextBindingGeneration);
        }
    }

    /**
     * Returns the coarsening cell for the given normalized S2 cell center.
     *
     * @throws PopulationDensityUnavailableException if the provider is unavailable, the query
     *     fails, or the query times out.
     */
    public long getCoarsenedS2CellId(double latitudeDegrees, double longitudeDegrees)
            throws PopulationDensityUnavailableException {
        BindingToken bindingToken = mBindingToken;
        IPopulationDensityProvider provider = bindingToken.mProvider;
        if (provider == null) {
            throw new PopulationDensityUnavailableException("provider not bound");
        }

        CompletableFuture<Long> query = new CompletableFuture<>();
        requestCoarsenedS2Cells(provider, latitudeDegrees, longitudeDegrees, query);

        try {
            long s2CellId = query.get(mQueryTimeoutMillis, TimeUnit.MILLISECONDS);
            if (mBindingToken != bindingToken) {
                throw new PopulationDensityUnavailableException("provider binding changed");
            }
            return s2CellId;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            query.cancel(false);
            throw new PopulationDensityUnavailableException("query interrupted", exception);
        } catch (ExecutionException exception) {
            throw new PopulationDensityUnavailableException("query failed", exception.getCause());
        } catch (TimeoutException exception) {
            query.cancel(false);
            throw new PopulationDensityUnavailableException("query timed out", exception);
        }
    }

    private static void requestCoarsenedS2Cells(
            IPopulationDensityProvider provider,
            double latitudeDegrees,
            double longitudeDegrees,
            CompletableFuture<Long> query) {
        IS2CellIdsCallback callback =
                new IS2CellIdsCallback.Stub() {
                    @Override
                    public void onResult(long[] s2CellIds) {
                        if (s2CellIds == null || s2CellIds.length == 0) {
                            query.completeExceptionally(
                                    new IllegalStateException(
                                            "population density provider returned no S2 cells"));
                            return;
                        }
                        query.complete(s2CellIds[0]);
                    }

                    @Override
                    public void onError() {
                        query.completeExceptionally(
                                new IllegalStateException(
                                        "population density provider reported an error"));
                    }
                };

        try {
            provider.getCoarsenedS2Cells(
                    latitudeDegrees, longitudeDegrees, /* numAdditionalCells= */ 0, callback);
        } catch (RemoteException | RuntimeException exception) {
            query.completeExceptionally(exception);
        }
    }

    /** Indicates that the population density provider could not produce a coarsening cell. */
    public static final class PopulationDensityUnavailableException extends Exception {
        public PopulationDensityUnavailableException(String message) {
            super(message);
        }

        public PopulationDensityUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Identifies one bound or unbound provider state. */
    private static final class BindingToken {

        private final @Nullable IPopulationDensityProvider mProvider;
        private final long mGeneration;

        private BindingToken(@Nullable IPopulationDensityProvider provider, long generation) {
            mProvider = provider;
            mGeneration = generation;
        }
    }
}
