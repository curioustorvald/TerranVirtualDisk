package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.*
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskSkimmer.Companion.read
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.ATTRIBS_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VirtualDisk.Companion.NAME_LENGTH
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.CLUSTER_SIZE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FAT_ENTRY_SIZE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.INLINE_FILE_CLUSTER_BASE
import java.io.*
import java.nio.charset.Charset
import java.util.*
import kotlin.Comparator
import kotlin.collections.HashMap
import kotlin.experimental.and
import kotlin.math.ceil

/**
 * Created by minjaesong on 2023-03-31.
 */
class ClusteredFormatArchiver(val dom: ClusteredFormatDOM?) : Archiver() {

    override val specversion = ClusteredFormatArchiver.specversion

    override fun serialize(outFile: File) {
        TODO("Not yet implemented")
    }

    override fun serializeToBA64(): ByteArray64 {
        val buffer = ByteArray64(dom!!.ARCHIVE.length())
        dom.ARCHIVE.seek(0)
        for (k in 0 until dom.ARCHIVE.length() step ClusteredFormatDOM.CLUSTER_SIZE.toLong()) {
            buffer.appendBytes(dom.ARCHIVE.read(CLUSTER_SIZE))
        }
        return buffer
    }

    /**
     * @param file the archive
     * @param charset does nothing; charset info is stored in the archive itself
     */
    override fun deserialize(file: File, charset: Charset?): ClusteredFormatDOM {
        return ClusteredFormatDOM(RandomAccessFile(file, "rw"))
    }

    companion object {
        const val specversion = 0x11.toByte()
    }
}

private fun Int.isValidCluster() = this in 0x000004 until INLINE_FILE_CLUSTER_BASE

private fun Int.incClusterNum(increment: Int) = if (this.isValidCluster()) this + increment else this

