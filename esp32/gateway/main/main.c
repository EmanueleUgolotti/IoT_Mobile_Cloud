/*
 * Gateway (ESP32 #2)
 *
 * Task 1 (fatto): inizializzazione WiFi/ESP-NOW, ricezione e validazione
 * pacchetti dall'Acquisition Node.
 *
 * Task 2 (fatto): connessione WiFi vera (necessaria per l'HTTP),
 * decisione di offloading (Cloud in priorita', Mobile come riserva se
 * il Cloud non e' raggiungibile), costruzione del payload JSON coerente
 * con network-design.md, inoltro HTTP verso /cloud/ingest o
 * /mobile/ingest, coda locale di retry.
 *
 * Task 3 (questo file): ricezione e ricomposizione di foto vere,
 * mandate frammentate dall'Acquisition Node (un pacchetto ESP-NOW non
 * contiene una foto intera - vedi lo schema esp_now_frag_packet_t sotto,
 * IDENTICO byte per byte a quello nel firmware dell'Acquisition Node).
 * Una volta ricomposta e verificata (CRC), viene inoltrata come
 * modalita' 'captured' con lo stesso meccanismo di offloading gia'
 * usato per i pacchetti 'reference'.
 *
 * NOTA SULLO SCOPE DI QUESTO TASK: se un tentativo di scatto fallisce
 * (pezzi mancanti, CRC non valido, inoltro fallito), per ora viene solo
 * LOGGATO chiaramente su questo Gateway - notificare il backend (cosi'
 * l'app/Cloud possano mostrare "scatto da rifare") e' un piccolo passo
 * successivo separato, che richiede anche una modifica al backend
 * (vedi roadmap).
 *
 * Configurazione (WiFi, indirizzi backend, API key): tramite
 * "idf.py menuconfig" -> "BPA Gateway Configuration", vedi
 * main/Kconfig.projbuild. Nessuna credenziale scritta qui nel codice.
 */

#include <string.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/queue.h"
#include "esp_now.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_rom_crc.h"
#include "esp_timer.h"
#include "esp_http_client.h"
#include "nvs_flash.h"
#include "cJSON.h"
#include "mbedtls/base64.h"

static const char *TAG = "BPA_GATEWAY";

/* ---------- Stato di connessione WiFi ---------- */

static EventGroupHandle_t s_wifi_event_group;
#define WIFI_CONNECTED_BIT BIT0

/* ---------- Coda locale di retry (solo per pacchetti 'reference') ----------
 * Se l'inoltro fallisce sia verso Cloud sia verso Mobile, il pacchetto
 * viene messo in questa coda invece di essere perso subito. Un task in
 * background riprova periodicamente, rifacendo da capo la decisione di
 * offloading (non si fissa sulla stessa destinazione che era fallita -
 * se nel frattempo il Cloud torna raggiungibile, ci prova).
 *
 * NOTA: le foto catturate (modalita' 'captured') NON usano questa coda
 * - richiederebbe conservare l'intero buffer immagine (fino a decine di
 * KB) invece di un piccolo pacchetto a dimensione fissa. Se l'inoltro di
 * una foto fallisce, per ora viene semplicemente persa con un log
 * chiaro (vedi notify_capture_failed) - limite noto, accettato.
 *
 * LIMITE NOTO (documentato, non risolto qui): la coda vive solo in RAM,
 * viene persa se il Gateway si riavvia. Risolverlo richiederebbe
 * salvarla su flash (NVS), non necessario per lo scopo di questo
 * progetto - il rischio principale (perdita dati per un riavvio
 * durante un'interruzione di rete) e' un evento raro nella pratica.
 */
#define RETRY_QUEUE_DEPTH 10
#define RETRY_INTERVAL_MS 30000

static QueueHandle_t s_retry_queue;

/* ---------- Schema del pacchetto ESP-NOW 'reference' (invariato) ---------- */

typedef enum {
    MODE_REFERENCE = 0,
    MODE_CAPTURED = 1,
} acquisition_mode_t;

typedef struct __attribute__((packed)) {
    uint8_t node_id;
    uint32_t seq_num;
    uint8_t mode;
    uint32_t timestamp;
    char image_ref[64];
    uint16_t crc;
} esp_now_packet_t;

#define CRC_SEED 0xFFFF

