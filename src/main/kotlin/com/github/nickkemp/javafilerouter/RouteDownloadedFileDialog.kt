package com.github.nickkemp.javafilerouter

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipFile
import javax.swing.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import javax.swing.text.MaskFormatter

class RouteDownloadedFileDialog(private val project: Project) : DialogWrapper(project) {

    private val editorFont = EditorColorsManager.getInstance().globalScheme.let {
        java.awt.Font(it.editorFontName, java.awt.Font.PLAIN, it.editorFontSize)
    }

    private val moduleCombo   = ComboBox<String>()
    private val fileListModel = DefaultListModel<DownloadedFile>()
    private val fileList      = JList<DownloadedFile>(fileListModel)
    private val previewArea   = JTextArea(7, 60).apply {
        isEditable = false
        font = editorFont
    }
    private val resultArea    = JTextArea(7, 60).apply {
        isEditable = false
        font = editorFont
    }

    private val sdf           = SimpleDateFormat("dd/MM/yyyy")
    private val sdtf          = SimpleDateFormat("dd/MM/yyyy HH:mm")
    private val downloadsDir  = File(System.getProperty("user.home") + File.separator + "Downloads")
    private val checkedItems  = mutableSetOf<Int>()

    // ── DownloadedFile model ──────────────────────────────────────────────

    data class DownloadedFile(val file: File, val lastModified: Long) {
        override fun toString() = file.name
    }

    // ── Date filter fields ────────────────────────────────────────────────

    private val fromField = createDateField()
    private val toField   = createDateField()

    private fun createDateField(): JFormattedTextField {
        val fmt = MaskFormatter("##/##/####").apply { placeholderCharacter = '_' }
        return JFormattedTextField(fmt).apply {
            columns    = 10
            text       = sdf.format(Date())
            addPropertyChangeListener("value") { applyFilter() }
        }
    }

