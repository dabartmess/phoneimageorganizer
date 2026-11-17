package net.dingodaveth.phonefileorganizer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile

class FileScanner(private val context: Context) {
  fun scan(mediaAllowed: Boolean, folderUris: Set<String>): List<FileItem> {
    val found = linkedMapOf<String, FileItem>()
    if (mediaAllowed) {
      val columns = mutableListOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH
      )
      if (Build.VERSION.SDK_INT >= 29) columns += MediaStore.MediaColumns.OWNER_PACKAGE_NAME
      val collections = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
      )
      for (collection in collections) {
        try {
          context.contentResolver.query(collection, columns.toTypedArray(), null, null, null)
            ?.use { c ->
              val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
              val name = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
              val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
              val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
              val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
              val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
              val owner = c.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
              while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(id))
                val pkg = if (owner >= 0) c.getString(owner) else null
                val item = FileItem(
                  uri,
                  c.getString(name) ?: "unnamed",
                  c.getLong(size).coerceAtLeast(0),
                  c.getLong(modified) * 1000,
                  c.getString(mime) ?: "application/octet-stream",
                  pkg?.takeIf { it.isNotBlank() } ?: "Unknown app",
                  c.getString(path) ?: "",
                  true,
                  false)
                found[uri.toString()] = item
              }
            }
        } catch (_: SecurityException) { /* Permission denied: continue with accessible collections */
        }
      }
    }
    folderUris.forEach { stringUri ->
      try {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(stringUri)) ?: return@forEach
        val queue = ArrayDeque<Pair<DocumentFile, String>>()
        queue.add(root to (root.name ?: "folder"))
        var visited = 0
        while (queue.isNotEmpty() && visited < 20000) {
          val (current, directory) = queue.removeFirst()
          current.listFiles().forEach { file ->
            visited++
            if (file.isDirectory && visited < 20000) queue.add(file to "$directory/${file.name ?: "folder"}")
            else if (file.isFile) {
              val item = FileItem(
                file.uri,
                file.name ?: "unnamed",
                file.length().coerceAtLeast(0),
                file.lastModified(),
                file.type ?: "application/octet-stream",
                "Folder: ${root.name ?: "selected"} (creator unknown)",
                directory,
                false,
                file.canWrite()
              )
              found[item.uri.toString()] = item
            }
          }
        }
      } catch (_: Exception) { /* Provider can revoke access or fail to enumerate */
      }
    }
    return found.values.toList()
  }
}
