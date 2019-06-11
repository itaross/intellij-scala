package org.jetbrains.plugins.scala
package annotator
package element

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.extensions.ElementText
import org.jetbrains.plugins.scala.lang.psi.api.base._
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{ScIntegerLiteral, ScLongLiteral}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScPrefixExpr
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.api
import org.jetbrains.plugins.scala.project._

object ScLiteralAnnotator extends ElementAnnotator[ScLiteral] {

  private val StringLiteralSizeLimit = 65536
  private val StringCharactersCountLimit = StringLiteralSizeLimit / 4

  override def annotate(literal: ScLiteral,
                        holder: AnnotationHolder,
                        typeAware: Boolean): Unit = {
    implicit val implicitHolder: AnnotationHolder = holder
    implicit val containingFile: PsiFile = literal.getContainingFile

    literal match {
      case _: ScLongLiteral => checkIntegerLiteral(literal, isLong = true)
      case _: ScIntegerLiteral => checkIntegerLiteral(literal, isLong = false)
      case interpolatedStringLiteral: ScInterpolatedStringLiteral =>
        createStringIsTooLongAnnotation(interpolatedStringLiteral, interpolatedStringLiteral.getStringParts: _*)
      case ScLiteral(string) => createStringIsTooLongAnnotation(literal, string)
      case _ =>
    }
  }

  private def createStringIsTooLongAnnotation[L <: ScLiteral](literal: L, strings: String*)
                                                             (implicit holder: AnnotationHolder,
                                                              containingFile: PsiFile) =
    if (strings.exists(stringIsTooLong)) {
      holder.createErrorAnnotation(
        literal,
        ScalaBundle.message("string.literal.is.too.long")
      )
    }

  private def stringIsTooLong(string: String)
                             (implicit containingFile: PsiFile): Boolean = string.length match {
    case length if length >= StringLiteralSizeLimit => true
    case length if length >= StringCharactersCountLimit => utf8Size(string) >= StringLiteralSizeLimit
    case _ => false
  }

  private def utf8Size(string: String)
                      (implicit containingFile: PsiFile): Int = {
    val lineSeparator = Option(containingFile)
      .flatMap(file => Option(file.getVirtualFile))
      .flatMap(virtualFile => Option(virtualFile.getDetectedLineSeparator))
      .getOrElse(Option(System.lineSeparator).getOrElse("\n"))

    string.map {
      case '\n' => lineSeparator.length
      case '\r' => 0
      case character if character >= 0 && character <= '\u007F' => 1
      case character if character >= '\u0080' && character <= '\u07FF' => 2
      case character if character >= '\u0800' && character <= '\uFFFF' => 3
      case _ => 4
    }.sum
  }

  private sealed abstract class IntegerKind(val radix: Int,
                                            protected val beginIndex: Int,
                                            val divider: Int = 2) {

    final def apply(text: String,
                    isLong: Boolean): String = text.substring(
      beginIndex,
      text.length - (if (isLong) 1 else 0)
    )

    final def get: this.type = this

    final def isEmpty: Boolean = false

    final def _1: Int = radix

    final def _2: Int = divider
  }

  private object IntegerKind {

    def apply(text: String): IntegerKind = text.head match {
      case '0' if text.length > 1 =>
        text(1) match {
          case 'x' | 'X' => Hex
          case 'l' | 'L' => Dec
          case _ => Oct
        }
      case _ => Dec
    }

    def unapply(kind: IntegerKind): IntegerKind = kind
  }

  private case object Dec extends IntegerKind(10, 0, 1)

  private case object Hex extends IntegerKind(16, 2)

  private case object Oct extends IntegerKind(8, 1)

