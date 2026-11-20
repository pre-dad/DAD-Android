package org.dad.foundation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionModelsTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @Test fun sessionReplacementCancelsWorkAndDropsOldModels() = runTest {
        val stores = SessionModelStores()
        val owner = stores.owner("donor:1")
        val old = ViewModelProvider.create(owner, viewModelFactory { initializer { Probe() } })[Probe::class.java]
        val request = old.viewModelScope.launch { awaitCancellation() }
        runCurrent()
        assertSame("rotation retains the session store", owner, stores.owner("donor:1"))
        assertFalse(request.isCancelled)
        val next = stores.owner("auth:2")
        assertNotSame(owner, next)
        assertFalse("swapping owners during composition clears nothing", old.cleared)
        stores.releaseRetired()
        assertTrue(old.cleared)
        assertTrue("old requests cannot continue after sign-out", request.isCancelled)
        val replacement = ViewModelProvider.create(next, viewModelFactory { initializer { Probe() } })[Probe::class.java]
        assertNotSame(old, replacement)
    }

    @Test fun activityDestructionAlsoClearsAnOwnerNotYetReleased() {
        val activityStore = ViewModelStore()
        val stores = ViewModelProvider.create(activityStore, viewModelFactory { initializer { SessionModelStores() } })[SessionModelStores::class.java]
        val model = ViewModelProvider.create(stores.owner("donor:1"), viewModelFactory { initializer { Probe() } })[Probe::class.java]
        stores.owner("auth:2")
        activityStore.clear()
        assertTrue(model.cleared)
    }

    @Test fun activityDestructionClearsTheActiveSessionToo() {
        val activityStore = ViewModelStore()
        val stores = ViewModelProvider.create(activityStore, viewModelFactory { initializer { SessionModelStores() } })[SessionModelStores::class.java]
        val owner = stores.owner("partner:1")
        val model = ViewModelProvider.create(owner, viewModelFactory { initializer { Probe() } })[Probe::class.java]
        activityStore.clear()
        assertTrue(model.cleared)
    }
}
