package com.saascom.caller.call

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.saascom.caller.ui.components.AfterCallBar
import com.saascom.caller.ui.components.CallerCard
import com.saascom.caller.ui.theme.CallerTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** The small bar shown once the call has ended. */
data class AfterCallPrompt(
    val title: String,
    val primary: String,
    val secondary: String,
    val onPrimary: () -> Unit,
    val onSecondary: () -> Unit,
)

/** What the overlay window shows. */
private sealed interface OverlayContent {
    data class Card(val state: CardState, val onClose: () -> Unit) : OverlayContent
    data class Prompt(val prompt: AfterCallPrompt) : OverlayContent
}

/**
 * Draws over the call screen (TYPE_APPLICATION_OVERLAY, needs "display over other apps"):
 * the caller card while it rings, then the after-call bar. One window at a time; showing
 * something else on a visible window only swaps its content.
 */
object OverlayCard {
    private const val TOP_OFFSET_DP = 240

    private val content = MutableStateFlow<OverlayContent?>(null)
    private var view: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, card: CardState, onClose: () -> Unit) = attach(context, OverlayContent.Card(card, onClose))

    fun showPrompt(context: Context, prompt: AfterCallPrompt) = attach(context, OverlayContent.Prompt(prompt))

    fun hide(context: Context) {
        val current = view ?: return
        runCatching { context.getSystemService(WindowManager::class.java).removeView(current) }
        owner?.destroy()
        view = null
        owner = null
        content.value = null
    }

    private fun attach(context: Context, next: OverlayContent) {
        content.value = next
        if (view != null) return

        val windowManager = context.getSystemService(WindowManager::class.java)
        val lifecycleOwner = OverlayLifecycleOwner()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                val current by content.collectAsState()
                CallerTheme {
                    when (val c = current) {
                        is OverlayContent.Card -> CallerCard(
                            state = c.state,
                            onClose = c.onClose,
                            onOpenUrl = { url -> openUrl(context, url) },
                        )
                        is OverlayContent.Prompt -> AfterCallBar(c.prompt)
                        null -> Unit
                    }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Below the heads-up "incoming call" banner (unlocked phone), which is drawn above
            // overlays, and above the answer buttons of the full-screen call UI (locked phone).
            gravity = Gravity.TOP
            y = (TOP_OFFSET_DP * context.resources.displayMetrics.density).toInt()
        }

        windowManager.addView(composeView, params)
        lifecycleOwner.resume()
        view = composeView
        owner = lifecycleOwner
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No browser / WhatsApp installed: nothing to open.
        }
    }
}

/**
 * A ComposeView outside an Activity needs a lifecycle and a saved-state registry of its own,
 * otherwise Compose crashes when the view is attached.
 */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    init {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
