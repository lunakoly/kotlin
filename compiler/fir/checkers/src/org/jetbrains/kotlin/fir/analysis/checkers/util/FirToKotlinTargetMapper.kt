/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.util

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget.*
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.getModifierList
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertySetter
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.types.FirStarProjection
import org.jetbrains.kotlin.fir.types.FirTypeProjection
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.lexer.KtTokens
import java.util.*

object FirToKotlinTargetMapper {

    class TargetSet(
        val defaultTargets: Set<KotlinTarget>,
        val canBeSubstituted: Set<KotlinTarget> = emptySet(),
        val onlyWithUseSiteTarget: Set<KotlinTarget> = emptySet()
    )

    object TargetSets {
        private class TargetSetBuilder(vararg val defaultTargets: KotlinTarget) {
            private var canBeSubstituted: MutableSet<KotlinTarget> = mutableSetOf()
            private var onlyWithUseSiteTarget: MutableSet<KotlinTarget> = mutableSetOf()

            fun canBeSubstituted(vararg targets: KotlinTarget) {
                canBeSubstituted.plusAssign(targets)
            }

            fun onlyWithUseSiteTarget(vararg targets: KotlinTarget) {
                onlyWithUseSiteTarget.plusAssign(targets)
            }

            private fun Collection<KotlinTarget>.toEnumSet(): Set<KotlinTarget> =
                if (this.isEmpty()) emptySet() else EnumSet.copyOf(this)

            private fun Array<out KotlinTarget>.toEnumSet(): Set<KotlinTarget> =
                this.toSet().toEnumSet()

            fun build() = TargetSet(defaultTargets.toEnumSet(), canBeSubstituted.toEnumSet(), onlyWithUseSiteTarget.toEnumSet())
        }

        private fun buildTargetSet(
            vararg defaultTargets: KotlinTarget,
            recordOtherTargets: TargetSetBuilder.() -> Unit = {}
        ): TargetSet {
            val builder = TargetSetBuilder(*defaultTargets)
            builder.recordOtherTargets()
            return builder.build()
        }

        val FILE_SET = buildTargetSet(FILE)

        val CLASS_SET = buildTargetSet(CLASS_ONLY, CLASS)
        val LOCAL_CLASS_SET = buildTargetSet(LOCAL_CLASS, CLASS)
        val INTERFACE_SET = buildTargetSet(INTERFACE, CLASS)
        val ENUM_CLASS_SET = buildTargetSet(ENUM_CLASS, CLASS)
        val ENUM_ENTRY_SET = buildTargetSet(ENUM_ENTRY, PROPERTY, FIELD)
        val ANNOTATION_CLASS_SET = buildTargetSet(ANNOTATION_CLASS, CLASS)
        val OBJECT_SET = buildTargetSet(OBJECT, CLASS)
        val COMPANION_OBJECT_SET = buildTargetSet(COMPANION_OBJECT, OBJECT, CLASS)

        val DESTRUCTURING_DECLARATION_SET = buildTargetSet(DESTRUCTURING_DECLARATION)

        val LOCAL_VARIABLE_SET = buildTargetSet(LOCAL_VARIABLE) {
            onlyWithUseSiteTarget(PROPERTY_SETTER, VALUE_PARAMETER)
        }

        private fun TargetSetBuilder.additionalPropertyTargets(hasBackingField: Boolean, hasDelegate: Boolean) {
            onlyWithUseSiteTarget(VALUE_PARAMETER, PROPERTY_GETTER, PROPERTY_SETTER)
            if (hasBackingField) canBeSubstituted(FIELD)
            if (hasDelegate) onlyWithUseSiteTarget(FIELD)
        }

        fun getMemberPropertySet(hasBackingField: Boolean, hasDelegate: Boolean): TargetSet =
            buildTargetSet(
                when {
                    hasBackingField -> MEMBER_PROPERTY_WITH_BACKING_FIELD
                    hasDelegate -> MEMBER_PROPERTY_WITH_DELEGATE
                    else -> MEMBER_PROPERTY_WITHOUT_FIELD_OR_DELEGATE
                },
                MEMBER_PROPERTY, PROPERTY
            ) {
                additionalPropertyTargets(hasBackingField, hasDelegate)
            }

        fun getTopLevelPropertySet(hasBackingField: Boolean, hasDelegate: Boolean): TargetSet =
            buildTargetSet(
                when {
                    hasBackingField -> TOP_LEVEL_PROPERTY_WITH_BACKING_FIELD
                    hasDelegate -> TOP_LEVEL_PROPERTY_WITH_DELEGATE
                    else -> TOP_LEVEL_PROPERTY_WITHOUT_FIELD_OR_DELEGATE
                },
                TOP_LEVEL_PROPERTY, PROPERTY
            ) {
                additionalPropertyTargets(hasBackingField, hasDelegate)
            }

        val LOCAL_FUNCTION_SET = buildTargetSet(LOCAL_FUNCTION, FUNCTION) {
            onlyWithUseSiteTarget(VALUE_PARAMETER)
        }
        val MEMBER_FUNCTION_SET = buildTargetSet(MEMBER_FUNCTION, FUNCTION) {
            onlyWithUseSiteTarget(VALUE_PARAMETER)
        }
        val TOP_LEVEL_FUNCTION_SET = buildTargetSet(TOP_LEVEL_FUNCTION, FUNCTION) {
            onlyWithUseSiteTarget(VALUE_PARAMETER)
        }

        val CONSTRUCTOR_SET = buildTargetSet(CONSTRUCTOR)
        val TYPEALIAS_SET = buildTargetSet(TYPEALIAS)

