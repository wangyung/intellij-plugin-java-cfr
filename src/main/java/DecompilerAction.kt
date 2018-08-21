
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.sun.javafx.scene.CameraHelper.project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.projectRoots.Sdk



class DecompilerAction : AnAction() {

    private val sourceRegex = Regex("\\.(kt|java)\$", RegexOption.IGNORE_CASE)

    override fun actionPerformed(actionEvent: AnActionEvent) {
        val currentDoc = actionEvent.getData(LangDataKeys.EDITOR)?.document
        val project = actionEvent.project
        if (currentDoc == null || project == null) {
            return
        }

        val targetClassFilePath = getClassFilePath(currentDoc, project)
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
    }

    override fun update(actionEvent: AnActionEvent) {
        super.update(actionEvent)
        val currentDoc = actionEvent.getData(LangDataKeys.EDITOR)?.document
        val project = actionEvent.project
        actionEvent.presentation.icon = AllIcons.Ide.Link
        actionEvent.presentation.isVisible =
                project != null
                && currentDoc != null
                && getClassFilePath(currentDoc, project).isNotEmpty()
    }

    private fun getClassFilePath(currentDoc: Document, project: Project): String {
        val currentSrc = FileDocumentManager.getInstance().getFile(currentDoc)
        var targetFiles = emptyArray<PsiFile>()
        currentSrc?.let {
            val currentClassFileName = sourceRegex.replace(it.name, EXTENTION_NAME_CLASS)
            if (!currentClassFileName.endsWith(EXTENTION_NAME_CLASS)) {
                return@let
            }
            targetFiles = FilenameIndex.getFilesByName(project,
                    currentClassFileName, GlobalSearchScope.allScope(project))
            targetFiles.forEach {
                println("target class files: ${it.virtualFile.path}")
            }
        }
        return if (targetFiles.isNotEmpty()) {
            targetFiles[0].name
        } else ""
    }

    companion object {
        private const val EXTENTION_NAME_CLASS = ".class"
    }
}
