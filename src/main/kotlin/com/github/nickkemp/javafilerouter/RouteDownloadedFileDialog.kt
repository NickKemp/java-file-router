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
import java.awt.Color
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

    // ── Font ──────────────────────────────────────────────────────────────

    private val editorFont = EditorColorsManager.getInstance().globalScheme.let {
        java.awt.Font(it.editorFontName, java.awt.Font.PLAIN, it.editorFontSize)
    }

    // ── File status ───────────────────────────────────────────────────────

    enum class FileStatus { NEW, CHANGED, IDENTICAL, SKIP }

    data class AnalysedEntry(val label: String, val status: FileStatus)

    // ── UI components ─────────────────────────────────────────────────────

    private val moduleCombo   = ComboBox<String>()
    private val fileListModel = DefaultListModel<DownloadedFile>()
    private val fileList      = JList<DownloadedFile>(fileListModel)
    private val statusArea    = JTextArea(10, 60).apply {
        isEditable = false
        font = editorFont
    }
    private val resultArea    = JTextArea(5, 60).apply {
        isEditable = false
        font = editorFont
    }

    private val sdf          = SimpleDateFormat("dd/MM/yyyy")
    private val sdtf         = SimpleDateFormat("dd/MM/yyyy HH:mm")
    private val downloadsDir = File(System.getProperty("user.home") + File.separator + "Downloads")
    private val checkedItems = mutableSetOf<Int>()

    // Analyse results: keyed by "zipname/filename" or "filename"
    private val analysedEntries = mutableListOf<AnalysedEntry>()
    private var analyseComplete = false

    private lateinit var analyseAction: Action
    private lateinit var installAction: Action

    // ── DownloadedFile model ──────────────────────────────────────────────

    data class DownloadedFile(val file: File, val lastModified: Long) {
        override fun toString() = file.name
    }

    // ── Date filter fields ────────────────────────────────────────────────

    private val fromField = createDateField()
    private val toField   = createDateField()
    private val allFiles  = mutableListOf<DownloadedFile>()

    private fun createDateField(): JFormattedTextField {
        val fmt = MaskFormatter("##/##/####").apply { placeholderCharacter = '_' }
        return JFormattedTextField(fmt).apply {
            columns = 10
            text    = sdf.format(Date())
            addPropertyChangeListener("value") { resetAnalysis() }
        }
    }

    init {
        title = "Route Downloaded Java File"
        loadDownloadsFolder()
        init()
        applyFilter()
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
        moduleCombo.addActionListener { resetAnalysis() }
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
                if (index >= 0) { toggleChecked(index); resetAnalysis() }
            }
        })

        val selectAllBtn = JButton("Select All").apply  { addActionListener { setAllChecked(true);  resetAnalysis() } }
        val clearAllBtn  = JButton("Clear All").apply   { addActionListener { setAllChecked(false); resetAnalysis() } }
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
        filePanel.add(JBScrollPane(fileList).apply { preferredSize = Dimension(660, 130) }, BorderLayout.CENTER)
        filePanel.add(listBtnRow, BorderLayout.SOUTH)

        // ── Status ────────────────────────────────────────────────────────
        val statusPanel = JPanel(BorderLayout(4, 4))
        statusPanel.add(JBLabel("Analysis:"), BorderLayout.NORTH)
        statusPanel.add(JBScrollPane(statusArea).apply { preferredSize = Dimension(660, 160) }, BorderLayout.CENTER)

        // ── Result ────────────────────────────────────────────────────────
        val resultPanel = JPanel(BorderLayout(4, 4))
        resultPanel.add(JBLabel("Result:"), BorderLayout.NORTH)
        resultPanel.add(JBScrollPane(resultArea).apply { preferredSize = Dimension(660, 90) }, BorderLayout.CENTER)

        // ── Centre layout ─────────────────────────────────────────────────
        val centre = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(filePanel)
            add(Box.createVerticalStrut(8))
            add(statusPanel)
            add(Box.createVerticalStrut(8))
            add(resultPanel)
        }

        panel.add(modulePanel, BorderLayout.NORTH)
        panel.add(centre,      BorderLayout.CENTER)
        panel.preferredSize = Dimension(720, 680)
        return panel
    }

    override fun createActions(): Array<Action> {
        analyseAction = object : DialogWrapperAction("Analyse") {
            override fun doAction(e: ActionEvent) { analyseFiles() }
        }
        installAction = object : DialogWrapperAction("Install") {
            override fun doAction(e: ActionEvent) { installFiles() }
        }
        installAction.isEnabled = false

        return arrayOf(
            analyseAction,
            installAction,
            object : DialogWrapperAction("Close") {
                override fun doAction(e: ActionEvent) { close(OK_EXIT_CODE) }
            }
        )
    }

    // ── Load and filter ───────────────────────────────────────────────────

    private fun loadDownloadsFolder() {
        allFiles.clear()
        checkedItems.clear()
        if (!downloadsDir.exists()) return
        downloadsDir.listFiles { f ->
            f.isFile && (f.name.endsWith(".java") || f.name.endsWith(".zip"))
        }
        ?.map { DownloadedFile(it, it.lastModified()) }
        ?.sortedByDescending { it.lastModified }
        ?.let { allFiles.addAll(it) }
    }

    private fun applyFilter() {
        fileListModel.clear()
        checkedItems.clear()
        resetAnalysis()

        val fromDate = parseDate(fromField.text)
        val toDate   = parseDate(toField.text)?.let {
            Calendar.getInstance().apply { time = it; add(Calendar.DAY_OF_MONTH, 1) }.time
        }

        allFiles.filter { df ->
            val fileDate = Date(df.lastModified)
            (fromDate == null || !fileDate.before(fromDate)) &&
            (toDate   == null || fileDate.before(toDate))
        }.forEach { fileListModel.addElement(it) }
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

    // ── Reset analysis state ──────────────────────────────────────────────

    private fun resetAnalysis() {
        analyseComplete = false
        analysedEntries.clear()
        statusArea.text = ""
        resultArea.text = ""
        installAction.isEnabled = false
        fileList.repaint()
    }

    // ── Analyse ───────────────────────────────────────────────────────────

    private fun analyseFiles() {
        resetAnalysis()
        val sourceRoots = getSourceRoots() ?: run { status("ERROR: No source roots found for selected module."); return }

        if (checkedItems.isEmpty()) { status("No files selected."); return }

        var newCount       = 0
        var changedCount   = 0
        var identicalCount = 0
        var skipCount      = 0

        checkedItems.sorted().forEach { index ->
            val df = fileListModel.getElementAt(index)
            when {
                df.file.name.endsWith(".zip")  -> {
                    val counts = analyseZip(df.file, sourceRoots)
                    newCount       += counts[0]
                    changedCount   += counts[1]
                    identicalCount += counts[2]
                    skipCount      += counts[3]
                }
                df.file.name.endsWith(".java") -> {
                    when (analyseJava(df.file, sourceRoots)) {
                        FileStatus.NEW       -> newCount++
                        FileStatus.CHANGED   -> changedCount++
                        FileStatus.IDENTICAL -> identicalCount++
                        FileStatus.SKIP      -> skipCount++
                    }
                }
            }
        }

        status("")
        status("── Summary ─────────────────────────────────────────")
        status("  NEW:       $newCount  file(s) — will be installed")
        status("  CHANGED:   $changedCount  file(s) — will be installed")
        status("  IDENTICAL: $identicalCount  file(s) — will be skipped")
        status("  SKIPPED:   $skipCount  file(s) — package not found or no package")

        analyseComplete = true
        installAction.isEnabled = (newCount + changedCount) > 0
        if (!installAction.isEnabled) status("\nNothing to install.")
    }

    private fun analyseJava(file: File, sourceRoots: List<File>): FileStatus {
        val content   = file.readText()
        val pkg       = readPackage(content) ?: run { status("SKIP  (no package):  ${file.name}"); return FileStatus.SKIP }
        val targetDir = findPackageDir(pkg, sourceRoots) ?: run { status("SKIP  (pkg missing): $pkg  ←  ${file.name}"); return FileStatus.SKIP }
        val className = cleanFileName(file.name)
        val target    = File(targetDir, className)
        return when {
            !target.exists()               -> { status("NEW       ${file.name}"); FileStatus.NEW }
            target.readText() == content   -> { FileStatus.IDENTICAL }
            else                           -> { status("CHANGED   ${file.name}"); FileStatus.CHANGED }
        }
    }

    private fun analyseZip(file: File, sourceRoots: List<File>): IntArray {
        var newCount = 0; var changedCount = 0; var identicalCount = 0; var skipCount = 0
        status("ZIP: ${file.name}")
        var javaCount = 0
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".java") }.forEach { entry ->
                javaCount++
                val content   = zip.getInputStream(entry).bufferedReader().readText()
                val pkg       = readPackage(content)
                val fileName  = entry.name.substringAfterLast("/")
                val className = cleanFileName(fileName)
                if (pkg == null) {
                    status("  SKIP  (no package):  $fileName")
                    skipCount++
                    return@forEach
                }
                val targetDir = findPackageDir(pkg, sourceRoots)
                if (targetDir == null) {
                    status("  SKIP  (pkg missing): $pkg  ←  $fileName")
                    skipCount++
                    return@forEach
                }
                val target = File(targetDir, className)
                when {
                    !target.exists()                   -> { status("  NEW       $fileName"); newCount++ }
                    target.readText() == content       -> { identicalCount++ }
                    else                               -> { status("  CHANGED   $fileName"); changedCount++ }
                }
            }
        }
        if (javaCount == 0) status("  (no routeable .java files in zip)")
        return intArrayOf(newCount, changedCount, identicalCount, skipCount)
    }

    // ── Install ───────────────────────────────────────────────────────────

    private fun installFiles() {
        resultArea.text = ""
        if (!analyseComplete) { result("Run Analyse first."); return }
        val sourceRoots = getSourceRoots() ?: return
        var installed = 0; var skipped = 0; var errors = 0

        checkedItems.sorted().forEach { index ->
            val df = fileListModel.getElementAt(index)
            when {
                df.file.name.endsWith(".zip")  -> {
                    val (i, s, e) = installZip(df.file, sourceRoots)
                    installed += i; skipped += s; errors += e
                }
                df.file.name.endsWith(".java") -> {
                    when (installJava(df.file, sourceRoots)) {
                        InstallResult.INSTALLED -> installed++
                        InstallResult.SKIPPED   -> skipped++
                        InstallResult.ERROR     -> errors++
                    }
                }
            }
        }

        result("")
        result("── Complete ─────────────────────────────────────────")
        result("  Installed: $installed  |  Skipped (identical): $skipped  |  Errors: $errors")
        LocalFileSystem.getInstance().refresh(true)
    }

    enum class InstallResult { INSTALLED, SKIPPED, ERROR }

    private fun installJava(file: File, sourceRoots: List<File>): InstallResult {
        val content   = file.readText()
        val pkg       = readPackage(content) ?: run { result("SKIP  (no package):  ${file.name}"); return InstallResult.ERROR }
        val targetDir = findPackageDir(pkg, sourceRoots) ?: run { result("SKIP  (pkg missing): $pkg  ←  ${file.name}"); return InstallResult.ERROR }
        val className = cleanFileName(file.name)
        val target    = File(targetDir, className)

        if (target.exists() && target.readText() == content) {
            return InstallResult.SKIPPED
        }

        if (target.exists()) {
            val bak = File(targetDir, "$className.bak")
            if (bak.exists()) bak.delete()
            target.renameTo(bak)
            result("BACKUP    $className  →  $className.bak")
        }

        Files.copy(file.toPath(), target.toPath())
        result("INSTALLED ${file.name}  →  ${targetDir.absolutePath}")
        return InstallResult.INSTALLED
    }

    private fun installZip(file: File, sourceRoots: List<File>): Triple<Int, Int, Int> {
        var installed = 0; var skipped = 0; var errors = 0
        result("ZIP: ${file.name}")
        ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".java") }.forEach { entry ->
                val content   = zip.getInputStream(entry).bufferedReader().readText()
                val pkg       = readPackage(content)
                val fileName  = entry.name.substringAfterLast("/")
                val className = cleanFileName(fileName)

                if (pkg == null) { result("  SKIP  (no package):  $fileName"); errors++; return@forEach }
                val targetDir = findPackageDir(pkg, sourceRoots) ?: run {
                    result("  SKIP  (pkg missing): $pkg  ←  $fileName"); errors++; return@forEach
                }
                val target = File(targetDir, className)

                if (target.exists() && target.readText() == content) {
                    skipped++
                    return@forEach
                }

                if (target.exists()) {
                    val bak = File(targetDir, "$className.bak")
                    if (bak.exists()) bak.delete()
                    target.renameTo(bak)
                    result("  BACKUP    $className  →  $className.bak")
                }

                target.writeText(content)
                result("  INSTALLED $fileName  →  ${targetDir.absolutePath}")
                installed++
            }
        }
        return Triple(installed, skipped, errors)
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
    private fun status(line: String) { statusArea.append(line + "\n") }
    private fun result(line: String) { resultArea.append(line + "\n") }

    // ── Checkbox list renderer ────────────────────────────────────────────

    inner class CheckBoxListRenderer : ListCellRenderer<DownloadedFile> {
        private val panel     = JPanel(BorderLayout())
        private val checkBox  = JCheckBox().apply { font = editorFont }
        private val dateLabel = JBLabel("").apply {
            foreground = Color.GRAY
            font = editorFont
        }
        init { panel.add(checkBox, BorderLayout.WEST); panel.add(dateLabel, BorderLayout.EAST) }

        override fun getListCellRendererComponent(
            list: JList<out DownloadedFile>, value: DownloadedFile, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
            checkBox.text       = value.file.name
            checkBox.isSelected = checkedItems.contains(index)
            dateLabel.text      = "  " + sdtf.format(Date(value.lastModified)) + "  "
            val bg = if (isSelected) list.selectionBackground else list.background
            panel.background     = bg
            checkBox.background  = bg
            dateLabel.background = bg
            checkBox.foreground  = if (isSelected) list.selectionForeground else list.foreground
            return panel
        }
    }
}
