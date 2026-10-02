/*
 * Copyright (C) 2026 GrapheneOS
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

package com.android.server.locksettings.recoverablekeystore.storage;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.os.ServiceSpecificException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.recoverablekeystore.KeyStoreProxy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.KeyStoreException;
import java.util.Arrays;
import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ApplicationKeyStorageTest {
    private static final int USER_ID = 10;
    private static final int UID = 1012345;
    private static final String ALIAS_PREFIX =
            "com.android.server.locksettings.recoverablekeystore/application/10/1012345/";
    private static final String MATCHING_ALIAS_1 = ALIAS_PREFIX + "first";
    private static final String MATCHING_ALIAS_2 = ALIAS_PREFIX + "second";

    @Mock private KeyStoreProxy mKeyStore;

    private ApplicationKeyStorage mStorage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStorage = new ApplicationKeyStorage(mKeyStore);
    }

    @Test
    public void deleteEntriesForRecoveryAgent_deletesOnlyMatchingAliases() throws Exception {
        when(mKeyStore.aliases())
                .thenReturn(
                        Collections.enumeration(
                                Arrays.asList(
                                        MATCHING_ALIAS_1,
                                        "com.android.server.locksettings.recoverablekeystore/application/11/1012345/first",
                                        "com.android.server.locksettings.recoverablekeystore/application/10/1012346/first",
                                        "com.android.server.locksettings.recoverablekeystore/application/10/10123450/first",
                                        MATCHING_ALIAS_2)));

        mStorage.deleteEntriesForRecoveryAgent(USER_ID, UID);

        verify(mKeyStore).aliases();
        verify(mKeyStore).deleteEntry(MATCHING_ALIAS_1);
        verify(mKeyStore).deleteEntry(MATCHING_ALIAS_2);
        verifyNoMoreInteractions(mKeyStore);
    }

    @Test
    public void deleteEntriesForRecoveryAgent_continuesAfterDeletionFailure() throws Exception {
        when(mKeyStore.aliases())
                .thenReturn(
                        Collections.enumeration(Arrays.asList(MATCHING_ALIAS_1, MATCHING_ALIAS_2)));
        doThrow(new KeyStoreException("failure")).when(mKeyStore).deleteEntry(MATCHING_ALIAS_1);

        assertThrows(
                ServiceSpecificException.class,
                () -> mStorage.deleteEntriesForRecoveryAgent(USER_ID, UID));

        verify(mKeyStore).deleteEntry(MATCHING_ALIAS_1);
        verify(mKeyStore).deleteEntry(MATCHING_ALIAS_2);
    }
}
