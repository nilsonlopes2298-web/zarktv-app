package cfd.zarktvqx.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Entrega o APK baixado ao instalador do Android (sem AndroidX). Só a pasta "apks" do app. */
public class ApkProvider extends ContentProvider {
    static final String AUTORIDADE = "cfd.zarktvqx.launcher.apk";

    static Uri uri(File f) {
        return Uri.parse("content://" + AUTORIDADE + "/" + f.getName());
    }

    private File arquivo(Uri u) throws FileNotFoundException {
        String nome = u.getLastPathSegment();
        if (nome == null || nome.contains("/") || nome.contains("..")) throw new FileNotFoundException();
        File f = new File(new File(getContext().getCacheDir(), "apks"), nome);
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(arquivo(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri u) { return "application/vnd.android.package-archive"; }

    @Override
    public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        try {
            File f = arquivo(u);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
