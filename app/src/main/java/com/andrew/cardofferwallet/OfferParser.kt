package com.andrew.cardofferwallet

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class OcrLine(val text: String, val height: Int)

object OfferParser {
    private val noise = Regex(
        "(?i)^(new(?: offer)?|limited time|featured|offers?|added|eligible|enrolled|details?|terms?|" +
            "shop now|shop online|visit website|photo|health|dining|activity|completed hotel stay|" +
            "completed car rental|completed vacation package|completed flight|activate|activated|" +
            "expires?|expiration|cash back|rewards?)$"
    )
    private val merchantBoilerplate = Regex(
        "(?i)[%$]|\\boffers?\\b|cash back|expires?|spend|purchase|use your card|added to card|" +
            "shop online|visit website|click the button|go to the merchant|website|review exclusions|" +
            "avoid ad blockers|view all offer terms|payouts? (?:are|is)|about this deal|how to get this deal|offer details"
    )
    private val nonMerchantCardText = Regex(
        "(?i)^(visa|mastercard|american express|amerikam|anerikam|[oq0]?uicksilver|sapphire|preferred|platinum|gold)$"
    )
    private val statusBarText = Regex("^\\d{1,2}:\\d{2}\\b")
    private val rewardPatterns = listOf(
        Regex("(?i)(?:up to\\s+)?([0-9]+(?:\\.[0-9]+)?)\\s*%\\s*(back|cash back|off)"),
        Regex("(?i)(up to\\s+)?\\$\\s?([0-9]+(?:\\.[0-9]{1,2})?)\\s*(back|cash back|statement credit)"),
        Regex("(?i)(earn|get|save)\\s+\\$?([0-9]+(?:\\.[0-9]{1,2})?)")
    )
    private val datePatterns = listOf(
        Regex("(?i)(?:expires?(?:\\s+on)?|expiration(?: date)?|valid (?:through|until)|use by|offer ends?)[:\\s-]*([A-Za-z]{3,9}\\s+\\d{1,2},?\\s+20\\d{2})"),
        Regex("(?i)(?:expires?(?:\\s+on)?|expiration(?: date)?|valid (?:through|until)|use by|offer ends?)[:\\s-]*(\\d{1,2}[/-]\\d{1,2}[/-](?:20)?\\d{2})"),
        Regex("(?i)\\bby\\s+(\\d{1,2}[/-]\\d{1,2}[/-](?:20)?\\d{2})"),
        Regex("(?i)(?:expires?(?:\\s+on)?|expiration(?: date)?|valid (?:through|until)|use by|offer ends?)[:\\s-]*([A-Za-z]{3,9}\\s+\\d{1,2})(?![,\\s]+20\\d{2})")
    )

