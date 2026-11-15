"""Guard regressions: these tests send no HTTP requests and start no containers."""
import tempfile
from pathlib import Path
import unittest
import run


class PerformanceGuardsTest(unittest.TestCase):
    def config(self, *arguments, env=None):
        return run.configuration(run.parser().parse_args(arguments), env or {})

    def test_default_is_a_plan_with_bounded_smoke_settings(self):
        self.assertEqual('plan', run.parser().parse_args([]).action)
        config = self.config()
        self.assertEqual((50, 30, 20, 2000), (config.vus, config.duration_seconds, config.max_rps, config.capacity))

    def test_full_profile_requires_explicit_opt_in(self):
        with self.assertRaises(ValueError):
            self.config('--profile', 'full')
        config = self.config('--profile', 'full', env={'PERF_FULL': '1'})
        self.assertEqual((1000, 1800, 100000), (config.vus, config.duration_seconds, config.capacity))

    def test_smoke_cannot_silently_become_a_stress_test(self):
        for arguments in (('--vus', '51'), ('--duration-seconds', '31'), ('--max-rps', '21')):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                self.config(*arguments)

    def test_zero_is_rejected_instead_of_replaced_by_a_default(self):
        for argument in ('--vus', '--duration-seconds', '--max-rps', '--capacity', '--generator-memory-mib', '--generator-cpus'):
            with self.subTest(argument=argument), self.assertRaises(ValueError):
                self.config(argument, '0')

    def test_cohort_requires_stress_opt_in_and_an_exact_size(self):
        with self.assertRaises(ValueError):
            self.config('--scenario', 'cohort')
        config = self.config('--scenario', 'cohort', env={'PERF_STRESS': '1'})
        self.assertEqual(100, config.vus)
        config = self.config('--scenario', 'cohort', '--vus', '1000', env={'PERF_STRESS': '1'})
        self.assertEqual(1000, config.vus)
        with self.assertRaises(ValueError):
            self.config('--scenario', 'cohort', '--vus', '99', env={'PERF_STRESS': '1'})

    def test_capacity_accounting_rejects_negative_or_missing_counters(self):
        valid = {'capacity': 3, 'available': 1, 'reserved': 1, 'pendingConfirmation': 0, 'sold': 1, 'complimentary': 0}
        self.assertTrue(run.counter_invariant(valid))
        self.assertFalse(run.counter_invariant({**valid, 'sold': 2}))
        self.assertFalse(run.counter_invariant({**valid, 'available': -1, 'sold': 3}))
        self.assertFalse(run.counter_invariant({'capacity': 0}))

    def test_k6_normalized_duration_preserves_the_requested_window(self):
        self.assertEqual(1800, run.inspected_duration_seconds('30m0s'))
        self.assertEqual(30, run.inspected_duration_seconds('30s'))
        for value in ('', '30ms', 'invalid'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                run.inspected_duration_seconds(value)

    def test_generator_does_not_compete_with_quality_profile(self):
        runtime = {'docker': {'MemTotal': 8 * 1024 ** 3}, 'containers': [{'service': 'sonar', 'running': True, 'memoryLimitBytes': 1024}]}
        with self.assertRaises(ValueError):
            run.check_resources(self.config(), runtime)

    def test_memory_overcommit_fails_before_load(self):
        runtime = {'docker': {'MemTotal': 1024 ** 3}, 'containers': [{'service': 'catalog', 'running': True, 'memoryLimitBytes': 800 * 1024 ** 2}]}
        with self.assertRaises(ValueError):
            run.check_resources(self.config(), runtime)

    def test_cohort_parser_retains_real_attempts_only(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'attempts.jsonl'
            path.write_text('diagnostic\n{"kind":"other"}\n{"kind":"cohort_attempt","vu":1,"status":202}\n', encoding='utf-8')
            self.assertEqual([{'kind': 'cohort_attempt', 'vu': 1, 'status': 202}], run.parse_attempts(path))


if __name__ == '__main__':
    unittest.main()
