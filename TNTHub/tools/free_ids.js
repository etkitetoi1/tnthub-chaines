// A coller dans la console (F12) sur https://tv.free.fr/, connecté, liste des chaînes visible.
// La liste est "virtuelle" : seules les lignes visibles existent. Le script les collecte au fil du défilement.
// 1) Colle le script. 2) Il fait défiler la liste tout seul ; si le compteur ne monte plus, fais défiler
//    la liste à la main de haut en bas. 3) Tape  dump()  dans la console : la liste est copiée.
(() => {
  const out = {};
  const scan = () => document.querySelectorAll('a[href*="included_channels/"]').forEach(a => {
    const m = a.getAttribute('href').match(/included_channels\/(\d+)/);
    if (!m) return;
    const name = (a.getAttribute('aria-label') || a.innerText || a.textContent || '').trim().replace(/\s+/g, ' ');
    if (name) out[name] = +m[1];
  });
  new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
  scan();
  const scrollers = () => [...document.querySelectorAll('*')].filter(e => e.scrollHeight > e.clientHeight + 50 && /(auto|scroll)/.test(getComputedStyle(e).overflowY));
  let n = 0;
  const t = setInterval(() => {
    scrollers().forEach(e => e.scrollBy(0, Math.max(200, e.clientHeight * 0.8)));
    scan();
    if (++n > 150) clearInterval(t);
    console.log(Object.keys(out).length + ' chaînes trouvées');
  }, 400);
  window.dump = () => { clearInterval(t); const s = JSON.stringify(out, null, 1); console.log(s); try { copy(s); console.log('Copié dans le presse-papiers'); } catch (e) {} return out; };
})();
