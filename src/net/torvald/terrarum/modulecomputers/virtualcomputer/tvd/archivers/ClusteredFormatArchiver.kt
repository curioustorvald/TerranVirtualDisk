package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.*
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskSkimmer.Companion.read
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.ATTRIBS_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.NAME_LENGTH
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlin.experimental.and
import kotlin.math.ceil

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

private fun Int.incClusterNum(increment: Int) = when (this) {
    in 0x000004 until 0xF00000 -> this + increment
    else -> this
}

private fun ByteArray.renumFAT(increment: Int) {
    val entryID = this.toInt24()
    val newID = entryID.incClusterNum(increment)
    // increment parent ID
    this.writeInt24(newID, 3)

    // increment parent IDs on the Extended Entries
    // inline directories
    if (entryID == 0xFFFF11) {
        for (offset in 8 until 256 step 3) {
            val originalID = this.toInt24(offset)
            val newFileID = originalID.incClusterNum(increment)
            this.writeInt24(newFileID, offset)
        }
    }
}

class ClusteredFormatDOM(private val file: RandomAccessFile, val charset: Charset, val throwErrorOnReadError: Boolean = false) {

    private val fatEntryIndices = HashMap<EntryID, Int>() // EntryID, FATIndex
    private var fatEntryHighest = 2 to 0 // FATIndex, EntryID

