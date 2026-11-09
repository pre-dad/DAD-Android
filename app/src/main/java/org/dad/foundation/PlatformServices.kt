package org.dad.foundation

interface PushRegistration {
    suspend fun register(token: String)
    suspend fun unregister()
}
