import importlib.util
import sys
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('simulator', Path(__file__).with_name('console-driving-simulator.py'))
sim = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = sim
spec.loader.exec_module(sim)


class SimulatorTest(unittest.TestCase):
    def test_kmh_integrates_elapsed_seconds(self):
        previous = sim.Sample(10, 36, 'Drive', 0)
        current = sim.Sample(10, 36, 'Drive', 1)
        self.assertAlmostEqual(10.01, sim.next_km(previous, current, 10))

    def test_speed_changes_use_average(self):
        self.assertAlmostEqual(10.015, sim.next_km(
            sim.Sample(10, 36, 'Drive', 0), sim.Sample(10, 72, 'Drive', 1), 10))

    def test_park_first_read_or_sampling_gap_does_not_add_distance(self):
        current = sim.Sample(10, 36, 'Drive', 1)
        self.assertIsNone(sim.next_km(None, current, 10))
        for previous in (sim.Sample(10, 36, 'Park', 0), sim.Sample(10, 36, 'Drive', -5),
                         sim.Sample(10, 36, 'Drive', 1)):
            self.assertIsNone(sim.next_km(previous, current, 10))
        self.assertIsNone(sim.next_km(sim.Sample(10, 36, 'Drive', 0),
                                     sim.Sample(10, 36, 'Park', 1), 10))

    def test_manual_distance_change_is_preserved(self):
        self.assertIsNone(sim.next_km(sim.Sample(10, 36, 'Drive', 0),
                                     sim.Sample(123, 36, 'Drive', 1), 10))

    def test_reverse_counts_positive_distance_and_stationary_adds_nothing(self):
        self.assertAlmostEqual(10.01, sim.next_km(sim.Sample(10, 36, 'Reverse', 0),
                                                sim.Sample(10, 36, 'Reverse', 1), 10))
        self.assertIsNone(sim.next_km(sim.Sample(10, 0, 'Drive', 0), sim.Sample(10, 0, 'Drive', 1), 10))

    def test_upper_limit_does_not_wrap(self):
        self.assertIsNone(sim.next_km(sim.Sample(1_000_000, 36, 'Drive', 0),
                                     sim.Sample(1_000_000, 36, 'Drive', 1), 1_000_000))

    def test_invalid_or_disconnected_inputs_fail(self):
        def payload():
            return {'odometer': {'bridge_ready': True, 'emulator_active': True, 'traveled_distance_km': 10},
                    'controls': {'emulator_active': True, 'dds_ready': {'speed': True}, 'speed_kph': 36},
                    'gear': {'bridge_ready': True, 'actual': {'value': 'Drive'}}}
        self.assertEqual(36, sim.sample(payload(), 1).speed)
        for speed in (-1, 241, float('nan'), float('inf'), '36', True):
            p = payload(); p['controls']['speed_kph'] = speed
            with self.assertRaises(ValueError): sim.sample(p, 1)
        p = payload(); p['odometer']['bridge_ready'] = False
        with self.assertRaises(ValueError): sim.sample(p, 1)
        p = payload(); p['gear']['actual']['value'] = 'Unknown'
        with self.assertRaises(ValueError): sim.sample(p, 1)


if __name__ == '__main__':
    unittest.main()
