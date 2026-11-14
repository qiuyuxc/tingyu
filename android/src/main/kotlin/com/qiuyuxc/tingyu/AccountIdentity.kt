package com.qiuyuxc.tingyu

internal enum class MusicPlatform(val id: String, val label: String, val icon: Int, val home: String) {
    NETEASE("netease", "网易云音乐", R.drawable.ic_platform_netease, "https://music.163.com/"),
    KUGOU("kugou", "酷狗概念版", R.drawable.ic_platform_kugou, "https://www.kugou.com/lite/");

    companion object {
        fun fromId(id: String?): MusicPlatform? = entries.firstOrNull { it.id == id }
    }
}

internal data class PlatformProfile(
    val platform: MusicPlatform,
    val userId: String,
    val nickname: String,
    val avatar: String = "",
    val connectedAt: Long = System.currentTimeMillis(),
)

internal data class DisplayIdentity(val nickname: String, val avatar: String, val platform: MusicPlatform?)

/** Platform identity and the local collection never share identifiers or storage keys. */
internal data class IdentitySettings(
    val nickname: String = "音乐听友",
    val avatar: String = "",
    val source: String? = null,
) {
    fun onLinked(platform: MusicPlatform): IdentitySettings =
        if (source == null) copy(source = platform.id) else this

    fun onDisconnected(platform: MusicPlatform): IdentitySettings =
        if (source == platform.id) copy(source = LOCAL) else this

    fun display(accounts: List<PlatformProfile>): DisplayIdentity {
        val main = accounts.firstOrNull { it.platform.id == source }
        return if (main != null) DisplayIdentity(main.nickname, main.avatar, main.platform)
        else DisplayIdentity(nickname.ifBlank { "音乐听友" }, avatar, null)
    }

    companion object { const val LOCAL = "local" }
}
