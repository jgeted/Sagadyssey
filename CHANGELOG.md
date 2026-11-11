# Changelog

## 1.9.0 - Release candidate

### Minecraft 1.21.11 migration

- Target Minecraft Java 1.21.11 and NeoForge 21.11.48.
- Port NPC rendering, horse equipment, saved data, world generation, item models, networking, and the in-game test runner.
- Register client packet handlers on the client to remove NeoForge's startup warning about `@OnlyIn`.
- Verify new-world persistence across a server restart. Use a new world for this release.

### Added

- `small_realm` r8 seeds individual long-term goals, persistent background memories and symmetric family/friend/mentor/rival relationships for its residents.
- Added command-wand selection workflows for assigning private beds, private storage, workplaces and shifts, plus politically authorized explicit institution contracts.
- Added one shared bounded-rational decision model for work, patrol, relationship defense, rest and retreat, including memory and relationship effects.
- Added time-limited NPC institution contracts with configurable physical emerald wages, automatic warehouse-to-private-storage payment, renewal, status inspection, arrears and breach history.
- Added persistent households with exclusive membership, household roles, head succession, player management commands and physically separate shared storage.
- Added persistent NPC hunger, real food consumption from carried/private/household stores, a visible eating action, and settlement-ration feedback without double-consuming public stock.
- Added household care decisions: well-fed NPCs can physically move one prepared food from their bag/private store into the shared pantry for a hungry member, with cooldowns, memories and relationship effects.
- Added persistent NPC schedule snapshots that unify explicit commands, day/night/flexible shifts, emergency hunger, work, patrol, rest and personal time; `/saga npc schedule` explains the current state.
- `small_realm` r9 seeds one kin household and one non-kin clergy household, each with a real shared pantry and initial possessions.
- `small_realm` r7 adds a persistent life record and a physical private room, unique bed, private storage barrel, workplace and shift for every fixed resident.
- Added fatigue accumulation/recovery, day/night/flexible sleep schedules, probabilistic rest decisions with diminishing returns and decision inertia.
- Added probabilistic low-health retreat based on injury, morale, courage and fatigue; unowned residents can now flee away from threats.
- `/saga npc inspect` now reports residence, bed, private storage, shift, fatigue, hunger, last food source, current authority and the latest explainable decision.
- `small_realm` r4 adds the Redstone Mine worksite, exposed coal/iron resources, furnace stations, two physical test-market chests and a twentieth resident trader.
- Added autonomous physical coal, wheat and emerald exchange between the two nearby markets, plus institutional warehouse access for unowned fixed residents.
- Added settlement patrol routes for combat NPCs, real furnace loading/collection for blacksmiths, `/saga npc inspect`, and `/saga logistics locate` diagnostics.
- Added `/saga scenario observe` market inventory/order reporting and nearby route feedback for institutional traders.
- Fixed `small_realm` superflat scenario with `/saga scenario` preview, generation, status and cancellation commands.
- Restart-safe staged deployment for roads, irrigated farms, 16 existing NBT buildings, five physical warehouses and 19 persistent NPC residents.
- Persistent social characters separated from player accounts, including paid rescue, permanent character death and new-life isolation.
- Settlement and generated-building registry, world scenarios, political relations and persistent world history.
- Kings, local lords, vassalage, title grants, designated heirs and server-authoritative succession.
- Physical warehouses and logistics orders with a dedicated courier profession, real cargo inventories and road-preferring routes.
- Convoy escort strategy for combat squads and defense response when a transporter is attacked.
- Farmer, worker and blacksmith output deposits into authorized nearby warehouses.
- Daily settlement food consumption, shortages, morale effects and physical relief-transport requests.
- Dynamic famine, plague, border raid, truce, market fair and succession-crisis events.
- `/saga audit` read-only consistency check across characters, politics, settlements, logistics and active events.
- Headless `verifySocialSystem` Gradle verification and a consolidated multiplayer acceptance checklist.

### Changed

