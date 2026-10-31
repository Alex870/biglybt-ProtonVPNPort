# ProtonVPNPort — Design & Roadmap

Status: v0.1.0 implemented · Applies to: plugin v0.0.2 → v1.0

## Implementation status

| Version | State |
|---|---|
| v0.1.0 | **Implemented** (unit-tested; not yet tested in a live BiglyBT + Proton VPN setup) |
| v0.2.0 – v1.0.0 | Not started |

Where v0.1.0 differs from or adds to the text below:
- **Forced re-read (§4.3, point 2).** Even when the size and modified time look unchanged, the log is re-copied at least every 5 minutes. Windows can report stale size and time for a file another process holds open, so an unchanged-looking file isn't proof that it is unchanged.
- **`UNCHANGED` (§2).** This kind is only returned when the cached result had a port. An unchanged file with no port keeps returning `NOT_FOUND`, without copying it again.
- **What gets compared (§4.6).** The NAT-PMP **TCP** port is compared with the log (UDP is used if TCP failed). Differing TCP and UDP ports are counted separately as `TCP_UDP_SPLIT`, not as their own outcome.
- **Logging settings (§4.5).** The old `debug_level` setting is no longer registered. `verbose` takes its default from the old `debug` value.
- **Project layout (§4.1).** `Eclipse_Project/` keeps its name, but it now uses the standard Maven layout inside (`src/main/resources`, `src/test/java`). Moving it to the repo root is still planned for v0.2.0.
- **GitHub Releases** (planned for v0.2.0) were adopted in v0.1.0. The jar is no longer committed; see `docs/RELEASING.md`.
- **Unload.** The plugin implements `UnloadablePlugin`, so its timer and UI are cleaned up on unload.

This document covers what changes in the plugin, why, and in what order. It
builds on the review of v0.0.2 and on these decisions from the project owner:

| Topic | Decision |
|---|---|
| Copying the log before reading | **Keep the copy.** It was chosen for practical reasons: the copy is fast, and we don't know how long Proton VPN holds a lock on its log. Give the temp file a unique name so two instances can't clash. |
| Non-Windows paths | Use OS-neutral handling where possible (temp dir, path joining) and try a list of candidate log paths for each OS. The user-set path always wins. |
| NAT-PMP | Ship it in the **next update** as a **secondary, comparison-only** check. The **log stays the source of truth.** Gather data on how reliable NAT-PMP is before deciding whether to promote it. |
| BiglyBT VPN Helper | Out of scope. It doesn't support Proton and hasn't been maintained in years. |

---

## 1. Goals and non-goals

**Goals**
- Keep BiglyBT's incoming TCP/UDP port equal to the port Proton VPN currently forwards, with as little delay and as few surprises as possible.
- Fail loudly and safely: never write an invalid port, and always tell the user what happened.
- Make the plugin buildable, testable and releasable by anyone who clones the repo.
- Collect evidence on whether NAT-PMP can replace log parsing, which is the only realistic route to working on any OS.

**Non-goals (for now)**
- Managing the VPN connection itself (connecting, choosing servers, kill switch).
- Supporting VPN providers other than Proton.
- Changing BiglyBT's network binding automatically. The plugin may *warn* about it but won't change it.

---

## 2. Target architecture

v0.0.2 is a single class with one anonymous timer callback. Every helper takes
`(isDebug, debugLevel, logger)` as arguments. The target split:

```
PortConfig (Plugin entry point; wiring only)
 ├── Settings            typed access to config parameters, defaults, migration
 ├── PluginLog           always / verbose logging → BiglyBT log view
 ├── CheckScheduler      15 s tick; decides when a check is due (time-based)
 ├── PortCheck           one check cycle: query sources → compare → apply
 │    ├── LogFilePortSource   (PRIMARY / source of truth)
 │    │     ├── LogLocator     resolves the log path (user setting → OS candidates)
 │    │     ├── LogSnapshot    unique temp copy, change detection, cleanup
 │    │     └── LogLineParser  pure function: text → Optional<port>
 │    ├── NatPmpPortSource    (SECONDARY / comparison only, experimental)
 │    │     └── NatPmpClient   pure encode/decode + UDP transport
 │    ├── ComparisonTracker   classifies & records log vs NAT-PMP results
 │    └── PortApplier         validates range, writes TCP/UDP core params
 └── StatusView          status lines + "Check now" in config section
```

