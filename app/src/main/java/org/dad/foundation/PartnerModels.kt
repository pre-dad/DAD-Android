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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

// Partner portal view models + the service seam. The models own load/ready/failed state, role-gated actions
// and push register/unregister, so they are unit-tested without a network or UI — the Kotlin counterpart of
// the iOS partner models.

private val RATE_LIMITED = UiText.res(R.string.common_too_many_attempts_please_wait_a)
private val SOMETHING_WENT_WRONG = UiText.res(R.string.common_something_went_wrong_please_try)

data class PartnerSnapshot(
    val overview: PartnerOverview,
    val initiatives: List<PartnerInitiative>,
    val spend: List<PartnerSpendCategory>,
    val people: PartnerPeople?,
    val teams: List<TeamStanding>,
    val users: List<PartnerUser>,
    val exports: List<PartnerExport>,
    val logo: ByteArray?,
    /** True when the overview loaded but at least one other section failed to load. */
    val sectionsUnavailable: Boolean,
) {
    val selfId: String? get() = overview.userId
}

/** One earlier report export (P-14). [downloadable] is false once its retained PDF has aged out. */
data class PartnerExport(
    val id: String,
    val createdAt: OffsetDateTime,
    val exportedBy: String,
    val committedMinor: Long,
    val deployedMinor: Long,
    val downloadable: Boolean,
)

interface PartnerService {
    suspend fun overview(): PartnerOverview
    suspend fun initiatives(): List<PartnerInitiative>
    suspend fun spend(): List<PartnerSpendCategory>
    suspend fun people(): PartnerPeople
    /** The company team leaderboard (EM-5); teams under five members are left out server-side. */
    suspend fun teams(): List<TeamStanding>
    suspend fun users(): List<PartnerUser>
    /** Admin-only (P-13). The model gates on role before calling, but the server re-checks. */
    suspend fun inviteUser(email: String, name: String, role: PartnerRole)
    suspend fun removeUser(id: String)
    /** Admin-only (§7.2): the organization's name and description. */
    suspend fun saveProfile(organization: String, description: String)
    /** Admin-only: the headcount enrollment percentages are measured against (EM-3). */
    suspend fun saveWorkforce(size: Int)
    /** The organization's logo as PNG, or null when it has none. */
    suspend fun logo(): ByteArray?
    /** Admin-only: a PNG of at most 512 by 512 pixels and 256 KiB, or null to remove the logo. */
    suspend fun saveLogo(png: ByteArray?)
    /** Admin-only: earlier report exports, newest first. */
    suspend fun exports(): List<PartnerExport>
    /** Admin-only: a new PDF impact report (recorded as an export). */
    suspend fun report(): ByteArray
    suspend fun retainedReport(id: String): ByteArray
    suspend fun signOut()
    /** Partner push (§7.5). Register on sign-in, clear on sign-out; both native-only. */
    suspend fun registerPushDevice(token: String)
    suspend fun unregisterPushDevice()
}

sealed interface PartnerPhase {
    data object Loading : PartnerPhase
    data object Ready : PartnerPhase
    data class Failed(val message: UiText) : PartnerPhase
}

/** A PDF ready for the share sheet; [id] tells two reports apart even when their bytes match. */
class PartnerReport(val id: Long, val name: String, val pdf: ByteArray)

class PartnerModel(private val service: PartnerService) : ViewModel() {
    var phase: PartnerPhase by mutableStateOf(PartnerPhase.Loading)
        private set
    var snapshot: PartnerSnapshot? by mutableStateOf(null)
        private set
    var actionError: UiText? by mutableStateOf(null)
        private set
    /** Confirms the last completed action ("Organization profile saved."). */
    var notice: UiText? by mutableStateOf(null)
        private set
    var isBusy: Boolean by mutableStateOf(false)
        private set
    /** True once the session ended (signed out, expired or revoked): the app returns to sign-in. */
    var signedOut: Boolean by mutableStateOf(false)
        private set
    var pendingRemoval: PartnerUser? by mutableStateOf(null)
        private set
    var report: PartnerReport? by mutableStateOf(null)
        private set
    private var reports = 0L

    /** The current role, defaulting to the least-privileged VIEWER until the overview loads, so
     *  capability gates never open early. */
    val role: PartnerRole get() = snapshot?.overview?.role ?: PartnerRole.VIEWER

