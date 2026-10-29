# Reservas QR · v1.8

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

Los equipos se hablan **directamente entre sí por el Wi-Fi** (ver «Red entre equipos cercanos»): lo que valida el equipo de la vía aparece al instante
en el de adentro, sin internet. Además, todo se envía al servidor (si lo hay) en el siguiente ciclo de sincronización (cada 1–30 min, a elección).
Si un solo equipo hace los dos momentos, no necesita servidor ni red.

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

- **Arriba:** foto y nombre del operador, **modo del equipo** (VÍA / INTERIOR), alertas, estado de impresora, lector, envíos y **equipos cercanos**,
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

## Red entre equipos cercanos (v1.7)

Los Sunmi que están cerca **se avisan entre sí, en segundo plano, lo que leen**: si el equipo de la vía valida un carro, el de adentro lo sabe
en menos de un segundo (y viceversa); si dos equipos de adentro leen el mismo carro, el segundo ve «YA INGRESÓ en INTERIOR 1» y no imprime otro ticket.
Si se anula algo en un equipo, se anula en todos.

**Tecnología elegida: Wi-Fi en la misma red local** (el Wi-Fi de un router, tenga o no internet, o la zona Wi-Fi de uno de los equipos).
Bluetooth y NFC se descartaron: el NFC solo funciona a ~4 cm (hay que acercar un equipo a otro) y el Bluetooth exige emparejar cada par de equipos, alcanza ~10 m y aguanta pocas conexiones a la vez.
Tampoco se usa «Nearby/Google», porque necesita los servicios de Google Play y la app está pensada para funcionar sin ellos.

### Cómo funciona

1. Cada equipo avisa cada 4 s «aquí estoy» por la red local (UDP) y así los demás lo encuentran solos. No hay que emparejar ni escribir nada.
2. Entre cada par de equipos queda una conexión abierta (TCP). Cada validación, ingreso o anulación viaja por ahí al instante, **cifrada** (AES-256-GCM) con la **clave de la red**.
3. Un equipo que recibe un aviso lo reenvía a los demás, así que también llega a los que no alcanzan al equipo que lo originó.
4. Cada 30 s, y al conectarse, los equipos comparan lo que saben y se pasan lo que le falte al otro. Un equipo que estuvo apagado o sin señal **se pone al día solo**.
5. Los avisos de validación e ingreso llevan datos mínimos (código de reserva, placa, hora, nombre del equipo y operador). Desde la v1.8 también viajan, **solo hacia el satélite**, la tabla de reservas y los registros pendientes con su foto (ver «Satélite» más abajo).
6. Lo que llega de otro equipo **no se vuelve a enviar al servidor** como aviso: lo envía el equipo que lo originó, o el satélite si ese equipo no tiene permiso de hablar con el servidor (así no se duplica).

Funciona **en segundo plano**: la app deja un servicio activo con un aviso fijo («Reservas QR · VÍA 1 · 2 equipos conectados»). Android obliga a mostrar ese aviso.

### Cómo usarla

1. Conecte todos los Sunmi **al mismo Wi-Fi**. (No hace falta que ese Wi-Fi tenga internet.)
2. En cada equipo: **Ajustes → Red entre equipos**: ponga un **nombre** distinto a cada uno («VÍA 1», «INTERIOR 1»…). Deje la clave vacía o ponga **la misma en todos**.
3. Abra la app una vez en cada equipo (también después de reiniciar el equipo) y acepte el permiso de notificaciones si lo pide.
4. Mire el indicador de la pantalla principal: **«● 2 equipos conectados»**. Tóquelo para ver la lista (nombre, dirección y si está conectado).
5. En Ajustes, **«Permitir trabajar en segundo plano (batería)»** abre la pantalla de Android donde se elige «Sin restricciones» para esta app; recomendado para que no se duerma con la pantalla apagada.

### Si no se encuentran

