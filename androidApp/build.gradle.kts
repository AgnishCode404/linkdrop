import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/** Development switch from gradle.properties; see the comment there. */
val allowPlaintext = providers.gradleProperty("linkdrop.allowPlaintext").map { it.toBoolean() }.orElse(false)

/** linkdrop.version (gradle.properties), e.g. 1.2.3, and its versionCode, e.g. 10203. */
val appVersion: String = providers.gradleProperty("linkdrop.version").get()
val appVersionCode: Int = run {
    val parts = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)?.destructured?.toList()
        ?: throw GradleException("linkdrop.version must look like 1.2.3 (each part 0-99), not \"$appVersion\"")
    parts.fold(0) { code, part -> code * 100 + part.toInt() }
}

/**
 * The release signing key: a keystore OUTSIDE this repository, named by these properties in
 * ~/.gradle/gradle.properties (never in the project's own gradle.properties). See README.
 * Without them the release APK is built unsigned.
 */
val signingStoreFile = providers.gradleProperty("linkdrop.signing.storeFile")
val signingStorePassword = providers.gradleProperty("linkdrop.signing.storePassword")
val signingKeyAlias = providers.gradleProperty("linkdrop.signing.keyAlias")
val signingKeyPassword = providers.gradleProperty("linkdrop.signing.keyPassword")
val releaseSigning = signingStoreFile.isPresent

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)

    implementation(libs.okhttp)
    // Dispatchers.Main for the connection service (it was only there through other libraries).
    implementation(libs.kotlinx.coroutinesAndroid)

    // JVM unit tests: HelloClient against a local fake WebSocket server.
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverWebsockets)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.agnish.linkdrop"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.agnish.linkdrop"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersion
    }
    signingConfigs {
        if (releaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile.get())
                storePassword = signingStorePassword.orNull
                    ?: throw GradleException("linkdrop.signing.storePassword is missing")
                keyAlias = signingKeyAlias.orNull ?: throw GradleException("linkdrop.signing.keyAlias is missing")
                keyPassword = signingKeyPassword.orNull ?: storePassword
                storeType = "PKCS12"
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_PLAINTEXT", allowPlaintext.get().toString())
        }
        release {
            // Never plaintext in a release, regardless of linkdrop.allowPlaintext.
            buildConfigField("boolean", "ALLOW_PLAINTEXT", "false")
            if (releaseSigning) signingConfig = signingConfigs.getByName("release")
            // R8: removes unused code and resources and shortens names (release only; debug builds
            // and tests are unchanged). Libraries bring their own keep rules; LinkDrop needs none:
            // every JSON message names its serializer in code, nothing is looked up by class name.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Unit tests run on JDK 25, like the desktop: the pairing tests need the TLS exporter API
// (JDK 21 lacks it). The app itself runs on Android, which has its own (SSLSockets).
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}

// Release guard: refuse to build a release while plaintext is enabled, or if any network
// security config that a release uses permits cleartext. Only debug/ may allow it.
val checkNoPlaintextInRelease by tasks.registering {
    val allow = allowPlaintext
    val releaseNetworkConfigs = fileTree("src") { include("main/res/xml/**", "release/res/xml/**") }
    inputs.property("allowPlaintext", allow)
    inputs.files(releaseNetworkConfigs)
    doLast {
        if (allow.get()) {
            throw GradleException(
                "Release builds are blocked while linkdrop.allowPlaintext=true (gradle.properties). " +
                    "Releases require TLS + pairing; plaintext is for development only.",
            )
        }
        val cleartext = Regex("""cleartextTrafficPermitted\s*=\s*"true"""")
        releaseNetworkConfigs.filter { cleartext.containsMatchIn(it.readText()) }.forEach {
            throw GradleException("Release network config permits cleartext: $it")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkNoPlaintextInRelease) }

// Signing guard: the key must never be inside the repository (where it could be committed), and
// a release without a key is built unsigned (it cannot be installed) with a clear note.
val checkReleaseSigning by tasks.registering {
    val store = signingStoreFile.map { file(it).canonicalFile }
    val repo = rootDir.canonicalFile
    inputs.property("signing", signingStoreFile.orElse(""))
    doLast {
        if (!store.isPresent) {
            logger.warn(
                "No release signing key (linkdrop.signing.* in ~/.gradle/gradle.properties): " +
                    "the release APK is UNSIGNED and cannot be installed. See README.",
            )
            return@doLast
        }
        val key = store.get()
        if (key.startsWith(repo)) throw GradleException("The signing key must not be inside the repository: $key")
        if (!key.isFile) throw GradleException("Signing key not found: $key")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }

// LinkDrop's LICENSE and THIRD-PARTY-NOTICES (repository root) as assets, for the About screen.
abstract class LegalAssets : DefaultTask() {
    @get:InputFiles
    abstract val files: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        files.forEach { it.copyTo(File(dir, it.name)) }
    }
}
val legalAssets = tasks.register<LegalAssets>("legalAssets") {
    files.from(rootProject.file("LICENSE"), rootProject.file("THIRD-PARTY-NOTICES"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(legalAssets, LegalAssets::outputDir) }
}

// `check` fails if THIRD-PARTY-NOTICES does not list every library the release APK ships.
extra["noticesConfiguration"] = "releaseRuntimeClasspath"
extra["noticesSection"] = "Android app"
extra["noticesArtifactType"] = "android-classes-jar"
apply(from = rootProject.file("gradle/third-party-notices.gradle.kts"))