package org.dad.foundation

interface PushRegistration {
    suspend fun register(token: String)
}
