package com.qiuyuxc.tingyu

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** One header and a separate scrolling viewport, shared by all retained pages. */
internal class TopBar(context: Context) : MiuixView(context) {
    private var heading by mutableStateOf("")
    private var description by mutableStateOf<String?>(null)
    private var hasBack by mutableStateOf(false)
    private var primaryIcon by mutableIntStateOf(0)
    private var primaryLabel by mutableStateOf<String?>(null)
    private var secondaryIcon by mutableIntStateOf(0)
    private var secondaryLabel by mutableStateOf("")
    private var backAction: Runnable? = null
    private var primaryAction: Runnable? = null
    private var secondaryAction: Runnable? = null
    private var scroll: ScrollView? = null
    private var headingSpacer = false

    @Composable
    override fun Render() {
        PageHeader(
            title = heading,
            subtitle = description,
            large = !hasBack,
            back = if (hasBack) ({ backAction?.run() }) else null,
        ) {
            if (secondaryIcon != 0) {
                MiuixIconButton(secondaryIcon, secondaryLabel) { secondaryAction?.run() }
            }
            if (primaryIcon != 0) {
                MiuixIconButton(primaryIcon, primaryLabel.orEmpty()) { primaryAction?.run() }
            } else if (!primaryLabel.isNullOrBlank()) {
                TextAction(primaryLabel.orEmpty()) { primaryAction?.run() }
            }
            if (!hasBack && heading == "音乐") {
                val revision = AccountStore.revision
                val identity = remember(revision) { AccountStore.identity(context) }
                Box(
                    Modifier.size(48.dp)
                        .semantics { contentDescription = "打开个人中心" }
                        .clickable(onClickLabel = "打开个人中心") {
                            (context as? MainActivity)?.openAccountCentre()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AccountAvatar(identity, size = 32.dp)
                }
            }
        }
    }

    @JvmOverloads
    fun set(
        text: String,
        backVisible: Boolean,
        backAction: Runnable?,
        actionLabel: String?,
        actionAction: Runnable?,
        actionIconRes: Int = 0,
    ) {
        heading = text
        hasBack = backVisible
        this.backAction = backAction
        primaryLabel = actionLabel
        primaryAction = actionAction
        primaryIcon = actionIconRes
        secondaryIcon = 0
        description = null
        updateContentInsets()
    }

    fun subtitle(text: String?) {
        description = text
        updateContentInsets()
    }

    fun secondary(resource: Int, label: String, onClick: Runnable?) {
        secondaryIcon = resource
        secondaryLabel = label
        secondaryAction = onClick
    }

    fun attachScroll(source: ScrollView?, headingSpacer: Boolean = false) {
        scroll = source
        this.headingSpacer = headingSpacer
        source?.isVerticalScrollBarEnabled = false
        source?.let {
            it.setPadding(
                it.paddingLeft,
                it.paddingTop,
                it.paddingRight,
                UI.dp(PageInsets.bottom.value),
            )
            it.clipToPadding = false
        }
        updateContentInsets()
    }

    private fun updateContentInsets() {
        val root = !hasBack && heading in setOf("音乐", "我的")
        visibility = if (root) GONE else VISIBLE
        val extra =
            if (description.isNullOrBlank()) 0
            else ceil(17 * context.resources.configuration.fontScale).toInt()
        val height = if (root) 0 else UI.dp(UI.topBarHeight(context) + extra)
        layoutParams?.let {
            it.height = height
            layoutParams = it
        }
        // The header's height belongs to the viewport, never to scrollable content.
        (scroll?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = height
            scroll?.layoutParams = it
        }
        scroll?.getChildAt(0)?.let { body ->
            if (headingSpacer) {
                (body as? ViewGroup)?.getChildAt(0)?.let { spacer ->
                    spacer.layoutParams = spacer.layoutParams.apply { this.height = UI.dp(8) }
                }
            } else {
                body.setPadding(body.paddingLeft, UI.dp(12), body.paddingRight, body.paddingBottom)
            }
        }
    }

    companion object {
        @JvmStatic
        fun params(context: Context) =
            FrameLayout.LayoutParams(-1, UI.dp(UI.topBarHeight(context))).apply {
                gravity = Gravity.TOP
            }
    }
}
