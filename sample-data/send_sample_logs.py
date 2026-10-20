"""Send the demo log events in application-logs.jsonl to the ingestion API.

Usage:
    python sample-data/send_sample_logs.py [--url http://localhost:8081]

Uses only the Python standard library. Every run stores the events again, as new rows.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

SAMPLE_FILE = Path(__file__).with_name("application-logs.jsonl")


def send(base_url: str, event: dict) -> dict:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/v1/logs",
        data=json.dumps(event).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--url", default="http://localhost:8081", help="ingestion-service base URL"
    )
    args = parser.parse_args()

    events = [
        json.loads(line)
        for line in SAMPLE_FILE.read_text(encoding="utf-8").splitlines()
        if line
    ]
    for event in events:
        try:
            result = send(args.url, event)
        except urllib.error.HTTPError as error:
            print(
                f"Rejected ({error.code}): {error.read().decode('utf-8')}",
                file=sys.stderr,
            )
            return 1
        except urllib.error.URLError as error:
            print(f"Cannot reach {args.url}: {error.reason}", file=sys.stderr)
            return 1
        print(
            f"{result['id']}  {event['severity']:<5}  {event['service']:<17}  "
            f"fingerprint {result['fingerprint'][:12]}"
        )
    print(f"Sent {len(events)} demo events.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
