# Temple Trekking Script — Changelog

All notable changes to this plugin are documented here.
Format: [MAJOR.MINOR.PATCH] — Date — Summary

---

## [1.0.0] — 2026-03-10

Initial stable release.

### Features
- Full Temple Trekking / Burgh de Rott Ramble automation (both directions)
- Multi-trek loop: automatically restarts after each completed trek
- Route 1 selection via map widget

### Event handlers
- **Combat (evade)** — clicks Evade-event path stone (id:13831)
- **Bridge — dead trees** — chops 3 logs, repairs bridge stage by stage (ids:13834→13835→13836→13837), crosses, continues trek
- **Bridge — undead lumberjacks** — kills zombies for planks (ground-first priority, attacks proactively), repairs bridge (ids:13834→22533→22534→22535), crosses, continues trek
- **River crossing** — cuts 3 short vines, combines into long vine, attaches to bare branch (id:13845), swings from vine branch (id:13846), continues trek

### Architecture decisions
- State machine: IDLE → START_TREK → SELECT_ROUTE → TREKKING ↔ DETECT_EVENT → [event handler] → TREKKING → TREK_COMPLETE → loop
- OBJ_CONTINUE_PATH (13832) never used as event trigger — only clicked explicitly after puzzle completion
- `logsCollected` and `planksCollected` latches prevent re-entering gather phases once materials are secured
- Separate bridge stage helpers for log variant (13834/13835/13836/13837) and plank variant (13834/22533/22534/22535)
- World-object state used as source of truth throughout — no stale references held across ticks