    private var loading: Job? = null

    fun load() {
        if (loading?.isActive == true) return
        phase = PartnerPhase.Loading
        loading = viewModelScope.launch {
            try {
                // Overview first: it carries the role every other section and action is gated on.
                val overview = service.overview()
                var partial = false
                // A secondary read: a failure hides that section rather than the whole portal; cancellation
                // and an ended session still propagate.
                suspend fun <T> optional(fallback: T, read: suspend () -> T): T = try {
                    read()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AccountFailure.SignedOut) {
                    throw error
                } catch (_: Exception) {
                    partial = true
                    fallback
                }
                val loaded = coroutineScope {
                    val initiatives = async { optional(emptyList()) { service.initiatives() } }
                    val spend = async { optional(emptyList()) { service.spend() } }
                    val people = async { optional(null) { service.people() } }
                    val teams = async { optional(emptyList()) { service.teams() } }
                    val users = async { optional(emptyList()) { service.users() } }
                    val exports = async { if (overview.role.canExport) optional(emptyList()) { service.exports() } else emptyList() }
                    val logo = async { if (overview.hasLogo) optional(null) { service.logo() } else null }
                    PartnerSnapshot(overview, initiatives.await(), spend.await(), people.await(), teams.await(), users.await(),
                        exports.await(), logo.await(), sectionsUnavailable = false)
                }
                snapshot = loaded.copy(sectionsUnavailable = partial)
                phase = PartnerPhase.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountFailure.SignedOut) {
                signedOut = true
                phase = PartnerPhase.Failed(SESSION_ENDED)
            } catch (error: Exception) {
                phase = PartnerPhase.Failed(message(error))
            }
        }
    }

    /** Runs one portal action: one at a time, errors inline, and a lost session leaves the portal. */
    private fun act(success: UiText?, invalid: UiText? = null, conflict: UiText? = null, work: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        actionError = null
        notice = null
        viewModelScope.launch {
            try {
                work()
                notice = success
            } catch (error: CancellationException) {
                throw error
            } catch (_: AccountFailure.SignedOut) {
                signedOut = true
            } catch (error: Exception) {
                actionError = when {
                    error is AccountFailure.InvalidInput && invalid != null -> invalid
                    error is AccountFailure.Conflict && conflict != null -> conflict
                    error is AccountFailure.Forbidden -> UiText.res(R.string.partner_only_an_admin_can_do_that)
                    else -> message(error)
                }
            } finally {
                isBusy = false
            }
        }
    }

    private fun update(change: (PartnerSnapshot) -> PartnerSnapshot) { snapshot = snapshot?.let(change) }

