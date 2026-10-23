(function () {
    'use strict';

    const feed = document.getElementById('feed');
    const empty = document.getElementById('empty');
    const screenBar = document.getElementById('screen-bar');
    const filter = document.getElementById('filter');
    const count = document.getElementById('count');
    const pauseButton = document.getElementById('pause');
    const searchButton = document.getElementById('search');
    const pausedBar = document.getElementById('paused-bar');
    const pendingCount = document.getElementById('pending');
    const entries = new Map();
    const activeColors = new Set();
    let screens = [];
    let current = null;
    let paused = false;
    const MAX_SCREENS = 10;
    const MAX_ENTRIES = 1500;
    let allProjects = false;
    let pending = [];

    const COPY_ICON = '<svg viewBox="0 0 16 16"><path d="M5 1.5h7.5A1.5 1.5 0 0 1 14 3v8.5a1.5 1.5 0 0 1-1.5 1.5H11v-1.5h1.5V3H5v1.5H3.5V3A1.5 1.5 0 0 1 5 1.5zM2 6.5A1.5 1.5 0 0 1 3.5 5h6A1.5 1.5 0 0 1 11 6.5v6A1.5 1.5 0 0 1 9.5 14h-6A1.5 1.5 0 0 1 2 12.5v-6zm1.5 0v6h6v-6h-6z"/></svg>';
    const CHECK_ICON = '<svg viewBox="0 0 16 16"><path d="M13.3 3.8l1.1 1.1-7.6 7.6-4.2-4.2 1.1-1.1 3.1 3.1z"/></svg>';

    // JCEF renders off-screen and may leave stale pixels where content shrank (e.g. the setup guide going from
    // "setup" to "ready"); touching the root forces a full repaint
    function repaintAll() {
        const root = document.documentElement;
        root.style.opacity = '0.999';
        requestAnimationFrame(function () {
            requestAnimationFrame(function () { root.style.opacity = ''; });
        });
    }

    function post(msg) {
        if (window.dumpiePost) window.dumpiePost(JSON.stringify(msg));
    }

    function esc(value) {
        return String(value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function basename(path) {
        return String(path).split(/[\\/]/).pop();
    }

    // innerHTML does not run scripts; VarDumper output needs them to become interactive
    function setHtml(el, html) {
        el.innerHTML = html;
        el.querySelectorAll('script').forEach(function (old) {
            const s = document.createElement('script');
            s.textContent = old.textContent;
            old.replaceWith(s);
        });
    }

    function isDump(value) {
        return typeof value === 'string' && value.indexOf('sf-dump') !== -1;
    }

    // Values coming from ArgumentConverter: scalars as-is, everything else as VarDumper HTML
    function primitive(value) {
        if (value === null || value === undefined) return '<pre class="box">null</pre>';
        if (typeof value === 'boolean' || typeof value === 'number') return '<pre class="box">' + value + '</pre>';
        if (isDump(value)) return value;
        if (typeof value === 'object') return '<pre class="box">' + esc(JSON.stringify(value, null, 2)) + '</pre>';
        return text(value);
    }

    function text(value) {
        return '<div class="text">' + esc(value) + '</div>';
    }

    function fileLink(file, line, label) {
        return '<a data-file="' + esc(file) + '" data-line="' + (line || 1) + '" title="' + esc(file) + ':' + (line || 1) + '">'
            + esc(label || (basename(file) + ':' + (line || 1))) + '</a>';
    }

    // Table cells may carry HTML from the PHP side (laravel-ray decorates cache/.env values), so strings are not escaped
    function kvTable(obj) {
        if (!obj || typeof obj !== 'object') return primitive(obj);
        let rows = '';
        Object.keys(obj).forEach(function (key) {
            const value = obj[key];
            let cell;
            if (typeof value === 'string' && !isDump(value)) cell = '<div class="text">' + value + '</div>';
            else if (value === null || typeof value === 'number' || typeof value === 'boolean') cell = '<div class="text">' + String(value) + '</div>';
            else cell = primitive(value);
            rows += '<tr><td>' + esc(key) + '</td><td>' + cell + '</td></tr>';
        });
        return '<table class="kv">' + rows + '</table>';
    }

    function frameWhere(f) {
        return (f.class ? f.class + '::' : '') + (f.method || '') || '{main}';
    }

    // Consecutive vendor frames are folded into one expandable row
    function frames(list) {
        let html = '<ul class="frames">';
        let vendor = [];

        function flushVendor() {
            if (!vendor.length) return;
            html += '<li class="vendor-group"><details><summary>' + vendor.length + ' vendor frame' + (vendor.length > 1 ? 's' : '')
                + '</summary><ul class="frames">' + vendor.join('') + '</ul></details></li>';
            vendor = [];
        }

        (list || []).forEach(function (f) {
            const li = '<li><span class="frame-where mono">' + esc(frameWhere(f)) + '</span>'
                + fileLink(f.file_name, f.line_number) + '</li>';
            if (f.vendor_frame) {
                vendor.push(li);
            } else {
                flushVendor();
                html += li;
            }
        });
        flushVendor();
        return html + '</ul>';
    }

    function framesText(list) {
        return (list || []).map(function (f, i) {
            return '#' + i + ' ' + frameWhere(f) + ' ' + f.file_name + ':' + f.line_number;
        }).join('\n');
    }

    function snippet(frame) {
        if (!frame || !Array.isArray(frame.snippet) || !frame.snippet.length) return '';
        let rows = '';
        frame.snippet.forEach(function (row) {
            rows += '<div class="line' + (row.line_number === frame.line_number ? ' hl' : '') + '">'
                + '<span class="ln">' + row.line_number + '</span><span class="code">' + esc(row.text) + '</span></div>';
        });
        return '<div class="snippet mono">'
            + '<div class="snippet-file">' + fileLink(frame.file_name, frame.line_number) + '</div>'
            + rows + '</div>';
    }

    function className(fqcn) {
        const name = String(fqcn || '');
        const i = name.lastIndexOf('\\');
        if (i === -1) return '<b>' + esc(name) + '</b>';
        return '<span class="muted">' + esc(name.slice(0, i + 1)) + '</span><b>' + esc(name.slice(i + 1)) + '</b>';
    }

    function renderException(c) {
        const appFrame = (c.frames || []).find(function (f) { return !f.vendor_frame; }) || (c.frames || [])[0];
        return '<div class="exception-class mono">' + className(c.class) + '</div>'
            + '<div class="alert-message">' + esc(c.message) + '</div>'
            + snippet(appFrame)
            + frames(c.frames);
    }

    function persons(list) {
        return (list || []).map(function (p) {
            return p.name ? p.name + ' <' + p.email + '>' : p.email;
        }).join(', ');
    }

    function renderMail(c) {
        const head = {};
        if (c.subject) head['Subject'] = esc(c.subject);
        ['from', 'to', 'cc', 'bcc'].forEach(function (key) {
            const value = persons(c[key]);
            if (value) head[key.charAt(0).toUpperCase() + key.slice(1)] = esc(value);
        });
        // allow-same-origin without allow-scripts: the mail cannot run code, but we can read its height
        return (Object.keys(head).length ? kvTable(head) : '')
            + '<iframe class="mail" sandbox="allow-same-origin" srcdoc="' + esc(c.html || '') + '"></iframe>';
    }

    function renderQuery(c) {
        const meta = [c.connection_name, formatMs(c.time)].filter(Boolean).join(' · ');
        const bindings = Array.isArray(c.bindings) && c.bindings.length ? c.bindings : null;
        return '<pre class="box">' + esc(c.sql) + '</pre>'
            + (bindings ? '<div class="muted mono query-meta">bindings: ' + esc(JSON.stringify(bindings)) + '</div>' : '')
            + (meta ? '<div class="muted mono query-meta">' + esc(meta) + '</div>' : '');
    }

    function formatMs(ms) {
        return typeof ms === 'number' ? ms.toFixed(2) + ' ms' : '';
    }

    function formatBytes(bytes) {
        return typeof bytes === 'number' ? (bytes / 1048576).toFixed(2) + ' MB' : '';
    }

    function prettyJson(value) {
        try {
            return JSON.stringify(JSON.parse(value), null, 2);
        } catch (e) {
            return String(value);
        }
    }

    /**
     * Content payloads -> {label, html, copy}; `copy` is {lang, text} or null to copy the rendered text.
     * Returns null for payloads that only modify the entry.
     */
    function render(type, c, entry) {
        c = c || {};
        switch (type) {
            case 'log': {
                const values = c.values || [];
                const clip = values.length === 1 && c.meta && c.meta[0] && c.meta[0].clipboard_data;
                return {
                    label: '',
                    html: values.map(primitive).join(''),
                    copy: clip ? { lang: isDump(values[0]) ? 'php' : '', text: clip } : null
                };
            }
            case 'custom':
                if (c.content === null || typeof c.content === 'boolean' || typeof c.content === 'number') {
                    return { label: c.label || '', html: primitive(c.content), copy: { lang: '', text: String(c.content) } };
                }
                // ray()->file() labels the payload with the file name and sends <br>-separated, entity-encoded text
                if (/\.\w+$/.test(c.label || '')) {
                    return {
                        label: c.label,
                        html: '<div class="box mono file-content">' + c.content + '</div>',
                        copy: { lang: c.label.split('.').pop().toLowerCase(), text: null }
                    };
                }
                return { label: c.label || '', html: String(c.content), copy: c.label === 'XML' ? { lang: 'xml', text: null } : null };
            case 'table':
                return { label: c.label || 'Table', html: kvTable(c.values), copy: null };
            case 'json_string':
                return { label: 'JSON', html: '<pre class="box">' + esc(prettyJson(c.value)) + '</pre>', copy: { lang: 'json', text: prettyJson(c.value) } };
            case 'exception':
                entry.classList.add('alert', 'color-red');
                return {
                    label: '',
                    html: renderException(c),
                    copy: { lang: '', text: c.class + ': ' + c.message + '\n\n' + framesText(c.frames) }
                };
            case 'trace':
                return { label: 'Trace', html: frames(c.frames), copy: { lang: '', text: framesText(c.frames) } };
            case 'caller':
                return { label: 'Caller', html: frames([c.frame]), copy: { lang: '', text: framesText([c.frame]) } };
            case 'measure':
                if (c.is_new_timer) return { label: 'Measure', html: text('Timer "' + c.name + '" started'), copy: null };
                return {
                    label: 'Measure',
                    html: kvTable({
                        'Name': esc(c.name),
                        'Total time': formatMs(c.total_time),
                        'Since last call': formatMs(c.time_since_last_call),
                        'Max memory': formatBytes(c.max_memory_usage_during_total_time)
                    }),
                    copy: null
                };
            case 'application_log':
                // laravel-ray sends the level as a follow-up color request: red for errors, orange for warnings
                entry.classList.add('alert');
                return {
                    label: c.level || 'Log',
                    // Log files deliver the context/stack trace as plain text; laravel-ray sends a dump
                    html: '<div class="alert-message">' + esc(c.value) + '</div>'
                        + (typeof c.context === 'string' && !isDump(c.context) ? '<pre class="box">' + esc(c.context) + '</pre>' : (c.context ? primitive(c.context) : '')),
                    copy: null
                };
            case 'executed_query':
                return { label: 'Query', html: renderQuery(c), copy: { lang: 'sql', text: c.sql } };
            case 'event':
                return { label: 'Event', html: text(c.name) + (c.event ? primitive(c.event) : '') + (c.payload ? primitive(c.payload) : ''), copy: null };
            case 'job_event':
                return { label: 'Job', html: text(c.event_name) + (c.job ? primitive(c.job) : '') + (c.exception ? primitive(c.exception) : ''), copy: null };
            case 'eloquent_model':
                return { label: 'Model', html: text(c.class_name) + primitive(c.attributes), copy: null };
            case 'view':
                return { label: 'View', html: '<div class="text">' + fileLink(c.view_path, 1, c.view_path_relative_to_project_root) + '</div>' + primitive(c.data), copy: null };
            case 'mailable':
                return { label: 'Mail', html: renderMail(c), copy: { lang: 'html', text: c.html || '' } };
            case 'response':
                return {
                    label: 'Response ' + (c.status_code || ''),
                    html: primitive(c.headers) + (c.json ? primitive(c.json) : '<pre class="box">' + esc(c.content || '') + '</pre>'),
                    copy: null
                };
            case 'carbon':
                return { label: 'Carbon', html: kvTable({ 'Formatted': esc(c.formatted), 'Timestamp': c.timestamp, 'Timezone': esc(c.timezone) }), copy: null };
            case 'create_lock':
                return { label: 'Paused', html: lockControls(c.name), copy: null };
            case 'label':
                entry.querySelector('.entry-label').textContent = c.label || '';
                return null;
            case 'color':
                entry.className = entry.className.replace(/\bcolor-\w+/g, '').trim();
                entry.classList.add('color-' + c.color);
                return null;
            case 'size':
                entry.classList.remove('size-lg', 'size-sm');
                entry.classList.add('size-' + c.size);
                return null;
            case 'hide':
                entry.classList.add('collapsed');
                return null;
            default:
                return { label: type, html: '<pre class="box">' + esc(JSON.stringify(c, null, 2)) + '</pre>', copy: null };
        }
    }

    function lockControls(name) {
        return '<div class="lock" data-lock="' + esc(name) + '">'
            + '<span>Execution paused</span>'
            + '<button class="primary" data-lock-action="continue">Continue</button>'
            + '<button data-lock-action="stop">Stop</button>'
            + '</div>';
    }

    // ray()->expand(2), ->expand('key', 'a.b'), ->expandAll(): opens VarDumper nodes of an entry
    function expandDumps(entry, c) {
        const level = c.level || 0;
        entry.querySelectorAll('pre.sf-dump').forEach(function (pre) {
            if (level > 0) {
                pre.querySelectorAll('samp.sf-dump-compact').forEach(function (samp) {
                    if ((parseInt(samp.dataset.depth, 10) || 0) <= level) openSamp(samp);
                });
            }
            (c.keys || []).forEach(function (path) { expandPath(pre, String(path)); });
        });
    }

    function openSamp(samp) {
        const toggle = samp.previousElementSibling;
        if (samp.classList.contains('sf-dump-compact') && toggle && toggle.classList.contains('sf-dump-toggle')) toggle.click();
    }

    function expandPath(pre, path) {
        let scope = pre;
        path.split('.').forEach(function (part) {
            if (!scope) return;
            const key = Array.prototype.find.call(
                scope.querySelectorAll('.sf-dump-key, .sf-dump-public, .sf-dump-protected, .sf-dump-private'),
                function (el) { return el.textContent === part; }
            );
            let el = key ? key.nextElementSibling : null;
            while (el && el.tagName !== 'SAMP') el = el.nextElementSibling;
            if (el) openSamp(el);
            scope = el;
        });
    }

    function confetti() {
        const colors = ['--green', '--orange', '--red', '--purple', '--blue'];
        for (let i = 0; i < 80; i++) {
            const piece = document.createElement('i');
            piece.className = 'confetti';
            piece.style.left = Math.random() * 100 + 'vw';
            piece.style.background = 'var(' + colors[i % colors.length] + ')';
            piece.style.animationDelay = Math.random() * 0.6 + 's';
            piece.style.animationDuration = 1.6 + Math.random() * 1.4 + 's';
            document.body.appendChild(piece);
            setTimeout(function () { piece.remove(); }, 3800);
        }
    }

    // Screens: ray()->newScreen() archives the current messages, clear_all drops everything

    // Time the IDE received the request (kept on replays after a session switch), or now
    function receivedTime(request) {
        const at = request && request.meta && request.meta.dumpie_received_at;
        return (at ? new Date(at) : new Date()).toLocaleTimeString();
    }

    // time: when the screen was opened; null for the implicit first screen, which takes the time of its first entry
    function newScreen(name, time) {
        // An empty current screen is reused instead of being archived (clearAll() followed by newScreen())
        if (current && screens[screens.length - 1] === current && !current.el.children.length) {
            current.name = name || '';
            current.time = time;
            showScreen(screens.length - 1);
            return;
        }

        const el = document.createElement('section');
        el.className = 'screen';
        feed.appendChild(el);
        screens.push({ name: name || '', el: el, time: time });
        // Old screens are dropped together with their entries to keep the page light
        while (screens.length > MAX_SCREENS) dropScreen(screens.shift());
        showScreen(screens.length - 1);
    }

    function dropScreen(screen) {
        screen.el.querySelectorAll('.entry').forEach(function (entry) { entries.delete(entry.dataset.uuid); });
        screen.el.remove();
    }

    function showScreen(index) {
        current = screens[index];
        screens.forEach(function (s) { s.el.hidden = s !== current; });
        // Screens are tabs (projects are chips): ray()->newScreen() history of the session, newest on the right
        screenBar.innerHTML = '<span class="bar-caption">Screens</span>' + screens.map(function (s, i) {
            return '<button type="button" class="screen-tab' + (i === index ? ' active' : '') + '" data-screen="' + i + '">'
                + esc(s.name || 'Screen ' + (i + 1)) + '<span class="screen-time">' + esc(s.time || '') + '</span></button>';
        }).join('') + '<button type="button" class="screen-add" title="New screen" data-new-screen>+</button>';
        screenBar.hidden = screens.length < 2 && !current.name;
        const activeTab = screenBar.querySelector('.active');
        if (activeTab) activeTab.scrollIntoView({ block: 'nearest', inline: 'nearest' });
        updateCount();
    }

    function clearAll() {
        entries.clear();
        screens = [];
        feed.querySelectorAll('.screen').forEach(function (el) { el.remove(); });
        document.body.style.background = '';
        newScreen('', null);
    }

    function entryFor(uuid, origin, source, time) {
        let entry = entries.get(uuid);
        if (entry) return entry;

        entry = document.createElement('article');
        entry.className = 'entry';
        entry.copyParts = [];
        entry.innerHTML = '<div class="entry-edge" title="Collapse / expand"></div>'
            + '<div class="entry-main">'
            + '<div class="entry-tags">'
            + (allProjects && source ? projectBadge(source) : '')
            + '<span class="entry-label"></span></div>'
            + '<div class="entry-body"></div>'
            + '<div class="entry-meta">'
            + (origin && origin.file ? fileLink(origin.file, origin.line_number) : '')
            + '<span>' + esc(time) + '</span>'
            + '</div></div>'
            + '<button class="icon entry-copy" type="button" title="Copy as Markdown">' + COPY_ICON + '</button>';
        if (source) entry.dataset.session = source.id;
        entry.dataset.uuid = uuid;
        entries.set(uuid, entry);
        const last = screens[screens.length - 1];
        last.el.appendChild(entry);
        const untimed = !last.time;
        if (untimed) last.time = time;
        if (current !== last || untimed) showScreen(screens.length - 1);
        // The oldest entries go first once the page holds too many (e.g. All projects with many busy projects)
        if (entries.size > MAX_ENTRIES) removeEntry(entries.keys().next().value);
        return entry;
    }

    function removeEntry(uuid) {
        const entry = entries.get(uuid);
        if (entry) entry.remove();
        entries.delete(uuid);
    }

    // textContent would include the inline <script>/<style> VarDumper emits with every dump
    function visibleText(el) {
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
            acceptNode: function (node) {
                const tag = node.parentNode.nodeName;
                return tag === 'SCRIPT' || tag === 'STYLE' ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
            }
        });
        let result = '';
        while (walker.nextNode()) result += walker.currentNode.nodeValue + ' ';
        return result.toLowerCase();
    }

    function applyFilter(entry) {
        const q = filter.value.trim().toLowerCase();
        const color = (entry.className.match(/\bcolor-(\w+)/) || [])[1];
        if (q !== '' && entry.searchText == null) entry.searchText = visibleText(entry.querySelector('.entry-main'));
        const textOk = q === '' || entry.searchText.indexOf(q) !== -1;
        const colorOk = activeColors.size === 0 || activeColors.has(color);
        entry.classList.toggle('hidden-by-filter', !(textOk && colorOk));
    }

    function updateCount() {
        const n = current ? current.el.querySelectorAll('.entry').length : 0;
        count.textContent = n ? String(n) : '';
        empty.hidden = n > 0;
    }

    // Markdown fenced blocks keep syntax highlighting when pasted into chats and issue trackers
    function copyEntry(entry, button) {
        const blocks = entry.copyParts.map(function (part) {
            const body = part.text !== null ? part.text : part.el.innerText;
            return '```' + part.lang + '\n' + String(body).replace(/[ \t]+$/gm, '').replace(/\s+$/, '') + '\n```';
        });
        const origin = entry.querySelector('.entry-meta a[data-file]');
        const header = origin ? origin.textContent + '\n' : '';
        post({ action: 'copy', text: header + blocks.join('\n') });

        button.innerHTML = CHECK_ICON;
        button.classList.add('done');
        setTimeout(function () {
            button.innerHTML = COPY_ICON;
            button.classList.remove('done');
        }, 1200);
    }

    function setPaused(value) {
        paused = value;
        pauseButton.classList.toggle('on', paused);
        pauseButton.title = paused ? 'Resume feed' : 'Pause feed';
        pausedBar.hidden = !paused;
        if (paused) return;

        const queued = pending;
        pending = [];
        pendingCount.textContent = '0';
        queued.forEach(function (item) { show(item.request, item.source); });
    }

    // A ray()->pause() blocks the PHP script, so it must never wait behind a paused feed
    function hasLock(request) {
        return (request.payloads || []).some(function (p) { return p.type === 'create_lock'; });
    }

    // source: {id, name} of the project session the request belongs to
    function receive(request, source) {
        if (paused && !hasLock(request)) {
            pending.push({ request: request, source: source });
            pendingCount.textContent = String(pending.length);
            return;
        }
        show(request, source);
    }

    function show(request, source) {
        const nearBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
        // Sending to an existing Ray instance replaces what it showed (ray()->send(), count())
        const existed = entries.has(request.uuid);
        let replaced = false;

        (request.payloads || []).forEach(function (payload) {
            const type = payload.type;
            const content = payload.content || {};

            switch (type) {
                case 'clear_all':
                    clearAll();
                    return;
                case 'new_screen':
                    newScreen(content.name, receivedTime(request));
                    return;
                case 'screen_color':
                    document.body.style.background = content.color || '';
                    return;
                case 'remove':
                    removeEntry(request.uuid);
                    return;
                case 'separator': {
                    const hr = document.createElement('hr');
                    hr.className = 'separator';
                    screens[screens.length - 1].el.appendChild(hr);
                    return;
                }
                case 'confetti':
                    confetti();
                    return;
                case 'expand':
                    if (entries.has(request.uuid)) expandDumps(entries.get(request.uuid), content);
                    return;
                // Handled on the IDE side (tool window visibility, balloon)
                case 'notify':
                case 'show_app':
                case 'hide_app':
                    return;
            }

            const entry = entryFor(request.uuid, payload.origin, source, receivedTime(request));
            entry.searchText = null;
            const result = render(type, content, entry);
            if (!result) {
                applyFilter(entry);
                return;
            }

            const body = entry.querySelector('.entry-body');
            if (existed && !replaced) {
                body.innerHTML = '';
                entry.copyParts = [];
                replaced = true;
            }

            const label = entry.querySelector('.entry-label');
            if (result.label && !label.textContent) label.textContent = result.label;

            const block = document.createElement('div');
            block.className = 'payload';
            body.appendChild(block);
            setHtml(block, result.html);
            entry.copyParts.push({
                lang: result.copy ? result.copy.lang : '',
                text: result.copy ? result.copy.text : null,
                el: block
            });
            applyFilter(entry);
        });

        updateCount();
        if (nearBottom) window.scrollTo(0, document.body.scrollHeight);
    }

    document.addEventListener('click', function (e) {
        const link = e.target.closest('a[data-file]');
        if (link) {
            e.preventDefault();
            e.stopPropagation();
            const owner = link.closest('.entry');
            post({
                action: 'open',
                file: link.dataset.file,
                line: parseInt(link.dataset.line, 10) || 1,
                session: owner ? owner.dataset.session : undefined
            });
            return;
        }

        // Links from payload HTML (ray()->url(), .env values) must not navigate the panel itself
        const external = e.target.closest('a[href]');
        if (external && /^(https?:|mailto:)/i.test(external.getAttribute('href'))) {
            e.preventDefault();
            post({ action: 'browse', url: external.getAttribute('href') });
            return;
        }

        // Dumps rendered by the IDE (dump() server) come without VarDumper's script; toggle them here
        const toggle = e.target.closest('pre.dumpie-dump a.sf-dump-toggle');
        if (toggle) {
            const samp = toggle.nextElementSibling;
            const open = samp.classList.contains('sf-dump-compact');
            samp.classList.toggle('sf-dump-compact', !open);
            samp.classList.toggle('sf-dump-expanded', open);
            toggle.firstElementChild.textContent = open ? '▼' : '▶';
            return;
        }

        const guideAction = e.target.closest('#empty [data-action]');
        if (guideAction) {
            e.preventDefault();
            post({ action: guideAction.dataset.action });
            return;
        }
        const guideCopy = e.target.closest('#empty [data-copy]');
        if (guideCopy) {
            post({ action: 'copy', text: guideCopy.dataset.copy });
            guideCopy.textContent = 'Copied';
            setTimeout(function () { guideCopy.textContent = 'Copy'; }, 1200);
            return;
        }

        const copyButton = e.target.closest('.entry-copy');
        if (copyButton) {
            copyEntry(copyButton.closest('.entry'), copyButton);
            return;
        }

        const lockButton = e.target.closest('[data-lock-action]');
        if (lockButton) {
            const lock = lockButton.closest('[data-lock]');
            post({ action: 'lock', name: lock.dataset.lock, mode: lockButton.dataset.lockAction });
            lock.classList.add('released');
            lock.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
            lock.querySelector('span').textContent = lockButton.dataset.lockAction === 'stop' ? 'Stopped' : 'Continued';
            return;
        }

        const edge = e.target.closest('.entry-edge');
        if (edge) edge.parentElement.classList.toggle('collapsed');
    });

    // Fit mail previews to their content
    document.addEventListener('load', function (e) {
        const frame = e.target;
        if (frame.tagName !== 'IFRAME' || !frame.contentDocument) return;
        // Collapse first: scrollHeight never reports less than the current frame height
        frame.style.height = '0px';
        frame.style.height = frame.contentDocument.documentElement.scrollHeight + 'px';
    }, true);

    document.getElementById('clear').addEventListener('click', function () {
        clearAll();
        repaintAll();
        pending = [];
        pendingCount.textContent = '0';
        post({ action: 'clear' });
    });

    pauseButton.addEventListener('click', function () { setPaused(!paused); });
    document.getElementById('resume').addEventListener('click', function () { setPaused(false); });

    screenBar.addEventListener('click', function (e) {
        if (e.target.closest('[data-new-screen]')) return post({ action: 'newScreen' });
        const tab = e.target.closest('[data-screen]');
        if (tab) showScreen(parseInt(tab.dataset.screen, 10));
    });

    // The IDE adds a new_screen to the session, so the screen survives session switches and shows up in every window
    document.getElementById('new-screen').addEventListener('click', function () {
        post({ action: 'newScreen' });
    });

    document.getElementById('colors').addEventListener('click', function (e) {
        const dot = e.target.closest('[data-color]');
        if (!dot) return;
        const color = dot.dataset.color;
        if (activeColors.has(color)) activeColors.delete(color); else activeColors.add(color);
        dot.classList.toggle('active', activeColors.has(color));
        entries.forEach(applyFilter);
    });

    let filterTimer = 0;
    filter.addEventListener('input', function () {
        clearTimeout(filterTimer);
        filterTimer = setTimeout(function () { entries.forEach(applyFilter); }, 120);
    });

    function toggleSearch(open) {
        filter.hidden = !open;
        searchButton.classList.toggle('on', open);
        if (open) {
            filter.focus();
        } else if (filter.value) {
            filter.value = '';
            entries.forEach(applyFilter);
        }
    }

    searchButton.addEventListener('click', function () { toggleSearch(filter.hidden); });
    filter.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') toggleSearch(false);
    });

    // Setup hints shown in the empty feed: what the viewed project still needs to send output here
    const guide = document.getElementById('guide');
    const guideLead = document.getElementById('guide-lead');
    const guideLinks = document.getElementById('guide-links');

    function guideRow(state, title, body) {
        const mark = { ready: '✓', setup: '!', unavailable: '–', off: '–' }[state] || '–';
        return '<div class="guide-row ' + state + '"><span class="guide-mark">' + mark + '</span><div class="guide-text">'
            + '<div class="guide-title">' + title + '</div>' + body + '</div></div>';
    }

    function guideCode(text) {
        return '<pre class="box guide-code">' + esc(text) + '</pre>';
    }

    function guideButton(label, attrs, primary) {
        return '<button type="button"' + (primary ? ' class="primary"' : '') + ' ' + attrs + '>' + esc(label) + '</button>';
    }

    function status(s) {
        const off = '<div class="muted">Turned off for this project.</div>' + guideButton('Settings', 'data-action="settings"');
        let dump;
        if (s.dump.state === 'ready') dump = '<div class="muted">Sent here: <code>VAR_DUMPER_FORMAT</code> is set in .env.</div>';
        else if (s.dump.state === 'setup') {
            const lines = s.dump.envLines.join('\n');
            dump = '<div class="muted">Add to the project\'s .env:</div>' + guideCode(lines) + '<div class="guide-actions">'
                + (s.dump.canAddEnv ? guideButton('Add to .env', 'data-action="addEnv"', true) : '')
                + guideButton('Copy', 'data-copy="' + esc(lines) + '"') + '</div>';
        } else if (s.dump.state === 'unavailable') dump = '<div class="muted">Needs <code>symfony/var-dumper</code>, which comes with Laravel and Symfony.</div>';
        else dump = off;

        let ray;
        if (s.ray.state === 'ready') ray = '<div class="muted">' + esc(s.ray.package) + ' found.</div>';
        else if (s.ray.state === 'setup') ray = '<div class="muted">Optional: tables, queries, <code>pause()</code> and more.</div>'
            + guideCode(s.ray.install) + '<div class="guide-actions">' + guideButton('Copy', 'data-copy="' + esc(s.ray.install) + '"') + '</div>';
        else ray = off;

        let logs;
        if (s.logs.state === 'ready') logs = '<div class="muted">Following <code>' + esc(s.logs.patterns) + '</code>.</div>';
        else if (s.logs.state === 'unavailable') logs = '<div class="muted">No log directory found.</div>' + guideButton('Settings', 'data-action="settings"');
        else logs = off;

        // The checklist only matters while nothing can reach the IDE: once dump() or ray() works, output will come
        const nothingReady = s.dump.state !== 'ready' && s.ray.state !== 'ready';
        guide.innerHTML = nothingReady
            ? guideRow(s.dump.state, '<code>dump()</code> and <code>dd()</code>', dump)
                + guideRow(s.ray.state, '<code>ray()</code>', ray)
                + guideRow(s.logs.state, 'Log files', logs)
            : '';
        guideLinks.hidden = !nothingReady;
        guideLead.innerHTML = nothingReady
            ? 'To catch output from <b>' + esc(s.project) + '</b> without spatie/ray, add one line to .env — or install spatie/ray.'
            : 'Debug output of <b>' + esc(s.project) + '</b> will appear here.';
        guideLead.classList.toggle('attention', nothingReady);
        repaintAll();
    }

    // Session bar: one chip per open project plus "All projects", shown only when there is more than one project.
    // Every project gets a stable color, reused for its badge in the All projects feed.
    const sessionBar = document.getElementById('session-bar');
    const PROJECT_COLORS = ['#548af7', '#a571e6', '#3fb9c4', '#d9a33f', '#e46fa5', '#7cb24a'];

    function projectColor(id) {
        let hash = 0;
        for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) | 0;
        return PROJECT_COLORS[Math.abs(hash) % PROJECT_COLORS.length];
    }

    function projectBadge(source) {
        return '<span class="entry-project" style="--pc:' + projectColor(source.id) + '"><i></i>' + esc(source.name) + '</span>';
    }

    // projectCount: the bar is only useful with two or more open projects
    function sessions(list, projectCount) {
        allProjects = list.some(function (s) { return s.id === 'all' && s.viewed; });
        sessionBar.innerHTML = '<span class="bar-caption">Projects</span>' + list.map(function (s) {
            const all = s.id === 'all';
            return '<button type="button" class="session-chip' + (s.viewed ? ' active' : '') + (all ? ' all' : '') + '"'
                + ' data-session="' + esc(s.id) + '"' + (all ? '' : ' style="--pc:' + projectColor(s.id) + '"') + '>'
                + '<i></i>' + esc(s.name) + (s.own ? '<small>this</small>' : '')
                + (s.unread > 0 ? '<b class="unread" title="' + s.unread + ' new">' + (s.unread > 99 ? '99+' : s.unread) + '</b>' : '')
                + '</button>';
        }).join('');
        sessionBar.hidden = projectCount < 2;
        const active = sessionBar.querySelector('.active');
        if (active) active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }

    // Drops everything shown before switching to another session; the IDE replays the new one
    function reset() {
        clearAll();
        pending = [];
        pendingCount.textContent = '0';
        repaintAll();
    }

    sessionBar.addEventListener('click', function (e) {
        const chip = e.target.closest('[data-session]');
        if (chip && !chip.classList.contains('active')) post({ action: 'session', id: chip.dataset.session });
    });

    function theme(vars, dark) {
        const root = document.documentElement;
        root.dataset.theme = dark ? 'dark' : 'light';
        Object.keys(vars).forEach(function (name) { root.style.setProperty(name, vars[name]); });
    }

    // Settings that are not part of the theme
    function options(o) {
        document.getElementById('top').classList.toggle('pinned', o.pinBars);
    }

    newScreen('', null);
    // The IDE sends output in batches: one call per burst instead of one per request
    function receiveBatch(items) {
        items.forEach(function (item) { receive(item.r, item.s); });
    }

    window.dumpie = { receive: receive, receiveBatch: receiveBatch, theme: theme, options: options, sessions: sessions, reset: reset, status: status };
})();
