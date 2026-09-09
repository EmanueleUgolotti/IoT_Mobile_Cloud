/*
 * Acquisition Node (ESP32 #1) - Task 1: modalita' 'reference'
 *
 * Scope di questo task: SOLO l'invio di pacchetti in modalita'
 * 'reference' (un riferimento a un'immagine gia' presente nel dataset
 * condiviso, non i byte veri) - coerente con la roadmap, che segna la
 * modalita' 'captured' (foto vera dalla fotocamera, con relativa
 * frammentazione ESP-NOW) come task separato, successivo, che
 * richiedera' l'hardware della fotocamera (non ancora disponibile).
 *
 * Il nodo si limita a scegliere, a rotazione, un nome file dalla stessa
 * lista che esiste davvero nel dataset condiviso (data/dataset/bpa_v1/
 * sul backend), e a mandarlo periodicamente al Gateway via ESP-NOW.
 *
 * IMPORTANTE: la struttura del pacchetto e l'algoritmo CRC qui sotto
 * devono restare IDENTICI, byte per byte, a quelli definiti nel
 * firmware del Gateway (esp32/gateway/main/main.c) - i due dispositivi
 * devono "parlare la stessa lingua". Se uno dei due cambia, va
 * aggiornato anche l'altro.
 *
 * Configurazione (WiFi, MAC del Gateway, intervallo di invio): tramite
 * "idf.py menuconfig" -> "BPA Acquisition Node Configuration", vedi
 * main/Kconfig.projbuild.
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
#include "nvs_flash.h"

static const char *TAG = "BPA_ACQUISITION_NODE";

/* ---------- Stato di connessione WiFi ---------- */

static EventGroupHandle_t s_wifi_event_group;
#define WIFI_CONNECTED_BIT BIT0

/* ---------- Schema del pacchetto ESP-NOW ----------
 * IDENTICO a quello del Gateway - vedi nota in cima al file.
 */

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

/* ---------- Lista delle immagini del dataset condiviso ----------
 * Deve corrispondere ai file realmente presenti in
 * data/dataset/bpa_v1/ sul backend - se il dataset cambia, aggiornare
 * anche questa lista.
 */
static const char *DATASET_IMAGES[] = {
    "1-1paper25.jpg",
    "5-6paper50.jpg",
    "C3.jpg",
    "Hf73.jpg",
    "Hf77.jpg",
    "Hr23.jpg",
    "Hr34.jpg",
    "Hr70.jpg",
};
#define DATASET_IMAGES_COUNT (sizeof(DATASET_IMAGES) / sizeof(DATASET_IMAGES[0]))

/* ---------- Connessione WiFi (stesso schema del Gateway) ---------- */

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

    ESP_LOGI(TAG, "Connessione a rete WiFi '%s' in corso "
                  "(stessa rete del Gateway, necessaria per ESP-NOW)...", CONFIG_BPA_WIFI_SSID);

    EventBits_t bits = xEventGroupWaitBits(s_wifi_event_group, WIFI_CONNECTED_BIT,
                                            pdFALSE, pdTRUE, pdMS_TO_TICKS(90000));
    if (bits & WIFI_CONNECTED_BIT) {
        ESP_LOGI(TAG, "WiFi connesso con successo.");
    } else {
        ESP_LOGW(TAG, "WiFi non connesso dopo 90 secondi - ESP-NOW potrebbe non "
                      "funzionare se il canale radio non e' allineato con il Gateway.");
    }
}

/* ---------- Analisi dell'indirizzo MAC del Gateway (da stringa a byte) ---------- */

static bool parse_mac_address(const char *mac_str, uint8_t *mac_bytes) {
    int values[6];
    int parsed = sscanf(mac_str, "%x:%x:%x:%x:%x:%x",
                         &values[0], &values[1], &values[2],
                         &values[3], &values[4], &values[5]);
    if (parsed != 6) {
        return false;
    }
    for (int i = 0; i < 6; i++) {
        mac_bytes[i] = (uint8_t)values[i];
    }
    return true;
}

