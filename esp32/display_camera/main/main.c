/*
 * Acquisition Node (ESP32 #1)  *
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
#include "nvs_flash.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "bsp/esp-bsp.h"
#include "esp_camera.h"

static const char *TAG = "BPA_ACQUISITION_NODE";

/* Stato di connessione WiFi */

static EventGroupHandle_t s_wifi_event_group;
#define WIFI_CONNECTED_BIT BIT0

/* Schema del pacchetto ESP-NOW 
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

/* Lista delle immagini del dataset condiviso 
 * Deve corrispondere ai file realmente presenti in
 * data/dataset/bpa_v1/ sul backend
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

/* Pulsanti (letti via ADC, condividono un solo pin)
 * Uso in questo progetto: PLAY = scatta e manda subito (sempre
 * disponibile); MENU = accende/spegne la modalita' automatica a
 * intervalli. UP e DOWN non hanno ancora una funzione assegnata.
 */
#define BUTTONS_ADC_CHANNEL ADC_CHANNEL_0  // GPIO1 su ESP32-S3

typedef enum {
    BUTTON_NONE = 0,
    BUTTON_MENU,
    BUTTON_PLAY,
    BUTTON_DOWN,
    BUTTON_UP,
} button_t;

typedef struct {
    button_t button;
    int min_mv;
    int max_mv;
} button_voltage_range_t;

static const button_voltage_range_t BUTTON_RANGES[] = {
    { BUTTON_MENU, 2310, 2510 },
    { BUTTON_PLAY, 1880, 2080 },
    { BUTTON_DOWN, 720, 920 },
    { BUTTON_UP, 280, 480 },
};
#define BUTTON_RANGES_COUNT (sizeof(BUTTON_RANGES) / sizeof(BUTTON_RANGES[0]))

static adc_oneshot_unit_handle_t s_adc_handle;
static adc_cali_handle_t s_adc_cali_handle;
static bool s_adc_calibrated = false;

