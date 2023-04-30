package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.*
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskSkimmer.Companion.read
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.ATTRIBS_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.NAME_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.CLUSTER_SIZE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FAT_ENTRY_SIZE
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.HashMap
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

private fun ByteArray.renumFAT(increment: Int): ByteArray {
    if (this.size != FAT_ENTRY_SIZE) throw IllegalStateException()

    val entryID = this.toInt24().incClusterNum(increment)
    // increment parent ID
    this.writeInt24(entryID, 0)

    // increment parent IDs on the Extended Entries
    // inline directories
    if (entryID == 0xFFFF11) {
        for (offset in 8 until 256 step 3) {
            val originalID = this.toInt24(offset)
            val newFileID = originalID.incClusterNum(increment)
            this.writeInt24(newFileID, offset)
        }
    }

    return this
}

private fun ByteArray.renumCluster(increment: Int): ByteArray {
    if (this.size != CLUSTER_SIZE) throw IllegalStateException()

    val meta1 = this[0].toUint()
    val meta2 = this[1].toUint()
    val newPrevPtr = this.toInt24(2).incClusterNum(increment)
    val newNextPtr = this.toInt24(5).incClusterNum(increment)

    val filetype = meta1 and 15

    // renumber prev and next ptr
    this.writeInt24(newPrevPtr, 2)
    this.writeInt24(newNextPtr, 5)

    // renumber clusternum on the directory files
    if (filetype == 1) {
        val entrySize = this.toInt16(8)
        for (entryOffset in 10 until 10 + 3*entrySize step 3) {
            val clusterNum = this.toInt24(entryOffset).incClusterNum(increment).toInt24Arr()
            System.arraycopy(clusterNum, 0, this, entryOffset, 3)
        }
    }

    return this
}

class ClusteredFormatDOM(private val file: RandomAccessFile, val charset: Charset, val throwErrorOnReadError: Boolean = false) {

    private inline fun testPause(msg: Any?) {
//        dbgprintln("\n\n== $msg ==\n\n"); dbgprint("> "); Scanner(System.`in`).nextLine()
    }

    private inline fun dbgprint(msg: Any? = "") {
        print(msg)
    }

    private inline fun dbgprintln(msg: Any? = "") {
        println(msg)
    }

    companion object {
        const val CLUSTER_SIZE = 4096 // as per spec
        const val FAT_ENTRY_SIZE = 256 // as per spec
        const val FATS_PER_CLUSTER = CLUSTER_SIZE / FAT_ENTRY_SIZE // typically 16
        private val EMPTY_CLUSTER = ByteArray(CLUSTER_SIZE)
        private val EMPTY_FAT_ENTRY = ByteArray(FAT_ENTRY_SIZE)

        const val FILE_BLOCK_HEADER_SIZE = 10 // as per spec
        const val FILE_BLOCK_OFFSET_CONTENT_LEN = 8 // as per spec
        const val FILE_BLOCK_CONTENTS_SIZE = CLUSTER_SIZE - FILE_BLOCK_HEADER_SIZE // typically 4086

        const val NULL_CLUSTER = 0
        const val HEAD_CLUSTER = 0
        const val LEAF_CLUSTER = 0xFFFFFF
        const val INLINE_FILE_CLUSTER_BASE = 0xF00000
        const val INLINE_FILE_CLUSTER_LAST = 0xFFFDFF
        const val EXTENDED_ENTRIES_BASE = 0xFFFF00

        const val INLINED_ENTRY_BYTES = FAT_ENTRY_SIZE - 8 // typically 248
        const val FILENAME_PRIMARY_LENGTH = FAT_ENTRY_SIZE - 16 // typically 240

        const val INLINING_THRESHOLD = INLINED_ENTRY_BYTES * 8 // compare with <= -- files up to this size is recommended to be inlined

        fun createNewArchive(outPath: File, charset: Charset, diskName: String, capacityInSectors: Int): RandomAccessFile {
            val timeNow = (System.currentTimeMillis() / 1000L).toInt48Arr()
            val file = FileOutputStream(outPath)

            //// CLUSTER 0 ////
            // header
            file.write(VirtualDisk.MAGIC)
            // capacity
            file.write((CLUSTER_SIZE * capacityInSectors).toLong().toInt48Arr())
            // disk name
            file.write(diskName.toEntryName(32, charset))
            // dummy CRC
            file.write(0.toInt32Arr())
            // version
            file.write(ClusteredFormatArchiver.specversion.toInt())
            // attributes
            file.write(0)
            // more attirbutes
            repeat(16) { file.write(0) }
            // FAT size (2)
            file.write(2.toInt32Arr())
            // cluster filler
            file.write(ByteArray(4028))

            //// CLUSTER 1 ////
            // dummy bootsector
            file.write(EMPTY_CLUSTER)

            //// CLUSTER 2-3 ////
            // FAT for the root
            file.write(byteArrayOf(0, 0, 4, 4))
            // creation date
            file.write(timeNow)
            // modification date
            file.write(timeNow)
            // empty filename
            file.write(ByteArray(240))
            // rest of the sectors
            val remainingSectCnt = 2 * FATS_PER_CLUSTER - 1
            repeat(remainingSectCnt) { file.write(EMPTY_FAT_ENTRY) }

            //// CLUSTER 4 ////
            // root directory
            file.write(ROOT_DIR_CLUSTER)


            file.flush(); file.close()

            return RandomAccessFile(outPath, "rw")
        }

        /** Typically the root dir will sit on ID=4 */
        internal val ROOT_DIR_CLUSTER = ByteArray(CLUSTER_SIZE).also {
            // meta1 (type:dir)
            it[0] = 1
            // meta2 (persistent:true)
            it[1] = 0x80.toByte()
            // prev ptr
            it.writeInt24(HEAD_CLUSTER, 2)
            // next ptr
            it.writeInt24(LEAF_CLUSTER, 5)
        }


    }

