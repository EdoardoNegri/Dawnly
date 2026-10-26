package com.example.sunwakeup.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

private const val CONTACT = "edon.negri@gmail.com"

/** Where the author can be thanked with a drink; empty hides the button. */
private const val SUPPORT_URL = "https://revolut.me/edo_ne"

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}.getOrDefault("")

/** A mail to the author, which says by itself which app, which version and which phone it comes from. */
private fun writeMail(context: Context) {
    val version = versionName(context)
    val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT))
        putExtra(Intent.EXTRA_SUBJECT, "Dawnly $version")
        putExtra(
            Intent.EXTRA_TEXT,
            "\n\n--\nDawnly $version\n" +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n",
        )
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No mail app was found.", Toast.LENGTH_SHORT).show() }
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(context, "No browser was found.", Toast.LENGTH_SHORT).show() }
}

/** Opens the app's page in the Play Store, where it is rated; in the browser if the phone has no Play Store. */
private fun rateApp(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())) }
        .onFailure { openLink(context, "https://play.google.com/store/apps/details?id=${context.packageName}") }
}

/** The quiet link at the bottom of every page that opens [AboutSheet]. */
@Composable
fun AboutLink(modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text("About Dawnly", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Who made Dawnly, and how to reach or thank them, in a sheet that slides up from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = versionName(context)
    // Open all the way, so the last button isn't hidden under the fold.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("About", style = MaterialTheme.typography.headlineSmall)
            if (version.isNotEmpty()) Note("Dawnly $version")
            Spacer(Modifier.height(8.dp))
            Text(
                "Hi, I am Edo, and I made Dawnly: a gentler way to fall asleep and wake up, with light, " +
                    "sound and vibration that change slowly. No ads, no tracking, no account and no internet " +
                    "access. Your settings stay on your phone."
            )
            Note("Is something broken, or do you have an idea?")
            AboutButton("Send feedback") { writeMail(context) }
            Note("Enjoying it? A rating helps others find it.")
            AboutButton("Rate the app") { rateApp(context) }
            if (SUPPORT_URL.isNotEmpty()) {
                Note("Or, if you would like to say thanks:")
                AboutButton("Buy me a drink") { openLink(context, SUPPORT_URL) }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** An amber outline that fills in solid amber while it is held down. */
@Composable
private fun AboutButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val amber = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(if (pressed) amber else Color.Transparent, label = "fill")
    val ink by animateColorAsState(if (pressed) MaterialTheme.colorScheme.onPrimary else amber, label = "ink")
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, amber),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = fill, contentColor = ink),
        interactionSource = interaction,
    ) { Text(text) }
}
