"""Tests de bout en bout : DBC (cantools) -> journal candump -> code C du boîtier -> trames Bluetooth."""

import json
import struct
import subprocess
from pathlib import Path

import cantools
import pytest

ROOT = Path(__file__).resolve().parents[1]
DB = cantools.database.load_file(ROOT / "tools" / "ev_test.dbc")
REPLAY = ROOT / "build" / "replay"


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def packets(path: Path):
    raw = path.read_bytes()
    assert len(raw) % 20 == 0
    for i in range(0, len(raw), 20):
        yield raw[i : i + 20]


def run_replay(log: Path, out: Path) -> dict:
    res = subprocess.run([str(REPLAY), str(log), str(out)], capture_output=True, text=True, check=True)
    return json.loads(res.stdout)


@pytest.fixture(scope="module", autouse=True)
def build_replay():
    if not REPLAY.exists():
        subprocess.run(["make", "-C", str(ROOT), "build/replay"], check=True)


def test_dbc_and_c_decoder_agree(tmp_path):
    """Une trame encodée par cantools à partir du DBC doit ressortir identique du boîtier."""
    bms = DB.get_message_by_name("BMS_Pack")
    vcu = DB.get_message_by_name("VCU_Speed")
    phys = {"PackVoltage": 356.78, "PackCurrent": -123.4, "StateOfCharge": 74.15, "CellTempMax": 31.2}
    b = bms.encode(phys).hex().upper()
    v = vcu.encode({"VehicleSpeed": 87.4, "GearState": 3}).hex().upper()
    lines = []
    for k in range(5):  # 0,5 s de trafic
        t = 1000.0 + 0.1 * k
        lines.append(f"({t:.6f}) can0 {bms.frame_id:03X}#{b}")
        lines.append(f"({t + 0.01:.6f}) can0 {vcu.frame_id:03X}#{v}")
    log = tmp_path / "mini.log"
    log.write_text("\n".join(lines) + "\n")
    out = tmp_path / "p.bin"
    run_replay(log, out)
    last = [p for p in packets(out) if p[1] == 1][-1]
    speed, volt, cur, soc, temp = struct.unpack_from("<HHhHh", last, 8)
    assert speed / 100 == pytest.approx(87.4)
    assert volt / 100 == pytest.approx(356.78)
    assert cur / 10 == pytest.approx(-123.4)
    assert soc / 100 == pytest.approx(74.15)
    assert temp / 10 == pytest.approx(31.2)
    assert last[3] == 3


def test_every_packet_has_valid_crc():
    p = ROOT / "android" / "app" / "src" / "main" / "assets" / "demo_drive.bin"
    n = 0
    for pkt in packets(p):
        assert pkt[0] == 0xA5
        assert crc16(pkt[:18]) == struct.unpack_from("<H", pkt, 18)[0]
        n += 1
    assert n == 14402


@pytest.mark.skipif(not (ROOT / "data" / "drive_cycle.log").exists(), reason="lancer make data")
def test_energy_against_simulator_truth(tmp_path):
    truth = json.loads((ROOT / "data" / "truth.json").read_text())
    got = run_replay(ROOT / "data" / "drive_cycle.log", tmp_path / "p.bin")
    net_truth = truth["traction_wh"] - truth["regen_wh"]
    net = got["traction_wh"] - got["regen_wh"]
    assert abs(net - net_truth) / net_truth < 0.01  # consommation nette à moins de 1 %
    assert got["distance_km"] == pytest.approx(truth["distance_km"], abs=0.01)
    assert got["traction_wh"] == pytest.approx(truth["traction_wh"], rel=0.01)
    assert got["regen_wh"] == pytest.approx(truth["regen_wh"], rel=0.10)  # 10 Hz : pics de freinage lissés
