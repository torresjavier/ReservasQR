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
- cuando se juntan N registros (N se elige en Ajustes, de 1 a 10; por defecto 5),
- de inmediato si llegan a 10 (nunca quedan más de 10 esperando mientras haya internet),
- y cada 15 minutos para enviar lo que haya quedado.

Si no hay internet, los registros se guardan en el equipo y se envían en lotes de 10 cuando vuelva la conexión.

**Cuerpo que envía la app:**

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
      "tipo_evento": "INGRESO",
      "id_ingreso": "9c2e…",
      "codigo_reserva": "R-1001",
      "placa": "ABC123",
      "nombre": "Ana Gómez",
      "evento": "Concierto Movistar Arena",
      "tipo_cliente": "VIP",
      "vip": true,
      "fecha_ingreso": "2026-10-28T13:04:55-05:00",
      "fecha_evento": "2026-10-28T13:04:55-05:00",
      "origen": "PLACA_CAMARA",
      "placa_leida": "ABC123",
      "operador": "Javier",
      "dispositivo": "5b0c…",
      "modelo": "V3H",
      "datos": { "NOMBRE": "Ana Gómez", "CODIGO_RESERVA": "R-1001", "PLACA": "ABC123", "EVENTO": "…", "TIPO": "VIP" },
      "foto": "<JPEG en base64>",
      "foto_tipo": "image/jpeg"
    }
  ]
}
```

| Campo | Significado |
|---|---|
| `id_evento` | Único por envío. Si la conexión se corta, la app puede reenviar el mismo: **ignore los repetidos**. |
| `tipo_evento` | `INGRESO`, o `ANULACION` cuando el operador anula un ingreso. |
| `id_ingreso` | Identifica el ingreso; una anulación trae el mismo `id_ingreso` del ingreso que anula. |
| `origen` | `QR`, `PLACA_CAMARA` (leída con la cámara) o `BUSQUEDA_MANUAL`. |
| `placa_leida` | Lo que leyó la cámara antes de corregir (por ejemplo `ABC12Q`). |
| `foto` | Solo en ingresos con la cámara: foto del vehículo (máximo 1024 px, JPEG). |
| `datos` | Todas las columnas de esa reserva tal como están en el equipo. |

**Respuesta esperada:** cualquier código 200–299. Si responde

```json
{ "ok": true, "recibidos": ["a1f3…", "…"] }
```

la app quita de su cola solo esos; si no trae `recibidos`, quita todo el lote.
Con 401/403 la app deja de intentar hasta que se corrija el token; con 5xx o sin conexión reintenta sola.

---

## 2. Traer reservas: `GET /api/reservas`

La app la llama cada 15 minutos (si está activado en Ajustes) y con el menú **⋮ → Traer reservas del servidor**.
Sirve para agregar reservas **sin subir un Excel**. El Excel sigue funcionando igual.

```
GET /api/reservas?dispositivo=5b0c…&desde=2026-10-28T18:00:00.000Z
```

- `desde` es el valor `hora` de la respuesta anterior. Devuelva solo las reservas nuevas o cambiadas desde ese momento, o todas si no viene.

**Respuesta:**

```json
{
  "hora": "2026-10-28T18:15:00.000Z",
  "reservas": [
    { "codigo_reserva": "R-2001", "placa": "KLM456", "nombre": "Pedro Pérez", "evento": "Concierto", "tipo": "VIP", "telefono": "3001234567" }
  ]
}
```

También se acepta una lista sola: `[ {…}, {…} ]`.

- Las claves se reconocen igual que las columnas del Excel (`codigo_reserva`/`codigo`/`reserva`, `placa`, `nombre`/`cliente`, `evento`, `tipo`/`categoria`).
- Cualquier otra clave (`telefono`, `puesto`…) se guarda como columna adicional.
- Si la reserva ya existe en el equipo (mismo código, o misma placa si no trae código) se **actualiza**; si no, se **agrega**.
- Los ingresos ya registrados en el equipo **no se pierden**.

---

## 3. (Servidor de ejemplo) Agregar reservas: `POST /api/reservas`

Para cargar reservas al servidor desde otro sistema, sin Excel:

```bash
curl -X POST https://su-servidor.com/api/reservas \
  -H "Authorization: Bearer SU_TOKEN" -H "Content-Type: application/json" \
  -d '{"reservas":[{"codigo_reserva":"R-2001","placa":"KLM456","nombre":"Pedro Pérez","evento":"Concierto","tipo":"VIP"}]}'
```

Respuesta: `{"ok":true,"agregadas":1,"actualizadas":0,"total":1}`. En los siguientes 15 minutos aparecen en todos los equipos.

## 4. (Servidor de ejemplo) Ver ingresos

- `GET /api/registros`: todos los registros recibidos, en JSON (las fotos quedan en `foto_url`).
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
