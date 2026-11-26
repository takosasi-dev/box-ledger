package io.github.takosasidev.boxledger

import io.github.takosasidev.boxledger.core.model.AppSettings
import io.github.takosasidev.boxledger.core.model.DarkModeMode
import io.github.takosasidev.boxledger.core.model.Game
import io.github.takosasidev.boxledger.core.model.GameStatus
import io.github.takosasidev.boxledger.core.model.ThemeColorMode
import io.github.takosasidev.boxledger.core.model.ViewMode
import io.github.takosasidev.boxledger.core.model.WinCondition
import io.github.takosasidev.boxledger.data.db.Converters
import io.github.takosasidev.boxledger.data.toEntity
import io.github.takosasidev.boxledger.data.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** 表の形と :core のモデルの行き来で、列を取り違えていないかを確かめる。 */
class EntityMappingTest {

    private val at = Instant.parse("2026-11-26T01:02:03.456Z")

    @Test
    fun 設定は全部の項目が往復する() {
        // 既定値と違う値を全部に入れる(取り違えると、どれかが既定値に戻る)
        val settings = AppSettings(
            defaultViewMode = ViewMode.LIST, unplayedSuggestionThresholdDays = 45, themeColorMode = ThemeColorMode.MANUAL,
            manualThemeColor = -123, darkModeMode = DarkModeMode.DARK, celebrationEnabled = false, autoBackupEnabled = true,
            autoBackupIntervalDays = 3, autoBackupKeepCount = 9, lastAutoBackupAt = at, appLockEnabled = true, showExpansionsInList = true,
        )
        assertEquals(settings, settings.toEntity().toModel())
    }

    @Test
    fun ゲームの列を取り違えない() {
        val game = Game(
            uuid = "u", title = "架空のゲーム", parentUuid = "p", barcodeValue = "4900000000017", boxPhotoFile = "a.jpg",
            minPlayers = 1, maxPlayers = 2, minPlayTimeMinutes = 3, maxPlayTimeMinutes = 4, complexityRating = 2.5,
            purchasePrice = 5, purchaseCurrency = "USD", purchaseForeignAmount = BigDecimal("6.5"), purchaseExchangeRate = BigDecimal("7.25"),
            purchaseDate = LocalDate.parse("2026-01-02"), storageLocation = "棚", personalRating = 4,
            winCondition = WinCondition.LOW_SCORE, status = GameStatus.WISHLIST, isArchived = true, excludeFromSuggestion = true,
        )
        val e = game.toEntity(id = 8, parentId = 9, createdAt = at, updatedAt = at.plusSeconds(1))
        assertEquals(8L, e.id)
        assertEquals(9L, e.parentGameId)
        assertEquals(listOf(1, 2, 3, 4), listOf(e.minPlayers, e.maxPlayers, e.minPlayTimeMinutes, e.maxPlayTimeMinutes))
        assertEquals(listOf("u", "架空のゲーム", "4900000000017", "a.jpg", "USD", "棚"), listOf(e.uuid, e.title, e.barcodeValue, e.boxPhotoFile, e.purchaseCurrency, e.storageLocation))
        assertEquals(BigDecimal("6.5"), e.purchaseForeignAmount)
        assertEquals(BigDecimal("7.25"), e.purchaseExchangeRate)
        assertEquals(5, e.purchasePrice)
        assertEquals(4, e.personalRating)
        assertEquals(WinCondition.LOW_SCORE, e.winCondition)
        assertEquals(GameStatus.WISHLIST, e.status)
        assertEquals(true, e.isArchived)
        assertEquals(true, e.excludeFromSuggestion)
        assertEquals(at, e.createdAt)
        assertEquals(at.plusSeconds(1), e.updatedAt)
    }

    @Test
    fun 日付は_ISO_の文字_日時は_epoch_ミリ秒_外貨は文字で保存する() {
        val c = Converters()
        assertEquals("2027-01-31", c.localDateToText(LocalDate.parse("2027-01-31")))
        assertEquals(LocalDate.parse("2027-01-31"), c.textToLocalDate("2027-01-31"))
        assertEquals(at.toEpochMilli(), c.instantToMillis(at))
        assertEquals(at, c.millisToInstant(at.toEpochMilli()))
        assertEquals("59.99", c.decimalToText(BigDecimal("59.99")))
        assertEquals(BigDecimal("59.99"), c.textToDecimal("59.99"))
        assertNull(c.localDateToText(null))
        assertNull(c.textToLocalDate("こわれた値"))
        assertNull(c.millisToInstant(null))
        assertNull(c.textToDecimal("こわれた値"))
    }
}
