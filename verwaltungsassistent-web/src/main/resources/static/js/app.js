// app.js — Verwaltungsassistent application utilities

document.body.addEventListener('htmx:configRequest', (event) => {
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (csrfToken && csrfHeader) {
        event.detail.headers[csrfHeader] = csrfToken;
    }
});

// PDF-Aktionen (öffnen/herunterladen): beim Klick deaktivieren + deutlichen
// Lade-Zustand zeigen, damit nicht versehentlich doppelt geklickt wird.
// Der Browser meldet für einen Link-Download KEIN Abschluss-Ereignis — die
// Wiederverfügbarkeit wird daher nach einer konservativen Dauer wiederher-
// gestellt, die die PDF-Erzeugung (ein paar Sekunden) abdeckt. Die Operation
// selbst ist idempotent (reine GET-Auslieferung aus dem gespeicherten
// Ergebnis) — ein erneuter Klick kann keine doppelte Arbeit anstoßen.
document.body.addEventListener('click', (event) => {
    const link = event.target && event.target.closest
        ? event.target.closest('a[data-pdf-action]')
        : null;
    if (!link || link.dataset.pdfBusy) {
        return;
    }
    link.dataset.pdfBusy = '1';
    link.classList.add('is-pdf-busy');
    const originalText = link.textContent;
    link.innerHTML = '<span class="pdf-busy-spinner" aria-hidden="true"></span> PDF wird vorbereitet …';
    setTimeout(() => {
        delete link.dataset.pdfBusy;
        link.classList.remove('is-pdf-busy');
        link.textContent = originalText;
    }, 4000);
});

document.body.addEventListener('htmx:responseError', (event) => {
    const xhr = event.detail.xhr;
    if (xhr.status === 401) {
        window.location.href = '/login';
        return;
    }
    if (xhr.status === 403) {
        window.location.href = '/error/403';
        return;
    }
    if (xhr.status >= 500) {
        event.preventDefault();
        showToast('Ein unerwarteter Fehler ist aufgetreten.', 'error');
    }
});

function showToast(message, type) {
    const container = document.getElementById('toast-container');
    if (!container) return;
    const toast = document.createElement('div');
    toast.className = 'message message--' + type;
    toast.textContent = message;
    toast.setAttribute('role', 'alert');
    container.appendChild(toast);
    setTimeout(() => {
        toast.remove();
    }, 5000);
}

/**
 * Tab selection for the tabBar component: moves the active class and
 * aria-selected state and syncs the Alpine scope that controls the
 * x-show visibility of the tab panels.
 */
