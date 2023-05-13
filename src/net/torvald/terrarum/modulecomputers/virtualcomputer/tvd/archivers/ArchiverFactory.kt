package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk

/**
 * Created by minjaesong on 2023-03-31.
 */
object ArchiverFactory {

    fun getDomArchiver(format: String, dom: Any?): Archiver = when (format) {
        "format3" -> Format3Archiver(dom as VirtualDisk)
        "clustered" -> ClusteredFormatArchiver(dom as ClusteredFormatDOM)
        else -> throw IllegalArgumentException("Unrecognised archiver format: $format")
    }

}