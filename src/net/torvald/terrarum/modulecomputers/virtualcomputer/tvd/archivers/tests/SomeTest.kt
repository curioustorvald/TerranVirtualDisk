import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.*
import java.io.File
import java.util.*

/**
 * Created by minjaesong on 2023-05-26.
 */
fun main() {
    val charset = Charsets.ISO_8859_1

    fun testPause(msg: String) {
        println("\n\n== $msg ==\n\n")
        print("> "); Scanner(System.`in`).nextLine()
    }


    println("Charset: ${charset.name().toUpperCase()}")

    val archiveFile = File("./testclustered3.tevd")

    println("Cleanup...")
    archiveFile.delete()


    println("Creating Archive")
    ClusteredFormatDOM.createNewArchive(archiveFile, charset, "TEST DRIVE 3", 600)


    val testfile = Clustfile(archiveFile, "/testfile")
    val cos = ClustfileOutputStream(testfile, false)

    println("ClustfileOutputStream Overwrite test")

    cos.write(ByteArray(4000) { 0x42 })
    testfile.readBytes().let {
        println(it.toString(charset))
        println(it.size)
    }

    val cos2 = ClustfileOutputStream(testfile, false)

    cos2.write(ByteArray(5000) { 0x41 })
    testfile.readBytes().let {
        println(it.toString(charset))
        println(it.size)
    }

    DomMgr[archiveFile].buildFreeClustersMap()
    println("Free clusters: ${DomMgr[archiveFile].getFreeClusterMap()}")


    testfile.delete()
}