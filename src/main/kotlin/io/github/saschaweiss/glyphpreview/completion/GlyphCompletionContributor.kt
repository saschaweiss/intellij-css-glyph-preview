package io.github.saschaweiss.glyphpreview.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.css.CssBlock
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import io.github.saschaweiss.glyphpreview.css.CssGlyphUtil
import io.github.saschaweiss.glyphpreview.font.GlyphMetadata
import io.github.saschaweiss.glyphpreview.font.GlyphRenderer
import io.github.saschaweiss.glyphpreview.settings.GlyphSettings

/**
 * The "Symbolauswahl": inside a `content` value, when the rule's font-family is a
 * registered font WITH a metadata map, offer the icon set as completion items —
 * searchable by name, each showing its rendered glyph.
 *
 * Selecting an item rewrites the matched range to the `\fXXXX` escape.
 */
class GlyphCompletionContributor : CompletionContributor() {

    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), Provider())
    }

    private class Provider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet,
        ) {
            val position = parameters.position

            // Only inside a `content:` declaration.
            val decl = CssGlyphUtil.declarationOf(position) ?: return
            if (!decl.text.trimStart().startsWith("content")) return

            val family = CssGlyphUtil.resolveFamily(position)
            val weight = CssGlyphUtil.resolveWeight(position)
            val candidates = GlyphSettings.getInstance().candidates(family, weight)
            if (candidates.isEmpty()) return
            val metadataPath = candidates.firstNotNullOfOrNull { it.metadataPath } ?: return

            val glyphs = GlyphMetadata.load(metadataPath)
            if (glyphs.isEmpty()) return

            // Let the user type the icon name even though the prefix may start with "\f".
            val cleanPrefix = result.prefixMatcher.prefix.trimStart('\\', 'f', '"', '\'')
            val rs = result.withPrefixMatcher(cleanPrefix)

            for (glyph in glyphs) {
                // Render with whichever candidate font actually contains the glyph
                // (weight-preferred). Skip names no candidate font can display.
                val rendered = candidates.firstNotNullOfOrNull { e ->
                    GlyphRenderer.icon(e.fontFilePath, glyph.codepoint)?.let { e to it }
                } ?: continue
                val (renderEntry, icon) = rendered
                val requiredWeight = renderEntry.weight
                val escape = "\\%x".format(glyph.codepoint)
                val element = LookupElementBuilder.create(glyph, glyph.name)
                    .withIcon(icon)
                    .withTypeText(escape, true)
                    // Also match without the "fa-" prefix, so typing "house" finds "fa-house".
                    .withLookupString(glyph.name.removePrefix("fa-"))
                    .withInsertHandler { ctx, _ ->
                        ctx.document.replaceString(ctx.startOffset, ctx.tailOffset, escape)
                        // The declaration is now complete → terminate it with a semicolon.
                        ensureSemicolon(ctx, ctx.startOffset)
                        // Make sure the rule actually renders on the web: the picked
                        // glyph only exists at `requiredWeight`, so set font-weight
                        // if the rule's effective weight differs.
                        ensureFontWeight(ctx, ctx.startOffset, requiredWeight)
                    }
                rs.addElement(element)
            }
        }

        private fun ensureFontWeight(ctx: InsertionContext, offset: Int, requiredWeight: Int) {
            if (requiredWeight == 0) return // font registered as "any weight" — nothing to enforce
            val doc = ctx.document
            val pdm = PsiDocumentManager.getInstance(ctx.project)
            pdm.commitDocument(doc)

            val element = ctx.file.findElementAt(offset) ?: return
            // Includes parent blocks + the 400 default; if already correct, leave it.
            if (CssGlyphUtil.resolveWeight(element) == requiredWeight) return

            val block = PsiTreeUtil.getParentOfType(element, CssBlock::class.java, false) ?: return
            val blockStart = block.textRange.startOffset
            val existing = Regex("""font-weight\s*:\s*\d+""").find(block.text)

            if (existing != null) {
                doc.replaceString(
                    blockStart + existing.range.first,
                    blockStart + existing.range.last + 1,
                    "font-weight: $requiredWeight",
                )
            } else {
                // Insert a font-weight line AFTER the content line, matching its indent.
                val line = doc.getLineNumber(offset)
                val lineStart = doc.getLineStartOffset(line)
                val lineEnd = doc.getLineEndOffset(line)
                val indent = doc.getText(TextRange(lineStart, offset)).takeWhile { it == ' ' || it == '\t' }
                doc.insertString(lineEnd, "\n${indent}font-weight: $requiredWeight;")
            }
            pdm.commitDocument(doc)
        }

        /** Appends a `;` at the end of the content line if it isn't terminated yet. */
        private fun ensureSemicolon(ctx: InsertionContext, offset: Int) {
            val doc = ctx.document
            val line = doc.getLineNumber(offset)
            val lineStart = doc.getLineStartOffset(line)
            val lineEnd = doc.getLineEndOffset(line)
            val lineText = doc.getText(TextRange(lineStart, lineEnd))
            val trimmed = lineText.trimEnd()
            if (trimmed.isNotEmpty() && !trimmed.endsWith(";")) {
                doc.insertString(lineStart + trimmed.length, ";")
            }
        }
    }
}
