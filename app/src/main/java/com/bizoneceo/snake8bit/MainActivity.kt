package com.bizoneceo.snake8bit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.bizoneceo.snake8bit.ui.SnakeGameScreen
import com.bizoneceo.snake8bit.ui.theme.Snake8BitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Snake8BitTheme {
                SnakeGameScreen()
            }
        }
    }
}