static uint16_t compute_packet_crc(const esp_now_packet_t *packet) {
    size_t len_without_crc = sizeof(esp_now_packet_t) - sizeof(uint16_t);
    return esp_rom_crc16_le(CRC_SEED, (const uint8_t *)packet, len_without_crc);
}

/* ---------- Schema dei pacchetti per foto vere (modalita' 'captured') ----------
 * IDENTICO, byte per byte, a quello nel firmware dell'Acquisition Node
 * (esp32/acquisition-node/main/main.c) - i due dispositivi devono
 * "parlare la stessa lingua". Se uno dei due cambia, va aggiornato
 * anche l'altro.
 */

typedef enum {
    FRAG_PACKET_HEADER = 0,
    FRAG_PACKET_DATA = 1,
} frag_packet_type_t;

#define FRAGMENT_DATA_SIZE 200

typedef struct __attribute__((packed)) {
    uint8_t node_id;
    uint32_t seq_num;
    uint8_t packet_type;

    union {
        struct __attribute__((packed)) {
            uint32_t total_size;
            uint16_t total_fragments;
            uint16_t image_crc;
        } header;

        struct __attribute__((packed)) {
            uint16_t fragment_index;
            uint16_t fragment_len;
            uint8_t data[FRAGMENT_DATA_SIZE];
        } fragment;
    } payload;

    uint16_t crc;
} esp_now_frag_packet_t;

static uint16_t compute_frag_packet_crc(const esp_now_frag_packet_t *packet) {
    size_t len_without_crc = sizeof(esp_now_frag_packet_t) - sizeof(uint16_t);
    return esp_rom_crc16_le(CRC_SEED, (const uint8_t *)packet, len_without_crc);
}

/* ---------- Contatore per request_id (node_id + seq_num, come da schema) ---------- */

static char *build_request_id(uint8_t node_id, uint32_t seq_num) {
    static char buf[32];
    snprintf(buf, sizeof(buf), "gw01-node%d-%06lu", node_id, (unsigned long)seq_num);
    return buf;
}

/* ---------- Connessione WiFi ---------- */

static void wifi_event_handler(void *arg, esp_event_base_t event_base,
                                int32_t event_id, void *event_data) {
    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        ESP_LOGW(TAG, "WiFi disconnesso, ritento la connessione...");
        xEventGroupClearBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
        esp_wifi_connect();
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)event_data;
        ESP_LOGI(TAG, "WiFi connesso, IP ottenuto: " IPSTR, IP2STR(&event->ip_info.ip));
        xEventGroupSetBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
    }
}

static bool is_wifi_connected(void) {
    EventBits_t bits = xEventGroupGetBits(s_wifi_event_group);
    return (bits & WIFI_CONNECTED_BIT) != 0;
}

static void init_wifi(void) {
    s_wifi_event_group = xEventGroupCreate();

    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &wifi_event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &wifi_event_handler, NULL));

    wifi_config_t wifi_config = {
        .sta = {
            .ssid = CONFIG_BPA_WIFI_SSID,
            .password = CONFIG_BPA_WIFI_PASSWORD,
        },
    };

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    ESP_ERROR_CHECK(esp_wifi_start());

    // Disattiva il risparmio energetico del radio WiFi: di default,
    // ESP-IDF alterna periodicamente il radio in "modem sleep" per
    // risparmiare energia - questo pero' fa perdere pacchetti ESP-NOW
    // in arrivo proprio durante quelle brevi finestre di sonno (sintomo
    // osservato: consegne alternate, un pacchetto sì e uno no). Con
    // entrambe le board alimentate da USB (non a batteria), il consumo
    // extra non e' un problema per questo progetto.
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));

    ESP_LOGI(TAG, "Connessione a rete WiFi '%s' in corso...", CONFIG_BPA_WIFI_SSID);

    // Aspetta la connessione per un tempo limitato, cosi' il Gateway non
    // resta bloccato per sempre se la rete configurata non e' disponibile
    // (es. rete pubblica con portale di accesso, o password sbagliata).
    // Timeout ampio: alcune reti (es. hotspot di telefono) possono
    // impiegare anche piu' di un minuto per completare la connessione
    // WPA2 la prima volta - osservato empiricamente fino a ~75 secondi.
    EventBits_t bits = xEventGroupWaitBits(s_wifi_event_group, WIFI_CONNECTED_BIT,
                                            pdFALSE, pdTRUE, pdMS_TO_TICKS(90000));
    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "WiFi connesso con successo.");
    } else {
        ESP_LOGW(TAG, "WiFi non connesso dopo 90 secondi - l'inoltro verso "
                      "Mobile/Cloud fallira' finche' la connessione non riesce. "
                      "Verifica SSID/password con 'idf.py menuconfig', e che la "
                      "rete non richieda un login via browser (portale captive).");
    }
}

