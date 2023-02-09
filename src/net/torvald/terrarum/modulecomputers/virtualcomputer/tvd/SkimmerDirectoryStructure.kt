package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskEntry.Companion.NAME_LENGTH
import java.nio.charset.Charset

data class NameIdPair(var name: ByteArray, val id: EntryID, var childs: ArrayList<NameIdPair> = ArrayList())

class SkimmerDirectoryStructure(val diskSkimmer: DiskSkimmer, val charset: Charset) {

    internal lateinit var rootNode: NameIdPair; private set


    private fun traverse(currentNode: NameIdPair, entry: EntryDirectory) {
        entry.forEach { thisID ->

            diskSkimmer.requestFile(thisID)?.let { thisFile ->
                val newNode = NameIdPair(thisFile.filename, thisID)

                currentNode.childs.add(newNode)

                if (thisFile.contents is EntryDirectory) {
                    traverse(newNode, thisFile.contents)
                }
            }

        }
    }



    fun rebuild() {
        rootNode = NameIdPair(byteArrayOf(), 0)
        traverse(rootNode, diskSkimmer.requestFile(0)!!.contents as EntryDirectory)
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
     * @param path slash-delimetered absolute path string without a drive letter and not slashes (examples of valid inputs: "tvdos/includes", "tvdos/bin/command.js")
     * @return Entry ID of the file, or null if not found
     */
    fun find(path: String): NameIdPair? {
        val tokens = path.split('/').toMutableList()
        return traverse2(rootNode, tokens).first
    }

    private fun <T> List<T>.linearSearchBy(selector: (T) -> Boolean): T? {
        this.forEach {
            if (selector.invoke(it)) return it
        }

        return null
    }
}
