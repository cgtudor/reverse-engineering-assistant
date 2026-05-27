# ReVa MCP Server — Improvement Roadmap

Based on analysis of real session transcripts from nwncx_ee reverse engineering work (480+ get-decompilation calls, 74 search-decompilation calls across multiple sessions).

## Completed

- [x] **`search-listing` tool** — instant disassembly search without decompilation (new ListingToolProvider)
- [x] **`get-symbols` namePattern + symbolTypes filtering** — regex filter on symbols, avoids 37KB full dumps
- [x] **`search-decompilation` pagination** — `startFunctionIndex` + `maxFunctionsToProcess` for large programs
- [x] **Headless program opening** — `DomainFile.getDomainObject()` fallback when ProgramOpener fails in headless mode
- [x] **`mcp-reva --serve` mode** — persistent HTTP server for multi-session sharing
- [x] **`mcp-reva --project` flag** — open existing Ghidra projects in headless mode
- [x] **Auto-start server** — stdio bridge checks if server already running, connects to existing or starts new

## ~~Priority 1: Decompilation Cache~~ DONE

**Problem**: 438 `get-decompilation` calls but only 199 unique functions. Top function decompiled 27 times. ~134KB of redundant response content in 3 sessions alone. Over half of all decompilation calls are re-reads.

**Solution**: In-memory `Map<Address, CachedDecompilation>` in DecompilerToolProvider. Cache decompiled C text + metadata. Invalidate on program change events (rename, retype, etc.). Since only 6 renames, 6 datatype changes, 6 prototype changes occurred across ALL sessions, cache hit rate would be ~95%+.

**Impact**: Eliminates ~50% of get-decompilation latency. Biggest single token and time saver.

## ~~Priority 2: Auto-Override Function Limit for Large Programs~~ DONE

Instead of erroring when program exceeds function limit, auto-paginates with batch size = configured limit (default 1000). Response includes `autoPaginated: true` and `nextFunctionIndex` to continue. Tool description rewritten to strongly direct agent to faster alternatives (search-listing, get-symbols, find-cross-references) before using search-decompilation.

## ~~Priority 3: Timeout Blacklist~~ DONE (part of decompilation cache)

**Problem**: `0x1400b9df5` timed out 3 times in a row with identical parameters. `0x1400b8380` times out across multiple sessions — a known-bad function. Each retry wastes 10+ seconds of wall time plus tokens for the error response and agent reasoning.

**Solution**: Track addresses that timed out in a `Set<Address>` (per-program). On subsequent `get-decompilation` calls to the same address, return immediately with a message like "Decompilation previously timed out for this function. Use read-memory or search-listing to inspect it instead." Clear on program change events.

**Impact**: Prevents 3x wasted calls on known-bad functions. Small but prevents frustrating agent loops.

## ~~Priority 4: Better Tool Descriptions / Routing~~ DONE (part of auto-override)

**Problem**: Agent picks `search-decompilation` when `search-listing`, `get-symbols`, or `find-cross-references` would be faster. Current description says "try the cross reference tools first" but doesn't mention `search-listing` or `get-symbols`.

**Solution**: Update tool descriptions:
- `search-decompilation`: "SLOW — decompiles every function. Use search-listing for instruction patterns, get-symbols(namePattern=...) for symbol name search, get-strings for string search, or find-cross-references for call/data references. Only use this for patterns that exist exclusively in decompiled C code (variable names, type casts, high-level constructs)."
- `search-listing`: Emphasize it's the fast alternative
- `get-symbols`: Mention the new namePattern filter prominently

**Impact**: Fewer wrong-tool calls. Hard to quantify but probably 10-20% fewer wasted calls.

## ~~Priority 5: `get-function-overview` Compound Tool~~ DONE

Single tool returning decompilation + callers (with call-site snippets from cache) + callees + referenced strings + xref counts. Leverages the decompilation cache for caller context. Limits: 20 callers, 30 strings. Collapses 3-4 round trips into 1.

## ~~Priority 6: Cross-Session Decompilation Persistence~~ DONE

JSON file-backed persistent cache at `~/.reva/decomp-cache/<programName>.json`. Loaded lazily on first cache access per program per session. Flushed to disk on program close and server shutdown. Explicit invalidation (rename/retype) deletes the cache file. Stores cCode + signature + timestamp per function address.

## ~~Priority 7: Stale Lock Auto-Cleanup~~ DONE

On `LockException`, attempts to rename the lock file (atomic check — fails if file is actively held by another process on Windows). If rename succeeds, the lock is stale and gets deleted, then project open is retried.

## Data Sources

Analysis based on session transcripts from:
- `D--tdn-workspace-nwncx-ee/` project sessions
- 4 largest JSONL files (~50MB total)
- 480+ get-decompilation calls, 74 search-decompilation calls
- 199 unique functions decompiled across 438 addressed calls
