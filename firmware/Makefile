# Firmware du boîtier d'acquisition : NUCLEO-F446RE + transceiver CAN + module BLE HM-10.
#   make deps   récupère HAL et CMSIS de STMicroelectronics (versions figées)
#   make        build/evtc_box.bin et .hex (glisser le .bin sur le lecteur NODE_F446RE)

PREFIX  ?= arm-none-eabi-
CC      := $(PREFIX)gcc
OBJCOPY := $(PREFIX)objcopy
SIZE    := $(PREFIX)size
TP      := third_party
BUILD   := build

CPU     := -mcpu=cortex-m4 -mthumb -mfpu=fpv4-sp-d16 -mfloat-abi=hard
CFLAGS  := $(CPU) -std=c99 -Os -g -Wall -Wextra -ffunction-sections -fdata-sections -fsingle-precision-constant \
           -DSTM32F446xx -DUSE_HAL_DRIVER -I../core -Iinc -I$(TP)/stm32f4xx_hal_driver/Inc \
           -I$(TP)/cmsis_device_f4/Include -I$(TP)/cmsis_core/CMSIS/Core/Include
LDFLAGS := $(CPU) -Wl,--gc-sections -Wl,--print-memory-usage -T STM32F446RETx_FLASH.ld \
           -Wl,--defsym=FLASH_SIZE=512K -Wl,--defsym=RAM_SIZE=128K --specs=nano.specs --specs=nosys.specs -lm

HAL     := stm32f4xx_hal stm32f4xx_hal_cortex stm32f4xx_hal_rcc stm32f4xx_hal_rcc_ex stm32f4xx_hal_gpio \
           stm32f4xx_hal_can stm32f4xx_hal_uart stm32f4xx_hal_dma stm32f4xx_hal_pwr stm32f4xx_hal_pwr_ex
SRC     := src/main.c ../core/evtc.c $(TP)/cmsis_device_f4/Source/Templates/system_stm32f4xx.c \
           $(addprefix $(TP)/stm32f4xx_hal_driver/Src/,$(addsuffix .c,$(HAL)))
ASM     := $(TP)/cmsis_device_f4/Source/Templates/gcc/startup_stm32f446xx.s

.PHONY: all deps clean
all: $(BUILD)/evtc_box.bin

deps:
	bash fetch_deps.sh

$(BUILD)/evtc_box.elf: $(SRC) $(ASM) ../core/evtc.h | $(BUILD)
	$(CC) $(CFLAGS) $(SRC) $(ASM) $(LDFLAGS) -Wl,-Map=$(BUILD)/evtc_box.map -o $@
	$(SIZE) $@

$(BUILD)/evtc_box.bin: $(BUILD)/evtc_box.elf
	$(OBJCOPY) -O binary $< $@
	$(OBJCOPY) -O ihex $< $(BUILD)/evtc_box.hex

$(BUILD):
	mkdir -p $@

clean:
	rm -rf $(BUILD)
