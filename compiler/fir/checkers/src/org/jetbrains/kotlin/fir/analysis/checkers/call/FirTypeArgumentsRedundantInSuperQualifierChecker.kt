/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.call

import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.findClosestClassOrObject
import org.jetbrains.kotlin.fir.analysis.checkers.followAllAlias
import org.jetbrains.kotlin.fir.analysis.checkers.toRegularClass
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.resolve.transformers.firClassLike
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

object FirTypeArgumentsRedundantInSuperQualifierChecker : FirQualifiedAccessChecker() {
    override fun check(functionCall: FirQualifiedAccessExpression, context: CheckerContext, reporter: DiagnosticReporter) {
        /*
        The key idea.

        Suppose we have `super<T<K>>`.
        First, we get `T<K>` as a FirClass.
        Second, we take the closest class we're
        in and search its parents recursively trying to find that
        very `T` so that we can compare our actual
        `K` parameters with the ones used in this declaration.

        To be able to report diagnostics on the `K` type we also
        need to go there via nodes with source fields.
        */

        // require to be called over a super reference
        val superReference = functionCall.calleeReference.safeAs<FirSuperReference>()
            ?: return

        // `T<K>` in `super<T<K>>` with the source field
        val typeSourceReference = superReference.superTypeRef.safeAs<FirResolvedTypeRef>()
            ?.delegatedTypeRef.safeAs<FirUserTypeRef>()
            ?.qualifier
            ?.first()
            ?: return

        // no need to check anything if there're
        // no type parameters in super<T<...>>
        if (typeSourceReference.typeArguments.isEmpty()) {
            return
        }

        // `T<K>` in `super<T<K>>` as FirClass
        val typeReference = superReference.superTypeRef.safeAs<FirResolvedTypeRef>()
            ?.firClassLike(context.session)
            ?.followAllAlias(context.session).safeAs<FirClass<*>>()
            ?: return

        val closestType = context.findClosestClassOrObject()
            ?: return

        // the way `T<K>` was used as some super type
        val declaration = closestType.findSourceFor(typeReference, context).safeAs<FirResolvedTypeRef>()
            ?.type
            ?: return

        if (typeSourceReference.typeArguments.matches(declaration.typeArguments, context)) {
            reporter.reportRedundant(typeSourceReference.typeArguments.first().source)
        } else {
            reporter.reportInappropriate(typeSourceReference.typeArguments.first().source)
        }
    }

    /**
     * For the given FirClass<*> find the FirTypeRef among the parents
     * of `this` recursively. Returns null if no such a parent found.
     */
    private fun FirClass<*>.findSourceFor(type: FirClass<*>, context: CheckerContext): FirTypeRef? {
        fun FirClass<*>.findSourceFor(exclude: MutableSet<FirClass<*>>): FirTypeRef? {
            for (it in superTypeRefs) {
                val candidate = it.firClassLike(session)
                    ?.followAllAlias(session)
                    ?.safeAs<FirClass<*>>()
                    ?: continue

                if (candidate in exclude) {
                    continue
                }

                exclude.add(candidate)

                if (candidate == type) {
                    return it
                }

                val nested = candidate.findSourceFor(exclude)

                if (nested != null) {
                    return nested
                }
            }

            return null
        }

        return findSourceFor(mutableSetOf())
    }

    /**
     * Compares the two typeArguments lists: the one found
     * in the actual possibly illegal code and the one
     * found in the declaration.
     */
    private fun List<FirTypeProjection>.matches(
        other: Array<out ConeTypeProjection>,
        context: CheckerContext
    ): Boolean {
        if (size != other.size) {
            return false
        }

        // we fill the set with the arguments
        // found in the first list and check that all
        // the same arguments are present in the second list
        val found = mutableSetOf<FirRegularClass>()

        for (it in 0 until size) {
            // return false happens when
            // item leads to some unresolved reference
            val item = this[it].safeAs<FirTypeProjectionWithVariance>()
                ?.typeRef
                ?.toRegularClass(context.session)
                ?: return false
            found.add(item)
        }

        for (element in other) {
            val item = element.safeAs<ConeClassLikeType>()
                ?.toRegularClass(context.session)
                ?: return false

            if (item !in found) {
                return false
            }
        }

        return true
    }

    private fun DiagnosticReporter.reportRedundant(source: FirSourceElement?) {
        source?.let {
            report(FirErrors.TYPE_ARGUMENTS_REDUNDANT_IN_SUPER_QUALIFIER.on(it))
        }
    }

    private fun DiagnosticReporter.reportInappropriate(source: FirSourceElement?) {
        source?.let {
            report(FirErrors.INAPPROPRIATE_TYPE_ARGUMENT_IN_SUPER_QUALIFIER.on(it))
        }
    }
}