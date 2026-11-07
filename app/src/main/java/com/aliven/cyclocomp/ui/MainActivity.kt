package com.aliven.cyclocomp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aliven.cyclocomp.recording.RideRecorder
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideRecorder.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }
}

private sealed interface Screen {
    data object Ride : Screen
    data object History : Screen
    data object EditPages : Screen
    data object Sync : Screen
    data object OcrDebug : Screen
    data object Screenshot : Screen
    data object Progress : Screen
    data object Plan : Screen
    data object Update : Screen
    data object Account : Screen
    data object Routes : Screen
    data class Detail(val path: String) : Screen
}

@Composable
private fun AppNav() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Ride) }
    var pages by remember { mutableStateOf(PageStore.load(context)) }
    var rides by remember { mutableStateOf(RideRecorder.rides()) }

    BackHandler(enabled = screen != Screen.Ride) {
        screen = when (screen) {
            is Screen.Detail, Screen.Progress -> Screen.History
            Screen.Plan -> Screen.Progress
            Screen.OcrDebug, Screen.Screenshot, Screen.Update, Screen.Account -> Screen.Sync
            else -> Screen.Ride
        }
    }

    when (val s = screen) {
        Screen.Ride -> RideScreen(
            pages = pages,
            onOpenHistory = {
                rides = RideRecorder.rides()
                screen = Screen.History
            },
            onEditPages = { screen = Screen.EditPages },
            onOpenSync = { screen = Screen.Sync },
            onOpenRoutes = { screen = Screen.Routes },
        )
        Screen.Sync -> SyncScreen(
            onBack = { screen = Screen.Ride },
            onOpenOcrDebug = { screen = Screen.OcrDebug },
            onOpenScreenshot = { screen = Screen.Screenshot },
            onOpenUpdate = { screen = Screen.Update },
            onOpenAccount = { screen = Screen.Account },
        )
        Screen.Account -> AccountScreen(onBack = { screen = Screen.Sync })
        Screen.Routes -> RoutesScreen(
            onBack = { screen = Screen.Ride },
            onFollowing = { screen = Screen.Ride },
        )
        Screen.Update -> UpdateScreen(onBack = { screen = Screen.Sync })
        Screen.OcrDebug -> OcrDebugScreen(onBack = { screen = Screen.Sync })
        Screen.Screenshot -> ScreenshotImportScreen(onBack = { screen = Screen.Sync })
        Screen.History -> HistoryScreen(
            rides = rides,
            onOpen = { screen = Screen.Detail(it.absolutePath) },
            onOpenProgress = { screen = Screen.Progress },
            onBack = { screen = Screen.Ride },
        )
        Screen.Progress -> ProgressScreen(
            onBack = { screen = Screen.History },
            onOpenPlan = { screen = Screen.Plan },
        )
        Screen.Plan -> PlanScreen(onBack = { screen = Screen.Progress })
        Screen.EditPages -> PagesEditor(
            initial = pages,
            onSave = {
                PageStore.save(context, it)
                pages = it
                screen = Screen.Ride
            },
            onCancel = { screen = Screen.Ride },
        )
        is Screen.Detail -> DetailScreen(
            file = File(s.path),
            onBack = { screen = Screen.History },
            onDeleted = {
                rides = RideRecorder.rides()
                screen = Screen.History
            },
        )
    }
}
