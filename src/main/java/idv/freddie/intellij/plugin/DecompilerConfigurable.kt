package idv.freddie.intellij.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class DecompilerConfigurable : Configurable {

    private val configuration: DecompilerConfiguration = DecompilerConfiguration()

    override fun isModified(): Boolean = configuration.isSettingChanged

    override fun getDisplayName(): String = "Java CFR Decompiler"

    override fun apply() {
        configuration.save()
    }

    override fun createComponent(): JComponent? {
        configuration.decompilerPath = PropertiesComponent.getInstance()
                .getValue(KEY_DECOMPILER_PATH, "")
        return configuration.`$$$getRootComponent$$$`()
    }

    companion object {
        const val KEY_DECOMPILER_PATH = "idv.freddie.decompiler.path"
    }
}
