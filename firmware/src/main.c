/*
 * Boîtier d'acquisition d'essais : NUCLEO-F446RE, transceiver CAN 3,3 V (SN65HVD230) et module Bluetooth Low Energy
 * HM-10 (UART transparent). Écoute le CAN véhicule à 500 kbit/s, calcule l'énergie en temps réel et envoie à
 * l'application Android une trame d'échantillon toutes les 100 ms et un bilan énergétique toutes les secondes.
 *
 * Câblage : CAN1 PA11 (RX) / PA12 (TX) vers le transceiver ; USART1 PA9 (TX) / PA10 (RX) vers le HM-10 (9 600 bauds) ;
 * USART2 PA2 / PA3 : port COM du ST-LINK pour le journal ; LED LD2 PA5 : clignote à chaque trame CAN reçue.
 */
#include <string.h>

#include "evtc.h"
#include "stm32f4xx_hal.h"

#define SAMPLE_PERIOD_MS   100u
#define ENERGY_PERIOD_MS   1000u
#define CAN_TIMEOUT_MS     500u
#define FLAG_CAN_STALE     0x80u   /* bit de poids fort de l'octet « rapport » : données CAN périmées */

CAN_HandleTypeDef hcan1;
UART_HandleTypeDef huart1;   /* HM-10 */
UART_HandleTypeDef huart2;   /* journal */

static volatile evtc_sample_t s_live;   /* mis à jour par l'interruption CAN */
static volatile uint32_t s_last_rx_ms;
static volatile uint32_t s_rx_count;

static void clock_84mhz(void);
static void gpio_init(void);
static void can1_init(void);
static void uart_init(UART_HandleTypeDef *h, USART_TypeDef *inst, uint32_t baud);

void HAL_CAN_RxFifo0MsgPendingCallback(CAN_HandleTypeDef *hcan)
{
    CAN_RxHeaderTypeDef hdr;
    uint8_t data[8];
    while (HAL_CAN_GetRxFifoFillLevel(hcan, CAN_RX_FIFO0) > 0) {
        if (HAL_CAN_GetRxMessage(hcan, CAN_RX_FIFO0, &hdr, data) != HAL_OK) break;
        if (hdr.IDE == CAN_ID_STD && evtc_can_decode(hdr.StdId, data, (uint8_t)hdr.DLC, (evtc_sample_t *)&s_live)) {
            s_last_rx_ms = HAL_GetTick();
            s_rx_count++;
        }
    }
}

int main(void)
{
    HAL_Init();
    clock_84mhz();
    gpio_init();
    uart_init(&huart1, USART1, 9600);
    uart_init(&huart2, USART2, 115200);
    can1_init();

    static const char boot[] = "evtc : boitier d'acquisition pret, CAN 500 kbit/s, BLE 9600 bauds\r\n";
    HAL_UART_Transmit(&huart2, (uint8_t *)boot, sizeof(boot) - 1, 100);

    evtc_energy_t energy;
    evtc_sample_t snap;
    uint8_t pkt[EVTC_PKT_LEN], seq = 0;
    uint32_t next_sample = HAL_GetTick(), next_energy = next_sample + ENERGY_PERIOD_MS, seen = 0;
    evtc_energy_reset(&energy);

    for (;;) {
        uint32_t now = HAL_GetTick();
        if ((int32_t)(now - next_sample) < 0) continue;
        next_sample += SAMPLE_PERIOD_MS;

        __disable_irq();                                   /* copie cohérente de l'échantillon courant */
        memcpy(&snap, (const void *)&s_live, sizeof(snap));
        uint32_t last_rx = s_last_rx_ms, count = s_rx_count;
        __enable_irq();

        snap.t_ms = now;
        int stale = (now - last_rx) > CAN_TIMEOUT_MS;
        if (!stale) evtc_energy_update(&energy, &snap);    /* pas d'intégration sur des données figées */
        if (stale) snap.gear |= FLAG_CAN_STALE;

        evtc_pack_sample(pkt, seq++, &snap);
        HAL_UART_Transmit(&huart1, pkt, EVTC_PKT_LEN, 50);
        if ((int32_t)(now - next_energy) >= 0) {
            next_energy += ENERGY_PERIOD_MS;
            evtc_pack_energy(pkt, seq++, now, &energy);
            HAL_UART_Transmit(&huart1, pkt, EVTC_PKT_LEN, 50);
        }
        if (count != seen) { HAL_GPIO_TogglePin(GPIOA, GPIO_PIN_5); seen = count; }
    }
}

