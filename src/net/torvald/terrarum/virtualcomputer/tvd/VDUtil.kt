package net.torvald.terrarum.virtualcomputer.tvd

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import java.util.logging.Level
import javax.naming.OperationNotSupportedException

/**
 * Created by SKYHi14 on 2017-04-01.
 */
object VDUtil {

    fun dumpToRealMachine(disk: VirtualDisk, outfile: File) {
        if (!outfile.exists()) outfile.createNewFile()
        outfile.writeBytes(disk.serialize().array)
    }

    /**
     * Reads serialised binary and returns corresponding VirtualDisk instance.
     *
     * @param crcWarnLevel Level.OFF -- no warning, Level.WARNING -- print out warning, Level.SEVERE -- throw error
     */
    fun readDiskArchive(infile: File, crcWarnLevel: Level = Level.SEVERE): VirtualDisk {
        val inbytes = infile.readBytes()

        if (magicMismatch(VirtualDisk.MAGIC, inbytes))
            throw RuntimeException("Invalid Virtual Disk file!")

        val diskSize = inbytes.sliceArray(4..7).toIntBig()
        val diskName = inbytes.sliceArray(8..8 + 31)
        val diskCRC = inbytes.sliceArray(8 + 32..8 + 32 + 3).toIntBig() // to check with completed vdisk

        val vdisk = VirtualDisk(diskSize, diskName)

        println("[VDUtil] currentUnixtime = $currentUnixtime")

        var entryOffset = 44
        while (!Arrays.equals(inbytes.sliceArray(entryOffset..entryOffset + 1), VirtualDisk.FOOTER_START_MARK)) {
            println("[VDUtil] entryOffset = $entryOffset")
            // read and prepare all the shits
            val entryIndexNum = inbytes.sliceArray(entryOffset..entryOffset + 3).toIntBig()
            val entryTypeFlag = inbytes[entryOffset + 4]
            val entryFileName = inbytes.sliceArray(entryOffset + 5..entryOffset + 260)
            val entryCreationTime = inbytes.sliceArray(entryOffset + 261..entryOffset + 268).toLongBig()
            val entryModifyTime = inbytes.sliceArray(entryOffset + 269..entryOffset + 276).toLongBig()
            val entryCRC = inbytes.sliceArray(entryOffset + 277..entryOffset + 280).toIntBig() // to check with completed entry

            val entryData = when (entryTypeFlag) {
                DiskEntry.NORMAL_FILE -> {
                    val filesize = inbytes.sliceArray(entryOffset + 281..entryOffset + 284).toIntBig()
                    println("[VDUtil] --> is file; filesize = $filesize")
                    inbytes.sliceArray(entryOffset + 285..entryOffset + 284 + filesize)
                }
                DiskEntry.DIRECTORY   -> {
                    val entryCount = inbytes.sliceArray(entryOffset + 281..entryOffset + 282).toShortBig()
                    println("[VDUtil] --> is directory; entryCount = $entryCount")
                    inbytes.sliceArray(entryOffset + 283..entryOffset + 282 + entryCount * 4)
                }
                DiskEntry.SYMLINK     -> {
                    inbytes.sliceArray(entryOffset + 281..entryOffset + 284)
                }
                else -> throw RuntimeException("Unknown entry with type $entryTypeFlag")
            }



            // update entryOffset so that we can fetch next entry in the binary
            entryOffset += 281 + entryData.size + when (entryTypeFlag) {
                DiskEntry.NORMAL_FILE -> 4
                DiskEntry.DIRECTORY   -> 2
                DiskEntry.SYMLINK     -> 0
                else -> throw RuntimeException("Unknown entry with type $entryTypeFlag")
            }


            // create entry
            val diskEntry = DiskEntry(
                    indexNumber = entryIndexNum,
                    filename = entryFileName,
                    creationDate = entryCreationTime,
                    modificationDate = entryModifyTime,
                    contents = if (entryTypeFlag == DiskEntry.NORMAL_FILE) {
                        EntryFile(entryData)
                    }
                    else if (entryTypeFlag == DiskEntry.DIRECTORY) {
                        val entryList = ArrayList<IndexNumber>()
                        (0..entryData.size / 4 - 1).forEach {
                            entryList.add(entryData.sliceArray(4 * it..4 * it + 3).toIntBig())
                        }

                        EntryDirectory(entryList)
                    }
                    else if (entryTypeFlag == DiskEntry.SYMLINK) {
                        EntrySymlink(entryData.toIntBig())
                    }
                    else
                        throw RuntimeException("Unknown entry with type $entryTypeFlag")
            )

            // check CRC of entry
            if (crcWarnLevel == Level.SEVERE || crcWarnLevel == Level.WARNING) {
                val calculatedCRC = diskEntry.hashCode()

                val crcMsg = "$diskEntry CRC failed -- expected ${entryCRC.toHex()}, got ${calculatedCRC.toHex()}"

                if (calculatedCRC != entryCRC) {
                    if (crcWarnLevel == Level.SEVERE)
                        throw RuntimeException(crcMsg)
                    else
                        println("[VDUtil] $crcMsg")
                }
            }

            // add entry to disk
            vdisk.entries[entryIndexNum] = diskEntry
        }


        // check CRC of disk
        if (crcWarnLevel == Level.SEVERE || crcWarnLevel == Level.WARNING) {
            val calculatedCRC = vdisk.hashCode()

            val crcMsg = "$vdisk CRC failed -- expected ${diskCRC.toHex()}, got ${calculatedCRC.toHex()}"

            if (calculatedCRC != diskCRC) {
                if (crcWarnLevel == Level.SEVERE)
                    throw RuntimeException(crcMsg)
                else
                    println("[VDUtil] $crcMsg")
            }
        }

        return vdisk
    }


