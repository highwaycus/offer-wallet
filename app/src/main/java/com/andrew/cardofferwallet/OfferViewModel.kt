package com.andrew.cardofferwallet

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OfferViewModel(application: Application) : AndroidViewModel(application) {
    private val database = OfferDatabase(application)
    private val _offers = MutableStateFlow(database.allOffers())
    val offers = _offers.asStateFlow()
    private val _cards = MutableStateFlow(database.allCards())
    val cards = _cards.asStateFlow()
    private val _draft = MutableStateFlow<OfferDraft?>(null)
    val draft = _draft.asStateFlow()
    private val _isReading = MutableStateFlow(false)
    val isReading = _isReading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun importScreenshot(uri: Uri) {
        _isReading.value = true
        _error.value = null
        val image = runCatching { InputImage.fromFilePath(getApplication(), uri) }.getOrElse {
            _isReading.value = false
            _error.value = "Could not open this screenshot."
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { result ->
                val ocrLines = result.textBlocks.flatMap { block ->
                    block.lines.map { line -> OcrLine(line.text, line.boundingBox?.height() ?: 0) }
                }
                _draft.value = OfferParser.parse(result.text, uri.toString(), _cards.value, ocrLines)
                _isReading.value = false
            }
            .addOnFailureListener {
                _error.value = "Text recognition failed. You can try another screenshot."
                _isReading.value = false
            }
    }

    fun openForEdit(offer: Offer) {
        _draft.value = OfferDraft(
            offer.merchant, offer.cardName, offer.reward, offer.rewardScore,
            offer.expiresOn, offer.minimumSpend, offer.maximumReward, offer.terms,
            offer.screenshotUri, offer.rawText
        )
        editingId = offer.id
        editingCreatedAt = offer.createdAt
        editingRedeemed = offer.redeemed
    }

    private var editingId = 0L
    private var editingCreatedAt = 0L
    private var editingRedeemed = false

    fun saveDraft(draft: OfferDraft) {
        val created = if (editingId == 0L) System.currentTimeMillis() else editingCreatedAt
        database.save(draft.copy(rewardScore = OfferParser.score(draft.reward)).toOffer(editingId, editingRedeemed, created))
        clearDraft()
        refresh()
    }

    fun clearDraft() {
        _draft.value = null
        editingId = 0L
        editingCreatedAt = 0L
        editingRedeemed = false
    }

    fun toggleRedeemed(offer: Offer) {
        database.save(offer.copy(redeemed = !offer.redeemed))
        refresh()
    }

    fun delete(offer: Offer) {
        database.delete(offer.id)
        refresh()
    }

    fun addCard(name: String, issuer: String, ending: String) {
        val digits = ending.filter(Char::isDigit)
        if (name.isBlank() || issuer.isBlank() || digits.length !in 4..5) return
        database.saveCard(CardProfile(name = name, issuer = issuer, ending = digits))
        _cards.value = database.allCards()
    }

    fun deleteCard(card: CardProfile) {
        database.deleteCard(card.id)
        _cards.value = database.allCards()
    }

    fun dismissError() { _error.value = null }
    private fun refresh() { _offers.value = database.allOffers() }
}
