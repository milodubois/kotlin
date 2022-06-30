/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.references

import com.intellij.psi.PsiReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.resolve.references.ReferenceAccess

class KotlinFirReferenceContributor : KotlinReferenceProviderContributor {
    override fun registerReferenceProviders(registrar: KotlinPsiReferenceRegistrar) {
        with(registrar) {
            registerProvider(factory = ::KtFirSimpleNameReference)
            registerProvider(factory = ::KtFirForLoopInReference)
            registerProvider(factory = ::KtFirInvokeFunctionReference)
            registerProvider(factory = ::KtFirPropertyDelegationMethodsReference)
            registerProvider(factory = ::KtFirDestructuringDeclarationReference)
            registerProvider(factory = ::KtFirArrayAccessReference)
            registerProvider(factory = ::KtFirConstructorDelegationReference)
            registerProvider(factory = ::KtFirCollectionLiteralReference)

            registerMultiProvider<KtNameReferenceExpression> { nameReferenceExpression ->
                when (nameReferenceExpression.readWriteAccess(useResolveForReadWrite = true)) {
                    ReferenceAccess.READ -> arrayOf(KtFirSyntheticPropertyAccessorReference(nameReferenceExpression, isGetter = true))
                    ReferenceAccess.WRITE -> arrayOf(KtFirSyntheticPropertyAccessorReference(nameReferenceExpression, isGetter = false))
                    ReferenceAccess.READ_WRITE -> arrayOf(
                        KtFirSyntheticPropertyAccessorReference(nameReferenceExpression, isGetter = true),
                        KtFirSyntheticPropertyAccessorReference(nameReferenceExpression, isGetter = false)
                    )
                }
            }
        }
    }
}
