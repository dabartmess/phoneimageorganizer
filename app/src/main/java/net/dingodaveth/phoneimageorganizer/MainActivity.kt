package net.dingodaveth.phonefileorganizer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.example.phonefileorganizer.BuildConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
  private val scope = CoroutineScope(Dispatchers.Main)
  private val all = mutableListOf<FileItem>()
  private val treeUris = linkedSetOf<String>()
  private lateinit var status: TextView
  private lateinit var list: LinearLayout
  private lateinit var query: EditText
  private lateinit var type: Spinner
  private lateinit var source: Spinner
  private lateinit var sort: Spinner
  private lateinit var date: Spinner
  private lateinit var folder: EditText
  private var waitingForDropbox = false
  private var credential: DbxCredential? = null
  private var uploadedFiles = emptyList<FileItem>()

  private val grantMedia =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
  private val selectFolder =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        try {
          contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          )
        } catch (_: SecurityException) {
          try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          } catch (_: Exception) {
          }
        }
        treeUris.add(uri.toString())
        getSharedPreferences("settings", MODE_PRIVATE).edit()
          .putStringSet("folders", treeUris.toSet()).apply()
        refresh()
      }
    }
  private val deletion =
    registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        announce("Android confirmed media deletion"); refresh()
      } else announce("Media deletion canceled")
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    treeUris.addAll(
      getSharedPreferences("settings", MODE_PRIVATE).getStringSet(
        "folders",
        emptySet()
      ) ?: emptySet()
    )
    buildScreen()
    refresh()
  }

  override fun onResume() {
    super.onResume()
    if (waitingForDropbox) {
      waitingForDropbox = false
      val received = Auth.getDbxCredential()
      if (received != null) {
        credential = received; announce("Dropbox connected for this session")
      } else announce("Dropbox authorization not completed")
    }
  }

  private fun buildScreen() {
    val outer =
      LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12) }
    val heading = TextView(this).apply { text = "File Investigator"; textSize = 23f }
    outer.addView(heading)
    status = TextView(this).apply { text = "Choose access, then scan."; textSize = 13f }
    outer.addView(status)
    val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    controls.addView(
      rowButtons(
        "Media permissions" to { requestMedia() },
        "Add folder" to { selectFolder.launch(null) },
        "Scan" to { refresh() })
    )
    query =
      EditText(this).apply { hint = "Search filename, path, app or AI label"; setSingleLine(true) }
    query.addTextChangedListener(object : android.text.TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        render()
      }

      override fun afterTextChanged(s: android.text.Editable?) {}
    })
    controls.addView(query)
    type = spinner(listOf("All types", "Images", "Videos", "Audio", "Documents", "Other"))
    date = spinner(listOf("Any date", "Past 7 days", "Past 30 days", "Past year"))
    sort = spinner(
      listOf(
        "Size: largest",
        "Size: smallest",
        "Date: newest",
        "Date: oldest",
        "Name",
        "Source app"
      )
    )
    source = spinner(listOf("All sources"))
    controls.addView(lineWith("Type", type)); controls.addView(lineWith("Date", date))
    controls.addView(lineWith("Sort", sort)); controls.addView(lineWith("Source", source))
    listOf(type, date, sort, source).forEach { spinner ->
      spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        override fun onItemSelected(
          parent: android.widget.AdapterView<*>?,
          view: View?,
          position: Int,
          id: Long
        ) {
          render()
        }
      }
    }
    controls.addView(rowButtons("Select visible" to {
      visibleItems().forEach {
        it.selected = true
      }; render()
    }, "Clear selection" to { all.forEach { it.selected = false }; render() }))
    controls.addView(
      rowButtons(
        "AI label selected" to { labelImages() },
        "Delete selected" to { confirmDelete() })
    )
    controls.addView(
      rowButtons(
        "Connect Dropbox" to { connectDropbox() },
        "Upload / Move" to { uploadDialog() })
    )
    folder = EditText(this).apply {
      setSingleLine(true); hint = "/PhoneArchive"; inputType = InputType.TYPE_CLASS_TEXT; setText(
      getSharedPreferences("settings", MODE_PRIVATE).getString("dropboxFolder", "/PhoneArchive")
    )
    }
    controls.addView(lineWith("Dropbox folder", folder))
    outer.addView(controls)
    val scroller = ScrollView(this)
    list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    scroller.addView(list)
    outer.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
    setContentView(outer)
  }

  private fun spinner(values: List<String>): Spinner = Spinner(this).apply {
    adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
  }

  private fun lineWith(caption: String, control: View): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(
      TextView(this@MainActivity).apply { text = "$caption: "; textSize = 14f },
      LinearLayout.LayoutParams(0, -2, 1f)
    )
    addView(control, LinearLayout.LayoutParams(0, -2, 2f))
  }

  private fun rowButtons(vararg pairs: Pair<String, () -> Unit>): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    pairs.forEach { (title, action) ->
      addView(Button(this@MainActivity).apply {
        text = title; textSize = 11f; setOnClickListener { action() }
      }, LinearLayout.LayoutParams(0, -2, 1f))
    }
  }

  private fun announce(message: String) {
    status.text = message
  }

  private fun hasMediaPermission(): Boolean {
    val permissions = if (Build.VERSION.SDK_INT >= 33) listOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
      Manifest.permission.READ_MEDIA_AUDIO
    )
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    return permissions.any {
      ContextCompat.checkSelfPermission(
        this,
        it
      ) == PackageManager.PERMISSION_GRANTED
    }
  }

  private fun requestMedia() {
    val permissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
      Manifest.permission.READ_MEDIA_AUDIO,
      Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    else if (Build.VERSION.SDK_INT >= 33) arrayOf(
      Manifest.permission.READ_MEDIA_IMAGES,
      Manifest.permission.READ_MEDIA_VIDEO,
      Manifest.permission.READ_MEDIA_AUDIO
    )
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    grantMedia.launch(permissions)
  }

  private fun refresh() {
    announce("Scanning authorized media and folders…")
    scope.launch {
      val scanned = withContext(Dispatchers.IO) {
        FileScanner(this@MainActivity).scan(
          hasMediaPermission() || (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
          ) == PackageManager.PERMISSION_GRANTED), treeUris
        )
      }
      all.clear(); all.addAll(scanned)
      val selected = source.selectedItem?.toString() ?: "All sources"
      val sources = listOf("All sources") + all.map { it.source }.distinct().sorted()
      source.adapter =
        ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sources)
      source.setSelection(sources.indexOf(selected).coerceAtLeast(0))
      render()
    }
  }

  private fun visibleItems(): List<FileItem> {
    val needle = query.text.toString().lowercase().trim()
    val filterType = type.selectedItem?.toString() ?: "All types"
    val filterSource = source.selectedItem?.toString() ?: "All sources"
    val minimum = when (date.selectedItem?.toString()) {
      "Past 7 days" -> System.currentTimeMillis() - 7L * 86400000
      "Past 30 days" -> System.currentTimeMillis() - 30L * 86400000
      "Past year" -> System.currentTimeMillis() - 365L * 86400000
      else -> 0L
    }
    return all.filter { f ->
      val category = when {
        f.mime.startsWith("image/") -> "Images"
        f.mime.startsWith("video/") -> "Videos"
        f.mime.startsWith("audio/") -> "Audio"
        f.mime.startsWith("text/") || f.mime.contains("pdf") || f.mime.contains("document") || f.mime.contains(
          "sheet"
        ) || f.mime.contains("presentation") -> "Documents"

        else -> "Other"
      }
      (filterType == "All types" || category == filterType) && (filterSource == "All sources" || filterSource == f.source) &&
          f.modified >= minimum && (needle.isEmpty() || listOf(
        f.name,
        f.location,
        f.source,
        f.labels
      ).any { it.lowercase().contains(needle) })
    }.let { items ->
      when (sort.selectedItem?.toString()) {
        "Size: smallest" -> items.sortedBy { it.bytes }
        "Date: newest" -> items.sortedByDescending { it.modified }
        "Date: oldest" -> items.sortedBy { it.modified }
        "Name" -> items.sortedBy { it.name.lowercase() }
        "Source app" -> items.sortedBy { it.source.lowercase() }
        else -> items.sortedByDescending { it.bytes }
      }
    }
  }

  private fun render() {
    if (!::list.isInitialized || !::query.isInitialized || !::source.isInitialized || source.adapter == null) return
    val items = visibleItems()
    list.removeAllViews()
    val selected = all.count { it.selected }
    announce(
      "${all.size} indexed | ${items.size} matching | $selected selected | ${
        formatBytes(
          items.sumOf { it.bytes })
      } visible"
    )
    items.take(250).forEach { file ->
      val box = CheckBox(this).apply {
        text = "${file.name}  •  ${formatBytes(file.bytes)}\n${file.source}  |  ${file.location}\n${
          DateFormat.getDateInstance().format(Date(file.modified))
        }  •  ${file.mime}" +
            (if (file.labels.isNotEmpty()) "\nAI: ${file.labels}" else "")
        textSize = 12f; isChecked = file.selected
        setOnCheckedChangeListener { _, checked ->
          file.selected = checked; status.text = "${all.count { it.selected }} selected"
        }
        setOnLongClickListener {
          AlertDialog.Builder(this@MainActivity).setTitle(file.name).setMessage(file.uri.toString())
            .setPositiveButton("OK", null).show(); true
        }
      }
      list.addView(box)
    }
    if (items.size > 250) list.addView(TextView(this).apply {
      text = "Showing first 250 matching records; narrow filters to see more."
    })
  }

  private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
  }

  private fun labelImages() {
    val targets = all.filter { it.selected && it.mime.startsWith("image/") }
    if (targets.isEmpty()) {
      announce("Select at least one image first"); return
    }
    AlertDialog.Builder(this).setTitle("Local image investigation")
      .setMessage("Analyze ${targets.size} selected images on this device? Results are approximate. Images are not uploaded for AI analysis.")
      .setNegativeButton("Cancel", null).setPositiveButton("Analyze") { _, _ ->
        announce("Analyzing selected images…")
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        fun next(index: Int) {
          if (index >= targets.size) {
            labeler.close(); render(); return
          }
          val file = targets[index]
          try {
            val image = InputImage.fromFilePath(this, file.uri)
            labeler.process(image).addOnSuccessListener { labels ->
              file.labels =
                labels.filter { it.confidence >= 0.50f }.take(5).joinToString(", ") { it.text }
              next(index + 1)
            }.addOnFailureListener { file.labels = "Unable to analyze"; next(index + 1) }
          } catch (_: Exception) {
            file.labels = "Unable to open"; next(index + 1)
          }
        }
        next(0)
      }.show()
  }

  private fun connectDropbox() {
    if (BuildConfig.DROPBOX_APP_KEY == "SET_DROPBOX_APP_KEY") {
      announce("Set DROPBOX_APP_KEY in local.properties first"); return
    }
    waitingForDropbox = true
    Auth.startOAuth2PKCE(
      this,
      BuildConfig.DROPBOX_APP_KEY,
      DbxRequestConfig("FileInvestigator"),
      listOf("files.content.write")
    )
  }

  private fun uploadDialog() {
    val selected = all.filter { it.selected }
    if (selected.isEmpty()) {
      announce("Select files first"); return
    }
    if (credential == null) {
      announce("Connect Dropbox first"); return
    }
    val path =
      folder.text.toString().trim().let { if (it.startsWith("/")) it else "/$it" }.trimEnd('/')
    if (path.isBlank() || path == "/" || path.contains("..")) {
      announce("Enter a Dropbox folder such as /PhoneArchive"); return
    }
    AlertDialog.Builder(this).setTitle("Approve Dropbox upload")
      .setMessage("Upload ${selected.size} selected files (${formatBytes(selected.sumOf { it.bytes })}) to $path? Existing files will not be overwritten. Local originals will remain unless you explicitly approve deletion afterward.")
      .setNegativeButton("Cancel", null).setPositiveButton("Upload") { _, _ ->
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("dropboxFolder", path)
          .apply()
        scope.launch {
          announce("Uploading to Dropbox…")
          val outcome = withContext(Dispatchers.IO) {
            val success = mutableListOf<FileItem>();
            val failed = mutableListOf<String>()
            val client = DbxClientV2(DbxRequestConfig("FileInvestigator"), credential!!)
            // Create each parent folder before uploading; existing folders are fine.
            var current = ""
            for (segment in path.split('/').filter { it.isNotEmpty() }) {
              current += "/$segment"
              try {
                client.files().createFolderV2(current)
              } catch (_: com.dropbox.core.v2.files.CreateFolderErrorException) { /* already exists or inaccessible; upload reports error */
              }
            }
            for (file in selected) {
              try {
                val safeName = file.name.replace(Regex("[/\\\\]"), "_")
                val unique = "${System.currentTimeMillis()}_${
                  java.util.UUID.randomUUID().toString().take(8)
                }_$safeName"
                val remote = "$path/$unique"
                contentResolver.openInputStream(file.uri)?.use { stream ->
                  val result = client.files().uploadBuilder(remote).uploadAndFinish(stream)
                  if (result.size != file.bytes) error("Upload size mismatch")
                  success.add(file)
                } ?: error("Unable to open input")
              } catch (e: Exception) {
                failed.add("${file.name}: ${e.message}")
              }
            }
            Pair(success.toList(), failed.toList())
          }
          uploadedFiles = outcome.first
          announce("${outcome.first.size} uploaded and size-verified; ${outcome.second.size} failed")
          if (outcome.first.isNotEmpty()) {
            AlertDialog.Builder(this@MainActivity).setTitle("Upload complete")
              .setMessage("${outcome.first.size} files uploaded. ${outcome.second.size} failed. Keep originals or request a separate, explicit deletion confirmation for successfully uploaded files only?")
              .setNegativeButton("Keep originals", null)
              .setPositiveButton("Review deletion") { _, _ -> confirmDelete(outcome.first) }.show()
          }
        }
      }.show()
  }

  private fun confirmDelete(files: List<FileItem> = all.filter { it.selected }) {
    if (files.isEmpty()) {
      announce("Select files first"); return
    }
    AlertDialog.Builder(this).setTitle("Permanently delete ${files.size} files?")
      .setMessage("This is irreversible. Only accessible files will be deleted; some require an additional Android system approval. Dropbox uploads, if any, are not affected.")
      .setNegativeButton("Cancel", null)
      .setPositiveButton("Request deletion") { _, _ -> deleteFiles(files) }.show()
  }

  private fun deleteFiles(files: List<FileItem>) {
    val media = files.filter { it.isMedia }
    val documents = files.filterNot { it.isMedia }
    var removed = 0;
    var refused = 0
    documents.forEach { file ->
      try {
        val document = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, file.uri)
        if (file.writable && document?.delete() == true) removed++ else refused++
      } catch (_: Exception) {
        refused++
      }
    }
    if (media.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
      try {
        val request: PendingIntent =
          MediaStore.createDeleteRequest(contentResolver, media.map { it.uri })
        deletion.launch(
          androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build()
        )
      } catch (e: Exception) {
        announce("Deleted $removed folder items; $refused unavailable. Android media deletion error: ${e.message}")
      }
    } else if (media.isNotEmpty()) {
      announce("Deleted $removed folder files. Media deletion requires Android 11+ in this implementation.")
    } else {
      announce("Deleted $removed files; $refused lacked write access"); refresh()
    }
  }
}
