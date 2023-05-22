package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.ByteArray64
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDIOException
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.writeBytes64
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_BINARY
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_DIRECTORY
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toHex
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
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

    private val type: Int?
        get() = FAT?.fileType


    init {
        rebuildSelfPath(absolutePath)
        updateFATreference()
        updatePropertiesByHeadCluster()

        dbgprintln("[Clustfile] New file created, path: \"$fullpath\", pathHierarchy = [${pathHierarchy.joinToString(" > ", transform = { "\"$it\"" })}]")
        dbgprintln("[Clustfile] FAT: $FAT")
    }

    private fun getDirListing(dir: ClusteredFormatDOM.FATEntry): List<Int>? {
        if (DOM.getFileType(dir) != FILETYPE_DIRECTORY) return null
        dbgprintln("[Clustfile.getDirListing] filelen?")
        val filelen = DOM.getFileLength(dir)
        dbgprintln("[Clustfile.getDirListing] filelen = $filelen; readBytes?")
        val ba = DOM.readBytes(dir, filelen, 0)
        dbgprintln("[Clustfile.getDirListing] ${ba.size} bytes read")
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

        parentFAT = searchForFAT(pathHierarchy.init())

        dbgprintln("[Clustfile.updateFATreference] pathHierarchy = [${pathHierarchy.joinToString(" > ", transform = { "\"$it\"" })}]")
        FAT = searchForFAT(pathHierarchy)
        dbgprintln("[Clustfile.updateFATreference] new FAT = $FAT")
    }

    private fun updatePropertiesByHeadCluster() {

    }

    private fun searchForFAT(pathHierarchy: Array<String>): ClusteredFormatDOM.FATEntry? {
        dbgprintln("[Clustfile.searchForFAT] file path: [${pathHierarchy.joinToString(" > ", transform = { "\"$it\"" })}]")
        var currentDir: ClusteredFormatDOM.FATEntry? = DOM.getRootDir()
        for (dirName in pathHierarchy) {
            dbgprintln("[Clustfile.searchForFAT] dirName = $dirName")

            if (currentDir == null) {
                dbgprintln("[Clustfile.searchForFAT]     currentDir == null, breaking")
                break
            }

            dbgprintln("[Clustfile.searchForFAT]     getDirListing of ID ${currentDir.entryID.toHex()}...")

            val dirListing = getDirListing(currentDir)

            dbgprintln("[Clustfile.searchForFAT]     ls: [${dirListing?.joinToString { it.toHex() }}]")

            if (dirListing != null) {
                var found = false
                for (entryID in dirListing) {
                    val dirFile = DOM.getFile(entryID)

                    dbgprintln("[Clustfile.searchForFAT]     entryID = ${entryID.toHex()}; dirName $dirName ?= ${DOM.getFile(entryID)?.filename}")

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

        dbgprintln("[Clustfile.searchForFAT] end of search; currentDir = $currentDir")

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

    open fun readBytes(): ByteArray = ClustfileInputStream(this).readAllBytes()





    open fun pwrite(buf: ByteArray, bufOffset: Int, count: Int, fileOffset: Int): Boolean {
        if (!exists()) createNewFile()
        return if (FAT == null || !canWrite()) false
        else {
            require(type == FILETYPE_BINARY)
            try {
                DOM.writeBytes(FAT!!, buf, bufOffset, count, fileOffset)
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
                DOM.setFileLength(FAT!!, buf.size)
                DOM.writeBytes(FAT!!, buf, 0, buf.size, 0)
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
        return if (type == FILETYPE_BINARY && FAT != null) {
            try {
                DOM.setFileLength(FAT!!, 0)
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
    open fun isDirectory() = (exists() && type == FILETYPE_DIRECTORY)
    open fun isFile() = (exists() && type == FILETYPE_BINARY)
    open fun isHidden() = (exists() && FAT!!.hidden)
    open fun lastModified() = FAT?.modificationDate ?: 0L
    open fun length() = if (exists()) DOM.getFileLength(FAT!!).toLong() else 0L

    /**
     * If the file is not directory, null will be returned to comply with the Behaviour of the Java 17
     */
    open fun list(nameFilter: (String) -> Boolean = { true }): Array<String>? {
        if (!exists()) return arrayOf<String>()
        return getDirListing(this.FAT!!)?.map { id -> DOM.getFile(id)!! }?.filter { fat -> !fat.deleted }?.map { fat ->
            "${this.fullpath}/${fat.filename}" }?.filter(nameFilter)?.toTypedArray()
    }
    /**
     * If the file is not directory, null will be returned to comply with the Behaviour of the Java 17
     */
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

        FAT!!.let { FAT ->
            DOM.commitFATchangeToDisk(FAT)
        }
        return true
    }

    open fun addChild(file: Clustfile): Boolean {
        require(type == FILETYPE_DIRECTORY)

        return continueIfTrue {
            // write the child
            if (FAT == null) false // run initDir() to create the file
            else {
                val dirListing = getDirListing(FAT!!)!!
                // if the entry is not already there, write one
                if (!dirListing.contains(file.FAT!!.entryID)) {
                    val defaultDirs = ByteArray(3)
                    defaultDirs.writeInt24(file.FAT!!.entryID, 0)
                    DOM.writeBytes(FAT!!, defaultDirs, 0, 3, dirListing.size * 3)
                    updateFATreference(); DOM.commitFATchangeToDisk(FAT!!)
                }

                true
            }
        }
    }

    open fun removeChild(file: Clustfile): Boolean {
        require(type == FILETYPE_DIRECTORY)


        return continueIfTrue {
            val dirListing = getDirListing(FAT!!)!!
            val fileEntryID = file.FAT!!.entryID
            if (dirListing.contains(fileEntryID)) {
                continueIfTrue {
                    val newBytes = ByteArray((dirListing.size - 1) * 3)
                    dirListing.filter { it != fileEntryID }.forEachIndexed { index, id -> newBytes.writeInt24(id, index * 3) }
                    DOM.setFileLength(FAT!!, newBytes.size)
                    DOM.writeBytes(FAT!!, newBytes, 0, newBytes.size, 0)
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
            mkfat(FILETYPE_DIRECTORY)

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






    private fun mkfat(fileType: Int) {
        if (FAT != null) throw IllegalStateException()
        try {
            FAT = DOM.allocateFile(0, fileType, filename)
        }
        catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    /**
     * Creates new binary file. To create a new directory, call [mkdir]
     */
    open fun createNewFile(): Boolean {
        if (this.exists()) return false

        // create new 0-byte file
        try {
            mkfat(FILETYPE_BINARY)

            dbgprintln("[Clustfile.createNewFile] getParentFile")

            val parentFile = getParentFile()

            dbgprintln("[Clustfile.createNewFile] parent path: $parentPath")
            dbgprintln("[Clustfile.createNewFile] parent FAT: ${parentFile.FAT}")

            return continueIfTrue { dbgprintln("[Clustfile.createNewFile] trying mkdirs..."); parentFile.mkdirs(); true } // create parent directories
                  .continueIfTrue { dbgprintln("[Clustfile.createNewFile] trying addChild..."); parentFile.addChild(this) }
        }
        catch (e: VDIOException) {
            e.printStackTrace()
            return false
        }
    }

    open fun delete(): Boolean {
        if (!this.exists()) return false
        DOM.discardFile(FAT!!)
        return true
    }


    /**
     * Exports this file or directory to the host filesystem. If this file is directory, all of its contents will be exported recursively.
     */
    open fun exportTo(otherFile: File): Boolean {
        if (!this.exists()) return false
        return if (this.isFile()) exportFileTo(otherFile)
        else if (this.isDirectory()) exportDirTo(otherFile)
        else return false
    }

    private fun exportFileTo(otherFile: File): Boolean {
        return otherFile.createNewFile().continueIfTrue {
            otherFile.writeBytes(readBytes())
            true
        }
    }
    private fun exportDirTo(otherFile: File): Boolean {
        fun recurse1(file: Clustfile, dir: File) {
            // return conditions
            if (file.isFile()) {
                // if not a directory, write as file
                val newFile = File(dir, file.getName())
                newFile.writeBytes(file.readBytes())
                return
            }
            // recurse
            else if (file.isDirectory()) {
                // mkdir
                val newDir = File(dir, file.getName())
                newDir.mkdir()
                // for entries in this fileDirectory...
                file.listFiles()!!.forEach {
                    recurse1(it, newDir)
                }
            }
            else throw InternalError("File is neither a binary nor a directory (filetype=${this.type})")
        }


        // mkdir to superNode
        val newDir = File(otherFile, this.getName())
        return newDir.mkdir().continueIfTrue {
            // for entries in this fileDirectory...
            this.listFiles()!!.forEach { recurse1(it, newDir) }
            true
        }
    }



    private fun continueIfTrue(action: () -> Boolean): Boolean {
        return action()
    }
    private fun Boolean.continueIfTrue(action: () -> Boolean): Boolean {
        if (this) return action()
        else return false
    }
}