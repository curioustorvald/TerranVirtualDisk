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
        capacity.model = SpinnerNumberModel(368640, 0, 2147483647, 1) // default 360 KiB, MAX 2 GiB
        mainPanel.layout = BorderLayout()
        settingPanel.layout = GridLayout(2, 2, 2, 0)

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

class OptionFileNameAndCap {
    val name = JTextField(11)
    val capacity = JSpinner()
    val mainPanel = JPanel()
    val settingPanel = JPanel()

    init {
        capacity.model = SpinnerNumberModel(4096, 0, 2147483647, 1) // default 360 KiB, MAX 2 GiB
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
    val capacity = JSpinner()
    val settingPanel = JPanel()

    init {
        capacity.model = SpinnerNumberModel(368640, 0, 2147483647, 1) // default 360 KiB, MAX 2 GiB

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
