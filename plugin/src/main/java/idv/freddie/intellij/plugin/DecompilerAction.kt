package idv.freddie.intellij.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import idv.freddie.intellij.plugin.configuration.DecompilerConfigurable
import org.benf.cfr.reader.api.CfrDriver
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.Charset

class DecompilerAction : AnAction() {

    private val sourceRegex = Regex("\\.(kt|java)\$", RegexOption.IGNORE_CASE)

    private var possibleClassRoots: List<File> = EMPTY_FILE_LIST

    private var currentProjectName: String = ""

    private val logger: Logger = Logger.getInstance("CFRDecompiler")

    override fun actionPerformed(actionEvent: AnActionEvent) {
        val editor = actionEvent.getData(LangDataKeys.EDITOR)
        val currentDoc = editor?.document
        val project = actionEvent.dataContext.getData(DataKeys.PROJECT.name) as? Project
        if (currentDoc == null || project == null) {
            logger.error(Messages.NO_DOCUMENT_OR_PROJECT)
            return
        }

        var targetClassFile = getVirtualClassFile(currentDoc, project)
        if (targetClassFile == null) {
            val selectedText = editor.selectionModel.selectedText ?: ""
            targetClassFile = getVirtualClassFile(selectedText, project)
        }

        val javaExePath = getJavaExePath(project)

        val basePath = project.basePath
        if (basePath == null || javaExePath.isNullOrEmpty()) {
            outputError(Messages.INCORRECT_JAVA_PATH)
            return
        }

        val classFilePath = targetClassFile?.path
        if (classFilePath.isNullOrEmpty()) {
            outputError(Messages.NO_CLASS_FILE)
            return
        }

        val decompileOutputDirectory = getDecompilePath(basePath)
        val decompileFileName = createDecompiledFileName(targetClassFile?.name ?: "")
        val outputFilePath = "$decompileOutputDirectory/$decompileFileName"

        makeSureDirectoryExist(decompileOutputDirectory)

        decompileClassFile(project, classFilePath, outputFilePath)
    }

