package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import java.nio.charset.Charset
import java.util.*
import java.util.zip.CRC32
import kotlin.experimental.and
import kotlin.experimental.or


typealias EntryID = Int

//val specversion = 0x03.toByte()

/**
 * This class provides DOM (disk object model) of the TEVD virtual filesystem.
 *
 * Created by minjaesong on 2017-03-31.
 */
class VirtualDisk(
        /** capacity of 0 makes the disk read-only */
        var capacity: Long,
        var diskName: ByteArray = ByteArray(NAME_LENGTH),
        var attribs: Byte = 0.toByte(),
        val extraAttribs: ByteArray = ByteArray(ATTRIBS_LENGTH)
) {
    val entries = HashMap<EntryID, DiskEntry>()
    var isReadOnly: Boolean
        set(value) { attribs = (attribs and 0xFE.toByte()) or value.toBit() }
        get() = capacity == 0L || ( attribs.and(1) == 1.toByte())
    fun getDiskNameString(charset: Charset) = diskName.toCanonicalString(charset)
    val root: DiskEntry
        get() = entries[0]!!


    private fun Boolean.toBit() = if (this) 1.toByte() else 0.toByte()

    private fun serializeEntriesOnly(): ByteArray64 {
        val buffer = ByteArray64()
        entries.forEach {
            val serialised = it.value.serialize()
            buffer.appendBytes(serialised)
        }

        return buffer
    }

    @Deprecated("TEVD now supports multiple archival format. Please use Please use net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ArchiverFactory", ReplaceWith("net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ArchiverFactory.getDomArchiver(\"format3\").serializeToBA64(this)"))
    fun serialize(): ByteArray64 {
        val entriesBuffer = serializeEntriesOnly()
        val buffer = ByteArray64(HEADER_SIZE + entriesBuffer.size)
        val crc = hashCode().toInt32Arr()

        buffer.appendBytes(MAGIC)
        buffer.appendBytes(capacity.toInt48Arr())
        buffer.appendBytes(diskName.forceSize(NAME_LENGTH))
        buffer.appendBytes(crc)
        buffer.appendByte(0x03)
        buffer.appendByte(attribs)
        buffer.appendBytes(extraAttribs.forceSize(ATTRIBS_LENGTH))
        buffer.appendBytes(entriesBuffer)

        return buffer

        //throw UnsupportedOperationException("Please use net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ArchiverFactory")
    }

    override fun hashCode(): Int {
        val crcList = IntArray(entries.size)
        var crcListAppendCursor = 0
        entries.forEach { _, u ->
            crcList[crcListAppendCursor] = u.hashCode()
            crcListAppendCursor++
        }
        crcList.sort()
        val crc = CRC32()
        crcList.forEach { crc.update(it) }

        return crc.value.toInt()
    }

    /** Expected size of the virtual disk */
    val usedBytes: Long
        get() = entries.map { it.value.serialisedSize }.sum() + HEADER_SIZE

    fun generateUniqueID(): Int {
        var id: Int
        do {
            id = Random().nextInt()
        } while (null != entries[id])
        return id
    }

    override fun equals(other: Any?) = if (other == null) false else this.hashCode() == other.hashCode()
    override fun toString() = "VirtualDisk(name: ${getDiskNameString(Charsets.UTF_8)}, capacity: $capacity bytes, crc: ${hashCode().toHex()})"

    companion object {
        val HEADER_SIZE = 64L // according to the spec
        val NAME_LENGTH = 32
        val ATTRIBS_LENGTH = 16

        val MAGIC = "TEVd".toByteArray()
    }
}


