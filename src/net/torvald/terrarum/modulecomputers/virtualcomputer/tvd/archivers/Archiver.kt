package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.ByteArray64
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk
import java.io.File
import java.nio.charset.Charset

/**
 * This interface is a "generic" archiver that works with full DOM. Some archival format may allow the disk operation with
 * not-quite-full DOM (e.g. PartialDOM/DiskSkimmer/Clustered; see individual archiver class for more details.
 *
 * Created by minjaesong on 2023-03-31.
 */
interface Archiver {

    fun serialize(dom: VirtualDisk, outFile: File)
    fun serializeToBA64(dom: VirtualDisk): ByteArray64
    fun deserialize(file: File, charset: Charset): VirtualDisk
    val specversion: Byte

}