/* ---------- Controllo di raggiungibilita' del Cloud ---------- */

static bool is_cloud_reachable(void) {
    if (!is_wifi_connected()) {
        return false;
    }

    char url[128];
    snprintf(url, sizeof(url), "http://%s:%d/health", CONFIG_BPA_CLOUD_HOST, CONFIG_BPA_CLOUD_PORT);

    esp_http_client_config_t config = {
        .url = url,
        .method = HTTP_METHOD_GET,
        .timeout_ms = 2000,  // controllo rapido, non vogliamo bloccare a lungo
    };

    esp_http_client_handle_t client = esp_http_client_init(&config);
    esp_err_t err = esp_http_client_perform(client);

    bool reachable = false;
    if (err == ESP_OK) {
        int status = esp_http_client_get_status_code(client);
        reachable = (status == 200);
    }

    esp_http_client_cleanup(client);
    return reachable;
}

/* ---------- Costruzione del payload JSON (schema da network-design.md) ---------- */

static char *build_json_payload(const esp_now_packet_t *packet, const char *request_id) {
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "request_id", request_id);
    cJSON_AddNumberToObject(root, "node_id", packet->node_id);
    cJSON_AddNumberToObject(root, "seq_num", packet->seq_num);

    // Timestamp del gateway (non quello, non affidabile, del nodo mittente).
    // Senza un modulo RTC/NTP configurato, usiamo un valore placeholder
    // leggibile - non e' l'orario assoluto reale, ma la logica applicativa
    // (ordine di arrivo, request_id univoco) non dipende da questo valore.
    cJSON_AddStringToObject(root, "gateway_timestamp", "1970-01-01T00:00:00Z");

    cJSON_AddStringToObject(root, "mode", "reference");
    cJSON_AddStringToObject(root, "image_ref", packet->image_ref);
    cJSON_AddStringToObject(root, "dataset_id", "bpa_v1");

    cJSON_AddNumberToObject(root, "retry_attempt", 0);

    char *json_string = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return json_string;  // il chiamante deve fare free() su questo puntatore
}

/*
 * Costruisce il payload JSON per una foto vera, gia' ricomposta e
 * verificata - stesso schema 'captured' che il backend gia' gestisce,
 * con l'immagine codificata in base64 (mbedtls, gia' incluso per HTTPS,
 * riusato qui - nessuna nuova dipendenza).
 */
static char *build_captured_json_payload(uint8_t node_id, uint32_t seq_num,
                                          const uint8_t *jpeg_data, size_t jpeg_len,
                                          const char *request_id) {
    size_t b64_capacity = 4 * ((jpeg_len + 2) / 3) + 4;
    char *b64_buf = malloc(b64_capacity);
    if (!b64_buf) {
        return NULL;
    }

    size_t actual_len = 0;
    int err = mbedtls_base64_encode((unsigned char *)b64_buf, b64_capacity, &actual_len,
                                     jpeg_data, jpeg_len);
    if (err != 0) {
        free(b64_buf);
        return NULL;
    }
    b64_buf[actual_len] = '\0';

    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "request_id", request_id);
    cJSON_AddNumberToObject(root, "node_id", node_id);
    cJSON_AddNumberToObject(root, "seq_num", seq_num);
    cJSON_AddStringToObject(root, "gateway_timestamp", "1970-01-01T00:00:00Z");
    cJSON_AddStringToObject(root, "mode", "captured");

    char capture_id[32];
    snprintf(capture_id, sizeof(capture_id), "node%d-%06lu", node_id, (unsigned long)seq_num);
    cJSON_AddStringToObject(root, "capture_id", capture_id);
    cJSON_AddStringToObject(root, "image_data", b64_buf);
    cJSON_AddNumberToObject(root, "retry_attempt", 0);

    free(b64_buf);

    char *json_string = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return json_string;
}

