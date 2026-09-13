from dataclasses import replace
import copy
import unittest
from unittest.mock import patch

from parseltongue_game.engine import Game, Rules, Snake
from parseltongue_game.runner import run_match, verify_replay
from parseltongue_game.stubs import STUBS


class RulesTests(unittest.TestCase):
    def game(self, overrides=None, food=(), **options):
        snakes = [Snake("s1", ((2, 2), (1, 2)), "RIGHT", 100),
                  Snake("s2", ((9, 2), (10, 2)), "LEFT", 100),
                  Snake("s3", ((9, 9), (10, 9)), "LEFT", 100),
                  Snake("s4", ((2, 9), (1, 9)), "RIGHT", 100)]
        for index, changes in (overrides or {}).items():
            snakes[index] = replace(snakes[index], **changes)
        return Game(Rules(width=12, height=12, food_count=0, **options),
                    snakes=tuple(snakes), food=set(food))

    def advance(self, game, actions=None, failures=None):
        directions = {s.id: s.direction for s in game.snakes if s.alive}
        directions.update(actions or {})
        return game.step(directions, failures)

    def test_wall_death(self):
        game = self.game({0: {"body": ((11, 3), (10, 3))}})
        self.advance(game)
        self.assertEqual(game.snakes[0].death_reason, "WALL")
        self.assertTrue(game.snakes[1].alive)

    def test_self_body_collision(self):
        game = self.game({0: {"body": ((3, 3), (3, 4), (2, 4), (2, 3), (2, 2)), "direction": "UP"}})
        self.advance(game, {"s1": "LEFT"})
        self.assertEqual(game.snakes[0].death_reason, "BODY")

    def test_own_vacated_tail_is_legal(self):
        game = self.game({0: {"body": ((3, 3), (3, 4), (2, 4), (2, 3)), "direction": "UP"}})
        self.advance(game, {"s1": "LEFT"})
        self.assertTrue(game.snakes[0].alive)
        self.assertEqual(game.snakes[0].body[0], (2, 3))

    def test_other_vacated_tail_is_legal(self):
        game = self.game({1: {"body": ((4, 2), (3, 2)), "direction": "RIGHT"}})
        self.advance(game)
        self.assertTrue(game.snakes[0].alive)

    def test_growth_retains_tail(self):
        game = self.game({1: {"body": ((4, 2), (3, 2)), "direction": "RIGHT"}}, food={(5, 2)})
        self.advance(game)
        self.assertEqual(game.snakes[0].death_reason, "BODY")
        self.assertEqual(len(game.snakes[1].body), 3)

    def test_head_on_is_simultaneous_and_food_not_consumed(self):
        game = self.game({0: {"body": ((4, 3), (3, 3))},
                          1: {"body": ((6, 3), (7, 3))}}, food={(5, 3)})
        self.advance(game)
        self.assertEqual([s.death_reason for s in game.snakes[:2]], ["HEAD_ON", "HEAD_ON"])
        self.assertIn((5, 3), game.food)

    def test_head_swap(self):
        game = self.game({0: {"body": ((4, 3), (3, 3))},
                          1: {"body": ((5, 3), (6, 3))}})
        self.advance(game)
        self.assertEqual([s.death_reason for s in game.snakes[:2]], ["HEAD_SWAP", "HEAD_SWAP"])

    def test_faulted_body_still_blocks_this_turn(self):
        game = self.game({1: {"body": ((4, 2), (3, 2)), "direction": "RIGHT"}})
        self.advance(game, failures={"s2": "AI_ERROR"})
        self.assertEqual(game.snakes[0].death_reason, "BODY")
        self.assertEqual(game.snakes[1].death_reason, "AI_ERROR")

    def test_simultaneous_wall_death_still_releases_tail(self):
        game = self.game({0: {"body": ((9, 4), (9, 5)), "direction": "UP"},
                          1: {"body": ((11, 3), (10, 3), (9, 3)), "direction": "RIGHT"}})
        self.advance(game)
        self.assertTrue(game.snakes[0].alive)
        self.assertEqual(game.snakes[1].death_reason, "WALL")

    def test_starvation(self):
        game = self.game({0: {"hunger": 1}})
        self.advance(game)
        self.assertEqual(game.snakes[0].death_reason, "STARVATION")
        self.assertEqual(game.snakes[0].hunger, 0)

    def test_food_saves_hunger_one_and_grows(self):
        game = self.game({0: {"hunger": 1}}, food={(3, 2)})
        self.advance(game)
        self.assertTrue(game.snakes[0].alive)
        self.assertEqual(game.snakes[0].hunger, 40)
        self.assertEqual(len(game.snakes[0].body), 3)
        self.assertNotIn((3, 2), game.food)

    def test_recovery_caps_at_maximum(self):
        game = self.game(food={(3, 2)})
        self.advance(game)
        self.assertEqual(game.snakes[0].hunger, 100)

    def test_reverse_is_fault(self):
        game = self.game()
        self.advance(game, {"s1": "LEFT"})
        self.assertEqual(game.snakes[0].death_reason, "REVERSE")

    def test_last_actual_survivor_wins(self):
        game = self.game({i: {"hunger": 2 if i == 0 else 1} for i in range(4)})
        self.advance(game)
        self.assertEqual(game.result["winner_id"], "s1")
        self.assertEqual(game.result["outcomes"], {"s1": "WIN", "s2": "LOSS", "s3": "LOSS", "s4": "LOSS"})

    def test_all_dead_draw(self):
        game = self.game({i: {"hunger": 1} for i in range(4)})
        self.advance(game)
        self.assertIsNone(game.result["winner_id"])
        self.assertEqual(set(game.result["outcomes"].values()), {"DRAW"})

    def test_previously_dead_does_not_draw(self):
        game = self.game({i: {"hunger": 1 if i == 0 else 2} for i in range(4)})
        self.advance(game)
        self.advance(game)
        self.assertEqual(game.result["outcomes"], {"s1": "LOSS", "s2": "DRAW", "s3": "DRAW", "s4": "DRAW"})

    def test_all_ai_errors_are_losses(self):
        game = self.game()
        game.step({}, {s.id: "AI_ERROR" for s in game.snakes})
        self.assertEqual(set(game.result["outcomes"].values()), {"LOSS"})

    def test_turn_limit_does_not_award_longest_snake(self):
        game = self.game(max_ticks=1, food={(3, 2)})
        self.advance(game)
        self.assertEqual(game.result["reason"], "NO_CONTEST")
        self.assertEqual(set(game.result["outcomes"].values()), {"NO_CONTEST"})

    def test_snapshot_mutation_cannot_change_state_or_another_observation(self):
        game = self.game()
        before = game.snapshot()
        observation = game.observation("s1")
        observation["snakes"][1]["alive"] = False
        observation["self"]["hunger"] = 999
        observation["food"].append([0, 0])
        self.assertEqual(game.snapshot(), before)
        self.assertTrue(game.observation("s2")["self"]["alive"])

    def test_forged_target_is_an_invalid_action(self):
        game = self.game()
        self.advance(game, {"s1": {"snake_id": "s2", "direction": "UP"}})
        self.assertEqual(game.snakes[0].death_reason, "INVALID_ACTION")
        self.assertEqual(game.snakes[1].direction, "LEFT")

    def test_unknown_id_rejected_without_advancing(self):
        game = self.game()
        with self.assertRaises(ValueError):
            game.step({"admin": "UP"})
        self.assertEqual(game.tick, 0)

    def test_no_action_is_a_failure(self):
        game = self.game()
        game.step({})
        self.assertTrue(all(s.death_reason == "MISSING_ACTION" for s in game.snakes))

    def test_cannot_step_after_result(self):
        game = self.game(max_ticks=1)
        self.advance(game)
        with self.assertRaises(ValueError):
            self.advance(game)

    def test_bad_configuration_and_overlapping_spawn_rejected(self):
        for option in ({"hunger_decay": 0}, {"width": 2}, {"max_ticks": 0}, {"food_count": -1}, {"max_ticks": True}):
            with self.assertRaises(ValueError):
                Rules(**option)
        with self.assertRaises(ValueError):
            self.game({1: {"body": ((2, 2), (3, 2))}})