    /**
     * Get list of entries of directory.
     */
    fun getDirectoryEntries(disk: VirtualDisk, entry: DiskEntry): Array<DiskEntry> {
        if (entry.contents !is EntryDirectory)
            throw IllegalArgumentException("The entry is not directory")

        return Array<DiskEntry>(
                entry.contents.entries.size,
                { disk.entries[entry.contents.entries[it]]!! }
        )
    }
    /**
     * Get list of entries of directory.
     */
    fun getDirectoryEntries(disk: VirtualDisk, entryID: IndexNumber): Array<DiskEntry> {
        val entry = disk.entries[entryID]
        if (entry == null) {
            throw IOException("Entry does not exist")
        }
        else {
            return getDirectoryEntries(disk, entry)
        }
    }

    /**
     * Search a entry using path
     * @return Pair of <The file, Parent file>
     */
    fun getFile(disk: VirtualDisk, path: String): EntrySearchResult? {
        try {
            // TODO will path starts with(out) / cause quirky behaviours?

            val resolvedPath = path.sanitisePath().split('/')
            val hierarchy = ArrayList<Int>()
            hierarchy.add(0)
            var directory = getDirectoryEntries(disk, disk.entries[hierarchy.last()]!!)
            resolvedPath.forEachIndexed { index, pathName ->
                // if finalpath then...
                if (index == resolvedPath.lastIndex) {
                    return EntrySearchResult(
                            directory.searchForFilename(pathName)!!, // file to search
                            disk.entries[hierarchy.last()]!!         // parent directory
                    )
                }
                else {
                    val searchResult = directory.searchForFilename(pathName)!!
                    hierarchy.add(searchResult.indexNumber)
                    directory = getDirectoryEntries(disk, searchResult)
                }
            }
        }
        catch (noSuchFileException: KotlinNullPointerException) {
            throw KotlinNullPointerException("No such file")
        }
        catch (unexpectedNotADirectoryException: IllegalArgumentException) {
            throw IllegalArgumentException("No such directory")
        }

        return null
    }

