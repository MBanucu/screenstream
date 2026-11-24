// The library half of the control surface: a ContentProvider base, its ordered
// broadcast twin, the reply/uid/token rules and the `content call` wire parser.
// Versions and SDK levels come from gradle/libs.versions.toml, like the root
// project, so a release has one number for every artifact.
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.mbanucu.control"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }
}

group = "io.github.mbanucu"
version = providers.gradleProperty("version").getOrElse(libs.versions.projectVersion.get())

dependencies {
    // The tests are deliberately device-free: the gate, the reply text, the
    // token and the parser are plain java.*, so they run on the build machine's
    // JVM with no emulator and no Robolectric.
    testImplementation(libs.junit)
}

publishing {
    // The same file repository the root project uses, so publish.sh can sign
    // and bundle both artifacts without Gradle's publication being special.
    repositories {
        maven {
            name = "outRepo"
            url = uri(layout.buildDirectory.dir("m2repo"))
        }
    }

    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("android-control-surface")
                description.set(
                    "A uid-gated, token-capable ContentProvider control surface with a frozen key=value reply format."
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
