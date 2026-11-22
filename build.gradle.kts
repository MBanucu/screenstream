// The Gradle view of this library. There are no resources and no dependencies:
// everything in it is android.jar and java.*. The manifest lives at the repository
// root (build.sh copies it into the AAR), so it is pointed at rather than moved.
plugins {
    // 9.x is the line that runs on Gradle 9.6+: AGP 8.x uses a Gradle internal API
    // that was removed there, and Termux packages Gradle 9.9.2.
    id("com.android.library") version "9.5.2"
    `maven-publish`
}

android {
    namespace = "io.github.mbanucu.screenstream"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    sourceSets["main"].manifest.srcFile("AndroidManifest.xml")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

group = "io.github.mbanucu"
version = providers.gradleProperty("version").getOrElse("0.1.0")

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("screenstream")
                description.set(
                    "The phone's screen as raw RGBA frames on a local port, for apps and for tests."
                )
                url.set("https://github.com/MBanucu/screenstream")
                licenses {
                    license {
                        name.set("GNU General Public License v3.0 or later")
                        url.set("https://www.gnu.org/licenses/gpl-3.0-standalone.html")
                    }
                }
                developers {
                    developer {
                        id.set("MBanucu")
                        name.set("MBanucu")
                        url.set("https://github.com/MBanucu")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/MBanucu/screenstream.git")
                    developerConnection.set("scm:git:ssh://git@github.com/MBanucu/screenstream.git")
                    url.set("https://github.com/MBanucu/screenstream")
                }
            }
        }
    }
}
