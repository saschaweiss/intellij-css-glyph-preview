package io.github.saschaweiss.glyphpreview.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import io.github.saschaweiss.glyphpreview.font.GlyphMetadata
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.AbstractTableModel

/**
 * Settings > Tools > CSS Glyph Preview.
 *
 * A table of registered fonts. Add/Edit open [FontEntryDialog] (with proper
 * browse-button fields) rather than a bare FileChooser popup.
 */
class GlyphConfigurable : Configurable {

    private val settings = GlyphSettings.getInstance()
    private val working = mutableListOf<FontEntry>()
    private val model = FontTableModel(working)
    private var table: JBTable? = null

    override fun getDisplayName(): String = "CSS Glyph Preview"

    override fun createComponent(): JComponent {
        reset()
        val t = JBTable(model)
        table = t

        val decorated = ToolbarDecorator.createDecorator(t)
            .setAddAction {
                val dialog = FontEntryDialog(null)
                if (dialog.showAndGet()) {
                    working.add(dialog.toEntry())
                    model.fireTableDataChanged()
                }
            }
            .setEditAction {
                val row = t.selectedRow
                if (row >= 0) {
                    val dialog = FontEntryDialog(working[row])
                    if (dialog.showAndGet()) {
                        working[row] = dialog.toEntry()
                        model.fireTableDataChanged()
                    }
                }
            }
            .setRemoveAction {
                val row = t.selectedRow
                if (row >= 0) {
                    working.removeAt(row)
                    model.fireTableDataChanged()
                }
            }
            .createPanel()

        return JPanel(BorderLayout()).apply { add(decorated, BorderLayout.CENTER) }
    }

    override fun isModified(): Boolean =
        working.map { it.snapshot() } != settings.fonts.map { it.snapshot() }

    override fun apply() {
        settings.fonts = working.map { it.copyOf() }.toMutableList()
        GlyphRenderer.clearCache()
        GlyphMetadata.clearCache()
        FontAssets.cleanupOrphans(settings.fonts)
    }

    override fun reset() {
        working.clear()
        settings.fonts.forEach { working.add(it.copyOf()) }
        model.fireTableDataChanged()
    }
}

private fun FontEntry.copyOf(): FontEntry = FontEntry().also {
    it.displayName = displayName
    it.fontFamily = fontFamily
    it.fontFilePath = fontFilePath
    it.weight = weight
    it.metadataPath = metadataPath
}

private fun FontEntry.snapshot() =
    listOf(displayName, fontFamily, fontFilePath, weight, metadataPath)

/** Read-only table (editing happens in the dialog). */
private class FontTableModel(val rows: List<FontEntry>) : AbstractTableModel() {
    private val cols = listOf("Name", "font-family", "Weight", "Font file", "Metadata")

    override fun getRowCount() = rows.size
    override fun getColumnCount() = cols.size
    override fun getColumnName(c: Int) = cols[c]
    override fun isCellEditable(r: Int, c: Int) = false

    override fun getValueAt(r: Int, c: Int): Any = rows[r].let {
        when (c) {
            0 -> it.displayName
            1 -> it.fontFamily
            2 -> if (it.weight == 0) "" else it.weight.toString()
            3 -> it.fontFilePath
            else -> it.metadataPath ?: ""
        }
    }
}
