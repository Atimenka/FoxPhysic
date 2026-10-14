"""Writes 3D element models for the assembler block, gravity gun and water bucket (NeoForge assets).

Coordinates are in 1/16 block units. UVs use the default auto mapping, so the 128 px textures from
gen_hd.py are sampled proportionally. Run from the repository root.
"""
import json
import os

ROOT = os.path.join("neoforge", "src", "main", "resources", "assets", "foxphys", "models")


def box(frm, to, faces, shade=True):
    el = {"from": frm, "to": to, "faces": {}}
    for name, tex in faces.items():
        el["faces"][name] = {"texture": tex}
    if not shade:
        el["shade"] = False
    return el


def all_faces(tex, exclude=()):
    return {f: tex for f in ("north", "south", "east", "west", "up", "down") if f not in exclude}


def assembler():
    elements = [
        # Plinth
        box([0, 0, 0], [16, 2, 16], {"down": "#bottom", "up": "#side", "north": "#side", "south": "#side", "east": "#side", "west": "#side"}),
        # Main cabinet, slightly inset from the plinth
        box([1, 2, 1], [15, 14, 15], all_faces("#side", exclude=("up", "down")) | {"up": "#side", "down": "#side"}),
        # Control panel on the south (+z) face
        box([2.5, 3, 15], [13.5, 14, 15.5], {"south": "#front", "north": "#side", "east": "#side", "west": "#side", "up": "#side", "down": "#side"}),
        # Top cap with the hatch
        box([0, 14, 0], [16, 16, 16], {"up": "#top", "down": "#bottom", "north": "#side", "south": "#side", "east": "#side", "west": "#side"}),
        # Vertical conduit on the north-east corner
        box([14.5, 2, -0.5], [16.5, 12, 1.5], {"north": "#side", "south": "#side", "east": "#side", "west": "#side", "up": "#top", "down": "#bottom"}),
    ]
    return {
        "parent": "minecraft:block/block",
        "textures": {
            "top": "foxphys:block/physics_assembler_top",
            "side": "foxphys:block/physics_assembler_side",
            "bottom": "foxphys:block/physics_assembler_bottom",
            "front": "foxphys:block/physics_assembler_front",
            "particle": "foxphys:block/physics_assembler_side",
        },
        "elements": elements,
    }


def gravity_gun():
    t = "foxphys:item/gravity_gun"
    elements = [
        box([2, 6, 5.5], [11, 10, 10.5], all_faces(t)),          # receiver
        box([10.5, 6.5, 6.5], [15, 9.5, 9.5], all_faces(t)),      # barrel
        box([14.5, 5.5, 5], [15.5, 10.5, 11], all_faces(t)),      # emitter ring (glows in the texture)
        box([3, 1.5, 6], [6.5, 6, 10], all_faces(t)),             # grip under the receiver
        box([9, 5.8, 5.8], [9.8, 10.2, 10.2], all_faces(t)),      # copper coil around the barrel
        box([6, 10, 6], [10, 11.5, 10], all_faces(t)),            # capacitor on top
    ]
    return {
        "parent": "minecraft:item/handheld",
        "textures": {"layer0": t},
        "elements": elements,
    }


def water_bucket():
    metal = "#metal"
    elements = [
        box([3.5, 0, 3.5], [12.5, 0.6, 12.5], {"down": metal, "up": metal}),          # bottom
        box([3, 0, 3], [13, 9.5, 3.6], all_faces(metal, exclude=("up", "down"))),      # north wall
        box([3, 0, 12.4], [13, 9.5, 13], all_faces(metal, exclude=("up", "down"))),    # south wall
        box([3, 0, 3], [3.6, 9.5, 13], all_faces(metal, exclude=("up", "down"))),      # west wall
        box([12.4, 0, 3], [13, 9.5, 13], all_faces(metal, exclude=("up", "down"))),    # east wall
        box([2.6, 9.5, 2.6], [13.4, 10.1, 3.4], all_faces(metal)),                     # rim north
        box([2.6, 9.5, 12.6], [13.4, 10.1, 13.4], all_faces(metal)),                   # rim south
        box([2.6, 9.5, 2.6], [3.4, 10.1, 13.4], all_faces(metal)),                     # rim west
        box([12.6, 9.5, 2.6], [13.4, 10.1, 13.4], all_faces(metal)),                   # rim east
        box([2.4, 9.5, 7.6], [3.0, 11.2, 8.4], all_faces(metal)),                      # bail post west
        box([13.0, 9.5, 7.6], [13.6, 11.2, 8.4], all_faces(metal)),                    # bail post east
        box([2.4, 11.2, 7.6], [13.6, 11.8, 8.4], all_faces(metal)),                    # bail bar
        # Water surface: a single upward-facing plane inside the bucket
        {"from": [3.6, 7.5, 3.6], "to": [12.4, 7.5, 12.4], "shade": False,
         "faces": {"up": {"texture": "#water"}}},
    ]
    return {
        "parent": "minecraft:item/generated",
        "textures": {
            "layer0": "foxphys:item/physics_water_bucket",
            "metal": "foxphys:item/physics_water_bucket",
            "water": "foxphys:item/physics_water_surface",
        },
        "elements": elements,
    }


def write(rel, data):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print("wrote", path)


if __name__ == "__main__":
    write(os.path.join("block", "physics_assembler.json"), assembler())
    write(os.path.join("item", "gravity_gun.json"), gravity_gun())
    write(os.path.join("item", "physics_water_bucket.json"), water_bucket())
