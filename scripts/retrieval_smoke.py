#!/usr/bin/env python3
"""Run repeatable hybrid-retrieval queries against Source's loopback debug API."""

import argparse
import json
import time
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("query", nargs="+", help="one or more queries to run")
    parser.add_argument("--url", default="http://127.0.0.1:8081", help="Source setup URL")
    parser.add_argument("--limit", type=int, default=5)
    args = parser.parse_args()
    if not 1 <= args.limit <= 50:
        parser.error("--limit must be between 1 and 50")

    for query in args.query:
        parameters = urllib.parse.urlencode({"q": query, "limit": args.limit})
        started = time.perf_counter()
        with urllib.request.urlopen(
            f"{args.url.rstrip('/')}/retrieval?{parameters}", timeout=300
        ) as response:
            result = json.load(response)
        elapsed_ms = (time.perf_counter() - started) * 1000
        print(f"\n{query!r} — {elapsed_ms:.1f} ms")
        print(
            f"lexical={result['lexical_count']} semantic={result['semantic_count']} "
            f"semantic_state={result['semantic_state']}"
        )
        if result.get("semantic_error"):
            print(f"semantic_error={result['semantic_error']}")
        for index, item in enumerate(result["results"], 1):
            channels = []
            if item.get("lexical_rank"):
                channels.append(f"lexical#{item['lexical_rank']}")
            if item.get("semantic_rank"):
                channels.append(
                    f"semantic#{item['semantic_rank']} distance={item['semantic_distance']:.4f}"
                )
            excerpt = " ".join(item["text"].split())[:160]
            print(
                f"{index}. score={item['score']:.6f} {' '.join(channels)} "
                f"source={item['bronze_title']!r} evidence={item['evidence_id'][:12]} {excerpt!r}"
            )


if __name__ == "__main__":
    main()
