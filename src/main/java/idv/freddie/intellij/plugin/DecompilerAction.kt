package idv.freddie.intellij.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import java.io.File
import java.io.IOException
import java.nio.charset.Charset

class DecompilerAction : AnAction() {

    private val sourceRegex = Regex("\\.(kt|java)\$", RegexOption.IGNORE_CASE)

    private var possibleClassRoots: List<File> = EMPTY_FILE_LIST

    private val windowManager: WindowManager = WindowManager.getInstance()

    private val logger: Logger = Logger.getInstance("CFRDecompiler")

    override fun actionPerformed(actionEvent: AnActionEvent) {
        val editor = actionEvent.getData(LangDataKeys.EDITOR)
        val currentDoc = editor?.document
        val project = actionEvent.dataContext.getData(DataKeys.PROJECT.name) as? Project
        if (currentDoc == null || project == null) {
            logger.error(Messages.NO_DOCUMENT_OR_PROJECT)
            return
        }

        val targetClassFile = getVirtualClassFile(currentDoc, project)
        val javaExePath = getJavaExePath(project)

        val basePath = project.basePath
        if (basePath == null || javaExePath.isNullOrEmpty()) {
            outputError(Messages.INCORRECT_JAVA_PATH, project)
            return
        }

        val classFilePath = targetClassFile?.path ?: ""
        if (classFilePath.isEmpty()) {
            outputError(Messages.NO_CLASS_FILE, project)
            return
        }

        createCommandLine(basePath, javaExePath!!, classFilePath)?.let {
            val output = ExecUtil.execAndGetOutput(it)
            if (output.exitCode == 0) {
                makeSureDecompileRootPath(basePath)
                val outputFilePath = "${getDecompilePath(basePath)}/${createDecompiledFileName(targetClassFile?.name ?: "")}"
                writeDecompileResult(outputFilePath, output, project)
            } else {
                outputError(output.stderr, project)
            }
        } ?: run { outputError(Messages.NO_CLASS_FILE, project) }
    }

    private fun outputError(message: String, project: Project) {
        val statusBar = windowManager.getStatusBar(project)
        Notifications.Bus.notify(Notification("CFRCompiler", "error", message, NotificationType.ERROR))
        logger.warn(message)
        statusBar.info = Messages.ERROR
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

    private fun writeDecompileResult(outputFilePath: String, output: ProcessOutput, project: Project) {
        WriteAction.run<IOException> {
            val tempFile = File(outputFilePath).also {
                it.writeText(output.stdout, Charsets.UTF_8)
            }

            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tempFile)?.let {
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(it, true)
                }
            } ?: run { outputError(Messages.CANT_LAUNCH_FILE_EDITOR, project) }
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
                            .filter { file -> file.isDirectory && (file.name == "build" || file.name == "out") }
                            .toList()
                } ?: EMPTY_FILE_LIST
            }

            possibleClassRoots.forEach { file ->
                file.walkTopDown().find { classFile ->
                    compareClassFileName(classFile.name, currentClassFileName)
                }?.run {
                    val targetFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(this)
                    if (targetFile == null) {
                        possibleClassRoots = EMPTY_FILE_LIST
                    }
                    return targetFile
                }
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
        private const val EXTENTION_NAME_CLASS = ".class"
        private const val EXTENTION_NAME_JAVA = ".java"
        private val EMPTY_FILE_LIST = emptyList<File>()
    }
}
