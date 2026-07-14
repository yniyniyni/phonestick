package streetwalrus.usbmountr

import android.content.Intent
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.shape.ShapeAppearanceModel
import java.io.InputStreamReader

class LicenseActivity : AppCompatActivity() {
    private val TAG = "LicenseActivity"

    private data class License(val name: String, val type: String?, val file: String?, val url: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val licenseList: MutableList<License> = mutableListOf()
        val xrp = resources.getXml(R.xml.licenses)
        while (xrp.eventType != XmlResourceParser.END_DOCUMENT) {
            xrp.next()
            if (xrp.eventType == XmlResourceParser.START_TAG && xrp.name == "license") {
                licenseList.add(License(
                        xrp.getAttributeValue(null, "name"),
                        xrp.getAttributeValue(null, "type"),
                        xrp.getAttributeValue(null, "file"),
                        xrp.getAttributeValue(null, "url")))
            }
        }

        val list = findViewById<RecyclerView>(R.id.license_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = LicenseAdapter(licenseList)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showLicense(license: License) {
        val content = layoutInflater.inflate(R.layout.dialog_license, null)
        val textView = content.findViewById<TextView>(R.id.textView)
        textView.text = try {
            InputStreamReader(assets.open("licenses/${license.file}")).readText()
        } catch (e: Exception) {
            Log.w(TAG, "No license text for ${license.name}", e)
            listOfNotNull(license.type, license.url).joinToString("\n")
        }
        textView.movementMethod = ScrollingMovementMethod()

        val builder = MaterialAlertDialogBuilder(this)
                .setTitle(license.name)
                .setView(content)
                .setNegativeButton(R.string.licenses_close, null)
        if (license.url != null) {
            builder.setPositiveButton(R.string.licenses_upstream) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(license.url)))
            }
        }
        builder.show()
    }

    private inner class LicenseAdapter(private val licenses: List<License>)
        : RecyclerView.Adapter<LicenseAdapter.LicenseViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LicenseViewHolder {
            val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.license_row, parent, false)
            return LicenseViewHolder(view)
        }

        override fun getItemCount() = licenses.size

        override fun onBindViewHolder(holder: LicenseViewHolder, position: Int) {
            val license = licenses[position]
            holder.name.text = license.name
            if (license.type != null) {
                holder.type.visibility = View.VISIBLE
                holder.type.text = license.type
            } else {
                holder.type.visibility = View.GONE
            }

            val shapeStyle = when {
                licenses.size == 1 -> R.style.ShapeAppearance_PhoneStick_ListItem_Single
                position == 0 -> R.style.ShapeAppearance_PhoneStick_ListItem_First
                position == licenses.size - 1 -> R.style.ShapeAppearance_PhoneStick_ListItem_Last
                else -> R.style.ShapeAppearance_PhoneStick_ListItem_Middle
            }
            holder.card.shapeAppearanceModel =
                    ShapeAppearanceModel.builder(holder.card.context, shapeStyle, 0).build()
            holder.card.setCardBackgroundColor(
                    SurfaceColors.SURFACE_1.getColor(holder.card.context))

            holder.itemView.setOnClickListener { showLicense(license) }
        }

        inner class LicenseViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card = view as MaterialCardView
            val name = view.findViewById<TextView>(R.id.license_name)!!
            val type = view.findViewById<TextView>(R.id.license_type)!!
        }
    }
}
