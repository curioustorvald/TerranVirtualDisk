package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.EntryID
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.FATEntry
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toHex
import java.util.*
import java.util.function.Consumer
import java.util.function.IntFunction
import java.util.stream.Stream
import kotlin.collections.ArrayList

class FileTableMap : Collection<FATEntry> {
    private val internalMap = SortedArrayList<FATEntry>(16)

    internal val renumberHook = {
        internalMap.arrayList.sort()
    }

    operator fun get(id: EntryID): FATEntry? {
        return internalMap.searchFor(id) { it.entryID }
    }

    fun put(fat: FATEntry) {
        internalMap.add(fat)
    }

    fun remove(id: EntryID) {
        println("[FileTableMap] remove ${id.toHex()}")
//        Thread.currentThread().stackTrace.forEach { println(it) }
        internalMap.searchForIndex(id) { it.entryID }?.let {
            internalMap.removeAt(it)
        }
    }

    override val size = internalMap.size

    override fun contains(element: FATEntry): Boolean {
        return internalMap.contains(element)
    }
    fun contains(id: EntryID): Boolean {
        return (internalMap.searchForIndex(id) { it.entryID } ?: -1) >= 0
    }

    override fun containsAll(elements: Collection<FATEntry>): Boolean {
        return internalMap.containsAll(elements)
    }

    override fun forEach(action: Consumer<in FATEntry>?) {
        internalMap.forEach(action)
    }


    override fun isEmpty(): Boolean {
        return internalMap.isEmpty()
    }

    override fun iterator(): Iterator<FATEntry> {
        return internalMap.iterator()
    }

    override fun parallelStream(): Stream<FATEntry> {
        return internalMap.parallelStream()
    }

    override fun spliterator(): Spliterator<FATEntry> {
        return internalMap.spliterator()
    }

    override fun stream(): Stream<FATEntry> {
        return internalMap.stream()
    }

    override fun <T : Any?> toArray(generator: IntFunction<Array<T>>?): Array<T> {
        throw UnsupportedOperationException()
    }

    fun toTypedArray() = internalMap.toTypedArray()

    fun getByInternalIndex(iidx: Int) = internalMap[iidx]
}

private class SortedArrayList<T: Comparable<T>>(initialSize: Int = 10) : MutableCollection<T> {

    val arrayList = ArrayList<T>(initialSize)

    /**
     */
    override fun add(element: T): Boolean {
        // don't append-at-tail-and-sort; just insert at right index
        // this is a modified binary search to search the right "spot" where the insert elem fits
        var low = 0
        var high = arrayList.size

        while (low < high) {
            val mid = (low + high).ushr(1)

            if ((arrayList[mid] as Comparable<T>) > element)
                high = mid
            else
                low = mid + 1
        }

        arrayList.add(low, element)

        return true
    }

    override fun addAll(elements: Collection<T>): Boolean {
        arrayList.addAll(elements)
        arrayList.sort()

        return true
    }

    override val size: Int
        get() = arrayList.size

    override inline fun isEmpty() = arrayList.isEmpty()
    inline fun lastIndexOf(element: T) = arrayList.lastIndexOf(element)

    inline fun removeAt(index: Int) = arrayList.removeAt(index)
    override inline fun remove(element: T) = arrayList.remove(element) // don't mess up with your own half-assed implementation
    override inline fun removeAll(elements: Collection<T>) = arrayList.removeAll(elements)
    inline fun removeLast() = arrayList.removeAt(arrayList.size - 1)

    override fun retainAll(elements: Collection<T>) = arrayList.retainAll(elements)

    operator inline fun get(index: Int) = arrayList[index]
    fun getOrNull(index: Int?) = if (index == null) null else get(index)

    fun indexOf(element: T): Int = searchForIndex(element.hashCode()) { element.hashCode() } ?: -1

    /**
     * Searches for the element. Null if the element was not found
     */
    override fun contains(element: T): Boolean {
        // code from collections/Collections.kt
        var low = 0
        var high = this.size - 1

        while (low <= high) {
            val mid = (low + high).ushr(1) // safe from overflows

            val midVal = get(mid)

            if ((element) > midVal)
                low = mid + 1
            else if (element < midVal)
                high = mid - 1
            else
                return true // key found
        }
        return false // key not found
    }

    override fun containsAll(elements: Collection<T>) = arrayList.containsAll(elements)

    /** Searches the element using given predicate instead of the element itself. Returns index in the array where desired, null when there is no such element.
     * element is stored.
     * (e.g. search the Actor by its ID rather than the actor instance)
     *
     * @param searchQuery what exactly are we looking for?
     * @param searchHow and where or how can it be found?
     */
    fun <R: Comparable<R>> searchForIndex(searchQuery: R, searchHow: (T) -> R): Int? {
        var low = 0
        var high = this.size - 1

        while (low <= high) {
            val mid = (low + high).ushr(1) // safe from overflows

            val midVal = searchHow(get(mid))

            if (searchQuery > midVal)
                low = mid + 1
            else if (searchQuery < midVal)
                high = mid - 1
            else
                return mid // key found
        }
        return null // key not found
    }

    /**
     * e.g.
     *
     * 0 2 4 5 7 , find 3
     *
     * will return (1, 2), which corresponds value (2, 4) of which input value 3 is in between.
     */
    fun <R: Comparable<R>> searchForInterval(searchQuery: R, searchHow: (T) -> R): Pair<Int,Int> {
        var low: Int = 0
        var high: Int = this.size - 1

        while (low <= high) {
            val mid = (low + high).ushr(1)
            val midVal = searchHow(get(mid))

            if (searchQuery < midVal)
                high = mid - 1
            else if (searchQuery > midVal)
                low = mid + 1
            else
                return Pair(mid, mid)
        }

        val first = Math.max(high, 0)
        val second = Math.min(low, this.size - 1)
        return Pair(first, second)
    }
    /** Searches the element using given predicate instead of the element itself
     * (e.g. search the Actor by its ID rather than the actor instance)
     *
     * @param searchQuery what exactly are we looking for?
     * @param searchHow and where or how can it be found?
     *
     * @return The element you're searching; null when there is no such element.
     */
    fun <R: Comparable<R>> searchFor(searchQuery: R, searchHow: (T) -> R = { it as R }): T? = getOrNull(searchForIndex(searchQuery, searchHow))

    override inline fun iterator() = arrayList.iterator()
    inline fun listIterator() = arrayList.listIterator()
    inline fun listIterator(index: Int) = arrayList.listIterator(index)
    inline fun subList(fromIndex: Int, toIndex: Int) = arrayList.subList(fromIndex, toIndex)
    override inline fun forEach(action: Consumer<in T>?) = arrayList.forEach(action)
    inline fun forEachIndexed(action: (Int, T) -> Unit) = arrayList.forEachIndexed(action)

    fun cloneToList(): List<T> {
        return arrayList.toList()
    }


    inline fun <reified R> map(transformation: (T) -> R) = arrayList.map(transformation)

    /*fun <R> filter(function: (T) -> Boolean): List<R> {
        val retList = ArrayList<R>() // sorted-ness is preserved
        this.arrayList.forEach { if (function(it)) retList.add(it as R) }
        return retList
    }*/
    inline fun filter(function: (T) -> Boolean) = arrayList.filter(function)

    inline fun <reified R> filterIsInstance() = arrayList.filterIsInstance<R>()

    /**
     * Does NOT create copies!
     */
    fun toArrayList() = arrayList

    override fun clear() = arrayList.clear()
}