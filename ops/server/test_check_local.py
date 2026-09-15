import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("check_local", Path(__file__).with_name("check-local.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ReadinessTest(unittest.TestCase):
    def healthy(self):
        return dict(healthy=True, draining=False, worldCleanupHealthy=True, safeToStop=False,
                    activeInstances=0, pendingProfileOperations=0, unacknowledgedProfileWrites=0)

    def test_ready_does_not_require_drained(self):
        self.assertIsNone(checker.validate(200, self.healthy()))

    def test_unhealthy_status_or_cleanup_and_drain_fail(self):
        self.assertIsNotNone(checker.validate(503, self.healthy()))
        for field, value in [("healthy", False), ("worldCleanupHealthy", False), ("draining", True),
                             ("unacknowledgedProfileWrites", 1)]:
            body = self.healthy()
            body[field] = value
            self.assertIsNotNone(checker.validate(200, body))

    def test_other_services_and_malformed_fields_cannot_pass(self):
        for body in [[], {}, {"healthy": True}]:
            self.assertIsNotNone(checker.validate(200, body))
        for value in [True, -1, "0"]:
            body = self.healthy()
            body["activeInstances"] = value
            self.assertIsNotNone(checker.validate(200, body))


if __name__ == "__main__":
    unittest.main()
