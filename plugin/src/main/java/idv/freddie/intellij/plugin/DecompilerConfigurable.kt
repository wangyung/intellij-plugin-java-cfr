package idv.freddie.intellij.plugin

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class DecompilerConfigurable : Configurable {

    private val configurationModel: DecompilerConfigurableModel = DecompilerConfigurableModel()

    override fun isModified(): Boolean = configurationModel.settingChanged

    override fun getDisplayName(): String = "Java CFR Decompiler"

    override fun apply() {
        configurationModel.save()
    }

    override fun createComponent(): JComponent? {
        configurationModel.decompilerPath = PropertiesComponent.getInstance()
                .getValue(KEY_DECOMPILER_PATH, "")
        return configurationModel.rootView
    }

    companion object {
        const val KEY_DECOMPILER_PATH = "idv.freddie.decompiler.path"
    }
}