class DiskEntry(
        // header
        var entryID: EntryID,
        var parentEntryID: EntryID,
        var filename: ByteArray = ByteArray(NAME_LENGTH),
        var creationDate: Long,
        var modificationDate: Long,

        // content
        val contents: DiskEntryContent
): Comparable<DiskEntry> {
    override fun compareTo(other: DiskEntry) = entryID.compareTo(other.entryID)

    fun getFilenameString(charset: Charset) = if (entryID == 0) ROOTNAME else filename.toCanonicalString(charset)

    val serialisedSize: Long
        get() = contents.getSizeEntry() + HEADER_SIZE

    val typeString: String
        get() = DiskEntry.getTypeString(this.contents)

    companion object {
        val HEADER_SIZE = 281L // according to the spec
        val ROOTNAME = "."
        val NAME_LENGTH  = 256

        val NORMAL_FILE = 1.toByte()
        val DIRECTORY =   2.toByte()
        val SYMLINK =     3.toByte()

        private fun DiskEntryContent.getTypeFlag() =
                if      (this is EntryFile)      NORMAL_FILE
                else if (this is EntryDirectory) DIRECTORY
                else if (this is EntrySymlink)   SYMLINK
                else 0 // NULL

        fun getTypeString(entry: DiskEntryContent) = when(entry.getTypeFlag()) {
            NORMAL_FILE -> "File"
            DIRECTORY   -> "Directory"
            SYMLINK     -> "Symbolic Link"
            else        -> "(unknown type)"
        }
    }

    fun serialize(): ByteArray64 {
        val serialisedContents = contents.serialize()
        val buffer = ByteArray64(HEADER_SIZE + serialisedContents.size)

        buffer.appendBytes(entryID.toInt32Arr())
        buffer.appendBytes(parentEntryID.toInt32Arr())
        buffer.appendByte(contents.getTypeFlag())
        buffer.appendBytes(filename.forceSize(NAME_LENGTH))
        buffer.appendBytes(creationDate.toInt48Arr())
        buffer.appendBytes(modificationDate.toInt48Arr())
        buffer.appendBytes(this.hashCode().toInt32Arr())
        buffer.appendBytes(serialisedContents)

        return buffer
    }

    override fun hashCode() = contents.serialize().getCRC32()

    override fun equals(other: Any?) = if (other == null) false else this.hashCode() == other.hashCode()

    override fun toString() = "DiskEntry(name: ${getFilenameString(Charsets.UTF_8)}, ID: $entryID, parent: $parentEntryID, type: ${contents.getTypeFlag()}, contents size: ${contents.getSizeEntry()}, crc: ${hashCode().toHex()})"
}


fun ByteArray.forceSize(size: Int): ByteArray {
    return ByteArray(size, { if (it < this.size) this[it] else 0.toByte() })
}
interface DiskEntryContent {
    fun serialize(): ByteArray64
    fun getSizePure(): Long
    fun getSizeEntry(): Long
    fun getContent(): Any
    fun replaceContent(obj: Any)
}

/**
 * Do not retrieve bytes directly from this! Use VDUtil.retrieveFile(DiskEntry)
 * And besides, the bytes could be compressed.
 */
open class EntryFile(internal val bytes: ByteArray64) : DiskEntryContent {

    override fun getSizePure() = bytes.size
    override fun getSizeEntry() = getSizePure() + 6

    /** Create new blank file */
    constructor(size: Long): this(ByteArray64(size))

    override fun serialize(): ByteArray64 {
        val buffer = ByteArray64(getSizeEntry())
        buffer.appendBytes(getSizePure().toInt48Arr())
        buffer.appendBytes(bytes)
        return buffer
    }

    override fun getContent() = bytes

