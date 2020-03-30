package org.jetbrains.plugins.scala.externalHighlighters

import java.io.File

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import org.jetbrains.jps.incremental.scala.{Client, DummyClient}
import org.jetbrains.plugins.scala.compiler.{CompilerEvent, CompilerEventListener}
import org.jetbrains.plugins.scala.util.CompilationId

private class CompilerEventGeneratingClient(project: Project, indicator: ProgressIndicator) extends DummyClient {

  final val compilationId = CompilationId.generate()

  indicator.setIndeterminate(false)

  override def progress(text: String, done: Option[Float]): Unit = {
    indicator.setText(text)
    indicator.setFraction(done.getOrElse(-1.0F).toDouble)
    done.foreach { doneVal =>
      sendEvent(CompilerEvent.ProgressEmitted(compilationId, doneVal))
    }
  }

  override def message(msg: Client.ClientMsg): Unit =
    sendEvent(CompilerEvent.MessageEmitted(compilationId, msg))

  override def compilationEnd(sources: Set[File]): Unit =
    sources.foreach { source =>
      sendEvent(CompilerEvent.CompilationFinished(compilationId, source))
    }

  private def sendEvent(event: CompilerEvent): Unit =
    project.getMessageBus
      .syncPublisher(CompilerEventListener.topic)
      .eventReceived(event)
}