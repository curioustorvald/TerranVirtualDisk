package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.finder

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.DiskSkimmer.Companion.read
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.EntryID
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.*
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.CLUSTER_SIZE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FATS_PER_CLUSTER
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FAT_ENTRY_SIZE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_BINARY
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.FILETYPE_DIRECTORY
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM.Companion.INLINE_FILE_CLUSTER_BASE
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toUint
import java.awt.*
import java.awt.event.*
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.text.DefaultCaret


/**
 * Original Cracker Created by SKYHi14 on 2017-04-01.
 * Cracker for Clustered Format Created by minjaesong on 2023-05-22.
 */
class VirtualDiskCrackerClustered() : JFrame() {


    private val annoyHackers = false // Jar build settings. Intended for Terrarum proj.


    private val PREVIEW_MAX_BYTES = 1048576L

    private val appName = "TerranVirtualDiskCracker"
    private val copyright = "Copyright 2017-18, 2022-2023 CuriousTorvald (minjaesong). Distributed under MIT license."

    private val magicOpen = "I solemnly swear that I am up to no good."
    private val magicSave = "Mischief managed."
    private val annoyWhenLaunchMsg = "Type in following to get started:\n$magicOpen"
    private val annoyWhenSaveMsg = "Type in following to save:\n$magicSave"

    private val panelMain = JPanel()
    private val menuBar = JMenuBar()
    private val tableFiles: JTable
    private val tableEntries: JTable
    private val bootEditPane = JTextArea()
    private val fileDesc = JTextArea()
    private val diskInfo = JTextArea()
    private val clustmapGrid = JPanel(GridLayout())
    private val statBar = JLabel("Open a disk or create new to get started")

    private var vdisk: ClusteredFormatDOM? = null
    private var clipboard: Clustfile? = null
    private var clipboardCutMode = false
    private val rootFile; get() = Clustfile(vdisk!!, "/")

    private val labelPath = JLabel("(root)")
    private var currentDirectoryEntries: Array<Clustfile>? = null
    private val directoryHierarchy = Stack<Clustfile>()

    private lateinit var originalFile: File
    private lateinit var swapFile: File
    private lateinit var backupFile: File



    private fun deselect() {
        selectedFile = null
        fileDesc.text = ""
        tableFiles.clearSelection()
        tableEntries.clearSelection()
    }
    private fun gotoSubDirectory(file: Clustfile) {
        directoryHierarchy.push(file)
        labelPath.text = file.name
        deselect()
        updateDiskInfo()
    }
    val currentDirectory: Clustfile
        get() = directoryHierarchy.peek()
    val upperDirectory: Clustfile
        get() = if (directoryHierarchy.lastIndex == 0) rootFile
        else directoryHierarchy[directoryHierarchy.lastIndex - 1]
    private fun gotoRoot() {
        directoryHierarchy.removeAllElements()
        directoryHierarchy.push(rootFile)
        deselect()
        updateDiskInfo()
    }
    private fun gotoParent() {
        if (directoryHierarchy.size > 1)
            directoryHierarchy.pop()
        deselect()
        updateDiskInfo()
    }



    private var selectedFile: Clustfile? = null

    val tableColumns = arrayOf("Name", "Date Modified", "Size")
    val tableParentRecord = arrayOf(arrayOf("..", "", ""))

    val tableColumnsEntriesMode = arrayOf("Name", "FAT+idx", "Type", "Size", "Created", "Modified", "# Ext")
    var tableEntriesRecord = arrayOf(arrayOf("", "", "", "", "", "", ""))

    private val tableFilesPreferredSize = arrayOf(240, 120, 80)
    private val tableEntriesPreferredSize = arrayOf(120, 64, 24, 48, 36, 36, 18)



    private val panelFinderTab: JTabbedPane

    private val inEntriesMode: Boolean
        get() = panelFinderTab.selectedIndex == 1

    private val isMacos = UIManager.getSystemLookAndFeelClassName().startsWith("com.apple.")