    private val allFiles = mutableListOf<DownloadedFile>()
    init {
        title = "Route Downloaded Java File"
        loadDownloadsFolder()
        init()        // triggers createCenterPanel() — UI is ready after this
        applyFilter() // now safe to populate the list
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8))

        // ── Module selector ───────────────────────────────────────────────
        val modulePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        modulePanel.add(JBLabel("Module:  "))
        ModuleManager.getInstance(project).modules
            .map { it.name }.sorted()
            .forEach { moduleCombo.addItem(it) }
        moduleCombo.preferredSize = Dimension(300, 28)
        moduleCombo.addActionListener { updatePreview() }
        modulePanel.add(moduleCombo)

        // ── Date filter ───────────────────────────────────────────────────
        val todayBtn = JButton("Today").apply {
            addActionListener {
                val today = sdf.format(Date())
                fromField.text = today
                toField.text   = today
                applyFilter()
            }
        }
        val clearBtn = JButton("Clear").apply {
            addActionListener {
                fromField.text = ""
                toField.text   = ""
                applyFilter()
            }
        }

        val filterPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
        filterPanel.add(JBLabel("From: "))
        filterPanel.add(fromField)
        filterPanel.add(Box.createHorizontalStrut(8))
        filterPanel.add(JBLabel("To: "))
        filterPanel.add(toField)
        filterPanel.add(Box.createHorizontalStrut(8))
        filterPanel.add(todayBtn)
        filterPanel.add(Box.createHorizontalStrut(4))
        filterPanel.add(clearBtn)

        // ── File list ─────────────────────────────────────────────────────
        fileList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        fileList.cellRenderer  = CheckBoxListRenderer()
        fileList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val index = fileList.locationToIndex(e.point)
                if (index >= 0) { toggleChecked(index); updatePreview() }
            }
        })

        val selectAllBtn = JButton("Select All").apply  { addActionListener { setAllChecked(true);  updatePreview() } }
        val clearAllBtn  = JButton("Clear All").apply   { addActionListener { setAllChecked(false); updatePreview() } }
        val refreshBtn   = JButton("Refresh").apply     { addActionListener { loadDownloadsFolder(); applyFilter() } }

        val listBtnRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("Downloads: ${downloadsDir.absolutePath}"))
            add(Box.createHorizontalGlue())
            add(refreshBtn)
            add(Box.createHorizontalStrut(4))
            add(selectAllBtn)
            add(Box.createHorizontalStrut(4))
            add(clearAllBtn)
        }

        val filePanel = JPanel(BorderLayout(4, 4))
        filePanel.add(filterPanel, BorderLayout.NORTH)
        filePanel.add(JBScrollPane(fileList).apply { preferredSize = Dimension(620, 130) }, BorderLayout.CENTER)
        filePanel.add(listBtnRow, BorderLayout.SOUTH)

        // ── Preview ───────────────────────────────────────────────────────
        val previewPanel = JPanel(BorderLayout(4, 4))
        previewPanel.add(JBLabel("Preview:"), BorderLayout.NORTH)
        previewPanel.add(JBScrollPane(previewArea).apply { preferredSize = Dimension(620, 110) }, BorderLayout.CENTER)

        // ── Result ────────────────────────────────────────────────────────
        val resultPanel = JPanel(BorderLayout(4, 4))
        resultPanel.add(JBLabel("Result:"), BorderLayout.NORTH)
        resultPanel.add(JBScrollPane(resultArea).apply { preferredSize = Dimension(620, 110) }, BorderLayout.CENTER)

        // ── Centre layout ─────────────────────────────────────────────────
        val centre = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(filePanel)
            add(Box.createVerticalStrut(8))
            add(previewPanel)
            add(Box.createVerticalStrut(8))
            add(resultPanel)
        }

        panel.add(modulePanel, BorderLayout.NORTH)
        panel.add(centre,      BorderLayout.CENTER)
        panel.preferredSize = Dimension(680, 620)
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Route") {
            override fun doAction(e: ActionEvent) { routeFiles() }
        },
        cancelAction
    )

    // ── Load and filter ───────────────────────────────────────────────────


    private fun loadDownloadsFolder() {
        allFiles.clear()
        checkedItems.clear()
        if (!downloadsDir.exists()) return
        downloadsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".java") || f.name.endsWith(".zip"))
        }
        ?.map { DownloadedFile(it, it.lastModified()) }
        ?.sortedByDescending { it.lastModified }  // newest first
        ?.let { allFiles.addAll(it) }
    }

    private fun applyFilter() {
        fileListModel.clear()
        checkedItems.clear()

        val fromDate = parseDate(fromField.text)
        val toDate   = parseDate(toField.text)?.let {
            // Include the entire 'to' day
            Calendar.getInstance().apply { time = it; add(Calendar.DAY_OF_MONTH, 1) }.time
        }

        allFiles.filter { df ->
            val fileDate = Date(df.lastModified)
            (fromDate == null || !fileDate.before(fromDate)) &&
            (toDate   == null || fileDate.before(toDate))
        }.forEach { fileListModel.addElement(it) }

        updatePreview()
    }

    private fun parseDate(text: String): Date? {
        if (text.isBlank() || text.contains('_')) return null
        return try { sdf.parse(text.trim()) } catch (e: Exception) { null }
    }

    private fun toggleChecked(index: Int) {
        if (checkedItems.contains(index)) checkedItems.remove(index) else checkedItems.add(index)
        fileList.repaint()
    }

    private fun setAllChecked(checked: Boolean) {
        checkedItems.clear()
        if (checked) (0 until fileListModel.size).forEach { checkedItems.add(it) }
        fileList.repaint()
    }

    // ── Preview ───────────────────────────────────────────────────────────

    private fun updatePreview() {
        previewArea.text = ""
        val sourceRoots = getSourceRoots() ?: return

        checkedItems.sorted().forEach { index ->
            val df = fileListModel.getElementAt(index)
            when {
                df.file.name.endsWith(".zip")  -> previewZip(df.file, sourceRoots)
                df.file.name.endsWith(".java") -> previewJava(df.file, sourceRoots)
            }
        }

        if (previewArea.text.isEmpty() && checkedItems.isNotEmpty())
            previewArea.text = "No files can be routed — check module and package."
    }

    private fun previewJava(file: File, sourceRoots: List<File>) {
        val pkg       = readPackage(file.readText()) ?: run { preview("SKIP (no package):  ${file.name}"); return }
        val targetDir = findPackageDir(pkg, sourceRoots) ?: run { preview("SKIP (pkg missing): $pkg  ←  ${file.name}"); return }
        val className = cleanFileName(file.name)
        if (File(targetDir, className).exists()) preview("BACKUP → $className.bak")
        preview("ROUTE  ${file.name}  →  ${targetDir.absolutePath}")
    }

    private fun previewZip(file: File, sourceRoots: List<File>) {
        preview("ZIP: ${file.name}")
        var count = 0
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".java") }.forEach { entry ->
                count++
                val content   = zip.getInputStream(entry).bufferedReader().readText()
                val pkg       = readPackage(content)
                val fileName  = entry.name.substringAfterLast("/")
                val className = cleanFileName(fileName)
                if (pkg == null) {
                    preview("  SKIP (no package):  $fileName")
                    return@forEach
                }
                val targetDir = findPackageDir(pkg, sourceRoots)
                if (targetDir == null) {
                    preview("  SKIP (pkg missing): $pkg")
                    preview("         $fileName")
                    return@forEach
                }
                if (File(targetDir, className).exists())
                    preview("  BACKUP $className  →  $className.bak")
                preview("  ROUTE  $fileName")
                preview("         →  ${targetDir.absolutePath}")
            }
        }
        if (count == 0) {
            val otherTypes = ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .map { it.name.substringAfterLast(".").lowercase() }
                    .distinct().sorted().toList()
            }
            if (otherTypes.isEmpty())
                preview("  (zip is empty)")
            else
                preview("  (no routeable .java files — zip contains: ${otherTypes.joinToString(", ")})")
        }
    }

    // ── Route ─────────────────────────────────────────────────────────────

    private fun routeFiles() {
        resultArea.text = ""
        val sourceRoots = getSourceRoots() ?: return
        var routed = 0; var errors = 0

        checkedItems.sorted().forEach { index ->
            val df = fileListModel.getElementAt(index)
            when {
                df.file.name.endsWith(".zip")  -> { val (r, e) = routeZip(df.file, sourceRoots);  routed += r; errors += e }
                df.file.name.endsWith(".java") -> { if (routeJava(df.file, sourceRoots)) routed++ else errors++ }
            }
        }

        result("")
        result("Complete: $routed file(s) routed, $errors skipped.")
        LocalFileSystem.getInstance().refresh(true)
    }

    private fun routeJava(file: File, sourceRoots: List<File>): Boolean {
        val pkg       = readPackage(file.readText()) ?: run { result("SKIP (no package):  ${file.name}"); return false }
        val targetDir = findPackageDir(pkg, sourceRoots) ?: run { result("SKIP (pkg missing): $pkg  ←  ${file.name}"); return false }
        val className = cleanFileName(file.name)
        val target    = File(targetDir, className)
        if (target.exists()) { val bak = File(targetDir, "$className.bak"); if (bak.exists()) bak.delete(); target.renameTo(bak); result("Backed up: $className  →  $className.bak") }
        Files.copy(file.toPath(), target.toPath())
        result("Routed:   ${file.name}  →  ${targetDir.absolutePath}")
        return true
    }

    private fun routeZip(file: File, sourceRoots: List<File>): Pair<Int, Int> {
        var routed = 0; var errors = 0
        result("ZIP: ${file.name}")
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".java") }.forEach { entry ->
                val content   = zip.getInputStream(entry).bufferedReader().readText()
                val pkg       = readPackage(content)
                val fileName  = entry.name.substringAfterLast("/")
                val className = cleanFileName(fileName)
                if (pkg == null) { result("  SKIP (no package):  $fileName"); errors++; return@forEach }
                val targetDir = findPackageDir(pkg, sourceRoots) ?: run { result("  SKIP (pkg missing): $pkg  ←  $fileName"); errors++; return@forEach }
                val target    = File(targetDir, className)
                if (target.exists()) { val bak = File(targetDir, "$className.bak"); if (bak.exists()) bak.delete(); target.renameTo(bak); result("  Backed up: $className  →  $className.bak") }
                target.writeText(content)
                result("  Routed:   $fileName  →  ${targetDir.absolutePath}")
                routed++
            }
        }
        return Pair(routed, errors)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun getSourceRoots(): List<File>? {
        val name   = moduleCombo.selectedItem as? String ?: return null
        val module = ModuleManager.getInstance(project).modules.firstOrNull { it.name == name } ?: return null
        return ModuleRootManager.getInstance(module).getSourceRoots(false).map { File(it.path) }
    }

    private fun readPackage(content: String): String? {
        for (line in content.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("@")) continue
            if (t.startsWith("package ")) return t.removePrefix("package ").removeSuffix(";").trim()
            if (t.startsWith("import ") || t.startsWith("public ") || t.startsWith("class ")) break
        }
        return null
    }

    private fun findPackageDir(pkg: String, sourceRoots: List<File>): File? {
        val rel = pkg.replace('.', File.separatorChar)
        return sourceRoots.map { File(it, rel) }.firstOrNull { it.exists() && it.isDirectory }
    }

    private fun cleanFileName(name: String) = name.replace(Regex("\\s*\\(\\d+\\)\\.java$"), ".java")
    private fun preview(line: String) { previewArea.append(line + "\n") }
    private fun result(line: String)  { resultArea.append(line + "\n")  }

    // ── Checkbox list renderer ────────────────────────────────────────────

    inner class CheckBoxListRenderer : ListCellRenderer<DownloadedFile> {
        private val panel     = JPanel(BorderLayout())
        private val checkBox  = JCheckBox().apply { font = editorFont }
        private val dateLabel = JBLabel("").apply {
            foreground = java.awt.Color.GRAY
            font = editorFont
        }
        init { panel.add(checkBox, BorderLayout.WEST); panel.add(dateLabel, BorderLayout.EAST) }

        override fun getListCellRendererComponent(
            list: JList<out DownloadedFile>, value: DownloadedFile, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
            checkBox.text      = value.file.name
            checkBox.isSelected = checkedItems.contains(index)
            dateLabel.text     = "  " + sdtf.format(Date(value.lastModified)) + "  "
            val bg = if (isSelected) list.selectionBackground else list.background
            panel.background    = bg
            checkBox.background = bg
            dateLabel.background = bg
            checkBox.foreground = if (isSelected) list.selectionForeground else list.foreground
            return panel
        }
    }
}
