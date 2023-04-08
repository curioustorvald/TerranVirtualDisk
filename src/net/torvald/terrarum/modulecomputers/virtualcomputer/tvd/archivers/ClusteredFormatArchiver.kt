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

class ClusteredFormatDOM(private val file: RandomAccessFile, val charset: Charset, val throwErrorOnReadError: Boolean = false) {

    private data class FileAttrib(
            val charset: Charset,

            /** if ID is 1, the file is 0-byte file */
            var entryID: Int,

            var readOnly: Boolean,
            var hidden: Boolean,
            var system: Boolean,
            var deleted: Boolean,

            var creationDate: Long = -1L,
            var modificationDate: Long = -1L,
            var filename: String = "",

            val extraEntries: MutableList<ByteArray> = MutableList<ByteArray>(0) { ByteArray(0) }
    ) {
        companion object {
            /**
             * @param charset Charset
             * @param fat256 The entire 256 byte of the FAT entry
             * @return Pair of Clusternum to [FileAttrib]
             */
            fun fromBytes(charset: Charset, fat256: ByteArray) = if (fat256.size != FAT_ENTRY_SIZE) throw IllegalArgumentException("FAT not $FAT_ENTRY_SIZE bytes long (${fat256.size})") else
                fat256.toInt24() to FileAttrib(charset,
                        fat256.toInt24(),
                    fat256[3].and(1.toByte()) != 0.toByte(),
                    fat256[3].and(2.toByte()) != 0.toByte(),
                    fat256[3].and(4.toByte()) != 0.toByte(),
                    fat256[3].and(128.toByte()) != 0.toByte(),

                    fat256.sliceArray(4..9).toInt48(),
                    fat256.sliceArray(10..15).toInt48(),
                    fat256.sliceArray(16..255).toCanonicalString(charset)
            )
        }

        fun toFlag() = readOnly.toInt(0) or
                hidden.toInt(1) or
                system.toInt(2) or
                deleted.toInt(7)

        /**
         * @return List(ByteArray(256) x n)
         */
        fun toBytes(): List<ByteArray> {
            val ba = ByteArray(256)

            ba[0] =entryID.ushr(16).toByte()
            ba[1] = entryID.ushr(8).toByte()
            ba[2] = entryID.ushr(0).toByte()


            val flags = readOnly.toInt(0) or
                    hidden.toInt(1) or
                    system.toInt(2) or
                    deleted.toInt(7)
            val cd = creationDate.toInt48()
            val md = modificationDate.toInt48()
            val name = filename.toByteArray(charset)

            ba[3] = flags.toByte()
            System.arraycopy(cd, 0, ba, 4, 6)
            System.arraycopy(md, 0, ba, 10, 6)
            System.arraycopy(name, 0, ba, 16, 240)

            return listOf(ba) + extraEntries
        }

        fun renum(increment: Int) {
            if (entryID > 2) {
                entryID += increment
                extraEntries.forEach {
                    //val typeId = it[7].toUint()

                    // increment parent ID
                    it[3] = entryID.ushr(16).toByte()
                    it[4] = entryID.ushr(8).toByte()
                    it[5] = entryID.ushr(0).toByte()
                }
            }
        }
    }

    companion object {
        const val CLUSTER_SIZE = 4096 // as per spec
        const val FAT_ENTRY_SIZE = 256 // as per spec
        private val EMPTY_CLUSTER = ByteArray(CLUSTER_SIZE)
        private val EMPTY_FAT_ENTRY = ByteArray(FAT_ENTRY_SIZE)
    }

    private var fileTable = HashMap<Int, FileAttrib>() // path is unknown until the path tree is traversed
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

