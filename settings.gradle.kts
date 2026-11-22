// A Gradle build alongside build.sh, for the things Gradle does better: a
// consumer's Gradle metadata (.module, which is what makes the dependency resolve
// without an explicit @aar), JitPack, and an IDE import. build.sh stays the build
// that runs on the phone without a download; this one is for a machine with time
// and network, and it builds the same sources and the same manifest.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "screenstream"
