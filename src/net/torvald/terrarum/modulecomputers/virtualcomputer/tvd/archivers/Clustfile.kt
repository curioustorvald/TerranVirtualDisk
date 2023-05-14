package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDIOException
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.EXTENDED_ENTRIES_BASE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_BINARY
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_DIRECTORY
import java.nio.ByteBuffer



/**
 * Created by minjaesong on 2023-04-21.
 */
open class Clustfile(private val DOM: ClusteredFormatDOM, absolutePath: String) {

    private inline fun dbgprint(msg: Any? = "") {
        print(msg)
    }

    private inline fun dbgprintln(msg: Any? = "") {
        println(msg)
    }

    private fun <T> Array<T>.tail() = this.sliceArray(1 until this.size)
    private fun <T> Array<T>.init() = this.sliceArray(0 until this.lastIndex)
    private fun <T> List<T>.tail() = this.subList(1, this.size)
    private fun <T> List<T>.init() = this.subList(0, this.lastIndex)

    enum class FileType {
        Undefined, BinaryFile, Directory
    }

    internal var FAT: ClusteredFormatDOM.FATEntry? = null
    internal var parentFAT: ClusteredFormatDOM.FATEntry? = null

    private var charset = DOM.charset

    private lateinit var filename: String
    private lateinit var parentPath: String
    private lateinit var fullpath: String // without drive letter
    private lateinit var pathHierarchy: Array<String>
    private lateinit var pathHierarchyBytes: Array<ByteArray>
    private val filenameBytes: ByteArray
        get() = pathHierarchyBytes.last()

    private lateinit var type: FileType


