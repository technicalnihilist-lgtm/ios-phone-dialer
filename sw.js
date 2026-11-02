const CACHE = "phone-v2";
const ASSETS = [
  "./", "./index.html", "./manifest.webmanifest",
  "./icons/icon-180.png", "./icons/icon-192.png", "./icons/icon-512.png"
];

self.addEventListener("install", e => {
  e.waitUntil(
    caches.open(CACHE)
      /* one missing asset used to fail the whole install, so the app never updated */
      .then(c => Promise.all(ASSETS.map(u => c.add(u).catch(() => {}))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", e => {
  const req = e.request;
  if (req.method !== "GET") return;
  let url;
  try { url = new URL(req.url); } catch (err) { return; }
  /* only our own files: third-party responses were being cached for ever */
  if (url.origin !== self.location.origin) return;

  const store = res => {
    if (res && res.ok) {                    /* errors used to be cached as if they were assets */
      const copy = res.clone();
      caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
    }
    return res;
  };

  if (req.mode === "navigate") {
    /* network first, so a new build actually reaches an installed app instead of
       serving the first index.html this device ever saw */
    e.respondWith(
      fetch(req).then(store)
        .catch(() => caches.match(req).then(hit => hit || caches.match("./index.html"))
          /* with nothing cached this resolved to undefined, which respondWith()
             rejects with a TypeError instead of showing an offline page */
          .then(hit => hit || Response.error()))
    );
    return;
  }

  /* serve the cached copy at once, and refresh it in the background. A plain
     cache-first answer was never replaced, so an icon or the manifest kept the
     bytes from the first install until sw.js itself happened to change. */
  e.respondWith(
    caches.match(req).then(hit => {
      const fresh = fetch(req).then(store).catch(() => null);
      return hit || fresh.then(res => res || Response.error());
    })
  );
});
