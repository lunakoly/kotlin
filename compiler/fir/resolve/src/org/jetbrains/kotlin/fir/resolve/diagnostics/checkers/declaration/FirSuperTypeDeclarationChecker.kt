/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.diagnostics.checkers.declaration

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.resolve.diagnostics.onSource
import org.jetbrains.kotlin.fir.resolve.transformers.firClassLike
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.types.model.dependsOnTypeConstructor


object FirSuperTypeDeclarationChecker : FirDeclarationChecker<FirMemberDeclaration>() {
    override fun check(declaration: FirMemberDeclaration, reporter: DiagnosticReporter) {
        if (declaration !is FirRegularClass) return

        val allowedFinalSupertypes = emptySet<FirMemberDeclaration?>() // TODO
        val checkedSupertypes = mutableSetOf<FirMemberDeclaration?>()

        var classAppeared = false
        var addSupertype = true

        for (supertype: FirTypeRef in declaration.superTypeRefs) {
            val supertypeDeclaration = supertype.firClassLike(declaration.session) as? FirRegularClass
            if (supertype.source != null) {
                if (supertypeDeclaration!!.classKind != ClassKind.INTERFACE) {
                    if (supertypeDeclaration.classKind != ClassKind.INTERFACE &&
                        !supertypeDeclaration.classKind.isSingleton &&
                        !declaration.isExpect && !declaration.isExternal && false
                                /* TODO: how to check init of super class */) {
                        reporter.report(Errors.SUPERTYPE_NOT_INITIALIZED, declaration.source)
                    }
                    if (declaration.classKind == ClassKind.ENUM_CLASS) {
                        reporter.report(Errors.CLASS_IN_SUPERTYPE_FOR_ENUM, declaration.source)
                        addSupertype = false
                    } else if (declaration.classKind == ClassKind.INTERFACE && !classAppeared /* TODO dynamic types */) {
                        reporter.report(Errors.INTERFACE_WITH_SUPERCLASS, declaration.source)
                        addSupertype = false
                    }

                    if (classAppeared) {
                        reporter.report(Errors.MANY_CLASSES_IN_SUPERTYPE_LIST, declaration.source)
                    } else {
                        classAppeared = true
                    }
                }
            }

            if (addSupertype && !checkedSupertypes.add(supertypeDeclaration)) {
                reporter.report(Errors.SUPERTYPE_APPEARS_TWICE, declaration.source)
            }

            if (supertype.source == null) return
            if (supertypeDeclaration!!.classKind.isSingleton) {
                if (supertypeDeclaration.classKind != ClassKind.ENUM_ENTRY) {
                    reporter.report(Errors.SINGLETON_IN_SUPERTYPE, declaration.source)
                }
            } else if (!allowedFinalSupertypes.contains(supertypeDeclaration)) {
                if (supertypeDeclaration.modality == Modality.SEALED) {
                    // TODO: analogue for containing declaration
                } else if (supertypeDeclaration.modality == Modality.FINAL) {
                    reporter.report(Errors.FINAL_SUPERTYPE, declaration.source)
                } // TODO: CLASS_CANNOT_BE_EXTENDED_DIRECTLY
            }
        }
    }

    private fun DiagnosticReporter.report(error: DiagnosticFactory0<*>, source: FirSourceElement?) {
        source?.let { report((error as DiagnosticFactory0<PsiElement>).onSource(it)) }
    }
}