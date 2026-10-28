# Reservas QR

App Android para controlar el ingreso de reservas en un parqueadero/evento, pensada para los equipos
**Sunmi con impresora** (V2, V2 Pro, P2, T2, D2…). También funciona en cualquier Android 7.0 o superior.

## Qué hace

**Pestaña «Ingresos»**
- **Escanear el QR**: lee el QR que trae el cliente y lo busca en el listado.
  - Verde **RESERVA INGRESADA** (dorado si es VIP): registra la hora e **imprime el ticket** automáticamente.
  - Amarillo **YA INGRESÓ**: ese QR ya se usó (muestra la hora); permite reimprimir.
  - Rojo **NO ESTÁ EN EL LISTADO**: vibra y suena; ofrece buscar la placa a mano.
- Lista de vehículos **adentro** (con placa, nombre, evento, VIP y hora) y de **pendientes**.
- Tocar un vehículo: reimprimir el ticket o anular el ingreso.

**Pestaña «Buscar placa»**
- Escriba la placa (sirve parcial, con o sin guion; también código o nombre).
- Toque el resultado → **Registrar ingreso** → imprime el mismo ticket.

**Ticket impreso**
Encabezado del parqueadero · RESERVA INGRESADA · *** VIP *** (si aplica) · placa grande ·
nombre · código de reserva · evento · tipo · fecha y hora · mensaje de bienvenida · QR de la reserva.
El encabezado, el mensaje de bienvenida y el mensaje VIP se cambian en **Ajustes** (rueda arriba a la derecha).
En el mensaje puede usar `{nombre}` y `{evento}`.

**Listado**
- **Cargar Excel**: desde los archivos del equipo.
- **Bluetooth**: enciende el Bluetooth y deja el equipo visible 5 minutos. Desde el otro teléfono:
  abrir el Excel → Compartir → Bluetooth → elegir el Sunmi. Al recibirlo, toque la notificación
  «Archivo recibido» para abrirlo con Reservas QR, o use «Abrir recibido».
- **Columnas**: muestra qué columnas debe tener el Excel y cuáles detectó; se pueden corregir tocándolas.
  Desde ahí puede **compartir la plantilla** de Excel.
- **Exportar**: Excel con el listado + ESTADO (INGRESADO/PENDIENTE) + HORA_INGRESO, filas ingresadas en verde.

Todo queda guardado en el equipo aunque se cierre la app.

## Columnas del Excel

Primera fila con los títulos (el orden no importa); debajo, una reserva por fila:

| NOMBRE | CODIGO_RESERVA | PLACA | EVENTO | TIPO |
|---|---|---|---|---|
| Ana Gómez | R-1001 | ABC123 | Concierto | VIP |
| Carlos Ruiz | R-1002 | XYZ789 | Concierto | NORMAL |

- Obligatorio: **CODIGO_RESERVA** o **PLACA** (al menos una). El QR del cliente debe contener el código
  de la reserva (o la placa).
- También reconoce títulos parecidos: «Nombre del cliente», «Código de reserva», «Placa vehículo»,
  «Nombre del evento», «Tipo de cliente», «Categoría»…
- **TIPO**: escriba VIP o NORMAL.
- Columnas adicionales (teléfono, puesto, etc.) se muestran al registrar.
- Formatos: .xlsx o .csv. Si tiene un .xls antiguo, guárdelo como .xlsx.

## Cómo obtener el APK con GitHub (gratis)

1. Entre a https://github.com con su cuenta.
2. **+** (arriba a la derecha) → **New repository** → nombre `ReservasQR` → **Create repository**.
   (Use un repositorio nuevo; no lo mezcle con el del Verificador QR anterior.)
3. Toque **uploading an existing file**. Descomprima el zip y arrastre **todo lo que hay dentro de la
   carpeta `ReservasQR`** (incluida la carpeta `.github`) → **Commit changes**.
   - En Mac, para ver la carpeta oculta `.github` presione Cmd + Shift + punto.
   - Si el navegador no deja subir carpetas, cree el archivo a mano: **Add file → Create new file**,
     nombre `.github/workflows/compilar-apk.yml` y pegue el contenido de ese archivo.
4. Pestaña **Actions** → «Compilar APK» (tarda 4–7 minutos).
5. Cuando salga ✓ verde: en la página principal del repositorio, columna derecha **Releases** →
   descargue **ReservasQR.apk** desde el Sunmi (o páselo por cable/Bluetooth).
6. Abra el APK y permita «Instalar apps de origen desconocido».

Si aparece una ✗ roja en Actions, abra el error, copie el texto y envíelo para corregirlo.

## Primera prueba en el Sunmi

1. Rueda de **Ajustes** → escriba su nombre y el encabezado → **Imprimir ticket de prueba**.
2. **Columnas → Compartir plantilla** (o use el Excel propio) y cárguelo.
3. Escanee un QR que contenga, por ejemplo, `R-1001`.
