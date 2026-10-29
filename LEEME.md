# Reservas QR · v1.5

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

Pensada para carros y motos de Colombia, **placas amarillas o blancas**, y para el vehículo **en movimiento** en la vía.

### Motor de IA (nuevo en v1.5)

La lectura principal ahora usa inteligencia artificial que corre **dentro del equipo, sin internet**:

1. **Detector de placas** (YOLOv9): encuentra dónde está la placa en la imagen, sea de carro o de moto, sin depender del color ni de la forma.
2. **Lector de placas** (fast-plate-ocr): lee la placa recortada y, para cada una de las 10 posiciones, entrega la probabilidad de cada letra o número.
3. **Formato obligatorio.** Carro `AAA000` (3 letras + 3 números) y moto `AAA00A` (3 letras + 2 números + 1 letra).
   En las posiciones 1–3 solo pueden ser letras y en la 4–5 solo números, así que **una G nunca se confunde con 6, ni una O con 0**.
   La última posición decide el tipo: si encaja mejor un número es **CARRO**, si encaja mejor una letra es **MOTO**
   (funciona igual con placas de moto de un renglón o de dos).
4. **Se compara con el listado de reservas.** De todas las placas del listado se elige la que más probabilidad tiene según el modelo.
   Así se corrigen confusiones como Q/O o 8/B usando probabilidades reales y no una tabla fija. Si dos placas del listado se parecen
   y el modelo no puede distinguirlas, la app **espera una lectura más clara** en vez de adivinar.
5. **Varios cuadros se suman.** Con el vehículo en movimiento se promedian las probabilidades de los últimos 2,5 s: pesan más los cuadros nítidos
   y los que el modelo vio con seguridad; un cuadro borroso casi no cuenta.
6. **Respaldo.** Si la IA no ve ninguna placa en un cuadro, de vez en cuando entra el lector de la versión anterior (ML Kit).
   Si la IA no se puede cargar o falla, la app sigue funcionando con ML Kit.

Probado con fotos reales de una moto (LGU06F) y un carro (LQW954): las dos se leyeron perfectas, también con la imagen reducida, oscura,
con ruido o algo borrosa; con listado de 1000 placas no hubo ninguna equivocación y una placa que no estaba en el listado nunca se confundió con otra.
Con mucho movimiento (borroso fuerte) el modelo se equivoca igual que cualquier lector: por eso conviene usar **Movimiento RÁPIDO** y **Calidad ALTA**.

En el botón **Motor** se ve cuánto tarda la IA por cuadro en este equipo (por ejemplo «Motor: IA + ML Kit · 120 ms»).

### Cómo trabaja el respaldo (ML Kit)

1. **Zona fija de lectura.** Hay un recuadro blanco punteado en la pantalla. El operador pone el Sunmi en un punto fijo (o lo apoya) y la placa debe pasar por ese recuadro.
   La cámara **enfoca y mide la luz una sola vez en ese punto** y se queda quieta: ya no «se vuelve loca» buscando foco a cada cuadro.
2. **Lectura de ese recuadro, no de toda la escena.** El OCR lee solo la franja del recuadro, ampliada. Un detector de color/forma de placa ayuda a ubicarla dentro de la franja,
   pero **ya no es obligatorio**: si el detector no la ve (moto, placa sucia, poca luz) igual se lee la zona.
3. **Varias versiones de la misma imagen.** Cuando hay indicio de placa, cada cuadro se prueba alternando: original, contraste + afilado, blanco y negro adaptativo,
   blanco y negro Otsu y afilado horizontal (contra el borroso del movimiento).
4. **Se descartan los cuadros movidos.** Se mide la nitidez del recorte; si está borroso se salta (máximo 3 seguidos) y se espera un cuadro mejor.
5. **Votación entre cuadros.** Durante 2,5 s se juntan todas las lecturas. Cada una pesa más si fue limpia y nítida; la placa final sale carácter por carácter del que más votos tuvo.
   Una lectura exacta que esté en el listado se acepta rápido; una corregida o una de «sin reserva» necesita más acuerdo entre cuadros.