    /*private*/ val fatEntryIndices = HashMap<EntryID, Int>() // EntryID, FATIndex
    private var fatEntryHighest = -1 to -1 // FATIndex, EntryID

    data class FATEntry(
            val charset: Charset,

            /** if ID is 1, the file is 0-byte file */
            var entryID: EntryID,

            var readOnly: Boolean,
            var hidden: Boolean,
            var system: Boolean,
            var deleted: Boolean,

            var isInlineDirectory: Boolean = false,

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

                        fat256[3].and(16.toByte()) != 0.toByte(),

                        fat256.sliceArray(4..9).toInt48(),
                        fat256.sliceArray(10..15).toInt48(),
                        fat256.sliceArray(16..255).toCanonicalString(charset),

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

            ba.writeInt24(entryID, 0)


            val flags = readOnly.toInt(0) or
                    hidden.toInt(1) or
                    system.toInt(2) or
                    deleted.toInt(7)
            val cd = creationDate.toInt48Arr()
            val md = modificationDate.toInt48Arr()
            val name = filename.toEntryName(240, charset)

            ba[3] = flags.toByte()
            System.arraycopy(cd, 0, ba, 4, 6)
            System.arraycopy(md, 0, ba, 10, 6)
            System.arraycopy(name, 0, ba, 16, 240)

            return listOf(ba) + extendedEntries
        }

        /**
         * Called by ClusteredFormatDOM, this function assists the global renum operation.
         */
        internal fun _fatRenum(increment: Int) {
            if (entryID > 2 && entryID < INLINE_FILE_CLUSTER_BASE) {
                entryID += increment
                extendedEntries.forEach {
                    it.renumFAT(increment)
                }
            }
        }

        val isInline: Boolean
            get() = entryID in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST

        fun takeAttribsFrom(other: FATEntry) {
            this.entryID = other.entryID
            this.readOnly = other.readOnly
            this.hidden = other.hidden
            this.system = other.system
            this.deleted = other.deleted
            this.creationDate = other.creationDate
            this.modificationDate = other.modificationDate
        }

        fun validate() {
            val inlineType = 0xFFFF10 or isInlineDirectory.toInt()
            extendedEntries.forEachIndexed { index, bytes ->
                val type = bytes.toInt24()
                if (type xor 0xFFFF10 < 16 && type != inlineType)
                    throw IllegalStateException("Contradictory Inlining Extended Entries (both ${type.and(15)} and ${inlineType.and(15)} exist)")
            }
        }

        fun getInlineBytes(): ByteArray {
            extendedEntries.filter { it.toInt24() xor 0xFFFF10 < 16 }.let {
                var lengthAkku = 0
                val ba = ByteArray(INLINED_ENTRY_BYTES * it.size) // must be truncated afterwards

                it.forEach {
                    val size = it[7].toUint()
                    System.arraycopy(it, 8, ba, lengthAkku, size)
                    lengthAkku += size
                }

                return ba.sliceArray(0 until lengthAkku)
            }
        }

        fun getInlinedLength(): Int {
            extendedEntries.filter { it.toInt24() xor 0xFFFF10 < 16 }.let {
                var lengthAkku = 0

                it.forEach {
                    val size = it[7].toUint()
                    lengthAkku += size
                }

                return lengthAkku
            }
        }

        fun hasExtraEntryWithType(type: Int): Boolean = hasExtraEntryWithType { it == type }