    /** Invite a user (P-13), admin-only. Gated client-side; the server re-checks. */
    fun inviteUser(email: String, name: String, role: PartnerRole, onInvited: () -> Unit = {}) {
        if (!this.role.canManageUsers) { actionError = UiText.res(R.string.partner_only_an_admin_can_invite_users); return }
        if (!isPlausibleEmail(email)) { actionError = UiText.res(R.string.partner_enter_a_valid_email_address); return }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) { actionError = UiText.res(R.string.partner_enter_their_name); return }
        val address = email.trim()
        act(UiText.res(R.string.partner_invitation_sent_to, address), invalid = UiText.res(R.string.partner_check_the_name_and_email_address),
            conflict = UiText.res(R.string.partner_that_email_already_signs_in_to_dad)) {
            service.inviteUser(address, trimmedName, role)
            onInvited()
            val users = runCatching { service.users() }.getOrNull()
            if (users != null) update { it.copy(users = users) }
        }
    }

    /** Whether [user] may be removed by the signed-in admin: never yourself, and never the last admin. */
    fun canRemove(user: PartnerUser): Boolean = role.canManageUsers && removalBlock(user.id) == null

    private fun removalBlock(id: String): UiText? {
        val current = snapshot ?: return UiText.res(R.string.partner_you_cant_remove_this_user)
        if (id == current.selfId) return UiText.res(R.string.partner_you_cant_remove_yourself)
        val user = current.users.firstOrNull { it.id == id } ?: return UiText.res(R.string.partner_you_cant_remove_this_user)
        if (user.role == PartnerRole.ADMIN && current.users.count { it.role == PartnerRole.ADMIN } <= 1) return UiText.res(R.string.partner_you_cant_remove_the_last_admin)
        return null
    }

    /** Step one of removal: stage the user for the inline confirmation. Nothing is sent yet. */
    fun requestRemoval(id: String) {
        if (!role.canManageUsers) { actionError = UiText.res(R.string.partner_only_an_admin_can_remove_users); return }
        removalBlock(id)?.let { actionError = it; return }
        actionError = null
        pendingRemoval = snapshot?.users?.firstOrNull { it.id == id }
    }

    fun cancelRemoval() { pendingRemoval = null }

    fun confirmRemoval() {
        val user = pendingRemoval ?: return
        pendingRemoval = null
        removeUser(user.id)
    }

    fun removeUser(id: String) {
        if (!role.canManageUsers) { actionError = UiText.res(R.string.partner_only_an_admin_can_remove_users); return }
        removalBlock(id)?.let { actionError = it; return }
        act(null, conflict = UiText.res(R.string.partner_you_cant_remove_the_last_admin)) {
            service.removeUser(id)
            update { it.copy(users = it.users.filterNot { u -> u.id == id }) }
        }
    }

    fun saveProfile(organization: String, description: String) {
        if (!role.canEditProfile) { actionError = UiText.res(R.string.partner_only_an_admin_can_edit_the); return }
        val name = organization.trim()
        val about = description.trim()
        if (name.length !in 1..200 || about.length > 4000) { actionError = PROFILE_RULE; return }
        act(UiText.res(R.string.partner_organization_profile_saved), invalid = PROFILE_RULE) {
            service.saveProfile(name, about)
            val overview = service.overview()
            update { it.copy(overview = overview) }
        }
    }

    fun saveWorkforce(raw: String) {
        if (!role.canEditProfile) { actionError = UiText.res(R.string.partner_only_an_admin_can_set_the); return }
        val size = raw.trim().toIntOrNull()?.takeIf { it >= 0 } ?: run { actionError = WORKFORCE_RULE; return }
        act(UiText.res(R.string.partner_workforce_size_saved), invalid = WORKFORCE_RULE) {
            service.saveWorkforce(size)
            val overview = service.overview()
            val people = runCatching { service.people() }.getOrNull()
            update { it.copy(overview = overview, people = people ?: it.people) }
        }
    }

    /** [png] is already scaled to fit the server's limits; null removes the logo. */
    fun saveLogo(png: ByteArray?) {
        if (!role.canEditProfile) { actionError = UiText.res(R.string.partner_only_an_admin_can_change_the_logo); return }
        act(if (png == null) UiText.res(R.string.partner_logo_removed) else UiText.res(R.string.partner_logo_saved), invalid = LOGO_RULE) {
            service.saveLogo(png)
            update { it.copy(logo = png) }
        }
    }

    /** The picked image could not be read or scaled to the logo limits. */
    fun rejectLogo() {
        notice = null
        actionError = LOGO_RULE
    }

    fun exportReport() {
        if (!role.canExport) { actionError = UiText.res(R.string.partner_only_an_admin_can_export_a_report); return }
        act(null) {
            report = PartnerReport(++reports, "dad-impact-report.pdf", service.report())
            val exports = runCatching { service.exports() }.getOrNull()
            if (exports != null) update { it.copy(exports = exports) }
        }
    }

    fun download(export: PartnerExport) {
        if (!role.canExport || !export.downloadable) return
        act(null) {
            try {
                report = PartnerReport(++reports, "dad-impact-report-${export.id.take(8)}.pdf", service.retainedReport(export.id))
            } catch (_: AccountFailure.NotFound) {
                actionError = UiText.res(R.string.partner_that_report_is_no_longer_available)
                val exports = runCatching { service.exports() }.getOrNull()
                if (exports != null) update { it.copy(exports = exports) }
            }
        }
    }

    /** The share sheet has the report; forget the bytes. */
    fun reportShared() { report = null }

    fun signOut() {
        viewModelScope.launch {
            bestEffort { service.unregisterPushDevice() }
            bestEffort { service.signOut() }
            signedOut = true
        }
    }

    /** Register this device for partner push after sign-in (§7.5). Best-effort — never blocks. */
    fun registerPush(token: String) {
        viewModelScope.launch { bestEffort { service.registerPushDevice(token) } }
    }

    private suspend fun bestEffort(action: suspend () -> Unit) {
        try { action() } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }

    private fun message(error: Exception): UiText = when (error) {
        is AccountFailure.SignedOut -> SESSION_ENDED
        is AccountFailure.RateLimited -> RATE_LIMITED
        else -> SOMETHING_WENT_WRONG
    }

    companion object {
        val PROFILE_RULE = UiText.res(R.string.partner_use_a_name_of_up_to_200_characters)
        val WORKFORCE_RULE = UiText.res(R.string.partner_enter_the_number_of_employees_as_a)
        val LOGO_RULE = UiText.res(R.string.partner_use_a_png_or_jpeg_image_of_at_most)
        val SESSION_ENDED = UiText.res(R.string.common_please_sign_in_again)

        fun factory(service: PartnerService): ViewModelProvider.Factory = viewModelFactory {
            initializer { PartnerModel(service) }
        }
    }
}

