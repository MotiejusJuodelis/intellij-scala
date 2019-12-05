package org.jetbrains.plugins.scala
package codeInsight
package implicits

import com.intellij.codeHighlighting.EditorBoundHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.ex.util.EditorScrollingPositionKeeper
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.util.DocumentUtil
import org.jetbrains.plugins.scala.annotator.ScalaAnnotator
import org.jetbrains.plugins.scala.annotator.hints._
import org.jetbrains.plugins.scala.codeInsight.hints.methodChains.ScalaMethodChainInlayHintsPass
import org.jetbrains.plugins.scala.codeInsight.hints.{ScalaHintsSettings, ScalaTypeHintsPass}
import org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHintsPass._
import org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocumentationProvider
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.base.ScConstructorInvocation
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.lang.psi.api.{ImplicitArgumentsOwner, ScalaPsiElement}
import org.jetbrains.plugins.scala.lang.psi.implicits.ImplicitCollector._
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

private[codeInsight] class ImplicitHintsPass(private val editor: Editor, private val rootElement: ScalaPsiElement, override val settings: ScalaHintsSettings)
  extends EditorBoundHighlightingPass(editor, rootElement.getContainingFile, /*runIntentionPassAfter*/ false)
    with ScalaTypeHintsPass with ScalaMethodChainInlayHintsPass {

  import annotator.hints._

  private var hints: Seq[Hint] = Seq.empty

  override def doCollectInformation(indicator: ProgressIndicator): Unit = {
    hints = Seq.empty

    if (myDocument != null && rootElement.containingVirtualFile.isDefined) {
      // TODO Use a dedicated pass when built-in "advanced" hint API will be available in IDEA, SCL-14502
      rootElement.elements.foreach(e => AnnotatorHints.in(e).foreach(hints ++= _.hints))
      // TODO Use a dedicated pass when built-in "advanced" hint API will be available in IDEA, SCL-14502
      hints ++= collectTypeHints(editor, rootElement)
      collectConversionsAndArguments()
      collectMethodChainHints(editor, rootElement)
    }
  }

  private def collectConversionsAndArguments(): Unit = {
    val settings = ScalaProjectSettings.getInstance(rootElement.getProject)
    val showNotFoundImplicitForFile = ScalaAnnotator.isAdvancedHighlightingEnabled(rootElement) && settings.isShowNotFoundImplicitArguments

    def showNotFoundImplicits(element: PsiElement) =
      settings.isShowNotFoundImplicitArguments && ScalaAnnotator.isAdvancedHighlightingEnabled(element)

    if (!ImplicitHints.enabled && !showNotFoundImplicitForFile)
      return

    def implicitArgumentsOrErrorHints(owner: ImplicitArgumentsOwner): Seq[Hint] = {
      val showNotFoundArgs = showNotFoundImplicits(owner)
      val shouldSearch = ImplicitHints.enabled || showNotFoundArgs

      //todo: cover ambiguous implicit case (right now it is not always correct)
      def shouldShow(arguments: Seq[ScalaResolveResult]) =
        ImplicitHints.enabled ||
          (showNotFoundArgs && arguments.exists(p => p.isImplicitParameterProblem && !isAmbiguous(p)))

      if (shouldSearch) {
        owner.findImplicitArguments.toSeq.flatMap {
          case args if shouldShow(args) =>
            implicitArgumentsHint(owner, args)(editor.getColorsScheme)
          case _ => Seq.empty
        }
      }
      else Seq.empty
    }

    def explicitArgumentHint(e: ImplicitArgumentsOwner): Seq[Hint] = {
      if (!ImplicitHints.enabled) return Seq.empty

      e.explicitImplicitArgList.toSeq
        .flatMap(explicitImplicitArgumentsHint)
    }

    def implicitConversionHints(e: ScExpression): Seq[Hint] = {
      if (!ImplicitHints.enabled) return Seq.empty

      e.implicitConversion().toSeq.flatMap { conversion =>
        implicitConversionHint(e, conversion)(editor.getColorsScheme)
      }
    }

    rootElement.depthFirst().foreach {
      case enum@ScEnumerator.withDesugaredAndEnumeratorToken(desugaredEnum, token) =>
        val analogCall = desugaredEnum.analogMethodCall
        def mapBackTo(e: PsiElement)(hint: Hint): Hint = hint.copy(element = e)
        enum match {
          case _: ScForBinding | _: ScGuard =>
            hints ++:= implicitConversionHints(analogCall).map(mapBackTo(enum))
          case _ =>
        }
        hints ++:= implicitArgumentsOrErrorHints(analogCall).map(mapBackTo(token))
      case e: ScExpression =>
        hints ++:= implicitConversionHints(e)
        hints ++:= explicitArgumentHint(e)
        hints ++:= implicitArgumentsOrErrorHints(e)
      case c: ScConstructorInvocation =>
        hints ++:= explicitArgumentHint(c)
        hints ++:= implicitArgumentsOrErrorHints(c)
      case _ =>
    }
  }

  override def doApplyInformationToEditor(): Unit = {
    EditorScrollingPositionKeeper.perform(myEditor, false, regenerateHints())

    if (rootElement == myFile) {
      ImplicitHints.setUpToDate(myEditor, myFile)
    }
  }

  private def regenerateHints(): Unit = {
    val inlayModel = myEditor.getInlayModel
    val existingInlays = inlayModel.inlaysIn(rootElement.getTextRange)

    val bulkChange = existingInlays.length + hints.length  > BulkChangeThreshold

    DocumentUtil.executeInBulk(myEditor.getDocument, bulkChange, () => {
      existingInlays.foreach(Disposer.dispose)
      hints.foreach(inlayModel.add(_))
      regenerateMethodChainHints(myEditor, inlayModel, rootElement)
    })
  }
}

