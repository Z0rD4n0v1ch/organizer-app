/// <reference path="../pb_data/types.d.ts" />
// API бригады для «Органайзера монтажника». Всё под /api/brig/*.

// создать бригаду: {brigade, name} → бригадир
routerAdd("POST", "/api/brig/create", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const body = e.requestInfo().body || {};
  const bname = String(body.brigade || "").trim(), name = String(body.name || "").trim();
  if (!bname || !name) throw new BadRequestError("укажите название бригады и своё имя");
  let m = null;
  e.app.runInTransaction((tx) => {
    const b = new Record(tx.findCollectionByNameOrId("brigades"));
    b.set("name", bname.slice(0, 200)); b.set("code", L.code()); b.set("seq", 0);
    tx.save(b);
    m = L.newMember(tx, b.id, name, "foreman");
  });
  return $apis.recordAuthResponse(e, m, "brigade");
});

// вступить по коду: {code, name} → монтажник
routerAdd("POST", "/api/brig/join", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const body = e.requestInfo().body || {};
  const c = String(body.code || "").trim().toUpperCase().replace(/[^A-Z0-9]/g, ""), name = String(body.name || "").trim();
  if (!c || !name) throw new BadRequestError("укажите код и своё имя");
  let b = null;
  try { b = e.app.findFirstRecordByFilter("brigades", "code = {:c}", { c }); } catch (err) { throw new NotFoundError("бригада с таким кодом не найдена"); }
  const m = L.newMember(e.app, b.id, name, "worker");
  return $apis.recordAuthResponse(e, m, "brigade");
});

// кто я, бригада, участники
routerAdd("GET", "/api/brig/me", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e), b = L.brigadeOf(e.app, m);
  const mem = e.app.findRecordsByFilter("members", "brigade = {:b} && active = true", "", 0, 0, { b: b.id }).map(L.memberJson);
  return e.json(200, { me: L.memberJson(m), brigade: { id: b.id, name: b.getString("name"), code: m.getString("role") === "foreman" ? b.getString("code") : "" }, members: mem });
}, $apis.requireAuth("members"));

// отправка правок: {items:[{obj,kind,cid,data,upd,del}]}
routerAdd("POST", "/api/brig/push", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e);
  const items = ((e.requestInfo().body || {}).items) || [];
  const res = [];
  e.app.runInTransaction((tx) => {
    let vis = L.visibleObjs(tx, m);
    for (const it of items) {
      let r;
      try { r = L.applyItem(tx, m, it, vis); } catch (err) { r = { ok: false, reason: String(err) }; }
      if (r.ok && it.kind === "obj" && !it.del && vis.indexOf(it.cid) < 0) vis.push(it.cid);
      res.push(Object.assign({ kind: it.kind, cid: it.cid }, r));
    }
  });
  return e.json(200, { results: res });
}, $apis.requireAuth("members"));

// получение правок: ?since=N[&full=cid1,cid2] → {items, objs, seq}
routerAdd("GET", "/api/brig/pull", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e), bid = m.getString("brigade");
  const since = parseInt(e.request.url.query().get("since") || "0", 10) || 0;
  const full = String(e.request.url.query().get("full") || "").split(",").filter(Boolean);
  const vis = L.visibleObjs(e.app, m), b = e.app.findRecordById("brigades", bid);
  const out = [], seen = {};
  const add = (r) => { const k = r.getString("kind") + ":" + r.getString("cid"); if (seen[k]) return; seen[k] = 1; if (vis.indexOf(r.getString("obj")) >= 0) out.push(L.itemJson(r)); };
  e.app.findRecordsByFilter("items", "brigade = {:b} && seq > {:s}", "seq", 5000, 0, { b: bid, s: since }).forEach(add);
  for (const o of full) if (vis.indexOf(o) >= 0) e.app.findRecordsByFilter("items", "brigade = {:b} && obj = {:o}", "seq", 0, 0, { b: bid, o }).forEach(add);
  return e.json(200, { items: out, objs: vis, seq: b.getInt("seq") });
}, $apis.requireAuth("members"));

// бригадир: назначить людей на объект {obj, members:[id]}
routerAdd("POST", "/api/brig/assign", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e);
  if (m.getString("role") !== "foreman") throw new ForbiddenError("назначать может только бригадир");
  const body = e.requestInfo().body || {};
  const bid = m.getString("brigade");
  let r = null;
  try { r = e.app.findFirstRecordByFilter("items", "brigade = {:b} && kind = 'obj' && cid = {:c}", { b: bid, c: String(body.obj || "") }); } catch (err) { throw new NotFoundError("объект ещё не выложен в бригаду"); }
  const ids = (body.members || []).map(String);
  const ok = e.app.findRecordsByFilter("members", "brigade = {:b} && active = true", "", 0, 0, { b: bid }).map((x) => x.id);
  const as = [m.id].concat(ids.filter((x) => ok.indexOf(x) >= 0 && x !== m.id));
  e.app.runInTransaction((tx) => { r.set("assigned", as); r.set("seq", L.bumpSeq(tx, bid)); tx.save(r); });
  return e.json(200, { obj: r.getString("cid"), assigned: as });
}, $apis.requireAuth("members"));

// бригадир: убрать человека из бригады {member}
routerAdd("POST", "/api/brig/kick", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e);
  if (m.getString("role") !== "foreman") throw new ForbiddenError("убирать может только бригадир");
  const id = String((e.requestInfo().body || {}).member || "");
  if (id === m.id) throw new BadRequestError("нельзя убрать себя");
  const x = e.app.findRecordById("members", id);
  if (x.getString("brigade") !== m.getString("brigade")) throw new NotFoundError("нет такого участника");
  x.set("active", false); x.refreshTokenKey(); e.app.save(x);   // старый токен больше не работает
  return e.json(200, { ok: true });
}, $apis.requireAuth("members"));

// файлы фото и PDF: загрузка (multipart: key, obj, file) и скачивание (?key=)
routerAdd("POST", "/api/brig/file", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e), bid = m.getString("brigade");
  const key = String(e.request.formValue("key") || ""), obj = String(e.request.formValue("obj") || "");
  if (!key || L.visibleObjs(e.app, m).indexOf(obj) < 0) throw new ForbiddenError("нет доступа к объекту");
  const files = e.findUploadedFiles("file");
  if (!files || !files.length) throw new BadRequestError("нет файла");
  let r = null;
  try { r = e.app.findFirstRecordByFilter("bfiles", "brigade = {:b} && key = {:k}", { b: bid, k: key }); } catch (err) { r = new Record(e.app.findCollectionByNameOrId("bfiles")); r.set("brigade", bid); r.set("key", key); }
  r.set("obj", obj); r.set("file", files[0]);
  e.app.save(r);
  return e.json(200, { ok: true });
}, $apis.requireAuth("members"));

routerAdd("GET", "/api/brig/file", (e) => {
  const L = require(`${__hooks}/brig_lib.js`);
  const m = L.me(e), bid = m.getString("brigade");
  const key = String(e.request.url.query().get("key") || "");
  let r = null;
  try { r = e.app.findFirstRecordByFilter("bfiles", "brigade = {:b} && key = {:k}", { b: bid, k: key }); } catch (err) { throw new NotFoundError("файла нет"); }
  if (L.visibleObjs(e.app, m).indexOf(r.getString("obj")) < 0) throw new ForbiddenError("нет доступа к объекту");
  const fs = e.app.newFilesystem();
  try { fs.serve(e.response, e.request, r.baseFilesPath() + "/" + r.getString("file"), key); } finally { fs.close(); }
}, $apis.requireAuth("members"));
