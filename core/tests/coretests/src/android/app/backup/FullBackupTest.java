/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app.backup;

import static android.app.backup.FullBackup.ConfigSection.CLOUD_BACKUP;
import static android.app.backup.FullBackup.ConfigSection.CROSS_PLATFORM_TRANSFER;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.backup.FullBackup.BackupScheme.PathWithRequiredFlags;
import android.app.backup.FullBackup.BackupScheme.PlatformSpecificParams;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.backup.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class FullBackupTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private XmlPullParserFactory mFactory;
    private XmlPullParser mXpp;
    private Context mContext;

    Map<String, Set<PathWithRequiredFlags>> includeMap;
    Set<PathWithRequiredFlags> excludesSet;
    Map<String, PlatformSpecificParams> mPlatformSpecificParamsMap;

    @Before
    public void setUp() throws Exception {
        mFactory = XmlPullParserFactory.newInstance();
        mXpp = mFactory.newPullParser();
        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        includeMap = new ArrayMap<>();
        excludesSet = new ArraySet<>();
        mPlatformSpecificParamsMap = new ArrayMap<>();
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyInclude() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"onlyInclude.txt\" domain=\"file\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Excluding files when there was no <exclude/> tag.", 0, excludesSet.size());
        assertEquals("Unexpected number of <include/>s", 1, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        PathWithRequiredFlags include = fileDomainIncludes.iterator().next();
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                include.getPath());
        assertEquals("Invalid requireFlags parsed for <include/>", 0, include.getRequiredFlags());
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyIncludeRequireEncryptionFlag() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"onlyInclude.txt\" domain=\"file\""
                                + " requireFlags=\"clientSideEncryption\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Excluding files when there was no <exclude/> tag.", 0, excludesSet.size());
        assertEquals("Unexpected number of <include/>s", 1, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        PathWithRequiredFlags include = fileDomainIncludes.iterator().next();
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                include.getPath());
        assertEquals(
                "Invalid requireFlags parsed for <include/>",
                BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED,
                include.getRequiredFlags());
    }

    @Test
    public void testParseBackupSchemeFromXml_onlyIncludeRequireFakeEncryptionFlag()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"onlyInclude.txt\" domain=\"file\""
                                + " requireFlags=\"fakeClientSideEncryption\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        PathWithRequiredFlags include = fileDomainIncludes.iterator().next();
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                include.getPath());
        assertEquals(
                "Invalid requireFlags parsed for <include/>",
                BackupAgent.FLAG_FAKE_CLIENT_SIDE_ENCRYPTION_ENABLED,
                include.getRequiredFlags());
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyIncludeRequireD2DFlag() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"onlyInclude.txt\" domain=\"file\""
                                + " requireFlags=\"deviceToDeviceTransfer\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Excluding files when there was no <exclude/> tag.", 0, excludesSet.size());
        assertEquals("Unexpected number of <include/>s", 1, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        PathWithRequiredFlags include = fileDomainIncludes.iterator().next();
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                include.getPath());
        assertEquals(
                "Invalid requireFlags parsed for <include/>",
                BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER,
                include.getRequiredFlags());
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyIncludeRequireEncryptionAndD2DFlags()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"onlyInclude.txt\" domain=\"file\""
                                + " requireFlags=\"clientSideEncryption|deviceToDeviceTransfer\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Excluding files when there was no <exclude/> tag.", 0, excludesSet.size());
        assertEquals("Unexpected number of <include/>s", 1, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        PathWithRequiredFlags include = fileDomainIncludes.iterator().next();
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                include.getPath());
        assertEquals(
                "Invalid requireFlags parsed for <include/>",
                BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED
                        | BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER,
                include.getRequiredFlags());
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyIncludeRequireD2DFlagAndIngoreGarbage()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"onlyInclude.txt\" domain=\"file\""
                                + " requireFlags=\"deviceToDeviceTransfer|garbageFlag\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Excluding files when there was no <exclude/> tag.", 0, excludesSet.size());
        assertEquals("Unexpected number of <include/>s", 1, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        PathWithRequiredFlags include = fileDomainIncludes.iterator().next();
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                include.getPath());
        assertEquals(
                "Invalid requireFlags parsed for <include/>",
                BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER,
                include.getRequiredFlags());
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyExcludeRequireFlagsNotSupported()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<exclude path=\"onlyExclude.txt\" domain=\"file\""
                                + " requireFlags=\"deviceToDeviceTransfer\"/>"
                                + "</full-backup-content>"));

        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Having more than 3 attributes in exclude should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {
        }
    }

    @Test
    public void testparseBackupSchemeFromXml_onlyExclude() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<exclude path=\"onlyExclude.txt\" domain=\"file\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Including files when there was no <include/> tag.", 0, includeMap.size());
        assertEquals("Unexpected number of <exclude/>s", 1, excludesSet.size());
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getFilesDir(), "onlyExclude.txt").getCanonicalPath(),
                excludesSet.iterator().next().getPath());
    }

    @Test
    public void testparseBackupSchemeFromXml_includeAndExclude() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<exclude path=\"exclude.txt\" domain=\"file\"/>"
                                + "<include path=\"include.txt\" domain=\"file\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "include.txt").getCanonicalPath(),
                fileDomainIncludes.iterator().next().getPath());

        assertEquals("Unexpected number of <exclude/>s", 1, excludesSet.size());
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getFilesDir(), "exclude.txt").getCanonicalPath(),
                excludesSet.iterator().next().getPath());
    }

    @Test
    public void testparseBackupSchemeFromXml_lotsOfIncludesAndExcludes() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<exclude path=\"exclude1.txt\" domain=\"file\"/>"
                                + "<include path=\"include1.txt\" domain=\"file\"/>"
                                + "<exclude path=\"exclude2.txt\" domain=\"database\"/>"
                                + "<include path=\"include2.txt\" domain=\"database\"/>"
                                + "<exclude path=\"exclude3\" domain=\"sharedpref\"/>"
                                + "<include path=\"include3\" domain=\"sharedpref\"/>"
                                + "<exclude path=\"exclude4.xml\" domain=\"sharedpref\"/>"
                                + "<include path=\"include4.xml\" domain=\"sharedpref\"/>"
                                + "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "include1.txt").getCanonicalPath(),
                fileDomainIncludes.iterator().next().getPath());

        Set<PathWithRequiredFlags> databaseDomainIncludes =
                includeMap.get(FullBackup.DATABASE_TREE_TOKEN);
        Set<String> databaseDomainIncludesPaths = new ArraySet<>();
        for (PathWithRequiredFlags databaseInclude : databaseDomainIncludes) {
            databaseDomainIncludesPaths.add(databaseInclude.getPath());
        }
        // Three expected here because of "-journal" and "-wal" files
        assertEquals(
                "Didn't find expected database domain include.", 3, databaseDomainIncludes.size());
        assertTrue(
                "Invalid path parsed for <include/>",
                databaseDomainIncludesPaths.contains(
                        new File(mContext.getDatabasePath("foo").getParentFile(), "include2.txt")
                                .getCanonicalPath()));
        assertTrue(
                "Invalid path parsed for <include/>",
                databaseDomainIncludesPaths.contains(
                        new File(
                                        mContext.getDatabasePath("foo").getParentFile(),
                                        "include2.txt-journal")
                                .getCanonicalPath()));
        assertTrue(
                "Invalid path parsed for <include/>",
                databaseDomainIncludesPaths.contains(
                        new File(
                                        mContext.getDatabasePath("foo").getParentFile(),
                                        "include2.txt-wal")
                                .getCanonicalPath()));

        List<PathWithRequiredFlags> sharedPrefDomainIncludes =
                new ArrayList<PathWithRequiredFlags>(
                        includeMap.get(FullBackup.SHAREDPREFS_TREE_TOKEN));
        ArrayList<String> sharedPrefDomainIncludesPaths = new ArrayList<>();
        for (PathWithRequiredFlags sharedPrefInclude : sharedPrefDomainIncludes) {
            sharedPrefDomainIncludesPaths.add(sharedPrefInclude.getPath());
        }
        // Sets are annoying to iterate over b/c order isn't enforced - convert to an array and
        // sort lexicographically.
        Collections.sort(sharedPrefDomainIncludesPaths);

        assertEquals(
                "Didn't find expected sharedpref domain include.",
                3,
                sharedPrefDomainIncludes.size());
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "include3")
                        .getCanonicalPath(),
                sharedPrefDomainIncludesPaths.get(0));
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "include3.xml")
                        .getCanonicalPath(),
                sharedPrefDomainIncludesPaths.get(1));
        assertEquals(
                "Invalid path parsed for <include/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "include4.xml")
                        .getCanonicalPath(),
                sharedPrefDomainIncludesPaths.get(2));

        assertEquals("Unexpected number of <exclude/>s", 7, excludesSet.size());
        // Sets are annoying to iterate over b/c order isn't enforced - convert to an array and
        // sort lexicographically.
        ArrayList<String> arrayedSet = new ArrayList<>();
        for (PathWithRequiredFlags exclude : excludesSet) {
            arrayedSet.add(exclude.getPath());
        }
        Collections.sort(arrayedSet);

        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getDatabasePath("foo").getParentFile(), "exclude2.txt")
                        .getCanonicalPath(),
                arrayedSet.get(0));
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getDatabasePath("foo").getParentFile(), "exclude2.txt-journal")
                        .getCanonicalPath(),
                arrayedSet.get(1));
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getDatabasePath("foo").getParentFile(), "exclude2.txt-wal")
                        .getCanonicalPath(),
                arrayedSet.get(2));
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getFilesDir(), "exclude1.txt").getCanonicalPath(),
                arrayedSet.get(3));
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "exclude3")
                        .getCanonicalPath(),
                arrayedSet.get(4));
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "exclude3.xml")
                        .getCanonicalPath(),
                arrayedSet.get(5));
        assertEquals(
                "Invalid path parsed for <exclude/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "exclude4.xml")
                        .getCanonicalPath(),
                arrayedSet.get(6));
    }

    @Test
    public void testParseBackupSchemeFromXml_invalidXmlFails() throws Exception {
        // Invalid root tag.
        mXpp.setInput(
                new StringReader(
                        "<full-weird-tag>"
                                + "<exclude path=\"invalidRootTag.txt\" domain=\"file\"/>"
                                + "</ffull-weird-tag>"));

        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Invalid root xml tag should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {
        }

        // Invalid exclude tag.
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<excluded path=\"invalidExcludeTag.txt\" domain=\"file\"/>"
                                + "</full-backup-conten>t"));
        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Misspelled xml exclude tag should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {
        }

        // Just for good measure - invalid include tag.
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<yinclude path=\"invalidIncludeTag.txt\" domain=\"file\"/>"
                                + "</full-backup-conten>t"));
        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Misspelled xml exclude tag should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {
        }
    }

    @Test
    public void testInvalidPath_doesNotBackup() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<exclude path=\"..\" domain=\"file\"/>"
                                + // Invalid use of ".." dir.
                                "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Didn't throw away invalid \"..\" path.", 0, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertNull("Didn't throw away invalid \"..\" path.", fileDomainIncludes);
    }

    @Test
    public void testParseNewBackupSchemeFromXml_emptyCloudSectionIsRespected() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<data-extraction-rules>"
                                + "<cloud-backup>"
                                + "</cloud-backup>"
                                + "</data-extraction-rules>"));

        FullBackup.BackupScheme backupScheme = FullBackup.getBackupSchemeForTest(mContext);
        boolean result =
                backupScheme.parseNewBackupSchemeFromXmlLocked(
                        mXpp, CLOUD_BACKUP, excludesSet, includeMap, mPlatformSpecificParamsMap);

        assertTrue(result);
    }

    @Test
    public void testParseNewBackupSchemeFromXml_emptyCloudSectionWithEncryptionFlagIsRespected()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<data-extraction-rules>"
                                + "<cloud-backup disableIfNoEncryptionCapabilities=\"true\">"
                                + "</cloud-backup>"
                                + "</data-extraction-rules>"));

        FullBackup.BackupScheme backupScheme = FullBackup.getBackupSchemeForTest(mContext);
        boolean result =
                backupScheme.parseNewBackupSchemeFromXmlLocked(
                        mXpp, CLOUD_BACKUP, excludesSet, includeMap, mPlatformSpecificParamsMap);

        assertTrue(result);
        assertEquals(
                backupScheme.getRequiredTransportFlags(),
                BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED);
    }

    @Test
    public void testDoubleDotInPath_isIgnored() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<include path=\"..\" domain=\"file\"/>"
                                + // Invalid use of ".." dir.
                                "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Didn't throw away invalid \"..\" path.", 0, includeMap.size());

        Set<PathWithRequiredFlags> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertNull("Didn't throw away invalid \"..\" path.", fileDomainIncludes);
    }

    @Test
    public void testDoubleSlashInPath_isIgnored() throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content>"
                                + "<exclude path=\"//hello.txt\" domain=\"file\"/>"
                                + // Invalid use of "//"
                                "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Didn't throw away invalid path containing \"//\".", 0, excludesSet.size());
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_CROSS_PLATFORM_TRANSFER})
    public void testParseOldBackupSchemeFromXml_flagOn_crossPlatformTransferSection_throws()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<full-backup-content><platform-specific-params"
                            + " bundleId=\"com.example.app\" teamId=\"0\" contentVersion=\"1.0\" />"
                            + "</full-backup-content>"));
        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);

        assertThrows(
                "Unexpected platform-specific-params in old scheme should throw an exception",
                XmlPullParserException.class,
                () -> bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap));
    }

    @Test
    @DisableFlags({Flags.FLAG_ENABLE_CROSS_PLATFORM_TRANSFER})
    public void testParseNewBackupSchemeFromXml_flagOff_crossPlatformTransferSection_throws()
            throws Exception {
        mXpp.setInput(
                new StringReader(
                        "<data-extraction-rules><cross-platform-transfer platform=\"ios\"><include"
                                + " path=\"file.txt\" domain=\"file\" /><platform-specific-params"
                                + " bundleId=\"com.example.app\" teamId=\"0123abcd\""
                                + " contentVersion=\"1.0\" /></cross-platform-transfer>"
                                + "</data-extraction-rules>"));
        FullBackup.BackupScheme bs =
                FullBackup.getBackupSchemeForTest(
                        mContext, BackupAnnotations.BackupDestination.CROSS_PLATFORM_TRANSFER);

        assertThrows(
                "cross-platform-transfer in backup rules when feature is disabled should throw an"
                    + " exception",
                XmlPullParserException.class,
                () ->
                        bs.parseNewBackupSchemeFromXmlLocked(
                                mXpp,
                                CROSS_PLATFORM_TRANSFER,
                                excludesSet,
                                includeMap,
                                mPlatformSpecificParamsMap));
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_CROSS_PLATFORM_TRANSFER})
    public void testParseNewBackupSchemeFromXml_flagOn_crossPlatformTransferSection_isParsed()
            throws Exception {
        assumeTrue(Flags.enableCrossPlatformTransfer());
        mXpp.setInput(
                new StringReader(
                        "<data-extraction-rules><cross-platform-transfer platform=\"ios\"><include"
                                + " path=\"file.txt\" domain=\"file\" /><platform-specific-params"
                                + " bundleId=\"com.example.app\" teamId=\"0123abcd\""
                                + " contentVersion=\"1.0\" /></cross-platform-transfer>"
                                + "</data-extraction-rules>"));
        FullBackup.BackupScheme bs =
                FullBackup.getBackupSchemeForTest(
                        mContext, BackupAnnotations.BackupDestination.CROSS_PLATFORM_TRANSFER);

        boolean result =
                bs.parseNewBackupSchemeFromXmlLocked(
                        mXpp,
                        CROSS_PLATFORM_TRANSFER,
                        excludesSet,
                        includeMap,
                        mPlatformSpecificParamsMap);

        assertTrue(result);
        assertThat(mPlatformSpecificParamsMap).containsKey("ios");
        PlatformSpecificParams actual = mPlatformSpecificParamsMap.get("ios");
        assertThat(actual.getBundleId()).isEqualTo("com.example.app");
        assertThat(actual.getTeamId()).isEqualTo("0123abcd");
        assertThat(actual.getContentVersion()).isEqualTo("1.0");
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_CROSS_PLATFORM_TRANSFER})
    public void testParseNewBackupSchemeFromXml_flagOn_missingPlatform_isParsedButIgnored()
            throws Exception {
        assumeTrue(Flags.enableCrossPlatformTransfer());
        mXpp.setInput(
                new StringReader(
                        "<data-extraction-rules><cross-platform-transfer><include"
                                + " path=\"file.txt\" domain=\"file\" /><platform-specific-params"
                                + " bundleId=\"com.example.app\" teamId=\"0123abcd\""
                                + " contentVersion=\"1.0\" /></cross-platform-transfer>"
                                + "</data-extraction-rules>"));
        FullBackup.BackupScheme bs =
                FullBackup.getBackupSchemeForTest(
                        mContext, BackupAnnotations.BackupDestination.CROSS_PLATFORM_TRANSFER);

        boolean result =
                bs.parseNewBackupSchemeFromXmlLocked(
                        mXpp,
                        CROSS_PLATFORM_TRANSFER,
                        excludesSet,
                        includeMap,
                        mPlatformSpecificParamsMap);

        assertTrue(result);
        assertThat(mPlatformSpecificParamsMap).isEmpty();
    }

    @Test
    @EnableFlags({Flags.FLAG_ENABLE_CROSS_PLATFORM_TRANSFER})
    public void testParseNewBackupSchemeFromXml_flagOn_invalidAttributeCount_throws()
            throws Exception {
        assumeTrue(Flags.enableCrossPlatformTransfer());
        mXpp.setInput(
                new StringReader(
                        "<data-extraction-rules><cross-platform-transfer platform=\"ios\"><include"
                                + " path=\"file.txt\" domain=\"file\" /><platform-specific-params"
                                + " bundleId=\"com.example.app\" teamId=\"0123abcd\""
                                + " contentVersion=\"1.0\" platform=\"ios\" />"
                                + "</cross-platform-transfer>"
                                + "</data-extraction-rules>"));
        FullBackup.BackupScheme bs =
                FullBackup.getBackupSchemeForTest(
                        mContext, BackupAnnotations.BackupDestination.CROSS_PLATFORM_TRANSFER);

        assertThrows(
                "invalid number of attributes in platform-specific-params should throw",
                XmlPullParserException.class,
                () ->
                        bs.parseNewBackupSchemeFromXmlLocked(
                                mXpp,
                                CROSS_PLATFORM_TRANSFER,
                                excludesSet,
                                includeMap,
                                mPlatformSpecificParamsMap));
    }

    @Test
    public void testRestoreFile_setsMtimeFromUnixSeconds() throws Exception {
        File outFile = new File(mContext.getCacheDir(), "restoreFileMtime");
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        pipe[1].close();
        try {
            FullBackup.restoreFile(
                    pipe[0], 0, BackupAgent.TYPE_FILE, 0600, 1_700_000_000L, outFile);

            assertEquals(1_700_000_000_000L, outFile.lastModified());
        } finally {
            pipe[0].close();
            outFile.delete();
        }
    }
}