    /**
     * SYNOPSIS  disk.getFile("bin/msh.lua")!!.file.getAsNormalFile(disk)
     *
     * Use VirtualDisk.getAsNormalFile(path)
     */
    private fun DiskEntry.getAsNormalFile(disk: VirtualDisk): EntryFile =
            this.contents as? EntryFile ?:
                    if (this.contents is EntryDirectory)
                        throw RuntimeException("this is directory")
                    else if (this.contents is EntrySymlink)
                        disk.entries[this.contents.target]!!.getAsNormalFile(disk)
                    else
                        throw RuntimeException("Unknown entry type")
    /**
     * SYNOPSIS  disk.getFile("bin/msh.lua")!!.first.getAsNormalFile(disk)
     *
     * Use VirtualDisk.getAsNormalFile(path)
     */
    private fun DiskEntry.getAsDirectory(disk: VirtualDisk): EntryDirectory =
            this.contents as? EntryDirectory ?:
                    if (this.contents is EntrySymlink)
                        disk.entries[this.contents.target]!!.getAsDirectory(disk)
                    else if (this.contents is EntryFile)
                        throw RuntimeException("this is not directory")
                    else
                        throw RuntimeException("Unknown entry type")

    /**
     * Search for the file and returns a instance of normal file.
     */
    fun getAsNormalFile(disk: VirtualDisk, path: String) =
            getFile(disk, path)!!.file.getAsNormalFile(disk)
    /**
     * Fetch the file and returns a instance of normal file.
     */
    fun getAsNormalFile(disk: VirtualDisk, entryIndex: IndexNumber) =
            disk.entries[entryIndex]!!.getAsNormalFile(disk)
    /**
     * Search for the file and returns a instance of directory.
     */
    fun getAsDirectory(disk: VirtualDisk, path: String) =
            getFile(disk, path)!!.file.getAsDirectory(disk)
    /**
     * Fetch the file and returns a instance of directory.
     */
    fun getAsDirectory(disk: VirtualDisk, entryIndex: IndexNumber) =
            disk.entries[entryIndex]!!.getAsDirectory(disk)
    /**
     * Deletes file on the disk safely.
     */
    fun deleteFile(disk: VirtualDisk, path: String) {
        disk.checkReadOnly()

        if (path.sanitisePath() == "/" || path.isEmpty())
            throw IOException("Cannot delete root file system")

        try {
            val file = getFile(disk, path)!!
            // delete file record
            disk.entries.remove(file.file.indexNumber)
            // unlist file from parent directory
            (file.parent.contents as EntryDirectory).entries.remove(file.file.indexNumber)
        }
        catch (e: KotlinNullPointerException) {
            throw FileNotFoundException("No such file")
        }
    }
    /**
     * Deletes file on the disk safely.
     */
    fun deleteFile(disk: VirtualDisk, parent: IndexNumber, target: IndexNumber) {
        disk.checkReadOnly()

        try {
            if (target != 0) {
                if (disk.entries[target]!!.contents is EntryDirectory &&
                        (disk.entries[target]!!.contents as EntryDirectory).entries.size > 0) {
                    throw IOException("Cannot delete directory that contains something")
                }

                // delete file record
                disk.entries.remove(target)
                // unlist file from parent directory
                (disk.entries[parent]!!.contents as EntryDirectory).entries.remove(target)
            }
            else {
                throw IOException("Cannot delete root file system")
            }
        }
        catch (e: KotlinNullPointerException) {
            throw FileNotFoundException("No such file")
        }
    }
    /**
     * Changes the name of the entry.
     */
    fun renameFile(disk: VirtualDisk, path: String, newName: String) {
        val file = getFile(disk, path)?.file

        if (file != null) {
            file.filename = newName.sanitisePath().toByteArray()
            file.modificationDate = currentUnixtime
        }
        else {
            throw FileNotFoundException()
        }
    }

