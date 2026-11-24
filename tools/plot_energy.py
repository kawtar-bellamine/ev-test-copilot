"""Figure du README : vitesse, puissance batterie et bilan énergétique calculés par le boîtier sur l'essai simulé."""

import struct
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
raw = (ROOT / "build" / "packets.bin").read_bytes()
t, v, p, et, eT, eR = [], [], [], [], [], []
for i in range(0, len(raw), 20):
    k = raw[i : i + 20]
    ts = struct.unpack_from("<I", k, 4)[0] / 60000
    if k[1] == 1:
        speed, volt, cur = struct.unpack_from("<HHh", k, 8)
        t.append(ts)
        v.append(speed / 100)
        p.append(volt / 100 * cur / 10 / 1000)
    else:
        tr, rg = struct.unpack_from("<II", k, 8)
        et.append(ts)
        eT.append(tr / 10000)
        eR.append(rg / 10000)

# Moyenne glissante sur 1 s pour la lisibilité (les calculs utilisent les échantillons bruts)
p = [sum(p[max(0, i - 9) : i + 1]) / len(p[max(0, i - 9) : i + 1]) for i in range(len(p))]

plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
fig, ax = plt.subplots(3, 1, figsize=(10, 7.2), sharex=True, gridspec_kw={"height_ratios": [1, 1.3, 1]})
ax[0].plot(t, v, color="#455A64", lw=1)
ax[0].set_ylabel("Vitesse (km/h)")
ax[0].set_title("Essai simulé de 22 min, 28,3 km : données calculées par le boîtier à 10 Hz", loc="left")
ax[1].fill_between(t, p, 0, where=[x >= 0 for x in p], color="#0288D1", lw=0, label="Traction")
ax[1].fill_between(t, p, 0, where=[x < 0 for x in p], color="#43A047", lw=0, label="Régénération")
ax[1].axhline(0, color="#999", lw=0.6)
ax[1].set_ylabel("Puissance batterie,\nmoyenne 1 s (kW)")
ax[1].legend(loc="upper left", frameon=False)
ax[2].plot(et, eT, color="#0288D1", label="Énergie de traction")
ax[2].plot(et, eR, color="#43A047", label="Énergie récupérée")
ax[2].plot(et, [a - b for a, b in zip(eT, eR, strict=True)], color="#212121", ls="--", label="Énergie nette")
ax[2].set_ylabel("Énergie (kWh)")
ax[2].set_xlabel("Temps (min)")
ax[2].legend(loc="upper left", frameon=False)
fig.tight_layout()
out = ROOT / "docs" / "essai_energie.png"
fig.savefig(out, dpi=150)
print(out)
