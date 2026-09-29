import unittest

import validate_retrieval_deployment as validation


class ValidateRetrievalDeploymentTest(unittest.TestCase):
    def test_selects_query_from_deterministic_observation_without_exposing_it(self):
        state = {
            "published": {
                "source": {
                    "observations": [
                        {
                            "kind": "text-block",
                            "payload": {"text": "This contains DistinctiveEvidence for retrieval."},
                            "producer": {"processor_id": "source.silver.format-extraction"},
                        }
                    ]
                }
            }
        }
        self.assertEqual(validation.select_private_query(state), "contains")

    def test_extracts_structured_row_text(self):
        observation = {
            "kind": "parsed-table-row",
            "payload": {"columns": {"place": "Göteborg", "year": "2026"}},
        }
        self.assertIn("Göteborg", validation.observation_text(observation))


if __name__ == "__main__":
    unittest.main()
