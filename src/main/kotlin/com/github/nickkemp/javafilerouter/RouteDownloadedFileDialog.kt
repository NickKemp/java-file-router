package com.github.nickkemp.javafilerouter

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
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

class RouteDownloadedFileDialog(
    private val project: Project,
    private val event: AnActionEvent
) : DialogWrapper(project) {

    // ── Font ──────────────────────────────────────────────────────────────

    private val editorFont = EditorColorsManager.getInstance().globalScheme.let {
        java.awt.Font(it.editorFontName, java.awt.Font.PLAIN, it.editorFontSize)
    }

    // ── File status ───────────────────────────────────────────────────────

    enum class FileStatus { NEW, CHANGED, IDENTICAL, SKIP, NEW_PKG }

    data class AnalysedEntry(
        val pkg:      String?,   // null = no package declaration
        val fileName: String,
        val status:   FileStatus,
        val source:   String     // originating file (zip name or java filename)
    )

    // ── Changed file record — holds both sides of the diff ────────────────

    data class ChangedFile(
        val fileName:        String,
        val downloadedText:  String,
        val existingText:    String
    )

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

    // Analyse results
    private val analysedEntries  = mutableListOf<AnalysedEntry>()
    private val changedFiles     = mutableListOf<ChangedFile>()
    private val newPackages      = mutableSetOf<String>()  // distinct new package names
    private var analyseComplete  = false
    private var knownFileCount   = 0
    private var newPackageCount  = 0

    private lateinit var analyseAction:   Action
    private lateinit var installAction:   Action
    private lateinit var showDiffsAction: Action

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

        // Pre-select the module containing the currently active file
        val virtualFile = event.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: event.getData(CommonDataKeys.PSI_FILE)?.virtualFile
        if (virtualFile != null) {
            val currentModule = ModuleUtilCore.findModuleForFile(virtualFile, project)
            if (currentModule != null) {
                moduleCombo.selectedItem = currentModule.name
            }
        }

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

    override fun createSouthAdditionalPanel(): JPanel {
        val version = com.intellij.ide.plugins.PluginManagerCore
            .getPlugin(com.intellij.openapi.extensions.PluginId.getId(
                "com.github.nickkemp.javafilerouter"))
            ?.version ?: "unknown"
        val label = JBLabel("Java File Download Router  v$version").apply {
            foreground = java.awt.Color.GRAY
            font       = editorFont.deriveFont(java.awt.Font.PLAIN, editorFont.size - 1f)
        }
        return JPanel(BorderLayout()).apply { add(label, BorderLayout.WEST) }
    }

    override fun createActions(): Array<Action> {
        analyseAction = object : DialogWrapperAction("Analyse") {
            override fun doAction(e: ActionEvent) { analyseFiles() }
        }
        installAction = object : DialogWrapperAction("Install") {
            override fun doAction(e: ActionEvent) { installFiles() }
        }
        showDiffsAction = object : DialogWrapperAction("Show Diffs") {
            override fun doAction(e: ActionEvent) { showDiffPicker() }
        }
        installAction.isEnabled   = false
        showDiffsAction.isEnabled = false

        return arrayOf(
            analyseAction,
            showDiffsAction,
            installAction,
            object : DialogWrapperAction("Close") {
                override fun doAction(e: ActionEvent) { close(OK_EXIT_CODE) }
            }
        )
    }

    // ── Diff picker ───────────────────────────────────────────────────────

    private fun showDiffPicker() {
        if (changedFiles.isEmpty()) return

        val dialog = object : DialogWrapper(project, true) {
            init {
                title = "Changed Files — Select to View Diff"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val listModel = DefaultListModel<String>()
                changedFiles.forEach { listModel.addElement(it.fileName) }

                val list = JList(listModel).apply {
                    selectionMode = ListSelectionModel.SINGLE_SELECTION
                    font = editorFont
                    selectedIndex = 0
                }

                val panel = JPanel(BorderLayout(4, 4))
                panel.add(JBLabel("Select a file to view its diff:"), BorderLayout.NORTH)
                panel.add(JBScrollPane(list).apply {
                    preferredSize = Dimension(500, 300)
                }, BorderLayout.CENTER)

                // Double-click opens the diff immediately
                list.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (e.clickCount == 2) {
                            val index = list.selectedIndex
                            if (index >= 0) openDiff(changedFiles[index])
                        }
                    }
                })

                val viewBtn = JButton("View Diff").apply {
                    addActionListener {
                        val index = list.selectedIndex
                        if (index >= 0) openDiff(changedFiles[index])
                    }
                }

                panel.add(viewBtn, BorderLayout.SOUTH)
                return panel
            }

            override fun createActions(): Array<Action> = arrayOf(
                object : DialogWrapperAction("Close") {
                    override fun doAction(e: ActionEvent) { close(OK_EXIT_CODE) }
                }
            )
        }
        dialog.show()
    }

    private fun openDiff(changed: ChangedFile) {
        val factory = DiffContentFactory.getInstance()
        val left:  DiffContent = factory.create(changed.existingText)
        val right: DiffContent = factory.create(changed.downloadedText)
        val request = SimpleDiffRequest(
            changed.fileName,
            left,
            right,
            "Current (project)",
            "Downloaded"
        )
        DiffManager.getInstance().showDiff(project, request)
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
        analyseComplete  = false
        knownFileCount   = 0
        newPackageCount  = 0
        analysedEntries.clear()
        changedFiles.clear()
        newPackages.clear()
        statusArea.text = ""
        resultArea.text = ""
        installAction.isEnabled   = false
        showDiffsAction.isEnabled = false
        fileList.repaint()
    }

    // ── Analyse ───────────────────────────────────────────────────────────

    private fun analyseFiles() {
        resetAnalysis()
        val sourceRoots = getSourceRoots() ?: run { status("ERROR: No source roots found for selected module."); return }
        if (checkedItems.isEmpty()) { status("No files selected."); return }

        // ── Pass 1: collect all entries ───────────────────────────────────
        checkedItems.sorted().forEach { index ->
            val df = fileListModel.getElementAt(index)
            when {
                df.file.name.endsWith(".zip")  -> collectZip(df.file, sourceRoots)
                df.file.name.endsWith(".java") -> collectJava(df.file, sourceRoots)
            }
        }

        // ── Pass 2: derive counts ─────────────────────────────────────────
        var newCount       = 0
        var changedCount   = 0
        var identicalCount = 0
        var skipCount      = 0
        var newPkgCount    = 0

        analysedEntries.forEach { e ->
            when (e.status) {
                FileStatus.NEW       -> newCount++
                FileStatus.CHANGED   -> changedCount++
                FileStatus.IDENTICAL -> identicalCount++
                FileStatus.SKIP      -> skipCount++
                FileStatus.NEW_PKG   -> newPkgCount++
            }
        }

        knownFileCount  = newCount + changedCount
        newPackageCount = newPkgCount

        // ── Pass 3: render grouped output ─────────────────────────────────

        val byPkg   = linkedMapOf<String, MutableList<AnalysedEntry>>()
        val skipped = mutableListOf<AnalysedEntry>()

        analysedEntries.forEach { e ->
            when {
                e.status == FileStatus.SKIP || e.pkg == null -> skipped.add(e)
                e.status == FileStatus.IDENTICAL             -> { /* not shown */ }
                else -> byPkg.getOrPut(e.pkg) { mutableListOf() }.add(e)
            }
        }

        renderGrouped(byPkg, skipped)

        // ── Summary ───────────────────────────────────────────────────────
        val newPkgNames   = newPackages.toSortedSet()
        val effectiveNew  = newCount + (if (knownFileCount > 0) newPkgCount else 0)

        status("")
        status("── Summary ─────────────────────────────────────────")
        if (newPkgCount > 0 && knownFileCount > 0) {
            val pkgWord = if (newPkgNames.size == 1) "package" else "packages"
            status("  NEW PKG:   ${newPkgNames.size}  new $pkgWord will be created:")
            newPkgNames.forEach { status("             $it") }
        } else if (newPkgCount > 0) {
            val pkgWord = if (newPkgNames.size == 1) "package" else "packages"
            status("  NEW PKG:   ${newPkgNames.size}  new $pkgWord detected — SKIPPED (wrong module selected?):")
            newPkgNames.forEach { status("             $it") }
        }
        status("  NEW:       $effectiveNew  file(s) — will be installed")
        status("  CHANGED:   $changedCount  file(s) — will be installed")
        status("  IDENTICAL: $identicalCount  file(s) — will be skipped")
        status("  SKIPPED:   $skipCount  file(s) — no package declaration")

        analyseComplete = true
        val installable = knownFileCount + (if (knownFileCount > 0) newPkgCount else 0)
        installAction.isEnabled   = installable > 0
        showDiffsAction.isEnabled = changedCount > 0
        if (!installAction.isEnabled) status("\nNothing to install.")
    }

    /**
     * Render grouped output:
     *   Source header (ZIP or .java filename)
     *     NEW PKG / UPD PKG header per package
     *       NEW / CHANGED per file indented
     * IDENTICAL files are not shown.
     * Files with no package shown at the end.
     */
    private fun renderGrouped(
        byPkg:   LinkedHashMap<String, MutableList<AnalysedEntry>>,
        skipped: List<AnalysedEntry>
    ) {
        // Determine source order and which packages belong to which source
        val sourceOrder = linkedSetOf<String>()
        val pkgBySource = linkedMapOf<String, MutableList<String>>()
        byPkg.keys.forEach { pkg ->
            val src = byPkg[pkg]!!.first().source
            sourceOrder.add(src)
            pkgBySource.getOrPut(src) { mutableListOf() }.add(pkg)
        }

        sourceOrder.forEach { src ->
            status(if (src.endsWith(".zip")) "ZIP: $src" else src)
            pkgBySource[src]?.forEach { pkg ->
                val entries = byPkg[pkg] ?: return@forEach
                val isNew   = newPackages.contains(pkg)
                status("  ${if (isNew) "NEW PKG" else "UPD PKG"}   $pkg")
                entries.forEach { e ->
                    val tag = when (e.status) {
                        FileStatus.NEW, FileStatus.NEW_PKG -> "NEW    "
                        FileStatus.CHANGED                 -> "CHANGED"
                        else                               -> return@forEach
                    }
                    status("            $tag   ${e.fileName}")
                }
            }
        }

        // Flat skipped entries grouped by source
        skipped.groupBy { it.source }.forEach { (src, entries) ->
            val isEmptyZip = entries.all { it.fileName.startsWith("(no routeable") }
            if (!isEmptyZip) status(if (src.endsWith(".zip")) "ZIP: $src" else src)
            entries.forEach { e ->
                status(if (e.fileName.startsWith("(no routeable"))
                    "  $src — ${e.fileName}"
                else
                    "  SKIP  (no package):  ${e.fileName}")
            }
        }
    }

    private fun collectJava(file: File, sourceRoots: List<File>) {
        val content   = file.readText()
        val pkg       = readPackage(content)
        val className = cleanFileName(file.name)
        if (pkg == null) { analysedEntries.add(AnalysedEntry(null, className, FileStatus.SKIP, file.name)); return }
        val targetDir = findPackageDir(pkg, sourceRoots)
        if (targetDir == null) {
            newPackages.add(pkg)
            analysedEntries.add(AnalysedEntry(pkg, className, FileStatus.NEW_PKG, file.name))
            return
        }
        val target = File(targetDir, className)
        val status = when {
            !target.exists()             -> FileStatus.NEW
            target.readText() == content -> FileStatus.IDENTICAL
            else                         -> {
                changedFiles.add(ChangedFile(className, content, target.readText()))
                FileStatus.CHANGED
            }
        }
        analysedEntries.add(AnalysedEntry(pkg, className, status, file.name))
    }

    private fun collectZip(file: File, sourceRoots: List<File>) {
        var javaCount = 0
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".java") }
                .forEach { entry ->
                    javaCount++
                    val content   = zip.getInputStream(entry).bufferedReader().readText()
                    val pkg       = readPackage(content)
                    val fileName  = entry.name.substringAfterLast("/")
                    val className = cleanFileName(fileName)
                    if (pkg == null) {
                        analysedEntries.add(AnalysedEntry(null, className, FileStatus.SKIP, file.name))
                        return@forEach
                    }
                    val targetDir = findPackageDir(pkg, sourceRoots)
                    if (targetDir == null) {
                        newPackages.add(pkg)
                        analysedEntries.add(AnalysedEntry(pkg, className, FileStatus.NEW_PKG, file.name))
                        return@forEach
                    }
                    val target = File(targetDir, className)
                    val status = when {
                        !target.exists()             -> FileStatus.NEW
                        target.readText() == content -> FileStatus.IDENTICAL
                        else                         -> {
                            changedFiles.add(ChangedFile(className, content, target.readText()))
                            FileStatus.CHANGED
                        }
                    }
                    analysedEntries.add(AnalysedEntry(pkg, className, status, file.name))
                }
        }
        if (javaCount == 0) analysedEntries.add(AnalysedEntry(null, "(no routeable .java files in zip)", FileStatus.SKIP, file.name))
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

        val existingDir = findPackageDir(pkg, sourceRoots)
        val targetDir = if (existingDir != null) {
            existingDir
        } else {
            if (knownFileCount == 0) {
                result("SKIP  (new package, no known files to confirm module): $pkg  ←  ${file.name}")
                return InstallResult.SKIPPED
            }
            findOrCreatePackageDir(pkg, sourceRoots) ?: run {
                result("ERROR  (could not create package): $pkg  ←  ${file.name}"); return InstallResult.ERROR
            }
        }

        val className = cleanFileName(file.name)
        val target    = File(targetDir, className)

        if (target.exists() && target.readText() == content) return InstallResult.SKIPPED

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

                val existingDir = findPackageDir(pkg, sourceRoots)
                val targetDir = if (existingDir != null) {
                    existingDir
                } else {
                    if (knownFileCount == 0) {
                        result("  SKIP  (new package, no known files to confirm module): $pkg  ←  $fileName")
                        skipped++
                        return@forEach
                    }
                    findOrCreatePackageDir(pkg, sourceRoots) ?: run {
                        result("  ERROR  (could not create package): $pkg  ←  $fileName"); errors++; return@forEach
                    }
                }

                val target = File(targetDir, className)

                if (target.exists() && target.readText() == content) { skipped++; return@forEach }

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

    /**
     * Like findPackageDir but creates the directory under the first source root
     * if it doesn't exist yet. Used during install so new packages are created
     * automatically rather than being skipped.
     */
    private fun findOrCreatePackageDir(pkg: String, sourceRoots: List<File>): File? {
        val rel = pkg.replace('.', File.separatorChar)
        val existing = sourceRoots.map { File(it, rel) }.firstOrNull { it.exists() && it.isDirectory }
        if (existing != null) return existing
        val root = sourceRoots.firstOrNull() ?: return null
        val newDir = File(root, rel)
        newDir.mkdirs()
        return if (newDir.exists()) newDir else null
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
