package streetwalrus.usbmountr;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.lang.reflect.Method;
import android.text.TextUtils;

public class PathResolver {
    private static final String TAG = "PathResolver";

    public static String getPath(Context context, Uri uri) {
        try {
            // DocumentProvider
            if (DocumentsContract.isDocumentUri(context, uri)) {
                // ExternalStorageProvider
                if (isExternalStorageDocument(uri)) {
                    final String docId = DocumentsContract.getDocumentId(uri);
                    final String[] split = docId.split(":");
                    final String type = split[0];

                    if ("primary".equalsIgnoreCase(type)) {
                        return Environment.getExternalStorageDirectory() + "/" + split[1];
                    } else {
                        // Secondary volumes (SD cards): match the volume by UUID.
                        StorageManager mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);

                        try {
                            for (StorageVolume volume : mStorageManager.getStorageVolumes()) {
                                final String state = volume.getState();
                                final boolean mounted = Environment.MEDIA_MOUNTED.equals(state)
                                        || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
                                if (!mounted) continue;

                                //Primary storage is already handled.
                                if (volume.isPrimary() && volume.isEmulated()) continue;

                                String uuid = volume.getUuid();
                                if (uuid != null && uuid.equals(type)) {
                                    // StorageVolume.getDirectory() only became public in
                                    // API 30; reflect the hidden getPath() below that.
                                    Method getPath = StorageVolume.class.getMethod("getPath");
                                    return getPath.invoke(volume) + "/" + split[1];
                                }
                            }
                        }
                        catch (Exception ex) {
                            Toast.makeText(context, ex.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                // DownloadsProvider
                else if (isDownloadsDocument(uri)) {
                    final String id = DocumentsContract.getDocumentId(uri);

                    if (!TextUtils.isEmpty(id)) {
                        if (id.startsWith("raw:")) {
                            return id.replaceFirst("raw:", "");
                        }
                        try {
                            final Uri contentUri = ContentUris.withAppendedId(
                                    Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                            return getDataColumn(context, contentUri, null, null);
                        } catch (NumberFormatException e) {
                             return null;
                        }
                    }
                    final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
                    return getDataColumn(context, contentUri, null, null);
                }
                // MediaProvider
                else if (isMediaDocument(uri)) {
                    final String docId = DocumentsContract.getDocumentId(uri);
                    final String[] split = docId.split(":");
                    final String type = split[0];
                    Uri contentUri = null;
                    if ("image".equals(type)) {
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    } else if ("video".equals(type)) {
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                    } else if ("audio".equals(type)) {
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    }
                    final String selection = "_id=?";
                    final String[] selectionArgs = new String[] {split[1]};
                    return getDataColumn(context, contentUri, selection, selectionArgs);
                }
            }
            // MediaStore (and general)
            else if ("content".equalsIgnoreCase(uri.getScheme())) {
                try {
                    // Return the remote address
                    if (isGooglePhotosUri(uri))
                        return uri.getLastPathSegment();
                    return getDataColumn(context, uri, null, null);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error processing content URI: " + e.getMessage(), e);
                    return null;
                }
            }
            // File
            else if ("file".equalsIgnoreCase(uri.getScheme())) {
                try {
                    return uri.getPath();
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Error processing file URI: " + e.getMessage(), e);
                    return null;
                }
            }
            return null;
        } catch (Exception e) {
            android.util.Log.e(TAG, "Critical error in getPath: " + e.getMessage(), e);
            Toast.makeText(context, context.getString(R.string.path_resolver_error, e.getMessage()), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = { column };
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }

    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is Google Photos.
     */
    public static boolean isGooglePhotosUri(Uri uri) {
        return "com.google.android.apps.photos.content".equals(uri.getAuthority());
    }
}
