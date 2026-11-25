package com.android.server.pm;

import android.os.PersistableBundle;

import com.android.internal.util.XmlSerializerWrapper;
import com.android.modules.utils.BinaryXmlSerializer;
import com.android.modules.utils.ModifiedUtf8;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * Writes a {@code <string>} value that exceeds the binary XML token limit as adjacent TEXT tokens.
 * Everything else is forwarded to the wrapped serializer unchanged.
 */
final class ShortcutStringExtras extends XmlSerializerWrapper implements TypedXmlSerializer {
    private static final int MAX_TOKEN_BYTES = 0xffff;
    // Modified UTF-8 needs at most three bytes per UTF-16 code unit.
    private static final int MAX_CHUNK_CHARS = MAX_TOKEN_BYTES / 3;
    // Readers join the tokens of one string in quadratic time. A longer string cannot arrive in
    // a single Binder transaction.
    private static final int MAX_STRING_CHARS = 512 * 1024;

    private final TypedXmlSerializer mOut;

    private ShortcutStringExtras(TypedXmlSerializer out) {
        super(out);
        mOut = out;
    }

    static void write(TypedXmlSerializer out, PersistableBundle bundle)
            throws IOException, XmlPullParserException {
        if (out instanceof BinaryXmlSerializer) out = new ShortcutStringExtras(out);
        bundle.saveToXml(out);
    }

    @Override
    public XmlSerializer text(String value) throws IOException {
        // Other text, such as the hex digits of a <byte-array>, keeps its limit.
        if (value.length() <= MAX_CHUNK_CHARS || !"string".equals(getName())
                || (value.length() <= MAX_TOKEN_BYTES
                        && ModifiedUtf8.countBytes(value, false) <= MAX_TOKEN_BYTES)) {
            return super.text(value);
        }
        if (value.length() > MAX_STRING_CHARS) {
            throw new IOException("Shortcut extra string too long: " + value.length());
        }
        // Binary XML readers join adjacent TEXT tokens. Keep surrogate pairs in one token.
        int start = 0;
        while (value.length() - start > MAX_CHUNK_CHARS) {
            int end = start + MAX_CHUNK_CHARS;
            if (Character.isSurrogatePair(value.charAt(end - 1), value.charAt(end))) end--;
            super.text(value.substring(start, end));
            start = end;
        }
        return super.text(value.substring(start));
    }

    // XmlUtils#makeTyped would turn typed attributes into strings. Keep their encoding.

    @Override
    public XmlSerializer attributeInterned(String namespace, String name, String value)
            throws IOException {
        return mOut.attributeInterned(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeBytesHex(String namespace, String name, byte[] value)
            throws IOException {
        return mOut.attributeBytesHex(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeBytesBase64(String namespace, String name, byte[] value)
            throws IOException {
        return mOut.attributeBytesBase64(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeInt(String namespace, String name, int value)
            throws IOException {
        return mOut.attributeInt(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeIntHex(String namespace, String name, int value)
            throws IOException {
        return mOut.attributeIntHex(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeLong(String namespace, String name, long value)
            throws IOException {
        return mOut.attributeLong(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeLongHex(String namespace, String name, long value)
            throws IOException {
        return mOut.attributeLongHex(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeFloat(String namespace, String name, float value)
            throws IOException {
        return mOut.attributeFloat(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeDouble(String namespace, String name, double value)
            throws IOException {
        return mOut.attributeDouble(namespace, name, value);
    }

    @Override
    public XmlSerializer attributeBoolean(String namespace, String name, boolean value)
            throws IOException {
        return mOut.attributeBoolean(namespace, name, value);
    }
}
