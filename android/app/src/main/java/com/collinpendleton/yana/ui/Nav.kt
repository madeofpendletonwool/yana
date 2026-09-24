package com.collinpendleton.yana.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.collinpendleton.yana.YanaApp
import com.collinpendleton.yana.data.normalizeServerUrl
import com.collinpendleton.yana.ui.screens.NoteScreen
import com.collinpendleton.yana.ui.screens.ServerScreen
import com.collinpendleton.yana.ui.screens.SettingsScreen
import com.collinpendleton.yana.ui.screens.SignInScreen
import com.collinpendleton.yana.ui.screens.SpaceScreen
import com.collinpendleton.yana.ui.screens.SpacesScreen
import kotlinx.serialization.Serializable

@Serializable data object ServerRoute
@Serializable data class SignInRoute(val server: String, val setup: Boolean)
@Serializable data object SpacesRoute
@Serializable data class SpaceRoute(val name: String, val label: String)
@Serializable data class NoteRoute(val id: String, val title: String)
@Serializable data object SettingsRoute

@Composable
fun YanaNavHost(app: YanaApp, nav: NavHostController = rememberNavController()) {
    val client = app.client
    val session by client.session.collectAsStateWithLifecycle()
    val start: Any = remember { if (session != null) SpacesRoute else ServerRoute }

    // A session that ends underneath the shell (revoked on the web, signed
    // out, expired) sends the app back to the start with nothing behind it.
    LaunchedEffect(session == null) {
        if (session != null) return@LaunchedEffect
        val here = nav.currentBackStackEntry?.destination?.route ?: return@LaunchedEffect
        if (here.contains("ServerRoute") || here.contains("SignInRoute")) return@LaunchedEffect
        nav.navigate(ServerRoute) { popUpTo(0) { inclusive = true } }
        client.lastServer?.let(::normalizeServerUrl)?.let { nav.navigate(SignInRoute(it.toString(), setup = false)) }
    }

    NavHost(nav, startDestination = start) {
        composable<ServerRoute> {
            ServerScreen(client) { server, setup -> nav.navigate(SignInRoute(server, setup)) }
        }
        composable<SignInRoute> { entry ->
            val r = entry.toRoute<SignInRoute>()
            SignInScreen(
                client = client,
                server = r.server,
                setup = r.setup,
                onBack = { if (!nav.popBackStack()) nav.navigate(ServerRoute) { popUpTo(0) { inclusive = true } } },
                onSignedIn = { nav.navigate(SpacesRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<SpacesRoute> {
            SpacesScreen(
                client = client,
                onSpace = { nav.navigate(SpaceRoute(it.name, it.displayName)) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<SpaceRoute> { entry ->
            val r = entry.toRoute<SpaceRoute>()
            SpaceScreen(
                client = client,
                space = r.name,
                label = r.label,
                onBack = { nav.popBackStack() },
                onNote = { id, title -> nav.navigate(NoteRoute(id, title)) },
            )
        }
        composable<NoteRoute> { entry ->
            val r = entry.toRoute<NoteRoute>()
            NoteScreen(client = client, id = r.id, title = r.title, onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(app = app, onBack = { nav.popBackStack() })
        }
    }
}
