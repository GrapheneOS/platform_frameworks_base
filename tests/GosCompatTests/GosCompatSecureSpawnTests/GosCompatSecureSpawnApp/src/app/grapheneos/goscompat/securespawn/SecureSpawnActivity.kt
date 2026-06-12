package app.grapheneos.goscompat.securespawn

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnHiddenApiCheck
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnReflectiveDumpCheck
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnSmapsCheck
import app.grapheneos.goscompat.securespawn.shared.SecureSpawnTestApiCompatCheck
import kotlin.concurrent.thread

object SecureSpawnUiTags {
    const val RUN_CHECK_BUTTON = "secure_spawn_run_check_button"
    const val OPEN_SECURITY_SETTINGS_BUTTON = "secure_spawn_open_security_settings_button"
    const val SECURE_APP_SPAWNING_STATE = "secure_spawn_setting_state"
    const val CHECK_RESULT = "secure_spawn_check_result"
    const val HIDDEN_API_REFLECTION_RESULT = "secure_spawn_hidden_api_reflection_result"
    const val TEST_API_COMPAT_DEFAULT_RESULT = "secure_spawn_test_api_compat_default_result"
    const val ACYCLIC_REFLECTIVE_DUMP_RESULT = "secure_spawn_acyclic_reflective_dump_result"
}

class SecureSpawnActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            var resultState by remember {
                mutableStateOf<CheckResultState>(CheckResultState.NotRun)
            }
            var settingState by remember {
                mutableStateOf<SettingResultState>(SettingResultState.Loading)
            }
            var running by remember { mutableStateOf(false) }

            fun refreshSecureAppSpawningState() {
                settingState = SettingResultState.Loading
                readSecureAppSpawningSetting { result ->
                    settingState = result
                }
            }

            fun runCheckFromUi() {
                if (running) {
                    return
                }
                running = true
                runCheck { result ->
                    if (result is CheckResultState.Success) {
                        settingState = SettingResultState.Success(
                            result.result.secureAppSpawningSetting(),
                        )
                    }
                    resultState = result
                    running = false
                }
            }

            LaunchedEffect(Unit) {
                refreshSecureAppSpawningState()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SecureSpawnScreen(
                        settingState = settingState,
                        resultState = resultState,
                        running = running,
                        onRunCheck = { runCheckFromUi() },
                        onOpenAppInfo = { openAppInfo() },
                    )
                }
            }
        }
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null))
        startActivity(intent)
    }

    private fun runCheck(onResult: (CheckResultState) -> Unit) {
        thread(name = "secure-spawn-check") {
            val result = try {
                CheckResultState.Success(SecureSpawnCheck.run())
            } catch (t: RuntimeException) {
                CheckResultState.Error("${t.javaClass.simpleName}: ${t.message}")
            } catch (t: UnsatisfiedLinkError) {
                CheckResultState.Error("${t.javaClass.simpleName}: ${t.message}")
            }
            runOnUiThread { onResult(result) }
        }
    }

    private fun readSecureAppSpawningSetting(onResult: (SettingResultState) -> Unit) {
        thread(name = "secure-spawn-setting") {
            val result = try {
                SettingResultState.Success(SecureSpawnCheck.secureAppSpawningSetting())
            } catch (t: RuntimeException) {
                SettingResultState.Error("${t.javaClass.simpleName}: ${t.message}")
            } catch (t: UnsatisfiedLinkError) {
                SettingResultState.Error("${t.javaClass.simpleName}: ${t.message}")
            }
            runOnUiThread { onResult(result) }
        }
    }
}

private sealed class SettingResultState {
    object Loading : SettingResultState()
    data class Success(val setting: SecureSpawnCheck.SecureAppSpawningSetting) :
        SettingResultState()
    data class Error(val message: String) : SettingResultState()
}

private sealed class CheckResultState {
    object NotRun : CheckResultState()
    data class Success(val result: SecureSpawnCheck.Result) : CheckResultState()
    data class Error(val message: String) : CheckResultState()
}