/* ---------- Inoltro HTTP verso Cloud o Mobile ---------- */

static bool forward_via_http(const char *host, int port, const char *endpoint_path,
                              const char *json_payload) {
    char url[128];
    snprintf(url, sizeof(url), "http://%s:%d%s", host, port, endpoint_path);

    esp_http_client_config_t config = {
        .url = url,
        .method = HTTP_METHOD_POST,
        .timeout_ms = 10000,
    };

    esp_http_client_handle_t client = esp_http_client_init(&config);
    esp_http_client_set_header(client, "Content-Type", "application/json");

    if (strlen(CONFIG_BPA_API_KEY) > 0) {
        esp_http_client_set_header(client, "X-API-Key", CONFIG_BPA_API_KEY);
    }

    esp_http_client_set_post_field(client, json_payload, strlen(json_payload));

    esp_err_t err = esp_http_client_perform(client);
    bool success = false;

    if (err == ESP_OK) {
        int status = esp_http_client_get_status_code(client);
        success = (status >= 200 && status < 300);
        ESP_LOGI(TAG, "Inoltro a %s -> HTTP %d (%s)", url, status, success ? "OK" : "fallito");
    } else {
        ESP_LOGW(TAG, "Inoltro a %s fallito: %s", url, esp_err_to_name(err));
    }

    esp_http_client_cleanup(client);
    return success;
}

/*
 * Decide dove mandare il pacchetto 'reference' (offloading) e lo
 * inoltra. Regola di offloading: priorita' al Cloud (piu' potenza di
 * calcolo disponibile); se il Cloud non risponde a un controllo
 * rapido, il Mobile diventa il piano di riserva. Stessa regola usata
 * anche per le foto catturate (vedi forward_captured_photo sotto).
 */
static void handle_offloading_and_forward(const esp_now_packet_t *packet) {
    char *request_id = build_request_id(packet->node_id, packet->seq_num);
    char *json_payload = build_json_payload(packet, request_id);

    if (json_payload == NULL) {
        ESP_LOGE(TAG, "Errore nella costruzione del payload JSON, pacchetto scartato");
        return;
    }

    bool sent = false;

    if (is_cloud_reachable()) {
        ESP_LOGI(TAG, "Cloud raggiungibile - offloading verso Cloud");
        sent = forward_via_http(CONFIG_BPA_CLOUD_HOST, CONFIG_BPA_CLOUD_PORT,
                                 "/cloud/ingest", json_payload);
    } else {
        ESP_LOGI(TAG, "Cloud non raggiungibile - offloading verso Mobile (riserva)");
        sent = forward_via_http(CONFIG_BPA_MOBILE_HOST, CONFIG_BPA_MOBILE_PORT,
                                 "/mobile/ingest", json_payload);
    }

    if (!sent) {
        if (s_retry_queue != NULL &&
                xQueueSend(s_retry_queue, packet, 0) == pdTRUE) {
            ESP_LOGW(TAG, "Inoltro fallito per entrambe le destinazioni - pacchetto "
                          "'%s' messo in coda per un retry tra %d secondi.",
                     request_id, RETRY_INTERVAL_MS / 1000);
        } else {
            ESP_LOGE(TAG, "Inoltro fallito E coda di retry piena (max %d elementi in "
                          "attesa) - pacchetto '%s' perso definitivamente.",
                     RETRY_QUEUE_DEPTH, request_id);
        }
    }

    free(json_payload);
}

/*
 * Registra un tentativo di scatto fallito (pezzi mancanti, CRC non
 * valido, o inoltro fallito). Per ora solo un log chiaro sul Gateway -
 * notificare il backend (cosi' l'app/Cloud possono mostrare "scatto da
 * rifare") e' un piccolo passo successivo separato, non ancora fatto
 * in questo task (richiede anche una modifica al backend).
 */
static void notify_capture_failed(uint8_t node_id, uint32_t seq_num, const char *reason) {
    ESP_LOGW(TAG, "*** SCATTO FALLITO *** nodo=%d seq=%lu - motivo: %s. "
                  "(la notifica al backend non e' ancora implementata - prossimo task)",
             node_id, (unsigned long)seq_num, reason);
}

