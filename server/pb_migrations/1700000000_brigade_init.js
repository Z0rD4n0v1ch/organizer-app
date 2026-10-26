/// <reference path="../pb_data/types.d.ts" />
// Схема сервера бригады. Все коллекции закрыты (правила null) — доступ только через маршруты /api/brig/* в pb_hooks.
migrate((app) => {
  const T = (name, max) => ({ name, type: "text", max: max || 0 });
  const N = (name) => ({ name, type: "number" });
  const B = (name) => ({ name, type: "bool" });
  const J = (name) => ({ name, type: "json", maxSize: 20 * 1024 * 1024 });

  app.save(new Collection({
    type: "base", name: "brigades",
    fields: [T("name", 200), T("code", 40), N("seq")],
    indexes: ["CREATE UNIQUE INDEX idx_brig_code ON brigades (code)"],
  }));

  const members = new Collection({
    type: "auth", name: "members",
    fields: [T("name", 120), T("brigade", 40), T("role", 20), B("active")],
    indexes: ["CREATE INDEX idx_mem_brig ON members (brigade)"],
  });
  members.authToken.duration = 60 * 60 * 24 * 365;   // год: устройство бригады входит один раз
  app.save(members);

  app.save(new Collection({
    type: "base", name: "items",
    fields: [T("brigade", 40), T("obj", 80), T("kind", 20), T("cid", 80), J("data"), N("upd"), T("by", 40), B("del"), N("seq"), J("assigned")],
    indexes: [
      "CREATE UNIQUE INDEX idx_items_key ON items (brigade, kind, cid)",
      "CREATE INDEX idx_items_seq ON items (brigade, seq)",
      "CREATE INDEX idx_items_obj ON items (brigade, obj)",
    ],
  }));

  app.save(new Collection({
    type: "base", name: "bfiles",
    fields: [T("brigade", 40), T("key", 120), T("obj", 80), { name: "file", type: "file", maxSelect: 1, maxSize: 30 * 1024 * 1024 }],
    indexes: ["CREATE UNIQUE INDEX idx_bfiles_key ON bfiles (brigade, key)"],
  }));
}, (app) => {
  for (const n of ["bfiles", "items", "members", "brigades"]) {
    try { app.delete(app.findCollectionByNameOrId(n)); } catch (e) {}
  }
});
