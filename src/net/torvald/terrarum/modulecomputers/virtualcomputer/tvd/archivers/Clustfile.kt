package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * Created by minjaesong on 2023-04-21.
 */
class Clustfile(private val archive: RandomAccessFile, relativePath: String) {

    private val fs = ClustFileSystem(archive)
    private val path: String? = null

    @Transient private val prefixLength = 0
    val separatorChar = 0.toChar()
    val separator: String? = null
    val pathSeparatorChar = 0.toChar()
    val pathSeparator: String? = null
    private val PATH_OFFSET: Long = 0
    private val PREFIX_LENGTH_OFFSET: Long = 0

    @Transient private val filePath: Path? = null


}