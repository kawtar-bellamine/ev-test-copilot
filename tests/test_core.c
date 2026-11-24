/* Tests unitaires du cœur C partagé par le firmware et l'outil de rejeu. */
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "evtc.h"

static int fails = 0;
#define CHECK(c) do { if (!(c)) { printf("ÉCHEC %s:%d : %s\n", __FILE__, __LINE__, #c); fails++; } } while (0)
#define NEAR(a, b, tol) CHECK(fabs((double)(a) - (double)(b)) <= (tol))

static void test_crc(void)
{
    const char *s = "123456789";
    CHECK(evtc_crc16((const uint8_t *)s, 9) == 0x29B1); /* CRC-16/CCITT-FALSE */
}

static void test_can_decode(void)
{
    evtc_sample_t s;
    memset(&s, 0, sizeof s);
    /* 356,78 V ; -123,4 A ; 74,15 % ; 31,2 °C (petit-boutiste, comme le DBC) */
    uint8_t bms[8] = {0x5E, 0x8B, 0x2E, 0xFB, 0xF7, 0x1C, 0x38, 0x01};
    CHECK(evtc_can_decode(EVTC_CAN_BMS_PACK, bms, 8, &s) == 1);
    NEAR(s.pack_v, 356.78, 1e-3);
    NEAR(s.pack_a, -123.4, 1e-3);
    NEAR(s.soc_pct, 74.15, 1e-3);
    NEAR(s.temp_c, 31.2, 1e-3);
    uint8_t vcu[4] = {0x24, 0x22, 0x03, 0x00}; /* 87,40 km/h, rapport D */
    CHECK(evtc_can_decode(EVTC_CAN_VCU_SPEED, vcu, 4, &s) == 1);
    NEAR(s.speed_kmh, 87.40, 1e-3);
    CHECK(s.gear == 3);
    CHECK(evtc_can_decode(0x123, vcu, 4, &s) == 0);      /* identifiant inconnu */
    CHECK(evtc_can_decode(EVTC_CAN_BMS_PACK, bms, 4, &s) == 0); /* longueur trop courte */
}

static void test_zero_crossing(void)
{
    evtc_energy_t e;
    evtc_sample_t s;
    memset(&s, 0, sizeof s);
    evtc_energy_reset(&e);
    s.pack_v = 400.0f; s.pack_a = 100.0f; s.t_ms = 0;    /* +40 kW */
    evtc_energy_update(&e, &s);
    s.pack_a = -100.0f; s.t_ms = 1000;                   /* -40 kW une seconde plus tard */
    evtc_energy_update(&e, &s);
    NEAR(e.traction_wh, 40.0 * 0.25 / 3.6, 1e-3);
    NEAR(e.regen_wh, 40.0 * 0.25 / 3.6, 1e-3);
}

static void test_packets(void)
{
    evtc_sample_t s = {123456u, 87.4f, 356.78f, -123.4f, 74.15f, 31.2f, 3};
    uint8_t p[EVTC_PKT_LEN];
    evtc_pack_sample(p, 7, &s);
    CHECK(p[0] == EVTC_PKT_HEADER && p[1] == EVTC_PKT_SAMPLE && p[2] == 7);
    CHECK(evtc_pkt_valid(p));
    p[10] ^= 1;
    CHECK(!evtc_pkt_valid(p));
}

int main(void)
{
    test_crc();
    test_can_decode();
    test_zero_crossing();
    test_packets();
    printf(fails ? "%d test(s) en échec\n" : "Tests C : tout est vert\n", fails);
    return fails != 0;
}
