#!/bin/bash
cd "$(dirname "$0")/.."

JAVA_OPTS="-Xms512m -Xmx2g"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Duser.timezone=Asia/Shanghai"
JAVA_OPTS="$JAVA_OPTS -Dskiko.renderApi=SOFTWARE"
JAVA_OPTS="$JAVA_OPTS -Dskiko.hardwareAcceleration=false"

java $JAVA_OPTS -jar lib/dynamic-bot-1.6.jar