/*
 * Inoltra una foto vera, gia' ricomposta e verificata, usando la stessa
 * logica di offloading dei pacchetti 'reference' (Cloud prima, Mobile
 * come riserva). A differenza dei pacchetti 'reference', in caso di
 * fallimento NON viene accodata per un retry (richiederebbe conservare
 * l'intero buffer immagine) - viene solo segnalata come fallita.
 */
static void forward_captured_photo(uint8_t node_id, uint32_t seq_num,
                                    const uint8_t *jpeg_data, size_t jpeg_len) {
    char request_id[32];
    snprintf(request_id, sizeof(request_id), "gw01-node%d-c%06lu", node_id, (unsigned long)seq_num);

    char *json_payload = build_captured_json_payload(node_id, seq_num, jpeg_data, jpeg_len, request_id);
    if (json_payload == NULL) {
        ESP_LOGE(TAG, "Errore nella costruzione del payload JSON per la foto catturata.");
        notify_capture_failed(node_id, seq_num, "errore nella costruzione del payload JSON");
        return;
    }

    bool sent = false;

    if (is_cloud_reachable()) {
        ESP_LOGI(TAG, "Cloud raggiungibile - inoltro foto catturata verso Cloud");
        sent = forward_via_http(CONFIG_BPA_CLOUD_HOST, CONFIG_BPA_CLOUD_PORT,
                                 "/cloud/ingest", json_payload);
    } else {
        ESP_LOGI(TAG, "Cloud non raggiungibile - inoltro foto catturata verso Mobile (riserva)");
        sent = forward_via_http(CONFIG_BPA_MOBILE_HOST, CONFIG_BPA_MOBILE_PORT,
                                 "/mobile/ingest", json_payload);
    }

    if (!sent) {
        notify_capture_failed(node_id, seq_num, "inoltro fallito su entrambe le destinazioni");
    }

    free(json_payload);
}

/* ---------- Ricezione e ricomposizione di foto frammentate ----------
 * Stato di UNA SOLA ricomposizione alla volta (coerente con l'
 * Acquisition Node, che ha un raffreddamento tra uno scatto e l'altro
 * e non ne manda mai due in parallelo).
 */

static uint8_t *s_reassembly_buffer = NULL;
static bool *s_reassembly_fragment_received = NULL;
static uint32_t s_reassembly_total_size = 0;
static uint16_t s_reassembly_total_fragments = 0;
static uint16_t s_reassembly_fragments_received_count = 0;
static uint16_t s_reassembly_image_crc = 0;
static uint8_t s_reassembly_node_id = 0;
static uint32_t s_reassembly_seq_num = 0;
static int64_t s_reassembly_start_time_ms = 0;

// Se una ricomposizione non si completa entro questo tempo, e'
// considerata fallita (pezzi persi per strada) e scartata.
#define REASSEMBLY_TIMEOUT_MS 15000

static bool reassembly_in_progress(void) {
    return s_reassembly_buffer != NULL;
}

static void free_reassembly_state(void) {
    if (s_reassembly_buffer != NULL) {
        free(s_reassembly_buffer);
        s_reassembly_buffer = NULL;
    }
    if (s_reassembly_fragment_received != NULL) {
        free(s_reassembly_fragment_received);
        s_reassembly_fragment_received = NULL;
    }
    s_reassembly_total_size = 0;
    s_reassembly_total_fragments = 0;
    s_reassembly_fragments_received_count = 0;
}

