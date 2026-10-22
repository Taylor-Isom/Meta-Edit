package com.example.metaedit

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.metaedit.ui.editor.EditorScreen
import com.example.metaedit.ui.editor.EditorViewModel
import com.example.metaedit.ui.library.LibraryScreen
import com.example.metaedit.ui.library.LibraryViewModel
import com.example.metaedit.ui.theme.MetaEditTheme

class MainActivity : ComponentActivity() {
    /** Files handed to us by other apps ("Open with" / "Share"). */
    private val incoming = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MetaEditTheme {
                MetaEditRoot(incoming)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) incoming.value = uris
    }
}

/** Survives rotation but is cleared whenever the editor closes, so reopening a file starts fresh. */
class EditorStoreHolder : ViewModel() {
    val store = ViewModelStore()
    override fun onCleared() = store.clear()
}

@Composable
private fun MetaEditRoot(incoming: MutableState<List<Uri>?>) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    var editing by rememberSaveable { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(incoming.value) {
        incoming.value?.let { uris ->
            editing = uris.map(Uri::toString)
            incoming.value = null
        }
    }

    val libraryViewModel: LibraryViewModel = viewModel()
    val editorStores: EditorStoreHolder = viewModel()
    val editorOwner = remember(editorStores) {
        object : ViewModelStoreOwner {
            override val viewModelStore get() = editorStores.store
        }
    }

    val openDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            // Keep access across restarts; ask for write too so saving needs no further prompt.
            val resolver = context.contentResolver
            runCatching {
                resolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.recoverCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (uris.isNotEmpty()) editing = uris.map(Uri::toString)
    }

    val current = editing
    if (current == null) {
        LibraryScreen(
            viewModel = libraryViewModel,
            onOpenFiles = { openDocuments.launch(arrayOf("audio/*", "video/*")) },
            onEdit = { uris -> editing = uris.map(Uri::toString) },
        )
    } else {
        val uris = remember(current) { current.map(Uri::parse) }
        val editorViewModel: EditorViewModel = viewModel(
            viewModelStoreOwner = editorOwner,
            key = current.joinToString("|"),
            factory = viewModelFactory { initializer { EditorViewModel(app, uris) } },
        )
        EditorScreen(editorViewModel) { saved ->
            editing = null
            editorStores.store.clear()
            if (saved) libraryViewModel.onEdited(uris)
        }
    }
}
