package com.sednium.kryptonide.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus for cross-composable tab and screen navigation triggered from notifications or external intents.
 */
object NavigationBus {
    private val _events = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 2)
    val events = _events.asSharedFlow()

    fun navigate(tab: String) {
        _events.tryEmit(tab)
    }
}
