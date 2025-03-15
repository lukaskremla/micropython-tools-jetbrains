package dev.micropythontools.sourceroots

import org.jetbrains.jps.model.ex.JpsElementTypeBase
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

class MpySourceRootType private constructor() : JpsElementTypeBase<JavaSourceRootProperties?>(), JpsModuleSourceRootType<JavaSourceRootProperties?> {
    override fun createDefaultProperties(): JavaSourceRootProperties {
        return JpsJavaExtensionService.getInstance().createSourceRootProperties("")
    }

    override fun isForTests(): Boolean {
        return false
    }

    companion object {
        val SOURCE: MpySourceRootType = MpySourceRootType()
    }
}