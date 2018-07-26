
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.jetbrains.annotations.NotNull

class DecompilerAction : AnAction() {
    override fun actionPerformed(@NotNull e: AnActionEvent) {
    }

    override fun update(e: AnActionEvent?) {
        super.update(e)
        e?.presentation?.icon = AllIcons.Ide.Link
    }
}