Shared interface:

```java
interface PortSource {
    PortResult query();          // never throws
}

final class PortResult {
    enum Kind { FOUND, NOT_FOUND, UNCHANGED, ERROR }
    Kind kind; Integer tcpPort; Integer udpPort; String detail; long timestampMs;
}
```

Rules:
- `query()` never throws. All exceptions turn into `ERROR` results with a message.
- `PortCheck.run()` wraps everything in `try { … } catch (Throwable t)`, so a failure can't escape into BiglyBT's timer thread.
- Only `PortApplier` writes BiglyBT config.
- Target **Java 8 bytecode**, to match the released 0.0.2 jar and the oldest Java BiglyBT runs on.

---

## 3. Release plan

| Version | Theme | Summary |
|---|---|---|
| **v0.1.0** (next update) | Stabilize + trial | Repo and build cleanup, bug fixes, safer log handling, OS-neutral paths, **NAT-PMP shadow trial**, status UI |
| **v0.2.0** | Harden + automate | CI, release automation, package rename, stale-port handling, restore-on-disable, binding hint, evaluate trial data |
| **v0.3.0** | Promote (conditional) | If the trial criteria are met: NAT-PMP becomes the primary source on non-Windows (and optionally Windows), with mapping keep-alive; log becomes the fallback |
| **v1.0.0** | Distribute | Naming conventions, documentation, BiglyBT plugin listing, update checks |

The sections below describe each version in detail.

---

## 4. v0.1.0 — Stabilize and start the NAT-PMP trial

### 4.1 Repository and build cleanup

| Item | Action |
|---|---|
| `Eclipse_Project/target/` committed (two builds that disagree, plus an orphan `Test_GetPort.class`) | Delete it and add a `.gitignore` (`target/`, `*.class`, IDE files) |
| `Eclipse_Project/plugins/azupdater/` (BiglyBT's own updater, unrelated) | Delete |
| Duplicate `LICENSE` | Keep the one at the repo root |
| `pom.xml` has no BiglyBT dependency, so `mvn package` can't compile | Add a `provided`-scope dependency on the BiglyBT core jar (see below) |
| `pom.xml` targets `<release>24</release>`, but the shipped jar is Java 8 | Change to `<release>8</release>` |
| Versions disagree (`0.0.1-SNAPSHOT` vs `0.0.2`) | The pom becomes the single source. Generate `plugin.properties` from it with Maven resource filtering (`plugin.version=${project.version}`) |
| Release jar committed under `Release/` | **Done in v0.1.0 (brought forward from v0.2.0):** jars are published as GitHub Releases. `Release/` holds only a pointer README. The process is in `docs/RELEASING.md` |
| README typos ("reconfigues", "repositiory") | Fix |

**BiglyBT dependency.** As far as we know, BiglyBT doesn't publish its core to
Maven Central (this needs checking). The fallback is to reference the
`BiglyBT.jar` from a local install:

```xml
<properties>
  <biglybt.jar>${env.BIGLYBT_JAR}</biglybt.jar>
</properties>
<dependency>
  <groupId>com.biglybt</groupId>
  <artifactId>biglybt-core</artifactId>
  <version>local</version>
  <scope>system</scope>
  <systemPath>${biglybt.jar}</systemPath>
</dependency>
```

Alternatively, developers run `mvn install:install-file` once and the scope
becomes `provided`. Either way, document the steps in the README's developer
section. A `maven-jar-plugin` / `assembly` step produces the plugin jar with
`plugin.properties` at its root.

Optionally, move `Eclipse_Project/` to a standard repo-root Maven layout. Eclipse
imports Maven projects natively, so the IDE-named folder isn't needed. (This
can wait until v0.2.0 to keep the v0.1.0 diff easy to review.)

