package dev.xueria.loom

import org.gradle.api.provider.Property

abstract class LoomExtendExtension {

    abstract val url: Property<String>

}