/* ---------- Callback di conferma invio ESP-NOW ---------- */

static void on_esp_now_data_sent(const wifi_tx_info_t *tx_info, esp_now_send_status_t status) {
    if (status == ESP_NOW_SEND_SUCCESS) {
        ESP_LOGI(TAG, "Pacchetto consegnato al Gateway (ack radio ricevuto).");
    } else {
        ESP_LOGW(TAG, "Consegna al Gateway fallita (nessun ack radio - "
                      "il Gateway potrebbe essere spento o troppo lontano).");
    }
}

static uint8_t s_gateway_mac[6];

static void init_esp_now(void) {
    if (!parse_mac_address(CONFIG_BPA_GATEWAY_MAC_ADDR, s_gateway_mac)) {
        ESP_LOGE(TAG, "Indirizzo MAC del Gateway non valido: '%s' - controlla "
                      "'idf.py menuconfig'. Atteso formato XX:XX:XX:XX:XX:XX.",
                 CONFIG_BPA_GATEWAY_MAC_ADDR);
        return;
    }

    ESP_ERROR_CHECK(esp_now_init());
    ESP_ERROR_CHECK(esp_now_register_send_cb(on_esp_now_data_sent));

    esp_now_peer_info_t peer = {0};
    memcpy(peer.peer_addr, s_gateway_mac, 6);
    peer.channel = 0; // 0 = usa il canale della connessione WiFi attuale
    peer.encrypt = false;

    esp_err_t err = esp_now_add_peer(&peer);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Impossibile registrare il Gateway come peer ESP-NOW: %s",
                 esp_err_to_name(err));
        return;
    }

    ESP_LOGI(TAG, "ESP-NOW inizializzato, Gateway registrato come peer (MAC %s).",
             CONFIG_BPA_GATEWAY_MAC_ADDR);
}

/* ---------- Invio ciclico dei pacchetti 'reference' ---------- */

static void send_next_reference_packet(uint32_t seq_num, size_t image_index) {
    esp_now_packet_t packet = {
        .node_id = (uint8_t)CONFIG_BPA_NODE_ID,
        .seq_num = seq_num,
        .mode = MODE_REFERENCE,
        .timestamp = 0, // non affidabile senza RTC/NTP - vedi stessa nota nel Gateway
    };
    strncpy(packet.image_ref, DATASET_IMAGES[image_index], sizeof(packet.image_ref) - 1);
    packet.crc = compute_packet_crc(&packet);

    ESP_LOGI(TAG, "Invio pacchetto #%lu - image_ref='%s'",
             (unsigned long)seq_num, packet.image_ref);

    esp_err_t err = esp_now_send(s_gateway_mac, (const uint8_t *)&packet, sizeof(packet));
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "esp_now_send ha restituito un errore: %s", esp_err_to_name(err));
    }
}

static void acquisition_loop_task(void *pvParameters) {
    uint32_t seq_num = 0;
    size_t image_index = 0;

    for (;;) {
        send_next_reference_packet(seq_num, image_index);

        seq_num++;
        image_index = (image_index + 1) % DATASET_IMAGES_COUNT;

        vTaskDelay(pdMS_TO_TICKS(CONFIG_BPA_SEND_INTERVAL_MS));
    }
}

void app_main(void) {
    ESP_LOGI(TAG, "Avvio firmware Acquisition Node (Task 1: modalita' reference)");

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    init_wifi();
    init_esp_now();

    ESP_LOGI(TAG, "Acquisition Node pronto - invio un pacchetto ogni %d ms, "
                  "%d immagini in rotazione.",
             CONFIG_BPA_SEND_INTERVAL_MS, DATASET_IMAGES_COUNT);

    xTaskCreate(acquisition_loop_task, "acquisition_loop", 4096, NULL, 5, NULL);
}