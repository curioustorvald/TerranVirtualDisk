package net.torvald.terrarum.virtualcomputer.tvd

import java.io.File

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
        val infile = File("./test-assets/tevd-test-suite-00.tevd")
        val outfile = File("./test-assets/tevd-test-suite-00_results.tevd")
    }

}