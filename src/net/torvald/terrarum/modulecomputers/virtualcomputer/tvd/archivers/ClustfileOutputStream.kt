package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.ByteArray64
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_BINARY
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toUint
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * The target file will be immediately modified with the operation. IF APPEND=FALSE AND ANY OF THE WRITING OPERATION IS
 * PERFORMED, THE TARGET FILE WILL BE WIPED CLEAN.
 *
 * @param DOM disk object model
 * @param file a file to write to
 * @param append if `false`, initial write operation will discard the existing content of the file; if `true`, initial
 * write will be done right after the existing bytes. Any subsequent operation from this Outputstream will append the
 * existing bytes.
 * @throws IllegalStateException if the file is directory
 *
 * Created by minjaesong on 2023-05-13.
 */
class ClustfileOutputStream(private val file: Clustfile, val append: Boolean = false) : OutputStream() {

    private var cursor = if (append) file.length() else 0L

    init {
        if (file.isDirectory) throw IllegalStateException()
    }

    override fun write(p0: Int) {
        write(byteArrayOf(p0.toByte()))
    }

    override fun write(b: ByteArray) {
        if (!file.exists()) file.createNewFile()
        file.pwrite(b, 0, b.size, cursor)
        cursor += b.size
        file.setFileLength(cursor)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!file.exists()) file.createNewFile()
        file.pwrite(b, off, len, cursor)
        cursor += len
        file.setFileLength(cursor)
    }
}

class ClustfileInputStream(private val file: Clustfile) : InputStream() {
    private var cursor = 0
    private var fileLength = file.length()
    private var markLimit = 0
    private var markPos = 0

    override fun skip(n: Long): Long {
        val n1 = (if (cursor + n > fileLength) cursor + fileLength else n).toInt()
        cursor += n1
        return n1.toLong()
    }

    override fun read(): Int {
        if (cursor >= fileLength) return -1
        val b = ByteArray(1)
        file.pread(b, 0, 1, cursor)
        cursor += 1
        markLimit -= 1
        return b[0].toUint()
    }

    override fun read(b: ByteArray): Int {
        val count = file.pread(b, 0, b.size, cursor)
        cursor += count
        markLimit -= 1
        return count
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = file.pread(b, off, len, cursor)
        cursor += count
        markLimit -= count
        return count
    }

    override fun readAllBytes(): ByteArray {
        if (fileLength > 2147483639L) throw IndexOutOfBoundsException("File too large")

        val b = ByteArray(fileLength.toInt())
        file.pread(b, 0, b.size, cursor)
        cursor += b.size
        markLimit -= b.size
        return b
    }

    override fun readNBytes(len: Int): ByteArray {
        val b = ByteArray(len)
        file.pread(b, 0, b.size, cursor)
        cursor += b.size
        markLimit -= b.size
        return b
    }

    override fun readNBytes(b: ByteArray, off: Int, len: Int): Int {
        return file.pread(b, off, len, cursor).also {
            cursor += len
            markLimit -= len
        }
    }

    override fun skipNBytes(n: Long) {
        cursor -= n.toInt()
        markLimit -= n.toInt()
        if (cursor < 0) cursor = 0
    }

    /**
     * If marked, will return to the marked position. If not marked or the mark became invalidated (exceeding readlimit
     * of the mark), will return to the beginning of the file.
     */
    override fun reset() {
        cursor = if (markLimit > 0) markPos else 0
    }

    override fun available() = (fileLength - cursor).coerceAtMost(2147483647).toInt()

    override fun mark(readlimit: Int) {
        markPos = cursor
        markLimit = readlimit
    }

    override fun markSupported() = true
}