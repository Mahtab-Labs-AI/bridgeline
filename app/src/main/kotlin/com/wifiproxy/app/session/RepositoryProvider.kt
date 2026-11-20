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
        _repository.value = create(demoMode)
    }

    private fun create(demoMode: Boolean) = if (demoMode) createFake() else createReal()
}
