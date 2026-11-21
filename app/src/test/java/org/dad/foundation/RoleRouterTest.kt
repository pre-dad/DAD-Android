package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoleRouterTest {
    @Test fun restoredIdentityIsResolvedBeforeTheProductGraphIsChosen() {
        assertEquals(RoleRouter.Root.Restoring, RoleRouter.resolve(booted = false, donorSignedIn = true, partnerSignedIn = false))
        assertEquals(RoleRouter.Root.Restoring, RoleRouter.resolve(booted = false, donorSignedIn = false, partnerSignedIn = false))
        assertEquals(RoleRouter.Root.Authenticated(AppRole.DONOR), RoleRouter.resolve(booted = true, donorSignedIn = true, partnerSignedIn = false))
        assertEquals(RoleRouter.Root.Unauthenticated, RoleRouter.resolve(booted = true, donorSignedIn = false, partnerSignedIn = false))
        assertEquals(RoleRouter.Root.Authenticated(AppRole.PARTNER), RoleRouter.resolve(booted = true, donorSignedIn = false, partnerSignedIn = true))
        assertEquals(RoleRouter.Root.Authenticated(AppRole.DONOR), RoleRouter.resolve(booted = true, donorSignedIn = true, partnerSignedIn = true))
    }
}