private object ImplicitHintsPass {
  import org.jetbrains.plugins.scala.annotator.hints.Hint

  private final val BulkChangeThreshold = 1000

  private def implicitConversionHint(e: ScExpression, conversion: ScalaResolveResult)
                                    (implicit scheme: EditorColorsScheme): Seq[Hint] =
    Seq(Hint(namedBasicPresentation(conversion) :+ Text("("), e, suffix = false, menu = Some(menu.ImplicitConversion)),
      Hint(Text(")") +: collapsedPresentationOf(conversion.implicitParameters), e, suffix = true, menu = Some(menu.ImplicitArguments)))

  private def implicitArgumentsHint(e: ImplicitArgumentsOwner, arguments: Seq[ScalaResolveResult])
                                   (implicit scheme: EditorColorsScheme): Seq[Hint] =
    Seq(Hint(presentationOf(arguments), e, suffix = true, menu = Some(menu.ImplicitArguments)))

  private def explicitImplicitArgumentsHint(args: ScArgumentExprList): Seq[Hint] =
    Seq(Hint(Seq(Text(".explicitly")), args, suffix = false, menu = Some(menu.ExplicitArguments)))

  private def presentationOf(arguments: Seq[ScalaResolveResult])
                            (implicit scheme: EditorColorsScheme): Seq[Text] = {

    if (!ImplicitHints.enabled)
      collapsedPresentationOf(arguments)
    else
      expandedPresentationOf(arguments)
  }

  private def collapsedPresentationOf(arguments: Seq[ScalaResolveResult])(implicit scheme: EditorColorsScheme): Seq[Text] =
    if (arguments.isEmpty) Seq.empty
    else {
      val problems = arguments.filter(_.isImplicitParameterProblem)
      val folding = Text(foldedString,
        attributes = foldedAttributes(error = problems.nonEmpty),
        expansion = Some(() => expandedPresentationOf(arguments).drop(1).dropRight(1))
      )

      Seq(
        Text("("),
        folding,
        Text(")")
      ).withErrorTooltipIfEmpty(notFoundTooltip(problems))
    }

  private def expandedPresentationOf(arguments: Seq[ScalaResolveResult])
                                    (implicit scheme: EditorColorsScheme): Seq[Text] =
    if (arguments.isEmpty) Seq.empty
    else {
      arguments.join(
        Text("("),
        Text(", "),
        Text(")")
      )(presentationOf)
    }

  private def presentationOf(argument: ScalaResolveResult)(implicit scheme: EditorColorsScheme): Seq[Text] =
    argument.isImplicitParameterProblem
      .option(problemPresentation(parameter = argument))
      .getOrElse(namedBasicPresentation(argument) ++ collapsedPresentationOf(argument.implicitParameters))

  private def namedBasicPresentation(result: ScalaResolveResult): Seq[Text] = {
    val delegate = result.element match {
      case f: ScFunction => Option(f.syntheticNavigationElement).getOrElse(f)
      case element => element
    }

    val tooltip = ScalaDocumentationProvider.getQuickNavigateInfo(delegate, result.substitutor)
    Seq(
      Text(result.name, navigatable = delegate.asOptionOf[Navigatable], tooltip = Some(tooltip))
    )
  }

