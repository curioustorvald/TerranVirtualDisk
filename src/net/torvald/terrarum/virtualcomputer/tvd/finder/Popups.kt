package net.torvald.terrarum.virtualcomputer.tvd.finder

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
    val capacity = JSpinner()
    val mainPanel = JPanel()
    val settingPanel = JPanel()

    init {
        capacity.model = SpinnerNumberModel(368640, 0, 1073741824, 1) // default 360 KiB, MAX 1 GiB
        mainPanel.layout = BorderLayout()
        settingPanel.layout = GridLayout(2, 2, 2, 0)

        name.text = "Unnamed"

        settingPanel.add(JLabel("Name (max 32 bytes)"), BorderLayout.WEST)
        settingPanel.add(name, BorderLayout.EAST)
        settingPanel.add(JLabel("Capacity (bytes)"), BorderLayout.WEST)
        settingPanel.add(capacity, BorderLayout.EAST)

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

class OptionFileNameAndCap {
    val name = JTextField(11)
    val capacity = JSpinner()
    val mainPanel = JPanel()
    val settingPanel = JPanel()

    init {
        capacity.model = SpinnerNumberModel(368640, 0, 1073741824, 1) // default 360 KiB, MAX 1 GiB
        mainPanel.layout = BorderLayout()
        settingPanel.layout = GridLayout(2, 2, 2, 0)

        name.text = "Unnamed"

        settingPanel.add(JLabel("Name (max 256 bytes)"), BorderLayout.WEST)
        settingPanel.add(name, BorderLayout.EAST)
        settingPanel.add(JLabel("Capacity (bytes)"), BorderLayout.WEST)
        settingPanel.add(capacity, BorderLayout.EAST)

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