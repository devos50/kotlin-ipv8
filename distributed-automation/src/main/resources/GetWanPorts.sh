#!/bin/sh
DEVICES=$(adb devices | grep -v devices | grep device | cut -f 1)
for device in $DEVICES; do
	adb -s "$device" root
	adb -s "$device" pull /data/user/0/nl.tudelft.trustchain/files/wanPort "$HOME/Downloads/wanPorts/$device"
done
