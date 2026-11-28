@echo off
chcp 65001 >nul
title ZarkTV - Configurar box
echo.
echo  ===============================================
echo    ZarkTV Launcher - configurar box (uma vez so)
echo  ===============================================
echo.
echo  Antes de comecar:
echo   1. Box resetada (sem conta Google cadastrada).
echo   2. Box e PC na mesma rede (Wi-Fi ou cabo).
echo   3. Depuracao ADB ligada na box (nas MXQ costuma vir ligada).
echo   4. O arquivo adb.exe nesta pasta (pacote "platform-tools" do Android).
echo.
if not exist "%~dp0adb.exe" (
  echo  [ERRO] Nao achei o adb.exe nesta pasta.
  echo  Baixe o "SDK Platform-Tools for Windows" no site do Android,
  echo  e copie adb.exe, AdbWinApi.dll e AdbWinUsbApi.dll para esta pasta.
  pause
  exit /b 1
)
set ADB="%~dp0adb.exe"
set /p IP= Digite o IP da box (aparece em Configuracoes ^> Rede da box): 
echo.
echo  ==^> Conectando na box %IP% ...
%ADB% connect %IP%:5555
%ADB% -s %IP%:5555 wait-for-device
echo.
echo  ==^> Informacoes da box:
for /f "delims=" %%a in ('%ADB% -s %IP%:5555 shell getprop ro.build.version.release') do set ANDROID=%%a
for /f "delims=" %%a in ('%ADB% -s %IP%:5555 shell getprop ro.product.model') do set MODELO=%%a
echo     Modelo: %MODELO%
echo     Android: %ANDROID%
echo.
echo  ==^> Baixando a ZarkTV Launcher mais nova...
powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing 'https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/launcher/zarktv-launcher.apk' -OutFile '%~dp0zarktv-launcher.apk'"
if not exist "%~dp0zarktv-launcher.apk" (
  echo  [ERRO] Nao consegui baixar a launcher. Confira a internet do PC.
  pause
  exit /b 1
)
echo.
echo  ==^> Instalando a launcher na box...
%ADB% -s %IP%:5555 install -r "%~dp0zarktv-launcher.apk"
echo.
echo  ==^> Tornando a launcher a "dona" da box (Android 5 ou mais novo)...
%ADB% -s %IP%:5555 shell dpm set-device-owner cfd.zarktvqx.launcher/.Admin
if errorlevel 1 (
  echo.
  echo  [AVISO] A box nao aceitou a launcher como dona.
  echo  Motivos comuns: Android 4.4 ^(nao tem esse recurso^), ou ja tem conta cadastrada.
  echo  A launcher funciona mesmo assim: modo root se a box tiver root, senao modo simples.
)
echo.
echo  ==^> Deixando a launcher como tela inicial...
%ADB% -s %IP%:5555 shell cmd package set-home-activity cfd.zarktvqx.launcher/.HomeActivity >nul 2>&1
%ADB% -s %IP%:5555 shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
echo.
echo  ===============================================
echo   Pronto! Em alguns segundos a box aparece no
echo   painel: https://painel.zarktvqx.cfd/boxes/admin
echo   Se a box for sua, abra ela no painel e marque "minha".
echo  ===============================================
pause
