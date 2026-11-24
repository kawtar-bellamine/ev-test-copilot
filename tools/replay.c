/* Rejoue un enregistrement candump à travers le code du boîtier (core/evtc.c), exactement comme le firmware :
 * décodage CAN, échantillon toutes les 100 ms, bilan énergétique toutes les secondes.
 * Sorties : trames Bluetooth binaires (pour l'application Android et ses tests) et bilan final sur stdout. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "evtc.h"

static int hexval(char c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        fprintf(stderr, "usage : %s enregistrement.log trames.bin\n", argv[0]);
        return 2;
    }
    FILE *in = fopen(argv[1], "r"), *out = fopen(argv[2], "wb");
    if (!in || !out) { perror("fichier"); return 1; }

    char line[256];
    double t0 = -1.0, next_sample = 0.0, next_energy = 1.0;
    evtc_sample_t s;
    evtc_energy_t e;
    uint8_t pkt[EVTC_PKT_LEN], seq = 0;
    unsigned n_frames = 0, n_known = 0, n_pkt = 0;
    memset(&s, 0, sizeof(s));
    evtc_energy_reset(&e);

    while (fgets(line, sizeof line, in)) {
        double ts;
        char ifname[16], frame[64];
        if (sscanf(line, "(%lf) %15s %63s", &ts, ifname, frame) != 3) continue;
        if (t0 < 0) t0 = ts;
        double t = ts - t0;
        while (t >= next_sample) {                         /* cadence du boîtier : 10 Hz */
            s.t_ms = (uint32_t)(next_sample * 1000.0 + 0.5);
            evtc_energy_update(&e, &s);
            evtc_pack_sample(pkt, seq++, &s);
            fwrite(pkt, 1, EVTC_PKT_LEN, out);
            n_pkt++;
            if (next_sample + 1e-9 >= next_energy) {
                evtc_pack_energy(pkt, seq++, s.t_ms, &e);
                fwrite(pkt, 1, EVTC_PKT_LEN, out);
                n_pkt++;
                next_energy += 1.0;
            }
            next_sample += 0.1;
        }
        char *hash = strchr(frame, '#');
        if (!hash) continue;
        *hash = '\0';
        uint32_t id = (uint32_t)strtoul(frame, NULL, 16);
        uint8_t data[8];
        int dlc = 0;
        for (char *p = hash + 1; p[0] && p[1] && dlc < 8; p += 2) data[dlc++] = (uint8_t)(hexval(p[0]) << 4 | hexval(p[1]));
        n_frames++;
        n_known += (unsigned)evtc_can_decode(id, data, (uint8_t)dlc, &s);
    }
    fclose(in);
    fclose(out);
    printf("{\"frames\": %u, \"decoded\": %u, \"packets\": %u, \"traction_wh\": %.3f, \"regen_wh\": %.3f, "
           "\"distance_km\": %.4f, \"wh_per_km\": %.2f, \"regen_ratio\": %.4f, \"soc_end\": %.2f}\n",
           n_frames, n_known, n_pkt, e.traction_wh, e.regen_wh, e.distance_km, evtc_energy_wh_per_km(&e),
           evtc_energy_regen_ratio(&e), s.soc_pct);
    return 0;
}