    /**
     * Add file to the specified directory.
     */
    fun addFile(disk: VirtualDisk, parentPath: String, file: DiskEntry) {
        disk.checkReadOnly()
        disk.checkCapacity(file.size)

        try {
            // add record to the directory
            getAsDirectory(disk, parentPath).entries.add(file.indexNumber)
            // add entry on the disk
            disk.entries[file.indexNumber] = file
        }
        catch (e: KotlinNullPointerException) {
            throw FileNotFoundException("No such directory")
        }
    }
    /**
     * Add file to the specified directory.
     */
    fun addFile(disk: VirtualDisk, directoryID: IndexNumber, file: DiskEntry) {
        disk.checkReadOnly()
        disk.checkCapacity(file.size)

        try {
            // add record to the directory
            getAsDirectory(disk, directoryID).entries.add(file.indexNumber)
            // add entry on the disk
            disk.entries[file.indexNumber] = file
        }
        catch (e: KotlinNullPointerException) {
            throw FileNotFoundException("No such directory")
        }
    }
    /**
     * Add subdirectory to the specified directory.
     */
    fun addDir(disk: VirtualDisk, parentPath: String, name: String) {
        disk.checkReadOnly()
        disk.checkCapacity(EntryDirectory.NEW_ENTRY_SIZE)

        val newID = disk.generateUniqueID()

        try {
            // add record to the directory
            getAsDirectory(disk, parentPath).entries.add(newID)
            // add entry on the disk
            disk.entries[newID] = DiskEntry(
                    newID,
                    name.toByteArray(),
                    currentUnixtime,
                    currentUnixtime,
                    EntryDirectory()
            )
        }
        catch (e: KotlinNullPointerException) {
            throw FileNotFoundException("No such directory")
        }
    }
    /**
     * Add file to the specified directory.
     */
    fun addDir(disk: VirtualDisk, directoryID: IndexNumber, name: String) {
        disk.checkReadOnly()
        disk.checkCapacity(EntryDirectory.NEW_ENTRY_SIZE)

        val newID = disk.generateUniqueID()

        try {
            // add record to the directory
            getAsDirectory(disk, directoryID).entries.add(newID)
            // add entry on the disk
            disk.entries[newID] = DiskEntry(
                    newID,
                    name.toByteArray(),
                    currentUnixtime,
                    currentUnixtime,
                    EntryDirectory()
            )
        }
        catch (e: KotlinNullPointerException) {
            throw FileNotFoundException("No such directory")
        }
    }


    /**
     * Imports external file and returns corresponding DiskEntry.
     */
    fun importFile(file: File, id: IndexNumber): DiskEntry {
        if (file.isDirectory) {
            throw IOException("The file is a directory")
        }

        return DiskEntry(
                indexNumber = id,
                filename = file.name.toByteArray(),
                creationDate = currentUnixtime,
                modificationDate = currentUnixtime,
                contents = EntryFile(file.readBytes())
        )
    }
    /**
     * Export file on the virtual disk into real disk.
     */
    fun exportFile(entryFile: EntryFile, outfile: File) {
        outfile.createNewFile()
        outfile.writeBytes(entryFile.bytes)
    }

    /**
     * Check for name collision in specified directory.
     */
    fun nameExists(disk: VirtualDisk, name: String, directoryID: IndexNumber, charset: Charset = Charsets.UTF_8): Boolean {
        val name = name.toEntryName(256, charset)
        val directoryContents = getDirectoryEntries(disk, directoryID)
        directoryContents.forEach {
            if (Arrays.equals(name, it.filename))
                return true
        }
        return false
    }


