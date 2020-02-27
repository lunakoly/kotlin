/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.js.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.ir.backend.js.JsIrBackendContext
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

private inline class Arity(val p: Pair<Int, Int>) {

    constructor(t: Int, a: Int) : this(Pair(t, a))

    val total get() = p.first
    val arguments get() = p.second
    val arity get() = total - arguments
}

class PropertyReferenceLowering(private val context: JsIrBackendContext) : BodyLoweringPass {

    private val implicitDeclarationFile = context.implicitDeclarationFile
    private val referenceBuilderSymbol = context.kpropertyBuilder
    private val jsClassSymbol = context.intrinsics.jsClass

    private val throwISE = context.throwISEsymbol

    private val newDeclarations = mutableListOf<IrDeclaration>()

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        newDeclarations.clear()
        irBody.transformChildrenVoid(PropertyReferenceTransformer())
        implicitDeclarationFile.declarations.addAll(newDeclarations)
    }


    private inner class PropertyReferenceTransformer : IrElementTransformerVoid() {

        private fun computeArity(reference: IrPropertyReference, getter: IrSimpleFunction): Arity {
            var total = 0
            if (getter.dispatchReceiverParameter != null) total++
            if (getter.extensionReceiverParameter != null) total++

            var arguments = 0
            if (reference.dispatchReceiver != null) arguments++
            if (reference.extensionReceiver != null) arguments++

            return Arity(total, arguments)
        }

        private fun buildFactoryFunction(reference: IrPropertyReference, mutable: Boolean, arity: Arity): IrSimpleFunction {
            val property = reference.symbol.owner

            val factoryDeclaration = buildFun {
                startOffset = reference.startOffset
                endOffset = reference.endOffset
                returnType = reference.type
                name = Name.identifier("${property.name.asString()}\$factory")
            }

            factoryDeclaration.parent = implicitDeclarationFile

            val boundArguments = listOfNotNull(reference.dispatchReceiver, reference.extensionReceiver)

            val valueParameters = ArrayList<IrValueParameter>(boundArguments.size)
            factoryDeclaration.valueParameters = valueParameters

            for ((i, arg) in boundArguments.withIndex()) {
                val vp = buildValueParameter {
                    type = arg.type
                    index = i
                    name = Name.identifier("\$b$i")
                }
                vp.parent = factoryDeclaration
                valueParameters.add(vp)
            }

            // TODO: type parameters

//            factoryDeclaration.typeParameters = constructor.typeParameters.map {
//                it.copyToWithoutSuperTypes(factoryDeclaration).also { tp ->
//                    // TODO: make sure it is done well
//                    tp.superTypes += it.superTypes
//                }
//            }

            // 0 - name
            // 1 - paramCount
            // 2 - type
            // 3 - getter
            // 4 - setter

            val irBuilder = context.createIrBuilder(factoryDeclaration.symbol)
            factoryDeclaration.body = irBuilder.irBlockBody {
                +irReturn(irCall(referenceBuilderSymbol).apply {
                    putValueArgument(0, reference.nameExpression())
                    putValueArgument(1, irInt(arity.arity))
                    putValueArgument(2, reference.getJsTypeConstructor())
                    putValueArgument(3, buildGetterLambda(factoryDeclaration, reference, valueParameters))
                    putValueArgument(4, buildSetterLambda(factoryDeclaration, reference, valueParameters))
                })
            }

            newDeclarations.add(factoryDeclaration)

            return factoryDeclaration
        }

        private fun buildGetterLambda(factory: IrSimpleFunction, reference: IrPropertyReference, boundValueParameters: List<IrValueParameter>): IrExpression {
            val getter = reference.getter?.owner ?: error("Getter expected")
            val classifier = (reference.type as IrSimpleType).classOrNull ?: error("Simple type expected")
            val supperGetter = classifier.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == "get" }

            val function = buildFun {
                startOffset = reference.startOffset
                endOffset = reference.endOffset
                returnType = supperGetter.returnType
                name = supperGetter.name
            }

            function.parent = factory

            var total = 0
            if (getter.dispatchReceiverParameter != null) total++
            if (getter.extensionReceiverParameter != null) total++
            val arity = total - boundValueParameters.size

            val unboundValueParameters = supperGetter.valueParameters.map { it.copyTo(function) }
            function.valueParameters = unboundValueParameters

            var b = 0
            var u = 0

            val irBuilder = context.createIrBuilder(function.symbol)
            function.body = irBuilder.irBlockBody {
                val irGetterCall = irCall(getter.symbol)

                if (getter.dispatchReceiverParameter != null) {
                    irGetterCall.dispatchReceiver =
                        if (reference.dispatchReceiver != null) irGet(boundValueParameters[b++]) else irGet(unboundValueParameters[u++])
                }

                if (getter.extensionReceiverParameter != null) {
                    irGetterCall.extensionReceiver =
                        if (reference.extensionReceiver != null) irGet(boundValueParameters[b++]) else irGet(unboundValueParameters[u++])
                }

                assert(u == arity)
                assert((u + b) == total)

                +irReturn(irGetterCall)
            }

            return IrFunctionExpressionImpl(
                reference.startOffset,
                reference.endOffset,
                context.irBuiltIns.anyType,
                function,
                IrStatementOrigin.LAMBDA
            )
        }

        private fun buildSetterLambda(factory: IrSimpleFunction, reference: IrPropertyReference, boundValueParameters: List<IrValueParameter>): IrExpression {
            val setter = reference.run {
                setter?.owner ?: return IrConstImpl.constNull(startOffset, endOffset, context.irBuiltIns.nothingNType)
            }

            val classifier = (reference.type as IrSimpleType).classOrNull ?: error("Simple type expected")
            val supperSetter = classifier.owner.declarations.filterIsInstance<IrSimpleFunction>().single { it.name.asString() == "set" }

            val function = buildFun {
                startOffset = reference.startOffset
                endOffset = reference.endOffset
                returnType = supperSetter.returnType
                name = supperSetter.name
            }

            function.parent = factory

            var total = 1
            if (setter.dispatchReceiverParameter != null) total++
            if (setter.extensionReceiverParameter != null) total++
            val arity = total - boundValueParameters.size

            val unboundValueParameters = supperSetter.valueParameters.map { it.copyTo(function) }
            function.valueParameters = unboundValueParameters

            var b = 0
            var u = 0

            val irBuilder = context.createIrBuilder(function.symbol)
            function.body = irBuilder.irBlockBody {
                val irSetterCall = irCall(setter.symbol)

                if (setter.dispatchReceiverParameter != null) {
                    irSetterCall.dispatchReceiver =
                        if (reference.dispatchReceiver != null) irGet(boundValueParameters[b++]) else irGet(unboundValueParameters[u++])
                }

                if (setter.extensionReceiverParameter != null) {
                    irSetterCall.extensionReceiver =
                        if (reference.extensionReceiver != null) irGet(boundValueParameters[b++]) else irGet(unboundValueParameters[u++])
                }

                irSetterCall.putValueArgument(0, irGet(unboundValueParameters[u++]))

                assert(u == arity)
                assert((u + b) == total)

                +irReturn(irSetterCall)
            }

            return IrFunctionExpressionImpl(
                reference.startOffset,
                reference.endOffset,
                context.irBuiltIns.anyType,
                function,
                IrStatementOrigin.LAMBDA
            )
        }

        private fun IrPropertyReference.nameExpression(): IrExpression {
            val propertyName = symbol.owner.name.asString()
            return IrConstImpl.string(startOffset, endOffset, context.irBuiltIns.stringType, propertyName)
        }

        private fun IrExpression.getJsTypeConstructor(): IrExpression {
            val irCall = IrCallImpl(startOffset, endOffset, jsClassSymbol.owner.returnType, jsClassSymbol, 1, 0)
            irCall.putTypeArgument(0, type)
            return irCall
        }

        override fun visitPropertyReference(expression: IrPropertyReference): IrExpression {
            expression.transformChildrenVoid(this)
            val property = expression.symbol
            val getter = expression.getter?.owner ?: error("Expected getter for property ${property.owner.render()}")
            val isMutable = expression.setter != null
            val arity = computeArity(expression, getter)

            val factoryFunction = buildFactoryFunction(expression, isMutable, arity)

            assert(expression.valueArgumentsCount == 0)

            return IrCallImpl(
                expression.startOffset,
                expression.endOffset,
                expression.type,
                factoryFunction.symbol,
                expression.typeArgumentsCount,
                arity.arguments
            ).apply {
                for (ti in 0 until typeArgumentsCount) {
                    putTypeArgument(ti, expression.getTypeArgument(ti))
                }

                var vi = 0
                expression.dispatchReceiver?.let { putValueArgument(vi++, it) }
                expression.extensionReceiver?.let { putValueArgument(vi++, it) }

                assert(vi == arity.arguments)
            }
        }

        override fun visitLocalDelegatedPropertyReference(expression: IrLocalDelegatedPropertyReference): IrExpression {
            expression.transformChildrenVoid(this)

            val builderCall = expression.run {
                IrCallImpl(startOffset, endOffset, type, referenceBuilderSymbol, typeArgumentsCount, 5)
            }

            // 0 - name
            // 1 - paramCount
            // 2 - type
            // 3 - getter
            // 4 - setter


            expression.run {
                builderCall.putValueArgument(0, IrConstImpl.string(startOffset, endOffset, context.irBuiltIns.stringType, expression.symbol.owner.name.asString()))
                builderCall.putValueArgument(1, IrConstImpl.int(startOffset, endOffset, context.irBuiltIns.intType, 0))
                builderCall.putValueArgument(2, expression.getJsTypeConstructor())
                builderCall.putValueArgument(3, buildLocalDelegateLambda(expression))
                builderCall.putValueArgument(4, IrConstImpl.constNull(startOffset, endOffset, context.irBuiltIns.nothingNType))
            }

            return builderCall
        }

        private fun buildLocalDelegateLambda(expression: IrLocalDelegatedPropertyReference): IrExpression {
            val delegatedVar = expression.delegate.owner
            val function = buildFun {
                startOffset = expression.startOffset
                endOffset = expression.endOffset
                returnType = context.irBuiltIns.nothingType
                name = Name.identifier("${delegatedVar.name}\$stub")
            }

            function.parent = delegatedVar.parent

            function.body = with(context.createIrBuilder(function.symbol)) {
                irBlockBody {
                    +irReturn(irCall(throwISE))
                }
            }

            return expression.run {
                IrFunctionExpressionImpl(startOffset, endOffset, context.irBuiltIns.anyType, function, IrStatementOrigin.LAMBDA)
            }
        }
    }
}