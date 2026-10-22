package com.bizoneceo.nardy

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.bizoneceo.nardy.platform.NardyViewModel
import com.bizoneceo.nardy.platform.toFoldInfo
import com.bizoneceo.nardy.ui.screen.FoldInfo
import com.bizoneceo.nardy.ui.screen.NardyApp
import androidx.window.layout.WindowInfoTracker

class MainActivity : ComponentActivity() {
    private val vm: NardyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A board game is mostly "thinking time": don't let the screen dim mid-game.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        onBackPressedDispatcher.addCallback(this) {
            if (!vm.model.back()) finish()
        }

        val tracker = WindowInfoTracker.getOrCreate(this)
        setContent {
            val fold by produceState<FoldInfo?>(initialValue = null) {
                tracker.windowLayoutInfo(this@MainActivity).collect { value = it.toFoldInfo() }
            }
            NardyApp(vm.model, fold)
        }
    }
}
