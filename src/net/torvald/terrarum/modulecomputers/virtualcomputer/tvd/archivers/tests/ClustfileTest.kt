import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.Clustfile
import java.io.File
import java.util.*

/**
 * Created by minjaesong on 2023-05-14.
 */
fun main(args: Array<String>) {

    fun testPause(msg: String) {
        println("\n\n== $msg ==\n\n")
        print("> "); Scanner(System.`in`).nextLine()
    }


    println("Charset: ${charset.name().toUpperCase()}")

    val archiveFile = File("./testclustered2.tevd")

    println("Cleanup...")
    archiveFile.delete()


    println("Creating Archive")
    val diskFile = ClusteredFormatDOM.createNewArchive(archiveFile, charset, "TEST DRIVE 2", 600)
    val DOM = ClusteredFormatDOM(diskFile)


//    val rootFile = Clustfile(DOM, "/")
//    println("rootFile.exists = ${rootFile.exists()}")

    val file1 = Clustfile(DOM, "test.txt")
    println("Writing...")
    println("Successful? " + file1.overwrite("Testing! This text should be written on the FAT area!".toByteArray(charset)))

}