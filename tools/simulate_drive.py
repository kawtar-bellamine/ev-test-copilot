"""Simulateur d'essai d'un véhicule électrique compact du segment C sur un cycle mixte urbain, route et autoroute.

Il produit ce que verrait le boîtier d'acquisition branché sur le CAN du véhicule :
  - les trames VCU_Speed toutes les 20 ms et BMS_Pack toutes les 100 ms (base tools/ev_test.dbc), au format candump ;
  - la « vérité terrain » énergétique, calculée à partir du modèle physique, pour valider le calcul embarqué.

Modèle longitudinal : P_roue = (m·a + ½·ρ·Cx·S·v² + Crr·m·g)·v ; rendement de chaîne en traction, rendement et
puissance de régénération limités, régénération estompée entre 15 et 8 km/h ;
batterie = tension à vide fonction de l'état de charge et résistance interne.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from pathlib import Path

import cantools
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
DBC = cantools.database.load_file(str(ROOT / "tools" / "ev_test.dbc"))
DT = 0.02  # 50 Hz


@dataclass
class Vehicle:
    mass: float = 1_750.0  # kg, conducteur et instrumentation compris
    cx_s: float = 0.29 * 2.30  # m²
    crr: float = 0.0090
    eta_traction: float = 0.88
    eta_regen: float = 0.70
    regen_max_kw: float = 60.0
    aux_kw: float = 0.6  # climatisation, électronique, instrumentation d'essai
    usable_kwh: float = 60.0
    r_int: float = 0.085  # ohm


def ocv(soc: float) -> float:
    return 345.0 + 0.75 * soc  # tension à vide du pack, V (soc en %)


def drive_cycle(seed: int = 7) -> np.ndarray:
    """Vitesse (km/h) échantillonnée à 50 Hz : 6 arrêts urbains, une portion route, une portion autoroute."""
    rng = np.random.default_rng(seed)
    segs: list[tuple[float, float]] = []  # (vitesse cible, durée en s)
    for _ in range(6):
        segs += [(0, rng.uniform(8, 20)), (rng.uniform(35, 50), rng.uniform(25, 60))]
    segs += [(0, 10), (80, 180), (60, 40), (90, 160), (130, 300), (110, 120), (70, 60), (30, 60), (0, 15)]
    v, out = 0.0, []
    for target, dur in segs:
        for _ in range(int(dur / DT)):
            acc = 2.2 if target > v else -2.8  # km/h par seconde
            v = min(target, v + acc * DT) if target > v else max(target, v + acc * DT)
            out.append(v)
    v = np.asarray(out)
    jitter = np.convolve(rng.normal(0, 0.6, v.size), np.ones(50) / 50, mode="same")
    return np.clip(v + np.where(v > 5, jitter, 0), 0, None)


def simulate(seed: int = 7):
    veh, rng = Vehicle(), np.random.default_rng(seed + 1)
    v_kmh = drive_cycle(seed)
    v = v_kmh / 3.6
    a = np.gradient(v, DT)
    soc, temp = 82.0, 24.0
    rows, truth = [], dict(traction_wh=0.0, regen_wh=0.0, distance_km=0.0)
    for k in range(v.size):
        f = veh.mass * a[k] + 0.5 * 1.2 * veh.cx_s * v[k] ** 2 + (veh.crr * veh.mass * 9.81 if v[k] > 0.1 else 0.0)
        p_wheel = f * v[k] / 1000.0  # kW
        if p_wheel >= 0:
            p_batt = p_wheel / veh.eta_traction
        else:  # régénération, relayée par le frein mécanique
            fade = min(max((v_kmh[k] - 8.0) / 7.0, 0.0), 1.0)  # entre 15 et 8 km/h
            p_batt = max(p_wheel * veh.eta_regen, -veh.regen_max_kw) * fade
        p_batt += veh.aux_kw
        u0 = ocv(soc)
        i = (u0 - math.sqrt(u0 * u0 - 4 * veh.r_int * p_batt * 1000.0)) / (2 * veh.r_int)
        u = u0 - veh.r_int * i
        soc -= u * i * DT / 3600.0 / (veh.usable_kwh * 1000.0) * 100.0
        temp += (abs(i) * abs(i) * veh.r_int * 2e-6 - (temp - 24.0) * 1e-4) * DT * 10
        if u * i >= 0:
            truth["traction_wh"] += u * i * DT / 3600.0
        else:
            truth["regen_wh"] += -u * i * DT / 3600.0
        truth["distance_km"] += v_kmh[k] * DT / 3600.0
        rows.append((k * DT, v_kmh[k], u + rng.normal(0, 0.05), i + rng.normal(0, 0.3), soc, temp))
    return rows, truth


def to_candump(rows) -> list[str]:
    """Trames telles qu'enregistrées par candump : VCU toutes les 20 ms, BMS toutes les 100 ms."""
    speed, bms = DBC.get_message_by_name("VCU_Speed"), DBC.get_message_by_name("BMS_Pack")
    lines = []
    for k, (t, vk, u, i, soc, temp) in enumerate(rows):
        gear = 3 if vk > 0.5 else 0
        frame = speed.encode({"VehicleSpeed": round(vk, 2), "GearState": gear})
        lines.append(f"({t + 1_700_000_000:.6f}) can0 {speed.frame_id:03X}#{frame.hex().upper()}")
        if k % 5 == 0:
            frame = bms.encode(
                {
                    "PackVoltage": round(u, 2),
                    "PackCurrent": round(i, 1),
                    "StateOfCharge": round(min(max(soc, 0), 100), 2),
                    "CellTempMax": round(temp, 1),
                }
            )
            lines.append(f"({t + 0.004 + 1_700_000_000:.6f}) can0 {bms.frame_id:03X}#{frame.hex().upper()}")
    return lines


def main() -> None:
    rows, truth = simulate()
    out = ROOT / "data"
    out.mkdir(exist_ok=True)
    (out / "drive_cycle.log").write_text("\n".join(to_candump(rows)) + "\n", encoding="utf-8")
    import json

    (out / "truth.json").write_text(
        json.dumps({k: round(v, 3) for k, v in truth.items()} | {"duration_s": round(len(rows) * DT, 1)}, indent=2),
        encoding="utf-8",
    )
    print(
        f"{len(rows) * DT / 60:.1f} min simulées, {truth['distance_km']:.2f} km, "
        f"traction {truth['traction_wh']:.0f} Wh, régénération {truth['regen_wh']:.0f} Wh"
    )


if __name__ == "__main__":
    main()
