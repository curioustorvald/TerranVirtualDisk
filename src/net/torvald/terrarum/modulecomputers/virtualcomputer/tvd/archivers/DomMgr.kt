package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import java.io.File
import java.io.RandomAccessFile
import java.util.*

/**
 * Created by minjaesong on 2023-07-09.
 */
internal object DomMgr {

    private val doms = TreeMap<String, ClusteredFormatDOM>()

    internal operator fun get(archive: File): ClusteredFormatDOM {
        return doms.getOrPut(archive.path) { ClusteredFormatDOM(RandomAccessFile(archive , "rwd")) }
    }

}