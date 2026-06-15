import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

group   = "com.github.nickkemp.javafilerouter"
version = "1.0.2"

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
            sinceBuild = "241.0"
            untilBuild = provider { null }
        }
        changeNotes = provider {
            changelog.renderItem(
                changelog.getLatest(),
                org.jetbrains.changelog.Changelog.OutputType.HTML
            )
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
