package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoleRouterTest {
    @Test fun restoredIdentityIsResolvedBeforeTheProductGraphIsChosen() {
        assertEquals(RoleRouter.Root.Restoring, RoleRouter.resolve(false, true, false))
        assertEquals(RoleRouter.Root.Restoring, RoleRouter.resolve(false, false, false))
        assertEquals(RoleRouter.Root.Authenticated(AppRole.DONOR), RoleRouter.resolve(true, true, false))
        assertEquals(RoleRouter.Root.Unauthenticated, RoleRouter.resolve(true, false, false))
        assertEquals(RoleRouter.Root.Authenticated(AppRole.PARTNER), RoleRouter.resolve(true, false, true))
        assertEquals(RoleRouter.Root.Authenticated(AppRole.DONOR), RoleRouter.resolve(true, true, true))
    }
}
