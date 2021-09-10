package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import java.io.*
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.HashMap
import kotlin.experimental.and

/**
 * Creates entry-to-offset tables to allow streaming from the disk, without storing whole VD file to the memory.
 *
 * Skimming is only useful for limited applications, reading/adding/removing one or two files occasionally.
 *
 * IO Operation using the skimmer has huge overheads for every operation. For large operations, use VDUtil to load the
 * entire disk onto the memory and modify the disk as much as you want, then export the changes as a new file.
 *
 * Created by minjaesong on 2017-11-17.
 */
class DiskSkimmer(private val diskFile: File, val charset: Charset = Charset.defaultCharset()) {


    /**
     * EntryID to Offset.
     *
     * Offset is where the header begins, so first 4 bytes are exactly the same as the EntryID.
     */
    private var entryToOffsetTable = HashMap<EntryID, Long>()
    private var footerPosition: Long = 0L

    private val footerSize: Int
        get() = (diskFile.length() - footerPosition).toInt()

    /** temporary storage to store tree edges */
    private var directoryStruct = ArrayList<DirectoryEdge>()

    /** root node of the directory tree */
    private var directory = DirectoryNode(0, null, DiskEntry.DIRECTORY, "")

    private data class DirectoryEdge(val nodeParent: EntryID, val node: EntryID, val type: Byte, val name: String)
    private data class DirectoryNode(var nodeThis: EntryID, val nodeParent: EntryID?, var type: Byte, var name: String)

    private val dirDelim = Regex("""[\\/]""")
    private val DIR = "/"

    val fis = FileInputStream(diskFile)
    val fc = fis.channel

    init {
        println("[DiskSkimmer] loading the diskfile ${diskFile.canonicalPath}")

        var currentPosition = fis.skip(47) // skip disk header


        fun skipRead(bytes: Long) {
            currentPosition += fis.skip(bytes)
        }
        /**
         * Reads a byte and adds up the position var
         */
        fun readByte(): Byte {
            currentPosition++
            val read = fis.read()

            if (read < 0) throw InternalError("Unexpectedly reached EOF")
            return read.toByte()
        }

        /**
         * Reads specific bytes to the buffer and adds up the position var
         */
        fun readBytes(buffer: ByteArray): Int {
            val readStatus = fis.read(buffer)
            currentPosition += readStatus
            return readStatus
        }
        fun readUshortBig(): Int {
            val buffer = ByteArray(2)
            val readStatus = readBytes(buffer)
            if (readStatus != 2) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
            return buffer.toShortBig()
        }
        fun readIntBig(): Int {
            val buffer = ByteArray(4)
            val readStatus = readBytes(buffer)
            if (readStatus != 4) throw InternalError("Unexpected error -- EOF reached? (expected 4, got $readStatus)")
            return buffer.toIntBig()
        }
        fun readInt48(): Long {
            val buffer = ByteArray(6)
            val readStatus = readBytes(buffer)
            if (readStatus != 6) throw InternalError("Unexpected error -- EOF reached? (expected 6, got $readStatus)")
            return buffer.toInt48()
        }
        fun readLongBig(): Long {
            val buffer = ByteArray(8)
            val readStatus = readBytes(buffer)
            if (readStatus != 8) throw InternalError("Unexpected error -- EOF reached? (expected 8, got $readStatus)")
            return buffer.toLongBig()
        }

        val currentLength = fc.size()
        while (currentPosition < currentLength) {
            val entryID = readLongBig() // at this point, cursor is 4 bytes past to the entry head


            // fill up the offset table
            entryToOffsetTable[entryID] = currentPosition - 4

            val parentID = readLongBig()
            val entryType = readByte()
            val nameBytes = ByteArray(256); readBytes(nameBytes) // read and store to the bytearray

            // fill up the tree's edges table
            directoryStruct.add(DirectoryEdge(parentID, entryID, entryType, nameBytes.toCanonicalString(charset)))

            skipRead(6 + 6 + 4) // skips rest of the header


            // figure out the entry size so that we can skip
            val entrySize: Long = when(entryType and 127) {
                0x01.toByte() -> readInt48()
                0x02.toByte() -> readUshortBig() * 4L
                0x03.toByte() -> 4 // symlink
                else -> throw InternalError("Unknown entry type: ${entryType.toUint()}")
            }


            skipRead(entrySize) // skips rest of the entry's actual contents

            println("[DiskSkimmer] successfully read the entry $entryID (name: ${nameBytes.toCanonicalString(charset)})")
        }


        // construct directory tree from the edges

        /*
         */

        val nodes = HashSet<DirectoryNode>()
        directoryStruct.forEach {
            // TODO
        }
    }

    //////////////////////////////////////////////////
    // THESE ARE METHODS TO SUPPORT ON-LINE READING //
    //////////////////////////////////////////////////

