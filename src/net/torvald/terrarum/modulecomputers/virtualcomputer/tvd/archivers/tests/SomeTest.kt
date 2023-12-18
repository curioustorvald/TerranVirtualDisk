import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.Clustfile
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClustfileOutputStream
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.FileTableMap
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

    val archiveFile = File("./newformat.tevd")

    println("Cleanup...")
    archiveFile.delete()


    println("Creating Archive")
    val diskFile = ClusteredFormatDOM.createNewArchive(archiveFile, charset, "TEST DRIVE 3", 600)
    val DOM = ClusteredFormatDOM(diskFile)


    val test1 = Clustfile(DOM, "/test1.txt").also {
        it.createNewFile()
        it.writeBytes("Testing 1".toByteArray(charset))
    }

    val test2 = Clustfile(DOM, "/test2.txt").also {
        test1.copyInto(it)
    }

    println("Test1.txt = ${test1.readBytes().toString(charset)}")
    println("Test2.txt = ${test2.readBytes().toString(charset)}")

    testPause("")

    // modify the head file
    test1.writeBytes("Testing 321".toByteArray(charset))

    // modify the shadow
//    test2.writeBytes("Testing 666".toByteArray(charset))

    println("Test1.txt = ${test1.readBytes().toString(charset)}")
    println("Test2.txt = ${test2.readBytes().toString(charset)}")
}