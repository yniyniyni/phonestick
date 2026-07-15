package streetwalrus.usbmountr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OsDetectorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        private const val PVD_OFFSET = 32768
        private const val FIXTURE_MTIME = 1_000_000_000_000L
    }

    /** Writes a minimal file whose sector 16 is a valid ISO9660 PVD with [label]. */
    private fun isoWithLabel(label: String, name: String = "test.iso"): File {
        val data = ByteArray(PVD_OFFSET + 2048)
        data[PVD_OFFSET] = 1 // descriptor type: primary
        "CD001".toByteArray(Charsets.ISO_8859_1).copyInto(data, PVD_OFFSET + 1)
        label.padEnd(32).toByteArray(Charsets.ISO_8859_1)
                .copyInto(data, PVD_OFFSET + 40, 0, 32)
        return writeFixture(name, data)
    }

    private fun writeFixture(name: String, data: ByteArray): File {
        val file = File(tmp.root, name)
        file.writeBytes(data)
        file.setLastModified(FIXTURE_MTIME)
        return file
    }

    @Test
    fun `ubuntu volume label detected`() {
        val file = isoWithLabel("Ubuntu 24.04 LTS amd64")
        assertEquals(DetectedOs.UBUNTU, OsDetector.detect(file))
    }

    @Test
    fun `windows microsoft label prefix detected`() {
        val file = isoWithLabel("CCCOMA_X64FRE_EN-US_DV9")
        assertEquals(DetectedOs.WINDOWS, OsDetector.detect(file))
    }

    @Test
    fun `unknown label falls back to filename`() {
        val file = isoWithLabel("MYCUSTOMBUILD", name = "fedora-42.iso")
        assertEquals(DetectedOs.FEDORA, OsDetector.detect(file))
    }

    @Test
    fun `file too small for a PVD uses filename`() {
        val file = writeFixture("archlinux-2026.07.01-x86_64.iso", ByteArray(1000))
        assertEquals(DetectedOs.ARCH, OsDetector.detect(file))
    }

    @Test
    fun `garbage sector and meaningless name detects nothing`() {
        val data = ByteArray(PVD_OFFSET + 2048) { 0x5A }
        val file = writeFixture("image.bin", data)
        assertNull(OsDetector.detect(file))
    }

    @Test
    fun `generic linux fallback when no specific distro matches`() {
        val file = isoWithLabel("Some Linux 1.0")
        assertEquals(DetectedOs.LINUX_GENERIC, OsDetector.detect(file))
    }

    @Test
    fun `ubuntu flavor maps to ubuntu`() {
        val file = isoWithLabel("Xubuntu 24.04")
        assertEquals(DetectedOs.UBUNTU, OsDetector.detect(file))
    }

    @Test
    fun `wrong magic with valid type byte uses filename`() {
        val data = ByteArray(PVD_OFFSET + 2048)
        data[PVD_OFFSET] = 1 // descriptor type: primary
        "XX001".toByteArray(Charsets.ISO_8859_1).copyInto(data, PVD_OFFSET + 1)
        val file = writeFixture("debian-13.iso", data)
        assertEquals(DetectedOs.DEBIAN, OsDetector.detect(file))
    }

    @Test
    fun `wrong type byte with valid magic uses filename`() {
        val data = ByteArray(PVD_OFFSET + 2048)
        data[PVD_OFFSET] = 2 // not the primary descriptor type
        "CD001".toByteArray(Charsets.ISO_8859_1).copyInto(data, PVD_OFFSET + 1)
        "Ubuntu 24.04".padEnd(32).toByteArray(Charsets.ISO_8859_1)
                .copyInto(data, PVD_OFFSET + 40, 0, 32)
        val file = writeFixture("kali-2026.iso", data)
        assertEquals(DetectedOs.KALI, OsDetector.detect(file))
    }

    @Test
    fun `blank volume label falls back to filename`() {
        val file = isoWithLabel("", name = "manjaro-xfce.iso")
        assertEquals(DetectedOs.MANJARO, OsDetector.detect(file))
    }

    @Test
    fun `result is cached for an unchanged file`() {
        val file = isoWithLabel("Ubuntu 24.04 LTS amd64", name = "cached.iso")
        val size = file.length()
        assertEquals(DetectedOs.UBUNTU, OsDetector.detect(file))
        // Overwrite with same-size garbage and restore mtime: the (path, size,
        // mtime) key is unchanged, so the cached result must be returned
        // without re-reading the content.
        file.writeBytes(ByteArray(size.toInt()) { 0x5A })
        file.setLastModified(FIXTURE_MTIME)
        assertEquals(DetectedOs.UBUNTU, OsDetector.detect(file))
    }
}
