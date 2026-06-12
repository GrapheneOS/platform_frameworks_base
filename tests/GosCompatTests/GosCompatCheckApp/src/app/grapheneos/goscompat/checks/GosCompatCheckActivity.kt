package app.grapheneos.goscompat.checks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.DeadObjectException
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.grapheneos.goscompat.checks.dmabuf.DmaBufReleasePanel

class GosCompatCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
            ),
        )

        setContent {
            val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                val directMapsScanResult = remember { mutableStateOf<MapsScanResult?>(null) }
                val directMapsScanRunning = remember { mutableStateOf(false) }
                val reflectiveMapsScanResult = remember { mutableStateOf<MapsScanResult?>(null) }
                val reflectiveMapsScanRunning = remember { mutableStateOf(false) }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GosCompatCheckScreen(
                        directMapsScanResult = directMapsScanResult.value,
                        directMapsScanRunning = directMapsScanRunning.value,
                        reflectiveMapsScanResult = reflectiveMapsScanResult.value,
                        reflectiveMapsScanRunning = reflectiveMapsScanRunning.value,
                        onRunDirectMapsScan = {
                            startMapsScan(
                                GosCompatContract.MapsScan.Method.RUN_DIRECT_CHECK,
                                directMapsScanResult,
                                directMapsScanRunning,
                            )
                        },
                        onRunReflectiveMapsScan = {
                            startMapsScan(
                                GosCompatContract.MapsScan.Method.RUN_REFLECTIVE_CHECK,
                                reflectiveMapsScanResult,
                                reflectiveMapsScanRunning,
                            )
                        },
                        onRunSecureSpawn = {
                            startSecureSpawn()
                        },
                        dmaBufReleaseContent = {
                            DmaBufReleasePanel(this@GosCompatCheckActivity)
                        },
                    )
                }
            }
        }
    }

    private fun startSecureSpawn() {
        try {
            startActivity(Intent().setClassName(
                GosCompatContract.SecureSpawn.PACKAGE_NAME,
                GosCompatContract.SecureSpawn.ACTIVITY_CLASS,
            ))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Secure spawn app is not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMapsScan(
        method: String,
        result: MutableState<MapsScanResult?>,
        running: MutableState<Boolean>,
    ) {
        if (running.value) {
            return
        }
        running.value = true
        Thread {
            val checkResult = runMapsScanProvider(this, method)
            runOnUiThread {
                result.value = checkResult
                running.value = false
            }
        }.start()
    }

    private fun runMapsScanProvider(context: Context, method: String): MapsScanResult {
        val startTimeMillis = System.currentTimeMillis()
        return try {
            val client = context.contentResolver.acquireUnstableContentProviderClient(
                GosCompatContract.MapsScan.CONTENT_URI,
            ) ?: return MapsScanResult.failed("Maps scan provider was unavailable")
            val bundle = try {
                // Keep the UI process from becoming a stable provider dependent. The scanner is
                // expected to crash when reproducing the compatibility issue on unfixed builds.
                client.call(method, null, null)
            } finally {
                client.close()
            }
            if (bundle != null) {
                MapsScanResult.fromBundle(bundle)
            } else {
                MapsScanResult.failed("Maps scan provider returned no result")
            }
        } catch (e: DeadObjectException) {
            MapsScanTombstoneReporter.getNativeCrashResult(context, startTimeMillis)
        } catch (t: Throwable) {
            MapsScanResult.failed(
                "Maps scan provider failed: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }
}

@Composable
private fun GosCompatCheckScreen(
    directMapsScanResult: MapsScanResult?,
    directMapsScanRunning: Boolean,
    reflectiveMapsScanResult: MapsScanResult?,
    reflectiveMapsScanRunning: Boolean,
    onRunDirectMapsScan: () -> Unit,
    onRunReflectiveMapsScan: () -> Unit,
    onRunSecureSpawn: () -> Unit,
    dmaBufReleaseContent: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "GOS Compat Checks",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Secure app spawning",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onRunSecureSpawn) {
                    Text("Open check")
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Memory maps scan",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                MapsScanControl(
                    title = "Direct JNI maps scan",
                    mapsScanResult = directMapsScanResult,
                    mapsScanRunning = directMapsScanRunning,
                    onRunMapsScan = onRunDirectMapsScan,
                )
                ResultSection("Direct JNI details", directMapsScanResult?.details.orEmpty())
                ResultSection("Direct JNI errors", directMapsScanResult?.errors.orEmpty())

                MapsScanControl(
                    title = "Reflective JNI maps scan",
                    mapsScanResult = reflectiveMapsScanResult,
                    mapsScanRunning = reflectiveMapsScanRunning,
                    onRunMapsScan = onRunReflectiveMapsScan,
                )
                ResultSection("Reflective JNI details", reflectiveMapsScanResult?.details.orEmpty())
                ResultSection("Reflective JNI errors", reflectiveMapsScanResult?.errors.orEmpty())
            }
        }

        dmaBufReleaseContent()
    }
}

@Composable
private fun MapsScanControl(
    title: String,
    mapsScanResult: MapsScanResult?,
    mapsScanRunning: Boolean,
    onRunMapsScan: () -> Unit,
) {
    val status = when {
        mapsScanRunning -> "Running"
        mapsScanResult != null -> mapsScanResult.statusText
        else -> "Not run"
    }
    val statusColor = when {
        mapsScanRunning -> MaterialTheme.colorScheme.tertiary
        mapsScanResult?.isCompleted == true && mapsScanResult.usedWorkerThread() ->
            MaterialTheme.colorScheme.primary
        mapsScanResult != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status,
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onRunMapsScan, enabled = !mapsScanRunning) {
                Text("Run")
            }
        }

        Text(
            text = mapsScanResult?.summary ?: "Memory maps scan has not been run.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
internal fun ResultSection(title: String, lines: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (lines.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        softWrap = false,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}
