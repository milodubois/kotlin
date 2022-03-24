/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.test.framework.base

import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.TestDataFile
import junit.framework.ComparisonFailure
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.analyse
import org.jetbrains.kotlin.analysis.api.analyseInDependedAnalysisSession
import org.jetbrains.kotlin.analysis.test.framework.AnalysisApiTestDirectives
import org.jetbrains.kotlin.analysis.test.framework.TestWithDisposable
import org.jetbrains.kotlin.analysis.test.framework.project.structure.ktModuleProvider
import org.jetbrains.kotlin.analysis.test.framework.services.ExpressionMarkerProvider
import org.jetbrains.kotlin.analysis.test.framework.services.ExpressionMarkersSourceFilePreprocessor
import org.jetbrains.kotlin.analysis.test.framework.services.libraries.LibraryWasNotCompiledDueToExpectedCompilationError
import org.jetbrains.kotlin.analysis.test.framework.test.configurators.AnalysisApiTestConfigurator
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.TestInfrastructureInternals
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.builders.testConfiguration
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.ResultingArtifact
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerTest
import org.jetbrains.kotlin.test.services.*
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.JvmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.impl.TemporaryDirectoryManagerImpl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension

abstract class AbstractAnalysisApiBasedTest : TestWithDisposable() {
    abstract val configurator: AnalysisApiTestConfigurator

    private lateinit var testInfo: KotlinTestInfo

    protected lateinit var testDataPath: Path
        private set

    private lateinit var moduleStructure: TestModuleStructure

    protected open fun configureTest(builder: TestConfigurationBuilder) {
        configurator.configureTest(builder, disposable)
    }

    protected abstract fun doTestByFileStructure(moduleStructure: TestModuleStructure, testServices: TestServices)

    protected fun AssertionsService.assertEqualsToTestDataFileSibling(actual: String, extension: String = ".txt") {
        val testPrefix = configurator.testPrefix

        val expectedFile = getTestDataFileSiblingPath(extension, testPrefix = testPrefix)
        assertEqualsToFile(expectedFile, actual)

        if (testPrefix != null) {
            val expectedFileWithoutPrefix = getTestDataFileSiblingPath(extension, testPrefix = null)
            if (expectedFile != expectedFileWithoutPrefix) {
                try {
                    assertEqualsToFile(expectedFileWithoutPrefix, actual)
                } catch (ignored: ComparisonFailure) {
                    return
                }

                throw AssertionError("\"$expectedFile\" has the same content as \"$expectedFileWithoutPrefix\". Delete the prefixed file.")
            }
        }
    }

    protected fun getTestDataFileSiblingPath(extension: String, testPrefix: String?): Path {
        val extensionWithDot = "." + extension.removePrefix(".")
        val baseName = testDataPath.nameWithoutExtension

        if (testPrefix != null) {
            val prefixedFile = testDataPath.resolveSibling("$baseName.$testPrefix$extensionWithDot")
            if (prefixedFile.exists()) {
                return prefixedFile
            }
        }

        return testDataPath.resolveSibling(baseName + extensionWithDot)
    }

    @OptIn(TestInfrastructureInternals::class)
    private val configure: TestConfigurationBuilder.() -> Unit = {
        globalDefaults {
            frontend = FrontendKinds.FIR
            targetPlatform = JvmPlatforms.defaultJvmPlatform
            dependencyKind = DependencyKind.Source
        }
        useConfigurators(
            ::CommonEnvironmentConfigurator,
            ::JvmEnvironmentConfigurator,
        )
        assertions = JUnit5Assertions
        useAdditionalService<TemporaryDirectoryManager>(::TemporaryDirectoryManagerImpl)

        useDirectives(*AbstractKotlinCompilerTest.defaultDirectiveContainers.toTypedArray())
        useDirectives(JvmEnvironmentConfigurationDirectives)

        useSourcePreprocessor(::ExpressionMarkersSourceFilePreprocessor)
        useAdditionalService { ExpressionMarkerProvider() }

        registerAnalysisApiBaseTestServices(disposable, configurator)

        useDirectives(AnalysisApiTestDirectives)

        configureTest(this)

        startingArtifactFactory = { ResultingArtifact.Source() }
        this.testInfo = this@AbstractAnalysisApiBasedTest.testInfo
    }

    protected open fun handleInitializationError(exception: Throwable, moduleStructure: TestModuleStructure): InitializationErrorAction =
        InitializationErrorAction.THROW

    enum class InitializationErrorAction {
        IGNORE, THROW
    }

    protected fun runTest(@TestDataFile path: String) {
        testDataPath = configurator.preprocessTestDataPath(Paths.get(path))
        val testConfiguration = testConfiguration(path, configure)
        Disposer.register(disposable, testConfiguration.rootDisposable)
        val testServices = testConfiguration.testServices
        val moduleStructure =
            testConfiguration.moduleStructureExtractor.splitTestDataByModules(path, testConfiguration.directives)
        testServices.register(TestModuleStructure::class, moduleStructure)
        this.moduleStructure = moduleStructure
        testConfiguration.testServices.register(TestModuleStructure::class, moduleStructure)

        try {
            testConfiguration.preAnalysisHandlers.forEach { preprocessor -> preprocessor.preprocessModuleStructure(moduleStructure) }
        } catch(ignored: LibraryWasNotCompiledDueToExpectedCompilationError) {
            return
        }

        testConfiguration.preAnalysisHandlers.forEach { preprocessor ->
            preprocessor.prepareSealedClassInheritors(moduleStructure)
        }

        moduleStructure.modules.forEach { module ->
            val files = testServices.ktModuleProvider.getModuleFiles(module)
            configurator.prepareFilesInModule(files, module, testServices)
        }

        doTestByFileStructure(moduleStructure, testServices)
    }


    protected fun <R> analyseForTest(contextElement: KtElement, action: KtAnalysisSession.() -> R): R {
        return if (configurator.analyseInDependentSession
            && AnalysisApiTestDirectives.DISABLE_DEPENDED_MODE !in this.moduleStructure.allDirectives
        ) {
            val originalContainingFile = contextElement.containingKtFile
            val fileCopy = originalContainingFile.copy() as KtFile
            analyseInDependedAnalysisSession(originalContainingFile, PsiTreeUtil.findSameElementInCopy(contextElement, fileCopy), action)
        } else {
            analyse(contextElement, action)
        }
    }

    @BeforeEach
    fun initTestInfo(testInfo: TestInfo) {
        this.testInfo = KotlinTestInfo(
            className = testInfo.testClass.orElseGet(null)?.name ?: "_undefined_",
            methodName = testInfo.testMethod.orElseGet(null)?.name ?: "_testUndefined_",
            tags = testInfo.tags
        )
    }
}