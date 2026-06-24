package io.github.saschaweiss.glyphpreview.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Add/Edit dialog for one registered font.
 *
 * Uses [TextFieldWithBrowseButton] instead of a bare FileChooser popup: this is
 * the standard JetBrains pattern for choosing files inside a modal Settings
 * dialog and handles the nested-modality/focus correctly on macOS.
 */
class FontEntryDialog(source: FontEntry?) : DialogWrapper(true) {

    private val displayField = JBTextField()
    private val familyField = JBTextField()
    private val weightField = JBTextField()
    private val fontField = TextFieldWithBrowseButton()
    private val metaField = TextFieldWithBrowseButton()

    init {
        title = if (source == null) "Add Icon Font" else "Edit Icon Font"

        fontField.addBrowseFolderListener(
            null,
            // single file, no folders/jars/multi-select
            FileChooserDescriptor(true, false, false, false, false, false)
                .withTitle("Select Icon Font (.ttf / .otf)"),
        )
        metaField.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor("css")
                .withTitle("Select Icon Metadata CSS (e.g. fontawesome.min.css)"),
        )

        source?.let {
            displayField.text = it.displayName
            familyField.text = it.fontFamily
            weightField.text = if (it.weight == 0) "" else it.weight.toString()
            fontField.text = it.fontFilePath
            metaField.text = it.metadataPath ?: ""
        }

        // Auto-fill the other fields when a font is chosen — but only blank ones,
        // so manual edits and existing values are never clobbered. Registered
        // AFTER restoring [source] so opening the Edit dialog doesn't overwrite.
        fontField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = autofillFromFont()
        })

        init()
    }

    private fun autofillFromFont() {
        val path = fontField.text.trim()
        if (path.isBlank() || !File(path).isFile) return
        val baseName = File(path).nameWithoutExtension

        if (displayField.text.isBlank()) displayField.text = baseName
        if (familyField.text.isBlank()) {
            familyField.text = runCatching { GlyphRenderer.familyNameOf(path) }.getOrNull() ?: baseName
        }
        if (weightField.text.isBlank()) {
            Regex("""(\d{3})""").find(baseName)?.groupValues?.get(1)?.let { weightField.text = it }
        }
        if (metaField.text.isBlank()) {
            FontAssets.findMetadataNear(path)?.let { metaField.text = it }
        }
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Name:", displayField)
        .addLabeledComponent("font-family:", familyField)
        .addLabeledComponent("Weight (e.g. 900):", weightField)
        .addLabeledComponent("Font file (.ttf / .otf):", fontField)
        .addLabeledComponent("Metadata CSS (optional):", metaField)
        .panel

    override fun doValidate(): ValidationInfo? {
        val path = fontField.text.trim()
        if (path.isBlank()) return ValidationInfo("Please choose a font file.", fontField)
        if (!File(path).isFile) return ValidationInfo("File not found.", fontField)
        return null
    }

    /** Builds the entry, importing the chosen files into the plugin config folder. */
    fun toEntry(): FontEntry {
        val fontPath = fontField.text.trim()
        val baseName = File(fontPath).nameWithoutExtension
        val importedFont = FontAssets.importAsset(fontPath)

        val metaInput = metaField.text.trim().ifBlank { FontAssets.findMetadataNear(fontPath) ?: "" }
        val importedMeta = if (metaInput.isNotBlank()) FontAssets.importAsset(metaInput) else null

        return FontEntry().apply {
            displayName = displayField.text.trim().ifBlank { baseName }
            fontFamily = familyField.text.trim().ifBlank {
                GlyphRenderer.familyNameOf(importedFont) ?: baseName
            }
            weight = weightField.text.trim().toIntOrNull()
                ?: Regex("""(\d{3})""").find(baseName)?.groupValues?.get(1)?.toIntOrNull()
                ?: 0
            fontFilePath = importedFont
            metadataPath = importedMeta
        }
    }
}

/**
 * Copies fonts/metadata into <IDE-config>/css-glyph-preview/assets/ so registered
 * entries survive the originals being deleted, and locates a FontAwesome-style
 * icon map next to a chosen font.
 */
object FontAssets {

    private fun assetsDir(): Path = Path.of(PathManager.getConfigPath(), "css-glyph-preview", "assets")

    fun importAsset(sourcePath: String): String = runCatching {
        val dir = assetsDir()
        Files.createDirectories(dir)
        val target = dir.resolve(Path.of(sourcePath).fileName.toString())
        Files.copy(Path.of(sourcePath), target, StandardCopyOption.REPLACE_EXISTING)
        target.toString()
    }.getOrDefault(sourcePath)

    /** Deletes copied assets that no longer belong to any registered entry. */
    fun cleanupOrphans(entries: List<FontEntry>) {
        runCatching {
            val dir = assetsDir()
            if (!Files.isDirectory(dir)) return
            val referenced = buildSet {
                entries.forEach {
                    add(it.fontFilePath)
                    it.metadataPath?.let { m -> add(m) }
                }
            }
            Files.list(dir).use { stream ->
                stream.filter { it.toString() !in referenced }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /**
     * The name->codepoint rules live in fontawesome(.min).css / all(.min).css —
     * NOT in the per-style solid/regular files. Looks in the font's folder,
     * its parent, and sibling/parent `css` folders.
     */
    fun findMetadataNear(fontPath: String): String? {
        val fontDir = File(fontPath).parentFile ?: return null
        val dirs = listOfNotNull(
            fontDir,
            fontDir.parentFile,
            File(fontDir, "css"),
            fontDir.parentFile?.let { File(it, "css") },
        )
        val names = listOf("fontawesome.min.css", "fontawesome.css", "all.min.css", "all.css")
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            for (name in names) {
                val f = File(dir, name)
                if (f.isFile) return f.path
            }
        }
        return null
    }
}
