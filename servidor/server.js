// Servidor de ejemplo para Reservas QR (Node.js 18+, sin librerías externas).
//
//   POST /api/registros   ← la app envía los ingresos (con foto) en lotes de máximo 10
//   GET  /api/registros   → ver los ingresos recibidos
//   GET  /api/reservas    → la app descarga reservas nuevas o cambiadas (?desde=fecha)
//   POST /api/reservas    ← usted (u otro sistema) agrega o actualiza reservas sin Excel
//   GET  /fotos/<archivo> → foto de un ingreso
//   GET  /                → página sencilla para ver los últimos ingresos
//
// Todas las rutas piden el token:  Authorization: Bearer <TOKEN>   (o ?token=<TOKEN> en el navegador)
//
// Uso:  TOKEN=mi-clave-secreta PORT=8080 node server.js

const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 8080);
const TOKEN = process.env.TOKEN || "cambie-esta-clave";
const DATOS = process.env.DATOS || path.join(__dirname, "datos");
const FOTOS = path.join(DATOS, "fotos");
const ARCH_REG = path.join(DATOS, "registros.json");
const ARCH_RES = path.join(DATOS, "reservas.json");
const MAX_CUERPO = 25 * 1024 * 1024; // 25 MB (10 registros con foto caben de sobra)

fs.mkdirSync(FOTOS, { recursive: true });

function leer(archivo, porDefecto) {
  try { return JSON.parse(fs.readFileSync(archivo, "utf8")); } catch { return porDefecto; }
}
function escribir(archivo, datos) {
  const tmp = archivo + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(datos, null, 1));
  fs.renameSync(tmp, archivo);
}
function responder(res, codigo, obj) {
  const cuerpo = JSON.stringify(obj);
  res.writeHead(codigo, { "Content-Type": "application/json; charset=utf-8", "Content-Length": Buffer.byteLength(cuerpo) });
  res.end(cuerpo);
}
function autorizado(req, url) {
  const h = req.headers["authorization"] || "";
  const t = h.startsWith("Bearer ") ? h.slice(7) : url.searchParams.get("token");
  if (!t) return false;
  const a = Buffer.from(t), b = Buffer.from(TOKEN);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}
function leerCuerpo(req) {
  return new Promise((ok, mal) => {
    const partes = [];
    let total = 0;
    req.on("data", (c) => {
      total += c.length;
      if (total > MAX_CUERPO) { mal(new Error("demasiado grande")); req.destroy(); return; }
      partes.push(c);
    });
    req.on("end", () => ok(Buffer.concat(partes).toString("utf8")));
    req.on("error", mal);
  });
}
const normal = (s) => String(s || "").normalize("NFD").replace(/\p{M}/gu, "").toUpperCase().replace(/[^A-Z0-9]/g, "");
const ahora = () => new Date().toISOString();
const seguro = (s) => String(s || "").replace(/[^A-Za-z0-9_-]/g, "");
const html = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

// ---------------------------------------------------------------- registros (ingresos)

async function recibirRegistros(req, res) {
  const cuerpo = JSON.parse(await leerCuerpo(req));
  const lista = Array.isArray(cuerpo.registros) ? cuerpo.registros : [];
  const guardados = leer(ARCH_REG, []);
  const vistos = new Set(guardados.map((r) => r.id_evento));
  const recibidos = [];
  for (const r of lista) {
    if (!r || !r.id_evento) continue;
    recibidos.push(r.id_evento);
    if (vistos.has(r.id_evento)) continue; // la app puede reenviar si se cortó la conexión
    const reg = { ...r, recibido: ahora(), dispositivo: r.dispositivo || cuerpo.dispositivo };
    if (r.foto) {
      const nombre = seguro(r.id_ingreso || r.id_evento) + ".jpg";
      fs.writeFileSync(path.join(FOTOS, nombre), Buffer.from(r.foto, "base64"));
      reg.foto_url = "/fotos/" + nombre;
    }
    delete reg.foto;
    guardados.push(reg);
    vistos.add(r.id_evento);
  }
  escribir(ARCH_REG, guardados);
  console.log(`${ahora()}  ${recibidos.length} registros de ${cuerpo.dispositivo || "?"} (${cuerpo.operador || ""})`);
  responder(res, 200, { ok: true, recibidos });
}

// ---------------------------------------------------------------- reservas

function clave(r) {
  return r.codigo_reserva ? "C:" + normal(r.codigo_reserva) : "P:" + normal(r.placa);
}

