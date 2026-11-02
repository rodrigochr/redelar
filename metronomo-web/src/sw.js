// Service worker do BluesTempo: guarda o app e os sons para funcionar offline.
const CACHE = 'bluestempo-__VERSION__';
const SHELL = ['./', 'index.html', 'app.css', 'app.js?v=__VERSION__', 'samples.bin', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k.startsWith('bluestempo-') && k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) {
    // Fontes do Google: usa a rede e guarda uma cópia para uso offline.
    if (url.hostname.endsWith('googleapis.com') || url.hostname.endsWith('gstatic.com')) {
      event.respondWith(
        caches.open(CACHE).then((cache) => fetch(request).then((res) => {
          cache.put(request, res.clone());
          return res;
        }).catch(() => cache.match(request))),
      );
    }
    return;
  }
  // Página: rede primeiro (pega atualizações), cache se offline. Demais arquivos: cache primeiro.
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('index.html')));
    return;
  }
  event.respondWith(caches.match(request).then((hit) => hit || fetch(request)));
});
