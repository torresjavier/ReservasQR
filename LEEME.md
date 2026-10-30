# Reservas QR · v2.3

App para el ingreso de vehículos con reserva, pensada para el **Sunmi V3H**
(impresora y lector integrados). Funciona en Android 7.0 o superior.

## Novedades de la v2.3

### 1. Las fotos de la cámara ahora se ven, y viajan por el Wi-Fi a todos los equipos
Antes la foto que tomaba la cámara al registrar una placa quedaba guardada pero **no se mostraba en ninguna parte**. Ahora:

- **En la lista de reservas** cada fila trae su **miniatura** (la del ingreso, o la de la vía si todavía no ingresa). Un toque la muestra completa.
- **Menú → «Registros con foto»**: una pantalla con todas las validaciones y todos los ingresos, del más reciente al más viejo, con su foto, la placa, el nombre, quién lo registró y la hora. Filtros: *Todos · Con foto · Ingresos · Vía*.
- **Se reparten por el Wi-Fi entre equipos**, sin servidor ni internet: cuando un equipo registra con foto, los demás la reciben y la ven en su lista y en «Registros con foto». Si un equipo estaba apagado o fuera de la red, **las recibe al volver a conectarse** (solo las que le faltan, sin repetir).
- Las fotos que viajan se **reducen** (máx. 800 px) para que sean livianas. Se borran con «Borrar todos los ingresos» / «Fin del evento», igual que los registros.
- Para que las fotos de otros equipos lleguen, **«Red entre equipos» debe estar activa** (Ajustes) y los equipos en el mismo Wi-Fi.

### 2. Estadísticas con gráficas
**Menú → «Estadísticas (gráficas)»** (librería **MPAndroidChart**). Se actualiza sola y cuenta lo de todos los equipos:

- Fichas: reservas, ingresaron (y %), pendientes, **espera media y máxima entre la vía y el ingreso**, ingresos de la última hora.
- **Estado de las reservas** (ingresaron / validadas en la vía sin ingresar / sin llegar).
- **Validaciones e ingresos por hora** del día.
- **Carros y motos** (por la forma de la placa) y **VIP vs normales** que ingresaron.
- **Registros por equipo** (quién registra más), **ingresos por evento** (si el Excel trae varios eventos) y **alertas por motivo**.

### 3. Se quitó la radio

La radio (walkie-talkie) ya no existe en la app: no hay botón «RADIO», ni pantalla de radio, ni sección en Ajustes, ni permiso de micrófono.
La red entre equipos sigue haciendo todo lo demás (reservas, registros, fotos, administradores y receptores). El `server.js` vuelve a ser el de antes de la radio.
Los archivos `Radio.kt`, `RadioActivity.kt`, `RadioUi.kt`, `Voz.java` y `HistorialVoz.java` quedaron vacíos a propósito (para que la actualización no se rompa si
no se borran); se pueden borrar del repositorio.

### 4. Icono de la app (Redparking)
El logo de Redparking no se pudo bajar de la página (el entorno de trabajo no puede entrar a `redparking.co`). **Mientras tanto la app conserva su icono actual.** Cuando se tenga el archivo del logo (PNG, idealmente cuadrado y de 512 px o más), se generan todos los tamaños del icono de la app.

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

**Lo que trae el servidor solo actualiza, nunca reemplaza** (igual que «Cargar Excel → Agregar sin duplicar»): suma las reservas nuevas,
actualiza los datos de las que ya estaban y sube el estado (validada / ingresada) cuando otro equipo ya la había pasado, pero **no borra
ni baja nada de lo ya validado o ingresado** en el equipo. Si el servidor repite una reserva, no se duplica.

Cada registro lleva su `id_evento` y el `id_reserva` que el servidor le dio a la reserva, para poder actualizarla.
Detalle de los formatos JSON y un servidor de ejemplo listo para usar: carpeta `servidor/` (`API.md` y `server.js`).

## Seguridad

La app pide la **clave de desbloqueo del equipo** (PIN, patrón o contraseña) al abrirla,
y otra vez si estuvo más de 30 segundos en segundo plano. Si el equipo no tiene clave, la app pide configurarla.

## Fin del evento: borrar todo (v2.0)

Cuando el evento y la operación terminan se puede dejar **todo limpio** para el siguiente. Está en **Ajustes (con PIN) → «FIN DEL EVENTO · BORRAR DATOS»**.

**Qué se borra:** el listado de reservas, las validaciones, los ingresos y sus tickets, las fotos, las alertas y lo que el equipo sabía de los
otros equipos. **Qué no:** los ajustes (PIN, servidor, nombre del equipo, papel administrador/receptor, clave de la red) y los **registros que todavía no
se hayan enviado al servidor**: el borrado **no espera** a que se envíen, pero esos registros se siguen enviando (con su foto) y desaparecen
solo cuando el servidor los confirma. La descarga de reservas del servidor vuelve a empezar de cero (trae todo lo que el servidor entregue).

