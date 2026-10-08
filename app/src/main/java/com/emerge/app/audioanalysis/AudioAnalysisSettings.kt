package com.emerge.app.audioanalysis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TextServiceConfig(val enabled: Boolean = false, val endpoint: String = "", val token: String = "",
    val dailyLimit: Int = 20)

class AudioAnalysisSettings(context: Context) {
    private val preferences = context.getSharedPreferences("audio_analysis", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val config = state.asStateFlow()
    private fun read() = TextServiceConfig(preferences.getBoolean("enabled", false),
        preferences.getString("endpoint", "").orEmpty(),
        preferences.getString("token", null)?.let { runCatching { decrypt(it) }.getOrDefault("") }.orEmpty(),
        preferences.getInt("limit", 20).coerceIn(1, 100))

    fun save(value: TextServiceConfig, allowLocalHttp: Boolean) {
        val endpoint = if (value.endpoint.isBlank() && !value.enabled) "" else EndpointPolicy.validate(value.endpoint, allowLocalHttp)
        require(!value.enabled || endpoint.isNotBlank()) { "请先填写文字服务地址" }
        val clean = value.copy(endpoint = endpoint, token = value.token.trim(), dailyLimit = value.dailyLimit.coerceIn(1, 100))
        require(clean.token.length <= 512 && clean.token.all { it.code in 33..126 }) { "访问令牌只能是不含空格的 ASCII 字符，最多 512 个" }
        val token = if (clean.token.isEmpty()) "" else encrypt(clean.token)
        check(preferences.edit().putBoolean("enabled", clean.enabled).putString("endpoint", clean.endpoint)
            .putString("token", token).putInt("limit", clean.dailyLimit).commit()) { "保存失败，请重试" }
        state.value = clean
    }

    @Synchronized fun reserveRequest(): Boolean {
        val today = LocalDate.now().toString()
        val previous = if (preferences.getString("day", "") == today) preferences.getInt("count", 0) else 0
        if (previous >= state.value.dailyLimit) return false
        return preferences.edit().putString("day", today).putInt("count", previous + 1).commit()
    }
    fun requestsToday(): Int = if (preferences.getString("day", "") == LocalDate.now().toString()) preferences.getInt("count", 0) else 0

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("emerge_text_service", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("emerge_text_service", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
