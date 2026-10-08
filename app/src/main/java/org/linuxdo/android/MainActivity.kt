package org.linuxdo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.linuxdo.android.ui.AppShell
import org.linuxdo.android.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val browser = (application as App).container.browser
        setContent {
            AppShell(viewModel, browser)
        }
    }
}
