/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.nsd;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.connectivity.ConnectivityCompatChanges.ENABLE_PLATFORM_MDNS_BACKEND;
import static android.net.connectivity.ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.app.compat.CompatChanges;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityThread;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The Network Service Discovery Manager class provides the API to discover services
 * on a network. As an example, if device A and device B are connected over a Wi-Fi
 * network, a game registered on device A can be discovered by a game on device
 * B. Another example use case is an application discovering printers on the network.
 *
 * <p> The API currently supports DNS based service discovery and discovery is currently
 * limited to a local network over Multicast DNS. DNS service discovery is described at
 * http://files.dns-sd.org/draft-cheshire-dnsext-dns-sd.txt
 *
 * <p> The API is asynchronous, and responses to requests from an application are on listener
 * callbacks on a separate internal thread.
 *
 * <p> There are three main operations the API supports - registration, discovery and resolution.
 * <pre>
 *                          Application start
 *                                 |
 *                                 |
 *                                 |                  onServiceRegistered()
 *                     Register any local services  /
 *                      to be advertised with       \
 *                       registerService()            onRegistrationFailed()
 *                                 |
 *                                 |
 *                          discoverServices()
 *                                 |
 *                      Maintain a list to track
 *                        discovered services
 *                                 |
 *                                 |--------->
 *                                 |          |
 *                                 |      onServiceFound()
 *                                 |          |
 *                                 |     add service to list
 *                                 |          |
 *                                 |<----------
 *                                 |
 *                                 |--------->
 *                                 |          |
 *                                 |      onServiceLost()
 *                                 |          |
 *                                 |   remove service from list
 *                                 |          |
 *                                 |<----------
 *                                 |
 *                                 |
 *                                 | Connect to a service
 *                                 | from list ?
 *                                 |
 *                          resolveService()
 *                                 |
 *                         onServiceResolved()
 *                                 |
 *                     Establish connection to service
 *                     with the host and port information
 *
 * </pre>
 * An application that needs to advertise itself over a network for other applications to
 * discover it can do so with a call to {@link #registerService}. If Example is a http based
 * application that can provide HTML data to peer services, it can register a name "Example"
 * with service type "_http._tcp". A successful registration is notified with a callback to
 * {@link RegistrationListener#onServiceRegistered} and a failure to register is notified
 * over {@link RegistrationListener#onRegistrationFailed}
 *
 * <p> A peer application looking for http services can initiate a discovery for "_http._tcp"
 * with a call to {@link #discoverServices}. A service found is notified with a callback
 * to {@link DiscoveryListener#onServiceFound} and a service lost is notified on
 * {@link DiscoveryListener#onServiceLost}.
 *
 * <p> Once the peer application discovers the "Example" http service, and either needs to read the
 * attributes of the service or wants to receive data from the "Example" application, it can
 * initiate a resolve with {@link #resolveService} to resolve the attributes, host, and port
 * details. A successful resolve is notified on {@link ResolveListener#onServiceResolved} and a
 * failure is notified on {@link ResolveListener#onResolveFailed}.
 *
 * Applications can reserve for a service type at
 * http://www.iana.org/form/ports-service. Existing services can be found at
 * http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xml
 *
 * @see NsdServiceInfo
 */
@SystemService(Context.NSD_SERVICE)
public final class NsdManager {
    private static final String TAG = NsdManager.class.getSimpleName();
    private static final boolean DBG = false;

    // TODO : remove this class when udc-mainline-prod is abandoned and android.net.flags.Flags is
    // available here
    /** @hide */
    public static class Flags {
        static final String REGISTER_NSD_OFFLOAD_ENGINE_API =
                "com.android.net.flags.register_nsd_offload_engine_api";
    }

    /**
     * Broadcast intent action to indicate whether network service discovery is
     * enabled or disabled. An extra {@link #EXTRA_NSD_STATE} provides the state
     * information as int.
     *
     * @see #EXTRA_NSD_STATE
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NSD_STATE_CHANGED = "android.net.nsd.STATE_CHANGED";

    /**
     * The lookup key for an int that indicates whether network service discovery is enabled
     * or disabled. Retrieve it with {@link android.content.Intent#getIntExtra(String,int)}.
     *
     * @see #NSD_STATE_DISABLED
     * @see #NSD_STATE_ENABLED
     */
    public static final String EXTRA_NSD_STATE = "nsd_state";

    /**
     * Network service discovery is disabled
     *
     * @see #ACTION_NSD_STATE_CHANGED
     */
    // TODO: Deprecate this since NSD service is never disabled.
    public static final int NSD_STATE_DISABLED = 1;

    /**
     * Network service discovery is enabled
     *
     * @see #ACTION_NSD_STATE_CHANGED
     */
    public static final int NSD_STATE_ENABLED = 2;

    /** @hide */
    public static final int DISCOVER_SERVICES                       = 1;
    /** @hide */
    public static final int DISCOVER_SERVICES_STARTED               = 2;
    /** @hide */
    public static final int DISCOVER_SERVICES_FAILED                = 3;
    /** @hide */
    public static final int SERVICE_FOUND                           = 4;
    /** @hide */
    public static final int SERVICE_LOST                            = 5;

    /** @hide */
    public static final int STOP_DISCOVERY                          = 6;
    /** @hide */
    public static final int STOP_DISCOVERY_FAILED                   = 7;
    /** @hide */
    public static final int STOP_DISCOVERY_SUCCEEDED                = 8;

    /** @hide */
    public static final int REGISTER_SERVICE                        = 9;
    /** @hide */
    public static final int REGISTER_SERVICE_FAILED                 = 10;
    /** @hide */
    public static final int REGISTER_SERVICE_SUCCEEDED              = 11;

    /** @hide */
    public static final int UNREGISTER_SERVICE                      = 12;
    /** @hide */
    public static final int UNREGISTER_SERVICE_FAILED               = 13;
    /** @hide */
    public static final int UNREGISTER_SERVICE_SUCCEEDED            = 14;

    /** @hide */
    public static final int RESOLVE_SERVICE                         = 15;
    /** @hide */
    public static final int RESOLVE_SERVICE_FAILED                  = 16;
    /** @hide */
    public static final int RESOLVE_SERVICE_SUCCEEDED               = 17;

    /** @hide */
    public static final int DAEMON_CLEANUP                          = 18;
    /** @hide */
    public static final int DAEMON_STARTUP                          = 19;

    /** @hide */
    public static final int MDNS_SERVICE_EVENT                      = 20;

    /** @hide */
    public static final int REGISTER_CLIENT                         = 21;
    /** @hide */
    public static final int UNREGISTER_CLIENT                       = 22;

    /** @hide */
    public static final int MDNS_DISCOVERY_MANAGER_EVENT            = 23;

    /** @hide */
    public static final int STOP_RESOLUTION                         = 24;
    /** @hide */
    public static final int STOP_RESOLUTION_FAILED                  = 25;
    /** @hide */
    public static final int STOP_RESOLUTION_SUCCEEDED               = 26;

    /** @hide */
    public static final int REGISTER_SERVICE_CALLBACK               = 27;
    /** @hide */
    public static final int REGISTER_SERVICE_CALLBACK_FAILED        = 28;
    /** @hide */
    public static final int SERVICE_UPDATED                         = 29;
    /** @hide */
    public static final int SERVICE_UPDATED_LOST                    = 30;

    /** @hide */
    public static final int UNREGISTER_SERVICE_CALLBACK             = 31;
    /** @hide */
    public static final int UNREGISTER_SERVICE_CALLBACK_SUCCEEDED   = 32;
    /** @hide */
    public static final int REGISTER_OFFLOAD_ENGINE                 = 33;
    /** @hide */
    public static final int UNREGISTER_OFFLOAD_ENGINE               = 34;

    /** Dns based service discovery protocol */
    public static final int PROTOCOL_DNS_SD = 0x0001;

    private static final SparseArray<String> EVENT_NAMES = new SparseArray<>();
    static {
        EVENT_NAMES.put(DISCOVER_SERVICES, "DISCOVER_SERVICES");
        EVENT_NAMES.put(DISCOVER_SERVICES_STARTED, "DISCOVER_SERVICES_STARTED");
        EVENT_NAMES.put(DISCOVER_SERVICES_FAILED, "DISCOVER_SERVICES_FAILED");
        EVENT_NAMES.put(SERVICE_FOUND, "SERVICE_FOUND");
        EVENT_NAMES.put(SERVICE_LOST, "SERVICE_LOST");
        EVENT_NAMES.put(STOP_DISCOVERY, "STOP_DISCOVERY");
        EVENT_NAMES.put(STOP_DISCOVERY_FAILED, "STOP_DISCOVERY_FAILED");
        EVENT_NAMES.put(STOP_DISCOVERY_SUCCEEDED, "STOP_DISCOVERY_SUCCEEDED");
        EVENT_NAMES.put(REGISTER_SERVICE, "REGISTER_SERVICE");
        EVENT_NAMES.put(REGISTER_SERVICE_FAILED, "REGISTER_SERVICE_FAILED");
        EVENT_NAMES.put(REGISTER_SERVICE_SUCCEEDED, "REGISTER_SERVICE_SUCCEEDED");
        EVENT_NAMES.put(UNREGISTER_SERVICE, "UNREGISTER_SERVICE");
        EVENT_NAMES.put(UNREGISTER_SERVICE_FAILED, "UNREGISTER_SERVICE_FAILED");
        EVENT_NAMES.put(UNREGISTER_SERVICE_SUCCEEDED, "UNREGISTER_SERVICE_SUCCEEDED");
        EVENT_NAMES.put(RESOLVE_SERVICE, "RESOLVE_SERVICE");
        EVENT_NAMES.put(RESOLVE_SERVICE_FAILED, "RESOLVE_SERVICE_FAILED");
        EVENT_NAMES.put(RESOLVE_SERVICE_SUCCEEDED, "RESOLVE_SERVICE_SUCCEEDED");
        EVENT_NAMES.put(DAEMON_CLEANUP, "DAEMON_CLEANUP");
        EVENT_NAMES.put(DAEMON_STARTUP, "DAEMON_STARTUP");
        EVENT_NAMES.put(MDNS_SERVICE_EVENT, "MDNS_SERVICE_EVENT");
        EVENT_NAMES.put(STOP_RESOLUTION, "STOP_RESOLUTION");
        EVENT_NAMES.put(STOP_RESOLUTION_FAILED, "STOP_RESOLUTION_FAILED");
        EVENT_NAMES.put(STOP_RESOLUTION_SUCCEEDED, "STOP_RESOLUTION_SUCCEEDED");
        EVENT_NAMES.put(REGISTER_SERVICE_CALLBACK, "REGISTER_SERVICE_CALLBACK");
        EVENT_NAMES.put(REGISTER_SERVICE_CALLBACK_FAILED, "REGISTER_SERVICE_CALLBACK_FAILED");
        EVENT_NAMES.put(SERVICE_UPDATED, "SERVICE_UPDATED");
        EVENT_NAMES.put(UNREGISTER_SERVICE_CALLBACK, "UNREGISTER_SERVICE_CALLBACK");
        EVENT_NAMES.put(UNREGISTER_SERVICE_CALLBACK_SUCCEEDED,
                "UNREGISTER_SERVICE_CALLBACK_SUCCEEDED");
        EVENT_NAMES.put(MDNS_DISCOVERY_MANAGER_EVENT, "MDNS_DISCOVERY_MANAGER_EVENT");
        EVENT_NAMES.put(REGISTER_CLIENT, "REGISTER_CLIENT");
        EVENT_NAMES.put(UNREGISTER_CLIENT, "UNREGISTER_CLIENT");
    }

    /** @hide */
    public static String nameOf(int event) {
        String name = EVENT_NAMES.get(event);
        if (name == null) {
            return Integer.toString(event);
        }
        return name;
    }

    private static final int FIRST_LISTENER_KEY = 1;

    private final INsdServiceConnector mService;
    private final Context mContext;

    private int mListenerKey = FIRST_LISTENER_KEY;
    @GuardedBy("mMapLock")
    private final SparseArray mListenerMap = new SparseArray();
    @GuardedBy("mMapLock")
    private final SparseArray<NsdServiceInfo> mServiceMap = new SparseArray<>();
    @GuardedBy("mMapLock")
    private final SparseArray<Executor> mExecutorMap = new SparseArray<>();
    private final Object mMapLock = new Object();
    // Map of listener key sent by client -> per-network discovery tracker
    @GuardedBy("mPerNetworkDiscoveryMap")
    private final ArrayMap<Integer, PerNetworkDiscoveryTracker>
            mPerNetworkDiscoveryMap = new ArrayMap<>();

    @GuardedBy("mOffloadEngines")
    private final ArrayList<OffloadEngineProxy> mOffloadEngines = new ArrayList<>();
    private final ServiceHandler mHandler;

    private static class OffloadEngineProxy extends IOffloadEngine.Stub {
        private final Executor mExecutor;
        private final OffloadEngine mEngine;

        private OffloadEngineProxy(@NonNull Executor executor, @NonNull OffloadEngine appCb) {
            mExecutor = executor;
            mEngine = appCb;
        }

        @Override
        public void onOffloadServiceUpdated(OffloadServiceInfo info) {
            mExecutor.execute(() -> mEngine.onOffloadServiceUpdated(info));
        }

        @Override
        public void onOffloadServiceRemoved(OffloadServiceInfo info) {
            mExecutor.execute(() -> mEngine.onOffloadServiceRemoved(info));
        }
    }

    /**
     * Registers an OffloadEngine with NsdManager.
     *
     * A caller can register itself as an OffloadEngine if it supports mDns hardware offload.
     * The caller must implement the {@link OffloadEngine} interface and update hardware offload
     * state property when the {@link OffloadEngine#onOffloadServiceUpdated} and
     * {@link OffloadEngine#onOffloadServiceRemoved} callback are called. Multiple engines may be
     * registered for the same interface, and that the same engine cannot be registered twice.
     *
     * @param ifaceName  indicates which network interface the hardware offload runs on
     * @param offloadType    the type of offload that the offload engine support
     * @param offloadCapability    the capabilities of the offload engine
     * @param executor   the executor on which to receive the offload callbacks
     * @param engine     the OffloadEngine that will receive the offload callbacks
     * @throws IllegalStateException if the engine is already registered.
     *
     * @hide
     */
    @FlaggedApi(NsdManager.Flags.REGISTER_NSD_OFFLOAD_ENGINE_API)
    @SystemApi
    @RequiresPermission(anyOf = {NETWORK_SETTINGS, PERMISSION_MAINLINE_NETWORK_STACK,
            NETWORK_STACK})
    public void registerOffloadEngine(@NonNull String ifaceName,
            @OffloadEngine.OffloadType long offloadType,
            @OffloadEngine.OffloadCapability long offloadCapability, @NonNull Executor executor,
            @NonNull OffloadEngine engine) {
        Objects.requireNonNull(ifaceName);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(engine);
        final OffloadEngineProxy cbImpl = new OffloadEngineProxy(executor, engine);
        synchronized (mOffloadEngines) {
            if (CollectionUtils.contains(mOffloadEngines, impl -> impl.mEngine == engine)) {
                throw new IllegalStateException("This engine is already registered");
            }
            mOffloadEngines.add(cbImpl);
        }
        try {
            mService.registerOffloadEngine(ifaceName, cbImpl, offloadCapability, offloadType);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }


    /**
     * Unregisters an OffloadEngine from NsdService.
     *
     * A caller can unregister itself as an OffloadEngine when it doesn't want to receive the
     * callback anymore. The OffloadEngine must have been previously registered with the system
     * using the {@link NsdManager#registerOffloadEngine} method.
     *
     * @param engine OffloadEngine object to be removed from NsdService
     * @throws IllegalStateException if the engine is not registered.
     *
     * @hide
     */
    @FlaggedApi(NsdManager.Flags.REGISTER_NSD_OFFLOAD_ENGINE_API)
    @SystemApi
    @RequiresPermission(anyOf = {NETWORK_SETTINGS, PERMISSION_MAINLINE_NETWORK_STACK,
            NETWORK_STACK})
    public void unregisterOffloadEngine(@NonNull OffloadEngine engine) {
        Objects.requireNonNull(engine);
        final OffloadEngineProxy cbImpl;
        synchronized (mOffloadEngines) {
            final int index = CollectionUtils.indexOf(mOffloadEngines,
                    impl -> impl.mEngine == engine);
            if (index < 0) {
                throw new IllegalStateException("This engine is not registered");
            }
            cbImpl = mOffloadEngines.remove(index);
        }

        try {
            mService.unregisterOffloadEngine(cbImpl);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private class PerNetworkDiscoveryTracker {
        final String mServiceType;
        final int mProtocolType;
        final DiscoveryListener mBaseListener;
        final Executor mBaseExecutor;
        final ArrayMap<Network, DelegatingDiscoveryListener> mPerNetworkListeners =
                new ArrayMap<>();

        final NetworkCallback mNetworkCb = new NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                final DelegatingDiscoveryListener wrappedListener = new DelegatingDiscoveryListener(
                        network, mBaseListener, mBaseExecutor);
                mPerNetworkListeners.put(network, wrappedListener);
                // Run discovery callbacks inline on the service handler thread, which is the
                // same thread used by this NetworkCallback, but DelegatingDiscoveryListener will
                // use the base executor to run the wrapped callbacks.
                discoverServices(mServiceType, mProtocolType, network, Runnable::run,
                        wrappedListener);
            }

            @Override
            public void onLost(@NonNull Network network) {
                final DelegatingDiscoveryListener listener = mPerNetworkListeners.get(network);
                if (listener == null) return;
                listener.notifyAllServicesLost();
                // Listener will be removed from map in discovery stopped callback
                stopServiceDiscovery(listener);
            }
        };

        // Accessed from mHandler
        private boolean mStopRequested;

        public void start(@NonNull NetworkRequest request) {
            final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
            cm.registerNetworkCallback(request, mNetworkCb, mHandler);
            mHandler.post(() -> mBaseExecutor.execute(() ->
                    mBaseListener.onDiscoveryStarted(mServiceType)));
        }

        /**
         * Stop discovery on all networks tracked by this class.
         *
         * This will request all underlying listeners to stop, and the last one to stop will call
         * onDiscoveryStopped or onStopDiscoveryFailed.
         *
         * Must be called on the handler thread.
         */
        public void requestStop() {
            mHandler.post(() -> {
                mStopRequested = true;
                final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
                cm.unregisterNetworkCallback(mNetworkCb);
                if (mPerNetworkListeners.size() == 0) {
                    mBaseExecutor.execute(() -> mBaseListener.onDiscoveryStopped(mServiceType));
                    return;
                }
                for (int i = 0; i < mPerNetworkListeners.size(); i++) {
                    final DelegatingDiscoveryListener listener = mPerNetworkListeners.valueAt(i);
                    stopServiceDiscovery(listener);
                }
            });
        }

        private PerNetworkDiscoveryTracker(String serviceType, int protocolType,
                Executor baseExecutor, DiscoveryListener baseListener) {
            mServiceType = serviceType;
            mProtocolType = protocolType;
            mBaseExecutor = baseExecutor;
            mBaseListener = baseListener;
        }

        /**
         * Subset of NsdServiceInfo that is tracked to generate service lost notifications when a
         * network is lost.
         *
         * Service lost notifications only contain service name, type and network, so only track
         * that information (Network is known from the listener). This also implements
         * equals/hashCode for usage in maps.
         */
        private class TrackedNsdInfo {
            private final String mServiceName;
            private final String mServiceType;
            TrackedNsdInfo(NsdServiceInfo info) {
                mServiceName = info.getServiceName();
                mServiceType = info.getServiceType();
            }

            @Override
            public int hashCode() {
                return Objects.hash(mServiceName, mServiceType);
            }

            @Override
            public boolean equals(Object obj) {
                if (!(obj instanceof TrackedNsdInfo)) return false;
                final TrackedNsdInfo other = (TrackedNsdInfo) obj;
                return Objects.equals(mServiceName, other.mServiceName)
                        && Objects.equals(mServiceType, other.mServiceType);
            }
        }

        /**
         * A listener wrapping calls to an app-provided listener, while keeping track of found
         * services, so they can all be reported lost when the underlying network is lost.
         *
         * This should be registered to run on the service handler.
         */
        private class DelegatingDiscoveryListener implements DiscoveryListener {
            private final Network mNetwork;
            private final DiscoveryListener mWrapped;
            private final Executor mWrappedExecutor;
            private final ArraySet<TrackedNsdInfo> mFoundInfo = new ArraySet<>();
            // When this flag is set to true, no further service found or lost callbacks should be
            // handled. This flag indicates that the network for this DelegatingDiscoveryListener is
            // lost, and any further callbacks would be redundant.
            private boolean mAllServicesLost = false;

            private DelegatingDiscoveryListener(Network network, DiscoveryListener listener,
                    Executor executor) {
                mNetwork = network;
                mWrapped = listener;
                mWrappedExecutor = executor;
            }

            void notifyAllServicesLost() {
                for (int i = 0; i < mFoundInfo.size(); i++) {
                    final TrackedNsdInfo trackedInfo = mFoundInfo.valueAt(i);
                    final NsdServiceInfo serviceInfo = new NsdServiceInfo(
                            trackedInfo.mServiceName, trackedInfo.mServiceType);
                    serviceInfo.setNetwork(mNetwork);
                    mWrappedExecutor.execute(() -> mWrapped.onServiceLost(serviceInfo));
                }
                mAllServicesLost = true;
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                // The delegated listener is used when NsdManager takes care of starting/stopping
                // discovery on multiple networks. Failure to start on one network is not a global
                // failure to be reported up, as other networks may succeed: just log.
                Log.e(TAG, "Failed to start discovery for " + serviceType + " on " + mNetwork
                        + " with code " + errorCode);
                mPerNetworkListeners.remove(mNetwork);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                // Wrapped listener was called upon registration, it is not called for discovery
                // on each network
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Failed to stop discovery for " + serviceType + " on " + mNetwork
                        + " with code " + errorCode);
                mPerNetworkListeners.remove(mNetwork);
                if (mStopRequested && mPerNetworkListeners.size() == 0) {
                    // Do not report onStopDiscoveryFailed when some underlying listeners failed:
                    // this does not mean that all listeners did, and onStopDiscoveryFailed is not
                    // actionable anyway. Just report that discovery stopped.
                    mWrappedExecutor.execute(() -> mWrapped.onDiscoveryStopped(serviceType));
                }
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                mPerNetworkListeners.remove(mNetwork);
                if (mStopRequested && mPerNetworkListeners.size() == 0) {
                    mWrappedExecutor.execute(() -> mWrapped.onDiscoveryStopped(serviceType));
                }
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (mAllServicesLost) {
                    // This DelegatingDiscoveryListener no longer has a network connection. Ignore
                    // the callback.
                    return;
                }
                mFoundInfo.add(new TrackedNsdInfo(serviceInfo));
                mWrappedExecutor.execute(() -> mWrapped.onServiceFound(serviceInfo));
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                if (mAllServicesLost) {
                    // This DelegatingDiscoveryListener no longer has a network connection. Ignore
                    // the callback.
                    return;
                }
                mFoundInfo.remove(new TrackedNsdInfo(serviceInfo));
                mWrappedExecutor.execute(() -> mWrapped.onServiceLost(serviceInfo));
            }
        }
    }

    /**
     * Create a new Nsd instance. Applications use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * {@link android.content.Context#NSD_SERVICE Context.NSD_SERVICE}.
     * @param service the Binder interface
     * @hide - hide this because it takes in a parameter of type INsdManager, which
     * is a system private class.
     */
    public NsdManager(Context context, INsdManager service) {
        mContext = context;
        // Use a common singleton thread ConnectivityThread to be shared among all nsd tasks.
        // Instead of launching separate threads to handle tasks from the various instances.
        mHandler = new ServiceHandler(ConnectivityThread.getInstanceLooper());

        if (android.content.pm.SpecialRuntimePermAppUtils.isInternetCompatEnabled()) {
            // INsdManager#connect() enforces INTERNET permission
            mService = new INsdServiceConnector() {
                final NsdCallbackImpl callback = new NsdCallbackImpl(mHandler);

                @Override public void registerService(int listenerKey, NsdServiceInfo serviceInfo) {
                    callback.onRegisterServiceFailed(listenerKey, FAILURE_INTERNAL_ERROR);
                }
                @Override public void unregisterService(int listenerKey) {
                    callback.onUnregisterServiceFailed(listenerKey, FAILURE_INTERNAL_ERROR);
                }
                @Override public void discoverServices(int listenerKey, NsdServiceInfo serviceInfo) {
                    callback.onDiscoverServicesFailed(listenerKey, FAILURE_INTERNAL_ERROR);
                }
                @Override public void stopDiscovery(int listenerKey) {
                    callback.onStopDiscoveryFailed(listenerKey, FAILURE_INTERNAL_ERROR);
                }
                @Override public void resolveService(int listenerKey, NsdServiceInfo serviceInfo) {
                    callback.onResolveServiceFailed(listenerKey, FAILURE_INTERNAL_ERROR);
                }
                @Override public void startDaemon() {}
                @Override public void stopResolution(int listenerKey) {}
                @Override public void registerServiceInfoCallback(int listenerKey, NsdServiceInfo serviceInfo) {}
                @Override public void unregisterServiceInfoCallback(int listenerKey) {}
                @Override public android.os.IBinder asBinder() { return null; }
            };
        } else {
            try {
                mService = service.connect(new NsdCallbackImpl(mHandler), CompatChanges.isChangeEnabled(
                        ENABLE_PLATFORM_MDNS_BACKEND));
            } catch (RemoteException e) {
                throw new RuntimeException("Failed to connect to NsdService");
            }
        }

        // Only proactively start the daemon if the target SDK < S, otherwise the internal service
        // would automatically start/stop the native daemon as needed.
        if (!CompatChanges.isChangeEnabled(RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)) {
            try {
                mService.startDaemon();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to proactively start daemon");
                // Continue: the daemon can still be started on-demand later
            }
        }
    }

    private static class NsdCallbackImpl extends INsdManagerCallback.Stub {
        private final Handler mServHandler;

        NsdCallbackImpl(Handler serviceHandler) {
            mServHandler = serviceHandler;
        }

        private void sendInfo(int message, int listenerKey, NsdServiceInfo info) {
            mServHandler.sendMessage(mServHandler.obtainMessage(message, 0, listenerKey, info));
        }

        private void sendError(int message, int listenerKey, int error) {
            mServHandler.sendMessage(mServHandler.obtainMessage(message, error, listenerKey));
        }

        private void sendNoArg(int message, int listenerKey) {
            mServHandler.sendMessage(mServHandler.obtainMessage(message, 0, listenerKey));
        }

        @Override
        public void onDiscoverServicesStarted(int listenerKey, NsdServiceInfo info) {
            sendInfo(DISCOVER_SERVICES_STARTED, listenerKey, info);
        }

        @Override
        public void onDiscoverServicesFailed(int listenerKey, int error) {
            sendError(DISCOVER_SERVICES_FAILED, listenerKey, error);
        }

        @Override
        public void onServiceFound(int listenerKey, NsdServiceInfo info) {
            sendInfo(SERVICE_FOUND, listenerKey, info);
        }

        @Override
        public void onServiceLost(int listenerKey, NsdServiceInfo info) {
            sendInfo(SERVICE_LOST, listenerKey, info);
        }

        @Override
        public void onStopDiscoveryFailed(int listenerKey, int error) {
            sendError(STOP_DISCOVERY_FAILED, listenerKey, error);
        }

        @Override
        public void onStopDiscoverySucceeded(int listenerKey) {
            sendNoArg(STOP_DISCOVERY_SUCCEEDED, listenerKey);
        }

        @Override
        public void onRegisterServiceFailed(int listenerKey, int error) {
            sendError(REGISTER_SERVICE_FAILED, listenerKey, error);
        }

        @Override
        public void onRegisterServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            sendInfo(REGISTER_SERVICE_SUCCEEDED, listenerKey, info);
        }

        @Override
        public void onUnregisterServiceFailed(int listenerKey, int error) {
            sendError(UNREGISTER_SERVICE_FAILED, listenerKey, error);
        }

        @Override
        public void onUnregisterServiceSucceeded(int listenerKey) {
            sendNoArg(UNREGISTER_SERVICE_SUCCEEDED, listenerKey);
        }

        @Override
        public void onResolveServiceFailed(int listenerKey, int error) {
            sendError(RESOLVE_SERVICE_FAILED, listenerKey, error);
        }

        @Override
        public void onResolveServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            sendInfo(RESOLVE_SERVICE_SUCCEEDED, listenerKey, info);
        }

        @Override
        public void onStopResolutionFailed(int listenerKey, int error) {
            sendError(STOP_RESOLUTION_FAILED, listenerKey, error);
        }

        @Override
        public void onStopResolutionSucceeded(int listenerKey) {
            sendNoArg(STOP_RESOLUTION_SUCCEEDED, listenerKey);
        }

        @Override
        public void onServiceInfoCallbackRegistrationFailed(int listenerKey, int error) {
            sendError(REGISTER_SERVICE_CALLBACK_FAILED, listenerKey, error);
        }

        @Override
        public void onServiceUpdated(int listenerKey, NsdServiceInfo info) {
            sendInfo(SERVICE_UPDATED, listenerKey, info);
        }

        @Override
        public void onServiceUpdatedLost(int listenerKey) {
            sendNoArg(SERVICE_UPDATED_LOST, listenerKey);
        }

        @Override
        public void onServiceInfoCallbackUnregistered(int listenerKey) {
            sendNoArg(UNREGISTER_SERVICE_CALLBACK_SUCCEEDED, listenerKey);
        }
    }

    /**
     * Failures are passed with {@link RegistrationListener#onRegistrationFailed},
     * {@link RegistrationListener#onUnregistrationFailed},
     * {@link DiscoveryListener#onStartDiscoveryFailed},
     * {@link DiscoveryListener#onStopDiscoveryFailed} or {@link ResolveListener#onResolveFailed}.
     *
     * Indicates that the operation failed due to an internal error.
     */
    public static final int FAILURE_INTERNAL_ERROR               = 0;

    /**
     * Indicates that the operation failed because it is already active.
     */
    public static final int FAILURE_ALREADY_ACTIVE              = 3;

    /**
     * Indicates that the operation failed because the maximum outstanding
     * requests from the applications have reached.
     */
    public static final int FAILURE_MAX_LIMIT                   = 4;

    /**
     * Indicates that the stop operation failed because it is not running.
     * This failure is passed with {@link ResolveListener#onStopResolutionFailed}.
     */
    public static final int FAILURE_OPERATION_NOT_RUNNING       = 5;

    /**
     * Indicates that the service has failed to resolve because of bad parameters.
     *
     * This failure is passed with
     * {@link ServiceInfoCallback#onServiceInfoCallbackRegistrationFailed}.
     */
    public static final int FAILURE_BAD_PARAMETERS              = 6;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            FAILURE_OPERATION_NOT_RUNNING,
    })
    public @interface StopOperationFailureCode {
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            FAILURE_ALREADY_ACTIVE,
            FAILURE_BAD_PARAMETERS,
    })
    public @interface ResolutionFailureCode {
    }

    /** Interface for callback invocation for service discovery */
    public interface DiscoveryListener {

        public void onStartDiscoveryFailed(String serviceType, int errorCode);

        public void onStopDiscoveryFailed(String serviceType, int errorCode);

        public void onDiscoveryStarted(String serviceType);

        public void onDiscoveryStopped(String serviceType);

        public void onServiceFound(NsdServiceInfo serviceInfo);

        public void onServiceLost(NsdServiceInfo serviceInfo);
    }

    /** Interface for callback invocation for service registration */
    public interface RegistrationListener {

        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode);

        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode);

        public void onServiceRegistered(NsdServiceInfo serviceInfo);

        public void onServiceUnregistered(NsdServiceInfo serviceInfo);
    }

    /**
     * Callback for use with {@link NsdManager#resolveService} to resolve the service info and use
     * with {@link NsdManager#stopServiceResolution} to stop resolution.
     */
    public interface ResolveListener {

        /**
         * Called on the internal thread or with an executor passed to
         * {@link NsdManager#resolveService} to report the resolution was failed with an error.
         *
         * A resolution operation would call either onServiceResolved or onResolveFailed once based
         * on the result.
         */
        void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode);

        /**
         * Called on the internal thread or with an executor passed to
         * {@link NsdManager#resolveService} to report the resolved service info.
         *
         * A resolution operation would call either onServiceResolved or onResolveFailed once based
         * on the result.
         */
        void onServiceResolved(NsdServiceInfo serviceInfo);

        /**
         * Called on the internal thread or with an executor passed to
         * {@link NsdManager#resolveService} to report the resolution was stopped.
         *
         * A stop resolution operation would call either onResolutionStopped or
         * onStopResolutionFailed once based on the result.
         */
        default void onResolutionStopped(@NonNull NsdServiceInfo serviceInfo) { }

        /**
         * Called once on the internal thread or with an executor passed to
         * {@link NsdManager#resolveService} to report that stopping resolution failed with an
         * error.
         *
         * A stop resolution operation would call either onResolutionStopped or
         * onStopResolutionFailed once based on the result.
         */
        default void onStopResolutionFailed(@NonNull NsdServiceInfo serviceInfo,
                @StopOperationFailureCode int errorCode) { }
    }

    /**
     * Callback to listen to service info updates.
     *
     * For use with {@link NsdManager#registerServiceInfoCallback} to register, and with
     * {@link NsdManager#unregisterServiceInfoCallback} to stop listening.
     */
    public interface ServiceInfoCallback {

        /**
         * Reports that registering the callback failed with an error.
         *
         * Called on the executor passed to {@link NsdManager#registerServiceInfoCallback}.
         *
         * onServiceInfoCallbackRegistrationFailed will be called exactly once when the callback
         * could not be registered. No other callback will be sent in that case.
         */
        void onServiceInfoCallbackRegistrationFailed(@ResolutionFailureCode int errorCode);

        /**
         * Reports updated service info.
         *
         * Called on the executor passed to {@link NsdManager#registerServiceInfoCallback}. Any
         * service updates will be notified via this callback until
         * {@link NsdManager#unregisterServiceInfoCallback} is called. This will only be called once
         * the service is found, so may never be called if the service is never present.
         */
        void onServiceUpdated(@NonNull NsdServiceInfo serviceInfo);

        /**
         * Reports when the service that this callback listens to becomes unavailable.
         *
         * Called on the executor passed to {@link NsdManager#registerServiceInfoCallback}. The
         * service may become available again, in which case {@link #onServiceUpdated} will be
         * called.
         */
        void onServiceLost();

        /**
         * Reports that service info updates have stopped.
         *
         * Called on the executor passed to {@link NsdManager#registerServiceInfoCallback}.
         *
         * A callback unregistration operation will call onServiceInfoCallbackUnregistered
         * once. After this, the callback may be reused.
         */
        void onServiceInfoCallbackUnregistered();
    }

    @VisibleForTesting
    class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message message) {
            // Do not use message in the executor lambdas, as it will be recycled once this method
            // returns. Keep references to its content instead.
            final int what = message.what;
            final int errorCode = message.arg1;
            final int key = message.arg2;
            final Object obj = message.obj;
            final Object listener;
            final NsdServiceInfo ns;
            final Executor executor;
            synchronized (mMapLock) {
                listener = mListenerMap.get(key);
                ns = mServiceMap.get(key);
                executor = mExecutorMap.get(key);
            }
            if (listener == null) {
                Log.d(TAG, "Stale key " + key);
                return;
            }
            if (DBG) {
                Log.d(TAG, "received " + nameOf(what) + " for key " + key + ", service " + ns);
            }
            switch (what) {
                case DISCOVER_SERVICES_STARTED:
                    final String s = getNsdServiceInfoType((NsdServiceInfo) obj);
                    executor.execute(() -> ((DiscoveryListener) listener).onDiscoveryStarted(s));
                    break;
                case DISCOVER_SERVICES_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((DiscoveryListener) listener).onStartDiscoveryFailed(
                            getNsdServiceInfoType(ns), errorCode));
                    break;
                case SERVICE_FOUND:
                    executor.execute(() -> ((DiscoveryListener) listener).onServiceFound(
                            (NsdServiceInfo) obj));
                    break;
                case SERVICE_LOST:
                    executor.execute(() -> ((DiscoveryListener) listener).onServiceLost(
                            (NsdServiceInfo) obj));
                    break;
                case STOP_DISCOVERY_FAILED:
                    // TODO: failure to stop discovery should be internal and retried internally, as
                    // the effect for the client is indistinguishable from STOP_DISCOVERY_SUCCEEDED
                    removeListener(key);
                    executor.execute(() -> ((DiscoveryListener) listener).onStopDiscoveryFailed(
                            getNsdServiceInfoType(ns), errorCode));
                    break;
                case STOP_DISCOVERY_SUCCEEDED:
                    removeListener(key);
                    executor.execute(() -> ((DiscoveryListener) listener).onDiscoveryStopped(
                            getNsdServiceInfoType(ns)));
                    break;
                case REGISTER_SERVICE_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((RegistrationListener) listener).onRegistrationFailed(
                            ns, errorCode));
                    break;
                case REGISTER_SERVICE_SUCCEEDED:
                    executor.execute(() -> ((RegistrationListener) listener).onServiceRegistered(
                            (NsdServiceInfo) obj));
                    break;
                case UNREGISTER_SERVICE_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((RegistrationListener) listener).onUnregistrationFailed(
                            ns, errorCode));
                    break;
                case UNREGISTER_SERVICE_SUCCEEDED:
                    // TODO: do not unregister listener until service is unregistered, or provide
                    // alternative way for unregistering ?
                    removeListener(key);
                    executor.execute(() -> ((RegistrationListener) listener).onServiceUnregistered(
                            ns));
                    break;
                case RESOLVE_SERVICE_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((ResolveListener) listener).onResolveFailed(
                            ns, errorCode));
                    break;
                case RESOLVE_SERVICE_SUCCEEDED:
                    removeListener(key);
                    executor.execute(() -> ((ResolveListener) listener).onServiceResolved(
                            (NsdServiceInfo) obj));
                    break;
                case STOP_RESOLUTION_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((ResolveListener) listener).onStopResolutionFailed(
                            ns, errorCode));
                    break;
                case STOP_RESOLUTION_SUCCEEDED:
                    removeListener(key);
                    executor.execute(() -> ((ResolveListener) listener).onResolutionStopped(
                            ns));
                    break;
                case REGISTER_SERVICE_CALLBACK_FAILED:
                    removeListener(key);
                    executor.execute(() -> ((ServiceInfoCallback) listener)
                            .onServiceInfoCallbackRegistrationFailed(errorCode));
                    break;
                case SERVICE_UPDATED:
                    executor.execute(() -> ((ServiceInfoCallback) listener)
                            .onServiceUpdated((NsdServiceInfo) obj));
                    break;
                case SERVICE_UPDATED_LOST:
                    executor.execute(() -> ((ServiceInfoCallback) listener).onServiceLost());
                    break;
                case UNREGISTER_SERVICE_CALLBACK_SUCCEEDED:
                    removeListener(key);
                    executor.execute(() -> ((ServiceInfoCallback) listener)
                            .onServiceInfoCallbackUnregistered());
                    break;
                default:
                    Log.d(TAG, "Ignored " + message);
                    break;
            }
        }
    }

    private int nextListenerKey() {
        // Ensure mListenerKey >= FIRST_LISTENER_KEY;
        mListenerKey = Math.max(FIRST_LISTENER_KEY, mListenerKey + 1);
        return mListenerKey;
    }

    // Assert that the listener is not in the map, then add it and returns its key
    private int putListener(Object listener, Executor e, NsdServiceInfo s) {
        checkListener(listener);
        final int key;
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            if (valueIndex != -1) {
                throw new IllegalArgumentException("listener already in use");
            }
            key = nextListenerKey();
            mListenerMap.put(key, listener);
            mServiceMap.put(key, s);
            mExecutorMap.put(key, e);
        }
        return key;
    }

    private void removeListener(int key) {
        synchronized (mMapLock) {
            mListenerMap.remove(key);
            mServiceMap.remove(key);
            mExecutorMap.remove(key);
        }
    }

    private int getListenerKey(Object listener) {
        checkListener(listener);
        synchronized (mMapLock) {
            int valueIndex = mListenerMap.indexOfValue(listener);
            if (valueIndex == -1) {
                throw new IllegalArgumentException("listener not registered");
            }
            return mListenerMap.keyAt(valueIndex);
        }
    }

    private static String getNsdServiceInfoType(NsdServiceInfo s) {
        if (s == null) return "?";
        return s.getServiceType();
    }

    /**
     * Register a service to be discovered by other services.
     *
     * <p> The function call immediately returns after sending a request to register service
     * to the framework. The application is notified of a successful registration
     * through the callback {@link RegistrationListener#onServiceRegistered} or a failure
     * through {@link RegistrationListener#onRegistrationFailed}.
     *
     * <p> The application should call {@link #unregisterService} when the service
     * registration is no longer required, and/or whenever the application is stopped.
     *
     * @param serviceInfo The service being registered
     * @param protocolType The service discovery protocol
     * @param listener The listener notifies of a successful registration and is used to
     * unregister this service through a call on {@link #unregisterService}. Cannot be null.
     * Cannot be in use for an active service registration.
     */
    public void registerService(NsdServiceInfo serviceInfo, int protocolType,
            RegistrationListener listener) {
        registerService(serviceInfo, protocolType, Runnable::run, listener);
    }

    /**
     * Register a service to be discovered by other services.
     *
     * <p> The function call immediately returns after sending a request to register service
     * to the framework. The application is notified of a successful registration
     * through the callback {@link RegistrationListener#onServiceRegistered} or a failure
     * through {@link RegistrationListener#onRegistrationFailed}.
     *
     * <p> The application should call {@link #unregisterService} when the service
     * registration is no longer required, and/or whenever the application is stopped.
     * @param serviceInfo The service being registered
     * @param protocolType The service discovery protocol
     * @param executor Executor to run listener callbacks with
     * @param listener The listener notifies of a successful registration and is used to
     * unregister this service through a call on {@link #unregisterService}. Cannot be null.
     */
    public void registerService(@NonNull NsdServiceInfo serviceInfo, int protocolType,
            @NonNull Executor executor, @NonNull RegistrationListener listener) {
        if (serviceInfo.getPort() <= 0) {
            throw new IllegalArgumentException("Invalid port number");
        }
        checkServiceInfo(serviceInfo);
        checkProtocol(protocolType);
        int key = putListener(listener, executor, serviceInfo);
        try {
            mService.registerService(key, serviceInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister a service registered through {@link #registerService}. A successful
     * unregister is notified to the application with a call to
     * {@link RegistrationListener#onServiceUnregistered}.
     *
     * @param listener This should be the listener object that was passed to
     * {@link #registerService}. It identifies the service that should be unregistered
     * and notifies of a successful or unsuccessful unregistration via the listener
     * callbacks.  In API versions 20 and above, the listener object may be used for
     * another service registration once the callback has been called.  In API versions <= 19,
     * there is no entirely reliable way to know when a listener may be re-used, and a new
     * listener should be created for each service registration request.
     */
    public void unregisterService(RegistrationListener listener) {
        int id = getListenerKey(listener);
        try {
            mService.unregisterService(id);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Initiate service discovery to browse for instances of a service type. Service discovery
     * consumes network bandwidth and will continue until the application calls
     * {@link #stopServiceDiscovery}.
     *
     * <p> The function call immediately returns after sending a request to start service
     * discovery to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link DiscoveryListener#onDiscoveryStarted} or a failure
     * through {@link DiscoveryListener#onStartDiscoveryFailed}.
     *
     * <p> Upon successful start, application is notified when a service is found with
     * {@link DiscoveryListener#onServiceFound} or when a service is lost with
     * {@link DiscoveryListener#onServiceLost}.
     *
     * <p> Upon failure to start, service discovery is not active and application does
     * not need to invoke {@link #stopServiceDiscovery}
     *
     * <p> The application should call {@link #stopServiceDiscovery} when discovery of this
     * service type is no longer required, and/or whenever the application is paused or
     * stopped.
     *
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     * Cannot be null. Cannot be in use for an active service discovery.
     */
    public void discoverServices(String serviceType, int protocolType, DiscoveryListener listener) {
        discoverServices(serviceType, protocolType, (Network) null, Runnable::run, listener);
    }

    /**
     * Initiate service discovery to browse for instances of a service type. Service discovery
     * consumes network bandwidth and will continue until the application calls
     * {@link #stopServiceDiscovery}.
     *
     * <p> The function call immediately returns after sending a request to start service
     * discovery to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link DiscoveryListener#onDiscoveryStarted} or a failure
     * through {@link DiscoveryListener#onStartDiscoveryFailed}.
     *
     * <p> Upon successful start, application is notified when a service is found with
     * {@link DiscoveryListener#onServiceFound} or when a service is lost with
     * {@link DiscoveryListener#onServiceLost}.
     *
     * <p> Upon failure to start, service discovery is not active and application does
     * not need to invoke {@link #stopServiceDiscovery}
     *
     * <p> The application should call {@link #stopServiceDiscovery} when discovery of this
     * service type is no longer required, and/or whenever the application is paused or
     * stopped.
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param network Network to discover services on, or null to discover on all available networks
     * @param executor Executor to run listener callbacks with
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     */
    public void discoverServices(@NonNull String serviceType, int protocolType,
            @Nullable Network network, @NonNull Executor executor,
            @NonNull DiscoveryListener listener) {
        if (TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }
        checkProtocol(protocolType);

        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceType(serviceType);
        s.setNetwork(network);

        int key = putListener(listener, executor, s);
        try {
            mService.discoverServices(key, s);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Initiate service discovery to browse for instances of a service type. Service discovery
     * consumes network bandwidth and will continue until the application calls
     * {@link #stopServiceDiscovery}.
     *
     * <p> The function call immediately returns after sending a request to start service
     * discovery to the framework. The application is notified of a success to initiate
     * discovery through the callback {@link DiscoveryListener#onDiscoveryStarted} or a failure
     * through {@link DiscoveryListener#onStartDiscoveryFailed}.
     *
     * <p> Upon successful start, application is notified when a service is found with
     * {@link DiscoveryListener#onServiceFound} or when a service is lost with
     * {@link DiscoveryListener#onServiceLost}.
     *
     * <p> Upon failure to start, service discovery is not active and application does
     * not need to invoke {@link #stopServiceDiscovery}
     *
     * <p> The application should call {@link #stopServiceDiscovery} when discovery of this
     * service type is no longer required, and/or whenever the application is paused or
     * stopped.
     *
     * <p> During discovery, new networks may connect or existing networks may disconnect - for
     * example if wifi is reconnected. When a service was found on a network that disconnects,
     * {@link DiscoveryListener#onServiceLost} will be called. If a new network connects that
     * matches the {@link NetworkRequest}, {@link DiscoveryListener#onServiceFound} will be called
     * for services found on that network. Applications that do not want to track networks
     * themselves are encouraged to use this method instead of other overloads of
     * {@code discoverServices}, as they will receive proper notifications when a service becomes
     * available or unavailable due to network changes.
     * @param serviceType The service type being discovered. Examples include "_http._tcp" for
     * http services or "_ipp._tcp" for printers
     * @param protocolType The service discovery protocol
     * @param networkRequest Request specifying networks that should be considered when discovering
     * @param executor Executor to run listener callbacks with
     * @param listener  The listener notifies of a successful discovery and is used
     * to stop discovery on this serviceType through a call on {@link #stopServiceDiscovery}.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    public void discoverServices(@NonNull String serviceType, int protocolType,
            @NonNull NetworkRequest networkRequest, @NonNull Executor executor,
            @NonNull DiscoveryListener listener) {
        if (TextUtils.isEmpty(serviceType)) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }
        Objects.requireNonNull(networkRequest, "NetworkRequest cannot be null");
        checkProtocol(protocolType);

        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceType(serviceType);

        final int baseListenerKey = putListener(listener, executor, s);

        final PerNetworkDiscoveryTracker discoveryInfo = new PerNetworkDiscoveryTracker(
                serviceType, protocolType, executor, listener);

        synchronized (mPerNetworkDiscoveryMap) {
            mPerNetworkDiscoveryMap.put(baseListenerKey, discoveryInfo);
            discoveryInfo.start(networkRequest);
        }
    }

    /**
     * Stop service discovery initiated with {@link #discoverServices}.  An active service
     * discovery is notified to the application with {@link DiscoveryListener#onDiscoveryStarted}
     * and it stays active until the application invokes a stop service discovery. A successful
     * stop is notified to with a call to {@link DiscoveryListener#onDiscoveryStopped}.
     *
     * <p> Upon failure to stop service discovery, application is notified through
     * {@link DiscoveryListener#onStopDiscoveryFailed}.
     *
     * @param listener This should be the listener object that was passed to {@link #discoverServices}.
     * It identifies the discovery that should be stopped and notifies of a successful or
     * unsuccessful stop.  In API versions 20 and above, the listener object may be used for
     * another service discovery once the callback has been called.  In API versions <= 19,
     * there is no entirely reliable way to know when a listener may be re-used, and a new
     * listener should be created for each service discovery request.
     */
    public void stopServiceDiscovery(DiscoveryListener listener) {
        int id = getListenerKey(listener);
        // If this is a PerNetworkDiscovery request, handle it as such
        synchronized (mPerNetworkDiscoveryMap) {
            final PerNetworkDiscoveryTracker info = mPerNetworkDiscoveryMap.get(id);
            if (info != null) {
                info.requestStop();
                return;
            }
        }
        try {
            mService.stopDiscovery(id);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Resolve a discovered service. An application can resolve a service right before
     * establishing a connection to fetch the IP and port details on which to setup
     * the connection.
     *
     * @param serviceInfo service to be resolved
     * @param listener to receive callback upon success or failure. Cannot be null.
     * Cannot be in use for an active service resolution.
     *
     * @deprecated the returned ServiceInfo may get stale at any time after resolution, including
     * immediately after the callback is called, and may not contain some service information that
     * could be delivered later, like additional host addresses. Prefer using
     * {@link #registerServiceInfoCallback}, which will keep the application up-to-date with the
     * state of the service.
     */
    @Deprecated
    public void resolveService(NsdServiceInfo serviceInfo, ResolveListener listener) {
        resolveService(serviceInfo, Runnable::run, listener);
    }

    /**
     * Resolve a discovered service. An application can resolve a service right before
     * establishing a connection to fetch the IP and port details on which to setup
     * the connection.
     * @param serviceInfo service to be resolved
     * @param executor Executor to run listener callbacks with
     * @param listener to receive callback upon success or failure.
     *
     * @deprecated the returned ServiceInfo may get stale at any time after resolution, including
     * immediately after the callback is called, and may not contain some service information that
     * could be delivered later, like additional host addresses. Prefer using
     * {@link #registerServiceInfoCallback}, which will keep the application up-to-date with the
     * state of the service.
     */
    @Deprecated
    public void resolveService(@NonNull NsdServiceInfo serviceInfo,
            @NonNull Executor executor, @NonNull ResolveListener listener) {
        checkServiceInfo(serviceInfo);
        int key = putListener(listener, executor, serviceInfo);
        try {
            mService.resolveService(key, serviceInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop service resolution initiated with {@link #resolveService}.
     *
     * A successful stop is notified with a call to {@link ResolveListener#onResolutionStopped}.
     *
     * <p> Upon failure to stop service resolution for example if resolution is done or the
     * requester stops resolution repeatedly, the application is notified
     * {@link ResolveListener#onStopResolutionFailed} with {@link #FAILURE_OPERATION_NOT_RUNNING}
     *
     * @param listener This should be a listener object that was passed to {@link #resolveService}.
     *                 It identifies the resolution that should be stopped and notifies of a
     *                 successful or unsuccessful stop. Throws {@code IllegalArgumentException} if
     *                 the listener was not passed to resolveService before.
     */
    public void stopServiceResolution(@NonNull ResolveListener listener) {
        int id = getListenerKey(listener);
        try {
            mService.stopResolution(id);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Register a callback to listen for updates to a service.
     *
     * An application can listen to a service to continuously monitor availability of given service.
     * The callback methods will be called on the passed executor. And service updates are sent with
     * continuous calls to {@link ServiceInfoCallback#onServiceUpdated}.
     *
     * This is different from {@link #resolveService} which provides one shot service information.
     *
     * <p> An application can listen to a service once a time. It needs to cancel the registration
     * before registering other callbacks. Upon failure to register a callback for example if
     * it's a duplicated registration, the application is notified through
     * {@link ServiceInfoCallback#onServiceInfoCallbackRegistrationFailed} with
     * {@link #FAILURE_BAD_PARAMETERS}.
     *
     * @param serviceInfo the service to receive updates for
     * @param executor Executor to run callbacks with
     * @param listener to receive callback upon service update
     */
    public void registerServiceInfoCallback(@NonNull NsdServiceInfo serviceInfo,
            @NonNull Executor executor, @NonNull ServiceInfoCallback listener) {
        checkServiceInfo(serviceInfo);
        int key = putListener(listener, executor, serviceInfo);
        try {
            mService.registerServiceInfoCallback(key, serviceInfo);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister a callback registered with {@link #registerServiceInfoCallback}.
     *
     * A successful unregistration is notified with a call to
     * {@link ServiceInfoCallback#onServiceInfoCallbackUnregistered}. The same callback can only be
     * reused after this is called.
     *
     * <p>If the callback is not already registered, this will throw with
     * {@link IllegalArgumentException}.
     *
     * @param listener This should be a listener object that was passed to
     *                 {@link #registerServiceInfoCallback}. It identifies the registration that
     *                 should be unregistered and notifies of a successful or unsuccessful stop.
     *                 Throws {@code IllegalArgumentException} if the listener was not passed to
     *                 {@link #registerServiceInfoCallback} before.
     */
    public void unregisterServiceInfoCallback(@NonNull ServiceInfoCallback listener) {
        // Will throw IllegalArgumentException if the listener is not known
        int id = getListenerKey(listener);
        try {
            mService.unregisterServiceInfoCallback(id);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private static void checkListener(Object listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
    }

    private static void checkProtocol(int protocolType) {
        if (protocolType != PROTOCOL_DNS_SD) {
            throw new IllegalArgumentException("Unsupported protocol");
        }
    }

    private static void checkServiceInfo(NsdServiceInfo serviceInfo) {
        Objects.requireNonNull(serviceInfo, "NsdServiceInfo cannot be null");
        if (TextUtils.isEmpty(serviceInfo.getServiceName())) {
            throw new IllegalArgumentException("Service name cannot be empty");
        }
        if (TextUtils.isEmpty(serviceInfo.getServiceType())) {
            throw new IllegalArgumentException("Service type cannot be empty");
        }
    }
}
