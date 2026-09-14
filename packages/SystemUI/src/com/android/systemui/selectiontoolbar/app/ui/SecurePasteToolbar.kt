package com.android.systemui.selectiontoolbar.app.ui

import android.content.Context
import android.text.TextUtils
import android.view.selectiontoolbar.ToolbarMenuItem

internal object SecurePasteToolbar {
    // Older toolkits use custom IDs. Match only exact localized titles, then canonicalize the
    // visible action before it can authorize clipboard access.
    fun resolvePasteActionId(context: Context, item: ToolbarMenuItem): Int? {
        return when {
            item.itemId == android.R.id.paste ||
                TextUtils.equals(context.getText(android.R.string.paste), item.title) ->
                android.R.id.paste
            item.itemId == android.R.id.pasteAsPlainText ||
                TextUtils.equals(
                    context.getText(android.R.string.paste_as_plain_text),
                    item.title,
                ) -> android.R.id.pasteAsPlainText
            else -> null
        }
    }

    fun isPasteAction(item: ToolbarMenuItem): Boolean {
        return item.itemId == android.R.id.paste || item.itemId == android.R.id.pasteAsPlainText
    }
}
