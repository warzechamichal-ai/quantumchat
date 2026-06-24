package com.quantumchat.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.data.ContactRepository
import com.quantumchat.core.networking.TransportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// MVI State Errors
sealed interface ContactsError {
    data class LoadFailed(val message: String) : ContactsError
    data class QrVerificationFailed(val message: String) : ContactsError
    data class DatabaseSaveFailed(val message: String) : ContactsError
}

// MVI State
data class ContactsUiState(
    val isLoading: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val error: ContactsError? = null,
    val isScanningQr: Boolean = false,
    val lastScanResultMessage: String? = null
)

// MVI Intent
sealed interface ContactsUiIntent {
    object LoadContacts : ContactsUiIntent
    data class AddContact(
        val name: String,
        val fingerprint: String? = null,
        val address: String? = null
    ) : ContactsUiIntent
    data class DeleteContact(val contact: Contact) : ContactsUiIntent
    data class UpdateContact(val contact: Contact) : ContactsUiIntent
    object StartQrScanner : ContactsUiIntent
    object StopQrScanner : ContactsUiIntent
    data class ProcessScannedQR(val qrContent: String) : ContactsUiIntent
}

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val cryptoManager: CryptoManager,
    private val transportManager: TransportManager
) : ViewModel() {

    private val _state = MutableStateFlow(ContactsUiState())
    val state: StateFlow<ContactsUiState> = _state.asStateFlow()

    init {
        observeContacts()
    }

    private fun observeContacts() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                combine(
                    contactRepository.observeContacts(),
                    transportManager.onlinePeers
                ) { contactsList, onlinePeers ->
                    contactsList.map { contact ->
                        val isPeerOnline = onlinePeers.contains(contact.publicKeyFingerprint)
                        contact.copy(isOnline = isPeerOnline)
                    }
                }.collect { contactsList ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        contacts = contactsList,
                        error = if (_state.value.error is ContactsError.LoadFailed) null else _state.value.error
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = ContactsError.LoadFailed(e.message ?: "Unknown load error")
                )
            }
        }
    }

    fun handleIntent(intent: ContactsUiIntent) {
        viewModelScope.launch {
            when (intent) {
                is ContactsUiIntent.LoadContacts -> {
                    // Handled automatically by observeContacts flow
                }
                is ContactsUiIntent.AddContact -> {
                    try {
                        val fingerprint = if (intent.fingerprint.isNullOrBlank()) {
                            cryptoManager.generateContactFingerprint()
                        } else {
                            intent.fingerprint
                        }
                        val newContact = Contact(
                            id = UUID.randomUUID().toString(),
                            name = intent.name,
                            publicKeyFingerprint = fingerprint,
                            isOnline = false,
                            onionAddress = intent.address
                        )
                        contactRepository.addContact(newContact)
                        _state.value = _state.value.copy(error = null)
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            error = ContactsError.DatabaseSaveFailed(e.message ?: "Failed to add contact")
                        )
                    }
                }
                is ContactsUiIntent.UpdateContact -> {
                    try {
                        contactRepository.updateContact(intent.contact)
                        _state.value = _state.value.copy(error = null)
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            error = ContactsError.DatabaseSaveFailed(e.message ?: "Failed to update contact")
                        )
                    }
                }
                is ContactsUiIntent.DeleteContact -> {
                    try {
                        contactRepository.deleteContact(intent.contact)
                        cryptoManager.deleteSession(intent.contact.publicKeyFingerprint)
                        _state.value = _state.value.copy(error = null)
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            error = ContactsError.DatabaseSaveFailed(e.message ?: "Failed to delete contact")
                        )
                    }
                }
                is ContactsUiIntent.StartQrScanner -> {
                    _state.value = _state.value.copy(isScanningQr = true, lastScanResultMessage = null, error = null)
                }
                is ContactsUiIntent.StopQrScanner -> {
                    _state.value = _state.value.copy(isScanningQr = false)
                }
                is ContactsUiIntent.ProcessScannedQR -> {
                    try {
                        val verifiedContact = cryptoManager.extractContactFromQR(intent.qrContent)
                        if (verifiedContact != null) {
                            try {
                                val displayName = if (verifiedContact.name == "DEV-MODE" || verifiedContact.name == "Device") {
                                    "Verified Device (${verifiedContact.publicKeyFingerprint.take(6)})"
                                } else {
                                    "Verified ${verifiedContact.name} (${verifiedContact.publicKeyFingerprint.take(6)})"
                                }
                                val newContact = Contact(
                                    id = UUID.randomUUID().toString(),
                                    name = displayName,
                                    publicKeyFingerprint = verifiedContact.publicKeyFingerprint,
                                    isOnline = true,
                                    onionAddress = verifiedContact.onionAddress
                                )
                                contactRepository.addContact(newContact)
                                _state.value = _state.value.copy(
                                    isScanningQr = false,
                                    lastScanResultMessage = "Successfully verified and added contact.",
                                    error = null
                                )
                            } catch (dbEx: Exception) {
                                _state.value = _state.value.copy(
                                    isScanningQr = false,
                                    error = ContactsError.DatabaseSaveFailed(dbEx.message ?: "Database write failed"),
                                    lastScanResultMessage = null
                                )
                            }
                        } else {
                            _state.value = _state.value.copy(
                                isScanningQr = false,
                                error = ContactsError.QrVerificationFailed("Invalid QR code signature or scheme"),
                                lastScanResultMessage = null
                            )
                        }
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            isScanningQr = false,
                            error = ContactsError.QrVerificationFailed(e.message ?: "Error processing QR payload"),
                            lastScanResultMessage = null
                        )
                    }
                }
            }
        }
    }
}
