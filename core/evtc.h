/*
 * evtc : coeur portable du boîtier d'acquisition d'essais (C99, sans allocation dynamique).
 *  - décodage des trames CAN du véhicule (base ev_test.dbc) ;
 *  - calcul énergétique en temps réel (énergie de traction, énergie régénérée, Wh/km, autonomie) ;
 *  - trames binaires de 20 octets pour la liaison Bluetooth Low Energy (taille utile par défaut d'une notification).
 * Le même code est compilé sur PC (tests) et dans le firmware STM32 ; l'application Android implémente le même protocole.
 */
#ifndef EVTC_H
#define EVTC_H

#include <stdint.h>

#define EVTC_CAN_BMS_PACK   0x3D5u  /* 981 : tension, courant, état de charge, température */
#define EVTC_CAN_VCU_SPEED  0x217u  /* 535 : vitesse véhicule, rapport engagé */

#define EVTC_PKT_LEN        20
#define EVTC_PKT_HEADER     0xA5u
#define EVTC_PKT_SAMPLE     0x01u
#define EVTC_PKT_ENERGY     0x02u

typedef struct {
    uint32_t t_ms;        /* horodatage du boîtier */
    float speed_kmh;
    float pack_v;
    float pack_a;         /* > 0 en traction, < 0 en régénération */
    float soc_pct;
    float temp_c;
    uint8_t gear;         /* 0 P, 1 R, 2 N, 3 D */
} evtc_sample_t;

typedef struct {
    double traction_wh;   /* énergie sortie de la batterie */
    double regen_wh;      /* énergie rendue à la batterie */
    double distance_km;
    uint32_t last_t_ms;
    float last_power_kw;
    float last_speed_kmh;
    int started;
} evtc_energy_t;

/* Décodage CAN : met à jour l'échantillon courant ; retourne 1 si l'identifiant est connu, 0 sinon. */
int evtc_can_decode(uint32_t id, const uint8_t *data, uint8_t dlc, evtc_sample_t *s);

/* Calcul énergétique : intégration trapézoïdale de P = U x I et de la vitesse. */
void evtc_energy_reset(evtc_energy_t *e);
void evtc_energy_update(evtc_energy_t *e, const evtc_sample_t *s);
double evtc_energy_net_wh(const evtc_energy_t *e);
double evtc_energy_wh_per_km(const evtc_energy_t *e);        /* 0 si la distance est trop courte */
double evtc_energy_regen_ratio(const evtc_energy_t *e);       /* part de l'énergie de traction récupérée */
double evtc_range_km(const evtc_energy_t *e, double soc_pct, double usable_kwh);

/* Trames Bluetooth : 20 octets, en-tête 0xA5, type, séquence, données petit-boutistes, CRC16-CCITT final. */
uint16_t evtc_crc16(const uint8_t *buf, int len);
void evtc_pack_sample(uint8_t out[EVTC_PKT_LEN], uint8_t seq, const evtc_sample_t *s);
void evtc_pack_energy(uint8_t out[EVTC_PKT_LEN], uint8_t seq, uint32_t t_ms, const evtc_energy_t *e);
int evtc_pkt_valid(const uint8_t pkt[EVTC_PKT_LEN]);

#endif
