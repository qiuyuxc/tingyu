package com.qiuyuxc.tingyu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Only public profile data enters preferences. Login credentials live in noBackupFilesDir. */
internal object AccountStore {
    var revision by mutableIntStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences("music_accounts", Context.MODE_PRIVATE)

    fun settings(context: Context): IdentitySettings =
        prefs(context).let {
            IdentitySettings(
                it.getString("nickname", "音乐听友").orEmpty(),
                it.getString("avatar", "").orEmpty(),
                it.getString("identitySource", null),
            )
        }

    fun accounts(context: Context): List<PlatformProfile> =
        MusicPlatform.entries.mapNotNull { platform ->
            val raw =
                prefs(context).getString("profile.${platform.id}", null) ?: return@mapNotNull null
            runCatching {
                val json = JSONObject(raw)
                PlatformProfile(
                    platform,
                    json.getString("userId"),
                    json.getString("nickname"),
                    json.optString("avatar"),
                    json.optLong("connectedAt"),
                )
            }
                .getOrNull()
        }

    fun identity(context: Context): DisplayIdentity = settings(context).display(accounts(context))

    fun quality(context: Context, platform: MusicPlatform): AudioQuality =
        AudioQuality.preference(
            platform,
            AudioQuality.fromId(prefs(context).getString("quality.${platform.id}", null)),
        )

    fun setQuality(context: Context, platform: MusicPlatform, quality: AudioQuality) {
        prefs(context)
            .edit()
            .putString("quality.${platform.id}", AudioQuality.preference(platform, quality).id)
            .apply()
        revision++
    }

    fun reverseFavorites(context: Context, platform: MusicPlatform): Boolean =
        prefs(context).getBoolean("favorites.reverse.${platform.id}", false)

    fun setReverseFavorites(context: Context, platform: MusicPlatform, reversed: Boolean) {
        prefs(context).edit().putBoolean("favorites.reverse.${platform.id}", reversed).apply()
        revision++
    }

    fun membership(context: Context, profile: PlatformProfile): PlatformMembership? = runCatching {
        val raw = prefs(context).getString("membership.${profile.platform.id}", null) ?: return null
        val json = JSONObject(raw)
        if (json.optString("userId") != profile.userId) return null
        PlatformMembership.fromJson(json).let {
            if (profile.platform == MusicPlatform.NETEASE && !json.has("subscriptions"))
                it.copy(checkedAt = 0)
            else it
        }
    }
        .getOrNull()

    @Synchronized
    fun updateMembership(
        context: Context,
        profile: PlatformProfile,
        membership: PlatformMembership,
    ) {
        if (
            accounts(context).none {
                it.platform == profile.platform && it.userId == profile.userId
            }
        )
            return
        val result = membership.retainKnownSubscriptions(AccountStore.membership(context, profile))
        prefs(context)
            .edit()
            .putString(
                "membership.${profile.platform.id}",
                result.toJson().put("userId", profile.userId).toString(),
            )
            .apply()
        revision++
    }

    fun session(context: Context, platform: MusicPlatform): String? =
        AccountVault.read(context, platform)

    @Synchronized
    fun authorization(context: Context, platform: MusicPlatform): AuthorizedAccount? {
        val profile = accounts(context).firstOrNull { it.platform == platform } ?: return null
        val session = AccountVault.read(context, platform) ?: return null
        return AuthorizedAccount(profile, session)
    }

    @Synchronized
    fun connect(context: Context, profile: PlatformProfile, session: String) {
        require(profile.userId.isNotBlank() && session.isNotBlank())
        AccountVault.write(context, profile.platform, session)
        PlatformLibraryStore.clear(profile.platform)
        DiscoveryStore.clear(profile.platform)
        Player.get().removePlatform(profile.platform)
        val identity = settings(context).onLinked(profile.platform)
        prefs(context)
            .edit()
            .remove("membership.${profile.platform.id}")
            .putString(
                "profile.${profile.platform.id}",
                JSONObject()
                    .apply {
                        put("userId", profile.userId)
                        put("nickname", profile.nickname)
                        put("avatar", profile.avatar)
                        put("connectedAt", profile.connectedAt)
                    }
                    .toString(),
            )
            .putString("identitySource", identity.source)
            .apply()
        revision++
    }

