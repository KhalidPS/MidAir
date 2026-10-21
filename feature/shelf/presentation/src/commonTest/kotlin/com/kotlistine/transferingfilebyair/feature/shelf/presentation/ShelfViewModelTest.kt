package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ShelfViewModelTest {

    @Test
    fun `starts with an empty shelf`() = runTest {
        val viewModel = ShelfViewModel()

        viewModel.state.test {
            assertThat(awaitItem()).isEqualTo(ShelfState(items = emptyList()))
        }
    }

    @Test
    fun `emits no events before any action`() = runTest {
        val viewModel = ShelfViewModel()

        viewModel.events.test {
            expectNoEvents()
        }
    }
}
