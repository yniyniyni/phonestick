package streetwalrus.usbmountr

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.io.FileInputStream

class ImageChooserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SELECTED = "selected"
        private const val REQUEST_ADD_IMAGE = 1
        private const val TAG = "ImageChooserActivity"
    }

    private var directory = File("/")
    private lateinit var adapter: ImageFilesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directory = filesDir
        setContentView(R.layout.activity_image_chooser)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.itemAnimator = DefaultItemAnimator()

        adapter = ImageFilesAdapter(directory, this, intent.getStringExtra(EXTRA_SELECTED))
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            startActivityForResult(intent, REQUEST_ADD_IMAGE)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ADD_IMAGE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        // Prefer a direct root-readable filesystem path — the mass_storage gadget reads
        // the image by path, so no copy is needed. Fall back to copying the file into
        // the app's internal image library.
        val resolved = try {
            PathResolver.getPath(this, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving path", e)
            null
        }

        if (resolved != null && isRootReadablePath(resolved) && File(resolved).exists()) {
            Log.d(TAG, "Using resolved path $resolved")
            returnSelection(resolved)
            return
        }

        Log.d(TAG, "Path unresolved ($resolved); copying into internal storage")
        CopyInTask(this, directory).execute(uri)
    }

    private fun isRootReadablePath(path: String): Boolean {
        return path.startsWith("/storage/") || path.startsWith("/sdcard") ||
                path.startsWith("/mnt/") || path.startsWith("/data/")
    }

    fun returnSelection(path: String) {
        val result = Intent()
        result.putExtra("path", path)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private class CopyInTask(private val activity: ImageChooserActivity, private val directory: File)
        : AsyncTask<Uri, Pair<Int, Int>, Boolean>() {
        private val TAG = "CopyInTask"
        private val contentResolver = activity.contentResolver

        private lateinit var dialog: Dialog
        private lateinit var progressBar: LinearProgressIndicator
        private lateinit var filenameView: TextView

        override fun onPreExecute() {
            val content = activity.layoutInflater.inflate(R.layout.dialog_copying, null)
            progressBar = content.findViewById(R.id.copy_progress)
            filenameView = content.findViewById(R.id.copy_filename)
            dialog = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.image_chooser_copying_dialog)
                    .setView(content)
                    .setCancelable(false)
                    .create()
            dialog.show()
        }

        override fun doInBackground(vararg params: Uri): Boolean {
            try {
                params.forEach { uri ->
                    val fileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return false
                    fileDescriptor.use { fd ->
                        val totalSize = fd.statSize // -1 if unknown

                        var filename: String = queryDisplayName(uri) ?: uri.lastPathSegment ?: "image.bin"
                        filename = filename.replace('/', '_')
                        if (File(directory, filename).exists()) {
                            var i = 1
                            val index = filename.lastIndexOf('.').let { if (it < 0) filename.length else it }
                            val basename = filename.substring(0, index)
                            val extension = filename.substring(index)
                            do {
                                filename = "%s.%d%s".format(basename, i, extension)
                                i++
                            } while (File(directory, filename).exists())
                        }
                        activity.runOnUiThread { filenameView.text = filename }

                        var transferred = 0L
                        var counter = 0
                        FileInputStream(fd.fileDescriptor).use { input ->
                            File(directory, filename).outputStream().use { output ->
                                val buffer = ByteArray(1 shl 16)
                                while (true) {
                                    if (isCancelled) return false
                                    val readBytes = input.read(buffer)
                                    if (readBytes <= 0) break
                                    output.write(buffer, 0, readBytes)
                                    transferred += readBytes
                                    counter++
                                    if (counter % 16 == 0) publishProgress(Pair(
                                            (transferred shr 10).toInt(),
                                            (totalSize shr 10).toInt()))
                                }
                            }
                        }
                    }
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error copying image: ${e.message}", e)
                return false
            }
        }

        private fun queryDisplayName(uri: Uri): String? {
            return try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
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
                progressBar.isIndeterminate = true
            } else {
                if (progressBar.isIndeterminate) {
                    progressBar.visibility = View.INVISIBLE
                    progressBar.isIndeterminate = false
                    progressBar.visibility = View.VISIBLE
                }
                progressBar.max = max
                progressBar.progress = progress
            }
        }

        override fun onPostExecute(result: Boolean) {
            if (dialog.isShowing) dialog.dismiss()
            if (result) {
                activity.adapter.refresh()
            } else {
                Toast.makeText(activity.applicationContext,
                        activity.getString(R.string.file_picker_denied),
                        Toast.LENGTH_LONG).show()
            }
        }
    }
}
