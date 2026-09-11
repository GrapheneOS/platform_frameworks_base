/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spaprivileged.template.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.text.BidiFormatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.android.settingslib.development.DevelopmentSettingsEnabler
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.isSpaExpressiveEnabled
import com.android.settingslib.spa.widget.preference.IntroAppPreference
import com.android.settingslib.spa.widget.ui.CopyableBody
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsTitle
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.rememberAppRepository
import java.text.DateFormat
import java.util.Date

class AppInfoProvider(private val packageInfo: PackageInfo) {
    @Composable
    fun AppInfo(displayVersion: Boolean = false, isClonedAppPage: Boolean = false) {
        if (isSpaExpressiveEnabled) {
            val appRepository = rememberAppRepository()
            val app = checkNotNull(packageInfo.applicationInfo)
            val title = appRepository.produceLabel(app, isClonedAppPage).value

            val descriptions = mutableListOf<String>()
            if (app.isInstantApp) {
                descriptions.add(
                    stringResource(
                        com.android.settingslib.widget.preference.app.R.string.install_type_instant
                    )
                )
            }
            if (displayVersion) {
                val versionName = packageInfo.versionNameBidiWrapped
                if (versionName != null) descriptions.add(versionName)
            }

            IntroAppPreference(
                title = title,
                descriptions = descriptions,
                appIcon = {
                    Image(
                        painter = rememberDrawablePainter(appRepository.produceIcon(app).value),
                        contentDescription = appRepository.produceIconContentDescription(app).value,
                    )
                },
            )
        } else {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            horizontal = SettingsDimension.itemPaddingStart,
                            vertical = SettingsDimension.itemPaddingVertical,
                        )
                        .semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val app = checkNotNull(packageInfo.applicationInfo)
                Box(modifier = Modifier.padding(SettingsDimension.itemPaddingAround)) {
                    AppIcon(app = app, size = SettingsDimension.appIconInfoSize)
                }
                AppLabel(app, isClonedAppPage)
                InstallType(app)
                if (displayVersion) AppVersion()
            }
        }
    }

    @Composable
    private fun InstallType(app: ApplicationInfo) {
        if (!app.isInstantApp) return
        Spacer(modifier = Modifier.height(SettingsDimension.paddingSmall))
        SettingsBody(
            stringResource(
                com.android.settingslib.widget.preference.app.R.string.install_type_instant
            )
        )
    }

    @Composable
    private fun AppVersion() {
        val versionName = packageInfo.versionNameBidiWrapped ?: return
        Spacer(modifier = Modifier.height(SettingsDimension.paddingSmall))
        SettingsBody(versionName)
    }

    @Composable
    fun FooterAppVersion(showPackageName: Boolean = rememberIsDevelopmentSettingsEnabled()) {
        val context = LocalContext.current
        val footer =
            remember(packageInfo) {
                getFooterText(context)
            }
        if (footer.isBlank()) return
        if (!isSpaExpressiveEnabled) HorizontalDivider()
        Box(modifier = Modifier.padding(SettingsDimension.footerPadding)) {
            CopyableBody(footer, showDropdownTitle = false)
        }
    }

    @Composable
    private fun rememberIsDevelopmentSettingsEnabled(): Boolean {
        val context = LocalContext.current
        return remember { DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(context) }
    }

    private companion object {
        /** Wrapped the version name, so its directionality still keep same when RTL. */
        val PackageInfo.versionNameBidiWrapped: String?
            get() = BidiFormatter.getInstance().unicodeWrap(versionName)
    }

    enum class FormattingCommand {
        MonospaceTextStart,
        MonospaceTextEnd,
    }

    private fun getFooterText(ctx: Context): AnnotatedString {
        val pi = packageInfo

        val dateFormat = android.text.format.DateFormat.getMediumDateFormat(ctx)
        val timeFormat = android.text.format.DateFormat.getTimeFormat(ctx)

        fun formatDate(unixTs: Long, dateFormat: DateFormat, timeFormat: DateFormat): String {
            val d = Date(unixTs)
            return dateFormat.format(d) + "; " + timeFormat.format(d)
        }

        val appInfo = pi.applicationInfo

        val lines = mutableListOf<Any>()
        lines.add(pi.packageName)
        pi.versionNameBidiWrapped?.let {
            lines.add(ctx.getString(R.string.version_text, it))
        }
        lines.add("versionCode ${pi.getLongVersionCode()}")
        if (appInfo != null) {
            lines.add("targetSdk ${appInfo.targetSdkVersion} | minSdk ${appInfo.minSdkVersion}")
            if (appInfo.hasPlayStoreSourceStamp()) {
                lines.add(ctx.getString(R.string.app_info_has_play_store_source_stamp))
            }
        }

        // some system apps report being installed in January 2009, skip showing install time for them
        val minTime = 1_240_000_000_000 // April 2009

        var addedBlankLineBeforeTime = false

        if (pi.firstInstallTime > minTime) {
            lines.add("")
            addedBlankLineBeforeTime = true
            val s = formatDate(pi.firstInstallTime, dateFormat, timeFormat)
            lines.add(ctx.getString(R.string.app_info_install_time, s))
        }

        if (pi.lastUpdateTime != pi.firstInstallTime) {
            if (!addedBlankLineBeforeTime) {
                lines.add("")
            }
            val s = formatDate(pi.lastUpdateTime, dateFormat, timeFormat)
            lines.add(ctx.getString(R.string.app_info_update_time, s))
        }

        val signingInfo = pi.signingInfo
        if (signingInfo != null) {
            val certs = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                val certHistory = signingInfo.signingCertificateHistory
                if (certHistory != null) {
                    arrayOf(certHistory.last())
                } else {
                    null
                }
            }
            if (certs != null) {
                lines.add("")
                val hexFormat = HexFormat {
                    bytes {
                        bytesPerGroup = 2
                        groupSeparator = " "
                    }
                }
                certs.forEach {
                    lines.add(ctx.getString(R.string.app_info_apk_cert_digest))
                    val digest = it.sha256Digest
                    check(digest.size == 32)
                    lines.add(FormattingCommand.MonospaceTextStart)
                    lines.add(digest.copyOfRange(0, 16).toHexString(hexFormat))
                    lines.add(digest.copyOfRange(16, 32).toHexString(hexFormat))
                    lines.add(FormattingCommand.MonospaceTextEnd)
                }
            }
        }

        return buildAnnotatedString {
            var start = -1
            lines.forEachIndexed { idx, obj ->
                when {
                    obj is String -> {
                        if (length != 0) {
                            append('\n')
                        }
                        append(obj)
                    }
                    obj == FormattingCommand.MonospaceTextStart -> {
                        start = length
                    }
                    obj == FormattingCommand.MonospaceTextEnd -> {
                        addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, length)
                    }
                    else -> {
                        error("unknown command $obj")
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppIcon(app: ApplicationInfo, size: Dp) {
    val appRepository = rememberAppRepository()
    Image(
        painter = rememberDrawablePainter(appRepository.produceIcon(app).value),
        contentDescription = appRepository.produceIconContentDescription(app).value,
        modifier = Modifier.size(size),
    )
}

@Composable
internal fun AppLabel(app: ApplicationInfo, isClonedAppPage: Boolean = false) {
    val appRepository = rememberAppRepository()
    SettingsTitle(appRepository.produceLabel(app, isClonedAppPage).value, useMediumWeight = true)
}
