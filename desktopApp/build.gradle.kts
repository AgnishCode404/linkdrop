import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverWebsockets)
    runtimeOnly(libs.slf4j.simple)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.agnish.linkdrop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "linkdrop"
            packageVersion = "1.0.0"
        }
    }
}