- Verifique que **todos tengan la misma clave de red** y estén en el **mismo Wi-Fi**.
- Algunos routers tienen **«aislamiento de clientes / AP isolation»**: impide que los equipos se vean. Desactívelo, o use la zona Wi-Fi de uno de los Sunmi.
- Si los equipos tienen **datos móviles (4G)** encendidos y el Wi-Fi no tiene internet, apáguelos para la prueba (la app intenta usar el Wi-Fi igualmente).
- Como último recurso, en **Ajustes → Red entre equipos → IP de otros equipos**, escriba las direcciones IP de los demás (se ven en «Equipos cercanos» de cada uno). Con IP fija en el router es lo más seguro.
- **Cambie la clave de red** (la predeterminada viene dentro de la app y cualquiera que tenga el APK podría conocerla) y póngala igual en todos: quien no la tenga no puede leer ni enviar avisos.

### Detalles a tener en cuenta

- **Los relojes deben estar en hora** (el Sunmi lo hace solo con internet o red celular). Con diferencias grandes la anulación puede no ganarle a la validación.
- El **día de operación cambia a las 5:00 a. m.**: solo se aplican avisos del día de hoy; los de ayer se ignoran aunque el listado siga cargado.
- Los avisos se conservan 48 horas en cada equipo.
- **«Borrar ingresos»** limpia solo ese equipo y hace que no vuelva a aplicar lo anterior; para empezar de cero un día de pruebas hágalo en **todos** los equipos.
- Si dos equipos validan la misma reserva casi a la vez, cada uno conserva su hora; para los demás cuenta la más temprana.
- Lo que un equipo hace sin ningún otro cerca **queda guardado** y se entrega cuando aparezcan.
- Mientras el servicio está activo el equipo mantiene despiertos el Wi-Fi y el procesador, lo que gasta algo más de batería: lo mejor es tener los equipos cargando. Para apagarlo, desmarque **«Compartir con los equipos cercanos»** en Ajustes.
- Las direcciones manuales deben ser **IP** (por ejemplo `192.168.1.21`), no nombres.

## Satélite: un solo equipo habla con el servidor (v1.8)

Para no saturar el servidor ni gastar datos en todos los equipos, **solo el «satélite» consulta las reservas y sube los registros**.
Los demás equipos **no se comunican con el servidor**: le entregan lo suyo al satélite por el Wi-Fi y de él reciben las reservas.

**Ajustes → Red entre equipos → «Papel de este equipo con el servidor»**:

| Papel | Qué hace |
|---|---|
| **Satélite de la VÍA** | Sube al servidor lo de la vía (validaciones) y trae las reservas para la vía. |
| **Satélite del INTERIOR** | Sube al servidor lo del interior (ingresos, tickets, fotos) y trae las reservas para el interior. |
| **Satélite de VÍA e INTERIOR** | Hace las dos cosas (es lo normal si hay un solo equipo con servidor). **Es el valor de fábrica.** |
| **Normal** | No toca el servidor. Entrega sus registros al satélite y recibe de él las reservas. |

Cómo dejarlo:

1. Elija **un satélite para la vía y uno para el interior** (o uno solo para los dos momentos).
2. En **todos los demás equipos** ponga **«Normal»**. Como de fábrica todos vienen como satélite, **hay que cambiarlo en cada equipo** o seguirán hablando con el servidor.
3. Solo en el satélite hay que escribir las direcciones del servidor y el token.
4. Todos los equipos deben estar en el mismo Wi-Fi que su satélite (ver «Red entre equipos cercanos»).

Qué pasa por detrás:

- **Reservas:** cuando el satélite descarga las reservas del servidor (o carga un Excel), **las empuja a todos los equipos**; no hace falta que ninguno más las consulte. Un equipo «Normal» que se conecta después las pide al satélite.
- **Registros:** un equipo «Normal» deja sus registros (con su foto) en su bandeja y los **entrega al satélite** de ese momento; el satélite los sube al servidor. Si el satélite no está cerca, quedan guardados y se entregan cuando aparezca. Cuando el servidor confirma, el satélite borra la foto.
- **Estados:** lo que el servidor ya sabe (validadas, ingresadas) lo ve el satélite y lo reparte; se marca como «SERVIDOR» y no se cuenta como trabajo de ningún equipo.
- **Contadores:** los equipos de vía validan y **informan cuántas reservas han validado**, para que los de interior sepan qué deben registrar y enviar.

