# API de Reservas QR

La app trabaja con dos direcciones que se escriben en **Ajustes → Servidor**, más una clave (token).
Todas las peticiones llevan el encabezado:

```
Authorization: Bearer <TOKEN>
Content-Type: application/json
```

Se puede usar `server.js` (incluido) o programar estas mismas rutas en su propio sistema.

---

## 1. Enviar registros: `POST /api/registros`

La app la llama **sola, en segundo plano y solo con internet**:
- cuando se juntan N registros (N se elige en Ajustes: **5, 10 o 20**; también es el máximo por envío),
- cada **N minutos** (Ajustes: 1, 2, 5, 10, 15 o 30) mientras la app está abierta,
- apenas **vuelve el internet**,
- y cada 15 minutos por una tarea de respaldo de Android, aunque la app se haya cerrado.

Si un envío falla (sin internet, error 5xx, tiempo agotado) **los registros se conservan con el mismo `id_evento`** y se reintentan
en el siguiente ciclo. Por eso el servidor debe **ignorar los `id_evento` que ya tiene** (y aun así incluirlos en `recibidos`).

### Tipos de registro (`tipo_evento`)

| tipo_evento | Cuándo | `momento` | `estado_reserva` |
|---|---|---|---|
| `VALIDACION` | Momento 1, **en la vía**: se leyó el QR o la placa y se dio acceso (sin ticket). | `VIA` | `VALIDADA` |
| `INGRESO` | Momento 2, **adentro**: se leyó otra vez, se registró el ingreso y se imprimió el ticket. | `INTERIOR` | `INGRESADA` |
| `ANULACION` | El administrador (con PIN) anuló un ingreso o una validación. Trae `anula`: `INGRESO` o `VALIDACION`. | `INTERIOR` / `VIA` | `VALIDADA` o `PENDIENTE` |
| `ALERTA` | Intento de colarse o algo raro. Trae `motivo` y `detalle` (ver abajo). | según el equipo | estado actual |

**Motivos de alerta (`motivo`):**
`SIN_RESERVA_VIA` (placa/QR que no está en el listado, en la vía) ·
`SIN_RESERVA_INTERIOR` (vehículo adentro sin reserva) ·
`REPETIDA_EN_VIA` (el mismo QR o placa se validó otra vez pasados 20 s: posible copia) ·
`INGRESO_SIN_VALIDAR` (ingresó adentro sin haber pasado por la vía) ·
`BLOQUEADO_SIN_VALIDAR` (se le negó el ingreso por no haber pasado por la vía) ·
`TIPO_NO_COINCIDE` (la cámara ve una placa de moto y la reserva es de carro, o al revés).
Las alertas traen la foto del vehículo cuando se detectaron con la cámara.

### Cuerpo que envía la app

```json
{
  "dispositivo": "5b0c…",
  "modelo": "V3H",
  "operador": "Javier",
  "enviado": "2026-10-28T13:05:12-05:00",
  "cantidad": 2,
  "registros": [
    {
      "id_evento": "a1f3…",
      "tipo_evento": "VALIDACION",
      "momento": "VIA",
      "estado_reserva": "VALIDADA",
      "id_reserva": "da66af97-…",
      "codigo_reserva": "R-1001",
      "placa": "ABC123",
      "tipo_vehiculo": "CARRO",
      "nombre": "Ana Gómez",
      "evento": "Concierto Movistar Arena",
      "tipo_cliente": "VIP",
      "vip": true,
      "id_validacion": "9c2e…",
      "fecha_validacion": "2026-10-28T13:04:55-05:00",
      "fecha_evento": "2026-10-28T13:04:55-05:00",
      "origen": "PLACA_CAMARA",
      "placa_leida": "ABC12Q",
      "operador": "Javier",
      "dispositivo": "5b0c…",
      "modelo": "V3H",
      "datos": { "NOMBRE": "Ana Gómez", "CODIGO_RESERVA": "R-1001", "PLACA": "ABC123", "EVENTO": "…", "TIPO": "VIP" },
      "foto": "<JPEG en base64>",
      "foto_tipo": "image/jpeg"
    },
    {
      "id_evento": "b7d0…",
      "tipo_evento": "ALERTA",
      "motivo": "SIN_RESERVA_VIA",
      "detalle": "Quiso pasar sin reserva en el listado.",
      "momento": "VIA",
      "estado_reserva": "SIN_RESERVA",
      "placa": "ZZZ999",
      "placa_leida": "ZZZ999",
      "fecha_evento": "2026-10-28T13:05:03-05:00",
      "operador": "Javier",
      "dispositivo": "5b0c…",
      "modelo": "V3H",
      "foto": "<JPEG en base64>",
      "foto_tipo": "image/jpeg"
    }
  ]
}
```

