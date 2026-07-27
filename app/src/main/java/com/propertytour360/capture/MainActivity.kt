package com.propertytour360.capture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.propertytour360.capture.ui.MainViewModel
import com.propertytour360.capture.ui.PropertyTourApp
import com.propertytour360.capture.ui.PropertyTourTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PropertyTourTheme {
                val viewModel: MainViewModel = viewModel()
                PropertyTourApp(viewModel)
            }
        }
    }
}
