// Общая логика сервера бригады. Подключается внутри обработчиков: const L = require(`${__hooks}/brig_lib.js`)
const J = (v) => { if (v == null) return null; try { return JSON.parse(typeof v === "string" ? v : toString(v)); } catch (e) { return null; } };
const OBJ_KINDS = ["obj", "task", "equip", "mat", "check", "photo", "doc"];

function code() { return $security.randomStringWithAlphabet(6, "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"); }

function newMember(app, brigadeId, name, role) {
  const m = new Record(app.findCollectionByNameOrId("members"));
  m.set("email", $security.randomStringWithAlphabet(16, "abcdefghijklmnopqrstuvwxyz0123456789") + "@brig.local");
  m.setPassword($security.randomString(32));
  m.set("name", String(name || "").slice(0, 120));
  m.set("brigade", brigadeId);
  m.set("role", role);
  m.set("active", true);
  app.save(m);
  return m;
}

function me(e) {
  const m = e.auth;
  if (!m || m.collection().name !== "members") throw new UnauthorizedError("нужен вход в бригаду");
  if (!m.getBool("active")) throw new ForbiddenError("revoked");
  return m;
}

function brigadeOf(app, m) { return app.findRecordById("brigades", m.getString("brigade")); }

function memberJson(m) { return { id: m.id, name: m.getString("name"), role: m.getString("role"), active: m.getBool("active") }; }

// объекты, видимые участнику: бригадиру — все неудалённые, монтажнику — где он в assigned
function visibleObjs(app, m) {
  const recs = app.findRecordsByFilter("items", "brigade = {:b} && kind = 'obj' && del = false", "", 0, 0, { b: m.getString("brigade") });
  const out = [];
  for (const r of recs) {
    const as = J(r.get("assigned")) || [];
    if (m.getString("role") === "foreman" || as.indexOf(m.id) >= 0) out.push(r.getString("cid"));
  }
  return out;
}

function bumpSeq(app, brigadeId) {
  const b = app.findRecordById("brigades", brigadeId);
  const s = b.getInt("seq") + 1;
  b.set("seq", s);
  app.save(b);
  return s;
}

function itemJson(r) {
  return { obj: r.getString("obj"), kind: r.getString("kind"), cid: r.getString("cid"), data: J(r.get("data")), upd: r.getFloat("upd"), by: r.getString("by"), del: r.getBool("del"), seq: r.getInt("seq"), assigned: r.getString("kind") === "obj" ? (J(r.get("assigned")) || []) : undefined };
}

// слияние карточки объекта по полям: data = {поле: значение, _t: {поле: время}}
function mergeObj(oldData, inc) {
  const o = oldData || {}, ot = o._t || {}, it = inc._t || {}, out = Object.assign({}, o), t = Object.assign({}, ot);
  for (const k in inc) {
    if (k === "_t") continue;
    const ti = it[k] || 0;
    if (ti >= (ot[k] || 0)) { out[k] = inc[k]; t[k] = ti; }
  }
  out._t = t;
  return out;
}

// применение одной правки с телефона; возвращает {ok} или {ok:false, reason, item}
function applyItem(app, m, it, visible) {
  const bid = m.getString("brigade"), role = m.getString("role");
  if (OBJ_KINDS.indexOf(it.kind) < 0) return { ok: false, reason: "kind" };
  const cid = String(it.cid || ""), obj = String(it.kind === "obj" ? cid : it.obj || "");
  if (!cid || !obj) return { ok: false, reason: "id" };
  let cur = null;
  try { cur = app.findFirstRecordByFilter("items", "brigade = {:b} && kind = {:k} && cid = {:c}", { b: bid, k: it.kind, c: cid }); } catch (e) { cur = null; }
  if (it.kind === "obj") {
    if (!cur && role !== "foreman") return { ok: false, reason: "только бригадир добавляет объекты" };
    if (cur && role !== "foreman" && (it.del || visible.indexOf(cid) < 0)) return { ok: false, reason: "нет прав", item: itemJson(cur) };
  } else {
    if (visible.indexOf(obj) < 0) return { ok: false, reason: "нет доступа к объекту" };
    if (it.del && cur && role !== "foreman" && cur.getString("by") !== m.id) return { ok: false, reason: "удалять может бригадир или автор", item: itemJson(cur) };
  }
  const upd = Number(it.upd) || Date.now();
  let data = it.data == null ? null : it.data;
  if (cur) {
    if (it.kind === "obj" && !it.del) data = mergeObj(J(cur.get("data")), data || {});
    else if (upd < cur.getFloat("upd")) return { ok: false, reason: "есть более новая правка", item: itemJson(cur) };
  } else {
    cur = new Record(app.findCollectionByNameOrId("items"));
    cur.set("brigade", bid); cur.set("kind", it.kind); cur.set("cid", cid); cur.set("obj", obj); cur.set("by", m.id);
    if (it.kind === "obj") cur.set("assigned", [m.id]);
  }
  if (data != null) cur.set("data", data);
  cur.set("upd", Math.max(upd, cur.getFloat("upd")));
  cur.set("del", !!it.del);
  if (!cur.getString("by")) cur.set("by", m.id);
  cur.set("seq", bumpSeq(app, bid));
  app.save(cur);
  return { ok: true };
}

module.exports = { J, code, newMember, me, brigadeOf, memberJson, visibleObjs, bumpSeq, itemJson, applyItem };