/* HSI 16 MHz -> PLL -> 84 MHz ; APB1 à 42 MHz (horloge du CAN). */
static void clock_84mhz(void)
{
    RCC_OscInitTypeDef osc = {0};
    RCC_ClkInitTypeDef clk = {0};
    __HAL_RCC_PWR_CLK_ENABLE();
    __HAL_PWR_VOLTAGESCALING_CONFIG(PWR_REGULATOR_VOLTAGE_SCALE3);
    osc.OscillatorType = RCC_OSCILLATORTYPE_HSI;
    osc.HSIState = RCC_HSI_ON;
    osc.HSICalibrationValue = RCC_HSICALIBRATION_DEFAULT;
    osc.PLL.PLLState = RCC_PLL_ON;
    osc.PLL.PLLSource = RCC_PLLSOURCE_HSI;
    osc.PLL.PLLM = 16;
    osc.PLL.PLLN = 336;
    osc.PLL.PLLP = RCC_PLLP_DIV4;
    osc.PLL.PLLQ = 7;
    osc.PLL.PLLR = 2;
    HAL_RCC_OscConfig(&osc);
    clk.ClockType = RCC_CLOCKTYPE_HCLK | RCC_CLOCKTYPE_SYSCLK | RCC_CLOCKTYPE_PCLK1 | RCC_CLOCKTYPE_PCLK2;
    clk.SYSCLKSource = RCC_SYSCLKSOURCE_PLLCLK;
    clk.AHBCLKDivider = RCC_SYSCLK_DIV1;
    clk.APB1CLKDivider = RCC_HCLK_DIV2;
    clk.APB2CLKDivider = RCC_HCLK_DIV1;
    HAL_RCC_ClockConfig(&clk, FLASH_LATENCY_2);
}

static void gpio_init(void)
{
    GPIO_InitTypeDef g = {0};
    __HAL_RCC_GPIOA_CLK_ENABLE();
    g.Pin = GPIO_PIN_5;
    g.Mode = GPIO_MODE_OUTPUT_PP;
    HAL_GPIO_Init(GPIOA, &g);
}

/* 500 kbit/s : 42 MHz / 6 = 7 MHz, 14 quanta (1 + 11 + 2), point d'échantillonnage à 86 %. */
static void can1_init(void)
{
    GPIO_InitTypeDef g = {0};
    __HAL_RCC_CAN1_CLK_ENABLE();
    g.Pin = GPIO_PIN_11 | GPIO_PIN_12;
    g.Mode = GPIO_MODE_AF_PP;
    g.Speed = GPIO_SPEED_FREQ_VERY_HIGH;
    g.Alternate = GPIO_AF9_CAN1;
    HAL_GPIO_Init(GPIOA, &g);

    hcan1.Instance = CAN1;
    hcan1.Init.Prescaler = 6;
    hcan1.Init.Mode = CAN_MODE_SILENT;                     /* écoute seule : le boîtier n'émet jamais sur le véhicule */
    hcan1.Init.SyncJumpWidth = CAN_SJW_1TQ;
    hcan1.Init.TimeSeg1 = CAN_BS1_11TQ;
    hcan1.Init.TimeSeg2 = CAN_BS2_2TQ;
    hcan1.Init.AutoBusOff = ENABLE;
    hcan1.Init.AutoRetransmission = DISABLE;
    HAL_CAN_Init(&hcan1);

    CAN_FilterTypeDef f = {0};                             /* ne garder que BMS_Pack (0x3D5) et VCU_Speed (0x217) */
    f.FilterBank = 0;
    f.FilterMode = CAN_FILTERMODE_IDLIST;
    f.FilterScale = CAN_FILTERSCALE_16BIT;
    f.FilterIdHigh = (uint16_t)(EVTC_CAN_BMS_PACK << 5);
    f.FilterIdLow = (uint16_t)(EVTC_CAN_VCU_SPEED << 5);
    f.FilterMaskIdHigh = (uint16_t)(EVTC_CAN_BMS_PACK << 5);
    f.FilterMaskIdLow = (uint16_t)(EVTC_CAN_VCU_SPEED << 5);
    f.FilterFIFOAssignment = CAN_RX_FIFO0;
    f.FilterActivation = ENABLE;
    f.SlaveStartFilterBank = 14;
    HAL_CAN_ConfigFilter(&hcan1, &f);

    HAL_NVIC_SetPriority(CAN1_RX0_IRQn, 5, 0);
    HAL_NVIC_EnableIRQ(CAN1_RX0_IRQn);
    HAL_CAN_Start(&hcan1);
    HAL_CAN_ActivateNotification(&hcan1, CAN_IT_RX_FIFO0_MSG_PENDING);
}

static void uart_init(UART_HandleTypeDef *h, USART_TypeDef *inst, uint32_t baud)
{
    GPIO_InitTypeDef g = {0};
    if (inst == USART1) {
        __HAL_RCC_USART1_CLK_ENABLE();
        g.Pin = GPIO_PIN_9 | GPIO_PIN_10;
    } else {
        __HAL_RCC_USART2_CLK_ENABLE();
        g.Pin = GPIO_PIN_2 | GPIO_PIN_3;
    }
    g.Mode = GPIO_MODE_AF_PP;
    g.Speed = GPIO_SPEED_FREQ_VERY_HIGH;
    g.Alternate = GPIO_AF7_USART1;                         /* AF7 pour USART1 et USART2 */
    HAL_GPIO_Init(GPIOA, &g);
    h->Instance = inst;
    h->Init.BaudRate = baud;
    h->Init.WordLength = UART_WORDLENGTH_8B;
    h->Init.StopBits = UART_STOPBITS_1;
    h->Init.Parity = UART_PARITY_NONE;
    h->Init.Mode = UART_MODE_TX_RX;
    h->Init.HwFlowCtl = UART_HWCONTROL_NONE;
    h->Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(h);
}

void CAN1_RX0_IRQHandler(void) { HAL_CAN_IRQHandler(&hcan1); }
void SysTick_Handler(void) { HAL_IncTick(); }
