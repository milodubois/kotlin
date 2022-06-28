/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.references

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.fir.KtFirAnalysisSession
import org.jetbrains.kotlin.analysis.api.fir.symbols.KtFirSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.asJava.canHaveSyntheticGetter
import org.jetbrains.kotlin.asJava.canHaveSyntheticSetter
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

class KtFirSyntheticPropertyAccessorReference(
    expression: KtNameReferenceExpression,
    isGetter: Boolean,
) : SyntheticPropertyAccessorReference(expression, isGetter), KtFirReference {

    override fun KtAnalysisSession.resolveToSymbols(): Collection<KtSymbol> {
        check(this is KtFirAnalysisSession)
        return FirReferenceResolveHelper.resolveSimpleNameReferenceExpression(expression, analysisSession = this)
    }

    override fun getResolvedToPsi(analysisSession: KtAnalysisSession): Collection<PsiElement> = with(analysisSession) {
        resolveToSymbols().mapNotNull { symbol ->
            if (symbol is KtFirSyntheticJavaPropertySymbol) {
                if (getter) symbol.javaGetterSymbol.psi
                else symbol.javaSetterSymbol?.psi
            } else {
                null
            }
        }
    }

    override fun canBeReferenceTo(candidateTarget: PsiElement): Boolean = when {
        candidateTarget !is PsiMethod -> false
        !isAccessorName(candidateTarget.name) -> false
        getter -> candidateTarget.canHaveSyntheticGetter
        else -> candidateTarget.canHaveSyntheticSetter
    }
}