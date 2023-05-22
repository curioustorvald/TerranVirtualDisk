import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.Clustfile
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClustfileInputStream
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

    println("Successful? " + file1.pwrite(superlongtext, 0, superlongtext.size, 256))

    println(ClustfileInputStream(file1).readAllBytes().toString(charset))

    testPause("")

    val root_bin = Clustfile(DOM, "/bin").also {
        it.mkdir()
    }

    testPause("new file '/bin' created; check the archive")

    val root_bin_file = Clustfile(DOM, "/bin/foo.bar").also {
        "println('Hello, world!');return 0;".toByteArray(charset).let { t ->
            it.pwrite(t, 0, t.size, 0)
        }
    }

    println(ClustfileInputStream(root_bin_file).readAllBytes().toString(charset))

    testPause("")

    val root = Clustfile(DOM, "/").also {
        println("Files in '/': ")
        it.listFiles()?.forEach {
            println(it.getPath() + (if (it.isDirectory()) "/" else ""))
        }
    }
}