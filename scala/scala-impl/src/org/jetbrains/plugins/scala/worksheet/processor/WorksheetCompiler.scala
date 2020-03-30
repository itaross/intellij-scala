package org.jetbrains.plugins.scala
package worksheet.processor

import java.io.{File, FileWriter}

import com.intellij.compiler.progress.CompilerTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.{CompilerMessage, CompilerMessageCategory}
 import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.{Document, Editor}
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.jps.incremental.messages.BuildMessage.Kind
import org.jetbrains.jps.incremental.scala.{Client, DelegateClient}
import org.jetbrains.plugins.scala.compiler.{CompileServerLauncher, ScalaCompileServerSettings}
import org.jetbrains.plugins.scala.extensions.{LoggerExt, ThrowableExt, using}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.project.ScalaSdkNotConfiguredException
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.jetbrains.plugins.scala.worksheet.processor.WorksheetCompiler.WorksheetCompilerResult.{CompileServerIsNotRunningError, Precondition, PreconditionError}
import org.jetbrains.plugins.scala.worksheet.processor.WorksheetCompilerUtil.WorksheetCompileRunRequest
import org.jetbrains.plugins.scala.worksheet.processor.WorksheetCompilerUtil.WorksheetCompileRunRequest._
import org.jetbrains.plugins.scala.worksheet.runconfiguration.WorksheetCache
import org.jetbrains.plugins.scala.worksheet.server.RemoteServerConnector.Args.{CompileOnly, PlainModeArgs, ReplModeArgs}
import org.jetbrains.plugins.scala.worksheet.server.RemoteServerConnector.{CompilerInterface, CompilerMessagesConsumer, RemoteServerConnectorResult}
import org.jetbrains.plugins.scala.worksheet.server._
import org.jetbrains.plugins.scala.worksheet.settings.WorksheetExternalRunType.WorksheetPreprocessError
import org.jetbrains.plugins.scala.worksheet.settings._
import org.jetbrains.plugins.scala.worksheet.ui.printers.{WorksheetEditorPrinter, WorksheetEditorPrinterRepl}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise, TimeoutException}
import scala.util.control.NonFatal
import scala.util.{Success, Try}

