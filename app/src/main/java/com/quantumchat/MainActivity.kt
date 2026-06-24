package com.quantumchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.quantumchat.feature.chat.ChatScreen
import com.quantumchat.feature.contacts.ContactsScreen
import com.quantumchat.feature.settings.SettingsScreen
import com.quantumchat.core.networking.TorManager
import com.quantumchat.ui.theme.QuantumTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
object ContactsListDestination

@Serializable
data class ChatScreenDestination(
    val contactId: String,
    val contactName: String
)

@Serializable
object SettingsDestination

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var torManager: TorManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Auto-start Tor on startup if enabled and Orbot is installed
        if (torManager.isAutoStartEnabled && torManager.isOrbotInstalled()) {
            torManager.startTor()
        }
        
        enableEdgeToEdge()
        setContent {
            QuantumTheme {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Set up the type-safe Compose Navigation Host
                    NavHost(
                        navController = navController,
                        startDestination = ContactsListDestination,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable<ContactsListDestination> {
                            ContactsScreen(
                                onContactClick = { contact ->
                                    navController.navigate(
                                        ChatScreenDestination(
                                            contactId = contact.id,
                                            contactName = contact.name
                                        )
                                    )
                                },
                                onNavigateToSettings = {
                                    navController.navigate(SettingsDestination)
                                }
                            )
                        }
                        composable<ChatScreenDestination> { backStackEntry ->
                            val chatArgs = backStackEntry.toRoute<ChatScreenDestination>()
                            ChatScreen(
                                contactId = chatArgs.contactId,
                                contactName = chatArgs.contactName,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                        composable<SettingsDestination> {
                            SettingsScreen(
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
