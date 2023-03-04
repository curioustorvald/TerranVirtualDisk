package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.VDUtil.sanitisePath
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.HashSet

internal data class FileCache(val file: DiskEntry, var hits: Int = 1, var lastAccess: Long = System.currentTimeMillis())

/**
 * DOM with lesser memory footprint. Suitable for multi-machine environment.
 *
 * Goals
 * - A compromise between full DOM and DiskSkimmer (no DOM) and built on top of DiskSkimmer
 * - With an assumption that only one file gets IO operation at a time (e.g. TVDOS)
 * - Directory structure is always kept for fast navigation on DOS
 * - Caches the frequently-access files, no larger than set size (default: 10 MB)
 * - Changes on the Disk are accumulated
 *
 * @param diskFile Disk Archive File
 * @param charset Character set of the VM
 * @param CACHE_SIZE How many files are cached. Default: 16
 * @param MAX_FILESIZE_TO_CACHE Maximum size of the file for caching. A file larger than this will not be cached. Default: 10485760
 * @param CACHE_RETAINING_TIME How hong a file should be cached. A file unused for this time or longer are considered unused and will be pending for removal. Default: 60000 (10 minutes)
 * @param FRESH_CACHE_REMOVAL_CHANCE If there is no cache old enough to be removed, a oldest fresh cache will still be removed randomly. This value decides how often should it happen. Default: 0.1 (10 %)
 *
 * Created by minjaesong on 2023-02-05.
 */
