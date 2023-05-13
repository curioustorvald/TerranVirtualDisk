package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.*
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.readBytes64
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.toInt48Big
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.toIntBig
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.toShortBig
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.writeBytes64
import java.io.File
import java.nio.charset.Charset
import java.util.logging.Level

/**
 * Created by minjaesong on 2023-03-31.
 */
class Format3Archiver(val dom: VirtualDisk?) : Archiver() {

    override val specversion = 0x03.toByte()

    override fun serialize(outFile: File) {
        outFile.writeBytes64(serializeToBA64())
    }

    override fun serializeToBA64(): ByteArray64 {
        dom!!.let {
//        val entriesBuffer = dom.serializeEntriesOnly()
            val buffer = ByteArray64()
            val crc = dom.hashCode().toInt32Arr()


            buffer.appendBytes(VirtualDisk.MAGIC)
            buffer.appendBytes(dom.capacity.toInt48Arr())
            buffer.appendBytes(dom.diskName.forceSize(VirtualDisk.NAME_LENGTH))
            buffer.appendBytes(crc)
            buffer.appendByte(specversion)
            buffer.appendByte(dom.attribs)
            buffer.appendBytes(dom.extraAttribs.forceSize(VirtualDisk.ATTRIBS_LENGTH))

//        buffer.appendBytes(entriesBuffer)
            dom.entries.forEach {
                val serialised = it.value.serialize()
                buffer.appendBytes(serialised)
            }

            return buffer
        }
    }

    override fun deserialize(file: File, charset: Charset): VirtualDisk {
        return readDiskArchive(file, charset = charset)
    }

