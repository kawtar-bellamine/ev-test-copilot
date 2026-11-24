# EV Test Copilot : make test lance tous les tests hors Android (C et Python)
CC      ?= cc
CFLAGS  := -std=c99 -O2 -Wall -Wextra -Werror -Icore

.PHONY: all data test firmware android figures clean
all: test

build/replay: tools/replay.c core/evtc.c core/evtc.h
	@mkdir -p build
	$(CC) $(CFLAGS) tools/replay.c core/evtc.c -lm -o $@

build/test_core: tests/test_core.c core/evtc.c core/evtc.h
	@mkdir -p build
	$(CC) $(CFLAGS) tests/test_core.c core/evtc.c -lm -o $@

data/drive_cycle.log: tools/simulate_drive.py
	python tools/simulate_drive.py

data: data/drive_cycle.log

build/packets.bin: build/replay data/drive_cycle.log
	./build/replay data/drive_cycle.log $@

test: build/test_core build/replay data
	./build/test_core
	python -m pytest -q tests

figures: build/replay data
	python tools/plot_energy.py

firmware:
	$(MAKE) -C firmware deps all

android:
	cd android && ./gradlew testDebugUnitTest assembleDebug

clean:
	rm -rf build firmware/build
