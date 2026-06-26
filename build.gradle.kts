import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

group   = "com.github.nickkemp.javafilerouter"
version = "1.6.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2026.1")
        testFramework(TestFrameworkType.Platform)
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
        changeNotes = provider {
            changelog.renderItem(
                changelog.getLatest(),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
        }
    }

    pluginVerification {
        ides {
            local(file("C:/Program Files/JetBrains/IntelliJ IDEA 2024.2.3"))
        }
    }

    publishing {
        token = providers.gradleProperty("publish.token")
            .orElse(providers.environmentVariable("PUBLISH_TOKEN"))
    }
}

changelog {
    version = project.version.toString()
    path    = file("CHANGELOG.md").canonicalPath
}

kotlin {
    jvmToolchain(21)
}
