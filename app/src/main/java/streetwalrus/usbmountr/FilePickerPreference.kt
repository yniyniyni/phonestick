package streetwalrus.usbmountr

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.preference.Preference
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

class FilePickerPreference : Preference, ActivityResultDispatcher.ActivityResultHandler {
    val TAG = "FilePickerPreference"

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int)
        : super(context, attrs, defStyleAttr)

    val appContext = context.applicationContext as UsbMountrApplication
    private val mActivityResultId = appContext.mActivityResultDispatcher.registerHandler(this)

    override fun onCreateView(parent: ViewGroup?): View {
        updateSummary()
        return super.onCreateView(parent)
    }
    override fun onPrepareForRemoval() {
        super.onPrepareForRemoval()

        val appContext = context.applicationContext as UsbMountrApplication
        appContext.mActivityResultDispatcher.removeHandler(mActivityResultId)
    }

    override fun onClick() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)

        val activity = context as Activity
        activity.startActivityForResult(intent, mActivityResultId)
    }

    override fun onActivityResult(resultCode: Int, resultData: Intent?) {
        if (resultCode != Activity.RESULT_OK || resultData?.data == null) return
        val uri = resultData.data!!
        Log.d(TAG, "Picked uri=$uri authority=${uri.authority} scheme=${uri.scheme}")

        // First, try to resolve a real filesystem path. The root mass_storage gadget
        // reads the image by path, and root can read /storage, /sdcard, /mnt and /data.
        val resolved = try {
            PathResolver.getPath(context, uri)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException resolving path", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving path", e)
            null
        }

        if (resolved != null && isRootReadablePath(resolved) && File(resolved).exists()) {
            Log.d(TAG, "Using resolved path $resolved")
            persistString(resolved)
            updateSummary()
            return
        }

        // Couldn't get a usable path (Storage Access Framework / scoped storage).
        // Copy the picked file into the app's internal storage so root can read it by path.
        Log.d(TAG, "Path unresolved ($resolved); copying into internal storage")
        ImportTask().execute(uri)
    }

    private fun isRootReadablePath(path: String): Boolean {
        return path.startsWith("/storage/") || path.startsWith("/sdcard") ||
                path.startsWith("/mnt/") || path.startsWith("/data/")
    }

    private fun updateSummary() {
        try {
            val value = getPersistedString("")
            if (value.equals("")) {
                summary = context.getString(R.string.file_picker_nofile)
            } else {
                try {
                    summary = File(value).name
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting file name from path: $value", e)
                    summary = value // Fallback to showing the full path
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating summary: ${e.message}", e)
        }
    }

    private inner class ImportTask : AsyncTask<Uri, Pair<Int, Int>, String?>() {
        private val resolver = context.contentResolver
        private val targetDir = context.filesDir
        private val progressDialog = ProgressDialog(context)

        override fun onPreExecute() {
            progressDialog.setTitle(R.string.image_chooser_copying_dialog)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            progressDialog.isIndeterminate = true
            progressDialog.setCancelable(false)
            progressDialog.show()
        }

        override fun doInBackground(vararg params: Uri): String? {
            val uri = params[0]
            try {
                val pfd = resolver.openFileDescriptor(uri, "r") ?: return null
                pfd.use {
                    val totalSize = it.statSize // -1 if unknown
                    var name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "image.bin"
                    name = name.replace('/', '_')

                    var target = File(targetDir, name)
                    if (target.exists()) {
                        var i = 1
                        val dot = name.lastIndexOf('.').let { idx -> if (idx < 0) name.length else idx }
                        val base = name.substring(0, dot)
                        val ext = name.substring(dot)
                        do {
                            target = File(targetDir, "%s.%d%s".format(base, i, ext))
                            i++
                        } while (target.exists())
                    }

                    var transferred = 0L
                    var counter = 0
                    FileInputStream(it.fileDescriptor).use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                if (isCancelled) {
                                    target.delete()
                                    return null
                                }
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                transferred += read
                                counter++
                                if (counter % 16 == 0) {
                                    publishProgress(Pair(
                                            (transferred shr 10).toInt(),
                                            (totalSize shr 10).toInt()))
                                }
                            }
                        }
                    }
                    return target.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing picked file: ${e.message}", e)
                return null
            }
        }

        private fun queryDisplayName(uri: Uri): String? {
            return try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query display name", e)
                null
            }
        }

        override fun onProgressUpdate(vararg values: Pair<Int, Int>) {
            val (progress, max) = values[0]
            if (max <= 0) {
                progressDialog.isIndeterminate = true
            } else {
                progressDialog.isIndeterminate = false
                progressDialog.max = max
                progressDialog.progress = progress
            }
        }

        override fun onPostExecute(result: String?) {
            if (progressDialog.isShowing) progressDialog.dismiss()
            if (result != null) {
                Log.d(TAG, "Imported to $result")
                persistString(result)
                updateSummary()
            } else {
                Toast.makeText(context.applicationContext,
                        context.getString(R.string.file_picker_denied),
                        Toast.LENGTH_LONG).show()
            }
        }
    }
}
