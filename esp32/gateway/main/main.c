/*
 * Gateway (ESP32 #2)
 *
 * Task 1 (fatto): inizializzazione WiFi/ESP-NOW, ricezione e validazione
 * pacchetti dall'Acquisition Node.
 *
 * Task 2 (questo file): connessione WiFi vera (necessaria per l'HTTP),
 * decisione di offloading (Cloud in priorita', Mobile come riserva se
 * il Cloud non e' raggiungibile), costruzione del payload JSON coerente
 * con network-design.md, inoltro HTTP verso /cloud/ingest o
 * /mobile/ingest.
 *
 * NON ancora incluso in questo task: coda locale di retry se l'inoltro
 * fallisce (WiFi assente, backend giu') - se l'inoltro fallisce, per
 * ora il pacchetto viene semplicemente perso con un log di avviso. La
 * coda di retry e' il prossimo task (vedi docs/roadmap.md).
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
#include "esp_now.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_rom_crc.h"
#include "esp_timer.h"
#include "esp_http_client.h"
#include "nvs_flash.h"
#include "cJSON.h"

static const char *TAG = "BPA_GATEWAY";

/* ---------- Stato di connessione WiFi ---------- */

static EventGroupHandle_t s_wifi_event_group;
#define WIFI_CONNECTED_BIT BIT0

/* ---------- Schema del pacchetto ESP-NOW (invariato dal Task 1) ---------- */

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

    if (packet->mode == MODE_REFERENCE) {
        cJSON_AddStringToObject(root, "mode", "reference");
        cJSON_AddStringToObject(root, "image_ref", packet->image_ref);
        cJSON_AddStringToObject(root, "dataset_id", "bpa_v1");
    } else {
        // Modalita' captured non ancora gestita del tutto (richiede la
        // frammentazione ESP-NOW, non ancora progettata - vedi roadmap).
        // "image_data" vuoto e' solo un placeholder per far rispondere
        // l'app velocemente durante i test di connettivita' (evita che
        // l'app provi a decodificare/classificare, restando cosi' subito
        // libera di rispondere) - NON e' l'implementazione reale.
        cJSON_AddStringToObject(root, "mode", "captured");
        cJSON_AddStringToObject(root, "capture_id", "selftest");
        cJSON_AddStringToObject(root, "image_data", "");
    }

    cJSON_AddNumberToObject(root, "retry_attempt", 0);

    char *json_string = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return json_string;  // il chiamante deve fare free() su questo puntatore
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
 * Decide dove mandare il pacchetto (offloading) e lo inoltra.
 *
 * Regola di offloading: priorita' al Cloud (piu' potenza di calcolo
 * disponibile); se il Cloud non risponde a un controllo rapido, il
 * Mobile diventa il piano di riserva.
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
        // TODO (task successivo): accodare qui il pacchetto per un retry
        // successivo, invece di perderlo. Per ora solo un log di avviso.
        ESP_LOGW(TAG, "Inoltro fallito per entrambe le destinazioni (o Mobile non "
                      "raggiungibile) - pacchetto '%s' perso. La coda di retry "
                      "e' un task successivo non ancora implementato.", request_id);
    }

    free(json_payload);
}

/* ---------- Ricezione ESP-NOW (Task 1, invariato) ---------- */

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

    if (data_len != sizeof(esp_now_packet_t)) {
        ESP_LOGW(TAG, "Pacchetto di dimensione inattesa da %02X:%02X:%02X:%02X:%02X:%02X "
                      "(%d byte, attesi %d) - scartato",
                 sender_mac[0], sender_mac[1], sender_mac[2],
                 sender_mac[3], sender_mac[4], sender_mac[5],
                 data_len, (int)sizeof(esp_now_packet_t));
        return;
    }

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

    ESP_LOGI(TAG, "Pacchetto valido ricevuto - nodo=%d seq=%lu mode=%s image_ref='%s'",
             packet.node_id,
             (unsigned long)packet.seq_num,
             packet.mode == MODE_REFERENCE ? "reference" : "captured",
             packet.mode == MODE_REFERENCE ? packet.image_ref : "(captured, non gestito in questo task)");

    handle_offloading_and_forward(&packet);
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
        // Il request_id (node_id + seq_num) deve essere univoco nel
        // database - usiamo il tempo trascorso da boot per farlo
        // cambiare ad ogni test, invece di un valore fisso che
        // causerebbe un errore "duplicate key" dal secondo tentativo
        // in poi.
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
    ESP_LOGI(TAG, "Avvio firmware Gateway (Task 2: offloading + inoltro HTTP)");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    init_wifi();
    log_own_mac_address();
    init_esp_now();

    ESP_LOGI(TAG, "Gateway pronto. In attesa di pacchetti dall'Acquisition Node.");

#if CONFIG_BPA_SELFTEST_SEND_DUMMY_PACKET
    if (is_wifi_connected()) {
        vTaskDelay(pdMS_TO_TICKS(1000)); // piccolo margine, non indispensabile
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