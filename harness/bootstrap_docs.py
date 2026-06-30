#!/usr/bin/env python3
import argparse
import json
import mimetypes
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def encode_multipart(field_name: str, file_path: Path, boundary: str):
    content_type = mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"
    data = file_path.read_bytes()
    lines = [
        f"--{boundary}\r\n".encode("utf-8"),
        (
            f'Content-Disposition: form-data; name="{field_name}"; '
            f'filename="{file_path.name}"\r\n'
        ).encode("utf-8"),
        f"Content-Type: {content_type}\r\n\r\n".encode("utf-8"),
        data,
        b"\r\n",
        f"--{boundary}--\r\n".encode("utf-8"),
    ]
    return b"".join(lines)


def upload_file(base_url: str, file_path: Path, timeout: int):
    boundary = "----DevAssistBenchmarkBoundary"
    body = encode_multipart("file", file_path, boundary)
    request = Request(
        base_url.rstrip("/") + "/api/upload",
        data=body,
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
        },
        method="POST",
    )
    with urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8")
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        parsed = {"raw": raw}
    return response.status, parsed


def main():
    parser = argparse.ArgumentParser(description="Upload aiops-docs into DevAssist knowledge base")
    parser.add_argument("--base-url", default="http://localhost:9900")
    parser.add_argument("--docs-dir", default=str(Path(__file__).resolve().parent.parent / "aiops-docs"))
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    docs_dir = Path(args.docs_dir)
    files = sorted(list(docs_dir.glob("*.md")) + list(docs_dir.glob("*.markdown")))
    if not files:
        print(f"No markdown docs found in {docs_dir}")
        return 2

    failed = 0
    for file_path in files:
        print(f"[UPLOAD] {file_path.name}")
        try:
            status, response = upload_file(args.base_url, file_path, args.timeout)
            ok = status == 200 and response.get("code", 200) == 200
            print(f"  - {'OK' if ok else 'FAIL'} status={status}")
            if not ok:
                failed += 1
                print(f"  - response={json.dumps(response, ensure_ascii=False)[:500]}")
        except (HTTPError, URLError, TimeoutError, Exception) as exc:
            failed += 1
            print(f"  - FAIL {exc}")

    print()
    print(f"Uploaded: {len(files) - failed}/{len(files)}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())

