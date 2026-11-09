package org.dad.foundation

/**
 * Which product surface the signed-in person is using. Donor and partner are distinct
 * journeys (PRD §6 vs §7) with separate navigation graphs; the admin console is web-only
 * and intentionally absent here.
 */
enum class AppRole { DONOR, PARTNER }

/** A session keeps its product shell while account data loads or fails; only sign-out returns to the splash. */
class RoleRouter(root: Root = Root.Restoring) {
    sealed interface Root {
        /** Session state is being restored on launch. */
        data object Restoring : Root
        /** No session; the unauthenticated stack owns role selection and sign-in. */
        data object Unauthenticated : Root
        /** A session exists for this role; show its graph. */
        data class Authenticated(val role: AppRole) : Root
    }

    var root: Root = root
        private set

    /** A session was established for [role] (sign-in, claim link, or restored on launch). */
    fun signedIn(role: AppRole) {
        root = Root.Authenticated(role)
    }

    /** The session ended; return to the splash. */
    fun signedOut() {
        root = Root.Unauthenticated
    }
}