@Composable
private fun SecureSpawnScreen(
    settingState: SettingResultState,
    resultState: CheckResultState,
    running: Boolean,
    onRunCheck: () -> Unit,
    onOpenAppInfo: () -> Unit,
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
            text = "GOS Secure Spawn Checks",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "For manual comparison, change secure app spawning setting, then run the check again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenAppInfo,
                modifier = Modifier.testTag(SecureSpawnUiTags.OPEN_SECURITY_SETTINGS_BUTTON),
            ) {
                Text("Open app settings")
            }
        }

        SecureAppSpawningCard(settingState)

        CheckRunnerCard(running, onRunCheck)
        CheckResultContent(
            resultState = resultState,
            running = running,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SecureSpawnUiTags.CHECK_RESULT),
        )
    }
}

@Composable
private fun CheckRunnerCard(running: Boolean, onRunCheck: () -> Unit) {
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
                text = "Secure spawn checks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                onClick = onRunCheck,
                enabled = !running,
                modifier = Modifier.testTag(SecureSpawnUiTags.RUN_CHECK_BUTTON),
            ) {
                Text(if (running) "Running" else "Run checks")
            }
        }
    }
}

@Composable
private fun CheckResultContent(
    resultState: CheckResultState,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            running -> ResultCard {
                StatusHeader(label = "Status", status = "RUNNING", passed = null)
            }
            resultState is CheckResultState.NotRun ->
                ResultCard {
                    StatusHeader(label = "Status", status = "NOT RUN", passed = null)
                }
            resultState is CheckResultState.Error -> ErrorSection(resultState.message)
            resultState is CheckResultState.Success -> StructuredResult(resultState.result)
        }
    }
}

@Composable
private fun StructuredResult(result: SecureSpawnCheck.Result) {
    ProcessStateCard(result.processState())
    RuntimeMemoryAccountingCard(result.androidRuntimeSmaps())
    HiddenApiReflectionCard(result.hiddenApiEnforcement())
    TestApiCompatDefaultCard(result.testApiCompatDefault())
    AcyclicReflectiveDumpCard(result.acyclicReflectiveDump())
}

