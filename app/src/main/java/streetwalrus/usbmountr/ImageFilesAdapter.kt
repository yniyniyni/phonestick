package streetwalrus.usbmountr

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.File

class ImageFilesAdapter(
        private val directory: File,
        private val activity: ImageChooserActivity,
        private val selectedPath: String?
) : RecyclerView.Adapter<ImageFilesAdapter.ImageFilesViewHolder>() {

    private var fileList = listFiles()

    private fun listFiles(): List<File> =
            (directory.listFiles() ?: emptyArray()).filter { it.isFile }.sortedBy { it.name }

    fun refresh() {
        fileList = listFiles()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageFilesViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.image_chooser_row, parent, false)
        return ImageFilesViewHolder(view)
    }

    override fun getItemCount(): Int {
        return fileList.size
    }

    override fun onBindViewHolder(holder: ImageFilesViewHolder, position: Int) {
        val context = holder.view.context
        val file = fileList[position]
        val sizeMib = file.length().toDouble() / (1 shl 20)
        val os = OsDetector.detect(file)

        holder.filename.text = file.name
        holder.fileSize.text =
                if (os != null) context.getString(R.string.image_chooser_filesize_os,
                        sizeMib, context.getString(os.nameRes))
                else context.getString(R.string.image_chooser_filesize_mib, sizeMib)
        // Undetected images still get the Tux glyph; only distro-specific
        // glyphs come from detection.
        holder.icon.setImageResource(os?.iconRes ?: R.drawable.ic_os_linux)

        // Expressive grouped rounding: outer corners of the group are large,
        // inner corners small.
        val shapeStyle = when {
            fileList.size == 1 -> R.style.ShapeAppearance_PhoneStick_ListItem_Single
            position == 0 -> R.style.ShapeAppearance_PhoneStick_ListItem_First
            position == fileList.size - 1 -> R.style.ShapeAppearance_PhoneStick_ListItem_Last
            else -> R.style.ShapeAppearance_PhoneStick_ListItem_Middle
        }
        holder.card.shapeAppearanceModel =
                ShapeAppearanceModel.builder(context, shapeStyle, 0).build()

        val selected = file.path == selectedPath
        holder.card.setCardBackgroundColor(
                if (selected) MaterialColors.getColor(holder.card, R.attr.colorSecondaryContainer)
                else SurfaceColors.SURFACE_1.getColor(context))
        val contentColor = MaterialColors.getColor(holder.card,
                if (selected) R.attr.colorOnSecondaryContainer else R.attr.colorOnSurface)
        val supportingColor = MaterialColors.getColor(holder.card,
                if (selected) R.attr.colorOnSecondaryContainer else R.attr.colorOnSurfaceVariant)
        holder.filename.setTextColor(contentColor)
        holder.fileSize.setTextColor(supportingColor)
        holder.icon.setColorFilter(supportingColor)

        holder.view.setOnClickListener {
            activity.returnSelection(file.path)
        }
        holder.view.setOnLongClickListener {
            activity.confirmDelete(file)
            true
        }
    }

    class ImageFilesViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val card = view as MaterialCardView
        val icon = view.findViewById<ImageView>(R.id.row_icon)!!
        val filename = view.findViewById<TextView>(R.id.filename)!!
        val fileSize = view.findViewById<TextView>(R.id.file_size)!!
    }
}
