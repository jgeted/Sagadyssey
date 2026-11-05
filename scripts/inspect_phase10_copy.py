"""Inspect real entity-region NBT; produce audit input or compare physical assets.

Requires nbtlib (the existing tmp/nbt-deps installation is also supported).
Never writes the source world or alters an existing entity/region/player file.
"""
import argparse
import gzip
import io
import json
import pathlib
import struct
import sys
import uuid
import zlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tmp/nbt-deps"))
import nbtlib


def identity(value):
    return str(uuid.UUID(bytes=struct.pack(">4i", *map(int, value))))


def chunks(folder):
    for path in sorted(folder.glob("*.mca")):
        raw = path.read_bytes()
        for index in range(1024):
            start = int.from_bytes(raw[index * 4:index * 4 + 3], "big") * 4096
            if not start:
                continue
            length = int.from_bytes(raw[start:start + 4], "big")
            compression = raw[start + 4]
            if compression & 128:
                raise ValueError(f"External region stream requires explicit support: {path}")
            payload = raw[start + 5:start + 4 + length]
            if compression == 2:
                payload = zlib.decompress(payload)
            elif compression == 1:
                payload = gzip.decompress(payload)
            elif compression != 3:
                raise ValueError(f"Unknown region compression {compression}: {path}")
            yield nbtlib.File.parse(io.BytesIO(payload))


def entities(world):
    result = {}
    for dimension, subdir in [("minecraft:overworld", ""),
                              ("minecraft:the_nether", "DIM-1"),
                              ("minecraft:the_end", "DIM1")]:
        def visit(entity):
            if str(entity.get("id", "")) == "sagadyssey:npc_base":
                body = identity(entity["UUID"])
                if body in result:
                    raise ValueError(f"Duplicate original body {body}")
                result[body] = (dimension, entity)
            for passenger in entity.get("Passengers", []):
                visit(passenger)
        for chunk in chunks(world / subdir / "entities"):
            for entity in chunk.get("Entities", []):
                visit(entity)
    return result


def canonical(tag):
    if isinstance(tag, nbtlib.Compound):
        return [tag.tag_id, [[key, canonical(tag[key])] for key in sorted(tag)]]
    if isinstance(tag, nbtlib.List):
        return [tag.tag_id, [canonical(value) for value in tag]]
    if isinstance(tag, (nbtlib.ByteArray, nbtlib.IntArray, nbtlib.LongArray)):
        return [tag.tag_id, list(map(int, tag))]
    return [tag.tag_id, str(tag)]


def item(tag):
    if not tag or int(tag.get("count", 0)) == 0:
        return None
    # Container slot is an address, not part of the stack components.
    return canonical(nbtlib.Compound({k: v for k, v in tag.items() if k != "Slot"}))


def slots(tags, size):
    result = [None] * size
    seen = set()
    for tag in tags:
        slot = int(tag["Slot"]) & 255
        if not 0 <= slot < size or slot in seen:
            raise ValueError("Invalid or duplicated inventory slot")
        seen.add(slot)
        result[slot] = item(tag)
    return result


def inventory(entity):
    hands = list(entity.get("HandItems", []))
    armor = list(entity.get("ArmorItems", []))
    return [slots(entity.get("Items", []), 36),
            slots(entity.get("TransportCargo", {}).get("Items", []), 27),
            [item(hands[i]) if i < len(hands) else None for i in range(2)],
            [item(armor[i]) if i < len(armor) else None for i in range(4)],
            item(entity.get("body_armor_item", {})),
            [item(tag) for tag in entity.get("InventoryMigrationOverflow", [])]]


def containers(world):
    result = {}
    for subdir in ["", "DIM-1", "DIM1"]:
        for chunk in chunks(world / subdir / "region"):
            for block in chunk.get("block_entities", []):
                if "Items" in block:
                    key = (subdir, int(block["x"]), int(block["y"]), int(block["z"]))
                    result[key] = sorted([(int(t["Slot"]) & 255, item(t)) for t in block["Items"]])
    return result


