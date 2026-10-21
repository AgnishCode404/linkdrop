// Applied by desktopApp and androidApp: `check` fails if a library the app packages is missing from
// its section of THIRD-PARTY-NOTICES, or is listed with another version. The fix is to regenerate
// the file: ./gradlew :desktopApp:createDistributable, then tools/third_party_notices.py.
//
// The applying build sets these extra properties first:
//   noticesConfiguration: the configuration whose libraries the app packages
//   noticesSection: the app's heading in THIRD-PARTY-NOTICES
//   noticesArtifactType (optional): which form of each library to resolve, where there are several
//     (Android: android-classes-jar, the code every library ends up as in the APK)
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

val noticesConfiguration = project.extra["noticesConfiguration"] as String
val noticesSection = project.extra["noticesSection"] as String
val noticesArtifactType = project.extra.properties["noticesArtifactType"] as String?

val checkThirdPartyNotices by tasks.registering {
    group = "verification"
    description = "Checks that THIRD-PARTY-NOTICES lists every library this app ships."
    val notices = rootProject.layout.projectDirectory.file("THIRD-PARTY-NOTICES")
    // Every module that puts a file (jar or aar) into the app, as "group:artifact version".
    val shipped = configurations.named(noticesConfiguration)
        .flatMap { configuration ->
            configuration.incoming.artifactView {
                noticesArtifactType?.let { type ->
                    attributes { attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, type) }
                }
            }.artifacts.resolvedArtifacts
        }
        .map { artifacts ->
            artifacts.mapNotNull { artifact ->
                (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module} ${it.version}" }
            }.toSortedSet()
        }
    val section = noticesSection
    inputs.property("shipped", shipped)
    inputs.file(notices)
    doLast {
        val text = notices.asFile.readText()
        val start = text.indexOf("\n$section\n")
        if (start < 0) throw GradleException("THIRD-PARTY-NOTICES has no \"$section\" section")
        val end = text.indexOf("\n\n\n", start + 1).let { if (it < 0) text.length else it }
        val listed = text.substring(start, end).lines().mapNotNull { line ->
            Regex("""^ {2}(\S+) (\S+)\s""").find(line)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        }.toSet()
        val missing = shipped.get() - listed
        if (missing.isNotEmpty()) {
            throw GradleException(
                "THIRD-PARTY-NOTICES (\"$section\") does not list these libraries the app ships:\n" +
                    missing.joinToString("\n") { "  $it" } +
                    "\nRegenerate it: ./gradlew :desktopApp:createDistributable, then tools/third_party_notices.py",
            )
        }
    }
}
tasks.named("check") { dependsOn(checkThirdPartyNotices) }
