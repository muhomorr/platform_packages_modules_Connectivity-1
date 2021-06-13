/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.NsdService.DaemonConnection;
import com.android.server.NsdService.DaemonConnectionSupplier;
import com.android.server.NsdService.NativeCallbackReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

// TODOs:
//  - test client can send requests and receive replies
//  - test NSD_ON ENABLE/DISABLED listening
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NsdServiceTest {

    static final int PROTOCOL = NsdManager.PROTOCOL_DNS_SD;
    private static final long CLEANUP_DELAY_MS = 500;

    long mTimeoutMs = 100; // non-final so that tests can adjust the value.

    @Mock Context mContext;
    @Mock ContentResolver mResolver;
    @Mock NsdService.NsdSettings mSettings;
    @Mock DaemonConnection mDaemon;
    NativeCallbackReceiver mDaemonCallback;
    HandlerThread mThread;
    TestHandler mHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mThread = new HandlerThread("mock-service-handler");
        mThread.start();
        mHandler = new TestHandler(mThread.getLooper());
        when(mContext.getContentResolver()).thenReturn(mResolver);
    }

    @After
    public void tearDown() throws Exception {
        if (mThread != null) {
            mThread.quit();
            mThread = null;
        }
    }

    @Test
    public void testNoDaemonStartedWhenClientsConnect() {
        when(mSettings.isEnabled()).thenReturn(true);

        NsdService service = makeService();

        // Creating an NsdManager will not cause any cmds executed, which means
        // no daemon is started.
        NsdManager client1 = connectClient(service);
        verify(mDaemon, never()).execute(any());

        // Creating another NsdManager will not cause any cmds executed.
        NsdManager client2 = connectClient(service);
        verify(mDaemon, never()).execute(any());

        client1.disconnect();
        // Still 1 client remains, daemon shouldn't be stopped.
        verify(mDaemon, never()).maybeStop();

        client2.disconnect();
        // All clients are disconnected, stop the daemon after CLEANUP_DELAY_MS.
        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);

        client1.disconnect();
        client2.disconnect();
    }

    @Test
    public void testClientRequestsAreGCedAtDisconnection() {
        when(mSettings.isEnabled()).thenReturn(true);
        when(mDaemon.execute(any())).thenReturn(true);

        NsdService service = makeService();
        NsdManager client = connectClient(service);

        verify(mDaemon, never()).maybeStart();
        verify(mDaemon, never()).execute(any());

        NsdServiceInfo request = new NsdServiceInfo("a_name", "a_type");
        request.setPort(2201);

        // Client registration request
        NsdManager.RegistrationListener listener1 = mock(NsdManager.RegistrationListener.class);
        client.registerService(request, PROTOCOL, listener1);
        verify(mDaemon, timeout(mTimeoutMs).times(1)).maybeStart();
        verifyDaemonCommand("register 2 a_name a_type 2201");

        // Client discovery request
        NsdManager.DiscoveryListener listener2 = mock(NsdManager.DiscoveryListener.class);
        client.discoverServices("a_type", PROTOCOL, listener2);
        verify(mDaemon, timeout(mTimeoutMs).times(1)).maybeStart();
        verifyDaemonCommand("discover 3 a_type");

        // Client resolve request
        NsdManager.ResolveListener listener3 = mock(NsdManager.ResolveListener.class);
        client.resolveService(request, listener3);
        verify(mDaemon, timeout(mTimeoutMs).times(1)).maybeStart();
        verifyDaemonCommand("resolve 4 a_name a_type local.");

        // Client disconnects, stop the daemon after CLEANUP_DELAY_MS.
        client.disconnect();
        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);

        // checks that request are cleaned
        verifyDaemonCommands("stop-register 2", "stop-discover 3", "stop-resolve 4");

        client.disconnect();
    }

    @Test
    public void testCleanupDelayNoRequestActive() {
        when(mSettings.isEnabled()).thenReturn(true);
        when(mDaemon.execute(any())).thenReturn(true);

        NsdService service = makeService();
        NsdManager client = connectClient(service);

        NsdServiceInfo request = new NsdServiceInfo("a_name", "a_type");
        request.setPort(2201);
        NsdManager.RegistrationListener listener1 = mock(NsdManager.RegistrationListener.class);
        client.registerService(request, PROTOCOL, listener1);
        verify(mDaemon, timeout(mTimeoutMs).times(1)).maybeStart();
        verifyDaemonCommand("register 2 a_name a_type 2201");

        client.unregisterService(listener1);
        verifyDaemonCommand("stop-register 2");

        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);
        reset(mDaemon);
        client.disconnect();
        // Client disconnects, after CLEANUP_DELAY_MS, maybeStop the daemon.
        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);
    }

    NsdService makeService() {
        DaemonConnectionSupplier supplier = (callback) -> {
            mDaemonCallback = callback;
            return mDaemon;
        };
        NsdService service = new NsdService(mContext, mSettings,
                mHandler, supplier, CLEANUP_DELAY_MS);
        verify(mDaemon, never()).execute(any(String.class));
        return service;
    }

    NsdManager connectClient(NsdService service) {
        return new NsdManager(mContext, service);
    }

    void verifyDelayMaybeStopDaemon(long cleanupDelayMs) {
        // Stop daemon shouldn't be called immediately.
        verify(mDaemon, timeout(mTimeoutMs).times(0)).maybeStop();
        // Clean up the daemon after CLEANUP_DELAY_MS.
        verify(mDaemon, timeout(cleanupDelayMs + mTimeoutMs)).maybeStop();
    }

    void verifyDaemonCommands(String... wants) {
        verifyDaemonCommand(String.join(" ", wants), wants.length);
    }

    void verifyDaemonCommand(String want) {
        verifyDaemonCommand(want, 1);
    }

    void verifyDaemonCommand(String want, int n) {
        ArgumentCaptor<Object> argumentsCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mDaemon, timeout(mTimeoutMs).times(n)).execute(argumentsCaptor.capture());
        String got = "";
        for (Object o : argumentsCaptor.getAllValues()) {
            got += o + " ";
        }
        assertEquals(want, got.trim());
        // rearm deamon for next command verification
        reset(mDaemon);
        when(mDaemon.execute(any())).thenReturn(true);
    }

    public static class TestHandler extends Handler {
        public Message lastMessage;

        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            lastMessage = obtainMessage();
            lastMessage.copyFrom(msg);
        }
    }
}