static void init_buttons(void) {
    adc_oneshot_unit_init_cfg_t init_config = {
        .unit_id = ADC_UNIT_1,
    };
    ESP_ERROR_CHECK(adc_oneshot_new_unit(&init_config, &s_adc_handle));

    adc_oneshot_chan_cfg_t channel_config = {
        .bitwidth = ADC_BITWIDTH_DEFAULT,
        .atten = ADC_ATTEN_DB_12,
    };
    ESP_ERROR_CHECK(adc_oneshot_config_channel(s_adc_handle, BUTTONS_ADC_CHANNEL, &channel_config));

    adc_cali_curve_fitting_config_t cali_config = {
        .unit_id = ADC_UNIT_1,
        .chan = BUTTONS_ADC_CHANNEL,
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    s_adc_calibrated = (adc_cali_create_scheme_curve_fitting(&cali_config, &s_adc_cali_handle) == ESP_OK);

    if (!s_adc_calibrated) {
        ESP_LOGW(TAG, "Calibrazione ADC non disponibile - la lettura pulsanti "
                      "potrebbe essere meno precisa (ma di solito funziona comunque).");
    }

    ESP_LOGI(TAG, "Pulsanti inizializzati (ADC su GPIO1).");
}

/* Legge il pulsante premuto in questo istante, o BUTTON_NONE se nessuno lo e'. */
static button_t read_pressed_button(void) {
    int raw_value;
    if (adc_oneshot_read(s_adc_handle, BUTTONS_ADC_CHANNEL, &raw_value) != ESP_OK) {
        return BUTTON_NONE;
    }

    int voltage_mv;
    if (s_adc_calibrated) {
        adc_cali_raw_to_voltage(s_adc_cali_handle, raw_value, &voltage_mv);
    } else {
        // Stima approssimativa senza calibrazione (12 bit, 0-3300mV) -
        // sufficiente per distinguere le fasce di tensione dei pulsanti.
        voltage_mv = (raw_value * 3300) / 4095;
    }

    for (size_t i = 0; i < BUTTON_RANGES_COUNT; i++) {
        if (voltage_mv >= BUTTON_RANGES[i].min_mv && voltage_mv <= BUTTON_RANGES[i].max_mv) {
            return BUTTON_RANGES[i].button;
        }
    }
    return BUTTON_NONE;
}

// Fotocamera + display (anteprima dal vivo + scatto su richiesta) 

static uint8_t *s_cam_buff;
static lv_obj_t *s_camera_canvas;

static void init_camera_and_display(void) {
    bsp_i2c_init();
    bsp_display_start();
    bsp_display_backlight_on();

    const camera_config_t camera_config = BSP_CAMERA_DEFAULT_CONFIG;
    esp_err_t err = esp_camera_init(&camera_config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Inizializzazione fotocamera fallita: %s", esp_err_to_name(err));
        return;
    }

    sensor_t *sensor = esp_camera_sensor_get();
    sensor->set_vflip(sensor, BSP_CAMERA_VFLIP);
    sensor->set_hmirror(sensor, BSP_CAMERA_HMIRROR);
    ESP_LOGI(TAG, "Fotocamera inizializzata.");

    uint32_t cam_buff_size = BSP_LCD_H_RES * BSP_LCD_V_RES * 2;
    s_cam_buff = heap_caps_malloc(cam_buff_size, MALLOC_CAP_SPIRAM);
    assert(s_cam_buff);

    bsp_display_lock(0);
    s_camera_canvas = lv_canvas_create(lv_scr_act());
    lv_canvas_set_buffer(s_camera_canvas, s_cam_buff, BSP_LCD_H_RES, BSP_LCD_V_RES, LV_COLOR_FORMAT_RGB565);
    lv_obj_center(s_camera_canvas);
    bsp_display_unlock();
}

/* Aggiorna continuamente l'anteprima sul display, il
 * piu' velocemente possibile, per un'immagine fluida. Gira in parallelo
 * a acquisition_loop_task (pulsanti/invio), senza interferire con essa. */
static void camera_preview_task(void *pvParameters) {
    uint32_t cam_buff_size = BSP_LCD_H_RES * BSP_LCD_V_RES * 2;

    for (;;) {
        camera_fb_t *pic = esp_camera_fb_get();
        if (pic) {
            bsp_display_lock(0);
            memcpy(s_cam_buff, pic->buf, cam_buff_size);
            esp_camera_fb_return(pic);
            if (BSP_LCD_BIGENDIAN) {
                lv_draw_sw_rgb565_swap(s_cam_buff, cam_buff_size);
            }
            lv_obj_invalidate(s_camera_canvas);
            bsp_display_unlock();
        } else {
            ESP_LOGW(TAG, "Anteprima: cattura fotogramma fallita.");
        }
        vTaskDelay(1);
    }
}

/*
 * Scatta una foto vera su richiesta (pulsante PLAY). Per ora si limita
 * a confermare la cattura via log - l'invio via ESP-NOW e' il prossimo
 * pezzo di lavoro (richiede la frammentazione, non ancora costruita).
 */
static void capture_photo_on_demand(void) {
    camera_fb_t *pic = esp_camera_fb_get();
    if (!pic) {
        ESP_LOGW(TAG, "Scatto richiesto, ma la cattura e' fallita.");
        return;
    }

    ESP_LOGI(TAG, "Foto scattata: %zu byte (formato %d, %dx%d) - invio via ESP-NOW "
                  "non ancora implementato (richiede la frammentazione, prossimo passo).",
             pic->len, pic->format, pic->width, pic->height);

    esp_camera_fb_return(pic);
}

/* Connessione WiFi (stesso schema del Gateway) */

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

    // Disattiva il risparmio energetico del radio WiFi - vedi la stessa
    // identica nota nel firmware del Gateway (main.c) per il perche':
    // senza questo, ESP-NOW perde pacchetti a intermittenza per via del
    // "modem sleep" periodico del radio.
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));

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