function tabBarSelect(button) {
    const container = button.closest('.tab-bar-container');
    if (!container) return;
    if (window.Alpine) {
        const data = window.Alpine.$data(container);
        if (data) data.activeTab = button.dataset.tab;
    }
    container.querySelectorAll('.tab-bar__tab').forEach(tab => {
        const active = tab.dataset.tab === button.dataset.tab;
        tab.classList.toggle('tab-bar__tab--active', active);
        tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
}

/**
 * Generic liveness animation for the shared progress panel
 * (fragments/progress :: progressPanel): the panel title AND the active
 * stage line (the one prefixed with "●") cycle ". .. ..." once per second
 * while a long-running operation is active. The cycle state lives outside
 * the swapped DOM, so htmx polling swaps do not reset it; it stops when
 * the final result replaces the panel.
 * The assistant page has its own inline implementation (chat.html) and
 * is skipped here on purpose.
 */
(function () {
    var dotsTimer = null;
    var dotStep = 0;
    var activeTitle = null;
    var baseTitle = '';
    var activeLine = null;
    var baseLine = '';
    var dots = ['.', '..', '...'];

    function stopDots() {
        if (dotsTimer) { clearInterval(dotsTimer); dotsTimer = null; }
        activeTitle = null;
        baseTitle = '';
        activeLine = null;
        baseLine = '';
        dotStep = 0;
    }

    function applyDots(el, base) {
        el.textContent = base + ' ' + dots[dotStep];
    }

    function tick() {
        if (!dotsTimer) return;
        var titleAlive = activeTitle && document.body.contains(activeTitle);
        var lineAlive = activeLine && document.body.contains(activeLine);
        if (!titleAlive && !lineAlive) { stopDots(); return; }
        dotStep = (dotStep + 1) % dots.length;
        if (titleAlive) applyDots(activeTitle, baseTitle);
        if (lineAlive) applyDots(activeLine, baseLine);
    }

    function ensureAnimation(title) {
        if (!title || title.closest('#assistant-result')) return;
        var base = title.textContent.trim().replace(/[….\s]+$/, '');
        if (!base) return;
        if (activeTitle !== title) {
            activeTitle = title;
            baseTitle = base;
        }
        applyDots(title, baseTitle);
        if (!dotsTimer) { dotsTimer = setInterval(tick, 1000); }
    }

    function ensureLineAnimation(line) {
        if (!line || line.closest('#assistant-result')) return;
        var base = line.textContent.trim().replace(/[….\s]+$/, '');
        if (!base) return;
        if (activeLine !== line) {
            activeLine = line;
            baseLine = base;
        }
        applyDots(line, baseLine);
        if (!dotsTimer) { dotsTimer = setInterval(tick, 1000); }
    }

    // Checklist changes affect the phase gate shown in the phase header;
    // the checklist endpoints respond with HX-Trigger:refreshPhaseState and
    // this listener re-renders the phase state fragment.
    document.body.addEventListener('refreshPhaseState', function (evt) {
        const url = evt.detail && evt.detail.value;
        if (url && window.htmx) {
            htmx.ajax('GET', url, { target: '#phase-state', swap: 'outerHTML' });
        }
    });

    // ── Beleg-Detail (Evidence Explorer): hebt die spezifischen Begriffe des
    // Falls im belegten Auszug hervor (gelb, wie ein Textmarker). Die Begriffe
    // liefert der Server (analysisHighlightTerms = Stoppwort-gefilterte
    // Themenwörter des Falls) — niemals generische Boilerplate-Wörter der
    // Analyseanweisung ("den Fall", "Empfehlung", …), die den Eindruck von
    // Relevanz erzeugen, wo keine ist. Nur der Originaltext wird hervorgehoben
    // — nichts wird erfunden oder weggelassen.
    function highlightEvidence(modalEl) {
        if (!modalEl || !modalEl.querySelector) return;
        var quote = modalEl.querySelector('.evidence-highlight');
        if (!quote) return;
        var question = (modalEl.closest && modalEl.closest('[data-question]')
            ? modalEl.closest('[data-question]').getAttribute('data-question')
            : modalEl.getAttribute('data-question')) || '';
        var termsAttr = modalEl.getAttribute('data-highlight-terms');
        if (!quote.dataset.original) {
            quote.dataset.original = quote.textContent;
        }
        var text = quote.dataset.original;
        var terms;
        if (termsAttr && termsAttr.trim()) {
            // Server-gelieferte spezifische Begriffe (bereits Stoppwort-gefiltert).
            terms = [];
            var seen = {};
            termsAttr.split(/\s+/).forEach(function (w) {
                if (!w || seen[w]) return;
                seen[w] = true;
                terms.push(w);
                germanVariants(w).forEach(function (v) {
                    if (!seen[v]) { seen[v] = true; terms.push(v); }
                });
            });
        } else {
            terms = extractEvidenceTerms(question);
        }
        if (terms.length === 0) {
            quote.textContent = text;
            return;
        }
        var pattern = terms.map(escapeRegExp).sort(function (a, b) { return b.length - a.length; }).join('|');
        var re = new RegExp('\\b(' + pattern + ')\\b', 'gi');
        quote.innerHTML = text.replace(re, '<mark class="evidence-mark">$1</mark>');
    }

    function extractEvidenceTerms(question) {
        var words = (question.toLowerCase().match(/[a-zäöüß0-9]+/g) || [])
            .filter(function (w) { return w.length >= 3; });
        var terms = [];
        var seen = {};
        function add(t) {
            if (t.length < 4 || seen[t]) return;
            seen[t] = true;
            terms.push(t);
        }
        // Prefer coherent phrases: include n-grams up to 4 words.
        for (var i = 0; i < words.length; i++) {
            for (var n = 1; n <= 4 && i + n <= words.length; n++) {
                add(words.slice(i, i + n).join(' '));
            }
        }
        // Single-word morphological variants remain useful for inflections.
        words.forEach(function (w) {
            germanVariants(w).forEach(add);
        });
        return terms;
    }

    function germanVariants(w) {
        var prefixes = ['ueber', 'uber', 'unter', 'an', 'ab', 'um', 'aus', 'auf', 'ein',
            'vor', 'nach', 'mit', 'bei', 'ver', 'be', 'ent', 'er', 'ge'];
        var out = [];
        for (var i = 0; i < prefixes.length; i++) {
            var p = prefixes[i];
            if (w.indexOf(p) === 0 && w.length > p.length + 3) {
                out.push(w.substring(p.length));
            }
        }
        return out;
    }

    function escapeRegExp(s) {
        return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    // Alpine expressions resolve against window — expose the highlighter there
    // (the IIFE scope alone would hide it from x-init/x-effect).
    window.highlightEvidence = highlightEvidence;

    // Alpine does not process directives in htmx-swapped content on its own;
    // re-initialize the swapped subtree so modals and other x-* directives work.
    // outerHTML swaps (e.g. the Fälle filter list) detach the old target: htmx
    // reports the REMOVED element, and Alpine 3.14's mutation observer already
    // initializes the live replacement, so re-initializing the detached tree
    // is pointless and can race with the swap (intermittent open modals).
    document.body.addEventListener('htmx:afterSwap', function (evt) {
        if (!window.Alpine || !evt.detail.target) return;
        if (!document.body.contains(evt.detail.target)) return;
        if (evt.detail.target.querySelector) {
            try {
                window.Alpine.initTree(evt.detail.target);
            } catch (err) {
                // ignore; Alpine logs its own errors for malformed subtrees
            }
        }
    });

    // Progress-panel liveness animation, scoped strictly to the triggering
    // element: for any swap NOT initiated by the progress-panel poll this
    // handler runs no document-level query at all. (A former version that
    // probed the DOM on every htmx swap intermittently opened confirm modals
    // on other pages — an Alpine/htmx race this version cannot hit.)
    document.body.addEventListener('htmx:afterSwap', function (evt) {
        var elt = evt.detail && evt.detail.elt;
        if (!elt || !elt.closest) return;
        var block = elt.closest('.progress-block');
        if (!block) return;
        var title = block.querySelector('.progress-panel__title');
        var line = block.querySelector('.progress-terminal__line--active .progress-terminal__message');
        if (title) {
            ensureAnimation(title);
        } else if (activeTitle && !document.body.contains(activeTitle)) {
            stopDots();
        }
        if (line) {
            ensureLineAnimation(line);
        } else if (activeLine && !document.body.contains(activeLine)) {
            stopDots();
        }
    });

    // ══ Automatic inactivity logout ══
    // Only REAL user interaction (mouse/keyboard/touch/navigation, throttled)
    // sends the /session/activity beacon — background polling and other AJAX
    // requests never keep an inactive user alive. The server is authoritative:
    // it expires the session after app.security.inactivity-timeout-minutes.
    // The client shows a warning with a 60-second countdown shortly before.
    (function () {
        var timeoutSeconds = parseInt(document.body.getAttribute('data-inactivity-timeout') || '0', 10);
        if (timeoutSeconds <= 0) return;
        if (window.location.pathname.startsWith('/login')) return;

        var BEACON_INTERVAL = 60 * 1000;      // throttle: at most one beacon per minute
        // 3 Minuten Vorwarnzeit statt 60 Sekunden: die Nutzerin hat genug Zeit,
        // den „Angemeldet bleiben"-Button zu erreichen und zu klicken.
        var WARNING_BEFORE = 180;             // seconds before expiry the warning appears
        var lastBeaconAt = 0;
        var lastUserEventAt = Date.now();
        var countdownRemaining = -1;
        var countdownTimer = null;
        var warning = document.getElementById('inactivity-warning');
        var warningText = document.getElementById('inactivity-warning-text');
        var keepAlive = document.getElementById('inactivity-keep-alive');

        function sendBeacon() {
            if (Date.now() - lastBeaconAt < BEACON_INTERVAL) return;
            lastBeaconAt = Date.now();
            lastUserEventAt = Date.now();
            var csrfMeta = document.querySelector('meta[name="_csrf"]');
            var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
            var headers = {};
            if (csrfMeta && csrfHeader) headers[csrfHeader.content] = csrfMeta.content;
            fetch('/session/activity', { method: 'POST', headers: headers, credentials: 'same-origin' }).catch(function () {});
        }

        function hideWarning() {
            if (warning) warning.style.display = 'none';
            if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
            countdownRemaining = -1;
        }

        function showCountdown(seconds) {
            if (!warning || !warningText) return;
            warning.style.display = 'flex';
            warningText.textContent = 'Sie waren seit ' + Math.round(timeoutSeconds / 60) +
                ' Minuten nicht aktiv. Sie werden in ' + seconds + ' Sekunden automatisch abgemeldet.';
            if (countdownTimer) clearInterval(countdownTimer);
            countdownRemaining = seconds;
            countdownTimer = setInterval(function () {
                countdownRemaining--;
                if (countdownRemaining <= 0) {
                    hideWarning();
                    window.location.href = '/login?expired=inactivity';
                    return;
                }
                warningText.textContent = 'Sie waren seit ' + Math.round(timeoutSeconds / 60) +
                    ' Minuten nicht aktiv. Sie werden in ' + countdownRemaining + ' Sekunden automatisch abgemeldet.';
            }, 1000);
        }

        // throttled real-activity tracking
        function onUserActivity() {
            var now = Date.now();
            lastUserEventAt = now;
            if (countdownRemaining >= 0) {
                sendBeacon();       // "Angemeldet bleiben" via real interaction
                hideWarning();
            } else if (now - lastBeaconAt >= BEACON_INTERVAL) {
                sendBeacon();
            }
        }
        ['mousemove', 'mousedown', 'keydown', 'touchstart', 'wheel'].forEach(function (evt) {
            document.addEventListener(evt, function () {
                // mousemove fires constantly; only react to real movement, not hover
                onUserActivity();
            }, { passive: true });
        });
        // throttle mousemove specifically: ignore it entirely when the warning
        // is hidden and a beacon was recently sent
        document.addEventListener('mousemove', function () {
            if (Date.now() - lastUserEventAt < 5000) return;
            onUserActivity();
        }, { passive: true });

        if (keepAlive) {
            keepAlive.addEventListener('click', function () {
                sendBeacon();
                hideWarning();
            });
        }

        // Warning + server-side expiry supervision
        setInterval(function () {
            var idleSeconds = Math.floor((Date.now() - lastUserEventAt) / 1000);
            if (countdownRemaining < 0 && idleSeconds >= timeoutSeconds - WARNING_BEFORE) {
                showCountdown(timeoutSeconds - idleSeconds);
            }
        }, 5000);
    })();
})();

// ── Globale Such-/Navigations-Palette (Strg+K) ───────────────────────────
// Bewusst als einfaches, deterministisches Vanilla-JS-Modul statt Alpine:
// Die Alpine-Initialisierung war hier unzuverlässig (CDN-Start griff teils
// nicht), wodurch die Palette sichtbar "hängen" blieb. Öffnen: Strg+K oder
// der Such-Button in der Topbar. Schließen: Esc, Klick auf den Hintergrund
// oder die ×-Schaltfläche. Filtert die Einträge live; Enter führt die
// echte Wissensbasis-Suche aus (form action=/knowledge).
window.Verwaltungsassistent = window.Verwaltungsassistent || {};
window.Verwaltungsassistent.palette = (function () {
    var overlay = null;
    var input = null;
    var lastFocus = null;

    function els() {
        if (!overlay) {
            overlay = document.getElementById('command-palette');
            input = document.getElementById('palette-input');
        }
        return overlay;
    }
    function isOpen() {
        var o = els();
        return !!o && o.style.display !== 'none';
    }
    function open() {
        var o = els();
        if (!o || isOpen()) { return; }
        lastFocus = document.activeElement;
        o.style.display = 'flex';
        document.body.style.overflow = 'hidden';
        if (input) {
            input.value = '';
            filterItems('');
            input.focus();
        }
    }
    function close() {
        var o = els();
        if (!o || !isOpen()) { return; }
        o.style.display = 'none';
        document.body.style.overflow = '';
        if (lastFocus && typeof lastFocus.focus === 'function') { lastFocus.focus(); }
    }
    function filterItems(raw) {
        var o = els();
        if (!o) { return; }
        var q = (raw || '').toLowerCase();
        var items = o.querySelectorAll('.palette-item');
        for (var i = 0; i < items.length; i++) {
            var show = !q || items[i].textContent.toLowerCase().indexOf(q) !== -1;
            items[i].style.display = show ? '' : 'none';
        }
        var hint = document.getElementById('palette-hint');
        if (hint) { hint.style.display = q ? '' : 'none'; }
    }
    function init() {
        var o = els();
        if (!o) { return; }
        // Hintergrund-Klick schließt (nur wenn wirklich der Overlay selbst Ziel ist)
        o.addEventListener('click', function (e) { if (e.target === o) { close(); } });
        var x = document.getElementById('palette-close');
        if (x) { x.addEventListener('click', close); }
        if (input) { input.addEventListener('input', function () { filterItems(input.value); }); }
        var btns = document.querySelectorAll('#topbar-search, .topbar-search');
        for (var i = 0; i < btns.length; i++) {
            btns[i].addEventListener('click', function (e) { e.preventDefault(); open(); });
        }
    }
    document.addEventListener('keydown', function (e) {
        if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
            e.preventDefault();
            open();
        } else if (e.key === 'Escape' && isOpen()) {
            close();
        }
    });
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
    return { open: open, close: close, isOpen: isOpen };
})();

// ── Pipeline-Visualisierung: Info-Zeile unter der Knotenleiste ─────────────
// Hover/Fokus zeigt die Detail-Info des Knotens in der reservierten
// Info-Zeile, Klick pinst sie an. Die Daten stammen aus den data-Attributen
// der Knoten (echte Laufzeitwerte vom Server); textContent statt innerHTML,
// damit nie Markup aus den Infotexten ausgeführt wird.
document.addEventListener('mouseover', function (evt) {
    var node = evt.target.closest ? evt.target.closest('.pipeline-node') : null;
    if (node) showPipelineInfo(node, false);
});
document.addEventListener('mouseout', function (evt) {
    var node = evt.target.closest ? evt.target.closest('.pipeline-node') : null;
    if (node && !node.classList.contains('pipeline-node--pinned')) showPipelineInfo(null, false);
});
document.addEventListener('click', function (evt) {
    var node = evt.target.closest ? evt.target.closest('.pipeline-node') : null;
    if (!node) return;
    var diagram = node.closest('.pipeline-diagram');
    if (!diagram) return;
    var wasPinned = node.classList.contains('pipeline-node--pinned');
    diagram.querySelectorAll('.pipeline-node--pinned').forEach(function (n) {
        n.classList.remove('pipeline-node--pinned');
    });
    if (!wasPinned) {
        node.classList.add('pipeline-node--pinned');
        node.focus();
        showPipelineInfo(node, true);
    } else {
        showPipelineInfo(null, true);
    }
});
document.addEventListener('focusin', function (evt) {
    var node = evt.target.closest ? evt.target.closest('.pipeline-node') : null;
    if (node) showPipelineInfo(node, false);
});
function showPipelineInfo(node, pinned) {
    var diagram = node ? node.closest('.pipeline-diagram') : null;
    if (!diagram) return;
    var bar = diagram.querySelector('.pipeline-info');
    if (!bar) return;
    var text = bar.querySelector('.pipeline-info__text');
    if (!text) {
        text = document.createElement('span');
        text.className = 'pipeline-info__text';
        bar.appendChild(text);
    }
    if (node) {
        var label = node.dataset.label || '';
        var info = node.dataset.info || '';
        text.textContent = label + ' — ' + info;
    } else {
        var def = bar.dataset.default || '';
        text.textContent = def;
    }
}

// ── Reservierter PDF-Tab (Popup-Blocker-sicherer Auto-Öffnen-Pfad) ─────────
// Jeder "Analyse starten"-Klick (hx-post .../decision/analyze) öffnet SOFORT
// innerhalb der Nutzer-Geste einen Tab mit der Warteseite; die Warteseite
// pollt den Analyse-Zustand und navigiert den bereits offenen Tab nach
// Abschluss selbst zur fertigen PDF. window.open nach einer asynchronen
// Analyse würde der Browser blockieren — deshalb kein Auto-Öffnen im
// Abschluss-Fragment.
var __decisionPdfWaitTab = null;

window.openPdfWaitTab = function (waitUrl) {
    var w = __decisionPdfWaitTab;
    if (w && !w.closed) {
        w.focus();
        return;
    }
    w = window.open(waitUrl, '_blank');
    if (w) {
        __decisionPdfWaitTab = w;
    }
};

document.addEventListener('click', function (evt) {
    var el = evt.target && evt.target.closest ? evt.target.closest('button') : null;
    if (!el) return;
    var postUrl = el.getAttribute('hx-post');
    var form = el.form;
    var formAction = form ? (form.getAttribute('hx-post') || form.getAttribute('action') || '') : '';
    var url = (postUrl || formAction || '');
    if (url.indexOf('/decision/analyze') === -1) return;
    // Vorgangs-ID aus dem aktuellen Pfad ableiten (/cases/{id} oder /cases/{id}/decision*).
    var m = window.location.pathname.match(/\/cases\/([^/]+)(?=\/|$)/);
    if (!m) return;
    window.openPdfWaitTab('/cases/' + encodeURIComponent(m[1]) + '/decision/pdf-wait');
});

// ── Arbeitspool View-Lease: Heartbeat + Freigabe (Fall-Ansicht) ────────────
// Solange eine Mitarbeiterin einen freien Pool-Vorgang geöffnet hat, hält sie
// einen temporären Anspruch (Lease). Heartbeat ≈ alle 25 s verlängert ihn;
// beim Verlassen wird er explizit freigegeben (fetch keepalive — sendBeacon
// kann keine CSRF-Header setzen). Sicherheitsnetz bei Crash/Tab-Verlust ist
// der serverseitige Lease-Ablauf (≈ 60 s), nicht dieses Script.
(function () {
    var m = window.location.pathname.match(/^\/cases\/([0-9a-fA-F-]{36})$/);
    if (!m) return;
    var caseId = m[1];
    var csrfMeta = document.querySelector('meta[name="_csrf"]');
    var csrfHeader = document.querySelector('meta[name="_csrf_header"]');
    function csrfHeaders() {
        var h = {};
        if (csrfMeta && csrfHeader) h[csrfHeader.content] = csrfMeta.content;
        return h;
    }
    function post(path) {
        fetch(path, { method: 'POST', headers: csrfHeaders(), credentials: 'same-origin',
                      keepalive: true }).catch(function () {});
    }
    var heartbeat = window.setInterval(function () {
        post('/cases/' + caseId + '/view-claim/heartbeat');
    }, 25 * 1000);
    window.addEventListener('pagehide', function () {
        window.clearInterval(heartbeat);
        post('/cases/' + caseId + '/view-claim/release');
    });
})();
