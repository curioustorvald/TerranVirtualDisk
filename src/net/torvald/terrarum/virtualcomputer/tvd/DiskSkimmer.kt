package net.torvald.terrarum.virtualcomputer.tvd

import java.io.*

/**
 * Creates entry-to-offset tables to allow streaming from the disk, without storing whole VD file to the memory.
 *
 * Created by minjaesong on 2017-11-17.
 */
class DiskSkimmer(private var diskFile: File) {


    /**
     * EntryID to Offset.
     *
     * Offset is where the header begins, so first 4 bytes are exactly the same as the EntryID.
     */
    var entryToOffsetTable = HashMap<EntryID, Long>()
    var footerPosition: Long = 0L

    val footerSize: Int
        get() = (diskFile.length() - footerPosition).toInt()


    init {
        val fis = FileInputStream(diskFile)
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


        while (true) {
            val entryID = readIntBig()

            // footer
            if (entryID == 0xFEFEFEFE.toInt()) {
                footerPosition = currentPosition
                break
            }


            // fill up table
            entryToOffsetTable[entryID] = currentPosition

            skipRead(4) // skip entryID of parent

            val entryType = readByte()

            skipRead(256 + 6 + 6 + 4) // skips rest of the header


            // figure out the entry size so that we can skip
            val entrySize: Long = when(entryType) {
                0x01.toByte() -> readInt48()
                0x11.toByte() -> readInt48() + 6 // size of compressed payload + 6 (header elem for uncompressed size)
                0x02.toByte() -> readIntBig().shl(16).toLong() * 4 - 2 // #entris is 2 bytes, we read 4 bytes, so we subtract 2
                0x03.toByte() -> 4 // symlink
                else -> throw InternalError("Unknown entry type: ${entryType.toUint()}")
            }


            skipRead(entrySize) // skips rest of the entry's actual contents
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
        // FIXME untested
        entryToOffsetTable[entryID].let { offset ->
            if (offset == null)
                return null
            else {
                val fis = FileInputStream(diskFile)
                fis.skip(offset + 4) // get to the EntryHeader's parent directory area
                val parent = fis.read(4).toIntBig()
                val fileFlag = fis.read(1)[0]
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
                        fis.read(2).toUint16().toLong()
                    }
                    DiskEntry.SYMLINK -> 4L
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
                        // read 4 bytes at a time
                        val bytesBuffer4 = ByteArray(4)
                        for (c in 0L until entrySize) {
                            fis.read(bytesBuffer4)
                            dirContents.add(bytesBuffer4.toIntBig())
                        }

                        EntryDirectory(dirContents)
                    }
                    DiskEntry.SYMLINK -> {
                        val target = fis.read(4).toIntBig()

                        EntrySymlink(target)
                    }
                    else -> throw UnsupportedOperationException("Unsupported entry type: $fileFlag") // FIXME no support for compressed file
                }

                return DiskEntry(entryID, parent, filename, creationTime, modifyTime, entryContent)
            }
        }
    }

    ///////////////////////////////////////////////////////
    // THESE ARE METHODS TO SUPPORT ON-LINE MODIFICATION //
    ///////////////////////////////////////////////////////

    fun appendEntry(entry: DiskEntry) = appendEntries(listOf(entry))

    fun appendEntries(entries: List<DiskEntry>): Boolean {
        // FIXME untested

        // buffer the footer
        // define newFooterPos = 0
        // define newEntryOffsetTable = entryToOffsetTable.clone()
        // make new diskFile_tmp such that:
        //      try :
        //          copy (0 until footerPosition) bytes to the tmpfile -> throws IOException
        //          serialise newly adding entries -> throws IOException
        //          update newEntryOffsetTable
        //          copy (footerPosition until file.length) bytes to the tmpfile -> throws IOException
        //          set newFooterPos
        //      catch IOException:
        //          return false
        // try:
        //      move diskFile to diskFile_old -> throws IOException
        //      move diskFile_tmp to diskFile -> throws IOException
        //      delete diskFile_old -> throws IOException
        // catch IOException:
        //      try:
        //          if (diskFile_old) exists, rename diskFile_old to diskFile -> throws IOException
        //      catch IOException:
        //          do nothing
        //      return false
        // footerPosition = newFooterPos
        // entryToOffsetTable = newEntryOffsetTable
        // return true


        var newFooterPos = 0L
        val originalFile = diskFile.absoluteFile
        val newEntryOffsetTable = entryToOffsetTable.clone() as HashMap<EntryID, Long>
        val tmpFile = File(originalFile.absolutePath + "_tmp")
        val oldFile = File(originalFile.absolutePath + "_old")


        // buffer the footer
        val fis = FileInputStream(diskFile)
        fis.skip(footerPosition)
        val footerBytes = fis.read(footerSize)
        fis.close()


        // make tmpfile
        try {
            val tmpOut = BufferedOutputStream(FileOutputStream(tmpFile))
            val oldIn = BufferedInputStream(FileInputStream(diskFile))
            var entryCounter = footerPosition
            for (c in 0 until footerPosition) {
                tmpOut.write(oldIn.read())
            }
            entries.forEach { entry ->
                val bytes = entry.serialize().array

                // update newEntryOffsetTable
                newEntryOffsetTable[entry.entryID] = entryCounter

                // actually copy the bytes
                bytes.forEachBanks { tmpOut.write(it) }

                // update counter
                entryCounter += bytes.size
            }
            tmpOut.write(footerBytes)

            tmpOut.flush(); tmpOut.close()
            oldIn.close()

            // at this point, entryCounter should rightfully point the new footer position
            newFooterPos = entryCounter
        }
        catch (e: IOException) {
            return false
        }

        // replace tmpFile with original file
        try {
            oldFile.delete()
            val suc1 = diskFile.renameTo(oldFile)
            val suc2 = tmpFile.renameTo(originalFile)
            if (!suc1 or !suc2) return false

            diskFile = tmpFile
        }
        catch (e: IOException) {
            // try to recover from failure
            try {
                if (oldFile.exists()) {
                    oldFile.renameTo(originalFile)
                }
            }
            catch (e: Throwable) {  }
            return false
        }


        footerPosition = newFooterPos
        entryToOffsetTable = newEntryOffsetTable
        return true

    }



    companion object {
        fun InputStream.read(size: Int): ByteArray {
            val ba = ByteArray(size)
            this.read(ba)
            return ba
        }
    }

    private fun ByteArray.toUint16(): Int {
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

    private fun ByteArray.toInt64(): Long {
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