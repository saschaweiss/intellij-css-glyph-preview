import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            providers.gradleProperty("platformVersion").get(),
        )

        // CSS PSI (CssDeclaration, CssRuleset, ...) lives here.
        bundledPlugin("com.intellij.css")
        // SCSS files reuse the CSS PSI but need the Sass plugin loaded too.
        bundledPlugin("org.jetbrains.plugins.sass")

        pluginVerifier()
    }
}

intellijPlatform {
    // The instrumentCode task fails on some JDKs (looks for a non-existent
    // <jdk>/Contents/Home/Packages). We don't use .form files or @NotNull
    // bytecode instrumentation, so turn it off.
    instrumentCode = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No upper bound: the plugin won't be blocked by future IDE updates.
            untilBuild = provider { null }
        }
    }

    // `./gradlew verifyPlugin` runs JetBrains' Plugin Verifier against a set of
    // IDEs matching the plugin's compatibility range — the same check the
    // Marketplace runs during moderation.
    pluginVerification {
        ides {
            recommended()
        }
    }
}

kotlin {
    jvmToolchain(21)
}
