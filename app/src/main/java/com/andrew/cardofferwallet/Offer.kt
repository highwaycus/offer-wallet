package com.andrew.cardofferwallet

import java.time.LocalDate

data class Offer(
    val id: Long = 0,
    val merchant: String,
    val cardName: String,
    val reward: String,
    val rewardScore: Double = 0.0,
    val expiresOn: String = "",
    val minimumSpend: String = "",
    val maximumReward: String = "",
    val terms: String = "",
    val screenshotUri: String = "",
    val rawText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val redeemed: Boolean = false
) {
    fun expirationDate(): LocalDate? = runCatching { LocalDate.parse(expiresOn) }.getOrNull()
    fun isExpired(today: LocalDate = LocalDate.now()): Boolean = expirationDate()?.isBefore(today) == true
    fun isActive(today: LocalDate = LocalDate.now()): Boolean = !redeemed && !isExpired(today)
}

data class CardProfile(
    val id: Long = 0,
    val name: String,
    val issuer: String,
    val ending: String
) {
    fun displayName(): String = "$name ••$ending"
}

data class OfferDraft(
    val merchant: String = "",
    val cardName: String = "",
    val reward: String = "",
    val rewardScore: Double = 0.0,
    val expiresOn: String = "",
    val minimumSpend: String = "",
    val maximumReward: String = "",
    val terms: String = "",
    val screenshotUri: String = "",
    val rawText: String = ""
) {
    fun toOffer(id: Long = 0, redeemed: Boolean = false, createdAt: Long = System.currentTimeMillis()) = Offer(
        id, merchant.trim(), cardName.trim(), reward.trim(), rewardScore,
        expiresOn.trim(), minimumSpend.trim(), maximumReward.trim(), terms.trim(),
        screenshotUri, rawText, createdAt, redeemed
    )
}
