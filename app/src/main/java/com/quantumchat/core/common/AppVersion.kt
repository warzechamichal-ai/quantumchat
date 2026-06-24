package com.quantumchat.core.common

import com.quantumchat.BuildConfig

/**
 * Centrally manages the application version and changelog.
 */
object AppVersion {
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    val changelog: List<String> = listOf(
        "v3.4: Rozbudowano zarządzanie kontaktami (CRUD) w UI: dodano dynamiczne dialogi dodawania, edycji i usuwania kontaktów, a także zaktualizowano testy jednostkowe ContactsViewModelTest w celu poprawy stabilności oraz sporządzono diagnostykę tagów Logcat.",
        "v3.3: Wdrożono potwierdzenia dostarczenia wiadomości (Acks: SENT/DELIVERED), dynamiczny status online kontaktów (Flow/onlinePeers z TorTransport), automatyczne czyszczenie wygasłych offline wiadomości (TTL 14 dni) w Outbox oraz rozbudowany zestaw testów jednostkowych (PendingMessageDaoTest i TransportManagerOutboxTest).",
        "v3.2: Wdrożono trwałe kolejkowanie wiadomości offline (Outbox) za pomocą bazy danych Room i SQLCipher (nowa encja PendingMessageEntity i migracja 6->7) z logiką automatycznego wysyłania po połączeniu i limitem prób ponowień, a także zaimplementowano optymalizację rozgrzewania połączeń (pre-connect) w tle dla najczęściej używanych kontaktów oraz rozszerzone cache'owanie i pulę PeerConnection w TorTransport.",
        "v3.1: Wprowadzono automatyczną wymianę adresów .onion w procesie parowania kontaktów, poprawiono wydajność TorTransport przez kompresję pakietów, batchowanie wiadomości z 100ms buforowaniem, mechanizm keep-alive (heartbeat 60s) oraz odporność na błędy dzięki połączeniom z wykładniczym backoffem.",
        "v3.0: Rozbudowano integrację Tor o dwukierunkową komunikację przez Onion Service (ServerSocket na dedykowanym porcie 9095, handshake i ponowne użycie gniazd przychodzących w TorTransport) oraz automatyczne uruchamianie Tora przy starcie aplikacji.",
        "v2.9: Zaimplementowano integrację Tor Onion Services (v3) przy użyciu Orbot SOCKS proxy. Dodano TorManager monitorujący status i adres .onion za pomocą odbiorników rozgłoszeniowych (BroadcastReceiver) oraz TorTransport realizujący handshake tożsamości.",
        "v2.8: Wdrożono podstawowy routing Mesh (multi-hop z klasą MeshPacket, dynamicznymi tabelami routingu i floodingiem) w WiFiDirectTransport oraz zaimplementowano inteligentną optymalizację baterii (dynamiczne start/stop discovery w ChatViewModel zależne od połączenia i opcję manualnego wyszukiwania).",
        "v2.7: Zaimplementowano podpisywanie pakietów mDNS kluczem ML-DSA i weryfikację przy resolve, wdrożono ulepszenia UX i obsługi uprawnień w SettingsScreen dla WiFi Direct oraz dodano logikę auto-reconnectu i role tracking w WiFiDirectTransport.",
        "v2.6: Wdrożono podpisywanie pakietów discovery kluczem ML-DSA (Dilithium), zaimplementowano MdnsDiscovery (mDNS/NSD przez NsdManager) oraz podstawową obsługę WiFiDirectTransport z uprawnieniami.",
        "v2.5: Dodano automatyczne wykrywanie urządzeń w sieci lokalnej (LocalNetworkDiscovery przez UDP Broadcast) oraz ulepszono TransportManager o priorytetyzację i automatyczny fallback połączeń.",
        "v2.4: Wprowadzono architekturę wielu transportów (Multi-Transport) z obsługą bezpośredniej komunikacji P2P TCP (LocalNetworkTransport) oraz zmigrowano i zintegrowano WebSocketTransport.",
        "v2.3: Zintegrowano autentykację wiadomości przy użyciu podpisów post-kwantowych ML-DSA (Dilithium), ulepszono wymianę kluczy ML-KEM o asymetryczny uścisk dłoni (handshake) oraz dodano obsługę sieciową OkHttp WebSocket.",
        "v2.2: Wprowadzono hybrydowe uzgadnianie kluczy (X25519 + ML-KEM-768 z Bouncy Castle) oraz bezpieczną migrację bazy danych bez utraty danych (wersja 3 → 4).",
        "v2.1: Zaimplementowano pełen protokół Double Ratchet z rotacją kluczy DH (Asymmetric Ratchet) oraz przechowywaniem pominiętych kluczy wiadomości (Skipped Keys).",
        "v2.0: Wprowadzono bezpieczne, szyfrowane przechowywanie stanu ratchetu w bazie danych SQLCipher.",
        "v1.0: Zmigrowano aplikację na architekturę modułową Clean Architecture z MVI, Compose Navigation i Dagger Hilt."
    )
}