static void handle_frag_header(const esp_now_frag_packet_t *packet) {
    if (reassembly_in_progress()) {
        ESP_LOGW(TAG, "Nuova foto in arrivo (nodo=%d seq=%lu) mentre la precedente "
                      "(nodo=%d seq=%lu) non era completa (%u/%u frammenti).",
                 packet->node_id, (unsigned long)packet->seq_num,
                 s_reassembly_node_id, (unsigned long)s_reassembly_seq_num,
                 s_reassembly_fragments_received_count, s_reassembly_total_fragments);
        notify_capture_failed(s_reassembly_node_id, s_reassembly_seq_num,
                               "sovrascritta da un nuovo scatto prima di completarsi");
        free_reassembly_state();
    }

    uint32_t total_size = packet->payload.header.total_size;
    uint16_t total_fragments = packet->payload.header.total_fragments;

    if (total_size == 0 || total_fragments == 0) {
        ESP_LOGW(TAG, "Intestazione non valida (dimensione o numero frammenti a zero) - ignorata.");
        return;
    }

    s_reassembly_buffer = malloc(total_size);
    if (s_reassembly_buffer == NULL) {
        ESP_LOGE(TAG, "Impossibile allocare %lu byte per la ricomposizione della foto.",
                 (unsigned long)total_size);
        notify_capture_failed(packet->node_id, packet->seq_num, "memoria insufficiente sul Gateway");
        return;
    }

    s_reassembly_fragment_received = calloc(total_fragments, sizeof(bool));
    if (s_reassembly_fragment_received == NULL) {
        ESP_LOGE(TAG, "Impossibile allocare la mappa dei frammenti.");
        free(s_reassembly_buffer);
        s_reassembly_buffer = NULL;
        notify_capture_failed(packet->node_id, packet->seq_num, "memoria insufficiente sul Gateway");
        return;
    }

    s_reassembly_total_size = total_size;
    s_reassembly_total_fragments = total_fragments;
    s_reassembly_image_crc = packet->payload.header.image_crc;
    s_reassembly_node_id = packet->node_id;
    s_reassembly_seq_num = packet->seq_num;
    s_reassembly_fragments_received_count = 0;
    s_reassembly_start_time_ms = esp_timer_get_time() / 1000;

    ESP_LOGI(TAG, "Ricezione foto iniziata (nodo=%d seq=%lu): %lu byte attesi, %u frammenti.",
             packet->node_id, (unsigned long)packet->seq_num,
             (unsigned long)total_size, total_fragments);
}

static void handle_frag_data(const esp_now_frag_packet_t *packet) {
    if (!reassembly_in_progress()) {
        ESP_LOGW(TAG, "Frammento ricevuto (nodo=%d seq=%lu) senza un'intestazione "
                      "precedente - scartato.",
                 packet->node_id, (unsigned long)packet->seq_num);
        return;
    }

    if (packet->node_id != s_reassembly_node_id || packet->seq_num != s_reassembly_seq_num) {
        ESP_LOGW(TAG, "Frammento di uno scatto diverso da quello in corso - scartato.");
        return;
    }

    uint16_t index = packet->payload.fragment.fragment_index;
    uint16_t len = packet->payload.fragment.fragment_len;

    if (index >= s_reassembly_total_fragments) {
        ESP_LOGW(TAG, "Indice frammento fuori range (%u, attesi max %u) - scartato.",
                 index, s_reassembly_total_fragments);
        return;
    }

    size_t offset = (size_t)index * FRAGMENT_DATA_SIZE;
    if (offset + len > s_reassembly_total_size) {
        ESP_LOGW(TAG, "Frammento %u fuori dai limiti del buffer della foto - scartato.", index);
        return;
    }

    memcpy(s_reassembly_buffer + offset, packet->payload.fragment.data, len);

    if (!s_reassembly_fragment_received[index]) {
        s_reassembly_fragment_received[index] = true;
        s_reassembly_fragments_received_count++;
    }

    if (s_reassembly_fragments_received_count < s_reassembly_total_fragments) {
        return; // ancora incompleta, aspetta altri frammenti
    }

    // Tutti i frammenti attesi sono arrivati - verifica l'integrita'
    // prima di inoltrare, confrontando il CRC calcolato ora con quello
    // dichiarato nell'intestazione (protegge da corruzione durante la
    // trasmissione radio, non solo da pezzi mancanti).
    uint16_t computed_crc = esp_rom_crc16_le(CRC_SEED, s_reassembly_buffer, s_reassembly_total_size);

    if (computed_crc == s_reassembly_image_crc) {
        ESP_LOGI(TAG, "Foto ricomposta con successo (nodo=%d seq=%lu): %lu byte, CRC verificato.",
                 s_reassembly_node_id, (unsigned long)s_reassembly_seq_num,
                 (unsigned long)s_reassembly_total_size);
        forward_captured_photo(s_reassembly_node_id, s_reassembly_seq_num,
                                s_reassembly_buffer, s_reassembly_total_size);
    } else {
        ESP_LOGW(TAG, "Foto ricomposta ma CRC non corrisponde (atteso 0x%04X, calcolato 0x%04X).",
                 s_reassembly_image_crc, computed_crc);
        notify_capture_failed(s_reassembly_node_id, s_reassembly_seq_num,
                               "CRC non valido dopo la ricomposizione (corruzione in trasmissione)");
    }

    free_reassembly_state();
}

