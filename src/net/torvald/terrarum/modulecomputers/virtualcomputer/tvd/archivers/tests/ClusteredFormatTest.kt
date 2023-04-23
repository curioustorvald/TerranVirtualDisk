import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM
import java.io.File

/**
 * Created by minjaesong on 2023-04-23.
 */

fun main(args: Array<String>) {

    val charset = Charsets.UTF_8
    val archiveFile = File("./testclustered.tevd")

    println("Cleanup...")
    archiveFile.delete()


    println("Creating Archive")
    val diskFile = ClusteredFormatDOM.createNewArchive(archiveFile, charset, "TEST DRIVE", 600)
    val DOM = ClusteredFormatDOM(diskFile, charset)


}
