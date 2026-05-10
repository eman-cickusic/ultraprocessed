package com.b2.ultraprocessed.storage.room

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovaDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databaseName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFrom1To4_preservesRowsAndAddsDefaults() = runBlocking {
        createVersion1Database()

        val database = Room.databaseBuilder(context, NovaDatabase::class.java, databaseName)
            .addMigrations(*NovaDatabase.MIGRATIONS)
            .build()

        val row = database.scanResultDao().getScanResultById(1L)
        database.close()

        requireNotNull(row)
        assertEquals("Scanned label", row.productName)
        assertEquals(0, row.novaGroup)
        assertEquals("corn, salt", row.cleanedIngredients)
        assertEquals("", row.allergens)
        assertFalse(row.isBarcodeLookupOnly)
        assertEquals("", row.modelId)
        assertEquals("", row.modelName)
        assertEquals("", row.provider)
        assertEquals(0, row.estimatedTotalTokens)
        assertEquals(0.0, row.estimatedCostUsd, 0.0)
    }

    private fun createVersion1Database() {
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scan_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ocrText TEXT NOT NULL,
                    cleanedIngredients TEXT NOT NULL,
                    verdict TEXT NOT NULL,
                    confidenceScore REAL NOT NULL,
                    detectedMarkers TEXT NOT NULL,
                    explanation TEXT NOT NULL,
                    engineUsed TEXT NOT NULL,
                    scannedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO scan_results (
                    id, ocrText, cleanedIngredients, verdict, confidenceScore,
                    detectedMarkers, explanation, engineUsed, scannedAt
                ) VALUES (
                    1, 'Ingredients: corn, salt', 'corn, salt', 'NOVA 3',
                    0.7, '[]', 'Processed food', 'legacy', 123456
                )
                """.trimIndent(),
            )
            db.version = 1
        }
    }
}
