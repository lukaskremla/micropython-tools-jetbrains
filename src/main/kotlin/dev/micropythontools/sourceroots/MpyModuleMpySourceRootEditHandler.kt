package dev.micropythontools.sourceroots

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.roots.ui.configuration.ModuleSourceRootEditHandler
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import org.jetbrains.annotations.Nls
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import java.awt.Color
import javax.swing.Icon

class MpyModuleMpySourceRootEditHandler : ModuleSourceRootEditHandler<JavaSourceRootProperties>(MpySourceRootType.SOURCE) {
    override fun getRootTypeName(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "MicroPython Sources"
    }

    override fun getRootIcon(): Icon {
        return IconLoader.getIcon("/icons/MpySource.svg", MpyModuleMpySourceRootEditHandler::class.java)
    }

    override fun getFolderUnderRootIcon(): Icon? {
        return rootIcon
    }

    override fun getMarkRootShortcutSet(): CustomShortcutSet? {
        return null
    }

    override fun getRootsGroupTitle(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "MicroPython Sources"
    }

    override fun getRootsGroupColor(): Color {
        return JBColor(
            Color(75, 188, 236),  // Light theme
            Color(50, 185, 236)   // Dark theme
        )
    }

    override fun getUnmarkRootButtonText(): @Nls(capitalization = Nls.Capitalization.Title) String {
        return "Unmark as MicroPython Sources"
    }
}