package streetwalrus.usbmountr

/**
 * Builds the root-shell scripts that drive the configfs USB gadget, and
 * parses their stdout tokens. Pure Kotlin — no Android types — so it runs
 * under plain JUnit.
 */
object GadgetShell {

    sealed class ShellResult {
        object Success : ShellResult()
        object NoRoot : ShellResult()
        object Unsupported : ShellResult()
        object BindFail : ShellResult()
        data class MountedProbe(val path: String) : ShellResult()
        object UnmountedProbe : ShellResult()
        data class Error(val raw: String) : ShellResult()
    }

    private const val FIND_CONFIGFS =
            "CONFIGFS=`mount -t configfs | head -n1 | cut -d' ' -f 3`"
    private const val BAIL_UNSUPPORTED =
            "if [ -z \"\$CONFIGFS\" ] || [ ! -d \"\$CONFIGFS/usb_gadget\" ]; then echo unsupported; exit 0; fi"

    /** Backslash-escape every character so the path survives the shell. */
    private fun escape(path: String) = "(.)".toRegex().replace(path, "\\\\$1")

    fun mountScript(path: String, ro: Boolean, cdrom: Boolean): Array<String> {
        val roFlag = if (ro) "y" else "n"
        val cdromFlag = if (cdrom) "y" else "n"
        return arrayOf(
                FIND_CONFIGFS,
                BAIL_UNSUPPORTED,

                // A leftover gadget (bindfail, or a teardown the system killed)
                // would make every mkdir below fail; clear it first.
                "if [ -d \"\$CONFIGFS/usb_gadget/swy\" ]; then",
                "echo '' > \"\$CONFIGFS/usb_gadget/swy/UDC\" 2>/dev/null",
                "rm \"\$CONFIGFS/usb_gadget/swy/configs/swyconfig.1/mass_storage.0\" 2>/dev/null",
                "rmdir \"\$CONFIGFS/usb_gadget/swy/configs/swyconfig.1/strings/0x409\" 2>/dev/null",
                "rmdir \"\$CONFIGFS/usb_gadget/swy/configs/swyconfig.1\" 2>/dev/null",
                "rmdir \"\$CONFIGFS/usb_gadget/swy/functions/mass_storage.0\" 2>/dev/null",
                "rmdir \"\$CONFIGFS/usb_gadget/swy/strings/0x409\" 2>/dev/null",
                "rmdir \"\$CONFIGFS/usb_gadget/swy\" 2>/dev/null",
                "fi",

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

                "echo $roFlag > functions/mass_storage.0/lun.0/ro",
                "echo y > functions/mass_storage.0/lun.0/removable",
                "echo $cdromFlag > functions/mass_storage.0/lun.0/cdrom",
                // configfs ignores ro/cdrom writes while a file is assigned,
                // so the backing file must be set last.
                "echo ${escape(path)} > functions/mass_storage.0/lun.0/file",

                "ln -s functions/mass_storage.0 configs/swyconfig.1 # swy: add a symbolic link to put our function into a premade config folder",

                "# swy: detach the USB port from the original gadget ",
                "echo '' > ../g1/UDC",

                "# swy: enable/attach the gadget to the physical USB device controller; mark this gadget as active",
                "# swy: note: `getprop sys.usb.controller` == `ls /sys/class/udc`",
                "getprop sys.usb.controller > UDC",
                "setprop sys.usb.state mass_storage",
                "[ -s UDC ] && echo success || echo bindfail")
    }

    fun unmountScript(): Array<String> = arrayOf(
            FIND_CONFIGFS,
            BAIL_UNSUPPORTED,
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
            "echo success")

    fun probeScript(): Array<String> = arrayOf(
            FIND_CONFIGFS,
            BAIL_UNSUPPORTED,
            "if [ -s \"\$CONFIGFS/usb_gadget/swy/UDC\" ]; then",
            "echo mounted",
            "cat \"\$CONFIGFS/usb_gadget/swy/functions/mass_storage.0/lun.0/file\"",
            "else",
            "echo unmounted",
            "fi")

    fun parse(output: List<String>?): ShellResult {
        if (output == null || output.isEmpty()) return ShellResult.NoRoot
        return when {
            output.contains("unsupported") -> ShellResult.Unsupported
            output.contains("bindfail") -> ShellResult.BindFail
            output.contains("mounted") -> {
                val i = output.indexOf("mounted")
                ShellResult.MountedProbe(output.getOrNull(i + 1)?.trim() ?: "")
            }
            output.contains("unmounted") -> ShellResult.UnmountedProbe
            output.contains("success") -> ShellResult.Success
            else -> ShellResult.Error(output.joinToString("\n"))
        }
    }
}
