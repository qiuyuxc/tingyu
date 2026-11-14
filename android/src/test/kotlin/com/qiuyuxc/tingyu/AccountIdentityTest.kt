package com.qiuyuxc.tingyu

import org.junit.Assert.*
import org.junit.Test

class AccountIdentityTest {
    private val netease = PlatformProfile(MusicPlatform.NETEASE, "42", "云朵", "https://example.com/cloud.jpg")
    private val kugou = PlatformProfile(MusicPlatform.KUGOU, "42", "晴天", "https://example.com/dog.jpg")

    @Test fun firstConnectedPlatformBecomesMainUntilExplicitlyChanged() {
        val settings = IdentitySettings("自己的昵称", "file:/local.jpg").onLinked(netease.platform)
        assertEquals("云朵", settings.display(listOf(netease)).nickname)
        assertEquals(MusicPlatform.NETEASE, settings.display(listOf(netease)).platform)
        assertEquals("自己的昵称", settings.nickname)
        assertEquals("file:/local.jpg", settings.avatar)
    }

    @Test fun connectingSecondPlatformDoesNotOverrideMainProfile() {
        val settings = IdentitySettings().onLinked(netease.platform).onLinked(kugou.platform)
        assertEquals(netease.avatar, settings.display(listOf(kugou, netease)).avatar)
        assertEquals(netease.nickname, settings.display(listOf(kugou, netease)).nickname)
    }

    @Test fun explicitlyChosenLocalProfileSurvivesConnectingBothPlatforms() {
        val settings = IdentitySettings("本地听友", "file:/avatar.jpg", IdentitySettings.LOCAL)
            .onLinked(netease.platform).onLinked(kugou.platform)
        assertEquals(DisplayIdentity("本地听友", "file:/avatar.jpg", null), settings.display(listOf(netease, kugou)))
    }

    @Test fun sameNumericIdsOnDifferentPlatformsRemainDistinct() {
        val settings = IdentitySettings(source = kugou.platform.id)
        assertEquals(kugou.nickname, settings.display(listOf(netease, kugou)).nickname)
        assertEquals(kugou.avatar, settings.display(listOf(netease, kugou)).avatar)
        assertEquals(MusicPlatform.KUGOU, settings.display(listOf(netease, kugou)).platform)
    }

    @Test fun disconnectingMainReturnsToSavedLocalProfileWithoutAdoptingOtherPlatform() {
        val settings = IdentitySettings("我的音乐", "file:/avatar.jpg", netease.platform.id).onDisconnected(netease.platform)
        assertEquals(IdentitySettings.LOCAL, settings.source)
        assertEquals(DisplayIdentity("我的音乐", "file:/avatar.jpg", null), settings.display(listOf(kugou)))
    }

    @Test fun disconnectingSecondaryKeepsMainAndMissingMainFallsBackToLocal() {
        val settings = IdentitySettings("本地资料", source = netease.platform.id).onDisconnected(kugou.platform)
        assertEquals(netease.nickname, settings.display(listOf(netease)).nickname)
        assertEquals(DisplayIdentity("本地资料", "", null), settings.display(listOf(kugou)))
    }
}
