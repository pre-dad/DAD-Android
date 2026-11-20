package org.dad.foundation

/**
 * Which product surface the signed-in person is using. Donor and partner are distinct
 * journeys (PRD §6 vs §7) with separate navigation graphs; the admin console is web-only
 * and intentionally absent here.
 */
enum class AppRole { DONOR, PARTNER }

/** A session keeps its product shell while account data loads or fails; only sign-out returns to the splash. */
object RoleRouter {
    sealed interface Root {
        /** Session state is being restored on launch. */
        data object Restoring : Root
        /** No session; the unauthenticated stack owns role selection and sign-in. */
        data object Unauthenticated : Root
        /** A session exists for this role; show its graph. */
        data class Authenticated(val role: AppRole) : Root
    }

    fun resolve(booted: Boolean, donorSignedIn: Boolean, partnerSignedIn: Boolean): Root = when {
        !booted -> Root.Restoring
        donorSignedIn -> Root.Authenticated(AppRole.DONOR)
        partnerSignedIn -> Root.Authenticated(AppRole.PARTNER)
        else -> Root.Unauthenticated
    }
}
