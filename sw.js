var V="tyt-panel-v1";
var CORE=["./","index.html","manifest.webmanifest","icon-180.png","icon-192.png","icon-512.png"];
self.addEventListener("install",function(e){
  e.waitUntil(caches.open(V).then(function(c){return c.addAll(CORE);})
    .catch(function(){}).then(function(){return self.skipWaiting();}));
});
self.addEventListener("activate",function(e){
  e.waitUntil(caches.keys().then(function(ks){
    return Promise.all(ks.map(function(k){if(k!==V)return caches.delete(k);}));
  }).then(function(){return self.clients.claim();}));
});
self.addEventListener("fetch",function(e){
  var req=e.request;if(req.method!=="GET")return;
  var url=new URL(req.url);
  if(req.mode==="navigate"){            /* sayfa: once ag, kopamazsa onbellek */
    e.respondWith(fetch(req).then(function(r){
      var c=r.clone();caches.open(V).then(function(x){x.put("index.html",c);});return r;
    }).catch(function(){
      return caches.match("index.html").then(function(m){return m||caches.match("./");});
    }));
    return;
  }
  var ok=url.origin===location.origin||/gstatic|googleapis/.test(url.host);
  e.respondWith(caches.match(req).then(function(hit){
    if(hit)return hit;
    return fetch(req).then(function(r){
      if(ok&&r&&r.status===200){var c=r.clone();caches.open(V).then(function(x){x.put(req,c);});}
      return r;
    }).catch(function(){return new Response("",{status:504});});
  }));
});
