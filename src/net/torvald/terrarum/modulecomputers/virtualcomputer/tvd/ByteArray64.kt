package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import java.io.*


/**
 * ByteArray that can hold larger than 2 GiB of Data.
 *
 * Works kind of like Bank Switching of old game console's cartridges which does same thing.
 *
 * Note that this class is just a fancy ArrayList. Internal size will grow accordingly
 *
 * @param initialSize Initial size of the array. If it's not specified, 8192 will be used instead.
 *
 * Created by Minjaesong on 2017-04-12.
 */
class ByteArray64(initialSize: Long = bankSize.toLong()) {
    var internalCapacity: Long = initialSize
        private set

    var size = 0L
        internal set

    companion object {
        val bankSize: Int = 8192

        fun fromByteArray(byteArray: ByteArray): ByteArray64 {
            val ba64 = ByteArray64(byteArray.size.toLong())
            byteArray.forEachIndexed { i, byte -> ba64[i.toLong()] = byte }
            return ba64
        }
    }

    internal val __data: ArrayList<ByteArray>

    init {
        if (internalCapacity < 0)
            throw IllegalArgumentException("Invalid array size: $internalCapacity")
        else if (internalCapacity == 0L) // signalling empty array
            internalCapacity = bankSize.toLong()

        val requiredBanks: Int = (initialSize - 1).toBankNumber() + 1

        __data = ArrayList<ByteArray>(requiredBanks)
        repeat(requiredBanks) { __data.add(ByteArray(bankSize)) }
    }

    private fun Long.toBankNumber(): Int = (this / bankSize).toInt()
    private fun Long.toBankOffset(): Int = (this % bankSize).toInt()

    operator fun set(index: Long, value: Byte) {
        ensureCapacity(index + 1)

        try {
            __data[index.toBankNumber()][index.toBankOffset()] = value
            size = maxOf(size, index + 1)
        }
        catch (e: IndexOutOfBoundsException) {
            val msg = "index: $index -> bank ${index.toBankNumber()} offset ${index.toBankOffset()}\n" +
                    "But the array only contains ${__data.size} banks.\n" +
                    "InternalCapacity = $internalCapacity, Size = $size"
            throw IndexOutOfBoundsException(msg)
        }
    }

    fun add(value: Byte) = set(size, value)

    operator fun get(index: Long): Byte {
        if (index < 0 || index >= size)
            throw ArrayIndexOutOfBoundsException("size $size, index $index")

        try {
            val r = __data[index.toBankNumber()][index.toBankOffset()]
            return  r
        }
        catch (e: IndexOutOfBoundsException) {
            System.err.println("index: $index -> bank ${index.toBankNumber()} offset ${index.toBankOffset()}")
            System.err.println("But the array only contains ${__data.size} banks.")

            throw e
        }
    }

    private fun doubleTheCapacity() {
        val oldSize = __data.size
        repeat(oldSize) { __data.add(ByteArray(bankSize)) }

        internalCapacity = __data.size * bankSize.toLong()
    }

    /**
     * Increases the capacity of it, if necessary, to ensure that it can hold at least the number of elements specified by the minimum capacity argument.
     */
    fun ensureCapacity(minCapacity: Long) {
        while (minCapacity > internalCapacity) {
            doubleTheCapacity()
        }
    }


    operator fun iterator(): ByteIterator {
        return object : ByteIterator() {
            var iterationCounter = 0L

            override fun nextByte(): Byte {
                iterationCounter += 1
                return this@ByteArray64[iterationCounter - 1]
            }

            override fun hasNext() = iterationCounter < this@ByteArray64.size
        }
    }

    fun iteratorChoppedToInt(): IntIterator {
        return object : IntIterator() {
            var iterationCounter = 0L
            val iteratorSize = 1 + ((this@ByteArray64.size - 1) / 4).toInt()

            override fun nextInt(): Int {
                var byteCounter = iterationCounter * 4L
                var int = 0
                (0..3).forEach {
                    if (byteCounter + it < this@ByteArray64.size) {
                        int += this@ByteArray64[byteCounter + it].toInt() shl (it * 8)
                    }
                    else {
                        int += 0 shl (it * 8)
                    }
                }


                iterationCounter += 1
                return int
            }

            override fun hasNext() = iterationCounter < iteratorSize
        }
    }

