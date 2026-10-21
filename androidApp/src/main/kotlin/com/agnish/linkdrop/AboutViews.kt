package com.agnish.linkdrop

import android.content.res.AssetManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LinkDrop's license and the third-party notices, read from the APK's assets: the repository's
 * LICENSE and THIRD-PARTY-NOTICES, copied in by the build (androidApp/build.gradle.kts).
 */
class LegalTexts(val license: String, val notices: String) {
    /** The notices in blocks (split at blank lines), so a long list scrolls without lag. */
    val noticeBlocks: List<String> get() = notices.split(Regex("\n{2,}")).map { it.trimEnd() }.filter { it.isNotBlank() }

    companion object {
        fun load(assets: AssetManager) = LegalTexts(
            license = assets.open("LICENSE").bufferedReader().use { it.readText() }.trim(),
            notices = assets.open("THIRD-PARTY-NOTICES").bufferedReader().use { it.readText() },
        )
    }
}

/** "About LinkDrop": the version, LinkDrop's license, then every third-party license. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val texts by produceState<LegalTexts?>(null) {
        value = withContext(Dispatchers.IO) { LegalTexts.load(context.assets) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().safeContentPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = BUTTON_HEIGHT)) { Text("← Back") }
        }
        item {
            Text("LinkDrop", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val loaded = texts ?: return@LazyColumn
        item {
            SectionTitle("License")
            Text(loaded.license, style = MaterialTheme.typography.bodySmall)
        }
        item { SectionTitle("Open source licenses") }
        items(loaded.noticeBlocks) { block ->
            Text(
                block,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