    init {
        rebuildSelfPath(absolutePath)
        updateFATreference()
        updatePropertiesByHeadCluster()

        dbgprintln("[Clustfile] New file created, path: \"$fullpath\", pathHierarchy = [${pathHierarchy.joinToString(" > ", transform = { "\"$it\"" })}]")
        dbgprintln("[Clustfile] FAT: $FAT")
    }

    private fun getDirListing(dir: ClusteredFormatDOM.FATEntry): List<Int>? {
        if (DOM.getFileType(dir) != FILETYPE_DIRECTORY) return null
        val ba = DOM.readBytes(dir, DOM.getFileLength(dir), 0)
        if (ba.size % 3 != 0) throw IllegalStateException("Length of dir not multiple of 3")
        return List(ba.size / 3) { ba.toInt24(it * 3) }
    }

    private fun rebuildSelfPath(absolutePath: String) {
        fullpath = absolutePath.replace('\\', '/').replace(Regex("//+"), "/").let {
            (if (it.startsWith('/')) it.drop(1) else it).let {
                if (it.endsWith('/')) it.dropLast(1) else it
            }
        }
        val pathWords = fullpath.split('/')
        filename = pathWords.last()
        parentPath = pathWords.init().joinToString("/")
        pathHierarchyBytes = pathWords.map { it.toByteArray(charset) }.toTypedArray()
        pathHierarchy = pathHierarchyBytes.map { it.toString(charset) }.toTypedArray()

        if (pathHierarchy.size == 1 && pathHierarchy[0].isEmpty()) {
            pathHierarchy = emptyArray()
            pathHierarchyBytes = emptyArray()
        }
    }

    private fun updateFATreference() {
        FAT = searchForFAT(pathHierarchy)
        parentFAT = searchForFAT(pathHierarchy.init())
    }

    private fun updatePropertiesByHeadCluster() {
        if (FAT != null) {
            if (FAT!!.entryID < EXTENDED_ENTRIES_BASE) {
                type = numToFileType(DOM.getFileType(FAT!!))
            }
            else {
                type = if (FAT!!.isInlineDirectory) FileType.Directory else FileType.BinaryFile
            }
        }
        else {
            type = FileType.Undefined
        }
    }

    private fun searchForFAT(pathHierarchy: Array<String>): ClusteredFormatDOM.FATEntry? {
        dbgprintln("[Clustfile.searchForFAT] file path: [${pathHierarchy.joinToString(" > ", transform = { "\"$it\"" })}]")
        var currentDir: ClusteredFormatDOM.FATEntry? = DOM.getRootDir()
        for (dirName in pathHierarchy) {
            dbgprintln("[Clustfile.searchForFAT] dirName = $dirName")

            if (currentDir == null) break

            val dirListing = getDirListing(currentDir)

            dbgprintln("[Clustfile.searchForFAT] ls: ${dirListing?.joinToString()}")

            if (dirListing != null) {
                var found = false
                for (entryID in dirListing) {
                    val dirFile = DOM.getFile(entryID)

                    dbgprintln("[Clustfile.searchForFAT] entryID = $entryID; dirName $dirName ?= ${DOM.getFile(entryID)?.filename}")

                    if (dirFile != null && dirName == DOM.getFile(entryID)?.filename) {
                        currentDir = dirFile
                        found = true
                        break
                    }
                }
                if (!found) {
                    currentDir = null
                }
            }
            else {
                currentDir = null
            }

        }

        return currentDir
    }




    open fun pread(buf: ByteArray, bufOffset: Int, count: Int, fileOffset: Int): Int {
        return if (FAT == null) -1
        else DOM.readBytes(FAT!!, buf, bufOffset, count, fileOffset)
    }

    open fun pread(buf: ByteBuffer, bufOffset: Int, count: Int, fileOffset: Int): Int {
        return if (FAT == null) -1
        else {
            val bbuf = ByteArray(count)
            val readCount = DOM.readBytes(FAT!!, bbuf, 0, count, fileOffset)
            buf.put(bbuf, bufOffset, readCount)
            readCount
        }
    }

    open fun pread(unsafe: sun.misc.Unsafe, bufptr: Long, count: Int, fileOffset: Int): Int {
        return if (FAT == null) -1
        else {
            val bbuf = ByteArray(count)
            val readCount = DOM.readBytes(FAT!!, bbuf, 0, count, fileOffset)
            unsafe.copyMemory(bbuf, unsafe.arrayBaseOffset(bbuf.javaClass).toLong(), null, bufptr, readCount.toLong())
            readCount
        }
    }





    open fun pwrite(buf: ByteArray, bufOffset: Int, count: Int, fileOffset: Int): Boolean {
        return if (FAT == null || !canWrite()) false
        else {
            require(type == FileType.BinaryFile)
            try {
                DOM.writeBytes(FAT!!, buf, bufOffset, count, fileOffset, FILETYPE_BINARY)
                updateFATreference()
                dbgprintln("[Clustfile.pwrite] FAT update: $FAT")
                DOM.commitFATchangeToDisk(FAT!!)
                true
            }
            catch (e: Throwable) {
                e.printStackTrace()
                false
            }
        }
    }

    open fun pwrite(buf: ByteBuffer, bufOffset: Int, count: Int, fileOffset: Int): Boolean {
        return if (FAT == null || !canWrite()) false
        else {
            val bbuf = ByteArray(count) { buf[bufOffset + it] }
            return pwrite(bbuf, 0, count, fileOffset)
        }
    }

    open fun pwrite(unsafe: sun.misc.Unsafe, bufptr: Long, count: Int, fileOffset: Int): Boolean {
        return if (FAT == null || !canWrite()) false
        else {
            val bbuf = ByteArray(count)
            unsafe.copyMemory(null, bufptr, bbuf, unsafe.arrayBaseOffset(bbuf.javaClass).toLong(), count.toLong())
            pwrite(bbuf, 0, count, fileOffset)
        }
    }



    open fun overwrite(buf: ByteArray): Boolean {
        if (!exists()) createNewFile()
        return continueIfTrue {
            dbgprintln("[Clustfile.overwrite] FAT: $FAT")

            try {
                DOM.setFileLength(FAT!!, buf.size, filetypeToNum())
                DOM.writeBytes(FAT!!, buf, 0, buf.size, 0, filetypeToNum())
                updateFATreference(); DOM.commitFATchangeToDisk(FAT!!)
                true
            }
            catch (e: Throwable) {
                e.printStackTrace()
                false
            }
        }
    }

    open fun overwrite(unsafe: sun.misc.Unsafe, bufptr: Long, count: Int): Boolean {
        val ba = ByteArray(count)
        unsafe.copyMemory(null, bufptr, ba, unsafe.arrayBaseOffset(ba.javaClass).toLong(), count.toLong())
        return overwrite(ba)
    }

    /**
     * Sets the length of the file to zero. Binary file only
     */
    open fun clear(): Boolean {
        return if (type == FileType.BinaryFile && FAT != null) {
            try {
                DOM.setFileLength(FAT!!, 0, filetypeToNum())
                true
            }
            catch (e: Throwable) {
                e.printStackTrace()
                false
            }
        }
        else false
    }





    open fun getName() = filename
    open fun getParent() = parentPath
    open fun getParentFile() = Clustfile(DOM, parentPath)
    open fun getPath() = fullpath



    open fun canRead() = true
    open fun canWrite() = (FAT?.readOnly == false)
    open fun exists() = (FAT != null)
    open fun isDirectory() = (exists() && type == FileType.Directory)
    open fun isFile() = (exists() && type == FileType.BinaryFile)
    open fun isHidden() = (exists() && FAT!!.hidden)
    open fun lastModified() = FAT?.modificationDate ?: 0L
    open fun length() = if (exists()) DOM.getFileLength(FAT!!).toLong() else 0L
    open fun list(nameFilter: (String) -> Boolean = { true }): Array<String>? {
        if (!exists()) return arrayOf<String>()
        return getDirListing(this.FAT!!)?.map { id -> DOM.getFile(id)!! }?.filter { fat -> !fat.deleted }?.map { fat ->
            "${this.fullpath}/${fat.filename}" }?.filter(nameFilter)?.toTypedArray()
    }
    open fun listFiles(nameFilter: (String) -> Boolean = { true }): Array<Clustfile>? {
        if (!exists()) return arrayOf<Clustfile>()
        return getDirListing(this.FAT!!)?.map { id -> DOM.getFile(id)!! }?.filter { fat -> !fat.deleted }?.mapNotNull { fat ->
            val fullFilePath = "${this.fullpath}/${fat.filename}"
            if (nameFilter(fullFilePath)) Clustfile(DOM, fullFilePath) else null
        }?.toTypedArray()
    }

    /**
     * Dir content initialiser for mkdir
     */
    private fun initDir(): Boolean {
        if (FAT == null) {
            try {
                FAT = DOM.allocateFile(0, FILETYPE_DIRECTORY, filename)
            }
            catch (e: VDIOException) {
                e.printStackTrace()
                return false
            }
        }

        type = FileType.Directory

        FAT!!.let { FAT ->
            DOM.commitFATchangeToDisk(FAT)
        }
        return true
    }

    open fun addChild(file: Clustfile): Boolean {
        require(type == FileType.Directory)

        if (FAT == null) return false // run initDir() to create the file

        return continueIfTrue {
            // write the child
            if (FAT == null) false
            else {
                val dirListing = getDirListing(FAT!!)!!
                // if the entry is not already there, write one
                if (!dirListing.contains(file.FAT!!.entryID)) {
                    val defaultDirs = ByteArray(3)
                    defaultDirs.writeInt24(file.FAT!!.entryID, 0)
                    DOM.writeBytes(FAT!!, defaultDirs, 0, 3, dirListing.size * 3, FILETYPE_DIRECTORY)
                    updateFATreference(); DOM.commitFATchangeToDisk(FAT!!)
                }

                true
            }
        }
    }

    open fun removeChild(file: Clustfile): Boolean {
        require(type != FileType.BinaryFile)


        return continueIfTrue {
            val dirListing = getDirListing(FAT!!)!!
            val fileEntryID = file.FAT!!.entryID
            if (dirListing.contains(fileEntryID)) {
                continueIfTrue {
                    val newBytes = ByteArray((dirListing.size - 1) * 3)
                    dirListing.filter { it != fileEntryID }.forEachIndexed { index, id -> newBytes.writeInt24(id, index * 3) }
                    DOM.setFileLength(FAT!!, newBytes.size, FILETYPE_DIRECTORY)
                    DOM.writeBytes(FAT!!, newBytes, 0, newBytes.size, 0, FILETYPE_DIRECTORY)
                    updateFATreference(); DOM.commitFATchangeToDisk(FAT!!)
                    true
                }
            }
            else true
        }
    }

    open fun renameTo(dest: Clustfile): Boolean {

        val destParent = Clustfile(DOM, dest.parentPath)
        val thisParent = Clustfile(DOM, parentPath)

        return continueIfTrue {
            if (!destParent.exists()) {
                destParent.mkdirs()
            }
            else true
        }.continueIfTrue { destParent.addChild(this) } // add self to parent dir; implies commitFATchangeToDisk(destParent.FAT)
         .continueIfTrue { thisParent.removeChild(this) } // remove self from parent dir; implies commitFATchangeToDisk(thisParent.FAT)
         .continueIfTrue {
             rebuildSelfPath(dest.fullpath)
             updateFATreference()
             true
         }
    }

    open fun mkdirs(): Boolean {
        // create dirs if those parent dirs don't exist
        return if (this.exists())
            false
        else if (this.mkdir())
            true
        else {
            val parent = getParentFile()
            dbgprintln("[Clustfile.mkdirs] this path = ${fullpath}")
            dbgprintln("[Clustfile.mkdirs] parent path = $parentPath")
            (parent.mkdirs() || parent.exists()) && this.mkdir()
        }
    }

    open fun mkdir(): Boolean {
        val parent = getParentFile()
        if (parent.exists()) {
            type = FileType.Directory

            return continueIfTrue { parent.addChild(this) } // implies commitFATchangeToDisk
                  .continueIfTrue { this.initDir() } // implies commitFATchangeToDisk
                  .continueIfTrue { updateFATreference(); true }
        }
        else {
            return false
        }
    }



    open fun setLastModified(time: Long): Boolean {
        FAT?.modificationDate = time
        return exists()
    }

    open fun setReadOnly(): Boolean {
        FAT?.readOnly = true
        return exists()
    }

    open fun setWritable(state: Boolean): Boolean {
        FAT?.readOnly = !state
        return exists()
    }

    open fun getTotalSpace() = DOM.totalSpace
    open fun getFreeSpace() = DOM.freeSpace
    open fun getUsedSpace() = DOM.usedSpace
    open fun getUsableSpace() = DOM.usableSpace





    private fun filetypeToNum() = when (type) {
        FileType.Undefined -> 0
        FileType.BinaryFile -> FILETYPE_BINARY
        FileType.Directory -> FILETYPE_DIRECTORY
    }
    private fun numToFileType(type: Int) = when (type) {
        0 -> FileType.Undefined
        FILETYPE_BINARY -> FileType.BinaryFile
        FILETYPE_DIRECTORY -> FileType.Directory
        else -> throw IllegalArgumentException()
    }

    /**
     * Creates new binary file. To create a new directory, call [mkdir]
     */
    open fun createNewFile(): Boolean {
        if (this.exists()) return false

        // create new 0-byte file
        try {
            FAT = DOM.allocateFile(0, FILETYPE_BINARY, filename)
            type = FileType.BinaryFile

            dbgprintln("[Clustfile.createNewFile] getParentFile")

            val parentFile = getParentFile()

            dbgprintln("[Clustfile.createNewFile] parent path: $parentPath")
            dbgprintln("[Clustfile.createNewFile] parent FAT: ${parentFile.FAT}")

            return continueIfTrue { dbgprintln("[Clustfile.createNewFile] trying mkdirs..."); parentFile.mkdirs(); true } // create parent directories
                  .continueIfTrue { dbgprintln("[Clustfile.createNewFile] trying addChild..."); parentFile.addChild(this) }
        }
        catch (e: VDIOException) {
            e.printStackTrace()
            type = FileType.Undefined
            return false
        }
    }

    open fun delete(): Boolean {
        if (!this.exists()) return false
        DOM.discardFile(FAT!!.entryID)
        return true
    }


    private fun continueIfTrue(action: () -> Boolean): Boolean {
        return action()
    }
    private fun Boolean.continueIfTrue(action: () -> Boolean): Boolean {
        if (this) return action()
        else return false
    }
}