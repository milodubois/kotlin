/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.yarn

import org.gradle.api.GradleException
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener

abstract class YarnLockMismatchReportService : BuildService<BuildServiceParameters.None>, AutoCloseable, OperationCompletionListener {
    @Volatile
    private var shouldFailOnClose: Boolean = false

    fun failOnClose() {
        shouldFailOnClose = true
    }

    override fun onFinish(event: FinishEvent?) {
        // noop
    }

    override fun close() {
        if (shouldFailOnClose) {
            throw GradleException(YARN_LOCK_MISMATCH_MESSAGE)
        }
    }
}

internal val YARN_LOCK_MISMATCH_MESSAGE = "yarn.lock was changed"