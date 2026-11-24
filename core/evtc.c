#include "evtc.h"

#include <math.h>
#include <string.h>

static uint16_t rd_u16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static void wr_u16(uint8_t *p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static void wr_u32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24);
}

/* Arrondi identique en C, en Python et en Kotlin : partie entière de x + 0,5, puis saturation. */
static int32_t q(double x, double scale, int32_t lo, int32_t hi)
{
    double r = floor(x * scale + 0.5);
    if (r < lo) r = lo;
    if (r > hi) r = hi;
    return (int32_t)r;
}

int evtc_can_decode(uint32_t id, const uint8_t *d, uint8_t dlc, evtc_sample_t *s)
{
    if (id == EVTC_CAN_BMS_PACK && dlc >= 8) {
        s->pack_v = rd_u16(&d[0]) * 0.01f;
        s->pack_a = (int16_t)rd_u16(&d[2]) * 0.1f;
        s->soc_pct = rd_u16(&d[4]) * 0.01f;
        s->temp_c = (int16_t)rd_u16(&d[6]) * 0.1f;
        return 1;
    }
    if (id == EVTC_CAN_VCU_SPEED && dlc >= 3) {
        s->speed_kmh = rd_u16(&d[0]) * 0.01f;
        s->gear = d[2];
        return 1;
    }
    return 0;
}

void evtc_energy_reset(evtc_energy_t *e)
{
    memset(e, 0, sizeof(*e));
}

void evtc_energy_update(evtc_energy_t *e, const evtc_sample_t *s)
{
    float p_kw = s->pack_v * s->pack_a / 1000.0f;
    if (e->started) {
        double dt_h = (double)(s->t_ms - e->last_t_ms) / 3600000.0;
        if (dt_h > 0.0 && dt_h < 2.0 / 3600.0) {          /* trou de plus de 2 s : on ne fait pas d'hypothèse */
            double p0 = (double)e->last_power_kw, p1 = (double)p_kw;
            double pos, neg;                               /* aires positive et négative du trapèze, en kWh */
            if ((p0 >= 0.0) == (p1 >= 0.0)) {
                double a = 0.5 * (p0 + p1) * dt_h;
                pos = a > 0.0 ? a : 0.0;
                neg = a < 0.0 ? -a : 0.0;
            } else {                                       /* passage traction / régénération : on coupe au zéro */
                double f = p0 / (p0 - p1);
                double a0 = 0.5 * p0 * f * dt_h, a1 = 0.5 * p1 * (1.0 - f) * dt_h;
                pos = (a0 > 0.0 ? a0 : 0.0) + (a1 > 0.0 ? a1 : 0.0);
                neg = (a0 < 0.0 ? -a0 : 0.0) + (a1 < 0.0 ? -a1 : 0.0);
            }
            e->traction_wh += pos * 1000.0;
            e->regen_wh += neg * 1000.0;
            e->distance_km += 0.5 * ((double)s->speed_kmh + (double)e->last_speed_kmh) * dt_h;
        }
    }
    e->started = 1;
    e->last_t_ms = s->t_ms;
    e->last_power_kw = p_kw;
    e->last_speed_kmh = s->speed_kmh;
}

double evtc_energy_net_wh(const evtc_energy_t *e) { return e->traction_wh - e->regen_wh; }

double evtc_energy_wh_per_km(const evtc_energy_t *e)
{
    return e->distance_km > 0.1 ? evtc_energy_net_wh(e) / e->distance_km : 0.0;
}

double evtc_energy_regen_ratio(const evtc_energy_t *e)
{
    return e->traction_wh > 0.0 ? e->regen_wh / e->traction_wh : 0.0;
}

double evtc_range_km(const evtc_energy_t *e, double soc_pct, double usable_kwh)
{
    double whkm = evtc_energy_wh_per_km(e);
    return whkm > 1.0 ? soc_pct / 100.0 * usable_kwh * 1000.0 / whkm : 0.0;
}

uint16_t evtc_crc16(const uint8_t *buf, int len)
{
    uint16_t crc = 0xFFFF;                                 /* CRC16-CCITT (polynôme 0x1021, init 0xFFFF) */
    for (int i = 0; i < len; i++) {
        crc ^= (uint16_t)buf[i] << 8;
        for (int b = 0; b < 8; b++) crc = (crc & 0x8000) ? (uint16_t)((crc << 1) ^ 0x1021) : (uint16_t)(crc << 1);
    }
    return crc;
}

static void finish(uint8_t out[EVTC_PKT_LEN])
{
    wr_u16(&out[18], evtc_crc16(out, 18));
}

void evtc_pack_sample(uint8_t out[EVTC_PKT_LEN], uint8_t seq, const evtc_sample_t *s)
{
    memset(out, 0, EVTC_PKT_LEN);
    out[0] = EVTC_PKT_HEADER;
    out[1] = EVTC_PKT_SAMPLE;
    out[2] = seq;
    out[3] = s->gear;
    wr_u32(&out[4], s->t_ms);
    wr_u16(&out[8], (uint16_t)q(s->speed_kmh, 100.0, 0, 65535));
    wr_u16(&out[10], (uint16_t)q(s->pack_v, 100.0, 0, 65535));
    wr_u16(&out[12], (uint16_t)(int16_t)q(s->pack_a, 10.0, -32768, 32767));
    wr_u16(&out[14], (uint16_t)q(s->soc_pct, 100.0, 0, 10000));
    wr_u16(&out[16], (uint16_t)(int16_t)q(s->temp_c, 10.0, -32768, 32767));
    finish(out);
}

void evtc_pack_energy(uint8_t out[EVTC_PKT_LEN], uint8_t seq, uint32_t t_ms, const evtc_energy_t *e)
{
    memset(out, 0, EVTC_PKT_LEN);
    out[0] = EVTC_PKT_HEADER;
    out[1] = EVTC_PKT_ENERGY;
    out[2] = seq;
    wr_u32(&out[4], t_ms);
    wr_u32(&out[8], (uint32_t)q(e->traction_wh, 10.0, 0, 2147483647));
    wr_u32(&out[12], (uint32_t)q(e->regen_wh, 10.0, 0, 2147483647));
    wr_u16(&out[16], (uint16_t)q(e->distance_km, 100.0, 0, 65535));   /* pas de 10 m */
    finish(out);
}

int evtc_pkt_valid(const uint8_t pkt[EVTC_PKT_LEN])
{
    return pkt[0] == EVTC_PKT_HEADER && rd_u16(&pkt[18]) == evtc_crc16(pkt, 18);
}
