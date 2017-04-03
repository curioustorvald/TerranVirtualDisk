package net.torvald.terrarum.virtualcomputer.tvd.finder

import net.torvald.terrarum.virtualcomputer.tvd.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.Charset
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.logging.Level
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.ListSelectionModel




/**
 * Created by SKYHi14 on 2017-04-01.
 */
class VirtualDiskCracker(val sysCharset: Charset = Charsets.UTF_8) : JFrame() {

    private val PREVIEW_MAX_BYTES = 4 * 1024 // 4 kBytes

    private val appName = "TerranVirtualDiskCracker"
    private val copyright = "Copyright 2017 Torvald (minjaesong). Distributed under MIT license."

    private val panelMain = JPanel()
    private val menuBar = JMenuBar()
    private val tableFiles: JTable
    private val fileDesc = JTextArea()
    private val diskInfo = JTextArea()
    private val statBar = JLabel("Open a disk or create new to get started")

    private var vdisk: VirtualDisk? = null
    private var clipboard: IndexNumber? = null

    private val labelPath = JLabel("(root)")
    private var currentDirectoryEntries: Array<DiskEntry>? = null
    private val directoryHierarchy = Stack<IndexNumber>(); init { directoryHierarchy.push(0) }

    private fun gotoSubDirectory(id: IndexNumber) {
        directoryHierarchy.push(id)
        labelPath.text = vdisk!!.entries[id]!!.getFilenameString(sysCharset)
        updateDiskInfo()
        updateCurrentDirectoryEntry()
    }
    val currentDirectory: IndexNumber
        get() = directoryHierarchy.peek()
    val parentDirectory: IndexNumber
        get() = if (directoryHierarchy.lastIndex == 0) 0
                else directoryHierarchy[directoryHierarchy.lastIndex - 1]
    private fun gotoRoot() {
        directoryHierarchy.removeAllElements()
        directoryHierarchy.push(0)
        updateDiskInfo()
        updateCurrentDirectoryEntry()
    }
    private fun gotoParent() {
        if (directoryHierarchy.size > 1)
            directoryHierarchy.pop()
        updateDiskInfo()
        updateCurrentDirectoryEntry()
    }



    private var currentlySelectedFile: IndexNumber? = null

    val tableColumns = arrayOf("Name", "Date Modified", "Size")
    val tableParentRecord = arrayOf(arrayOf("..", "", ""))