Hay dos formas, y las dos se mandan a **todos los equipos conectados**:

| Opción | Quién | Qué hace |
|---|---|---|
| **Borrar todo AHORA (todos los equipos)** | Un **administrador** (o «Automático») | Pide escribir `BORRAR` para confirmar. Se borra este equipo y la orden viaja por el Wi-Fi a los demás, que se borran en segundo plano al recibirla. |
| **Programar borrado automático** | Un **administrador** (o «Automático») | Se elige **día y hora**. La orden se guarda en cada equipo y, a esa hora, todos se borran solos sin que nadie toque nada. |
| **Cancelar el borrado programado** | Un **administrador** (o «Automático») | Avisa a todos que no borren. |
| **Borrar los datos de ESTE equipo ahora** | Un **receptor** | Borra solo ese equipo (no manda ninguna orden). |

Cómo funciona por dentro:

- La orden la da **un administrador (o un equipo en «Automático»)**, para que no haya dos órdenes distintas; si se da otra, la más nueva reemplaza a la anterior. Los receptores ven en Ajustes lo que está programado.
- Cada equipo **guarda la orden** y la reenvía a los demás; se cumple con el reloj interno de la app (cada 15 segundos), con el servicio en segundo plano y con una tarea de respaldo de Android. **Los equipos deben estar encendidos a esa hora** (y la red entre equipos encendida para recibir la orden).
- **Un equipo apagado o fuera del Wi-Fi** recibe la orden apenas se reconecte a cualquier equipo. Si ya pasó la hora, se borra al recibirla **solo si pasaron menos de 12 horas** y el equipo **no trabajó después de esa hora**; si no, la orden se da por atendida sin borrar y hay que borrar ese equipo a mano.
- Lo que haya pasado antes del borrado no se vuelve a aplicar. **Un equipo que no se borró no puede «revivir» el listado en uno ya limpio**: las tablas de reservas de un equipo con datos de antes del borrado se rechazan, y ese equipo recibe la orden y se limpia.
- Los relojes de los equipos deben estar en hora.
- Es **definitivo**: lo borrado no se recupera. Exporte antes si necesita el Excel con los ingresos.

## Cargar el Excel varias veces, sin duplicar (v1.9)

**Menú ⋮ → «Cargar Excel»** (pide el PIN si ya hay un listado). Si todavía no hay reservas, el archivo se carga como siempre.
Si **ya hay un listado**, después de leer el archivo la app pregunta:

| Opción | Qué hace |
|---|---|
| **Agregar sin duplicar** | Suma las reservas nuevas y actualiza las que ya estaban. **No se pierde nada de lo ya validado ni ingresado** (tampoco los tickets ni las fotos). Se puede cargar **el mismo archivo las veces que se quiera**. |
| **Reemplazar todo** | Borra el listado actual con sus validaciones e ingresos y deja solo el archivo (antes de hacerlo avisa y permite exportar). |

Al terminar, la app muestra cuántas reservas son **nuevas**, cuántas **ya estaban** (y cuántas no cambiaron en nada) y el **total**.

Cómo se reconoce que una reserva «ya estaba»:

1. Por el **código de reserva** (sin importar mayúsculas, espacios ni guiones).
2. Si la que estaba no tenía código, por la **placa**. Si el archivo trae un código nuevo para esa placa, la reserva recibe el código (no se duplica).
3. Si la reserva **no trae código ni placa**, solo cuando todos sus datos son idénticos a una que ya estaba.

Una misma placa con **dos códigos distintos** son dos reservas (por ejemplo, el mismo carro en dos eventos). Si el archivo trae columnas que el listado no tenía, se crean.

Lo agregado se reparte a todos los demás equipos; en ellos no se duplica por la misma regla.
**Ojo:** «Reemplazar todo» en un equipo no borra las reservas que los otros equipos ya tienen; para empezar de cero en todos hay que hacerlo en cada uno.

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
5. Los avisos de validación e ingreso llevan datos mínimos (código de reserva, placa, hora, nombre del equipo y operador). Desde la v1.8 también viajan la tabla de reservas (a todos) y los registros pendientes con su foto (hacia un administrador). Desde la 2.1 los administradores también reparten entre sí lo pendiente (ver «Administradores y receptores» más abajo).
6. Lo que llega de otro equipo **no se vuelve a enviar al servidor** como aviso: lo envía el equipo que lo originó, o un administrador si ese equipo es receptor (así no se duplica: el servidor descarta lo repetido).

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

## Administradores y receptores: quién habla con el servidor (v2.1)

