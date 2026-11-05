#!/usr/bin/env python3
"""Create palette-swapped preview variants of the vilg_1 structure template.

The script only changes length-prefixed block identifiers in the decompressed
NBT payload. Block-state properties remain untouched, so stairs, doors, logs,
and slabs retain their original orientation and shape.
"""

from __future__ import annotations

import argparse
import gzip
from pathlib import Path


VARIANT_PALETTES = {
    # Birch bark was visually noisy next to cobblestone, while an all-birch
    # roof made the silhouette too pale. Use stripped supports and retain the
    # original dark-oak roof for a clearer light/dark contrast.
    "birch": {
        "oak_log": "stripped_birch_log",
        "oak_door": "birch_door",
        "oak_stairs": "birch_stairs",
    },
    "spruce": {
        "oak_log": "spruce_log",
        "oak_door": "spruce_door",
        "oak_stairs": "spruce_stairs",
    },
    "acacia": {
        "oak_log": "acacia_log",
        "oak_door": "acacia_door",
        "oak_stairs": "dark_oak_stairs",
    },
}


def nbt_string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    if len(encoded) > 0xFFFF:
        raise ValueError(f"NBT string is too long: {value}")
    return len(encoded).to_bytes(2, "big") + encoded


def replace_palette_names(payload: bytes, wood: str) -> tuple[bytes, dict[str, int]]:
    counts: dict[str, int] = {}
    transformed = payload

    for source_name, target_name in VARIANT_PALETTES[wood].items():
        source = f"minecraft:{source_name}"
        target = f"minecraft:{target_name}"
        source_token = nbt_string(source)
        count = transformed.count(source_token)
        if count == 0:
            raise ValueError(f"Expected palette entry is missing: {source}")
        transformed = transformed.replace(source_token, nbt_string(target))
        counts[f"{source} -> {target}"] = count

    return transformed, counts


def write_gzip_nbt(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as raw_output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw_output, mtime=0) as output:
            output.write(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source",
        type=Path,
        default=Path("src/main/resources/data/sagadyssey/structure/vilg_1.nbt"),
        help="Source structure template",
    )
    args = parser.parse_args()

    source = args.source.resolve()
    with gzip.open(source, "rb") as input_file:
        payload = input_file.read()

    for wood in VARIANT_PALETTES:
        transformed, counts = replace_palette_names(payload, wood)
        output = source.with_name(f"vilg_1_{wood}.nbt")
        write_gzip_nbt(output, transformed)
        print(f"created {output}")
        for replacement, count in counts.items():
            print(f"  {count:>2} x {replacement}")


if __name__ == "__main__":
    main()
