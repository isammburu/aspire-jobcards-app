package com.aspirelending.jobcards.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.aspirelending.jobcards.AppViewModel

@Composable
fun Shell(userName: String, vm: AppViewModel, onScan: () -> Unit, onConnect: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Home") })
            NavigationBarItem(false, onScan, { Icon(Icons.Default.CameraAlt, null) }, label = { Text("Scan") })
            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.History, null) }, label = { Text("History") })
            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.MoreHoriz, null) }, label = { Text("More") })
        }
    }) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(userName, vm, onScan, onConnect)
                1 -> HistoryScreen(vm)
                else -> SettingsScreen(vm, onConnect)
            }
        }
    }
}
