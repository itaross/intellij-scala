package org.jetbrains.plugins.scala.tasty

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon
import org.jetbrains.plugins.scala.icons.Icons

object TastyFileType extends FileType {
  override def getName = "TASTY"

  override def getDescription = "TASTY"

  override def getDefaultExtension = "tasty"

  override def getIcon: Icon = Icons.SCALA_SMALL_LOGO

  override def isBinary = true

  override def isReadOnly = true

  override def getCharset(file: VirtualFile, content: Array[Byte]): String = null
}