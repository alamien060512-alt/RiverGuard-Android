@echo off setlocal cd /d "%~dp0" where node >nul 2>&1 || (echo Node.js 20+ not found. Install from https://nodejs.org & exit /b 1) where java >nul 2>&1 || (echo JDK 21 not found. Install Temurin 21 from https://adoptium.net & exit /b 1) if "%ANDROID_HOME%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk" if "%ANDROID_HOME%"=="" (echo Android SDK not found. Install Android Studio, open SDK Manager, install Platform 35 + Build-Tools. & exit /b 1) set "SDKDIR=%ANDROID_HOME:\=/%" echo sdk.dir=%SDKDIR%> android\local.properties call npm ci || exit /b 1 cal
@echo off
setlocal

cd /d "%~dp0"

:: Check Node.js
where node >nul 2>&1
if errorlevel 1 (
    echo Node.js 20+ not found.
    echo Install from https://nodejs.org
    exit /b 1
)

:: Check Java
where java >nul 2>&1
if errorlevel 1 (
    echo JDK 21 not found.
    echo Install Temurin 21 from https://adoptium.net
    exit /b 1
)

:: Detect Android SDK
if "%ANDROID_HOME%"=="" (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    )
)

if "%ANDROID_HOME%"=="" (
    echo Android SDK not found.
    echo Install Android Studio and use SDK Manager to install:
    echo Android SDK Platform 35 and Android SDK Build-Tools.
    exit /b 1
)

:: Configure Android SDK path
set "SDKDIR=%ANDROID_HOME:\=/%"
echo sdk.dir=%SDKDIR%> android\local.properties

:: Install npm dependencies
echo.
echo [1/4] Installing npm dependencies...
call npm ci
if errorlevel 1 exit /b 1

:: Synchronize Capacitor
echo.
echo [2/4] Syncing Capacitor with Android...
call npx cap sync android
if errorlevel 1 exit /b 1

:: Build Android APK
echo.
echo [3/4] Building debug APK...
cd android
call gradlew.bat assembleDebug
if errorlevel 1 exit /b 1

:: Display output location
echo.
echo [4/4] Build completed successfully!
echo.
echo APK:
echo %~dp0android\app\build\outputs\apk\debug\app-debug.apk
echo.

endlocal
l npx cap sync android || exit /b 1 cd android call gradlew.bat assembleDebug || exit /b 1 echo. echo APK: %~dp0android\app\build\outputs\apk\debug\app-debug.apk
