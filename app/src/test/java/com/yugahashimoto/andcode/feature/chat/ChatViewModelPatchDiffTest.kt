package com.yugahashimoto.andcode.feature.chat

import com.yugahashimoto.andcode.core.api.OpenCodeAgent
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.OpenCodeFileChange
import com.yugahashimoto.andcode.core.api.OpenCodeHealth
import com.yugahashimoto.andcode.core.api.OpenCodeMessage
import com.yugahashimoto.andcode.core.api.OpenCodeSession
import com.yugahashimoto.andcode.core.api.OpenCodeTime
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.core.api.ProviderCatalog
import com.yugahashimoto.andcode.runtime.BackendKind
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeCapabilities
import com.yugahashimoto.andcode.runtime.RuntimeState
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import com.yugahashimoto.andcode.runtime.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Covers [ChatViewModel.openPatchDiff] and [ChatViewModel.dismissPatchDiff]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelPatchDiffTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a diff-capable backend loads the diff`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = true)
            backend.diffResult = listOf(OpenCodeFileChange(file = "A.kt", patch = "@@ -1 +1 @@\n-old\n+new"))
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            val diff = viewModel.uiState.value.patchDiff
            assertTrue(diff is PatchDiffState.Loaded)
            assertEquals(listOf("A.kt"), diff?.files)
            assertEquals(1, backend.sessionDiffCalls)
        }

    @Test
    fun `a backend without diff support never gets a call and shows unavailable immediately`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.patchDiff is PatchDiffState.Unavailable)
            assertEquals(0, backend.sessionDiffCalls)
        }

    @Test
    fun `a diff-capable backend that throws is treated as unavailable`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = true)
            backend.diffError = IllegalStateException("boom")
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.patchDiff is PatchDiffState.Unavailable)
        }

    @Test
    fun `an empty diff response is treated as unavailable rather than as no changes`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = true)
            backend.diffResult = emptyList()
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()

            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.patchDiff is PatchDiffState.Unavailable)
        }

    @Test
    fun `dismissing clears the dialog`() =
        runTest(dispatcher) {
            val backend = FakeTarget(diffCapable = false)
            val viewModel = ChatViewModel(backend = backend, eventFlow = backend.events).also { advanceUntilIdle() }
            viewModel.sendMessage("Hello")
            advanceUntilIdle()
            viewModel.openPatchDiff(ChatPart.Patch("p1", listOf("A.kt"), "m1"))
            advanceUntilIdle()

            viewModel.dismissPatchDiff()

            assertEquals(null, viewModel.uiState.value.patchDiff)
        }

    private class FakeTarget(diffCapable: Boolean) : RuntimeTarget {
        override val id: String = "fake"
        override val displayName: String = "Fake"
        override val kind: BackendKind = BackendKind.LOCAL
        override val type: RuntimeType = RuntimeType.LOCAL
        override val capabilities: RuntimeCapabilities = RuntimeCapabilities(diffCapable = diffCapable)
        private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Connected("test"))
        override val state: StateFlow<RuntimeState> = mutableState

        val events = MutableSharedFlow<OpenCodeEvent>(extraBufferCapacity = 20)
        var sessionDiffCalls = 0
        var diffResult: List<OpenCodeFileChange> = emptyList()
        var diffError: Throwable? = null

        override suspend fun connect(): Result<OpenCodeHealth> = Result.success(OpenCodeHealth(true, "test"))

        override fun disconnect() = Unit

        override suspend fun listWorkspaces(): List<WorkspaceRef> = emptyList()

        override suspend fun health(): OpenCodeHealth = OpenCodeHealth(true, "test")

        override suspend fun listSessions(directory: String?): List<OpenCodeSession> = emptyList()

        override suspend fun createSession(
            title: String?,
            directory: String?,
        ): OpenCodeSession = OpenCodeSession(id = "s1", title = title ?: "", directory = directory, time = OpenCodeTime(created = 1))

        override suspend fun listMessages(sessionId: String): List<OpenCodeMessage> = emptyList()

        override suspend fun listProviders(): ProviderCatalog = ProviderCatalog()

        override suspend fun listAgents(): List<OpenCodeAgent> = emptyList()

        override suspend fun sendMessage(
            sessionId: String,
            request: PromptRequest,
        ) = Unit

        override suspend fun abortSession(sessionId: String): Boolean = true

        override suspend fun respondToPermission(
            sessionId: String,
            permissionId: String,
            response: PermissionResponse,
            remember: Boolean,
        ): Boolean = true

        override suspend fun sessionDiff(
            sessionId: String,
            directory: String?,
            messageId: String?,
        ): List<OpenCodeFileChange> {
            sessionDiffCalls++
            diffError?.let { throw it }
            return diffResult
        }

        override fun events(): Flow<OpenCodeEvent> = events
    }
}