- Replaced overlapping work identities with one active authority: institution, player, explicit contract, or none. Ordinary recruitment now suspends the original institution job and its warehouse/order permissions while preserving origin, history and private property; dismissal restores the former post.
- Contract pay and missed-pay memories now feed back into probabilistic work decisions; unloaded or full private storage pauses payment without falsely blaming the employer.
- Repeated on-time pay now changes employer trust with diminishing returns, while arrears erode trust; permanent character death terminates that character's work authority instead of transferring it to the account's next life.
- Producer NPCs without an authorized public warehouse occasionally contribute one quarter of transferable output to their household, while preserving personal reserves and private-property separation.
- Household care is assessed once per game day only while its real pantry is loaded; repeated severe shortages become persistent household status and history instead of treating unloaded containers as empty.
- Work professions and patrols now admit new tasks only in their shared schedule window; urgent hunger and rest no longer rely on unrelated goals racing for movement control.
- Recruited blacksmiths can still smelt carried materials and use player-managed supplies, while recruited traders only accept their current player's orders unless a later explicit contract grants an institution exception.
- Expanded the command wand with visible selection feedback, squads, ranks, merge/split operations and per-squad strategies.
- Group work commands now support all working professions; work-zone assignment remains specific to farmers and workers.
- NPC combat prioritizes direct threats to owners and group members and avoids unreachable target fixation.
- Logistics details and route feedback are visible only to the NPC's owning player.
- Reduced road scans and convoy target searches and added bounded warehouse, order and event retention.
- Network protocol is now `13`; all clients and the server must use the same build.

### Fixed

- Recruited farmers and workers no longer retain an active entity work zone from their suspended institution job; the original zone is restored on dismissal and player zones persist separately.
- Production output falls back to the NPC's private storage when no authorized public warehouse is available; broken private facilities are reported and can be replaced by scenario repair.
- Fixed recruitment or profession changes leaving blacksmiths and institutional traders with a valid profession but no executable work authority; `/saga npc inspect` now reports the work relationship and eligible-order count.
- Fixed blacksmiths pathing into solid furnaces/chests by selecting reachable adjacent interaction positions, with stalled-path retries and diagnostics.
- Fixed mounted traders missing road surfaces because routing used the rider's elevated coordinates; blocked mounted logistics now falls back to walking without losing cargo.
- Added no-order mounted market patrols, early producer deposits, tagged overflow recovery, and preview/confirm migration of legacy ground resources.
- Fixed mounted production NPCs remaining permanently unable to work when no fence was available for their mount.
- Restricted automatic mount spawning to mobile combat and transport professions, and added mounted patrol movement.
- Bound the fixed-world worker to the Redstone Mine work zone and renamed the role to Redstone Miner.
- Restricted patrol target acquisition and pursuit to the assigned duty area so superflat slimes cannot pull guards away indefinitely.
- Fixed `small_realm` farmers tilling the stable by assigning each farmer a dedicated 9x9 farm work zone.
- Filtered structure blocks and invalid template entities from both fixed-scenario and natural structure placement.
- Allowed intentionally institutional governments in `/saga audit` instead of reporting them as missing rulers.
- Replaced the striped mega-fields and oversized central plaza with bounded farm modules and a tighter road layout.
- Added `/saga scenario verify` checks for settlements, 9x9 farms, building ownership, template markers, warehouses and loaded NPCs.
- Added confirmed scenario repair deployment with precise legacy marker cleanup and warehouse-inventory preservation.
- Persisted scenario definition revisions so old deployments are reported as upgradeable instead of silently treated as current.
- Scenario repair now preserves recruited NPCs, missing/dead NPCs, NPC progression and evolved political relations.
- Fixed scenario wheat farmers now use crop-only mode and propagate neighboring wheat when filling empty farmland.
- Completed deployments now run one automatic structural verification pass and report failures immediately.
- Registered all fixed-world NPCs as persistent social characters using their entity UUIDs.
- Assigned fixed-world settlement titles and vassalage, with the royal throne explicitly vacant and the steward acting below sovereign rank.
- Connected registered NPC death to title succession, vacancy handling and world history without affecting ordinary NPCs.
- Added revision-aware r2-to-r3 political migration so later repairs preserve evolved titles and fealty.

- Prevented NPC UI responses from opening without a recent matching client interaction.
- Prevented other players from opening the control UI for an owned NPC.
- Added bounded UTF-8 decoding and per-player rate limits to mutable client-to-server packets.
- Prevented production NPCs from depositing into nearby warehouses they are not authorized to use.
- Made shortage logistics follow the current lord or ruler after succession instead of a deceased founder.
- Prevented self-vassal loops during inheritance and stale non-vacant realms when a ruler changes faction.
- Added safe reassignment and heartbeat handling for abandoned empty logistics orders without deleting physical cargo.
