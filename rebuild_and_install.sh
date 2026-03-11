#!/bin/bash

echo "Building APK..."
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/nikeshkrishnan/AndroidStudioProjects/anonychat

./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo "Build successful!"
    echo "Installing APK..."
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    
    if [ $? -eq 0 ]; then
        echo "Installation successful!"
        echo "Launching app..."
        adb shell am start -n com.example.anonychat/.MainActivity
    else
        echo "Installation failed!"
    fi
else
    echo "Build failed!"
fi

# Made with Bob
