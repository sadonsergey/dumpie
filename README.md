# <img src="src/main/resources/META-INF/pluginIcon.svg" width="40" height="40" alt="" align="top"> Dumpie

**PHP debug output right inside PhpStorm.**

`dump()`, `dd()`, log records, SQL queries, exceptions and `ray()` calls show up in a tool window next to your code,
each with a link to the line that produced it. No separate app, no extra browser tab, no scrolling through
`laravel.log`.

![Dumpie next to the code in PhpStorm](docs/marketplace/01-hero.png)

## Many projects. Many IDEs. Nothing mixes up.

Real work means several projects open at once — often in different IDEs. Dumpie keeps every project's output in
its own session and delivers it to the window that has the project open.

![Viewing another project's session and all projects at once](docs/marketplace/02-sessions.png)

![Output of each project reaches its own IDE window](docs/marketplace/03-ides.png)

- **A session per project.** `shop` never sees `blog`'s dumps, even when both run at the same time.
- **Switch or see everything.** The project chips under the toolbar open any open project's session from any
  window, or **All projects**: one feed with every entry tagged by its project.
- **Unread counters.** Each project chip shows how many new entries arrived while you were looking at another
  session.
- **Across IDEs.** PhpStorm and WebStorm (or two IDE versions) open side by side? The IDE that receives the output
  forwards it to the one that has the project open. `ray()->pause()` can be continued from either.
- **No restart when IDEs come and go.** Quit the IDE that owned the ports, and another one takes them over within
  seconds.
- **Files open in the right window.** Clicking `File.php:42` of another session opens it in that project's window.

## Contents

- [Many projects. Many IDEs.](#many-projects-many-ides-nothing-mixes-up)
- [Features](#features)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Using the tool window](#using-the-tool-window)
- [Settings](#settings)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [Contributing](#contributing)
- [Support the project](#support-the-project)
- [License](#license)

## Features

- **`dump()` and `dd()` without extra packages.** Any project with `symfony/var-dumper` (Laravel, Symfony and most
  modern PHP apps) sends dumps to the IDE after adding one line to `.env`. Dumps stay interactive: nested arrays and
  objects expand and collapse, colors follow your editor scheme.
- **Log files.** New records from `storage/logs/*.log` (Laravel) or `var/log/*.log` (Symfony) appear as they are
  written, colored by level, with the stack trace attached.
- **Works with `spatie/ray`.** Projects that already use `spatie/ray` or `spatie/laravel-ray` work as they are:
  tables, queries, events, jobs, mails, models, exceptions, measurements, `pause()`, colors, labels and screens.
- **Jump to code.** Every entry links to its file and line. Docker paths such as `/var/www/html/app/...` are matched
  to your project automatically. And back: an icon in the editor gutter marks the lines that sent output.
- **SQL insights.** Repeated queries (possible N+1) and slow queries are marked; the toolbar shows how many queries a
  screen ran and their total time.
- **One session per project, several IDEs at once.** See [above](#many-projects-many-ides-nothing-mixes-up).
- **Guided setup.** When a project cannot send anything yet, the empty tool window shows what is missing, with an
  **Add to .env** button and ready-to-copy commands.
- **Made for reading.** Highlighted code, SQL and JSON, folded stack traces. Filter by color, search, pause the feed,
  collapse entries, copy any entry as a Markdown code block or open it in an editor tab.
- **Nothing lost.** Whatever Clear removes goes to an archive and can be restored to its project.
- **Follows the IDE.** Light and dark themes, UI and editor fonts come from your IDE settings.
- **Local only.** Dumpie listens on `127.0.0.1`; nothing leaves your machine.

## Installation

Requires **PhpStorm 2024.3 or newer**. Dumpie also installs in **WebStorm 2024.3 or newer**: every build is checked
with the JetBrains Plugin Verifier against PhpStorm and WebStorm 2024.3–2026.2.

1. Get `dumpie-<version>.zip` from the [Releases](https://github.com/sadonsergey/dumpie/releases) page, or build it
   yourself (see [Building from source](#building-from-source)).
2. Open **Settings | Plugins**, click the gear icon, choose **Install Plugin from Disk…** and select the archive.
3. Open your project. Dumpie inspects it and turns on the sources it supports.

## Quick start

Open the **Dumpie** tool window: if the project cannot send anything yet (no `spatie/ray` and no `VAR_DUMPER_FORMAT`
in `.env`), it shows what is missing and offers the fix right there. The same information is in
**Settings | Tools | Dumpie**.

![Setup hints in the empty tool window](docs/marketplace/09-setup.png)

### dump() and dd()

Add to the project's `.env`, or press **Add to .env** in the tool window or on the settings page (it never adds a
key twice and updates an existing value instead):

```dotenv
VAR_DUMPER_FORMAT=server
VAR_DUMPER_SERVER=127.0.0.1:9912
```

Call `dump($order)` anywhere: the dump appears in the Dumpie tool window instead of the page or the console.
When the IDE is closed, PHP prints dumps as usual.

> Laravel reads `.env` only when the configuration is not cached. After `php artisan config:cache`, run
> `php artisan config:clear` or define the variables in the server environment.

### Log files

Nothing to configure. Dumpie follows the log directory it found in the project (`storage/logs/*.log` or
`var/log/*.log`) and shows records written after the project was opened. The paths can be changed in the settings.

### Projects with spatie/ray

Nothing to configure either: `ray()` sends to `localhost:23517` by default, and that is where Dumpie listens.

```php
ray('Hello from Dumpie')->green();
ray()->table(['Customer' => $order->customer, 'Total' => $order->total], 'Order');
ray()->showQueries();
```

> **Quit the Ray desktop app.** Ray and Dumpie both listen on port `23517`, and only one application can use a port
> at a time: while Ray is running, `ray()` output goes to Ray and Dumpie receives nothing from `ray()`.
> `dump()`, `dd()` and log files are not affected — they reach Dumpie even with Ray open.

**Keeping Ray and Dumpie side by side.** Give Dumpie its own port in **Settings | Tools | Dumpie**, for example
`23518`, and point the projects that should report to Dumpie at it. Every project sends `ray()` output to one port
only, so the other projects keep reporting to Ray:

```dotenv
RAY_PORT=23518   # spatie/laravel-ray, read by the published config/ray.php
```

For plain `spatie/ray`, set `'port' => 23518` in the project's `ray.php`.

### Docker and Laravel Sail

PHP inside a container reaches the IDE through `host.docker.internal`:

```dotenv
VAR_DUMPER_SERVER=host.docker.internal:9912
RAY_HOST=host.docker.internal   # spatie/laravel-ray, read by the published config/ray.php
```

File links work without extra setup: a container path is matched to the project by its longest existing suffix.

## Using the tool window

![Exception with code snippet, and the feed filtered to errors and warnings](docs/marketplace/04-errors.png)

The panel follows the IDE theme and fonts:

![Light theme](docs/marketplace/10-light.png)

- **Open the source**: click `File.php:42` under an entry.
- **Filter**: click color dots (several at once), or the search icon to filter by text; `Esc` clears the search.
- **Pause**: the pause button freezes the feed; new entries are counted and shown when you resume.
  A `ray()->pause()` call is never held back, so the script does not hang.
- **Collapse**: click the colored left edge of an entry.
- **Copy**: hover an entry and press the copy icon. The entry goes to the clipboard as a Markdown block
  (```` ```php ````, ```` ```sql ````, ```` ```json ````) together with its `file:line`.
- **Open in editor**: the icon next to Copy opens the entry in an editor tab, handy for big dumps and JSON.
- **Screens**: `ray()->newScreen('Checkout')` starts a fresh screen; earlier screens stay in the screen selector. The
  `×` on the active tab closes a screen.
- **Sessions**: with several projects open, the project bar under the toolbar switches between their sessions or
  shows **All**. Every project keeps its color; a new tool window always starts with its own project.
- **Clear**: the broom icon clears the session you are looking at. The clock icon lists what was cleared;
  **Restore** puts it back into the project it came from.
- **Gutter icons**: lines that produced output get a Dumpie icon in the editor gutter. Hover it to see what the line
  sent, click it to jump to the entry.
- **SQL**: queries that ran 3 or more times with different values are marked **Repeated** (possible N+1), queries over
  the threshold (100 ms by default) are marked **Slow**.

![Gutter icons: hover shows what a line sent, a click jumps to the entry](docs/marketplace/06-gutter.png)

![SQL insights: repeated and slow queries](docs/marketplace/05-sql.png)

![An entry opened in an editor tab](docs/marketplace/08-open.png)

### Keyboard shortcuts

![Shortcuts and the archive of cleared output](docs/marketplace/07-keys.png)

They work while the Dumpie tool window has focus, so ⌘K still commits in the editor. All of them are in
**Tools | Dumpie** and can be remapped in **Settings | Keymap**.

| Action | macOS | Windows / Linux |
|---|---|---|
| Clear all | ⌘K | Ctrl+K |
| Clear the current screen | ⌘L | Ctrl+L |
| New screen | ⌘N | Ctrl+N |
| Pause / resume | ⌘P | Ctrl+P |
| Search | ⌘F | Ctrl+F |
| Next / previous screen | ⌘] / ⌘[ | Ctrl+] / Ctrl+[ |

## Settings

**Settings | Tools | Dumpie**

- **Receivers (all projects)**: turn the `spatie/ray` receiver (port `23517`) and the `dump()` receiver (port `9912`)
  on or off and change their ports.
- **Display**: open the tool window when output arrives, keep the bars pinned while scrolling, use a white
  background in light themes, show gutter icons.
- **SQL queries**: query totals in the toolbar, **Repeated** marks, **Slow** marks and their threshold.
- **This project**: which sources feed this project (`ray()`, `dump()`, log files), the log file patterns, and what was
  detected in `composer.lock` and `.env`. **Detect Again** re-reads the project.

Project settings are stored in `.idea/workspace.xml`, so they are not shared through version control.

## Troubleshooting

- **`ray()` calls do not show up, but dumps and logs do**: the Ray desktop app is probably running and holds port
  `23517`. Quit Ray, or move Dumpie to another port as described in
  [Keeping Ray and Dumpie side by side](#projects-with-spatieray).
- **"Port … is used by another application"**: another tool listens on that port, typically the Ray desktop app,
  `php artisan dump-server` or `bin/console server:dump`. Stop it or change the port in the settings (and in
  `VAR_DUMPER_SERVER` / `RAY_PORT` of the project).
- **Dumps still show up in the browser**: PHP does not see `VAR_DUMPER_FORMAT`. Check `.env`, the cached Laravel
  configuration and, for Docker, `VAR_DUMPER_SERVER`.
- **The tool window stays empty**: make sure a project is open in the IDE (receivers start with the first project)
  and that the source is enabled under **This project**.

Still stuck? [Open an issue](https://github.com/sadonsergey/dumpie/issues) and describe your setup.

## Building from source

Requirements: JDK 21 or newer.

```bash
./gradlew buildPlugin   # build/distributions/dumpie-<version>.zip
./gradlew runIde        # starts a sandbox IDE with the plugin installed
./gradlew test          # unit, IDE and browser tests (the first run downloads headless Chromium)
```

By default the build downloads PhpStorm (`platformVersion` in `gradle.properties`). To use an installed IDE instead,
create a `local.properties` file (it is not committed):

```properties
platformLocalPath=/Applications/PhpStorm.app
```

## Contributing

Dumpie is a young project, and I am always happy to make it better. Ideas, bug reports and code are all welcome:

- **Found a bug or have an idea?** [Open an issue](https://github.com/sadonsergey/dumpie/issues). Include your IDE
  version, PHP framework and, if possible, the code that produced the output.
- **Want to change something yourself?** Fork the repository and send a pull request. Small focused changes are the
  easiest to review; for bigger ones, start with an issue so we can agree on the approach.
- **Built something on top of Dumpie?** Forks are welcome too. Keep the license notice, and feel free to share what
  you made.

## Support the project

Dumpie is free and will stay free. If it saves you time, you can support its development with a donation on
[Boosty](https://boosty.to/sadonsergey/donate). A star on GitHub helps too: it makes the project easier to find.

## License

Dumpie is free and open source software under the [MIT License](LICENSE).

In plain words:

- **You can** use Dumpie for free, for personal and commercial work, read and change its code, fork it and
  distribute your own versions.
- **You must** keep the copyright notice and the license text in copies and substantial parts of the code.
- **No warranty**: the software is provided as is, without any guarantees; the author is not liable for any damage
  arising from its use.

The [LICENSE](LICENSE) file is the legally binding text; this summary is for convenience only.

Dumpie is an independent project, not affiliated with or endorsed by Spatie. Ray and spatie/ray are products of Spatie.