**Todos los equipos son iguales para la red**: cada uno recibe, guarda, procesa y reenvía todo a los demás (reservas, validaciones, ingresos,
anulaciones, órdenes de borrado). **Solo cambia quién habla con el servidor**: los **administradores**, que son **máximo 2 a la vez y mínimo 1**.
Ellos suben los registros de todos, traen las reservas del servidor y se las reparten a todos los equipos.

**Ajustes → Red entre equipos → «Papel de este equipo»**:

| Papel | Qué hace |
|---|---|
| **Automático** (valor de fábrica) | Es administrador **solo si hace falta**: si no hay ningún administrador conectado, el primero de los automáticos asume. Así siempre hay uno. |
| **Administrador** | Quiere administrar: sube al servidor y trae reservas. Si hay más de 2 marcados, **trabajan 2 y los demás quedan de reserva**; cuando uno se desconecta, entra uno de reserva. |
| **Receptor** | Nunca habla con el servidor. Registra, imprime, valida y se lo entrega a un administrador. |

Cómo dejarlo (recomendado):

1. Marque **«Administrador»** en **1 o 2 equipos** que tengan **buen internet (4G o Wi-Fi con salida)**. Dos es lo ideal: si uno falla, el otro sigue.
2. Deje todos los demás en **«Automático»** (o «Receptor» si no quiere que nunca administren).
3. Escriba **las direcciones del servidor y el token en los equipos que pueden administrar** (un equipo sin dirección de servidor se anuncia como receptor).
4. **Todos los equipos deben estar conectados al mismo Wi-Fi** (ver «Red entre equipos cercanos»).

Qué pasa por detrás:

- **Registrar en cualquier equipo:** un equipo de adentro (sea administrador o receptor) valida si falta, registra, imprime y avisa **a todos** por el Wi-Fi al instante.
- **Registros al servidor:** un receptor entrega cada registro (con su foto) **a uno de los 2 administradores** (los reparte entre ambos). El administrador que lo recibe **le manda una copia de respaldo al otro**; el que lo suba primero **le avisa al otro** («ya subido») para que no lo repita. Si el servidor ya lo tenía, lo descarta (nunca se duplica).
- **Si falla la conexión o un administrador:** el registro **no se pierde**: se queda guardado en la cola (en el receptor, en el administrador y en la copia de respaldo) y **se sigue reintentando** hasta que el servidor confirme. Si los 2 administradores están caídos, los registros esperan en cada equipo y se entregan cuando aparezca uno.
- **Reservas:** cuando un administrador descarga reservas del servidor (o cualquier equipo carga un Excel), **las reparte a todos los demás**. Los equipos que se conectan después las piden a los que ya están.
- **Estados del servidor:** lo que el servidor ya sabe (validadas, ingresadas) lo ven los administradores y lo reparten a todos; se marca como «SERVIDOR».
- **Contadores:** cada equipo informa cuántas reservas ha validado y registrado.

> **Importante:** si el equipo administrador se apaga o pierde el Wi-Fi, entra el siguiente (otro administrador o un «Automático»). Pero **nadie sube al servidor si no hay ningún equipo con internet**: en ese caso todo queda guardado en los equipos y sube en cuanto vuelva la conexión.

### Adentro se puede registrar aunque no haya pasado por la vía (v2.1)

- **Equipo de la VÍA:** solo valida (vibra / aviso corto), sin imprimir.
- **Equipo de INTERIOR (administrador o receptor):** si la reserva ya estaba validada, la registra. **Si no estaba validada, la valida y la registra en el mismo momento**, imprime el ticket y avisa a todos. Queda una alerta «Ingresó sin pasar por la vía» y el servidor recibe `validado_en_via: false` y `validado_en_interior: true`.
- La opción «Bloquear adentro a quien no pasó por la vía» **ya no se activa sola** (antes el botón «Equipo INTERIOR» la dejaba activada y por eso los equipos que no eran satélite no podían registrar). Sigue existiendo como interruptor en Ajustes, apagada.

### Equipos conectados (Ajustes → «▸ Equipos conectados»)

Lista desplegable con **cada equipo de la red, por nombre del operario y del equipo**:

- su papel (administrador, automático o receptor), si **administra ahora** (★) o está de reserva, su dirección IP y si está conectado ahora;
- **última actualización recibida** y **última enviada** a ese equipo (hora exacta y «hace X»);
- cuántas reservas **validó** y **ingresó** hoy;
- la última vez que **subió** y **consultó** al servidor;
- avisos si **no hay ningún administrador** conectado, si trabaja **uno solo**, o si hay **más de 2 marcados** (los extra quedan de reserva).

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
`app/src/main/assets/placas/`.

Las gráficas usan [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) (Apache 2.0), que se baja de JitPack al compilar.
Los avisos de licencia están en `LICENCIAS_TERCEROS.txt`.

## Compilar

Al subir los cambios a GitHub, la pestaña **Actions** genera el APK y lo publica en **Releases**.
