package com.wifiproxy.app.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Switches between the real [ServiceSessionRepository] and [FakeSessionRepository] by recreating
 * the repository, rather than one repository implementation serving both. [createReal] and
 * [createFake] are injected (no Android dependency here) so this is plain, pure logic.
 */
class RepositoryProvider(
    private val createReal: () -> SessionRepository,
    private val createFake: () -> SessionRepository,
    initialDemoMode: Boolean,
) {
    private val _repository = MutableStateFlow(create(initialDemoMode))
    val repository: StateFlow<SessionRepository> = _repository.asStateFlow()

    fun setDemoMode(demoMode: Boolean) {
        val old = _repository.value
        val new = create(demoMode)
        // Cancel the outgoing repository's own collectors/pollers before swapping it out (I7):
        // closing after would leave a window where both repositories are doing live work at once,
        // and closing it at all is what keeps a demo <-> real round trip from piling up abandoned
        // work on the shared parent scope.
        old.close()
        _repository.value = new
    }

    private fun create(demoMode: Boolean) = if (demoMode) createFake() else createReal()
}
