package org.jetbrains.plugins.scala.externalHighlighters

import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.PlatformTestUtil
import org.hamcrest.{Description, Matcher}
import org.jetbrains.plugins.scala.HighlightingTests
import org.jetbrains.plugins.scala.annotator.ScalaHighlightingMode
import org.jetbrains.plugins.scala.debugger.ScalaCompilerTestBase
import org.jetbrains.plugins.scala.extensions.HighlightInfoExt
import org.jetbrains.plugins.scala.externalHighlighters.UpdateCompilerGeneratedStateListener.{CompilerGeneratedStateTopic, CompilerGeneratedStateTopicListener}
import org.jetbrains.plugins.scala.project.VirtualFileExt
import org.jetbrains.plugins.scala.util.TestUtilsScala
import org.jetbrains.plugins.scala.util.matchers.{HamcrestMatchers, ScalaBaseMatcher}
import org.jetbrains.plugins.scala.util.runners.{MultipleScalaVersionsRunner, RunWithScalaVersions, TestScalaVersion}
import org.junit.Assert.{assertThat, fail}
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

@RunWith(classOf[MultipleScalaVersionsRunner])
@Category(Array(classOf[HighlightingTests]))
class ScalaCompilerHighlightingTest
  extends ScalaCompilerTestBase
    with HamcrestMatchers {

  import ScalaCompilerHighlightingTest._

  @RunWithScalaVersions(Array(
    TestScalaVersion.Scala_2_13,
    TestScalaVersion.Scala_3_0
  ))
  def testWarningHighlighting(): Unit = runTestCase(
    fileName = "ExhaustiveMatchWarning.scala",
    content =
      """
        |class ExhaustiveMatchWarning {
        |  val option: Option[Int] = Some(1)
        |  option match {
        |    case Some(_) =>
        |  }
        |}
        |""".stripMargin,
    expectedResult = expectedResult(ExpectedHighlighting(
      severity = HighlightSeverity.WARNING,
      range = Some(new TextRange(70, 76)),
      msgPrefix = "match may not be exhaustive"
    ))
  )

  @RunWithScalaVersions(Array(
    TestScalaVersion.Scala_2_13,
    TestScalaVersion.Scala_3_0
  ))
  def testErrorHighlighting(): Unit = runTestCase(
    fileName = "AbstractMethodInClassError.scala",
    content =
      """
        |class AbstractMethodInClassError {
        |  def method: Int
        |}
        |""".stripMargin,
    expectedResult = expectedResult(ExpectedHighlighting(
      severity = HighlightSeverity.ERROR,
      range = Some(new TextRange(7, 33)),
      msgPrefix = "class AbstractMethodInClassError needs to be abstract"
    ))
  )

  private val worksheetContent =
    """42
      |val option: Option[Int] = Some(1)
      |option match {
      |  case Some(_) =>
      |}
      |unknownFunction()
      |val x = 23 //actually, in worksheets this should be treated as OK, but for now we just fix the behaviour in tests
      |val x = 23
      |"""

  /** see [[org.jetbrains.plugins.scala.worksheet.processor.WorksheetCompiler.WrappedWorksheetCompilerMessagesFixer]] */
  @RunWithScalaVersions(Array(TestScalaVersion.Scala_2_13))
  def testOnlyErrorsAreExpectedInWorksheet_Scala_2_13(): Unit = runTestCaseWithoutRebuild(
    fileName = "worksheet.sc",
    content = worksheetContent.stripMargin,
    expectedResult = expectedResult(
      ExpectedHighlighting(
        severity = HighlightSeverity.ERROR,
        range = Some(new TextRange(72, 87)),
        msgPrefix = "not found: value unknownFunction"
      ),
      ExpectedHighlighting(
        severity = HighlightSeverity.ERROR,
        range = Some(new TextRange(208, 209)),
        msgPrefix = "x is already defined as value x"
      )
    )
  )

  /* see [[org.jetbrains.plugins.scala.worksheet.processor.WorksheetCompiler.WrappedWorksheetCompilerMessagesFixer]] */
  @RunWithScalaVersions(Array(TestScalaVersion.Scala_3_0))
  def testOnlyErrorsAreExpectedInWorksheet_Scala_3(): Unit = runTestCaseWithoutRebuild(
    fileName = "worksheet.sc",
    content = worksheetContent.stripMargin,
    expectedResult = expectedResult(
      ExpectedHighlighting(
        severity = HighlightSeverity.ERROR,
        range = Some(new TextRange(72, 87)),
        msgPrefix = "Not found: unknownFunction"
      ),
      ExpectedHighlighting(
        severity = HighlightSeverity.ERROR,
        range = Some(new TextRange(208, 209)),
        msgPrefix = "Double definition:\nval x: Int in worksheet.sc at line 8 and\nval x: Int in worksheet.sc at line 9"
      )
    )
  )

  private def runTestCase(fileName: String,
                          content: String,
                          expectedResult: ExpectedResult): Unit = withErrorsFromCompiler {
    val virtualFile = addFileToProjectSources(fileName, content)
    FileEditorManager.getInstance(getProject).openFile(virtualFile, true)

    compiler.rebuild()

    val document = virtualFile.findDocument.get
    val actualResult = DaemonCodeAnalyzerImpl.getHighlights(document, null, getProject).asScala

    assertThat(actualResult, expectedResult)
  }

  private def runTestCaseWithoutRebuild(
    fileName: String,
    content: String,
    expectedResult: ExpectedResult
  ): Unit = withErrorsFromCompiler {
    val virtualFile = addFileToProjectSources(fileName, content)
    lazy val document = virtualFile.findDocument.get

    val promise = Promise[Unit]()
    val listener: CompilerGeneratedStateTopicListener = _ => {
      promise.complete(Success(())) // todo (minor): we should also ensure that the file is actually the tested file
    }
    getProject.getMessageBus.connect().subscribe(CompilerGeneratedStateTopic, listener)

    FileEditorManager.getInstance(getProject).openFile(virtualFile, true)

    val timeout = 30.seconds
    TestUtilsScala.awaitWithoutUiStarving(promise.future, timeout) match {
      case Some(Success(_))  =>
      case Some(Failure(ex)) => throw ex
      case None              => fail(s"Document wasn't highlighted during expected time frame: $timeout")
    }
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

    val actualResult = DaemonCodeAnalyzerImpl.getHighlights(document, null, getProject).asScala
    assertThat(actualResult, expectedResult)
  }
}

