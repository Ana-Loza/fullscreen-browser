# Fullscreen Browser

App Android que abre cualquier página web a pantalla completa, sin la barra de Chrome ni las barras del sistema. Pensada para tablets en modo kiosko con un editor web.

## Qué hace

- Muestra la página en un WebView a pantalla completa (modo inmersivo) y mantiene la pantalla encendida.
- La primera vez pide la dirección (URL) de la página de inicio y la recuerda.
- Desde Chrome: **Compartir → Fullscreen Browser** abre esa página a pantalla completa. También aparece como opción al abrir enlaces.
- **Menú oculto:** 3 toques rápidos en la esquina superior derecha, o el botón Atrás cuando no hay más páginas anteriores. Permite cambiar la página de inicio, usar la actual como inicio, recargar, ir atrás y cerrar la app.
- Si no hay internet, muestra un aviso y reintenta cada 10 segundos.
- Soporta subir archivos, videos a pantalla completa, cookies y almacenamiento local (para que el login del editor se mantenga).

## Cómo instalar la app

1. Ve a la pestaña **Actions** de este repositorio y abre el build más reciente que tenga el check verde ✅.
2. Baja hasta **Artifacts** y descarga el archivo. Se descarga como un `.zip`.
3. Descomprime el `.zip` y pasa el archivo `.apk` a tu tablet (por cable, Drive o correo).
4. En la tablet, abre el `.apk` y toca **Instalar**. Si Android te lo pide, permite instalar apps de origen desconocido.
5. Si ya tenías la app, se actualiza encima sin perder nada.

## Instalar en la tablet

1. Copia el APK a la tablet (cable, Drive, etc.) y ábrelo.
2. Acepta "instalar apps de origen desconocido" si Android lo pide.
3. Abre **Fullscreen Browser** y escribe la URL del editor.

Nota: el APK va firmado con la clave de depuración, sirve para instalar directo pero no para Play Store.

## Siguientes pasos posibles

- PIN para el menú oculto.
- Fijar pantalla (screen pinning) o modo kiosko con Device Owner para que no se pueda salir.
- Arrancar sola al encender la tablet.
