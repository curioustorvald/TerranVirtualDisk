package net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.finder

import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatArchiver
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClusteredFormatDOM
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.Clustfile
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.archivers.ClustfileOutputStream
import net.torvald.terrarum.modulecomputers.virtualcomputer.tvd.toHex
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.logging.Level
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.ListSelectionModel
import javax.swing.text.DefaultCaret


/**
 * Original Cracker Created by SKYHi14 on 2017-04-01.
 * Cracker for Clustered Format Created by minjaesong on 2023-05-22.
 */
class VirtualDiskCrackerClustered() : JFrame() {


    private val annoyHackers = false // Jar build settings. Intended for Terrarum proj.


    private val PREVIEW_MAX_BYTES = 4L * 1024 // 4 kBytes

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
    private val fileDesc = JTextArea()
    private val diskInfo = JTextArea()
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



    private fun gotoSubDirectory(file: Clustfile) {
        directoryHierarchy.push(file)
        labelPath.text = file.getName()
        selectedFile = null
        fileDesc.text = ""
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
        selectedFile = null
        fileDesc.text = ""
        updateDiskInfo()
    }
    private fun gotoParent() {
        if (directoryHierarchy.size > 1)
            directoryHierarchy.pop()
        selectedFile = null
        fileDesc.text = ""
        updateDiskInfo()
    }



    private var selectedFile: Clustfile? = null

    val tableColumns = arrayOf("Name", "Date Modified", "Size")
    val tableParentRecord = arrayOf(arrayOf("..", "", ""))

    val tableColumnsEntriesMode = arrayOf("Name", "FAT ID", "Type", "Date Modified", "Size")
    val tableEntriesRecord = arrayOf(arrayOf("", "", "", "", "", ))
    private var currentDirectoryEntriesAltMode: Array<ClusteredFormatDOM.FATEntry>? = null



    private val panelFinderTab: JTabbedPane

    private val inEntriesMode: Boolean
        get() = panelFinderTab.selectedIndex == 1