    fun parse(
        text: String,
        uri: String,
        cards: List<CardProfile> = emptyList(),
        ocrLines: List<OcrLine> = emptyList()
    ): OfferDraft {
        val normalized = text.replace('•', ' ').replace(Regex("[ \\t]+"), " ").trim()
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }
        val rewardMatch = rewardPatterns.firstNotNullOfOrNull { it.find(normalized) }
        val reward = correctCapitalOneReward(rewardMatch?.value?.trim().orEmpty(), normalized)
        val date = datePatterns.firstNotNullOfOrNull { it.find(normalized)?.groupValues?.getOrNull(1) }
        val merchant = detectMerchant(lines, rewardMatch?.range?.first ?: Int.MAX_VALUE, ocrLines)
        val minSpend = Regex("(?i)(?:spend|purchase(?:s)? (?:of|totaling)|when you spend)\\s+\\$\\s?([0-9]+(?:\\.[0-9]{1,2})?)")
            .find(normalized)?.let { "\$${it.groupValues[1]}" }.orEmpty()
        val maxReward = Regex("(?i)(?:maximum|max(?:imum)? (?:cash back|reward)|up to(?:\\s+a)?\\s+total\\s+of|up to)[:\\s]*\\$\\s?([0-9]+(?:\\.[0-9]{1,2})?)")
            .find(normalized)?.let { "\$${it.groupValues[1]}" }.orEmpty()
        val card = detectCard(text, cards)
        val expiration = parseDate(date).ifBlank { parseRelativeDate(normalized) }
        return OfferDraft(
            merchant = merchant,
            cardName = card,
            reward = reward,
            rewardScore = score(reward),
            expiresOn = expiration,
            minimumSpend = minSpend,
            maximumReward = maxReward,
            terms = normalized.take(4000),
            screenshotUri = uri,
            rawText = normalized
        )
    }

    private fun detectCard(text: String, cards: List<CardProfile>): String {
        fun has(value: String) = text.contains(value, ignoreCase = true)
        fun hasWord(value: String) = Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(value)}(?![A-Za-z0-9])").containsMatchIn(text)
        val ending = extractCardEnding(text)
        val issuer = detectIssuer(text, ending)
        val suffixMatches = cards.filter { card ->
            ending != null && (
                card.ending == ending ||
                    (normalizeIssuer(card.issuer) == "amex" &&
                        (card.ending.endsWith(ending) || ending.endsWith(card.ending)))
                )
        }
        val issuerMatches = suffixMatches.filter { normalizeIssuer(it.issuer) == normalizeIssuer(issuer) }
        val profile = when {
            issuerMatches.size == 1 -> issuerMatches.single()
            suffixMatches.size == 1 -> suffixMatches.single()
            else -> null
        }
        if (profile != null) return profile.displayName()
        if (suffixMatches.size > 1) return ""

        fun label(name: String) = if (ending.isNullOrBlank()) name else "$name ••$ending"

        return when {
            has("Sapphire") && has("Preferred") -> label("Chase Sapphire Preferred")
            has("Sapphire") && has("Reserve") -> label("Chase Sapphire Reserve")
            has("Freedom Unlimited") -> label("Chase Freedom Unlimited")
            has("Freedom Flex") -> label("Chase Freedom Flex")
            has("Amazon Prime") || has("Prime Visa") -> label("Amazon Prime Visa")
            has("United Explorer") -> label("Chase United Explorer")
            has("Blue Cash Everyday") -> label("Amex Blue Cash Everyday")
            (has("American Express") && has("Platinum")) || has("Amex Platinum") -> label("Amex Platinum")
            (has("American Express") && has("Gold")) || has("Amex Gold") -> label("Amex Gold")
            has("AAdvantage") && has("Platinum") -> label("Citi AAdvantage Platinum")
            Regex("(?i)\\b[oq0]?uick\\s*silver\\b").containsMatchIn(text) -> label("Capital One Quicksilver")
            hasWord("Savor") -> label("Capital One Savor")
            has("Fry's") && has("Elite") -> label("Fry's Elite Mastercard")
            has("American Express") || has("Amex") -> label("American Express")
            Regex("(?i)\\bcapital\\s*one\\b").containsMatchIn(text) || has("capitaloneoffers.com") -> label("Capital One")
            hasWord("Chase") -> label("Chase")
            hasWord("Citi") -> label("Citi")
            else -> ""
        }
    }

    private fun extractCardEnding(text: String): String? {
        val insideCardLabel = Regex("(?is)added\\s+to\\s+card\\s*\\(([^)]{1,30})\\)")
            .find(text)?.groupValues?.getOrNull(1)?.filter(Char::isDigit)
        if (insideCardLabel != null && insideCardLabel.length in 4..5) return insideCardLabel

        return Regex("(?:\\.{2,}|•{2,}|\\*{2,})\\s*((?:\\d[\\s-]*){4,5})")
            .find(text)?.groupValues?.getOrNull(1)?.filter(Char::isDigit)?.takeIf { it.length in 4..5 }
    }

    private fun detectIssuer(text: String, ending: String?): String = when {
        text.contains("American Express", true) || text.contains("Amex", true) || ending?.length == 5 -> "Amex"
        Regex("(?i)\\bcapital\\s*one\\b").containsMatchIn(text) || text.contains("capitaloneoffers.com", true) -> "Capital One"
        Regex("(?i)\\bchase\\b").containsMatchIn(text) -> "Chase"
        Regex("(?i)\\bciti\\b").containsMatchIn(text) -> "Citi"
        else -> ""
    }

    private fun normalizeIssuer(value: String?): String = when {
        value.isNullOrBlank() -> ""
        value.contains("American Express", true) || value.contains("Amex", true) -> "amex"
        else -> value.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun correctCapitalOneReward(reward: String, text: String): String {
        val isCapitalOne = text.contains("capitaloneoffers.com", true) ||
            Regex("(?i)\\bcapital\\s*one(?:\\s+offers)?\\b").containsMatchIn(text)
        if (!isCapitalOne || !reward.contains('%')) return reward

        val detected = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*%")
            .find(reward)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return reward
        if (detected <= 30.0) return reward

        val plausibleRates = Regex("(?<![0-9])([0-9]{1,2}(?:\\.[0-9]+)?)\\s*%")
            .findAll(text)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .filter { it in 0.1..30.0 }
            .toList()
        val corrected = plausibleRates.maxOrNull() ?: return reward
        val display = if (corrected % 1.0 == 0.0) corrected.toInt().toString() else corrected.toString()
        return if (reward.contains("up to", true)) "Up to $display% back" else "$display% back"
    }

    private fun detectMerchant(lines: List<String>, rewardPosition: Int, ocrLines: List<OcrLine>): String {
        val combinedText = lines.joinToString(" ")
        val merchantFromExclusions = Regex(
            "(?i)orders? deemed by\\s+([A-Za-z0-9&'. -]{2,40}?)\\s+to be\\b"
        ).find(combinedText)?.groupValues?.getOrNull(1)?.trim()
        if (!merchantFromExclusions.isNullOrBlank()) return merchantFromExclusions

        val heights = ocrLines.mapNotNull { it.height.takeIf { value -> value > 0 } }.sorted()
        val medianHeight = if (heights.isEmpty()) 0.0 else heights[heights.size / 2].toDouble()
        fun lineKey(value: String) = value.trim().trimStart { !it.isLetterOrDigit() }
            .lowercase().replace(Regex("\\s+"), " ")
        val heightByText = ocrLines.groupBy { lineKey(it.text) }
            .mapValues { (_, matches) -> matches.maxOfOrNull { it.height } ?: 0 }
        val shopOnlineIndex = lines.indexOfFirst { it.contains("shop online", true) }

        fun meaningfulOccurrences(candidate: String): Int {
            val escaped = Regex.escape(candidate.trim())
            return Regex("(?i)(?<![A-Za-z0-9])$escaped(?![A-Za-z0-9])")
                .findAll(combinedText)
                .count()
        }

        var offset = 0
        val candidates = lines.mapIndexedNotNull { index, originalLine ->
            val position = offset
            offset += originalLine.length + 1
            val line = originalLine.trim().trimStart { !it.isLetterOrDigit() }.trim()
            val letters = line.filter(Char::isLetter)
            val wordCount = line.split(Regex("\\s+")).size
            if (
                line.length !in 2..50 || letters.length < 2 || noise.matches(line) ||
                merchantBoilerplate.containsMatchIn(line) || nonMerchantCardText.matches(line) ||
                statusBarText.containsMatchIn(line) ||
                Regex("(?i)https?://|www\\.|\\.com\\b|capital\\s*one").containsMatchIn(line)
            ) return@mapIndexedNotNull null

            var score = 0
            if (letters.all(Char::isUpperCase)) score += 5
            if (wordCount <= 3) score += 3
            if (position <= rewardPosition) score += 4
            if (index < 12) score += 2
            if (line.length <= 25) score += 1
            if (letters.length >= 3) score += 2
            // A merchant often appears once as the heading and again in the offer terms.
            // Repeated brand text is stronger evidence than a one-off UI badge.
            if (meaningfulOccurrences(line) >= 2) score += 8
            if (shopOnlineIndex >= 0 && index < shopOnlineIndex && shopOnlineIndex - index <= 3) score += 6
            if (medianHeight > 0) {
                val ratio = (heightByText[lineKey(line)] ?: 0) / medianHeight
                score += when {
                    ratio >= 1.8 -> 10
                    ratio >= 1.35 -> 5
                    ratio >= 1.15 -> 2
                    else -> 0
                }
            }
            line to score
        }
        return candidates.maxByOrNull { it.second }?.first.orEmpty()
    }

    fun score(reward: String): Double {
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(reward)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        return when {
            reward.contains('%') -> number
            reward.contains('$') -> number / 10.0
            else -> number / 100.0
        }
    }

    private fun parseDate(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val cleaned = value.replace(",", "").trim()
        val formats = listOf("MMMM d yyyy", "MMM d yyyy", "M/d/yyyy", "MM/dd/yyyy", "M-d-yyyy", "MM-dd-yyyy", "M/d/yy", "MM/dd/yy")
        for (format in formats) {
            try {
                return LocalDate.parse(cleaned, DateTimeFormatter.ofPattern(format, Locale.US)).toString()
            } catch (_: DateTimeParseException) { }
        }

        val today = LocalDate.now()
        for (format in listOf("MMMM d", "MMM d")) {
            try {
                var inferred = LocalDate.parse("$cleaned ${today.year}", DateTimeFormatter.ofPattern("$format yyyy", Locale.US))
                if (inferred.isBefore(today.minusMonths(2))) inferred = inferred.plusYears(1)
                return inferred.toString()
            } catch (_: DateTimeParseException) { }
        }
        return ""
    }

    private fun parseRelativeDate(text: String): String {
        val days = Regex("(?i)expires?\\s+in\\s+(\\d{1,3})\\s+days?")
            .find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return ""
        return LocalDate.now().plusDays(days).toString()
    }
}