    /**
     * Reads serialised binary and returns corresponding VirtualDisk instance.
     *
     * @param crcWarnLevel Level.OFF -- no warning, Level.WARNING -- print out warning, Level.SEVERE -- throw error
     */
    fun readDiskArchive(infile: File, crcWarnLevel: Level = Level.SEVERE, warningFunc: ((String) -> Unit)? = null, charset: Charset): VirtualDisk {
        val inbytes = infile.readBytes64()



        if (magicMismatch(VirtualDisk.MAGIC, inbytes.sliceArray64(0L..3L).toByteArray()))
            throw RuntimeException("Invalid Virtual Disk file!")

        val diskSize = inbytes.sliceArray64(4L..9L).toInt48Big()
        val diskName = inbytes.sliceArray64(10L..10L + 31)
        val diskCRC = inbytes.sliceArray64(10L + 32..10L + 32 + 3).toIntBig() // to check with completed vdisk
        val diskSpecVersion = inbytes[10L + 32 + 4]
        val attrib0 = inbytes[10L + 32 + 5]
        val attribs = inbytes.sliceArray(10 + 32 + 6 until 10 + 32 + 6 + 16)


        if (diskSpecVersion != specversion)
            throw RuntimeException("Unsupported disk format version: current internal version is $specversion; the file's version is $diskSpecVersion")

        val vdisk = VirtualDisk(diskSize, diskName.toByteArray(), attrib0, attribs)

        //println("[VDUtil] currentUnixtime = $currentUnixtime")

        var entryOffset = VirtualDisk.HEADER_SIZE
        // read through the entire disk, overwrite existing entry if duplicates were found
        while (entryOffset < inbytes.size) {
            //println("[VDUtil] entryOffset = $entryOffset")
            // read and prepare all the shits

            val entryID = inbytes.sliceArray64(entryOffset..entryOffset + 3).toIntBig()

            // process entries
            val entryParentID = inbytes.sliceArray64(entryOffset + 4..entryOffset + 7).toIntBig()
            val entryTypeFlag = inbytes[entryOffset + 8]
            val entryFileName = inbytes.sliceArray64(entryOffset + 9..entryOffset + 9 + 255).toByteArray()
            val entryCreationTime = inbytes.sliceArray64(entryOffset + 265..entryOffset + 270).toInt48Big()
            val entryModifyTime = inbytes.sliceArray64(entryOffset + 271..entryOffset + 276).toInt48Big()
            val entryCRC = inbytes.sliceArray64(entryOffset + 277..entryOffset + 280).toIntBig() // to check with completed entry

            val entryData = when (entryTypeFlag) {
                DiskEntry.NORMAL_FILE -> {
                    val filesize = inbytes.sliceArray64(entryOffset + DiskEntry.HEADER_SIZE..entryOffset + DiskEntry.HEADER_SIZE + 5).toInt48Big()
                    //println("[VDUtil] --> is file; filesize = $filesize")
                    inbytes.sliceArray64(entryOffset + DiskEntry.HEADER_SIZE + 6..entryOffset + DiskEntry.HEADER_SIZE + 5 + filesize)
                }
                DiskEntry.DIRECTORY   -> {
                    val entryCount = inbytes.sliceArray64(entryOffset + DiskEntry.HEADER_SIZE..entryOffset + DiskEntry.HEADER_SIZE + 1).toShortBig()
                    //println("[VDUtil] --> is directory; entryCount = $entryCount")
                    inbytes.sliceArray64(entryOffset + DiskEntry.HEADER_SIZE + 2..entryOffset + DiskEntry.HEADER_SIZE + 1 + entryCount * 4)
                }
                DiskEntry.SYMLINK     -> {
                    inbytes.sliceArray64(entryOffset + DiskEntry.HEADER_SIZE..entryOffset + DiskEntry.HEADER_SIZE + 3)
                }
                else -> throw RuntimeException("Unknown entry with type $entryTypeFlag at entryOffset $entryOffset")
            }



            // update entryOffset so that we can fetch next entry in the binary
            entryOffset += DiskEntry.HEADER_SIZE + entryData.size + when (entryTypeFlag) {
                DiskEntry.NORMAL_FILE -> 6      // PLEASE DO REFER TO Spec.md
                DiskEntry.DIRECTORY   -> 2      // PLEASE DO REFER TO Spec.md
                DiskEntry.SYMLINK     -> 0      // PLEASE DO REFER TO Spec.md
                else -> throw RuntimeException("Unknown entry with type $entryTypeFlag")
            }


            // create entry
            val diskEntry = DiskEntry(
                    entryID = entryID,
                    parentEntryID = entryParentID,
                    filename = entryFileName,
                    creationDate = entryCreationTime,
                    modificationDate = entryModifyTime,
                    contents = if (entryTypeFlag == DiskEntry.NORMAL_FILE) {
                        EntryFile(entryData)
                    }
                    else if (entryTypeFlag == DiskEntry.DIRECTORY) {
                        val entryList = ArrayList<EntryID>()
                        (0..entryData.size / 4 - 1).forEach {
                            entryList.add(entryData.sliceArray64(4 * it..4 * it + 3).toIntBig())
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

                val crcMsg = "CRC failed: stored value is ${entryCRC.toHex()}, but calculated value is ${calculatedCRC.toHex()}\n" +
                        "at file \"${diskEntry.getFilenameString(charset)}\" (entry ID ${diskEntry.entryID})"

                if (calculatedCRC != entryCRC) {

                    println("CRC failed; entry info:\n$diskEntry")

                    if (crcWarnLevel == Level.SEVERE)
                        throw VDIOException(crcMsg)
                    else if (warningFunc != null)
                        warningFunc(crcMsg)
                }
            }

            // add entry to disk
            vdisk.entries[entryID] = diskEntry
        }


        // check CRC of disk
        if (crcWarnLevel == Level.SEVERE || crcWarnLevel == Level.WARNING) {
            val calculatedCRC = vdisk.hashCode()

            val crcMsg = "Disk CRC failed: expected ${diskCRC.toHex()}, got ${calculatedCRC.toHex()}"

            if (calculatedCRC != diskCRC) {
                if (crcWarnLevel == Level.SEVERE)
                    throw VDIOException(crcMsg)
                else if (warningFunc != null)
                    warningFunc(crcMsg)
            }
        }

        return vdisk
    }
}