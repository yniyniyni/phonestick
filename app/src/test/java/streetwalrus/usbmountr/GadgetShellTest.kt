package streetwalrus.usbmountr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetShellTest {

    // --- parse ---

    @Test
    fun nullOutputMeansNoRoot() {
        assertEquals(GadgetShell.ShellResult.NoRoot, GadgetShell.parse(null))
    }

    @Test
    fun emptyOutputMeansNoRoot() {
        assertEquals(GadgetShell.ShellResult.NoRoot, GadgetShell.parse(emptyList()))
    }

    @Test
    fun unsupportedToken() {
        assertEquals(GadgetShell.ShellResult.Unsupported,
                GadgetShell.parse(listOf("unsupported")))
    }

    @Test
    fun bindfailToken() {
        assertEquals(GadgetShell.ShellResult.BindFail,
                GadgetShell.parse(listOf("bindfail")))
    }

    @Test
    fun successToken() {
        assertEquals(GadgetShell.ShellResult.Success,
                GadgetShell.parse(listOf("success")))
    }

    @Test
    fun probeMountedCarriesThePathFromTheNextLine() {
        assertEquals(GadgetShell.ShellResult.MountedProbe("/data/user/0/jinbaittai.phonestick/files/x.iso"),
                GadgetShell.parse(listOf("mounted", "/data/user/0/jinbaittai.phonestick/files/x.iso")))
    }

    @Test
    fun probeMountedWithMissingPathLineYieldsEmptyPath() {
        assertEquals(GadgetShell.ShellResult.MountedProbe(""),
                GadgetShell.parse(listOf("mounted")))
    }

    @Test
    fun probeUnmounted() {
        assertEquals(GadgetShell.ShellResult.UnmountedProbe,
                GadgetShell.parse(listOf("unmounted")))
    }

    @Test
    fun unknownOutputBecomesErrorWithRawText() {
        assertEquals(GadgetShell.ShellResult.Error("mkdir: no such file\nsh: nope"),
                GadgetShell.parse(listOf("mkdir: no such file", "sh: nope")))
    }

    // --- mountScript ---

    @Test
    fun mountScriptSetsRoAndCdromFlags() {
        val script = GadgetShell.mountScript("/data/x.iso", ro = true, cdrom = false).toList()
        assertTrue(script.any { it.startsWith("echo y > functions/mass_storage.0/lun.0/ro") })
        assertTrue(script.any { it.startsWith("echo n > functions/mass_storage.0/lun.0/cdrom") })

        val script2 = GadgetShell.mountScript("/data/x.iso", ro = false, cdrom = true).toList()
        assertTrue(script2.any { it.startsWith("echo n > functions/mass_storage.0/lun.0/ro") })
        assertTrue(script2.any { it.startsWith("echo y > functions/mass_storage.0/lun.0/cdrom") })
    }

    @Test
    fun mountScriptAssignsBackingFileAfterTheFlags() {
        // configfs ignores ro/cdrom writes while a file is assigned, so the
        // file must land last.
        val script = GadgetShell.mountScript("/data/x.iso", ro = true, cdrom = true).toList()
        val fileLine = script.indexOfFirst { it.contains("> functions/mass_storage.0/lun.0/file") }
        val roLine = script.indexOfFirst { it.contains("> functions/mass_storage.0/lun.0/ro") }
        val cdromLine = script.indexOfFirst { it.contains("> functions/mass_storage.0/lun.0/cdrom") }
        assertTrue(fileLine > roLine)
        assertTrue(fileLine > cdromLine)
    }

    @Test
    fun mountScriptEscapesEveryPathCharacter() {
        val script = GadgetShell.mountScript("/a b.iso", ro = true, cdrom = false).toList()
        val fileLine = script.first { it.contains("> functions/mass_storage.0/lun.0/file") }
        assertTrue(fileLine.startsWith("""echo \/\a\ \b\.\i\s\o >"""))
    }

    @Test
    fun mountScriptTearsDownALeftoverGadgetFirst() {
        val script = GadgetShell.mountScript("/data/x.iso", ro = true, cdrom = false).toList()
        val guard = script.indexOfFirst { it.contains("if [ -d \"\$CONFIGFS/usb_gadget/swy\" ]") }
        val create = script.indexOfFirst { it.startsWith("mkdir \$CONFIGFS/usb_gadget/swy") }
        assertTrue(guard in 0 until create)
        assertTrue(script.any { it.contains("rmdir \"\$CONFIGFS/usb_gadget/swy\" 2>/dev/null") })
    }

    @Test
    fun mountScriptOnlyReportsSuccessWhenTheUdcBound() {
        val script = GadgetShell.mountScript("/data/x.iso", ro = true, cdrom = false).toList()
        assertEquals("[ -s UDC ] && echo success || echo bindfail", script.last())
    }

    // --- probeScript ---

    @Test
    fun probeScriptChecksTheUdcFileAndReadsTheBackingFile() {
        val script = GadgetShell.probeScript().toList()
        assertTrue(script.any { it.contains("[ -s \"\$CONFIGFS/usb_gadget/swy/UDC\" ]") })
        assertTrue(script.any { it.contains("functions/mass_storage.0/lun.0/file") })
        assertTrue(script.any { it == "echo unmounted" })
    }

    // --- unmountScript ---

    @Test
    fun unmountScriptReattachesTheStockGadget() {
        val script = GadgetShell.unmountScript().toList()
        assertTrue(script.any { it.contains("getprop sys.usb.controller > ../g1/UDC") })
        assertTrue(script.any { it == "echo success" })
    }
}