def prepare(world, source):
    if world.resolve().name != "world" or world.resolve().parent.parent.name != "phase10-migration":
        raise ValueError("Audit manifests may be generated only inside an isolated phase10-migration copy")
    marker = world / "phase10-copy-manifest.nbt"
    if marker.exists():
        raise ValueError("Do not overwrite an existing copy audit manifest")
    originals = entities(world)
    brains = nbtlib.load(world / "data/sagadyssey_npc_brain_v3.dat")["data"]["Brains"]
    modes = {identity(row["NpcId"]): str(row["Mode"]) for row in brains}
    active = {identity(row["NpcId"]) for row in brains if "Active" in row}
    # Select existing idle legacy originals, one per production profession.
    targets = []
    for role in ["BLACKSMITH", "WORKER", "FARMER"]:
        candidates = sorted(body for body, (_, entity) in originals.items()
                            if str(entity.get("Profession")) == role
                            and modes.get(body, "LEGACY") == "LEGACY" and body not in active)
        if not candidates:
            raise ValueError(f"No idle legacy original {role}; do not manufacture a fixture")
        targets.append(candidates[0])
    rows = nbtlib.List[nbtlib.Compound]()
    for body, (dimension, entity) in sorted(originals.items()):
        rows.append(nbtlib.Compound({"Body": entity["UUID"],
                                    "Person": entity["CharacterId"],
                                    "Dimension": nbtlib.String(dimension),
                                    "Selected": nbtlib.Byte(body in targets),
                                    "OriginalMode": nbtlib.String(modes.get(body, "LEGACY")),
                                    "OriginalNoAI": nbtlib.Byte(entity.get("NoAI", 0)),
                                    "SourceEntity": entity}))
    nbtlib.File({"Source": nbtlib.String(str(source.resolve())), "Originals": rows}).save(marker, gzipped=True)
    print(json.dumps({"originalBodies": len(originals), "selectedOriginals": targets,
                      "source": str(source.resolve()), "copy": str(world.resolve())}, ensure_ascii=False))


def compare(source, copy):
    before, after = entities(source), entities(copy)
    if set(before) != set(after):
        raise AssertionError(f"Original body set changed: {set(before) ^ set(after)}")
    for body in before:
        dim, entity = before[body]
        other_dim, other = after[body]
        assert dim == other_dim and identity(entity["CharacterId"]) == identity(other["CharacterId"]), body
        assert inventory(entity) == inventory(other), f"Physical NPC inventory/components changed: {body}"
        for key in ["Faction", "FactionId", "Owner", "OwnerCharacterId"]:
            assert (canonical(entity[key]) if key in entity else None) == (canonical(other[key]) if key in other else None), (body, key)
    original_containers, copied_containers = containers(source), containers(copy)
    assert original_containers == copied_containers, "Original block-container slots/components changed"
    player_count = 0
    for player in (source / "playerdata").glob("*.dat"):
        assert player.read_bytes() == (copy / "playerdata" / player.name).read_bytes(), f"Player/Research bytes changed: {player.name}"
        player_count += 1
    source_player = nbtlib.load(source / "level.dat")["Data"].get("Player", nbtlib.Compound())
    copy_player = nbtlib.load(copy / "level.dat")["Data"].get("Player", nbtlib.Compound())
    assert canonical(source_player) == canonical(copy_player), "Embedded single-player data/attachments changed"
    # Existing domain row counts provide a coarse complete-world comparison, not an invented
    # guarantee that all simulation timestamps remain byte-for-byte identical.
    domain_rows = {}
    for file in (source / "data").glob("sagadyssey*.dat"):
        old = nbtlib.load(file)["data"]
        new = nbtlib.load(copy / "data" / file.name)["data"]
        for key, value in old.items():
            if isinstance(value, nbtlib.List):
                assert key in new and len(value) == len(new[key]), f"Domain row count changed: {file.name}/{key}"
                domain_rows[f"{file.name}/{key}"] = len(value)
    print(json.dumps({"result": "PASS", "sameOriginalBodies": len(before),
                      "sameOriginalPeople": len({identity(e["CharacterId"]) for _, e in before.values()}),
                      "unchangedPhysicalContainers": len(original_containers),
                      "unchangedPlayerFilesIncludingStoredAttachments": player_count,
                      "unchangedDomainListCounts": domain_rows}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["prepare", "compare"])
    parser.add_argument("source", type=pathlib.Path)
    parser.add_argument("copy", type=pathlib.Path)
    args = parser.parse_args()
    if args.source.resolve() == args.copy.resolve():
        raise ValueError("Source and copy must be distinct")
    if args.action == "prepare":
        prepare(args.copy, args.source)
    else:
        compare(args.source, args.copy)
