package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.*
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskSkimmer.Companion.read
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.ATTRIBS_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.NAME_LENGTH
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlin.experimental.and

/**
 * Created by minjaesong on 2023-03-31.
 */
class ClusteredFormatArchiver : Archiver {

    override val specversion = ClusteredFormatArchiver.specversion

    override fun serialize(dom: VirtualDisk, outFile: File) {
        TODO("Not yet implemented")
    }

    override fun serializeToBA64(dom: VirtualDisk): ByteArray64 {
        TODO("Not yet implemented")
    }

    override fun deserialize(file: File, charset: Charset): VirtualDisk {
        TODO("Not yet implemented")
    }

    companion object {
        const val specversion = 0x11.toByte()
    }
}

class ClusteredFormatDOM(private val file: RandomAccessFile, val charset: Charset) {

    companion object {
        const val CLUSTER_SIZE = 4096 // as per spec
        const val FAT_ENTRY_SIZE = 256 // as per spec
        private val EMPTY_CLUSTER = ByteArray(CLUSTER_SIZE)
        private val EMPTY_FAT_ENTRY = ByteArray(FAT_ENTRY_SIZE)
    }

    private var fileTable = HashMap<Int, Pair<Byte, String>>() // path is unknown until the path tree is traversed
    /** Formatted size of the disk. Archive offset 4 */
    private var diskSize = -1L
    /** How many clusters FAT is taking up. Archive offset 64 */
    private var fatClusterCount = -1
    /** Disk name in bytes. Archive offset 10 */
    private val diskName = ByteArray(NAME_LENGTH)
    /** Primary flags. Archive offset 47 */
    private var primaryAttribs = 0
    /** User-defined flags. Archive offset 48 */
    private val userAttribs = ByteArray(ATTRIBS_LENGTH)

    private var usedClusterCount = -1 // only to tally the disk usage. NOT THE ACTUAL SIZE OF THE ARCHIVE!

    private var isReadOnly: Boolean
        get() = primaryAttribs.and(1) != 0
        set(value) {
            primaryAttribs = (primaryAttribs and 0xFE) or value.toInt()
            file.seek(47L); file.write(primaryAttribs)
        }

    private var isFixedSize: Boolean
        get() = primaryAttribs.and(0x10) != 0
        set(value) {
            primaryAttribs = (primaryAttribs and 0xEF) or value.toInt(4)
            file.seek(47L); file.write(primaryAttribs)
        }

    private fun formatMatches(): Boolean {
        val magic = ByteArray(4)
        file.seek(0L)
        file.read(magic, 0, 4)
        file.seek(46L)
        val ver = file.read().toByte()

        return (magic.contentEquals(VirtualDisk.MAGIC) && ver == ClusteredFormatArchiver.specversion)
    }

    fun dispose() {
        file.close()
    }

    init {
        if (!formatMatches()) throw RuntimeException("Invalid Virtual Disk file!")

        readMeta()
        readFAT()
    }

    private fun readMeta() {
        file.seek(4L)
        diskSize = file.readInt48()
        file.readBytes(diskName)
        file.seek(47L)
        primaryAttribs = file.read()
        file.readBytes(userAttribs)
        fatClusterCount = file.readIntBig()

        usedClusterCount = 2 + fatClusterCount
    }

    private fun readFAT() {
        if (fatClusterCount < 0) throw InternalError("Disk has not been read")
        if (fatClusterCount == 0) throw RuntimeException("Invalid FAT size ($fatClusterCount)")

        for (block in 2 until 2 + fatClusterCount) {
            TODO()
        }
    }

    private fun checkDiskCapacity() {
        val usedBytes = CLUSTER_SIZE * usedClusterCount
        TODO()
    }