object ScalaCompilerHighlightingTest {

  private type ExpectedResult = Matcher[Seq[HighlightInfo]]

  private case class ExpectedHighlighting(severity: HighlightSeverity,
                                          range: Option[TextRange] = None,
                                          msgPrefix: String = "")

  private def expectedResult(expected: ExpectedHighlighting*): ExpectedResult = new ScalaBaseMatcher[Seq[HighlightInfo]] {

    override protected def valueMatches(actualValue: Seq[HighlightInfo]): Boolean = {
      expected.size == actualValue.size &&
      expected.zip(actualValue).forall { case (expected, actual) =>
        actual.getSeverity == expected.severity &&
          expected.range.forall(_ == actual.range) &&
          actual.getDescription.startsWith(expected.msgPrefix)
      }
    }

    override protected def description: String =
      descriptionFor(expected)

    override def describeMismatch(item: Any, description: Description): Unit =
      item match {
        case seq: Seq[HighlightInfo] =>
          val itemFixed = descriptionFor(seq.map(toExpectedHighlighting))
          super.describeMismatch(itemFixed, description)
        case _ =>
          super.describeMismatch(item, description)
      }

    private def toExpectedHighlighting(info: HighlightInfo): ExpectedHighlighting =
      ExpectedHighlighting(info.getSeverity, Some(info.range), info.getDescription)

    private def descriptionFor(highlightings: Seq[ExpectedHighlighting]): String =
      highlightings.map(descriptionFor).mkString("\n")

    private def descriptionFor(highlighting: ExpectedHighlighting): String = {
      val ExpectedHighlighting(severity, range, msgPrefix) = highlighting
      val values = Seq(
        "severity" -> severity,
        "range" -> range.getOrElse("?"),
        "msgPrefix" -> msgPrefix
      ).map { case (name, value) =>
        s"$name=$value"
      }.mkString(",")
      s"HighlightInfo($values)"
    }
  }

  private def withErrorsFromCompiler(body: => Unit): Unit = {
    val registry = Registry.get(ScalaHighlightingMode.ShowScalacErrorsKey)

    registry.setValue(true)

    try body
    finally registry.setValue(false)
  }
}