    fun notifyError(throwable: Throwable) {
        if (throwErrorOnReadError)
            throw throwable
        else
            throwable.printStackTrace()
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

        file.seek(2L * CLUSTER_SIZE)
        for (block in 2 until 2 + fatClusterCount) {
            for (blockOff in 0 until CLUSTER_SIZE step FAT_ENTRY_SIZE) {

                val fat = file.read(FAT_ENTRY_SIZE)

                val mainPtr = fat.toInt24()
                // extended attribs
                if (mainPtr == 2) {
                    val parentPtr = fat.toInt24(3)
                    fileTable[parentPtr]?.extraEntries?.add(fat) ?: notifyError(IllegalStateException(" but no main FAT entry for ID $mainPtr is found"))
                }
                // normal entries (incl. 0-byte file)
                else if (mainPtr == 1 || mainPtr >= 2 + fatClusterCount) {
                    fileTable[mainPtr] = FileAttrib.fromBytes(charset, fat).second
                }
            }
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
        HashMap<Int, FileAttrib>().let { newFileTable ->
            fileTable.entries.forEach { (clusternum, attribs) ->
                attribs.renum(increment)
                newFileTable[clusternum + increment] = attribs
            }
            fileTable = newFileTable
        }

        // copy over clusters, renumber any applicable cluster numbers before copying
        for (clusternum in file.length().div(CLUSTER_SIZE) - increment - 1L downTo 2 + fatClusterCount) {
            file.seekToCluster(clusternum)

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
            file.seekToCluster(clusternum + increment)
            file.write(clusterContents)
        }

        fatClusterCount += increment

        // renumber (actually re-write) FAT on the archive
        fileTable.entries.sortedBy { it.key }.let { FATs ->
            // try to rewrite every entry on the FAT
            var index = 0
            while (index in 0 until (CLUSTER_SIZE / FAT_ENTRY_SIZE) * (fatClusterCount)) {
                file.seek(CLUSTER_SIZE * 2L + FAT_ENTRY_SIZE * index)

                // if the entry is real, write some values
                if (index < FATs.size) {
                    // single FileAttrib may have multiple FATs associated
                    FATs[index].value.toBytes().let { newFATs ->
                        newFATs.forEach { file.write(it) }
                        index += newFATs.size
                    }
                }
                // if not, fill with zeros
                else {
                    file.write(EMPTY_FAT_ENTRY)
                    index += 1
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

        file.seekToCluster(clusterNum)
        val flags = file.read()
        file.seekToCluster(clusterNum)
        file.write(flags or 0x80)
    }

    /**
     * Mark the cluster as dirty
     */
    fun setDirty(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot modify cluster #$clusterNum -- is Meta/Bootsector/FAT")

        file.seekToCluster(clusterNum)
        val flags = file.read()
        file.seekToCluster(clusterNum)
        file.write(flags or 0x10)
    }

    /**
     * Mark the cluster as not dirty
     */
    fun unsetDirty(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot modify cluster #$clusterNum -- is Meta/Bootsector/FAT")

        file.seekToCluster(clusterNum)
        val flags = file.read()
        file.seekToCluster(clusterNum)
        file.write(flags and 0xEF)
    }

    private fun expandFile(clusterCount: Int) {
        file.seek(file.length())
        repeat(clusterCount) {
            file.write(EMPTY_CLUSTER)
        }
    }


    fun getFileIterator(clusterNum: Int): ByteIterator {
        if (clusterNum !in 2 + fatClusterCount..file.length() / CLUSTER_SIZE)
            throw IllegalArgumentException("Not a valid cluster number: $clusterNum")

        file.seekToCluster(clusterNum)
        val fileType = file.read()

        if (fileType != 0) // will also detect non-zero-ness of the "error flags"
            throw UnsupportedOperationException("File is not a binary file (type ${fileType and 15}, flags ${fileType.ushr(4) and 15})")

        // initial cluster must be clean and not dirty!
        return object : ByteIterator() {

            private var clusterOffset = 0 // 0..4086

            private var nextCluster = clusterNum // end-of-cluster = 0
            private var bytesInCurrentCluster = -1

            private var foundDirtyCluster = false

            private fun getSizeOnThisClusterAndResetClusterOffset(clusterNum: Int) {
                file.seekToCluster(clusterNum)

                // detect the dirty/deleted cluster
                val flags = file.read().ushr(4)

                if (flags != 0) {
                    foundDirtyCluster = true
                    nextCluster = 0
                    bytesInCurrentCluster = 0
                    file.skipBytes(9)
                }
                else {
                    file.skipBytes(4)
                    nextCluster = file.readInt24()
                    bytesInCurrentCluster = file.readUshortBig()
                }
                clusterOffset = 0
            }

            override fun hasNext(): Boolean {
                if (clusterOffset >= bytesInCurrentCluster) {
                    if (nextCluster == 0) return false
                    else {
                        getSizeOnThisClusterAndResetClusterOffset(nextCluster)
                        if (foundDirtyCluster) return false
                    }
                }

                return !(clusterOffset >= bytesInCurrentCluster && nextCluster == 0)
            }

            override fun nextByte(): Byte {
                if (clusterOffset >= bytesInCurrentCluster) {
                    getSizeOnThisClusterAndResetClusterOffset(nextCluster)
                }

                file.read().toByte().let {
                    clusterOffset += 1
                    return it
                }
            }

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
        if (readStatus != 3) throw InternalError("Unexpected error -- EOF reached? (expected 3, got $readStatus)")
        return buffer.toInt24()
    }
    private fun RandomAccessFile.seekToCluster(clusterNum: Int, offset: Int = 0) {
        this.seek(CLUSTER_SIZE * clusterNum.toLong() + offset)
    }
    private fun RandomAccessFile.seekToCluster(clusterNum: Long, offset: Int = 0) {
        this.seek(CLUSTER_SIZE * clusterNum + offset)
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
}

internal fun ByteArray.toShortBig(offset: Int = 0): Int {
    return  this[0 + offset].toUint().shl(8) or
            this[1 + offset].toUint()
}
internal fun ByteArray.toIntBig(offset: Int = 0): Int {
    return  this[0 + offset].toUint().shl(24) or
            this[1 + offset].toUint().shl(16) or
            this[2 + offset].toUint().shl(8) or
            this[3 + offset].toUint()
}
internal fun ByteArray.toInt48(offset: Int = 0): Long {
    return  this[0 + offset].toUlong().shl(40) or
            this[1 + offset].toUlong().shl(32) or
            this[2 + offset].toUlong().shl(24) or
            this[3 + offset].toUlong().shl(16) or
            this[4 + offset].toUlong().shl(8) or
            this[5 + offset].toUlong()
}
internal fun ByteArray.toInt24(offset: Int = 0): Int {
    return  this[0 + offset].toUint().shl(16) or
            this[1 + offset].toUint().shl(8) or
            this[2 + offset].toUint()
}
internal fun ByteArray.toInt64(offset: Int = 0): Long {
    return  this[0 + offset].toUlong().shl(56) or
            this[1 + offset].toUlong().shl(48) or
            this[2 + offset].toUlong().shl(40) or
            this[3 + offset].toUlong().shl(32) or
            this[4 + offset].toUlong().shl(24) or
            this[5 + offset].toUlong().shl(16) or
            this[6 + offset].toUlong().shl(8) or
            this[7 + offset].toUlong()
}