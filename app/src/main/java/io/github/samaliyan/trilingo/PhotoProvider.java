package io.github.samaliyan.trilingo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the camera app write the photo for "Scan text" into this app's cache (one file, no other access).
 */
public final class PhotoProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.samaliyan.trilingo.photo";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/scan.jpg");

    static File file(android.content.Context c) {
        File dir = new File(c.getCacheDir(), "scan");
        if (!dir.exists() && !dir.mkdirs()) return new File(c.getCacheDir(), "scan.jpg");
        return new File(dir, "scan.jpg");
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/scan.jpg".equals(uri.getPath()) || getContext() == null) throw new FileNotFoundException(uri.toString());
        int m = mode.contains("w") ? ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(file(getContext()), m);
    }

    @Override public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues v) {
        return null;
    }

    @Override public int delete(Uri uri, String s, String[] a) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues v, String s, String[] a) {
        return 0;
    }
}
