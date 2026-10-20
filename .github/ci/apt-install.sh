#!/bin/sh
# Installs Ubuntu packages on a GitHub runner without letting a slow mirror hold up the job.
#
# The runner fetches from Azure's Ubuntu mirror first, and when that mirror crawls a
# release sits in apt for half an hour. kernel.org's mirror goes first instead, with the
# runner's usual mirrors kept behind it, and every request gives up on a silent
# connection after 20 seconds and retries.
#
# usage: apt-install.sh <package>...
set -eu

if [ -f /etc/apt/apt-mirrors.txt ]; then
    printf '%s\tpriority:%s\n' \
        https://mirrors.edge.kernel.org/ubuntu/ 1 \
        http://azure.archive.ubuntu.com/ubuntu/ 2 \
        https://archive.ubuntu.com/ubuntu/ 3 \
        https://security.ubuntu.com/ubuntu/ 4 | sudo tee /etc/apt/apt-mirrors.txt >/dev/null
fi

# A mirror that stops mid-transfer, or a dpkg lock that is never released, would otherwise hold the
# job until the six-hour cap; ten minutes is several times a normal install.
apt() {
    opts="-o Acquire::Retries=3 -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 -o DPkg::Lock::Timeout=120"
    # shellcheck disable=SC2086
    timeout 600 sudo apt-get $opts "$@"
}
apt update
apt install -y --no-install-recommends "$@"
