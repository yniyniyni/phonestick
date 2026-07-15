package streetwalrus.usbmountr

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class DetectedOs(@StringRes val nameRes: Int, @DrawableRes val iconRes: Int) {
    ALPINE(R.string.os_alpine, R.drawable.ic_os_alpine),
    UBUNTU(R.string.os_ubuntu, R.drawable.ic_os_ubuntu),
    DEBIAN(R.string.os_debian, R.drawable.ic_os_debian),
    FEDORA(R.string.os_fedora, R.drawable.ic_os_fedora),
    ARCH(R.string.os_arch, R.drawable.ic_os_arch),
    MINT(R.string.os_mint, R.drawable.ic_os_mint),
    OPENSUSE(R.string.os_opensuse, R.drawable.ic_os_opensuse),
    KALI(R.string.os_kali, R.drawable.ic_os_kali),
    MANJARO(R.string.os_manjaro, R.drawable.ic_os_manjaro),
    FREEBSD(R.string.os_freebsd, R.drawable.ic_os_freebsd),
    WINDOWS(R.string.os_windows, R.drawable.ic_os_windows),
    LINUX_GENERIC(R.string.os_linux, R.drawable.ic_os_linux),
}

/**
 * Best-effort identification of the OS inside a disk image: the ISO9660
 * volume label when present, the filename otherwise. Failures of any kind
 * yield null; callers then render the generic presentation.
 */
object OsDetector {
    private const val PVD_OFFSET = 32768L // sector 16 * 2048
    private const val SECTOR_SIZE = 2048

    // First match wins: specific distros before the generic "linux" catch-all.
    private val patterns = listOf(
            Regex("alpine") to DetectedOs.ALPINE,
            Regex("ubuntu") to DetectedOs.UBUNTU, // also [klx]ubuntu flavors
            Regex("debian") to DetectedOs.DEBIAN,
            Regex("fedora") to DetectedOs.FEDORA,
            Regex("arch") to DetectedOs.ARCH,
            Regex("mint") to DetectedOs.MINT,
            Regex("suse") to DetectedOs.OPENSUSE,
            Regex("kali") to DetectedOs.KALI,
            Regex("manjaro") to DetectedOs.MANJARO,
            Regex("freebsd") to DetectedOs.FREEBSD,
            Regex("windows|win10|win11|^cccoma_|^cpba_") to DetectedOs.WINDOWS,
            Regex("linux") to DetectedOs.LINUX_GENERIC,
    )

    private val cache = HashMap<Triple<String, Long, Long>, DetectedOs?>()

    fun detect(file: File): DetectedOs? {
        val key = Triple(file.path, file.length(), file.lastModified())
        if (cache.containsKey(key)) return cache[key]
        val result = volumeLabel(file)?.let { match(it) } ?: match(file.name)
        cache[key] = result
        return result
    }

    internal fun match(text: String): DetectedOs? {
        val lower = text.lowercase()
        return patterns.firstOrNull { it.first.containsMatchIn(lower) }?.second
    }

    /** Volume ID from the ISO9660 primary volume descriptor, or null. */
    internal fun volumeLabel(file: File): String? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < PVD_OFFSET + SECTOR_SIZE) return null
                raf.seek(PVD_OFFSET)
                val sector = ByteArray(SECTOR_SIZE)
                raf.readFully(sector)
                if (sector[0].toInt() != 1) return null
                if (String(sector, 1, 5, Charsets.ISO_8859_1) != "CD001") return null
                return String(sector, 40, 32, Charsets.ISO_8859_1).trim().ifEmpty { null }
            }
        } catch (e: IOException) {
            return null
        }
    }
}