Un `INGRESO` trae además `id_ingreso`, `fecha_ingreso`, `validado_en_via` (true/false) y, si pasó por la vía, `fecha_validacion`.
Desde la 2.1, si el equipo de adentro tuvo que validar la reserva en el mismo momento (no había pasado por la vía), el `INGRESO` trae `validado_en_via: false` y `validado_en_interior: true`, y antes llega un evento `VALIDACION` con `momento: "INTERIOR"`.

| Campo | Significado |
|---|---|
| `id_evento` | **Identifica cada registro** (único). El servidor lo devuelve en `recibidos` para confirmar. Si la app reenvía el mismo, **ignórelo** (no lo guarde dos veces). |
| `id_reserva` | El `id` que el servidor le dio a la reserva en `GET /api/reservas`. Úselo para **actualizar esa reserva** en el servidor. Si la reserva no trae `id`, la app manda el código de la reserva. |
| `codigo_reserva`, `placa` | Otras formas de ubicar la reserva (por si no hay `id_reserva`). |
| `estado_reserva` | Estado en que **queda** la reserva después de este registro: `PENDIENTE`, `VALIDADA` o `INGRESADA`. |
| `tipo_vehiculo` | `CARRO` (ABC123) o `MOTO` (ABC12D / ABC12), según la placa de la reserva. |
| `origen` | `QR`, `PLACA_CAMARA` (leída con la cámara) o `BUSQUEDA_MANUAL`. |
| `placa_leida` | Lo que leyó la cámara antes de corregir (por ejemplo `ABC12Q`). |
| `foto` | Foto del vehículo (máx. 1024 px, JPEG) si se hizo con la cámara. |
| `datos` | Todas las columnas de esa reserva tal como están en el equipo. |

### Respuesta esperada

Cualquier código 200–299. Lo ideal:

```json
{ "ok": true, "recibidos": ["a1f3…", "b7d0…"] }
```

- La app quita de su cola **solo los `id_evento` que vengan en `recibidos`**. Si la respuesta no trae `recibidos`, quita todo el lote.
- Opcional: `"rechazados": ["id…"]` para registros que el servidor nunca va a aceptar (la app los quita de la cola y avisa).
- Opcional: `"reservas": [ … ]` (mismo formato que el GET). La app las aplica de inmediato: así cada envío ya trae los cambios de los otros equipos.
- Con 401/403 la app deja de intentar hasta que se corrija el token. Con 413 reduce el tamaño del lote. Con 5xx o sin conexión, reintenta en el siguiente ciclo.

---

## 2. Traer reservas: `GET /api/reservas`

La app la llama cada N minutos (los mismos de Ajustes, si está activado «Traer reservas») y con el menú **⋮ → Traer reservas del servidor**.
Sirve para agregar reservas **sin subir un Excel** y para que el equipo de adentro sepa **quién ya pasó por la vía**. El Excel sigue funcionando igual.

```
GET /api/reservas?dispositivo=5b0c…&desde=2026-10-28T18:00:00.000Z
```

- `desde` es el valor `hora` de la respuesta anterior. Devuelva solo las reservas nuevas o cambiadas desde ese momento, o todas si no viene.

**Respuesta:**

```json
{
  "hora": "2026-10-28T18:15:00.000Z",
  "reservas": [
    {
      "id": "da66af97-a166-42ec-a402-73b67333591f",
      "codigo_reserva": "R-2001", "placa": "KLM456", "nombre": "Pedro Pérez", "evento": "Concierto", "tipo": "VIP", "telefono": "3001234567",
      "estado": "VALIDADA",
      "fecha_validacion": "2026-10-28T13:04:55-05:00"
    }
  ]
}
```