### Equipos conectados (Ajustes → «▸ Equipos conectados»)

Lista desplegable con **cada equipo de la red, por nombre del operario y del equipo**:

- su papel (satélite o normal), su dirección IP y si está conectado ahora;
- **última actualización recibida** y **última enviada** a ese equipo (hora exacta y «hace X»);
- cuántas reservas **validó** y **ingresó** hoy;
- la última vez que **subió** y **consultó** al servidor;
- avisos en rojo si **falta un satélite** para algún momento o si hay **dos satélites** para el mismo momento.

Se refresca sola cada 4 segundos. Tocar el indicador «● n equipos conectados» de la pantalla principal abre una lista rápida.

## Sonido y vibración al leer (v1.8)

- **Lectura buena** (QR o placa): suena **un pitido corto**, para que el operario de la vía sepa que se leyó sin mirar la pantalla.
- **No encontrada, repetida o con algo raro** (ya validada, sin pasar por la vía, tipo distinto): el equipo **vibra** en lugar de sonar.
- Si el volumen de multimedia está en cero, o se apaga el pitido en Ajustes, una lectura buena hace una vibración breve.
- Se activa o desactiva en **Ajustes → «AVISOS AL LEER» → «Sonar un pitido cuando se lee una reserva»**.
- El pitido de la app usa el **volumen de multimedia** del equipo: súbalo en la vía.
- El lector físico del Sunmi puede tener su **propio pitido** (Ajustes de Sunmi → Escáner → Sonido); si se oye doble, desactive uno de los dos.

## Lector Sunmi

Si un QR no llega a la app, en el Sunmi abra **Ajustes → Escáner** y active **Salida por broadcast**.

**Cómo se recibe cada código (v1.6).** El lector puede mandar el código de dos formas a la vez: como un teclado que escribe letra por letra
y como un aviso del sistema (broadcast) con el texto completo. Antes la app procesaba las dos por separado, y la copia por teclado podía quedar
a medias, mezclarse con el cuadro de búsqueda o cerrar la ventana de resultado. Ahora:

- Cada código se procesa **una sola vez y completo**. Si llega el aviso del sistema, ese texto manda y la copia por teclado se descarta; si solo llega por teclado, se espera una fracción de segundo (0,2 s) por si llega el aviso exacto.
- Las teclas del lector **ya no llegan a los botones de la ventana** (el Enter del final cerraba el resultado) ni al cuadro de búsqueda: lo que alcanzó a entrar se borra.
- El cuadro de búsqueda ya no corta a 20 letras: acepta hasta 500 (un QR largo llegaba cortado).
- El aviso rojo («SIN RESERVA», «YA SE VALIDÓ») dura 5 s en vez de 3 s. Se cierra tocándolo.
- Si el código trae letras especiales (ñ, tildes), conviene la salida **por broadcast**: el modo teclado no puede escribirlas.

## Componentes de terceros

El detector y el lector de placas son modelos de código abierto (licencia MIT) de los proyectos
[open-image-models](https://github.com/ankandrew/open-image-models) y [fast-plate-ocr](https://github.com/ankandrew/fast-plate-ocr),
y se ejecutan con [ONNX Runtime](https://github.com/microsoft/onnxruntime) de Microsoft (MIT). Los modelos pesan unos 11 MB y están en
`app/src/main/assets/placas/`. Los avisos de licencia están en `LICENCIAS_TERCEROS.txt`.

## Compilar

Al subir los cambios a GitHub, la pestaña **Actions** genera el APK y lo publica en **Releases**.
