"""Cloud Mobility Platform configuration."""

# Trail Management Service (Java, folder cloud/tms/)
TMS_URL = "http://localhost:8080"

# Secret s of the pseudonym h(s, e) - the SAME as the Edge (edge/src/main/java/edge/Config.java).
# The cloud stores no real ids; only whoever has the salt can find the trail of a uid.
SALT = "htf-dev-salt"

# Initial values of the interface
DEFAULT_USER = 4000
DEFAULT_DAY = 20

GRID = 200