    /**
     * Shifts the cluster number of every entry to given number, then actually inserts new clusters to the FAT area.
     * @param increment number of clusters to be inserted
     */
    fun renum(increment: Int) {
        usedClusterCount += increment
        expandFile(increment)

        // renumber my FAT
        HashMap<Int, Pair<Byte, String>>().let { newFileTable ->
            fileTable.entries.forEach { (clusternum, flagAndLabel) -> newFileTable[clusternum + increment] = flagAndLabel }
            fileTable = newFileTable
        }

        // copy over clusters, renumber any applicable cluster numbers before copying
        for (clusternum in file.length().div(CLUSTER_SIZE) - increment - 1L downTo 2 + fatClusterCount) {
            file.seek(CLUSTER_SIZE * clusternum)

            // do the actual renumbering on the cluster contents
            val clusterContents = file.read(CLUSTER_SIZE)
            val prevPtr = clusterContents.sliceArray(2..4).toInt24().let { if (it > 1) it + increment else it }.toInt24()
            val nextPtr = clusterContents.sliceArray(5..7).toInt24().let { if (it > 1) it + increment else it }.toInt24()

            System.arraycopy(prevPtr, 0, clusterContents, 2, 3)
            System.arraycopy(nextPtr, 0, clusterContents, 5, 3)

            // renumber clusternum on the directory files
            if (clusterContents[0].toInt().and(15) == 1) {
                val entrySize = clusterContents.sliceArray(8..9).toShortBig()
                for (entryOffset in 10 until 10 + 3*entrySize step 3) {
                    val clusterNum = clusterContents.sliceArray(entryOffset..entryOffset+2).toInt24().let { if (it > 1) it + increment else it }.toInt24()
                    System.arraycopy(clusterNum, 0, clusterContents, entryOffset, 3)
                }
            }

            // write modified cluster to the new (forward) position of the archive
            file.seek(CLUSTER_SIZE * (clusternum + increment))
            file.write(clusterContents)
        }

        fatClusterCount += increment

        // renumber (actually re-write) FAT on the archive
        fileTable.entries.sortedBy { it.key }.let { newFAT ->
            // try to rewrite every entry on the FAT
            for (index in 0 until (CLUSTER_SIZE / FAT_ENTRY_SIZE) * (fatClusterCount)) {
                file.seek(CLUSTER_SIZE * 2L + FAT_ENTRY_SIZE * index)

                // if the entry is real, write some values
                if (index < newFAT.size) {
                    val (ptr, flagAndLabel) = newFAT[index]
                    file.writeInt24(ptr)
                    file.write(flagAndLabel.first.toInt())
                    file.write(flagAndLabel.second.toByteArray(charset).forceSize(252))
                }
                // if not, fill with zeros
                else {
                    file.write(EMPTY_FAT_ENTRY)
                }
            }
        }

        // write new metavalues
        file.seek(64L)
        file.writeInt32(fatClusterCount)

    }

    /**
     * Mark the cluster as to-be-deleted
     */
    fun discard(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot discard cluster #$clusterNum -- is Meta/Bootsector/FAT")

        file.seek(CLUSTER_SIZE * clusterNum.toLong())
        val flags = file.read()
        file.seek(CLUSTER_SIZE * clusterNum.toLong())
        file.write(flags or 0x80)
    }

    /**
     * Mark the cluster as dirty
     */
    fun setDirty(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot modify cluster #$clusterNum -- is Meta/Bootsector/FAT")

        file.seek(CLUSTER_SIZE * clusterNum.toLong())
        val flags = file.read()
        file.seek(CLUSTER_SIZE * clusterNum.toLong())
        file.write(flags or 0x10)
    }

    /**
     * Mark the cluster as not dirty
     */
    fun unsetDirty(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot modify cluster #$clusterNum -- is Meta/Bootsector/FAT")

        file.seek(CLUSTER_SIZE * clusterNum.toLong())
        val flags = file.read()
        file.seek(CLUSTER_SIZE * clusterNum.toLong())
        file.write(flags and 0xEF)
    }

