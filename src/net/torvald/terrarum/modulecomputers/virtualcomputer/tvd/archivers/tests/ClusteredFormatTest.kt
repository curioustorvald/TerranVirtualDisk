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

    println("Allocating a longfile")
    val longfile = DOM.allocateFile(3000, 0, "Longfile3000")

    println("Writing a longfile")
    val longtext = "This is a long file!".toByteArray(charset)
    DOM.writeBytes(longfile, longtext, 0, longtext.size, 0, 0)

    println("Writing done, trying to read what has written")
    val whatsWritten = DOM.readBytes(longfile, longtext.size, 0)

    println(whatsWritten.toString(charset))
}
