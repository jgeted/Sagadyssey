#!/usr/bin/env python3
"""Create biome-themed preview variants for Sagadyssey structure templates.

Only block identifiers in the compressed structure NBT palette are changed.
All block-state properties remain intact, including stair facing/shape, slab
type, door halves, fence connections, and log axes.
"""

from __future__ import annotations

import argparse
import gzip
import re
from pathlib import Path


VARIANTS = ("birch", "spruce", "acacia")
VARIANT_SUFFIX = re.compile(r"_(?:birch|spruce|acacia)$")
WOOD_IDENTIFIER = re.compile(
    rb"minecraft:(?:stripped_)?(?:dark_oak|oak|spruce|birch)_[a-z0-9_]+"
)
WOOD_PARTS = re.compile(
    r"minecraft:(?P<stripped>stripped_)?"
    r"(?P<family>dark_oak|oak|spruce|birch)_(?P<part>[a-z0-9_]+)"
)

# Spruce stairs, slabs, and trapdoors are commonly used for roofs and eaves in
# these templates. Keeping them dark prevents the birch buildings from losing
# their silhouette and becoming a single pale mass.
BIRCH_DARK_PARTS = {"stairs", "slab", "trapdoor"}
ACACIA_DARK_PARTS = {"stairs", "slab"}


def themed_identifier(identifier: str, variant: str) -> str:
    match = WOOD_PARTS.fullmatch(identifier)
    if match is None:
        return identifier

    stripped = match.group("stripped") is not None
    family = match.group("family")
    part = match.group("part")

    if part == "leaves":
        return f"minecraft:{variant}_leaves"

    # All-bark wood blocks often form continuous roof beams. Keeping spruce
    # beams dark avoids the pale horizontal stripes seen in birch previews.
    if variant == "birch" and family == "spruce" and part == "wood":
        prefix = "stripped_" if stripped else ""
        return f"minecraft:{prefix}dark_oak_wood"

    if part in {"log", "wood"}:
        # Birch bark was too visually noisy beside cobblestone in the first
        # preview, so every birch structural log uses its stripped form.
        use_stripped = variant == "birch" or stripped
        prefix = "stripped_" if use_stripped else ""
        return f"minecraft:{prefix}{variant}_{part}"

    # Preserve the darkest family as an accent layer. Logs and leaves were
    # handled above because they may belong to decorative trees.
    if family == "dark_oak":
        return identifier

    if variant == "birch" and family == "spruce" and part in BIRCH_DARK_PARTS:
        return f"minecraft:dark_oak_{part}"

    # Acacia works best as wall and framing color; a dark roof keeps large
    # buildings from becoming a single saturated orange mass.
    if variant == "acacia" and part in ACACIA_DARK_PARTS:
        return f"minecraft:dark_oak_{part}"

    return f"minecraft:{variant}_{part}"


def nbt_string(value: bytes) -> bytes:
    if len(value) > 0xFFFF:
        raise ValueError("NBT string exceeds the unsigned-short length limit")
    return len(value).to_bytes(2, "big") + value


def replace_palette_names(payload: bytes, variant: str) -> tuple[bytes, dict[str, int]]:
    replacements: list[tuple[int, int, bytes]] = []
    counts: dict[str, int] = {}

    for source_bytes in sorted(set(WOOD_IDENTIFIER.findall(payload))):
        source = source_bytes.decode("ascii")
        target = themed_identifier(source, variant)
        if target == source:
            continue

        source_token = nbt_string(source_bytes)
        target_token = nbt_string(target.encode("ascii"))
        cursor = 0
        count = 0
        while True:
            offset = payload.find(source_token, cursor)
            if offset < 0:
                break
            replacements.append((offset, offset + len(source_token), target_token))
            cursor = offset + len(source_token)
            count += 1

        if count:
            counts[f"{source} -> {target}"] = count

    replacements.sort(key=lambda item: item[0])
    for previous, current in zip(replacements, replacements[1:]):
        if previous[1] > current[0]:
            raise ValueError("Overlapping NBT string replacements detected")

    chunks: list[bytes] = []
    cursor = 0
    for start, end, replacement in replacements:
        chunks.append(payload[cursor:start])
        chunks.append(replacement)
        cursor = end
    chunks.append(payload[cursor:])
    return b"".join(chunks), counts


def write_gzip_nbt(path: Path, payload: bytes) -> None:
    with path.open("wb") as raw_output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw_output, mtime=0) as output:
            output.write(payload)


def find_sources(directory: Path, requested: list[str]) -> list[Path]:
    if requested:
        sources = [directory / f"{name}.nbt" for name in requested]
        missing = [str(path) for path in sources if not path.is_file()]
        if missing:
            raise FileNotFoundError("Missing structure template(s): " + ", ".join(missing))
        return sources

    return sorted(
        path for path in directory.glob("*.nbt")
        if not VARIANT_SUFFIX.search(path.stem)
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--directory",
        type=Path,
        default=Path("src/main/resources/data/sagadyssey/structure"),
        help="Directory containing base structure NBT files",
    )
    parser.add_argument(
        "--structure",
        action="append",
        default=[],
        help="Only generate the named base structure; may be specified repeatedly",
    )
    args = parser.parse_args()

    directory = args.directory.resolve()
    created = 0
    skipped = 0

    for source in find_sources(directory, args.structure):
        with gzip.open(source, "rb") as input_file:
            payload = input_file.read()

        structure_created = 0
        for variant in VARIANTS:
            transformed, counts = replace_palette_names(payload, variant)
            if not counts:
                continue

            output = source.with_name(f"{source.stem}_{variant}.nbt")
            write_gzip_nbt(output, transformed)
            print(f"created {output.name} ({sum(counts.values())} palette states changed)")
            structure_created += 1
            created += 1

        if structure_created == 0:
            print(f"skipped {source.name} (no supported wood-family blocks)")
            skipped += 1

    print(f"done: {created} variants created, {skipped} base templates skipped")


if __name__ == "__main__":
    main()
