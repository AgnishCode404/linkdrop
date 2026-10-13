import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Protocol and connection logic shared by both apps. No UI lives here.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    android {
       namespace = "com.agnish.linkdrop.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()

       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serializationJson)
            api(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