async function agregarReservas(req, res) {
  const cuerpo = JSON.parse(await leerCuerpo(req));
  const nuevas = Array.isArray(cuerpo) ? cuerpo : Array.isArray(cuerpo.reservas) ? cuerpo.reservas : [cuerpo];
  const reservas = leer(ARCH_RES, []);
  const indice = new Map(reservas.map((r, i) => [clave(r), i]));
  let agregadas = 0, actualizadas = 0;
  for (const n of nuevas) {
    if (!n || (!n.codigo_reserva && !n.placa)) continue;
    const r = { ...n, _actualizado: ahora() };
    const k = clave(r);
    if (indice.has(k)) { reservas[indice.get(k)] = { ...reservas[indice.get(k)], ...r }; actualizadas++; }
    else { indice.set(k, reservas.length); reservas.push(r); agregadas++; }
  }
  escribir(ARCH_RES, reservas);
  responder(res, 200, { ok: true, agregadas, actualizadas, total: reservas.length });
}

function entregarReservas(url, res) {
  const desde = url.searchParams.get("desde") || "";
  const t = desde ? Date.parse(desde) : NaN;
  const todas = leer(ARCH_RES, []);
  const lista = todas
    .filter((r) => isNaN(t) || Date.parse(r._actualizado) > t)
    .map(({ _actualizado, ...r }) => r); // los campos internos no se envían
  responder(res, 200, { hora: ahora(), reservas: lista });
}

// ---------------------------------------------------------------- página para ver ingresos

function pagina(url, res) {
  const token = url.searchParams.get("token") || "";
  const regs = leer(ARCH_REG, []).slice(-200).reverse();
  const filas = regs.map((r) => `
    <tr class="${r.tipo_evento === "ANULACION" ? "anulado" : ""}">
      <td>${html((r.fecha_ingreso || "").replace("T", " ").slice(0, 16))}</td>
      <td><b class="placa">${html(r.placa)}</b>${r.vip ? ' <span class="vip">VIP</span>' : ""}</td>
      <td>${html(r.nombre)}<br><small>${html(r.evento)} · ${html(r.codigo_reserva)}</small></td>
      <td>${html(r.tipo_evento)}<br><small>${html(r.origen)} · ${html(r.operador)}</small></td>
      <td>${r.foto_url ? `<a href="${r.foto_url}?token=${encodeURIComponent(token)}" target="_blank"><img src="${r.foto_url}?token=${encodeURIComponent(token)}"></a>` : ""}</td>
    </tr>`).join("");
  const cuerpo = `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reservas QR · ingresos</title><meta http-equiv="refresh" content="30">
<style>body{margin:0;background:#000;color:#fff;font-family:system-ui,sans-serif}header{padding:16px;border-bottom:1px solid #262626}
h1{margin:0;font-size:20px}h1 span{color:#E10600}table{width:100%;border-collapse:collapse}td{padding:10px 16px;border-bottom:1px solid #1a1a1a;vertical-align:top}
small{color:#9b9b9b}.placa{background:#fff;color:#000;padding:2px 8px;border-radius:6px;font-family:monospace;font-size:16px}
.vip{background:#E10600;padding:2px 8px;border-radius:99px;font-size:12px;font-weight:700}img{height:56px;border-radius:8px}.anulado{opacity:.45;text-decoration:line-through}</style></head>
<body><header><h1>Reservas QR · <span>${regs.length}</span> ingresos recientes</h1><small>Se actualiza cada 30 segundos</small></header>
<table>${filas || '<tr><td>Aún no han llegado registros.</td></tr>'}</table></body></html>`;
  res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
  res.end(cuerpo);
}

// ---------------------------------------------------------------- rutas

http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  try {
    if (!autorizado(req, url)) return responder(res, 401, { ok: false, error: "token inválido" });
    const r = url.pathname.replace(/\/+$/, "") || "/";
    if (req.method === "POST" && r === "/api/registros") return await recibirRegistros(req, res);
    if (req.method === "GET" && r === "/api/registros") return responder(res, 200, { registros: leer(ARCH_REG, []) });
    if (req.method === "GET" && r === "/api/reservas") return entregarReservas(url, res);
    if (req.method === "POST" && r === "/api/reservas") return await agregarReservas(req, res);
    if (req.method === "GET" && r.startsWith("/fotos/")) {
      const f = path.join(FOTOS, seguro(path.basename(r, ".jpg")) + ".jpg");
      if (!fs.existsSync(f)) return responder(res, 404, { ok: false });
      res.writeHead(200, { "Content-Type": "image/jpeg" });
      return fs.createReadStream(f).pipe(res);
    }
    if (req.method === "GET" && r === "/") return pagina(url, res);
    responder(res, 404, { ok: false, error: "ruta no encontrada" });
  } catch (e) {
    responder(res, 400, { ok: false, error: String(e.message || e) });
  }
}).listen(PORT, () => console.log(`Reservas QR escuchando en el puerto ${PORT}`));