    init {

        if (annoyHackers) {
            val mantra = JOptionPane.showInputDialog(annoyWhenLaunchMsg)
            if (mantra != magicOpen) {
                System.exit(1)
            }
        }



        panelMain.layout = BorderLayout()
        this.defaultCloseOperation = JFrame.EXIT_ON_CLOSE


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
                            if (record.isDirectory()) {
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
                                0 -> entry.getName()
                                1 -> Instant.ofEpochSecond(entry.lastModified()).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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
        }



        tableEntries = JTable(tableEntriesRecord, tableColumnsEntriesMode).apply {
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val table = e.source as JTable
                    val row = table.rowAtPoint(e.point)


                    selectedFile = null // disable selection //currentDirectoryEntriesAltMode!![row]
                    fileDesc.text = "" //getFileInfoText(selectedFile)
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
                override fun getRowCount() = currentDirectoryEntriesAltMode?.size ?: 0

                override fun getColumnCount() = tableColumnsEntriesMode.size

                override fun getColumnName(column: Int) = tableColumnsEntriesMode[column]

                override fun getValueAt(rowIndex: Int, columnIndex: Int): String? {
                    if (vdisk != null) {
                        val entry = currentDirectoryEntriesAltMode!![rowIndex]
                        return when (columnIndex) {
                            0 -> entry.filename
                            1 -> entry.entryID.toHex()
                            2 -> entry.fileType.toString()
                            3 -> Instant.ofEpochSecond(entry.modificationDate).atZone(TimeZone.getDefault().toZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            4 -> vdisk!!.getFileLength(entry).toString()
                            else -> ""
                        }
                    }
                    else {
                        return ""
                    }
                }
            }
        }



        JMenu("File").apply {
            mnemonic = KeyEvent.VK_F
            add("New Disk…").addMouseListener(object : MouseAdapter() {
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

                                gotoRoot()
                                updateDiskInfo()
                                setWindowTitleWithName(fileChooser.selectedFile.canonicalPath)
                                setStat("Disk created")
                            }
                            catch (e: Exception) {
                                e.printStackTrace()
                                popupError(e.toString())
                            }
                        }
                    }
                }
            })
            add("Open Disk…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    val makeNewDisk = if (vdisk != null) confirmedDiscard() else true
                    if (makeNewDisk) {
                        val fileChooser = JFileChooser("./")
                        fileChooser.showOpenDialog(null)
                        if (fileChooser.selectedFile != null) {
                            try {
                                // create swap file
                                originalFile = fileChooser.selectedFile!!
                                swapFile = File(originalFile.absolutePath + ".swap")
                                backupFile = File(originalFile.absolutePath + ".bak")

                                originalFile.copyTo(swapFile, true)
                                // all the editing is done on the swap file
                                vdisk = ClusteredFormatArchiver(null).deserialize(swapFile, null)

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
            add("Save Disk as…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {


                        if (annoyHackers) {
                            val mantra = JOptionPane.showInputDialog(annoyWhenSaveMsg)
                            if (mantra != magicSave) {
                                popupError("Nope!")
                                return
                            }
                        }


                        val fileChooser = JFileChooser("./")
                        fileChooser.showSaveDialog(null)
                        if (fileChooser.selectedFile != null) {
                            try {
                                TODO("mv original archive as original.bak / swap .swap file with the original archive")
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
            menuBar.add(this)
        }



        JMenu("Edit").apply {
            mnemonic = KeyEvent.VK_E
            add("New File…").addMouseListener(object : MouseAdapter() {
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
                                        ClustfileOutputStream(target, true).let {
                                            it.write(ByteArray((dialogBox.capacity.value as Long).toInt()))
                                        }
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
            add("New Directory…").addMouseListener(object : MouseAdapter() {
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
            add("Cut").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    clipboard = selectedFile
                    clipboardCutMode = true
                    setStat("File cut to clipboard. Note that the source file will not be deleted until the Paste operation")
                }
            })
            add("Copy").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    clipboard = selectedFile
                    clipboardCutMode = false
                    setStat("File copied to clipboard")
                }
            })
            add("Paste").addMouseListener(object : MouseAdapter() {
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
                            var newfile = Clustfile(vdisk!!, currentDirectory, clipboard!!.getName())

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
            add("Delete").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null && selectedFile != null) {
                        selectedFile!!.delete().let {
                            if (it) {
                                updateDiskInfo()
                                setStat("File deleted")
                            }
                            else {
                                popupError("Trying to delete the file that does not actually exists (perhaps there is a bug in this app)")
                            }
                        }
                    }
                }
            })
            add("Rename…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (selectedFile != null) {
                        try {
                            val newname = JOptionPane.showInputDialog("Enter a new name:")
                            if (newname != null) {
                                val target = Clustfile(vdisk!!, selectedFile!!.getParentFile(), newname)
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
                        "File:${clipboard!!.getPath()}"
                    else "(nothing)", "Clipboard"
                    )
                }

            })
            addSeparator()
            add("Import Files/Folders…").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        if (!inEntriesMode) {
                            val fileChooser = JFileChooser("./")
                            fileChooser.fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                            fileChooser.isMultiSelectionEnabled = true
                            fileChooser.showOpenDialog(null)
                            if (fileChooser.selectedFiles.isNotEmpty()) {
                                try {
                                    fileChooser.selectedFiles.forEach {
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

                                    }
                                    updateDiskInfo()
                                    setStat("File(s) imported")
                                }
                                catch (e: Exception) {
                                    e.printStackTrace()
                                    popupError(e.toString())
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
            add("Export…").addMouseListener(object : MouseAdapter() {
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
            add("Count Reclaimable").addMouseListener(object : MouseAdapter() {
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
            add("Trim").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val oldSize = vdisk!!.ARCHIVE.length()
                            vdisk!!.trimArchive()
                            val newSize = vdisk!!.ARCHIVE.length()

                            val diff = newSize / oldSize

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
            addSeparator()
            add("Defrag").addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent?) {
                    if (vdisk != null) {
                        try {
                            val oldSize = vdisk!!.ARCHIVE.length()
                            vdisk!!.defrag()
                            val newSize = vdisk!!.ARCHIVE.length()

                            val diff = newSize / oldSize

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
                }
            })
            menuBar.add(this)
        }


        diskInfo.highlighter = null
        diskInfo.text = "(Disk not loaded)"
        diskInfo.preferredSize = Dimension(-1, 60)

        fileDesc.highlighter = null
        fileDesc.text = ""
        fileDesc.caret.isVisible = false
        (fileDesc.caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE

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

        panelFinderTab = JTabbedPane().apply {
            addTab("Navigator", panelFinder)
            addTab("FAT", tableEntriesScroll)
        }

        val panelFileDesc = JPanel(BorderLayout()).apply {
            add(JLabel("Entry Information"), BorderLayout.NORTH)
            add(fileDescScroll, BorderLayout.CENTER)
        }

        val filesSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelFinderTab, panelFileDesc)
        filesSplit.resizeWeight = 0.571428


        val panelDiskOp = JPanel(BorderLayout(2, 2)).apply {
            add(filesSplit, BorderLayout.CENTER)
            add(diskInfo, BorderLayout.SOUTH)
        }


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
//        println("entries: ${vdisk!!.entries.keys.toList()}")
        currentDirectoryEntriesAltMode = vdisk!!.fileTable.values.sortedBy { it.entryID }.toTypedArray()
//        println("entries2: size=${currentDirectoryEntriesAltMode!!.size}")

    }
    private fun updateDiskInfo() {
        val sb = StringBuilder()
        directoryHierarchy.forEach {
            sb.append(it.getName())
            sb.append('/')
        }
        sb.dropLast(1)
        labelPath.text = sb.toString()

        diskInfo.text = if (vdisk == null) "(Disk not loaded)" else getDiskInfoText(vdisk!!)

        updateCurrentDirectory()

        tableFiles.revalidate()
        tableFiles.repaint()

        tableEntries.revalidate()
        tableEntries.repaint()
    }
    private fun getDiskInfoText(disk: ClusteredFormatDOM): String {
        return """Name: ${disk.diskNameString}
Capacity: ${disk.totalSpace} bytes (${disk.usedSpace} bytes used, ${disk.freeSpace} bytes free)
Clusters: ${disk.totalClusterCount} clusters (${disk.usedClusterCount} clusters used, ${disk.freeClusterCount} clusters free)
Write protected: ${disk.isReadOnly.toEnglish()}"""
    }


    private fun Boolean.toEnglish() = if (this) "Yes" else "No"


    private fun getFileInfoText(file: Clustfile?): String {
        if (file == null) return ""

        return """Name: ${file.getName()}
Size: ${file.getEffectiveSize()}
Type: ${file.FAT?.fileType}
CRC: ${file.hashCode().toHex()}
FAT ID: ${file.FAT?.entryID}
Contents: """ + if (file.exists())
    ByteArray(minOf(PREVIEW_MAX_BYTES, file.length()).toInt()).also {
        file.pread(it, 0, it.size, 0)
    }.toString(vdisk?.charset ?: Charsets.ISO_8859_1) else "" }

    private fun setWindowTitleWithName(name: String) {
        this.title = "$appName - $name"
    }

    private fun Long.bytes() = if (this == 1L) "one byte" else "$this bytes"
    private fun Int.entries() = if (this == 1) "one entry" else "$this entries"
    private fun Int.clusters() = if (this == 1) "one cluster" else "$this clusters"
    private fun Long.clusters() = if (this == 1L) "one cluster" else "$this clusters"
    private fun Clustfile.getEffectiveSize() = if (this.isFile())
        this.length()
    else if (this.isDirectory())
        this.length() / 3
    else
        "n/a"
    private fun setStat(message: String) {
        statBar.text = message
    }
}

fun main(args: Array<String>) {
    VirtualDiskCrackerClustered()
}