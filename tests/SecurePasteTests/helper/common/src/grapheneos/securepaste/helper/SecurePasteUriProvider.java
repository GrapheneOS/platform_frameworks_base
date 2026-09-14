package grapheneos.securepaste.helper;

import android.content.ClipDescription;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class SecurePasteUriProvider extends ContentProvider {
    public static final String DATA = "secure-paste-uri-provider-data";

    public static Uri getUri(String packageName) {
        return getUri(packageName, "clip");
    }

    static Uri getIntentUri(String packageName) {
        return getUri(packageName, "intent-clip");
    }

    private static Uri getUri(String packageName, String path) {
        return new Uri.Builder()
                .scheme("content")
                .authority(packageName + ".uri")
                .path(path)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Unsupported mode " + mode);
        }
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out =
                             new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write(DATA.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            }, "SecurePasteUriPipe").start();
            return pipe[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "text/plain";
    }

    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        return ClipDescription.compareMimeTypes("text/plain", mimeTypeFilter)
                ? new String[] {"text/plain"} : null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
