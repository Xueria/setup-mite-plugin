
val pluginReadableName = providers.gradleProperty("pluginReadableName").get()
val pluginDescription = providers.gradleProperty("pluginDescription").get()
val pluginGroup = providers.gradleProperty("pluginGroup").get()
val pluginName = providers.gradleProperty("pluginName").get()
val pluginVersion = providers.gradleProperty("pluginVersion").get()

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.vanniktech.maven.publish)
    signing
}

group = pluginGroup
version = pluginVersion
description = pluginDescription

repositories {
    mavenCentral()
}

dependencies {
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

gradlePlugin {
    plugins {
        create(pluginName) {
            id = pluginName
            implementationClass = "dev.xueria.loom.LoomExtendPlugin"
        }
    }
}

signing {
    useGpgCmd()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(groupId = pluginGroup, artifactId = pluginName, version = pluginVersion)

    pom {
        name = pluginReadableName
        description = pluginDescription
        url = "https://github.com/Xueria/setup-mite-plugin"

        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }

        developers {
            developer {
                name = "Xueria"
            }
        }

        scm {
            url = "https://github.com/Xueria/setup-mite-plugin"
        }
    }
}