class RunnerTests(unittest.TestCase):
    def test_builtins_match_is_deterministic_and_replayable(self):
        first = run_match(seed=42, rules=Rules(max_ticks=100))
        self.assertEqual(first, run_match(seed=42, rules=Rules(max_ticks=100)))
        self.assertTrue(verify_replay(first))
        changed = copy.deepcopy(first)
        changed["frames"][1]["state"]["snakes"][0]["hunger"] += 1
        with self.assertRaises(ValueError):
            verify_replay(changed)

    def test_unknown_code_or_module_cannot_be_loaded(self):
        for names in (("os",) * 4, ("../evil.py",) * 4, ("greedy",) * 3):
            with self.assertRaises(ValueError):
                run_match(names)

    def test_stub_exception_only_kills_assigned_snake(self):
        with patch.dict(STUBS, {"straight": lambda obs: 1 / 0}):
            replay = run_match(rules=Rules(max_ticks=1))
        self.assertEqual(replay["frames"][1]["state"]["snakes"][0]["death_reason"], "AI_ERROR")
        self.assertTrue(replay["frames"][1]["state"]["snakes"][1]["alive"])
        self.assertTrue(verify_replay(replay))

    def test_seeded_matches_preserve_live_board_invariants(self):
        for seed in (1, 7, 42):
            replay = run_match(seed=seed, rules=Rules(max_ticks=80))
            for frame in replay["frames"]:
                occupied = set()
                for snake in frame["state"]["snakes"]:
                    if snake["alive"]:
                        self.assertTrue(0 < snake["hunger"] <= 100)
                        for point in snake["body"]:
                            self.assertTrue(0 <= point[0] < 20 and 0 <= point[1] < 20)
                            self.assertNotIn(tuple(point), occupied)
                            occupied.add(tuple(point))
                self.assertFalse(occupied & {tuple(p) for p in frame["state"]["food"]})


if __name__ == "__main__":
    unittest.main()