//noinspection ScalaWrongPlatformMethodsUsage
// TODO: rename/refactor, the class has more responsibilities then to "Compile"
//private[worksheet]
class WorksheetCompiler(
  module: Module,
  worksheetFile: ScalaFile
) {

  import worksheet.processor.WorksheetCompiler._

  private implicit val project: Project = worksheetFile.getProject

  private val runType = WorksheetFileSettings.getRunType(worksheetFile)
  private val makeType = WorksheetCompiler.getMakeType(project, runType)

  private val virtualFile = worksheetFile.getVirtualFile

  def compileOnlySync(document: Document, client: Client, waitAtMost: Duration): Either[TimeoutException, WorksheetCompilerResult] = {
    val promise = Promise[WorksheetCompilerResult]
    compileOnly(document, client) { result =>
      promise.complete(Success(result))
    }
    Try(Right(Await.result(promise.future, waitAtMost)))
      .recover { case timeout: TimeoutException => Left(timeout) }
      .get
  }

  def compileOnly(document: Document, client: Client)(originalCallback: EvaluationCallback): Unit = {
    Log.traceSafe(s"compileOnly: $document")
    val headlessMode = {
      val application = ApplicationManager.getApplication
      !application.isInternal || application.isUnitTestMode
    }
    val compilerTask = new CompilerTask(
      project,
      ScalaBundle.message("highlighting.compilation"),
      headlessMode,
      /*forceAsync=*/ false,
      /*waitForPreviousSession=*/ true,
      /*compilationStartedAutomatically=*/ true
    )

    CompileServerLauncher.ensureServerRunning(project)
    compilerTask.startWork {

      val callback: RemoteServerConnectorResult => Unit = result => {
        Log.traceSafe(s"compileOnly result: $result")
        originalCallback(WorksheetCompilerResult.Compiled)
      }
      try {
        val originalFilePath = virtualFile.getCanonicalPath
        val (_, tempFile, outputDir) = WorksheetCache.getInstance(project).updateOrCreateCompilationInfo(
          filePath = originalFilePath,
          fileName = worksheetFile.getName,
          tempDirName = Some("worksheet-compilation")
        )
        WorksheetWrapper.writeWrappedToFile(document.getText, tempFile)
        val connector = new RemoteServerConnector(module, worksheetFile, CompileOnly(tempFile, outputDir), InProcessServer)
        val delegatingClient = new WrappedWorksheetCompilerMessagesFixer(new File(originalFilePath), client)
        connector.compileAndRun(virtualFile, delegatingClient)(callback)
      } catch {
        case NonFatal(ex) =>
          callback(toError(ex))
      }
    }
  }

  private def createCompilerTask: CompilerTask = {
    val waitForPreviousSession = true
    new CompilerTask(
      project, ScalaBundle.message("worksheet.compilation", worksheetFile.getName),
      false,
      false,
      waitForPreviousSession,
      false
    )
  }

  private def toError(ex: Throwable): RemoteServerConnectorResult.UnhandledError = ex match {
    case ex: ScalaSdkNotConfiguredException => RemoteServerConnectorResult.ExpectedError(ex)
    case ex                                 => RemoteServerConnectorResult.UnexpectedError(ex)
  }

  private def compileAndRun(autoTriggered: Boolean, editor: Editor, request: WorksheetCompileRunRequest)
                           (originalCallback: EvaluationCallback): Unit = {
    WorksheetCompilerUtil.removeOldMessageContent(project) //or not?

    makeType match {
      case InProcessServer | OutOfProcessServer =>
        if (!CompileServerLauncher.ensureServerRunning(project)) {
          originalCallback(CompileServerIsNotRunningError)
        }
      case NonServer =>
    }

    val task = createCompilerTask
    val printer = runType.createPrinter(editor, worksheetFile)
    // do not show error messages in Plain mode on auto-run
    val ignoreCompilerMessages = request match {
      case RunRepl(_)       => false
      case RunCompile(_, _) => autoTriggered
    }
    val consumer = new CompilerInterfaceImpl(task, printer, ignoreCompilerMessages)
    printer match {
      case replPrinter: WorksheetEditorPrinterRepl =>
        replPrinter.updateMessagesConsumer(consumer)
      case _ =>
    }

    // this is needed to close the timer of printer in one place
    val callback: EvaluationCallback = result => {
      if (!consumer.isCompiledWithErrors) {
        printer.flushBuffer()
      }
      printer.close()
      originalCallback(result)
    }

    val cache = WorksheetCache.getInstance(project)
    if (ApplicationManager.getApplication.isUnitTestMode) {
      cache.addCompilerMessagesCollector(editor, consumer)
    }

    val args = request match {
      case RunRepl(code) =>
        ReplModeArgs(virtualFile.getCanonicalPath, code)
      case RunCompile(code, className) =>
        val (_, tempFile, outputDir) = cache.updateOrCreateCompilationInfo(virtualFile.getCanonicalPath, worksheetFile.getName)
        FileUtil.writeToFile(tempFile, code)
        PlainModeArgs(tempFile, outputDir, className)
    }
    val afterCompileCallback: RemoteServerConnectorResult => Unit = {
      case RemoteServerConnectorResult.Done =>
        if (consumer.isCompiledWithErrors) {
          callback(WorksheetCompilerResult.CompilationError)
        } else {
          makeType match {
            case OutOfProcessServer =>
              val plainArgs = args.asInstanceOf[PlainModeArgs]
              WorksheetCompilerLocalEvaluator.executeWorksheet(worksheetFile, plainArgs.className, plainArgs.outputDir.getAbsolutePath)(callback, printer)(module)
            case _ =>
              callback(WorksheetCompilerResult.CompiledAndEvaluated)
          }
        }
      case error: RemoteServerConnectorResult.UnhandledError          =>
        callback(WorksheetCompilerResult.RemoteServerConnectorError(error))
    }
    task.startWork {
      try {
        val connector = new RemoteServerConnector(module, worksheetFile, args, makeType)
        connector.compileAndRun(virtualFile, consumer)(afterCompileCallback)
      } catch {
        case NonFatal(ex) =>
          afterCompileCallback(toError(ex))
      }
    }
  }

  def compileAndRun(autoTriggered: Boolean, editor: Editor)
                   (originalCallback: EvaluationCallback): Unit = try {
    if (DumbService.getInstance(project).isDumb) {
      originalCallback(PreconditionError(Precondition.ProjectShouldBeInSmartState))
    } else if (runType.isReplRunType && makeType != InProcessServer) {
      originalCallback(PreconditionError(Precondition.ReplRequiresCompileServerProcess))
    } else {
      runType.process(worksheetFile, editor) match {
        case Right(request) =>
          compileAndRun(autoTriggered, editor, request)(originalCallback)
        case Left(preprocessError)  =>
          originalCallback(WorksheetCompilerResult.PreprocessError(preprocessError))
      }
    }
  } catch {
    case NonFatal(ex) =>
      originalCallback(WorksheetCompilerResult.UnknownError(ex))
  }
}

//private[worksheet]
object WorksheetCompiler extends WorksheetPerFileConfig {

  private val EmptyRunnable: Runnable = () => {}

  private val Log: Logger = Logger.getInstance(getClass)

  type EvaluationCallback = WorksheetCompilerResult => Unit

  sealed trait WorksheetCompilerResult
  object WorksheetCompilerResult {
    // worksheet was compiled without any errors (but warnings possible) and evaluated successfully
    object CompiledAndEvaluated extends WorksheetCompilerResult
    object Compiled extends WorksheetCompilerResult