    override fun replaceContent(obj: Any) {
        bytes.clear()
        if (obj is ByteArray)
            bytes.appendBytes(obj)
        else if (obj is ByteArray64)
            bytes.appendBytes(obj)
        else
            throw IllegalArgumentException("Unknown object type '${obj.javaClass.canonicalName}'")
    }
}
/*class EntryFileCompressed(internal var uncompressedSize: Long, bytes: ByteArray64) : EntryFile(bytes) {

    override fun getSizePure() = bytes.size
    override fun getSizeEntry() = getSizePure() + 12

    /* No new blank file for the compressed */

    override fun serialize(): ByteArray64 {
        val buffer = ByteArray64(getSizeEntry())
        buffer.put(getSizePure().toInt48())
        buffer.put(uncompressedSize.toInt48())
        buffer.put(bytes)
        return buffer
    }

    fun decompress(): ByteArray64 {
        val unzipdBytes = ByteArray64()
        val zi = GZIPInputStream(ByteArray64InputStream(bytes))
        while (true) {
            val byte = zi.read()
            if (byte == -1) break
            unzipdBytes.add(byte.toByte())
        }
        zi.close()
        return unzipdBytes
    }
}*/
class EntryDirectory(private val entries: ArrayList<EntryID> = ArrayList<EntryID>()) : DiskEntryContent {

    override fun getSizePure() = entries.size * 4L
    override fun getSizeEntry() = getSizePure() + 2
    private fun checkCapacity(toAdd: Int = 1) {
        if (entries.size + toAdd > 65535)
            throw VDIOException("Directory entries limit exceeded.")
    }

    fun add(entryID: EntryID) {
        checkCapacity()
        entries.add(entryID)
    }

    fun remove(entryID: EntryID) {
        entries.remove(entryID)
    }

    fun contains(entryID: EntryID) = entries.contains(entryID)

    fun forEach(consumer: (EntryID) -> Unit) = entries.forEach(consumer)

    val entryCount: Int
        get() = entries.size

    override fun serialize(): ByteArray64 {
        val buffer = ByteArray64(getSizeEntry())
        buffer.appendBytes(entries.size.toShort().toBigEndian())
        entries.forEach { indexNumber -> buffer.appendBytes(indexNumber.toInt32Arr()) }
        return buffer
    }

    override fun getContent() = entries.toIntArray()

    override fun replaceContent(obj: Any) {
        entries.clear()
        if (obj is Collection<*> && obj.first() is EntryID) {
            entries.addAll(obj as Collection<EntryID>)
        }
        else {
            throw IllegalArgumentException("Unknown object type '${obj.javaClass.canonicalName}'")
        }
    }

    companion object {
        val NEW_ENTRY_SIZE = DiskEntry.HEADER_SIZE + 4L
    }
}
class EntrySymlink(_target: EntryID) : DiskEntryContent {

    var target: EntryID = _target
        internal set

    override fun getSizePure() = 4L
    override fun getSizeEntry() = 4L

    override fun serialize(): ByteArray64 {
        val buffer = ByteArray64(4)
        buffer.appendBytes(target.toInt32Arr())
        return buffer
    }

    override fun getContent() = target

    override fun replaceContent(obj: Any) {
        if (obj is EntryID)
            target = obj
        else
            throw IllegalArgumentException("Unknown object type '${obj.javaClass.canonicalName}'")
    }
}


fun Int.toHex() = this.toLong().and(0xFFFFFFFF).toString(16).padStart(8, '0').toUpperCase()
fun Int.toInt24Arr(): ByteArray {
    return ByteArray(3) { this.ushr(16 - (8 * it)).toByte() }
}
fun Int.toInt32Arr(): ByteArray {
    return ByteArray(4) { this.ushr(24 - (8 * it)).toByte() }
}
fun Long.toInt48Arr(): ByteArray {
    return ByteArray(6) { this.ushr(40 - (8 * it)).toByte() }
}
fun Long.toInt64Arr(): ByteArray {
    return ByteArray(8) { this.ushr(64 - (8 * it)).toByte() }
}
fun Short.toBigEndian(): ByteArray {
    return byteArrayOf(
            this.div(256).toByte(),
            this.toByte()
    )
}
fun Boolean.toInt(shl: Int = 0) = if (this) 1.shl(shl) else 0

fun ByteArray64.getCRC32(): Int {
    val crc = CRC32()
    this.forEachInt32 { crc.update(it) }
    return crc.value.toInt()
}