        fun hasExtraEntryWithType(predicate: (Int) -> Boolean): Boolean {
            return extendedEntries.any { predicate(it.toInt24()) }
        }
    }

    private fun getTimeNow() = System.currentTimeMillis() / 1000

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
    private var fatClusterCount = -1
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
        tallyClusterCount()
    }

    fun notifyError(throwable: Throwable) {
        if (throwErrorOnReadError)
            throw throwable
        else
            throwable.printStackTrace()
    }

    private fun tallyClusterCount() {
        if (fatClusterCount < 0) throw InternalError("Uninitialised -- call readMeta() and readFAT() first")
        usedClusterCount = 2 + fatClusterCount

        fileTable.forEach { id, entry ->
            var clusterCount = 0
            traverseClusters(entry.entryID) { clusterCount += 1 }
            usedClusterCount += 1
        }

        dbgprintln("[Clustered] usedClusterCount: $usedClusterCount")
    }

    private fun readMeta() {
        file.seek(4L)
        diskSize = file.readInt48()
        file.readBytes(diskName)
        file.seek(47L)
        primaryAttribs = file.read()
        file.readBytes(userAttribs)
        fatClusterCount = file.readIntBig()
    }

    private fun readFAT() {
        if (fatClusterCount < 0) throw InternalError("Disk has not been read")
        if (fatClusterCount == 0) throw RuntimeException("Invalid FAT size ($fatClusterCount)")

        file.seek(2L * CLUSTER_SIZE)
        for (block in 2 until 2 + fatClusterCount) {
            for (blockOff in 0 until CLUSTER_SIZE step FAT_ENTRY_SIZE) {

                val fat = file.read(FAT_ENTRY_SIZE)

                val mainPtr = fat.toInt24()

//                dbgprintln("[Clustered] FAT ptr: $mainPtr")

                // Extended Entries
                if (mainPtr >= EXTENDED_ENTRIES_BASE) {
                    val parentPtr = fat.toInt24(3)
                    fileTable[parentPtr]?.extendedEntries?.add(fat) ?: notifyError(IllegalStateException("Extended Entry 0x${parentPtr.toHex().drop(2)} is reached but no main FAT entry (ID $parentPtr) was found"))
                }
                // normal entries
                else if (mainPtr >= 2 + fatClusterCount) {
                    fileTable[mainPtr] = FATEntry.fromBytes(charset, fat).second
                }

                if (mainPtr != 0) {
                    fatEntryCount += 1

                    val fatIndex = blockOff / FAT_ENTRY_SIZE + block * FAT_ENTRY_SIZE / CLUSTER_SIZE
                    fatEntryIndices[mainPtr] = fatIndex

//                    dbgprintln("[Clustered] - index: $fatIndex")

                    if (fatIndex > fatEntryHighest.first) {
//                        dbgprintln("[Clustered] - new fatEntryHighest: $fatIndex to $mainPtr")
                        fatEntryHighest = fatIndex to mainPtr
                    }
                }

            }
        }


        fileTable.forEach { _, fatEntry -> fatEntry.validate() }

//        dbgprintln("fatEntryHighest = $fatEntryHighest")
    }

    private fun checkDiskCapacity(bytesToAdd: Int) {
        if (bytesToAdd <= 0) return
        val usedBytes = CLUSTER_SIZE * usedClusterCount + bytesToAdd
        if (usedBytes > diskSize) throw VDIOException("Not enough space on the disk")
    }

    /**
     * Shifts the cluster number of every entry to given number, then actually inserts new clusters to the FAT area.
     * @param increment number of clusters to be inserted
     */
    fun renum(increment: Int) {
        val oldUsedClusterCount = usedClusterCount

        expandArchive(increment) // implied: usedClusterCount += increment

        // renumber my FAT
        HashMap<Int, FATEntry>().let { newFileTable ->
            fileTable.entries.forEach { (clusternum, entry) ->

                dbgprint("[Clustered] renum($increment) -- writing new fileTable: entryID $clusternum (actual ID: ${entry.entryID}) => ")

                entry._fatRenum(increment)
                newFileTable[clusternum.incClusterNum(increment)] = entry

                dbgprintln("entryID ${clusternum.incClusterNum(increment)} (actual ID: ${entry.entryID})")
            }
            fileTable = newFileTable
        }

        // copy over clusters, renumber any applicable cluster numbers before copying
        // using file.length().div(CLUSTER_SIZE) instead of usedClusterCount to really access every clusters
        for (clusternum in oldUsedClusterCount - 1L downTo 2 + fatClusterCount) {
            dbgprintln("[Clustered] renum($increment) -- moving cluster ${clusternum.toHex()} to ${(clusternum + increment).toHex()}")

            file.seekToCluster(clusternum)


            // do the actual renumbering on the cluster contents
            val clusterContents = file.read(CLUSTER_SIZE)
            clusterContents.renumCluster(increment)


            // write modified cluster to the new (forward) position of the Archive
            file.seekToCluster(clusternum + increment)
            file.write(clusterContents)
        }

        fatClusterCount += increment

        // renumber FAT on the Archive
        dbgprintln("[Clustered] renum($increment) -- about to renum FATs (fatClusterCount = $fatClusterCount, FATs: $fatEntryCount/${fatClusterCount * FATS_PER_CLUSTER})")
        fatEntryIndices.clear()
        for (kluster in 0 until fatEntryCount) {
            file.seekToFAT(kluster)
            val fat = file.read(FAT_ENTRY_SIZE).renumFAT(increment)
            file.seekToFAT(kluster)
            file.write(fat)

            val entryID = fat.toInt24(0)
            if (entryID < EXTENDED_ENTRIES_BASE) {
                fatEntryIndices[entryID] = kluster
            }
        }
        for (qluster in fatEntryCount until fatClusterCount * FATS_PER_CLUSTER) {
            file.seekToFAT(qluster)
            file.write(EMPTY_FAT_ENTRY)
        }
        // fatmgrAddEntry() may break write order -- renumber the FATs manually as above


        // write new metavalues
        file.seek(64L)
        file.writeInt32(fatClusterCount)

    }

    private fun fatmgrUpdateModificationDate(entry: FATEntry, time: Long) {
        dbgprintln("[Clustered] fatmgrUpdateModificationDate on ${entry.entryID}")

//        dbgprintln("[Clustered] fatEntryIndices:")
//        fatEntryIndices.forEach { id, fatIndex ->
//            dbgprintln("                entryID ${id.toHex()} -> fatIndex ${(2 * CLUSTER_SIZE + fatIndex * FAT_ENTRY_SIZE).toHex()}")
//        }

        file.seekToFAT(fatEntryIndices[entry.entryID]!!, 10)
        file.writeInt48(time)
    }

    /**
     * Creates a new FAT entry, adds it to the `fileTable` and the Archive via [fatmgrAddEntry], then returns the newly-created entry.
     */
    private fun fatmgrCreateNewEntry(charset: Charset, entryID: EntryID, filename: String, readOnly: Boolean, hidden: Boolean, system: Boolean, deleted: Boolean, creationTime: Long, modificationTime: Long, isInlineDir: Boolean = false): FATEntry {
        FATEntry(charset, entryID, readOnly, hidden, system, deleted, isInlineDir, creationTime, modificationTime).let {
            it.filename = filename
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
        // write changes to the disk
        val nextIndex = fatEntryCount

        // toBytes() creates a clone of the entry and thus now de-synced from what's in the fileTable
        // (remember: spliceFAT may renumber FATs in the fileTable)
        // therefore it should be safe to do this
        spliceFAT(nextIndex, 0, entry.toBytes())

        fileTable[entry.entryID] = entry
        // `entry` is contained in the fileTable and thus should have been re-numbered by the spliceFAT
        fatEntryHighest = nextIndex to entry.entryID
        fatEntryIndices[entry.entryID] = nextIndex

        dbgprintln("[Clustered] fatmgrAddEntry -- entryID: ${entry.entryID.toHex()}, FAT index: ${fatEntryIndices[entry.entryID]}")
    }

    private fun fatmgrAllocateInlineFile(entry: FATEntry, size: Int, fileType: Int) {
        val myEntryIndex = fatEntryIndices[entry.entryID]
                ?: throw IllegalArgumentException("No such file with following ID: ${entry.entryID}")

        if (!entry.isInline) throw IllegalArgumentException("File is not inline (ID: ${entry.entryID})")
        if (entry.hasExtraEntryWithType { it xor 0xFFFF10 < 16 }) throw IllegalArgumentException("File is already allocated and is inline (ID: ${entry.entryID})")

        val entrySizeDelta = ceil(size.toDouble() / INLINED_ENTRY_BYTES).toInt()

        // create bytearrays to append to
        val newBytes = List(entrySizeDelta) { ByteArray(FAT_ENTRY_SIZE) }
        val newBytesStart = entry.extendedEntries.size
        var allocBytesRemaining = size
        newBytes.forEachIndexed { index, bytes ->
            // write Extra Entry Header
            bytes.writeInt24(0xFFFF10 or fileType, 0)
            bytes.writeInt24(entry.entryID, 3)
            bytes[6] = index.toByte()
            bytes[7] = minOf(INLINED_ENTRY_BYTES, allocBytesRemaining).toByte()

            allocBytesRemaining -= INLINED_ENTRY_BYTES
        }

        // put the bytearrays to the entry in the memory
        entry.extendedEntries.addAll(newBytes)

        // shift FAT entries up
        fatEntryIndices.entries.filter { it.value > myEntryIndex }.forEach { (entryID, fatIndex) ->
            fatEntryIndices[entryID] = fatIndex + entrySizeDelta
        }


        dbgprintln("[Clustered] fatmgrAllocateInlineFile1 -- entryID: ${entry.entryID.toHex()}, FAT index: ${fatEntryIndices[entry.entryID]}")

        // splice the bytearrays into the Archive
        spliceFAT(myEntryIndex + newBytesStart + 1, 0, newBytes)


        dbgprintln("[Clustered] fatmgrAllocateInlineFile2 -- entryID: ${entry.entryID.toHex()}, FAT index: ${fatEntryIndices[entry.entryID]}")
    }

    /**
     * Unlike [writeBytes], this function will change the length of the file -- all the previous bytes will be replaced!
     */
    private fun fatmgrSetInlineBytes(entry: FATEntry, infileBytes: ByteArray, fileType: Int) {
        val myEntryIndex = fatEntryIndices[entry.entryID]
                ?: throw IllegalArgumentException("No such file with following ID: ${entry.entryID.toHex()}")

        if (!entry.isInline) throw IllegalArgumentException("File is not inline (ID: ${entry.entryID.toHex()})")

        val oldExtendedEntryCount = entry.extendedEntries.size

        val newBytes = List(ceil(infileBytes.size.toDouble() / INLINED_ENTRY_BYTES).toInt()) { ByteArray(FAT_ENTRY_SIZE) }

        // write bytes to the newBytes
        var remaining = infileBytes.size
        var written = 0
        newBytes.forEachIndexed { index, bytes ->
            val writeSize = minOf(INLINED_ENTRY_BYTES, remaining)

            // write Extra Entry Header
            bytes.writeInt24(0xFFFF10 or fileType, 0)
            bytes.writeInt24(entry.entryID, 3)
            bytes[6] = index.toByte()
            bytes[7] = writeSize.toByte()
            System.arraycopy(infileBytes, written, bytes, 8, writeSize)

            remaining -= INLINED_ENTRY_BYTES
            written += INLINED_ENTRY_BYTES
        }


        // remove any inline bytes of matching type from the entry
        entry.extendedEntries.removeIf { it.toInt24(0) == 0xFFFF10 or fileType }
        // then append newBytes
        entry.extendedEntries.addAll(newBytes)

        // splice up the Archive
        spliceFAT(myEntryIndex + 1, oldExtendedEntryCount, entry.extendedEntries)
    }

    private fun fatmgrSetFilename(entry: FATEntry, name: ByteArray) {
        name.trimNull().let { name ->

            if (name.size <= FILENAME_PRIMARY_LENGTH) {

            }
            else {
                val filenameHead = name.sliceArray(0 until FILENAME_PRIMARY_LENGTH)
            }
        }
    }

    /**
     * Rewrites FATs on the `fileTable` to the Archive, then updates the `fatEntryIndices` and `fatEntryHighest`.
     *
     * Following FAT entries will be discarded:
     * - Exists on the Archive but somehow not on the `fileTable`
     * - Marked as `deleted`
     *
     * `fatEntryCount` will be inc/decremented accordingly.
     */
    private fun fatmgrRewriteFAT() {

        fatEntryHighest = 2 to 0
        fatEntryIndices.clear()
        fatEntryCount = 0


        fileTable.entries.sortedBy { it.key }.let { FATs ->
            // try to rewrite every entry on the FAT
            var fatWritePos = 0
            file.seekToFAT(0)
            FATs.forEach { (ptr, fat) ->
                // update fatEntryIndices
                fatEntryIndices[ptr] = fatWritePos

                // update fatEntryHighest
                if (fatWritePos > fatEntryHighest.first) {
                    fatEntryHighest = fatWritePos to ptr
                }

                // write bytes to the disk
                // single FileAttrib may have multiple FATs associated
                fat.toBytes().let { newFATbytes ->
                    newFATbytes.forEach {
                        file.write(it)
                        fatWritePos += 1
                        fatEntryCount += 1
                    }
                }
            }

            for (qluster in fatEntryCount until fatClusterCount * FATS_PER_CLUSTER) {
                file.seekToFAT(qluster)
                file.write(EMPTY_FAT_ENTRY)
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
    fun allocateFile(size: Int, fileType: Int, filename: String): FATEntry {
        if (fileType != 0 && fileType != 1) throw IllegalArgumentException("Unknown File type: $fileType")

        checkDiskCapacity(size)
        val timeNow = getTimeNow()

        val ptr = if (size <= INLINING_THRESHOLD) getNextFreeInlineCluster() else usedClusterCount

        dbgprintln("[Clustered] allocateFile where: ${ptr.toHex()}")

        if (ptr in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) {
            fatmgrCreateNewEntry(charset, ptr, filename, false, false, false, false, timeNow, timeNow, fileType == 1).let {
                fatmgrAllocateInlineFile(it, size, fileType)
                return it
            }
        }
        else {
            fatmgrCreateNewEntry(charset, ptr, filename, false, false, false, false, timeNow, timeNow).let {
                // actually create zero-filled clusters
                if (size > 0) {
                    expandFile(size, HEAD_CLUSTER, ptr, fileType) // will increment usedClusterCount
                }

                return it
            }
        }
    }

    /**
     * Expands a file by creating new clusters then returns the pointer to the start of the new clusters.
     * NextPtr of the current cluster will be the beginning of the newly-added clusters
     *
     * @return Cluster ID of the head of the new cluster; `LEAF_CLUSTER` if sizeDelta < 0; `currentCluster` if sizeDelta == 0
     */
    private fun expandFile(sizeDelta: Int, prevCluster: Int, currentCluster: Int, fileType: Int): Int {
        dbgprintln("[Clustered] expandFile(sizeDelta=$sizeDelta, prevCluster=$prevCluster, currentCluster=$currentCluster, fileType=$fileType)")

        if (sizeDelta < 0) return LEAF_CLUSTER
        if (sizeDelta == 0) return currentCluster

        val clustersToAdd = ceil(sizeDelta.toDouble() / CLUSTER_SIZE).toInt()
        val nextCluster = expandArchive(clustersToAdd)

        // expanding on existing
        if (nextCluster != currentCluster) {
            dbgprintln("[Clustered] expand on existing cluster? (currentCluster=$currentCluster, nextCluster=$nextCluster)")

            // set the next cluster (previously 0xFFFFFF)
            file.seekToCluster(currentCluster, 5)
            file.writeInt24(nextCluster)
        }

        // increment content size of this cluster
        file.seekToCluster(currentCluster, FILE_BLOCK_OFFSET_CONTENT_LEN)
        val currentContentSize = file.readUshortBig()
        val sizeNumToWrite = minOf(FILE_BLOCK_CONTENTS_SIZE, currentContentSize + sizeDelta)
        val sizeDiscount = sizeNumToWrite - currentContentSize

        dbgprintln("[Clustered] sizeNumToWrite=$sizeNumToWrite, currentContentSize=$currentContentSize, sizeDiscount=$sizeDiscount")

        file.seekToCluster(currentCluster, FILE_BLOCK_OFFSET_CONTENT_LEN)
        file.writeInt16(sizeNumToWrite)

        dbgprintln("[Clustered] expandFile -- writing contentSize $sizeNumToWrite to cluster ${currentCluster.toHex()}")

        if (nextCluster != currentCluster)
            initClusters(sizeDelta - sizeDiscount, currentCluster, nextCluster, clustersToAdd, fileType)

        else
            initClusters(sizeDelta - sizeDiscount, prevCluster, currentCluster, clustersToAdd, fileType)
        
        return nextCluster
    }

    private fun initClusters(sizeDelta: Int, parent: Int, current: Int, clusterCount: Int, fileType: Int, next: Int? = null) {
        val ptrs = if (next == null)
            listOf(parent) + (current until current+clusterCount) + listOf(LEAF_CLUSTER)
        else
            listOf(parent, current) + (next until next+clusterCount) + listOf(LEAF_CLUSTER)

        var remaining = sizeDelta
        for (k in 0 until clusterCount) {
            file.seekToCluster(ptrs[k+1])
            file.write(fileType)
            file.write(0)
            file.writeInt24(ptrs[k])
            file.writeInt24(ptrs[k+2])
            // write content size for the clusters
            val currentContentSize = file.readUshortBig()
            val sizeNumToWrite = minOf(FILE_BLOCK_CONTENTS_SIZE, currentContentSize + remaining)
            remaining -= (sizeNumToWrite - currentContentSize)
            file.seekToCluster(ptrs[k+1], FILE_BLOCK_OFFSET_CONTENT_LEN)
            file.writeInt16(sizeNumToWrite)

            dbgprintln("[Clustered] initClusters -- writing contentSize $sizeNumToWrite to cluster ${ptrs[k+1].toHex()}")
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
        file.seekToCluster(newPtr.toLong())
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
     *
     * `fatEntryCount` will be inc/decremented accordingly.
     *
     * @param insertPos where the new FATs will be inserted, FAT index-wise
     * @param deleteCount how many FATs, starting from the `insertPos`, must be deleted before adding. If the value is less than zero, it will be treated as zero
     * @param FATs actual FAT data
     * @return offset from the start of the Archive where the first new FAT is written
     */
    private fun spliceFAT(insertPos: Int, deleteCount: Int, FATs: List<ByteArray>): Long {
        checkDiskCapacity(FATs.size * FAT_ENTRY_SIZE)

        val deleteCount = deleteCount.coerceAtLeast(0)

        if (deleteCount > fatEntryCount) throw IllegalArgumentException("deleteCount ($deleteCount) is larger than the number of FAT entries in the Archive ($fatEntryCount)")


        dbgprintln("[Clustered] spliceFAT called; insertPos=$insertPos(foff: ${(2 * CLUSTER_SIZE + insertPos * FAT_ENTRY_SIZE).toHex()}), deleteCount=$deleteCount, FATs=(${FATs.size})[${FATs.joinToString(" ; ") { it.sliceArray(0..15).joinToString(" ") { it.toUint().toString(16).padStart(2, '0').toUpperCase() } }}], current FAT cap: ${fatClusterCount * FATS_PER_CLUSTER}")


        // grow FAT area?
        if (insertPos + FATs.size - 1 >= fatClusterCount * FATS_PER_CLUSTER) {
            testPause("about to grow FAT area; check the archive, then hit Return to continue")


            val fatRenumDelta = growFAT()
            // renum inserting FATs
            FATs.forEach {
                it.renumFAT(fatRenumDelta)
            }


            testPause("FAT area has grown; check the archive, then hit Return to continue")
        }

        // shift FATS on the Archive
        val strideByEntry = (FATs.size - deleteCount)
        val stride = (FATs.size - deleteCount) * FAT_ENTRY_SIZE
        val seekpos = 2L* CLUSTER_SIZE + insertPos*FAT_ENTRY_SIZE


        // moving one FAT at a time to constrain the memory footdbgprint
        val forRange = if (stride > 0)
            2L*CLUSTER_SIZE + (fatEntryCount - 1) * FAT_ENTRY_SIZE downTo seekpos step FAT_ENTRY_SIZE.toLong()
        else if (stride < 0)
            seekpos..2L*CLUSTER_SIZE + (fatEntryCount - 1) * FAT_ENTRY_SIZE step FAT_ENTRY_SIZE.toLong()
        else
            LongRange.EMPTY

        dbgprintln("[Clustered]     splice delete=$deleteCount, add=${FATs.size}; stride=$stride, fatEntryCount=$fatEntryCount, forRange=${if (forRange.isEmpty()) "(empty)" else "${forRange.first.toHex()} to ${forRange.last.toHex()}"}")

        for (startOffset in forRange) {
            dbgprintln("[Clustered]         moving FAT at ${startOffset.toHex()} to ${(startOffset + stride).toHex()}")
            file.seek(startOffset)
            val bytes = file.read(FAT_ENTRY_SIZE)
            file.seek(startOffset + stride)
            file.write(bytes)

            val entryID = bytes.toInt24()

            dbgprintln("[Clustered]           FAT at ${startOffset.toHex()}: ${bytes.sliceArray(0..15).joinToString(" ") { it.toUint().toString(16).padStart(2, '0').toUpperCase() }}")

            if (entryID in 2..INLINE_FILE_CLUSTER_BASE) {
                dbgprint("[Clustered]         trying to change FAT Index for entry $entryID")
                fatEntryIndices[entryID]?.let {
                    dbgprint("....${it} -> ${(it + strideByEntry)}")
                    fatEntryIndices[entryID] = it + strideByEntry
                }
                dbgprintln()
            }
        }

        // delete trailing FATs if stride < 0
        if (stride < 0) {
            file.seek(2L * CLUSTER_SIZE + (fatEntryCount + FATs.size - deleteCount) * FAT_ENTRY_SIZE)
            for (i in 0 until deleteCount - FATs.size) {
                file.write(EMPTY_FAT_ENTRY)
            }
        }

        // write new FATs
        if (FATs.isNotEmpty()) {
            dbgprintln("[Clustered]     writing FATs at file offset ${seekpos.toHex()}")
            file.seek(seekpos)
            FATs.forEach { bytes ->
                file.write(bytes)
            }
        }

        dbgprintln("[Clustered]     incrementing fatEntryCount by ${FATs.size - deleteCount} (${fatEntryCount} -> ${fatEntryCount + FATs.size - deleteCount})")
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
            inlinedFile.modificationDate = getTimeNow()
            fatmgrSetInlineBytes(inlinedFile, fileBytes, fileType)
        }
        // un-inline the file
        else {
            val uninlinedFile = allocateFile(fileBytes.size, fileType, inlinedFile.filename)
            uninlinedFile.takeAttribsFrom(inlinedFile)
            fileTable.remove(inlinedFile.entryID)
            fatEntryCount -= inlinedFile.extendedEntries.size + 1

            fatmgrRewriteFAT() // must precede the writeBytes
            writeBytes(uninlinedFile, fileBytes, 0, fileBytes.size, 0, fileType)
        }
    }

    fun writeBytes(entry: FATEntry, buffer: ByteArray, bufferOffset: Int, writeLength: Int, writeStartOffset: Int, fileType: Int) {
        val addedBytes = writeStartOffset + writeLength - getFileLength(entry)

        checkDiskCapacity(addedBytes)

        if (entry.isInline) return writeBytesInline(entry, buffer, bufferOffset, writeLength, writeStartOffset, fileType)

        var writeCursor = writeStartOffset
        var remaining = writeLength

        var ptr = entry.entryID
        file.seekToCluster(ptr)
        dbgprintln("[Clustered]   file.seekToCluster $ptr")
        var meta1 = file.read()
        var meta2 = file.read()
        var prev = file.readInt24()
        var nextPtr = file.readInt24()
        var contentsSizeInThisCluster = file.readUshortBig()

        var firstClusterOfWriting = true

        var cursorInClusterFileArea = writeStartOffset


        while (writeCursor < writeLength + writeStartOffset) {
            dbgprintln("[Clustered] writeCursor = $writeCursor < ${writeLength + writeStartOffset}")


            // seek to next cluster
            // if cursorInCluster is larger than FILE_BLOCK_CONTENTS_SIZE, this operation will loop until the file cursor is on the right cluster
            while (cursorInClusterFileArea >= FILE_BLOCK_CONTENTS_SIZE) {

                dbgprintln("[Clustered]   cursorInClusterFileArea = $cursorInClusterFileArea")


                // if next cluster is NULL,,,
                if (nextPtr == LEAF_CLUSTER) {
                    // allocate new cluster and then modify the nextPtr on the Archive
                    val sizeDelta = if (addedBytes > 0) addedBytes - writeLength + remaining else remaining
                    dbgprintln("[Clustered]   expanding file (sizeDelta=$sizeDelta, prevCluster=$prev, currentCluster=$ptr, fileType=$fileType)")
                    nextPtr = expandFile(sizeDelta, prev, ptr, fileType)

                    firstClusterOfWriting = false
                }
                ptr = nextPtr
                file.seekToCluster(ptr)
                dbgprintln("[Clustered]   file.seekToCluster $ptr")
                meta1 = file.read()
                meta2 = file.read()
                prev = file.readInt24()
                nextPtr = file.readInt24()
                contentsSizeInThisCluster = file.readUshortBig()

                cursorInClusterFileArea -= FILE_BLOCK_CONTENTS_SIZE
            }


            // mark the cluster as "dirty"
            file.seekToCluster(ptr)
            dbgprintln("[Clustered] file.seekToCluster (to mark dirty) $ptr")
            file.write(meta1.and(0xEF) or 16)
            file.write(meta2)


            // at the end of the skip-run, position the file to the right place
            file.seekToCluster(ptr, FILE_BLOCK_HEADER_SIZE + cursorInClusterFileArea)

            // cursor is moved to the right place, do the writing
            val writeLengthOnThisCluster =
                    listOf(remaining,
                            FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea,
                            FILE_BLOCK_CONTENTS_SIZE - contentsSizeInThisCluster
                    ).filter { it > 0 }.minOf { it }.toInt()


            dbgprintln("[Clustered] writeLengthOnThisCluster = $writeLengthOnThisCluster (minOf $remaining, ${FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea}, ${FILE_BLOCK_CONTENTS_SIZE - contentsSizeInThisCluster})")

            // actually write
            file.write(buffer, bufferOffset + writeCursor - writeStartOffset, writeLengthOnThisCluster)
            remaining -= writeLengthOnThisCluster
            writeCursor += writeLengthOnThisCluster
            cursorInClusterFileArea += writeLengthOnThisCluster
            // update the 'number of bytes in this cluster' number on the file
            file.seekToCluster(ptr, FILE_BLOCK_OFFSET_CONTENT_LEN)
            val thisClusterContentsSizeBefore = file.readUshortBig()
            val thisClusterContentsSizeAfter = if (firstClusterOfWriting)
                                        writeLengthOnThisCluster + writeStartOffset
                                    else
                                        minOf(FILE_BLOCK_CONTENTS_SIZE, remaining)

            dbgprintln("[Clustered] size before: $thisClusterContentsSizeBefore, after: $thisClusterContentsSizeAfter")

            if (thisClusterContentsSizeBefore < thisClusterContentsSizeAfter) {
                file.seekToCluster(ptr, FILE_BLOCK_OFFSET_CONTENT_LEN)
                file.writeInt16(thisClusterContentsSizeAfter)
                dbgprintln("[Clustered] writeBytes -- writing contentSize $thisClusterContentsSizeAfter to cluster ${ptr.toHex()}")
            }
            firstClusterOfWriting = false


            // unset the dirty flag
            file.seekToCluster(ptr)
            file.write(meta1 and 0xEF)

            // set modification date on the FAT
            val timeNow = getTimeNow()
            entry.modificationDate = timeNow
            fatmgrUpdateModificationDate(entry, timeNow)
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
        var contentsSizeInThisCluster = file.readUshortBig()

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
                contentsSizeInThisCluster = file.readUshortBig()

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

        var parent = HEAD_CLUSTER
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
                nextCluster = expandFile(remaining, parent, cluster, fileType)
            }

            parent = cluster
            cluster = nextCluster
        } while (remaining > 0)
    }

    /**
     * @param start cluster number to start traverse
     * @param action what to do. Argument: Int Current cluster number
     */
    private fun traverseClusters(start: Int, action: (Int) -> Unit) {
        var cluster = start

        do {
            // seek to cluster
            file.seekToCluster(cluster, 5)
            // get next cluster
            val nextCluster = file.readInt24()

            if (cluster == nextCluster) throw VDIOException("Loop to self detected -- prev cluster: $cluster, next cluster: $nextCluster")

            action(cluster)

            cluster = nextCluster
        } while (cluster in 1 until LEAF_CLUSTER)
    }

    fun getFileLength(entry: FATEntry): Int {
        var accumulator = 0

        if (entry.entryID in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) {
            accumulator += entry.getInlinedLength()
        }
        else {
            traverseClusters(entry.entryID) {
                // get length for this cluster
                val len = file.readUshortBig()
                // add to accumulator
                accumulator += len
            }
        }

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

    fun readBoot(): ByteArray {
        file.seek(CLUSTER_SIZE.toLong())
        return file.read(CLUSTER_SIZE)
    }

    fun writeBoot(code: ByteArray) {
        file.seek(CLUSTER_SIZE.toLong())
        file.write(code, 0, minOf(code.size, CLUSTER_SIZE))
    }








    private fun RandomAccessFile.readBytes(buffer: ByteArray): Int {
        val readStatus = file.read(buffer)
        return readStatus
    }
    private fun RandomAccessFile.readUshortBig(): Int {
        val buffer = ByteArray(2)
        val readStatus = readBytes(buffer)
        if (readStatus != 2) throw InternalError("Unexpected error -- EOF reached? (expected 2, got $readStatus)")
        return buffer.toInt16()
    }
    private fun RandomAccessFile.readIntBig(): Int {
        val buffer = ByteArray(4)
        val readStatus = readBytes(buffer)
        if (readStatus != 4) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
        return buffer.toInt32()
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
    private fun RandomAccessFile.seekToFAT(index: Int, offset: Int = 0) {
        this.seek(2L * CLUSTER_SIZE + index * FAT_ENTRY_SIZE + offset)
    }
    private fun RandomAccessFile.writeInt16(value: Int) {

        if (value == 4098) {
            dbgprintln("[WriteInt16] writing 4098")
            val indentation = " ".repeat("[WriteInt16]".length + 4)
            Thread.currentThread().stackTrace.forEachIndexed { index, it ->
                if (index == 1)
                    dbgprintln("[[WriteInt16]]> $it")
                else if (index > 1)
                    dbgprintln("$indentation$it")
            }
        }

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
    private fun RandomAccessFile.writeInt48(value: Long) {
        this.write(value.ushr(40).toInt())
        this.write(value.ushr(32).toInt())
        this.write(value.ushr(24).toInt())
        this.write(value.ushr(16).toInt())
        this.write(value.ushr( 8).toInt())
        this.write(value.ushr( 0).toInt())
    }
    private fun RandomAccessFile.writeInt64(value: Long) {
        this.write(value.ushr(56).toInt())
        this.write(value.ushr(48).toInt())
        this.write(value.ushr(40).toInt())
        this.write(value.ushr(32).toInt())
        this.write(value.ushr(24).toInt())
        this.write(value.ushr(16).toInt())
        this.write(value.ushr( 8).toInt())
        this.write(value.ushr( 0).toInt())
    }
    private fun ByteArray.trimNull(): ByteArray {
        var cnt = this.size - 1
        while (cnt >= 0) {
            if (this[cnt] != 0.toByte()) break
            cnt -= 1
        }
        return this.sliceArray(0..cnt)
    }
    private fun Long.toHex() = this.and(0xFFFFFFFF).toString(16).padStart(8, '0').toUpperCase().let {
        it.substring(0..4).toInt(16).toString(16).toUpperCase().padStart(3, '0') + ":" + it.substring(5..7)
    }
    private fun Int.toHex() = this.toLong().toHex()
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
internal fun ByteArray.toInt32(offset: Int = 0): Int {
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
internal fun ByteArray.writeInt16(value: Int, offset: Int) {
    this[offset+0] = value.ushr(8).toByte()
    this[offset+1] = value.ushr(0).toByte()
}
internal fun ByteArray.writeInt24(value: Int, offset: Int) {
    this[offset+0] = value.ushr(16).toByte()
    this[offset+1] = value.ushr(8).toByte()
    this[offset+2] = value.ushr(0).toByte()
}
internal fun ByteArray.writeInt32(value: Int, offset: Int) {
    this[offset+0] = value.ushr(24).toByte()
    this[offset+1] = value.ushr(16).toByte()
    this[offset+2] = value.ushr(8).toByte()
    this[offset+3] = value.ushr(0).toByte()
}
internal fun ByteArray.writeInt48(value: Long, offset: Int) {
    this[offset+0] = value.ushr(40).toByte()
    this[offset+1] = value.ushr(32).toByte()
    this[offset+2] = value.ushr(24).toByte()
    this[offset+3] = value.ushr(16).toByte()
    this[offset+4] = value.ushr(8).toByte()
    this[offset+5] = value.ushr(0).toByte()
}
internal fun ByteArray.writeInt64(value: Long, offset: Int) {
    this[offset+0] = value.ushr(56).toByte()
    this[offset+1] = value.ushr(48).toByte()
    this[offset+2] = value.ushr(40).toByte()
    this[offset+3] = value.ushr(32).toByte()
    this[offset+4] = value.ushr(24).toByte()
    this[offset+5] = value.ushr(16).toByte()
    this[offset+6] = value.ushr(8).toByte()
    this[offset+7] = value.ushr(0).toByte()
}
