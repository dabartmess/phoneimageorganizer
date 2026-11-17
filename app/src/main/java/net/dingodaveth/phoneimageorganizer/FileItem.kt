package net.dingodaveth.phonefileorganizer

import android.net.Uri

data class FileItem(
  val uri: Uri,
  val name: String,
  val bytes: Long,
  val modified: Long,
  val mime: String,
  val source: String,
  val location: String,
  val isMedia: Boolean,
  val writable: Boolean,
  var labels: String = "",
  var selected: Boolean = false
)