    data class FATEntry(
            val charset: Charset,

            /** if ID is 1, the file is 0-byte file */
            var entryID: EntryID,

            var readOnly: Boolean,
            var hidden: Boolean,
            var system: Boolean,
            var deleted: Boolean,

            var creationDate: Long = -1L,
            var modificationDate: Long = -1L,
            var filename: String = "",

            val extendedEntries: MutableList<ByteArray> = MutableList<ByteArray>(0) { ByteArray(0) }
    ) {
        companion object {
            /**
             * @param charset Charset
             * @param fat256 The entire 256 byte of the FAT entry
             * @return Pair of Clusternum to [FATEntry]
             */
            fun fromBytes(charset: Charset, fat256: ByteArray) = if (fat256.size != ClusteredFormatDOM.FAT_ENTRY_SIZE) throw IllegalArgumentException("FAT not ${ClusteredFormatDOM.FAT_ENTRY_SIZE} bytes long (${fat256.size})") else
                fat256.toInt24() to FATEntry(charset,
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
        fun toBytes(): Array<ByteArray> {
            val ba = ByteArray(256)

            ba.writeInt24(entryID, 0)


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

            return (listOf(ba) + extendedEntries).toTypedArray()
        }

        /**
         * Called by ClusteredFormatDOM, this function assists the global renum operation.
         */
        internal fun _fatRenum(increment: Int) {
            if (entryID > 2) {
                entryID += increment
                extendedEntries.forEach {
                    it.renumFAT(increment)
                }
            }
        }

        val isInline: Boolean
            get() = entryID in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST

        fun getInlineBytes(): ByteArray {
            TODO()
        }
    }

    companion object {
        const val CLUSTER_SIZE = 4096 // as per spec
        const val FAT_ENTRY_SIZE = 256 // as per spec
        private val EMPTY_CLUSTER = ByteArray(CLUSTER_SIZE)
        private val EMPTY_FAT_ENTRY = ByteArray(FAT_ENTRY_SIZE)

        const val FILE_BLOCK_HEADER_SIZE = 10 // as per spec
        const val FILE_BLOCK_CONTENTS_SIZE = CLUSTER_SIZE - FILE_BLOCK_HEADER_SIZE

        const val NULL_CLUSTER = 0
        const val INLINE_FILE_CLUSTER_BASE = 0xF00000
        const val INLINE_FILE_CLUSTER_LAST = 0xFFFDFF

        const val INLINING_THRESHOLD = 248 * 8 // compare with <= -- files up to this size is recommended to be inlined
    }

    /**
     * @return ID between 0xF00000 and 0xFFFDFF if the reserved area is not full; normal cluster ID otherwise
     */
    fun getNextFreeInlineCluster(): Int {
        var i = INLINE_FILE_CLUSTER_BASE
        while (i <= INLINE_FILE_CLUSTER_LAST) {
            if (!fileTable.containsKey(i)) return i
            i += 1
        }
        return usedClusterCount
    }

    private var fileTable = HashMap<Int, FATEntry>() // path is unknown until the path tree is traversed
    /** Formatted size of the disk. Archive offset 4 */
    private var diskSize = -1L
    /** How many clusters FAT is taking up. Archive offset 64 */
    private var fatClusterCount = 2
    /** How many FAT entries are there on the FAT area, including Extended Entries */
    private var fatEntryCount = 0
    /** Disk name in bytes. Archive offset 10 */
    private val diskName = ByteArray(NAME_LENGTH)
    /** Primary flags. Archive offset 47 */
    private var primaryAttribs = 0
    /** User-defined flags. Archive offset 48 */
    private val userAttribs = ByteArray(ATTRIBS_LENGTH)

    private var usedClusterCount = -1 // only to tally the disk usage. NOT THE ACTUAL SIZE OF THE ARCHIVE!

    var isReadOnly: Boolean
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
                if (mainPtr >= 0xFFFF00) {
                    val parentPtr = fat.toInt24(3)
                    fileTable[parentPtr]?.extendedEntries?.add(fat) ?: notifyError(IllegalStateException(" but no main FAT entry for ID $mainPtr is found"))
                }
                // normal entries (incl. 0-byte file)
                else if (mainPtr >= 2 + fatClusterCount) {
                    fileTable[mainPtr] = FATEntry.fromBytes(charset, fat).second
                }

                if (mainPtr != 0) {
                    fatEntryCount += 1
                }

                val fatIndex = blockOff / FAT_ENTRY_SIZE + block * FAT_ENTRY_SIZE / CLUSTER_SIZE
                fatEntryIndices[mainPtr] = fatIndex
                if (fatIndex > fatEntryHighest.first) {
                    fatEntryHighest = fatIndex to mainPtr
                }
            }
        }
    }

    private fun checkDiskCapacity(bytesToAdd: Int) {
        val usedBytes = CLUSTER_SIZE * usedClusterCount + bytesToAdd
        if (usedBytes > diskSize) throw VDIOException("Not enough space on the disk")
    }

    /**
     * Shifts the cluster number of every entry to given number, then actually inserts new clusters to the FAT area.
     * @param increment number of clusters to be inserted
     */
    fun renum(increment: Int) {
        expandArchive(increment) // implied: usedClusterCount += increment

        // renumber my FAT
        HashMap<Int, FATEntry>().let { newFileTable ->
            fileTable.entries.forEach { (clusternum, attribs) ->
                attribs._fatRenum(increment)
                newFileTable[clusternum + increment] = attribs
            }
            fileTable = newFileTable
        }

        // copy over clusters, renumber any applicable cluster numbers before copying
        // using file.length().div(CLUSTER_SIZE) instead of usedClusterCount to really access every clusters
        for (clusternum in file.length().div(CLUSTER_SIZE) - increment - 1L downTo 2 + fatClusterCount) {
            file.seekToCluster(clusternum)

            // do the actual renumbering on the cluster contents
            val clusterContents = file.read(CLUSTER_SIZE)
            val prevPtr = clusterContents.toInt24(2).incClusterNum(increment).toInt24Arr()
            val nextPtr = clusterContents.toInt24(5).incClusterNum(increment).toInt24Arr()

            System.arraycopy(prevPtr, 0, clusterContents, 2, 3)
            System.arraycopy(nextPtr, 0, clusterContents, 5, 3)

            // renumber clusternum on the directory files
            if (clusterContents[0].toInt().and(15) == 1) {
                val entrySize = clusterContents.toInt16(8)
                for (entryOffset in 10 until 10 + 3*entrySize step 3) {
                    val clusterNum = clusterContents.toInt24(entryOffset).incClusterNum(increment).toInt24Arr()
                    System.arraycopy(clusterNum, 0, clusterContents, entryOffset, 3)
                }
            }

            // write modified cluster to the new (forward) position of the Archive
            file.seekToCluster(clusternum + increment)
            file.write(clusterContents)
        }

        fatClusterCount += increment

        // renumber (actually re-write) FAT on the Archive
        fatmgrRewriteFAT()

        // write new metavalues
        file.seek(64L)
        file.writeInt32(fatClusterCount)

    }

    /**
     * Creates a new FAT entry, adds it to the `fileTable` and the Archive via [fatmgrAddEntry], then returns the newly-created entry.
     */
    private fun fatmgrCreateNewEntry(charset: Charset, entryID: EntryID, readOnly: Boolean, hidden: Boolean, system: Boolean, deleted: Boolean, creationTime: Long, modificationTime: Long): FATEntry {
        FATEntry(charset, entryID, false, false, false, false, creationTime, modificationTime).let {
            // sync the FAT region on the Archive
            fatmgrAddEntry(it)

            return it
        }
    }

    /**
     * Adds the given entry to the `fileTable` and to the Archive, then recalculates the `fatEntryHighest`.
     * The added entry will be inserted to the tail of the FAT region and the modified `fatEntryHighest` will point to the given entry.
     */
    private fun fatmgrAddEntry(entry: FATEntry) {
        fileTable[entry.entryID] = entry

        // write changes to the disk
        val nextIndex = 1 + fatEntryHighest.first + fileTable[fatEntryHighest.second]!!.extendedEntries.size

        // toBytes() creates a clone of the entry and thus now de-synced from what's in the fileTable
        // (remember: spliceFAT may renumber FATs in the fileTable)
        // therefore it should be safe to do this
        spliceFAT(nextIndex, 0, *entry.toBytes())

        // `entry` is contained in the fileTable and thus should have been re-numbered by the spliceFAT
        fatEntryHighest = nextIndex to entry.entryID
    }

    /**
     * Rewrites FATs on the `fileTable` to the Archive, then updates the `fatEntryIndices` and `fatEntryHighest`.
     *
     * Following FAT entries will be discarded:
     * - Exists on the Archive but somehow not on the `fileTable`
     * - Marked as `deleted`
     */
    private fun fatmgrRewriteFAT() {

        fatEntryHighest = 2 to 0
        fatEntryIndices.clear()

        fileTable.entries.filter { !it.value.deleted }.sortedBy { it.key }.let { FATs ->
            // try to rewrite every entry on the FAT
            var fatIndex = 0
            while (fatIndex in 0 until (CLUSTER_SIZE / FAT_ENTRY_SIZE) * (fatClusterCount)) {
                file.seek(CLUSTER_SIZE * 2L + FAT_ENTRY_SIZE * fatIndex)

                // if the entry is real, write some values
                if (fatIndex < FATs.size) {

                    FATs[fatIndex].let { (ptr, fat) ->

                        // update fatEntryIndices
                        fatEntryIndices[ptr] = fatIndex

                        // update fatEntryHighest
                        if (fatIndex > fatEntryHighest.first) {
                            fatEntryHighest = fatIndex to ptr
                        }

                        // write bytes to the disk
                        // single FileAttrib may have multiple FATs associated
                        fat.toBytes().let { newFATbytes ->
                            newFATbytes.forEach { file.write(it) }
                            fatIndex += newFATbytes.size
                        }
                    }

                }
                // if not, fill with zeros
                else {
                    file.write(EMPTY_FAT_ENTRY)
                    fatIndex += 1
                }
            }
        }
    }

    fun getFile(clusterNum: Int): FATEntry? {
        return fileTable[clusterNum]
    }

    /**
     * Mark the cluster and the FAT entry as to-be-deleted. Cluster and FAT changes will be written to the Archive.
     * Deleted FAT will remain on the fileTable; use `fatmgrRewriteFAT()` to purge them.
     */
    fun discardFile(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot discard cluster #$clusterNum -- is Meta/Bootsector/FAT")
        if (clusterNum in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) throw IllegalArgumentException("Cannot discard inline file using this function")

        (fileTable[clusterNum] ?: throw FileNotFoundException("No file is associated with cluster #$clusterNum")).let {
            it.deleted = true

            file.seekToCluster(clusterNum)
            val flags = file.read()
            file.seekToCluster(clusterNum)
            file.write(flags or 0x80)
        }
    }

    /**
     * Creates new FAT then writes it to the Archive. Returned FATEntry will be registered on the fileTable.
     * @return newly-created FAT entry
     */
    fun allocateFile(size: Int, fileType: Int): FATEntry {
        checkDiskCapacity(size)
        val timeNow = System.currentTimeMillis() / 1000

        val ptr = if (size <= INLINING_THRESHOLD) getNextFreeInlineCluster() else usedClusterCount

        if (ptr in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) {
            fatmgrCreateNewEntry(charset, ptr, false, false, false, false, timeNow, timeNow).let {
                fatmgrAllocateInlineFile(it, size, fileType)
                return it
            }
        }
        else {
            fatmgrCreateNewEntry(charset, ptr, false, false, false, false, timeNow, timeNow).let {
                // actually create zero-filled clusters
                if (size > 0) {
                    expandFile(size, ptr, fileType) // will increment usedClusterCount
                }

                return it
            }
        }
    }

    /**
     * Expands a file by creating new clusters then returns the pointer to the start of the new clusters.
     * NextPtr of the current cluster will be the beginning of the newly-added clusters
     */
    private fun expandFile(sizeDelta: Int, currentCluster: Int, fileType: Int): Int {
        checkDiskCapacity(sizeDelta)
        val clustersToAdd = ceil(sizeDelta.toDouble() / CLUSTER_SIZE).toInt()
        val nextCluster = expandArchive(clustersToAdd)
        initClusters(currentCluster, nextCluster, clustersToAdd, fileType)
        return nextCluster
    }

    private fun initClusters(parentPtr: Int, clusterStart: Int, clusterCount: Int, fileType: Int) {
        val ptrs = listOf(parentPtr) + (clusterStart until clusterStart+clusterCount) + listOf(NULL_CLUSTER)

        for (k in 0 until clusterCount) {
            file.seekToCluster(clusterStart + k)
            file.write(fileType)
            file.write((parentPtr != NULL_CLUSTER || k != 0).toInt())
            file.writeInt24(ptrs[k])
            file.writeInt24(ptrs[k+1])
        }
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

    private fun expandArchive(clusterCount: Int): Int {
        val newPtr = usedClusterCount
        file.seek(newPtr.toLong())
        repeat(clusterCount) {
            file.write(EMPTY_CLUSTER)
            usedClusterCount += 1
        }
        return newPtr
    }

    /**
     * @return how many FAT clusters were added
     */
    fun growFAT(): Int {
        val nextFatSize = (2 + fatClusterCount) * 2 - 2
        val fatSizeDelta = nextFatSize - fatClusterCount
        renum(fatSizeDelta)
        return fatSizeDelta
    }

    /**
     * Inserts given FAT entries to the FAT area. The area will grow if required. After the copying operation, the file
     * will be seeked to the position where the first newly-inserted FAT entry begins.
     * @param insertPos where the new FATs will be inserted, FAT index-wise
     * @param deleteCount how many FATs, starting from the `insertPos`, must be deleted before adding
     * @param FATs actual FAT data
     * @return offset from the start of the Archive where the first new FAT is written
     */
    private fun spliceFAT(insertPos: Int, deleteCount: Int, vararg FATs: ByteArray): Long {
        checkDiskCapacity(FATs.size * FAT_ENTRY_SIZE)

        if (deleteCount > fatEntryCount) throw IllegalArgumentException("deleteCount ($deleteCount) is larger than the number of FAT entries in the Archive ($fatEntryCount)")

        // grow FAT area?
        if (insertPos * FAT_ENTRY_SIZE / CLUSTER_SIZE >= fatClusterCount) {
            val fatRenumDelta = growFAT()
            // renum inserting FATs
            FATs.forEach {
                it.renumFAT(fatRenumDelta)
            }
        }

        // shift FATS on the Archive
        val stride = (FATs.size - deleteCount) * FAT_ENTRY_SIZE
        val seekpos = 2L* CLUSTER_SIZE + insertPos*FAT_ENTRY_SIZE

        // moving one FAT at a time to constrain the memory footprint
        val forRange = if (stride > 0)
            2L*CLUSTER_SIZE + fatEntryCount* FAT_ENTRY_SIZE downTo seekpos step CLUSTER_SIZE.toLong()
        else if (stride < 0)
            seekpos..2L*CLUSTER_SIZE + fatEntryCount* FAT_ENTRY_SIZE step CLUSTER_SIZE.toLong()
        else
            LongRange.EMPTY

        for (startOffset in forRange) {
            file.seek(startOffset)
            val bytes = file.read(CLUSTER_SIZE)
            file.seek(startOffset + stride)
            file.write(bytes)
        }

        // delete trailing clusters if stride < 0
        if (stride < 0) {
            file.seek(2L * CLUSTER_SIZE + (fatEntryCount + FATs.size - deleteCount) * FAT_ENTRY_SIZE)
            for (i in 0 until deleteCount - FATs.size) {
                file.write(EMPTY_CLUSTER)
            }
        }

        // write new FATs
        file.seek(seekpos)
        FATs.forEach { bytes ->
            file.write(bytes)
        }

        fatEntryCount += FATs.size - deleteCount

        file.seek(seekpos)
        return seekpos
    }

    private fun writeBytesInline(inlinedFile: FATEntry, buffer: ByteArray, bufferOffset: Int, writeLength: Int, writeStartOffset: Int, fileType: Int) {
        val fileCopy = inlinedFile.getInlineBytes()
        val fileBytes = ByteArray(maxOf(fileCopy.size, writeStartOffset + writeLength))

        System.arraycopy(fileCopy, 0, fileBytes, 0, fileCopy.size)
        System.arraycopy(buffer, bufferOffset, fileBytes, writeStartOffset, writeLength)

        if (fileBytes.size <= INLINING_THRESHOLD) {
            fatmgrSetInlineBytes(inlinedFile, fileBytes)
        }
        // un-inline the file
        else {
            val uninlinedFile = allocateFile(fileBytes.size, fileType)
            uninlinedFile.copyAttribsFrom(inlinedFile)
            fileTable.remove(inlinedFile.entryID)
            fatEntryCount -= inlinedFile.extendedEntries.size + 1

            fatmgrRewriteFAT() // must precede the writeBytes
            writeBytes(uninlinedFile, fileBytes, 0, fileBytes.size, 0, fileType)
        }
    }

    fun writeBytes(entry: FATEntry, buffer: ByteArray, bufferOffset: Int, writeLength: Int, writeStartOffset: Int, fileType: Int) {
        if (entry.isInline) return writeBytesInline(entry, buffer, bufferOffset, writeLength, writeStartOffset, fileType)

        var writeCursor = writeStartOffset
        var remaining = writeLength

        var ptr = entry.entryID
        file.seekToCluster(ptr)
        var meta1 = file.read()
        var meta2 = file.read()
        file.readInt24()
        var nextPtr = file.readInt24()
        var contentsSizeInThisCluster = file.readUnsignedShort()

        var firstClusterOfWriting = true
        var firstClusterOfFile = true

        var cursorInClusterFileArea = writeStartOffset
        while (writeCursor < writeLength + writeStartOffset) {
            // seek to next cluster
            // if cursorInCluster is larger than FILE_BLOCK_CONTENTS_SIZE, this operation will loop until the file cursor is on the right cluster
            while (cursorInClusterFileArea >= FILE_BLOCK_CONTENTS_SIZE) {
                // if next cluster is NULL,,,
                if (nextPtr == 0) {
                    // allocate new cluster and then modify the nextPtr on the Archive
                    expandFile(remaining, ptr, fileType)
                }
                ptr = nextPtr
                file.seekToCluster(ptr)
                meta1 = file.read()
                meta2 = file.read()
                file.readInt24()
                nextPtr = file.readInt24()
                contentsSizeInThisCluster = file.readUnsignedShort()

                cursorInClusterFileArea -= FILE_BLOCK_CONTENTS_SIZE

                firstClusterOfFile = false
            }


            // mark the cluster as "dirty"
            file.seekToCluster(ptr)
            file.write(meta1.and(0xEF) or 32)
            file.write(meta2.and(0xFE) or firstClusterOfFile.toInt())


            // at the end of the skip-run, position the file to the right place
            file.seekToCluster(ptr, FILE_BLOCK_HEADER_SIZE + cursorInClusterFileArea)

            // cursor is moved to the right place, do the writing
            val writeLengthOnThisCluster = minOf(remaining, FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea, contentsSizeInThisCluster)
            // actually write
            file.write(buffer, bufferOffset + writeCursor - writeStartOffset, writeLengthOnThisCluster)
            remaining -= writeLengthOnThisCluster
            writeCursor += writeLengthOnThisCluster
            cursorInClusterFileArea += writeLengthOnThisCluster
            // update the 'number of bytes in this cluster' number on the file
            file.seekToCluster(ptr, FILE_BLOCK_HEADER_SIZE - 2)
            file.writeInt16(
                    if (firstClusterOfWriting)
                        writeLengthOnThisCluster + writeStartOffset
                    else
                        minOf(FILE_BLOCK_CONTENTS_SIZE, remaining)
            )
            firstClusterOfWriting = false


            // unset the dirty flag
            file.seekToCluster(ptr)
            file.write(meta1 and 0xEF)
        }
    }

    fun readBytes(entry: FATEntry, buffer: ByteArray, bufferOffset: Int, readLength: Int, readStartOffset: Int): Int {
        var readCursor = readStartOffset
        var remaining = readLength

        var ptr = entry.entryID
        file.seekToCluster(ptr)
        var meta1 = file.read()
        var meta2 = file.read()
        file.readInt24()
        var nextPtr = file.readInt24()
        var contentsSizeInThisCluster = file.readUnsignedShort()

        var firstClusterOfFile = true

        var actualBytesWritten = 0

        var cursorInClusterFileArea = readStartOffset
        while (readCursor < readLength + readStartOffset) {
            // seek to next cluster
            // if cursorInCluster is larger than FILE_BLOCK_CONTENTS_SIZE, this operation will loop until the file cursor is on the right cluster
            while (cursorInClusterFileArea >= FILE_BLOCK_CONTENTS_SIZE) {
                // if next cluster is NULL,,,
                if (nextPtr == 0) {
                    // throw error
                    throw VDIOException("Unexpected end-of-cluster reached (file: ${entry.entryID}, read cursor: $readCursor)")
                }
                ptr = nextPtr
                file.seekToCluster(ptr)
                meta1 = file.read()
                meta2 = file.read()
                file.readInt24()
                nextPtr = file.readInt24()
                contentsSizeInThisCluster = file.readUnsignedShort()

                cursorInClusterFileArea -= FILE_BLOCK_CONTENTS_SIZE

                firstClusterOfFile = false
            }

            // at the end of the skip-run, position the file to the right place
            file.seekToCluster(ptr, FILE_BLOCK_HEADER_SIZE + cursorInClusterFileArea)

            // cursor is moved to the right place, do the reading
            val readLengthOnThisCluster = minOf(remaining, FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea, contentsSizeInThisCluster)
            // actually read
            file.read(buffer, bufferOffset + readCursor - readStartOffset, readLengthOnThisCluster)
            actualBytesWritten += readLengthOnThisCluster
            remaining -= readLengthOnThisCluster
            readCursor += readLengthOnThisCluster
            cursorInClusterFileArea += readLengthOnThisCluster
        }

        return actualBytesWritten
    }

    fun readBytes(entry: FATEntry, length: Int, offset: Int): ByteArray {
        val ba = ByteArray(length)
        val actualSize = readBytes(entry, ba, 0, length, offset)

        if (ba.size == actualSize) return ba

        val ba2 = ByteArray(actualSize)
        System.arraycopy(ba, 0, ba2, 0, actualSize)
        return ba2
    }


    fun setFileLength(entry: FATEntry, newLength: Int, fileType: Int) {
        var remaining = newLength

        var cluster = entry.entryID

        do {
            // seek to cluster
            file.seekToCluster(cluster, 5)
            // get length for this cluster
            val thisClusterLen = minOf(remaining, FILE_BLOCK_CONTENTS_SIZE)
            // get next cluster
            var nextCluster = file.readInt24()
            // write new contents length
            file.writeInt16(thisClusterLen)
            // subtract remaining
            remaining -= thisClusterLen

            // mark end-of-cluster if applicable
            if (remaining == 0) {
                file.seekToCluster(cluster, 5)
                file.writeInt16(NULL_CLUSTER)
            }
            // create new cluster if end-of-cluster is prematurely reached
            else if (nextCluster == NULL_CLUSTER) {
                nextCluster = expandFile(remaining, cluster, fileType)
            }

            cluster = nextCluster
        } while (remaining > 0)
    }

    fun getFileLength(entry: FATEntry): Int {
        var accumulator = 0

        var cluster = entry.entryID

        do {
            // seek to cluster
            file.seekToCluster(cluster, 5)
            // get next cluster
            val nextCluster = file.readInt24()
            // get length for this cluster
            val len = file.readUnsignedShort()
            // add to accumulator
            accumulator += len


            cluster = nextCluster
        } while (cluster > 0)

        return accumulator
    }

    fun getFileType(entry: FATEntry): Int = file.let {
        it.seek(entry.entryID.toLong() * CLUSTER_SIZE)
        val b = it.read()
        if (b == -1) throw IOException("The Archive cannot be read; offset: ${entry.entryID.toLong() * CLUSTER_SIZE}")
        return b and 15
    }

    fun isDirectory(entry: FATEntry) = getFileType(entry) == 1
    fun isFile(entry: FATEntry) = getFileType(entry) == 0

    fun defrag(option: Int) {
        TODO()
    }




    fun getFileIterator(clusterNum: Int): ByteIterator {
        if (clusterNum !in 2 + fatClusterCount..usedClusterCount)
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
internal fun ByteArray.toInt16(offset: Int = 0): Int {
    return  this[0 + offset].toUint().shl(8) or
            this[1 + offset].toUint()
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
internal fun ByteArray.writeInt24(value: Int, offset: Int) {
    this[offset+0] = value.ushr(16).toByte()
    this[offset+1] = value.ushr(8).toByte()
    this[offset+2] = value.ushr(0).toByte()
}