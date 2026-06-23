package com.quantumchat.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantumchat.core.common.model.Contact
import com.quantumchat.core.crypto.CryptoManager
import com.quantumchat.core.data.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// MVI State
data class ContactsUiState(
    val isLoading: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val error: String? = null,
    val isScanningQr: Boolean = false,
    val lastScanResultMessage: String? = null
)

// MVI Intent
sealed interface ContactsUiIntent {
    object LoadContacts : ContactsUiIntent
    data class AddContact(val name: String) : ContactsUiIntent
    data class DeleteContact(val contact: Contact) : ContactsUiIntent
    object StartQrScanner : ContactsUiIntent
    object StopQrScanner : ContactsUiIntent
    data class ProcessScannedQR(val qrContent: String) : ContactsUiIntent
}

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val contactRepository: ContactRepository,
    private val cryptoManager: CryptoManager
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
                contactRepository.observeContacts().collect { contactsList ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        contacts = contactsList,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load contacts: ${e.message}"
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
                        val fingerprint = cryptoManager.generateContactFingerprint()
                        val newContact = Contact(
                            id = System.currentTimeMillis().toString(),
                            name = intent.name,
                            publicKeyFingerprint = fingerprint,
                            isOnline = true
                        )
                        contactRepository.addContact(newContact)
                        _state.value = _state.value.copy(error = null)
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            error = "Failed to add contact: ${e.message}"
                        )
                    }
                }
                is ContactsUiIntent.DeleteContact -> {
                    try {
                        contactRepository.deleteContact(intent.contact)
                        _state.value = _state.value.copy(error = null)
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            error = "Failed to delete contact: ${e.message}"
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
                            contactRepository.addContact(verifiedContact)
                            _state.value = _state.value.copy(
                                isScanningQr = false,
                                lastScanResultMessage = "Successfully verified and added contact.",
                                error = null
                            )
                        } else {
                            _state.value = _state.value.copy(
                                isScanningQr = false,
                                error = "Verification failed: Invalid QR code signature or scheme.",
                                lastScanResultMessage = null
                            )
                        }
                    } catch (e: Exception) {
                        _state.value = _state.value.copy(
                            isScanningQr = false,
                            error = "Verification failed: ${e.message}",
                            lastScanResultMessage = null
                        )
                    }
                }
            }
        }
    }
}
