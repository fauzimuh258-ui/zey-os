import gzip
import logging
import logging.handlers
import os
import shutil

import requests
from flask import Flask, request, jsonify, Response

from security import get_or_create_api_key, require_api_key, rate_limited, TokenBucketRateLimiter

OLLAMA_API = "http://127.0.0.1:11434"
LOG_DIR = os.path.join(os.path.expanduser("~"), ".zeyos_logs")
os.makedirs(LOG_DIR, exist_ok=True)


def _gzip_rotator(source, dest):
    with open(source, "rb") as sf, gzip.open(dest, "wb") as df:
        shutil.copyfileobj(sf, df)
    os.remove(source)


handler = logging.handlers.RotatingFileHandler(
    os.path.join(LOG_DIR, "proxy.log"), maxBytes=512 * 1024, backupCount=5
)
handler.rotator = _gzip_rotator
handler.namer = lambda name: name + ".gz"
logging.basicConfig(level=logging.INFO, handlers=[handler, logging.StreamHandler()],
                     format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("zeyos.proxy")

app = Flask(__name__)
API_KEY = get_or_create_api_key()
limiter = TokenBucketRateLimiter(capacity=10, refill_per_second=5.0)

REQUEST_TIMEOUT = 30


@app.route("/health", methods=["GET"])
def health():
    try:
        r = requests.get(f"{OLLAMA_API}/api/tags", timeout=3)
        ollama_up = r.status_code == 200
    except requests.exceptions.RequestException:
        ollama_up = False
    return jsonify({"proxy": "ok", "ollama": "ok" if ollama_up else "unreachable"})


@app.route("/api/<path:subpath>", methods=["GET", "POST", "DELETE"])
@require_api_key(API_KEY)
@rate_limited(limiter)
def proxy(subpath):
    target_url = f"{OLLAMA_API}/api/{subpath}"
    logger.info(f"{request.method} /api/{subpath} from {request.remote_addr}")

    try:
        resp = requests.request(
            method=request.method,
            url=target_url,
            json=request.get_json(silent=True),
            timeout=REQUEST_TIMEOUT,
        )
        return Response(resp.content, status=resp.status_code, content_type=resp.headers.get("Content-Type"))
    except requests.exceptions.Timeout:
        logger.error(f"Timeout forwarding to {target_url}")
        return jsonify({"error": "upstream_timeout"}), 504
    except requests.exceptions.ConnectionError:
        logger.error(f"Ollama unreachable at {target_url}")
        return jsonify({"error": "ollama_unreachable"}), 502
    except Exception as e:
        logger.error(f"Unexpected proxy error: {e}")
        return jsonify({"error": "internal_error"}), 500


if __name__ == "__main__":
    logger.info(f"Zey OS proxy starting. API key: {API_KEY[:12]}... (see ~/.zeyos_api_key)")
    app.run(host="127.0.0.1", port=8787)
