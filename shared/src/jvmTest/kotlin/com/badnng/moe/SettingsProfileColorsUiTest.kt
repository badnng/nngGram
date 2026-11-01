package com.badnng.moe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.badnng.moe.chat.ChatSnapshot
import com.badnng.moe.chat.MediaFile
import com.badnng.moe.chat.MessageMedia
import com.badnng.moe.chat.StickerItem
import com.badnng.moe.settings.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Synthetic snapshots and real scene pointer events; no account, native transport or OS dialogs. */
class SettingsProfileColorsUiTest {
    @Test fun realProfileEntryOpensOnlyOnClickAndCancelClosesOnlyItsOwnPageWithoutWriting() {
        val f = Fixture(profileContext = true)
        f.scene().use { scene ->
            scene.settledFrame()
            assertTrue(f.opens.isEmpty())
            scene.enter(f)
            assertEquals(listOf(SettingsToken(7, 1)), f.opens)
            val owner = f.snapshot.token
            assertTrue(f.leases.single()())
            scene.clickText("颜色 2", f)
            scene.clickText("取消颜色编辑", f)
            assertEquals(listOf(owner), f.closes)
            assertFalse(f.leases.single()())
            assertTrue(f.saves.isEmpty())
            assertTrue(f.profileSaves.isEmpty(), "Color cancel never changes the existing parent's profile save fields.")
            scene.enter(f)
            scene.clickText("保存名字颜色", f)
            assertTrue(f.saves.isEmpty(), "A new page has discarded the cancelled draft.")
        }
    }

    @Test fun mountCannotBorrowAnAlreadyOpenedOwnerAndLaterForeignOwnerCannotReplaceTheAdoptedToken() {
        val f = Fixture(initial = snapshot().copy(token = SettingsProfileColorsToken(7, 30, SELF), opened = true), admitOpen = false)
        f.scene().use { scene ->
            scene.enter(f)
            assertTrue(scene.hasText("资料颜色尚未读取。"))
            assertFalse(scene.hasText("保存名字颜色"))
            f.onUi { f.snapshot = f.snapshot.copy(token = SettingsProfileColorsToken(7, 29, SELF)) }
            scene.settledFrame()
            assertFalse(scene.hasText("保存名字颜色"))
            f.onUi { f.snapshot = f.snapshot.copy(token = SettingsProfileColorsToken(7, 31, SELF)) }
            scene.settledFrame()
            assertTrue(scene.hasText("保存名字颜色"))
            scene.clickText("颜色 1", f)
            f.onUi { f.snapshot = f.snapshot.copy(token = SettingsProfileColorsToken(7, 32, SELF)) }
            scene.settledFrame()
            assertFalse(scene.hasText("保存名字颜色"))
            scene.clickText("取消颜色编辑", f)
            assertEquals(listOf(SettingsProfileColorsToken(7, 31, SELF)), f.closes)
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test fun accountRevisionAndUnmountRetireTheLeaseAndNeverResurrectCancelledDrafts() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 2", f)
            val first = f.snapshot.token
            val lease = f.leases.single()
            f.onUi { f.accountToken = SettingsToken(8, 1) }
            scene.settledFrame()
            assertFalse(lease())
            assertEquals(listOf(first), f.closes)
            assertEquals(1, f.opens.size, "An account change returns to the explicit entry.")
            scene.enter(f)
            assertEquals(SettingsToken(8, 1), f.opens.last())
            scene.clickText("保存名字颜色", f)
            assertTrue(f.saves.isEmpty())
            val second = f.snapshot.token
            val secondLease = f.leases.last()
            f.onUi { f.mounted = false }
            scene.settledFrame()
            assertFalse(secondLease())
            assertEquals(listOf(first, second), f.closes)
            f.onUi { f.snapshot = f.snapshot.copy(opened = true); f.mounted = true }
            scene.settledFrame()
            assertTrue(scene.hasText("名字与资料颜色"))
            assertEquals(2, f.opens.size)
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test fun nameAndProfileTabsRetainIndependentDraftsAndSubmitExactlyOnePair() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 1", f)
            scene.clickText("资料背景", f)
            scene.clickText("颜色 12", f)
            scene.clickText("名字颜色", f)
            scene.clickText("保存名字颜色", f)
            val name = f.saves.single().second
            assertEquals(SettingsProfileColorEdit(SettingsProfileColorPart.NAME, SettingsProfileColorValue(0),
                SettingsProfileColorValue(1), 1), name)
            f.complete(accepted = true)
            scene.settledFrame()
            scene.clickText("资料背景", f)
            scene.clickText("保存资料背景", f)
            assertEquals(2, f.saves.size)
            assertEquals(SettingsProfileColorEdit(SettingsProfileColorPart.PROFILE, SettingsProfileColorValue(11),
                SettingsProfileColorValue(12), 1), f.saves.last().second)
        }
    }

