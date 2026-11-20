package com.wifiproxy.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes the live [UsbSessionController] while [ProxySessionService] is running, so
 * `ServiceSessionRepository` (and anything else in the UI process) can observe the session
 * without binding to the service over AIDL.
 *
 * Deliberately has no `unbind()`: [ProxySessionService.onDestroy] used to null this out, but a
 * service that dies moments after a failed `start()` (C1's "port already in use" case, for
 * example) can go controller-bound -> idle -> controller-unbound fast enough that
 * `ServiceSessionRepository`'s `flatMapLatest` on this `StateFlow` never even observes the
 * intermediate non-null value - `StateFlow` only guarantees collectors see the *latest* value, not
 * every one, so a collector that hasn't yet been resumed when the next `bind`/`unbind` lands can
 * miss a whole generation, controller and all, including its `lastError`. Simply never nulling
 * this out avoids the problem entirely: the last controller (and whatever it has to show, like
 * `lastError`) just stays visible until [bind] replaces it with the next one, which - being a
 * single, lasting mutation rather than a bind-then-unbind pair - [ServiceSessionRepository] is
 * always guaranteed to observe.
 */
object SessionHolder {
    private val _controller = MutableStateFlow<UsbSessionController?>(null)
    val controller: StateFlow<UsbSessionController?> = _controller.asStateFlow()

    internal fun bind(controller: UsbSessionController) {
        _controller.value = controller
    }
}
