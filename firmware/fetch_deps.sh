#!/usr/bin/env bash
# Récupère les sources officielles STMicroelectronics (licence BSD-3) à des versions figées.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p third_party
fetch() {  # dépôt, commit
  local dir="third_party/$1"
  if [ ! -d "$dir/.git" ]; then
    git clone --quiet --filter=blob:none "https://github.com/STMicroelectronics/$1.git" "$dir"
  fi
  git -C "$dir" checkout --quiet "$2"
  echo "ok  $1 @ ${2:0:10}"
}
fetch stm32f4xx_hal_driver 6ef30cf0ef6cc29ad518f37b9e6de89b6b815f90
fetch cmsis_device_f4      8c79dcf2f1d866d139a5d66240083ae6194b6dd1
fetch cmsis_core           01d18147527e30ed69ace1cb03b818de78bc7062
