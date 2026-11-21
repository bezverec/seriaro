package org.seriestracker.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

internal data class WebDavCredentials(
    val url: String,
    val username: String,
    val password: String,
)

internal class SecretStore(context: Context) {
    private val webDav = AtomicFile(File(context.noBackupFilesDir, "webdav.enc"))

    @Synchronized
    fun readWebDav(): WebDavCredentials? {
        val values = read(webDav, "SeriesTrackerWebDav") ?: return null
        check(values.size == 3)
        return WebDavCredentials(values[0], values[1], values[2])
    }

    @Synchronized
    fun saveWebDav(url: String, username: String, password: String) {
        val secret = password.ifEmpty { readWebDav()?.password.orEmpty() }
        write(webDav, "SeriesTrackerWebDav", listOf(url.trim(), username, secret))
    }

    @Synchronized
    fun clearWebDav() = webDav.delete()

    private fun read(file: AtomicFile, alias: String): List<String>? {
        val payload = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return null
        }
        val input = DataInputStream(ByteArrayInputStream(payload))
        val length = input.readUnsignedByte()
        check(length == 12)
        val iv = ByteArray(length)
        input.readFully(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(alias, create = false), GCMParameterSpec(128, iv))
        val decrypted = cipher.doFinal(input.readBytes())
        return try {
            DataInputStream(ByteArrayInputStream(decrypted)).use { plain ->
                val count = plain.readUnsignedByte()
                check(count in 1..3)
                List(count) { plain.readUTF() }
            }
        } finally {
            decrypted.fill(0)
        }
    }

    private fun write(file: AtomicFile, alias: String, values: List<String>) {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeByte(values.size)
            values.forEach(output::writeUTF)
        }
        val plain = buffer.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias, create = true))
        val encrypted = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        val output = file.startWrite()
        try {
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun key(alias: String, create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
