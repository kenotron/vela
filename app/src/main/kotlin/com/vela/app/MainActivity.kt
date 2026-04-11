package com.vela.app

    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.enableEdgeToEdge
    import com.vela.app.ui.conversation.ConversationScreen
    import com.vela.app.ui.theme.VelaTheme
    import dagger.hilt.android.AndroidEntryPoint

    @AndroidEntryPoint
    class MainActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            setContent {
                VelaTheme {
                    ConversationScreen()
                }
            }
        }
    }
    