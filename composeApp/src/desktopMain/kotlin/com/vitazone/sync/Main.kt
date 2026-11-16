package com.vitazone.sync

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vitazone.sync.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vitazone: 1С → Google Sheets",
        state = rememberWindowState(width = 980.dp, height = 900.dp),
    ) {
        App(SettingsStore())
    }
}
