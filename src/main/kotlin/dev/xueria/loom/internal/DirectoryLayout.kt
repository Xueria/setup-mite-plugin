package dev.xueria.loom.internal

import java.nio.file.Path

/**
 * Location of the artifact cache, kept identical to the layout fml-loom itself uses so a jar
 * placed here is picked up by loom instead of being downloaded again.
 */
internal object DirectoryLayout {

    private const val CACHES = "caches"
    private const val CACHE_NAME = "fml-loom"

    fun directory(gradleUserHome: Path, version: String): Path =
        gradleUserHome
            .resolve(CACHES)
            .resolve(CACHE_NAME)
            .resolve(version)

    fun fileName(version: String): String = "$version.jar"

}
