package com.kotlistine.transferingfilebyair.core.transfer.data

import com.kotlistine.transferingfilebyair.core.transfer.domain.LinkGeometry
import com.kotlistine.transferingfilebyair.core.transfer.domain.SlideLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the lined-up geometry while the app runs; it starts from the defaults again next time. */
class InMemoryLinkGeometry(initial: SlideLink = SlideLink()) : LinkGeometry {
    private val current = MutableStateFlow(initial)
    override val link: StateFlow<SlideLink> = current.asStateFlow()

    override fun update(link: SlideLink) {
        current.value = link
    }
}
