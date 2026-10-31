# ProtonVPNPort — Design & Roadmap

Status: v0.1.0 implemented · Applies to: plugin v0.0.2 → v1.0

## Implementation status

| Version | State |
|---|---|
| v0.1.0 | **Implemented.** First live test 2026-10-31 (Windows): port detection and setting confirmed working, and the NAT-PMP trial revised as a result (§4.6.1). Released as 0.1.1 |
| 0.1.2 | **Implemented:** disagreement flagging (§4.8) |
| v0.2.0 – v1.0.0 | Not started |

Where v0.1.0 differs from or adds to the text below:
- **Forced re-read (§4.3, point 2).** Even when the size and modified time look unchanged, the log is re-copied at least every 5 minutes. Windows can report stale size and time for a file another process holds open, so an unchanged-looking file isn't proof that it is unchanged.
- **`UNCHANGED` (§2).** This kind is only returned when the cached result had a port. An unchanged file with no port keeps returning `NOT_FOUND`, without copying it again.
- **What gets compared (§4.6).** The NAT-PMP **TCP** port is compared with the log (UDP is used if TCP failed). Differing TCP and UDP ports are counted separately as `TCP_UDP_SPLIT`, not as their own outcome.
- **Logging settings (§4.5).** The old `debug_level` setting is no longer registered. `verbose` takes its default from the old `debug` value.
- **Project layout (§4.1).** `Eclipse_Project/` keeps its name, but it now uses the standard Maven layout inside (`src/main/resources`, `src/test/java`). Moving it to the repo root is still planned for v0.2.0.
- **Version number.** The v0.1.0 milestone is released as plugin version **0.1.1**. The first test build carried 0.1.0, and BiglyBT won't install a rebuilt jar over the same version number.
- **GitHub Releases** (planned for v0.2.0) were adopted in v0.1.0. The jar is no longer committed; see `docs/RELEASING.md`.
- **NAT-PMP trial request (§4.6).** Revised after the first live test: the trial asks about the app's mapping (internal and suggested port = log port, 60 s) instead of creating a new one, and skips checks with no log port. Counters reset on the schema change, and the trial file is now `natpmp-trial-v2.csv`.
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
| **v0.3.0** | NAT-PMP mode (conditional) | If the criteria are met: a NAT-PMP "own mapping" mode with keep-alive for setups without the Proton app (e.g. Linux + WireGuard). The log stays primary on Windows |
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

> **Revised after the first live test (2026-10-31).** The original design asked
> the gateway for a *new* mapping, using Proton's Linux recipe (internal port 1).
> On Windows, with the Proton app running, that returned a different port from
> the app's, and the port didn't forward anything. See §4.6.1. The trial now asks
> about the **app's own mapping** instead.

**Purpose.** Check that the plugin can talk to Proton's gateway over NAT-PMP and
that the gateway confirms the port the log reports. On Windows this can't make
NAT-PMP a replacement for the log: to ask about the app's mapping you need to
know its port, and only the log tells us that. What the trial does establish is
the groundwork for a NAT-PMP mode on setups without the Proton app (§6).

**Rules for the trial period**
- The **log is the source of truth.** NAT-PMP results are **never** written to
  BiglyBT's config.
- A NAT-PMP failure never blocks or delays applying the log's result. It runs
  after the log check, with short timeouts.
- **No request without a port from the log.** If the log has no valid port, the
  trial skips that check rather than creating a new mapping.
- It can be turned off in settings (`natpmp.enable`).

**Protocol** (RFC 6886). The request asks about the mapping for the log's port,
with the same lifetime the Windows app uses:

```
Request (12 bytes)                 Response (16 bytes)
 0  u8  version = 0                 0  u8  version = 0
 1  u8  opcode 1=UDP 2=TCP          1  u8  opcode + 128
 2  u16 reserved = 0                2  u16 result code (0 = success)
 4  u16 internal port = log port    4  u32 seconds since start of epoch  (recorded)
 6  u16 suggested ext = log port    8  u16 internal port                 (recorded)
 8  u32 lifetime = 60              10  u16 mapped external port   ← compared with the log
                                   12  u32 lifetime granted              (recorded)
```