    @Synchronized
    fun disconnect(context: Context, platform: MusicPlatform) {
        AccountVault.remove(context, platform)
        PlatformLibraryStore.clear(platform)
        DiscoveryStore.clear(platform)
        Player.get().removePlatform(platform)
        prefs(context)
            .edit()
            .remove("profile.${platform.id}")
            .remove("membership.${platform.id}")
            .putString("identitySource", settings(context).onDisconnected(platform).source)
            .apply()
        revision++
    }

    fun selectIdentity(context: Context, platform: MusicPlatform?) {
        require(platform == null || accounts(context).any { it.platform == platform })
        prefs(context)
            .edit()
            .putString("identitySource", platform?.id ?: IdentitySettings.LOCAL)
            .apply()
        revision++
    }

    fun setNickname(context: Context, nickname: String) {
        val value = nickname.trim()
        require(value.isNotEmpty() && value.length <= 32)
        prefs(context)
            .edit()
            .putString("nickname", value)
            .putString("identitySource", IdentitySettings.LOCAL)
            .apply()
        revision++
    }

    /** Copy a bounded thumbnail into app storage; no persistent gallery permission is needed. */
    fun setAvatar(context: Context, uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片" }
        val options =
            BitmapFactory.Options().apply {
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                inSampleSize = sample
            }
        val original =
            context.contentResolver.openInputStream(uri).use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("无法读取这张图片")
        val side = minOf(original.width, original.height)
        val cropped =
            Bitmap.createBitmap(
                original,
                (original.width - side) / 2,
                (original.height - side) / 2,
                side,
                side,
            )
        val avatar = Bitmap.createScaledBitmap(cropped, 256, 256, true)
        val file = File(context.filesDir, "profile/avatar-${System.nanoTime()}.jpg")
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { check(avatar.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            val old = settings(context).avatar
            prefs(context)
                .edit()
                .putString("avatar", Uri.fromFile(file).toString())
                .putString("identitySource", IdentitySettings.LOCAL)
                .apply()
            if (old.startsWith("file:"))
                runCatching {
                    val oldFile = File(Uri.parse(old).path.orEmpty())
                    if (oldFile.parentFile == file.parentFile) oldFile.delete()
                }
            revision++
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            if (avatar !== cropped) avatar.recycle()
            if (cropped !== original) cropped.recycle()
            original.recycle()
        }
    }
}

/** AES-GCM is scoped to both the package Keystore and the platform id. */
private object AccountVault {
    private const val ALIAS = "music-platform-sessions-v1"

    private fun file(context: Context, platform: MusicPlatform) =
        AtomicFile(File(context.noBackupFilesDir, "platform-accounts/${platform.id}.bin"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun write(context: Context, platform: MusicPlatform, session: String) {
        require(session.toByteArray().size <= 65536)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(platform.id.toByteArray())
            }
        val atomic = file(context, platform)
        atomic.baseFile.parentFile?.mkdirs()
        val output = atomic.startWrite()
        try {
            output.write(cipher.iv.size)
            output.write(cipher.iv)
            output.write(cipher.doFinal(session.toByteArray(Charsets.UTF_8)))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    fun read(context: Context, platform: MusicPlatform): String? = runCatching {
        val atomic = file(context, platform)
        if (!atomic.baseFile.exists()) return null
        require(atomic.baseFile.length() in 30..65580)
        val bytes = atomic.readFully()
        val length = bytes[0].toInt() and 0xff
        require(length == 12 && bytes.size > length + 17)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(1, length + 1)),
                )
                updateAAD(platform.id.toByteArray())
            }
        String(cipher.doFinal(bytes.copyOfRange(length + 1, bytes.size)), Charsets.UTF_8)
    }
        .getOrNull()

    fun remove(context: Context, platform: MusicPlatform) {
        file(context, platform).delete()
    }
}
