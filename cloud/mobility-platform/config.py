"""Cloud Mobility Platform configuration (HTF_* environment variables override the defaults)."""

import os

# Trail Management Service (Java, folder cloud/tms/)
TMS_URL = os.environ.get("HTF_TMS_URL", "http://localhost:8080")

# Secret s of the pseudonym h(s, e) - the SAME as the Edge (edge/src/main/java/edge/Config.java).
# The cloud stores no real ids; only whoever has the salt can find the trail of a uid.
SALT = os.environ.get("HTF_SALT", "htf-dev-salt")

# Login of the interface (the Cloud Mobility Platform may be open to the internet):
# HTF_CMP_USER / HTF_CMP_PASSWORD override these defaults.
CMP_USER = os.environ.get("HTF_CMP_USER", "htf")
CMP_PASSWORD = os.environ.get("HTF_CMP_PASSWORD", "htf")

# Initial values of the interface
DEFAULT_USER = 4000
DEFAULT_DAY = 20

GRID = 200
