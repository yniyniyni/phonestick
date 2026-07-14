package streetwalrus.usbmountr

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import eu.chainfire.libsuperuser.Shell
import java.io.File

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private lateinit var mPrefs: SharedPreferences

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusBlob: ImageView
    private lateinit var statusHeadline: TextView
    private lateinit var statusSupporting: TextView
    private lateinit var imageFileSummary: TextView
    private lateinit var roSwitch: MaterialSwitch

    private var mounted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        statusCard = findViewById(R.id.status_card)
        statusBlob = findViewById(R.id.status_blob)
        statusHeadline = findViewById(R.id.status_headline)
        statusSupporting = findViewById(R.id.status_supporting)
        imageFileSummary = findViewById(R.id.image_file_summary)
        roSwitch = findViewById(R.id.ro_switch)

        // Surface-container ladder, dynamic-color aware (re-themes under Monet).
        val rowColor = SurfaceColors.SURFACE_1.getColor(this)
        findViewById<MaterialCardView>(R.id.row_image).setCardBackgroundColor(rowColor)
        findViewById<MaterialCardView>(R.id.row_ro).setCardBackgroundColor(rowColor)

        findViewById<MaterialCardView>(R.id.row_image).setOnClickListener {
            val intent = Intent(this, ImageChooserActivity::class.java)
            intent.putExtra(ImageChooserActivity.EXTRA_SELECTED, sourceFile())
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        }

        roSwitch.isChecked = mPrefs.getBoolean(RO_KEY, true)
        roSwitch.setOnCheckedChangeListener { _, checked ->
            mPrefs.edit().putBoolean(RO_KEY, checked).apply()
        }
        findViewById<MaterialCardView>(R.id.row_ro).setOnClickListener { roSwitch.toggle() }

        updateImageFileRow()
        updateStatusHero()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_licenses -> {
                val intent = Intent(this, LicenseActivity::class.java)
                startActivity(intent)
            }
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val path = resultData?.getStringExtra("path") ?: return
            mPrefs.edit().putString(SOURCE_KEY, path).apply()
            updateImageFileRow()
            updateStatusHero()
        }
    }

    private fun sourceFile() = mPrefs.getString(SOURCE_KEY, "") ?: ""

    private fun updateImageFileRow() {
        val path = sourceFile()
        imageFileSummary.text =
                if (path.isEmpty()) getString(R.string.file_picker_nofile) else File(path).name
    }

    private fun updateStatusHero() {
        val headlineColor: Int
        val supportingColor: Int
        if (mounted) {
            statusCard.setCardBackgroundColor(
                    MaterialColors.getColor(statusCard, R.attr.colorPrimaryContainer))
            headlineColor = MaterialColors.getColor(statusCard, R.attr.colorOnPrimaryContainer)
            supportingColor = headlineColor
            statusBlob.alpha = 1f
            statusHeadline.setText(R.string.status_mounted)
            statusSupporting.text = sourceFile()
        } else {
            statusCard.setCardBackgroundColor(SurfaceColors.SURFACE_2.getColor(this))
            headlineColor = MaterialColors.getColor(statusCard, R.attr.colorOnSurface)
            supportingColor = MaterialColors.getColor(statusCard, R.attr.colorOnSurfaceVariant)
            statusBlob.alpha = 0.35f
            statusHeadline.setText(R.string.status_not_mounted)
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

        // Escape the file name to avoid bugs in the shell
        // Could use some finer filters but who cares
        val file = "(.)".toRegex().replace(rawFile, "\\\\$1")

        val ro = if (mPrefs.getBoolean(RO_KEY, true)) "1" else "0"

        UsbScript().execute(file, ro, "1")
    }

    @Suppress("unused")
    fun onDisableClicked(@Suppress("UNUSED_PARAMETER") v: View) {
        UsbScript().execute("", "1", "0")
    }

    inner class UsbScript : AsyncTask<String, Void, CharSequence>() {
        private var newMountState: Boolean? = null

        override fun doInBackground(vararg params: String): CharSequence {
            val file = params[0]
            val ro = params[1]
            val enable = params[2]
            try {
                if (enable != "0") {
                    try {
                        val output = Shell.SU.run(arrayOf(
                                "SWY_MOUNT_FILE=$file",
                                "SWY_MOUNT_CDROM='n'",
                                "if [ $ro == 1 ]",
                                "then",
                                "SWY_MOUNT_READ_ONLY='y'",
                                "else",
                                "SWY_MOUNT_READ_ONLY='n'",
                                "fi",
                                "CONFIGFS=`mount -t configfs | head -n1 | cut -d' ' -f 3`",
                                "if [ -z \"\$CONFIGFS\" ] || [ ! -d \"\$CONFIGFS/usb_gadget\" ]; then echo unsupported; exit 0; fi",
                                "mkdir \$CONFIGFS/usb_gadget/swy # swy: create a new gadget",
                                "cd    \$CONFIGFS/usb_gadget/swy # swy: enter the folder",

                                "echo 0x1d6b > idVendor  # swy: set the USB manufacturer code",
                                "echo 0x0104 > idProduct # swy: set the USB device code",
                                "echo 0x0100 > bcdUSB    # swy: set the USB revision",

                                "echo 0xEF > bDeviceClass # swy: Multi-interface Function: 0xEF",
                                "echo    2 > bDeviceSubClass # swy: USB Common Sub Class 2",
                                "echo    1 > bDeviceProtocol # swy: USB IAD Protocol 1",

                                "mkdir strings/0x409 # swy: create a folder to store the text descriptors that will be shown to the host; fill it out",
                                "echo 1337       > strings/0x409/serialnumber",
                                "echo swyter     > strings/0x409/manufacturer",
                                "echo [andropxe]  > strings/0x409/product",

                                "mkdir configs/swyconfig.1 # swy: create an empty configuration; the name doesn't matter",
                                "mkdir configs/swyconfig.1/strings/0x409",
                                "echo 'first rndis, then mass_storage to work on win32' > configs/swyconfig.1/strings/0x409/configuration",

                                "mkdir functions/mass_storage.0 # swy: create a gadget function of type 'mass_storage', only the part after the . is customizable",

                                "echo \$SWY_MOUNT_READ_ONLY                > functions/mass_storage.0/lun.0/ro",
                                "echo y                                   > functions/mass_storage.0/lun.0/removable",
                                "echo \$SWY_MOUNT_CDROM                    > functions/mass_storage.0/lun.0/cdrom",
                                "echo \$SWY_MOUNT_FILE                     > functions/mass_storage.0/lun.0/file # swy: make sure we assign the actual path last, or setting ro/cdrom won't work until we empty this",

                                "ln -s functions/mass_storage.0 configs/swyconfig.1 # swy: add a symbolic link to put our function into a premade config folder",

                                "# swy: detach the USB port from the original gadget ",
                                "echo '' > ../g1/UDC",

                                "# swy: enable/attach the gadget to the physical USB device controller; mark this gadget as active",
                                "# swy: note: `getprop sys.usb.controller` == `ls /sys/class/udc`",
                                "getprop sys.usb.controller > UDC",
                                "setprop sys.usb.state mass_storage",
                                "# swy: only report success if the gadget actually bound to a controller",
                                "[ -s UDC ] && echo success || echo bindfail"
                        ))
                        return when {
                            output == null || output.isEmpty() -> getString(R.string.host_noroot)
                            output.contains("unsupported") -> getString(R.string.host_unsupported)
                            output.contains("bindfail") -> getString(R.string.host_bind_failed)
                            output.contains("success") -> {
                                newMountState = true
                                getString(R.string.host_success)
                            }
                            else -> getString(R.string.host_error_generic, output.joinToString("\n"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error enabling USB gadget: ${e.message}", e)
                        return getString(R.string.host_error_enable, e.message)
                    }
                } else { // unmount
                    try {
                        val output = Shell.SU.run(arrayOf(
                                "CONFIGFS=`mount -t configfs | head -n1 | cut -d' ' -f 3`",
                                "if [ -z \"\$CONFIGFS\" ] || [ ! -d \"\$CONFIGFS/usb_gadget\" ]; then echo unsupported; exit 0; fi",
                                "cd    \$CONFIGFS/usb_gadget/swy # swy: enter the folder",

                                "# swy: detach the gadget from the physical USB port",
                                "echo '' > UDC",
                                "setprop sys.usb.state ''",
                                "svc usb resetUsbGadget",
                                "svc usb resetUsbPort # swy: https://android.stackexchange.com/a/236070",
                                "svc usb setFunctions ''",
                                "# swy: reattach to the original gadget",
                                "getprop sys.usb.controller > ../g1/UDC",
                                "rm configs/swyconfig.1/mass_storage.0 #swy: remove the symbolic link to each function, times two",
                                "rmdir configs/swyconfig.1/strings/0x409  #swy: deallocate the configuration strings",
                                "rmdir configs/swyconfig.1/               #swy: now we can remove the empty config",
                                "rmdir functions/mass_storage.0           #swy: remove the now-unlinked function",
                                "rmdir strings/0x409                      #swy: deallocate the gadget strings",
                                "cd .. && rmdir swy                       #swy: remove the now-empty gadget",
                                "echo success"
                        ))
                        return when {
                            output == null || output.isEmpty() -> getString(R.string.host_noroot)
                            output.contains("unsupported") -> getString(R.string.host_unsupported)
                            output.contains("success") -> {
                                newMountState = false
                                getString(R.string.host_disable_success)
                            }
                            else -> getString(R.string.host_error_generic, output.joinToString("\n"))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error disabling USB gadget: ${e.message}", e)
                        return getString(R.string.host_error_disable, e.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Critical error in USB script execution: ${e.message}", e)
                return getString(R.string.host_error_critical, e.message)
            }
        }

        override fun onPostExecute(result: CharSequence) {
            newMountState?.let {
                mounted = it
                updateStatusHero()
            }
            showSnackbar(result)
        }
    }
}
