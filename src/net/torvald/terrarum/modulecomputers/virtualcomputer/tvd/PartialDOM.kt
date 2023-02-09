package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import java.io.File
import java.nio.charset.Charset

internal data class FileCache(val file: DiskEntry, var hits: Int = 1)

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
 * Created by minjaesong on 2023-02-05.
 */
class PartialDOM(private val diskFile: File, val charset: Charset = Charset.defaultCharset(),
                 private val CACHE_SIZE: Int = 16,
                 private val MAX_FILESIZE_TO_CACHE: Int = 10485760) {

    private val fileAccessedAtLeastOnce = BloomFilter(256, 128)
    private val fileCache = HashMap<EntryID, FileCache>()

    private val changedFiles = HashSet<DiskEntry>()
    private val removedFiles = HashSet<EntryID>()

    private val diskSkimmer = DiskSkimmer(diskFile, charset)

    private val directoryStructure = SkimmerDirectoryStructure(diskSkimmer, charset)

    init {
        directoryStructure.rebuild()
    }

    internal fun cacheFile(file: DiskEntry?) {
        if (file == null) return
        if (file.contents is EntryFile && file.contents.getSizePure() > MAX_FILESIZE_TO_CACHE) return

        // remove most unpopular files from the cache
        while (fileCache.size >= CACHE_SIZE) {
            var hitrateToSearch = 2147483647
            var idFound = 0
            fileCache.forEach { id, cache ->
                if (cache.hits < hitrateToSearch) {
                    idFound = id
                    hitrateToSearch = cache.hits
                }
            }

            uncacheFile(idFound)
        }

        fileCache[file.entryID] = FileCache(file)
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


    private fun getFileByPath(path: String): DiskEntry? {
        TODO()
    }


    /**
     * Marks the file as "altered" so that the changes can be committed
     */
    fun touchFile(file: DiskEntry) {
        changedFiles.add(file)
    }
    fun touchFile(path: String) {
        TODO()
    }



    fun requestFile(id: EntryID): DiskEntry? {
        peekFile(id)
        return fileCache[id]?.file ?: diskSkimmer.requestFile(id)
    }
    fun requestFile(path: String): DiskEntry? {
        TODO()
    }

    /**
     * the file must be altered beforehand. This function is just a symlink to the `touchFile(DiskEntry)`
     */
    inline fun writeFile(file: DiskEntry) {
        touchFile(file)
    }

    fun removeFile(id: EntryID) {
        removedFiles.add(id)
        uncacheFile(id)
    }
    fun removeFile(path: String) {
        TODO()
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




}