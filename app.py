from flask import Flask, jsonify
import os
import json

app = Flask(__name__)

BASE_DIR = "/app"  # Docker container path; volume-mounted from NAS
SYNOLOGY_FILE = os.path.join(BASE_DIR, "synology_data.json")


def read_json_file(filepath):
    try:
        with open(filepath, "r") as f:
            return json.load(f)
    except Exception as e:
        return {"error": str(e)}


@app.route("/synology", methods=["GET"])
def get_synology_data():
    return jsonify(read_json_file(SYNOLOGY_FILE))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
