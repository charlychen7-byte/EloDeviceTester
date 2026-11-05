package com.elotouch.devicetester.modules.usbstorage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;

/**
 * File-copy primitives for the USB copy-speed test. Works on content:// URIs
 * (picker / SAF / MediaStore) and file:// URIs (pre-Q Downloads), so the same
 * copy loop serves both directions.
 */
final class UsbCopyEngine {

    /** Copy prefix so test files are easy to recognise and clean up. */
    static final String PREFIX = "UsbTest_";

    private UsbCopyEngine() { }

    /** Outcome of a completed copy. Speed includes the final fsync. */
    static final class Result {
        final long bytes;
        final double seconds;

        Result(long bytes, double seconds) {
            this.bytes = bytes;
            this.seconds = seconds;
        }

        double mbPerSecond() {
            return seconds > 0 ? bytes / (1024.0 * 1024.0) / seconds : 0;
        }
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    /**
     * Create the destination file in public Downloads. API 29+ goes through
     * MediaStore (no permission needed); earlier releases write the file directly,
     * which requires WRITE_EXTERNAL_STORAGE granted by the caller.
     */
    static Uri createDownloadsTarget(Context c, String name, String mime) throws IOException {
        ContentResolver cr = c.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) {
                throw new IOException("Cannot create Downloads entry 无法在 Downloads 创建文件");
            }
            return u;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create Downloads dir 无法创建 Downloads 目录");
        }
        return Uri.fromFile(new File(dir, name));
    }

    /** Clear the pending flag so the MediaStore entry becomes visible (API 29+). */
    static void finishDownloadsTarget(Context c, Uri uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && "content".equals(uri.getScheme())) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            c.getContentResolver().update(uri, v, null, null);
        }
    }

    /**
     * Copy {@code src} to {@code dst} and time it. The clock stops only after
     * the destination is fsync'd, so the figure is real write speed, not cache.
     *
     * @return the result, or {@code null} if {@code cancelled} turned true mid-copy
     */
    static Result copy(ContentResolver cr, Uri src, Uri dst, long total,
                       BooleanSupplier cancelled, Progress progress) throws IOException {
        byte[] buf = new byte[1 << 20];  // 1 MiB chunks
        try (InputStream in = cr.openInputStream(src)) {
            if (in == null) {
                throw new IOException("Cannot read source 无法读取源文件");
            }
            ParcelFileDescriptor pfd = cr.openFileDescriptor(dst, "w");
            if (pfd == null) {
                throw new IOException("Cannot write target 无法写入目标文件");
            }
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
                long done = 0;
                long start = System.nanoTime();
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    progress.onProgress(done, total);
                }
                out.flush();
                pfd.getFileDescriptor().sync();
                long elapsed = System.nanoTime() - start;
                return new Result(done, elapsed / 1e9);
            }
        }
    }

    /** Best-effort delete of a Downloads copy (content or file URI). */
    static void deleteDownloadsCopy(Context c, Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                new File(uri.getPath()).delete();
            } else {
                c.getContentResolver().delete(uri, null, null);
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** Best-effort delete of a document on the USB volume. */
    static void deleteDocument(Context c, Uri uri) {
        try {
            DocumentsContract.deleteDocument(c.getContentResolver(), uri);
        } catch (Exception ignored) {
            // best effort
        }
    }

    /** True when a SAF tree URI points at the volume root (root document id ends with ':'). */
    static boolean isTreeRoot(Uri treeUri) {
        if (treeUri == null || !DocumentsContract.isTreeUri(treeUri)) {
            return false;
        }
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        return docId != null && docId.endsWith(":");
    }
}
