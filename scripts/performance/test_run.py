"""Guard regressions: these tests send no HTTP requests and start no containers."""
import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
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
        self.assertEqual(1, config.cohort_tickets)
        config = self.config('--scenario', 'cohort', '--vus', '1000', env={'PERF_STRESS': '1'})
        self.assertEqual(1000, config.vus)
        with self.assertRaises(ValueError):
            self.config('--scenario', 'cohort', '--vus', '99', env={'PERF_STRESS': '1'})

    def test_cohort_ticket_count_is_explicit_and_bounded(self):
        config = self.config('--scenario', 'cohort', '--vus', '1000', '--cohort-tickets', '100', env={'PERF_STRESS': '1'})
        self.assertEqual(100, config.cohort_tickets)
        for tickets in ('0', '-1', '1001'):
            with self.subTest(tickets=tickets), self.assertRaises(ValueError):
                self.config('--scenario', 'cohort', '--cohort-tickets', tickets, env={'PERF_STRESS': '1'})
        with self.assertRaises(ValueError):
            self.config('--cohort-tickets', '2')

    def test_cohort_seeds_exact_capacity_without_unrelated_events(self):
        config = self.config('--scenario', 'cohort', '--vus', '1000', '--cohort-tickets', '100', env={'PERF_STRESS': '1'})
        with patch('run.http', return_value=(200, {'status': 'UP'})), \
                patch('run.create_event', return_value={'eventId': 'event', 'capacity': 100}) as create:
            dataset = run.seed_dataset(config, 'run')
        create.assert_called_once_with('run', 'hot', 100)
        self.assertEqual([], dataset['events'])
        self.assertEqual(100, dataset['hotEvent']['capacity'])

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


class CohortAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def prepare(self, vus=1000, tickets=100):
        self.config = run.configuration(run.parser().parse_args(
            ['--scenario', 'cohort', '--vus', str(vus), '--cohort-tickets', str(tickets)]), {'PERF_STRESS': '1'})
        self.dataset = {'events': [], 'hotEvent': {'eventId': 'event', 'capacity': tickets}}
        self.snapshot = {'capacity': tickets, 'available': 0, 'reserved': tickets, 'pendingConfirmation': 0, 'sold': 0, 'complimentary': 0}
        self.attempts = []
        self.orders = {}
        self.tickets = [{'eventId': 'event', 'ticketId': str(seat), 'status': 'RESERVED'} for seat in range(1, tickets + 1)]
        self.calls = []
        for vu in range(1, vus + 1):
            seat = str((vu - 1) % tickets + 1)
            order_id = 'order-' + str(vu)
            self.attempts.append({'kind': 'cohort_attempt', 'vu': vu, 'ticketId': seat, 'status': 202, 'orderId': order_id, 'sentAt': vu})
            self.orders[order_id] = {'orderId': order_id, 'eventId': 'event', 'requestedTicketIds': [seat],
                                     'activeTicketIds': [seat] if vu <= tickets else [],
                                     'status': 'RESERVED' if vu <= tickets else 'AVAILABLE',
                                     'workflowStatus': 'AWAITING_CONFIRMATION' if vu <= tickets else 'REJECTED'}

    def http(self, service, path, **kwargs):
        self.calls.append((service, path, kwargs))
        if service == 'orders':
            return 200, self.orders[path.rsplit('/', 1)[1]]
        if path.endswith('/availability'):
            return 200, self.snapshot
        return 200, {'items': self.tickets, 'nextCursor': None}

    def audit(self, callback=None):
        (self.directory / 'cohort-attempts.jsonl').write_text(
            '\n'.join(json.dumps(item) for item in self.attempts), encoding='utf-8')
        with patch('run.http', side_effect=callback or self.http):
            return run.audit(self.config, self.dataset, self.directory)['cohort']

    def test_default_one_seat_and_thousand_buyers_hundred_seats(self):
        for vus, tickets in ((100, 1), (1000, 100)):
            with self.subTest(vus=vus, tickets=tickets):
                self.prepare(vus, tickets)
                result = self.audit()
                self.assertEqual(tickets, result['reservedWinners'])
                self.assertEqual(vus - tickets, result['rejectedBuyers'])
                self.assertEqual(0, result['authoritativeAvailability']['sold'])
                self.assertEqual(tickets, len(result['authoritativeTickets']))
                self.assertTrue(all(len(owners) == 1 for owners in result['winnersByTicket'].values()))

    def test_http_conflicts_count_as_rejections_without_polling_them(self):
        self.prepare()
        for item in self.attempts[100:]:
            item.update(status=409, orderId=None)
        result = self.audit()
        self.assertEqual((100, 900, 0), (result['http202'], result['http409'], result['asynchronousRejections']))
        self.assertEqual(100, sum(service == 'orders' for service, _, _ in self.calls))

    def test_reserving_orders_are_polled_until_asynchronous_outcomes(self):
        self.prepare(vus=100, tickets=1)
        observed = set()
        def delayed(service, path, **kwargs):
            status, value = self.http(service, path, **kwargs)
            if service == 'orders' and value['orderId'] not in observed:
                observed.add(value['orderId'])
                return status, value | {'status': 'AVAILABLE', 'workflowStatus': 'RESERVING', 'activeTicketIds': []}
            return status, value
        with patch('run.time.sleep'):
            result = self.audit(delayed)
        self.assertEqual((1, 99, 0), (result['reservedWinners'], result['asynchronousRejections'], result['unresolved']))
        self.assertEqual(200, sum(service == 'orders' for service, _, _ in self.calls))

    def test_unresolved_reservations_fail_after_the_bounded_polling_window(self):
        self.prepare(vus=100, tickets=1)
        for order in self.orders.values():
            order.update(status='AVAILABLE', workflowStatus='RESERVING', activeTicketIds=[])
        clock = {'now': 0}
        with patch('run.time.monotonic', side_effect=lambda: clock['now']), \
                patch('run.time.sleep', side_effect=lambda _: clock.update(now=121)):
            with self.assertRaises(RuntimeError):
                self.audit()
        report = json.loads((self.directory/'cohort-audit.json').read_text())
        self.assertEqual((100, 0, 0), (report['unresolved'], report['reservedWinners'], report['asynchronousRejections']))

    def test_duplicate_winner_for_one_seat_fails_even_when_counters_balance(self):
        self.prepare()
        self.orders['order-100'].update(status='AVAILABLE', workflowStatus='REJECTED', activeTicketIds=[])
        self.orders['order-101'].update(status='RESERVED', workflowStatus='AWAITING_CONFIRMATION', activeTicketIds=['1'])
        with self.assertRaisesRegex(RuntimeError, 'unique reservation'):
            self.audit()

    def test_wrong_assignment_and_out_of_range_vu_are_rejected(self):
        for field, value in (('ticketId', '100'), ('vu', 1001)):
            with self.subTest(field=field):
                self.prepare()
                self.attempts[0][field] = value
                with self.assertRaises(RuntimeError):
                    self.audit()

    def test_order_identity_and_active_ticket_must_match_the_actual_attempt(self):
        for field, value in (('orderId', 'other'), ('eventId', 'other'), ('requestedTicketIds', ['2']), ('activeTicketIds', ['2'])):
            with self.subTest(field=field):
                self.prepare()
                self.orders['order-1'][field] = value
                with self.assertRaises(RuntimeError):
                    self.audit()

    def test_sold_counts_or_reserved_ticket_drift_fail_despite_balanced_totals(self):
        self.prepare()
        self.snapshot.update(reserved=99, sold=1)
        with self.assertRaisesRegex(RuntimeError, 'zero sales'):
            self.audit()
        self.prepare()
        self.tickets[0]['status'] = 'SOLD'
        with self.assertRaisesRegex(RuntimeError, 'unique reservations'):
            self.audit()

    def test_duplicate_missing_or_repeating_ticket_pages_are_rejected(self):
        for tickets in ([{'eventId': 'event', 'ticketId': '1', 'status': 'RESERVED'}] * 100, []):
            self.prepare()
            self.tickets = tickets
            with self.assertRaises(RuntimeError):
                self.audit()
        self.prepare()
        def repeated(service, path, **kwargs):
            if '/tickets?' in path:
                return 200, {'items': self.tickets[:1] if 'cursor=' not in path else self.tickets[1:2], 'nextCursor': 'same'}
            return self.http(service, path, **kwargs)
        with self.assertRaisesRegex(RuntimeError, 'pagination did not advance'):
            self.audit(repeated)

    def test_authoritative_ticket_audit_follows_encoded_cursor(self):
        self.prepare()
        pages = []
        def paginated(service, path, **kwargs):
            if '/tickets?' in path:
                pages.append(path)
                if 'cursor=' not in path:
                    return 200, {'items': self.tickets[:50], 'nextCursor': 'opaque+/='}
                return 200, {'items': self.tickets[50:], 'nextCursor': None}
            return self.http(service, path, **kwargs)
        result = self.audit(paginated)
        self.assertEqual(100, len(result['authoritativeTickets']))
        self.assertEqual('/api/v1/events/event/tickets?limit=100&cursor=opaque%2B%2F%3D', pages[1])


if __name__ == '__main__':
    unittest.main()