6. **Carro o moto.** Placa de **2 renglones = MOTO** (ABC12D / ABC12); de 1 renglón = CARRO (ABC123). Se muestra en pantalla y en los avisos.
   Si el tipo visto no coincide con el de la reserva, sale la alerta «Tipo no coincide».
7. **Confusiones del OCR:** O/Q/D/0, I/L/1, B/8, S/5, Z/2, G/6, A/4 y T/7 se corrigen según la posición de cada carácter y la búsqueda en el listado las tolera.
8. **Miniatura.** Abajo a la izquierda se ve lo que está leyendo el OCR (recorte ya procesado). El recuadro rojo marca la placa encontrada.

### Opciones (botones arriba de la cámara, quedan guardadas)

| Botón | Valores | Para qué sirve |
|---|---|---|
| **Motor** | IA + ML Kit · solo IA · solo ML Kit | IA + ML Kit es lo normal. «Solo IA» no usa el respaldo (más liviano). «Solo ML Kit» vuelve a la lectura de la versión 1.4. |
| **Lectura** | AUTO · ZONA FIJA · TODA LA IMAGEN | Con la IA, AUTO busca la placa en todo el ancho y la parte central de la imagen, ZONA FIJA solo dentro del recuadro y TODA LA IMAGEN en todo el cuadro. Con ML Kit, AUTO usa el detector por color y, si no encuentra, la zona fija. ZONA FIJA lee siempre el recuadro (la más estable para la vía). TODA LA IMAGEN para placas muy cerca o fuera del recuadro. |
| **Zoom** | 1x · 1,5x · 2x · 3x | Acerca la cámara. Úselo si la placa se ve pequeña en el recuadro. |
| **Foco** | AUTO · fijo a 70 cm · 1,2 m · 2 m · 4 m | AUTO enfoca una vez en el recuadro. Con una distancia fija el lente no se mueve: elija la distancia a la que pasan las placas. Si el equipo no lo permite, queda en AUTO. |
| **Movimiento** | NORMAL · RÁPIDO · MANUAL | RÁPIDO pide más cuadros por segundo y modo deporte (si el equipo lo tiene). MANUAL fija una exposición corta (menos borroso) y ajusta sola ISO/tiempo según la luz. |
| **Calidad** | NORMAL 720p · ALTA 1080p | ALTA da más detalle a la placa; gasta más batería. |

**Recomendación para empezar en la vía:** Lectura = ZONA FIJA, Zoom 1,5× o 2×, Foco = la distancia real, Movimiento = RÁPIDO (si hay borroso, MANUAL), Calidad = ALTA.
En el interior (vehículo parado): todo en AUTO/NORMAL basta.

### Ahorro de batería

- Sin placa por **20 segundos**: casi no procesa cuadros, baja el brillo y se despierta sola cuando aparece una placa (o al tocar la pantalla).
- Tocar la cámara en uso **fuerza una lectura de toda la imagen por 3 s** y vuelve a enfocar.
- Una zona que parece placa pero nunca se lee (un letrero amarillo, por ejemplo) se ignora unos segundos.

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

## Componentes de terceros

El detector y el lector de placas son modelos de código abierto (licencia MIT) de los proyectos
[open-image-models](https://github.com/ankandrew/open-image-models) y [fast-plate-ocr](https://github.com/ankandrew/fast-plate-ocr),
y se ejecutan con [ONNX Runtime](https://github.com/microsoft/onnxruntime) de Microsoft (MIT). Los modelos pesan unos 11 MB y están en
`app/src/main/assets/placas/`. Los avisos de licencia están en `LICENCIAS_TERCEROS.txt`.

## Compilar

Al subir los cambios a GitHub, la pestaña **Actions** genera el APK y lo publica en **Releases**.