    @Test fun laterSameValueRevisionIsNotConsumedByEarlierAcceptance() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 1", f)
            scene.clickText("保存名字颜色", f)
            scene.clickText("颜色 2", f)
            scene.clickText("颜色 1", f)
            assertEquals(1, f.saves.size)
            f.complete(accepted = true)
            scene.settledFrame()
            scene.clickText("保存名字颜色", f)
            assertEquals(2, f.saves.size, "A revision newer than the accepted edit still requires explicit save.")
            assertEquals(3L, f.saves.last().second.contentRevision)
            assertEquals(SettingsProfileColorValue(1), f.saves.last().second.base)
            assertEquals(SettingsProfileColorValue(1), f.saves.last().second.desired)
        }
    }

    @Test fun failureRetainsDraftAndAnOldOutcomeCannotFinishTheExplicitRetry() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 2", f)
            scene.clickText("保存名字颜色", f)
            f.complete(accepted = false, error = SettingsError.REQUEST_FAILED)
            scene.settledFrame()
            assertTrue(scene.hasText("请求未完成，请检查连接后重试。"))
            scene.clickText("保存名字颜色", f)
            assertEquals(2, f.saves.size)
            assertEquals(f.saves.first().second, f.saves.last().second)
            f.onUi { f.snapshot = f.snapshot.copy(busy = false, activeSave = null) }
            scene.settledFrame()
            scene.clickText("保存名字颜色", f)
            assertEquals(2, f.saves.size, "Replaying attempt one cannot clear attempt two's local submission.")
            f.complete(accepted = false, error = SettingsError.FORBIDDEN)
            scene.settledFrame()
            scene.clickText("保存名字颜色", f)
            assertEquals(3, f.saves.size)
        }
    }

    @Test fun remoteUpdatesPreserveDirtyTabsAndAdoptingLatestIsAnExplicitPerTabAction() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 1", f)
            scene.clickText("资料背景", f)
            scene.clickText("颜色 12", f)
            f.onUi {
                val data = assertNotNull(f.snapshot.data)
                f.snapshot = f.snapshot.copy(data = data.copy(values = data.values.copy(
                    name = SettingsProfileColorValue(3), profile = SettingsProfileColorValue(13))), refreshRequired = true)
            }
            scene.settledFrame()
            assertTrue(scene.hasText("此项已在其他位置更改，草稿已保留。请重新读取并确认最新值。"))
            scene.clickText("保存资料背景", f)
            assertTrue(f.saves.isEmpty())
            scene.clickText("重新读取资料颜色", f)
            assertEquals(listOf(f.snapshot.token), f.refreshes)
            f.freshRead()
            scene.settledFrame()
            scene.clickText("名字颜色", f)
            scene.clickText("采用最新读取值", f)
            scene.clickText("颜色 4", f)
            scene.clickText("保存名字颜色", f)
            assertEquals(SettingsProfileColorValue(3), f.saves.single().second.base)
            f.complete(accepted = true)
            scene.settledFrame()
            scene.clickText("资料背景", f)
            scene.clickText("保存资料背景", f)
            assertEquals(1, f.saves.size, "The other tab still holds its conflicting original base.")
            scene.clickText("采用最新读取值", f)
            scene.clickText("颜色 12", f)
            scene.clickText("保存资料背景", f)
            assertEquals(SettingsProfileColorValue(13), f.saves.last().second.base)
            assertEquals(SettingsProfileColorValue(12), f.saves.last().second.desired)
        }
    }

    @Test fun acceptedUnknownReadbackBlocksBothSettersAndRefreshDoesNotEraseTheOtherDraft() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 1", f)
            scene.clickText("保存名字颜色", f)
            f.complete(accepted = true, readbackUnknown = true)
            scene.settledFrame()
            assertTrue(scene.hasText("修改已接受，最新值尚未读回。请重新读取，勿重复保存。"))
            scene.clickText("保存名字颜色", f)
            scene.clickText("资料背景", f)
            scene.clickText("颜色 12", f)
            scene.clickText("保存资料背景", f)
            assertEquals(1, f.saves.size)
            scene.clickText("重新读取资料颜色", f)
            assertEquals(1, f.refreshes.size)
            f.freshRead(name = SettingsProfileColorValue(1))
            scene.settledFrame()
            scene.clickText("保存资料背景", f)
            assertEquals(2, f.saves.size)
            assertEquals(SettingsProfileColorValue(12), f.saves.last().second.desired)
        }
    }

    @Test fun resetUsesNativeNameModuloAndIndependentProfileMinusOneIncludingEmojiRemoval() {
        val f = Fixture(initial = snapshot(name = SettingsProfileColorValue(6, 99), profile = SettingsProfileColorValue(12, -88)))
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("恢复名字默认颜色", f)
            scene.clickText("保存名字颜色", f)
            assertEquals(SettingsProfileColorValue((SELF % 7).toInt(), 0), f.saves.single().second.desired)
            f.complete(accepted = true)
            scene.settledFrame()
            scene.clickText("资料背景", f)
            scene.clickText("恢复资料默认颜色", f)
            scene.clickText("保存资料背景", f)
            assertEquals(SettingsProfileColorValue(-1, 0), f.saves.last().second.desired)
        }
    }

    @Test fun unknownCatalogPreservesHiddenValueAndAllowsOnlyTheExplicitNativeReset() {
        val f = Fixture(initial = snapshot(name = SettingsProfileColorValue(77, 99), catalog = SettingsProfileColorsCatalog()))
        f.scene().use { scene ->
            scene.enter(f)
            assertTrue(scene.hasText("颜色目录尚未收到；当前颜色可保留，暂不能选择新颜色。"))
            assertTrue(scene.hasText("当前颜色未列入可选目录；可保留已有值。"))
            assertTrue(scene.hasText("当前颜色未知"))
            assertFalse(scene.hasText("颜色 1"))
            scene.clickText("保存名字颜色", f)
            assertTrue(f.saves.isEmpty())
            scene.clickText("恢复名字默认颜色", f)
            scene.clickText("保存名字颜色", f)
            assertEquals(SettingsProfileColorValue(77, 99), f.saves.single().second.base)
            assertEquals(SettingsProfileColorValue((SELF % 7).toInt(), 0), f.saves.single().second.desired)
        }
        val invalid = Fixture(initial = snapshot(catalog = catalog().copy(nameError = SettingsError.INVALID_VALUE)))
        invalid.scene().use { scene ->
            scene.enter(invalid)
            assertTrue(scene.hasText("颜色目录无效，请重新读取后选择。"))
            scene.clickText("恢复名字默认颜色", invalid)
            scene.clickText("保存名字颜色", invalid)
            assertTrue(invalid.saves.isEmpty())
        }
    }

    @Test fun currentPremiumFalseIsReadOnlyWithoutInventingABuyFlowOrMetadataRequest() {
        val f = Fixture(initial = snapshot(premium = false))
        f.scene().use { scene ->
            scene.enter(f)
            assertTrue(scene.hasText("修改资料颜色需要当前账号具备 Telegram Premium。此处只读，不提供购买流程。"))
            scene.clickText("颜色 1", f)
            scene.clickText("恢复名字默认颜色", f)
            scene.clickText("背景图案", f)
            scene.clickText("保存名字颜色", f)
            assertTrue(f.saves.isEmpty())
            assertTrue(f.backgroundOpens.isEmpty())
            scene.clickText("重新读取资料颜色", f)
            assertEquals(1, f.refreshes.size)
        }
        val unknown = Fixture(initial = snapshot().copy(data = null, loading = true))
        unknown.scene().use { scene ->
            scene.enter(unknown)
            assertFalse(scene.hasText("保存名字颜色"))
            assertTrue(unknown.saves.isEmpty())
        }
    }

    @Test fun giftReplacementIsAnExplicitFrozenNameConfirmationAndProfileNeverDropsIt() {
        val f = Fixture(initial = snapshot(gift = gift(), profile = SettingsProfileColorValue(11, -88)))
        f.scene().use { scene ->
            scene.enter(f)
            assertTrue(f.artwork.calls.any { it.id == 102L && it.giftModelId == -101L &&
                it.role == SettingsProfileColorArtworkRole.NAME_PREVIEW })
            scene.clickText("保存名字颜色", f)
            assertTrue(f.saves.isEmpty())
            assertFalse(scene.hasText("替换礼物名字外观？"), "Merely opening a gift-colored profile does not create replacement intent.")
            scene.clickText("颜色 0", f)
            assertTrue(f.artwork.calls.any { it.id == 0L && it.giftModelId == 0L &&
                it.role == SettingsProfileColorArtworkRole.NAME_PREVIEW }, "Selecting the existing raw color previews its plain appearance.")
            scene.clickText("保存名字颜色", f)
            assertTrue(scene.hasText("替换礼物名字外观？"))
            assertTrue(f.saves.isEmpty())
            scene.clickText("保留礼物", f)
            scene.clickText("保存名字颜色", f)
            scene.clickText("确认替换礼物", f)
            assertTrue(f.saves.single().second.replaceGift)
            assertEquals(SettingsProfileColorPart.NAME, f.saves.single().second.part)
            assertEquals(f.saves.single().second.base, f.saves.single().second.desired)
            assertEquals(1L, f.saves.single().second.contentRevision)
            f.complete(accepted = false, error = SettingsError.REQUEST_FAILED)
            scene.settledFrame()
            scene.clickText("资料背景", f)
            assertTrue(f.artwork.calls.any { it.id == -88L && it.giftModelId == 0L &&
                it.role == SettingsProfileColorArtworkRole.PROFILE_PREVIEW })
            scene.clickText("颜色 12", f)
            scene.clickText("保存资料背景", f)
            assertEquals(2, f.saves.size)
            assertFalse(f.saves.last().second.replaceGift)
            assertNotNull(f.snapshot.data?.values?.gift)
            f.complete(accepted = true)
            scene.settledFrame()
            scene.clickText("名字颜色", f)
            scene.clickText("采用最新读取值", f)
            scene.clickText("保存名字颜色", f)
            assertEquals(2, f.saves.size, "Adopting the latest raw pair also clears replacement intent.")
            assertEquals(-101L, f.artwork.calls.last { it.role == SettingsProfileColorArtworkRole.NAME_PREVIEW }.giftModelId)
        }
    }

    @Test fun staleGiftConfirmationCannotApplyToRemoteChangesOrAnInactivePage() {
        val f = Fixture(initial = snapshot(gift = gift()))
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 2", f)
            scene.clickText("保存名字颜色", f)
            f.onUi {
                val data = assertNotNull(f.snapshot.data)
                f.snapshot = f.snapshot.copy(data = data.copy(values = data.values.copy(gift = gift().copy(id = 900))))
            }
            scene.settledFrame()
            scene.clickText("确认替换礼物", f)
            assertTrue(f.saves.isEmpty())
            scene.clickText("保存名字颜色", f)
            val lease = f.leases.single()
            f.onUi { f.active = false }
            scene.settledFrame()
            assertFalse(lease())
            assertFalse(scene.hasText("确认替换礼物"))
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test fun frozenGiftEditRejectsANewerRevisionEvenWhenThePairMatchesAgain() {
        val f = Fixture(initial = snapshot(gift = gift()))
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 2", f)
            scene.clickText("保存名字颜色", f)
            scene.clickText("颜色 1", f)
            scene.clickText("颜色 2", f)
            scene.clickText("确认替换礼物", f)
            assertTrue(f.saves.isEmpty())
            scene.clickText("保存名字颜色", f)
            scene.clickText("确认替换礼物", f)
            assertEquals(3L, f.saves.single().second.contentRevision)
            assertTrue(f.saves.single().second.replaceGift)
        }
    }

    @Test fun patternsUseExplicitNativeMetadataAndSignedIdentityWithSeparateArtworkRoles() {
        val f = Fixture(backgroundMetadata = listOf(emoji(-44)))
        f.scene().use { scene ->
            scene.enter(f)
            assertTrue(f.backgroundOpens.isEmpty(), "Entering the page does not load a background directory.")
            scene.clickText("背景图案", f)
            assertEquals(listOf(f.snapshot.token), f.backgroundOpens)
            scene.clickText("图案 -44", f)
            assertTrue(f.artwork.calls.any { it.id == -44L && it.giftModelId == 0L && it.role == SettingsProfileColorArtworkRole.OPTION_THUMBNAIL })
            assertTrue(f.artwork.calls.any { it.id == -44L && it.giftModelId == 0L && it.role == SettingsProfileColorArtworkRole.NAME_PREVIEW })
            scene.clickText("保存名字颜色", f)
            assertEquals(-44L, f.saves.single().second.desired.backgroundCustomEmojiId)
            assertTrue(f.resolves.isEmpty())
        }
        val current = Fixture(initial = snapshot(name = SettingsProfileColorValue(0, 99)), backgroundMetadata = emptyList())
        current.scene().use { scene ->
            scene.enter(current)
            scene.clickText("背景图案", current)
            scene.clickText("读取当前图案", current)
            assertEquals(listOf(listOf(99L)), current.resolves)
        }
    }

    @Test fun actualNamePixelsUseOrdinaryAccentAndProfileRadialUsesSecondColorAtTheCenter() {
        val f = Fixture()
        f.scene().use { scene ->
            scene.enter(f)
            scene.clickText("颜色 2", f)
            val name = assertNotNull(scene.semanticsBounds {
                "名字颜色预览" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.firstOrNull { it.width > 0 && it.height > 0 })
            val nameFrame = scene.settledFrame()
            var ordinaryNamePixels = 0
            for (y in name.top.roundToInt() until name.bottom.roundToInt()) {
                for (x in name.left.roundToInt() until name.right.roundToInt()) {
                    if (rgbDistance(nameFrame[x, y], 0x8544d6) < 10) ordinaryNamePixels++
                }
            }
            assertTrue(ordinaryNamePixels > 3, "Actual ordinary-name glyphs use the fixed light peer role, independent of profile palette.")
            scene.clickText("资料背景", f)
            scene.clickText("颜色 12", f)
            scene.reveal("资料背景预览", f)
            val rect = assertNotNull(scene.semanticsBounds {
                "资料背景预览" in it.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            }.firstOrNull { it.width > 0 && it.height > 0 })
            val frame = scene.settledFrame()
            val centerSide = frame[(rect.center.x + 40).roundToInt(), (rect.top + 90).roundToInt()]
            val edge = frame[(rect.left + 7).roundToInt(), (rect.top + 90).roundToInt()]
            assertTrue(rgbDistance(centerSide, 0xe0b080) < rgbDistance(edge, 0xe0b080),
                "The native second background color is nearer the center, outside the real avatar footprint.")
            assertTrue(rgbDistance(edge, 0x103050) < 6, "The native first background color remains the edge color.")
            frame.save("profile-radial-roles-light")
        }
    }

    @Test fun wideAndLowThemesAndDensitiesUseRealPointerScrollingSaveCancelAndProduceOfflineFrames() {
        listOf(620 to 900, 350 to 300).forEach { (width, height) ->
            listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark).forEach { mode ->
                listOf(1f, 1.25f).forEach { density ->
                    val f = Fixture(width, height, density, mode)
                    f.scene().use { scene ->
                        scene.enter(f)
                        scene.clickText("颜色 1", f)
                        scene.reveal("名字与回复颜色预览", f)
                        scene.settledFrame().save("name-${mode.name}-${width}x${height}-$density")
                        scene.clickText("保存名字颜色", f)
                        assertEquals(1, f.saves.size)
                        f.complete(accepted = false, error = SettingsError.REQUEST_FAILED)
                        scene.settledFrame()
                        scene.clickText("取消颜色编辑", f)
                        scene.enter(f)
                        scene.clickText("资料背景", f)
                        scene.clickText("颜色 12", f)
                        scene.reveal("资料背景预览", f)
                        scene.settledFrame().save("profile-${mode.name}-${width}x${height}-$density")
                        scene.clickText("恢复资料默认颜色", f)
                        scene.clickText("保存资料背景", f)
                        assertEquals(2, f.saves.size)
                        assertEquals(SettingsProfileColorValue(-1), f.saves.last().second.desired)
                    }
                }
            }
        }
    }

    private data class ArtworkCall(val id: Long, val role: SettingsProfileColorArtworkRole,
        val part: SettingsProfileColorPart, val giftModelId: Long)
    private class RecordingArtwork : SettingsProfileColorPatternPreview {
        val calls = mutableListOf<ArtworkCall>()
        @Composable override fun Content(emoji: SettingsBackgroundEmoji?, customEmojiId: Long, part: SettingsProfileColorPart,
            role: SettingsProfileColorArtworkRole, tint: Color, active: Boolean, modifier: Modifier,
            giftModelCustomEmojiId: Long) {
            SideEffect { if (active) calls += ArtworkCall(customEmojiId, role, part, giftModelCustomEmojiId) }
            Box(modifier)
        }
    }
    private class InlineConfirmation : SettingsSecurityConfirmationHost {
        @Composable override fun Content(show: Boolean, title: String?, summary: String?, maxWidth: Dp,
            onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
            if (show) Column(Modifier.fillMaxWidth().chatSquircleBackground(MiuixTheme.colorScheme.surfaceContainer, 24.dp).padding(12.dp)) {
                title?.let { Text(it) }; summary?.let { Text(it) }; content()
            }
        }
    }
    private class Fixture(
        width: Int = 620, height: Int = 900, val density: Float = 1f,
        val mode: ColorSchemeMode = ColorSchemeMode.Light,
        initial: SettingsProfileColorsSnapshot = snapshot(), val admitOpen: Boolean = true,
        val profileContext: Boolean = false, val backgroundMetadata: List<SettingsBackgroundEmoji> = emptyList(),
    ) {
        val width = (width * density).roundToInt()
        val height = (height * density).roundToInt()
        var snapshot by mutableStateOf(initial)
        var accountToken by mutableStateOf(SettingsToken(7, 1))
        var active by mutableStateOf(true)
        var mounted by mutableStateOf(true)
        val profile = SettingsProfile(SELF, "Example", "Account", "example", "", "")
        val opens = mutableListOf<SettingsToken>()
        val leases = mutableListOf<() -> Boolean>()
        val closes = mutableListOf<SettingsProfileColorsToken>()
        val refreshes = mutableListOf<SettingsProfileColorsToken>()
        val backgroundOpens = mutableListOf<SettingsProfileColorsToken>()
        val resolves = mutableListOf<List<Long>>()
        val saves = mutableListOf<Pair<SettingsProfileColorsToken, SettingsProfileColorEdit>>()
        val profileSaves = mutableListOf<SettingsProfileEdit>()
        val artwork = RecordingArtwork()
        private var owner = initial.token.owner
        private var attempt = 0L
        val actions = SettingsProfileColorsActions(
            open = { account, user, lease ->
                opens += account; leases += lease
                if (admitOpen && lease()) snapshot = snapshot.copy(token = SettingsProfileColorsToken(account.generation, ++owner, user),
                    opened = true, busy = false, activeSave = null, outcomes = emptyMap(), readbackPending = emptySet(),
                    backgroundsOpened = false, backgrounds = emptyList(), refreshRequired = false)
            },
            close = { token -> closes += token; if (snapshot.token == token) snapshot = snapshot.copy(opened = false) },
            refresh = { refreshes += it },
            save = { token, edit ->
                saves += token to edit
                snapshot = snapshot.copy(busy = true, activeSave = SettingsProfileColorAttempt(++attempt, edit), error = null)
            },
            openBackgrounds = { token -> backgroundOpens += token
                snapshot = snapshot.copy(backgroundsOpened = true, backgrounds = backgroundMetadata, backgroundsLoading = false) },
            resolveBackgrounds = { _, ids -> resolves += ids },
            closeBackgrounds = { snapshot = snapshot.copy(backgroundsOpened = false) },
        )
        fun onUi(block: () -> Unit) { if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block) }
        fun complete(accepted: Boolean, error: SettingsError? = null, readbackUnknown: Boolean = false) = onUi {
            val write = SettingsProfileColorAttempt(attempt, saves.last().second)
            val old = assertNotNull(snapshot.data)
            val values = if (!accepted || readbackUnknown) old.values else if (write.edit.part == SettingsProfileColorPart.NAME)
                old.values.copy(name = write.edit.desired, gift = null) else old.values.copy(profile = write.edit.desired)
            snapshot = snapshot.copy(busy = false, activeSave = null, data = old.copy(values = values),
                error = error ?: if (readbackUnknown) SettingsError.REQUEST_FAILED else null,
                readbackPending = if (readbackUnknown) setOf(write.edit.part) else emptySet(), refreshRequired = readbackUnknown,
                outcomes = snapshot.outcomes + (write.edit.part to SettingsProfileColorOutcome(write, accepted,
                    error ?: if (readbackUnknown) SettingsError.REQUEST_FAILED else null)))
        }
        fun freshRead(name: SettingsProfileColorValue? = null) = onUi {
            val data = assertNotNull(snapshot.data)
            snapshot = snapshot.copy(data = if (name == null) data else data.copy(values = data.values.copy(name = name)),
                readbackPending = emptySet(), refreshRequired = false, error = null,
                outcomes = snapshot.outcomes.mapValues { (_, outcome) -> outcome.copy(error = null) })
        }
        fun scene() = ComposePixelScene(width, height) {
            val theme = remember { ThemeController(mode) }
            CompositionLocalProvider(LocalDensity provides Density(density), LocalSettingsPageActive provides active,
                LocalSettingsSecurityConfirmationHost provides InlineConfirmation(),
                LocalSettingsProfileColorPatternPreview provides artwork) {
                MiuixTheme(controller = theme) {
                    if (mounted) Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).padding(12.dp)) {
                        if (profileContext) SettingsProfilePage(SettingsSnapshot(token = accountToken, enabled = true,
                            profile = profile, loaded = setOf(SettingsSection.PROFILE), profileColors = snapshot),
                            SettingsActions(profileColors = actions, saveProfile = { _, edit -> profileSaves += edit }),
                            ChatSnapshot(), ChatFeatureActions())
                        else SettingsProfileColorsControls(accountToken, profile, snapshot, actions, active)
                    }
                }
            }
        }
    }

    private fun ComposePixelScene.texts(label: String) = semanticsBounds {
        it.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == label }
    }
    private fun ComposePixelScene.hasText(label: String) = texts(label).isNotEmpty()
    private fun ComposePixelScene.enter(f: Fixture) { settledFrame(); clickText("名字与资料颜色", f); settledFrame() }
    private fun ComposePixelScene.clickText(label: String, f: Fixture) {
        val rect = reveal(label, f)
        moveTo(rect.center.x, rect.center.y); pressAt(rect.center.x, rect.center.y); releaseAt(rect.center.x, rect.center.y); settledFrame()
    }
    private fun ComposePixelScene.reveal(label: String, f: Fixture): Rect {
        val inset = 3f * f.density
        fun visible() = texts(label).firstOrNull { it.width > 0 && it.height >= 8 * f.density && it.top >= inset && it.bottom <= f.height - inset }
        var down = false
        repeat(1100) {
            visible()?.let { return it }
            val target = texts(label).firstOrNull { it.width > 0 && it.height > 0 }
            val ranges = mutableListOf<Pair<Float, Float>>()
            semanticsBounds {
                it.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { axis -> ranges += axis.value() to axis.maxValue() }; false
            }
            val root = ranges.maxByOrNull { it.second }
            if (root?.first?.let { it <= .5f } == true) down = true
            val delta = when { target != null -> if (target.top < inset) -12f else 12f; down -> 12f; else -> -12f }
            // The outer scroll's left strip lies outside both bounded native-option grids.
            scrollAt(2f * f.density, f.height * .5f, deltaY = delta * f.density)
            settledFrame(4, 32)
        }
        return assertNotNull(visible(), "Actual pointer scrolling must reveal $label at ${f.width}x${f.height}; bounds=${texts(label)}")
    }
    private fun ComposePixelFrame.save(name: String) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, this[x, y])
        val file = File("build/settings-profile-colors-validation/frames/$name.png")
        file.parentFile.mkdirs(); ImageIO.write(image, "png", file)
    }
    private fun rgbDistance(actual: Int, rgb: Int): Int =
        kotlin.math.abs((actual ushr 16 and 255) - (rgb ushr 16 and 255)) +
            kotlin.math.abs((actual ushr 8 and 255) - (rgb ushr 8 and 255)) + kotlin.math.abs((actual and 255) - (rgb and 255))

    companion object {
        private const val SELF = 70001L
        private fun palette(seed: Int) = SettingsProfileAccentPalette(listOf(seed, 0xb09070),
            listOf(0x103050, 0xe0b080), listOf(0x708090, 0xb0c0d0))
        private fun catalog() = SettingsProfileColorsCatalog(nameColors = emptyList(), availableNameColorIds = (0..6).toList(),
            profileColors = (11..13).map { SettingsProfileAccentColor(it, palette(0x506070 + it), palette(0x90a0b0 + it)) },
            availableProfileColorIds = listOf(11, 12, 13), revision = 1)
        private fun snapshot(name: SettingsProfileColorValue = SettingsProfileColorValue(0),
            profile: SettingsProfileColorValue = SettingsProfileColorValue(11), premium: Boolean = true,
            catalog: SettingsProfileColorsCatalog = catalog(), gift: SettingsUpgradedGiftColors? = null) =
            SettingsProfileColorsSnapshot(token = SettingsProfileColorsToken(7, 0, SELF),
                data = SettingsProfileColorsData(SELF, premium, SettingsProfileColorsValues(name, profile, gift), catalog, 1))
        private fun gift() = SettingsUpgradedGiftColors(88, -101, 102, 0x123456, listOf(0x123456, 0x876543),
            0xabcdef, listOf(0xabcdef, 0x987654))
        private fun emoji(id: Long) = SettingsBackgroundEmoji(id, StickerItem(333, 4, "metadata only",
            MessageMedia(MediaFile(33), width = 100, height = 100)))
    }
}
