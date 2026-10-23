(function () {
    'use strict';

    const feed = document.getElementById('feed');
    const empty = document.getElementById('empty');
    const screenBar = document.getElementById('screen-bar');
    const filter = document.getElementById('filter');
    const count = document.getElementById('count');
    const queryStats = document.getElementById('query-stats');
    const noMatch = document.getElementById('no-match');
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
    // Set by the IDE (options()); until then the browser defaults apply
    let timeFormat = { locale: undefined, hour12: undefined };

    const COPY_ICON = '<svg viewBox="0 0 16 16"><path d="M5 1.5h7.5A1.5 1.5 0 0 1 14 3v8.5a1.5 1.5 0 0 1-1.5 1.5H11v-1.5h1.5V3H5v1.5H3.5V3A1.5 1.5 0 0 1 5 1.5zM2 6.5A1.5 1.5 0 0 1 3.5 5h6A1.5 1.5 0 0 1 11 6.5v6A1.5 1.5 0 0 1 9.5 14h-6A1.5 1.5 0 0 1 2 12.5v-6zm1.5 0v6h6v-6h-6z"/></svg>';
    const OPEN_ICON = '<svg viewBox="0 0 16 16"><path d="M3.5 2h4v1.5h-4v9h9v-4H14v4a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 2 12.5v-9A1.5 1.5 0 0 1 3.5 2zm6-.5H14.5V6.5H13V4.06L7.53 9.53 6.47 8.47 11.94 3H9.5V1.5z"/></svg>';
    const PROJECTS_ICON = '<svg viewBox="0 0 16 16"><path d="M2.5 2.5h4l1.5 1.5h5.5A1.5 1.5 0 0 1 15 5.5v7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 1 12.5v-8.5A1.5 1.5 0 0 1 2.5 2.5zm0 1.5v8.5h11v-7H7.38L5.88 4H2.5z"/></svg>';
    const SCREENS_ICON = '<svg viewBox="0 0 16 16"><path d="M5 1.5h8A1.5 1.5 0 0 1 14.5 3v7a1.5 1.5 0 0 1-1.5 1.5h-1.5V10H13V3H5v1.5H3.5V3A1.5 1.5 0 0 1 5 1.5zM3 5.5h8A1.5 1.5 0 0 1 12.5 7v6A1.5 1.5 0 0 1 11 14.5H3A1.5 1.5 0 0 1 1.5 13V7A1.5 1.5 0 0 1 3 5.5zM3 7v6h8V7H3z"/></svg>';
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
        if (typeof value === 'object') return '<pre class="box">' + highlightJson(JSON.stringify(value, null, 2)) + '</pre>';
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

    // Long stack traces: the first frames stay visible, the rest open with a button
    const FRAMES_SHOWN = 3;
    const LINES_SHOWN = 8;

    function foldButton(count, what) {
        const more = 'Show ' + count + ' more ' + what;
        return '<button type="button" class="fold-toggle" data-more="' + more + '" data-less="Show less">' + more + '</button>';
    }

    function foldedFrames(list) {
        list = list || [];
        if (list.length <= FRAMES_SHOWN + 1) return frames(list);
        return '<div class="foldable">' + frames(list.slice(0, FRAMES_SHOWN))
            + '<div class="fold-rest">' + frames(list.slice(FRAMES_SHOWN)) + '</div>'
            + foldButton(list.length - FRAMES_SHOWN, 'frames') + '</div>';
    }

    // Plain text such as a stack trace from a log file
    function foldedText(value) {
        const lines = String(value).split('\n');
        if (lines.length <= LINES_SHOWN + 2) return '<pre class="box">' + esc(value) + '</pre>';
        return '<div class="foldable"><pre class="box">' + esc(lines.slice(0, LINES_SHOWN).join('\n'))
            + '<span class="fold-rest">\n' + esc(lines.slice(LINES_SHOWN).join('\n')) + '</span></pre>'
            + foldButton(lines.length - LINES_SHOWN, 'lines') + '</div>';
    }

    // Snippets come as plain text; a light PHP highlighter colors them with the editor scheme (--syn-*)
    const PHP_KEYWORDS = new Set(('abstract and array as break callable case catch class clone const continue declare default do '
        + 'echo else elseif empty enum extends false final finally fn for foreach function global goto if implements include '
        + 'include_once instanceof insteadof interface isset list match namespace new null or parent print private protected '
        + 'public readonly require require_once return self static switch throw trait true try unset use var while xor yield '
        + 'bool float int iterable mixed never object string void').split(' '));
    const PHP_TOKEN = /(\/\/.*|#(?!\[).*|\/\*.*?(?:\*\/|$))|('(?:[^'\\]|\\.)*'?|"(?:[^"\\]|\\.)*"?)|(\$\w+)|\b(\d+(?:\.\d+)?)\b|([A-Za-z_]\w*)(?=\s*\()|([A-Za-z_]\w*)/g;

    // state.comment carries an open /* ... */ over to the next line
    function highlightPhp(line, state) {
        let html = '';
        let rest = line;
        if (state.comment || /^\s*\*/.test(line)) {
            const end = rest.indexOf('*/');
            const cut = end === -1 ? rest.length : end + 2;
            html += '<span class="syn-comment">' + esc(rest.slice(0, cut)) + '</span>';
            state.comment = end === -1;
            rest = rest.slice(cut);
        }
        let last = 0;
        let m;
        PHP_TOKEN.lastIndex = 0;
        while ((m = PHP_TOKEN.exec(rest)) !== null) {
            html += esc(rest.slice(last, m.index));
            let cls = '';
            if (m[1]) {
                cls = 'syn-comment';
                if (m[1].startsWith('/*') && !m[1].endsWith('*/')) state.comment = true;
            } else if (m[2]) cls = 'syn-string';
            else if (m[3]) cls = 'syn-var';
            else if (m[4]) cls = 'syn-number';
            else if (m[5]) cls = PHP_KEYWORDS.has(m[5].toLowerCase()) && !/(->|::)\s*$/.test(rest.slice(0, m.index)) ? 'syn-keyword' : 'syn-function';
            else if (m[6] && PHP_KEYWORDS.has(m[6].toLowerCase())) cls = 'syn-keyword';
            html += cls ? '<span class="' + cls + '">' + esc(m[0]) + '</span>' : esc(m[0]);
            last = m.index + m[0].length;
        }
        return html + esc(rest.slice(last));
    }

    // Escapes text and wraps every match of a global regex in the span markup render() returns for it
    function highlight(text, re, render) {
        let html = '';
        let last = 0;
        let m;
        text = String(text);
        re.lastIndex = 0;
        while ((m = re.exec(text)) !== null) {
            if (!m[0]) { re.lastIndex++; continue; }
            html += esc(text.slice(last, m.index)) + render(m);
            last = m.index + m[0].length;
        }
        return html + esc(text.slice(last));
    }

    function syn(cls, text) {
        return cls ? '<span class="' + cls + '">' + esc(text) + '</span>' : esc(text);
    }

    const SQL_KEYWORDS = new Set(('add all alter and as asc avg between by case cast count create cross default delete desc distinct '
        + 'drop else end exists false from full group having if in index inner insert into is join key left like limit max min '
        + 'not null offset on or order outer primary references returning right select set sum table then true truncate union '
        + 'unique update using values when where with').split(' '));
    const SQL_TOKEN = /(--.*|\/\*[\s\S]*?(?:\*\/|$))|('(?:[^']|'')*'?)|(`[^`]*`?|"[^"]*"?)|(\?|:[A-Za-z_]\w*)|\b(\d+(?:\.\d+)?)\b|([A-Za-z_]\w*)/g;

    function highlightSql(text) {
        return highlight(text, SQL_TOKEN, function (m) {
            if (m[1]) return syn('syn-comment', m[0]);
            if (m[2]) return syn('syn-string', m[0]);
            if (m[3]) return syn('syn-field', m[0]);
            if (m[4]) return syn('syn-var', m[0]);
            if (m[5]) return syn('syn-number', m[0]);
            return syn(SQL_KEYWORDS.has(m[6].toLowerCase()) ? 'syn-keyword' : '', m[0]);
        });
    }

    const JSON_TOKEN = /("(?:[^"\\]|\\.)*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;

    function highlightJson(text) {
        return highlight(text, JSON_TOKEN, function (m) {
            if (m[1]) return m[2] ? syn('syn-field', m[1]) + esc(m[2]) : syn('syn-string', m[1]);
            if (m[3]) return syn('syn-keyword', m[3]);
            return syn('syn-number', m[4]);
        });
    }

    function framesText(list) {
        return (list || []).map(function (f, i) {
            return '#' + i + ' ' + frameWhere(f) + ' ' + f.file_name + ':' + f.line_number;
        }).join('\n');
    }

    function snippet(frame) {
        if (!frame || !Array.isArray(frame.snippet) || !frame.snippet.length) return '';
        let rows = '';
        const php = /\.php$/i.test(frame.file_name || '');
        const state = { comment: false };
        frame.snippet.forEach(function (row) {
            rows += '<div class="line' + (row.line_number === frame.line_number ? ' hl' : '') + '">'
                + '<span class="ln">' + row.line_number + '</span><span class="code">'
                + (php ? highlightPhp(String(row.text), state) : esc(row.text)) + '</span></div>';
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
            + foldedFrames(c.frames);
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
        return '<pre class="box">' + highlightSql(c.sql) + '</pre>'
            + (bindings ? '<div class="muted mono query-meta">bindings: ' + esc(JSON.stringify(bindings)) + '</div>' : '')
            + (meta ? '<div class="muted mono query-meta">' + esc(meta) + '</div>' : '');
    }

    // SQL insights per screen: totals, repeated statements (possible N+1) and slow queries
    const REPEAT_MIN = 3;
    // Set by the IDE settings (options()); slowQueryMs 0 turns slow marks off
    let slowQueryMs = 100;
    let markRepeated = true;
    let showQueryStats = true;

    // The same statement with other values: string and number literals and IN lists become placeholders
    function queryShape(sql) {
        return String(sql).toLowerCase()
            .replace(/'(?:[^']|'')*'/g, '?')
            .replace(/\b\d+(?:\.\d+)?\b/g, '?')
            .replace(/\(\s*\?(?:\s*,\s*\?)*\s*\)/g, '(?)')
            .replace(/\s+/g, ' ')
            .trim();
    }

    function trackQuery(entry, c) {
        // A replaced entry (same uuid) is counted once
        if (entry.dataset.query) return;
        entry.dataset.query = '1';
        const screen = screens.find(function (s) { return s.el.contains(entry); }) || screens[screens.length - 1];
        const q = screen.queries || (screen.queries = { count: 0, time: 0, shapes: new Map() });
        q.count++;
        if (typeof c.time === 'number') q.time += c.time;

        const shape = queryShape(c.sql);
        const same = q.shapes.get(shape) || [];
        same.push(entry);
        q.shapes.set(shape, same);
        if (markRepeated && same.length >= REPEAT_MIN) {
            q.issues = true;
            same.forEach(function (e) {
                queryFlag(e, 'repeated', 'Repeated ×' + same.length, 'The same query ran ' + same.length + ' times on this screen: possible N+1');
            });
        }
        if (slowQueryMs > 0 && typeof c.time === 'number' && c.time >= slowQueryMs) {
            q.issues = true;
            queryFlag(entry, 'slow', 'Slow', 'Took ' + formatMs(c.time) + ', the threshold is ' + slowQueryMs + ' ms');
        }
    }

    function queryFlag(entry, kind, text, title) {
        let flag = entry.querySelector('.query-flag.' + kind);
        if (!flag) {
            flag = document.createElement('span');
            flag.className = 'query-flag ' + kind;
            entry.querySelector('.entry-tags').appendChild(flag);
        }
        flag.textContent = text;
        flag.title = title;
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
                return { label: 'JSON', html: '<pre class="box">' + highlightJson(prettyJson(c.value)) + '</pre>', copy: { lang: 'json', text: prettyJson(c.value) } };
            case 'exception':
                entry.classList.add('alert', 'color-red');
                return {
                    label: '',
                    html: renderException(c),
                    copy: { lang: '', text: c.class + ': ' + c.message + '\n\n' + framesText(c.frames) }
                };
            case 'trace':
                return { label: 'Trace', html: foldedFrames(c.frames), copy: { lang: '', text: framesText(c.frames) } };
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
                // Log files deliver the context/stack trace as plain text; laravel-ray sends a dump
                const plainContext = typeof c.context === 'string' && !isDump(c.context);
                return {
                    label: c.level || 'Log',
                    html: '<div class="alert-message">' + esc(c.value) + '</div>'
                        + (plainContext ? foldedText(c.context) : (c.context ? primitive(c.context) : '')),
                    // The folded part is hidden, so the rendered text would miss it
                    copy: plainContext ? { lang: '', text: c.value + '\n\n' + c.context } : null
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
                return { label: type, html: '<pre class="box">' + highlightJson(JSON.stringify(c, null, 2)) + '</pre>', copy: null };
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
        return (at ? new Date(at) : new Date()).toLocaleTimeString(timeFormat.locale, { hour12: timeFormat.hour12 });
    }

    // time: when the screen was opened; null for the implicit first screen, which takes the time of its first entry
    // uuid: the new_screen request that opened the screen, so closing it can drop it from the session history
    function newScreen(name, time, uuid) {
        // An empty current screen is reused instead of being archived (clearAll() followed by newScreen())
        if (current && screens[screens.length - 1] === current && !current.el.children.length) {
            current.name = name || '';
            current.time = time;
            current.uuid = uuid;
            showScreen(screens.length - 1);
            return;
        }

        const el = document.createElement('section');
        el.className = 'screen';
        feed.appendChild(el);
        screens.push({ name: name || '', el: el, time: time, uuid: uuid });
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
        screenBar.innerHTML = '<span class="bar-icon" title="Screens">' + SCREENS_ICON + '</span>' + screens.map(function (s, i) {
            return '<button type="button" class="screen-tab' + (i === index ? ' active' : '') + '" data-screen="' + i + '">'
                + esc(s.name || 'Screen ' + (i + 1)) + '<span class="screen-time">' + esc(s.time || '') + '</span>'
                + (screens.length > 1 ? '<span class="screen-close" data-close-screen="' + i + '" title="Close screen">×</span>' : '')
                + '</button>';
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
            + '<div class="entry-actions">'
            + '<button class="icon entry-open" type="button" title="Open in editor">' + OPEN_ICON + '</button>'
            + '<button class="icon entry-copy" type="button" title="Copy as Markdown">' + COPY_ICON + '</button></div>';
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

    function filtersActive() {
        return activeColors.size > 0 || filter.value.trim() !== '';
    }

    function updateCount() {
        const n = current ? current.el.querySelectorAll('.entry').length : 0;
        const shown = current ? current.el.querySelectorAll('.entry:not(.hidden-by-filter)').length : 0;
        const filtered = filtersActive() && shown < n;
        count.textContent = !n ? '' : (filtered ? shown + ' of ' : '') + n + (n === 1 ? ' entry' : ' entries');
        // Filters hid everything on this screen: say so instead of showing an empty feed
        noMatch.hidden = !(n > 0 && shown === 0 && filtersActive());
        const q = showQueryStats && current && current.queries;
        queryStats.textContent = q ? q.count + (q.count === 1 ? ' query' : ' queries') + (q.time ? ' · ' + formatMs(q.time) : '') : '';
        queryStats.hidden = !q;
        queryStats.classList.toggle('warn', !!(q && q.issues));
        empty.hidden = n > 0;
    }

    // Text of every payload of an entry with its language (php, sql, json, ...)
    function entryParts(entry) {
        return entry.copyParts.map(function (part) {
            const body = part.text !== null ? part.text : part.el.innerText;
            return { lang: part.lang, text: String(body).replace(/[ \t]+$/gm, '').replace(/\s+$/, '') };
        });
    }

    // Markdown fenced blocks keep syntax highlighting when pasted into chats and issue trackers
    function copyEntry(entry, button) {
        const blocks = entryParts(entry).map(function (part) {
            return '```' + part.lang + '\n' + part.text + '\n```';
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
        pauseButton.title = withShortcut(paused ? 'Resume feed' : 'Pause feed', 'pause');
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
        const nearBottom = feed.scrollTop + feed.clientHeight >= feed.scrollHeight - 40;
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
                    newScreen(content.name, receivedTime(request), request.uuid);
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
            if (type === 'executed_query') trackQuery(entry, content);
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
        if (nearBottom) feed.scrollTop = feed.scrollHeight;
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

        // Big entries are easier to read in an editor tab next to the code
        const openButton = e.target.closest('.entry-open');
        if (openButton) {
            const entry = openButton.closest('.entry');
            const parts = entryParts(entry);
            const langs = parts.map(function (p) { return p.lang; }).filter(Boolean);
            const origin = entry.querySelector('.entry-meta a[data-file]');
            post({
                action: 'openText',
                text: parts.map(function (p) { return p.text; }).join('\n\n'),
                lang: langs.length && langs.every(function (l) { return l === langs[0]; }) ? langs[0] : '',
                title: entry.querySelector('.entry-label').textContent || (origin ? origin.textContent : '')
            });
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

        const fold = e.target.closest('.fold-toggle');
        if (fold) {
            const open = fold.closest('.foldable').classList.toggle('open');
            fold.textContent = open ? fold.dataset.less : fold.dataset.more;
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

    function screenTitle(screen, index) {
        return '"' + (screen.name || 'Screen ' + (index + 1)) + '"';
    }

    // Archive of cleared output (newest first); Restore puts it back where it was
    const archivePanel = document.getElementById('archive');

    function archive(list) {
        archivePanel.innerHTML = '<div class="archive-title">Cleared output</div>' + (list.length ? '<div class="archive-list">' + list.map(function (item) {
            return '<div class="archive-item" data-restore="' + esc(item.id) + '" data-session="' + esc(item.session) + '" title="Restore">'
                + '<div class="archive-text"><b>' + esc(item.title) + '</b><span class="muted">'
                + esc(new Date(item.time).toLocaleTimeString(timeFormat.locale, { hour12: timeFormat.hour12 }))
                + ' · ' + item.count + (item.count === 1 ? ' entry' : ' entries')
                + (allProjects ? ' · ' + esc(item.project) : '') + '</span></div>'
                + '<span class="archive-restore">Restore</span></div>';
        }).join('') + '</div>' : '<div class="archive-empty muted">Nothing cleared yet. What Clear removes can be restored here.</div>');
        archivePanel.hidden = false;
        placeArchive();
    }

    // Under the history button, inside the panel; the caret points at the button
    function placeArchive() {
        const button = document.getElementById('history');
        const center = button.offsetLeft + button.offsetWidth / 2;
        const width = archivePanel.offsetWidth;
        const left = Math.max(8, Math.min(button.offsetLeft - 6, document.documentElement.clientWidth - width - 8));
        archivePanel.style.setProperty('--archive-left', left + 'px');
        archivePanel.style.setProperty('--caret-x', (center - left) + 'px');
    }

    document.getElementById('history').addEventListener('click', function () {
        if (archivePanel.hidden) post({ action: 'archive' }); else archivePanel.hidden = true;
    });

    archivePanel.addEventListener('click', function (e) {
        const restore = e.target.closest('[data-restore]');
        if (!restore) return;
        post({ action: 'restore', id: Number(restore.dataset.restore), session: restore.dataset.session });
        archivePanel.hidden = true;
    });

    // Any click elsewhere closes the list
    document.addEventListener('mousedown', function (e) {
        if (!archivePanel.hidden && !e.target.closest('#archive, #history')) archivePanel.hidden = true;
    });

    // Closing a screen drops it with its entries; the IDE removes them from the session history too
    function closeScreen(index) {
        const screen = screens[index];
        const uuids = Array.prototype.map.call(screen.el.querySelectorAll('.entry'), function (entry) { return entry.dataset.uuid; });
        if (screen.uuid) uuids.push(screen.uuid);
        post({ action: 'removeRequests', uuids: uuids, title: screenTitle(screen, index) + ' closed' });
        dropScreen(screen);
        screens.splice(index, 1);
        showScreen(screen === current ? Math.min(index, screens.length - 1) : screens.indexOf(current));
    }

    // Removes the entries of the current screen but keeps the screen; the IDE forgets them too
    function clearScreen() {
        const uuids = Array.prototype.map.call(current.el.querySelectorAll('.entry'), function (entry) { return entry.dataset.uuid; });
        if (uuids.length) post({ action: 'removeRequests', uuids: uuids, title: screenTitle(current, screens.indexOf(current)) + ' cleared' });
        uuids.forEach(function (uuid) { entries.delete(uuid); });
        current.el.innerHTML = '';
        current.queries = null;
        updateCount();
        repaintAll();
    }

    screenBar.addEventListener('click', function (e) {
        if (e.target.closest('[data-new-screen]')) return post({ action: 'newScreen' });
        const close = e.target.closest('[data-close-screen]');
        if (close) return closeScreen(parseInt(close.dataset.closeScreen, 10));
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
        updateCount();
    });

    let filterTimer = 0;
    filter.addEventListener('input', function () {
        clearTimeout(filterTimer);
        filterTimer = setTimeout(function () {
            entries.forEach(applyFilter);
            updateCount();
        }, 120);
    });

    function toggleSearch(open) {
        filter.hidden = !open;
        searchButton.classList.toggle('on', open);
        if (open) {
            filter.focus();
        } else if (filter.value) {
            filter.value = '';
            entries.forEach(applyFilter);
            updateCount();
        }
    }

    searchButton.addEventListener('click', function () { toggleSearch(filter.hidden); });

    document.getElementById('clear-filters').addEventListener('click', function () {
        activeColors.clear();
        document.querySelectorAll('#colors .dot').forEach(function (dot) { dot.classList.remove('active'); });
        filter.value = '';
        entries.forEach(applyFilter);
        updateCount();
    });
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
        sessionBar.innerHTML = '<span class="bar-icon" title="Projects">' + PROJECTS_ICON + '</span>' + list.map(function (s) {
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

    // A click on a gutter icon in the editor: bring the entry into view, on whichever screen it is
    function reveal(uuid) {
        const entry = entries.get(uuid);
        if (!entry) return;
        const index = screens.findIndex(function (s) { return s.el.contains(entry); });
        if (index >= 0 && screens[index] !== current) showScreen(index);
        entry.scrollIntoView({ block: 'center' });
        entry.classList.remove('flash');
        void entry.offsetWidth;
        entry.classList.add('flash');
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

    // Keyboard actions of the IDE (Tools | Dumpie in the keymap)
    function command(name) {
        const index = screens.indexOf(current);
        switch (name) {
            case 'clearAll': document.getElementById('clear').click(); break;
            case 'clearScreen': clearScreen(); break;
            case 'newScreen': post({ action: 'newScreen' }); break;
            case 'pause': setPaused(!paused); break;
            case 'search': toggleSearch(true); break;
            case 'nextScreen': if (index < screens.length - 1) showScreen(index + 1); break;
            case 'previousScreen': if (index > 0) showScreen(index - 1); break;
        }
    }

    let shortcuts = {};

    function withShortcut(title, name) {
        return shortcuts[name] ? title + ' (' + shortcuts[name] + ')' : title;
    }

    // Settings that are not part of the theme. Unpinned bars move into the feed and scroll away with it.
    function options(o) {
        const bars = document.getElementById('bars');
        if (o.pinBars) document.getElementById('top').appendChild(bars); else feed.insertBefore(bars, feed.firstChild);
        timeFormat = { locale: o.locale || undefined, hour12: typeof o.hour12 === 'boolean' ? o.hour12 : undefined };
        if (typeof o.slowQueryMs === 'number') slowQueryMs = o.slowQueryMs;
        if (typeof o.repeatedQueries === 'boolean') markRepeated = o.repeatedQueries;
        if (typeof o.queryStats === 'boolean') showQueryStats = o.queryStats;
        if (o.shortcuts) {
            shortcuts = o.shortcuts;
            pauseButton.title = withShortcut(paused ? 'Resume feed' : 'Pause feed', 'pause');
            searchButton.title = withShortcut('Search', 'search');
            document.getElementById('new-screen').title = withShortcut('New screen', 'newScreen');
            document.getElementById('clear').title = withShortcut('Clear all', 'clearAll');
        }
        updateCount();
    }

    newScreen('', null);
    // The IDE sends output in batches: one call per burst instead of one per request
    function receiveBatch(items) {
        items.forEach(function (item) { receive(item.r, item.s); });
    }

    window.dumpie = { receive: receive, receiveBatch: receiveBatch, theme: theme, options: options, sessions: sessions, reset: reset, status: status, reveal: reveal, command: command, archive: archive };
})();
