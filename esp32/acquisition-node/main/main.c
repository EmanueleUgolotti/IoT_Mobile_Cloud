/*
 * Acquisition Node (ESP32 #1) 
 * 
 * IMPORTANTE: la struttura del pacchetto e l'algoritmo CRC qui sotto
 * devono restare IDENTICI, byte per byte, a quelli definiti nel
 * firmware del Gateway (esp32/gateway/main/main.c)
 *
 * Configurazione (WiFi, MAC del Gateway, intervallo di invio): tramite
 * "idf.py menuconfig" -> "BPA Acquisition Node Configuration"
 */

#include <string.h>
#include <stdbool.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "esp_now.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_rom_crc.h"
#include "esp_timer.h"
#include "nvs_flash.h"
#include "bsp/esp-bsp.h"
#include "esp_camera.h"
#include "img_converters.h"
#include "iot_button.h"
#include "button_gpio.h"

static const char *TAG = "BPA_ACQUISITION_NODE";

//Stato di connessione WiFi

static EventGroupHandle_t s_wifi_event_group;
#define WIFI_CONNECTED_BIT BIT0

//Schema del pacchetto ESP-NOW IDENTICO a quello del Gateway
typedef enum {
    MODE_REFERENCE = 0,
    //MODE_CAPTURED = 1,
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

/*Schema dei pacchetti per foto vere (modalita' 'captured')
 * SEPARATO dallo schema 'reference' sopra - una foto vera (anche
 * compressa in JPEG) pesa molto piu' dei ~250 byte che un singolo
 * pacchetto ESP-NOW puo' contenere, quindi va spezzata in piu' pezzi.
 
 * IMPORTANTE: questa struttura deve restare IDENTICA, byte per byte,
 * anche nel firmware del Gateway 
 
 * Due tipi di pacchetto, distinti dal campo packet_type:
 * - HEADER: mandato una volta sola, dice quanti byte totali e quanti
 *   frammenti aspettarsi, piu' un codice di controllo (CRC) dell'intera
 *   immagine, per verificare che la ricomposizione finale sia integra.
 * - DATA: uno per ogni pezzo dell'immagine, numerato in ordine.
 */

typedef enum {
    FRAG_PACKET_HEADER = 0,
    FRAG_PACKET_DATA = 1,
} frag_packet_type_t;

#define FRAGMENT_DATA_SIZE 200  // byte di dati veri per pacchetto DATA

typedef struct __attribute__((packed)) {
    uint8_t node_id;
    uint32_t seq_num;        // identifica questo specifico scatto (non lo stesso contatore della modalita' reference)
    uint8_t packet_type;     // frag_packet_type_t

    union {
        struct __attribute__((packed)) {
            uint32_t total_size;       // byte totali dell'immagine (prima della frammentazione)
            uint16_t total_fragments;  // quanti pacchetti DATA aspettarsi
            uint16_t image_crc;        // CRC dell'immagine intera, per la verifica finale lato Gateway
        } header;

        struct __attribute__((packed)) {
            uint16_t fragment_index;   // 0-based
            uint16_t fragment_len;     // byte validi in questo frammento (l'ultimo puo' essere piu' corto degli altri)
            uint8_t data[FRAGMENT_DATA_SIZE];
        } fragment;
    } payload;

    uint16_t crc;  // CRC di QUESTO pacchetto (rileva corruzione in trasmissione, come nel reference)
} esp_now_frag_packet_t;

static uint16_t compute_frag_packet_crc(const esp_now_frag_packet_t *packet) {
    size_t len_without_crc = sizeof(esp_now_frag_packet_t) - sizeof(uint16_t);
    return esp_rom_crc16_le(CRC_SEED, (const uint8_t *)packet, len_without_crc);
}

/*Lista delle immagini del dataset condiviso
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

/* Fotocamera + display (anteprima dal vivo + scatto su richiesta)
 * Inizializzazione identica al progetto di prova "display_camera" */

static uint8_t *s_cam_buff;
static lv_obj_t *s_camera_canvas;

// Protegge l'accesso alla fotocamera: l'anteprima dal vivo e lo scatto
// su richiesta girano in due task/contesti diversi, e la libreria della
// fotocamera non e' sicura se chiamata contemporaneamente da entrambi
// (causava corruzione di memoria e crash).
// Questo mutex garantisce che solo uno dei due usi la fotocamera alla volta.
static SemaphoreHandle_t s_camera_mutex;
// NOTA: NON uso piu' vTaskSuspend/vTaskResume per mettere in pausa
// l'anteprima durante uno scatto - causava un blocco totale (deadlock):
// vTaskSuspend puo' fermare un task in QUALSIASI istante, anche mentre
// tiene il mutex della fotocamera, lasciandolo bloccato per sempre.
// Uso invece un segnale cooperativo: l'anteprima stessa controlla
// questo flag e si ferma da sola, solo in un punto sicuro (mai mentre
// tiene il mutex).
static volatile bool s_pause_preview = false;

static void init_camera_and_display(void) {
    s_camera_mutex = xSemaphoreCreateMutex();
    assert(s_camera_mutex != NULL);

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
 * piu' velocemente possibile, per un'immagine fluida. */
static void camera_preview_task(void *pvParameters) {
    uint32_t cam_buff_size = BSP_LCD_H_RES * BSP_LCD_V_RES * 2;

    for (;;) {
        if (s_pause_preview) {
            // Punto sicuro: nessun mutex tenuto qui, si può fermare
            // e ripartire senza rischi.
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }

        xSemaphoreTake(s_camera_mutex, portMAX_DELAY);
        camera_fb_t *pic = esp_camera_fb_get();
        if (pic) {
            bsp_display_lock(0);


            // Fix: copiare pic->len byte invece di cam_buff_size per evitare
            // letture oltre la fine del buffer della fotocamera.
            size_t bytes_to_copy = (pic->len < cam_buff_size) ? pic->len : cam_buff_size;
            memcpy(s_cam_buff, pic->buf, bytes_to_copy);

            esp_camera_fb_return(pic);
            if (BSP_LCD_BIGENDIAN) {
                lv_draw_sw_rgb565_swap(s_cam_buff, cam_buff_size);
            }
            lv_obj_invalidate(s_camera_canvas);
            bsp_display_unlock();
        } else {
            ESP_LOGW(TAG, "Anteprima: cattura fotogramma fallita.");
        }
        xSemaphoreGive(s_camera_mutex);
        vTaskDelay(1);
    }
}

/*
 * Scatta una foto vera su richiesta (pulsante BOOT). La fotocamera
 * cattura in formato grezzo RGB565 (necessario per l'anteprima dal
 * vivo), quindi qui la converto in JPEG compresso (funzione
 * frame2jpg, gia' inclusa nel pacchetto della fotocamera) prima di
 * frammentarla e mandarla - un JPEG e' molto piu' piccolo (decine di
 * KB invece di ~150KB)
 */
static void send_captured_photo(const uint8_t *jpeg_data, size_t jpeg_len, uint32_t seq_num);

static void capture_photo_on_demand(void) {
    // Sospendo del tutto il task di anteprima per l'intera durata
    // di scatto+conversione+invio (non solo la conversione) 
    // Sospendere il task, invece di solo bloccarlo con
    // un mutex, elimina qualunque sua attivita' (fotocamera+display+
    // PSRAM) per l'intera durata dell'operazione.
    s_pause_preview = true;

    xSemaphoreTake(s_camera_mutex, portMAX_DELAY);
    bsp_display_lock(0);

    camera_fb_t *pic = esp_camera_fb_get();
    if (!pic) {
        ESP_LOGW(TAG, "Scatto richiesto, ma la cattura e' fallita.");
        bsp_display_unlock();
        xSemaphoreGive(s_camera_mutex);
        s_pause_preview = false;
        return;
    }

    uint8_t *jpg_buf = NULL;
    size_t jpg_len = 0;

    // Diagnostica
    ESP_LOGI(TAG, "RAM interna libera: %u byte - PSRAM libera: %u byte",
             (unsigned int)heap_caps_get_free_size(MALLOC_CAP_INTERNAL),
             (unsigned int)heap_caps_get_free_size(MALLOC_CAP_SPIRAM));

    bool converted = frame2jpg(pic, 80, &jpg_buf, &jpg_len); // qualita' 80/100, buon compromesso

    if (!converted) {
        ESP_LOGE(TAG, "Conversione della foto in JPEG fallita.");
        esp_camera_fb_return(pic);
        bsp_display_unlock();
        xSemaphoreGive(s_camera_mutex);
        s_pause_preview = false;
        return;
    }

    ESP_LOGI(TAG, "Foto scattata e convertita in JPEG: %zu byte (originale grezzo: %u byte)",
             jpg_len, (unsigned int)pic->len);

    esp_camera_fb_return(pic);
    bsp_display_unlock();
    xSemaphoreGive(s_camera_mutex);

    // L'invio via ESP-NOW avviene DOPO aver rilasciato mutex e display,
    // ma il task di anteprima resta SOSPESO (non solo bloccato dal
    // mutex) per tutta la trasmissione 
    static uint32_t s_captured_seq_num = 0;
    if (s_captured_seq_num == 0) {
        s_captured_seq_num = (uint32_t)(esp_timer_get_time() / 1000);
    }
    send_captured_photo(jpg_buf, jpg_len, s_captured_seq_num++);

    free(jpg_buf);

    s_pause_preview = false;
}

/* Pulsante BOOT (unico pulsante, letto via GPIO puro)
 * NOTA IMPORTANTE: i quattro pulsanti MENU/PLAY/UP/DOWN condividono un
 * canale ADC - creare quei pulsanti avvia comunque un timer interno che
 * legge PERIODICAMENTE tutti i pulsanti dell'array, ADC compresi,
 * andando in conflitto con la fotocamera. Il pulsante BOOT invece e'
 * su un pin GPIO digitale indipendente (nessun ADC coinvolto) - creando
 * SOLO questo pulsante (iot_button_new_gpio_device, non
 * bsp_iot_button_create che li crea tutti insieme), il conflitto non
 * puo' verificarsi per costruzione.
 *
 * Un solo pulsante fisico, due modi di premerlo:
 * - pressione breve: scatta la fotocamera vera, subito
 * - pressione lunga (~1s): accende/spegne la modalita' automatica di
 *   invio riferimenti dal dataset - decidibile in tempo reale, senza
 *   bisogno di modificare la configurazione e riflashare.
 */

static volatile bool s_auto_mode_enabled = false;

/* La conversione JPEG nella callback causava un overflow dello stack. 
 * La callback ora avvia solo la richiesta, mentre la cattura viene eseguita 
 * da un task dedicato. */
static QueueHandle_t s_capture_request_queue;

static void on_boot_short_press(void *button_handle, void *usr_data) {
    int dummy = 1;
    xQueueSend(s_capture_request_queue, &dummy, 0); // non bloccante - se una richiesta e' gia' in coda, questa viene ignorata
}

static void on_boot_long_press(void *button_handle, void *usr_data) {
    s_auto_mode_enabled = !s_auto_mode_enabled;
    ESP_LOGI(TAG, "Pulsante BOOT (pressione lunga) - modalita' automatica ora %s.",
             s_auto_mode_enabled ? "ATTIVA" : "spenta");
}

/* Task dedicato che esegue davvero lo scatto,
 * si sveglia solo quando arriva una richiesta dalla coda. */
#define CAPTURE_COOLDOWN_MS 3000

static void capture_request_task(void *pvParameters) {
    int dummy;
    int64_t last_capture_end_ms = -CAPTURE_COOLDOWN_MS; // permette il primo scatto subito

    for (;;) {
        if (xQueueReceive(s_capture_request_queue, &dummy, portMAX_DELAY) == pdTRUE) {
            int64_t now_ms = esp_timer_get_time() / 1000;
            int64_t elapsed_ms = now_ms - last_capture_end_ms;

            if (elapsed_ms < CAPTURE_COOLDOWN_MS) {
                ESP_LOGW(TAG, "Scatto ignorato - aspetta almeno %d secondi dall'ultimo "
                              "scatto prima di premere di nuovo BOOT.",
                         CAPTURE_COOLDOWN_MS / 1000);
                continue;
            }

            ESP_LOGI(TAG, "Pulsante BOOT (pressione breve) - scatto con la fotocamera vera.");
            capture_photo_on_demand();
            last_capture_end_ms = esp_timer_get_time() / 1000;
        }
    }
}

static void init_buttons(void) {
    s_capture_request_queue = xQueueCreate(1, sizeof(int));
    assert(s_capture_request_queue != NULL);

    button_config_t button_cfg = {
        .long_press_time = 1000, // ms - oltre questa soglia conta come "pressione lunga"
        .short_press_time = 50,
    };
    button_gpio_config_t gpio_cfg = {
        .gpio_num = BSP_BUTTON_BOOT_IO,
        .active_level = 0, // BOOT e' attivo basso (premuto = collegato a massa)
    };

    button_handle_t button_handle = NULL;
    ESP_ERROR_CHECK(iot_button_new_gpio_device(&button_cfg, &gpio_cfg, &button_handle));

    ESP_ERROR_CHECK(iot_button_register_cb(button_handle, BUTTON_SINGLE_CLICK,
                                            NULL, on_boot_short_press, NULL));
    ESP_ERROR_CHECK(iot_button_register_cb(button_handle, BUTTON_LONG_PRESS_START,
                                            NULL, on_boot_long_press, NULL));

    ESP_LOGI(TAG, "Pulsante BOOT inizializzato (GPIO puro, nessun ADC coinvolto).");
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

    // Disattiva il risparmio energetico del radio WiFi 
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

// Usato per "mettere in pausa" l'invio dei frammenti finche' non
// arriva la vera conferma del precedente (invece di una pausa fissa
// "alla cieca") - evita di riempire la coda interna di ESP-NOW piu'
// velocemente di quanto la radio riesca a svuotarla.
static SemaphoreHandle_t s_esp_now_send_done_sem;

static void on_esp_now_data_sent(const wifi_tx_info_t *tx_info, esp_now_send_status_t status) {
    if (status == ESP_NOW_SEND_SUCCESS) {
        ESP_LOGI(TAG, "Pacchetto consegnato al Gateway (ack radio ricevuto).");
    } else {
        ESP_LOGW(TAG, "Consegna al Gateway fallita (nessun ack radio - "
                      "il Gateway potrebbe essere spento o troppo lontano).");
    }
    xSemaphoreGive(s_esp_now_send_done_sem);
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

    s_esp_now_send_done_sem = xSemaphoreCreateBinary();
    assert(s_esp_now_send_done_sem != NULL);

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

/* Invio frammentato di una foto vera (modalita' 'captured') */

/*
 * Spezza una foto JPEG gia' compressa in pacchetti ESP-NOW piccoli
 * abbastanza da starci dentro (FRAGMENT_DATA_SIZE byte di dati ciascuno),
 * e li manda in sequenza: prima un'intestazione (quanti byte, quanti
 * pezzi, CRC dell'immagine intera), poi i pezzi uno alla volta.
 *
 * Se l'invio di un singolo pacchetto fallisce (radio), si continua
 * comunque con i successivi - sara' il Gateway a decidere se
 * l'immagine ricevuta e' completa o va scartata, e a segnalarlo.
 */
static void send_captured_photo(const uint8_t *jpeg_data, size_t jpeg_len, uint32_t seq_num) {
    uint16_t total_fragments = (uint16_t)((jpeg_len + FRAGMENT_DATA_SIZE - 1) / FRAGMENT_DATA_SIZE);
    uint16_t image_crc = esp_rom_crc16_le(CRC_SEED, jpeg_data, jpeg_len);

    ESP_LOGI(TAG, "Invio foto catturata: %u byte totali, %u frammenti, seq=%lu",
             (unsigned int)jpeg_len, total_fragments, (unsigned long)seq_num);

    // 1. Intestazione
    esp_now_frag_packet_t header_packet = {0};
    header_packet.node_id = (uint8_t)CONFIG_BPA_NODE_ID;
    header_packet.seq_num = seq_num;
    header_packet.packet_type = FRAG_PACKET_HEADER;
    header_packet.payload.header.total_size = (uint32_t)jpeg_len;
    header_packet.payload.header.total_fragments = total_fragments;
    header_packet.payload.header.image_crc = image_crc;
    header_packet.crc = compute_frag_packet_crc(&header_packet);

    esp_err_t err = esp_now_send(s_gateway_mac, (const uint8_t *)&header_packet, sizeof(header_packet));
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Invio dell'intestazione fallito: %s - foto scartata (nessun frammento verra' mandato).",
                 esp_err_to_name(err));
        return;
    }

    // Aspetta la vera conferma di consegna (non una pausa fissa "alla
    // cieca") prima di procedere con i frammenti - garantisce che la
    // coda interna di ESP-NOW non si riempia piu' velocemente di
    // quanto la radio riesca effettivamente a svuotarla.
    xSemaphoreTake(s_esp_now_send_done_sem, pdMS_TO_TICKS(200));

    // 2. Frammenti, uno alla volta
    for (uint16_t i = 0; i < total_fragments; i++) {
        size_t offset = (size_t)i * FRAGMENT_DATA_SIZE;
        size_t remaining = jpeg_len - offset;
        uint16_t this_len = (remaining < FRAGMENT_DATA_SIZE) ? (uint16_t)remaining : FRAGMENT_DATA_SIZE;

        esp_now_frag_packet_t frag_packet = {0};
        frag_packet.node_id = (uint8_t)CONFIG_BPA_NODE_ID;
        frag_packet.seq_num = seq_num;
        frag_packet.packet_type = FRAG_PACKET_DATA;
        frag_packet.payload.fragment.fragment_index = i;
        frag_packet.payload.fragment.fragment_len = this_len;
        memcpy(frag_packet.payload.fragment.data, jpeg_data + offset, this_len);
        frag_packet.crc = compute_frag_packet_crc(&frag_packet);

        esp_err_t frag_err = esp_now_send(s_gateway_mac, (const uint8_t *)&frag_packet, sizeof(frag_packet));
        if (frag_err != ESP_OK) {
            ESP_LOGW(TAG, "Invio frammento %u/%u fallito: %s",
                     (unsigned int)(i + 1), total_fragments, esp_err_to_name(frag_err));
        }

        // Aspetta la vera conferma di QUESTO frammento prima di
        // costruire e mandare il prossimo - stesso principio di sopra.
        // Il timeout (200ms) e' solo una rete di sicurezza, per non
        // restare bloccati per sempre se una conferma si perdesse.
        xSemaphoreTake(s_esp_now_send_done_sem, pdMS_TO_TICKS(200));
    }

    ESP_LOGI(TAG, "Invio foto completato (%u frammenti mandati).", total_fragments);
}

/*  Invio ciclico dei pacchetti 'reference' */

static void send_reference_packet(uint32_t seq_num, const char *image_ref) {
    esp_now_packet_t packet = {
        .node_id = (uint8_t)CONFIG_BPA_NODE_ID,
        .seq_num = seq_num,
        .mode = MODE_REFERENCE,
        .timestamp = 0, // non affidabile senza RTC/NTP - vedi stessa nota nel Gateway
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

/*
 * Gestisce SOLO la modalita' automatica a intervalli (invio di
 * riferimenti dal dataset) - lo scatto manuale (PLAY) e l'accensione/
 * spegnimento della modalita' automatica (MENU) sono ora gestiti dalle
 * callback dei pulsanti sopra, non da un polling qui dentro.
 */
static void acquisition_loop_task(void *pvParameters) {
    // Il database del backend ricorda tutti i
    // request_id gia' visti, anche tra un riavvio della scheda e
    // l'altro - se il contatore ripartisse sempre da zero, un pacchetto
    // "numero zero" di oggi avrebbe lo stesso identico request_id di
    // un "numero zero" di ieri, venendo rifiutato per sempre come
    // duplicato. Si parte invece da un
    // numero legato al tempo trascorso dall'accensione.
    uint32_t seq_num = (uint32_t)(esp_timer_get_time() / 1000);
    size_t image_index = 0;

    // Se configurata, questa singola immagine viene mandata sempre,
    // ignorando la rotazione - vedi Kconfig.projbuild.
    bool single_image_mode = strlen(CONFIG_BPA_SINGLE_IMAGE_REF) > 0;

    s_auto_mode_enabled = CONFIG_BPA_AUTO_SEND_ENABLED_AT_BOOT;
    ESP_LOGI(TAG, "Modalita' automatica all'avvio: %s", s_auto_mode_enabled ? "ATTIVA" : "spenta");

    int64_t last_auto_send_ms = 0;

    for (;;) {
        if (s_auto_mode_enabled) {
            int64_t now_ms = esp_timer_get_time() / 1000;
            if (now_ms - last_auto_send_ms >= CONFIG_BPA_SEND_INTERVAL_MS) {
                if (single_image_mode) {
                    send_reference_packet(seq_num, CONFIG_BPA_SINGLE_IMAGE_REF);
                } else {
                    send_reference_packet(seq_num, DATASET_IMAGES[image_index]);
                    image_index = (image_index + 1) % DATASET_IMAGES_COUNT;
                }
                seq_num++;
                last_auto_send_ms = now_ms;
            }
        }

        vTaskDelay(pdMS_TO_TICKS(200));
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

    // Un tentativo precedente allocava gli stack di queste task
    // in PSRAM (xTaskCreateStatic) per aggirare la scarsita' di RAM
    // interna, causava pero' un crash, perche'
    // Espressif sconsiglia esplicitamente stack in PSRAM per task che
    // toccano codice di sistema (WiFi, fotocamera, persino memcpy).
    // La soluzione corretta e' invece liberare RAM interna.
    ESP_LOGI(TAG, "RAM interna libera prima di creare le task: %u byte",
             (unsigned int)heap_caps_get_free_size(MALLOC_CAP_INTERNAL));

    BaseType_t ok1 = xTaskCreate(camera_preview_task, "camera_preview", 4096, NULL, 4, NULL);
    BaseType_t ok2 = xTaskCreate(capture_request_task, "capture_request", 8192, NULL, 5, NULL);
    BaseType_t ok3 = xTaskCreate(acquisition_loop_task, "acquisition_loop", 4096, NULL, 5, NULL);

    if (ok1 != pdPASS) ESP_LOGE(TAG, "Creazione di camera_preview_task FALLITA (ricevuto %d).", (int)ok1);
    if (ok2 != pdPASS) ESP_LOGE(TAG, "Creazione di capture_request_task FALLITA (ricevuto %d).", (int)ok2);
    if (ok3 != pdPASS) ESP_LOGE(TAG, "Creazione di acquisition_loop_task FALLITA (ricevuto %d).", (int)ok3);
}