@Composable
private fun SecureAppSpawningCard(settingState: SettingResultState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SecureSpawnUiTags.SECURE_APP_SPAWNING_STATE),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Secure app spawning",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            when (settingState) {
                is SettingResultState.Loading ->
                    StatusHeader(label = "State", status = "READING", passed = null)
                is SettingResultState.Error ->
                    Text(
                        text = settingState.message,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                is SettingResultState.Success -> {
                    SettingStateRow(
                        label = "Current state",
                        status = if (settingState.setting.enabled()) "ENABLED" else "DISABLED",
                        enabled = settingState.setting.enabled(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessStateCard(processState: SecureSpawnCheck.ProcessState) {
    val passed = processState.pid() > 0 && processState.tid() > 0
    ResultCard {
        Section(label = "Process state", status = if (passed) "PASS" else "FAIL", passed = passed) {
            DetailRow("Exec spawned", processState.execSpawned().toString())
            DetailRow("hardened_malloc disabled", processState.hardenedMallocDisabled().toString())
            DetailRow("PID", processState.pid().toString())
            DetailRow("TID", processState.tid().toString())
            if (!passed) {
                Text(
                    text = "The process state check failed because PID/TID could not be read.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun RuntimeMemoryAccountingCard(smaps: SecureSpawnSmapsCheck.AndroidRuntimeSmaps) {
    val passed = smaps.sections() > 0 &&
            smaps.androidRuntimeSections() > 0 &&
            smaps.isWithinMemoryBounds()
    ResultCard {
        Section(
            label = "Runtime memory accounting",
            status = if (passed) "PASS" else "FAIL",
            passed = passed,
        ) {
            Text(
                text = runtimeMemoryAccountingSummary(smaps),
                style = MaterialTheme.typography.bodyMedium,
            )
            DetailRow("Total smaps sections", smaps.sections().toString())
            DetailRow("libandroid_runtime.so sections", smaps.androidRuntimeSections().toString())
            DetailRow("Measured sections", smaps.measuredSections().toString())
            DetailRow("Out-of-bounds sections", smaps.outOfBoundsSections().toString())
            DetailRow("Max libandroid_runtime.so Shared_Clean",
                    formatBytes(smaps.maxAndroidRuntimeSharedClean()))
            DetailRow("Max measured Shared_Clean", formatBytes(smaps.maxMeasuredSharedClean()))
            DetailRow("Max out-of-bounds Shared_Clean",
                    formatBytes(smaps.maxOutOfBoundsSharedClean()))
            DetailRow("Shared_Clean limit", formatBytes(smaps.sharedCleanLimit()))
            if (smaps.measuredDetails().isNotEmpty()) {
                MonospaceBlock("Measured mappings", smaps.measuredDetails())
            }
            if (smaps.firstOutOfBoundsHeader().isNotEmpty()) {
                DetailRow("First out-of-bounds Shared_Clean",
                        formatBytes(smaps.firstOutOfBoundsSharedClean()))
                DetailRow("First out-of-bounds Shared_Dirty",
                        formatBytes(smaps.firstOutOfBoundsSharedDirty()))
                MonospaceBlock("First out-of-bounds maps line", smaps.firstOutOfBoundsHeader())
            }
        }
    }
}

@Composable
private fun HiddenApiReflectionCard(
    hiddenApi: SecureSpawnHiddenApiCheck.HiddenApiEnforcement,
) {
    val passed = hiddenApi.objectShadowFieldsHidden()
    ResultCard(
        modifier = Modifier.testTag(SecureSpawnUiTags.HIDDEN_API_REFLECTION_RESULT),
    ) {
        Section(
            label = "Hidden API reflection",
            status = if (passed) "PASS" else "FAIL",
            passed = passed,
        ) {
            Text(
                text = if (passed) {
                    "Passed because Object shadow fields were hidden from reflection."
                } else {
                    "Failed because Object shadow fields were visible to reflection."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            DetailRow("Exec spawned", hiddenApi.execSpawned().toString())
            DetailRow("Object declared field count",
                    hiddenApi.objectDeclaredFieldCount().toString())
            DetailRow("shadow\$_klass_ visible",
                    hiddenApi.objectShadowKlassVisible().toString())
            DetailRow("shadow\$_monitor_ visible",
                    hiddenApi.objectShadowMonitorVisible().toString())
            MonospaceBlock("Object declared field names", hiddenApi.objectDeclaredFieldNames())
        }
    }
}

@Composable
private fun TestApiCompatDefaultCard(
    testApi: SecureSpawnTestApiCompatCheck.TestApiCompat,
) {
    val passed = testApi.accessResult().outcome() ==
            SecureSpawnTestApiCompatCheck.AccessOutcome.ACCESS_DENIED
    ResultCard(
        modifier = Modifier.testTag(SecureSpawnUiTags.TEST_API_COMPAT_DEFAULT_RESULT),
    ) {
        Section(
            label = "Test API compat default",
            status = if (passed) "PASS" else "FAIL",
            passed = passed,
        ) {
            Text(
                text = if (passed) {
                    "Passed because default test API access was denied."
                } else {
                    "Failed because default test API access was not denied."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            DetailRow("Exec spawned", testApi.execSpawned().toString())
            DetailRow("ALLOW_TEST_API_ACCESS change ID",
                    testApi.allowTestApiAccessChangeId().toString())
            DetailRow("Framework compat enabled", testApi.frameworkCompatEnabled())
            DetailRow("Candidate", testApi.accessResult().candidate())
            DetailRow("Outcome", testApi.accessResult().outcome().toString())
            DetailRow("Detail", testApi.accessResult().detail())
            DetailRow("Access allowed", testApi.accessAllowed().toString())
        }
    }
}

@Composable
private fun AcyclicReflectiveDumpCard(
    dump: SecureSpawnReflectiveDumpCheck.AcyclicReflectiveDump,
) {
    val passed = dump.threadTid() > 0 && dump.fixtureDepth() > 1 && dump.completed()
    ResultCard(
        modifier = Modifier.testTag(SecureSpawnUiTags.ACYCLIC_REFLECTIVE_DUMP_RESULT),
    ) {
        Section(
            label = "Acyclic reflective dump",
            status = if (passed) "PASS" else "FAIL",
            passed = passed,
        ) {
            Text(
                text = acyclicReflectiveDumpSummary(dump),
                style = MaterialTheme.typography.bodyMedium,
            )
            DetailRow("Exec spawned", dump.execSpawned().toString())
            DetailRow("Fixture depth", dump.fixtureDepth().toString())
            DetailRow("Completed", dump.completed().toString())
            DetailRow("Result length", dump.resultLength().toString())
            DetailRow("Thread", dump.threadName().ifEmpty { "<unknown>" })
            DetailRow("Thread TID", dump.threadTid().toString())
            if (dump.failureClass().isNotEmpty()) {
                DetailRow("Failure class", dump.failureClass())
            }
            if (dump.failureMessage().isNotEmpty()) {
                DetailRow("Failure message", dump.failureMessage())
            }
            DetailRow("dumpObject frames", dump.dumpObjectFrames().toString())
            DetailRow("getAllFields frames", dump.getAllFieldsFrames().toString())
            DetailRow("Arrays.toArray frames", dump.arraysToArrayFrames().toString())
            DetailRow("ArrayList.addAll frames", dump.arrayListAddAllFrames().toString())
            DetailRow("Compatibility frames", dump.compatibilityFrames().toString())
            if (dump.stackTraceSample().isNotEmpty()) {
                MonospaceBlock("Stack sample", dump.stackTraceSample())
            }
        }
    }
}

@Composable
private fun MonospaceBlock(label: String, value: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            softWrap = false,
        )
    }
}

private fun acyclicReflectiveDumpSummary(
    dump: SecureSpawnReflectiveDumpCheck.AcyclicReflectiveDump,
): String {
    if (dump.threadTid() <= 0) {
        return "Failed because the reflective dump worker thread did not report a TID."
    }
    if (dump.fixtureDepth() <= 1) {
        return "Failed because the acyclic fixture was not deep enough."
    }
    if (!dump.completed()) {
        return "Failed because the bounded acyclic reflective dump did not complete."
    }
    return "Passed because the bounded acyclic reflective dump completed depth " +
            "${dump.fixtureDepth()} without walking hidden runtime internals."
}

@Composable
private fun ResultCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

private fun runtimeMemoryAccountingSummary(
    smaps: SecureSpawnSmapsCheck.AndroidRuntimeSmaps,
): String {
    if (smaps.sections() <= 0) {
        return "Failed because /proc/self/smaps did not contain parseable sections."
    }
    if (smaps.androidRuntimeSections() <= 0) {
        return "Failed because no libandroid_runtime.so section was found in /proc/self/smaps."
    }
    if (!smaps.isWithinMemoryBounds()) {
        return "Failed because ${smaps.outOfBoundsSections()} libandroid_runtime.so section(s) " +
                "exceeded the memory accounting bound."
    }
    return "Passed because libandroid_runtime.so memory accounting stayed within bounds."
}

@Composable
private fun ErrorSection(message: String) {
    ResultCard {
        Section(label = "Status", status = "ERROR", passed = false) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Section(
    label: String,
    status: String? = null,
    passed: Boolean? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == null) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            StatusHeader(label = label, status = status, passed = passed)
        }
        content()
    }
}

@Composable
private fun StatusHeader(label: String, status: String, passed: Boolean?) {
    val statusColor = when (passed) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.tertiary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = status,
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = statusColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingStateRow(label: String, status: String, enabled: Boolean) {
    val statusColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = status,
            modifier = Modifier
                .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = statusColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return "$bytes bytes (${bytes / 1024L} KiB)"
}
