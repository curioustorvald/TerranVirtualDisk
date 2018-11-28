package net.torvald.terrarum.virtualcomputer.tvd

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DiskSkimmerTest {

    val fullBattery = listOf(
            { invoke00() }
    )

    operator fun invoke() {
        fullBattery.forEach { it.invoke() }
    }

    /**
     * Testing of DiskSkimmer
     */
    fun invoke00() {
        val _infile = File("./test-assets/tevd-test-suite-00.tevd")
        val outfile = File("./test-assets/tevd-test-suite-00_results.tevd")

        Files.copy(_infile.toPath(), outfile.toPath(), StandardCopyOption.REPLACE_EXISTING)

/*
Copied from instruction.txt

1. Create a file named "World!.txt" in the root directory.
2. Append "This is not SimCity 3k" on the file ./01_preamble/append-after-me
3. Delete a file ./01_preamble/deleteme
4. Modify this very file, delete everything and simply replace with "Mischief Managed."
5. Read the file ./instruction.txt and print its contents.

Expected console output:

Mischief Managed.
 */
        val skimmer = DiskSkimmer(outfile)

        // step 0
        val testfile = skimmer.requestFile(1403168679)!!
        println((testfile.contents as EntryFile).bytes.toByteArray().toString(Charset.defaultCharset()))

        val testfile2 = skimmer.requestFile(-1483001307)!!
        println((testfile2.contents as EntryFile).bytes.toByteArray().toString(Charset.defaultCharset()))

        val testfile3 = skimmer.requestFile("01_preamble/append-after-me")!!
        println((testfile3.contents as EntryFile).bytes.toByteArray().toString(Charset.defaultCharset()))


        println("=============================")

        // step 1
        val worldFile = ByteArray64.fromByteArray("sure, whatevs".toByteArray())
        skimmer.createNewFile("World!.txt", worldFile)

        // step 2
        /*val appendFile = skimmer.requestFile("01_preamble/append-after-me")!!
        val newText = (appendFile.contents as EntryFile).bytes.toByteArray() + "This is not SimCity 3k".toByteArray()
        skimmer.createNewFile("01_preamble/append-after-me", ByteArray64.fromByteArray(newText), true)*/

        // step 3
        /*val deleteFile = skimmer.requestFile("01_preamble/deleteme")!!
        skimmer.deleteEntry(deleteFile.entryID)*/
    }
}

fun main(args: Array<String>) {
    DiskSkimmerTest()
}