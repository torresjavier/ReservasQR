# Reservas QR · v1.3

App para el ingreso de vehículos con reserva, pensada para el **Sunmi V3H**
(impresora y lector integrados). Funciona en Android 7.0 o superior.

## La operación en DOS MOMENTOS

| | Momento 1 · **VÍA** | Momento 2 · **INTERIOR** |
|---|---|---|
| Dónde | En la entrada, con los carros en movimiento (no se pueden detener) | Ya adentro |
| Qué hace al leer QR o placa | **Solo valida**: vibra, muestra un aviso de 1,5 s y sigue. Sin ticket. | **Registra el ingreso**, toma la foto e **imprime el ticket** «RESERVA INGRESADA» |
| Estado de la reserva | Pendiente → **Validada** | Validada → **Ingresada** |

Qué hace cada lector se configura en **Ajustes (protegido con PIN)**:

- **Al leer el QR: solo validar** (sí / no)
- **Al leer la placa: solo validar** (sí / no)
- **Al validar: imprimir un comprobante corto** (sí / no)
- **Al validar: enviar el registro al servidor** (sí / no)
- Atajos: **«Equipo de la VÍA»** y **«Equipo INTERIOR»** dejan todo configurado de una vez.

Los equipos se hablan **a través del servidor**: lo que valida el equipo de la vía llega al de adentro en el siguiente ciclo de sincronización
(cada 1–30 min, a elección). Si un solo equipo hace los dos momentos, no necesita servidor.

### Cómo se evita que se cuelen

Cada intento queda como **alerta** (se ve en el chip rojo «n alertas» y en ⋮ → Alertas, y se envía al servidor con la foto):

| Alerta | Cuándo |
|---|---|
| Sin reserva (vía) | La placa o el QR no está en el listado y quiere pasar |
| Repetida en la vía | El mismo QR o placa se vuelve a validar después de 20 s (QR copiado, mismo vehículo dos veces) |
| Ingresó sin pasar por la vía | Adentro se lee una reserva que nunca fue validada |
| Bloqueado: no pasó por la vía | Con **«Adentro: no dar ingreso a quien no pasó por la vía»** activo, el ingreso se niega (el administrador puede forzarlo) |
| Vehículo adentro sin reserva | Adentro se lee una placa que no está en el listado |
| Tipo no coincide | La cámara ve una placa de **moto** y la reserva es de **carro**, o al revés |
| Validado y sin ingresar | La pestaña **Validados** muestra, en rojo, a quien lleva más de N minutos esperando (Ajustes) |

Pestañas de la lista: **Pendientes · Validados · Ingresados**.

## PIN de configuración

Al abrir **Ajustes** por primera vez se crea un **PIN de 4 a 8 números**. Desde ahí se pide para:
Ajustes, cargar un listado nuevo, anular ingresos o validaciones, borrar ingresos y borrar alertas.
Después de escribirlo vale 2 minutos. Con 5 intentos fallidos se bloquea 1 minuto.
Si se olvida: **«Olvidé el PIN»** pide la clave de desbloqueo del equipo y deja crear uno nuevo.
(El PIN es independiente de la clave del equipo, que sigue siendo necesaria para abrir la app.)

## Pantalla principal

- **Arriba:** foto y nombre del operador, **modo del equipo** (VÍA / INTERIOR), alertas, estado de impresora, lector y envíos,
  contador y **buscador de placa**.
- **Centro:** lista de Pendientes, Validados o Ingresados.
- **Abajo, accesos rápidos:** **Escanear QR**, **Leer placa** (rojo) y **Buscar placa**.
- **Menú ⋮:** cargar Excel, Bluetooth, traer reservas, enviar ahora, alertas, columnas, exportar, ticket de prueba, ajustes y borrar ingresos.

Colores: negro, rojo y blanco.

## Leer placas con la cámara

Pensada para carros y motos de Colombia, **placas amarillas o blancas**:

1. **Detector de placa:** primero un detector liviano busca una zona con **color de placa (amarillo o blanco), trazos de caracteres y forma de placa**.
   Si no hay placa a la vista, **no se usa el OCR** (no se gasta batería leyendo letreros, paredes o carrocerías).
2. **Enfoque:** al encontrar la placa, un recuadro rojo la sigue, la cámara **enfoca y mide la luz sobre ella** y, si está lejos y al centro, acerca un poco.
3. **OCR solo del recorte:** se lee únicamente la placa, ampliada. Esto evita confusiones con otros textos de la escena.
4. **Carro o moto:** ABC123 (carro, 1 renglón), ABC12D y ABC12 (moto, **2 renglones**: letras arriba y números abajo).
   La forma de la placa ayuda a decidir si el último carácter es letra o número.
5. **Confusiones del OCR:** O/Q/D/0, I/L/1, B/8, S/5, Z/2, G/6, A/4 y T/7 se corrigen según la posición de cada carácter
   y la búsqueda en el listado las tolera (índice instantáneo aunque haya miles de placas).
   Una lectura exacta se acepta en un cuadro; si hubo que corregir un carácter, se confirma con un segundo cuadro.
6. **Modo ahorro:** sin placa por **20 segundos** casi no procesa cuadros, baja el brillo y se despierta sola cuando aparece una placa
   (o al tocar la pantalla). Tocar la cámara en uso fuerza una lectura completa de 3 s si el detector no encuentra la placa.
7. Una zona que parece placa pero nunca se lee (por ejemplo un letrero amarillo) se ignora unos segundos.

Si la placa es de VIP, el aviso lleva borde rojo. Durante 12 segundos no se vuelve a procesar la misma placa.

## Servidor (envío y descarga)

En **Ajustes → Servidor** se escriben dos direcciones y una clave, y se elige:

- **Enviar cuando se junten 5, 10 o 20 registros** (también es el máximo por envío).
- **Enviar y traer reservas cada 1, 2, 5, 10, 15 o 30 minutos.**

El envío usa internet **solo cuando hay algo que enviar**, con las restricciones de red de Android (sin sondeos constantes).
**Si falla, el registro se conserva con el mismo id** (`id_evento`) y se reintenta en el siguiente ciclo, al volver el internet,
o con la tarea de respaldo de 15 minutos aunque la app esté cerrada. El servidor confirma devolviendo esos ids.

Cada registro lleva su `id_evento` y el `id_reserva` que el servidor le dio a la reserva, para poder actualizarla.
Detalle de los formatos JSON y un servidor de ejemplo listo para usar: carpeta `servidor/` (`API.md` y `server.js`).

## Seguridad

La app pide la **clave de desbloqueo del equipo** (PIN, patrón o contraseña) al abrirla,
y otra vez si estuvo más de 30 segundos en segundo plano. Si el equipo no tiene clave, la app pide configurarla.

## Columnas del Excel

`NOMBRE`, `CODIGO_RESERVA`, `PLACA`, `EVENTO`, `TIPO` (VIP o NORMAL).
Al menos debe existir el código o la placa. También reconoce títulos parecidos. Al exportar se agregan `ESTADO`, `HORA_VALIDACION` y `HORA_INGRESO`.

## Lector Sunmi

Si un QR no llega a la app, en el Sunmi abra **Ajustes → Escáner** y active **Salida por broadcast**.

## Compilar

Al subir los cambios a GitHub, la pestaña **Actions** genera el APK y lo publica en **Releases**.