  private def problemPresentation(parameter: ScalaResolveResult)
                                 (implicit scheme: EditorColorsScheme): Seq[Text] = {
    probableArgumentsFor(parameter) match {
      case Seq()                                                => noApplicableExpandedPresentation(parameter)
      case Seq((arg, result)) if arg.implicitParameters.isEmpty => presentationOfProbable(arg, result)
      case args                                                 => collapsedProblemPresentation(parameter, args)
    }
  }

  private def noApplicableExpandedPresentation(parameter: ScalaResolveResult)
                                              (implicit scheme: EditorColorsScheme) = {

    val qMarkText = Text("?", likeWrongReference, navigatable = parameter.element.asOptionOf[Navigatable])
    val paramTypeSuffix = Text(typeSuffix(parameter))

    (qMarkText :: paramTypeSuffix :: Nil)
      .withErrorTooltipIfEmpty(notFoundTooltip(parameter))
  }

  private def collapsedProblemPresentation(parameter: ScalaResolveResult, probableArgs: Seq[(ScalaResolveResult, FullInfoResult)])
                                          (implicit scheme: EditorColorsScheme): Seq[Text] = {
    val errorTooltip =
      if (probableArgs.size > 1) ambiguousTooltip(parameter)
      else notFoundTooltip(parameter)

    val presentationString =
      if (!ImplicitHints.enabled) foldedString else foldedString + typeSuffix(parameter)

    Seq(Text(
      presentationString,
      foldedAttributes(error = parameter.isImplicitParameterProblem),
      effectRange = Some((0, foldedString.length)),
      navigatable = parameter.element.asOptionOf[Navigatable],
      errorTooltip = Some(errorTooltip),
      expansion = Some(() => expandedProblemPresentation(parameter, probableArgs))
    ))
  }

  private def expandedProblemPresentation(parameter: ScalaResolveResult, arguments: Seq[(ScalaResolveResult, FullInfoResult)])
                                         (implicit scheme: EditorColorsScheme): Seq[Text] = {

    arguments match {
      case Seq((arg, result)) => presentationOfProbable(arg, result)
      case _                  => expandedAmbiguousPresentation(parameter, arguments)
    }
  }

  private def expandedAmbiguousPresentation(parameter: ScalaResolveResult, arguments: Seq[(ScalaResolveResult, FullInfoResult)])
                                           (implicit scheme: EditorColorsScheme) =
    arguments.join(Text(" | ", likeWrongReference)) {
      case (argument, result) => presentationOfProbable(argument, result)
    }.withErrorTooltipIfEmpty {
      ambiguousTooltip(parameter)
    }

  private def presentationOfProbable(argument: ScalaResolveResult, result: FullInfoResult)
                                    (implicit scheme: EditorColorsScheme): Seq[Text] = {
    result match {
      case OkResult =>
        namedBasicPresentation(argument)

      case ImplicitParameterNotFoundResult =>
        val presentationOfParameters = argument.implicitParameters
          .join(
            Text("("),
            Text(", "),
            Text(")")
          )(presentationOf)
        namedBasicPresentation(argument) ++ presentationOfParameters

      case DivergedImplicitResult =>
        namedBasicPresentation(argument)
          .withErrorTooltipIfEmpty("Implicit is diverged")
          .withAttributes(errorAttributes)

      case CantInferTypeParameterResult =>
        namedBasicPresentation(argument)
          .withErrorTooltipIfEmpty("Can't infer proper types for type parameters")
          .withAttributes(errorAttributes)
    }
  }

  private def isAmbiguous(parameter: ScalaResolveResult): Boolean =
    parameter.isImplicitParameterProblem && probableArgumentsFor(parameter).size > 1

  private def typeSuffix(parameter: ScalaResolveResult): String = {
    val paramType = parameter.implicitSearchState.map(_.presentableTypeText).getOrElse("NotInferred")
    s": $paramType"
  }

  private def paramWithType(parameter: ScalaResolveResult): String =
    StringUtil.escapeXmlEntities(parameter.name + typeSuffix(parameter))

  private def notFoundTooltip(parameter: ScalaResolveResult): String =
    "No implicits found for parameter " + paramWithType(parameter)

  private def notFoundTooltip(parameters: Seq[ScalaResolveResult]): Option[String] = {
    parameters match {
      case Seq()  => None
      case Seq(p) => Some(notFoundTooltip(p))
      case ps     => Some("No implicits found for parameters " + ps.map(paramWithType).mkString(", "))
    }
  }

  private def ambiguousTooltip(parameter: ScalaResolveResult): String =
    "Ambiguous implicits for parameter " + paramWithType(parameter)
}

