import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.Clustfile
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

    val archiveFile = File("./testclustered3.tevd")

    println("Cleanup...")
    archiveFile.delete()


    println("Creating Archive")
    val diskFile = ClusteredFormatDOM.createNewArchive(archiveFile, charset, "TEST DRIVE 3", 600)
    val DOM = ClusteredFormatDOM(diskFile)


    val zerobytefile = Clustfile(DOM, "/zerobytefile")
    zerobytefile.createNewFile()

    zerobytefile.pwrite(byteArrayOf(), 0, 0, 0L)


}