package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Created by minjaesong on 2023-04-21.
 */
open class Clustfile(private val DOM: ClusteredFormatDOM, absolutePath: String) {

    enum class FileType {
        Undefined, BinaryFile, Directory
    }

    private var FAT: ClusteredFormatDOM.FATEntry? = null

    private val charset = DOM.charset

    private val filename: String
    private val parentPath: String
    private val fullpath: String // without drive letter
    private val pathHierarchy: Array<ByteArray>
    private val filenameBytes: ByteArray
        get() = pathHierarchy.last()

    private var type: FileType = FileType.Undefined


    init {
        fullpath = absolutePath.replace('\\', '/').replace(Regex("//+"), "/").let {
            return@let if (it.startsWith('/')) it.substring(1) else it
        }
        val pathWords = fullpath.split('/')
        filename = pathWords.last()
        parentPath = pathWords.subList(0, pathWords.lastIndex).joinToString("/")
        pathHierarchy = pathWords.map { it.toByteArray(charset) }.toTypedArray()
    }


    open fun pread(buf: ByteArray, bufOffset: Int, count: Int, fileOffset: Int) {

    }

    open fun pread(buf: ByteBuffer, bufOffset: Int, count: Int, fileOffset: Int) {

    }

    open fun pread(unsafe: sun.misc.Unsafe, bufptr: Long, count: Int, fileOffset: Int) {

    }






    open fun pwrite(buf: ByteArray, bufOffset: Int, count: Int, fileOffset: Int) {

    }

    open fun pwrite(buf: ByteBuffer, bufOffset: Int, count: Int, fileOffset: Int) {

    }

    open fun pwrite(unsafe: sun.misc.Unsafe, bufptr: Long, count: Int, fileOffset: Int) {

    }



    open fun exists(): Boolean {

    }



    open fun renameTo(dest: Clustfile): Boolean {

    }

    open fun mkdirs(): Boolean {
        // create dirs if those parent dirs don't exist
    }

    open fun mkdir(): Boolean {
        if (Clustfile(DOM, parentPath).exists()) {
            type = FileType.Directory
            TODO("mkdir")
            return true
        }
        else {
            return false
        }
    }




    private fun filetypeToNum() = when (type) {
        FileType.Undefined -> 0
        FileType.BinaryFile -> 1
        FileType.Directory -> 2
    }

    open fun createNewFile() {
        if (FAT != null) {
            TODO("delete existing file")
        }

        // create new 0-byte file
        FAT = DOM.allocateFile(0, filetypeToNum(), filename)
        TODO("deal with parent directories (add cluster number to parent, create dirs if they don't exist)")
        mkdirs()
    }

}