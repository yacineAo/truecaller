package com.saascom.caller

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.saascom.caller.data.AppVersionDto
import com.saascom.caller.data.CallRecord
import com.saascom.caller.ui.AfterCallScreen
import com.saascom.caller.ui.AppUpdater
import com.saascom.caller.ui.LeadScreen
import com.saascom.caller.ui.LoginScreen
import com.saascom.caller.ui.NewLeadScreen
import com.saascom.caller.ui.OnboardingScreen
import com.saascom.caller.ui.RecentCallsScreen
import com.saascom.caller.ui.RequestScreen
import com.saascom.caller.ui.SearchScreen
import com.saascom.caller.ui.SettingsScreen
import com.saascom.caller.ui.UpdateDialog
import com.saascom.caller.ui.theme.CallerTheme
import com.saascom.caller.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Screen asked for by the after-call bar or a notification. */
    private val opened = MutableStateFlow<OpenTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) opened.value = AppIntents.parse(intent)
        setContent {
            CallerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(opened)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        AppIntents.parse(intent)?.let { opened.value = it }
    }
}

/** Pages shown above the tabs. */
private sealed interface Route {
    data object Tabs : Route
    data class Onboarding(val canGoBack: Boolean) : Route
    data class AfterCall(val callId: Long?, val phone: String) : Route
    data class NewLead(val phone: String, val callId: Long?) : Route
    data class Lead(val phone: String) : Route
    data class Request(val leadId: Long, val name: String) : Route
}

private fun OpenTarget.toRoute(): Route = when (this) {
    is OpenTarget.AfterCall -> Route.AfterCall(callId, phone)
    is OpenTarget.NewLead -> Route.NewLead(phone, callId)
    is OpenTarget.Lead -> Route.Lead(phone)
}

/** Keeps the page stack across rotation / process recreation as plain strings. */
private val RouteStackSaver = Saver<SnapshotStateList<Route>, List<String>>(
    save = { stack -> stack.map(::encode) },
    restore = { saved -> mutableStateListOf<Route>().apply { addAll(saved.mapNotNull(::decode)) } },
)

private fun encode(route: Route): String = when (route) {
    Route.Tabs -> "tabs"
    is Route.Onboarding -> "onboarding|${route.canGoBack}"
    is Route.AfterCall -> "after|${route.callId ?: ""}|${route.phone}"
    is Route.NewLead -> "new|${route.callId ?: ""}|${route.phone}"
    is Route.Lead -> "lead|${route.phone}"
    is Route.Request -> "request|${route.leadId}|${route.name}"
}

private fun decode(text: String): Route? {
    val parts = text.split("|", limit = 3)
    return when (parts[0]) {
        "tabs" -> Route.Tabs
        "onboarding" -> Route.Onboarding(parts.getOrNull(1) == "true")
        "after" -> Route.AfterCall(parts[1].toLongOrNull(), parts[2])
        "new" -> Route.NewLead(parts[2], parts[1].toLongOrNull())
        "lead" -> Route.Lead(parts[1])
        "request" -> Route.Request(parts[1].toLong(), parts[2])
        else -> null
    }
}

/** Logged out → login. Logged in → tabs, with setup first until the required permissions are granted. */
@Composable
private fun AppRoot(opened: MutableStateFlow<OpenTarget?>) {
    val context = LocalContext.current
    val user by Services.session.user.collectAsState()
    val target by opened.collectAsState()

    fun startStack() = mutableStateListOf<Route>(Route.Tabs).apply {
        if (!Permissions.isReady(context)) add(Route.Onboarding(canGoBack = false))
    }

    val stack = rememberSaveable(saver = RouteStackSaver) { startStack() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<AppVersionDto?>(null) }

    if (user == null) {
        LoginScreen(onLoggedIn = {
            stack.clear()
            stack.addAll(startStack())
            tab = 0
        })
        return
    }

    // A tap on the after-call bar or a notification opens its page on top.
    LaunchedEffect(target) {
        target?.let {
            stack.add(it.toRoute())
            opened.value = null
        }
    }

    // At most once a day: is there a newer APK on the CRM?
    LaunchedEffect(Unit) { update = runCatching { AppUpdater.check() }.getOrNull() }

    fun push(route: Route) {
        stack.add(route)
    }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun openCall(call: CallRecord) = push(
        when {
            call.leadId == null -> Route.NewLead(call.phone, call.id)
            call.answered && !call.logged -> Route.AfterCall(call.id, call.phone)
            else -> Route.Lead(call.phone)
        },
    )

    val route = stack.lastOrNull() ?: Route.Tabs
    val canPop = stack.size > 1 && !(route is Route.Onboarding && !route.canGoBack && !Permissions.isReady(context))
    BackHandler(enabled = canPop) { pop() }

    when (route) {
        Route.Tabs -> Tabs(
            tab = tab,
            onTab = { tab = it },
            onOpenCall = ::openCall,
            onOpenLead = { push(Route.Lead(it)) },
            onOpenOnboarding = { push(Route.Onboarding(canGoBack = true)) },
            onUpdateFound = { update = it },
        )
        is Route.Onboarding -> OnboardingScreen(
            onDone = ::pop,
            onBack = if (canPop) ::pop else null,
        )
        is Route.AfterCall -> AfterCallScreen(
            callId = route.callId,
            phone = route.phone,
            onBack = ::pop,
            onDone = ::pop,
            onOpenRequest = { id, name -> push(Route.Request(id, name)) },
            onOpenNewLead = { phone, callId -> push(Route.NewLead(phone, callId)) },
        )
        is Route.NewLead -> NewLeadScreen(
            phone = route.phone,
            callId = route.callId,
            onBack = ::pop,
            onDone = ::pop,
            onOpenLead = { phone ->
                pop()
                push(Route.Lead(phone))
            },
        )
        is Route.Lead -> LeadScreen(
            phone = route.phone,
            onBack = ::pop,
            onLogCall = { push(Route.AfterCall(null, it)) },
            onOpenRequest = { id, name -> push(Route.Request(id, name)) },
            onOpenNewLead = { push(Route.NewLead(it, null)) },
        )
        is Route.Request -> RequestScreen(leadId = route.leadId, leadName = route.name, onBack = ::pop, onDone = ::pop)
    }

    update?.let { UpdateDialog(it, onDismiss = { update = null }) }
}

@Composable
private fun Tabs(
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenCall: (CallRecord) -> Unit,
    onOpenLead: (String) -> Unit,
    onOpenOnboarding: () -> Unit,
    onUpdateFound: (AppVersionDto) -> Unit,
) {
    val items = listOf(
        R.string.tab_recent to R.drawable.ic_tab_recent,
        R.string.tab_search to R.drawable.ic_tab_search,
        R.string.tab_settings to R.drawable.ic_tab_settings,
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { onTab(index) },
                        icon = { Icon(painterResource(icon), contentDescription = null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).consumeWindowInsets(padding)
        Surface(modifier, color = MaterialTheme.colorScheme.background) {
            when (tab) {
                0 -> RecentCallsScreen(onOpenCall = onOpenCall, onOpenOnboarding = onOpenOnboarding)
                1 -> SearchScreen(onOpen = onOpenLead)
                else -> SettingsScreen(onOpenOnboarding = onOpenOnboarding, onUpdateFound = onUpdateFound)
            }
        }
    }
}
