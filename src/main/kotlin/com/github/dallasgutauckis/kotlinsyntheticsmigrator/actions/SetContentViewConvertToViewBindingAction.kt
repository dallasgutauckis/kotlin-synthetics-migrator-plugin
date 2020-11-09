package com.github.dallasgutauckis.kotlinsyntheticsmigrator.actions

import android.databinding.tool.ext.toCamelCase
import android.databinding.tool.ext.toCamelCaseAsVar
import com.android.tools.idea.res.ModuleRClass
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import org.jetbrains.android.augment.ResourceRepositoryInnerRClass
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.resolve.ImportPath
import org.jetbrains.kotlin.resolve.calls.callUtil.getValueArgumentsInParentheses
import java.awt.Dimension
import javax.swing.JComponent

class SetContentViewConvertToViewBindingAction : AnAction() {

    // TODO Create binding intializers based on the implementation (Activity, Fragment, View, maybe a raw LayoutInflater)
    private fun createBindingInitializer(ktPsiFactory: KtPsiFactory, bindingClassName: String, parentClass: KtClass): KtExpression {
        return ktPsiFactory.createExpression("binding = $bindingClassName.inflate(layoutInflater)")
    }

    private fun getFindViewByIdCalls(from: KtClassBody): List<KtCallExpression> {
        val findViewByIds = mutableListOf<KtCallExpression>()

        from.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement?) {
                if (element is KtCallExpression && element.parent !is KtDotQualifiedExpression) {
                    if (element.text.startsWith("findViewById")) {
                        findViewByIds.add(element)
                    }
                }
                super.visitElement(element)
            }
        })

        return findViewByIds.toList()
    }

    private fun getKotlinSyntheticsAccessors(from: KtClass): List<KtNameReferenceExpression> {
        val accessors = mutableListOf<KtNameReferenceExpression>()

        from.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement?) {
                if (element is KtNameReferenceExpression
                    && element.resolve() is XmlAttributeValue
                    && element.resolveMainReferenceToDescriptors().first().javaClass.packageName == "org.jetbrains.kotlin.android.synthetic.res") {
                    accessors += element
                }
                super.visitElement(element)
            }
        })

        return accessors.toList()
    }

    private fun getKotlinSyntheticsImports(ktFile: KtFile): List<KtImportDirective> {
        return ktFile.importDirectives.filter {
            it.text.startsWith("import kotlinx.android.synthetic")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val eElement = e.getData(CommonDataKeys.PSI_ELEMENT)
        val data = e.getData(CommonDataKeys.PSI_FILE)

        val references = mutableListOf<PsiElement>()

        data?.accept(object : JavaRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement?) {

                // Kotlin implementation
                if (element is KtCallExpression) {
                    @Suppress("UNCHECKED_CAST")
                    val valueArgumentsInParentheses = element.getValueArgumentsInParentheses() as List<KtValueArgument>

                    // Find setContentView call and replace it with a binding property
                    if (element.text.startsWith("setContentView") && valueArgumentsInParentheses.size == 1) {
                        // This is the my_layout_name field on R.layout.my_layout_name
                        val field = (valueArgumentsInParentheses[0].children[0].children[1] as KtNameReferenceExpression).resolve() as? PsiField

                        if (field != null) {
                            WriteCommandAction.writeCommandAction(element.project)
                                .withName("Convert setContentView(R.layout) to ViewBinding")
                                .run<Nothing> {
                                    val rLayout = (field.parent as ResourceRepositoryInnerRClass)
                                    val rImportQualifiedName = (rLayout.containingClass as ModuleRClass).qualifiedName!!
                                    val packageName = rImportQualifiedName.substring(0, rImportQualifiedName.length - 2)
                                    val bindingClassName = "${field.name.toCamelCase()}Binding"
                                    val qualifiedBinding = "$packageName.databinding.$bindingClassName"

                                    val ktPsiFactory = KtPsiFactory(element.project)

                                    val parentKtClass = element.parentOfType<KtClass>()!!
                                    val parentKtClassBody = parentKtClass.body!!

                                    // Adds the property to the class after the last property OR after the opening brace
                                    val anchor = parentKtClassBody.properties.lastOrNull() ?: parentKtClassBody.lBrace
                                    val containingKtFile = parentKtClass.containingKtFile

                                    // Adds the import for the [...]Binding class
                                    containingKtFile.addAfter(
                                        ktPsiFactory.createImportDirective(
                                            ImportPath.fromString(qualifiedBinding)),
                                            containingKtFile.importDirectives.lastOrNull {
                                                !it.text.startsWith("import kotlinx.android.synthetic")
                                            }
                                                ?: containingKtFile.packageDirective
                                                ?: containingKtFile.firstChild
                                    )

                                    parentKtClass.addAfter(ktPsiFactory.createProperty("private lateinit", "binding", bindingClassName, true, null), anchor)

                                    // Adds the binding initializer
                                    val bindingInitializer = createBindingInitializer(ktPsiFactory, bindingClassName, parentKtClass)
                                    element.parent.addBefore(bindingInitializer, element)

                                    element.parent.addBefore(ktPsiFactory.createWhiteSpace(), element)
                                    element.parent.addBefore(ktPsiFactory.createNewLine(2), element)
                                    element.parent.addBefore(ktPsiFactory.createWhiteSpace(), element)

                                    // Replaces old setContentView(R.layout.[...]) with setContentView(binding.root)
                                    // TODO see if this is activity
                                    val newSetContentView = ktPsiFactory.createExpression("setContentView(this.binding.root)")
                                    element.parent.addBefore(newSetContentView, element)

                                    // Find all findViewById calls and replace with binding.[id]
                                    getFindViewByIdCalls(parentKtClassBody).forEach {
                                        val findViewByIdArgs = it.getValueArgumentsInParentheses() as List<KtValueArgument>
                                        val bindingIdPropertyName = ((findViewByIdArgs[0].children[0].children[1] as KtReferenceExpression).resolve() as PsiField).name.toCamelCaseAsVar()
                                        val bindingIdAccessor = "binding.$bindingIdPropertyName"

                                        it.replace(ktPsiFactory.createExpression(bindingIdAccessor))
                                    }

                                    // Find all kotlin synthetics accessors and replace with binding property accessors
                                    getKotlinSyntheticsAccessors(parentKtClass).forEach {
                                        // TODO resolve imports & their aliases
                                        // Hopefully they're not using import aliases that aren't a simple lowerCamelCase implementation
                                        val camelCaseId = it.text.toCamelCaseAsVar()
                                        val bindingIdAccessor = "binding.$camelCaseId"

                                        it.replace(ktPsiFactory.createExpression(bindingIdAccessor))
                                    }

                                    // Remove old kotlin synthetics imports
                                    getKotlinSyntheticsImports(containingKtFile).forEach {
                                        it.delete()
                                    }

                                    // Delete original setContentView
                                    element.delete()
                                }
                        } else {
                            showErrorNotification("Couldn't resolve the layout we'll be using as the value inside setContentView isn't an R.layout field")
                        }
                    }

                }
                // Java implementation
                else if (element is PsiMethodCallExpression) {
                    if (element.text.contains("setContentView")) {
                        references.add(element)
                    }
                } else {
                    super.visitElement(element)
                }
            }
        })

        MyDialogWrapper(references.map { it.toString() }).show()
    }

    private fun showErrorNotification(text: String) {
        NOTIFICATION_GROUP_SETCONTENTVIEW_TO_BINDING.createNotification(text, NotificationType.ERROR)
    }

    companion object {
        val NOTIFICATION_GROUP_SETCONTENTVIEW_TO_BINDING = NotificationGroup("setContentView-to-Binding", NotificationDisplayType.BALLOON, true)
    }
}

val log: (String) -> Unit = Logger.getInstance("DALLAS")
    .let { it::warn }

/**
 * Currently no real reason to have this, just was using it to better understand some UI bits here
 */
class MyDialogWrapper(
    private val lines: List<String>
) : DialogWrapper(true) {
    init {
        init()
        title = "Bologna Window"
    }

    override fun createCenterPanel(): JComponent? {
        return JBScrollPane(JBList(lines).apply {

        }).apply {
            preferredSize = Dimension(800, 800)
        }
    }

}
