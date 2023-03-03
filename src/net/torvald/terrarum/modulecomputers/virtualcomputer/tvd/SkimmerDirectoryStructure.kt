package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskEntry.Companion.NAME_LENGTH
import java.nio.charset.Charset

data class NameIdPair(var name: ByteArray, val id: EntryID, var childs: ArrayList<NameIdPair> = ArrayList())

class SkimmerDirectoryStructure(val diskSkimmer: DiskSkimmer, val charset: Charset) {

//    internal lateinit var rootNode: NameIdPair; private set

    private val DEBUG = true

    internal val pathToID = HashMap<String, EntryID>()
    internal val IDtoPath = HashMap<EntryID, String>()

    private fun printdbg(msg: Any?) {
        if (DEBUG) println("[SkimmerDirectoryStructure] $msg")
    }

    private fun traverse(id: EntryID, entry: EntryDirectory, nameAkku: String) {
        if (id == 0)
            pathToID["/"] = 0
        else
            pathToID[nameAkku] = id

        entry.forEach { thisID ->

            diskSkimmer.requestFile(thisID)?.let { thisFile ->
                val newName = "$nameAkku/${thisFile.filename.toCanonicalString(charset)}"

                if (thisFile.contents is EntryDirectory) {
                    traverse(thisID, thisFile.contents, newName)
                }
                else {
                    pathToID[newName] = thisID
                }
            }

        }
    }



    fun rebuild() {
//        rootNode = NameIdPair(byteArrayOf(), 0)

        // TODO ignore filename of the root

        pathToID.clear()
        IDtoPath.clear()
        traverse(0, diskSkimmer.requestFile(0)!!.contents as EntryDirectory, "")

        pathToID.forEach { path, id ->
            IDtoPath[id] = path
        }

        if (DEBUG) {
            println("[SkimmerDirectoryStructure] PATH-TO-ID MAP:")
            pathToID.toSortedMap().forEach { path, id ->
                println("[SkimmerDirectoryStructure] $path -> $id")
            }
        }
    }

    private fun traverse2(currentNode: NameIdPair, tokens: MutableList<String>): Pair<NameIdPair?, Throwable?> {

        if (tokens.isEmpty()) return currentNode to null

        if (tokens.isNotEmpty() && currentNode.childs.isEmpty()) return null to UnsupportedOperationException("entry not found: unexpected leaf node reached")

        val headName = tokens.first().toEntryName(NAME_LENGTH, charset)

        val gotcha = currentNode.childs.linearSearchBy { it.name.contentEquals(headName) }
        if (gotcha != null) {
            tokens.removeAt(0)
            return traverse2(gotcha, tokens)
        }
        else {
            return null to NoSuchElementException("No file/dir named '${tokens[1]}' under the '${tokens[0]}'")
        }
    }

    /**
     * @param path slash-delimetered absolute path string without a drive letter but a slash (examples of valid inputs: "/tvdos/includes", "/tvdos/bin/command.js", "/!BOOTSEC", "/")
     * @return Entry ID of the file, or null if not found
     */
    fun find(path: String): EntryID? {
        /*val tokens = path.split('/').toMutableList()
        val (item, error) = traverse2(rootNode, tokens)

        if (error != null) {
            printdbg("Unable to find '$path': $error")
        }

        return item*/

        val r = pathToID[path]
        if (r == null) printdbg("File not found: '$path'")
        return r
    }

    fun toFullPath(id: EntryID) = IDtoPath[id]

    fun add(fullPath: String, id: EntryID) {
        pathToID[fullPath] = id
        IDtoPath[id] = fullPath
        printdbg("Accepting new file '$fullPath' -> $id")
    }

    fun remove(id: EntryID) {
        IDtoPath.remove(id)?.let { pathToID.remove(it) }
    }

    private fun <T> List<T>.linearSearchBy(selector: (T) -> Boolean): T? {
        this.forEach {
            if (selector.invoke(it)) return it
        }

        return null
    }
}
