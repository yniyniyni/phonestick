package streetwalrus.usbmountr

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.preference.PreferenceManager
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mPrefs: SharedPreferences

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusBlob: ImageView
    private lateinit var statusIcon: ImageView
    private lateinit var statusHeadline: TextView
    private lateinit var statusSupporting: TextView
    private lateinit var imageFileSummary: TextView
    private lateinit var imageFileIcon: ImageView
    private lateinit var roSwitch: MaterialSwitch
    private lateinit var cdromSwitch: MaterialSwitch
    private lateinit var serveButton: MaterialButton
    private lateinit var disableButton: MaterialButton

    // The service is the single source of truth for mount state; this
    // activity only renders it.
    private var mountService: MountService? = null
    private val collectJobs = mutableListOf<Job>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val service = (binder as MountService.LocalBinder).service
            mountService = service
            collectJobs += lifecycleScope.launch {
                service.state.collect { renderState(it) }
            }
            collectJobs += lifecycleScope.launch {
                service.messages.collect { showSnackbar(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mountService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        statusCard = findViewById(R.id.status_card)
        statusBlob = findViewById(R.id.status_blob)
        statusIcon = findViewById(R.id.status_icon)
        statusHeadline = findViewById(R.id.status_headline)
        statusSupporting = findViewById(R.id.status_supporting)
        imageFileSummary = findViewById(R.id.image_file_summary)
        imageFileIcon = findViewById(R.id.image_file_icon)
        roSwitch = findViewById(R.id.ro_switch)
        cdromSwitch = findViewById(R.id.cdrom_switch)
        serveButton = findViewById(R.id.serve)
        disableButton = findViewById(R.id.disable)

        // Surface-container ladder, dynamic-color aware (re-themes under Monet).
        val rowColor = SurfaceColors.SURFACE_1.getColor(this)
        findViewById<MaterialCardView>(R.id.row_image).setCardBackgroundColor(rowColor)
        findViewById<MaterialCardView>(R.id.row_ro).setCardBackgroundColor(rowColor)
        findViewById<MaterialCardView>(R.id.row_cdrom).setCardBackgroundColor(rowColor)

        findViewById<MaterialCardView>(R.id.row_image).setOnClickListener {
            val intent = Intent(this, ImageChooserActivity::class.java)
            intent.putExtra(ImageChooserActivity.EXTRA_SELECTED, sourceFile())
            (mountService?.state?.value as? MountState.Mounted)?.let {
                intent.putExtra(ImageChooserActivity.EXTRA_MOUNTED, it.path)
            }
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        }

        roSwitch.isChecked = mPrefs.getBoolean(RO_KEY, true)
        roSwitch.setOnCheckedChangeListener { _, checked ->
            mPrefs.edit().putBoolean(RO_KEY, checked).apply()
        }
        findViewById<MaterialCardView>(R.id.row_ro).setOnClickListener { roSwitch.toggle() }

        cdromSwitch.isChecked = mPrefs.getBoolean(CDROM_KEY, false)
        cdromSwitch.setOnCheckedChangeListener { _, checked ->
            mPrefs.edit().putBoolean(CDROM_KEY, checked).apply()
        }
        findViewById<MaterialCardView>(R.id.row_cdrom).setOnClickListener { cdromSwitch.toggle() }

        findViewById<View>(R.id.footer_licenses).setOnClickListener {
            startActivity(Intent(this, LicenseActivity::class.java))
        }
        findViewById<View>(R.id.footer_github).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))))
        }

        updateImageFileRow()
        renderState(MountState.Unknown)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, MountService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
        mountService = null
        unbindService(connection)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The selected image can be deleted from the library screen (which is
        // left via back, so no activity result fires). Clear the stale
        // selection instead of letting Mount target a ghost path.
        val path = sourceFile()
        if (path.isNotEmpty() && !File(path).exists()) {
            mPrefs.edit().remove(SOURCE_KEY).apply()
            updateImageFileRow()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val path = resultData?.getStringExtra("path") ?: return
            mPrefs.edit().putString(SOURCE_KEY, path).apply()
            updateImageFileRow()
        }
    }

    private fun sourceFile() = mPrefs.getString(SOURCE_KEY, "") ?: ""

    private fun updateImageFileRow() {
        val path = sourceFile()
        if (path.isEmpty()) {
            imageFileSummary.text = getString(R.string.file_picker_nofile)
            imageFileIcon.setImageResource(R.drawable.ic_album)
            return
        }
        val file = File(path)
        val os = OsDetector.detect(file)
        imageFileSummary.text =
                if (os != null) getString(R.string.image_file_summary_os,
                        file.name, getString(os.nameRes))
                else file.name
        imageFileIcon.setImageResource(os?.iconRes ?: R.drawable.ic_os_linux)
    }

    private fun renderState(state: MountState) {
        serveButton.isEnabled = state is MountState.Unmounted
        disableButton.isEnabled = state is MountState.Mounted

        val headlineColor: Int
        val supportingColor: Int
        if (state is MountState.Mounted) {
            statusCard.setCardBackgroundColor(
                    MaterialColors.getColor(statusCard, R.attr.colorPrimaryContainer))
            headlineColor = MaterialColors.getColor(statusCard, R.attr.colorOnPrimaryContainer)
            supportingColor = headlineColor
            statusBlob.alpha = 1f
            statusIcon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(statusCard, R.attr.colorOnPrimary))
            statusHeadline.setText(R.string.status_mounted)
            statusSupporting.text = state.path
        } else {
            statusCard.setCardBackgroundColor(SurfaceColors.SURFACE_2.getColor(this))
            headlineColor = MaterialColors.getColor(statusCard, R.attr.colorOnSurface)
            supportingColor = MaterialColors.getColor(statusCard, R.attr.colorOnSurfaceVariant)
            statusBlob.alpha = 0.35f
            statusIcon.imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(statusCard, R.attr.colorOnSurfaceVariant))
            statusHeadline.setText(
                    if (state is MountState.Unmounted) R.string.status_not_mounted
                    else R.string.status_busy)
            statusSupporting.setText(R.string.status_hint)
        }
        statusHeadline.setTextColor(headlineColor)
        statusSupporting.setTextColor(supportingColor)
    }

    private fun showSnackbar(message: CharSequence) {
        Snackbar.make(findViewById(R.id.activity_main), message, Snackbar.LENGTH_LONG).show()
    }

    @Suppress("unused")
    fun onServeClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        val rawFile = sourceFile()
        if (rawFile.isEmpty()) {
            showSnackbar(getString(R.string.status_hint))
            return
        }
        mountService?.mount(rawFile,
                ro = mPrefs.getBoolean(RO_KEY, true),
                cdrom = mPrefs.getBoolean(CDROM_KEY, false))
    }

    @Suppress("unused")
    fun onDisableClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        mountService?.unmount()
    }
}
