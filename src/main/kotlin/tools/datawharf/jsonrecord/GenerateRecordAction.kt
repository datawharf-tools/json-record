package tools.datawharf.jsonrecord

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.datatransfer.DataFlavor
import javax.swing.JComponent

class GenerateRecordAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val dir: VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory }
        val clip = runCatching { CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) }.getOrNull().orEmpty().trim()
        val dlg = InputDialog(if (clip.startsWith("{") || clip.startsWith("[")) clip else "")
        if (!dlg.showAndGet()) return
        val err = apply(project, editor, dir, dlg.json, dlg.className, dlg.style)
        if (err != null) Messages.showErrorDialog(project, err, "JSON to Java Record")
    }

    companion object {
        /**
         * Generates records and inserts them at the caret (or creates a file in [dir]).
         * Returns a user-facing error message, or null on success.
         */
        fun apply(project: Project, editor: Editor?, dir: VirtualFile?, json: String, className: String, style: AnnotationStyle): String? {
            val rootName = with(RecordGenerator) { className.ifBlank { "Root" }.toClassName() }
            val source = try {
                RecordGenerator.generate(json, Options(rootName, style))
            } catch (ex: Exception) {
                return ex.message ?: "Invalid JSON"
            }
            if (editor != null) {
                insertIntoEditor(project, editor, source, rootName)
                return null
            }
            if (dir != null) {
                return try {
                    runWriteAction {
                        if (dir.findChild("$rootName.java") != null) return@runWriteAction "$rootName.java already exists in ${dir.name}"
                        val f = dir.createChildData(this, "$rootName.java")
                        f.setBinaryContent(source.toByteArray(Charsets.UTF_8))
                        null
                    }
                } catch (ex: Exception) {
                    ex.message ?: "Could not create $rootName.java"
                }
            }
            return "Open a Java file or select a folder in the Project view first."
        }

        private fun insertIntoEditor(project: Project, editor: Editor, source: String, rootName: String) {
            val lines = source.lines()
            val imports = lines.filter { it.startsWith("import ") }.map { it.removePrefix("import ").removeSuffix(";") }
            val rawBody = lines.filterNot { it.startsWith("import ") }.joinToString("\n").trimStart('\n')
            WriteCommandAction.runWriteCommandAction(project, "Paste JSON as Java Record", null, {
                val doc = editor.document
                val sel = editor.selectionModel
                val offset = if (sel.hasSelection()) sel.selectionStart else editor.caretModel.offset
                val psiMgr = PsiDocumentManager.getInstance(project)
                psiMgr.commitDocument(doc)
                val jf = psiMgr.getPsiFile(doc) as? PsiJavaFile
                var body = rawBody
                if (jf == null) {
                    body = source // not a Java file: keep generated text as-is, imports included
                } else {
                    val insideClass = PsiTreeUtil.getParentOfType(jf.findElementAt(offset), PsiClass::class.java) != null
                    // a public top-level type must be named after the file
                    if (!insideClass && jf.name.removeSuffix(".java") != rootName) body = body.replaceFirst("public record ", "record ")
                }
                if (sel.hasSelection()) doc.deleteString(sel.selectionStart, sel.selectionEnd)
                doc.insertString(offset, body)
                if (jf != null) {
                    psiMgr.commitDocument(doc)
                    CodeStyleManager.getInstance(project).reformatText(jf, offset, offset + body.length)
                    psiMgr.commitDocument(doc)
                    val existing = jf.importList?.importStatements?.mapNotNull { it.qualifiedName }?.toSet().orEmpty()
                    val missing = imports.filter { it !in existing }
                    if (missing.isNotEmpty()) {
                        val il = jf.importList
                        val ins = missing.joinToString("") { "import $it;\n" }
                        val pkgEnd = jf.packageStatement?.textRange?.endOffset ?: 0
                        if (il != null && il.textLength > 0) doc.insertString(il.textRange.startOffset, ins)
                        else if (pkgEnd > 0) doc.insertString(pkgEnd, "\n\n" + ins)
                        else doc.insertString(0, ins + "\n")
                    }
                    psiMgr.commitDocument(doc)
                }
            })
        }
    }

    private class InputDialog(initial: String) : DialogWrapper(true) {
        private val name = JBTextField("Root")
        private val styleBox = ComboBox(AnnotationStyle.entries.toTypedArray())
        private val area = JBTextArea(initial, 16, 60)
        val json: String get() = area.text
        val className: String get() = name.text.ifBlank { "Root" }
        val style: AnnotationStyle get() = styleBox.selectedItem as AnnotationStyle

        init {
            title = "Paste JSON as Java Record"
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent("Root class name:", name)
            .addLabeledComponent("Annotations:", styleBox)
            .addLabeledComponentFillVertically("JSON:", JBScrollPane(area))
            .panel

        override fun doValidate(): ValidationInfo? =
            if (area.text.isBlank()) ValidationInfo("Paste some JSON", area) else null
    }
}
