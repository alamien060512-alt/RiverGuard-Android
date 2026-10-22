@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>&1 || (echo Node.js 20+ not found. Install from https://nodejs.org & exit /b 1)
where java >nul 2>&1 || (echo JDK 21 not found. Install Temurin 21 from https://adoptium.net & exit /b 1)
if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if "%ANDROID_HOME%"=="" (echo Android SDK not found. Install Android Studio, open SDK Manager, install Platform 35 + Build-Tools. & exit /b 1)
set "SDKDIR=%ANDROID_HOME:\=/%"
echo sdk.dir=%SDKDIR%> android\local.properties
call npm ci || exit /b 1
call npx cap sync android || exit /b 1
cd android
call gradlew.bat assembleDebug || exit /b 1
echo.
echo APK: %~dp0android\app\build\outputs\apk\debug\app-debug.apk
