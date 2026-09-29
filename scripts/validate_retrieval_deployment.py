#!/usr/bin/env python3
"""Validate a live Source retrieval deployment without printing personal data."""

import argparse
import hashlib
import json
import os
import re
import time
import urllib.parse
import urllib.request
from pathlib import Path


STOP_WORDS = {
    "about", "after", "again", "also", "because", "before", "could", "detta",
    "eller", "finns", "från", "have", "inte", "med", "more", "och", "som",
    "that", "the", "their", "there", "this", "till", "var", "with", "would",
}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def observation_text(observation):
    payload = observation.get("payload")
    if not isinstance(payload, dict):
        return ""
    kind = observation.get("kind")
    if kind in {"text-block", "markdown-heading"}:
        return payload.get("text", "")
    if kind == "parsed-json-value":
        return str(payload.get("value", ""))
    if kind in {"parsed-table-header", "parsed-table-row"}:
        columns = payload.get("columns")
        if isinstance(columns, dict):
            return " ".join(str(value) for value in columns.values())
        values = payload.get("values", [])
        return " ".join(str(value) for value in values)
    return ""


def select_private_query(silver_state):
    for source_id in sorted(silver_state.get("published", {})):
        dataset = silver_state["published"][source_id]
        for observation in dataset.get("observations", []):
            producer = observation.get("producer", {})
            if producer.get("processor_id") != "source.silver.format-extraction":
                continue
            for word in re.findall(r"[^\W\d_]{5,}", observation_text(observation).lower(), re.UNICODE):
                if word not in STOP_WORDS:
                    return word
    return "source"


def get_text(url, timeout=10):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read().decode("utf-8")


def get_json(url, timeout=300):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8081")
    parser.add_argument(
        "--data-root",
        default=os.environ.get("SOURCE_DATA_ROOT", str(Path.home() / ".local/share/source-v1")),
    )
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--metrics-out", type=Path)
    args = parser.parse_args()

    base_url = args.url.rstrip("/")
    data_root = Path(args.data_root)
    pairing_root = data_root / "pairing"
    if get_text(base_url + "/paired").strip() != "yes":
        raise RuntimeError("Source is not paired with Self")
    jobs = get_json(base_url + "/jobs")
    if "queued" not in jobs or "completed" not in jobs:
        raise RuntimeError("Source job API is unavailable")

    bronze_items = [
        read_json(path)
        for path in sorted((pairing_root / "bronze/items").glob("*.json"))
    ]
    live_bronze = sum(not item.get("deleted", False) for item in bronze_items)
    silver_state = read_json(pairing_root / "silver/state.json")
    published = silver_state.get("published", {})
    entities = sum(len(dataset.get("entities", [])) for dataset in published.values())
    claims = sum(len(dataset.get("claims", [])) for dataset in published.values())
    query = select_private_query(silver_state)
    query_hash = hashlib.sha256(query.encode("utf-8")).hexdigest()[:12]

    deadline = time.monotonic() + args.timeout_seconds
    response = None
    latency_ms = None
    while time.monotonic() < deadline:
        parameters = urllib.parse.urlencode({"q": query, "limit": 10})
        started = time.perf_counter()
        response = get_json(f"{base_url}/retrieval?{parameters}")
        latency_ms = (time.perf_counter() - started) * 1000
        representation = response["representation"]
        if representation["state"] == "ready":
            break
        if representation["state"] == "failed":
            raise RuntimeError(f"embedding representation failed: {representation.get('error', '')}")
        time.sleep(5)
    else:
        raise RuntimeError("retrieval representation did not become ready before timeout")

    representation = response["representation"]
    if live_bronze and published and representation["chunks"] == 0:
        raise RuntimeError("published text-like Silver produced no retrieval chunks")
    if representation["chunks"] != representation["embeddings"]:
        raise RuntimeError("retrieval chunks and embeddings did not converge")
    if representation["embedding_processor"].get("model_id") != "source-embedding":
        raise RuntimeError("unexpected embedding model identity")
    if representation["chunks"] and (
        response["lexical_count"] == 0
        or response["semantic_count"] == 0
        or not any(item.get("lexical_rank") for item in response["results"])
        or not any(item.get("semantic_rank") for item in response["results"])
    ):
        raise RuntimeError("live query did not exercise lexical and semantic retrieval")

    database = pairing_root / "silver/retrieval.sqlite"
    metrics = {
        "paired": True,
        "bronze_items": len(bronze_items),
        "live_bronze": live_bronze,
        "silver_sources": len(published),
        "silver_entities": entities,
        "silver_claims": claims,
        "retrieval_chunks": representation["chunks"],
        "retrieval_embeddings": representation["embeddings"],
        "retrieval_database_bytes": database.stat().st_size,
        "retrieval_latency_ms": round(latency_ms, 1),
        "lexical_candidates": response["lexical_count"],
        "semantic_candidates": response["semantic_count"],
        "hybrid_results": len(response["results"]),
        "query_sha256_prefix": query_hash,
    }
    print(json.dumps(metrics, sort_keys=True))
    if args.metrics_out:
        args.metrics_out.write_text(json.dumps(metrics, sort_keys=True), encoding="utf-8")


if __name__ == "__main__":
    main()