/*
 * Task in background che controlla se una ricomposizione in corso ha
 * superato il tempo massimo senza completarsi (pezzi persi per strada,
 * senza che ne sia mai arrivato uno nuovo per accorgersene subito).
 */
static void reassembly_timeout_task(void *pvParameters) {
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(2000));

        if (!reassembly_in_progress()) {
            continue;
        }

        int64_t elapsed_ms = (esp_timer_get_time() / 1000) - s_reassembly_start_time_ms;
        if (elapsed_ms > REASSEMBLY_TIMEOUT_MS) {
            ESP_LOGW(TAG, "Ricomposizione scaduta (nodo=%d seq=%lu): %u/%u frammenti "
                          "ricevuti dopo %lld secondi.",
                     s_reassembly_node_id, (unsigned long)s_reassembly_seq_num,
                     s_reassembly_fragments_received_count, s_reassembly_total_fragments,
                     elapsed_ms / 1000);
            notify_capture_failed(s_reassembly_node_id, s_reassembly_seq_num,
                                   "timeout - pezzi mancanti, ricezione mai completata");
            free_reassembly_state();
        }
    }
}

/* ---------- Ricezione ESP-NOW ---------- */

static void log_own_mac_address(void) {
    uint8_t mac[6];
    esp_wifi_get_mac(WIFI_IF_STA, mac);
    ESP_LOGI(TAG, "Indirizzo MAC di questo Gateway: %02X:%02X:%02X:%02X:%02X:%02X",
             mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    ESP_LOGI(TAG, "Serve per configurare questo dispositivo come 'peer' nel firmware dell'Acquisition Node.");
}

static void on_esp_now_data_received(const esp_now_recv_info_t *recv_info,
                                      const uint8_t *data, int data_len) {
    const uint8_t *sender_mac = recv_info->src_addr;

    // Pacchetto 'reference' (nome file dal dataset)
    if (data_len == sizeof(esp_now_packet_t)) {
        esp_now_packet_t packet;
        memcpy(&packet, data, sizeof(esp_now_packet_t));

        uint16_t expected_crc = compute_packet_crc(&packet);
        if (packet.crc != expected_crc) {
            ESP_LOGW(TAG, "CRC non valido da nodo %d, seq %lu (atteso 0x%04X, ricevuto 0x%04X) - "
                          "pacchetto scartato (possibile corruzione in trasmissione)",
                     packet.node_id, (unsigned long)packet.seq_num, expected_crc, packet.crc);
            return;
        }

        packet.image_ref[sizeof(packet.image_ref) - 1] = '\0';

        ESP_LOGI(TAG, "Pacchetto valido ricevuto - nodo=%d seq=%lu mode=reference image_ref='%s'",
                 packet.node_id, (unsigned long)packet.seq_num, packet.image_ref);

        handle_offloading_and_forward(&packet);
        return;
    }

    // Pacchetto di frammentazione (intestazione o pezzo di una foto vera)
    if (data_len == sizeof(esp_now_frag_packet_t)) {
        esp_now_frag_packet_t frag_packet;
        memcpy(&frag_packet, data, sizeof(esp_now_frag_packet_t));

        uint16_t expected_crc = compute_frag_packet_crc(&frag_packet);
        if (frag_packet.crc != expected_crc) {
            ESP_LOGW(TAG, "CRC non valido su un pacchetto di frammentazione da nodo %d - scartato "
                          "(possibile corruzione in trasmissione).",
                     frag_packet.node_id);
            return;
        }

        if (frag_packet.packet_type == FRAG_PACKET_HEADER) {
            handle_frag_header(&frag_packet);
        } else if (frag_packet.packet_type == FRAG_PACKET_DATA) {
            handle_frag_data(&frag_packet);
        } else {
            ESP_LOGW(TAG, "Tipo di pacchetto di frammentazione sconosciuto (%d) - scartato.",
                     frag_packet.packet_type);
        }
        return;
    }

    // Ne' l'uno ne' l'altro schema conosciuto
    ESP_LOGW(TAG, "Pacchetto di dimensione inattesa da %02X:%02X:%02X:%02X:%02X:%02X "
                  "(%d byte) - scartato",
             sender_mac[0], sender_mac[1], sender_mac[2],
             sender_mac[3], sender_mac[4], sender_mac[5], data_len);
}

/*
 * Task in background che svuota periodicamente la coda di retry (solo
 * pacchetti 'reference' - vedi nota sulla coda in cima al file),
 * riprovando l'inoltro di ogni pacchetto rimasto in sospeso.
 */
static void retry_task(void *pvParameters) {
    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(RETRY_INTERVAL_MS));

        UBaseType_t pending = uxQueueMessagesWaiting(s_retry_queue);
        if (pending == 0) {
            continue;
        }

        ESP_LOGI(TAG, "Retry: %u pacchetti in coda, nuovo tentativo di inoltro...",
                 (unsigned int)pending);

        for (UBaseType_t i = 0; i < pending; i++) {
            esp_now_packet_t packet;
            if (xQueueReceive(s_retry_queue, &packet, 0) != pdTRUE) {
                break;
            }
            handle_offloading_and_forward(&packet);
        }
    }
}

