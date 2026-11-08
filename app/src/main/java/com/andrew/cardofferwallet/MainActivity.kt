package com.andrew.cardofferwallet

import android.content.Intent
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OfferWalletTheme { OfferWalletApp() } }
    }
}

private val Pine = Color(0xFF355E4A)
private val Cream = Color(0xFFF8FAF7)
private enum class OfferList { AVAILABLE, EXPIRED, HISTORY }
private val preferredCards = listOf(
    "Amex Gold", "Amex Platinum", "Amex Blue Cash Everyday",
    "Chase Sapphire Preferred", "Chase Freedom", "Amazon Prime Visa",
    "United Explorer", "Capital One Savor", "Capital One Quicksilver",
    "Citi AAdvantage MileUp", "Fry's Elite Mastercard"
)

@Composable
fun OfferWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Pine, background = Cream, surface = Color.White),
        typography = Typography(), content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferWalletApp(vm: OfferViewModel = viewModel()) {
    val offers by vm.offers.collectAsState()
    val cards by vm.cards.collectAsState()
    val draft by vm.draft.collectAsState()
    val isReading by vm.isReading.collectAsState()
    val error by vm.error.collectAsState()
    var query by remember { mutableStateOf("") }
    var selectedCard by remember { mutableStateOf("All cards") }
    var offerList by remember { mutableStateOf(OfferList.AVAILABLE) }
    var showCardManager by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                vm.getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.importScreenshot(uri)
        }
    }
    val cardOptions = listOf("All cards") + offers.map { it.cardName }.filter { it.isNotBlank() }.distinct().sorted()
    val bestOfferIds = offers.filter { it.isActive() }.groupBy { it.merchant.lowercase().trim() }.values.mapNotNull { merchantOffers ->
        merchantOffers.maxWithOrNull(compareBy<Offer> { it.rewardScore }.thenByDescending { it.expirationDate() ?: LocalDate.MAX })?.id
    }.toSet()
    val matchingOffers = offers.filter {
        when (offerList) {
            OfferList.AVAILABLE -> it.isActive()
            OfferList.EXPIRED -> it.isExpired()
            OfferList.HISTORY -> true
        } &&
            (selectedCard == "All cards" || it.cardName == selectedCard) &&
            (query.isBlank() || listOf(it.merchant, it.cardName, it.reward, it.terms).any { field -> field.contains(query, true) })
    }
    val filtered = when (offerList) {
        OfferList.EXPIRED -> matchingOffers.sortedWith(compareByDescending<Offer> { it.expirationDate() ?: LocalDate.MIN }.thenByDescending { it.rewardScore })
        else -> matchingOffers.sortedWith(compareBy<Offer> { it.expirationDate() ?: LocalDate.MAX }.thenByDescending { it.rewardScore })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Offer Wallet", fontWeight = FontWeight.Bold)
                        Text("Know which card to use", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                    }
                },
                actions = {
                    IconButton(onClick = { showCardManager = true }) {
                        Icon(Icons.Default.CreditCard, contentDescription = "My cards")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("image/*")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Import offer") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SearchBar(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = offerList == OfferList.AVAILABLE, onClick = { offerList = OfferList.AVAILABLE }, label = { Text("Available") })
                FilterChip(selected = offerList == OfferList.EXPIRED, onClick = { offerList = OfferList.EXPIRED }, label = { Text("Expired") })
                FilterChip(selected = offerList == OfferList.HISTORY, onClick = { offerList = OfferList.HISTORY }, label = { Text("All history") })
                cardOptions.forEach { card ->
                    FilterChip(selected = selectedCard == card, onClick = { selectedCard = card }, label = { Text(card) })
                }
            }
            Spacer(Modifier.height(10.dp))
            if (filtered.isEmpty()) EmptyState(hasOffers = offers.isNotEmpty(), offerList = offerList, onImport = { picker.launch(arrayOf("image/*")) })
            else LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        when (offerList) {
                            OfferList.AVAILABLE -> "${filtered.size} offer${if (filtered.size == 1) "" else "s"} ready to use"
                            OfferList.EXPIRED -> "${filtered.size} expired offer${if (filtered.size == 1) "" else "s"}"
                            OfferList.HISTORY -> "${filtered.size} saved offers"
                        },
                        style = MaterialTheme.typography.labelLarge, color = Color.Gray
                    )
                }
                items(filtered, key = { it.id }) { offer ->
                    OfferCard(offer, isBest = offer.id in bestOfferIds, onEdit = { vm.openForEdit(offer) }, onRedeem = { vm.toggleRedeemed(offer) }, onDelete = { vm.delete(offer) })
                }
            }
        }
    }

    val cardChoices = (cards.map { it.displayName() } + preferredCards).distinct()
    if (draft != null) {
        key(draft!!.screenshotUri, draft!!.rawText) {
            OfferReviewDialog(draft!!, cardChoices, onDismiss = vm::clearDraft, onSave = vm::saveDraft)
        }
    }
    if (showCardManager) CardManagerDialog(
        cards = cards,
        onAdd = vm::addCard,
        onDelete = vm::deleteCard,
        onDismiss = { showCardManager = false }
    )
    if (isReading) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("Reading screenshot…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) })
    if (error != null) AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } }, title = { Text("Could not import offer") }, text = { Text(error!!) })
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, modifier = modifier.fillMaxWidth(), singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search merchant, card, or terms") },
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun EmptyState(hasOffers: Boolean, offerList: OfferList, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.CreditCard, null, modifier = Modifier.size(56.dp), tint = Pine)
        Spacer(Modifier.height(16.dp))
        Text(
            if (!hasOffers) "Your usable card offers will appear here"
            else when (offerList) {
                OfferList.AVAILABLE -> "No matching available offers"
                OfferList.EXPIRED -> "No expired offers"
                OfferList.HISTORY -> "No matching saved offers"
            },
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (!hasOffers) "Import a screenshot, review the detected details, and save it."
            else when (offerList) {
                OfferList.AVAILABLE -> "Try another search, or check Expired and All history."
                OfferList.EXPIRED -> "Offers move here automatically after their expiration date."
                OfferList.HISTORY -> "Try another search or card filter."
            },
            color = Color.Gray
        )
        if (!hasOffers) { Spacer(Modifier.height(18.dp)); Button(onClick = onImport) { Text("Choose screenshot") } }
    }
}

