package com.collinpendleton.yana.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.collinpendleton.yana.data.userMessage

/** What a screen that reads one thing from the server shows. */
data class Loaded<T>(
    val data: T? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Loads once when created and again on pull-to-refresh, keeping the last
 * good data on screen while a refresh runs or fails.
 */
class Loader<T>(private val fetch: suspend () -> T) : ViewModel() {
    private val state = MutableStateFlow(Loaded<T>())
    val loaded: StateFlow<Loaded<T>> = state.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun reload(pull: Boolean = false) {
        job?.cancel()
        state.value = state.value.copy(loading = state.value.data == null, refreshing = pull, error = null)
        job = viewModelScope.launch {
            state.value = try {
                Loaded(data = fetch(), loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value.copy(loading = false, refreshing = false, error = e.userMessage())
            }
        }
    }
}
