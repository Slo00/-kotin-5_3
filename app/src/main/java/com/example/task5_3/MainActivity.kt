package com.example.task5_3

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.task5_3.ui.theme.Task5_3Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Модель и ViewModel ----------

data class Photo(val file: File) { val name: String get() = file.name }

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val authority = "${app.packageName}.fileprovider"
    private val picturesDir: File
        get() = getApplication<Application>()
            .getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(getApplication<Application>().filesDir, "Pictures").also { it.mkdirs() }

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())
    val photos = _photos.asStateFlow()

    private var pendingFile: File? = null

    init {
        _photos.value = picturesDir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { Photo(it) }
            .orEmpty()
    }

    fun preparePhotoUri(): Uri {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(picturesDir.also { it.mkdirs() }, "IMG_$ts.jpg")
        pendingFile = file
        return FileProvider.getUriForFile(getApplication(), authority, file)
    }

    fun onCaptured(success: Boolean) {
        val file = pendingFile ?: return
        pendingFile = null
        if (success && file.exists() && file.length() > 0) {
            _photos.value = listOf(Photo(file)) + _photos.value
        } else file.delete()
    }

    fun export(photo: Photo, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { exportToMediaStore(photo.file) }
            onResult(ok)
        }
    }

    private fun exportToMediaStore(file: File): Boolean {
        if (!file.exists()) return false
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Task5_3")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return runCatching {
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out); file.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrElse { resolver.delete(uri, null, null); false }
    }
}

// ---------- Activity и навигация ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Task5_3Theme {
                var selected by remember { mutableStateOf<Photo?>(null) }
                val current = selected
                if (current == null) {
                    GalleryScreen(onPhotoClick = { selected = it })
                } else {
                    BackHandler { selected = null }
                    PhotoViewerScreen(photo = current, onBack = { selected = null })
                }
            }
        }
    }
}

// ---------- Главный экран: сетка фото ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onPhotoClick: (Photo) -> Unit,
    vm: GalleryViewModel = viewModel()
) {
    val context = LocalContext.current
    val photos by vm.photos.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { vm.onCaptured(it) }

    val askCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) takePicture.launch(vm.preparePhotoUri())
        else scope.launch { snackbar.showSnackbar(context.getString(R.string.camera_permission_denied)) }
    }

    val launchCamera: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) takePicture.launch(vm.preparePhotoUri())
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    val onExport: (Photo) -> Unit = { photo ->
        vm.export(photo) { ok ->
            scope.launch {
                snackbar.showSnackbar(
                    context.getString(if (ok) R.string.export_success else R.string.export_failed)
                )
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            if (photos.isNotEmpty()) FloatingActionButton(onClick = launchCamera) {
                Icon(Icons.Default.Add, stringResource(R.string.take_photo))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (photos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.empty_gallery),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = launchCamera) { Text(stringResource(R.string.take_first_photo)) }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(photos, key = { it.name }) { photo ->
                    PhotoCell(photo, onClick = { onPhotoClick(photo) }, onExport = { onExport(photo) })
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(photo: Photo, onClick: () -> Unit, onExport: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small),
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(
                model = photo.file,
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_actions), tint = Color.White)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_to_gallery)) },
                        onClick = { menuExpanded = false; onExport() }
                    )
                }
            }
        }
    }
}

// ---------- Экран просмотра фото ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    photo: Photo,
    onBack: () -> Unit,
    vm: GalleryViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(photo.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.export(photo) { ok ->
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(if (ok) R.string.export_success else R.string.export_failed)
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Default.Save, stringResource(R.string.export_to_gallery))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = photo.file,
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