- Transport: `DatagramSocket` to `gateway:5351`. Timeouts of 250 ms, 500 ms and
  1000 ms (3 tries, which cuts the RFC's 9-try back-off short). If every try
  fails, the result is `ERROR(timeout)`.
- Send both the UDP and TCP requests, and record whether they agree with each other.
- `NatPmpClient.encodeRequest` and `decodeResponse` are pure functions and are unit-tested with fixed byte arrays.
- No extra dependencies.

**Why the request is safe.** NAT-PMP has no read-only query, so every request
also renews the mapping, to the lifetime it asks for. The Windows app asks for
60 s and renews every 30 s (§4.6.1). The trial also asks for 60 s, so it only
moves the expiry to roughly where the app's next renewal would put it, and never
shortens it.

**Caveat on matches.** A gateway that simply echoes back whatever port is
suggested would always "match". The internal port, granted lifetime and epoch
fields are recorded so that can be spotted.

**Classifying each check** (`ComparisonTracker`):

| Outcome | Log | NAT-PMP | Meaning |
|---|---|---|---|
| `MATCH` | port P | port P | The gateway confirms the app's port |
| `MISMATCH` | port P | port Q ≠ P | Disagree. Note whether it clears by the next check (*transient*, likely just after a reconnect) or persists (*persistent*) |
| `LOG_ONLY` | port P | error/timeout | NAT-PMP unavailable (VPN down? OpenVPN rather than WireGuard? different gateway?) |
| `NATPMP_ONLY`, `NEITHER` | none | — | No longer produced: without a log port the trial skips the check |
| `TCP_UDP_SPLIT` | — | TCP ≠ UDP | Flag. We assume Proton uses one port for both |

**Recording the results**
- Counters for each outcome are stored in plugin config, so they survive restarts. They can be reset with `natpmp.reset_stats`. A trial *schema* number is stored with them; when it changes (v1 → v2, the request change above), the counters reset automatically so results from different methods never mix.
- Every outcome change is logged (a warning unless it's `MATCH`).
- A trial file in CSV format, at `getPluginUserFile("natpmp-trial-v2.csv")`: `timestamp_utc, log_kind, log_port, natpmp_kind, natpmp_tcp, natpmp_udp, outcome, tcp_udp_split, natpmp_rtt_ms, natpmp_internal_port, natpmp_lifetime_s, natpmp_epoch_s, log_file_mtime_utc`. Capped at 1 MB with a single `.1` rollover. Any v1 file (`natpmp-trial.csv`) from the first test is left as it was.
- The status area shows a summary such as "NAT-PMP trial: 412 match / 3 mismatch (3 transient) / 5 log-only since 2026-11-02".

#### 4.6.1 Findings from the first live test (2026-10-31, Windows, VM guest)

| Observation | Source |
|---|---|
| The log port (49669) was set in BiglyBT and is **open from outside** | canyouseeme.org from inside the VM guest, via the VPN address |
| A v1 trial request (internal port 1) returned **49260**, consistently, and that port was **not reachable** | Plugin log; BiglyBT NAT test straight after "Check now" |
| The app's port forward stayed open while the v1 requests ran, so they didn't disturb it | Same tests |
| The app logs `Received PortForwarding Status '<state>' … Port pair 49669->49669, expiring in 00:01:00 at <local time>` about every 10 s. The states are `SleepingUntilRefresh` and `PortMappingCommunication` | `client-logs.txt` |
| The app **renews every 30 s with a 60 s lifetime** (146 renewals in 73 minutes, all 30 s apart). The port stayed the same for the whole session | `client-logs.txt` |
| Proton allows **one working forward per VPN session** | Project owner's understanding; consistent with the above |

Conclusions:
1. On Windows, the log is the right source, and it stays the primary one.
2. A plugin-held mapping can't work alongside the Proton app, so the "own mapping" question moves to the no-app setup in §6.
3. Each line starts with an ISO-8601 UTC timestamp and repeats every ~10 s while forwarding is active. That makes a reliable "is the forward still live?" signal for stale-port handling (§5).

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

### 4.8 Disagreement flagging (0.1.2)

On Windows, NAT-PMP can't find the Proton app's port by itself, only confirm a
port it's given (§4.6.1). So the log stays the source, and NAT-PMP acts as a
check whose disagreements are made **visible** instead of sitting in a CSV.

**States** (`PortAgreement`, shown as "Port check:" in Status):

| State | When | What the user sees |
|---|---|---|
| Agree | `MATCH` | "Agree — the VPN server confirms port P" |
| Re-checking | First `MISMATCH` | Status shows both ports. The log cache is cleared and a check runs on the next tick (~15 s) |
| Disagree | `MISMATCH` again on the re-check | Status shows both ports, since when and how many checks. **One BiglyBT alert per episode** (popup plus alert history), if the alert setting is on (default). Otherwise a warning in the plugin log |
| Resolved | `MATCH` after Re-checking or Disagree | Log line "disagreement resolved after N checks" |
| No answer | `LOG_ONLY` | Status only; no alert (could be OpenVPN, a different gateway, or the VPN going down) |
| Off / Nothing to check | Trial off / no log port | Status only |

**BiglyBT always stays on the log's port.** The likeliest cause of a lasting
disagreement is a stale log. Then the plugin asks the gateway about an old port
and gets back a new, non-working mapping (as with 49260 in §4.6.1), so switching
to the NAT-PMP port would make things worse.

**Which side is stale?** The parser reads each line's leading UTC timestamp
(§4.6.1). The status and alert show the log entry's age, and call an entry older
than 2 minutes "stale". The app logs about every 10 s while forwarding is active.

Setting: `protonport.natpmp.alert` (default on), in the NAT-PMP group.

## 5. v0.2.0 — Harden and automate

| Item | Design |
|---|---|
| **CI** | GitHub Actions. On push/PR: `mvn -B verify` on JDK 8 and 21, with the BiglyBT jar fetched from a pinned BiglyBT GitHub release asset (or cached). On a `v*` tag: build the jar and attach it to a draft GitHub Release, which automates steps 3 and 5 of `docs/RELEASING.md` |
| **Package rename** | `org.biglybt.plugin.protonvpn` → `io.github.alex870.protonport`, so it doesn't look like it's in BiglyBT's namespace. `plugin.id=protonport` and all config keys stay the same, so user settings carry over. Only `plugin.class` / `plugin.langfile` change |
| **Stale-port handling** | Use the leading UTC timestamp of the last `Port pair` line. While forwarding is active, the Windows app writes one about every 10 s (§4.6.1). If the newest is more than ~2 minutes older than the log's last write (or the current time), the forward is no longer being maintained. Show "Port forwarding inactive (VPN disconnected?)" in Status and warn once (still no automatic port change). `LOG_ONLY` trial results and a `NetworkInterface` check for a local address in the gateway's /16 can corroborate it |
| **Restore original port** | Optional setting. When the plugin first changes the port, it saves the original. On disable or shutdown (`UnloadablePlugin.unload` / closedown listener), it puts the original back |
| **Binding hint** | Check BiglyBT's bind-IP setting. If it's empty or doesn't include the VPN interface, show a one-time warning: "Port forwarding works best when BiglyBT is bound to the Proton VPN interface (Options → Connection → Advanced Network Settings)". Inform only; never change it |
| **Trial review** | Read the counters and CSV from real use and decide on v0.3.0 using the criteria in §6 |
| **Layout** | Move `Eclipse_Project/` to the repo root (standard Maven layout) if not done in v0.1.0 |

---

## 6. v0.3.0 — NAT-PMP mode for setups without the Proton app (only if the criteria are met)

**Scope (revised after §4.6.1).** On Windows with the Proton app, the log stays
the primary source: Proton allows one forward per session, the app owns it, and
only the log tells us its port. NAT-PMP-primary mode is therefore aimed at
setups **without the Proton app**, for example Linux with a manual WireGuard
config and port forwarding enabled. There the plugin must create and keep its
own mapping, as Proton's Linux instructions describe.

**Criteria for adding NAT-PMP-primary mode:**
- From the Windows trial: the gateway answers in at least **99 %** of checks where the log shows an active port, with **zero persistent `MISMATCH`es** and no `TCP_UDP_SPLIT`. This shows the plugin's NAT-PMP client works reliably against Proton's gateways.
- From a test on a no-app setup (manual WireGuard): a mapping created and renewed by the plugin is **reachable from outside** (for example with canyouseeme.org), keeps its port across renewals for a whole session, and gets a new port after a reconnect.
- No evidence that NAT-PMP requests disturbed the Proton app's own mapping on Windows.

**If added:**
- A setting `protonport.source` with options *Auto* (default), *Log file* or *NAT-PMP (own mapping)*.
  - *Auto* on Windows: the log, as today. The NAT-PMP check of the app's mapping stays as a diagnostic.
  - *Auto* on Linux/macOS: NAT-PMP (own mapping), unless a log path is set.
- **Mapping keep-alive.** In own-mapping mode, Proton requires the mapping to be renewed within its 60 s lifetime, or the port is lost. The plugin renews every 30 s (as the Windows app does) on its own timer, independent of the check interval, and backs off on errors. Never enable this while the Proton app is managing port forwarding.
- README: document the setup without the app (WireGuard config with NAT-PMP / port forwarding enabled).

**If not promoted:** keep NAT-PMP as a diagnostic, document the failure modes
it showed, and treat Windows log parsing as the supported path.

**Also on the roadmap: own-forward mode on Windows (tabled 2026-10-31).** With port
forwarding turned **off** in the Proton app, the plugin would hold the session's
forward itself. That makes NAT-PMP the true primary source on Windows too,
independent of the log. Points to settle first:
- Does a plugin-held mapping actually forward traffic when the app isn't holding one? Test it with an experimental setting (off by default) and canyouseeme.
- The log can no longer cross-check the port, because the app isn't forwarding. The log check becomes a **conflict check** instead: flag it if the log shows the app's port forwarding is also active, since two forwards would compete for the session's single slot.
- The renewal timer (every 30 s) must be stopped whenever the plugin is disabled or unloaded.

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
2. **Proton Windows log rotation.** What happens to `client-logs.txt` when it rotates? Does it get renamed, truncated or numbered? The app writes about 4 KB per minute while forwarding is active (§4.6.1), so rotation is likely to happen in long sessions.
3. **Gateway on OpenVPN.** Is `10.2.0.1` also the NAT-PMP gateway on Proton's OpenVPN connections, or only WireGuard? The trial's `LOG_ONLY` data, together with the protocol in use, will show this.
4. **Linux/macOS log format.** Does Proton's Linux app log forwarded ports at all, and in what format? This only matters if NAT-PMP isn't promoted.
5. **BiglyBT core on Maven.** Confirm whether a published artifact exists. If one does, use it instead of the local-jar setup.
