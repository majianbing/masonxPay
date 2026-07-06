from __future__ import annotations

import unittest
from unittest.mock import patch

from fastapi import HTTPException

from app.main import require_rag_auth


class RagAuthTest(unittest.TestCase):
    def test_auth_not_required_allows_missing_header(self) -> None:
        with patch("app.main.RAG_REQUIRE_AUTH", False):
            require_rag_auth(None)

    def test_auth_required_rejects_missing_header(self) -> None:
        with patch("app.main.RAG_REQUIRE_AUTH", True), patch("app.main.RAG_AUTH_TOKEN", "secret"):
            with self.assertRaises(HTTPException) as raised:
                require_rag_auth(None)

            self.assertEqual(401, raised.exception.status_code)

    def test_auth_required_rejects_wrong_token(self) -> None:
        with patch("app.main.RAG_REQUIRE_AUTH", True), patch("app.main.RAG_AUTH_TOKEN", "secret"):
            with self.assertRaises(HTTPException) as raised:
                require_rag_auth("Bearer wrong")

            self.assertEqual(401, raised.exception.status_code)

    def test_auth_required_accepts_matching_bearer_token(self) -> None:
        with patch("app.main.RAG_REQUIRE_AUTH", True), patch("app.main.RAG_AUTH_TOKEN", "secret"):
            require_rag_auth("Bearer secret")

    def test_auth_required_without_config_returns_unavailable(self) -> None:
        with patch("app.main.RAG_REQUIRE_AUTH", True), patch("app.main.RAG_AUTH_TOKEN", ""):
            with self.assertRaises(HTTPException) as raised:
                require_rag_auth("Bearer secret")

            self.assertEqual(503, raised.exception.status_code)


if __name__ == "__main__":
    unittest.main()
