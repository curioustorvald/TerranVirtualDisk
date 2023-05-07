package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import java.io.RandomAccessFile
import java.nio.charset.Charset


/**
 * Created by minjaesong on 2023-04-21.
 */
class ClustFileSystem(private val archive: RandomAccessFile) {

    private val DOM: ClusteredFormatDOM

    init {
        DOM = ClusteredFormatDOM(archive)
    }

}