    private fun decompileClassFile(project: Project, classFilePath: String, outputFilePath: String) {
        // TODO: Remove the stdout redirection after options is supported.
        val prevPrintStream = System.out
        val outputFile = File(outputFilePath)
        val fileOutputStream = FileOutputStream(outputFile)
        System.setOut(PrintStream(fileOutputStream))
        // TODO: Support options in the future
        val cfrDriver: CfrDriver = CfrDriver.Builder().build()
        cfrDriver.analyse(listOf(classFilePath))
        System.setOut(prevPrintStream)

        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(outputFile)?.let {
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(it, true)
            }
        } ?: run { outputError(Messages.CANT_LAUNCH_FILE_EDITOR) }
    }

    private fun outputError(message: String) {
        Notifications.Bus.notify(
            Notification(
                "CFRDecompiler",
                "warning",
                "[Decompiler] $message",
                NotificationType.WARNING
            )
        )
        logger.warn(message)
    }

    private fun getJavaExePath(project: Project): String? {
        var javaExePath: String? = null
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk
        if (projectSdk is ProjectJdkImpl) {
            javaExePath = projectSdk.javaExecPath
        } else {
            if (isMac()) {
                javaExePath = System.getenv("JAVA_HOME")?.let { javaHome ->
                    "$javaHome/bin/java"
                }
            }
        }
        return javaExePath
    }

    override fun update(actionEvent: AnActionEvent) {
        super.update(actionEvent)
        val editor = actionEvent.getData(LangDataKeys.EDITOR)
        val currentDoc = editor?.document
        val project = actionEvent.project

        actionEvent.presentation.isVisible =
            project != null && currentDoc != null

        actionEvent.presentation.isEnabled = isShowAction(project, editor, currentDoc)
    }

    // Don't need to use this method to determine isEnabledAndVisible
    private fun isShowAction(project: Project?, editor: Editor?, currentDoc: Document?): Boolean {
        if (project == null || editor == null || currentDoc == null) {
            return false
        }

        return getVirtualClassFile(currentDoc, project) != null
                || (!editor.selectionModel.selectedText.isNullOrEmpty()
                && getVirtualClassFile(editor.selectionModel.selectedText!!, project) != null)
    }

    private fun getDecompilePath(basePath: String): String = "$basePath/build/decompile"

    private fun makeSureDirectoryExist(targetPath: String) {
        val targetFile = File(targetPath)
        if (targetFile.isFile && targetFile.exists()) {
            targetFile.delete()
        }

        if (!targetFile.exists()) {
            targetFile.mkdirs()
        }
    }

    private fun createDecompiledFileName(originalName: String): String =
        if (originalName.endsWith(EXTENSION_NAME_CLASS)) {
            originalName.replace(EXTENSION_NAME_CLASS, EXTENSION_NAME_JAVA)
        } else originalName

    // Standalone decompiler jar is deprecated.
    private fun createCommandLine(basePath: String, javaExePath: String, targetPath: String): GeneralCommandLine? {
        val decompilerPath = PropertiesComponent.getInstance()
            .getValue(DecompilerConfigurable.KEY_DECOMPILER_PATH)
        return if (decompilerPath == null) {
            null
        } else GeneralCommandLine(listOf(javaExePath, "-jar", decompilerPath, targetPath)).apply {
            charset = Charset.forName("UTF-8")
            workDirectory = File(basePath)
        }
    }

    private fun writeDecompileResult(outputFilePath: String, output: ProcessOutput, project: Project) {
        WriteAction.run<IOException> {
            val tempFile = File(outputFilePath).also {
                it.writeText(output.stdout, Charsets.UTF_8)
            }

            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)?.let {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
            } ?: run { outputError(Messages.CANT_LAUNCH_FILE_EDITOR) }
        }
    }

    private val ProjectJdkImpl.javaExecPath: String?
        get() = if (homePath?.isNotEmpty() == true) {
            "$homePath/bin/java"
        } else null

    private fun getVirtualClassFile(currentDoc: Document, project: Project): VirtualFile? {
        val currentSrc = FileDocumentManager.getInstance().getFile(currentDoc)
        currentSrc?.let { srcFile ->
            val currentClassFileName = sourceRegex.replace(srcFile.name, EXTENSION_NAME_CLASS)
            if (!currentClassFileName.endsWith(EXTENSION_NAME_CLASS)) {
                return@let
            }

            return findVirtualFile(currentClassFileName, project)
        }

        return null
    }

    private fun getVirtualClassFile(selectedText: String, project: Project): VirtualFile? =
        findVirtualFile(selectedText + EXTENSION_NAME_CLASS, project)

    private fun findVirtualFile(classFileName: String, project: Project): VirtualFile? {
        if (project.name != currentProjectName) {
            possibleClassRoots = EMPTY_FILE_LIST
            currentProjectName = project.name
        }

        if (possibleClassRoots.isEmpty()) {
            possibleClassRoots = project.basePath?.let { basePath ->
                File(basePath).walkTopDown()
                    .filter { file -> file.isDirectory && (file.name == "build" || file.name == "out") }
                    .toList()
            } ?: EMPTY_FILE_LIST
        }

        possibleClassRoots.forEach { file ->
            file.walkTopDown().find { classFile ->
                compareClassFileName(classFile.name, classFileName)
            }?.run {
                val targetFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(this)
                if (targetFile == null) {
                    possibleClassRoots = EMPTY_FILE_LIST
                }
                return targetFile
            }
        }

        return null
    }

    private fun compareClassFileName(srcFilename: String, expectedClassFilename: String): Boolean {
        //compare Filename.class and FilenameKt.class
        val srcNames = srcFilename.split(".")
        val expectedNames = expectedClassFilename.split(".")
        if (srcNames.size != 2 || srcNames.size != 2) {
            return false
        }
        if (srcNames[1] == "class") {
            return (srcNames[0] == expectedNames[0] || srcNames[0] == expectedNames[0] + "Kt")
        }
        return false
    }

    companion object {
        private const val EXTENSION_NAME_CLASS = ".class"
        private const val EXTENSION_NAME_JAVA = ".java"
        private val EMPTY_FILE_LIST = emptyList<File>()
    }
}
