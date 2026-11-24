// The Gradle view of this library. There are no resources and no runtime
// dependencies: everything in it is android.jar and java.*. Versions and SDK
// levels come from gradle/libs.versions.toml, which build.sh and publish.sh
// read too. The canonical manifest is at the repository root because build.sh
// copies it into the AAR; Gradle uses the staged copy in gradle/ that differs
// only by the package attribute AGP 9 no longer accepts.
import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.work.DisableCachingByDefault

plugins {
    // AGP 9.x is the line that runs on Gradle 9.6+: AGP 8.x uses a Gradle
    // internal API that was removed there, and Termux packages Gradle 9.9.2.
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.mbanucu.screenstream"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // AGP 9 takes the package from namespace and ignores - then warns about - the
    // package attribute a source manifest may still carry. build.sh needs that
    // attribute in the canonical root manifest, so Gradle reads an otherwise
    // identical copy. checkManifests keeps the two honest.
    sourceSets["main"].manifest.srcFile("gradle/AndroidManifest.xml")

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

    // The library is the AAR and nothing else; javadoc over android.* types would
    // otherwise fail the build on doclint warnings about them.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    }
}

group = "io.github.mbanucu"
version = providers.gradleProperty("version").getOrElse(libs.versions.projectVersion.get())

@DisableCachingByDefault(because = "Reads the machine's build environment")
abstract class CheckBuildEnv : DefaultTask() {
    @get:Input
    abstract val compileSdk: Property<Int>

    @get:Input
    abstract val javaSpecificationVersion: Property<String>

    @get:Input
    abstract val osArch: Property<String>

    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    @get:Optional
    abstract val termuxPrefix: Property<String>

    @get:Input
    @get:Optional
    abstract val sdkFromEnvironment: Property<String>

    @get:Input
    @get:Optional
    abstract val aapt2Override: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localProperties: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        val jdk = javaSpecificationVersion.get().removePrefix("1.").substringBefore('.').toIntOrNull() ?: 0
        if (jdk < 17) {
            problems += "JDK $jdk is too old; use JDK 17 or newer (Termux: pkg install openjdk-21)"
        }

        val localPropertiesFile = localProperties.files.firstOrNull()?.takeIf { it.isFile }
        val localSdk = if (localPropertiesFile != null) {
            Properties().apply {
                localPropertiesFile.inputStream().use { load(it) }
            }.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val sdk = sdkFromEnvironment.orNull?.takeIf { it.isNotBlank() } ?: localSdk
        val platform = compileSdk.get()
        if (sdk == null) {
            problems += "no Android SDK: export ANDROID_HOME=/path/to/android-sdk, or copy " +
                "local.properties.example to local.properties and set sdk.dir"
        } else if (!File(sdk).isDirectory) {
            problems += "the Android SDK at $sdk does not exist; set ANDROID_HOME or " +
                "local.properties sdk.dir to a real SDK directory"
        } else {
            val androidJar = File(sdk, "platforms/android-$platform/android.jar")
            if (!androidJar.isFile) {
                problems += "Android platform $platform is not installed in $sdk; run " +
                    "sdkmanager \"platforms;android-$platform\""
            }
        }

        val aapt2 = aapt2Override.orNull?.takeIf { it.isNotBlank() }
        if (aapt2 == null) {
            // Termux's aapt2 is a bionic binary; AGP's arm64/x86_64 downloads are
            // glibc builds and do not run there. Other arm64 hosts (macOS, glibc
            // Linux) can use AGP's own aapt2.
            val onTermux = termuxPrefix.orNull?.contains("com.termux") == true ||
                javaHome.get().contains("com.termux")
            val arm64 = osArch.get().lowercase().let { it == "aarch64" || it == "arm64" }
            if (onTermux && arm64) {
                problems += "no aapt2 override on Termux; add this to " +
                    "~/.gradle/gradle.properties (Termux's aapt2 is bionic, AGP's is glibc): " +
                    "android.aapt2FromMavenOverride=\$PREFIX/bin/aapt2"
            }
        } else {
            val binary = File(aapt2)
            if (!binary.isFile) {
                problems += "android.aapt2FromMavenOverride points at $aapt2, which is not a file; " +
                    "use \$PREFIX/bin/aapt2 on Termux"
            } else if (!binary.canExecute()) {
                problems += "the aapt2 override $aapt2 is not executable; run chmod +x $aapt2"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Android build environment is not ready:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nSee docs/termux-build.md for a working Termux setup."
            )
        }
        logger.lifecycle(
            "build environment: JDK $jdk, SDK $sdk, platform $platform, "
                + "aapt2 ${aapt2 ?: "AGP's own"}"
        )
    }
}

@DisableCachingByDefault(because = "Compares two small text files")
abstract class CheckManifests : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val canonical: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val staged: RegularFileProperty

    @TaskAction
    fun check() {
        // Only the package attribute may differ: build.sh's AAR keeps it, AGP 9
        // takes the namespace from build.gradle.kts and warns if a source manifest
        // still has it. Comments and declarations must stay in sync.
        val packageAttribute = Regex("""\s+package\s*=\s*"[^"]*"""")
        val expected = canonical.get().asFile.readText().replace(packageAttribute, "")
        val actual = staged.get().asFile.readText()
        if (expected != actual) {
            throw GradleException(
                "${staged.get().asFile} has drifted from ${canonical.get().asFile}; " +
                    "the two must differ only by the package attribute. " +
                    "Update the staged Gradle manifest from the canonical one."
            )
        }
    }
}

val stagedManifest = layout.projectDirectory.file("gradle/AndroidManifest.xml")
val canonicalManifest = layout.projectDirectory.file("AndroidManifest.xml")

tasks.register<CheckManifests>("checkManifests") {
    group = "verification"
    description = "Checks the Gradle-only manifest against the canonical one (package aside)."
    canonical.set(canonicalManifest)
    staged.set(stagedManifest)
}

tasks.register<CheckBuildEnv>("checkBuildEnv") {
    group = "verification"
    description = "Fails early, with one message, when the JDK, SDK, compile SDK or aapt2 is missing."
    compileSdk.set(libs.versions.compileSdk.map(String::toInt))
    javaSpecificationVersion.set(providers.systemProperty("java.specification.version"))
    osArch.set(providers.systemProperty("os.arch"))
    javaHome.set(providers.systemProperty("java.home"))
    termuxPrefix.set(providers.environmentVariable("PREFIX"))
    sdkFromEnvironment.set(
        providers.environmentVariable("ANDROID_HOME").filter { it.isNotBlank() }
            .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    )
    aapt2Override.set(providers.gradleProperty("android.aapt2FromMavenOverride"))
    localProperties.from(layout.projectDirectory.file("local.properties"))
}

// A staged manifest that no build reads is a trap; check it before every compile.
tasks.named("preBuild") {
    dependsOn("checkManifests")
}

publishing {
    // A file repository, so the layout Gradle would publish to Maven Central can be
    // inspected - and signed and bundled by publish.sh - without uploading.
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
