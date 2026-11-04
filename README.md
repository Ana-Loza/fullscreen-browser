# Fullscreen Browser

App Android que abre cualquier página web a pantalla completa, sin la barra de Chrome ni las barras del sistema. Pensada para tablets en modo kiosko con un editor web.

## Qué hace

- Muestra la página en un WebView a pantalla completa (modo inmersivo) y mantiene la pantalla encendida.
- La primera vez pide la dirección (URL) de la página de inicio y la recuerda.
- Desde Chrome: **Compartir → Fullscreen Browser** abre esa página a pantalla completa. También aparece como opción al abrir enlaces.
- **Menú oculto:** 3 toques rápidos en la esquina superior derecha, o el botón Atrás cuando no hay más páginas anteriores. Permite cambiar la página de inicio, usar la actual como inicio, recargar, ir atrás y cerrar la app.
- Si no hay internet, muestra un aviso y reintenta cada 10 segundos.
- Soporta subir archivos, videos a pantalla completa, cookies y almacenamiento local (para que el login del editor se mantenga).

## Cómo obtener el APK

**Opción A, GitHub (sin instalar nada):** sube esta carpeta a un repositorio de GitHub. La acción *Build APK* compila sola en cada push; descarga el APK desde la pestaña **Actions → último build → Artifacts**.

**Opción B, Android Studio:** abre esta carpeta con Android Studio y usa *Build → Build APK(s)*, o en terminal `./gradlew assembleRelease`. El APK queda en `app/build/outputs/apk/release/`.

## Instalar en la tablet

1. Copia el APK a la tablet (cable, Drive, etc.) y ábrelo.
2. Acepta "instalar apps de origen desconocido" si Android lo pide.
3. Abre **Fullscreen Browser** y escribe la URL del editor.

Nota: el APK va firmado con la clave de depuración, sirve para instalar directo pero no para Play Store.

## Siguientes pasos posibles

- PIN para el menú oculto.
- Fijar pantalla (screen pinning) o modo kiosko con Device Owner para que no se pueda salir.
- Arrancar sola al encender la tablet.
