package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.ByteArray64
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.ATTRIBS_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.NAME_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toUint
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toUlong
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

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

class ClusteredFormatDOM(private val file: RandomAccessFile) {

    companion object {
        const val CLUSTER_SIZE = 4096 // as per spec
        private val EMPTY_CLUSTER = ByteArray(CLUSTER_SIZE)
    }

    private val fileTable = HashMap<String, Int>()
    private var diskSize = -1L
    private var fatClusterCount = -1
    private val diskName = ByteArray(NAME_LENGTH)
    private var primaryAttribs = 0
    private val userAttribs = ByteArray(ATTRIBS_LENGTH)

    private var usedClusterCount = -1

    private var isReadOnly: Boolean
        get() = primaryAttribs.and(1) != 0
        set(value) { primaryAttribs = (primaryAttribs and 0xFE) or value.toInt() }

    private var isFixedSize: Boolean
        get() = primaryAttribs.and(0x10) != 0
        set(value) { primaryAttribs = (primaryAttribs and 0xEF) or value.toInt(4) }

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

    fun renum(increment: Int) {
        usedClusterCount += increment
        expandFile(increment)

        // renumber FAT on the archive
        TODO()

        // renumber file blocks
        for (block in 2L + fatClusterCount until file.length() / CLUSTER_SIZE) {
            file.seek(block + 2)
            var prevPtr = file.readInt24()
            var nextPtr = file.readInt24()
            if (prevPtr > 0) prevPtr += increment
            if (nextPtr > 0) nextPtr += increment
            file.seek(block + 2)
            file.writeInt24(prevPtr)
            file.writeInt24(nextPtr)
        }

        // renumber my FAT
        TODO()
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