// MARK: - Partner sign-in (approved partner users sign in with an emailed link)

interface PartnerSignInService {
    suspend fun requestSignInLink(email: String)
    /** Exchanges an emailed link's token for the partner session and keeps it. */
    suspend fun accept(token: String)
}

sealed interface PartnerSignInPhase {
    data object Editing : PartnerSignInPhase
    data object Submitting : PartnerSignInPhase
    data class Sent(val email: String) : PartnerSignInPhase
    data object Accepting : PartnerSignInPhase
    data object SignedIn : PartnerSignInPhase
    data class Failed(val message: UiText) : PartnerSignInPhase
}

class PartnerSignInModel(private val service: PartnerSignInService) : ViewModel() {
    var phase: PartnerSignInPhase by mutableStateOf(PartnerSignInPhase.Editing)
        private set
    var email by mutableStateOf("")
    /** The address the last link went to, so a failed link keeps the "check your email" screen. */
    var sentTo: String? by mutableStateOf(null)
        private set

    val canSubmit: Boolean get() = phase !is PartnerSignInPhase.Submitting && CredentialRules.isPlausibleEmail(email)

    fun submit() {
        if (!canSubmit) return
        val address = email.trim()
        phase = PartnerSignInPhase.Submitting
        viewModelScope.launch {
            phase = try {
                service.requestSignInLink(address)
                sentTo = address
                PartnerSignInPhase.Sent(address)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PartnerSignInPhase.Failed(when (error) {
                    is AccountFailure.InvalidInput -> UiText.res(R.string.common_check_your_email_address_then_try)
                    is AccountFailure.RateLimited -> RATE_LIMITED
                    else -> UiText.res(R.string.common_we_couldnt_send_your_sign_in_link)
                })
            }
        }
    }

    /** Signs in with the token from an emailed partner link, tapped or pasted. */
    fun accept(token: String) {
        if (phase is PartnerSignInPhase.Accepting) return
        phase = PartnerSignInPhase.Accepting
        viewModelScope.launch {
            phase = try {
                service.accept(token)
                PartnerSignInPhase.SignedIn
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PartnerSignInPhase.Failed(when (error) {
                    is AccountFailure.InvalidInput -> UiText.res(R.string.partner_that_sign_in_link_has_expired_or)
                    is AccountFailure.Forbidden -> UiText.res(R.string.partner_this_partner_account_cant_sign_in)
                    is AccountFailure.RateLimited -> RATE_LIMITED
                    else -> SOMETHING_WENT_WRONG
                })
            }
        }
    }

    /** Accepts a pasted partner link (`…/partner/accept#token=…`) or a bare token. */
    fun acceptPasted(raw: String) {
        val text = raw.trim()
        val token = text.substringAfter('#', "").split('&').firstOrNull { it.startsWith("token=") }?.removePrefix("token=") ?: text
        if (token.isEmpty() || token.any(Char::isWhitespace)) {
            phase = PartnerSignInPhase.Failed(UiText.res(R.string.partner_paste_the_whole_sign_in_link_from))
            return
        }
        accept(token)
    }

    fun edit() {
        sentTo = null
        phase = PartnerSignInPhase.Editing
    }

    companion object {
        fun factory(service: PartnerSignInService): ViewModelProvider.Factory = viewModelFactory { initializer { PartnerSignInModel(service) } }
    }
}