        val PROPERTY_GETTER_SET = buildTargetSet(PROPERTY_GETTER)
        val PROPERTY_SETTER_SET = buildTargetSet(PROPERTY_SETTER)

        val VALUE_PARAMETER_SET = buildTargetSet(VALUE_PARAMETER)

        val TYPE_REF_SET = buildTargetSet(TYPE) {
            onlyWithUseSiteTarget(VALUE_PARAMETER)
        }

        val TYPE_PARAMETER_SET = buildTargetSet(TYPE_PARAMETER)

        val TYPE_PROJECTION_SET = buildTargetSet(TYPE_PROJECTION)
        val STAR_PROJECTION_SET = buildTargetSet(STAR_PROJECTION)

        val INITIALIZER_SET = buildTargetSet(INITIALIZER)

        val EXPRESSION_SET = buildTargetSet(EXPRESSION)

        val EMPTY_SET = buildTargetSet()
    }

    // TODO Is extension receiver check necessary (and correct if so)?
    private fun isDeclarationTopLevel(element: FirDeclaration, context: CheckerContext): Boolean =
        context.containingDeclarations.last() is FirFile && (element !is FirCallableDeclaration<*> || element.receiverTypeRef == null)

    private fun isDeclarationLocal(context: CheckerContext): Boolean =
        context.containingDeclarations.last() is FirFunction<*>

    private val FirClass<*>.isInner: Boolean
        get() = source.getModifierList()?.modifiers?.find { it.token == KtTokens.INNER_KEYWORD } != null

    private val FirClass<*>.isCompanionObject: Boolean
        get() = source.getModifierList()?.modifiers?.find { it.token == KtTokens.COMPANION_KEYWORD } != null

    private val FirMemberDeclaration.isLocal: Boolean get() = status.visibility == Visibilities.LOCAL

    private val FirProperty.isDestructiveDeclaration: Boolean get() = name.asString() == "<destruct>"

    private val FirProperty.hasBackingField: Boolean
        get() {
            // based on the description of backing fields at https://kotlinlang.org/docs/reference/properties.html
            // TODO How to determine if the backing field was utilized in the property accessors?
            return getter is FirDefaultPropertyGetter || setter is FirDefaultPropertySetter
        }

    /*
     * see AnnotationChecker.getActualTargetList() and KotlinTarget.classActualTargets()
     */
    fun getTargetSet(element: FirElement, context: CheckerContext): TargetSet =
        when (element) {
            is FirFile -> TargetSets.FILE_SET
            is FirClass<*> -> when (element.classKind) { // TODO Should there be an analogue to T_CLASSIFIER like with KtClassOrObject in AnnotationChecker.getActualTargetList()?
                ClassKind.CLASS -> {
                    if (!element.isInner && isDeclarationLocal(context)) TargetSets.LOCAL_CLASS_SET else TargetSets.CLASS_SET
                }
                ClassKind.INTERFACE -> TargetSets.INTERFACE_SET
                ClassKind.ENUM_CLASS -> {
                    if (isDeclarationLocal(context)) TargetSets.LOCAL_CLASS_SET else TargetSets.ENUM_CLASS_SET
                }
                ClassKind.ENUM_ENTRY -> TargetSets.ENUM_ENTRY_SET
                ClassKind.ANNOTATION_CLASS -> TargetSets.ANNOTATION_CLASS_SET
                ClassKind.OBJECT -> {
                    if (element.isCompanionObject) TargetSets.COMPANION_OBJECT_SET else TargetSets.OBJECT_SET
                }
            }
            is FirProperty -> when {
                element.isDestructiveDeclaration -> TargetSets.DESTRUCTURING_DECLARATION_SET
                element.isLocal -> TargetSets.LOCAL_VARIABLE_SET
                isDeclarationTopLevel(element, context) -> {
                    TargetSets.getTopLevelPropertySet(element.hasBackingField, element.delegate != null)
                }
                else -> {
                    TargetSets.getMemberPropertySet(element.hasBackingField, element.delegate != null)
                }
            }
            is FirSimpleFunction -> when { // TODO Is this cast adequate or is it too narrowing in comparison to KtFunction?
                // isFunctionExpression() clause analogue is to be examined in future
                element.isLocal -> TargetSets.LOCAL_FUNCTION_SET
                isDeclarationTopLevel(element, context) -> TargetSets.TOP_LEVEL_FUNCTION_SET
                else -> TargetSets.MEMBER_FUNCTION_SET
            }
            is FirConstructor -> TargetSets.CONSTRUCTOR_SET
            is FirTypeAlias -> TargetSets.TYPEALIAS_SET
            is FirPropertyAccessor -> {
                if (element.isGetter) TargetSets.PROPERTY_GETTER_SET else TargetSets.PROPERTY_SETTER_SET
            }
            is FirValueParameter -> TargetSets.VALUE_PARAMETER_SET
            is FirTypeParameter -> TargetSets.TYPE_PARAMETER_SET
            is FirTypeProjection -> TargetSets.TYPE_PROJECTION_SET
            is FirStarProjection -> TargetSets.STAR_PROJECTION_SET
            is FirTypeRef -> TargetSets.TYPE_REF_SET
            is FirAnonymousInitializer -> TargetSets.INITIALIZER_SET
            // KtLambdaExpression clause analogue is to be examined in future
            // KtObjectLiteralExpression clause analogue is to be examined in future
            is FirExpression -> TargetSets.EXPRESSION_SET
            else -> TargetSets.EMPTY_SET
        }

    fun getDeclarationSiteTargets(element: FirElement, context: CheckerContext): Set<KotlinTarget> =
        getTargetSet(element, context).defaultTargets

}
