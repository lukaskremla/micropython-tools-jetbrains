package dev.micropythontools.sourceroots

import org.jetbrains.jps.model.serialization.JpsModelSerializerExtension
import org.jetbrains.jps.model.serialization.module.JpsModuleSourceRootPropertiesSerializer

class MpyJpsModelSerializerExtension : JpsModelSerializerExtension() {
    override fun getModuleSourceRootPropertiesSerializers(): List<JpsModuleSourceRootPropertiesSerializer<*>> {
        return listOf(MpySourceRootPropertiesSerializer())
    }
}