package streetwalrus.usbmountr

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import java.io.File

class ImageFilesAdapter(directory: File, val activity: Activity) : RecyclerView.Adapter<ImageFilesAdapter.ImageFilesViewHolder>() {
    private val TAG = "ImageFilesAdapter"
    private var fileList = directory.listFiles()!!

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageFilesViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.image_chooser_row, parent, false)
        return ImageFilesViewHolder(view)
    }

    override fun getItemCount(): Int {
        return fileList.size
    }

    override fun onBindViewHolder(holder: ImageFilesViewHolder, position: Int) {
        try {
            if (position >= fileList.size) {
                android.util.Log.w(TAG, "Position $position is out of bounds for fileList size ${fileList.size}")
                return
            }

            val file = fileList[position]
            val size = try {
                if(file.isDirectory)
                    0.0
                else
                    file.length().toDouble() / (1 shl 20)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error getting file size for: ${file.path}", e)
                0.0
            }

            try {
                holder.filename.text = file.name
                holder.fileSize.text = holder.fileSize.context.getString(R.string.image_chooser_filesize_mib, size)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error setting file info in view holder", e)
                holder.filename.text = "Error loading file"
                holder.fileSize.text = "0.0 MiB"
            }

            if(file.isFile) {
                holder.view.setOnClickListener {
                    try {
                        val result = Intent()
                        result.putExtra("path", file.path)
                        activity.setResult(Activity.RESULT_OK, result)
                        activity.finish()
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error handling file selection: ${e.message}", e)
                        Toast.makeText(activity, activity.getString(R.string.file_error_access, e.message), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Critical error in onBindViewHolder: ${e.message}", e)
        }
    }

    class ImageFilesViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val filename = view.findViewById<TextView>(R.id.filename)!!
        val fileSize = view.findViewById<TextView>(R.id.file_size)!!
    }

}
