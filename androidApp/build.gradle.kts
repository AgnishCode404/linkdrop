import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/** Development switch from gradle.properties; see the comment there. */
val allowPlaintext = providers.gradleProperty("linkdrop.allowPlaintext").map { it.toBoolean() }.orElse(false)

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

    implementation(libs.ktor.clientOkhttp)
    implementation(libs.ktor.clientWebsockets)

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
        versionCode = 1
        versionName = "1.0"
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
            isMinifyEnabled = false
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