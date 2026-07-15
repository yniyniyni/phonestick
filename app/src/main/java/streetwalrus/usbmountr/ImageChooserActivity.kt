package streetwalrus.usbmountr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class ImageChooserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SELECTED = "selected"
        const val EXTRA_MOUNTED = "mounted"
        private const val REQUEST_ADD_IMAGE = 1
        private const val TAG = "ImageChooserActivity"

        // Refuse copies that would leave less than this much internal storage.
        private const val FREE_SPACE_MARGIN = 64L shl 20 // 64 MiB
    }

    private var directory = File("/")
    private var mountedPath: String? = null
    private lateinit var adapter: ImageFilesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directory = filesDir
        mountedPath = intent.getStringExtra(EXTRA_MOUNTED)
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

        // Copy the picked file into the app's internal image library. The root
        // mass_storage gadget reads the image by path, and a library copy can't be
        // moved or deleted out from under an active mount.
        startCopy(uri)
    }

    fun returnSelection(path: String) {
        val result = Intent()
        result.putExtra("path", path)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    fun confirmDelete(file: File) {
        if (file.path == mountedPath) {
            // Deleting the image backing an active USB mount would break the
            // next mount and frees no space until unmount anyway.
            Snackbar.make(findViewById(R.id.activity_image_chooser),
                    R.string.image_chooser_delete_mounted, Snackbar.LENGTH_LONG).show()
            return
        }
        val sizeMib = file.length().toDouble() / (1 shl 20)
        MaterialAlertDialogBuilder(this)
                .setTitle(file.name)
                .setMessage(getString(R.string.image_chooser_delete_message, sizeMib))
                .setPositiveButton(R.string.image_chooser_delete) { _, _ ->
                    if (!file.delete()) {
                        Snackbar.make(findViewById(R.id.activity_image_chooser),
                                R.string.image_chooser_delete_failed, Snackbar.LENGTH_LONG).show()
                    }
                    adapter.refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private sealed class CopyResult {
        object Done : CopyResult()
        object Failed : CopyResult()
        data class NoSpace(val neededMib: Double, val freeMib: Double) : CopyResult()
    }

    private fun startCopy(uri: Uri) {
        val content = layoutInflater.inflate(R.layout.dialog_copying, null)
        val progressBar = content.findViewById<LinearProgressIndicator>(R.id.copy_progress)
        val filenameView = content.findViewById<TextView>(R.id.copy_filename)
        val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(R.string.image_chooser_copying_dialog)
                .setView(content)
                .setCancelable(false)
                .create()
        dialog.show()

        lifecycleScope.launch {
            val result = try {
                copyToLibrary(uri,
                        onFilename = { filenameView.text = it },
                        onProgress = { transferredKib, totalKib ->
                            if (totalKib <= 0) {
                                progressBar.isIndeterminate = true
                            } else {
                                if (progressBar.isIndeterminate) {
                                    progressBar.visibility = View.INVISIBLE
                                    progressBar.isIndeterminate = false
                                    progressBar.visibility = View.VISIBLE
                                }
                                progressBar.max = totalKib
                                progressBar.progress = transferredKib
                            }
                        })
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
            when (result) {
                CopyResult.Done -> adapter.refresh()
                CopyResult.Failed -> Toast.makeText(applicationContext,
                        getString(R.string.file_picker_denied), Toast.LENGTH_LONG).show()
                is CopyResult.NoSpace -> Snackbar.make(findViewById(R.id.activity_image_chooser),
                        getString(R.string.image_chooser_no_space, result.neededMib, result.freeMib),
                        Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Streams the picked document into the library directory. Checks free
     * space up front when the size is known, and guarantees a partial file
     * never survives a failed or cancelled copy.
     */
    private suspend fun copyToLibrary(
            uri: Uri,
            onFilename: (String) -> Unit,
            onProgress: (transferredKib: Int, totalKib: Int) -> Unit
    ): CopyResult = withContext(Dispatchers.IO) {
        var destination: File? = null
        var finished = false
        try {
            val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext CopyResult.Failed
            fileDescriptor.use { fd ->
                val totalSize = fd.statSize // -1 if unknown
                if (totalSize > 0 && totalSize > directory.usableSpace - FREE_SPACE_MARGIN) {
                    finished = true // nothing was written
                    return@withContext CopyResult.NoSpace(
                            totalSize.toDouble() / (1 shl 20),
                            directory.usableSpace.toDouble() / (1 shl 20))
                }

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
                withContext(Dispatchers.Main) { onFilename(filename) }

                destination = File(directory, filename)
                var transferred = 0L
                var counter = 0
                FileInputStream(fd.fileDescriptor).use { input ->
                    destination!!.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive()
                            val readBytes = input.read(buffer)
                            if (readBytes <= 0) break
                            output.write(buffer, 0, readBytes)
                            transferred += readBytes
                            counter++
                            if (counter % 16 == 0) {
                                val kib = (transferred shr 10).toInt()
                                val totalKib = (totalSize shr 10).toInt()
                                withContext(Dispatchers.Main) { onProgress(kib, totalKib) }
                            }
                        }
                    }
                }
            }
            finished = true
            CopyResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error copying image: ${e.message}", e)
            CopyResult.Failed
        } finally {
            // A partial file must never appear in the library.
            if (!finished) destination?.delete()
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
}
