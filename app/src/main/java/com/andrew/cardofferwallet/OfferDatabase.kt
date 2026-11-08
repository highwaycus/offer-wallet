package com.andrew.cardofferwallet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfferDatabase(context: Context) : SQLiteOpenHelper(context, "offers.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE offers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                merchant TEXT NOT NULL,
                card_name TEXT NOT NULL,
                reward TEXT NOT NULL,
                reward_score REAL NOT NULL DEFAULT 0,
                expires_on TEXT NOT NULL DEFAULT '',
                minimum_spend TEXT NOT NULL DEFAULT '',
                maximum_reward TEXT NOT NULL DEFAULT '',
                terms TEXT NOT NULL DEFAULT '',
                screenshot_uri TEXT NOT NULL DEFAULT '',
                raw_text TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                redeemed INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        createCardsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCardsTable(db)
    }

    private fun createCardsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                issuer TEXT NOT NULL,
                ending TEXT NOT NULL
            )""".trimIndent()
        )
    }

    fun allOffers(): List<Offer> {
        val offers = mutableListOf<Offer>()
        readableDatabase.query("offers", null, null, null, null, null, "created_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                offers += Offer(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    merchant = cursor.getString(cursor.getColumnIndexOrThrow("merchant")),
                    cardName = cursor.getString(cursor.getColumnIndexOrThrow("card_name")),
                    reward = cursor.getString(cursor.getColumnIndexOrThrow("reward")),
                    rewardScore = cursor.getDouble(cursor.getColumnIndexOrThrow("reward_score")),
                    expiresOn = cursor.getString(cursor.getColumnIndexOrThrow("expires_on")),
                    minimumSpend = cursor.getString(cursor.getColumnIndexOrThrow("minimum_spend")),
                    maximumReward = cursor.getString(cursor.getColumnIndexOrThrow("maximum_reward")),
                    terms = cursor.getString(cursor.getColumnIndexOrThrow("terms")),
                    screenshotUri = cursor.getString(cursor.getColumnIndexOrThrow("screenshot_uri")),
                    rawText = cursor.getString(cursor.getColumnIndexOrThrow("raw_text")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    redeemed = cursor.getInt(cursor.getColumnIndexOrThrow("redeemed")) == 1
                )
            }
        }
        return offers
    }

    fun save(offer: Offer): Long {
        val values = ContentValues().apply {
            put("merchant", offer.merchant)
            put("card_name", offer.cardName)
            put("reward", offer.reward)
            put("reward_score", offer.rewardScore)
            put("expires_on", offer.expiresOn)
            put("minimum_spend", offer.minimumSpend)
            put("maximum_reward", offer.maximumReward)
            put("terms", offer.terms)
            put("screenshot_uri", offer.screenshotUri)
            put("raw_text", offer.rawText)
            put("created_at", offer.createdAt)
            put("redeemed", if (offer.redeemed) 1 else 0)
        }
        return if (offer.id == 0L) writableDatabase.insertOrThrow("offers", null, values)
        else {
            writableDatabase.update("offers", values, "id=?", arrayOf(offer.id.toString()))
            offer.id
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("offers", "id=?", arrayOf(id.toString()))
    }

    fun allCards(): List<CardProfile> {
        val cards = mutableListOf<CardProfile>()
        readableDatabase.query("cards", null, null, null, null, null, "name COLLATE NOCASE").use { cursor ->
            while (cursor.moveToNext()) {
                cards += CardProfile(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    issuer = cursor.getString(cursor.getColumnIndexOrThrow("issuer")),
                    ending = cursor.getString(cursor.getColumnIndexOrThrow("ending"))
                )
            }
        }
        return cards
    }

    fun saveCard(card: CardProfile): Long {
        val values = ContentValues().apply {
            put("name", card.name.trim())
            put("issuer", card.issuer.trim())
            put("ending", card.ending.filter(Char::isDigit).takeLast(5))
        }
        return writableDatabase.insertOrThrow("cards", null, values)
    }

    fun deleteCard(id: Long) {
        writableDatabase.delete("cards", "id=?", arrayOf(id.toString()))
    }
}
