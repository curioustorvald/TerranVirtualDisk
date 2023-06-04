package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.finder

import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

/**
 * Created by SKYHi14 on 2017-04-01.
 */
object Popups {
    val okCancel = arrayOf("OK", "Cancel")

}

class OptionDiskNameAndCap {
    val name = JTextField(11)
    val capacity = JSpinner(SpinnerNumberModel(
            368640L.toJavaLong(),
            0L.toJavaLong(),
            (1L shl 38).toJavaLong(),
            1L.toJavaLong()
    )) // default 360 KiB, MAX 256 GiB
    val mainPanel = JPanel()
    val settingPanel = JPanel()

    init {
        mainPanel.layout = BorderLayout()
        settingPanel.layout = GridLayout(2, 2, 2, 2)

        //name.text = "Unnamed"

        settingPanel.add(JLabel("Name (max 32 bytes)"))
        settingPanel.add(name)
        settingPanel.add(JLabel("Capacity (bytes)"))
        settingPanel.add(capacity)

        mainPanel.add(settingPanel, BorderLayout.CENTER)
        mainPanel.add(JLabel("Set capacity to 0 to make the disk read-only"), BorderLayout.SOUTH)
    }

    /**
     * returns either JOptionPane.OK_OPTION or JOptionPane.CANCEL_OPTION
     */
    fun showDialog(title: String): Int {
        return JOptionPane.showConfirmDialog(null, mainPanel,
                title, JOptionPane.OK_CANCEL_OPTION)
    }
}

class OptionDiskNameAndCapSectors {
    val name = JTextField(11)
    val capacity = JSpinner(SpinnerNumberModel(
            480L.toJavaLong(),
            0L.toJavaLong(),
            0xF00000L.toJavaLong(),
            1L.toJavaLong()
    )) // default 360 KiB, MAX 256 GiB
    val mainPanel = JPanel()
    val settingPanel = JPanel()

    init {
        mainPanel.layout = BorderLayout()
        settingPanel.layout = GridLayout(2, 2, 2, 2)

        //name.text = "Unnamed"

        settingPanel.add(JLabel("Name (max 32 bytes)"))
        settingPanel.add(name)
        settingPanel.add(JLabel("Capacity in Sectors (1 sect = 4096 bytes)"))
        settingPanel.add(capacity)

        mainPanel.add(settingPanel, BorderLayout.CENTER)
        mainPanel.add(JLabel(""), BorderLayout.SOUTH)
    }

    /**
     * returns either JOptionPane.OK_OPTION or JOptionPane.CANCEL_OPTION
     */
    fun showDialog(title: String): Int {
        return JOptionPane.showConfirmDialog(null, mainPanel,
                title, JOptionPane.OK_CANCEL_OPTION)
    }
}

class OptionDwarvenTechDiskSelectors {
    val name = JTextField(11)
    val capacity = listOf(
            JRadioButton("Low FDD (~1 MB)"),
            JRadioButton("Low HDD (10 MB)"),
            JRadioButton("Mid FDD (~2 MB)"),
            JRadioButton("Mid HDD (20 MB)"),
            JRadioButton("High FDD (~4 MB)"),
            JRadioButton("High HDD (40 MB)"),
    )
    val mainPanel = JPanel()

    init {
        mainPanel.layout = BorderLayout()

        //name.text = "Unnamed"

        JPanel().also {
            it.layout = GridLayout(1, 2, 2, 2)
            it.add(JLabel("Name (max 32 bytes)"))
            it.add(name)

            mainPanel.add(it, BorderLayout.NORTH)
        }
        JPanel().also {
            it.layout = GridLayout(3, 2, 2, 2)
            capacity.forEach { btn -> it.add(btn) }

            mainPanel.add(it, BorderLayout.CENTER)
        }

        mainPanel.add(JLabel(""), BorderLayout.SOUTH)
    }

    /**
     * returns either JOptionPane.OK_OPTION or JOptionPane.CANCEL_OPTION
     */
    fun showDialog(title: String): Int {
        return JOptionPane.showConfirmDialog(null, mainPanel,
                title, JOptionPane.OK_CANCEL_OPTION)
    }
}

fun kotlin.Long.toJavaLong() = java.lang.Long(this)

class OptionFileNameAndCap {
    val name = JTextField(11)
    val capacity = JSpinner(SpinnerNumberModel(
            4096L.toJavaLong(),
            0L.toJavaLong(),
            ((1L shl 48) - 1L).toJavaLong(),
            1L.toJavaLong()
    )) // default 360 KiB, MAX 256 TiB
    val mainPanel = JPanel()
    val settingPanel = JPanel()

    init {
        mainPanel.layout = BorderLayout()
        settingPanel.layout = GridLayout(2, 2, 2, 0)

        //name.text = "Unnamed"

        settingPanel.add(JLabel("Name (max 32 bytes)"))
        settingPanel.add(name)
        settingPanel.add(JLabel("Capacity (bytes)"))
        settingPanel.add(capacity)

        mainPanel.add(settingPanel, BorderLayout.CENTER)
    }

    /**
     * returns either JOptionPane.OK_OPTION or JOptionPane.CANCEL_OPTION
     */
    fun showDialog(title: String): Int {
        return JOptionPane.showConfirmDialog(null, mainPanel,
                title, JOptionPane.OK_CANCEL_OPTION)
    }
}

class OptionSize {
    val capacity = JSpinner(SpinnerNumberModel(
            368640L.toJavaLong(),
            0L.toJavaLong(),
            (1L shl 38).toJavaLong(),
            1L.toJavaLong()
    )) // default 360 KiB, MAX 256 GiB
    val settingPanel = JPanel()

    init {
        settingPanel.add(JLabel("Size (bytes)"))
        settingPanel.add(capacity)
    }

    /**
     * returns either JOptionPane.OK_OPTION or JOptionPane.CANCEL_OPTION
     */
    fun showDialog(title: String): Int {
        return JOptionPane.showConfirmDialog(null, settingPanel,
                title, JOptionPane.OK_CANCEL_OPTION)
    }
}
