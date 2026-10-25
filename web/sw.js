/* Органайзер монтажника — офлайн-работа веб-версии и хранение фото */
const V='org-__VERSION__', PHOTOS='org-photos', FONTS='org-fonts', KEEP=[PHOTOS,FONTS,'org-backups','org-docs'];
const SHELL=['./','index.html','bolid.js','argus.js','rubezh.js','trassir.js','hikvision.js','smartec.js','pdfjs/pdf.min.js','pdfjs/pdf.worker.min.js','manifest.webmanifest','apple-touch-icon.png','icon-192.png','icon-512.png'];
self.addEventListener('install',e=>{ e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting())); });
self.addEventListener('activate',e=>{ e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==V&&!KEEP.includes(k)).map(k=>caches.delete(k)))).then(()=>self.clients.claim())); });
function netFirst(req){
  return caches.open(V).then(c=>{
    const net=fetch(req).then(r=>{ if(r&&r.ok) c.put(req,r.clone()); return r; });
    const timeout=new Promise(res=>setTimeout(res,3500));
    return Promise.race([net.catch(()=>null),timeout]).then(r=>r||c.match(req,{ignoreSearch:true}).then(m=>m||net));
  });
}
self.addEventListener('fetch',e=>{
  const req=e.request; if(req.method!=='GET') return;
  const u=new URL(req.url);
  if(u.origin===location.origin&&/\/photo\/[^/]+$/.test(u.pathname)){
    e.respondWith(caches.open(PHOTOS).then(c=>c.match(u.origin+u.pathname)).then(m=>m||new Response('',{status:404})));
    return;
  }
  if(/fonts\.(googleapis|gstatic)\.com$/.test(u.hostname)){
    e.respondWith(caches.open(FONTS).then(c=>c.match(req).then(m=>m||fetch(req).then(r=>{ if(r.ok||r.type==='opaque') c.put(req,r.clone()); return r; }))));
    return;
  }
  if(u.origin===location.origin){
    if(req.mode==='navigate') e.respondWith(netFirst(new Request('index.html')).then(r=>r||caches.match('./')));
    else e.respondWith(netFirst(req));
  }
});
