package org.dad.foundation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Settings view model + the service seam. The model owns load/ready/failed state, the
// toggle-and-save path (optimistic version bump), and the delete-account flow (confirm gate ->
// server delete -> local wipe), so it is unit-tested without a network or UI.

data class SettingsSnapshot(val profile: ProfileSummary, val preferences: Preferences)

/** The seam the settings model calls. A conformer maps to the generated client or a test double. */
interface SettingsService {
    suspend fun load(): SettingsSnapshot
    /** Save the preference block; returns the new snapshot (with the bumped version). */
    suspend fun savePreferences(preferences: Preferences): SettingsSnapshot
    /** Delete the account server-side (personal data removed, gift records kept). */
    suspend fun deleteAccount()
    /** Clear the local session (sign out / post-delete wipe). */
    suspend fun signOut()
}

sealed interface SettingsPhase {
    data object Loading : SettingsPhase
    data object Ready : SettingsPhase
    data class Failed(val message: UiText) : SettingsPhase
}

class SettingsModel(private val service: SettingsService) : ViewModel() {
    var phase: SettingsPhase by mutableStateOf(SettingsPhase.Loading)
        private set
    var snapshot: SettingsSnapshot? by mutableStateOf(null)
        private set
    /** True once the account is deleted or the donor signed out — the UI leaves the settings shell. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    var actionError: UiText? by mutableStateOf(null)
        private set
    /**
     * The server refused deletion (409) because a monthly gift is still set to renew or a checkout is open.
     * The screen then offers the real cancel-gift flow instead of a dead end; a retry clears it.
     */
    var deletionBlocked: Boolean by mutableStateOf(false)
        private set
    /** Why the last interest could not be added; cleared by the next add or remove. */
    var interestNotice: UiText? by mutableStateOf(null)
        private set
    private val saveLock = Mutex()
    private var leaving = false

    fun load() {
        phase = SettingsPhase.Loading
        viewModelScope.launch {
            try {
                snapshot = service.load()
                phase = SettingsPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                phase = SettingsPhase.Failed(message(error))
            }
        }
    }

    /** Flip one preference toggle and persist. Saves run one at a time against the latest snapshot,
     *  so rapid taps cannot overwrite each other with a stale version. On failure the old snapshot
     *  stays and the error surfaces inline. */
    fun toggle(key: String, value: Boolean) = save { Result.success(it.with(key, value)) }

    /**
     * Adds a free-form volunteer interest (E-1) and persists the list. Returns whether it was accepted, so the screen
     * can clear its field; a refusal leaves the reason in [interestNotice]. The rules run again against the latest
     * snapshot when the save's turn comes, so quick successive adds neither lose one nor exceed the limit.
     */
    fun addInterest(raw: String): Boolean {
        val current = snapshot?.preferences ?: return false
        val check = VolunteerInterests.add(current.volunteerInterests, raw)
        interestNotice = (check.exceptionOrNull() as? InterestRejected)?.reason?.message
        if (check.isFailure) return false
        save { latest -> VolunteerInterests.add(latest.volunteerInterests, raw).map { latest.copy(volunteerInterests = it) } }
        return true
    }

    fun removeInterest(interest: String) {
        interestNotice = null
        save { Result.success(it.copy(volunteerInterests = VolunteerInterests.remove(it.volunteerInterests, interest))) }
    }

    /** Runs one save against the latest snapshot; a [change] that no longer applies (a rule now failing) saves nothing. */
    private fun save(change: (Preferences) -> Result<Preferences>) {
        if (snapshot == null) return
        actionError = null
        viewModelScope.launch {
            saveLock.withLock {
                val current = snapshot ?: return@withLock
                val updated = change(current.preferences).getOrNull() ?: return@withLock
                try {
                    snapshot = service.savePreferences(updated)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionError = message(error)
                }
            }
        }
    }

    /** Delete the account only when the confirm gate is satisfied, then wipe the local session. */
    fun deleteAccount(gate: DeleteAccountGate) {
        if (!gate.isConfirmed) { actionError = UiText.res(R.string.settings_type_word_to_confirm, DeleteAccountGate.CONFIRM_WORD); return }
        if (leaving) return
        leaving = true
        actionError = null
        deletionBlocked = false
        viewModelScope.launch {
            try {
                service.deleteAccount()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                leaving = false
                deletionBlocked = error is AccountFailure.DeletionBlocked
                actionError = message(error)
                return@launch
            }
            // The account is gone; a failed remote revocation must not strand the donor in settings.
            try { service.signOut() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            signedOut = true
        }
    }

    fun signOut() {
        if (leaving) return
        leaving = true
        viewModelScope.launch {
            try {
                service.signOut()
                signedOut = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                leaving = false
                actionError = message(error)
            }
        }
    }

    private fun message(error: Exception): UiText = when (error) {
        is AccountFailure.SignedOut -> UiText.res(R.string.common_please_sign_in_again)
        is AccountFailure.RateLimited -> UiText.res(R.string.common_too_many_attempts_please_wait_a)
        is AccountFailure.DeletionBlocked ->
            UiText.res(R.string.settings_we_cant_delete_your_account_while)
        else -> UiText.res(R.string.common_something_went_wrong_please_try)
    }

    companion object {
        fun factory(service: SettingsService): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsModel(service) }
        }
    }
}
