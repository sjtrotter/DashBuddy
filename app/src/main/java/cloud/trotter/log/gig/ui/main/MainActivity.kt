package cloud.trotter.log.gig.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.ui.main.navigation.MainNavActions
import cloud.trotter.log.gig.ui.main.navigation.Screen
import cloud.trotter.log.gig.ui.main.navigation.TopLevel
import cloud.trotter.log.gig.ui.main.navigation.deliver
import cloud.trotter.log.gig.ui.main.navigation.routeTarget
import cloud.trotter.log.gig.ui.main.setup.consent.DebugEventReceiptLoading
import cloud.trotter.log.gig.ui.main.setup.consent.DebugEventReceiptShell
import cloud.trotter.log.gig.ui.main.setup.consent.EventReceiptConsentViewModel
import cloud.trotter.log.gig.ui.main.setup.consent.FrontDoorViewModel
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Deep-link target route pushed by an external launcher (the bubble's "Vehicle" just-in-time
     * action, #693). Held in [MainShellViewModel]'s SavedStateHandle (#1151 review MM4) so a route
     * waiting behind the debug block survives recreation; delivered once, then cleared.
     */
    private val shell: MainShellViewModel by viewModels()

    /**
     * #1151 review LL6 — the front door's deferral generation lives here (activity-scoped, survives
     * rotation); [onStop] bumps it only on a REAL departure from the foreground.
     */
    private val frontDoor: FrontDoorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Consume-once (#693 review F2b): getIntent() is sticky across recreation — without
        // removing the extra, a rotation re-reads it in the new instance's onCreate and pushes a
        // duplicate destination onto the restored back stack.
        shell.offer(intent?.getStringExtra(EXTRA_ROUTE))
        intent?.removeExtra(EXTRA_ROUTE)

        setContent {
            GigLogTheme {
                val navController = rememberNavController()

                // #1151 review LL4 — the debug decline gates the whole SHELL, not one screen: while
                // `DEBUG && DECLINED` (the pure buildEventReceiptConsentState) the block replaces
                // EVERY destination, deep links included (a pending route waits until unblocked).
                // The one allowed way forward is the Automation & Consent screen, rendered inside
                // the block host, plus Exit. The bubble HUD is a separate service and keeps running.
                val eventReceiptViewModel: EventReceiptConsentViewModel = hiltViewModel()
                val eventReceipt by eventReceiptViewModel.uiState.collectAsStateWithLifecycle()
                // MM3: in DEBUG the shell fails CLOSED until the value is read (release: never).
                if (eventReceipt.loading) {
                    DebugEventReceiptLoading(onExit = { finishAffinity() })
                    return@GigLogTheme
                }
                if (eventReceipt.blocked) {
                    DebugEventReceiptShell(onExit = { finishAffinity() })
                    return@GigLogTheme
                }

                // Deliver a deep-link route once, then clear it (#693 vehicle action). This is
                // composed only AFTER the loading/blocked early returns above — composition order
                // is the one gate, so a route waits (parked, MM4) behind the debug gate (NN7).
                val actions = remember(navController) { MainNavActions(navController) }
                val route by shell.pendingRoute.collectAsStateWithLifecycle()
                LaunchedEffect(route) {
                    shell.deliver {
                        // #693 review F3: MainActivity is exported (launcher) — a forged extra
                        // carrying a non-route string would crash navigate() with
                        // IllegalArgumentException. Fail closed: navigate only to known routes.
                        // #1447: at a tab root the plan opens in Playbook fresh; off a root a link
                        // only pushes over the current screen.
                        routeTarget(it)?.let(navController::deliver)
                            ?: Timber.tag("Main").w("Dropped unknown deep-link route (#693)")
                    }
                }

                val backStackEntry by navController.currentBackStackEntryAsState()
                MainScaffold(
                    selectedTab = TopLevel.fromRoute(backStackEntry?.destination?.route),
                    onSelectTab = actions::selectTab,
                ) { modifier ->
                    MainNavHost(navController = navController, actions = actions, modifier = modifier)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) frontDoor.onBackgrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shell.offer(intent.getStringExtra(EXTRA_ROUTE))
        intent.removeExtra(EXTRA_ROUTE)
    }

    companion object {
        /** Extra carrying a [Screen.route] to open on launch (the bubble's deep-link actions, #693). */
        const val EXTRA_ROUTE = "cloud.trotter.log.gig.extra.ROUTE"

        /**
         * Build an [Intent] that opens [MainActivity] on [route]. Used by the bubble overlay (a
         * separate task) to deep-link into settings, so it carries [Intent.FLAG_ACTIVITY_NEW_TASK].
         */
        fun routeIntent(context: Context, route: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                // SINGLE_TOP (#693 review F2a): without it, standard-launchMode + CLEAR_TOP
                // DESTROYS an open MainActivity and delivers to a fresh instance's onCreate —
                // onNewIntent never fires and the user's back stack is lost on every Vehicle tap.
                // With it, the single-activity app's task-top instance receives onNewIntent live.
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(EXTRA_ROUTE, route)
            }
    }
}
