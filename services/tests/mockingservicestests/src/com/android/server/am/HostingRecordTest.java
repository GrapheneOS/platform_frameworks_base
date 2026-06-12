/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.server.am;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.os.Process;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class HostingRecordTest {
    private static final String PACKAGE_NAME = "com.android.app";
    private static final String COMPONENT_CLASS = "com.android.app.Component";
    private static final ComponentName COMPONENT_NAME = new ComponentName(
            PACKAGE_NAME, COMPONENT_CLASS);
    private static final int UID = 10000;
    private static final String PROCESS_NAME = "com.android.app.process";
    private static final int CALLER_UID = 12345;
    private static final String CALLER_PROCESS_NAME = "com.caller.process";
    private static final String DEFINING_PACKAGE_NAME = "defPkg";
    private static final int DEFINING_UID = 10001;
    private static final String DEFINING_PROCESS_NAME = "proc";

    @Test
    public void testUsesNativeAppZygote() {
        HostingRecord nativeServiceRecord = HostingRecord.byAppZygote_(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                COMPONENT_NAME, PACKAGE_NAME, UID, PROCESS_NAME,
                /*isNativeService=*/ true,
                /*callerUid=*/ Process.INVALID_UID, /*callerProcessName=*/ null);
        assertThat(nativeServiceRecord.usesAppZygote_()).isTrue();
        assertThat(nativeServiceRecord.usesNativeAppZygote()).isTrue();

        HostingRecord managedAppZygoteRecord = HostingRecord.byAppZygote_(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                COMPONENT_NAME, PACKAGE_NAME, UID, PROCESS_NAME,
                /*isNativeService=*/ false,
                /*callerUid=*/ Process.INVALID_UID, /*callerProcessName=*/ null);
        assertThat(managedAppZygoteRecord.usesAppZygote_()).isTrue();
        assertThat(managedAppZygoteRecord.usesNativeAppZygote()).isFalse();
    }

    @Test
    public void testCallerInfo() {
        HostingRecord record = new HostingRecord(HostingRecord.HOSTING_TYPE_STARTED_SERVICE,
                COMPONENT_NAME,
                /* isTopApp */ false, /* isPcc */ false, CALLER_UID, CALLER_PROCESS_NAME);

        assertThat(record.getCallerUid()).isEqualTo(CALLER_UID);
        assertThat(record.getCallerProcessName()).isEqualTo(CALLER_PROCESS_NAME);
    }

    @Test
    public void testCallerInfo_byWebviewZygote() {
        HostingRecord record = HostingRecord.byWebviewZygote(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                COMPONENT_NAME,
                DEFINING_PACKAGE_NAME, DEFINING_UID, DEFINING_PROCESS_NAME,
                CALLER_UID, CALLER_PROCESS_NAME);
        assertThat(record.getCallerUid()).isEqualTo(CALLER_UID);
        assertThat(record.getCallerProcessName()).isEqualTo(CALLER_PROCESS_NAME);
    }

    @Test
    public void testCallerInfo_byAppZygote() {
        HostingRecord record = HostingRecord.byAppZygote_(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                COMPONENT_NAME,
                DEFINING_PACKAGE_NAME, DEFINING_UID, DEFINING_PROCESS_NAME,
                false, CALLER_UID, CALLER_PROCESS_NAME);
        assertThat(record.getCallerUid()).isEqualTo(CALLER_UID);
        assertThat(record.getCallerProcessName()).isEqualTo(CALLER_PROCESS_NAME);
    }

    @Test
    public void testGetHostingZygote() {
        HostingRecord record = new HostingRecord(HostingRecord.HOSTING_TYPE_ACTIVITY);
        assertThat(record.getHostingZygote()).isEqualTo(HostingRecord.ZYGOTE_TYPE_REGULAR);
    }

    @Test
    public void testGetHostingZygote_typeWebview() {
        HostingRecord webviewRecord = HostingRecord.byWebviewZygote(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                COMPONENT_NAME, DEFINING_PACKAGE_NAME, DEFINING_UID, DEFINING_PROCESS_NAME,
                CALLER_UID, CALLER_PROCESS_NAME);
        assertThat(webviewRecord.getHostingZygote()).isEqualTo(HostingRecord.ZYGOTE_TYPE_WEBVIEW);
    }

    @Test
    public void testGetHostingZygote_typeApp() {
        HostingRecord appZygoteRecord = HostingRecord.byAppZygote_(
                HostingRecord.HOSTING_TYPE_BOUND_SERVICE,
                COMPONENT_NAME, DEFINING_PACKAGE_NAME, DEFINING_UID, DEFINING_PROCESS_NAME,
                /* isNativeService */ false, CALLER_UID, CALLER_PROCESS_NAME);
        assertThat(appZygoteRecord.getHostingZygote()).isEqualTo(HostingRecord.ZYGOTE_TYPE_APP);
    }

    @Test
    public void testContentProviderInfo() {
        String authority = "com.android.authority";
        boolean stable = true;
        HostingRecord record = HostingRecord.forContentProvider(COMPONENT_NAME,
                /* isPcc */ false, CALLER_UID, CALLER_PROCESS_NAME, authority, stable);

        assertThat(record.getType()).isEqualTo(HostingRecord.HOSTING_TYPE_CONTENT_PROVIDER);
        assertThat(record.getHostingAuthority()).isEqualTo(authority);
        assertThat(record.isProviderStable()).isEqualTo(stable);
        assertThat(record.getCallerUid()).isEqualTo(CALLER_UID);
        assertThat(record.getCallerProcessName()).isEqualTo(CALLER_PROCESS_NAME);
        assertThat(record.isPcc()).isFalse();
    }
}