/* Analisi dell'indirizzo MAC del Gateway (da stringa a byte) */

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

/* Callback di conferma invio ESP-NOW */

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

/* Invio ciclico dei pacchetti 'reference' */

static void send_reference_packet(uint32_t seq_num, const char *image_ref) {
    esp_now_packet_t packet = {
        .node_id = (uint8_t)CONFIG_BPA_NODE_ID,
        .seq_num = seq_num,
        .mode = MODE_REFERENCE,
        .timestamp = 0, // non affidabile senza RTC/NTP
    };
    strncpy(packet.image_ref, image_ref, sizeof(packet.image_ref) - 1);
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

    // Se configurata, questa singola immagine viene mandata sempre,
    // ignorando la rotazione - vedi Kconfig.projbuild.
    bool single_image_mode = strlen(CONFIG_BPA_SINGLE_IMAGE_REF) > 0;

    bool auto_mode_enabled = CONFIG_BPA_AUTO_SEND_ENABLED_AT_BOOT;
    ESP_LOGI(TAG, "Modalita' automatica all'avvio: %s", auto_mode_enabled ? "ATTIVA" : "spenta");

    button_t previous_button = BUTTON_NONE; // per rilevare "appena premuto", non "tenuto premuto"
    int64_t last_auto_send_ms = 0;

    for (;;) {
        button_t current_button = read_pressed_button();
        bool just_pressed = (current_button != BUTTON_NONE && current_button != previous_button);
        previous_button = current_button;

        bool should_send_now = false;

        if (just_pressed && current_button == BUTTON_PLAY) {
            ESP_LOGI(TAG, "Pulsante PLAY premuto - scatto con la fotocamera vera.");
            capture_photo_on_demand();
            // Non imposta should_send_now: PLAY scatta una foto vera
            // (loggata per ora, non ancora mandata - vedi nota sopra
            // capture_photo_on_demand). Il ciclo automatico/MENU sotto
            // continua invece a mandare riferimenti dal dataset,
            // percorso di codice separato e gia' funzionante.

        } else if (just_pressed && current_button == BUTTON_MENU) {
            auto_mode_enabled = !auto_mode_enabled;
            ESP_LOGI(TAG, "Pulsante MENU premuto - modalita' automatica ora %s.",
                     auto_mode_enabled ? "ATTIVA" : "spenta");

        } else if (auto_mode_enabled) {
            int64_t now_ms = esp_timer_get_time() / 1000;
            if (now_ms - last_auto_send_ms >= CONFIG_BPA_SEND_INTERVAL_MS) {
                should_send_now = true;
                last_auto_send_ms = now_ms;
            }
        }

        if (should_send_now) {
            if (single_image_mode) {
                send_reference_packet(seq_num, CONFIG_BPA_SINGLE_IMAGE_REF);
            } else {
                send_reference_packet(seq_num, DATASET_IMAGES[image_index]);
                image_index = (image_index + 1) % DATASET_IMAGES_COUNT;
            }
            seq_num++;
        }

        // Controlla i pulsanti frequentemente (risposta rapida alla
        // pressione), molto piu' spesso dell'intervallo di invio vero
        // e proprio.
        vTaskDelay(pdMS_TO_TICKS(100));
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

    init_camera_and_display();
    init_wifi();
    init_esp_now();
    init_buttons();

    ESP_LOGI(TAG, "Acquisition Node pronto - invio un pacchetto ogni %d ms, "
                  "%d immagini in rotazione.",
             CONFIG_BPA_SEND_INTERVAL_MS, DATASET_IMAGES_COUNT);

    xTaskCreate(camera_preview_task, "camera_preview", 4096, NULL, 4, NULL);
    xTaskCreate(acquisition_loop_task, "acquisition_loop", 4096, NULL, 5, NULL);
}