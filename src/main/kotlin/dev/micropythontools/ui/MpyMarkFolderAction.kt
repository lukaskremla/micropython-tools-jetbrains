package dev.micropythontools.ui

import com.intellij.ide.projectView.actions.MarkRootActionBase
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.vfs.VirtualFile


class MpyMarkFolderAction : MarkRootActionBase {
    override fun modifyRoots(file: VirtualFile?, entry: ContentEntry?) {
        TODO("Not yet implemented")
        entry.AddSource
    }

    override fun isEnabled(selection: RootsSelection, module: Module): Boolean {
        if (!selection.mySelectedDirectories.isEmpty()) {
            return true
        }

        for (root in selection.mySelectedRoots) {
            if (!myRootType.equals(root.getRootType())) {
                return true
            }
        }
    }
}