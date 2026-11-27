package com.maekom.mongolens.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores MongoDB connection passwords securely in IntelliJ PasswordSafe keyed by immutable `connectionId`
 * so renaming a connection never loses its password and `.idea/maekom-mongo-connections.json` avoids plaintext storage.
 */
object ConnectionPasswordSafe {
    private const val SUBSYSTEM = "MaekomMongoLens.Connection"

    private fun attributes(connectionId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, connectionId))

    fun savePassword(connectionId: String, username: String?, password: String?): Boolean {
        return runCatching {
            val safe = PasswordSafe.instance
            if (password.isNullOrEmpty()) {
                safe.set(attributes(connectionId), null)
            } else {
                safe.set(attributes(connectionId), Credentials(username.orEmpty(), password))
            }
            true
        }.getOrDefault(false)
    }

    fun loadPassword(connectionId: String): String? {
        return runCatching {
            PasswordSafe.instance.getPassword(attributes(connectionId))
        }.getOrNull()
    }

    fun removePassword(connectionId: String) {
        runCatching {
            PasswordSafe.instance.set(attributes(connectionId), null)
        }
    }
}
