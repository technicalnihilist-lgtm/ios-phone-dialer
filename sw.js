/* The name of the cache this build writes, and the only thing that makes the
   worker re-install: a service worker is updated by a byte change in this file
   and nothing else, so a constant here meant `install` ran once, the first time
   the app was ever opened, and never again. Everything it precaches was then
   fixed at that first install: the navigate path below does refresh
   ./index.html from the network on every online open, but nothing ever refreshed
   the "./" entry beside it, so an installed app opened from the site root while
   offline served the very first build's page — months of releases behind, with
   no way to notice. Keep the build's version in this string and bump it with
   the manifest; install then runs once per release, precaches both entries from
   the network, and the activate handler drops the previous cache. */
const CACHE = "phone-2.6.40";
/* every cache this app owns starts with this, so the activate handler below can
   tell its own from the ones other apps on the same origin keep */
const PREFIX = "phone-";
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
      /* Cache storage is per origin, and this app is published from a project
         page on a shared host: every other app served from the same account
         keeps its caches in the same store. This deleted every cache on the
         origin that was not this one, so activating the dialer's worker wiped
         the caches of unrelated apps — their next load found nothing cached and
         had to fetch every asset again. Only the caches this app wrote are ours
         to drop, so match them by name. */
      .then(keys => Promise.all(keys.filter(k => k !== CACHE && k.indexOf(PREFIX) === 0)
        .map(k => caches.delete(k))))
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

  /* Returns a promise for the response, and that promise does not settle until
     the cache write has finished. The write used to be started here and left
     floating, so the promise handed to respondWith()/waitUntil() settled as soon
     as the network answered — the worker was free to be torn down with the put
     still in flight, which is exactly the half-written cache this was meant to
     prevent. A failed or non-2xx response is still never cached. */
  const store = res => {
    if (!res || !res.ok) return Promise.resolve(res);
    const copy = res.clone();
    return caches.open(CACHE)
      .then(c => c.put(req, copy))
      .then(() => res, () => res);
  };

  /* the cached copy, or a network error response when there is not one */
  const offline = () => caches.match(req)
    .then(hit => hit || caches.match("./index.html"))
    /* with nothing cached this resolved to undefined, which respondWith()
       rejects with a TypeError instead of showing an offline page */
    .then(hit => hit || Response.error())
    /* ...and the cache itself can refuse to answer: storage turned off, the
       quota gone, an entry that will not deserialize. That rejection used to
       travel straight into respondWith()/waitUntil(), which turns it into the
       browser's own network error page in place of the app — the one outcome
       the fallback chain above exists to prevent. Answer with an error
       response instead. */
    .catch(() => Response.error());

  if (req.mode === "navigate") {
    /* network first, so a new build actually reaches an installed app instead of
       serving the first index.html this device ever saw */
    e.respondWith(
      fetch(req).then(res => {
        /* An error status is not the app. This handed back whatever the host
           answered — a 404 from a moved file, a 500 or a 502 from a host that
           was mid-deploy, the error page of a proxy — and the offline copy was
           only ever reached when fetch() rejected outright. A working,
           installed dialer was then replaced by an error page until the next
           successful load. Anything that is not a success now falls back to
           the cache exactly like a failed request does. */
        if (!res || !res.ok) return offline();
        return store(res);
      }).catch(offline)
    );
    return;
  }

  /* serve the cached copy at once, and refresh it in the background. A plain
     cache-first answer was never replaced, so an icon or the manifest kept the
     bytes from the first install until sw.js itself happened to change. */
  /* the refresh has to be handed to waitUntil: answering from the cache settles
     the response straight away, and nothing else then keeps the worker alive,
     so the cache write could be cut off half way and the stale copy stayed. */
  const fresh = fetch(req).then(store).catch(() => null);
  e.waitUntil(fresh);
  e.respondWith(
    caches.match(req).then(hit => hit || fresh.then(res => res || Response.error()))
      /* The navigate path above already answers a cache that refuses to answer
         with an error response, because a rejection handed to respondWith()
         replaces the app with the browser's own network-error page. This path
         did not: caches.match() rejecting here — storage turned off, the quota
         gone, an entry that will not deserialize — failed the response for
         every icon, the manifest and every other asset the page asks for. Same
         rule as offline(), on the one branch that was missing it. */
      .catch(() => Response.error())
  );
});