    private fun expandFile(clusterCount: Int) {
        file.seek(file.length())
        repeat(clusterCount) {
            file.write(EMPTY_CLUSTER)
        }
    }








    private fun RandomAccessFile.readBytes(buffer: ByteArray): Int {
        val readStatus = file.read(buffer)
        return readStatus
    }
    private fun RandomAccessFile.readUshortBig(): Int {
        val buffer = ByteArray(2)
        val readStatus = readBytes(buffer)
        if (readStatus != 2) throw InternalError("Unexpected error -- EOF reached? (expected 2, got $readStatus)")
        return buffer.toShortBig()
    }
    private fun RandomAccessFile.readIntBig(): Int {
        val buffer = ByteArray(4)
        val readStatus = readBytes(buffer)
        if (readStatus != 4) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
        return buffer.toIntBig()
    }
    private fun RandomAccessFile.readInt48(): Long {
        val buffer = ByteArray(6)
        val readStatus = readBytes(buffer)
        if (readStatus != 6) throw InternalError("Unexpected error -- EOF reached? (expected 6, got $readStatus)")
        return buffer.toInt48()
    }
    private fun RandomAccessFile.readInt24(): Int {
        val buffer = ByteArray(3)
        val readStatus = readBytes(buffer)
        if (readStatus != 3) throw InternalError("Unexpected error -- EOF reached? (expected 6, got $readStatus)")
        return buffer.toInt24()
    }
    private fun ByteArray.toShortBig(): Int {
        return  this[0].toUint().shl(8) or
                this[1].toUint()
    }
    private fun ByteArray.toIntBig(): Int {
        return  this[0].toUint().shl(24) or
                this[1].toUint().shl(16) or
                this[2].toUint().shl(8) or
                this[3].toUint()
    }
    private fun ByteArray.toInt48(): Long {
        return  this[0].toUlong().shl(40) or
                this[1].toUlong().shl(32) or
                this[2].toUlong().shl(24) or
                this[3].toUlong().shl(16) or
                this[4].toUlong().shl(8) or
                this[5].toUlong()
    }
    private fun ByteArray.toInt24(): Int {
        return  this[0].toUint().shl(16) or
                this[1].toUint().shl(8) or
                this[2].toUint()
    }
    private fun ByteArray.toInt64(): Long {
        return  this[0].toUlong().shl(56) or
                this[1].toUlong().shl(48) or
                this[2].toUlong().shl(40) or
                this[3].toUlong().shl(32) or
                this[4].toUlong().shl(24) or
                this[5].toUlong().shl(16) or
                this[6].toUlong().shl(8) or
                this[7].toUlong()
    }
    private fun RandomAccessFile.writeInt16(value: Int) {
        this.write(value.ushr(8))
        this.write(value.ushr(0))
    }
    private fun RandomAccessFile.writeInt24(value: Int) {
        this.write(value.ushr(16))
        this.write(value.ushr(8))
        this.write(value.ushr(0))
    }
    private fun RandomAccessFile.writeInt32(value: Int) {
        this.write(value.ushr(24))
        this.write(value.ushr(16))
        this.write(value.ushr(8))
        this.write(value.ushr(0))
    }
    private fun RandomAccessFile.writeInt48(value: Int) {
        this.write(value.ushr(40))
        this.write(value.ushr(32))
        this.write(value.ushr(24))
        this.write(value.ushr(16))
        this.write(value.ushr(8))
        this.write(value.ushr(0))
    }
    private fun RandomAccessFile.writeInt64(value: Int) {
        this.write(value.ushr(56))
        this.write(value.ushr(48))
        this.write(value.ushr(40))
        this.write(value.ushr(32))
        this.write(value.ushr(24))
        this.write(value.ushr(16))
        this.write(value.ushr(8))
        this.write(value.ushr(0))
    }
    private fun Boolean.toInt(shl: Int = 0) = if (this) 1.shl(shl) else 0
}