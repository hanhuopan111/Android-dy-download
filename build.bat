@echo off
setlocal
set ROOT=%~dp0
set J=%ROOT%tools\jdk\jdk-17.0.20+8
set SDK=%ROOT%tools\android-sdk
set BT=%SDK%\build-tools\34.0.0
set ANDROID_JAR=%SDK%\platforms\android-34\android.jar
set JAVA_HOME=%J%
set PATH=%J%\bin;%PATH%

REM Build in an ASCII-only temp dir to avoid codepage issues with Chinese paths
set WORK=%TEMP%\apkbuild
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%"
xcopy /e /i /q "%ROOT%src" "%WORK%\src" >nul
xcopy /e /i /q "%ROOT%res" "%WORK%\res" >nul
xcopy /e /i /q "%ROOT%assets" "%WORK%\assets" >nul
copy /y "%ROOT%AndroidManifest.xml" "%WORK%\" >nul
REM aapt (native) cannot handle Chinese paths - copy android.jar to ASCII dir
mkdir "%WORK%\sdk"
copy /y "%ANDROID_JAR%" "%WORK%\sdk\android.jar" >nul
set JAR=%WORK%\sdk\android.jar
cd /d "%WORK%"

echo === [1/5] Compile Java ===
dir /s /b /o:n src\*.java > sources.txt
mkdir out\classes 2>nul
"%J%\bin\javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "%JAR%" -d out\classes @sources.txt
if errorlevel 1 goto :fail

echo === [2/5] D8 dex ===
mkdir out\dex 2>nul
call "%BT%\d8.bat" --lib "%JAR%" --release --min-api 24 --output out\dex out\classes\com\han\wmsave\*.class
if errorlevel 1 goto :fail

echo === [3/5] Package resources ===
mkdir out\apk 2>nul
"%BT%\aapt" package -f -M AndroidManifest.xml -S res -A assets -I "%JAR%" -F out\apk\unsigned.apk
if errorlevel 1 goto :fail

echo === [4/5] Add dex + align ===
cd /d "%WORK%\out\dex"
"%BT%\aapt" add "%WORK%\out\apk\unsigned.apk" classes.dex
"%BT%\zipalign" -f 4 "%WORK%\out\apk\unsigned.apk" "%WORK%\out\apk\aligned.apk"
if errorlevel 1 goto :fail

echo === [5/5] Sign ===
if not exist "%ROOT%key.jks" (
  "%J%\bin\keytool" -genkeypair -keystore "%ROOT%key.jks" -alias wmsave -keyalg RSA -keysize 2048 -validity 36500 -storepass REMOVED -keypass REMOVED -dname "CN=WatermarkSaver, O=han, C=CN"
)
call "%BT%\apksigner" sign --ks "%ROOT%key.jks" --ks-pass pass:REMOVED --key-pass pass:REMOVED --out "%WORK%\out\apk\wmsave.apk" "%WORK%\out\apk\aligned.apk"
if errorlevel 1 goto :fail

mkdir "%ROOT%build" 2>nul
copy /y "%WORK%\out\apk\wmsave.apk" "%ROOT%build\wmsave.apk" >nul
echo.
echo BUILD OK
goto :eof

:fail
echo BUILD FAILED
exit /b 1