static void init_esp_now(void) {
    ESP_ERROR_CHECK(esp_now_init());
    ESP_ERROR_CHECK(esp_now_register_recv_cb(on_esp_now_data_received));
    ESP_LOGI(TAG, "ESP-NOW inizializzato, in ascolto di pacchetti in arrivo...");
}

#if CONFIG_BPA_SELFTEST_SEND_DUMMY_PACKET
/*
 * Genera un pacchetto finto e lo fa passare per lo stesso identico
 * percorso di codice usato per un pacchetto ESP-NOW reale (offloading
 * + inoltro HTTP) - utile per testare quella logica senza dover
 * aspettare che l'Acquisition Node sia disponibile.
 */
static void send_selftest_dummy_packet(void) {
    ESP_LOGI(TAG, "=== SELFTEST: invio pacchetto di prova finto (modalita' reference, "
                  "immagine 'Hf73.jpg' - richiede che il backend Cloud sia raggiungibile "
                  "per scaricarla davvero e classificarla) ===");

    esp_now_packet_t dummy_packet = {
        .node_id = 1,
        .seq_num = (uint32_t)(esp_timer_get_time() / 1000),
        .mode = MODE_REFERENCE,
        .timestamp = 0,
    };
    strncpy(dummy_packet.image_ref, "Hf73.jpg", sizeof(dummy_packet.image_ref) - 1);
    dummy_packet.crc = compute_packet_crc(&dummy_packet);

    handle_offloading_and_forward(&dummy_packet);

    ESP_LOGI(TAG, "=== SELFTEST: completato ===");
}
#endif

void app_main(void) {
    ESP_LOGI(TAG, "Avvio firmware Gateway (Task 3: ricezione e ricomposizione foto)");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    init_wifi();
    log_own_mac_address();
    init_esp_now();

    s_retry_queue = xQueueCreate(RETRY_QUEUE_DEPTH, sizeof(esp_now_packet_t));
    if (s_retry_queue == NULL) {
        ESP_LOGE(TAG, "Impossibile creare la coda di retry - i pacchetti falliti "
                      "verranno persi invece di essere riprovati.");
    } else {
        xTaskCreate(retry_task, "retry_task", 4096, NULL, 5, NULL);
        ESP_LOGI(TAG, "Coda di retry attiva (capacita' %d, intervallo %d secondi).",
                 RETRY_QUEUE_DEPTH, RETRY_INTERVAL_MS / 1000);
    }

    xTaskCreate(reassembly_timeout_task, "reassembly_timeout", 4096, NULL, 5, NULL);
    ESP_LOGI(TAG, "Controllo timeout ricomposizione foto attivo (%d secondi).",
             REASSEMBLY_TIMEOUT_MS / 1000);

    ESP_LOGI(TAG, "Gateway pronto. In attesa di pacchetti dall'Acquisition Node.");

#if CONFIG_BPA_SELFTEST_SEND_DUMMY_PACKET
    if (is_wifi_connected()) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        send_selftest_dummy_packet();
    } else {
        ESP_LOGW(TAG, "SELFTEST saltato: WiFi non ancora connesso al momento "
                      "dell'avvio. Il selftest verra' fatto girare comunque "
                      "quando la connessione si stabilizza, se riavvii il "
                      "dispositivo (Ctrl+T poi Ctrl+R nel monitor) dopo aver "
                      "visto 'WiFi connesso' nel log.");
    }
#endif
}