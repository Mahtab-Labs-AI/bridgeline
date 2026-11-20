package com.wifiproxy.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes the live [UsbSessionController] while [ProxySessionService] is running, so
 * `ServiceSessionRepository` (and anything else in the UI process) can observe the session
 * without binding to the service over AIDL.
 */
object SessionHolder {
    private val _controller = MutableStateFlow<UsbSessionController?>(null)
    val controller: StateFlow<UsbSessionController?> = _controller.asStateFlow()

    internal fun bind(controller: UsbSessionController) {
        _controller.value = controller
    }

    internal fun unbind() {
        _controller.value = null
    }
}
