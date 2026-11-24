// A Gradle build alongside build.sh, for the things Gradle does better: a
// consumer's Gradle metadata (.module, which is what makes the dependency resolve
// without an explicit @aar), JitPack, and an IDE import. build.sh stays the build
// that runs on the phone without a download; this one is for a machine with time
// and network, and it builds the same sources and the same manifest.
import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Every artifact comes from the repositories here. A project-level
    // repositories {} block would split resolution across two places and is
    // refused instead of silently adding another source.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "screenstream"

// The control surface is a standalone AAR: a provider base, its ordered
// broadcast twin, the reply/uid/token rules and the `content call` parser. The
// root library depends on it, and wireless-adb-keeper can too without pulling
// in the screen capture.
include(":android-control-surface")
