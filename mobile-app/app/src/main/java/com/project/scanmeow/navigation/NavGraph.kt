package com.project.scanmeow.navigation

import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.project.scanmeow.data.model.Document
import com.project.scanmeow.ui.screens.*
import com.project.scanmeow.viewmodel.AppViewModel

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val CONFIRM = "confirm"
    const val DOCUMENT = "document/{documentId}"
    const val BLUETOOTH_OFF = "bluetooth_off/{documentId}"
    const val SENDING = "sending/{documentId}"

    fun document(id: Long) = "document/$id"
    fun bluetoothOff(id: Long) = "bluetooth_off/$id"
    fun sending(id: Long) = "sending/$id"
}

@Composable
fun ScanMeowNavGraph(
    navController: NavHostController,
    viewModel: AppViewModel = viewModel()
) {
    val recentDocuments by viewModel.recentDocuments.collectAsState()
    val capturedImagePath by viewModel.capturedImagePath.collectAsState()
    val sendingProgress by viewModel.sendingProgress.collectAsState()

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                recentDocuments = recentDocuments,
                isBluetoothConnected = viewModel.isBluetoothEnabled(),
                onScanClick = { navController.navigate(Routes.SCANNER) },
                onDocumentClick = { doc -> navController.navigate(Routes.document(doc.id)) },
                onSeeAllClick = { navController.navigate(Routes.SCANNER) }
            )
        }

        composable(Routes.SCANNER) {
            // Watch for a captured image and navigate — runs on main thread via LaunchedEffect
            LaunchedEffect(capturedImagePath) {
                if (capturedImagePath != null) {
                    navController.navigate(Routes.CONFIRM)
                }
            }
            ScannerScreen(
                onImageCaptured = { path ->
                    viewModel.setCapturedImage(path)  // just store; LaunchedEffect above drives navigation
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.CONFIRM) {
            ConfirmScreen(
                imagePath = capturedImagePath,
                onRetake = {
                    viewModel.clearCapturedImage()
                    navController.popBackStack()
                },
                onConfirm = { name ->
                    val path = capturedImagePath ?: return@ConfirmScreen
                    viewModel.saveDocument(path, name) { docId ->
                        viewModel.clearCapturedImage()
                        navController.navigate(Routes.document(docId)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                }
            )
        }

        composable(
            route = Routes.DOCUMENT,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getLong("documentId") ?: return@composable
            var document by remember { mutableStateOf<Document?>(null) }
            LaunchedEffect(documentId) {
                document = viewModel.getDocumentById(documentId)
            }
            DocumentViewScreen(
                document = document,
                onBack = { navController.popBackStack() },
                onShare = { doc ->
                    if (viewModel.isBluetoothEnabled()) {
                        navController.navigate(Routes.sending(doc.id))
                    } else {
                        navController.navigate(Routes.bluetoothOff(doc.id))
                    }
                },
                onDelete = { doc ->
                    viewModel.deleteDocument(doc)
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Routes.BLUETOOTH_OFF,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getLong("documentId") ?: return@composable
            BluetoothOffScreen(
                onEnableBluetooth = {
                    navController.navigate(Routes.sending(documentId)) {
                        popUpTo(Routes.bluetoothOff(documentId)) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.SENDING,
            arguments = listOf(navArgument("documentId") { type = NavType.LongType })
        ) { backStackEntry ->
            val documentId = backStackEntry.arguments?.getLong("documentId") ?: return@composable
            var document by remember { mutableStateOf<Document?>(null) }
            LaunchedEffect(documentId) {
                document = viewModel.getDocumentById(documentId)
                viewModel.simulateSending {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            }
            SendingScreen(
                document = document,
                progress = sendingProgress,
                onCancel = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
    }
}
