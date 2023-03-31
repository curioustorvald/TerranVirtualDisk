package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

/**
 * Created by minjaesong on 2023-03-31.
 */
object ArchiverFactory {

    fun getDomArchiver(format: String): Archiver = when (format) {
        "format3" -> Format3Archiver()
        else -> throw IllegalArgumentException("Unrecognised archiver format: $format")
    }

}