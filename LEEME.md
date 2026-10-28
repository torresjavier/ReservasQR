# Reservas QR · v1.2

App para el ingreso de vehículos con reserva, pensada para el **Sunmi V3H**
(impresora y lector integrados). Funciona en Android 7.0 o superior.

## Pantalla principal

- **Arriba:** foto y nombre del operador. Si toca la foto puede cambiarla.
  También está el estado de la impresora, del lector y de los envíos, el contador de ingresados y el **buscador de placa**.
- **En el centro:** lista de **Pendientes** (por defecto) o **Ingresados**. Al escribir en el buscador se muestran las coincidencias.
- **Abajo, accesos rápidos:**
  - **Escanear QR**: enciende el lector del Sunmi. Si deja el dedo presionado, usa la cámara.
  - **Leer placa** (botón rojo): cámara en modo continuo.
  - **Buscar placa**: abre el teclado en el buscador.
- **Menú ⋮:** cargar Excel, recibir por Bluetooth, traer reservas del servidor, enviar registros,
  columnas y plantilla, exportar, ticket de prueba, ajustes y borrar ingresos.

Colores: negro, rojo y blanco.

## Leer placas con la cámara (modo continuo)

Pensado para revisar muchos carros seguidos (por ejemplo 1000):

1. Toque **Leer placa** y apunte a cada placa.
2. Cuando la placa está en el listado, la app **vibra, toma la foto, registra el ingreso e imprime el ticket**.
   Luego sigue con el siguiente carro sin salir de la cámara.
3. Un aviso grande muestra el resultado:
   - blanco: **Ingresada**
   - borde rojo: **VIP**
   - negro: **Ya ingresó**
   - rojo: **Sin reserva**
4. Durante 12 segundos no se vuelve a procesar la misma placa, para no registrar dos veces el mismo carro.

La búsqueda es instantánea aunque haya miles de reservas. Las placas del listado se guardan en un índice que ya
tiene en cuenta las confusiones del OCR: **O/Q/D/0, I/L/1, B/8, S/5, Z/2, G/6, A/4 y T/7**.
Una lectura exacta se acepta en el primer cuadro; si hubo que corregir algún carácter, se confirma con un segundo cuadro.
Formatos colombianos: ABC123 (carro), ABC12D y ABC12 (moto).

## Servidor (envío y descarga)

En **Ajustes → Servidor** se escriben dos direcciones y una clave:

- **Enviar registros (POST):** los ingresos y las anulaciones, con foto, en JSON.
  Se envían en segundo plano y **solo con internet**: cuando se juntan N registros (de 1 a 10),
  o de inmediato al llegar a 10, y además cada 15 minutos.
  Si no hay internet, se guardan y se envían al volver la conexión.
- **Traer reservas (GET):** agrega reservas nuevas o actualiza las existentes sin subir Excel.
  Se hace cada 15 minutos con internet, o desde el menú. El Excel sigue funcionando.

Detalle de los formatos JSON y un servidor de ejemplo listo para usar: carpeta `servidor/` (`API.md` y `server.js`).

## Seguridad

La app pide la **clave de desbloqueo del equipo** (PIN, patrón o contraseña) al abrirla,
y otra vez si estuvo más de 30 segundos en segundo plano.
Si el equipo no tiene clave, la app pide configurarla.

## Columnas del Excel

`NOMBRE`, `CODIGO_RESERVA`, `PLACA`, `EVENTO`, `TIPO` (VIP o NORMAL).
Al menos debe existir el código o la placa. También reconoce títulos parecidos.

## Lector Sunmi

Si un QR no llega a la app, en el Sunmi abra **Ajustes → Escáner** y active **Salida por broadcast**.

## Compilar

Al subir los cambios a GitHub, la pestaña **Actions** genera el APK y lo publica en **Releases**.
