package com.timower.variosync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.timower.variosync.ui.theme.VarioSyncTheme

const val TAG = "XCSync"

const val DIR_URI_PREF = "dirURI"

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private var localContentUri: Uri? = null
        get() {
            if (field != null) return field

            val pref = getPreferences(MODE_PRIVATE).getString(DIR_URI_PREF, null)
            return pref?.let { Uri.parse(it) }
        }
        set(value) {
            with(getPreferences(MODE_PRIVATE)?.edit()) {
                this?.putString(DIR_URI_PREF, value.toString())
                this?.apply()
            }
            field = value
        }

    @Composable
    private fun FilesView(
        contentUri: Uri, syncViewModel: XCSyncViewModel = viewModel()
    ) {
        var ipMenuOpen by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(syncViewModel.currentAddress, contentUri) {
            if (syncViewModel.syncPlan == null && syncViewModel.hasAddress()) {
                syncViewModel.refresh(contentResolver, contentUri)
            }
        }

        CollapsableScaffold(
            expanded = ipMenuOpen,
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                if (syncViewModel.canExecutePlan()) {
                    FloatingActionButton(modifier = Modifier.systemBarsPadding()/*.navigationBarsPadding()*/,
                        onClick = {
                            syncViewModel.executePlan(contentResolver)
                        }) {
                        Icon(Icons.Filled.Send, contentDescription = null)
                    }
                }
            },
            topBar = {
                TopAppBar(title = { Text(text = syncViewModel.currentAddress ?: "No address") },
                    colors = TopAppBarDefaults.smallTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    actions = {
                        IconButton(onClick = {
                            syncViewModel.refresh(contentResolver, contentUri)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                        }

                        IconButton(onClick = { ipMenuOpen = !ipMenuOpen }) {
                            Icon(
                                if (ipMenuOpen) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                }, contentDescription = null
                            )
                        }
                    })
            },
            collapseContent = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        InterfaceDropdown(syncViewModel.selectedInterface,
                            syncViewModel.interfaces,
                            onSelectionChange = { iface ->
                                syncViewModel.selectedInterface = iface
                            })
                        ConfirmTextField(value = syncViewModel.currentAddress ?: "",
                            onValueChanged = { syncViewModel.currentAddress = it },
                            onSearchClicked = { syncViewModel.findIP() })
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(top = paddingValues.calculateTopPadding())
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.padding(
                        paddingValues
                    )
                ) {
                    if (syncViewModel.syncPlan != null) {
                        SyncPlanView(plan = syncViewModel.syncPlan!!) { doc, action ->
                            Log.d(TAG, "Update for ${doc.name} to $action")
                            syncViewModel.updatePlan(doc, action)
                        }
                    }

                    if (syncViewModel.jobInProgress()) {
                        CancelableProgress(modifier = Modifier.align(Alignment.TopCenter),
                            message = syncViewModel.syncProgress?.message,
                            progress = syncViewModel.syncProgress?.value,
                            onCancel = { syncViewModel.cancelJob() })
                    } else if (syncViewModel.errorMsg != null) {
                        ErrorBar(syncViewModel.errorMsg!!, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }

    @Composable
    private fun DialogHolder() {
        var contentUri by rememberSaveable { mutableStateOf(localContentUri) }

        val getDocumentAccess =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocumentTree(),
                onResult = { uri: Uri? ->
                    if (uri == null) {
                        return@rememberLauncherForActivityResult
                    }

                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )

                    localContentUri = uri
                    contentUri = uri
                })

        Log.d(TAG, "Content URI: $contentUri")
        if (contentUri == null) {
            AlertDialog(onDismissRequest = { },
                confirmButton = {
                    TextButton(onClick = { getDocumentAccess.launch(null) }) {
                        Text(text = "OK")
                    }
                },
                text = { Text(text = "Please select the xcsoar folder") },
                icon = { Icon(Icons.Filled.Info, contentDescription = "") })
        } else {
            FilesView(contentUri!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // enableEdgeToEdge()
        setContent {
            VarioSyncTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    DialogHolder()
                }
            }
        }
    }
}