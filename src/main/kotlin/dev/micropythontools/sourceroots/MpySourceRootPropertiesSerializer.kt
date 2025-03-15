package dev.micropythontools.sourceroots

import org.jdom.Element
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.serialization.module.JpsModuleSourceRootPropertiesSerializer

class MpySourceRootPropertiesSerializer :
    JpsModuleSourceRootPropertiesSerializer<JavaSourceRootProperties>(
        MpySourceRootType.SOURCE, "micropython-source"
    ) {

    override fun loadProperties(sourceRootTag: Element): JavaSourceRootProperties {
        return MpySourceRootType.SOURCE.createDefaultProperties()
    }

    override fun saveProperties(properties: JavaSourceRootProperties, sourceRootTag: Element) {
        // No additional properties to save
    }
}