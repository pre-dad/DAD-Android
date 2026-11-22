package org.dad.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/** Retained across rotation, cleared before a different session can create product models. */
internal class SessionModelStores : ViewModel() {
    private var identity: String? = null
    private var current: ViewModelStoreOwner? = null
    private var retired: ViewModelStoreOwner? = null

    /** The owner for [identity]; a replaced owner is only retired here and cleared by [releaseRetired], outside composition. */
    fun owner(identity: String): ViewModelStoreOwner {
        if (this.identity != identity) {
            retired?.viewModelStore?.clear()
            retired = current
            current = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
            this.identity = identity
        }
        return checkNotNull(current)
    }

    /** Drops the previous session's models once the composition that replaced them has been applied. */
    fun releaseRetired() {
        retired?.viewModelStore?.clear()
        retired = null
    }

    override fun onCleared() {
        releaseRetired()
        current?.viewModelStore?.clear()
        current = null
    }
}

@Composable
internal fun SessionModelScope(identity: String, content: @Composable () -> Unit) {
    val stores: SessionModelStores = viewModel(key = "session-model-stores")
    val owner = remember(stores, identity) { stores.owner(identity) }
    SideEffect { stores.releaseRetired() }
    key(identity) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }
}
