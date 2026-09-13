"""Compare the prompt's example/schema with actual engine output, without user code."""
from dataclasses import replace
import json
from pathlib import Path
import unittest

from parseltongue_game.engine import DIRECTIONS, Game, Rules

PROMPTS = Path(__file__).resolve().parents[2] / "arena-service/src/main/resources/prompts"


class PromptContractTests(unittest.TestCase):
    def test_observation_example_exactly_matches_engine_and_documented_move(self):
        game = Game(Rules(food_count=1, max_ticks=200), seed=42, food={(5, 2)})
        example = json.loads((PROMPTS / "strategy-observation-example.json").read_text())
        self.assertEqual(example, game.observation("s1"))
        frame = game.step({snake.id: snake.direction for snake in game.snakes})
        own = frame["state"]["snakes"][0]
        self.assertEqual(own["body"][0], [5, 2])
        self.assertEqual(len(own["body"]), 4)
        self.assertEqual(own["hunger"], 100)

    def test_schema_fields_and_enums_track_real_observations(self):
        schema = json.loads((PROMPTS / "strategy-observation.schema.json").read_text())
        game = Game()
        observation = game.observation("s1")
        self.assertEqual(set(schema["required"]), set(observation))
        self.assertEqual(set(schema["properties"]), set(observation))
        for key in ("board", "hunger_rules"):
            self.assertEqual(set(schema["properties"][key]["required"]), set(observation[key]))
        snake = schema["$defs"]["snake"]
        self.assertEqual(set(snake["required"]), set(observation["self"]))
        self.assertEqual(set(snake["properties"]), set(observation["self"]))
        self.assertEqual(set(snake["properties"]["direction"]["enum"]), set(DIRECTIONS))
        for key in ("protocol_version", "ruleset_version"):
            self.assertEqual(schema["properties"][key]["const"], observation[key])

    def test_hunger_edge_examples_use_decay_then_restore_then_cap(self):
        for hunger, food, expected in ((1, {(5, 2)}, 40), (99, {(5, 2)}, 100), (1, set(), 0)):
            game = Game(Rules(food_count=len(food)), food=food)
            game.snakes = (replace(game.snakes[0], hunger=hunger),) + game.snakes[1:]
            frame = game.step({snake.id: snake.direction for snake in game.snakes})
            own = frame["state"]["snakes"][0]
            self.assertEqual(own["hunger"], expected)
            self.assertEqual(own["alive"], expected > 0)