class PartialDOM(private val diskFile: File, val charset: Charset = Charset.defaultCharset(),
                 private val CACHE_SIZE: Int = 16,
                 private val MAX_FILESIZE_TO_CACHE: Int = 10485760,
                 private val CACHE_RETAINING_TIME: Long = 1000 * 10 * 60, // 10 minutes
                 private val FRESH_CACHE_REMOVAL_CHANCE: Double = 0.1
) {

    private val DEBUG = true
    private fun printdbg(msg: Any?) {
        if (DEBUG) println("[PartialDOM] $msg")
    }

    private val fileAccessedAtLeastOnce = BloomFilter(256, 128)
    private val fileCache = HashMap<EntryID, FileCache>()

    private val changedFiles = HashSet<DiskEntry>()
    private val removedFiles = HashSet<EntryID>()

    private val diskSkimmer = DiskSkimmer(diskFile, charset)

    private val directoryStructure = SkimmerDirectoryStructure(diskSkimmer, charset)

    val isReadOnly: Boolean
        get() = diskSkimmer.readOnly

    var usedBytes: Long; private set

    val capacity: Long
        get() = diskSkimmer.diskCapacity

    init {
        directoryStructure.rebuild()

        // calculate disk usage
        usedBytes = directoryStructure.IDtoPath.keys.fold(0L) { acc, id ->
            acc + diskSkimmer.requestFile(id)!!.serialisedSize // FullDOM uses the same variable for calculating the disk usage
        }
    }

    internal fun cacheFile(file: DiskEntry?) {
        if (file == null) return
        if (file.contents is EntryFile && file.contents.getSizePure() > MAX_FILESIZE_TO_CACHE) return

        val timeNow = System.currentTimeMillis()

        // 1. mark the oldest unused
        var oldest: Int? = null
        var oldestIdleTime = 0L
        fileCache.forEach { id, cache ->

            // TODO apply weighing using hitcount and idlingtime

            val check = timeNow - cache.lastAccess
            if (check > oldestIdleTime) {
                oldest = id
                oldestIdleTime = check
            }
        }
        // 2. if its idle time is not greater than CACHE_RETAINING_TIME, give it 10% chance for removal
        if (oldest != null && (oldestIdleTime > CACHE_RETAINING_TIME || Math.random() < FRESH_CACHE_REMOVAL_CHANCE)) {
            fileCache.remove(oldest)
        }


        if (fileCache.contains(file.entryID)) {
            fileCache[file.entryID]!!.let {
                it.hits += 1
                it.lastAccess = System.currentTimeMillis()
            }
        }
        else {
            fileCache[file.entryID] = FileCache(file)
        }

    }

    /**
     * also call this after a file removal!
     */
    internal fun uncacheFile(id: EntryID) {
        fileCache.remove(id)
    }


    /**
     * Updates the file's access counter
     */
    internal fun peekFile(id: EntryID) {
        fileAccessedAtLeastOnce.add(id)
        if (fileAccessedAtLeastOnce.contains(id)) {
            cacheFile(diskSkimmer.requestFile(id))
        }
    }


    fun checkReadOnly() {
        if (isReadOnly)
            throw IOException("Disk is read-only")
    }
    fun checkCapacity(newSize: Long) {
        if (usedBytes + newSize > capacity)
            throw IOException("Not enough space on the disk")
    }


    private fun String.sanitise(): String {
        var path = this.sanitisePath()
        // remove any superfluous initial slashes
        if (path.isEmpty()) return "/"
        while (path[0] == '/')
            path = path.substring(1)
        return "/"+path
    }

    /**
     * Marks the file as "altered" so that the changes can be committed
     */
    fun touchFile(file: DiskEntry) {
        checkReadOnly()
        _touchFileInternal(file)
    }
    fun touchFile(path: String) {
        directoryStructure.find(path.sanitise())?.let { id ->
            requestFile(id)?.let { _touchFileInternal(it) }
        }
    }
    private fun _touchFileInternal(file: DiskEntry) {
        changedFiles.add(file)
    }



    fun requestFile(id: EntryID): DiskEntry? {
        peekFile(id)
        // 1. look for fileCache, return if found
        // 2. look for changedFiles, return if found
        // 3. take a file from diskSkimmer:
        // 3.1. if the file is there:
        // 3.1.1 ...and the file is on the removedFiles -> return null
        // 3.1.2 ...and the file is NOT on the removedFiles -> return it
        // 3.2. if the file is not there, return null
        return fileCache.get(id)?.file ?: changedFiles.find { it.entryID == id } ?:
        let { dom ->
            val fileOnSkimmer = diskSkimmer.requestFile(id)
            if (removedFiles.contains(fileOnSkimmer?.entryID))
                null
            else fileOnSkimmer /* may be null! */
        }
    }
    fun requestFile(path: String): DiskEntry? {
        return directoryStructure.find(path.sanitise())?.let { requestFile(it) }
    }

    /**
     * the file must be altered beforehand. This function is NOT a symlink to the `touchFile(DiskEntry)`
     */
    fun writeFile(file: DiskEntry) {
        touchFile(file)
        // add filesize-delta to the usedBytes
        val oldFileSize = requestFile(file.entryID)?.serialisedSize ?: 0
        val sizeDelta = file.serialisedSize - oldFileSize
        usedBytes += sizeDelta
    }

    /**
     * Parent dir must be alternd manually; when you're done, touchFile() the parent as well
     */
    fun removeFile(id: EntryID) {
        checkReadOnly()

        requestFile(id)?.let {

            printdbg("Removing entry ${id}")

            removedFiles.add(id)
            directoryStructure.remove(id)
            uncacheFile(id)
        }
    }
    fun removeFile(path: String) {
        directoryStructure.find(path.sanitise())?.let { id ->
            removeFile(id)
        }
    }


    /**
     * Parent dir must be alternd manually; when you're done, touchFile() the parent as well
     */
    fun addNewFile(entry: DiskEntry) {
        checkCapacity(entry.serialisedSize)
        checkReadOnly()

        var fullPath = directoryStructure.toFullPath(entry.parentEntryID)
                ?: throw FileNotFoundException("Parent entry ${entry.parentEntryID} does not exist")
        var entryFileName = entry.filename.toCanonicalString(charset)
        while (fullPath.startsWith("/")) {
            fullPath = fullPath.substring(1)
        }
        while (entryFileName.startsWith("/")) {
            entryFileName = entryFileName.substring(1)
        }
        var addPath = "$fullPath/$entryFileName"
        while (addPath[1] == '/') {
            addPath = addPath.substring(1)
        }

        printdbg("Adding new entry ${entry.entryID}")
        _touchFileInternal(entry)
        directoryStructure.add(addPath, entry.entryID)
        usedBytes += entry.serialisedSize

    }


    /**
     * Saves the filesystem change to the underlying Disk Skimmer, which in turn creates a "dirty" disk image to the physical disk
     */
    fun commit() {
        diskSkimmer.deleteEntries(removedFiles.toList())
        diskSkimmer.appendEntries(changedFiles.toList())
        removedFiles.clear()
        changedFiles.clear()
    }

    /**
     * Clenses the dirty disk image by re-building a proper DOM then save it to the physical disk. Warning: large memory footprint
     */
    fun sync() {
        diskSkimmer.sync()
    }

    fun generateUniqueID(): Int {
        var id: Int
        do {
            id = Random().nextInt()
        } while (null != directoryStructure.IDtoPath[id])
        return id
    }


}