private fun ByteArray.renumFAT(increment: Int): ByteArray {
    if (this.size != FAT_ENTRY_SIZE) throw IllegalStateException()

    val entryID = this.toInt24().incClusterNum(increment)
    // increment parent ID
    this.writeInt24(entryID, 0)

    // increment parent IDs on the Extended Entries
    // inline directories
    if (entryID == 0xFFFF12) {
        for (offset in 8 until FAT_ENTRY_SIZE - 3 step 3) {
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
    if (filetype == 2) {
        val entrySize = this.toInt16(8)
        for (entryOffset in 10 until 10 + 3*entrySize step 3) {
            val clusterNum = this.toInt24(entryOffset).incClusterNum(increment).toInt24Arr()
            System.arraycopy(clusterNum, 0, this, entryOffset, 3)
        }
    }

    return this
}

class ClusteredFormatDOM(internal val ARCHIVE: RandomAccessFile, val throwErrorOnReadError: Boolean = false) {

    private inline fun testPause(msg: Any?) {
//        dbgprintln("\n\n== $msg ==\n\n"); dbgprint("> "); Scanner(System.`in`).nextLine()
    }

    private inline fun dbgprint(msg: Any? = "") {
//        print(msg)
    }

    private inline fun dbgprintln(msg: Any? = "") {
        println(msg)
    }

    private inline fun dbgprintln2(msg: Any? = "") {
//        println(msg)
    }

    companion object {

        const val FILETYPE_BINARY = 1
        const val FILETYPE_DIRECTORY = 2

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
        const val FILENAME_PRIMARY_LENGTH = 220

        const val INLINING_THRESHOLD = INLINED_ENTRY_BYTES * 8 // compare with <= -- files up to this size is recommended to be inlined

        fun createNewArchive(outPath: File, charset: Charset, diskName: String, capacityInSectors: Int): RandomAccessFile {
            val timeNow = (System.currentTimeMillis() / 1000L).toInt48Arr()
            val file = FileOutputStream(outPath)

            val charsetIndex = when (charset.name().toUpperCase()) {
                "UTF-8" -> 0x1000
                "UTF-16BE" -> 0x1001
                "UTF-16LE" -> 0x1002
                "ISO-8859-1" -> 0x0100
                "ISO-8859-2" -> 0x0101
                "ISO-8859-3" -> 0x0102
                "ISO-8859-4" -> 0x0103
                "ISO-8859-5" -> 0x0104
                "ISO-8859-6" -> 0x0105
                "ISO-8859-7" -> 0x0106
                "ISO-8859-8" -> 0x0107
                "ISO-8859-9" -> 0x0108
                "ISO-8859-10" -> 0x0109
                "ISO-8859-11" -> 0x010A
                "ISO-8859-12" -> 0x010B
                "ISO-8859-13" -> 0x010C
                "ISO-8859-14" -> 0x010D
                "ISO-8859-15" -> 0x010E
                "ISO-8859-16" -> 0x010F
                else -> 0 // IBM437
            }

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
            // Charset (2)
            file.write(charsetIndex ushr 8)
            file.write(charsetIndex and 255)
            // random UUID
            UUID.randomUUID().let {
                file.write(it.mostSignificantBits.toInt64Arr())
                file.write(it.leastSignificantBits.toInt64Arr())
            }
            // cluster filler
            file.write(ByteArray(4010))

            //// CLUSTER 1 ////
            // dummy bootsector
            file.write(EMPTY_CLUSTER)

            //// CLUSTER 2-3 ////
            // FAT for the root
            file.write(byteArrayOf(0, 0, 4, (FILETYPE_DIRECTORY.shl(4) or 4).toByte()))
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
            it[0] = FILETYPE_DIRECTORY.toByte()
            // meta2 (persistent:true)
            it[1] = 0x80.toByte()
            // prev ptr
            it.writeInt24(HEAD_CLUSTER, 2)
            // next ptr
            it.writeInt24(LEAF_CLUSTER, 5)
        }


    }

    /**
     * if given filename and filename stored in the extendedEntries contradicts, 'long filename' entries in
     * extendedEntries will be replaced
     */
    class FATEntry(
            val charset: Charset,
            private val renumberHook: () -> Unit,

            /** if ID is 1, the file is 0-byte file */
            entryID: EntryID,

            var readOnly: Boolean,
            var hidden: Boolean,
            var system: Boolean,
            var deleted: Boolean,

            var fileType: Int,

            var creationDate: Long = -1L,
            var modificationDate: Long = -1L,
            var filename: String = "",

            val extendedEntries: MutableList<ByteArray> = MutableList<ByteArray>(0) { ByteArray(0) }
    ) : Comparable<FATEntry> {
        companion object {
            /**
             * @param charset Charset
             * @param fat256 The entire 256 byte of the FAT entry
             * @return Pair of Clusternum to [FATEntry]
             */
            fun fromBytes(charset: Charset, renumberHook: () -> Unit, fat256: ByteArray) = if (fat256.size != FAT_ENTRY_SIZE)
                throw IllegalArgumentException("FAT not $FAT_ENTRY_SIZE bytes long (${fat256.size})")
            else
                FATEntry(
                    charset,
                    renumberHook,
                    fat256.toInt24(),
                    fat256[3].and(1.toByte()) != 0.toByte(),
                    fat256[3].and(2.toByte()) != 0.toByte(),
                    fat256[3].and(4.toByte()) != 0.toByte(),
                    fat256[3].and(8.toByte()) != 0.toByte(),

                    fat256[3].toUint().ushr(4).and(15),

                    fat256.toInt48(4),
                    fat256.toInt48(10),
                    fat256.sliceArray(16..255).toCanonicalString(charset)
                )
        }

        private var theRealEntryID: EntryID = entryID
        var entryID: EntryID
            get() = theRealEntryID
            set(value) {
                theRealEntryID = value
                renumberHook()
            }

        override fun compareTo(other: FATEntry): Int {
            return compareValues(this.entryID, other.entryID)
        }

        init {
            if (fileType == 0) throw Error()

            updateFilename(filename)
        }

        fun updateFilename(fullFilename: String) {
            // put long filename into the extendedEntries
            val fnameBytes = fullFilename.toByteArray(charset)
            if (fnameBytes.size > FILENAME_PRIMARY_LENGTH) {
                // clear out existing extended entries first
                extendedEntries.removeIf { it.toInt24() == 0xFFFF11 }

                extendedEntries.addAll(fnameBytes.sliceArray(FILENAME_PRIMARY_LENGTH until minOf(fnameBytes.size, 248 * 256 + FILENAME_PRIMARY_LENGTH))
                        .chunked(248).mapIndexed { index, bytes ->
                            val ba = ByteArray(256)
                            ba.writeInt24(0xFFFF11, 0)
                            ba.writeInt24(entryID, 3)
                            ba[6] = index.toByte()
                            ba[7] = bytes.size.toByte()
                            System.arraycopy(bytes, 0, ba, 8, bytes.size)
                            ba
                        })
            }
        }

        private fun Long.toHex() = this.and(0xFFFFFFFF).toString(16).padStart(8, '0').toUpperCase().let {
            it.substring(0..4).toInt(16).toString(16).toUpperCase().padStart(3, '0') + ":" + it.substring(5..7)
        }
        private fun Int.toHex() = this.toLong().toHex()

        override fun toString(): String {
            return "FATEntry(ID: ${entryID.toHex()}; filename=\"$filename\", #ExtEntries=${extendedEntries.size}; filetype=$fileType, readOnly=$readOnly, hidden=$hidden, system=$system, deleted=$deleted)"
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
                    deleted.toInt(4) or
                    fileType.shl(4)
            val cd = creationDate.toInt48Arr()
            val md = modificationDate.toInt48Arr()
            val name = filename.toEntryName(FILENAME_PRIMARY_LENGTH, charset)

            ba[3] = flags.toByte()
            System.arraycopy(cd, 0, ba, 4, 6)
            System.arraycopy(md, 0, ba, 10, 6)
            System.arraycopy(name, 0, ba, 16, FILENAME_PRIMARY_LENGTH)

            return listOf(ba) + extendedEntries
        }

        /**
         * Called by ClusteredFormatDOM, this function assists the global renum operation.
         */
        internal fun _fatRenum(increment: Int) {
            theRealEntryID = this.entryID.incClusterNum(increment)
            this.extendedEntries.forEach {
                it.renumFAT(increment)
            }
        }

        val isInline: Boolean
            get() = entryID in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST

        fun takeAttribsFrom(other: FATEntry) {
            this.readOnly = other.readOnly
            this.hidden = other.hidden
            this.system = other.system
            this.deleted = other.deleted
            this.creationDate = other.creationDate
            this.modificationDate = other.modificationDate
        }

        fun validate() {
            val inlineType = 0xFFFF10 or fileType
            val illegalFiletypes = (0xFFFF10..0xFFFF1F).minus(inlineType)
            (extendedEntries.map { it.toInt24() } intersect illegalFiletypes).let {
                if (it.isNotEmpty())
                    throw IllegalStateException("Contradictory Inlining Extended Entries (expected: only ${inlineType.toHex()}, got: ${it.sorted().joinToString { it.toHex() }})")
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



    // START OF THE ClusteredFormatDOM IMPL



    private val freeClusters = HashSet<EntryID>()

    val reclaimableClusters; get() = freeClusters.toTypedArray()

    private val fatEntryIndices = HashMap<EntryID, Int>() // EntryID, FATIndex
    private var fatEntryHighest = -1 to -1 // FATIndex, EntryID

    private fun getTimeNow() = System.currentTimeMillis() / 1000

    /**
     * @return ID between 0xF00000 and 0xFFFDFF if the reserved area is not full; normal cluster ID otherwise
     */
    fun getNextFreeInlineCluster(): Int {
        var i = INLINE_FILE_CLUSTER_BASE
        while (i <= INLINE_FILE_CLUSTER_LAST) {
            if (!fileTable.contains(i)) return i
            i += 1
        }
        return archiveSizeInClusters
    }

    val charset: Charset
    val uuid: UUID

    internal val fileTable = FileTableMap() // path is unknown until the path tree is traversed
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

    var isReadOnly: Boolean
        get() = primaryAttribs.and(1) != 0
        set(value) {
            primaryAttribs = (primaryAttribs and 0xFE) or value.toInt()
            ARCHIVE.seek(47L); ARCHIVE.write(primaryAttribs)
        }

    /*private var isFixedSize: Boolean
        get() = primaryAttribs.and(0x10) != 0
        set(value) {
            primaryAttribs = (primaryAttribs and 0xEF) or value.toInt(4)
            ARCHIVE.seek(47L); ARCHIVE.write(primaryAttribs)
        }*/

    fun renameDisk(str: String) {
        val ba = str.toByteArray(charset)
        for (k in 0 until NAME_LENGTH) {
            ba[k] = if (k < ba.size) diskName[k] else 0
        }
        diskNameString = ba.toCanonicalString(charset)
    }

    fun changeDiskCapacity(clusterCount: Int) {
        diskSize = clusterCount * 4L
        ARCHIVE.seek(4L)
        ARCHIVE.writeInt48(diskSize)
    }

    var diskNameString: String = ""
        private set

    private fun formatMatches(): Boolean {
        val magic = ByteArray(4)
        ARCHIVE.seek(0L)
        ARCHIVE.read(magic, 0, 4)
        ARCHIVE.seek(46L)
        val ver = ARCHIVE.read().toByte()

        return (magic.contentEquals(VirtualDisk.MAGIC) && ver == ClusteredFormatArchiver.specversion)
    }

    fun dispose() {
        ARCHIVE.close()
    }

    private fun toCharset(charsetCode: Int) = when (charsetCode) {
        in 0x0100..0x010F -> Charset.forName("iso-8859-${charsetCode.and(15) + 1}")
        0x1000 -> Charset.forName("utf-8")
        0x1001 -> Charset.forName("utf-16be")
        0x1002 -> Charset.forName("utf-16le")
        else -> Charset.forName("iso-8859-1") // supposed to be ibm437 but using this due to a bug? where ibm437 would not pass high bytes (0x80..0xFF) as-is
    }

    init {
        if (!formatMatches()) throw RuntimeException("Invalid Virtual Disk file!")

        // set charset first
        ARCHIVE.seek(68L)
        charset = toCharset(ARCHIVE.readUshortBig())

        ARCHIVE.seek(70L)
        uuid = UUID(ARCHIVE.readInt64(), ARCHIVE.readInt64())

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

    val archiveSizeInClusters; get() = (ARCHIVE.length() / CLUSTER_SIZE).toInt()

    private fun tallyClusterCount() {
        if (fatClusterCount < 0) throw InternalError("Uninitialised -- call readMeta() and readFAT() first")

        // trim if possible
        trimArchive()

        dbgprintln("[Clustered] archiveSizeInClusters: $archiveSizeInClusters")
    }

    private fun readMeta() {
        ARCHIVE.seek(4L)
        diskSize = ARCHIVE.readInt48()
        ARCHIVE.readBytes(diskName); diskNameString = diskName.toCanonicalString(charset)
        ARCHIVE.seek(47L)
        primaryAttribs = ARCHIVE.read()
        ARCHIVE.readBytes(userAttribs)
        fatClusterCount = ARCHIVE.readIntBig()
    }

    private fun readFAT() {
        if (fatClusterCount < 0) throw InternalError("Disk has not been read")
        if (fatClusterCount == 0) throw RuntimeException("Invalid FAT size ($fatClusterCount)")

        dbgprintln("[Clustered.readFAT] ============ readFAT ============")

        ARCHIVE.seek(2L * CLUSTER_SIZE)
        for (block in 2 until 2 + fatClusterCount) {
            for (blockOff in 0 until CLUSTER_SIZE step FAT_ENTRY_SIZE) {

                val fat = ARCHIVE.read(FAT_ENTRY_SIZE)

                val mainPtr = fat.toInt24()

//                dbgprintln("[Clustered] FAT ptr: $mainPtr")

                // Extended Entries
                if (mainPtr >= EXTENDED_ENTRIES_BASE) {
                    val parentPtr = fat.toInt24(3)
                    fileTable[parentPtr]?.extendedEntries?.add(fat) ?: notifyError(IllegalStateException("Extended Entry 0x${parentPtr.toHex().drop(2)} is reached but no main FAT entry (ID $parentPtr) was found"))
                    dbgprintln("[Clustered.readFAT]     Extended Entry ${mainPtr.toHex()}")
                }
                // normal entries
                else if (mainPtr >= 2 + fatClusterCount) {
                    val fat = FATEntry.fromBytes(charset, fileTable.renumberHook, fat)
                    fileTable.put(fat)
                    dbgprintln("[Clustered.readFAT] ID ${fat.entryID.toHex()} = ${fat.filename}")
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


        fileTable.forEach { it.validate() }

//        dbgprintln("fatEntryHighest = $fatEntryHighest")

        dbgprintln("[Clustered.readFAT] =================================")
    }

    private fun checkDiskCapacity(bytesToAdd: Int) = checkDiskCapacity(bytesToAdd.toLong())
    private fun checkDiskCapacity(bytesToAdd: Long) {
        if (bytesToAdd <= 0) return
        val usedBytes = ARCHIVE.length() + bytesToAdd
        if (usedBytes > diskSize) throw VDIOException("Not enough space on the disk")
    }

    /**
     * Shifts the cluster number of every entry to given number, then actually inserts new clusters to the FAT area.
     * @param increment number of clusters to be inserted
     */
    fun renum(increment: Int) {
        freeClusters.clear()

        val oldUsedClusterCount = archiveSizeInClusters

        expandArchive(increment)

        // renumber my FAT
        fileTable.forEach {
            val oldID = it.entryID
            it._fatRenum(increment)

            dbgprintln("[Clustered] renum($increment) -- ${oldID.toHex()} -> ${it.entryID.toHex()}")
        }
        fileTable.renumberHook()

        // copy over clusters, renumber any applicable cluster numbers before copying
        for (clusternum in oldUsedClusterCount - 1L downTo 2 + fatClusterCount) {
//            dbgprintln("[Clustered] renum($increment) -- moving cluster ${clusternum.toHex()} to ${(clusternum + increment).toHex()}")

            ARCHIVE.seekToCluster(clusternum)


            // do the actual renumbering on the cluster contents
            val clusterContents = ARCHIVE.read(CLUSTER_SIZE)
            clusterContents.renumCluster(increment)


            // write modified cluster to the new (forward) position of the Archive
            ARCHIVE.seekToCluster(clusternum + increment)
            ARCHIVE.write(clusterContents)
        }

        fatClusterCount += increment

        // renumber FAT on the Archive
//        dbgprintln("[Clustered] renum($increment) -- about to renum FATs (fatClusterCount = $fatClusterCount, FATs: $fatEntryCount/${fatClusterCount * FATS_PER_CLUSTER})")
        fatEntryIndices.clear()
        for (kfat in 0 until fatEntryCount) {
            ARCHIVE.seekToFAT(kfat)
            val fat = ARCHIVE.read(FAT_ENTRY_SIZE).renumFAT(increment)
            ARCHIVE.seekToFAT(kfat)
            ARCHIVE.write(fat)

            val entryID = fat.toInt24(0)
            if (entryID < EXTENDED_ENTRIES_BASE) {
                fatEntryIndices[entryID] = kfat
            }
        }
        for (qfat in fatEntryCount until fatClusterCount * FATS_PER_CLUSTER) {
            ARCHIVE.seekToFAT(qfat)
            ARCHIVE.write(EMPTY_FAT_ENTRY)
        }
        // fatmgrAddEntry() may break write order -- renumber the FATs manually as above


        // write new metavalues
        ARCHIVE.seek(64L)
        ARCHIVE.writeInt32(fatClusterCount)

    }

    private fun fatmgrUpdateModificationDate(entry: FATEntry, time: Long) {
//        dbgprintln("[Clustered] fatmgrUpdateModificationDate on ${entry.entryID}")

//        dbgprintln("[Clustered] fatEntryIndices:")
//        fatEntryIndices.forEach { id, fatIndex ->
//            dbgprintln("                entryID ${id.toHex()} -> fatIndex ${(2 * CLUSTER_SIZE + fatIndex * FAT_ENTRY_SIZE).toHex()}")
//        }

        entry.modificationDate = time
        ARCHIVE.seekToFAT(fatEntryIndices[entry.entryID]!!, 10)
        ARCHIVE.writeInt48(time)
    }

    /**
     * Creates a new FAT entry, adds it to the `fileTable` and the Archive via [fatmgrAddEntry], then returns the newly-created entry.
     */
    private fun fatmgrCreateNewEntry(
            entryID: EntryID, filename: String,
            readOnly: Boolean, hidden: Boolean, system: Boolean, deleted: Boolean,
            creationTime: Long, modificationTime: Long,
            fileType: Int
    ): FATEntry {
        FATEntry(charset, fileTable.renumberHook, entryID, readOnly, hidden, system, deleted, fileType, creationTime, modificationTime).let {
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

        fileTable.put(entry)
        // `entry` is contained in the fileTable and thus should have been re-numbered by the spliceFAT
        fatEntryHighest = nextIndex to entry.entryID
        fatEntryIndices[entry.entryID] = nextIndex

//        dbgprintln("[Clustered] fatmgrAddEntry -- entryID: ${entry.entryID.toHex()}, FAT index: ${fatEntryIndices[entry.entryID]}")
    }

    private fun fatmgrAllocateInlineFile(entry: FATEntry, size: Long) {
        val myEntryIndex = fatEntryIndices[entry.entryID]
                ?: throw IllegalArgumentException("No such file with following ID: ${entry.entryID}")
        val oldFATentrySize = entry.extendedEntries.size + 1

        if (entry.fileType == 0) throw UnsupportedOperationException("FAT has no file type set (${entry.fileType})")
        if (!entry.isInline) throw IllegalArgumentException("File is not inline (ID: ${entry.entryID})")
        if (entry.hasExtraEntryWithType { it xor 0xFFFF10 < 16 }) throw IllegalArgumentException("File is already allocated and is inline (ID: ${entry.entryID})")

        val entrySizeDelta = ceil(size.toDouble() / INLINED_ENTRY_BYTES).toInt().coerceAtLeast(1)

        // create bytearrays to append to
        val newBytes = List(entrySizeDelta) { ByteArray(FAT_ENTRY_SIZE) }
        val newBytesStart = entry.extendedEntries.size
        var allocBytesRemaining = size
        newBytes.forEachIndexed { index, bytes ->
            // write Extra Entry Header
            bytes.writeInt24(0xFFFF10 or entry.fileType, 0)
            bytes.writeInt24(entry.entryID, 3)
            bytes[6] = index.toByte()
            bytes[7] = minOf(INLINED_ENTRY_BYTES.toLong(), allocBytesRemaining).toByte()

            allocBytesRemaining -= INLINED_ENTRY_BYTES
        }

        // put the bytearrays to the entry in the memory
        entry.extendedEntries.addAll(newBytes)

        // flag filetype
        entry.fileType = entry.fileType

        // shift FAT entries up
        fatEntryIndices.entries.filter { it.value > myEntryIndex }.forEach { (entryID, fatIndex) ->
            fatEntryIndices[entryID] = fatIndex + entrySizeDelta
        }


        dbgprintln("[Clustered] fatmgrAllocateInlineFile1 -- entryID: ${entry.entryID.toHex()}, FAT index: ${fatEntryIndices[entry.entryID]}, myEntryIndex = $myEntryIndex")

        // overwrite the existing FAT created by allocateFile -> fatmgrAddEntry
        spliceFAT(myEntryIndex, oldFATentrySize, entry.toBytes())


        dbgprintln("[Clustered] fatmgrAllocateInlineFile2 -- entryID: ${entry.entryID.toHex()}, FAT index: ${fatEntryIndices[entry.entryID]}")
    }

    /**
     * Unlike [writeBytes], this function will change the length of the file -- all the previous bytes will be replaced!
     */
    private fun fatmgrSetInlineBytes(entry: FATEntry, infileBytes: ByteArray) {
        val myEntryIndex = fatEntryIndices[entry.entryID]
                ?: throw IllegalArgumentException("No such file with following ID: ${entry.entryID.toHex()}")

        if (entry.fileType == 0) throw UnsupportedOperationException("FAT has no file type set (${entry.fileType})")
        if (!entry.isInline) throw IllegalArgumentException("File is not inline (ID: ${entry.entryID.toHex()})")

        val oldExtendedEntryCount = entry.extendedEntries.size

        val newBytes = List(ceil(infileBytes.size.toDouble() / INLINED_ENTRY_BYTES).toInt()) { ByteArray(FAT_ENTRY_SIZE) }

        // write bytes to the newBytes
        var remaining = infileBytes.size
        var written = 0
        newBytes.forEachIndexed { index, bytes ->
            val writeSize = minOf(INLINED_ENTRY_BYTES, remaining)

            // write Extra Entry Header
            bytes.writeInt24(0xFFFF10 or entry.fileType, 0)
            bytes.writeInt24(entry.entryID, 3)
            bytes[6] = index.toByte()
            bytes[7] = writeSize.toByte()
            System.arraycopy(infileBytes, written, bytes, 8, writeSize)

            remaining -= INLINED_ENTRY_BYTES
            written += INLINED_ENTRY_BYTES
        }


        // remove any inline bytes of matching type from the entry
        entry.extendedEntries.removeIf { it.toInt24(0) == 0xFFFF10 or entry.fileType }
        // then append newBytes
        entry.extendedEntries.addAll(newBytes)

        // splice up the Archive
        spliceFAT(myEntryIndex + 1, oldExtendedEntryCount, entry.extendedEntries)
    }

    private fun tallyFATentryCount(): Int {
        return fileTable.sumBy { it.extendedEntries.size + 1 }
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
    private fun fatmgrRewriteAllFAT() {
        fatEntryHighest = 2 to 0
        fatEntryIndices.clear()
        fatEntryCount = 0


        // expand FAT area if required
        val newSize = tallyFATentryCount()
        val currentCap = fatClusterCount * FATS_PER_CLUSTER

        if (newSize > currentCap) {
            dbgprintln("[Clustered.fatmgrRewriteFAT] growing FAT area...")
            growFAT() // alters fileTable
        }



        // try to rewrite every entry on the FAT
        var fatWritePos = 0
        ARCHIVE.seekToFAT(0)
        fileTable.forEach { fat ->
            // update fatEntryIndices
            fatEntryIndices[fat.entryID] = fatWritePos

            // update fatEntryHighest
            if (fatWritePos > fatEntryHighest.first) {
                fatEntryHighest = fatWritePos to fat.entryID
            }

            // write bytes to the disk
            // single FileAttrib may have multiple FATs associated
            fat.toBytes().let { newFATbytes ->
                newFATbytes.forEach {
                    ARCHIVE.write(it)
                    fatWritePos += 1
                    fatEntryCount += 1
                }
            }
        }

        for (qfat in fatEntryCount until fatClusterCount * FATS_PER_CLUSTER) {
            ARCHIVE.seekToFAT(qfat)
            ARCHIVE.write(EMPTY_FAT_ENTRY)
        }

    }

    private fun fatmgrSyncInlinedBytes(entryID: EntryID) {
        dbgprintln("[Clustered.fatmgrSyncInlinedBytes] entryID=${entryID.toHex()}")

        val fat = fileTable[entryID]!!
        val foff = fatEntryIndices[entryID]!!
        ARCHIVE.seekToFAT(foff)
        // sanity check
        val checkID = ARCHIVE.readInt24()
        if (checkID != entryID) {
            throw InternalError("entryID ${entryID.toHex()} is supposed to be found on FAT offset ${foff.toHex()}, but found ${checkID.toHex()} instead")
        }

        var c = 1
        val newExtendedEntries = mutableListOf<ByteArray>()
        while (true) {
            ARCHIVE.seekToFAT(foff + c)
            val eent = ARCHIVE.read(FAT_ENTRY_SIZE)
            if (eent.toInt16() != 0xFFFF) break
            newExtendedEntries.add(eent)
            dbgprintln("[Clustered.fatmgrSyncInlinedBytes] Found ExtEnt for ${entryID.toHex()}: [${eent.chunked(3).map { it.toInt24() }.joinToString { it.toHex() }}]")
            c += 1
        }

        fat.extendedEntries.clear()
        fat.extendedEntries.addAll(newExtendedEntries)
    }

    private fun fatmgrDeleteEntry(fat: FATEntry) {
        val index = fatEntryIndices[fat.entryID]!!
        spliceFAT(index, fat.extendedEntries.size + 1, emptyList())
    }

    fun commitFATchangeToDisk(fat: FATEntry) {
        dbgprintln("[Clustered] commitFATchangeToDisk; FAT: $fat")

        var oldFATentryCount = 1
        val insertionPos = fatEntryIndices[fat.entryID]!!
        ARCHIVE.seekToFAT(insertionPos + 1)
        // tally oldFATentryCount
        while (true) {
            val id = ARCHIVE.readInt24()
            if (id >= EXTENDED_ENTRIES_BASE)
                oldFATentryCount += 1
            else
                break
            ARCHIVE.skipBytes(FAT_ENTRY_SIZE - 3)
        }

        spliceFAT(insertionPos, oldFATentryCount, fat.toBytes())
    }

    fun getFile(clusterNum: Int): FATEntry? {
        return fileTable[clusterNum]
    }

    fun getRootDir(): FATEntry {
        return fileTable[fatClusterCount + 2] ?: throw NullPointerException("ID ${(fatClusterCount + 2).toHex()} is not a root directory")
    }

    /**
     * Mark the cluster and the FAT entry as to-be-deleted. Cluster and FAT changes will be written to the Archive.
     * Deleted FAT will remain on the fileTable; use `fatmgrRewriteFAT()` to purge them.
     */
    fun discardFile(fat: FATEntry) {
        if (fat.deleted) return
        val clusterNum = fat.entryID
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot discard cluster #$clusterNum -- is Meta/Bootsector/FAT")

        (fileTable[clusterNum]
                ?: throw FileNotFoundException("No file is associated with cluster #$clusterNum")).let {
            it.deleted = true // notify the other applications the deletion
            if (clusterNum in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) {
                fileTable.remove(clusterNum)
            }
            else {
                traverseClusters(clusterNum) {
                    ARCHIVE.setClusterMeta1Flag(it, 0x80, 0x80)
                    freeClusters.add(it)
                }
            }
            fatmgrDeleteEntry(fat)
        }

    }

    /**
     * Creates new FAT then writes it to the Archive. Returned FATEntry will be registered on the fileTable.
     * @return newly-created FAT entry
     */
    fun allocateFile(size: Long, fileType: Int, filename: String): FATEntry {
        if (fileType != FILETYPE_BINARY && fileType != FILETYPE_DIRECTORY) throw UnsupportedOperationException("Invalid File type: $fileType")

        checkDiskCapacity(size)
        val timeNow = getTimeNow()

        val ptr = if (size <= INLINING_THRESHOLD) getNextFreeInlineCluster() else archiveSizeInClusters

        dbgprintln("[Clustered] allocateFile ptr: ${ptr.toHex()}")

        if (ptr in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) {
            fatmgrCreateNewEntry(ptr, filename, false, false, false, false, timeNow, timeNow, fileType).let {
                fatmgrAllocateInlineFile(it, size)
                return it
            }
        }
        else {
            fatmgrCreateNewEntry(ptr, filename, false, false, false, false, timeNow, timeNow, fileType).let {
                // actually create zero-filled clusters
                if (size > 0) {
                    expandFile(size, HEAD_CLUSTER, ptr, fileType)
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
    private fun expandFile(sizeDelta: Long, prevCluster: Int, currentCluster: Int, fileType: Int): Int {
        dbgprintln("[Clustered] expandFile(sizeDelta=$sizeDelta, prevCluster=$prevCluster, currentCluster=$currentCluster, fileType=$fileType)")

        if (fileType == 0) throw UnsupportedOperationException("Invalid file type: $fileType")

        if (sizeDelta < 0) return LEAF_CLUSTER
        if (sizeDelta == 0L) return currentCluster

        val clustersToAdd = ceil(sizeDelta.toDouble() / CLUSTER_SIZE).toInt()
        val nextCluster = expandArchive(clustersToAdd)

        // expanding on existing
        if (nextCluster != currentCluster) {
            dbgprintln("[Clustered] expand on existing cluster? (currentCluster=$currentCluster, nextCluster=$nextCluster)")

            // set the next cluster (previously 0xFFFFFF)
            ARCHIVE.seekToCluster(currentCluster, 5)
            ARCHIVE.writeInt24(nextCluster)
        }

        // increment content size of this cluster
        ARCHIVE.seekToCluster(currentCluster, FILE_BLOCK_OFFSET_CONTENT_LEN)
        val currentContentSize = ARCHIVE.readUshortBig()
        val sizeNumToWrite = minOf(FILE_BLOCK_CONTENTS_SIZE.toLong(), currentContentSize + sizeDelta)
        val sizeDiscount = sizeNumToWrite - currentContentSize

        dbgprintln("[Clustered] sizeNumToWrite=$sizeNumToWrite, currentContentSize=$currentContentSize, sizeDiscount=$sizeDiscount")

        ARCHIVE.seekToCluster(currentCluster, FILE_BLOCK_OFFSET_CONTENT_LEN)
        ARCHIVE.writeInt16(sizeNumToWrite.toInt())

        dbgprintln("[Clustered] expandFile -- writing contentSize $sizeNumToWrite to cluster ${currentCluster.toHex()}")

        if (nextCluster != currentCluster)
            initClusters(sizeDelta - sizeDiscount, currentCluster, nextCluster, clustersToAdd, fileType)

        else
            initClusters(sizeDelta - sizeDiscount, prevCluster, currentCluster, clustersToAdd, fileType)

        return nextCluster
    }

    private fun initClusters(sizeDelta: Long, parent: Int, current: Int, clusterCount: Int, fileType: Int) {
        if (fileType == 0) throw UnsupportedOperationException("Invalid file type: $fileType")

        val ptrs = listOf(parent) + (current until current+clusterCount) + listOf(LEAF_CLUSTER)
        freeClusters.remove(parent)

        var remaining = sizeDelta
        for (k in 0 until clusterCount) {
            ARCHIVE.seekToCluster(ptrs[k+1])
            ARCHIVE.write(byteArrayOf(fileType.toByte(), 0) + ptrs[k].toInt24Arr() + ptrs[k+2].toInt24Arr())

            dbgprintln("[Clustered] initClusters -- cluster ${ptrs[k+1].toHex()}: writing prevPtr ${ptrs[k].toHex()}, nextPtr ${ptrs[k+2].toHex()}")

            // write content size for the clusters
            val currentContentSize = ARCHIVE.readUshortBig()
            val sizeNumToWrite = minOf(FILE_BLOCK_CONTENTS_SIZE.toLong(), currentContentSize + remaining)
            remaining -= (sizeNumToWrite - currentContentSize)
            ARCHIVE.seekToCluster(ptrs[k+1], FILE_BLOCK_OFFSET_CONTENT_LEN)
            ARCHIVE.writeInt16(sizeNumToWrite.toInt())

            dbgprintln("[Clustered] initClusters -- writing contentSize $sizeNumToWrite to cluster ${ptrs[k+1].toHex()}")

            freeClusters.remove(ptrs[k+1])
        }
    }

    /**
     * Mark the cluster as dirty
     */
    fun setDirty(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot modify cluster #$clusterNum -- is Meta/Bootsector/FAT")
        ARCHIVE.setClusterMeta1Flag(clusterNum, 0x10, 0x10)
    }

    /**
     * Mark the cluster as not dirty
     */
    fun unsetDirty(clusterNum: Int) {
        if (clusterNum < 2 + fatClusterCount) throw IllegalArgumentException("Cannot modify cluster #$clusterNum -- is Meta/Bootsector/FAT")
        ARCHIVE.setClusterMeta1Flag(clusterNum, 0x10, 0x00)
    }

    private fun expandArchive(clusterCount: Int): Int {

        dbgprintln("[Clustered.expandArchive] expandArchive($clusterCount); archiveSizeInClusters = $archiveSizeInClusters")

        val newPtr = archiveSizeInClusters
        ARCHIVE.seekToCluster(newPtr.toLong())
        repeat(clusterCount) {
            ARCHIVE.write(EMPTY_CLUSTER)
        }

        dbgprintln("[Clustered.expandArchive] archiveSizeInClusters now = $archiveSizeInClusters")

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
        if (insertPos == 0 && (FATs.isEmpty() || FATs[0].toInt24() != fatClusterCount + 2)) throw IllegalArgumentException("Cannot remove FAT of the root directory (insertPos=$insertPos, deleteCount=$deleteCount, FATs=(${FATs.size})[...])")

        checkDiskCapacity(FATs.size * FAT_ENTRY_SIZE)

        val deleteCount = deleteCount.coerceAtLeast(0)
        val fatSizeDelta = FATs.size - deleteCount

        if (deleteCount > fatEntryCount) throw IllegalArgumentException("deleteCount ($deleteCount) is larger than the number of FAT entries in the Archive ($fatEntryCount)")


//        dbgprintln("[Clustered] spliceFAT called; insertPos=$insertPos(foff: ${(2 * CLUSTER_SIZE + insertPos * FAT_ENTRY_SIZE).toHex()}), deleteCount=$deleteCount, FATs=(${FATs.size})[${FATs.joinToString(" ; ") { it.sliceArray(0..15).joinToString(" ") { it.toUint().toString(16).padStart(2, '0').toUpperCase() } }}], current FAT cap: ${fatClusterCount * FATS_PER_CLUSTER}")


        // grow FAT area?
        if (fatEntryCount + fatSizeDelta > fatClusterCount * FATS_PER_CLUSTER) {
//            testPause("about to grow FAT area; check the archive, then hit Return to continue")

//            dbgprintln("[Clustered.spliceFAT]     growing FAT area...")

            val fatRenumDelta = growFAT()
            // renum inserting FATs
            FATs.forEach {
                it.renumFAT(fatRenumDelta)
            }


//            testPause("FAT area has grown; check the archive, then hit Return to continue")
        }

        // shift FATS on the Archive
        val strideByEntry = fatSizeDelta
        val stride = fatSizeDelta * FAT_ENTRY_SIZE
        val seekpos = 2L* CLUSTER_SIZE + insertPos*FAT_ENTRY_SIZE


        // moving one FAT at a time to constrain the memory footdbgprint
        val forRange = if (strideByEntry > 0)
            2L*CLUSTER_SIZE + (fatEntryCount - 1) * FAT_ENTRY_SIZE downTo seekpos step FAT_ENTRY_SIZE.toLong()
        else if (strideByEntry < 0)
            (seekpos + deleteCount * FAT_ENTRY_SIZE)..2L*CLUSTER_SIZE + (fatEntryCount - 1) * FAT_ENTRY_SIZE step FAT_ENTRY_SIZE.toLong()
        else
            LongRange.EMPTY

//        dbgprintln("[Clustered]     splice delete=$deleteCount, add=${FATs.size}; stride=$stride, fatEntryCount=$fatEntryCount, forRange=${if (forRange.isEmpty()) "(empty)" else "${forRange.first.toHex()} to ${forRange.last.toHex()}"}")

        for (startOffset in forRange) {
//            dbgprintln("[Clustered]         moving FAT at ${startOffset.toHex()} to ${(startOffset + stride).toHex()}")
            ARCHIVE.seek(startOffset)
            val bytes = ARCHIVE.read(FAT_ENTRY_SIZE)
            ARCHIVE.seek(startOffset + stride)
            ARCHIVE.write(bytes)

            val entryID = bytes.toInt24()

//            dbgprintln("[Clustered]           FAT at ${startOffset.toHex()}: ${bytes.sliceArray(0..15).joinToString(" ") { it.toUint().toString(16).padStart(2, '0').toUpperCase() }}")

//            dbgprint("[Clustered]         trying to change FAT Index for entry $entryID")
            fatEntryIndices[entryID]?.let {
//                dbgprint("....${it} -> ${(it + strideByEntry)}")
                fatEntryIndices[entryID] = it + strideByEntry
            }
//            dbgprintln()
        }

        // delete trailing FATs if stride < 0
        if (stride < 0) {
            ARCHIVE.seek(2L * CLUSTER_SIZE + (fatEntryCount + fatSizeDelta.toLong()) * FAT_ENTRY_SIZE)
            for (i in 0 until deleteCount - FATs.size) {
                ARCHIVE.write(EMPTY_FAT_ENTRY)
            }
        }

        // write new FATs
        if (FATs.isNotEmpty()) {
//            dbgprintln("[Clustered]     writing FATs at file offset ${seekpos.toHex()}")
            ARCHIVE.seek(seekpos)
            FATs.forEach { bytes ->
                ARCHIVE.write(bytes)
            }
        }

//        dbgprintln("[Clustered]     incrementing fatEntryCount by ${fatSizeDelta} (${fatEntryCount} -> ${fatEntryCount + fatSizeDelta.toLong()})")
        fatEntryCount += fatSizeDelta

        ARCHIVE.seek(seekpos)
        return seekpos
    }

    private fun getInliningThreshold(fat: FATEntry) = when (fat.fileType) {
        FILETYPE_BINARY -> INLINING_THRESHOLD
        FILETYPE_DIRECTORY -> 246 // limiting to 1 Entry due to the difficulties with renumbering
        else -> 0
    }

    private fun writeBytesInline(inlinedFile: FATEntry, buffer: ByteArray, bufferOffset: Int, writeLength: Int, writeStartOffset: Long) {
        if (inlinedFile.fileType == 0) throw UnsupportedOperationException("FAT has no file type set (${inlinedFile.fileType})")

        val fileCopy = inlinedFile.getInlineBytes()
        val fileBytes = ByteArray(maxOf(fileCopy.size.toLong(), writeStartOffset + writeLength).toInt())

        System.arraycopy(fileCopy, 0, fileBytes, 0, fileCopy.size)
        System.arraycopy(buffer, bufferOffset, fileBytes, writeStartOffset.toInt(), writeLength)

        if (fileBytes.size <= getInliningThreshold(inlinedFile)) {
            fatmgrSetInlineBytes(inlinedFile, fileBytes)
            fatmgrUpdateModificationDate(inlinedFile, getTimeNow())

        }
        // un-inline the file
        else {
            val uninlinedFile = allocateFile(fileBytes.size.toLong(), inlinedFile.fileType, inlinedFile.filename)
            uninlinedFile.takeAttribsFrom(inlinedFile)

            dbgprintln2("[Clustered.writeBytesInline] un-inlining; entryID = ${uninlinedFile.entryID.toHex()}")

            fileTable.remove(inlinedFile.entryID)
            fatEntryCount -= inlinedFile.extendedEntries.size + 1

            fatmgrRewriteAllFAT() // must precede the writeBytes

            writeBytes(uninlinedFile, fileBytes, 0, fileBytes.size, 0, true)

            renameEntryID(inlinedFile.entryID, uninlinedFile.entryID)

            fatmgrRewriteAllFAT()
        }
    }



    /**
     * Writes bytes to the file. If the write operation reaches outside the file's allocated size, length of the file
     * will be increased (i.e. new clusters will be allocated) to fit
     *
     * Writing operation may change the FAT reference, if the file was previously inlined then uninlined, or was uninlined then inlined.
     * The Clustfile must update its FAT reference after this function call.
     */
    fun writeBytes(entry: FATEntry, buffer: ByteArray, bufferOffset: Int, writeLength: Int, writeStartOffset: Long, forceUninline: Boolean = false) {
        if (entry.fileType == 0) throw UnsupportedOperationException("FAT has no file type set (${entry.fileType})")

        dbgprintln2("[Clustered] Writebytes FAT=$entry, writeLength=$writeLength, writeStartOffset=$writeStartOffset")

        val addedBytes = writeStartOffset + writeLength - getFileLength(entry)

        checkDiskCapacity(addedBytes)

        if (entry.isInline && !forceUninline) return writeBytesInline(entry, buffer, bufferOffset, writeLength, writeStartOffset)

        var writeCursor = writeStartOffset
        var remaining = writeLength.toLong()

        var ptr = entry.entryID
        val infoReadbuf = ByteArray(FILE_BLOCK_HEADER_SIZE)
        ARCHIVE.seekToCluster(ptr)
//        dbgprintln("[Clustered]   file.seekToCluster $ptr")
        ARCHIVE.read(infoReadbuf)
        var meta1 = infoReadbuf[0].toUint()
        var meta2 = infoReadbuf[1].toUint()
        var prev = infoReadbuf.toInt24(2)
        var nextPtr = infoReadbuf.toInt24(5)
        var contentsSizeInThisCluster = infoReadbuf.toInt16(8)

        var firstClusterOfWriting = true

        var cursorInClusterFileArea = writeStartOffset


        while (writeCursor < writeLength + writeStartOffset) {
            dbgprintln2("[Clustered] writeCursor = $writeCursor < ${writeLength + writeStartOffset}")


            // seek to next cluster
            // if cursorInCluster is larger than FILE_BLOCK_CONTENTS_SIZE, this operation will loop until the file cursor is on the right cluster
            while (cursorInClusterFileArea >= FILE_BLOCK_CONTENTS_SIZE) {

                dbgprintln2("[Clustered]   cursorInClusterFileArea = $cursorInClusterFileArea")


                // if next cluster is NULL,,,
                if (nextPtr == LEAF_CLUSTER) {
                    // allocate new cluster and then modify the nextPtr on the Archive
                    val sizeDelta = if (addedBytes > 0) addedBytes - writeLength + remaining else remaining
                    dbgprintln2("[Clustered]   expanding file (sizeDelta=$sizeDelta, prevCluster=$prev, currentCluster=$ptr, fileType=${entry.fileType})")
                    nextPtr = expandFile(sizeDelta, prev, ptr, entry.fileType)

                    firstClusterOfWriting = false
                }
                ptr = nextPtr
                ARCHIVE.seekToCluster(ptr)
                ARCHIVE.read(infoReadbuf)
                meta1 = infoReadbuf[0].toUint()
                meta2 = infoReadbuf[1].toUint()
                prev = infoReadbuf.toInt24(2)
                nextPtr = infoReadbuf.toInt24(5)
                contentsSizeInThisCluster = infoReadbuf.toInt16(8)

                cursorInClusterFileArea -= FILE_BLOCK_CONTENTS_SIZE
            }


            // mark the cluster as "dirty"
            ARCHIVE.seekToCluster(ptr)
            ARCHIVE.write(byteArrayOf(meta1.and(0xEF).or(16).toByte(), meta2.toByte()))
            freeClusters.remove(ptr)


            // at the end of the skip-run, position the file to the right place
            ARCHIVE.seekToCluster(ptr, FILE_BLOCK_HEADER_SIZE + cursorInClusterFileArea)

            // cursor is moved to the right place, do the writing
            val writeLengthOnThisCluster =
                    listOf(remaining,
                            FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea,
                            FILE_BLOCK_CONTENTS_SIZE - contentsSizeInThisCluster.toLong()
                    ).filter { it > 0 }.minOf { it }.toInt()


            dbgprintln2("[Clustered] writeLengthOnThisCluster = $writeLengthOnThisCluster (minOf $remaining, ${FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea}, ${FILE_BLOCK_CONTENTS_SIZE - contentsSizeInThisCluster})")

            // actually write
            ARCHIVE.write(buffer, (bufferOffset + writeCursor - writeStartOffset).toInt(), writeLengthOnThisCluster)
            remaining -= writeLengthOnThisCluster
            writeCursor += writeLengthOnThisCluster
            cursorInClusterFileArea += writeLengthOnThisCluster
            // update the 'number of bytes in this cluster' number on the file
            ARCHIVE.seekToCluster(ptr, FILE_BLOCK_OFFSET_CONTENT_LEN)
            val thisClusterContentsSizeBefore = ARCHIVE.readUshortBig()
            val thisClusterContentsSizeAfter = if (firstClusterOfWriting)
                                        writeLengthOnThisCluster + writeStartOffset
                                    else
                                        minOf(FILE_BLOCK_CONTENTS_SIZE.toLong(), remaining)

            dbgprintln2("[Clustered] size before: $thisClusterContentsSizeBefore, after: $thisClusterContentsSizeAfter")

            if (thisClusterContentsSizeBefore < thisClusterContentsSizeAfter) {
                ARCHIVE.seekToCluster(ptr, FILE_BLOCK_OFFSET_CONTENT_LEN)
                ARCHIVE.writeInt16(thisClusterContentsSizeAfter.toInt())
                dbgprintln2("[Clustered] writeBytes -- writing contentSize $thisClusterContentsSizeAfter to cluster ${ptr.toHex()}")
            }
            firstClusterOfWriting = false


            // unset the dirty flag
            ARCHIVE.seekToCluster(ptr)
            ARCHIVE.write(meta1 and 0xEF)

            // set modification date on the FAT
            val timeNow = getTimeNow()
            fatmgrUpdateModificationDate(entry, timeNow)
        }
    }

    fun readBytesInline(entry: FATEntry, buffer: ByteArray, bufferOffset: Int, readLength: Int, readStartOffset: Int): Int {
        dbgprintln2("[Clustered.readBytesInline] ptr = ${entry.entryID.toHex()}")
        val bytes = entry.getInlineBytes()
        var readLength = if (readLength + readStartOffset > bytes.size) bytes.size - readStartOffset else readLength
        if (readLength + bufferOffset > bytes.size) readLength -= bufferOffset

        dbgprintln2("[Clustered.readBytesInline] readLength=$readLength")

        System.arraycopy(bytes, readStartOffset, buffer, bufferOffset, readLength)
        return readLength
    }

    /**
     * Reads the file. Read bytes go to the given buffer. If the read operation reaches outside the file, the operation
     * will be finished, and the return value will be smaller than the specified readLength.
     * @return number of bytes actually read
     */
    fun readBytes(entry: FATEntry, buffer: ByteArray, bufferOffset: Int, readLength: Int, readStartOffset: Int): Int {
        if (entry.isInline) {
            dbgprintln2("[Clustered.readBytes] this file (ID ${entry.entryID.toHex()}) is in-lined, calling readBytesInline")
            return readBytesInline(entry, buffer, bufferOffset, readLength, readStartOffset)
        }

        var readCursor = readStartOffset
        var remaining = readLength

        var ptr = entry.entryID
        val infoReadbuf = ByteArray(FILE_BLOCK_HEADER_SIZE)
        ARCHIVE.seekToCluster(ptr)
        ARCHIVE.read(infoReadbuf)
        var meta1 = infoReadbuf[0].toUint()
        var meta2 = infoReadbuf[1].toUint()
        var nextPtr = infoReadbuf.toInt24(5)
        var contentsSizeInThisCluster = infoReadbuf.toInt16(8)

        var firstClusterOfFile = true

        var actualBytesWritten = 0

        var cursorInClusterFileArea = readStartOffset

        dbgprintln2("[Clustered.readBytes] ptr = ${ptr.toHex()}")

        while (readCursor < readLength + readStartOffset) {

            dbgprintln2("[Clustered.readBytes] while ( $readCursor < ${readLength + readStartOffset} )")

            // seek to next cluster
            // if cursorInCluster is larger than FILE_BLOCK_CONTENTS_SIZE, this operation will loop until the file cursor is on the right cluster
            while (cursorInClusterFileArea >= FILE_BLOCK_CONTENTS_SIZE) {
                // if next cluster is NULL,,,
                if (nextPtr == 0) {
                    // throw error
                    throw VDIOException("Unexpected end-of-cluster reached (file: ${entry.entryID}, read cursor: $readCursor)")
                }
                ptr = nextPtr
                ARCHIVE.seekToCluster(ptr)
                ARCHIVE.read(infoReadbuf)
                meta1 = infoReadbuf[0].toUint()
                meta2 = infoReadbuf[1].toUint()
                nextPtr = infoReadbuf.toInt24(5)
                contentsSizeInThisCluster = infoReadbuf.toInt16(8)

                cursorInClusterFileArea -= FILE_BLOCK_CONTENTS_SIZE

                firstClusterOfFile = false
            }

            // at the end of the skip-run, position the file to the right place
            dbgprintln2("[Clustered.readBytes] seek to ptr ${ptr.toHex()}, offset ${FILE_BLOCK_HEADER_SIZE + cursorInClusterFileArea}")
            ARCHIVE.seekToCluster(ptr, FILE_BLOCK_HEADER_SIZE + cursorInClusterFileArea)

            // cursor is moved to the right place, do the reading
            val readLengthOnThisCluster = listOf(remaining, FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea, contentsSizeInThisCluster).filter { it > 0 }.minOf { it  }

            dbgprintln2("[Clustered.readBytes] readLengthOnThisCluster = $readLengthOnThisCluster ; minOf $remaining, ${FILE_BLOCK_CONTENTS_SIZE - cursorInClusterFileArea}, $contentsSizeInThisCluster")

            // actually read
            ARCHIVE.read(buffer, bufferOffset + readCursor - readStartOffset, readLengthOnThisCluster)
            actualBytesWritten += readLengthOnThisCluster
            remaining -= readLengthOnThisCluster
            readCursor += readLengthOnThisCluster
            cursorInClusterFileArea += readLengthOnThisCluster
        }

        return actualBytesWritten
    }

    /**
     * Reads the file. If the read operation reaches outside the file, the operation
     * will be finished, and the length of the returning ByteArray will be smaller than the specified readLength.
     * @return ByteArray containing bytes actually been read. Size of this array may be smaller than the specified length.
     */
    fun readBytes(entry: FATEntry, length: Int, offset: Int): ByteArray {
        dbgprintln2("[Clustered.readBytes] readBytes ptr=${entry.entryID.toHex()}, len=$length")
        dbgprintln2("[Clustered.readBytes] called by:")
        Thread.currentThread().stackTrace.forEach {
            dbgprintln2(" ".repeat(22) + it)
        }

        val ba = ByteArray(length)
        val actualSize = readBytes(entry, ba, 0, length, offset)
        dbgprintln2("[Clustered.readBytes] readBytes actualSize=$actualSize")
        if (ba.size == actualSize) return ba

        val ba2 = ByteArray(actualSize)
        System.arraycopy(ba, 0, ba2, 0, actualSize)
        return ba2
    }

    private fun setFileLengthInline(entry: FATEntry, newLength: Long) {
        val oldBytes = entry.getInlineBytes()
        val newBytes = ByteArray(newLength.toInt())
        System.arraycopy(oldBytes, 0, newBytes, 0, minOf(oldBytes.size, newBytes.size))

        fatmgrSetInlineBytes(entry, newBytes) // will spliceFAT for us
        fatmgrUpdateModificationDate(entry, getTimeNow())
    }

    /**
     * Sets the length of the file. If the new length is smaller, the remaining clusters, if any, will be marked as discarded;
     * if the new length is longer, new clusters will be allocated. Clusters marked as discarded must be freed using [defrag].
     */
    fun setFileLength(entry: FATEntry, newLength: Long) {
        if (entry.fileType == 0) throw UnsupportedOperationException("FAT has no file type set (${entry.fileType})")
        if (entry.isInline) return setFileLengthInline(entry, newLength)

        var remaining = newLength

        var parent = HEAD_CLUSTER
        var cluster = entry.entryID

        do {
            // seek to cluster
            ARCHIVE.seekToCluster(cluster, 5)
            // get length for this cluster
            val thisClusterLen = minOf(remaining, FILE_BLOCK_CONTENTS_SIZE.toLong())
            // get next cluster
            var nextCluster = ARCHIVE.readInt24()

            if (remaining >= 0) {
                // write new contents length
                ARCHIVE.writeInt16(thisClusterLen.toInt())
                // subtract remaining
                remaining -= thisClusterLen
            }
            else {
                // mark remaining cluster as deleted
                ARCHIVE.seekToCluster(cluster)
                ARCHIVE.write(128)
            }

            // mark end-of-cluster if applicable
            if (remaining == 0L) {
                ARCHIVE.seekToCluster(cluster, 5)
                ARCHIVE.writeInt24(LEAF_CLUSTER)
            }
            // create new cluster if end-of-cluster is prematurely reached
            else if (nextCluster == NULL_CLUSTER) {
                nextCluster = expandFile(remaining, parent, cluster, entry.fileType)
            }

            parent = cluster
            cluster = nextCluster
        } while (cluster != LEAF_CLUSTER)

        fatmgrUpdateModificationDate(entry, getTimeNow())
    }

    /**
     * Performs given action over each cluster in the cluster-chain of the given file.
     *
     * For each action, the archive will be seeked to the header (offset zero) of the cluster.
     *
     * @param start cluster number to start traverse
     * @param action what to do. Argument: Int-Current cluster number
     */
    fun traverseClusters(start: Int, action: (Int) -> Unit) {
        var cluster = start

        val visited = HashSet<EntryID>()

        do {
            // seek to cluster
            ARCHIVE.seekToCluster(cluster, 5)
            visited.add(cluster)

//            dbgprintln("[Clustered.traverseClusters] cluster: ${cluster.toHex()}")

            // get next cluster
            val nextCluster = ARCHIVE.readInt24()

            if (visited.contains(nextCluster)) throw VDIOException("Loop detected -- cluster ${nextCluster} was visited already; prev: $cluster, next: $nextCluster")

            ARCHIVE.seekToCluster(cluster)
            action(cluster)

            cluster = nextCluster
        } while (cluster in 1 until LEAF_CLUSTER)
    }

    /**
     * Performs given action over each cluster in the cluster-chain of the given file.
     *
     * For each action, the archive will be seeked to the header (offset zero) of the cluster.
     *
     * @param start cluster number to start traverse
     * @param action what to do. Argument: Int-Current cluster number, Return: false to break the loop
     */
    fun traverseClustersBreakable(start: Int, action: (Int) -> Boolean) {
        var cluster = start

        val visited = HashSet<EntryID>()

        do {
            // seek to cluster
            ARCHIVE.seekToCluster(cluster, 5)
            visited.add(cluster)

//            dbgprintln("[Clustered.traverseClusters] cluster: ${cluster.toHex()}")

            // get next cluster
            val nextCluster = ARCHIVE.readInt24()

            if (visited.contains(nextCluster)) throw VDIOException("Loop detected -- cluster ${nextCluster} was visited already; prev: $cluster, next: $nextCluster")

            ARCHIVE.seekToCluster(cluster)
            if (!action(cluster)) break

            cluster = nextCluster
        } while (cluster in 1 until LEAF_CLUSTER)
    }

    /**
     * Returns the length of the file. The file's length is only knowable by chain traversal, so store the result of
     * this function to avoid unnecessary IO operations.
     */
    fun getFileLength(entry: FATEntry): Int {
        var accumulator = 0

        if (entry.entryID in INLINE_FILE_CLUSTER_BASE..INLINE_FILE_CLUSTER_LAST) {
            accumulator += entry.getInlinedLength()
        }
        else {
            traverseClusters(entry.entryID) {
                // get length for this cluster
                ARCHIVE.seekToCluster(it, 8)
                val len = ARCHIVE.readUshortBig()
                // add to accumulator
                accumulator += len
            }
        }

        return accumulator
    }

    /**
     * Build map of freeClusters, actually trim the archive, then removes any numbers in freeClusters
     * that points outside of the archive.
     */
    fun trimArchive() {
        dbgprintln("[Clustered.trimArchive] archiveSizeInClusters before = $archiveSizeInClusters")
        dbgprintln("[Clustered.trimArchive] freeClusters before: ${freeClusters.sortedDescending()}")


        if (freeClusters.isEmpty()) buildFreeClustersMap()


        dbgprintln("[Clustered] freeClusters after: ${freeClusters.sortedDescending()}")

        if (freeClusters.isEmpty()) {
            dbgprintln("[Clustered] disk is already as compact as it can be")
            return
        }

        (freeClusters.maxOf { it } + 1).let {
            if (!isThisClusterFree(it)) {
                dbgprintln("[Clustered] cannot trim -- cluster $it is occupied")
                return
            }
        }


        // trim the archive if applicable
        var ptr = -1
        freeClusters.sortedDescending().let {
            var k = 0
            while (k < it.size - 1) {
                if (it[k] - 1 == it[k + 1]) {
                    ptr = it[k+1]
                }

                k += 1
            }
        }
        dbgprintln("[Clustered.trimArchive] trim point: ${ptr}")

        if (ptr == -1) {
            ptr = maxOf(2 + fatClusterCount, archiveSizeInClusters)
        }

        ARCHIVE.setLength(CLUSTER_SIZE * ptr.toLong())

        dbgprintln("[Clustered.trimArchive] archiveSizeInClusters now = $archiveSizeInClusters")

        freeClusters.removeIf { it > ptr }
    }

    var defragInterruptRequested = false

    /**
     * Trims the archive (running [trimArchive]) then frees any clusters that are marked as discarded, then fills the
     * "gaps" made by those free clusters by moving last clusters on the disk into these "gaps".
     *
     * The operation essentially compacts the Archive by removing freed space.
     */
    fun defrag(option: Int = 0): List<Pair<EntryID, EntryID>> {
        defragInterruptRequested = false
        trimArchive() // will build freeClusters map if the map is empty

        val workReport = ArrayList<Pair<EntryID, EntryID>>()

        for (target in freeClusters.sorted()) {
            if (defragInterruptRequested) break
            val src = archiveSizeInClusters - 1
            if (src <= target) break
            changeClusterNum(src, target)
            workReport.add(src to target)
        }

        freeClusters.clear(); trimArchive()

        return workReport
    }

    /**
     * Constructs the [freeClusters] map. Meant to be used by Disk Defragment softwares.
     */
    fun buildFreeClustersMap() {
        for (kluster in 2 + fatClusterCount until ARCHIVE.length().toInt() / CLUSTER_SIZE) {
            if (isThisClusterFree(kluster)) {
                freeClusters.add(kluster)
            }
        }
    }

    /**
     * Gets the copy of the [freeClusters] map. Meant to be used by Disk Defragment softwares.
     */
    fun getFreeClusterMap(): List<EntryID> {
        return freeClusters.sorted()
    }

    private fun isThisClusterFree(clusterNum: Int): Boolean {
        if (clusterNum < fatClusterCount + 2) {
            dbgprintln("[Clustered.isThisClusterFree] ${clusterNum.toHex()} not free -- ${clusterNum} < ${fatClusterCount + 2}")
            return false
        }
        if (clusterNum >= archiveSizeInClusters) return true

        ARCHIVE.seekToCluster(clusterNum)

        val flag = ARCHIVE.read()
        val flag2 = ARCHIVE.read()

        if (flag == -1) return true
        if (flag2 == -1) return true

        // persistent?
        if (flag2 and 0x80 != 0) {
            dbgprintln("[Clustered.isThisClusterFree] ${clusterNum.toHex()} not free -- flag2 and 0x80 != 0 (${flag2.toString(2)})")
            return false
        }
        // temporarily duplicated AND marked as discarded?
        if (flag2 and 1 != 0 && flag and 0x80 != 0) return true

        // marked as discarded
        if (flag and 0x80 != 0) return true
        // file type of 0
        if (flag and 0x0F == 0) return true

        dbgprintln("[Clustered.isThisClusterFree] ${clusterNum.toHex()} not free -- generic failure (flags: ${flag.toString(2)}, ${flag2.toString(2)})")
        return false
    }

    private fun changeClusterNum(from: Int, to: Int) {
        if (!isThisClusterFree(to)) throw IllegalStateException("Target cluster $to(${to.toHex()}) is not free to overwrite")

        //// Step 1. copy over cluster
        // copy over a cluster
        ARCHIVE.seekToCluster(from)
        val cluster = ARCHIVE.read(CLUSTER_SIZE)
        // mark copied cluster as temporarily duplicated
        cluster[1] = (cluster[1].toUint() and 0xFE or 1).toByte()
        // paste the cluster to target position
        ARCHIVE.seekToCluster(to)
        ARCHIVE.write(cluster)

        val prevCluster = cluster.toInt24(2)
        val nextCluster = cluster.toInt24(5)

        //// Step 2. edit prev cluster's fwdlink
        if (prevCluster.isValidCluster()) {
            ARCHIVE.seekToCluster(prevCluster, 5)
            ARCHIVE.writeInt24(to)
        }

        //// Step 3. edit next cluster's bwdlink
        if (nextCluster.isValidCluster()) {
            ARCHIVE.seekToCluster(nextCluster, 2)
            ARCHIVE.writeInt24(to)
        }

        //// Step 4. find-replace on all FAT entries
        val affectedInlineFATs = HashSet<EntryID>()
        for (kfat in 0 until fatEntryCount) {
            ARCHIVE.seekToFAT(kfat)
            val ptr = ARCHIVE.readInt24()
            // Extended Entries?
            if (ptr >= EXTENDED_ENTRIES_BASE) {
                if (ptr == 0xFFFF12) {
                    val parentID = ARCHIVE.readInt24()
                    for (offset in 8 until FAT_ENTRY_SIZE - 3 step 3) {
                        ARCHIVE.seekToFAT(kfat, offset)
                        if (ARCHIVE.readInt24() == from) {
                            ARCHIVE.seekToFAT(kfat, offset)
                            ARCHIVE.writeInt24(to)
                            affectedInlineFATs.add(parentID)
                        }
                    }
                }
            }
            // THE Entry?
            else if (ptr == from) {
                ARCHIVE.seekToFAT(kfat)
                ARCHIVE.writeInt24(to)
            }
        }

        //// Step 4-1. sync the affected Extended Entries
        affectedInlineFATs.forEach {
            fatmgrSyncInlinedBytes(it)
        }

        //// Step 5-1. unset 'temporarily duplicated' flag of the copied cluster
        ARCHIVE.setClusterMeta2Flag(to, 1, 0)

        //// Step 6-1. set file type of source cluster to 0, and mark as discarded
        ARCHIVE.setClusterMeta1Flag(from, 0b1000_1111, 0b1000_0000)

        //// Step 6-2. trim the file size/zero-fill the cluster
        var isThisLastCluster = true
        for (offset in from+1 until ARCHIVE.length() / CLUSTER_SIZE) {
            if (!isThisClusterFree(offset.toInt())) {
                isThisLastCluster = false
                break
            }
        }
        dbgprintln("[Clustered.defrag] was cluster $from the last cluster? $isThisLastCluster")
        // trim file size
        if (isThisLastCluster) {
            ARCHIVE.setLength(from * CLUSTER_SIZE.toLong())
            dbgprintln("[Clustered.defrag]     archiveSizeInClusters = $archiveSizeInClusters (should be equal to `from`=$from)")
        }
        // zero-fill
        else {
            ARCHIVE.seekToCluster(from)
            ARCHIVE.write(EMPTY_CLUSTER)
        }
    }




    fun getFileIterator(clusterNum: Int): ByteIterator {
        if (clusterNum !in 2 + fatClusterCount..archiveSizeInClusters)
            throw IllegalArgumentException("Not a valid cluster number: $clusterNum")

        ARCHIVE.seekToCluster(clusterNum)
        val fileType = ARCHIVE.read()

        if (fileType != 1) // will also detect non-zero-ness of the "error flags"
            throw UnsupportedOperationException("File is not a binary file (type ${fileType and 15}, flags ${fileType.ushr(4) and 15})")

        // initial cluster must be clean and not dirty!
        return object : ByteIterator() {

            private var clusterOffset = 0 // 0..4086

            private var nextCluster = clusterNum // end-of-cluster = 0
            private var bytesInCurrentCluster = -1

            private var foundDirtyCluster = false

            private fun getSizeOnThisClusterAndResetClusterOffset(clusterNum: Int) {
                ARCHIVE.seekToCluster(clusterNum)

                // detect the dirty/deleted cluster
                val flags = ARCHIVE.read().ushr(4)

                if (flags != 0) {
                    foundDirtyCluster = true
                    nextCluster = 0
                    bytesInCurrentCluster = 0
                    ARCHIVE.skipBytes(9)
                }
                else {
                    ARCHIVE.skipBytes(4)
                    nextCluster = ARCHIVE.readInt24()
                    bytesInCurrentCluster = ARCHIVE.readUshortBig()
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

                ARCHIVE.read().toByte().let {
                    clusterOffset += 1
                    return it
                }
            }

        }
    }

    fun readBoot(): ByteArray {
        ARCHIVE.seek(CLUSTER_SIZE.toLong())
        return ARCHIVE.read(CLUSTER_SIZE)
    }

    fun writeBoot(code: ByteArray) {
        ARCHIVE.seek(CLUSTER_SIZE.toLong())
        ARCHIVE.write(code, 0, minOf(code.size, CLUSTER_SIZE))
    }

    val totalSpace: Long; get() = diskSize
    val usedSpace: Long; get() = ARCHIVE.length()
    val freeSpace: Long; get() = diskSize - ARCHIVE.length()
    val usableSpace: Long; get() = diskSize - ARCHIVE.length()

    val totalClusterCount: Int; get() = (diskSize / CLUSTER_SIZE.toLong()).toInt()
    val freeClusterCount: Int; get() = totalClusterCount - archiveSizeInClusters
    val clusterSize: Int = CLUSTER_SIZE

    /**
     * @return Cluster flags in big endian: high 8 bits for Meta1, low 8 bits for Meta2
     */
    fun getClusterFlags(clusterNum: Long): Int {
        ARCHIVE.seekToCluster(clusterNum)
        return ARCHIVE.readUshortBig()
    }
    /**
     * @return Cluster flags in big endian: high 8 bits for Meta1, low 8 bits for Meta2
     */
    fun getClusterFlags(clusterNum: Int): Int {
        ARCHIVE.seekToCluster(clusterNum)
        return ARCHIVE.readUshortBig()
    }

    /**
     * Renaming targets:
     * - FAT area: Extended Attributes 0xFFFF12
     * - File area: every directory
     *
     * fileTable must be updated beforehand!
     */
    internal fun renameEntryID(old: EntryID, new: EntryID) {
        dbgprintln("[Clustered.renameEntryID] trying to rename ${old.toHex()} to ${new.toHex()}")

        val toBeSortedInline = HashSet<EntryID>()
        val toBeSorted2 = HashSet<Int>()

        // rename FAT area
        /*for (fatIndex in 0 until fatEntryCount) {
            ARCHIVE.seekToFAT(fatIndex)
            // rename on inline directory
            if (ARCHIVE.readInt24() == 0xFFFF12) {
                val parentID = ARCHIVE.readInt24()
                for (offset in 8 until FAT_ENTRY_SIZE - 3 step 3) {
                    ARCHIVE.seekToFAT(fatIndex, offset)
                    if (ARCHIVE.readInt24() == old) {
                        ARCHIVE.seekToFAT(fatIndex, offset)
                        ARCHIVE.writeInt24(new)
                        dbgprintln("[Clustered.renameEntryID] ... at inlineDir of entry ${parentID.toHex()} ExtEnt order ${fatEntryIndices[parentID]?.minus(fatIndex)?.times(-1)}, offset $offset")
                        toBeSortedInline.add(parentID)
                    }
                }
            }
        }*/
        // rename FAT area
        fileTable.forEach { entry ->
            entry.extendedEntries.forEachIndexed { bindex, it ->
                if (it.toInt24(0) == 0xFFFF12) {
                    for (offset in 8 until FAT_ENTRY_SIZE - 3 step 3) {
                        if (it.toInt24(offset) == old) {
                            it.writeInt24(new, offset)
                            dbgprintln("[Clustered.renameEntryID] ... at inlineDir of entry ${entry.entryID.toHex()} ExtEnt order $bindex, offset $offset")
                            toBeSortedInline.add(entry.entryID)
                        }
                    }
                }
            }
        }


        // rename File area
        for (kluster in 2 + fatClusterCount until ARCHIVE.length().toInt() / CLUSTER_SIZE) {
            ARCHIVE.seekToCluster(kluster)
            if (ARCHIVE.read() and 15 == FILETYPE_DIRECTORY) {
                for (offset in FILE_BLOCK_HEADER_SIZE until CLUSTER_SIZE step 3) {
                    ARCHIVE.seekToCluster(kluster, offset)
                    if (ARCHIVE.readInt24() == old) {
                        ARCHIVE.seekToCluster(kluster, offset)
                        ARCHIVE.writeInt24(new)
                        dbgprintln("[Clustered.renameEntryID] ... at Cluster ${kluster.toHex()}, offset $offset")
                        toBeSorted2.add(kluster)
                    }
                }
            }
        }


        toBeSortedInline.forEach { sortDirectoryInline(fileTable[it]!!) }
        toBeSorted2.forEach { sortDirectoryCluster(it) }
    }

    fun getRawCluster(num: Int): ByteArray {
        ARCHIVE.seekToCluster(num)
        return ARCHIVE.read(CLUSTER_SIZE)
    }

    fun sortDirectory(entry: FATEntry) {
        if (entry.fileType != FILETYPE_DIRECTORY) throw VDIOException("Tried to sort non-directory (filetype = ${entry.fileType})")

        if (entry.isInline) {
            sortDirectoryInline(entry)
        }
        else {
            traverseClusters(entry.entryID) { clusternum ->
                sortDirectoryCluster(clusternum)
            }
        }
    }

    private fun sortDirectoryInline(entry: FATEntry) {
        val newContents = entry.getInlineBytes().chunked(3).map { it.toInt24() }.also {
            dbgprintln("[Clustered.sortDirectoryInline] trying to sort DirWithId ${entry.entryID.toHex()} [${it.joinToString { it.toHex() }}]")
            dbgprintln("[Clustered.sortDirectoryInline] FAT: [${fileTable.joinToString { it.entryID.toHex() }}]")
        }.sortedWith(fatComparator).let { ids ->
            ByteArray(ids.size * 3).also { ba ->
                ids.forEachIndexed { i, id -> ba.writeInt24(id, i * 3) }
            }
        }
        fatmgrSetInlineBytes(entry, newContents)
    }

    private fun sortDirectoryCluster(clusternum: Int) {
        ARCHIVE.seekToCluster(clusternum, FILE_BLOCK_OFFSET_CONTENT_LEN)
        val contentsSize = ARCHIVE.readUshortBig()

        dbgprintln("[Clusternum.sortDirectory] cluster ${clusternum.toHex()} contents size: $contentsSize")

        val newContents = ARCHIVE.read(contentsSize).chunked(3).map { it.toInt24() }.also {
            dbgprintln("[Clustered.sortDirectoryCluster] trying to sort [${it.joinToString { it.toHex() }}]")
        }.sortedWith(fatComparator).let { ids ->
            ByteArray(ids.size * 3).also { ba ->
                ids.forEachIndexed { i, id -> ba.writeInt24(id, i * 3) }
            }
        }

//                println("SORTED = ${newContents.joinToString { it.toUint().toString(16).toUpperCase().padStart(2, '0') }}")

        ARCHIVE.seekToCluster(clusternum, FILE_BLOCK_HEADER_SIZE)
        ARCHIVE.write(newContents)
    }




    private fun RandomAccessFile.setClusterMeta1Flag(clusterNum: Long, mask: Int, flag: Int) {
        this.seekToCluster(clusterNum, 0)
        val existing = this.read()
        val newFlag = (existing xor mask) or flag
        this.seekToCluster(clusterNum, 0)
        this.write(newFlag)
    }
    private fun RandomAccessFile.setClusterMeta2Flag(clusterNum: Long, mask: Int, flag: Int) {
        this.seekToCluster(clusterNum, 1)
        val existing = this.read()
        val newFlag = (existing xor mask) or flag
        this.seekToCluster(clusterNum, 1)
        this.write(newFlag)
    }
    private fun RandomAccessFile.setClusterMeta1Flag(clusterNum: Int, mask: Int, flag: Int) = this.setClusterMeta1Flag(clusterNum.toLong(), mask, flag)
    private fun RandomAccessFile.setClusterMeta2Flag(clusterNum: Int, mask: Int, flag: Int) = this.setClusterMeta2Flag(clusterNum.toLong(), mask, flag)
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

    private fun compareArray(a: ByteArray, b: ByteArray): Int {
        if (a == b) { // if the two items are the same reference, they're identical
            return 0
        }

        // now the item-by-item comparison - the loop runs as long as items in both arrays are equal
        val last = Math.min(a.size, b.size)
        for (i in 0 until last) {
            val ai = a[i]
            val bi = b[i]
            val comp = ai.compareTo(bi)
            if (comp != 0) {
                return comp
            }
        }

        // shorter array whose items are all equal to the first items of a longer array is considered 'less than'
        if (a.size < b.size) {
            return -1 // "a < b"
        }
        else if (a.size > b.size) {
            return 1 // "a > b"
        }

        // i.e. (a.length == b.length)
        return 0 // "a = b", same length, all items equal
    }

    fun compareFilenameHash(s1: String, s2: String): Int {
        return compareArray(s1.toByteArray(charset), s2.toByteArray(charset))
    }

    val fatComparator = Comparator<Int> { o1, o2 ->
        val f1 = getFile(o1).also {
            if (it == null) throw NullPointerException("First file (ID=${o1.toHex()}) does not exist")
        }!!.filename
        val f2 = getFile(o2).also {
            if (it == null) throw NullPointerException("Second file (ID=${o2.toHex()}) does not exist")
        }!!.filename
        compareFilenameHash(f1, f2)
    }
    val filenameComparator = Comparator<String> { f1, f2 ->
        compareFilenameHash(f1, f2)
    }
}

fun ByteArray.chunked(size: Int): List<ByteArray> {
    return List((this.size + size - 1) / size) {
        this.sliceArray(size * it until minOf(size * (it + 1), this.size))
    }
}

fun RandomAccessFile.readBytes(buffer: ByteArray): Int {
    val readStatus = this.read(buffer)
    return readStatus
}
fun RandomAccessFile.readUshortBig(): Int {
    val buffer = ByteArray(2)
    val readStatus = readBytes(buffer)
    if (readStatus != 2) throw InternalError("Unexpected error -- EOF reached? (expected 2, got $readStatus)")
    return buffer.toInt16()
}
fun RandomAccessFile.readIntBig(): Int {
    val buffer = ByteArray(4)
    val readStatus = readBytes(buffer)
    if (readStatus != 4) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
    return buffer.toInt32()
}
fun RandomAccessFile.readInt48(): Long {
    val buffer = ByteArray(6)
    val readStatus = readBytes(buffer)
    if (readStatus != 6) throw InternalError("Unexpected error -- EOF reached? (expected 6, got $readStatus)")
    return buffer.toInt48()
}
fun RandomAccessFile.readInt24(): Int {
    val buffer = ByteArray(3)
    val readStatus = readBytes(buffer)
    if (readStatus != 3) throw InternalError("Unexpected error -- EOF reached? (expected 3, got $readStatus)")
    return buffer.toInt24()
}
fun RandomAccessFile.readInt64(): Long {
    val buffer = ByteArray(8)
    val readStatus = readBytes(buffer)
    if (readStatus != 8) throw InternalError("Unexpected error -- EOF reached? (expected 8, got $readStatus)")
    return buffer.toInt48()
}
fun RandomAccessFile.seekToCluster(clusterNum: Int) {
    this.seek(CLUSTER_SIZE * clusterNum.toLong())
}
fun RandomAccessFile.seekToCluster(clusterNum: Long) {
    this.seek(CLUSTER_SIZE * clusterNum)
}
fun RandomAccessFile.seekToCluster(clusterNum: Int, offset: Int) {
    this.seek(CLUSTER_SIZE * clusterNum.toLong() + offset)
}
fun RandomAccessFile.seekToCluster(clusterNum: Int, offset: Long) {
    this.seek(CLUSTER_SIZE * clusterNum.toLong() + offset)
}
fun RandomAccessFile.seekToCluster(clusterNum: Long, offset: Int) {
    this.seek(CLUSTER_SIZE * clusterNum + offset)
}
fun RandomAccessFile.seekToCluster(clusterNum: Long, offset: Long) {
    this.seek(CLUSTER_SIZE * clusterNum + offset)
}
fun RandomAccessFile.seekToFAT(index: Int, offset: Int = 0) {
    this.seek(2L * CLUSTER_SIZE + index * FAT_ENTRY_SIZE + offset)
}
fun RandomAccessFile.writeInt16(value: Int) {
    this.write(value.ushr(8))
    this.write(value.ushr(0))
}
fun RandomAccessFile.writeInt24(value: Int) {
    this.write(value.ushr(16))
    this.write(value.ushr(8))
    this.write(value.ushr(0))
}
fun RandomAccessFile.writeInt32(value: Int) {
    this.write(value.ushr(24))
    this.write(value.ushr(16))
    this.write(value.ushr(8))
    this.write(value.ushr(0))
}
fun RandomAccessFile.writeInt48(value: Long) {
    this.write(value.ushr(40).toInt())
    this.write(value.ushr(32).toInt())
    this.write(value.ushr(24).toInt())
    this.write(value.ushr(16).toInt())
    this.write(value.ushr( 8).toInt())
    this.write(value.ushr( 0).toInt())
}
fun RandomAccessFile.writeInt64(value: Long) {
    this.write(value.ushr(56).toInt())
    this.write(value.ushr(48).toInt())
    this.write(value.ushr(40).toInt())
    this.write(value.ushr(32).toInt())
    this.write(value.ushr(24).toInt())
    this.write(value.ushr(16).toInt())
    this.write(value.ushr( 8).toInt())
    this.write(value.ushr( 0).toInt())
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
