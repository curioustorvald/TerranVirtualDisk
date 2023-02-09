package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException


/**
 * Created by minjaesong on 2023-02-05.
 */
class BloomFilter(capacity: Int, k: Int) {
    private lateinit var set: ByteArray

    private var keySize = 0
    private var setSize: Int = 0
    private var size: Int = 0

    private var md: MessageDigest? = null

    init {
        setSize = capacity
        set = ByteArray(setSize)
        keySize = k
        size = 0
        md = try {
            MessageDigest.getInstance("MD5")
        }
        catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException("Error : MD5 Hash not found")
        }
    }

    fun makeEmpty() {
        set = ByteArray(setSize)
        size = 0
        md = try {
            MessageDigest.getInstance("MD5")
        }
        catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException("Error : MD5 Hash not found")
        }
    }

    fun isEmpty(): Boolean {
        return size == 0
    }

    fun getSize(): Int {
        return size
    }

    private fun getHash(i: Int): Int {
        md!!.reset()
        val bytes: ByteArray = ByteBuffer.allocate(4).putInt(i).array()
        md!!.update(bytes, 0, bytes.size)
        return Math.abs(BigInteger(1, md!!.digest()).toInt()) % (set.size - 1)
    }

    fun add(obj: Int) {
        val tmpset = getSetArray(obj)
        for (i in tmpset) set[i] = 1
        size++
    }

    operator fun contains(obj: Int): Boolean {
        val tmpset = getSetArray(obj)
        for (i in tmpset) if (set[i].toInt() != 1) return false
        return true
    }

    private fun getSetArray(obj: Int): IntArray {
        val tmpset = IntArray(keySize)
        tmpset[0] = getHash(obj)
        for (i in 1 until keySize) tmpset[i] = getHash(tmpset[i - 1])
        return tmpset
    }
}