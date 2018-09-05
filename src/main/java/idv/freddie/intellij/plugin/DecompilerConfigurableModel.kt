package idv.freddie.intellij.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import javax.swing.JComponent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JLabel


class DecompilerConfigurableModel {

    var decompilerPath: String
        set(value) {
            configurationView.textField.text = value
            configurationView.warningMessage.isVisible = !verifyDecompilerFilePath(value)
        }
        get() = configurationView.textField.text

    var settingChanged: Boolean = false
        private set

    private val configurationView: DecompilerConfigurationView = DecompilerConfigurationView()

    private val textField: TextFieldWithBrowseButton = configurationView.textField
    private val warningMessage: JLabel = configurationView.warningMessage

    val rootView: JComponent = configurationView.root


    init {
        val descriptor = FileChooserDescriptor(
                true, false, true,
                true, true, false
        )

        configurationView.textField.textField.document.addDocumentListener(object : DocumentListener {
            override fun changedUpdate(e: DocumentEvent?) {
                warningMessage.isVisible = !verifyDecompilerFilePath(textField.text)
            }

            override fun insertUpdate(e: DocumentEvent?) {
                warningMessage.isVisible = !verifyDecompilerFilePath(textField.text)
            }

            override fun removeUpdate(e: DocumentEvent?) {
                warningMessage.isVisible = !verifyDecompilerFilePath(textField.text)
            }
        })

        configurationView.textField.addBrowseFolderListener(object : TextBrowseFolderListener(descriptor) {
            override fun onFileChosen(chosenFile: VirtualFile) {
                super.onFileChosen(chosenFile)
                settingChanged = true
            }
        })
    }

    fun save() {
        PropertiesComponent.getInstance()
                .setValue(DecompilerConfigurable.KEY_DECOMPILER_PATH, configurationView.textField.text)
        settingChanged = false
    }

    private fun verifyDecompilerFilePath(path: String): Boolean {
        if (path.isEmpty()) {
            return false
        }
        return path.toLowerCase().endsWith(".jar")
    }
}