    sealed trait WorksheetCompilerError extends WorksheetCompilerResult
    final case class PreprocessError(error: WorksheetPreprocessError) extends WorksheetCompilerError
    final case class PreconditionError(message: Precondition) extends WorksheetCompilerError
    final case object CompilationError extends WorksheetCompilerError
    final case class ProcessTerminatedError(returnCode: Int, message: String) extends WorksheetCompilerError
    final object CompileServerIsNotRunningError extends WorksheetCompilerError
    final case class RemoteServerConnectorError(error: RemoteServerConnectorResult.UnhandledError) extends WorksheetCompilerError
    final case class UnknownError(cause: Throwable) extends WorksheetCompilerError // TODO: maybe wrap result into Try instead?

    sealed trait Precondition
    object Precondition {
      final case object ReplRequiresCompileServerProcess extends Precondition
      final case object ProjectShouldBeInSmartState extends Precondition
    }
  }

  trait CompilerMessagesCollector {
    def collectedMessages: Seq[CompilerMessage]
  }

  class CompilerInterfaceImpl(
    task: CompilerTask,
    worksheetPrinter: WorksheetEditorPrinter,
    ignoreCompilerMessages: Boolean
  ) extends CompilerInterface
    with CompilerMessagesConsumer
    with CompilerMessagesCollector {

    private val messages = mutable.ArrayBuffer[CompilerMessage]()
    private var hasCompilationErrors = false

    private val isUnitTestMode = ApplicationManager.getApplication.isUnitTestMode

    override def isCompiledWithErrors: Boolean = hasCompilationErrors

    override def collectedMessages: Seq[CompilerMessage] = messages

    override def message(message: CompilerMessage): Unit = {
      // for now we only need the compiler messages in unit tests
      if (message.getCategory == CompilerMessageCategory.ERROR) {
        hasCompilationErrors = true
      }
      if (!ignoreCompilerMessages) {
        task.addMessage(message)
        if (isUnitTestMode) {
          messages += message
        }
      }
    }

    override def progress(text: String, done: Option[Float]): Unit = {
      if (ignoreCompilerMessages) return
      val taskIndicator = ProgressManager.getInstance.getProgressIndicator

      if (taskIndicator != null) {
        taskIndicator.setText(text)
        done.foreach(d => taskIndicator.setFraction(d.toDouble))
      }
    }

    override def worksheetOutput(text: String): Unit =
      worksheetPrinter.processLine(text)

    override def trace(ex: Throwable): Unit = {
      val message = "\n" + ex.stackTraceText // stacktrace already contains thr.toString which contains message
      worksheetPrinter.internalError(message)
    }
  }

  private def getMakeType(project: Project, runType: WorksheetExternalRunType): WorksheetMakeType =
    if (ScalaCompileServerSettings.getInstance.COMPILE_SERVER_ENABLED) {
      if (ScalaProjectSettings.getInstance(project).isInProcessMode || runType == WorksheetExternalRunType.ReplRunType) {
        InProcessServer
      } else {
        OutOfProcessServer
      }
    } else {
      NonServer
    }

  implicit class CompilerTaskOps(private val task: CompilerTask) extends AnyVal {

    def startWork[T](compilerWork: => Unit): Unit =
      task.start(() => compilerWork, EmptyRunnable)
  }

  private object WorksheetWrapper {
    val className = "WorksheetWrapper"
    private val header = "class " + className + " {\n"
    private val footer = "\n}"
    val headerLines: Int = header.linesIterator.size
    def writeWrappedToFile(worksheetCode: String, file: File): Unit =
      using(new FileWriter(file)) { writer =>
        writer.write(header)
        writer.write(worksheetCode)
        writer.write(footer)
      }
  }

  /**
   * restores:
   * 1. message position in the original worksheet file
   * 2. original file pointer
   */
  private class WrappedWorksheetCompilerMessagesFixer(originalFile: File, client: Client)
    extends DelegateClient(client) {

    override def message(msg: Client.ClientMsg): Unit =
      if (needToHandleMessage(msg)){
        val msgFixed = fixMessage(msg)
        super.message(msgFixed)
      }

    // for now show only errors, cause compiler returns many warnings that are not actual for the worksheet
    // for example, for a simple expression `2 + 1` it will show:
    // "A pure expression does nothing in statement position; you may be omitting necessary parentheses"
    // Need to find a way to filter out certain warnings.
    // Also maybe we would like to filter out some errors. For example for value/function redefinition:
    // ```val x = 42
    //    val x = 42```
    // you will get:
    // "Double definition: val x: Int in class WorksheetWrapper at line 7 and val x: Int in class WorksheetWrapper at line 8"
    private def needToHandleMessage(msg: Client.ClientMsg): Boolean =
      msg.kind == Kind.ERROR

    // note that messages text will contain positions from the wrapped code
    private def fixMessage(msg: Client.ClientMsg): Client.ClientMsg =
      msg.copy(
        line = msg.line.map(line => (line - WorksheetWrapper.headerLines).max(0)),
        source = msg.source.map(_ => originalFile),
        text = msg.text.replace(s"class ${WorksheetWrapper.className}", originalFile.getName).trim
      )

    override def compilationEnd(sources: Set[File]): Unit =
      super.compilationEnd(sources) // FIXME (minor): it is now empty for worksheets
  }
}
