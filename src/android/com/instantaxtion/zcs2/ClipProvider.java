package com.instantaxtion.zcs2;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands exported clips (files under the app's files, in clips/) to the app the player shares them with, read-only,
 * by a content:// address that the share grants for that one file: what a FileProvider would do, without a library.
 * Nothing else in the app's files can be reached through it.
 */
public final class ClipProvider extends ContentProvider {
    static final String AUTHORITY = "com.instantaxtion.zcs2.clips";

    static Uri uriFor(String folder, String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(folder).appendPath(name).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** The file an address means, or null if it isn't a clip's file. */
    private File fileOf(Uri uri) {
        if (getContext() == null || uri.getPathSegments().size() != 2) return null;
        try {
            File root = new File(getContext().getFilesDir(), "clips").getCanonicalFile();
            File f = new File(new File(root, uri.getPathSegments().get(0)), uri.getPathSegments().get(1)).getCanonicalFile();
            File parent = f.getParentFile();
            if (parent == null || !root.equals(parent.getParentFile())) return null;
            return f.isFile() ? f : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileOf(uri);
        if (f == null || !"r".equals(mode)) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String p = uri.getLastPathSegment();
        if (p == null) return null;
        return p.endsWith(".gif") ? "image/gif" : p.endsWith(".png") ? "image/png" : "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = fileOf(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int k = 0; k < cols.length; k++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[k])) row[k] = f.getParentFile().getName() + "_" + f.getName();
            else if (OpenableColumns.SIZE.equals(cols[k])) row[k] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
