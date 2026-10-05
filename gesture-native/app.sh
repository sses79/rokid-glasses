#!/bin/sh
# Control the app on the connected glasses without rebuilding.
#   ./app.sh start | stop | restart | log
PKG=dev.tim.gesturelab
case "$1" in
  start)   adb shell am start -n $PKG/.MainActivity ;;
  stop)    adb shell am force-stop $PKG ;;
  restart) adb shell am start -S -n $PKG/.MainActivity ;;
  log)     adb logcat -s GestureLab ;;
  *)       echo "usage: $0 start|stop|restart|log"; exit 1 ;;
esac