    init {

        if (annoyHackers) {
            val mantra = JOptionPane.showInputDialog(annoyWhenLaunchMsg)
            if (mantra != magicOpen) {
                System.exit(1)
            }
        }

        panelMain.layout = BorderLayout()
        this.defaultCloseOperation = EXIT_ON_CLOSE

        // delete swap files (if any) on exit
        this.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                if (::swapFile.isInitialized) {
                    swapFile.delete()
                }
            }
        })


        tableFiles = JTable(tableParentRecord, tableColumns).apply {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val table = e.source as JTable
                    val row = table.rowAtPoint(e.point)


                    selectedFile = if (row > 0)
                        currentDirectoryEntries!![row - 1]
                    else
                        null // clicked ".."


                    fileDesc.text = getFileInfoText(selectedFile)

                    fileDesc.caretPosition = 0

                    // double click
                    if (e.clickCount == 2) {
                        if (row == 0) {
                            gotoParent()
                        }
                        else {
                            val record = currentDirectoryEntries!![row - 1]
                            if (record.isDirectory) {
                                gotoSubDirectory(record)
                            }
                        }
                    }
                }
            })
            selectionModel = object : DefaultListSelectionModel() {
                init {
                    selectionMode = ListSelectionModel.SINGLE_SELECTION
                }

                override fun clearSelection() {} // required!
                override fun removeSelectionInterval(index0: Int, index1: Int) {} // required!
                override fun fireValueChanged(isAdjusting: Boolean) {} // required!
            }
            model = object : AbstractTableModel() {
                override fun getRowCount(): Int {
                    return if (vdisk != null)
                        1 + (currentDirectoryEntries?.size ?: 0)
                    else 1
                }

                override fun getColumnCount() = tableColumns.size

                override fun getColumnName(column: Int) = tableColumns[column]

                override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
                    if (rowIndex == 0) {
                        return tableParentRecord[0][columnIndex]
                    }
                    else {
                        if (vdisk != null) {
                            val entry = currentDirectoryEntries!![rowIndex - 1]
                            return when (columnIndex) {
                                0 -> entry.name + (if (entry.isDirectory) "/" else "")
                                1 -> Instant.ofEpochSecond(entry.lastModified()).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                2 -> "${entry.getEffectiveSize()} ${if (entry.isDirectory) "entries" else "bytes"}"
                                else -> ""
                            }
                        }
                        else {
                            return ""
                        }
                    }
                }


            }

            (0..2).forEach { this.columnModel.getColumn(it).preferredWidth = tableFilesPreferredSize[it] }
            this.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        }



        tableEntries = JTable(tableEntriesRecord, tableColumnsEntriesMode).apply {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val table = e.source as JTable
                    val row = table.rowAtPoint(e.point)

                    selectedFile = null
                    fileDesc.text = getFileInfoText(vdisk?.getFile((tableEntriesRecord[row][7]).toInt()))
                    fileDesc.caretPosition = 0
                }
            })
            selectionModel = object : DefaultListSelectionModel() {
                init {
                    selectionMode = ListSelectionModel.SINGLE_SELECTION
                }

                override fun clearSelection() {} // required!
                override fun removeSelectionInterval(index0: Int, index1: Int) {} // required!
                override fun fireValueChanged(isAdjusting: Boolean) {} // required!
            }
            model = object : AbstractTableModel() {
                override fun getRowCount() = tableEntriesRecord.size

                override fun getColumnCount() = tableColumnsEntriesMode.size

                override fun getColumnName(column: Int) = tableColumnsEntriesMode[column]

                override fun getValueAt(rowIndex: Int, columnIndex: Int): String? {
                    return if (vdisk != null) {
                        tableEntriesRecord[rowIndex][columnIndex]
                    }
                    else {
                        ""
                    }
                }
            }

            (0..6).forEach { this.columnModel.getColumn(it).preferredWidth = tableEntriesPreferredSize[it] }
            this.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        }



        JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add("New Disk…").apply { this.mnemonic = KeyEvent.VK_N }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    val makeNewDisk = if (vdisk != null) confirmedDiscard() else true
                    if (makeNewDisk) {
                        // show fileChooser dialogue to specify where the new file goes, then create new archive from it
                        val fileChooser = JFileChooser("./")
                        fileChooser.showSaveDialog(null)
                        if (fileChooser.selectedFile != null) {
                            try {
                                // new disk size (in clusters)?
                                val dialogBox = OptionDiskNameAndCapSectors()
                                val confirmNew = JOptionPane.OK_OPTION == dialogBox.showDialog("Set Property of New Disk")

                                if (confirmNew) {
                                    val diskName = dialogBox.name.text
                                    val diskSize = (dialogBox.capacity.value as Long).toInt()

                                    originalFile = fileChooser.selectedFile!!
                                    swapFile = File(originalFile.absolutePath + ".swap")
                                    backupFile = File(originalFile.absolutePath + ".bak")

                                    // create new file
                                    ClusteredFormatDOM.createNewArchive(originalFile, Charsets.ISO_8859_1, diskName, diskSize)

                                    originalFile.copyTo(swapFile, true)
                                    // all the editing is done on the swap file
                                    vdisk = ClusteredFormatArchiver(null).deserialize(swapFile, null)
                                    bootEditPane.text = ""

                                    gotoRoot()
                                    updateDiskInfo()
                                    setWindowTitleWithName(fileChooser.selectedFile.canonicalPath)
                                    setStat("Disk created")
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
            add("New Dwarventech-Compatible Disk…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    val makeNewDisk = if (vdisk != null) confirmedDiscard() else true
                    if (makeNewDisk) {
                        // show fileChooser dialogue to specify where the new file goes, then create new archive from it
                        val fileChooser = JFileChooser("./")
                        fileChooser.showSaveDialog(null)
                        if (fileChooser.selectedFile != null) {
                            try {
                                // new disk size (in clusters)?
                                val dialogBox = OptionDwarvenTechDiskSelectors()
                                val confirmNew = JOptionPane.OK_OPTION == dialogBox.showDialog("Set Property of New Disk")

                                val diskProps = listOf(
                                        240 to 0x1001,
                                        2500 to 0x2001,
                                        480 to 0x1002,
                                        5000 to 0x2002,
                                        960 to 0x1003,
                                        10000 to 0x2003
                                )

                                if (confirmNew) {
                                    val diskName = dialogBox.name.text
                                    var optionSelected = -1
                                    dialogBox.capacity.forEachIndexed { index, b ->
                                        if (b.isSelected) optionSelected = index
                                    }
                                    if (optionSelected == -1) {
                                        return
                                    }

                                    val diskSize = diskProps[optionSelected].first
                                    val diskExtraAttribs = ByteArray(2).also { it.writeInt16(diskProps[optionSelected].second, 0) }

                                    originalFile = fileChooser.selectedFile!!
                                    swapFile = File(originalFile.absolutePath + ".swap")
                                    backupFile = File(originalFile.absolutePath + ".bak")

                                    // create new file
                                    ClusteredFormatDOM.createNewArchive(originalFile, Charsets.ISO_8859_1, diskName, diskSize, diskExtraAttribs)

                                    originalFile.copyTo(swapFile, true)
                                    // all the editing is done on the swap file
                                    vdisk = ClusteredFormatArchiver(null).deserialize(swapFile, null)
                                    bootEditPane.text = ""

                                    gotoRoot()
                                    updateDiskInfo()
                                    setWindowTitleWithName(fileChooser.selectedFile.canonicalPath)
                                    setStat("Disk created")
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
            add("Open Disk…").apply { this.mnemonic = KeyEvent.VK_O }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    val makeNewDisk = if (vdisk != null) confirmedDiscard() else true
                    if (makeNewDisk) {
                        val fileChooser = JFileChooser("./")
                        fileChooser.showOpenDialog(null)
                        if (fileChooser.selectedFile != null && fileChooser.selectedFile.isFile) {
                            try {
                                // create swap file
                                originalFile = fileChooser.selectedFile!!
                                swapFile = File(originalFile.absolutePath + ".swap")
                                backupFile = File(originalFile.absolutePath + ".bak")

                                originalFile.copyTo(swapFile, true)
                                // all the editing is done on the swap file
                                vdisk = ClusteredFormatArchiver(null).deserialize(swapFile, null)
                                bootEditPane.text = vdisk?.readBoot()?.toString(vdisk?.charset ?: Charsets.ISO_8859_1) ?: ""

                                gotoRoot()
                                updateDiskInfo()
                                setWindowTitleWithName(fileChooser.selectedFile.canonicalPath)
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
            addSeparator()
            add("Save Disk").apply { this.mnemonic = KeyEvent.VK_S }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {


                        if (annoyHackers) {
                            val mantra = JOptionPane.showInputDialog(annoyWhenSaveMsg)
                            if (mantra != magicSave) {
                                popupError("Nope!")
                                return
                            }
                        }

                        try {
                            originalFile.copyTo(backupFile, true)
                            swapFile.copyTo(originalFile, true)
                            setStat("Disk saved")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })
            addSeparator()
            add("Refresh").apply { this.mnemonic = KeyEvent.VK_R }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        updateDiskInfo()
                        updateCurrentDirectory()
                    }
                }
            })
            menuBar.add(this)
        }



        JMenu("Edit").apply {
            mnemonic = KeyEvent.VK_E
            add("New File…").apply { this.mnemonic = KeyEvent.VK_F }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val dialogBox = OptionFileNameAndCap()
                            val confirmNew = JOptionPane.OK_OPTION == dialogBox.showDialog("Set Property of New File")
                            val newname = dialogBox.name.text
                            if (confirmNew) {
                                val target = Clustfile(vdisk!!, currentDirectory, newname)
                                if (target.exists()) {
                                    popupError("The name already exists")
                                    setStat("File creation cancelled")
                                }
                                else {
                                    target.createNewFile().let {
                                        if (!it) throw Error("Directory creation failed")
                                        target.writeBytes(ByteArray((dialogBox.capacity.value as Long).toInt()))
                                    }
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
            add("New Directory…").apply { this.mnemonic = KeyEvent.VK_D }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (inEntriesMode) {
                        popupError("File cannot be added in FAT Browsing mode")
                    }
                    else if (vdisk != null) {
                        val newname = JOptionPane.showInputDialog("Enter a new directory name:")
                        if (newname != null) {
                            try {
                                val target = Clustfile(vdisk!!, currentDirectory, newname)
                                if (target.exists()) {
                                    popupError("The directory already exists")
                                    setStat("Directory creation cancelled")
                                }
                                else {
                                    target.mkdir().let {
                                        if (!it) throw Error("Directory creation failed")
                                    }
                                    updateDiskInfo()
                                    setStat("Directory created")
                                }
                            }
                            catch (e: Exception) {
                                e.printStackTrace()
                                popupError(e.toString())
                                setStat("Directory creation failed")
                            }
                        }
                    }
                }
            })
            addSeparator()
            add("Cut").apply { this.mnemonic = KeyEvent.VK_X }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    clipboard = selectedFile
                    clipboardCutMode = true
                    setStat("File cut to clipboard. Note that the source file will not be deleted until the Paste operation")
                }
            })
            add("Copy").apply { this.mnemonic = KeyEvent.VK_C }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    clipboard = selectedFile
                    clipboardCutMode = false
                    setStat("File copied to clipboard")
                }
            })
            add("Paste").apply { this.mnemonic = KeyEvent.VK_V }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    fun paste1(newfile: Clustfile) {
                        try {
                            clipboard!!.readBytes().let {
                                newfile.createNewFile()
                                newfile.pwrite(it, 0, it.size, 0)
                            }

                            updateDiskInfo()
                            setStat("File pasted")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                    /*fun pasteForEntryMode(newname: ByteArray, newEntryID: Int?, newParentEntryID: Int?) {
                        try {
                            val newEntryID = newEntryID ?: vdisk!!.generateUniqueID()
                            val newParentEntryID = newParentEntryID ?: 0
                            if (vdisk!!.entries[newParentEntryID] != null) {
                                VDUtil.addFile(vdisk!!, newParentEntryID, DiskEntry(// clone
                                        newEntryID,
                                        newParentEntryID,
                                        newname,
                                        clipboard!!.creationDate,
                                        clipboard!!.modificationDate,
                                        clipboard!!.contents
                                ))
                            }
                            else {
                                vdisk!!.entries[newEntryID] = DiskEntry(
                                        newEntryID,
                                        newParentEntryID,
                                        newname,
                                        clipboard!!.creationDate,
                                        clipboard!!.modificationDate,
                                        clipboard!!.contents
                                )
                            }

                            updateDiskInfo()
                            setStat("File pasted")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }*/
                    if (clipboard != null && vdisk != null) {
                        if (inEntriesMode) {
                            /*val newname = JOptionPane.showInputDialog("Enter a new name: (1 of 3 params)")
                            val newID = JOptionPane.showInputDialog("Enter a new ID: (2 of 3 params, leave blank to use random ID)").toIntOrNull()
                            val newParentID = JOptionPane.showInputDialog("Enter a new Parent ID for ${newID}: (3 of 3 params, leave blank for root dir)").toIntOrNull()
                            pasteForEntryMode(newname.toEntryName(DiskEntry.NAME_LENGTH, sysCharset), newID, newParentID)*/
                            popupError("File cannot be added in FAT Browsing mode")
                        }
                        else {
                            var newfile = Clustfile(vdisk!!, currentDirectory, clipboard!!.name)

                            // check the name collision. If there is a collision, ask for a new one
                            while (newfile.exists()) {
                                val newname = JOptionPane.showInputDialog("The name already exists. Enter a new name:")
                                if (newname == null) {
                                    setStat("File pasting cancelled")
                                    return
                                }
                                newfile = Clustfile(vdisk!!, currentDirectory, newname)
                            }


                            paste1(newfile)
                            setStat("File pasted")

                            // delete original file if cutmode
                            if (clipboardCutMode) {
                                clipboard!!.delete().let {
                                    if (it) setStat("File was successfully pasted but the source file was not deleted")
                                    else setStat("File cut and pasted")
                                }
                                clipboard = null
                            }


                            clipboardCutMode = false
                        }
                    }
                }
            })
            /*add("Paste as Symbolic Link").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    fun pasteSymbolic(newname: ByteArray) {
                        try {
                            // check if the original file is there in the first place
                            if (vdisk!!.entries[clipboard!!.entryID] != null) {
                                val entrySymlink = EntrySymlink(clipboard!!.entryID)
                                VDUtil.addFile(vdisk!!, currentDirectory, DiskEntry(
                                        vdisk!!.generateUniqueID(),
                                        currentDirectory,
                                        newname,
                                        VDUtil.currentUnixtime,
                                        VDUtil.currentUnixtime,
                                        entrySymlink
                                ))

                                updateDiskInfo()
                                setStat("Symbolic link created")
                            }
                            else {
                                popupError("The orignal file is gone")
                            }
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                    fun pasteSymbolicForEntryMode(newname: ByteArray, newEntryID: Int?, newParentEntryID: Int?) {
                        try {
                            val newEntryID = newEntryID ?: vdisk!!.generateUniqueID()
                            val newParentEntryID = newParentEntryID ?: 0
                            // check if the original file is there in the first place
                            if (vdisk!!.entries[clipboard!!.entryID] != null) {
                                val entrySymlink = EntrySymlink(clipboard!!.entryID)
                                VDUtil.addFile(vdisk!!, currentDirectory, DiskEntry(
                                        newEntryID,
                                        newParentEntryID,
                                        newname,
                                        VDUtil.currentUnixtime,
                                        VDUtil.currentUnixtime,
                                        entrySymlink
                                ))

                                updateDiskInfo()
                                setStat("Symbolic link created")
                            }
                            else {
                                popupError("The orignal file is gone")
                            }
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                    if (clipboard != null && vdisk != null) {
                        if (inEntriesMode) {
                            /*val newname = JOptionPane.showInputDialog("Enter a new name: (1 of 3 params)")
                            val newID = JOptionPane.showInputDialog("Enter a new ID: (2 of 3 params, leave blank to use random ID)").toIntOrNull()
                            val newParentID = JOptionPane.showInputDialog("Enter a new Parent ID for ${newID}: (3 of 3 params, leave blank for root dir)").toIntOrNull()
                            pasteSymbolicForEntryMode(newname.toEntryName(DiskEntry.NAME_LENGTH, sysCharset), newID, newParentID)*/
                            popupError("File cannot be added in FAT Browsing mode")
                        }
                        else {
                            // check name collision. If it is, ask for new one
                            if (VDUtil.nameExists(vdisk!!, clipboard!!.getFilenameString(sysCharset), currentDirectory, sysCharset)) {
                                val newname = JOptionPane.showInputDialog("The name already exists. Enter a new name:")
                                if (newname != null) {
                                    pasteSymbolic(newname.toEntryName(DiskEntry.NAME_LENGTH, sysCharset))
                                }
                            }
                            else {
                                pasteSymbolic(clipboard!!.filename)
                            }
                        }
                    }
                }
            })*/
            add("Delete").apply { this.mnemonic = KeyEvent.VK_Q }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null && selectedFile != null) {
                        selectedFile!!.delete().let {
                            if (it) {
                                updateDiskInfo()
                                setStat("File deleted")
                                deselect()
                            }
                            else {
                                popupError("Trying to delete the file that does not actually exists (perhaps there is a bug in this app)")
                            }
                        }
                    }
                }
            })
            add("Rename…").apply { this.mnemonic = KeyEvent.VK_R }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (selectedFile != null) {
                        try {
                            val newname = JOptionPane.showInputDialog("Enter a new name:")
                            if (newname != null) {
                                val target = Clustfile(vdisk!!, selectedFile!!.parentFile, newname)
                                if (target.exists())
                                    popupError("The name already exists")
                                else {
                                    selectedFile!!.renameTo(target)
                                    updateDiskInfo()
                                    setStat("File renamed")
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
            /*add("Renumber…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (selectedFile != null) {
                        try {
                            val newID = JOptionPane.showInputDialog("Enter a new entry ID:").toIntOrNull()
                            val oldID = selectedFile!!
                            if (newID != null) {
                                if (!inEntriesMode && vdisk!!.entries[newID] != null) {
                                    popupError("The name already exists")
                                }
                                else {
                                    val file = vdisk!!.entries[oldID]!!.apply {
                                        entryID = newID

                                        val oldParentID = parentEntryID
                                        val newParentID = if (inEntriesMode)
                                            JOptionPane.showInputDialog("Enter a new parent ID (click Cancel to not change the parent ID):").toIntOrNull() ?: oldParentID
                                        else
                                            oldParentID


                                        (vdisk!!.entries[oldParentID]?.contents as? EntryDirectory)?.apply {
                                            remove(oldID)
                                        }
                                        (vdisk!!.entries[newParentID]?.contents as? EntryDirectory)?.apply {
                                            add(newID)
                                        }

                                        parentEntryID = newParentID
                                    }

                                    vdisk!!.entries.remove(oldID)
                                    vdisk!!.entries[newID] = file


                                    updateDiskInfo()
                                    setStat("File renumbered")
                                }
                            }
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })*/
            add("Look Clipboard").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    popupMessage(if (clipboard != null)
                        "File:${clipboard!!.path}"
                    else "(nothing)", "Clipboard"
                    )
                }

            })
            addSeparator()
            add("Import Files/Folders…").apply { this.mnemonic = KeyEvent.VK_I }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        if (!inEntriesMode) {
                            val fileChooser = JFileChooser("./")
                            fileChooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                            fileChooser.isMultiSelectionEnabled = true
                            fileChooser.showOpenDialog(null)
                            if (fileChooser.selectedFiles.isNotEmpty()) {
                                try {
                                    for (it in fileChooser.selectedFiles) {
                                        var entry = Clustfile(vdisk!!, currentDirectory, it.name)

                                        // check the name collision. If there is a collision, ask for a new one
                                        while (entry.exists()) {
                                            val newname = JOptionPane.showInputDialog("The file '${it.name}' already exists. Enter a new name:")
                                            if (newname == null) {
                                                setStat("File import cancelled")
                                                return
                                            }
                                            entry = Clustfile(vdisk!!, currentDirectory, newname)
                                        }


                                        entry.importFrom(it)
                                        currentDirectory.addChild(entry)
                                    }
                                    setStat("File(s) imported")
                                }
                                catch (e: Exception) {
                                    setStat("File(s) import failed")
                                    e.printStackTrace()
                                    popupError(e.toString())
                                }
                                finally {
                                    updateDiskInfo()
                                }
                            }

                            fileChooser.isMultiSelectionEnabled = false
                        }
                        else {
                            /*val fileChooser = JFileChooser("./")
                            fileChooser.fileSelectionMode = JFileChooser.FILES_ONLY
                            fileChooser.isMultiSelectionEnabled = false
                            fileChooser.showOpenDialog(null)
                            if (fileChooser.selectedFiles.isNotEmpty()) {
                                val newname = JOptionPane.showInputDialog("Enter a new name: (1 of 3 params; leave blank to use the same name as the original file)")
                                val newID = JOptionPane.showInputDialog("Enter a new ID: (2 of 3 params, leave blank to use random ID)").toIntOrNull() ?: vdisk!!.generateUniqueID()
                                val newParentID = JOptionPane.showInputDialog("Enter a new Parent ID for ${newID}: (3 of 3 params, leave blank for root dir)").toIntOrNull()

                                VDUtil.importFile(fileChooser.selectedFile, newID, sysCharset).apply {
                                    newParentID?.let { parentEntryID = it }
                                    if (newname.isNotBlank()) filename = newname.toEntryName(DiskEntry.NAME_LENGTH, sysCharset)
                                }
                            }*/

                            popupError("File cannot be added in FAT Browsing mode")
                        }
                    }
                }
            })
            add("Export…").apply { this.mnemonic = KeyEvent.VK_E }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        val file: Clustfile = selectedFile ?: currentDirectory

                        val fileChooser = JFileChooser("./")
                        fileChooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        fileChooser.isMultiSelectionEnabled = false
                        fileChooser.showSaveDialog(null)
                        if (fileChooser.selectedFile != null) {
                            try {
                                file.exportTo(fileChooser.selectedFile)
                            }
                            catch (e: Exception) {
                                e.printStackTrace()
                                popupError(e.toString())
                            }
                        }
                    }
                }
            })
            addSeparator()
            add("Rename Disk…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val newname = JOptionPane.showInputDialog("Enter a new disk name:")
                            if (newname != null) {
                                vdisk!!.renameDisk(newname)
                                updateDiskInfo()
                                setStat("Disk renamed")
                            }
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })
            add("Resize Disk…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val dialog = OptionSize()
                            val confirmed = dialog.showDialog("Input") == JOptionPane.OK_OPTION
                            if (confirmed) {
                                vdisk!!.changeDiskCapacity((dialog.capacity.value as Int))
                                updateDiskInfo()
                                setStat("Disk resized")
                            }
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })
            addSeparator()
            add("Set/Unset Write Protection").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            vdisk!!.isReadOnly = vdisk!!.isReadOnly.not()
                            updateDiskInfo()
                            setStat("Disk write protection ${if (vdisk!!.isReadOnly) "" else "dis"}engaged")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })

            menuBar.add(this)
        }



        JMenu("Manage").apply {
            mnemonic = KeyEvent.VK_M
            add("Count Reclaimable").apply { this.mnemonic = KeyEvent.VK_C }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            vdisk!!.buildFreeClustersMap()
                            val reclaimable = vdisk!!.reclaimableClusters
                            val message = "Reclaimable clusters: ${reclaimable.size}\n" +
                                    "Size: ${reclaimable.size.times(ClusteredFormatDOM.CLUSTER_SIZE).toLong().bytes()}"
                            popupMessage(message, "Total Reclaimable Clusters")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })
            addSeparator()
            add("Trim").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val oldSize = vdisk!!.ARCHIVE.length()
                            vdisk!!.trimArchive()
                            val newSize = vdisk!!.ARCHIVE.length()

                            val diff = oldSize - newSize

                            val message = "Reclaimed ${diff.bytes()} (${diff.div(ClusteredFormatDOM.CLUSTER_SIZE).clusters()})"
                            popupMessage(message, "Clusters Trimmed")
                            updateDiskInfo()
                            setStat("Clusters trimmed")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })
            add("Defrag").apply { this.mnemonic = KeyEvent.VK_D }.addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val oldSize = vdisk!!.ARCHIVE.length()
                            vdisk!!.defrag()
                            val newSize = vdisk!!.ARCHIVE.length()

                            val diff = oldSize - newSize

                            val message = "Reclaimed ${diff.bytes()} (${diff.div(ClusteredFormatDOM.CLUSTER_SIZE).clusters()})"
                            popupMessage(message, "Clusters Defragmented")
                            updateDiskInfo()
                            setStat("Clusters defragmented")
                        }
                        catch (e: Exception) {
                            e.printStackTrace()
                            popupError(e.toString())
                        }
                    }
                }
            })
            menuBar.add(this)
        }



        JMenu("About").apply {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    popupMessage(copyright, "Copyright")
                    println("========================== Cut Log Messages Above ==========================")
                }
            })
            menuBar.add(this)
        }

        diskInfo.highlighter = null
        diskInfo.text = "(Disk not loaded)"
        diskInfo.preferredSize = Dimension(-1, 120)

        fileDesc.font = Font.getFont(Font.MONOSPACED)
        fileDesc.highlighter = null
        fileDesc.text = ""
        fileDesc.caret.isVisible = false
        (fileDesc.caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE

        ////////////////////////////////////////////////////////////////////////////////////////////////////////

        bootEditPane.font = Font.getFont(Font.MONOSPACED)
        bootEditPane.text = ""



        val fileDescScroll = JScrollPane(fileDesc)
        val tableFilesScroll = JScrollPane(tableFiles).apply {
            size = Dimension(200, -1)
        }
        val tableEntriesScroll = JScrollPane(tableEntries).apply {
            size = Dimension(200, -1)
        }

        val panelFinder = JPanel(BorderLayout()).apply {
            add(labelPath, BorderLayout.NORTH)
            add(tableFilesScroll, BorderLayout.CENTER)
        }

        ////////////////////////////////////////////////////////////////////////////////////////////////////////

        val bootEditorButtons = JPanel().apply {
            add(JButton("Write").apply {
                this.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent?) {
                        if (vdisk != null) {
                            vdisk!!.writeBoot(bootEditPane.text.toByteArray(vdisk!!.charset))
                            bootEditPane.text = vdisk!!.readBoot().toString(vdisk!!.charset)
                            setStat("New Bootsector written")
                        }
                    }
                })
            })
            add(JButton("Rollback").apply {
                this.addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent?) {
                        bootEditPane.text = RandomAccessFile(originalFile, "r").let {
                            it.seekToCluster(1)
                            val s = it.read(CLUSTER_SIZE.toInt()).trimNull().toString(vdisk?.charset ?: Charsets.ISO_8859_1)
                            it.close()
                            s
                        }
                        vdisk!!.writeBoot(bootEditPane.text.toByteArray(vdisk!!.charset))
                        bootEditPane.text = vdisk!!.readBoot().toString(vdisk!!.charset)
                        setStat("Bootsector is rolled back")
                    }
                })
            })
        }

        val bootEditor = JPanel(BorderLayout()).apply {
            add(JScrollPane(bootEditPane).apply {
                size = Dimension(200, -1)
            }, BorderLayout.CENTER)
            add(bootEditorButtons, BorderLayout.SOUTH)
        }

        ////////////////////////////////////////////////////////////////////////////////////////////////////////


        val clustmap = JPanel(BorderLayout()).apply {
            add(JScrollPane(clustmapGrid), BorderLayout.CENTER)
        }

        ////////////////////////////////////////////////////////////////////////////////////////////////////////


        panelFinderTab = JTabbedPane().apply {
            addTab("Navigator", panelFinder)
            addTab("FAT", tableEntriesScroll)
            addTab("Clusters", clustmap)
            addTab("Bootsector", bootEditor)
        }

        val panelFileDesc = JPanel(BorderLayout()).apply {
            add(JLabel("Entry Information"), BorderLayout.NORTH)
            add(fileDescScroll, BorderLayout.CENTER)
        }

        val filesSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelFinderTab, panelFileDesc)
        filesSplit.resizeWeight = 0.6


        val panelDiskOp = JPanel(BorderLayout(2, 2)).apply {
            add(filesSplit, BorderLayout.CENTER)
            add(diskInfo, BorderLayout.SOUTH)
        }


        panelMain.add(menuBar, BorderLayout.NORTH)
        panelMain.add(panelDiskOp, BorderLayout.CENTER)
        panelMain.add(statBar, BorderLayout.SOUTH)


        this.title = appName
        this.add(panelMain)
        this.setSize(800, 800)
        this.isVisible = true
    }

    private val buttonColourFree = Color(0xfafafa)
    private val buttonColourOccupied = listOf(
            4086 to Color(0xff0d89),
            3269 to Color(0xff5899),
            2452 to Color(0xff7daa),
            1635 to Color(0xff9bba),
            818 to Color(0xffb6cb),
            1 to Color(0xfed0dc),
    )
    private fun contentsSizeToButtonColour(i: Int): Color {
        for (kv in buttonColourOccupied) {
            if (i >= kv.first) return kv.second
        }
        return buttonColourFree
    }

    private val buttonColourFAT = Color(0x6cee91)
    private val buttonColourFATdata = Color(0xf6cb07)
    private val buttonColourReserved = Color(0x12adff)
    private val buttonColourVirtual = Color(0xece8d9)
    private val buttonDim = Dimension(16, 16)

    private fun interpolateLinear(scale: Float, startValue: Float, endValue: Float): Float {
        if (startValue == endValue) {
            return startValue
        }
        if (scale <= 0f) {
            return startValue
        }
        return if (scale >= 1f) {
            endValue
        }
        else (1f - scale) * startValue + scale * endValue
    }

    private fun lerp(t: Float, c1: Color, c2: Color): Color {
        val r = interpolateLinear(t, c1.red.div(255f), c2.red.div(255f)).coerceIn(0f, 1f)
        val g = interpolateLinear(t, c1.green.div(255f), c2.green.div(255f)).coerceIn(0f, 1f)
        val b = interpolateLinear(t, c1.blue.div(255f), c2.blue.div(255f)).coerceIn(0f, 1f)
        return Color(r, g, b)
    }

    private fun rebuildClustmap() {

        var usedClusterMax = 4
        if (vdisk != null) {
            usedClusterMax = vdisk!!.fatClusterCount + 2
            val file = vdisk!!.ARCHIVE
            val filelen = file.length()
            val oldPtr = file.filePointer
            while (usedClusterMax * CLUSTER_SIZE < filelen) {
                file.seekToCluster(usedClusterMax)
                val c = file.readInt24()
                if (c == 0) break
                usedClusterMax += 1
            }
        }
        usedClusterMax -= 1

        val cols = 20
        val rows = usedClusterMax / cols
        (clustmapGrid.layout as GridLayout).let {
            it.columns = cols
            it.rows = rows
        }
        clustmapGrid.removeAll()


        vdisk?.let { vdisk ->
            for (i in 0 until usedClusterMax) {
                val buttonCol = if (i in 0..1) buttonColourReserved
                else if (i < vdisk.fatClusterCount + 2) {
                    vdisk.ARCHIVE.seekToCluster(i)
                    var dataFats = 0
                    for (k in 0 until CLUSTER_SIZE step FAT_ENTRY_SIZE.toLong()) {
                        if (vdisk.ARCHIVE.read(FAT_ENTRY_SIZE).toInt24() >= INLINE_FILE_CLUSTER_BASE) dataFats += 1
                    }
                    lerp(dataFats.toFloat() / FATS_PER_CLUSTER, buttonColourFAT, buttonColourFATdata)
                }
                else if (i >= vdisk.ARCHIVE.length() / CLUSTER_SIZE) buttonColourVirtual
                else {
                    contentsSizeToButtonColour(vdisk.contentSizeInThisCluster(i))
                }


                clustmapGrid.add(JButton().also {
                    it.text = ""
                    it.preferredSize = buttonDim
                    it.background = buttonCol
                    it.isOpaque = true
                    it.isBorderPainted = !isMacos // the default "Metal" L&F is not compatible with macOS
                    it.addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent?) {
                            fileDesc.text = getDescForCluster(i)
                        }
                    })
                })
            }
        }
    }

    private fun getDescForCluster(cluster: EntryID): String {
        val ret = "Cluster: #${cluster + 1} (${cluster.toHex()})"
        return if (vdisk == null) ret
        else if (cluster == 0) "$ret\n(header)"
        else if (cluster == 1) "$ret\n(bootsector)"
        else if (cluster < vdisk!!.fatClusterCount + 2) {
            vdisk!!.ARCHIVE.seekToCluster(cluster)
            var dataFats = 0
            for (k in 0 until CLUSTER_SIZE step FAT_ENTRY_SIZE.toLong()) {
                if (vdisk!!.ARCHIVE.read(FAT_ENTRY_SIZE).toInt24() >= INLINE_FILE_CLUSTER_BASE) dataFats += 1
            }
            if (dataFats > 0)
                "$ret\n(file allocation table with $dataFats inlined ${if (dataFats == 1) "entry" else "entries"})"
            else
                "$ret\n(file allocation table)"
        }
        else if (cluster >= vdisk!!.ARCHIVE.length() / CLUSTER_SIZE) "$ret\n(trimmed)"
        else {
            vdisk!!.ARCHIVE.seekToCluster(cluster)
            val bytes = vdisk!!.ARCHIVE.read(CLUSTER_SIZE.toInt())
            val prev = bytes.toInt24(2).let { if (it == 0) null else it }
            val next = bytes.toInt24(5).let { if (it == 0xFFFFFF) null else it }
            ret + """
Prev Chain: ${if (prev == null) "—" else "#${prev+1} (${prev.toHex()})"}
Next Chain: ${if (next == null) "—" else "#${next+1} (${next.toHex()})"}
Flag1: ${bytes[0].toUint().toString(2).padStart(8, '0')}
Flag2: ${bytes[1].toUint().toString(2).padStart(8, '0')}
Contents [${bytes.toInt16(8)}]:
"""+bytes.sliceArray(10 until CLUSTER_SIZE.toInt()).toString(vdisk!!.charset)
        }
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
    private fun popupWarning(message: String, title: String = "Careful…") {
        JOptionPane.showOptionDialog(
                null,
                message,
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null, null, null
        )
    }
    private fun updateCurrentDirectory() {
        currentDirectoryEntries = currentDirectory.listFiles()

    }
    private fun updateDiskInfo() {
        val sb = StringBuilder()
        directoryHierarchy.forEach {
            sb.append(it.name)
            sb.append('/')
        }
        sb.dropLast(1)
        labelPath.text = sb.toString()

        diskInfo.text = if (vdisk == null) "(Disk not loaded)" else getDiskInfoText(vdisk!!)

        updateCurrentDirectory()

        tableFiles.revalidate()
        tableFiles.repaint()

        rebuildTableEntries()
        tableEntries.revalidate()
        tableEntries.repaint()


        rebuildClustmap()
    }
    private fun getDiskInfoText(disk: ClusteredFormatDOM): String {
        return """Name: ${disk.diskNameString} (UUID: ${disk.uuid})
Capacity: ${disk.totalSpace} bytes (${disk.usedSpace} bytes used, ${disk.freeSpace} bytes free)
Clusters: ${disk.totalClusterCount} clusters (${disk.archiveSizeInClusters} clusters used, ${disk.freeClusterCount} clusters free)
Write protected: ${disk.isReadOnly.toEnglish()}
Bootable: ${disk.readBoot().isBootable()}"""
    }

    private fun ByteArray.isBootable() = when (this[0].toUint()) {
        0 -> "No (first byte is null)"
        32 -> "No (first byte is space char)"
        in 33..126 -> "Yes (plain text)"
        else -> {
            if (this[0] == 0x1F.toByte() && this[1] == 0x8B.toByte())
                "Yes (gzipped)"
            else
                "Unknown"
        }
    }

    private fun rebuildTableEntries() {
        tableEntriesRecord = if (vdisk != null) {
            vdisk!!.fileTable.toTypedArray().map { entry ->
                try {
                    arrayOf(
                            entry.filename,
                            entry.entryID.toHex() + " (${entry.indexInFAT})",
                            entry.fileType.toFileTypeString(),
                            vdisk!!.getFileLength(entry).toString(),
                            Instant.ofEpochSecond(entry.creationDate).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                            Instant.ofEpochSecond(entry.modificationDate).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                            entry.extendedEntries.size.toString(),
                            entry.entryID.toString()
                    )
                }
                catch (e: InternalError) {
                    arrayOf(
                            entry.filename,
                            entry.entryID.toHex() + " (${entry.indexInFAT})",
                            entry.fileType.toFileTypeString(),
                            "n/a",
                            Instant.ofEpochSecond(entry.creationDate).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                            Instant.ofEpochSecond(entry.modificationDate).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                            entry.extendedEntries.size.toString(),
                            entry.entryID.toString()
                    )
                }
            }.toTypedArray()
        }
        else {
            arrayOf(arrayOf("", "", "", "", "", "", ""))
        }
    }

    private fun Boolean.toEnglish() = if (this) "Yes" else "No"
    private fun Int.toFileTypeString() = when (this) {
        FILETYPE_BINARY -> "File"
        FILETYPE_DIRECTORY -> "Directory"
        else -> "Unk:$this"
    }

    private fun getFileInfoText(file: Clustfile?): String {
        if (file == null) return ""

        return """Name: ${file.name}
Size: ${file.getEffectiveSize()} ${if (file.isDirectory) "entries" else "bytes"}
Type: ${file.FAT?.fileType?.fileTypeToString()}
FAT ID: ${file.FAT?.entryID?.toHex()}
Flags: ${file.FAT?.toFlagsString()}
""" + if (file.exists() && file.isFile)
        ("Contents:\n" +
                ByteArray(minOf(PREVIEW_MAX_BYTES, file.length()).toInt()).apply {
                    file.pread(this, 0, this.size, 0)
                }.toString(vdisk?.charset ?: Charsets.ISO_8859_1))
        else ""
    }

    private fun getFileInfoText(fat: ClusteredFormatDOM.FATEntry?): String {
        if (fat == null) return ""

        val flen = vdisk!!.getFileLength(fat)

        return """Name: ${fat.filename}
Size: ${if (fat.fileType == FILETYPE_DIRECTORY) ("${flen / 3} entries") else "$flen bytes"}
Type: ${fat.fileType.fileTypeToString()}
FAT ID: ${fat.entryID.toHex()}
Flags: ${fat.toFlagsString()}
""" + if (fat.fileType == FILETYPE_BINARY)
            ("Contents:\n" +
                    ByteArray(minOf(PREVIEW_MAX_BYTES.toInt(), flen)).apply {
                        vdisk!!.readBytes(fat, this, 0, this.size, 0)
                    }.toString(vdisk?.charset ?: Charsets.ISO_8859_1))
        else ""
    }

    private fun setWindowTitleWithName(name: String) {
        this.title = "$appName - $name"
    }

    private fun Int?.fileTypeToString() = when (this) {
        null -> "null"
        FILETYPE_BINARY -> "Binary File"
        FILETYPE_DIRECTORY -> "Directory"
        else -> "Unknown ($this)"
    }

    private fun ClusteredFormatDOM.FATEntry.toFlagsString() =
            listOf(
                    (if (this.readOnly) "readonly" else null),
                    (if (this.system) "system" else null),
                    (if (this.hidden) "hidden" else null),
                    (if (this.deleted) "deleted" else null),
                    (if (this.isInternal) "internal" else null),
            ).filterNotNull().joinToString()

    private fun Long.bytes() = if (this == 1L) "one byte" else "$this bytes"
    private fun Int.entries() = if (this == 1) "one entry" else "$this entries"
    private fun Int.clusters() = if (this == 1) "one cluster" else "$this clusters"
    private fun Long.clusters() = if (this == 1L) "one cluster" else "$this clusters"
    private fun Clustfile.getEffectiveSize() = if (this.isFile)
        this.length()
    else if (this.isDirectory)
        this.length() / 3
    else
        "n/a"
    private fun setStat(message: String) {
        statBar.text = message
    }

    private fun Long.toHex() = this.and(0xFFFFFFFF).toString(16).padStart(8, '0').toUpperCase().let {
        it.substring(0..4).toInt(16).toString(16).toUpperCase().padStart(3, '0') + ":" + it.substring(5..7) + "h"
    }
    private fun Int.toHex() = this.toLong().toHex()
}

fun main(args: Array<String>) {
    VirtualDiskCrackerClustered()
}