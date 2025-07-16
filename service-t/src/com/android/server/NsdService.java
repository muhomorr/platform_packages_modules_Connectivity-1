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

package com.android.server;

import static android.Manifest.permission.DEVICE_POWER;
import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.Manifest.permission.NETWORK_STACK;
import static android.Manifest.permission.REGISTER_NSD_OFFLOAD_ENGINE;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK;
import static android.net.connectivity.ConnectivityCompatChanges.ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS;
import static android.net.connectivity.ConnectivityCompatChanges.RESTRICT_LOCAL_NETWORK;
import static android.net.connectivity.ConnectivityCompatChanges.USE_NSD_PICKER_WHEN_NO_LOCAL_NET_PERMISSION;
import static android.net.nsd.AdvertisingRequest.FLAG_OFFLOAD_ONLY;
import static android.net.nsd.AdvertisingRequest.FLAG_SKIP_PROBING;
import static android.net.nsd.AdvertisingRequest.FLAG_SKIP_SUBTYPE_ANNOUNCEMENTS;
import static android.net.nsd.DiscoveryRequest.FLAG_NO_PICKER;
import static android.net.nsd.DiscoveryRequest.FLAG_SHOW_PICKER;
import static android.net.nsd.DiscoveryRequest.FLAG_USER_APPROVED_ONLY;
import static android.net.nsd.NsdManager.FAILURE_INTERNAL_ERROR;
import static android.net.nsd.NsdManager.FAILURE_PERMISSION_DENIED;
import static android.net.nsd.NsdManager.MDNS_DISCOVERY_MANAGER_EVENT;
import static android.net.nsd.NsdManager.MDNS_SERVICE_EVENT;
import static android.net.nsd.NsdManager.OFFLOAD_ENGINE_SERVICE_INFO_UPDATE;
import static android.net.nsd.NsdManager.RESOLVE_SERVICE_SUCCEEDED;
import static android.net.nsd.NsdManager.SUBTYPE_LABEL_REGEX;
import static android.net.nsd.NsdManager.TYPE_REGEX;
import static android.net.nsd.OffloadEngine.OFFLOAD_CAPABILITY_BYPASS_MULTICAST_LOCK;
import static android.net.nsd.OffloadEngine.OFFLOAD_TYPE_FILTER_QUERIES;
import static android.net.nsd.OffloadEngine.OFFLOAD_TYPE_FILTER_REPLIES;
import static android.net.nsd.OffloadEngine.OFFLOAD_TYPE_QUERY;
import static android.net.nsd.OffloadEngine.OFFLOAD_TYPE_REPLY;
import static android.os.Process.SYSTEM_UID;
import static android.permission.PermissionManager.PERMISSION_GRANTED;
import static android.permission.flags.Flags.accessLocalNetworkPermissionEnabled;
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import static com.android.modules.utils.build.SdkLevel.isAtLeastB;
import static com.android.modules.utils.build.SdkLevel.isAtLeastU;
import static com.android.net.module.util.DnsUtils.toDnsUpperCase;
import static com.android.net.module.util.PermissionUtils.enforcePackageNameMatchesUid;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED;
import static com.android.server.ConnectivityStatsLog.CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_INVALID_SERVICE_TYPE_FROM_NSD_PICKER;
import static com.android.server.connectivity.mdns.MdnsAdvertiser.AdvertiserMetrics;
import static com.android.server.connectivity.mdns.MdnsConstants.NO_PACKET;
import static com.android.server.connectivity.mdns.MdnsConstants.NO_SERVICE_REMOVED;
import static com.android.server.connectivity.mdns.MdnsConstants.SERVICE_REMOVED_BY_GOODBYE_RECEIVED;
import static com.android.server.connectivity.mdns.MdnsConstants.SERVICE_REMOVED_BY_TTL_EXPIRED;
import static com.android.server.connectivity.mdns.MdnsRecord.MAX_LABEL_LENGTH;
import static com.android.server.connectivity.mdns.MdnsSearchOptions.AGGRESSIVE_QUERY_MODE;
import static com.android.server.connectivity.mdns.MdnsSearchOptions.PASSIVE_QUERY_MODE;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;
import static com.android.server.connectivity.mdns.util.MdnsUtils.createOffloadServiceInfoFromDiscoveryOffload;
import static com.android.tethering.flags.Flags.FLAG_NSD_SERVICE_PICKER;
import static com.android.tethering.flags.Flags.nsdMdnsScanOffload;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresApi;
import android.annotation.RequiresNoPermission;
import android.app.ActivityManager;
import android.app.compat.CompatChanges;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.ext.ConnectivityUtil;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.mdns.aidl.DiscoveryInfo;
import android.net.mdns.aidl.GetAddressInfo;
import android.net.mdns.aidl.IMDnsEventListener;
import android.net.mdns.aidl.RegistrationInfo;
import android.net.mdns.aidl.ResolutionInfo;
import android.net.nsd.AdvertisingRequest;
import android.net.nsd.DiscoveryRequest;
import android.net.nsd.INsdManager;
import android.net.nsd.INsdManagerCallback;
import android.net.nsd.INsdServiceConnector;
import android.net.nsd.IOffloadEngine;
import android.net.nsd.MDnsManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.nsd.OffloadEngine;
import android.net.nsd.OffloadServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PatternMatcher;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.connectivity.resources.aidl.NsdPickerConnector;
import com.android.connectivity.resources.aidl.NsdServiceReceiver;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.metrics.NetworkNsdReportedMetrics;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.HandlerUtils;
import com.android.net.module.util.InetAddressUtils;
import com.android.net.module.util.PermissionUtils;
import com.android.net.module.util.SdkUtil;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.ExecutorProvider;
import com.android.server.connectivity.mdns.MdnsAdvertiser;
import com.android.server.connectivity.mdns.MdnsAdvertisingOptions;
import com.android.server.connectivity.mdns.MdnsDiscoveryManager;
import com.android.server.connectivity.mdns.MdnsFeatureFlags;
import com.android.server.connectivity.mdns.MdnsInterfaceSocket;
import com.android.server.connectivity.mdns.MdnsMultinetworkSocketClient;
import com.android.server.connectivity.mdns.MdnsSearchOptions;
import com.android.server.connectivity.mdns.MdnsServiceBrowserListener;
import com.android.server.connectivity.mdns.MdnsServiceInfo;
import com.android.server.connectivity.mdns.MdnsServiceTypeClient.DiscoveryOffloadInfo;
import com.android.server.connectivity.mdns.MdnsSocketProvider;
import com.android.server.connectivity.mdns.OffloadCallback;
import com.android.server.connectivity.mdns.internal.ServiceAccessRepository;
import com.android.server.connectivity.mdns.util.MdnsUtils;
import com.android.tethering.flags.Flags;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network Service Discovery Service handles remote service discovery operation requests by
 * implementing the INsdManager interface.
 *
 * @hide
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class NsdService extends INsdManager.Stub {
    private static final String TAG = "NsdService";
    private static final String MDNS_TAG = "mDnsConnector";
    /**
     * Enable discovery using the Java DiscoveryManager, instead of the legacy mdnsresponder
     * implementation.
     */
    private static final String MDNS_DISCOVERY_MANAGER_VERSION = "mdns_discovery_manager_version";
    private static final String LOCAL_DOMAIN_NAME = "local";

    /**
     * Enable advertising using the Java MdnsAdvertiser, instead of the legacy mdnsresponder
     * implementation.
     */
    private static final String MDNS_ADVERTISER_VERSION = "mdns_advertiser_version";

    /**
     * Comma-separated list of type:flag mappings indicating the flags to use to allowlist
     * discovery/advertising using MdnsDiscoveryManager / MdnsAdvertiser for a given type.
     *
     * For example _mytype._tcp.local and _othertype._tcp.local would be configured with:
     * _mytype._tcp:mytype,_othertype._tcp.local:othertype
     *
     * In which case the flags:
     * "mdns_discovery_manager_allowlist_mytype_version",
     * "mdns_advertiser_allowlist_mytype_version",
     * "mdns_discovery_manager_allowlist_othertype_version",
     * "mdns_advertiser_allowlist_othertype_version"
     * would be used to toggle MdnsDiscoveryManager / MdnsAdvertiser for each type. The flags will
     * be read with
     * {@link DeviceConfigUtils#isTetheringFeatureEnabled}
     *
     * @see #MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX
     * @see #MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX
     * @see #MDNS_ALLOWLIST_FLAG_SUFFIX
     */
    private static final String MDNS_TYPE_ALLOWLIST_FLAGS = "mdns_type_allowlist_flags";

    private static final String MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX =
            "mdns_discovery_manager_allowlist_";
    private static final String MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX =
            "mdns_advertiser_allowlist_";
    private static final String MDNS_ALLOWLIST_FLAG_SUFFIX = "_version";

    private static final String FORCE_ENABLE_FLAG_FOR_TEST_PREFIX = "test_";

    // Copied from com.android.networkstack.tethering.TetheringFeatureFlags.
    private static final String TETHERING_AND_P2P_GO_LOCAL_AGENT =
            "tethering_and_p2p_go_local_agent";

    @VisibleForTesting
    static final String MDNS_CONFIG_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF =
            "mdns_config_running_app_active_importance_cutoff";
    @VisibleForTesting
    static final int DEFAULT_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF =
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    private final int mRunningAppActiveImportanceCutoff;

    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long CLEANUP_DELAY_MS = 10000;
    private static final int IFACE_IDX_ANY = 0;
    private static final int MAX_SERVICES_COUNT_METRIC_PER_CLIENT = 100;
    @VisibleForTesting
    static final int NO_TRANSACTION = -1;
    private static final int NO_SENT_QUERY_COUNT = 0;
    private static final int DISCOVERY_QUERY_SENT_CALLBACK = 1000;
    private static final int MAX_SUBTYPE_COUNT = 100;
    private static final int DNSSEC_PROTOCOL = 3;
    /**
     * Argument for {@link NsdManager#DISCOVER_SERVICES} indicating that the listener is a
     * {@link android.net.nsd.NsdManager.ServiceInfoCallback}, meaning that all services should
     * be resolved.
     */
    private static final int ARG_IS_SERVICE_INFO_CALLBACK = 1;
    private static final SharedLog LOGGER = new SharedLog("serviceDiscovery");

    private final Context mContext;
    @NonNull
    private final NsdHandler mHandler;
    // It can be null on V+ device since mdns native service provided by netd is removed.
    private final @Nullable MDnsManager mMDnsManager;
    private final MDnsEventCallback mMDnsEventCallback;
    private final @NonNull PermissionManager mPermissionManager;
    @NonNull
    private final Dependencies mDeps;
    @NonNull
    private final MdnsMultinetworkSocketClient mMdnsSocketClient;
    @NonNull
    private final MdnsDiscoveryManager mMdnsDiscoveryManager;
    @NonNull
    private final MdnsSocketProvider mMdnsSocketProvider;
    @NonNull
    private final MdnsAdvertiser mAdvertiser;
    @NonNull
    private final Clock mClock;
    private final SharedLog mServiceLogs = LOGGER.forSubComponent(TAG);
    private final ServiceAccessRepository mAccessRepository;
    // WARNING : Accessing these values in any thread is not safe, it must only be changed in the
    // handler thread.
    private boolean mIsDaemonStarted = false;
    private boolean mIsMonitoringSocketsStarted = false;

    /**
     * Clients receiving asynchronous messages
     */
    private final HashMap<NsdServiceConnector, ClientInfo> mClients = new HashMap<>();

    /* A map from transaction(unique) id to client info */
    private final SparseArray<ClientInfo> mTransactionIdToClientInfoMap = new SparseArray<>();

    // Note this is not final to avoid depending on the Wi-Fi service starting before NsdService
    @Nullable
    private WifiManager.MulticastLock mHeldMulticastLock;
    // Fulfilled network requests that require the Wi-Fi lock: key is the obtained Network,
    // value is the interface name.
    @NonNull
    private final ArrayMap<Network, String> mWifiLockRequiredNetworks = new ArrayMap<>();
    @NonNull
    private final ArraySet<Integer> mRunningAppActiveUids = new ArraySet<>();

    private final long mCleanupDelayMs;

    private static final int INVALID_ID = 0;
    private int mUniqueId = 1;
    // The count of the connected legacy clients.
    private int mLegacyClientCount = 0;
    // The number of client that ever connected.
    private int mClientNumberId = 1;

    private final RemoteCallbackList<IOffloadEngine> mOffloadEngines =
            new RemoteCallbackList<>();
    @NonNull
    private final MdnsFeatureFlags mMdnsFeatureFlags;
    private final boolean mEnablePicker;

    private static class OffloadEngineInfo {
        @NonNull final String mInterfaceName;
        final long mOffloadCapabilities;
        final long mOffloadType;
        @NonNull final IOffloadEngine mOffloadEngine;
        final NsdServiceConnector mConnector;

        OffloadEngineInfo(@NonNull IOffloadEngine offloadEngine,
                @NonNull String interfaceName, long capabilities, long offloadType,
                NsdServiceConnector connector) {
            this.mOffloadEngine = offloadEngine;
            this.mInterfaceName = interfaceName;
            this.mOffloadCapabilities = capabilities;
            this.mOffloadType = offloadType;
            this.mConnector = connector;
        }
    }

    @VisibleForTesting
    abstract static class MdnsListener implements MdnsServiceBrowserListener {
        protected final int mClientRequestId;
        protected final int mTransactionId;
        @NonNull
        protected final String mListenedServiceType;

        MdnsListener(int clientRequestId, int transactionId, @NonNull String listenedServiceType) {
            mClientRequestId = clientRequestId;
            mTransactionId = transactionId;
            mListenedServiceType = listenedServiceType;
        }

        @NonNull
        public String getListenedServiceType() {
            return mListenedServiceType;
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) { }

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) { }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) { }

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) { }

        @Override
        public void onSearchStoppedWithError(int error) { }

        @Override
        public void onSearchFailedToStart() { }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) { }

        @Override
        public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) { }

        void onUnregistered() {}

        // Ensure toString gets overridden
        @NonNull
        public abstract String toString();
    }

    private class DiscoveryListener extends MdnsListener {
        private final boolean mIsCompleteServiceInfoNeeded;

        DiscoveryListener(int clientRequestId, int transactionId,
                @NonNull String listenServiceType, DiscoveryRequest discoveryRequest) {
            super(clientRequestId, transactionId, listenServiceType);
            mIsCompleteServiceInfoNeeded = isCompleteServiceInfoRequired(discoveryRequest);
        }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            if (mIsCompleteServiceInfoNeeded) {
                return;
            }
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_FOUND,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        @Override
        public void onServiceFound(@androidx.annotation.NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            if (!mIsCompleteServiceInfoNeeded) {
                return;
            }
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_FOUND,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        // TODO: consider sending service found callbacks if a service is updated and starts
        //  matching DiscoveryRequest filters (onServiceUpdated is called).

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) {
            if (mIsCompleteServiceInfoNeeded) {
                return;
            }
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_LOST,
                    new MdnsEvent(mClientRequestId, serviceInfo, serviceRemovedReason));
        }

        @Override
        public void onServiceRemoved(@androidx.annotation.NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) {
            if (!mIsCompleteServiceInfoNeeded) {
                return;
            }
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_LOST,
                    new MdnsEvent(mClientRequestId, serviceInfo, serviceRemovedReason));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("DiscoveryListener: serviceType=%s", getListenedServiceType());
        }
    }

    /**
     * A listener to use for discovery that sends services to a UI picker instead of sending them
     * directly to the client.
     */
    private class PickerListener extends MdnsListener {
        private final ClientInfo mClientInfo;
        private final boolean mIsServiceInfoCallback;
        private final DiscoveryRequest mDiscoveryRequest;
        // Accumulate onServiceFound/onServiceLost callbacks until the picker receiver is registered
        private final ArrayList<PendingCallback> mPendingServiceCallbacks = new ArrayList<>();
        private final boolean mUseCompleteServiceInfo;

        private static class PendingCallback {
            final MdnsServiceInfo mServiceInfo;
            final int mEventCode;

            PendingCallback(MdnsServiceInfo info, int eventCode) {
                mServiceInfo = info;
                mEventCode = eventCode;
            }
        }

        private NsdServiceReceiver mServiceReceiver;
        private boolean mIsUnregistered = false;

        private final NsdPickerConnector.Stub mConnector = new NsdPickerConnector.Stub() {
            // The binder token to the connector is only sent to the picker app, so no additional
            // permission checks are necessary.
            @RequiresNoPermission
            @Override
            public void setServiceReceiver(@NonNull NsdServiceReceiver receiver) {
                mHandler.post(() -> handleSetServiceReceiver(receiver));
            }

            @RequiresNoPermission
            @Override
            public void notifyServiceSelected(@NonNull NsdServiceInfo service) {
                mHandler.post(() -> handleServiceSelected(service));
            }

            @RequiresNoPermission
            @Override
            public void notifySelectionCancelled() {
                mHandler.post(() -> handleSelectionCancelled());
            }
        };

        private PickerListener(int clientRequestId, int transactionId, String listenedServiceType,
                ClientInfo clientInfo, boolean isServiceInfoCallback,
                DiscoveryRequest discoveryRequest) {
            super(clientRequestId, transactionId, listenedServiceType);
            mClientInfo = clientInfo;
            mIsServiceInfoCallback = isServiceInfoCallback;
            mDiscoveryRequest = discoveryRequest;
            mUseCompleteServiceInfo = isServiceInfoCallback
                    || isCompleteServiceInfoRequired(discoveryRequest);
        }

        void startPicker() {
            final Intent intent = new Intent();
            intent.setAction(NsdPickerConnector.ACTION_PICKER);
            intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setPackage(mDeps.getConnectivityResourcesPackageName(mContext));
            final Bundle bundle = new Bundle();
            bundle.putBinder(NsdPickerConnector.EXTRA_CONNECTOR, mConnector);
            bundle.putString(NsdPickerConnector.EXTRA_APP_NAME, getAppName());
            bundle.putParcelable(NsdPickerConnector.EXTRA_REQUEST, mDiscoveryRequest);
            intent.putExtras(bundle);
            mContext.startActivityAsUser(intent, UserHandle.getUserHandleForUid(mClientInfo.mUid));
        }

        private String getAppName() {
            CharSequence appName;
            final PackageManager pm = mContext.getPackageManager();
            try {
                final ApplicationInfo appInfo = pm.getApplicationInfoAsUser(
                        mClientInfo.mPackageName, /* flags= */0,
                        UserHandle.getUserHandleForUid(mClientInfo.mUid));
                appName = pm.getApplicationLabel(appInfo);
            } catch (PackageManager.NameNotFoundException e) {
                mServiceLogs.e("Failed to find app name for " + mClientInfo.mPackageName);
                appName = null;
                // Fall through
            }
            return TextUtils.isEmpty(appName) ? mClientInfo.mPackageName : appName.toString();
        }

        @Override
        void onUnregistered() {
            mIsUnregistered = true;
            if (mServiceReceiver != null) {
                // Cancellation will be sent when mServiceReceiver is received otherwise
                sendCancellationToPicker(mServiceReceiver);
            }
        }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            if (mUseCompleteServiceInfo) {
                return;
            }
            mHandler.post(() -> handleOrQueueServiceFoundOrRemoved(serviceInfo,
                    NsdManager.SERVICE_FOUND, isServiceFromCache, NO_SERVICE_REMOVED));
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            if (!mUseCompleteServiceInfo) {
                return;
            }
            mHandler.post(() -> handleOrQueueServiceFoundOrRemoved(serviceInfo,
                    NsdManager.SERVICE_FOUND, isServiceFromCache, NO_SERVICE_REMOVED));
        }

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) {
            if (mUseCompleteServiceInfo) {
                return;
            }
            mHandler.post(() -> handleOrQueueServiceFoundOrRemoved(serviceInfo,
                    NsdManager.SERVICE_LOST, /* isServiceFromCache=*/false, serviceRemovedReason));
        }

        @Override
        public void onServiceRemoved(@androidx.annotation.NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) {
            if (!mUseCompleteServiceInfo) {
                return;
            }
            mHandler.post(() -> handleOrQueueServiceFoundOrRemoved(serviceInfo,
                    NsdManager.SERVICE_LOST, /* isServiceFromCache=*/false, serviceRemovedReason));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }

        private void handleOrQueueServiceFoundOrRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int eventCode, boolean isServiceFromCache, int serviceRemovedReason) {
            final DiscoveryManagerRequest request = getRequest();
            if (request == null) {
                // The request was unregistered, no need to update the picker
                return;
            }
            if (isServiceFilteredOut(serviceInfo, request.mDiscoveryRequest)) {
                return;
            }
            recordEventMetric(serviceInfo, eventCode, isServiceFromCache, serviceRemovedReason);
            if (mServiceReceiver == null) {
                mPendingServiceCallbacks.add(
                        new PendingCallback(serviceInfo, eventCode));
                return;
            }
            handleServiceFoundOrRemoved(serviceInfo, eventCode);
        }

        private void handleServiceFoundOrRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int eventCode) {
            final NsdServiceInfo nsdServiceInfo = buildNsdServiceInfoFromMdnsEvent(
                    serviceInfo, eventCode, mClientInfo);
            if (nsdServiceInfo == null) {
                // Errors are already logged if null
                return;
            }
            if (mUseCompleteServiceInfo) {
                addServiceInfoCallbackAttributes(serviceInfo, nsdServiceInfo);
            }
            // Ensure the picker always knows about the interface index. This is reset before
            // sending callbacks to apps.
            nsdServiceInfo.setInterfaceIndex(serviceInfo.getInterfaceIndex());
            try {
                if (eventCode == NsdManager.SERVICE_FOUND) {
                    mServiceReceiver.onServiceFound(nsdServiceInfo);
                } else {
                    mServiceReceiver.onServiceLost(nsdServiceInfo);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Could not send service to picker: picker may be closed");
            }
        }

        private void recordEventMetric(@NonNull MdnsServiceInfo serviceInfo, int eventCode,
                boolean isServiceFromCache, int serviceRemovedReason) {
            final ClientRequest request = getRequest();
            if (request == null) {
                return;
            }
            if (eventCode == NsdManager.SERVICE_FOUND) {
                if (isServiceFromCache) {
                    // Set the ServiceFromCache flag only if the service is actually being
                    // retrieved from the cache. This flag should not be overridden by later
                    // service found event, which may not be cached.
                    request.setServiceFromCache(true);
                }
                request.onServiceFound(serviceInfo.getServiceInstanceName());
            } else {
                request.onServiceLost(serviceRemovedReason);
            }
        }

        private void handleSetServiceReceiver(@NonNull NsdServiceReceiver receiver) {
            if (mIsUnregistered) {
                // Close the picker now if the callback was unregistered while it was starting
                sendCancellationToPicker(receiver);
                return;
            }
            mServiceReceiver = receiver;
            for (PendingCallback cb : mPendingServiceCallbacks) {
                handleServiceFoundOrRemoved(cb.mServiceInfo, cb.mEventCode);
            }
            mPendingServiceCallbacks.clear();
        }

        private void handleServiceSelected(@NonNull NsdServiceInfo service) {
            final ClientRequest request = getRequest();
            if (request == null) {
                Log.d(TAG, "Client request unregistered, ignoring selected service");
                return;
            }
            final String serviceType = service.getServiceType();
            // Service types from discovery have an extra dot at the end
            if (!serviceType.endsWith(".")) {
                Log.wtf(TAG, "Invalid service type format (expected dot suffix), ignoring");
                ConnectivityStatsLog.write_non_chained(
                        CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED,
                        mClientInfo.mUid,
                        null,
                        CORE_NETWORKING_CRITICAL_COUNTS_EVENT_OCCURRED__EVENT_TYPE__CRITICAL_COUNTS_EVENT_TYPE_INVALID_SERVICE_TYPE_FROM_NSD_PICKER,
                        1);
                return;
            }
            final String serviceTypeNoDot = serviceType.substring(0, serviceType.length() - 1);
            mAccessRepository.addAllowedService(mClientInfo.mUid, mClientInfo.mPackageName,
                    service.getServiceName(), serviceTypeNoDot);
            mClientInfo.log("Service selected for request " + mClientRequestId + ": " + service);
            final int ifIndex = service.getInterfaceIndex();
            if (service.getNetwork() != null) {
                // As documented in NsdServiceInfo#getInterfaceIndex, the interface index is only
                // sent back to apps when the network is null
                service.setInterfaceIndex(0);
            }
            // Metrics are already recorded when the service was discovered; only call the
            // client callbacks without recording metrics.
            // TODO: add metric for service selected
            if (mIsServiceInfoCallback) {
                mClientInfo.tryNotifyServiceUpdated(mClientRequestId, service, ifIndex, request);
            } else {
                // The picker may have returned a service with full information (address, port,
                // attributes). However, the DiscoveryListener.onServiceFound API contract specifies
                // that this information is only available after resolution. Clear these fields to
                // conform to the API and avoid providing unexpected data to the app.
                service.setPort(0);
                service.setHostname(null);
                service.clearAttributes();
                service.setHostAddresses(Collections.emptyList());
                mClientInfo.tryNotifyServiceFound(mClientRequestId, service);
            }

            stopDiscoveryManagerRequest(request, mClientRequestId, mTransactionId, mClientInfo);
            mClientInfo.onStopDiscoverySucceeded(mClientRequestId, request);
        }

        private void handleSelectionCancelled() {
            final ClientRequest request = getRequest();
            if (request == null) {
                Log.d(TAG, "Client request unregistered, ignoring selection cancellation");
                return;
            }
            mClientInfo.log("Service selection cancelled for request " + mClientRequestId);
            stopDiscoveryManagerRequest(request, mClientRequestId, mTransactionId, mClientInfo);
            mClientInfo.onStopDiscoverySucceeded(mClientRequestId, request);
        }

        private void sendCancellationToPicker(NsdServiceReceiver receiver) {
            try {
                mClientInfo.log("Picker cancelled for request " + mClientRequestId);
                receiver.onCancelled();
            } catch (RemoteException e) {
                mClientInfo.log("Could not send cancellation to picker: picker may be closed");
            }
        }

        private DiscoveryManagerRequest getRequest() {
            return (DiscoveryManagerRequest) mClientInfo.mClientRequests.get(mClientRequestId);
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("PickerListener: serviceType=%s", getListenedServiceType());
        }
    }

    private class ResolutionListener extends MdnsListener {
        private final String mServiceName;

        ResolutionListener(int clientRequestId, int transactionId,
                @NonNull String listenServiceType, @NonNull String serviceName) {
            super(clientRequestId, transactionId, listenServiceType);
            mServiceName = serviceName;
        }

        @Override
        public void onServiceFound(MdnsServiceInfo serviceInfo, boolean isServiceFromCache) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.RESOLVE_SERVICE_SUCCEEDED,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("ResolutionListener serviceName=%s, serviceType=%s",
                    mServiceName, getListenedServiceType());
        }
    }

    private class ServiceInfoListener extends MdnsListener {
        private final String mServiceNameLogTag;

        ServiceInfoListener(int clientRequestId, int transactionId,
                @NonNull String listenServiceType, @NonNull String serviceNameLogTag) {
            super(clientRequestId, transactionId, listenServiceType);
            this.mServiceNameLogTag = serviceNameLogTag;
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo,
                boolean isServiceFromCache) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED,
                    new MdnsEvent(mClientRequestId, serviceInfo, isServiceFromCache));
        }

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED,
                    new MdnsEvent(mClientRequestId, serviceInfo));
        }

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo,
                int serviceRemovedReason) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED_LOST,
                    new MdnsEvent(mClientRequestId, serviceInfo, serviceRemovedReason));
        }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes,
                int sentQueryTransactionId) {
            mHandler.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    DISCOVERY_QUERY_SENT_CALLBACK, new MdnsEvent(mClientRequestId));
        }

        @NonNull
        @Override
        public String toString() {
            return String.format("ServiceInfoListener serviceName=%s, serviceType=%s",
                    mServiceNameLogTag, getListenedServiceType());
        }
    }

    private class SocketRequestMonitor implements MdnsSocketProvider.SocketRequestMonitor {
        @Override
        public void onSocketRequestFulfilled(@Nullable Network socketNetwork,
                @NonNull MdnsInterfaceSocket socket, @NonNull int[] transports) {
            // The network may be null for Wi-Fi SoftAp interfaces (tethering), but there is no APF
            // filtering on such interfaces, so taking the multicast lock is not necessary to
            // disable APF filtering of multicast.
            if (socketNetwork == null
                    || !CollectionUtils.contains(transports, TRANSPORT_WIFI)
                    || CollectionUtils.contains(transports, TRANSPORT_VPN)) {
                return;
            }

            if (mWifiLockRequiredNetworks.put(socketNetwork, mDeps.getSocketInterfaceName(socket))
                    == null) {
                updateMulticastLock();
            }
        }

        @Override
        public void onSocketDestroyed(@Nullable Network socketNetwork,
                @NonNull MdnsInterfaceSocket socket) {
            if (mWifiLockRequiredNetworks.remove(socketNetwork) != null) {
                updateMulticastLock();
            }
        }
    }

    private class UidImportanceListener implements ActivityManager.OnUidImportanceListener {
        private final Handler mHandler;

        private UidImportanceListener(Handler handler) {
            mHandler = handler;
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            mHandler.post(() -> handleUidImportanceChanged(uid, importance));
        }
    }

    private void handleUidImportanceChanged(int uid, int importance) {
        // Lower importance values are more "important"
        final boolean modified = importance <= mRunningAppActiveImportanceCutoff
                ? mRunningAppActiveUids.add(uid)
                : mRunningAppActiveUids.remove(uid);
        if (modified) {
            updateMulticastLock();
        }
    }

    private boolean isServiceFilteredOut(@NonNull MdnsServiceInfo service,
            @NonNull DiscoveryRequest request) {
        if (request.getServiceNameFilter() != null) {
            // PatternMatcher glob tokens/modifiers do not include any character that would be
            // changed by toDnsUpperCase, so toDnsUpperCase can be applied to the pattern to make it
            // match an uppercase service name
            final PatternMatcher uppercaseMatcher = new PatternMatcher(
                    toDnsUpperCase(request.getServiceNameFilter().getPath()),
                    request.getServiceNameFilter().getType());
            if (!uppercaseMatcher.match(toDnsUpperCase(service.getServiceInstanceName()))) {
                return true;
            }
        }
        for (Map.Entry<String, PatternMatcher> entry : request.getAttributeFilters().entrySet()) {
            if (!service.attributeMatches(entry.getKey(), entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean isCompleteServiceInfoRequired(@NonNull DiscoveryRequest request) {
        return !request.getAttributeFilters().isEmpty()
                || request.getDisplayNameAttribute() != null;
    }

    private boolean serviceMatchesApprovedOnly(@NonNull ClientInfo clientInfo,
            @NonNull MdnsServiceInfo service, @NonNull DiscoveryRequest request) {
        final boolean approvedOnly = (request.getFlags() & FLAG_USER_APPROVED_ONLY) != 0;
        if (!approvedOnly) {
            return true;
        }
        final String serviceType = joinServiceType(service);
        return serviceType != null && mAccessRepository.isServiceAllowed(
                clientInfo.mUid, clientInfo.mPackageName, service.getServiceInstanceName(),
                serviceType);
    }

    @NonNull
    private Set<String> getOffloadedInterfaces() {
        final ArraySet<String> offloadedInterfaces = new ArraySet<>();
        final int count = mOffloadEngines.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                final OffloadEngineInfo engineInfo =
                        (OffloadEngineInfo) mOffloadEngines.getBroadcastCookie(i);

                final boolean hasBypassCapability = (engineInfo.mOffloadCapabilities
                        & OFFLOAD_CAPABILITY_BYPASS_MULTICAST_LOCK) != 0;
                final boolean matchQueryOffload =
                        (engineInfo.mOffloadType & OFFLOAD_TYPE_FILTER_REPLIES) != 0
                        || (engineInfo.mOffloadType & OFFLOAD_TYPE_QUERY) != 0;
                final boolean matchReplyOffload =
                        (engineInfo.mOffloadType & OFFLOAD_TYPE_FILTER_QUERIES) != 0
                        || (engineInfo.mOffloadType & OFFLOAD_TYPE_REPLY) != 0;
                final boolean hasRequiredTypes = matchQueryOffload && matchReplyOffload;

                if (hasBypassCapability && hasRequiredTypes) {
                    offloadedInterfaces.add(engineInfo.mInterfaceName);
                }
            }
        } finally {
            mOffloadEngines.finishBroadcast();
        }
        return offloadedInterfaces;
    }

    /**
     * Take or release the lock based on updated internal state.
     *
     * This determines whether the lock needs to be held based on
     * {@link #mWifiLockRequiredNetworks}, {@link #mRunningAppActiveUids} and
     * {@link ClientInfo#mClientRequests}, so it must be called after any of the these have been
     * updated.
     */
    private void updateMulticastLock() {
        final int needsLockUid = getMulticastLockNeededUid();
        final boolean shouldHoldLock = (needsLockUid >= 0);

        if (shouldHoldLock && mHeldMulticastLock == null) {
            // Acquire lock
            final WifiManager wm = mContext.getSystemService(WifiManager.class);
            if (wm == null) {
                Log.wtf(TAG, "Got a TRANSPORT_WIFI network without WifiManager");
                return;
            }
            mHeldMulticastLock = wm.createMulticastLock(TAG);
            mHeldMulticastLock.acquire();
            mServiceLogs.log("Taking multicast lock for uid " + needsLockUid);
        } else if (!shouldHoldLock && mHeldMulticastLock != null) {
            // Release lock
            mHeldMulticastLock.release();
            mHeldMulticastLock = null;
            mServiceLogs.log("Released multicast lock");
        }
    }

    /**
     * @return The UID of an app requiring the multicast lock, or -1 if none.
     */
    private int getMulticastLockNeededUid() {
        if (mWifiLockRequiredNetworks.size() == 0) {
            // Return early if NSD is not active, or not on any relevant network
            return -1;
        }

        // Get Wi-Fi networks that require multicast lock
        final Set<String> offloadedInterfaces = getOffloadedInterfaces();
        final Set<Network> networksRequiringLock = new ArraySet<>();
        mWifiLockRequiredNetworks.forEach((key, value) -> {
            if (!offloadedInterfaces.contains(value)) {
                networksRequiringLock.add(key);
            }
        });

        for (int i = 0; i < mTransactionIdToClientInfoMap.size(); i++) {
            final ClientInfo clientInfo = mTransactionIdToClientInfoMap.valueAt(i);
            if (!mRunningAppActiveUids.contains(clientInfo.mUid)) {
                // Ignore non-active UIDs
                continue;
            }

            if (clientInfo.hasAnyJavaBackendRequestForNonOffloadedNetworks(networksRequiringLock)) {
                return clientInfo.mUid;
            }
        }
        return -1;
    }

    /**
     *
     * @param uid The UID of the calling app
     * @return The permission for local network access, or empty string if the feature is disabled
     */
    private String getLocalNetworkPermission(int uid) {
        if (SdkLevel.isAtLeastB() && accessLocalNetworkPermissionEnabled()) {
            return Manifest.permission.ACCESS_LOCAL_NETWORK;
        } else if (isAtLeastB()
                && com.android.tethering.mainline.beta.Flags.lnpDeveloperOptIn()
                && CompatChanges.isChangeEnabled(RESTRICT_LOCAL_NETWORK, uid)) {
            return Manifest.permission.NEARBY_WIFI_DEVICES;
        }
        return "";
    }

    /**
     *
     * @return The error code for local network permission failures
     */
    private int getLocalNetworkPermissionError() {
        return accessLocalNetworkPermissionEnabled() ? FAILURE_PERMISSION_DENIED
                : FAILURE_INTERNAL_ERROR;
    }

    /**
     * @param uid The UID of the calling process
     * @param pid The PID of the calling process
     * @return The permission status for data delivery
     */
    private int checkDataDeliveryPermissions(int uid, int pid) {
        AttributionSource attributionSource = getAttributionSource(uid, pid);
        String localNetPermission = getLocalNetworkPermission(uid);
        if (attributionSource == null || localNetPermission.isEmpty()) {
            return PERMISSION_GRANTED;
        }
        return mPermissionManager.checkPermissionForStartDataDelivery(
                localNetPermission, attributionSource, null);
    }

    private boolean hasNetworkSettingsPermission(int uid, int pid) {
        return mContext.checkPermission(NETWORK_SETTINGS, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @param uid The UID of the calling process
     * @param pid The PID of the calling process
     */
    private void finishDataDelivery(int uid, int pid) {
        AttributionSource attributionSource = getAttributionSource(uid, pid);
        String localNetPermission = getLocalNetworkPermission(uid);
        if (attributionSource == null || localNetPermission.isEmpty()) {
            return;
        }
        mPermissionManager.finishDataDelivery(localNetPermission, attributionSource);
    }

    /**
     * Data class of mdns service callback information.
     */
    private static class MdnsEvent {
        final int mClientRequestId;
        @Nullable
        final MdnsServiceInfo mMdnsServiceInfo;
        final boolean mIsServiceFromCache;
        final int mServiceRemovedReason;

        MdnsEvent(int clientRequestId) {
            this(clientRequestId, null /* mdnsServiceInfo */, false /* isServiceFromCache */);
        }

        MdnsEvent(int clientRequestId, @Nullable MdnsServiceInfo mdnsServiceInfo) {
            this(clientRequestId, mdnsServiceInfo, false /* isServiceFromCache */);
        }

        MdnsEvent(int clientRequestId, @Nullable MdnsServiceInfo mdnsServiceInfo,
                boolean isServiceFromCache) {
            this(clientRequestId, mdnsServiceInfo, isServiceFromCache, NO_SERVICE_REMOVED);
        }

        MdnsEvent(int clientRequestId, @Nullable MdnsServiceInfo mdnsServiceInfo,
                int serviceRemovedReason) {
            this(clientRequestId, mdnsServiceInfo, false /* isServiceFromCache */,
                    serviceRemovedReason);
        }

        MdnsEvent(int clientRequestId, @Nullable MdnsServiceInfo mdnsServiceInfo,
                boolean isServiceFromCache, int serviceRemovedReason) {
            mClientRequestId = clientRequestId;
            mMdnsServiceInfo = mdnsServiceInfo;
            mIsServiceFromCache = isServiceFromCache;
            mServiceRemovedReason = serviceRemovedReason;
        }
    }

    private void maybeStartDaemon() {
        if (mIsDaemonStarted) {
            if (DBG) Log.d(TAG, "Daemon is already started.");
            return;
        }

        if (mMDnsManager == null) {
            Log.wtf(TAG, "maybeStartDaemon: mMDnsManager is null");
            return;
        }
        mMDnsManager.registerEventListener(mMDnsEventCallback);
        mMDnsManager.startDaemon();
        mIsDaemonStarted = true;
        maybeScheduleStop();
        mServiceLogs.log("Start mdns_responder daemon");
    }

    private void maybeStopDaemon() {
        if (!mIsDaemonStarted) {
            if (DBG) Log.d(TAG, "Daemon has not been started.");
            return;
        }

        if (mMDnsManager == null) {
            Log.wtf(TAG, "maybeStopDaemon: mMDnsManager is null");
            return;
        }
        mMDnsManager.unregisterEventListener(mMDnsEventCallback);
        mMDnsManager.stopDaemon();
        mIsDaemonStarted = false;
        mServiceLogs.log("Stop mdns_responder daemon");
    }

    private boolean isAnyRequestActive() {
        return mTransactionIdToClientInfoMap.size() != 0;
    }

    private void scheduleStop() {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(NsdManager.DAEMON_CLEANUP), mCleanupDelayMs);
    }

    private void maybeScheduleStop() {
        // The native daemon should stay alive and can't be cleanup
        // if any legacy client connected.
        if (!isAnyRequestActive() && mLegacyClientCount == 0) {
            scheduleStop();
        }
    }

    private void cancelStop() {
        mHandler.removeMessages(NsdManager.DAEMON_CLEANUP);
    }

    private void maybeStartMonitoringSockets() {
        if (mIsMonitoringSocketsStarted) {
            if (DBG) Log.d(TAG, "Socket monitoring is already started.");
            return;
        }

        mMdnsSocketProvider.startMonitoringSockets();
        mIsMonitoringSocketsStarted = true;
    }

    private void maybeStopMonitoringSocketsIfNoActiveRequest() {
        if (!mIsMonitoringSocketsStarted) return;
        if (isAnyRequestActive()) return;

        mMdnsSocketProvider.requestStopWhenInactive();
        mIsMonitoringSocketsStarted = false;
    }

    private boolean requestLimitReached(ClientInfo clientInfo) {
        if (clientInfo.mClientRequests.size() >= ClientInfo.MAX_LIMIT) {
            if (DBG) Log.d(TAG, "Exceeded max outstanding requests " + clientInfo);
            return true;
        }
        return false;
    }

    private ClientRequest storeLegacyRequestMap(int clientRequestId, int transactionId,
            ClientInfo clientInfo, int what, long startTimeMs) {
        final LegacyClientRequest request =
                new LegacyClientRequest(transactionId, what, startTimeMs);
        clientInfo.mClientRequests.put(clientRequestId, request);
        mTransactionIdToClientInfoMap.put(transactionId, clientInfo);
        // Remove the cleanup event because here comes a new request.
        cancelStop();
        return request;
    }

    private void storeAdvertiserRequestMap(int clientRequestId, int transactionId,
            ClientInfo clientInfo, @NonNull NsdServiceInfo serviceInfo) {
        final String serviceFullName =
                serviceInfo.getServiceName() + "." + serviceInfo.getServiceType();
        clientInfo.mClientRequests.put(clientRequestId, new AdvertiserClientRequest(
                transactionId, serviceInfo.getNetwork(), serviceFullName,
                mClock.elapsedRealtime()));
        mTransactionIdToClientInfoMap.put(transactionId, clientInfo);
        updateMulticastLock();
    }

    private void removeRequestMap(
            int clientRequestId, int transactionId, ClientInfo clientInfo) {
        final ClientRequest existing = clientInfo.mClientRequests.get(clientRequestId);
        if (existing == null) return;
        clientInfo.mClientRequests.remove(clientRequestId);
        mTransactionIdToClientInfoMap.remove(transactionId);

        if (existing instanceof LegacyClientRequest) {
            maybeScheduleStop();
        } else {
            maybeStopMonitoringSocketsIfNoActiveRequest();
            updateMulticastLock();
        }
    }

    private ClientRequest storeDiscoveryManagerRequestMap(int clientRequestId,
            int transactionId, MdnsListener listener, ClientInfo clientInfo,
            @Nullable Network requestedNetwork, boolean usingPermissionExemption,
            @Nullable DiscoveryRequest discoveryRequest) {
        final DiscoveryManagerRequest request = new DiscoveryManagerRequest(transactionId,
                listener, requestedNetwork, mClock.elapsedRealtime(), usingPermissionExemption,
                discoveryRequest);
        clientInfo.mClientRequests.put(clientRequestId, request);
        mTransactionIdToClientInfoMap.put(transactionId, clientInfo);
        updateMulticastLock();
        return request;
    }

    /**
     * Truncate a service name to up to 63 UTF-8 bytes.
     *
     * See RFC6763 4.1.1: service instance names are UTF-8 and up to 63 bytes. Truncating
     * names used in registerService follows historical behavior (see mdnsresponder
     * handle_regservice_request).
     */
    @NonNull
    private String truncateServiceName(@NonNull String originalName) {
        return MdnsUtils.truncateServiceName(originalName, MAX_LABEL_LENGTH);
    }

    private void stopDiscoveryManagerRequest(ClientRequest request, int clientRequestId,
            int transactionId, ClientInfo clientInfo) {
        clientInfo.unregisterMdnsListenerFromRequest(request);
        removeRequestMap(clientRequestId, transactionId, clientInfo);
    }

    private ClientInfo getClientInfoForReply(Message msg) {
        final ListenerArgs args = (ListenerArgs) msg.obj;
        return mClients.get(args.connector);
    }

    /**
     * Returns {@code false} if {@code subtypes} exceeds the maximum number limit or
     * contains invalid subtype label.
     */
    private boolean checkSubtypeLabels(Set<String> subtypes) {
        if (subtypes.size() > MAX_SUBTYPE_COUNT) {
            mServiceLogs.e(
                    "Too many subtypes: " + subtypes.size() + " (max = "
                            + MAX_SUBTYPE_COUNT + ")");
            return false;
        }

        for (String subtype : subtypes) {
            if (!checkSubtypeLabel(subtype)) {
                mServiceLogs.e("Subtype " + subtype + " is invalid");
                return false;
            }
        }
        return true;
    }

    private Set<String> dedupSubtypeLabels(Collection<String> subtypes) {
        final Map<String, String> subtypeMap = new LinkedHashMap<>(subtypes.size());
        for (String subtype : subtypes) {
            subtypeMap.put(toDnsUpperCase(subtype), subtype);
        }
        return new ArraySet<>(subtypeMap.values());
    }

    private boolean checkTtl(
                @Nullable Duration ttl, @NonNull ClientInfo clientInfo) {
        if (ttl == null) {
            return true;
        }

        final long ttlSeconds = ttl.toSeconds();
        final int uid = clientInfo.getUid();

        // Allows Thread module in the system_server to register TTL that is smaller than
        // 30 seconds
        final long minTtlSeconds = uid == SYSTEM_UID ? 0 : NsdManager.TTL_SECONDS_MIN;

        // Allows Thread module in the system_server to register TTL that is larger than
        // 10 hours
        final long maxTtlSeconds =
                uid == SYSTEM_UID ? 0xffffffffL : NsdManager.TTL_SECONDS_MAX;

        if (ttlSeconds < minTtlSeconds || ttlSeconds > maxTtlSeconds) {
            mServiceLogs.e("ttlSeconds exceeds allowed range (value = "
                    + ttlSeconds + ", allowedRange = [" + minTtlSeconds
                    + ", " + maxTtlSeconds + " ])");
            return false;
        }
        return true;
    }

    private boolean isOffloadOnlyAllowed() {
        if (!mContext.getPackageManager().hasSystemFeature(FEATURE_LEANBACK)) {
            return false;
        }
        // The offload-only code path is a fallback for Google Cast on Android TV devices.
        // To utilize APF-based mDNS offload, the service must be advertised via
        // NsdManager. However, limitations or edge cases might prevent Google Cast
        // service advertisement through NsdManager. Until these issues are resolved,
        // MediaShell can use the offload-only code path to still leverage APF for offload.
        // This code path is only valid in Android B TV release.
        return Build.VERSION_CODES.BAKLAVA == Build.VERSION.SDK_INT;
    }

    private class NsdHandler extends Handler {
        NsdHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int clientRequestId = msg.arg2;
            switch (msg.what) {
                case NsdManager.DISCOVER_SERVICES -> handleDiscoverServices(clientRequestId,
                        (DiscoveryArgs) msg.obj, msg.arg1 == ARG_IS_SERVICE_INFO_CALLBACK);
                case NsdManager.STOP_DISCOVERY -> handleStopDiscovery(clientRequestId,
                        (ListenerArgs) msg.obj);
                case NsdManager.REGISTER_SERVICE -> handleRegisterService(clientRequestId,
                        (AdvertisingArgs) msg.obj);
                case NsdManager.UNREGISTER_SERVICE -> handleUnregisterService(clientRequestId,
                        (ListenerArgs) msg.obj);
                case NsdManager.RESOLVE_SERVICE -> handleResolveService(clientRequestId,
                        (ListenerArgs) msg.obj);
                case NsdManager.STOP_RESOLUTION -> handleStopResolution(clientRequestId,
                        (ListenerArgs) msg.obj);
                case NsdManager.REGISTER_SERVICE_CALLBACK -> handleRegisterServiceCallback(
                        clientRequestId, (ListenerArgs) msg.obj);
                case NsdManager.UNREGISTER_SERVICE_CALLBACK -> handleUnregisterServiceCallback(
                        clientRequestId, (ListenerArgs) msg.obj);
                case MDNS_SERVICE_EVENT -> handleMDnsServiceEvent(msg.arg1, msg.arg2, msg.obj);
                case MDNS_DISCOVERY_MANAGER_EVENT ->
                    handleMdnsDiscoveryManagerEvent(msg.arg1, msg.arg2, msg.obj);
                case NsdManager.REGISTER_OFFLOAD_ENGINE -> handleRegisterOffloadEngine(
                        (OffloadEngineInfo) msg.obj);
                case NsdManager.UNREGISTER_OFFLOAD_ENGINE -> handleUnregisterOffloadEngine(
                        (IOffloadEngine) msg.obj);
                case NsdManager.INJECT_PROXY_OFFLOAD_ENGINE_RESPONSE ->
                        handleInjectProxyOffloadEngineResponse(
                                (ProxyOffloadEngineResponse) msg.obj
                        );
                case NsdManager.CHECK_PERMISSION_FOR_SERVICE ->
                        handleCheckPermissionForService((CheckPermissionArgs) msg.obj);
                case OFFLOAD_ENGINE_SERVICE_INFO_UPDATE ->
                        handleOffloadServiceInfoUpdate((OffloadServiceInfoUpdateArgs) msg.obj);
                case NsdManager.REGISTER_CLIENT -> handleRegisterClient(clientRequestId,
                        (ConnectorArgs) msg.obj);
                case NsdManager.UNREGISTER_CLIENT -> handleUnregisterClient(
                        (NsdServiceConnector) msg.obj);
                case NsdManager.DAEMON_CLEANUP -> handleDaemonCleanup();

                // This event should be only sent by the legacy (target SDK < S) clients.
                // Mark the sending client as legacy.
                case NsdManager.DAEMON_STARTUP -> handleDaemonStartup((ListenerArgs) msg.obj);
                default -> {
                    Log.wtf(TAG, "Unhandled " + msg);
                }
            }
        }

        void sendMessage(int what, int arg1, int arg2, @Nullable Object obj) {
            sendMessage(obtainMessage(what, arg1, arg2, obj));
        }
    }

    private static class DiscoveryPermissionResult {
        public final boolean usePicker;
        public final boolean usingLocalNetPermission;

        DiscoveryPermissionResult(boolean usePicker, boolean usingLocalNetPermission) {
            this.usePicker = usePicker;
            this.usingLocalNetPermission = usingLocalNetPermission;
        }
    }

    @Nullable
    private DiscoveryPermissionResult checkDiscoveryPermissionsAndPicker(
            ClientInfo clientInfo, DiscoveryRequest request, boolean useJavaBackend) {
        final long flags = request.getFlags();
        final boolean pickerRequested = (flags & FLAG_SHOW_PICKER) != 0;
        final boolean approvedOnly = (flags & FLAG_USER_APPROVED_ONLY) != 0;
        final boolean noPicker = (flags & FLAG_NO_PICKER) != 0;

        final boolean pickerSupported = useJavaBackend && mEnablePicker;
        if (pickerRequested && !pickerSupported) return null;

        final boolean permissionsRequired = !pickerSupported || (!pickerRequested && !approvedOnly);
        final boolean hasPermission = !permissionsRequired
                || checkDataDeliveryPermissions(
                        clientInfo.mUid, clientInfo.mPid) == PERMISSION_GRANTED;
        final boolean usePicker;
        if (pickerRequested) {
            usePicker = true;
        } else if (!hasPermission) {
            // App lacks permission. Show automatic picker if supported, allowed by flags,
            // and the compat change is enabled. Otherwise fail the request.
            if (pickerSupported && !noPicker && mDeps.isPickerAutoUpgradeEnabled(
                    clientInfo.getUid())) {
                usePicker = true;
            } else {
                return null;
            }
        } else {
            usePicker = false;
        }

        final boolean usingLocalNetPermission = permissionsRequired && hasPermission;
        return new DiscoveryPermissionResult(usePicker, usingLocalNetPermission);
    }

    private void handleDiscoverServices(int clientRequestId, DiscoveryArgs discoveryArgs,
            boolean isServiceInfoCallback) {
        if (DBG) Log.d(TAG, "Discover services");
        final ClientInfo clientInfo = mClients.get(discoveryArgs.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in discovery");
            return;
        }

        final DiscoveryRequest discoveryRequest = discoveryArgs.discoveryRequest;
        final Pair<String, List<String>> typeAndSubtype =
                parseTypeAndSubtype(discoveryRequest.getServiceType());
        final String serviceType = typeAndSubtype == null ? null : typeAndSubtype.first;
        final boolean useJavaBackend = useDiscoveryManager(clientInfo, serviceType);

        final DiscoveryPermissionResult permResult = checkDiscoveryPermissionsAndPicker(
                clientInfo, discoveryRequest, useJavaBackend);
        if (permResult == null) {
            clientInfo.onDiscoverServicesFailedPermissions(clientRequestId);
            return;
        }

        final boolean usePicker = permResult.usePicker;
        final boolean usingLocalNetPermission = permResult.usingLocalNetPermission;

        if (requestLimitReached(clientInfo)) {
            clientInfo.onDiscoverServicesFailedImmediately(clientRequestId,
                    NsdManager.FAILURE_MAX_LIMIT, true /* isLegacy */,
                    usingLocalNetPermission);
            return;
        }

        final int transactionId = getUniqueId();
        if (useJavaBackend) {
            if (serviceType == null || typeAndSubtype.second.size() > 1) {
                clientInfo.onDiscoverServicesFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_INTERNAL_ERROR, false /* isLegacy */,
                        usingLocalNetPermission);
                return;
            }

            String subtype = discoveryRequest.getSubtype();
            if (subtype == null && !typeAndSubtype.second.isEmpty()) {
                subtype = typeAndSubtype.second.get(0);
            }

            if (subtype != null && !checkSubtypeLabel(subtype)) {
                clientInfo.onDiscoverServicesFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_BAD_PARAMETERS, false /* isLegacy */,
                        usingLocalNetPermission);
                return;
            }

            final String listenServiceType = serviceType + ".local";
            maybeStartMonitoringSockets();
            final MdnsListener listener;
            if (usePicker) {
                final PickerListener pickerListener = new PickerListener(clientRequestId,
                        transactionId, listenServiceType, clientInfo, isServiceInfoCallback,
                        discoveryRequest);
                listener = pickerListener;
                pickerListener.startPicker();
                clientInfo.log("Register a PickerListener " + transactionId
                        + " for service type:" + listenServiceType);
            } else if (isServiceInfoCallback) {
                listener = new ServiceInfoListener(clientRequestId, transactionId,
                        listenServiceType, /* serviceNameLogTag= */"<all>");
                clientInfo.log("Register a ServiceInfoListener " + transactionId
                        + " for service type:" + listenServiceType);
            } else {
                listener = new DiscoveryListener(clientRequestId, transactionId, listenServiceType,
                        discoveryRequest);
                clientInfo.log("Register a DiscoveryListener " + transactionId
                        + " for service type:" + listenServiceType);
            }
            final boolean resolveAll = isServiceInfoCallback
                    || isCompleteServiceInfoRequired(discoveryRequest);
            final MdnsSearchOptions.Builder optionsBuilder =
                    MdnsSearchOptions.newBuilder()
                            .setNetwork(discoveryRequest.getNetwork())
                            .setRemoveExpiredService(true)
                            .setQueryMode(
                                    mMdnsFeatureFlags.isAggressiveQueryModeEnabled()
                                            ? AGGRESSIVE_QUERY_MODE
                                            : PASSIVE_QUERY_MODE)
                            .setResolveAllServices(resolveAll);
            if (subtype != null) {
                // checkSubtypeLabels() ensures that subtypes start with '_' but
                // MdnsSearchOptions expects the underscore to not be present.
                optionsBuilder.addSubtype(subtype.substring(1));
            }
            mMdnsDiscoveryManager.registerListener(
                    listenServiceType, listener, optionsBuilder.build());
            final boolean usingPermissionExemption = !usingLocalNetPermission;
            final ClientRequest clientRequest = storeDiscoveryManagerRequestMap(
                    clientRequestId, transactionId, listener, clientInfo,
                    discoveryRequest.getNetwork(), usingPermissionExemption,
                    discoveryRequest);
            clientInfo.onDiscoverServicesStarted(clientRequestId, discoveryRequest, clientRequest);
        } else {
            maybeStartDaemon();
            if (discoverServices(transactionId, discoveryRequest)) {
                if (DBG) {
                    Log.d(TAG, "Discover " + clientRequestId + " " + transactionId
                            + discoveryRequest.getServiceType());
                }
                final ClientRequest request = storeLegacyRequestMap(clientRequestId,
                        transactionId, clientInfo, NsdManager.DISCOVER_SERVICES,
                        mClock.elapsedRealtime());
                clientInfo.onDiscoverServicesStarted(clientRequestId, discoveryRequest, request);
            } else {
                stopServiceDiscovery(transactionId);
                clientInfo.onDiscoverServicesFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                        usingLocalNetPermission);
            }
        }
    }

    private void handleStopDiscovery(int clientRequestId, ListenerArgs args) {
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in stop discovery");
            return;
        }
        final ClientRequest request =
                clientInfo.mClientRequests.get(clientRequestId);
        if (request == null) {
            Log.e(TAG, "Unknown client request in STOP_DISCOVERY");
            return;
        }
        handleStopDiscovery(clientRequestId, clientInfo, request);
    }

    private void handleStopDiscovery(int clientRequestId, @NonNull ClientInfo clientInfo,
            @NonNull ClientRequest request) {
        if (DBG) Log.d(TAG, "Stop service discovery");
        final int transactionId = request.mTransactionId;
        // Note isMdnsDiscoveryManagerEnabled may have changed to false at this
        // point, so this needs to check the type of the original request to
        // unregister instead of looking at the flag value.
        if (request instanceof DiscoveryManagerRequest) {
            stopDiscoveryManagerRequest(
                    request, clientRequestId, transactionId, clientInfo);
            clientInfo.onStopDiscoverySucceeded(clientRequestId, request);
            clientInfo.log("Unregister the DiscoveryListener " + transactionId);
        } else {
            removeRequestMap(clientRequestId, transactionId, clientInfo);
            if (stopServiceDiscovery(transactionId)) {
                clientInfo.onStopDiscoverySucceeded(clientRequestId, request);
            } else {
                clientInfo.onStopDiscoveryFailed(
                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
            }
        }
    }

    private void handleRegisterService(int clientRequestId, AdvertisingArgs args) {
        if (DBG) Log.d(TAG, "Register service");
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in registration");
            return;
        }

        // Advertising always requires local network permissions, which are checked before posting
        // the request to the handler.
        final boolean usingLocalNetworkPermission = true;
        if (requestLimitReached(clientInfo)) {
            clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                    NsdManager.FAILURE_MAX_LIMIT, true /* isLegacy */, usingLocalNetworkPermission);
            return;
        }
        final AdvertisingRequest advertisingRequest = args.advertisingRequest;
        if (advertisingRequest == null) {
            Log.e(TAG, "Unknown advertisingRequest in registration");
            return;
        }
        final NsdServiceInfo serviceInfo = advertisingRequest.getServiceInfo();
        final String serviceType = serviceInfo.getServiceType();
        final Pair<String, List<String>> typeSubtype = parseTypeAndSubtype(
                serviceType);
        final String registerServiceType = typeSubtype == null
                ? null : typeSubtype.first;
        // Force set the hostname to null if the process id does not have NETWORK_SETTINGS. As this
        // permission is a signature permission, only processes having this permission will be able
        // to actually set the hostname.
        if (!TextUtils.isEmpty(serviceInfo.getHostname())
                && !hasNetworkSettingsPermission(clientInfo.mUid, clientInfo.mPid)) {
            serviceInfo.setHostname(null);
        }
        final String hostname = serviceInfo.getHostname();
        // Keep compatible with the legacy behavior: It's allowed to set host
        // addresses for a service registration although the host addresses
        // won't be registered. To register the addresses for a host, the
        // hostname must be specified.
        if (hostname == null) {
            serviceInfo.setHostAddresses(Collections.emptyList());
        }
        if (clientInfo.mUseJavaBackend
                || mDeps.isMdnsAdvertiserEnabled(mContext)
                || useAdvertiserForType(registerServiceType)) {
            if (serviceType != null && registerServiceType == null) {
                Log.e(TAG, "Invalid service type: " + serviceType);
                clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_INTERNAL_ERROR, false /* isLegacy */,
                        usingLocalNetworkPermission);
                return;
            }
            final int transactionId;
            boolean isUpdateOnly = (advertisingRequest.getFlags()
                    & AdvertisingRequest.NSD_ADVERTISING_UPDATE_ONLY) > 0;
            // If it is an update request, then reuse the old transactionId
            if (isUpdateOnly) {
                final ClientRequest existingClientRequest =
                        clientInfo.mClientRequests.get(clientRequestId);
                if (existingClientRequest == null) {
                    Log.e(TAG, "Invalid update on requestId: " + clientRequestId);
                    clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                            NsdManager.FAILURE_INTERNAL_ERROR,
                            false /* isLegacy */, usingLocalNetworkPermission);
                    return;
                }
                transactionId = existingClientRequest.mTransactionId;
            } else {
                transactionId = getUniqueId();
            }

            if (registerServiceType != null) {
                serviceInfo.setServiceType(registerServiceType);
                serviceInfo.setServiceName(
                        truncateServiceName(serviceInfo.getServiceName()));
            }

            if (!checkHostname(hostname)) {
                clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_BAD_PARAMETERS, false /* isLegacy */,
                        usingLocalNetworkPermission);
                return;
            }

            if (!checkPublicKey(serviceInfo.getPublicKey())) {
                Log.e(TAG,
                        "Invalid public key: "
                                + Arrays.toString(serviceInfo.getPublicKey()));
                clientInfo.onRegisterServiceFailedImmediately(
                        clientRequestId,
                        NsdManager.FAILURE_BAD_PARAMETERS,
                        false /* isLegacy */, usingLocalNetworkPermission);
                return;
            }

            Set<String> subtypes = new ArraySet<>(serviceInfo.getSubtypes());
            if (typeSubtype != null && typeSubtype.second != null) {
                for (String subType : typeSubtype.second) {
                    if (!TextUtils.isEmpty(subType)) {
                        subtypes.add(subType);
                    }
                }
            }
            subtypes = dedupSubtypeLabels(subtypes);

            if (!checkSubtypeLabels(subtypes)) {
                clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_BAD_PARAMETERS, false /* isLegacy */,
                        usingLocalNetworkPermission);
                return;
            }

            if (!checkTtl(advertisingRequest.getTtl(), clientInfo)) {
                clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_BAD_PARAMETERS, false /* isLegacy */,
                        usingLocalNetworkPermission);
                return;
            }
            final boolean isOffloadOnly =
                    (advertisingRequest.getFlags() & FLAG_OFFLOAD_ONLY) != 0;
            if (isOffloadOnly && !isOffloadOnlyAllowed()) {
                clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_BAD_PARAMETERS, false /* isLegacy */,
                        usingLocalNetworkPermission);
                return;
            }

            serviceInfo.setSubtypes(subtypes);
            maybeStartMonitoringSockets();
            final boolean skipProbing = (advertisingRequest.getFlags()
                    & FLAG_SKIP_PROBING) != 0;
            final boolean skipSubtypeAnnouncements = (advertisingRequest.getFlags()
                    & FLAG_SKIP_SUBTYPE_ANNOUNCEMENTS) != 0;
            final MdnsAdvertisingOptions mdnsAdvertisingOptions =
                    MdnsAdvertisingOptions.newBuilder()
                            .setIsOnlyUpdate(isUpdateOnly)
                            .setSkipProbing(skipProbing)
                            .setTtl(advertisingRequest.getTtl())
                            .setSkipSubtypeAnnouncements(skipSubtypeAnnouncements)
                            .setOffloadOnly(isOffloadOnly)
                            .build();
            mAdvertiser.addOrUpdateService(transactionId, serviceInfo,
                    mdnsAdvertisingOptions, clientInfo.mUid);
            storeAdvertiserRequestMap(clientRequestId, transactionId, clientInfo, serviceInfo);
        } else {
            maybeStartDaemon();
            final int transactionId = getUniqueId();
            if (registerService(transactionId, serviceInfo)) {
                if (DBG) {
                    Log.d(TAG, "Register " + clientRequestId
                            + " " + transactionId);
                }
                storeLegacyRequestMap(clientRequestId, transactionId, clientInfo,
                        NsdManager.REGISTER_SERVICE, mClock.elapsedRealtime());
                // Return success after mDns reports success
            } else {
                unregisterService(transactionId);
                clientInfo.onRegisterServiceFailedImmediately(clientRequestId,
                        NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                        usingLocalNetworkPermission);
            }
        }
    }

    private void handleUnregisterService(int clientRequestId, ListenerArgs args) {
        if (DBG) Log.d(TAG, "unregister service");
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in unregistration");
            return;
        }
        final ClientRequest request =
                clientInfo.mClientRequests.get(clientRequestId);
        if (request == null) {
            Log.e(TAG, "Unknown client request in UNREGISTER_SERVICE");
            return;
        }
        final int transactionId = request.mTransactionId;
        removeRequestMap(clientRequestId, transactionId, clientInfo);

        // Note isMdnsAdvertiserEnabled may have changed to false at this point,
        // so this needs to check the type of the original request to unregister
        // instead of looking at the flag value.
        if (request instanceof AdvertiserClientRequest) {
            final AdvertiserMetrics metrics =
                    mAdvertiser.getAdvertiserMetrics(transactionId);
            mAdvertiser.removeService(transactionId);
            clientInfo.onUnregisterServiceSucceeded(
                    clientRequestId, request, metrics);
        } else {
            if (unregisterService(transactionId)) {
                clientInfo.onUnregisterServiceSucceeded(clientRequestId, request,
                        new AdvertiserMetrics(NO_PACKET /* repliedRequestsCount */,
                                NO_PACKET /* sentPacketCount */,
                                0 /* conflictDuringProbingCount */,
                                0 /* conflictAfterProbingCount */));
            } else {
                clientInfo.onUnregisterServiceFailed(
                        clientRequestId, NsdManager.FAILURE_INTERNAL_ERROR);
            }
        }
    }

    private void handleResolveService(int clientRequestId, ListenerArgs args) {
        if (DBG) Log.d(TAG, "Resolve service");
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in resolution");
            return;
        }

        final Pair<String, List<String>> typeSubtype =
                parseTypeAndSubtype(args.serviceInfo.getServiceType());
        final String serviceType = typeSubtype == null
                ? null : typeSubtype.first;
        final boolean useJavaBackend = useDiscoveryManager(clientInfo, serviceType);
        if (useJavaBackend && serviceType == null) {
            clientInfo.onResolveServiceFailedImmediately(clientRequestId,
                    NsdManager.FAILURE_INTERNAL_ERROR, false /* isLegacy */,
                    false /* usingLocalNetworkPermission */);
            return;
        }
        final Runnable disallowedCb = () -> {
            clientInfo.mClientLogs.w("Not allowed to resolve service " + args.serviceInfo);
            clientInfo.onResolveServiceFailedPermissions(clientRequestId);
        };
        final Consumer<Boolean> allowedCb = usingPermissionExemption -> {
            if (useJavaBackend) {
                handleResolveServiceAfterPermissionCheck(clientRequestId, args.serviceInfo,
                        clientInfo, serviceType, usingPermissionExemption);
            } else {
                final boolean usingLocalNetworkPermission = !usingPermissionExemption;
                handleResolveServiceWithLegacyBackendAfterPermissionCheck(clientRequestId,
                        args.serviceInfo, clientInfo, usingLocalNetworkPermission);
            }
        };
        checkQueryServicePermissions(clientInfo, args.serviceInfo.getServiceName(), serviceType,
                disallowedCb, allowedCb);
    }

    private void checkQueryServicePermissions(@NonNull ClientInfo clientInfo,
            @Nullable String serviceName, @Nullable String serviceType,
            @NonNull Runnable disallowedCb, @NonNull Consumer<Boolean> allowedCb) {
        final boolean isServiceAllowed = mDeps.isAconfigFlagEnabled(FLAG_NSD_SERVICE_PICKER)
                && serviceName != null && serviceType != null
                && mAccessRepository.isServiceAllowed(clientInfo.mUid, clientInfo.mPackageName,
                serviceName, serviceType);

        // If the service is in the allowlist, no need for permissions.
        // Otherwise check for local network permission.
        // NsdService needs to track whether the local network permission or the allowlist is being
        // used, as if checkDataDeliveryPermissions returned PERMISSION_GRANTED, finishDataDelivery
        // will need to be called when the request is unregistered to stop blaming the app for using
        // the local network permission.
        final boolean usingPermissionExemption;
        if (isServiceAllowed) {
            usingPermissionExemption = true;
        } else if (checkDataDeliveryPermissions(
                clientInfo.mUid, clientInfo.mPid) == PERMISSION_GRANTED) {
            usingPermissionExemption = false;
        } else {
            disallowedCb.run();
            return;
        }
        allowedCb.accept(usingPermissionExemption);
    }

    private void handleResolveServiceAfterPermissionCheck(int clientRequestId, NsdServiceInfo info,
            ClientInfo clientInfo, String parsedServiceType, boolean usingPermissionExemption) {
        final int transactionId = getUniqueId();
        final String resolveServiceType = parsedServiceType + ".local";

        maybeStartMonitoringSockets();
        final MdnsListener listener = new ResolutionListener(clientRequestId,
                transactionId, resolveServiceType, info.getServiceName());
        final int ifaceIdx = info.getNetwork() != null ? 0 : info.getInterfaceIndex();
        final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                .setNetwork(info.getNetwork())
                .setInterfaceIndex(ifaceIdx)
                .setQueryMode(mMdnsFeatureFlags.isAggressiveQueryModeEnabled()
                        ? AGGRESSIVE_QUERY_MODE
                        : PASSIVE_QUERY_MODE)
                .setResolveInstanceName(info.getServiceName())
                .setRemoveExpiredService(true)
                .build();
        mMdnsDiscoveryManager.registerListener(
                resolveServiceType, listener, options);
        storeDiscoveryManagerRequestMap(clientRequestId, transactionId,
                listener, clientInfo, info.getNetwork(), usingPermissionExemption,
                /* discoveryRequest= */null);
        clientInfo.log("Register a ResolutionListener " + transactionId
                + " for service type:" + resolveServiceType);
    }

    private void handleResolveServiceWithLegacyBackendAfterPermissionCheck(int clientRequestId,
            NsdServiceInfo info, ClientInfo clientInfo, boolean usingLocalNetworkPermission) {
        final int transactionId = getUniqueId();
        if (clientInfo.mResolvedService != null) {
            clientInfo.onResolveServiceFailedImmediately(clientRequestId,
                    NsdManager.FAILURE_ALREADY_ACTIVE, true /* isLegacy */,
                    usingLocalNetworkPermission);
            return;
        }

        maybeStartDaemon();
        if (resolveService(transactionId, info)) {
            clientInfo.mResolvedService = new NsdServiceInfo();
            storeLegacyRequestMap(clientRequestId, transactionId, clientInfo,
                    NsdManager.RESOLVE_SERVICE, mClock.elapsedRealtime());
        } else {
            clientInfo.onResolveServiceFailedImmediately(clientRequestId,
                    NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                    usingLocalNetworkPermission);
        }
    }

    private void handleStopResolution(int clientRequestId, ListenerArgs args) {
        if (DBG) Log.d(TAG, "Stop service resolution");
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in stop resolution");
            return;
        }

        final ClientRequest request =
                clientInfo.mClientRequests.get(clientRequestId);
        if (request == null) {
            Log.e(TAG, "Unknown client request in STOP_RESOLUTION");
            return;
        }
        final int transactionId = request.mTransactionId;
        // Note isMdnsDiscoveryManagerEnabled may have changed to false at this
        // point, so this needs to check the type of the original request to
        // unregister instead of looking at the flag value.
        if (request instanceof DiscoveryManagerRequest) {
            stopDiscoveryManagerRequest(
                    request, clientRequestId, transactionId, clientInfo);
            clientInfo.onStopResolutionSucceeded(clientRequestId, request);
            clientInfo.log("Unregister the ResolutionListener " + transactionId);
        } else {
            removeRequestMap(clientRequestId, transactionId, clientInfo);
            if (stopResolveService(transactionId)) {
                clientInfo.onStopResolutionSucceeded(clientRequestId, request);
            } else {
                clientInfo.onStopResolutionFailed(
                        clientRequestId, NsdManager.FAILURE_OPERATION_NOT_RUNNING);
            }
            clientInfo.mResolvedService = null;
        }
    }

    private void handleRegisterServiceCallback(int clientRequestId, ListenerArgs args) {
        if (DBG) Log.d(TAG, "Register a service callback");
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in callback registration");
            return;
        }

        final NsdServiceInfo info = args.serviceInfo;
        final Pair<String, List<String>> typeAndSubtype =
                parseTypeAndSubtype(info.getServiceType());
        final String serviceType = typeAndSubtype == null
                ? null : typeAndSubtype.first;
        if (serviceType == null) {
            clientInfo.onServiceInfoCallbackRegistrationFailed(clientRequestId,
                    NsdManager.FAILURE_BAD_PARAMETERS, /* usingLocalNetworkPermission= */false);
            return;
        }

        Runnable disallowedCb = () -> {
            clientInfo.mClientLogs.w("Not allowed to watch service " + info);
            clientInfo.onServiceInfoCallbackRegistrationFailedPermissions(clientRequestId);
        };
        Consumer<Boolean> allowedCb = usingPermissionExemption ->
                handleRegisterServiceCallbackAfterPermissionCheck(
                        clientRequestId, args.serviceInfo, clientInfo, serviceType,
                        usingPermissionExemption);
        checkQueryServicePermissions(
                clientInfo, info.getServiceName(), serviceType, disallowedCb, allowedCb);
    }

    private void handleRegisterServiceCallbackAfterPermissionCheck(int clientRequestId,
            NsdServiceInfo info, ClientInfo clientInfo, String parsedServiceType,
            boolean usingPermissionExemption) {
        final int transactionId = getUniqueId();
        final String resolveServiceType = parsedServiceType + ".local";

        maybeStartMonitoringSockets();
        final MdnsListener listener = new ServiceInfoListener(clientRequestId,
                transactionId, resolveServiceType, info.getServiceName());
        final int ifIndex = info.getNetwork() != null
                ? 0 : info.getInterfaceIndex();
        final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                .setNetwork(info.getNetwork())
                .setInterfaceIndex(ifIndex)
                .setQueryMode(mMdnsFeatureFlags.isAggressiveQueryModeEnabled()
                        ? AGGRESSIVE_QUERY_MODE
                        : PASSIVE_QUERY_MODE)
                .setResolveInstanceName(info.getServiceName())
                .setRemoveExpiredService(true)
                .build();
        mMdnsDiscoveryManager.registerListener(resolveServiceType, listener, options);
        storeDiscoveryManagerRequestMap(clientRequestId, transactionId, listener,
                clientInfo, info.getNetwork(), usingPermissionExemption,
                /* discoveryRequest= */null);
        clientInfo.onServiceInfoCallbackRegistered(clientRequestId, transactionId);
        clientInfo.log("Register a ServiceInfoListener " + transactionId
                + " for service type:" + resolveServiceType);
    }

    private void handleUnregisterServiceCallback(int clientRequestId, ListenerArgs args) {
        final ClientInfo clientInfo = mClients.get(args.connector);
        // If the binder death notification for a INsdManagerCallback was received
        // before any calls are received by NsdService, the clientInfo would be
        // cleared and cause NPE. Add a null check here to prevent this corner case.
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in callback unregistration");
            return;
        }
        final ClientRequest request =
                clientInfo.mClientRequests.get(clientRequestId);
        if (request == null) {
            Log.e(TAG, "Unknown client request in UNREGISTER_SERVICE_CALLBACK");
            return;
        }

        if (request instanceof DiscoveryManagerRequest
                && ((DiscoveryManagerRequest) request).mDiscoveryRequest != null) {
            handleStopDiscovery(clientRequestId, clientInfo, request);
        } else {
            handleUnregisterServiceCallback(clientRequestId, clientInfo, request);
        }
    }

    private void handleUnregisterServiceCallback(int clientRequestId,
            @NonNull ClientInfo clientInfo, @NonNull ClientRequest request) {
        if (DBG) Log.d(TAG, "Unregister a service callback");
        final int transactionId = request.mTransactionId;
        if (request instanceof DiscoveryManagerRequest) {
            stopDiscoveryManagerRequest(
                    request, clientRequestId, transactionId, clientInfo);
            clientInfo.onServiceInfoCallbackUnregistered(clientRequestId, request);
            clientInfo.log("Unregister the ServiceInfoListener " + transactionId);
        } else {
            Log.e(TAG, "Unregister failed with non-DiscoveryManagerRequest.");
        }
    }

    private void handleOffloadServiceInfoUpdate(
            OffloadServiceInfoUpdateArgs offloadServiceInfoUpdateArgs
    ) {
        sendOffloadServiceInfosUpdate(
                offloadServiceInfoUpdateArgs.mInterfaceName,
                offloadServiceInfoUpdateArgs.mOffloadServiceInfo,
                offloadServiceInfoUpdateArgs.mIsRemove
        );
    }

    private void handleRegisterOffloadEngine(OffloadEngineInfo offloadEngineInfo) {
        final ClientInfo clientInfo = mClients.get(offloadEngineInfo.mConnector);
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in calls to register offload engine");
            return;
        }
        clientInfo.markIsOffloadEngine();
        // TODO: Limits the number of registrations created by a given class.
        mOffloadEngines.register(offloadEngineInfo.mOffloadEngine,
                offloadEngineInfo);
        sendAllOffloadServiceInfos(offloadEngineInfo);
        // Update the multicast lock state.
        updateMulticastLock();
    }

    private void handleUnregisterOffloadEngine(IOffloadEngine offloadEngine) {
        final int count = mOffloadEngines.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                final OffloadEngineInfo engineInfo =
                        (OffloadEngineInfo) mOffloadEngines.getBroadcastCookie(i);
                if (offloadEngine.asBinder() == engineInfo.mOffloadEngine.asBinder()) {
                    String interfaceName = engineInfo.mInterfaceName;
                    mOffloadEngines.unregister(offloadEngine);
                    mMdnsDiscoveryManager.notifyOffloadStop(interfaceName);
                    break;
                }
            }
        } finally {
            mOffloadEngines.finishBroadcast();
        }
        // Update the multicast lock state.
        updateMulticastLock();
    }

    private void handleInjectProxyOffloadEngineResponse(
            ProxyOffloadEngineResponse response) {
        final NsdServiceInfo serviceInfo = response.serviceInfo;
        final boolean isServiceLost = response.isServiceLost;
        final String ifaceName = response.interfaceName;
        mMdnsDiscoveryManager.handleProxyOffloadEngineResponse(
                serviceInfo,
                isServiceLost,
                ifaceName);
    }

    private void handleCheckPermissionForService(@NonNull CheckPermissionArgs args) {
        final ClientInfo clientInfo = mClients.get(args.mConnector);
        if (clientInfo == null) {
            Log.e(TAG, "Unknown connector in handleCheckPermissionForService");
            args.mResultReceiver.send(NsdManager.SERVICE_PERMISSION_DENIED, /* resultData= */null);
            return;
        }
        final boolean isServiceAllowed = mAccessRepository.isServiceAllowed(
                clientInfo.mUid, clientInfo.mPackageName, args.mServiceName, args.mServiceType);
        args.mResultReceiver.send(isServiceAllowed
                ? NsdManager.SERVICE_PERMISSION_GRANTED
                : NsdManager.SERVICE_PERMISSION_DENIED, /* resultData= */null);
    }

    private void handleRegisterClient(int clientRequestId, ConnectorArgs arg) {
        final INsdManagerCallback cb = arg.callback;
        try {
            cb.asBinder().linkToDeath(arg.connector, 0);
            final String tag = "Client" + arg.uid + "-" + mClientNumberId++;
            final NetworkNsdReportedMetrics metrics =
                    mDeps.makeNetworkNsdReportedMetrics(
                            (int) mClock.elapsedRealtime(), arg.uid);
            final ClientInfo clientInfo = new ClientInfo(cb, arg.uid, arg.pid, arg.packageName,
                    arg.useJavaBackend, mServiceLogs.forSubComponent(tag), metrics);
            mClients.put(arg.connector, clientInfo);
            if (mDeps.isAconfigFlagEnabled(FLAG_NSD_SERVICE_PICKER)) {
                // Load the access allowlist synchronously on the handler at client creation.
                // This ensures that any request processed for that client will have the allowlist
                // loaded. Loading the allowlist asynchronously (including posting it to the
                // handler) would create ordering problems where a request could come in before
                // the allowlist is loaded.
                mAccessRepository.loadPackage(arg.uid, arg.packageName);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Client request id " + clientRequestId + " has already died");
        }
    }

    private void handleUnregisterClient(NsdServiceConnector connector) {
        final ClientInfo clientInfo = mClients.remove(connector);
        if (clientInfo != null) {
            clientInfo.expungeAllRequests();
            if (clientInfo.isPreSClient()) {
                mLegacyClientCount -= 1;
            }
            if (mDeps.isAconfigFlagEnabled(FLAG_NSD_SERVICE_PICKER)
                    && !CollectionUtils.any(mClients.values(), c -> c.mUid == clientInfo.mUid
                    && Objects.equals(c.mPackageName, clientInfo.mPackageName))) {
                mAccessRepository.unloadPackage(clientInfo.mUid, clientInfo.mPackageName);
                mAccessRepository.maybeScheduleDatabaseMaintenance();
            }
        }
        maybeStopMonitoringSocketsIfNoActiveRequest();
        maybeScheduleStop();
    }

    private void handleDaemonCleanup() {
        maybeStopDaemon();
    }

    private void handleDaemonStartup(ListenerArgs args) {
        final ClientInfo clientInfo = mClients.get(args.connector);
        if (clientInfo != null) {
            cancelStop();
            clientInfo.setPreSClient();
            mLegacyClientCount += 1;
            maybeStartDaemon();
        }
    }

    private boolean handleMDnsServiceEvent(int code, int transactionId, Object obj) {
        ClientInfo clientInfo = mTransactionIdToClientInfoMap.get(transactionId);
        if (clientInfo == null) {
            Log.e(TAG, String.format(
                    "transactionId %d for %d has no client mapping", transactionId, code));
            return false;
        }

        /* This goes in response as msg.arg2 */
        int clientRequestId = clientInfo.getClientRequestId(transactionId);
        if (clientRequestId < 0) {
            // This can happen because of race conditions. For example,
            // SERVICE_FOUND may race with STOP_SERVICE_DISCOVERY,
            // and we may get in this situation.
            Log.d(TAG, String.format("%d for transactionId %d that is no longer active",
                    code, transactionId));
            return false;
        }
        final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
        if (request == null) {
            Log.e(TAG, "Unknown client request. clientRequestId=" + clientRequestId);
            return false;
        }
        if (DBG) {
            Log.d(TAG, String.format(
                    "MDns service event code:%d transactionId=%d", code, transactionId));
        }
        switch (code) {
            case IMDnsEventListener.SERVICE_FOUND -> handleMDnsServiceFound(clientInfo,
                    clientRequestId, request, obj);
            case IMDnsEventListener.SERVICE_LOST -> handleMDnsServiceLost(clientInfo,
                    clientRequestId, request, obj);
            case IMDnsEventListener.SERVICE_DISCOVERY_FAILED ->
                    handleMDnsServiceDiscoveryFailed(clientInfo, transactionId,
                            clientRequestId, request);
            case IMDnsEventListener.SERVICE_REGISTERED -> handleMDnsServiceRegistered(
                    clientInfo, clientRequestId, request, obj);
            case IMDnsEventListener.SERVICE_REGISTRATION_FAILED ->
                    handleMDnsServiceRegistrationFailed(clientInfo, transactionId,
                            clientRequestId, request);
            case IMDnsEventListener.SERVICE_RESOLVED -> handleMDnsServiceResolved(
                    clientInfo, transactionId,
                    clientRequestId, request, obj);
            case IMDnsEventListener.SERVICE_RESOLUTION_FAILED ->
                    handleMDnsServiceResolutionFailed(clientInfo, transactionId,
                            clientRequestId, request);
            case IMDnsEventListener.SERVICE_GET_ADDR_FAILED ->
                    handleMDnsServiceGetAddrFailed(clientInfo, transactionId,
                            clientRequestId, request);
            case IMDnsEventListener.SERVICE_GET_ADDR_SUCCESS ->
                    handleMDnsServiceGetAddrSuccess(clientInfo, transactionId,
                            clientRequestId, request, obj);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleMDnsServiceFound(ClientInfo clientInfo, int clientRequestId,
            ClientRequest request, Object obj) {
        final DiscoveryInfo info = (DiscoveryInfo) obj;
        final String name = info.serviceName;
        final String type = info.registrationType;
        final NsdServiceInfo servInfo = new NsdServiceInfo(name, type);
        final int foundNetId = info.netId;
        if (foundNetId == 0L) {
            // Ignore services that do not have a Network: they are not usable
            // by apps, as they would need privileged permissions to use
            // interfaces that do not have an associated Network.
            return;
        }
        if (foundNetId == INetd.DUMMY_NET_ID) {
            // Ignore services on the dummy0 interface: they are only seen when
            // discovering locally advertised services, and are not reachable
            // through that interface.
            return;
        }
        setServiceNetworkForCallback(servInfo, info.netId, info.interfaceIdx);

        clientInfo.onServiceFound(clientRequestId, servInfo, request);
    }

    private void handleMDnsServiceLost(ClientInfo clientInfo, int clientRequestId,
            ClientRequest request, Object obj) {
        final DiscoveryInfo info = (DiscoveryInfo) obj;
        final String name = info.serviceName;
        final String type = info.registrationType;
        final int lostNetId = info.netId;
        final NsdServiceInfo servInfo = new NsdServiceInfo(name, type);
        // The network could be set to null (netId 0) if it was torn down when the
        // service is lost
        // TODO: avoid returning null in that case, possibly by remembering
        // found services on the same interface index and their network at the time
        setServiceNetworkForCallback(servInfo, lostNetId, info.interfaceIdx);
        clientInfo.onServiceLost(
                clientRequestId, servInfo, request, SERVICE_REMOVED_BY_GOODBYE_RECEIVED);
    }

    private void handleMDnsServiceDiscoveryFailed(ClientInfo clientInfo, int transactionId,
            int clientRequestId, ClientRequest request) {
        clientInfo.onDiscoverServicesFailed(clientRequestId,
                NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                transactionId,
                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                request.usingLocalNetworkPermission());
    }

    private void handleMDnsServiceRegistered(ClientInfo clientInfo, int clientRequestId,
            ClientRequest request, Object obj) {
        final RegistrationInfo info = (RegistrationInfo) obj;
        final String name = info.serviceName;
        final NsdServiceInfo servInfo = new NsdServiceInfo(name, null /* serviceType */);
        clientInfo.onRegisterServiceSucceeded(clientRequestId, servInfo, request);
    }

    private void handleMDnsServiceRegistrationFailed(ClientInfo clientInfo,
            int transactionId, int clientRequestId, ClientRequest request) {
        clientInfo.onRegisterServiceFailed(clientRequestId,
                NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                transactionId,
                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                request.usingLocalNetworkPermission());
    }

    private void handleMDnsServiceResolved(ClientInfo clientInfo, int transactionId,
            int clientRequestId, ClientRequest request, Object obj) {
        final ResolutionInfo info = (ResolutionInfo) obj;
        int index = 0;
        final String fullName = info.serviceFullName;
        while (index < fullName.length() && fullName.charAt(index) != '.') {
            if (fullName.charAt(index) == '\\') {
                ++index;
            }
            ++index;
        }
        if (index >= fullName.length()) {
            Log.e(TAG, "Invalid service found " + fullName);
            return;
        }

        String name = unescape(fullName.substring(0, index));
        String rest = fullName.substring(index);
        String type = rest.replace(".local.", "");

        final NsdServiceInfo serviceInfo = clientInfo.mResolvedService;
        serviceInfo.setServiceName(name);
        serviceInfo.setServiceType(type);
        serviceInfo.setPort(info.port);
        serviceInfo.setTxtRecords(info.txtRecord);
        // Network will be added after SERVICE_GET_ADDR_SUCCESS

        stopResolveService(transactionId);
        removeRequestMap(clientRequestId, transactionId, clientInfo);

        final int transactionId2 = getUniqueId();
        if (getAddrInfo(transactionId2, info.hostname, info.interfaceIdx)) {
            storeLegacyRequestMap(clientRequestId, transactionId2, clientInfo,
                    NsdManager.RESOLVE_SERVICE, request.mStartTimeMs);
        } else {
            clientInfo.onResolveServiceFailed(clientRequestId,
                    NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                    transactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.usingLocalNetworkPermission());
            clientInfo.mResolvedService = null;
        }
    }

    private void handleMDnsServiceResolutionFailed(ClientInfo clientInfo, int transactionId,
            int clientRequestId, ClientRequest request) {
        /* NNN resolveId errorCode */
        stopResolveService(transactionId);
        removeRequestMap(clientRequestId, transactionId, clientInfo);
        clientInfo.onResolveServiceFailed(clientRequestId,
                NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                transactionId,
                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                request.usingLocalNetworkPermission());
        clientInfo.mResolvedService = null;
    }

    private void handleMDnsServiceGetAddrFailed(ClientInfo clientInfo, int transactionId,
            int clientRequestId, ClientRequest request) {
        /* NNN resolveId errorCode */
        stopGetAddrInfo(transactionId);
        removeRequestMap(clientRequestId, transactionId, clientInfo);
        clientInfo.onResolveServiceFailed(clientRequestId,
                NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                transactionId,
                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                request.usingLocalNetworkPermission());
        clientInfo.mResolvedService = null;
    }

    private void handleMDnsServiceGetAddrSuccess(ClientInfo clientInfo, int transactionId,
            int clientRequestId, ClientRequest request, Object obj) {
        /* NNN resolveId hostname ttl addr interfaceIdx netId */
        final GetAddressInfo info = (GetAddressInfo) obj;
        final String address = info.address;
        final int netId = info.netId;
        InetAddress serviceHost = null;
        try {
            serviceHost = InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            Log.wtf(TAG, "Invalid host in GET_ADDR_SUCCESS", e);
        }

        // If the resolved service is on an interface without a network, consider it
        // as a failure: it would not be usable by apps as they would need
        // privileged permissions.
        if (netId != NETID_UNSET && serviceHost != null) {
            clientInfo.mResolvedService.setHost(serviceHost);
            setServiceNetworkForCallback(clientInfo.mResolvedService,
                    netId, info.interfaceIdx);
            clientInfo.onResolveServiceSucceeded(
                    clientRequestId, clientInfo.mResolvedService, info.interfaceIdx, request);
        } else {
            clientInfo.onResolveServiceFailed(clientRequestId,
                    NsdManager.FAILURE_INTERNAL_ERROR, true /* isLegacy */,
                    transactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.usingLocalNetworkPermission());
        }
        stopGetAddrInfo(transactionId);
        removeRequestMap(clientRequestId, transactionId, clientInfo);
        clientInfo.mResolvedService = null;
    }

    @Nullable
    private NsdServiceInfo buildNsdServiceInfoFromMdnsEvent(
            final MdnsServiceInfo serviceInfo, int code, ClientInfo clientInfo) {
        final String joinedType = joinServiceType(serviceInfo);
        if (joinedType == null) {
            return null;
        }
        final String serviceType;
        switch (code) {
            case NsdManager.SERVICE_FOUND:
            case NsdManager.SERVICE_LOST:
                // For consistency with historical behavior, discovered service types have
                // a dot at the end.
                serviceType = joinedType + ".";
                break;
            case RESOLVE_SERVICE_SUCCEEDED:
                // For consistency with historical behavior, resolved service types have
                // a dot at the beginning.
                serviceType = "." + joinedType;
                break;
            default:
                serviceType = joinedType;
                break;
        }
        final String serviceName = serviceInfo.getServiceInstanceName();
        final NsdServiceInfo servInfo = new NsdServiceInfo(serviceName, serviceType);

        final long caps = serviceInfo.getCreationCapabilitiesBits();
        final boolean isLocalNetwork = (caps & (1L << NET_CAPABILITY_LOCAL_NETWORK)) != 0L;

        final Network network;
        if (!isLocalNetwork || (mMdnsFeatureFlags.mUseNetworkCallbackForLocalNetworks
                && CompatChanges.isChangeEnabled(
                ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS, clientInfo.getUid()))) {
            network = serviceInfo.getNetwork();
        } else {
            network = null;
        }

        // In MdnsDiscoveryManagerEvent, the Network can be null which means it is a
        // network for Tethering interface. In other words, the network == null means the
        // network has netId = INetd.LOCAL_NET_ID.
        setServiceNetworkForCallback(
                servInfo,
                network == null ? INetd.LOCAL_NET_ID : network.netId,
                serviceInfo.getInterfaceIndex());
        servInfo.setSubtypes(dedupSubtypeLabels(serviceInfo.getSubtypes()));
        servInfo.setExpirationTime(serviceInfo.getExpirationTime());
        return servInfo;
    }

    @Nullable
    private String joinServiceType(@NonNull MdnsServiceInfo serviceInfo) {
        final String[] typeArray = serviceInfo.getServiceType();
        if (typeArray.length == 0
                || !typeArray[typeArray.length - 1].equals(LOCAL_DOMAIN_NAME)) {
            Log.wtf(TAG, "MdnsServiceInfo type does not end in .local: "
                    + Arrays.toString(typeArray));
            return null;
        } else {
            return TextUtils.join(".", Arrays.copyOfRange(typeArray, 0, typeArray.length - 1));
        }
    }

    private void handleMdnsDiscoveryManagerEvent(
            int transactionId, int code, Object obj) {
        final ClientInfo clientInfo = mTransactionIdToClientInfoMap.get(transactionId);
        if (clientInfo == null) {
            Log.e(TAG, String.format(
                    "id %d for %d has no client mapping", transactionId, code));
            return;
        }

        final MdnsEvent event = (MdnsEvent) obj;
        final int clientRequestId = event.mClientRequestId;
        final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
        if (!(request instanceof DiscoveryManagerRequest)) {
            Log.e(TAG, "Unknown or invalid client request. clientRequestId=" + clientRequestId);
            return;
        }

        // Deal with the discovery sent callback
        if (code == DISCOVERY_QUERY_SENT_CALLBACK) {
            request.onQuerySent();
            return;
        }

        final DiscoveryRequest discReq = ((DiscoveryManagerRequest) request).mDiscoveryRequest;
        if (discReq != null && (isServiceFilteredOut(event.mMdnsServiceInfo, discReq)
                || !serviceMatchesApprovedOnly(clientInfo, event.mMdnsServiceInfo, discReq))) {
            if (DBG) {
                Log.d(TAG, "Service " + event.mMdnsServiceInfo + " filtered out for " + discReq);
            }
            return;
        }

        // Deal with other callbacks.
        final NsdServiceInfo info = buildNsdServiceInfoFromMdnsEvent(event.mMdnsServiceInfo,
                code, clientInfo);
        // Errors are already logged if null
        if (info == null) return;
        mServiceLogs.log(String.format(
                "MdnsDiscoveryManager event code=%s transactionId=%d",
                NsdManager.nameOf(code), transactionId));
        switch (code) {
            case NsdManager.SERVICE_FOUND -> handleDiscoveryManagerServiceFound(clientInfo,
                    clientRequestId, request,
                    info, event);
            case NsdManager.SERVICE_LOST -> handleDiscoveryManagerServiceLost(clientInfo,
                    clientRequestId, request, info, event.mServiceRemovedReason);
            case NsdManager.RESOLVE_SERVICE_SUCCEEDED ->
                    handleDiscoveryManagerResolveSucceeded(clientInfo, transactionId,
                            clientRequestId, request, info, event);
            case NsdManager.SERVICE_UPDATED -> handleDiscoveryManagerServiceUpdated(
                    clientInfo, clientRequestId, request,
                    info, event);
            case NsdManager.SERVICE_UPDATED_LOST -> handleDiscoveryManagerServiceUpdatedLost(
                    clientInfo, clientRequestId, request, info, event.mServiceRemovedReason);
        }
    }

    private void handleDiscoveryManagerServiceFound(ClientInfo clientInfo,
            int clientRequestId, ClientRequest request, NsdServiceInfo info,
            MdnsEvent event) {
        // Set the ServiceFromCache flag only if the service is actually being
        // retrieved from the cache. This flag should not be overridden by later
        // service found event, which may not be cached.
        if (event.mIsServiceFromCache) {
            request.setServiceFromCache(true);
        }
        clientInfo.onServiceFound(clientRequestId, info, request);
    }

    private void handleDiscoveryManagerServiceLost(ClientInfo clientInfo,
            int clientRequestId, ClientRequest request, NsdServiceInfo info,
            int serviceRemovedReason) {
        clientInfo.onServiceLost(clientRequestId, info, request, serviceRemovedReason);
    }

    private void handleDiscoveryManagerResolveSucceeded(ClientInfo clientInfo,
            int transactionId, int clientRequestId, ClientRequest request,
            NsdServiceInfo info, MdnsEvent event) {
        final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
        info.setPort(serviceInfo.getPort());

        Map<String, String> attrs = serviceInfo.getAttributes();
        for (Map.Entry<String, String> kv : attrs.entrySet()) {
            final String key = kv.getKey();
            try {
                info.setAttribute(key, serviceInfo.getAttributeAsBytes(key));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid attribute", e);
            }
        }
        info.setHostname(getHostname(serviceInfo));
        final List<InetAddress> addresses = getInetAddresses(serviceInfo);
        if (addresses.size() != 0) {
            info.setHostAddresses(addresses);
            request.setServiceFromCache(event.mIsServiceFromCache);
            clientInfo.onResolveServiceSucceeded(clientRequestId, info,
                    serviceInfo.getInterfaceIndex(), request);
        } else {
            // No address. Notify resolution failure.
            clientInfo.onResolveServiceFailed(clientRequestId,
                    NsdManager.FAILURE_INTERNAL_ERROR, false /* isLegacy */,
                    transactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.usingLocalNetworkPermission());
        }

        // Unregister the listener immediately like IMDnsEventListener design
        if (!(request instanceof DiscoveryManagerRequest)) {
            Log.wtf(TAG, "non-DiscoveryManager request in DiscoveryManager event");
            return;
        }
        stopDiscoveryManagerRequest(
                request, clientRequestId, transactionId, clientInfo);
    }

    private void addServiceInfoCallbackAttributes(MdnsServiceInfo mdnsServiceInfo,
            NsdServiceInfo nsdServiceInfo) {
        nsdServiceInfo.setPort(mdnsServiceInfo.getPort());

        Map<String, String> attrs = mdnsServiceInfo.getAttributes();
        for (Map.Entry<String, String> kv : attrs.entrySet()) {
            final String key = kv.getKey();
            try {
                nsdServiceInfo.setAttribute(key, mdnsServiceInfo.getAttributeAsBytes(key));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid attribute", e);
            }
        }

        nsdServiceInfo.setHostname(getHostname(mdnsServiceInfo));
        final List<InetAddress> addresses = getInetAddresses(mdnsServiceInfo);
        nsdServiceInfo.setHostAddresses(addresses);
    }

    private void handleDiscoveryManagerServiceUpdated(ClientInfo clientInfo,
            int clientRequestId, ClientRequest request, NsdServiceInfo nsdServiceInfo,
            MdnsEvent event) {
        final MdnsServiceInfo mdnsServiceInfo = event.mMdnsServiceInfo;
        addServiceInfoCallbackAttributes(mdnsServiceInfo, nsdServiceInfo);
        clientInfo.onServiceUpdated(clientRequestId, nsdServiceInfo,
                mdnsServiceInfo.getInterfaceIndex(), request);
        // Set the ServiceFromCache flag only if the service is actually being
        // retrieved from the cache. This flag should not be overridden by later
        // service updates, which may not be cached.
        if (event.mIsServiceFromCache) {
            request.setServiceFromCache(true);
        }
    }

    private void handleDiscoveryManagerServiceUpdatedLost(ClientInfo clientInfo,
            int clientRequestId, ClientRequest request, NsdServiceInfo info,
            int serviceRemovedReason) {
        clientInfo.onServiceUpdatedLost(clientRequestId, request, info, serviceRemovedReason);
    }

    @NonNull
    private static List<InetAddress> getInetAddresses(@NonNull MdnsServiceInfo serviceInfo) {
        final List<String> v4Addrs = serviceInfo.getIpv4Addresses();
        final List<String> v6Addrs = serviceInfo.getIpv6Addresses();
        final List<InetAddress> addresses = new ArrayList<>(v4Addrs.size() + v6Addrs.size());
        for (String ipv4Address : v4Addrs) {
            try {
                addresses.add(InetAddresses.parseNumericAddress(ipv4Address));
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Invalid ipv4 address", e);
            }
        }
        for (String ipv6Address : v6Addrs) {
            try {
                final Inet6Address addr = (Inet6Address) InetAddresses.parseNumericAddress(
                        ipv6Address);
                addresses.add(InetAddressUtils.withScopeId(addr, serviceInfo.getInterfaceIndex()));
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Invalid ipv6 address", e);
            }
        }
        return addresses;
    }

    @NonNull
    private static String getHostname(@NonNull MdnsServiceInfo serviceInfo) {
        String[] hostname = serviceInfo.getHostName();
        // Strip the "local" top-level domain.
        if (hostname.length >= 2 && hostname[hostname.length - 1].equals("local")) {
            hostname = Arrays.copyOf(hostname, hostname.length - 1);
        }
        return String.join(".", hostname);
    }

    private static void setServiceNetworkForCallback(NsdServiceInfo info, int netId, int ifaceIdx) {
        switch (netId) {
            case NETID_UNSET:
                info.setNetwork(null);
                break;
            case INetd.LOCAL_NET_ID:
                // Special case for LOCAL_NET_ID: Networks on netId 99 are not generally
                // visible / usable for apps, so do not return it. Store the interface
                // index instead, so at least if the client tries to resolve the service
                // with that NsdServiceInfo, it will be done on the same interface.
                // If they recreate the NsdServiceInfo themselves, resolution would be
                // done on all interfaces as before T, which should also work.
                info.setNetwork(null);
                info.setInterfaceIndex(ifaceIdx);
                break;
            default:
                info.setNetwork(new Network(netId));
        }
    }

    // The full service name is escaped from standard DNS rules on mdnsresponder, making it suitable
    // for passing to standard system DNS APIs such as res_query() . Thus, make the service name
    // unescape for getting right service address. See "Notes on DNS Name Escaping" on
    // external/mdnsresponder/mDNSShared/dns_sd.h for more details.
    private String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (++i >= s.length()) {
                    Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                    break;
                }
                c = s.charAt(i);
                if (c != '.' && c != '\\') {
                    if (i + 2 >= s.length()) {
                        Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                        break;
                    }
                    c = (char) ((c - '0') * 100 + (s.charAt(i + 1) - '0') * 10
                            + (s.charAt(i + 2) - '0'));
                    i += 2;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Check the given service type is valid and construct it to a service type
     * which can use for discovery / resolution service.
     *
     * <p>The valid service type should be 2 labels, or 3 labels if the query is for a
     * subtype (see RFC6763 7.1). Each label is up to 63 characters and must start with an
     * underscore; they are alphanumerical characters or dashes or underscore, except the
     * last one that is just alphanumerical. The last label must be _tcp or _udp.
     *
     * <p>The subtypes may also be specified with a comma after the service type, for example
     * _type._tcp,_subtype1,_subtype2
     *
     * @param serviceType the request service type for discovery / resolution service
     * @return constructed service type or null if the given service type is invalid.
     */
    @Nullable
    public static Pair<String, List<String>> parseTypeAndSubtype(String serviceType) {
        if (TextUtils.isEmpty(serviceType)) return null;
        final Pattern serviceTypePattern = Pattern.compile(TYPE_REGEX);
        final Matcher matcher = serviceTypePattern.matcher(serviceType);
        if (!matcher.matches()) return null;
        final String queryType = matcher.group(2);
        // Use the subtype at the beginning
        if (matcher.group(1) != null) {
            return new Pair<>(queryType, List.of(matcher.group(1)));
        }
        // Use the subtypes at the end
        final String subTypesStr = matcher.group(3);
        if (subTypesStr != null && !subTypesStr.isEmpty()) {
            final String[] subTypes = subTypesStr.substring(1).split(",");
            return new Pair<>(queryType, List.of(subTypes));
        }

        return new Pair<>(queryType, Collections.emptyList());
    }

    /**
     * Checks if the hostname is valid.
     *
     * <p>For now NsdService only allows single-label hostnames conforming to RFC 1035. In other
     * words, the hostname should be at most 63 characters long and it only contains letters, digits
     * and hyphens.
     *
     * <p>Additionally, this allows hostname starting with a digit to support Matter devices. Per
     * Matter spec 4.3.1.1:
     *
     * <p>The target host name SHALL be constructed using one of the available link-layer addresses,
     * such as a 48-bit device MAC address (for Ethernet and Wi‑Fi) or a 64-bit MAC Extended Address
     * (for Thread) expressed as a fixed-length twelve-character (or sixteen-character) hexadecimal
     * string, encoded as ASCII (UTF-8) text using capital letters, e.g., B75AFB458ECD.<domain>.
     */
    public static boolean checkHostname(@Nullable String hostname) {
        if (hostname == null) {
            return true;
        }
        String HOSTNAME_REGEX = "^[a-zA-Z0-9]([a-zA-Z0-9-_]{0,61}[a-zA-Z0-9])?$";
        return Pattern.compile(HOSTNAME_REGEX).matcher(hostname).matches();
    }

    /**
     * Checks if the public key is valid.
     *
     * <p>For simplicity, it only checks if the protocol is DNSSEC and the RDATA is not fewer than 4
     * bytes. See RFC 3445 Section 3.
     *
     * <p>Message format: flags (2 bytes), protocol (1 byte), algorithm (1 byte), public key.
     */
    private static boolean checkPublicKey(@Nullable byte[] publicKey) {
        if (publicKey == null) {
            return true;
        }
        if (publicKey.length < 4) {
            return false;
        }
        int protocol = publicKey[2];
        return protocol == DNSSEC_PROTOCOL;
    }

    /** Returns {@code true} if {@code subtype} is a valid DNS-SD subtype label. */
    private static boolean checkSubtypeLabel(String subtype) {
        return Pattern.compile("^" + SUBTYPE_LABEL_REGEX + "$").matcher(subtype).matches();
    }

    @VisibleForTesting
    NsdService(Context ctx, Looper looper, long cleanupDelayMs) {
        this(ctx, looper, cleanupDelayMs, new Dependencies());
    }

    @VisibleForTesting
    NsdService(Context ctx, Looper looper, long cleanupDelayMs, Dependencies deps) {
        mCleanupDelayMs = cleanupDelayMs;
        mContext = ctx;
        mHandler = new NsdHandler(looper);
        mHandler.post(() -> sendNsdStateChangeBroadcast(true));
        // It can fail on V+ device since mdns native service provided by netd is removed.
        mMDnsManager = SdkLevel.isAtLeastV() ? null : ctx.getSystemService(MDnsManager.class);
        mMDnsEventCallback = new MDnsEventCallback(mHandler);
        mDeps = deps;
        mMdnsFeatureFlags = new MdnsFeatureFlags.Builder()
                .setIsMdnsOffloadFeatureEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_FORCE_DISABLE_MDNS_OFFLOAD))
                .setIncludeInetAddressRecordsInProbing(mDeps.isFeatureEnabled(
                        mContext, MdnsFeatureFlags.INCLUDE_INET_ADDRESS_RECORDS_IN_PROBING))
                .setIsExpiredServicesRemovalEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_EXPIRED_SERVICES_REMOVAL))
                .setIsLabelCountLimitEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_LIMIT_LABEL_COUNT))
                .setIsKnownAnswerSuppressionEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_KNOWN_ANSWER_SUPPRESSION))
                .setIsUnicastReplyEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_UNICAST_REPLY_ENABLED))
                .setIsAggressiveQueryModeEnabled(mDeps.isFeatureEnabled(
                        mContext, MdnsFeatureFlags.NSD_AGGRESSIVE_QUERY_MODE))
                .setIsQueryWithKnownAnswerEnabled(mDeps.isAconfigFlagEnabled(
                        Flags.FLAG_NSD_QUERY_WITH_KNOWN_ANSWER))
                // Both accurate_delay_callback and optimized_expired_service_removal features are
                // tied with query_with_known_answer feature.
                .setIsAccurateDelayCallbackEnabled(mDeps.isAconfigFlagEnabled(
                        Flags.FLAG_NSD_QUERY_WITH_KNOWN_ANSWER))
                .setIsOptimizedExpiredServiceRemovalEnabled(mDeps.isAconfigFlagEnabled(
                        Flags.FLAG_NSD_QUERY_WITH_KNOWN_ANSWER))
                .setAvoidAdvertisingEmptyTxtRecords(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_AVOID_ADVERTISING_EMPTY_TXT_RECORDS))
                .setIsCachedServicesRemovalEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_CACHED_SERVICES_REMOVAL))
                .setCachedServicesRetentionTime(mDeps.getDeviceConfigPropertyInt(
                        MdnsFeatureFlags.NSD_CACHED_SERVICES_RETENTION_TIME,
                        MdnsFeatureFlags.DEFAULT_CACHED_SERVICES_RETENTION_TIME_MILLISECONDS))
                .setIsShortHostnamesEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_USE_SHORT_HOSTNAMES))
                .setIsCacheFlushPerAddressTypeEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_CACHE_FLUSH_PER_ADDRESS_TYPE))
                .setIsIgnoreTemporaryIPv6AddressesEnabled(mDeps.isTetheringFeatureNotChickenedOut(
                        mContext, MdnsFeatureFlags.NSD_IGNORE_TEMPORARY_IPV6_ADDRESSES))
                .setIsSelectiveMdnsResponseOffloadEnabled(true)
                // Note that on V+, isChangeEnabled returns false for
                // ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS even if the system UID is targeting
                // higher SDK due to b/401088586.
                // Thus, check compat change against the system UID is needed.
                // If this check is not performed, MdnsSocketProvider may fail to learn
                // local network agent events via network callbacks.
                .setUseNetworkCallbackForLocalNetworksEnabled(
                        mDeps.isSupportTetheringAndP2pGoLocalAgent(mContext)
                                && mDeps.isAconfigFlagEnabled(
                                        Flags.FLAG_NSD_USE_NETWORK_CALLBACK_FOR_LOCAL_NETWORKS)
                                && mDeps.isCompatChangeEnabledForSystem(
                                        ENABLE_MATCH_NON_THREAD_LOCAL_NETWORKS))
                .setIsMdnsScanOffloadEnabled(mDeps.isAconfigFlagEnabled(
                        Flags.FLAG_NSD_MDNS_SCAN_OFFLOAD))
                .setIsDualQueryForUnicastResponseEnabled(mDeps.isAconfigFlagEnabled(
                        Flags.FLAG_NSD_DUAL_QUERY_FOR_UNICAST_RESPONSE))
                .setOverrideProvider(new MdnsFeatureFlags.FlagOverrideProvider() {
                    @Override
                    public boolean isForceEnabledForTest(@NonNull String flag) {
                        return mDeps.isFeatureEnabled(
                                mContext,
                                FORCE_ENABLE_FLAG_FOR_TEST_PREFIX + flag);
                    }

                    @Override
                    public int getIntValueForTest(@NonNull String flag, int defaultValue) {
                        return mDeps.getDeviceConfigPropertyInt(
                                FORCE_ENABLE_FLAG_FOR_TEST_PREFIX + flag, defaultValue);
                    }
                })
                .build();
        mEnablePicker = mDeps.isAconfigFlagEnabled(FLAG_NSD_SERVICE_PICKER);

        mMdnsSocketProvider = deps.makeMdnsSocketProvider(ctx, looper,
                LOGGER.forSubComponent("MdnsSocketProvider"), new SocketRequestMonitor(),
                mMdnsFeatureFlags);
        // Netlink monitor starts on boot, and intentionally never stopped, to ensure that all
        // address events are received. When the netlink monitor starts, any IP addresses already
        // on the interfaces will not be seen. In practice, the network will not connect at boot
        // time As a result, all the netlink message should be observed if the netlink monitor
        // starts here.
        mHandler.post(mMdnsSocketProvider::startNetLinkMonitor);

        // NsdService is started after ActivityManager (startOtherServices in SystemServer, vs.
        // startBootstrapServices).
        mRunningAppActiveImportanceCutoff = mDeps.getDeviceConfigInt(
                MDNS_CONFIG_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF,
                DEFAULT_RUNNING_APP_ACTIVE_IMPORTANCE_CUTOFF);
        final ActivityManager am = ctx.getSystemService(ActivityManager.class);
        am.addOnUidImportanceListener(new UidImportanceListener(mHandler),
                mRunningAppActiveImportanceCutoff);

        final MdnsOffloadCallback offloadCallback = new MdnsOffloadCallback();
        mMdnsSocketClient =
                new MdnsMultinetworkSocketClient(looper, mMdnsSocketProvider,
                        LOGGER.forSubComponent("MdnsMultinetworkSocketClient"), mMdnsFeatureFlags);
        mMdnsDiscoveryManager = deps.makeMdnsDiscoveryManager(new ExecutorProvider(),
                mMdnsSocketClient, LOGGER.forSubComponent("MdnsDiscoveryManager"),
                mMdnsFeatureFlags, offloadCallback);
        mHandler.post(() -> mMdnsSocketClient.setCallback(mMdnsDiscoveryManager));
        mAdvertiser = deps.makeMdnsAdvertiser(looper, mMdnsSocketProvider,
                new AdvertiserCallback(), LOGGER.forSubComponent("MdnsAdvertiser"),
                mMdnsFeatureFlags, mContext, offloadCallback);
        mPermissionManager = Objects.requireNonNull(
                mContext.getSystemService(PermissionManager.class));
        mClock = deps.makeClock();
        mAccessRepository = deps.makeAccessRepository(ctx, looper,
                LOGGER.forSubComponent("MdnsAccess"));
        if (mEnablePicker) {
            mAccessRepository.start();
        }
    }

    /**
     * Dependencies of NsdService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Check whether the MdnsDiscoveryManager feature is enabled.
         *
         * @param context The global context information about an app environment.
         * @return true if the MdnsDiscoveryManager feature is enabled.
         */
        public boolean isMdnsDiscoveryManagerEnabled(Context context) {
            return isAtLeastU() || DeviceConfigUtils.isTetheringFeatureEnabled(context,
                    MDNS_DISCOVERY_MANAGER_VERSION);
        }

        /**
         * Check whether the MdnsAdvertiser feature is enabled.
         *
         * @param context The global context information about an app environment.
         * @return true if the MdnsAdvertiser feature is enabled.
         */
        public boolean isMdnsAdvertiserEnabled(Context context) {
            return isAtLeastU() || DeviceConfigUtils.isTetheringFeatureEnabled(context,
                    MDNS_ADVERTISER_VERSION);
        }

        /**
         * Get the type allowlist flag value.
         * @see #MDNS_TYPE_ALLOWLIST_FLAGS
         */
        @Nullable
        public String getTypeAllowlistFlags() {
            return DeviceConfigUtils.getDeviceConfigProperty(NAMESPACE_TETHERING,
                    MDNS_TYPE_ALLOWLIST_FLAGS, null);
        }

        /**
         * @see DeviceConfigUtils#isTetheringFeatureEnabled
         */
        public boolean isFeatureEnabled(Context context, String feature) {
            return DeviceConfigUtils.isTetheringFeatureEnabled(context, feature);
        }

        /**
         * @see DeviceConfigUtils#isTetheringFeatureNotChickenedOut
         */
        public boolean isTetheringFeatureNotChickenedOut(Context context, String feature) {
            return DeviceConfigUtils.isTetheringFeatureNotChickenedOut(context, feature);
        }

        /**
         * @see CompatChanges#isChangeEnabled(long, int)
         */
        public boolean isCompatChangeEnabledForSystem(long changeId) {
            return CompatChanges.isChangeEnabled(changeId, Process.SYSTEM_UID);
        }

        /** Get whether tethering and P2P GO local agent is enabled. */
        public boolean isSupportTetheringAndP2pGoLocalAgent(Context context) {
            // Determines support for the Tethering/P2P GO local network agent. This feature is
            // gated on Android V+ because it requires NET_CAPABILITY_LOCAL_NETWORK.
            // For 25Q4+, it is enabled by default with a kill switch to prevent impacting
            // existing devices if issues arise. For older V+ devices, rollout is controlled
            // by a mainline beta flag.

            // This flag is also used by IpServer. Because the flag value is read during service
            // construction and cached, the service will continue to use the old value even if the
            // aconfig flag is changed on a running device. The service would need to be restarted
            // to pick up the new value, and a reboot is the most common way for that to happen.
            // If the flag's value changed between IpServer and NsdService initializations, their
            // flags may be out-of-sync since they read the value separately.
            // a. IpServer(false), NsdService(true): NsdService expects the tethering event from
            //    the network callback, but IpServer does not send it. Thus, NsdService breaks on
            //    downstream interfaces.
            // b. IpServer(true), NsdService(false): NsdService learns downstream events from
            //    the tethering callback, so nothing breaks.
            return SdkLevel.isAtLeastV()
                    && (SdkUtil.isAtLeast25Q4()
                    ? isTetheringFeatureNotChickenedOut(context,
                            TETHERING_AND_P2P_GO_LOCAL_AGENT)
                    : isAconfigFlagEnabled(com.android.tethering.mainline.beta.Flags
                            .FLAG_TETHERING_AND_P2P_GO_LOCAL_AGENT));
        }

        /** Get whether a feature config is enabled. */
        public boolean isAconfigFlagEnabled(String feature) {
            return switch (feature) {
                case Flags.FLAG_NSD_QUERY_WITH_KNOWN_ANSWER -> Flags.nsdQueryWithKnownAnswer();
                case Flags.FLAG_NSD_USE_NETWORK_CALLBACK_FOR_LOCAL_NETWORKS ->
                        Flags.nsdUseNetworkCallbackForLocalNetworks();
                case Flags.FLAG_NSD_MDNS_SCAN_OFFLOAD -> Flags.nsdMdnsScanOffload();
                case FLAG_NSD_SERVICE_PICKER -> Flags.nsdServicePicker();
                case com.android.tethering.mainline.beta.Flags
                        .FLAG_TETHERING_AND_P2P_GO_LOCAL_AGENT ->
                        com.android.tethering.mainline.beta.Flags.tetheringAndP2pGoLocalAgent();
                case Flags.FLAG_NSD_DUAL_QUERY_FOR_UNICAST_RESPONSE ->
                        Flags.nsdDualQueryForUnicastResponse();
                default -> throw new IllegalStateException("Unknown flag " + feature);
            };
        }

        /**
         * @see DeviceConfigUtils#getDeviceConfigPropertyInt
         */
        public int getDeviceConfigPropertyInt(String feature, int defaultValue) {
            return DeviceConfigUtils.getDeviceConfigPropertyInt(
                    NAMESPACE_TETHERING, feature, defaultValue);
        }

        /**
         * @see DeviceConfigUtils#getConnectivityResourcesPackageName(Context)
         */
        public String getConnectivityResourcesPackageName(Context context) {
            return DeviceConfigUtils.getConnectivityResourcesPackageName(context);
        }

        /**
         * @see MdnsDiscoveryManager
         */
        public MdnsDiscoveryManager makeMdnsDiscoveryManager(
                @NonNull ExecutorProvider executorProvider,
                @NonNull MdnsMultinetworkSocketClient socketClient, @NonNull SharedLog sharedLog,
                @NonNull MdnsFeatureFlags featureFlags, @NonNull OffloadCallback cb) {
            return new MdnsDiscoveryManager(
                    executorProvider, socketClient, sharedLog, featureFlags, cb);
        }

        /**
         * @see MdnsAdvertiser
         */
        public MdnsAdvertiser makeMdnsAdvertiser(
                @NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
                @NonNull MdnsAdvertiser.AdvertiserCallback cb, @NonNull SharedLog sharedLog,
                MdnsFeatureFlags featureFlags, Context context, @NonNull OffloadCallback offloadCb
        ) {
            return new MdnsAdvertiser(
                    looper, socketProvider, cb, sharedLog, featureFlags, context, offloadCb);
        }

        /**
         * @see MdnsSocketProvider
         */
        public MdnsSocketProvider makeMdnsSocketProvider(@NonNull Context context,
                @NonNull Looper looper, @NonNull SharedLog sharedLog,
                @NonNull MdnsSocketProvider.SocketRequestMonitor socketCreationCallback,
                @NonNull MdnsFeatureFlags featureFlags) {
            return new MdnsSocketProvider(context, looper, sharedLog, socketCreationCallback,
                    featureFlags);
        }

        /**
         * @see DeviceConfig#getInt(String, String, int)
         */
        public int getDeviceConfigInt(@NonNull String config, int defaultValue) {
            return DeviceConfig.getInt(NAMESPACE_TETHERING, config, defaultValue);
        }

        /**
         * @see Binder#getCallingUid()
         */
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        /**
         * @see Binder#getCallingPid()
         */
        public int getCallingPid() {
            return Binder.getCallingPid();
        }

        /**
         * @see CompatChanges#isChangeEnabled(long, int)
         */
        public boolean isPickerAutoUpgradeEnabled(int uid) {
            return CompatChanges.isChangeEnabled(USE_NSD_PICKER_WHEN_NO_LOCAL_NET_PERMISSION, uid);
        }

        /**
         * @see NetworkNsdReportedMetrics
         */
        public NetworkNsdReportedMetrics makeNetworkNsdReportedMetrics(int clientId, int uid) {
            return new NetworkNsdReportedMetrics(clientId, uid);
        }

        /**
         * @see MdnsUtils.Clock
         */
        public Clock makeClock() {
            return new Clock();
        }

        /**
         * @see ServiceAccessRepository
         */
        public ServiceAccessRepository makeAccessRepository(@NonNull Context context,
                @NonNull Looper looper, @NonNull SharedLog sharedLog) {
            return new ServiceAccessRepository(context, looper, sharedLog);
        }

        /**
         * @see MdnsInterfaceSocket#getInterface()
         */
        public String getSocketInterfaceName(@NonNull MdnsInterfaceSocket socket) {
            return socket.getInterface().getName();
        }
    }

    /**
     * Return whether a type is allowlisted to use the Java backend.
     * @param type The service type
     * @param flagPrefix One of {@link #MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX} or
     *                   {@link #MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX}.
     */
    private boolean isTypeAllowlistedForJavaBackend(@Nullable String type,
            @NonNull String flagPrefix) {
        if (type == null) return false;
        final String typesConfig = mDeps.getTypeAllowlistFlags();
        if (TextUtils.isEmpty(typesConfig)) return false;

        final String mappingPrefix = type + ":";
        String mappedFlag = null;
        for (String mapping : TextUtils.split(typesConfig, ",")) {
            if (mapping.startsWith(mappingPrefix)) {
                mappedFlag = mapping.substring(mappingPrefix.length());
                break;
            }
        }

        if (mappedFlag == null) return false;

        return mDeps.isFeatureEnabled(mContext,
                flagPrefix + mappedFlag + MDNS_ALLOWLIST_FLAG_SUFFIX);
    }

    private boolean useDiscoveryManagerForType(@Nullable String type) {
        return isTypeAllowlistedForJavaBackend(type, MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX);
    }

    private boolean useDiscoveryManager(@NonNull ClientInfo clientInfo, @Nullable String type) {
        return clientInfo.mUseJavaBackend
                || mDeps.isMdnsDiscoveryManagerEnabled(mContext)
                || useDiscoveryManagerForType(type);
    }

    private boolean useAdvertiserForType(@Nullable String type) {
        return isTypeAllowlistedForJavaBackend(type, MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX);
    }

    public static NsdService create(Context context) {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        NsdService service = new NsdService(context, thread.getLooper(), CLEANUP_DELAY_MS);
        return service;
    }

    private static class MDnsEventCallback extends IMDnsEventListener.Stub {
        private final NsdHandler mHandler;

        MDnsEventCallback(NsdHandler handler) {
            mHandler = handler;
        }

        @Override
        public void onServiceRegistrationStatus(final RegistrationInfo status) {
            mHandler.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onServiceDiscoveryStatus(final DiscoveryInfo status) {
            mHandler.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onServiceResolutionStatus(final ResolutionInfo status) {
            mHandler.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onGettingServiceAddressStatus(final GetAddressInfo status) {
            mHandler.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return this.HASH;
        }
    }

    private void sendAllOffloadServiceInfos(@NonNull OffloadEngineInfo offloadEngineInfo) {
        final String targetInterface = offloadEngineInfo.mInterfaceName;
        final IOffloadEngine offloadEngine = offloadEngineInfo.mOffloadEngine;
        final List<MdnsAdvertiser.OffloadServiceInfoWrapper> offloadWrappers =
                mAdvertiser.notifyOffloadStart(targetInterface);
        for (MdnsAdvertiser.OffloadServiceInfoWrapper wrapper : offloadWrappers) {
            try {
                if (nsdMdnsScanOffload()) {
                    long updatedOffloadType = offloadEngineInfo.mOffloadType
                            & wrapper.mOffloadServiceInfo.getOffloadType();
                    if (updatedOffloadType != 0) {
                        OffloadServiceInfo updatedOffloadServiceInfo =
                                wrapper.mOffloadServiceInfo.withOffloadType(updatedOffloadType);
                        offloadEngine.onOffloadServiceUpdated(updatedOffloadServiceInfo);
                    }
                } else {
                    offloadEngine.onOffloadServiceUpdated(wrapper.mOffloadServiceInfo);
                }

            } catch (RemoteException e) {
                // Can happen in regular cases, do not log a stacktrace
                Log.i(TAG, "Failed to send offload callback, remote died: " + e.getMessage());
            }
        }

        // Check if the engine supports offload type for offloaded discovery
        if ((offloadEngineInfo.mOffloadType
                & DiscoveryOffloadInfo.OFFLOAD_TYPE) != 0) {
            final List<DiscoveryOffloadInfo> discoveryOffloadInfo =
                    mMdnsDiscoveryManager.notifyOffloadStart(targetInterface);
            for (DiscoveryOffloadInfo info : discoveryOffloadInfo) {
                try {
                    if (nsdMdnsScanOffload()) {
                        offloadEngine.onOffloadServiceUpdated(
                                createOffloadServiceInfoFromDiscoveryOffload(
                                        info,
                                        offloadEngineInfo.mOffloadType
                                                & DiscoveryOffloadInfo.OFFLOAD_TYPE
                                )
                        );
                    } else {
                        offloadEngine.onOffloadServiceUpdated(
                                createOffloadServiceInfoFromDiscoveryOffload(
                                        info,
                                        DiscoveryOffloadInfo.OFFLOAD_TYPE
                                )
                        );
                    }
                } catch (RemoteException e) {
                    // Can happen in regular cases, do not log a stacktrace
                    Log.i(TAG, "Failed to send offload callback, remote died: " + e.getMessage());
                }
            }
        }
    }

    private void sendOffloadServiceInfosUpdate(@NonNull String targetInterfaceName,
            @NonNull OffloadServiceInfo offloadServiceInfo, boolean isRemove) {
        final int count = mOffloadEngines.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                final OffloadEngineInfo offloadEngineInfo =
                        (OffloadEngineInfo) mOffloadEngines.getBroadcastCookie(i);
                final String interfaceName = offloadEngineInfo.mInterfaceName;
                if (!targetInterfaceName.equals(interfaceName)
                        || ((offloadEngineInfo.mOffloadType
                        & offloadServiceInfo.getOffloadType()) == 0)) {
                    continue;
                }
                OffloadServiceInfo updatedOffloadServiceInfo;
                if (nsdMdnsScanOffload()) {
                    updatedOffloadServiceInfo =
                            offloadServiceInfo.withOffloadType(offloadEngineInfo.mOffloadType
                                    & offloadServiceInfo.getOffloadType());
                } else {
                    updatedOffloadServiceInfo = offloadServiceInfo;
                }
                try {
                    if (isRemove) {
                        mOffloadEngines.getBroadcastItem(i).onOffloadServiceRemoved(
                                updatedOffloadServiceInfo);
                    } else {
                        mOffloadEngines.getBroadcastItem(i).onOffloadServiceUpdated(
                                updatedOffloadServiceInfo);
                    }
                } catch (RemoteException e) {
                    // Can happen in regular cases, do not log a stacktrace
                    Log.i(TAG, "Failed to send offload callback, remote died: " + e.getMessage());
                }
            }
        } finally {
            mOffloadEngines.finishBroadcast();
        }
    }

    private class AdvertiserCallback implements MdnsAdvertiser.AdvertiserCallback {
        // TODO: add a callback to notify when a service is being added on each interface (as soon
        // as probing starts), and call mOffloadCallbacks. This callback is for
        // OFFLOAD_CAPABILITY_FILTER_REPLIES offload type.

        @Override
        public void onRegisterServiceSucceeded(int transactionId, NsdServiceInfo registeredInfo) {
            mServiceLogs.log("onRegisterServiceSucceeded: transactionId " + transactionId);
            final ClientInfo clientInfo = getClientInfoOrLog(transactionId);
            if (clientInfo == null) return;

            final int clientRequestId = getClientRequestIdOrLog(clientInfo, transactionId);
            if (clientRequestId < 0) return;

            // onRegisterServiceSucceeded only has the service name in its info. This aligns with
            // historical behavior. The host name field was added in Android B.
            final NsdServiceInfo cbInfo = new NsdServiceInfo(registeredInfo.getServiceName(), null);
            cbInfo.setHostname(registeredInfo.getHostname());
            final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
            clientInfo.onRegisterServiceSucceeded(clientRequestId, cbInfo, request);
        }

        @Override
        public void onRegisterServiceFailed(int transactionId, int errorCode) {
            final ClientInfo clientInfo = getClientInfoOrLog(transactionId);
            if (clientInfo == null) return;

            final int clientRequestId = getClientRequestIdOrLog(clientInfo, transactionId);
            if (clientRequestId < 0) return;
            final ClientRequest request = clientInfo.mClientRequests.get(clientRequestId);
            clientInfo.onRegisterServiceFailed(clientRequestId, errorCode, false /* isLegacy */,
                    transactionId, request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.usingLocalNetworkPermission());
        }

        private ClientInfo getClientInfoOrLog(int transactionId) {
            final ClientInfo clientInfo = mTransactionIdToClientInfoMap.get(transactionId);
            if (clientInfo == null) {
                Log.e(TAG, String.format("Callback for service %d has no client", transactionId));
            }
            return clientInfo;
        }

        private int getClientRequestIdOrLog(@NonNull ClientInfo info, int transactionId) {
            final int clientRequestId = info.getClientRequestId(transactionId);
            if (clientRequestId < 0) {
                Log.e(TAG, String.format(
                        "Client request ID not found for service %d", transactionId));
            }
            return clientRequestId;
        }
    }

    private class MdnsOffloadCallback implements OffloadCallback {
        @Override
        public void onOffloadStartOrUpdate(@NonNull String interfaceName,
                @NonNull OffloadServiceInfo offloadServiceInfo) {
            mHandler.sendMessage(
                    mHandler.obtainMessage(
                            NsdManager.OFFLOAD_ENGINE_SERVICE_INFO_UPDATE,
                            new OffloadServiceInfoUpdateArgs(
                                    interfaceName,
                                    offloadServiceInfo,
                                    false
                            )
                    )
            );
        }

        @Override
        public void onOffloadStop(@NonNull String interfaceName,
                @NonNull OffloadServiceInfo offloadServiceInfo) {
            mHandler.sendMessage(
                    mHandler.obtainMessage(
                            NsdManager.OFFLOAD_ENGINE_SERVICE_INFO_UPDATE,
                            new OffloadServiceInfoUpdateArgs(
                                    interfaceName,
                                    offloadServiceInfo,
                                    true
                            )
                    )
            );
        }
    }

    private static class ConnectorArgs {
        @NonNull public final NsdServiceConnector connector;
        @NonNull public final INsdManagerCallback callback;
        public final boolean useJavaBackend;
        public final int uid;
        public final int pid;
        @NonNull public final String packageName;

        ConnectorArgs(@NonNull NsdServiceConnector connector, @NonNull INsdManagerCallback callback,
                boolean useJavaBackend, int uid, int pid, @NonNull String packageName) {
            this.connector = connector;
            this.callback = callback;
            this.useJavaBackend = useJavaBackend;
            this.uid = uid;
            this.pid = pid;
            this.packageName = packageName;
        }
    }

    @Override
    public INsdServiceConnector connect(INsdManagerCallback cb, boolean useJavaBackend,
            String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET, "NsdService");
        final int uid = mDeps.getCallingUid();
        final int pid = mDeps.getCallingPid();
        enforcePackageNameMatchesUid(mContext, uid, packageName);
        if (cb == null) {
            throw new IllegalArgumentException("Unknown client callback from uid=" + uid);
        }
        // Unfortunately we have to allow system apps to use this service even when under a lockdown
        // VPN, otherwise would break things.
        if (ConnectivityUtil.isRegularAppWithLockdownVpnEnabled(mContext, uid)) {
            Log.d(TAG, "connect(): caller (uid " + uid + ") is under VPN lockdown, returning null");
            return null;
        }
        if (DBG) Log.d(TAG, "New client connect. useJavaBackend=" + useJavaBackend);
        final INsdServiceConnector connector = new NsdServiceConnector();
        mHandler.sendMessage(mHandler.obtainMessage(NsdManager.REGISTER_CLIENT,
                new ConnectorArgs((NsdServiceConnector) connector, cb, useJavaBackend, uid, pid,
                        packageName)));
        return connector;
    }

    private static class ListenerArgs {
        public final NsdServiceConnector connector;
        public final NsdServiceInfo serviceInfo;
        ListenerArgs(NsdServiceConnector connector, NsdServiceInfo serviceInfo) {
            this.connector = connector;
            this.serviceInfo = serviceInfo;
        }
    }

    private static class ProxyOffloadEngineResponse {
        public final NsdServiceInfo serviceInfo;
        public final boolean isServiceLost;
        public final String interfaceName;

        ProxyOffloadEngineResponse(NsdServiceInfo serviceInfo,
                boolean isServiceLost, String interfaceName) {
            this.serviceInfo = serviceInfo;
            this.isServiceLost = isServiceLost;
            this.interfaceName = interfaceName;
        }
    }

    private static class AdvertisingArgs {
        public final NsdServiceConnector connector;
        public final AdvertisingRequest advertisingRequest;

        AdvertisingArgs(NsdServiceConnector connector, AdvertisingRequest advertisingRequest) {
            this.connector = connector;
            this.advertisingRequest = advertisingRequest;
        }
    }

    private static final class DiscoveryArgs {
        public final NsdServiceConnector connector;
        public final DiscoveryRequest discoveryRequest;
        DiscoveryArgs(NsdServiceConnector connector, DiscoveryRequest discoveryRequest) {
            this.connector = connector;
            this.discoveryRequest = discoveryRequest;
        }
    }

    private static final class CheckPermissionArgs {
        @NonNull
        final NsdServiceConnector mConnector;
        @NonNull
        final String mServiceName;
        @NonNull
        final String mServiceType;
        @NonNull
        final ResultReceiver mResultReceiver;
        CheckPermissionArgs(@NonNull NsdServiceConnector connector, String serviceName,
                @NonNull String serviceType, @NonNull ResultReceiver resultReceiver) {
            this.mConnector = connector;
            this.mServiceName = serviceName;
            this.mServiceType = serviceType;
            this.mResultReceiver = resultReceiver;
        }
    }

    private static final class OffloadServiceInfoUpdateArgs {
        @NonNull
        final String mInterfaceName;
        @NonNull
        final OffloadServiceInfo mOffloadServiceInfo;
        final boolean mIsRemove;

        OffloadServiceInfoUpdateArgs(
                @NonNull String interfaceName,
                @NonNull OffloadServiceInfo serviceInfo,
                boolean isRemove
        ) {
            this.mInterfaceName = interfaceName;
            this.mOffloadServiceInfo = serviceInfo;
            this.mIsRemove = isRemove;
        }
    }

    @Nullable
    private static AttributionSource getAttributionSource(int uid, int pid) {
        // AttributionSource builder method setPid() introduced in U, but check for 25Q2 here to
        // consolidate SDK level checks, since attribution source is only used for permission checks
        // introduced in 25Q2 and later.
        if (isAtLeastB()) {
            return new AttributionSource.Builder(uid).setPid(pid).build();
        }
        return null;
    }

    private class NsdServiceConnector extends INsdServiceConnector.Stub
            implements IBinder.DeathRecipient  {

        @Override
        public void registerService(int listenerKey, AdvertisingRequest advertisingRequest)
                throws RemoteException {
            int status = checkDataDeliveryPermissions(getCallingUid(), getCallingPid());
            if (status != PERMISSION_GRANTED) {
                throw new SecurityException("Missing local network permission");
            }
            NsdManager.checkServiceInfoForRegistration(advertisingRequest.getServiceInfo());
            mHandler.sendMessage(
                    NsdManager.REGISTER_SERVICE, /* arg1= */0, listenerKey,
                    new AdvertisingArgs(this, advertisingRequest)
            );
        }

        @Override
        public void unregisterService(int listenerKey) {
            mHandler.sendMessage(
                    NsdManager.UNREGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, (NsdServiceInfo) null));
        }

        @Override
        public void discoverServices(int listenerKey, DiscoveryRequest discoveryRequest) {
            mHandler.sendMessage(
                    NsdManager.DISCOVER_SERVICES, 0, listenerKey,
                    new DiscoveryArgs(this, discoveryRequest));
        }

        @Override
        public void stopDiscovery(int listenerKey) {
            mHandler.sendMessage(NsdManager.STOP_DISCOVERY,
                    0, listenerKey, new ListenerArgs(this, (NsdServiceInfo) null));
        }

        @Override
        public void resolveService(int listenerKey, NsdServiceInfo serviceInfo) {
            mHandler.sendMessage(
                    NsdManager.RESOLVE_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo));
        }

        @Override
        public void stopResolution(int listenerKey) {
            mHandler.sendMessage(NsdManager.STOP_RESOLUTION,
                    0, listenerKey, new ListenerArgs(this, (NsdServiceInfo) null));
        }

        @Override
        public void registerServiceInfoCallback(int listenerKey, NsdServiceInfo serviceInfo) {
            mHandler.sendMessage(
                    NsdManager.REGISTER_SERVICE_CALLBACK, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo));
        }

        @Override
        public void registerServiceInfoCallbackWithRequest(int listenerKey,
                DiscoveryRequest request) {
            mHandler.sendMessage(
                    NsdManager.DISCOVER_SERVICES, ARG_IS_SERVICE_INFO_CALLBACK, listenerKey,
                    new DiscoveryArgs(this, request));
        }

        @Override
        public void unregisterServiceInfoCallback(int listenerKey) {
            mHandler.sendMessage(
                    NsdManager.UNREGISTER_SERVICE_CALLBACK, 0, listenerKey,
                    new ListenerArgs(this, (NsdServiceInfo) null));
        }

        @Override
        public void startDaemon() {
            mHandler.sendMessage(mHandler.obtainMessage(NsdManager.DAEMON_STARTUP,
                    new ListenerArgs(this, (NsdServiceInfo) null)));
        }

        @Override
        public void binderDied() {
            mHandler.sendMessage(mHandler.obtainMessage(NsdManager.UNREGISTER_CLIENT, this));

        }

        @Override
        public void registerOffloadEngine(String ifaceName, IOffloadEngine cb,
                @OffloadEngine.OffloadCapability long offloadCapabilities,
                @OffloadEngine.OffloadType long offloadTypes) {
            checkOffloadEnginePermission(mContext);
            Objects.requireNonNull(ifaceName);
            Objects.requireNonNull(cb);
            mHandler.sendMessage(
                    mHandler.obtainMessage(NsdManager.REGISTER_OFFLOAD_ENGINE,
                            new OffloadEngineInfo(cb, ifaceName, offloadCapabilities,
                                    offloadTypes, this)));
        }

        @Override
        public void unregisterOffloadEngine(IOffloadEngine cb) {
            checkOffloadEnginePermission(mContext);
            Objects.requireNonNull(cb);
            mHandler.sendMessage(mHandler.obtainMessage(NsdManager.UNREGISTER_OFFLOAD_ENGINE, cb));
        }

        @Override
        public void injectOffloadEngineResponse(NsdServiceInfo serviceInfo,
                boolean isServiceLost, String ifaceName) {
            checkOffloadEnginePermission(mContext);
            mHandler.sendMessage(
                    mHandler.obtainMessage(NsdManager.INJECT_PROXY_OFFLOAD_ENGINE_RESPONSE,
                            new ProxyOffloadEngineResponse(serviceInfo, isServiceLost, ifaceName)));
        }

        @Override
        public void checkPermissionForService(String serviceName, String serviceType,
                ResultReceiver resultReceiver) {
            Objects.requireNonNull(serviceName);
            Objects.requireNonNull(serviceType);
            Objects.requireNonNull(resultReceiver);
            mHandler.sendMessage(mHandler.obtainMessage(NsdManager.CHECK_PERMISSION_FOR_SERVICE,
                    new CheckPermissionArgs(this, serviceName, serviceType, resultReceiver)));
        }

        private static void checkOffloadEnginePermission(Context context) {
            if (!SdkLevel.isAtLeastT()) {
                throw new SecurityException("API is not available in before API level 33");
            }

            final ArrayList<String> permissionsList = new ArrayList<>(Arrays.asList(NETWORK_STACK,
                    PERMISSION_MAINLINE_NETWORK_STACK, NETWORK_SETTINGS));

            if (SdkLevel.isAtLeastV()) {
                // REGISTER_NSD_OFFLOAD_ENGINE was only added to the SDK in V.
                permissionsList.add(REGISTER_NSD_OFFLOAD_ENGINE);
            } else if (SdkLevel.isAtLeastU()) {
                // REGISTER_NSD_OFFLOAD_ENGINE cannot be backport to U. In U, check the DEVICE_POWER
                // permission instead.
                permissionsList.add(DEVICE_POWER);
            }

            if (PermissionUtils.hasAnyPermissionOf(context,
                    permissionsList.toArray(new String[0]))) {
                return;
            }
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", permissionsList) + ".");
        }
    }

    private void sendNsdStateChangeBroadcast(boolean isEnabled) {
        final Intent intent = new Intent(NsdManager.ACTION_NSD_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        int nsdState = isEnabled ? NsdManager.NSD_STATE_ENABLED : NsdManager.NSD_STATE_DISABLED;
        intent.putExtra(NsdManager.EXTRA_NSD_STATE, nsdState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int getUniqueId() {
        if (++mUniqueId == INVALID_ID) return ++mUniqueId;
        return mUniqueId;
    }

    private boolean registerService(int transactionId, NsdServiceInfo service) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "registerService: mMDnsManager is null");
            return false;
        }

        if (DBG) {
            Log.d(TAG, "registerService: " + transactionId + " " + service);
        }
        String name = service.getServiceName();
        String type = service.getServiceType();
        int port = service.getPort();
        byte[] textRecord = service.getTxtRecord();
        final int registerInterface = getNetworkInterfaceIndex(service);
        if (service.getNetwork() != null && registerInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to register service on not found");
            return false;
        }
        return mMDnsManager.registerService(
                transactionId, name, type, port, textRecord, registerInterface);
    }

    private boolean unregisterService(int transactionId) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "unregisterService: mMDnsManager is null");
            return false;
        }
        return mMDnsManager.stopOperation(transactionId);
    }

    private boolean discoverServices(int transactionId, DiscoveryRequest discoveryRequest) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "discoverServices: mMDnsManager is null");
            return false;
        }

        final String type = discoveryRequest.getServiceType();
        final int discoverInterface = getNetworkInterfaceIndex(discoveryRequest);
        if (discoveryRequest.getNetwork() != null && discoverInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to discover service on not found");
            return false;
        }
        return mMDnsManager.discover(transactionId, type, discoverInterface);
    }

    private boolean stopServiceDiscovery(int transactionId) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "stopServiceDiscovery: mMDnsManager is null");
            return false;
        }
        return mMDnsManager.stopOperation(transactionId);
    }

    private boolean resolveService(int transactionId, NsdServiceInfo service) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "resolveService: mMDnsManager is null");
            return false;
        }
        final String name = service.getServiceName();
        final String type = service.getServiceType();
        final int resolveInterface = getNetworkInterfaceIndex(service);
        if (service.getNetwork() != null && resolveInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to resolve service on not found");
            return false;
        }
        return mMDnsManager.resolve(transactionId, name, type, "local.", resolveInterface);
    }

    /**
     * Guess the interface to use to resolve or discover a service on a specific network.
     *
     * This is an imperfect guess, as for example the network may be gone or not yet fully
     * registered. This is fine as failing is correct if the network is gone, and a client
     * attempting to resolve/discover on a network not yet setup would have a bad time anyway; also
     * this is to support the legacy mdnsresponder implementation, which historically resolved
     * services on an unspecified network.
     */
    private int getNetworkInterfaceIndex(NsdServiceInfo serviceInfo) {
        final Network network = serviceInfo.getNetwork();
        if (network == null) {
            // Fallback to getInterfaceIndex if present (typically if the NsdServiceInfo was
            // provided by NsdService from discovery results, and the service was found on an
            // interface that has no app-usable Network).
            if (serviceInfo.getInterfaceIndex() != 0) {
                return serviceInfo.getInterfaceIndex();
            }
            return IFACE_IDX_ANY;
        }
        return getNetworkInterfaceIndex(network);
    }

    /**
     * Returns the interface to use to discover a service on a specific network, or {@link
     * IFACE_IDX_ANY} if no network is specified.
     */
    private int getNetworkInterfaceIndex(DiscoveryRequest discoveryRequest) {
        final Network network = discoveryRequest.getNetwork();
        if (network == null) {
            return IFACE_IDX_ANY;
        }
        return getNetworkInterfaceIndex(network);
    }

    /**
     * Returns the interface of a specific network, or {@link IFACE_IDX_ANY} if no interface is
     * associated with {@code network}.
     */
    private int getNetworkInterfaceIndex(@NonNull Network network) {
        String interfaceName = getNetworkInterfaceName(network);
        if (interfaceName == null) {
            return IFACE_IDX_ANY;
        }
        return getNetworkInterfaceIndexByName(interfaceName);
    }

    private String getNetworkInterfaceName(@Nullable Network network) {
        if (network == null) {
            return null;
        }
        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Log.wtf(TAG, "No ConnectivityManager");
            return null;
        }
        final LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) {
            return null;
        }
        // Only resolve on non-stacked interfaces
        return lp.getInterfaceName();
    }

    private int getNetworkInterfaceIndexByName(final String ifaceName) {
        final NetworkInterface iface;
        try {
            iface = NetworkInterface.getByName(ifaceName);
        } catch (SocketException e) {
            Log.e(TAG, "Error querying interface", e);
            return IFACE_IDX_ANY;
        }

        if (iface == null) {
            Log.e(TAG, "Interface not found: " + ifaceName);
            return IFACE_IDX_ANY;
        }

        return iface.getIndex();
    }

    private boolean stopResolveService(int transactionId) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "stopResolveService: mMDnsManager is null");
            return false;
        }
        return mMDnsManager.stopOperation(transactionId);
    }

    private boolean getAddrInfo(int transactionId, String hostname, int interfaceIdx) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "getAddrInfo: mMDnsManager is null");
            return false;
        }
        return mMDnsManager.getServiceAddress(transactionId, hostname, interfaceIdx);
    }

    private boolean stopGetAddrInfo(int transactionId) {
        if (mMDnsManager == null) {
            Log.wtf(TAG, "stopGetAddrInfo: mMDnsManager is null");
            return false;
        }
        return mMDnsManager.stopOperation(transactionId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!PermissionUtils.hasDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        // Dump clients
        pw.println("Active clients:");
        pw.increaseIndent();
        HandlerUtils.runWithScissorsForDump(mHandler, () -> {
            for (ClientInfo clientInfo : mClients.values()) {
                pw.println(clientInfo.toString());
            }
        }, 10_000);
        pw.decreaseIndent();

        // Dump service and clients logs
        pw.println();
        pw.println("Logs:");
        pw.increaseIndent();
        mServiceLogs.reverseDump(pw);
        pw.decreaseIndent();

        //Dump DiscoveryManager
        pw.println();
        pw.println("DiscoveryManager:");
        pw.increaseIndent();
        HandlerUtils.runWithScissorsForDump(mHandler, () -> mMdnsDiscoveryManager.dump(pw), 10_000);
        pw.decreaseIndent();

        if (mDeps.isAconfigFlagEnabled(FLAG_NSD_SERVICE_PICKER)) {
            pw.println("ServiceAccessRepository:");
            pw.increaseIndent();
            HandlerUtils.runWithScissorsForDump(mHandler, () -> mAccessRepository.dump(pw), 10_000);
            pw.decreaseIndent();
        }
    }

    private abstract static class ClientRequest {
        private final int mTransactionId;
        private final long mStartTimeMs;
        private int mFoundServiceCount = 0;
        private int mLostServiceCount = 0;
        private final Set<String> mServices = new ArraySet<>();
        private boolean mIsServiceFromCache = false;
        private int mSentQueryCount = NO_SENT_QUERY_COUNT;
        private int mCachedServiceExpiredCount = 0;
        boolean mUsingPermissionExemption;

        private ClientRequest(int transactionId, long startTimeMs,
                boolean usingPermissionExemption) {
            mTransactionId = transactionId;
            mStartTimeMs = startTimeMs;
            mUsingPermissionExemption = usingPermissionExemption;
        }

        public long calculateRequestDurationMs(long stopTimeMs) {
            return stopTimeMs - mStartTimeMs;
        }

        public void onServiceFound(String serviceName) {
            mFoundServiceCount++;
            if (mServices.size() <= MAX_SERVICES_COUNT_METRIC_PER_CLIENT) {
                mServices.add(serviceName);
            }
        }

        void onServiceLost(int serviceRemovedReason) {
            mLostServiceCount++;
            if (serviceRemovedReason == SERVICE_REMOVED_BY_TTL_EXPIRED) {
                mCachedServiceExpiredCount++;
            }
        }

        public int getFoundServiceCount() {
            return mFoundServiceCount;
        }

        public int getLostServiceCount() {
            return mLostServiceCount;
        }

        public int getServicesCount() {
            return mServices.size();
        }

        public void setServiceFromCache(boolean isServiceFromCache) {
            mIsServiceFromCache = isServiceFromCache;
        }

        public boolean isServiceFromCache() {
            return mIsServiceFromCache;
        }

        public void onQuerySent() {
            mSentQueryCount++;
        }

        public int getSentQueryCount() {
            return mSentQueryCount;
        }

        int getCachedServiceExpiredCount() {
            return mCachedServiceExpiredCount;
        }

        @NonNull
        @Override
        public String toString() {
            return getRequestDescriptor() + " {" + mTransactionId
                    + ", startTime " + mStartTimeMs
                    + ", foundServices " + mFoundServiceCount
                    + ", lostServices " + mLostServiceCount
                    + ", fromCache " + mIsServiceFromCache
                    + ", sentQueries " + mSentQueryCount
                    + "}";
        }

        @NonNull
        protected abstract String getRequestDescriptor();
        protected abstract boolean usingLocalNetworkPermission();
    }

    private static class LegacyClientRequest extends ClientRequest {
        private final int mRequestCode;

        private LegacyClientRequest(int transactionId, int requestCode, long startTimeMs) {
            // Legacy requests cannot be using a permission exemption since they are always unused
            // on U+ (see Dependencies#isMdnsDiscoveryManagerEnabled), and the local network
            // permission check is only done on B+.
            super(transactionId, startTimeMs, /* usingPermissionExemption= */false);
            mRequestCode = requestCode;
        }

        @NonNull
        @Override
        protected String getRequestDescriptor() {
            return "Legacy (" + mRequestCode + ")";
        }

        @Override
        protected boolean usingLocalNetworkPermission() {
            return true;
        }
    }

    private abstract static class JavaBackendClientRequest extends ClientRequest {
        @Nullable
        private final Network mRequestedNetwork;

        private JavaBackendClientRequest(int transactionId, @Nullable Network requestedNetwork,
                long startTimeMs, boolean usingPermissionExemption) {
            super(transactionId, startTimeMs, usingPermissionExemption);
            mRequestedNetwork = requestedNetwork;
        }

        @Nullable
        public Network getRequestedNetwork() {
            return mRequestedNetwork;
        }
    }

    private static class AdvertiserClientRequest extends JavaBackendClientRequest {
        @NonNull
        private final String mServiceFullName;

        private AdvertiserClientRequest(int transactionId, @Nullable Network requestedNetwork,
                @NonNull String serviceFullName, long startTimeMs) {
            super(transactionId, requestedNetwork, startTimeMs,
                    // The picker does not apply to advertising, so there is no permission exemption
                    /* usingPermissionExemption= */false);
            mServiceFullName = serviceFullName;
        }

        @NonNull
        @Override
        public String getRequestDescriptor() {
            return String.format("Advertiser: serviceFullName=%s, net=%s",
                    mServiceFullName, getRequestedNetwork());
        }

        @Override
        protected boolean usingLocalNetworkPermission() {
            return !mUsingPermissionExemption;
        }
    }

    private static class DiscoveryManagerRequest extends JavaBackendClientRequest {
        @NonNull
        private final MdnsListener mListener;
        // Only set for discovery requests and serviceInfoCallback with a DiscoveryRequest, not for
        // resolve or single service serviceInfoCallback
        @Nullable
        private final DiscoveryRequest mDiscoveryRequest;

        private DiscoveryManagerRequest(int transactionId, @NonNull MdnsListener listener,
                @Nullable Network requestedNetwork, long startTimeMs,
                boolean usingPermissionExemption, DiscoveryRequest discoveryRequest) {
            super(transactionId, requestedNetwork, startTimeMs, usingPermissionExemption);
            mListener = listener;
            mDiscoveryRequest = discoveryRequest;
        }

        @NonNull
        @Override
        public String getRequestDescriptor() {
            return String.format("Discovery/%s, net=%s", mListener, getRequestedNetwork());
        }

        @Override
        protected boolean usingLocalNetworkPermission() {
            return !(mListener instanceof PickerListener) && !mUsingPermissionExemption;
        }
    }

    /* Information tracked per client */
    private class ClientInfo {

        /**
         * Maximum number of requests (callbacks) for a client.
         *
         * 200 listeners should be more than enough for most use-cases: even if a client tries to
         * file callbacks for every service on a local network, there are generally much less than
         * 200 devices on a local network (a /24 only allows 255 IPv4 devices), and while some
         * devices may have multiple services, many devices do not advertise any.
         */
        private static final int MAX_LIMIT = 200;
        private final INsdManagerCallback mCb;
        /* Remembers a resolved service until getaddrinfo completes */
        private NsdServiceInfo mResolvedService;

        /* A map from client request ID (listenerKey) to the request */
        private final SparseArray<ClientRequest> mClientRequests = new SparseArray<>();

        // The target SDK of this client < Build.VERSION_CODES.S
        private boolean mIsPreSClient = false;
        private final int mUid;
        private final int mPid;
        @NonNull
        private final String mPackageName;
        // The flag of using java backend if the client's target SDK >= U
        private final boolean mUseJavaBackend;
        // Store client logs
        private final SharedLog mClientLogs;
        // Report the nsd metrics data
        private final NetworkNsdReportedMetrics mMetrics;
        private boolean mIsOffloadEngine = false;

        private ClientInfo(INsdManagerCallback cb, int uid, int pid, @NonNull String packageName,
                boolean useJavaBackend, SharedLog sharedLog, NetworkNsdReportedMetrics metrics) {
            mCb = cb;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mUseJavaBackend = useJavaBackend;
            mClientLogs = sharedLog;
            mClientLogs.log("New client. useJavaBackend=" + useJavaBackend);
            mMetrics = metrics;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("mUid ").append(mUid).append(", ");
            sb.append("mPid ").append(mPid).append(", ");
            sb.append("mPackageName ").append(mPackageName).append(", ");
            sb.append("mResolvedService ").append(mResolvedService).append(", ");
            sb.append("mIsLegacy ").append(mIsPreSClient).append(", ");
            sb.append("mUseJavaBackend ").append(mUseJavaBackend).append(", ");
            if (mIsOffloadEngine) {
                sb.append("isOffloadEngine").append(", ");
            }
            sb.append("mClientRequests:\n");
            for (int i = 0; i < mClientRequests.size(); i++) {
                int clientRequestId = mClientRequests.keyAt(i);
                sb.append("  ").append(clientRequestId)
                        .append(": ").append(mClientRequests.valueAt(i).toString())
                        .append("\n");
            }
            return sb.toString();
        }

        public int getUid() {
            return mUid;
        }

        public int getPid() {
            return mPid;
        }

        private boolean isPreSClient() {
            return mIsPreSClient;
        }

        private void setPreSClient() {
            mIsPreSClient = true;
        }

        private void markIsOffloadEngine() {
            mIsOffloadEngine = true;
        }

        private MdnsListener unregisterMdnsListenerFromRequest(ClientRequest request) {
            final MdnsListener listener =
                    ((DiscoveryManagerRequest) request).mListener;
            mMdnsDiscoveryManager.unregisterListener(listener.getListenedServiceType(), listener);
            listener.onUnregistered();
            return listener;
        }

        // Remove any pending requests from the global map when we get rid of a client,
        // and send cancellations to the daemon.
        private void expungeAllRequests() {
            mClientLogs.log("Client unregistered. expungeAllRequests!");
            // TODO: to keep handler responsive, do not clean all requests for that client at once.
            for (int i = 0; i < mClientRequests.size(); i++) {
                final int clientRequestId = mClientRequests.keyAt(i);
                final ClientRequest request = mClientRequests.valueAt(i);
                final int transactionId = request.mTransactionId;
                mTransactionIdToClientInfoMap.remove(transactionId);
                if (DBG) {
                    Log.d(TAG, "Terminating clientRequestId " + clientRequestId
                            + " transactionId " + transactionId
                            + " type " + mClientRequests.get(clientRequestId));
                }

                if (request instanceof DiscoveryManagerRequest) {
                    final MdnsListener listener = unregisterMdnsListenerFromRequest(request);
                    if (listener instanceof DiscoveryListener
                            || listener instanceof PickerListener) {
                        mMetrics.reportServiceDiscoveryStop(false /* isLegacy */, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getFoundServiceCount(),
                                request.getLostServiceCount(),
                                request.getServicesCount(),
                                request.getSentQueryCount(),
                                request.isServiceFromCache(),
                                request.getCachedServiceExpiredCount());
                    } else if (listener instanceof ResolutionListener) {
                        mMetrics.reportServiceResolutionStop(false /* isLegacy */, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getSentQueryCount());
                    } else if (listener instanceof ServiceInfoListener) {
                        mMetrics.reportServiceInfoCallbackUnregistered(transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getFoundServiceCount(),
                                request.getLostServiceCount(),
                                request.isServiceFromCache(),
                                request.getSentQueryCount(),
                                request.getCachedServiceExpiredCount());
                    } else {
                        throw new RuntimeException("MdnsListener type not supported");
                    }
                    maybeFinishDataDelivery(request);
                    continue;
                }

                if (request instanceof AdvertiserClientRequest) {
                    final AdvertiserMetrics metrics =
                            mAdvertiser.getAdvertiserMetrics(transactionId);
                    mAdvertiser.removeService(transactionId);
                    mMetrics.reportServiceUnregistration(false /* isLegacy */, transactionId,
                            request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                            metrics.mRepliedRequestsCount, metrics.mSentPacketCount,
                            metrics.mConflictDuringProbingCount,
                            metrics.mConflictAfterProbingCount);
                    maybeFinishDataDelivery(request);
                    continue;
                }

                if (!(request instanceof LegacyClientRequest)) {
                    throw new IllegalStateException("Unknown request type: " + request.getClass());
                }

                switch (((LegacyClientRequest) request).mRequestCode) {
                    case NsdManager.DISCOVER_SERVICES:
                        stopServiceDiscovery(transactionId);
                        mMetrics.reportServiceDiscoveryStop(true /* isLegacy */, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                request.getFoundServiceCount(),
                                request.getLostServiceCount(),
                                request.getServicesCount(),
                                NO_SENT_QUERY_COUNT,
                                request.isServiceFromCache(),
                                request.getCachedServiceExpiredCount());
                        maybeFinishDataDelivery(request);
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        stopResolveService(transactionId);
                        mMetrics.reportServiceResolutionStop(true /* isLegacy */, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                NO_SENT_QUERY_COUNT);
                        maybeFinishDataDelivery(request);
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        unregisterService(transactionId);
                        mMetrics.reportServiceUnregistration(true /* isLegacy */, transactionId,
                                request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                                NO_PACKET /* repliedRequestsCount */,
                                NO_PACKET /* sentPacketCount */,
                                0 /* conflictDuringProbingCount */,
                                0 /* conflictAfterProbingCount */);
                        maybeFinishDataDelivery(request);
                        break;
                    default:
                        break;
                }
            }
            mClientRequests.clear();
            updateMulticastLock();
        }

        /**
         * Returns true if this client has any Java backend request that requests one of the given
         * networks.
         */
        boolean hasAnyJavaBackendRequestForNonOffloadedNetworks(@NonNull Set<Network> networks) {
            for (int i = 0; i < mClientRequests.size(); i++) {
                final ClientRequest req = mClientRequests.valueAt(i);
                if (!(req instanceof JavaBackendClientRequest)) {
                    continue;
                }
                final Network reqNetwork = ((JavaBackendClientRequest) mClientRequests.valueAt(i))
                        .getRequestedNetwork();
                if (MdnsUtils.isAnyNetworkMatched(reqNetwork, networks)) {
                    return true;
                }
            }
            return false;
        }

        // mClientRequests is a sparse array of client request id -> ClientRequest.  For a given
        // transaction id, return the corresponding client request id.
        private int getClientRequestId(final int transactionId) {
            for (int i = 0; i < mClientRequests.size(); i++) {
                if (mClientRequests.valueAt(i).mTransactionId == transactionId) {
                    return mClientRequests.keyAt(i);
                }
            }
            return -1;
        }

        private void log(String message) {
            mClientLogs.log(message);
        }

        private static boolean isLegacyClientRequest(@NonNull ClientRequest request) {
            return !(request instanceof DiscoveryManagerRequest)
                    && !(request instanceof AdvertiserClientRequest);
        }

        void onDiscoverServicesStarted(int listenerKey, DiscoveryRequest discoveryRequest,
                ClientRequest request) {
            mMetrics.reportServiceDiscoveryStarted(
                    isLegacyClientRequest(request), request.mTransactionId);
            try {
                mCb.onDiscoverServicesStarted(listenerKey, discoveryRequest);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesStarted", e);
            }
        }

        void onDiscoverServicesFailedImmediately(int listenerKey, int error, boolean isLegacy,
                boolean usingLocalNetPermission) {
            onDiscoverServicesFailed(listenerKey, error, isLegacy, NO_TRANSACTION,
                    0L /* durationMs */, usingLocalNetPermission);
        }

        void onDiscoverServicesFailed(int listenerKey, int error, boolean isLegacy,
                int transactionId, long durationMs, boolean usingLocalNetPermission) {
            mMetrics.reportServiceDiscoveryFailed(isLegacy, transactionId, durationMs);
            if (usingLocalNetPermission) {
                finishDataDelivery(mUid, mPid);
            }
            try {
                mCb.onDiscoverServicesFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesFailed", e);
            }
        }

        void onDiscoverServicesFailedPermissions(int listenerKey) {
            // Don't finish data delivery, because delivery never started if permission checks
            // failed.
            try {
                mCb.onDiscoverServicesFailed(listenerKey, getLocalNetworkPermissionError());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesFailed", e);
            }
        }

        void onServiceFound(int listenerKey, NsdServiceInfo info, ClientRequest request) {
            request.onServiceFound(info.getServiceName());
            tryNotifyServiceFound(listenerKey, info);
        }

        void tryNotifyServiceFound(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onServiceFound(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceFound(", e);
            }
        }

        void onServiceLost(int listenerKey, NsdServiceInfo info, ClientRequest request,
                int serviceRemovedReason) {
            request.onServiceLost(serviceRemovedReason);
            try {
                mCb.onServiceLost(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceLost(", e);
            }
        }

        void onStopDiscoveryFailed(int listenerKey, int error) {
            try {
                mCb.onStopDiscoveryFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoveryFailed", e);
            }
        }

        void onStopDiscoverySucceeded(int listenerKey, ClientRequest request) {
            mMetrics.reportServiceDiscoveryStop(
                    isLegacyClientRequest(request),
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.getFoundServiceCount(),
                    request.getLostServiceCount(),
                    request.getServicesCount(),
                    request.getSentQueryCount(),
                    request.isServiceFromCache(),
                    request.getCachedServiceExpiredCount());
            maybeFinishDataDelivery(request);
            try {
                mCb.onStopDiscoverySucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoverySucceeded", e);
            }
        }

        void onRegisterServiceFailedImmediately(int listenerKey, int error, boolean isLegacy,
                boolean usingLocalNetworkPermission) {
            onRegisterServiceFailed(listenerKey, error, isLegacy, NO_TRANSACTION,
                    0L /* durationMs */, usingLocalNetworkPermission);
        }

        void onRegisterServiceFailed(int listenerKey, int error, boolean isLegacy,
                int transactionId, long durationMs, boolean usingLocalNetworkPermission) {
            mMetrics.reportServiceRegistrationFailed(isLegacy, transactionId, durationMs);
            if (usingLocalNetworkPermission) {
                finishDataDelivery(mUid, mPid);
            }
            try {
                mCb.onRegisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceFailed", e);
            }
        }

        void onRegisterServiceSucceeded(int listenerKey, NsdServiceInfo info,
                ClientRequest request) {
            mMetrics.reportServiceRegistrationSucceeded(isLegacyClientRequest(request),
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()));
            try {
                mCb.onRegisterServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceSucceeded", e);
            }
        }

        void onUnregisterServiceFailed(int listenerKey, int error) {
            try {
                mCb.onUnregisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceFailed", e);
            }
        }

        void onUnregisterServiceSucceeded(int listenerKey, ClientRequest request,
                AdvertiserMetrics metrics) {
            mMetrics.reportServiceUnregistration(isLegacyClientRequest(request),
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    metrics.mRepliedRequestsCount, metrics.mSentPacketCount,
                    metrics.mConflictDuringProbingCount, metrics.mConflictAfterProbingCount);
            maybeFinishDataDelivery(request);
            try {
                mCb.onUnregisterServiceSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceSucceeded", e);
            }
        }

        void onResolveServiceFailedImmediately(int listenerKey, int error, boolean isLegacy,
                boolean usingLocalNetworkPermission) {
            onResolveServiceFailed(listenerKey, error, isLegacy, NO_TRANSACTION,
                    0L /* durationMs */, usingLocalNetworkPermission);
        }

        void onResolveServiceFailed(int listenerKey, int error, boolean isLegacy,
                int transactionId, long durationMs, boolean usingLocalNetworkPermission) {
            mMetrics.reportServiceResolutionFailed(isLegacy, transactionId, durationMs);
            if (usingLocalNetworkPermission) {
                finishDataDelivery(mUid, mPid);
            }
            try {
                mCb.onResolveServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceFailed", e);
            }
        }

        void onResolveServiceFailedPermissions(int listenerKey) {
            // Don't finish data delivery, because delivery never started if permission checks
            // failed.
            try {
                mCb.onResolveServiceFailed(listenerKey, getLocalNetworkPermissionError());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceFailed", e);
            }
        }

        void onResolveServiceSucceeded(int listenerKey, NsdServiceInfo info, int ifIndex,
                ClientRequest request) {
            mMetrics.reportServiceResolved(
                    isLegacyClientRequest(request),
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.isServiceFromCache(),
                    request.getSentQueryCount());
            maybeFinishDataDelivery(request);
            maybeAllowLocalNetAccess(ifIndex, info, request);
            try {
                mCb.onResolveServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceSucceeded", e);
            }
        }

        void onStopResolutionFailed(int listenerKey, int error) {
            try {
                mCb.onStopResolutionFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopResolutionFailed", e);
            }
        }

        void onStopResolutionSucceeded(int listenerKey, ClientRequest request) {
            mMetrics.reportServiceResolutionStop(
                    isLegacyClientRequest(request),
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.getSentQueryCount());
            maybeFinishDataDelivery(request);
            try {
                mCb.onStopResolutionSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopResolutionSucceeded", e);
            }
        }

        void onServiceInfoCallbackRegistrationFailed(int listenerKey, int error,
                boolean usingLocalNetworkPermission) {
            mMetrics.reportServiceInfoCallbackRegistrationFailed(NO_TRANSACTION);
            if (usingLocalNetworkPermission) {
                finishDataDelivery(mUid, mPid);
            }
            try {
                mCb.onServiceInfoCallbackRegistrationFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackRegistrationFailed", e);
            }
        }

        void onServiceInfoCallbackRegistrationFailedPermissions(int listenerKey) {
            // Don't finish data delivery, because delivery never started if permission checks
            // failed.
            try {
                mCb.onServiceInfoCallbackRegistrationFailed(listenerKey,
                        getLocalNetworkPermissionError());
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackRegistrationFailed", e);
            }
        }

        void onServiceInfoCallbackRegistered(int listenerKey, int transactionId) {
            mMetrics.reportServiceInfoCallbackRegistered(transactionId);
            try {
                mCb.onServiceInfoCallbackRegistered(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackRegistered", e);
            }
        }

        void onServiceUpdated(int listenerKey, NsdServiceInfo info, int ifIndex,
                ClientRequest request) {
            request.onServiceFound(info.getServiceName());
            tryNotifyServiceUpdated(listenerKey, info, ifIndex, request);
        }

        void tryNotifyServiceUpdated(int listenerKey, NsdServiceInfo info, int ifIndex,
                ClientRequest request) {
            maybeAllowLocalNetAccess(ifIndex, info, request);
            try {
                mCb.onServiceUpdated(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceUpdated(", e);
            }
        }

        void onServiceUpdatedLost(int listenerKey, ClientRequest request,
                NsdServiceInfo info, int serviceRemovedReason) {
            request.onServiceLost(serviceRemovedReason);
            try {
                mCb.onServiceUpdatedLost(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceUpdatedLost", e);
            }
        }

        void onServiceInfoCallbackUnregistered(int listenerKey, ClientRequest request) {
            mMetrics.reportServiceInfoCallbackUnregistered(
                    request.mTransactionId,
                    request.calculateRequestDurationMs(mClock.elapsedRealtime()),
                    request.getFoundServiceCount(),
                    request.getLostServiceCount(),
                    request.isServiceFromCache(),
                    request.getSentQueryCount(),
                    request.getCachedServiceExpiredCount());
            if (request.usingLocalNetworkPermission()) {
                finishDataDelivery(mUid, mPid);
            }
            try {
                mCb.onServiceInfoCallbackUnregistered(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackUnregistered", e);
            }
        }

        void maybeFinishDataDelivery(@NonNull ClientRequest request) {
            if (request.usingLocalNetworkPermission()) {
                finishDataDelivery(mUid, mPid);
            }
        }

        void maybeAllowLocalNetAccess(int ifIndex, NsdServiceInfo info, ClientRequest request) {
            if (isAtLeastB() && mEnablePicker && !request.usingLocalNetworkPermission()) {
                mContext.getSystemService(ConnectivityManager.class)
                        .allowLocalNetAccess(mUid, ifIndex, info.getHostAddresses());
            }
        }
    }
}