@Composable
private fun OfferCard(offer: Offer, isBest: Boolean, onEdit: () -> Unit, onRedeem: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val expired = offer.isExpired()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(containerColor = if (offer.isActive()) Color.White else Color(0xFFF1F1F1)),
        border = if (offer.isActive()) BorderStroke(1.dp, Color(0xFFDDE6DF)) else null
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(offer.merchant.ifBlank { "Unnamed merchant" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(offer.cardName.ifBlank { "Card not selected" }, color = Pine, style = MaterialTheme.typography.labelLarge)
                    if (isBest) Text("Best saved offer for this merchant", color = Color(0xFF27663C), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("•••") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (offer.redeemed) "Mark available" else "Mark used") }, onClick = { menu = false; onRedeem() }, leadingIcon = { Icon(Icons.Default.CheckCircle, null) })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(offer.reward.ifBlank { "Reward not detected" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (offer.minimumSpend.isNotBlank() || offer.maximumReward.isNotBlank()) {
                Text(listOfNotNull(
                    offer.minimumSpend.takeIf { it.isNotBlank() }?.let { "Min. spend $it" },
                    offer.maximumReward.takeIf { it.isNotBlank() }?.let { "Max. back $it" }
                ).joinToString(" · "), color = Color.DarkGray)
            }
            val restrictions = restrictionLabels(offer.terms)
            if (restrictions.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text(restrictions.joinToString(" · "), color = Color(0xFF7A4A18), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when { offer.redeemed -> "Used"; expired -> "Expired"; offer.expiresOn.isBlank() -> "No expiry saved"; else -> "Expires ${prettyDate(offer.expiresOn)}" }
                AssistChip(onClick = onEdit, label = { Text(status) })
                if (offer.isActive() && offer.expirationDate()?.let { !it.isAfter(LocalDate.now().plusDays(7)) } == true) {
                    Spacer(Modifier.width(8.dp)); Text("Ending soon", color = Color(0xFF9A4A20), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun prettyDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
}.getOrDefault(date)

private fun restrictionLabels(terms: String): List<String> = buildList {
    if (terms.contains("activate", true) || terms.contains("enroll", true)) add("Activation may be required")
    if (terms.contains("online only", true) || terms.contains("online purchase", true)) add("Online only")
    if (terms.contains("first paid subscription", true) || terms.contains("new customer", true)) add("New customer / first subscription")
    if (terms.contains("one time", true) || terms.contains("one-time", true)) add("One-time offer")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfferReviewDialog(initial: OfferDraft, cardChoices: List<String>, onDismiss: () -> Unit, onSave: (OfferDraft) -> Unit) {
    var merchant by remember(initial) { mutableStateOf(initial.merchant) }
    var card by remember(initial) { mutableStateOf(initial.cardName) }
    var reward by remember(initial) { mutableStateOf(initial.reward) }
    var expiry by remember(initial) { mutableStateOf(initial.expiresOn) }
    var minSpend by remember(initial) { mutableStateOf(initial.minimumSpend) }
    var maxReward by remember(initial) { mutableStateOf(initial.maximumReward) }
    var terms by remember(initial) { mutableStateOf(initial.terms) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review detected offer") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("OCR can miss details. Confirm these fields before saving.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                if (initial.screenshotUri.isNotBlank()) {
                    AsyncImage(
                        model = initial.screenshotUri,
                        contentDescription = "Imported offer screenshot",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Field("Merchant", merchant) { merchant = it }
                Field("Card", card) { card = it }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cardChoices.forEach { name -> SuggestionChip(onClick = { card = name }, label = { Text(name) }) }
                }
                Field("Reward (for example: 10% cash back)", reward) { reward = it }
                Field("Expiration (YYYY-MM-DD)", expiry, KeyboardType.Number) { expiry = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Minimum spend", minSpend, KeyboardType.Decimal, Modifier.weight(1f)) { minSpend = it }
                    Field("Maximum back", maxReward, KeyboardType.Decimal, Modifier.weight(1f)) { maxReward = it }
                }
                OutlinedTextField(value = terms, onValueChange = { terms = it }, label = { Text("Terms / notes") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            }
        },
        confirmButton = {
            Button(
                enabled = merchant.isNotBlank() && card.isNotBlank() && reward.isNotBlank(),
                onClick = { onSave(initial.copy(merchant = merchant, cardName = card, reward = reward, expiresOn = expiry, minimumSpend = minSpend, maximumReward = maxReward, terms = terms)) }
            ) { Text("Save offer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CardManagerDialog(
    cards: List<CardProfile>,
    onAdd: (String, String, String) -> Unit,
    onDelete: (CardProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var issuer by remember { mutableStateOf("Amex") }
    var ending by remember { mutableStateOf("") }
    val digits = ending.filter(Char::isDigit)
    val issuers = listOf("Amex", "Chase", "Citi", "Capital One", "Other")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My cards") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add each card once. Offer Wallet will match screenshots using the issuer and last 4 or 5 digits.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                if (cards.isEmpty()) Text("No cards saved yet.", color = Color.Gray)
                cards.forEach { card ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.name, fontWeight = FontWeight.SemiBold)
                            Text("${card.issuer} ••${card.ending}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { onDelete(card) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${card.name}")
                        }
                    }
                }
                if (cards.isNotEmpty()) HorizontalDivider()
                Field("Card name", name) { name = it }
                Text("Issuer", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    issuers.forEach { option ->
                        FilterChip(selected = issuer == option, onClick = { issuer = option }, label = { Text(option) })
                    }
                }
                Field("Last 4 or 5 digits", ending, KeyboardType.Number) { ending = it.filter(Char::isDigit).take(5) }
                Button(
                    enabled = name.isNotBlank() && digits.length in 4..5,
                    onClick = {
                        onAdd(name, issuer, digits)
                        name = ""
                        ending = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add card") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label) }, modifier = modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
