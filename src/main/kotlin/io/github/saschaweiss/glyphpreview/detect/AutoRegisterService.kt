package io.github.saschaweiss.glyphpreview.detect

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.notification.NotificationAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.saschaweiss.glyphpreview.font.GlyphMetadata
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.html.HtmlIconResolver
import io.github.saschaweiss.glyphpreview.settings.FontAssets
import io.github.saschaweiss.glyphpreview.settings.FontEntry
import io.github.saschaweiss.glyphpreview.settings.GlyphConfigurable
import io.github.saschaweiss.glyphpreview.settings.GlyphSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Reacts to unresolved icon usages reported by the resolvers: offers a one-click
 * registration if the font is found in the project, otherwise a hint. Each font
 * key is handled at most once per session and can be silenced permanently.
 */
@Service(Service.Level.PROJECT)
class AutoRegisterService(private val project: Project) {

    private val handled = ConcurrentHashMap.newKeySet<String>()

    /** Called (cheaply) from the marker pass when an icon usage cannot be rendered. */
    fun consider(usage: IconUsage) {
        val key = usage.key()
        if (!handled.add(key)) return                       // once per session per font
        if (GlyphSettings.getInstance().isIgnored(key)) return

        ReadAction.nonBlocking<IconFontDetector.Match?> { IconFontDetector.detect(project, usage) }
            .finishOnUiThread(ModalityState.defaultModalityState()) { match ->
                showNotification(usage, key, match)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun showNotification(usage: IconUsage, key: String, match: IconFontDetector.Match?) {
        if (project.isDisposed) return
        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

        val notification = if (match != null) {
            group.createNotification(
                "${usage.displayName} icons detected",
                "A matching font was found in your project. Register it so the icons preview here?",
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring("Register") { register(match) })
        } else {
            group.createNotification(
                "${usage.displayName} icons in use",
                "No matching font is registered yet. Open settings to add it.",
                NotificationType.INFORMATION,
            ).addAction(NotificationAction.createSimpleExpiring("Open settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GlyphConfigurable::class.java)
            })
        }

        notification.addAction(NotificationAction.createSimpleExpiring("Don't ask again") {
            GlyphSettings.getInstance().ignore(key)
        })
        notification.notify(project)
    }

    private fun register(match: IconFontDetector.Match) {
        val settings = GlyphSettings.getInstance()
        settings.fonts.add(
            FontEntry().apply {
                // Display name = the font file's own identity (honest); font-family = the
                // CSS-matchable family so it actually matches the rule.
                displayName = GlyphRenderer.familyNameOf(match.fontPath) ?: File(match.fontPath).nameWithoutExtension
                fontFilePath = FontAssets.importAsset(match.fontPath)
                fontFamily = match.family
                weight = match.weight
                metadataPath = match.metadataPath?.let { FontAssets.importAsset(it) }
            },
        )
        GlyphRenderer.clearCache()
        GlyphMetadata.clearCache()
        HtmlIconResolver.clearCache()
        val analyzer = DaemonCodeAnalyzer.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        FileEditorManager.getInstance(project).openFiles.forEach { vf ->
            psiManager.findFile(vf)?.let { analyzer.restart(it) }
        }
    }

    companion object {
        const val NOTIFICATION_GROUP = "CSS Glyph Preview"

        fun getInstance(project: Project): AutoRegisterService =
            project.getService(AutoRegisterService::class.java)
    }
}
