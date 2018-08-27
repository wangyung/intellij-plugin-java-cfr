package idv.freddie.intellij.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.io.IOException
import java.nio.charset.Charset


class DecompilerAction : AnAction() {

    private val sourceRegex = Regex("\\.(kt|java)\$", RegexOption.IGNORE_CASE)

    private var possibleClassRoots: List<File> = EMPTY_FILE_LIST

    override fun actionPerformed(actionEvent: AnActionEvent) {
        val editor = actionEvent.getData(LangDataKeys.EDITOR)
        val currentDoc = editor?.document
        val project = actionEvent.dataContext.getData(DataKeys.PROJECT.name) as? Project
        if (currentDoc == null || project == null) {
            return
        }

        val targetClassFile = getVirtualClassFile(currentDoc, project)
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk

        if (projectSdk is ProjectJdkImpl) {
            val basePath = project.basePath
            val execPath = projectSdk.javaExecPath
            if (basePath == null || execPath == null) {
                println("The exec path is incorrect.")
                return
            }

            val classFilePath = targetClassFile?.path ?: ""
            if (classFilePath.isEmpty()) {
                println("No target class file")
                return
            }

            val output = ExecUtil.execAndGetOutput(createCommandLine(basePath, execPath, classFilePath))
            if (output.exitCode == 0) {
                makeSureDecompileRootPath(basePath)
                val outputFilePath = "${getDecompilePath(basePath)}/${createDecompiledFileName(targetClassFile?.name ?: "")}"
                writeDecompileResult(outputFilePath, output, project)
            }
        }
    }

    private fun writeDecompileResult(outputFilePath: String, output: ProcessOutput, project: Project) {
        WriteAction.run<IOException> {
            val tempFile = File(outputFilePath).also {
                it.writeText(output.stdout, Charsets.UTF_8)
            }

            LocalFileSystem.getInstance().findFileByIoFile(tempFile)?.let {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
            }
        }
    }

    override fun update(actionEvent: AnActionEvent) {
        super.update(actionEvent)
        val currentDoc = actionEvent.getData(LangDataKeys.EDITOR)?.document
        val project = actionEvent.project

        actionEvent.presentation.isEnabledAndVisible =
                project != null
                && currentDoc != null
                && getVirtualClassFile(currentDoc, project) != null
    }

    private fun getDecompilePath(basePath: String): String = "$basePath/build/decompile"

    private fun makeSureDecompileRootPath(basePath: String) {
        val rootPath = File(getDecompilePath(basePath))
        if (rootPath.isFile && rootPath.exists()) {
            rootPath.delete()
        }

        if (!rootPath.exists()) {
            rootPath.mkdirs()
        }
    }

    private fun createDecompiledFileName(originalName: String): String =
        if (originalName.endsWith(EXTENTION_NAME_CLASS)) {
            originalName.replace(EXTENTION_NAME_CLASS, EXTENTION_NAME_JAVA)
        } else originalName

    private fun createCommandLine(basePath: String, exePath: String, targetPath: String): GeneralCommandLine {
        val decompilerPath = PropertiesComponent.getInstance()
                .getValue(DecompilerConfigurable.KEY_DECOMPILER_PATH)
        return GeneralCommandLine(listOf(exePath, "-jar", decompilerPath, targetPath)).apply {
            charset = Charset.forName("UTF-8")
            workDirectory = File(basePath)
        }
    }

    private val ProjectJdkImpl.javaExecPath: String?
            get() = if (homePath?.isNotEmpty() == true) { "$homePath/bin/java" } else null

    private fun getVirtualClassFile(currentDoc: Document, project: Project): VirtualFile? {
        val currentSrc = FileDocumentManager.getInstance().getFile(currentDoc)
        currentSrc?.let {
            val currentClassFileName = sourceRegex.replace(it.name, EXTENTION_NAME_CLASS)
            if (!currentClassFileName.endsWith(EXTENTION_NAME_CLASS)) {
                return@let
            }

            if (possibleClassRoots.isEmpty()) {
                possibleClassRoots = project.basePath?.let {
                    File(it).walkTopDown()
                            .filter { it.isDirectory && (it.name == "build" || it.name == "out") }
                            .toList()
                } ?: EMPTY_FILE_LIST
            }

            possibleClassRoots.forEach {
                it.walkTopDown().find { it.name == currentClassFileName }?.run {
                    val targetFile = LocalFileSystem.getInstance().findFileByIoFile(this)
                    if (targetFile == null) {
                        possibleClassRoots = EMPTY_FILE_LIST
                    }
                    return targetFile
                }
            }
        }

        return null
    }

    companion object {
        private const val EXTENTION_NAME_CLASS = ".class"
        private const val EXTENTION_NAME_JAVA = ".java"
        private val EMPTY_FILE_LIST = emptyList<File>()
    }
}
