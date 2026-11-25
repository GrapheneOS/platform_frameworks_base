package com.android.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ShortcutStringExtrasTest extends BaseShortcutManagerTest {
    private static final int MAX_STRING_CHARS = 512 * 1024;

    private static TypedXmlSerializer newBinarySerializer(ByteArrayOutputStream bytes)
            throws IOException {
        TypedXmlSerializer out = Xml.newBinarySerializer();
        out.setOutput(bytes, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        return out;
    }

    @Test
    public void testLargeStringExtrasReadWithExistingBinaryParser() throws Exception {
        String prefix = "ࠀ".repeat(21845); // 65,535 encoded bytes.
        PersistableBundle nested = new PersistableBundle();
        nested.putString("atLimit", prefix);
        nested.putString("overLimit", prefix + "x");
        nested.putString("unicode", prefix.substring(1) + "😁\u0000\ud800");
        nested.putString("pairs", "😁".repeat(18_000));
        nested.putString("pairAtSplit", "x".repeat(21844) + "😁" + "z".repeat(65536));
        nested.putString("longest", "x".repeat(MAX_STRING_CHARS));
        PersistableBundle extras = new PersistableBundle();
        extras.putPersistableBundle("nested", nested);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TypedXmlSerializer out = newBinarySerializer(bytes);
        ShortcutService.writeTagExtra(out, "extras", extras);
        out.endDocument();

        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(new ByteArrayInputStream(bytes.toByteArray()),
                StandardCharsets.UTF_8.name());
        parser.nextTag();
        PersistableBundle restored = PersistableBundle.restoreFromXml(parser)
                .getPersistableBundle("nested");
        for (String key : nested.keySet()) {
            assertEquals(nested.getString(key), restored.getString(key));
        }
    }

    @Test
    public void testStringOverTheLengthLimitFailsTheWrite() throws Exception {
        PersistableBundle extras = PersistableBundle.forPair("v", "x".repeat(MAX_STRING_CHARS + 1));
        TypedXmlSerializer out = newBinarySerializer(new ByteArrayOutputStream());
        assertThrows(IOException.class, () -> ShortcutService.writeTagExtra(out, "extras", extras));
    }

    @Test
    public void testStringArrayElementsKeepTheirAttributeLimit() throws Exception {
        PersistableBundle extras = new PersistableBundle();
        extras.putStringArray("array", new String[]{"x".repeat(65536)});
        TypedXmlSerializer out = newBinarySerializer(new ByteArrayOutputStream());
        assertThrows(IOException.class, () -> ShortcutService.writeTagExtra(out, "extras", extras));
    }

    @Test
    public void testByteArraysKeepTheirTextLimit() throws Exception {
        // Without byte array support, saveToXml() drops the value before writing it.
        Assume.assumeTrue(PersistableBundle.isValidType(new byte[0]));
        PersistableBundle extras = new PersistableBundle();
        extras.putByteArray("bytes", new byte[32768]); // 65,536 hex digits.
        TypedXmlSerializer out = newBinarySerializer(new ByteArrayOutputStream());
        assertThrows(IOException.class, () -> ShortcutService.writeTagExtra(out, "extras", extras));
    }
}
