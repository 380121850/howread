#!/usr/bin/env bash

#git reset --hard
#git pull

./fonts.sh

#/usr/libexec/java_home -V
if [ "$(uname)" == "Darwin" ]; then
  export JAVA_HOME=`/usr/libexec/java_home -v 24`
else
  export JAVA_HOME=/home/dev/.local/share/JetBrains/Toolbox/apps/android-studio/jbr
fi
####################################

./link_to_mupdf_1.23.7.sh

cd ../

./gradlew clean incVersion

./gradlew assembleProRelease
./gradlew assembleFdroidRelease

./gradlew copyApks -Prelease
./gradlew -stop

rm /home/dev/Dropbox/FREE_PDF_APK/testing/*.apk
rm /Users/ivanivanenko/Library/CloudStorage/Dropbox/FREE_PDF_APK/testing/*.apk

cd Builder
./remove_all.sh
