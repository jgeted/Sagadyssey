import unittest
from inspect_phase10_copy import nbtlib, inventory, slots, item


class CopyInspectorTests(unittest.TestCase):
    def stack(self, slot=0, name="original"):
        return nbtlib.Compound({"Slot": nbtlib.Byte(slot), "id": nbtlib.String("minecraft:bread"),
                               "count": nbtlib.Int(3), "components": nbtlib.Compound({
                                   "minecraft:custom_name": nbtlib.String(name)})})

    def test_empty_omitted_equipment_equals_explicit_empty_slots(self):
        self.assertEqual(inventory(nbtlib.Compound()), inventory(nbtlib.Compound({
            "HandItems": nbtlib.List[nbtlib.Compound]([nbtlib.Compound(), nbtlib.Compound()])})))

    def test_slot_addresses_and_components_are_both_preserved(self):
        self.assertNotEqual(slots([self.stack(0)], 36), slots([self.stack(1)], 36))
        self.assertNotEqual(item(self.stack(name="original")), item(self.stack(name="changed")))

    def test_list_order_does_not_change_container_slot_ownership(self):
        self.assertEqual(slots([self.stack(0), self.stack(1)], 36),
                         slots([self.stack(1), self.stack(0)], 36))

    def test_duplicate_slot_is_rejected_not_silently_overwritten(self):
        with self.assertRaises(ValueError):
            slots([self.stack(0), self.stack(0)], 36)

    def test_cargo_is_not_confused_with_personal_bag(self):
        self.assertNotEqual(inventory(nbtlib.Compound({"Items": nbtlib.List[nbtlib.Compound]([self.stack()])})),
                            inventory(nbtlib.Compound({"TransportCargo": nbtlib.Compound({
                                "Items": nbtlib.List[nbtlib.Compound]([self.stack()])})})))

    def test_duplicate_empty_slot_is_also_rejected(self):
        empty = self.stack(0)
        empty["count"] = nbtlib.Int(0)
        with self.assertRaises(ValueError):
            slots([empty, self.stack(0)], 36)


if __name__ == "__main__":
    unittest.main()
