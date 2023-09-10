package com.timower.variosync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import java.net.NetworkInterface


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsableScaffold(
    expanded: Boolean,
    collapseContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    topBarColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = contentWindowInsets
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(top = paddingValues.calculateTopPadding())
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (expanded) {
                Surface(color = topBarColor) {
                    collapseContent()
                }
            }

            content(
                PaddingValues(
                    top = 0.dp,
                    start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                    end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = paddingValues.calculateBottomPadding()
                )
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ConfirmTextField(value: String, onValueChanged: (String) -> Unit, onSearchClicked: () -> Unit) {
    var tempValue by rememberSaveable(key = value) { mutableStateOf(value) }

    OutlinedTextField(value = tempValue, onValueChange = {
        tempValue = it
    }, singleLine = true, modifier = Modifier.width(228.dp), leadingIcon = {
        IconButton(onClick = onSearchClicked) {
            Icon(Icons.Filled.Search, contentDescription = null)
        }
    }, trailingIcon = {
        IconButton(onClick = { onValueChanged(tempValue) }) {
            Icon(Icons.Filled.Check, contentDescription = null)
        }
    })
}


@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun InterfaceDropdown(
    selected: NetworkInterface,
    items: List<NetworkInterface>,
    onSelectionChange: (NetworkInterface) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            // The `menuAnchor` modifier must be passed to the text field for correctness.
            readOnly = true,
            value = selected.name,
            onValueChange = {},
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
            colors = ExposedDropdownMenuDefaults.textFieldColors(),
            singleLine = true,
            modifier = Modifier
                .menuAnchor()
                .widthIn(1.dp, 150.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { selectionOption ->
                DropdownMenuItem(
                    text = { Text(selectionOption.name) },
                    onClick = {
                        onSelectionChange(selectionOption)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
fun ErrorBar(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview
@Composable
fun CancelableProgress(
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    message: String? = null,
    progress: Float? = null
) {
    Surface(
        modifier = modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)/*.fillMaxWidth()*/,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                val centerModifier = Modifier.align(Alignment.Center)
                if (progress == null) {
                    CircularProgressIndicator(modifier = centerModifier)
                } else {
                    CircularProgressIndicator(
                        modifier = centerModifier, progress = progress
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Sharp.Close, contentDescription = null)
                }
            }

            if (message != null) {
                Text(text = message)
            }
        }
    }
}


@Preview(showBackground = false)
@Composable
fun TestSyncPlanView() {
    val docs = listOf(
        Document("map1.xcm", false), Document("map2.xcm", false), Document(
            "tasks", true, listOf(
                Document("task1.tsk", false), Document(
                    "dir2", true, listOf(
                        Document("task2.tsk", false)

                    )
                )
            )
        )
    )

    fun getDocs(doc: Document): List<Document> {
        return doc.children.flatMap { getDocs(it) }.plus(doc)
    }

    val allDocs = docs.flatMap { getDocs(it) }

    var plan: SyncPlan by remember {
        mutableStateOf(SyncPlan(docs, allDocs.associateWith { SyncAction.Push }))
    }

    SyncPlanView(plan = plan) { doc, action ->
        plan = SyncPlan(plan.localFiles, plan.plan.plus(doc to action))
    }
}


@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SyncPlanView(plan: SyncPlan, onPlanChange: (Document, SyncAction) -> Unit) {
    @Composable
    fun recursePlan(docs: List<Document>, depth: Int = 0) {
        docs.mapNotNull { plan.plan[it]?.to(it) }.sortedBy { it.second.name }
            .sortedBy { !it.second.isDir }.forEach {

                var expanded by rememberSaveable { mutableStateOf(true) }

                var modifier = Modifier.padding(start = depth * 16.dp)
                if (it.second.isDir) {
                    modifier = modifier.clickable { expanded = !expanded }
                }

                ListItem(headlineText = { Text(text = it.second.name) }, leadingContent = {
                    Icon(
                        if (it.second.isDir) {
                            if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight
                        } else {
                            Icons.Filled.Place
                        }, contentDescription = ""
                    )
                }, trailingContent = {
                    Checkbox(checked = it.first == SyncAction.Push,
                        enabled = !it.second.isDir && it.first != SyncAction.Ignore,
                        onCheckedChange = { checked ->
                            val newAction = if (checked) SyncAction.Push else SyncAction.Skip
                            onPlanChange(it.second, newAction)
                        })
                }, modifier = modifier
                )

                if (expanded) {
                    recursePlan(it.second.children, depth + 1)
                }
            }
    }


    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        recursePlan(docs = plan.localFiles)
    }
}