### 4.2 Bug fixes

| # | Bug (v0.0.2) | Fix |
|---|---|---|
| B1 | The interval only works for multiples of 15 s. For example, 100 s gives a check every 30 s and 1000 s gives one every 330 s (`PortConfig.java:79-83`) | **Time-based scheduling.** Keep a 15 s tick. A check is due when `now - lastCheck >= intervalSecs * 1000`. The label becomes "checked on a 15 s tick" |
| B2 | No check at startup; the first one waits a full interval | Run a check on the first tick (about 15 s after load). Optionally run one right away on a background thread |
| B3 | Errors and port changes are only logged in debug mode, so the plugin is silent by default | See §4.5. Errors, warnings and port changes are **always** logged |
| B4 | The debug-level label says "higher is more verbose", but the code does the opposite; levels 4–5 do nothing | Replace with a single "Verbose logging" switch (§4.5) |
| B5 | `substring(0, indexOf("->"))` throws when the line has no `->`, and `Integer.parseInt` throws on stray whitespace. Neither is caught | A regex-based `LogLineParser` (§4.3) plus a catch-all in `PortCheck` |
| B6 | A malformed line can leave the temp file behind | `finally`-based cleanup (§4.3) |
| B7 | No check that the port is in range | `PortApplier` rejects anything outside 1024–65535 and logs it |
| B8 | The UDP change message prints the old TCP port; messages call Proton's log the "BiglyBT log file"; dead `tick_count == 0`; pointless `Math.round` | Fix in passing |

### 4.3 Log reading (primary source)

**The copy step stays** (owner decision). It changes in these ways:

1. **Unique temp file.** Create it with
   `Files.createTempFile("protonport-", ".log")`, which uses `java.io.tmpdir`
   and works on every OS. Delete it in a `finally` block. Call
   `File.deleteOnExit()` as a backstop. On startup, remove any stale
   `protonport-*.log` files older than an hour (left by crashes).