    /**
     * Using entryToOffsetTable, composes DiskEntry on the fly upon request.
     * @return DiskEntry if the entry exists on the disk, `null` otherwise.
     */
    fun requestFile(entryID: EntryID): DiskEntry? {
        entryToOffsetTable[entryID].let { offset ->
            if (offset == null) {
                println("[DiskSkimmer.requestFile] entry $entryID does not exist on the table")
                return null
            }
            else {
                val fis = FileInputStream(diskFile)
                fis.skip(offset + 8) // get to the EntryHeader's parent directory area
                val parent = fis.read(8).toLongBig()
                val fileFlag = fis.read(4)[0]
                val filename = fis.read(256)
                val creationTime = fis.read(6).toInt48()
                val modifyTime = fis.read(6).toInt48()
                val skip_crc = fis.read(4)

                // get entry size     // TODO future me, is this kind of comment helpful or redundant?
                val entrySize = when (fileFlag) {
                    DiskEntry.NORMAL_FILE -> {
                        fis.read(6).toInt48()
                    }
                    DiskEntry.DIRECTORY -> {
                        fis.read(4).toIntBig().toLong()
                    }
                    DiskEntry.SYMLINK -> 8L
                    else -> throw UnsupportedOperationException("Unsupported entry type: $fileFlag") // FIXME no support for compressed file
                }


                val entryContent = when (fileFlag) {
                    DiskEntry.NORMAL_FILE -> {
                        val byteArray = ByteArray64(entrySize)
                        // read one byte at a time
                        for (c in 0L until entrySize) {
                            byteArray[c] = fis.read().toByte()
                        }

                        EntryFile(byteArray)
                    }
                    DiskEntry.DIRECTORY -> {
                        val dirContents = ArrayList<EntryID>()
                        // read 8 bytes at a time
                        val bytesBuffer8 = ByteArray(8)
                        for (c in 0L until entrySize) {
                            fis.read(bytesBuffer8)
                            dirContents.add(bytesBuffer8.toLongBig())
                        }

                        EntryDirectory(dirContents)
                    }
                    DiskEntry.SYMLINK -> {
                        val target = fis.read(8).toLongBig()

                        EntrySymlink(target)
                    }
                    else -> throw UnsupportedOperationException("Unsupported entry type: $fileFlag") // FIXME no support for compressed file
                }

                return DiskEntry(entryID, parent, filename, creationTime, modifyTime, entryContent)
            }
        }
    }

    /**
     * Try to find a file with given path (which uses '/' as a separator). Is search is failed for whatever reason,
     * `null` is returned.
     *
     * @param path A path to the file from the root, directory separated with '/' (and not '\')
     * @return DiskEntry if the search was successful, `null` otherwise
     */
    fun requestFile(path: String): DiskEntry? {
        // fixme pretty much untested

        val path = path.split(dirDelim)
        //println(path)

        // bunch-of-io-access approach (for reading)
        var traversedDir = 0L // entry ID
        var dirFile: DiskEntry? = null
        path.forEachIndexed { index, dirName ->
            println("[DiskSkimmer.requestFile] $index\t$dirName, traversedDir = $traversedDir")

            dirFile = requestFile(traversedDir)
            if (dirFile == null) {
                println("[DiskSkimmer.requestFile] requestFile($traversedDir) came up null")
                return null
            } // outright null
            if (dirFile!!.contents !is EntryDirectory && index < path.lastIndex) { // unexpectedly encountered non-directory
                return null // because other than the last path, everything should be directory (think about it!)
            }
            //if (index == path.lastIndex) return dirFile // reached the end of the search strings

            // still got more paths behind to traverse
            var dirGotcha = false
            // loop for current dir contents
            (dirFile!!.contents as EntryDirectory).forEach {
                if (!dirGotcha) { // alternative impl of 'break' as it's not allowed
                    // get name of the file
                    val childDirFile = requestFile(it)!!
                    if (childDirFile.filename.toCanonicalString(charset) == dirName) {
                        //println("[DiskSkimmer] found, $traversedDir -> $it")
                        dirGotcha = true
                        traversedDir = it
                    }
                }
            }

            if (!dirGotcha) return null // got null || directory empty ||
        }

        return requestFile(traversedDir)
    }

    ///////////////////////////////////////////////////////
    // THESE ARE METHODS TO SUPPORT ON-LINE MODIFICATION //
    ///////////////////////////////////////////////////////

    fun appendEntry(entry: DiskEntry) {

    }

    fun deleteEntry(entry: EntryID) {

    }

    fun appendEntries(entries: List<DiskEntry>) = entries.forEach { appendEntry(it) }
    fun deleteEntries(entries: List<EntryID>) = entries.forEach { deleteEntry(it) }

    /**
     * Writes new clean file
     */
    fun sync() {
        // rebuild VirtualDisk out of this and use it to write out
    }



    companion object {
        fun InputStream.read(size: Int): ByteArray {
            val ba = ByteArray(size)
            this.read(ba)
            return ba
        }
    }

    /**
     * total size of the entry block. This size includes that of the header
     */
    private fun getEntryBlockSize(id: EntryID): Long? {
        val offset = entryToOffsetTable[id] ?: return null

        val HEADER_SIZE = DiskEntry.HEADER_SIZE

        println("[DiskSkimmer.getEntryBlockSize] offset for entry $id = $offset")

        val fis = FileInputStream(diskFile)
        fis.skip(offset + 8)
        val type = fis.read().toByte()
        fis.skip(272) // skip name, timestamp and CRC


        val ret: Long
        when (type) {
            DiskEntry.NORMAL_FILE -> {
                ret = fis.read(6).toInt48() + HEADER_SIZE + 6
            }
            DiskEntry.DIRECTORY -> {
                ret = fis.read(2).toShortBig() * 4 + HEADER_SIZE + 2
            }
            DiskEntry.SYMLINK -> { ret = 4 }
            else -> throw UnsupportedOperationException("Unknown type $type for entry $id")
        }

        fis.close()

        return ret
    }

    private fun byteByByteCopy(size: Long, `in`: InputStream, out: OutputStream) {
        for (i in 0L until size) {
            out.write(`in`.read())
        }
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

    private fun ByteArray.toLongBig(): Long {
        return  this[0].toUlong().shl(56) or
                this[1].toUlong().shl(48) or
                this[2].toUlong().shl(40) or
                this[3].toUlong().shl(32) or
                this[4].toUlong().shl(24) or
                this[5].toUlong().shl(16) or
                this[6].toUlong().shl(8) or
                this[7].toUlong()
    }
}