También se acepta una lista sola: `[ {…}, {…} ]`.

- Las claves se reconocen igual que las columnas del Excel (`codigo_reserva`/`codigo`/`reserva`, `placa`, `nombre`/`cliente`, `evento`, `tipo`/`categoria`).
- Cualquier otra clave (`telefono`, `puesto`…) se guarda como columna adicional.
- Si la reserva ya existe en el equipo (mismo código, o misma placa si no trae código) se **actualiza**; si no, se **agrega**.
- Los ingresos ya registrados en el equipo **no se pierden**.
- `id`: identificador de la reserva en el servidor. La app lo devuelve como `id_reserva` en cada registro.
- `estado` (opcional): `VALIDADA` o `INGRESADA` (más `fecha_validacion` / `fecha_ingreso`). La app lo aplica así: **solo avanza** el estado
  (pendiente → validada → ingresada) y no toca reservas que ese equipo todavía tiene registros sin enviar. Con esto los equipos de la vía y del interior
  comparten quién ya fue validado. `estado`, `fecha_validacion` y `fecha_ingreso` no se guardan como columnas.

---

## 3. (Servidor de ejemplo) Agregar reservas: `POST /api/reservas`

Para cargar reservas al servidor desde otro sistema, sin Excel:

```bash
curl -X POST https://su-servidor.com/api/reservas \
  -H "Authorization: Bearer SU_TOKEN" -H "Content-Type: application/json" \
  -d '{"reservas":[{"codigo_reserva":"R-2001","placa":"KLM456","nombre":"Pedro Pérez","evento":"Concierto","tipo":"VIP"}]}'
```

Respuesta: `{"ok":true,"agregadas":1,"actualizadas":0,"total":1}`. A las reservas nuevas el servidor les asigna un `id`. En el siguiente ciclo (Ajustes) aparecen en todos los equipos.

## 4. (Servidor de ejemplo) Ver ingresos

- `GET /api/registros`: todos los registros recibidos, en JSON (las fotos quedan en `foto_url`). Filtro: `?tipo=ALERTA` (o `VALIDACION`, `INGRESO`, `ANULACION`).
- `https://su-servidor.com/?token=SU_TOKEN`: página que se actualiza sola con los últimos 200 ingresos y sus fotos.

---

## Poner en marcha el servidor de ejemplo

Necesita Node.js 18 o superior. No usa librerías externas.

```bash
TOKEN=una-clave-larga-y-secreta PORT=8080 node server.js
```

Los datos quedan en la carpeta `datos/` (`registros.json`, `reservas.json` y `fotos/`).

Para usarlo desde los equipos en la calle debe estar en un servidor con dirección pública y **HTTPS**:
un VPS, Render, Railway, Fly.io, o el mismo servidor de su sistema web.
En la app escriba:

- Enviar registros: `https://su-servidor.com/api/registros`
- Traer reservas: `https://su-servidor.com/api/reservas`
- Token: el mismo `TOKEN`


## Radio (walkie-talkie, v2.1)

La voz viaja en trozos de 250 ms comprimidos (μ-law, 8 kHz, ~2 KB cada uno) dentro de un paquete binario que la app arma; el servidor
**no lo interpreta ni lo guarda en disco**: solo lo reparte a los equipos del mismo canal durante unos segundos (en memoria).
Se pide el mismo `Authorization: Bearer <TOKEN>` que en las demás rutas.

### POST /api/radio

```json
{ "canal": "general", "de": "id-del-equipo", "paquetes": ["<base64>", "<base64>"] }
```

Respuesta: `{ "ok": true, "hasta": 17 }` (`hasta` = número del último paquete guardado en el canal).

### GET /api/radio?canal=general&yo=id-del-equipo&desde=17

«Long polling»: si hay paquetes con número mayor que `desde` los devuelve al instante; si no, **espera hasta 8 segundos** a que lleguen y responde.

```json
{ "hasta": 19, "paquetes": [ { "n": 18, "p": "<base64>" }, { "n": 19, "p": "<base64>" } ] }
```

La primera vez la app manda `desde=-1`: el servidor responde solo con `hasta` (sin paquetes) para no repetir voz vieja. Después la app vuelve a preguntar con el último `hasta`.
