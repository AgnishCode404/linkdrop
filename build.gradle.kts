plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

// Fails the build if a source or config file contains invisible characters: formatting ones
// (bidi overrides and isolates, zero-width spaces, byte order marks), line/paragraph separators,
// or control characters other than tab, newline and carriage return. They can make code read
// differently from what the compiler sees ("Trojan Source"). Tests use \uXXXX escapes instead.
val checkSourceCharacters by tasks.registering {
    group = "verification"
    description = "Fails if source files contain invisible or formatting characters."
    val sources = fileTree(rootDir) {
        include("**/*.kt", "**/*.kts", "**/*.java", "**/*.md", "**/*.toml", "**/*.xml", "**/*.properties", "**/*.pro", "**/*.json")
        exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".git/**", ".idea/**")
    }
    val root = rootDir
    val marker = layout.buildDirectory.file("checkSourceCharacters.ok")
    inputs.files(sources)
    outputs.file(marker)
    doLast {
        val findings = mutableListOf<String>()
        sources.files.sorted().forEach { file ->
            file.readLines(Charsets.UTF_8).forEachIndexed { lineIndex, line ->
                var offset = 0
                while (offset < line.length) {
                    val codePoint = line.codePointAt(offset)
                    val type = Character.getType(codePoint)
                    val forbidden = type == Character.FORMAT.toInt() ||
                        type == Character.LINE_SEPARATOR.toInt() ||
                        type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                        (type == Character.CONTROL.toInt() && codePoint != '\t'.code && codePoint != '\r'.code)
                    if (forbidden) {
                        val name = Character.getName(codePoint) ?: "unnamed"
                        findings += "${file.relativeTo(root)}:${lineIndex + 1}:${offset + 1}: " +
                            "U+%04X %s (write it as \\u%04X)".format(codePoint, name, codePoint)
                    }
                    offset += Character.charCount(codePoint)
                }
            }
        }
        if (findings.isNotEmpty()) {
            throw GradleException("Invisible or formatting characters in source files:\n" + findings.joinToString("\n"))
        }
        marker.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}

// Every test run and every `check` runs the scan first.
subprojects {
    tasks.matching { it is Test || it.name == "check" }.configureEach {
        dependsOn(":checkSourceCharacters")
    }
}