    /** Iterates over all written bytes. */
    fun forEach(consumer: (Byte) -> Unit) = iterator().forEach { consumer(it) }
    /** Iterates over all written 32-bit words. */
    fun forEachInt32(consumer: (Int) -> Unit) = iteratorChoppedToInt().forEach { consumer(it) }
    /** Iterates over all existing banks, even if they are not used. Please use [forEachUsedBanks] to iterate over banks that are actually been used. */
    fun forEachBanks(consumer: (ByteArray) -> Unit) = __data.forEach(consumer)
    /** Iterates over all written bytes. */
    fun forEachIndexed(consumer: (Long, Byte) -> Unit) {
        var cnt = 0L
        iterator().forEach {
            consumer(cnt, it)
            cnt += 1
        }
    }
    /** Iterates over all written 32-bit words. */
    fun forEachInt32Indexed(consumer: (Long, Int) -> Unit) {
        var cnt = 0L
        iteratorChoppedToInt().forEach {
            consumer(cnt, it)
            cnt += 1
        }
    }

    /**
     * @param consumer (Int, Int, ByteArray)-to-Unit function where first Int is index;
     * second Int is actual number of bytes written in that bank, 0 to BankSize inclusive.
     */
    fun forEachUsedBanksIndexed(consumer: (Int, Int, ByteArray) -> Unit) {
        __data.forEachIndexed { index, bytes ->
            consumer(index, (size - bankSize * index).coerceIn(0, bankSize.toLong()).toInt(), bytes)
        }
    }

    /**
     * @param consumer (Int, Int, ByteArray)-to-Unit function where Int is actual number of bytes written in that bank, 0 to BankSize inclusive.
     */
    fun forEachUsedBanks(consumer: (Int, ByteArray) -> Unit) {
        __data.forEachIndexed { index, bytes ->
            consumer((size - bankSize * index).coerceIn(0, bankSize.toLong()).toInt(), bytes)
        }
    }

    fun sliceArray64(range: LongRange): ByteArray64 {
        val newarr = ByteArray64(range.last - range.first + 1)
        range.forEach { index ->
            newarr[index - range.first] = this[index]
        }
        return newarr
    }

    fun sliceArray(range: IntRange): ByteArray {
        val newarr = ByteArray(range.last - range.first + 1)
        range.forEach { index ->
            newarr[index - range.first] = this[index.toLong()]
        }
        return newarr
    }

    fun toByteArray(): ByteArray {
        if (this.size > Integer.MAX_VALUE - 8) // according to OpenJDK; the size itself is VM-dependent
            throw TypeCastException("Impossible cast; too large to fit")

        return ByteArray(this.size.toInt()) { this[it.toLong()] }
    }

    fun writeToFile(file: File) {
        var fos = FileOutputStream(file, false)
        // following code writes in-chunk basis
        /*fos.write(__data[0])
        fos.flush()
        fos.close()

        if (__data.size > 1) {
            fos = FileOutputStream(file, true)
            for (i in 1..__data.lastIndex) {
                fos.write(__data[i])
                fos.flush()
            }
            fos.close()
        }*/

        forEach {
            fos.write(it.toInt())
        }
        fos.flush()
        fos.close()
    }
}

open class ByteArray64InputStream(val byteArray64: ByteArray64): InputStream() {
    protected open var readCounter = 0L

    override fun read(): Int {
        readCounter += 1

        return try {
            byteArray64[readCounter - 1].toUint()
        }
        catch (e: ArrayIndexOutOfBoundsException) {
            -1
        }
    }
}

/** Static ByteArray OutputStream. Less leeway, more stable. */
open class ByteArray64OutputStream(val byteArray64: ByteArray64): OutputStream() {
    protected open var writeCounter = 0L

    override fun write(b: Int) {
        try {
            byteArray64.add(b.toByte())
            writeCounter += 1
        }
        catch (e: ArrayIndexOutOfBoundsException) {
            throw IOException(e)
        }
    }
}

/** Just like Java's ByteArrayOutputStream, except its size grows if you exceed the initial size
 */
open class ByteArray64GrowableOutputStream(size: Long = ByteArray64.bankSize.toLong()): OutputStream() {
    protected open var buf = ByteArray64(size)
    protected open var count = 0L

    private var finalised = false

    init {
        if (size <= 0L) throw IllegalArgumentException("Illegal array size: $size")
    }

    override fun write(b: Int) {
        if (finalised) {
            throw IllegalStateException("This output stream is finalised and cannot be modified.")
        }
        else {
            buf.add(b.toByte())
            count += 1
        }
    }

    /** Unlike Java's, this does NOT create a copy of the internal buffer; this just returns its internal.
     * This method also "finalises" the buffer inside of the output stream, making further modification impossible.
     *
     * The output stream must be flushed and closed, warning you of closing the stream is not possible.
     */
    @Synchronized
    fun toByteArray64(): ByteArray64 {
        finalised = true
        buf.size = count
        return buf
    }
}