    /**
     * Creates new disk with given name and capacity
     */
    fun createNewDisk(diskSize: Int, diskName: String, charset: Charset = Charsets.UTF_8): VirtualDisk {
        val newdisk = VirtualDisk(diskSize, diskName.toEntryName(32, charset))
        val rootDir = DiskEntry(
                indexNumber = 0,
                filename = DiskEntry.ROOTNAME.toByteArray(charset),
                creationDate = currentUnixtime,
                modificationDate = currentUnixtime,
                contents = EntryDirectory()
        )

        newdisk.entries[0] = rootDir

        return newdisk
    }
    /**
     * Creates new zero-filled file with given name and size
     */
    fun createNewBlankFile(disk: VirtualDisk, directoryID: IndexNumber, fileSize: Int, filename: String, charset: Charset = Charsets.UTF_8) {
        disk.checkReadOnly()
        disk.checkCapacity(fileSize + DiskEntry.HEADER_SIZE + 4)

        addFile(disk, directoryID, DiskEntry(
                disk.generateUniqueID(),
                filename.toEntryName(charset = charset),
                currentUnixtime,
                currentUnixtime,
                EntryFile(fileSize)
        ))
    }


    /**
     * Throws an exception if the disk is read-only
     */
    fun VirtualDisk.checkReadOnly() {
        if (this.isReadOnly)
            throw IOException("Disk is read-only")
    }
    /**
     * Throws an exception if specified size cannot fit into the disk
     */
    fun VirtualDisk.checkCapacity(newSize: Int) {
        if (this.usedBytes + newSize > this.capacity)
            throw IOException("Not enough space in the disk")
    }
    private fun Array<DiskEntry>.searchForFilename(name: String): DiskEntry? {
        this.forEach { if (String(it.filename) == name) return it }
        return null
    }
    fun ByteArray.toIntBig(): Int {
        if (this.size != 4)
            throw OperationNotSupportedException("ByteArray is not Int")

        var i = 0
        this.forEachIndexed { index, byte -> i += byte.toUint().shl(24 - index * 8)}
        return i
    }
    fun ByteArray.toLongBig(): Long {
        if (this.size != 8)
            throw OperationNotSupportedException("ByteArray is not Long")

        var i = 0L
        this.forEachIndexed { index, byte -> i += byte.toUint().shl(56 - index * 8)}
        return i
    }
    fun ByteArray.toShortBig(): Short {
        if (this.size != 2)
            throw OperationNotSupportedException("ByteArray is not Long")

        return (this[0].toUint().shl(256) + this[1].toUint()).toShort()
    }
    fun String.sanitisePath(): String {
        val invalidChars = Regex("""[<>:"|?*\u0000-\u001F]""")
        if (this.contains(invalidChars))
            throw IOException("path contains invalid characters")

        val path1 = this.replace('\\', '/')
        return path1
    }
    data class EntrySearchResult(val file: DiskEntry, val parent: DiskEntry)

    fun String.toEntryName(length: Int = 256, charset: Charset = Charsets.UTF_8): ByteArray {
        val buffer = AppendableByteBuffer(length)
        val stringByteArray = this.toByteArray(charset)
        buffer.put(stringByteArray.sliceArray(0..minOf(length, stringByteArray.size)))
        return buffer.array
    }

    fun resolveIfSymlink(disk: VirtualDisk, indexNumber: IndexNumber, recurse: Boolean = false): DiskEntry {
        var entry: DiskEntry? = disk.entries[indexNumber]
        if (entry == null) throw IOException("File does not exist")
        if (recurse) {
            while (entry!!.contents is EntrySymlink) {
                entry = disk.entries[(entry.contents as EntrySymlink).target]
                if (entry == null) break
            }
        }
        else {
            entry = disk.entries[(entry.contents as EntrySymlink).target]
        }
        if (entry == null) throw IOException("Pointing file does not exist")
        return entry
    }

    private val currentUnixtime: Long
        get() = System.currentTimeMillis() / 1000
}

fun Byte.toUint() = java.lang.Byte.toUnsignedInt(this)
fun magicMismatch(magic: ByteArray, array: ByteArray): Boolean {
    return !Arrays.equals(array.sliceArray(0..magic.lastIndex), magic)
}