2. **Skip copying when nothing changed.** Before copying, read the source's
   `lastModified` and `size` (which doesn't need the file contents). If both
   match the last check, return `UNCHANGED` and reuse the last parsed port. This
   keeps the copy approach and makes short intervals almost free.
3. **Retry on lock contention.** If the copy fails with an `IOException`,
   retry up to 3 times with a 250 ms back-off before reporting `ERROR`. Record
   how often this happens; that answers the old "how long does Proton hold the
   file?" question.
4. **Parse with a regex**, not `substring`:

   ```java
   // matches e.g. "Port pair 51234->51234", tolerant of whitespace
   static final Pattern PORT_PAIR = Pattern.compile("Port pair\\s*(\\d{1,5})\\s*->\\s*(\\d{1,5})");
   ```

   Keep the **last** match in the file, as today. Return both numbers. If they
   differ, log a warning and use the first (the external port), which preserves
   current behavior.
5. **Read the copy with an explicit charset** (`UTF-8`, with
   `CodingErrorAction.REPLACE`) instead of `FileReader`'s platform default.
6. **Log rotation.** If the configured file is missing but sibling files match
   `client-logs*.txt`, read the newest by `lastModified`. Verify what Proton's
   Windows rotation actually looks like before relying on this; the design only
   requires that a missing current file gives `NOT_FOUND`, not a crash.

`LogLineParser` is a pure static function (`String → Optional<PortPair>`) with
no I/O, so it's covered by unit tests (§4.7).

### 4.4 OS-neutral paths

| Concern | v0.0.2 | v0.1.0 |
|---|---|---|
| Temp file location | `System.getenv("TEMP")` (null outside Windows) | `java.io.tmpdir` via `Files.createTempFile` |
| Path joining | Hard-coded `\\` | `Paths.get(base, "Proton", "Proton VPN", "Logs", "client-logs.txt")` |
| Default log path | Built once from `LOCALAPPDATA` (becomes `null\…` elsewhere) | `LogLocator` with a list of candidates for each OS |

`LogLocator` resolves paths in this order:
1. The user-configured path, if not blank. **The user's setting always wins.**
2. OS candidates, each checked with `Files.exists`:
   - **Windows:** `%LOCALAPPDATA%\Proton\Proton VPN\Logs\client-logs.txt` (verified).
   - **Linux:** `${XDG_CACHE_HOME:-~/.cache}/Proton/VPN/logs/` and `${XDG_STATE_HOME:-~/.local/state}/Proton/VPN/logs/`. *Unverified.* Proton's Linux app uses a different codebase and probably a different log format, so a matching path doesn't guarantee a working parser.
   - **macOS:** `~/Library/Logs/` and `~/Library/Application Support/` under `Proton*`. *Unverified.*
3. If nothing is found, show the status message "Proton VPN log not found — set the path manually".

The config field's default becomes **blank** ("auto-detect"), and the resolved
path is shown in the status area. Existing users who saved the old Windows
default keep it, which is harmless because it's the same file.

**Honest limitation:** outside Windows, log parsing is best effort. The
long-term answer for those platforms is NAT-PMP (§4.6 → §6), which doesn't
depend on any file path or log format.

### 4.5 Logging and settings

**Logging policy**

| Category | Logged when | Examples |
|---|---|---|
| Error | Always | Can't read the log; exception in a check; NAT-PMP socket error |
| Warning | Always | Port outside the valid range; log not found (once per state change, not every tick); log and NAT-PMP disagree |
| Change | Always | "Incoming TCP/UDP port changed 51234 → 60211 (source: log)" |
| Verbose | Only with **Verbose logging** on | Each check, copy/parse timings, unchanged-file skips, NAT-PMP round trips |

Repeated identical warnings are suppressed. A message is logged when the state
*changes* (for example, "found → not found") rather than on every tick.

**Settings** (existing keys are kept where the meaning is unchanged, so saved
configs carry over):

| Key | Type | Default | Notes |
|---|---|---|---|
| `protonport.enable` | bool | true | unchanged |
| `protonport.check_secs` | int 15–86400 | 60 | lower default, now cheap thanks to the change check |
| `protonport.vpn_log_path` | string | "" (auto) | see §4.4 |
| `protonport.verbose` | bool | false | replaces `debug` + `debug_level`. Migration: if the old `debug` was true, set `verbose` = true |
| `protonport.natpmp.enable` | bool | true | NAT-PMP comparison trial (§4.6) |
| `protonport.natpmp.gateway` | string | `10.2.0.1` | Proton's documented gateway |
| `protonport.natpmp.reset_stats` | action | — | resets the trial counters |
| `protonport.check_now` | action | — | runs a check immediately (on the timer thread pool, not the UI thread) |

### 4.6 NAT-PMP shadow trial (secondary source)

**Purpose.** Find out whether asking Proton's gateway over NAT-PMP reliably
gives the same port as the log. If it does, NAT-PMP can become the OS-neutral
primary source in v0.3.0.

**Rules for the trial period**
- The **log is the source of truth.** NAT-PMP results are **never** written to
  BiglyBT's config in v0.1.x.
- A NAT-PMP failure never blocks or delays applying the log's result. It runs
  after the log check, with short timeouts.
- It can be turned off in settings (`natpmp.enable`) in case it causes trouble.

**Protocol** (RFC 6886, the same requests as Proton's documented
`natpmpc -a 1 0 udp 60 -g 10.2.0.1` / `... tcp ...`):

```
Request (12 bytes)            Response (16 bytes)
 0  u8  version = 0            0  u8  version = 0
 1  u8  opcode 1=UDP 2=TCP     1  u8  opcode + 128
 2  u16 reserved = 0           2  u16 result code (0 = success)
 4  u16 internal port = 1      4  u32 seconds since start of epoch
 6  u16 suggested ext = 0      8  u16 internal port
 8  u32 lifetime = 60         10  u16 mapped external port   ← the value we compare
                              12  u32 lifetime granted
```

- Transport: `DatagramSocket` to `gateway:5351`. Timeouts of 250 ms, 500 ms and
  1000 ms (3 tries, which cuts the RFC's 9-try back-off short). If every try
  fails, the result is `ERROR(timeout)`.
- Send both the UDP and TCP requests, and record whether they agree with each other.
- `NatPmpClient.encodeRequest` and `decodeResponse` are pure functions and are unit-tested with fixed byte arrays.
- No extra dependencies.

**Known risk.** A mapping request is not read-only. It creates or renews a
60-second mapping on the gateway. On Windows the Proton app maintains the same
mapping, and we expect the gateway to return the session's single assigned
port. The trial must confirm this doesn't disturb the app's own mapping. That's
why it can be switched off, and why its first data points get special
attention.

**Classifying each check** (`ComparisonTracker`):

| Outcome | Log | NAT-PMP | Meaning |
|---|---|---|---|
| `MATCH` | port P | port P | Agree |
| `MISMATCH` | port P | port Q ≠ P | Disagree. Note whether it clears by the next check (*transient*, likely just after a reconnect) or persists (*persistent*) |
| `LOG_ONLY` | port P | error/timeout | NAT-PMP unavailable (VPN down? OpenVPN rather than WireGuard? different gateway?) |
| `NATPMP_ONLY` | none | port Q | NAT-PMP works but the log has no port (rotation, format change, or a non-Windows platform) |
| `NEITHER` | none | error | Probably no VPN or no port forwarding |
| `TCP_UDP_SPLIT` | — | TCP ≠ UDP | Flag. We assume Proton uses one port for both |

**Recording the results**
- Counters for each outcome are stored in plugin config, so they survive restarts. They can be reset with `natpmp.reset_stats`.
- Every outcome other than `MATCH` is logged as a warning (once per state change).
- A trial file in CSV format, at `getPluginUserFile("natpmp-trial.csv")`: `timestamp, log_port, natpmp_tcp, natpmp_udp, outcome, natpmp_rtt_ms, log_file_mtime`. Capped at 1 MB with a single `.1` rollover. This gives hard evidence for the v0.3.0 decision and can be attached to an issue.
- The status area shows a summary such as "NAT-PMP trial: 412 match / 3 mismatch (3 transient) / 5 log-only since 2026-11-02".

### 4.7 Status UI and tests

**Status** (labels in the config section, refreshed after each check):
- Log path in use (configured or auto-detected)
- Last check time and its result
- Port from the log, and port currently set in BiglyBT
- Last change: time and old → new
- Last error, if any
- NAT-PMP trial summary
- A **Check now** button

**Tests** (JUnit, run with `mvn test`):
- `LogLineParser`: the real line format, extra whitespace, no `->`, multiple matches (last one wins), out-of-range numbers, empty input. Use **anonymized real lines** from a Proton log as fixtures.
- `NatPmpClient`: request encoding, response decoding, error result codes, short or malformed packets.
- `CheckScheduler`: intervals that aren't multiples of 15 s fire at the right times (inject a fake clock).
- `ComparisonTracker`: outcome classification and how transient mismatches are counted.
- `PortApplier`: range checks; no write when the port is unchanged (mock `PluginConfig`).

---

## 5. v0.2.0 — Harden and automate

| Item | Design |
|---|---|
| **CI** | GitHub Actions. On push/PR: `mvn -B verify` on JDK 8 and 21, with the BiglyBT jar fetched from a pinned BiglyBT GitHub release asset (or cached). On a `v*` tag: build the jar and attach it to a draft GitHub Release, which automates steps 3 and 5 of `docs/RELEASING.md` |
| **Package rename** | `org.biglybt.plugin.protonvpn` → `io.github.alex870.protonport`, so it doesn't look like it's in BiglyBT's namespace. `plugin.id=protonport` and all config keys stay the same, so user settings carry over. Only `plugin.class` / `plugin.langfile` change |
| **Stale-port handling** | Use the trial data. If `LOG_ONLY` / `NEITHER` reliably show the VPN is down, add an *optional* "VPN appears down" status and warning (still no automatic port change). Also check, with `NetworkInterface`, for a local address in the gateway's /16 as a cheap "connected" hint |
| **Restore original port** | Optional setting. When the plugin first changes the port, it saves the original. On disable or shutdown (`UnloadablePlugin.unload` / closedown listener), it puts the original back |
| **Binding hint** | Check BiglyBT's bind-IP setting. If it's empty or doesn't include the VPN interface, show a one-time warning: "Port forwarding works best when BiglyBT is bound to the Proton VPN interface (Options → Connection → Advanced Network Settings)". Inform only; never change it |
| **Trial review** | Read the counters and CSV from real use and decide on v0.3.0 using the criteria in §6 |
| **Layout** | Move `Eclipse_Project/` to the repo root (standard Maven layout) if not done in v0.1.0 |

---

## 6. v0.3.0 — Promote NAT-PMP (only if the trial criteria are met)

**Criteria for promotion** (all must hold over the trial period):
- At least **4 weeks** of use and at least **20 distinct VPN sessions** (distinct log ports).
- **Zero persistent `MISMATCH`es.** Transient mismatches are fine if they always clear by the next check *and* NAT-PMP was the side that was right, i.e. the log caught up to NAT-PMP's port.
- NAT-PMP answers in at least **99 %** of checks where the log shows an active port.
- No evidence that the shadow requests disturbed the Proton app's own mapping (no unexplained port changes after a NAT-PMP request).
- `TCP_UDP_SPLIT` never seen.

**If promoted:**
- A setting `protonport.source` with options *Auto* (default), *Log file* or *NAT-PMP*.
  - *Auto* on Windows: NAT-PMP first, then the log if NAT-PMP fails. Keep comparing and warn on disagreement.
  - *Auto* on Linux/macOS: NAT-PMP only, unless a log path is set.
- **Mapping keep-alive.** Without the Windows app (for example on Linux with a manual WireGuard config), Proton requires the mapping to be renewed every 60 s or less, or the port is lost. In NAT-PMP-primary mode the plugin renews every ~45 s on its own timer, independent of the check interval, and backs off on errors.
- README: document the cross-platform setup (WireGuard config with NAT-PMP / port forwarding enabled).

**If not promoted:** keep NAT-PMP as a diagnostic, document the failure modes
it showed, and treat Windows log parsing as the supported path.

---

## 7. v1.0.0 — Distribution

- Name the jar `protonport_<version>.jar` to follow BiglyBT's `<plugin.id>_<version>.jar` convention, which plugin install and update checks expect.
- Fill in `plugin.properties` metadata (description, author, URLs).
- User documentation: install, settings, status meanings, troubleshooting ("port shows but no incoming connections" → binding, Proton P2P server, firewall).
- Consider submitting to the BiglyBT plugin listing so users get in-app updates.
- Localization: move all user-facing strings (status, warnings) into `Messages.properties`.

---

## 8. Open questions

1. **Copy vs direct read.** Covered by owner decision: keep the copy. v0.1.0's retry counters (§4.3, point 3) will show whether lock contention actually happens.
2. **Proton Windows log rotation.** What happens to `client-logs.txt` when it rotates? Does it get renamed, truncated or numbered?
3. **Gateway on OpenVPN.** Is `10.2.0.1` also the NAT-PMP gateway on Proton's OpenVPN connections, or only WireGuard? The trial's `LOG_ONLY` data, together with the protocol in use, will show this.
4. **Linux/macOS log format.** Does Proton's Linux app log forwarded ports at all, and in what format? This only matters if NAT-PMP isn't promoted.
5. **BiglyBT core on Maven.** Confirm whether a published artifact exists. If one does, use it instead of the local-jar setup.