    init {
        panelMain.layout = BorderLayout()
        this.defaultCloseOperation = JFrame.EXIT_ON_CLOSE


        tableFiles = JTable(tableParentRecord, tableColumns)
        tableFiles.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val table = e.source as JTable
                val row = table.rowAtPoint(e.point)


                currentlySelectedFile = if (row > 0)
                    currentDirectoryEntries!![row - 1].indexNumber
                else
                    null // clicked ".."
                fileDesc.text = if (currentlySelectedFile != null)
                    getFileInfoText(vdisk!!.entries[currentlySelectedFile!!]!!)
                else
                    ""


                // double click
                if (e.clickCount == 2) {
                    if (row == 0) {
                        gotoParent()
                    }
                    else {
                        val record = currentDirectoryEntries!![row - 1]
                        if (record.contents is EntryDirectory) {
                            gotoSubDirectory(record.indexNumber)
                        }
                    }
                }
            }
        })
        tableFiles.selectionModel = object : DefaultListSelectionModel() {
            init { selectionMode = ListSelectionModel.SINGLE_SELECTION }
            override fun clearSelection() { } // required!
            override fun removeSelectionInterval(index0: Int, index1: Int) { } // required!
            override fun fireValueChanged(isAdjusting: Boolean) { } // required!

        }
        tableFiles.model = object : AbstractTableModel() {
            override fun getRowCount(): Int {
                return if (vdisk != null)
                    1 + (VDUtil.getAsDirectory(vdisk!!, currentDirectory).entries.size)
                else 1
            }

            override fun getColumnCount() = tableColumns.size

            override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
                if (rowIndex == 0) {
                    return tableParentRecord[0][columnIndex]
                }
                else {
                    if (vdisk != null) {
                        updateCurrentDirectoryEntry()
                        val entry = currentDirectoryEntries!![rowIndex - 1]
                        return when(columnIndex) {
                            0 -> entry.getFilenameString(sysCharset)
                            1 -> Instant.ofEpochSecond(entry.modificationDate).
                                    atZone(TimeZone.getDefault().toZoneId()).
                                    format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            2 -> entry.getEffectiveSize()
                            else -> ""
                        }
                    }
                    else {
                        return ""
                    }
                }
            }
        }



        val menuFile = JMenu("File")
        menuFile.mnemonic = KeyEvent.VK_F
        menuFile.add("New Disk…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                val makeNewDisk: Boolean
                if (vdisk != null) {
                    makeNewDisk = confirmedDiscard()
                }
                else {
                    makeNewDisk = true
                }
                if (makeNewDisk) {
                    // inquire new size
                    val dialogBox = OptionDiskNameAndCap()
                    val confirmNew = JOptionPane.OK_OPTION == dialogBox.showDialog("Set Property of New Disk")

                    if (confirmNew) {
                        vdisk = VDUtil.createNewDisk(
                                dialogBox.capacity.value as Int,
                                dialogBox.name.text,
                                sysCharset
                        )
                        gotoRoot()
                        updateDiskInfo()
                        setStat("Disk created")
                    }
                }
            }
        })
        menuFile.add("Open Disk…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                val makeNewDisk: Boolean
                if (vdisk != null) {
                    makeNewDisk = confirmedDiscard()
                }
                else {
                    makeNewDisk = true
                }
                if (makeNewDisk) {
                    val fileChooser = JFileChooser()
                    fileChooser.showOpenDialog(null)
                    if (fileChooser.selectedFile != null) {
                        try {
                            vdisk = VDUtil.readDiskArchive(fileChooser.selectedFile, Level.OFF)
                            gotoRoot()
                            updateDiskInfo()
                            setStat("Disk loaded")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            }
        })
        menuFile.addSeparator()
        menuFile.add("Save Disk as…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null) {
                    val fileChooser = JFileChooser()
                    fileChooser.showOpenDialog(null)
                    if (fileChooser.selectedFile != null) {
                        try {
                            VDUtil.dumpToRealMachine(vdisk!!, fileChooser.selectedFile)
                            setStat("Disk saved")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            }
        })
        menuBar.add(menuFile)

        val menuEdit = JMenu("Edit")
        menuEdit.mnemonic = KeyEvent.VK_E
        menuEdit.add("New File…").addMouseListener(object: MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null) {
                    try {
                        val dialogBox = OptionFileNameAndCap()
                        val confirmNew = JOptionPane.OK_OPTION == dialogBox.showDialog("Set Property of New File")

                        if (confirmNew) {
                            if (VDUtil.nameExists(vdisk!!, dialogBox.name.text, currentDirectory, sysCharset)) {
                                popupError("The name already exists")
                            }
                            else {
                                VDUtil.createNewBlankFile(
                                        vdisk!!,
                                        currentDirectory,
                                        dialogBox.capacity.value as Int,
                                        dialogBox.name.text,
                                        sysCharset
                                )
                                updateDiskInfo()
                                setStat("File created")
                            }
                        }
                    }
                    catch (e: Exception) {
                        e.printStackTrace()
                        popupError(e.toString())
                    }
                }
            }
        })
        menuEdit.add("New Directory…").addMouseListener(object: MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null) {
                    val newname = JOptionPane.showInputDialog("Enter a new directory name:")
                    if (newname != null) {
                        try {
                            if (VDUtil.nameExists(vdisk!!, newname, currentDirectory, sysCharset)) {
                                popupError("The name already exists")
                            }
                            else {
                                VDUtil.addDir(vdisk!!, currentDirectory, newname)
                                updateDiskInfo()
                                setStat("Directory created")
                            }
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            }
        })
        menuEdit.add("New Symbolic Link…")
        menuEdit.addSeparator()
        menuEdit.add("Cut").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                // copy
                clipboard = currentlySelectedFile

                // delete
                if (vdisk != null && currentlySelectedFile != null) {
                    try {
                        VDUtil.deleteFile(vdisk!!, parentDirectory, currentlySelectedFile!!)
                        updateDiskInfo()
                        setStat("File deleted")
                    }
                    catch (e: Exception) {
                        e.printStackTrace()
                        popupError(e.toString())
                    }
                }
            }
        })
        menuEdit.add("Copy").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                clipboard = currentlySelectedFile
            }
        })
        menuEdit.add("Paste")
        menuEdit.add("Delete").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null && currentlySelectedFile != null) {
                    try {
                        VDUtil.deleteFile(vdisk!!, parentDirectory, currentlySelectedFile!!)
                        updateDiskInfo()
                        setStat("File deleted")
                    }
                    catch (e: Exception) {
                        e.printStackTrace()
                        popupError(e.toString())
                    }
                }
            }
        })
        menuEdit.add("Rename…")
        menuEdit.add("Look Clipboard").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                popupMessage(if (clipboard != null)
                    "Entry index $clipboard\n${vdisk?.entries?.get(clipboard!!) ?: "(Bug found!)"}"
                        else "(nothing)", "Clipboard")
            }
        })
        menuEdit.addSeparator()
        menuEdit.add("Import File…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null) {
                    val fileChooser = JFileChooser()
                    fileChooser.showOpenDialog(null)
                    if (fileChooser.selectedFile != null) {
                        try {
                            val entry = VDUtil.importFile(fileChooser.selectedFile, vdisk!!.generateUniqueID())
                            VDUtil.addFile(vdisk!!, currentDirectory, entry)
                            updateDiskInfo()
                            setStat("File added")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            }
        })
        menuEdit.add("Export File…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null && currentlySelectedFile != null) {
                    val file = vdisk!!.entries[currentlySelectedFile!!]!!

                    if (file.contents is EntryFile || file.contents is EntrySymlink) {
                        val fileChooser = JFileChooser()
                        fileChooser.showOpenDialog(null)
                        if (fileChooser.selectedFile != null) {
                            try {
                                val file = VDUtil.resolveIfSymlink(vdisk!!, file.indexNumber)
                                if (file.contents is EntryFile) {
                                    fileChooser.selectedFile.createNewFile()
                                    fileChooser.selectedFile.writeBytes(file.contents.bytes)
                                }
                                else {
                                    popupError("Cannot export directory")
                                }
                            }
                            catch (e: Exception) {
                                e.printStackTrace()
                                popupError(e.toString())
                            }
                        }
                    }
                    else if (file.contents is EntryDirectory) {
                        popupError("Cannot export directory")
                    }
                }
            }
        })
        menuEdit.addSeparator()
        menuEdit.add("Rename Disk…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null) {
                    val newname = JOptionPane.showInputDialog("Enter a new directory name:")
                    if (newname != null) {
                        vdisk!!.diskName = newname.toEntryName(VirtualDisk.NAME_LENGTH, sysCharset)
                        updateDiskInfo()
                        setStat("Disk renamed")
                    }
                }
            }
        })
        menuEdit.add("Resize Disk…").addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (vdisk != null) {
                    val dialog = OptionSize()
                    val confirmed = dialog.showDialog("Input") == JOptionPane.OK_OPTION
                    if (confirmed) {
                        vdisk!!.capacity = dialog.capacity.value as Int
                        updateDiskInfo()
                        setStat("Disk resized")
                    }
                }
            }
        })
        menuBar.add(menuEdit)

        val menuAbout = JMenu("About")
        menuAbout.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                popupMessage(copyright, "Copyright")
            }
        })
        menuBar.add(menuAbout)



        diskInfo.highlighter = null
        diskInfo.text = "(Disk not loaded)"
        diskInfo.preferredSize = Dimension(-1, 60)

        fileDesc.highlighter = null
        fileDesc.text = ""

        val fileDescScroll = JScrollPane(fileDesc)
        val tableFilesScroll = JScrollPane(tableFiles)

        val panelFinder = JPanel(BorderLayout())
        panelFinder.add(labelPath, BorderLayout.NORTH)
        panelFinder.add(tableFilesScroll, BorderLayout.CENTER)

        val panelFileDesc = JPanel(BorderLayout())
        panelFileDesc.add(JLabel("Entry Information"), BorderLayout.NORTH)
        panelFileDesc.add(fileDescScroll, BorderLayout.CENTER)

        val filesSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelFinder, panelFileDesc)
        filesSplit.resizeWeight = 0.9


        val panelDiskOp = JPanel(BorderLayout(2, 2))
        panelDiskOp.add(filesSplit, BorderLayout.CENTER)
        panelDiskOp.add(diskInfo, BorderLayout.SOUTH)


        panelMain.add(menuBar, BorderLayout.NORTH)
        panelMain.add(panelDiskOp, BorderLayout.CENTER)
        panelMain.add(statBar, BorderLayout.SOUTH)


        this.title = appName
        this.add(panelMain)
        this.setSize(700, 700)
        this.isVisible = true
    }

    private fun confirmedDiscard() = 0 == JOptionPane.showOptionDialog(
            null, // parent
            "Any changes to current disk will be discarded. Continue?",
            "Confirm Discard", // window title
            JOptionPane.DEFAULT_OPTION, // option type
            JOptionPane.WARNING_MESSAGE, // message type
            null, // icon
            Popups.okCancel, // options (provided by JOptionPane.OK_CANCEL_OPTION in this case)
            Popups.okCancel[1] // default selection
    )
    private fun popupMessage(message: String, title: String = "") {
        JOptionPane.showOptionDialog(
                null,
                message,
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null, null, null
        )
    }
    private fun popupError(message: String, title: String = "Uh oh…") {
        JOptionPane.showOptionDialog(
                null,
                message,
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.ERROR_MESSAGE,
                null, null, null
        )
    }
    private fun updateCurrentDirectoryEntry() {
        currentDirectoryEntries = VDUtil.getDirectoryEntries(vdisk!!, currentDirectory)
    }
    private fun updateDiskInfo() {
        val sb = StringBuilder()
        directoryHierarchy.forEach {
            sb.append(vdisk!!.entries[it]!!.getFilenameString(sysCharset))
            sb.append('/')
        }
        sb.dropLast(1)
        labelPath.text = sb.toString()

        diskInfo.text = if (vdisk == null) "(Disk not loaded)" else getDiskInfoText(vdisk!!)
        tableFiles.revalidate()
        tableFiles.repaint()
    }
    private fun getDiskInfoText(disk: VirtualDisk): String {
        return """Name: ${String(disk.diskName, sysCharset)}
Capacity: ${disk.capacity} bytes (${disk.usedBytes} bytes used)"""
    }
    private fun getFileInfoText(file: DiskEntry): String {
        return """Name: ${file.getFilenameString(sysCharset)}
Size: ${file.getEffectiveSize()}
Type: ${DiskEntry.getTypeString(file.contents)}
CRC: ${file.hashCode()}""" + if (file.contents is EntryFile) """

Contents:
${String(file.contents.bytes.sliceArray(0..minOf(PREVIEW_MAX_BYTES, file.contents.bytes.size)), sysCharset)}""" else ""
    }
    private fun Int.bytes() = if (this == 1) "1 byte" else "$this bytes"
    private fun Int.entries() = if (this == 1) "1 entry" else "$this entries"
    private fun DiskEntry.getEffectiveSize() = if (this.contents is EntryFile)
        this.contents.getSizePure().bytes()
    else if (this.contents is EntryDirectory)
        this.contents.entries.size.entries()
    else if (this.contents is EntrySymlink)
        "(symlink)"
    else
        "n/a"
    private fun setStat(message: String) {
        statBar.text = message
    }
}

fun main(args: Array<String>) {
    VirtualDiskCracker(Charset.forName("CP437"))
}