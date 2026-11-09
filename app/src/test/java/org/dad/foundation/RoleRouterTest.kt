package org.dad.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleRouterTest {
    @Test fun startsRestoringUntilTheSessionResolves() {
        val router = RoleRouter()
        assertTrue(router.root is RoleRouter.Root.Restoring)
        router.signedOut()
        assertEquals(RoleRouter.Root.Unauthenticated, router.root)
    }

    @Test fun aSessionSelectsItsProductSurfaceAndSignOutReturnsToTheSplash() {
        for (role in AppRole.entries) {
            val router = RoleRouter()
            router.signedIn(role)
            assertEquals(RoleRouter.Root.Authenticated(role), router.root)
            router.signedOut()
            assertEquals(RoleRouter.Root.Unauthenticated, router.root)
        }
    }
}
