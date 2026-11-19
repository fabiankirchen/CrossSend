package com.crosssend.app.ui.viewmodel

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Represents a transient UI message (toast or snackbar) emitted by ViewModels.
 */
sealed interface UiMessage {
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiMessage

    data class Plural(
        @param:PluralsRes val resId: Int,
        val quantity: Int,
        val args: List<Any> = emptyList()
    ) : UiMessage

    data class Text(val text: String) : UiMessage
}

/**
 * Resolves a [UiMessage] into a localized display string using [resources].
 */
fun UiMessage.resolve(resources: Resources): String {
    return when (this) {
        is UiMessage.Resource -> {
            if (args.isEmpty()) {
                resources.getString(resId)
            } else {
                resources.getString(resId, *args.toTypedArray())
            }
        }
        is UiMessage.Plural -> {
            if (args.isEmpty()) {
                resources.getQuantityString(resId, quantity)
            } else {
                resources.getQuantityString(resId, quantity, *args.toTypedArray())
            }
        }
        is UiMessage.Text -> text
    }
}