  private def checkIntegerLiteral(literal: ScLiteral, isLong: Boolean) // TODO isLong smells
                                 (implicit holder: AnnotationHolder): Unit = {
    import ScalaLanguageLevel._
    val languageLevel = literal.scalaLanguageLevel

    val text = literal.getLastChild.getText
    val kind = IntegerKind(text)

    val maybeParent = literal.getParent match {
      case prefixExpr: ScPrefixExpr =>
        // only "-1234" is negative, "- 1234" should be considered as positive 1234
        prefixExpr.getChildren match {
          case Array(ElementText("-"), _) => Some(prefixExpr)
          case _ => None
        }
      case _ => None
    }

    kind match {
      case Oct if languageLevel.exists(_ >= Scala_2_11) =>
        createAnnotation(
          literal,
          ScalaBundle.message("octal.literal.removed")
        )
        return
      case Oct if languageLevel.contains(Scala_2_10) =>
        createAnnotation(
          literal,
          ScalaBundle.message("octal.literals.deprecated"),
          ProblemHighlightType.LIKE_DEPRECATED
        )
      case _ =>
    }

    val number = kind(text, isLong)
    number.lastIndexOf('_') match {
      case -1 =>
      case index =>
        if (languageLevel.exists(_ < Scala_2_13)) {
          holder.createErrorAnnotation(literal, ScalaBundle.message("illegal.underscore.separator"))
        }
        if (index == number.length - 1) {
          holder.createErrorAnnotation(literal, ScalaBundle.message("trailing.underscore.separator"))
        }
    }

    parseIntegerNumber(number, kind, maybeParent.isDefined) match {
      case None =>
        holder.createErrorAnnotation(
          literal,
          ScalaBundle.message("long.literal.is.out.of.range")
        )
      case Some(Right(_)) if !isLong =>
        val expression = maybeParent.getOrElse(literal)
        val annotation = holder.createErrorAnnotation(
          expression,
          ScalaBundle.message("integer.literal.is.out.of.range")
        )

        val shouldRegisterFix = expression.expectedType().forall { `type` =>
          val longAndBigInt = api.Long(literal.getProject) :: ScalaPsiElementFactory.createTypeFromText(
            "_root_.scala.math.BigInt",
            literal.getContext,
            literal
          ).toList

          longAndBigInt.exists {
            _.weakConforms(`type`)
          }
        }

        if (shouldRegisterFix) {
          annotation.registerFix(new quickfix.AddLToLongLiteralFix(literal))
        }
      case _ =>
    }
  }

  private def createAnnotation(literal: ScLiteral, message: String,
                               highlightType: ProblemHighlightType = ProblemHighlightType.GENERIC_ERROR)
                              (implicit holder: AnnotationHolder): Unit = {
    val annotation = highlightType match {
      case ProblemHighlightType.GENERIC_ERROR => holder.createErrorAnnotation(literal, message)
      case _ => holder.createWarningAnnotation(literal, message)
    }
    annotation.setHighlightType(highlightType)
    annotation.registerFix(new quickfix.ConvertOctalToHexFix(literal))
  }

  private[this] def parseIntegerNumber(number: String,
                                       kind: IntegerKind,
                                       isNegative: Boolean) =
    stringToNumber(number, kind, isNegative)().map {
      case (value, false) => Left(value.toInt)
      case (value, _) => Right(value)
    }

  @annotation.tailrec
  private[this] def stringToNumber(number: String,
                                   kind: IntegerKind,
                                   isNegative: Boolean)
                                  (index: Int = 0,
                                   value: Long = 0L,
                                   exceedsIntLimit: Boolean = false): Option[(Long, Boolean)] =
    if (index == number.length) {
      val newValue = if (isNegative) -value else value
      Some(newValue, exceedsIntLimit)
    } else {
      number(index) match {
        case '_' => stringToNumber(number, kind, isNegative)(index + 1, value, exceedsIntLimit)
        case char =>
          val digit = char.asDigit
          val IntegerKind(radix, divider) = kind
          val newValue = value * radix + digit

          def exceedsLimit(limit: Long) =
            limit / (radix / divider) < value ||
              limit - (digit / divider) < value * (radix / divider) &&
                !(isNegative && limit == newValue - 1)

          if (value < 0 || exceedsLimit(java.lang.Long.MAX_VALUE)) None
          else stringToNumber(number, kind, isNegative)(
            index + 1,
            newValue,
            value > Integer.MAX_VALUE || exceedsLimit(Integer.MAX_VALUE)
          )
      }
    }
}