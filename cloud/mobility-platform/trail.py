"""Cloud Mobility Platform functions: pseudonym, TMS (Java) queries and charts."""

import hashlib
import hmac

import matplotlib
import requests

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib.patches import Rectangle  # noqa: E402

import config  # noqa: E402


def pseudonym(uid: int, salt: str = config.SALT) -> str:
    """h(s, e) = HMAC-SHA256(salt, uid) - same as edge.EdgeApp.pseudonym (Java).
    Re-identification happens here, on the client: the TMS never sees the uid."""
    return hmac.new(salt.encode(), str(uid).encode(), hashlib.sha256).hexdigest()


def fetch_trail(h: str, day: int, url: str = config.TMS_URL) -> list[dict]:
    resp = requests.get(f"{url}/trail/{h}", params={"day": day}, timeout=5)
    resp.raise_for_status()
    return resp.json()


def fetch_events(h: str, day: int, url: str = config.TMS_URL) -> list[dict]:
    """GS1 EPCIS 2.0 events (arriving/departing) the Fogs sent for this pseudonym and day."""
    resp = requests.get(f"{url}/events/{h}", params={"day": day}, timeout=5)
    resp.raise_for_status()
    return resp.json()


def events_table(events: list[dict]) -> list[dict]:
    """One row per EPCIS event: what, when, where, why."""
    return [{
        "eventTime": e["eventTime"],
        "bizStep": e["bizStep"],
        "readPoint (Fog)": e["readPoint"]["id"],
        "bizLocation (cell)": e["bizLocation"]["id"],
        "epc": e["epcList"][0][:30] + "…",
    } for e in events]


def summary(trail: list[dict]) -> dict:
    return {
        "pings": sum(v["pings"] for v in trail),
        "fogs": len({v["fog"] for v in trail}),
        "handovers": sum(1 for v in trail if v["reason"] == "handover"),
        "start": trail[0]["arrival"],
        "end": trail[-1]["departure"],
    }


def table(trail: list[dict]) -> list[dict]:
    return [{
        "#": i,
        "Fog": v["fog"],
        "arrival": v["arrival"],
        "departure": v["departure"],
        "pings": v["pings"],
        "cells": " → ".join(f"({x},{y})" for x, y in v["cells"]),
        "ended by": "handover → Fog %d" % v["nextFog"] if v.get("nextFog") else v["reason"],
    } for i, v in enumerate(trail, 1)]


def _area(fog: int, fog_size: int) -> tuple[float, float]:
    per_axis = config.GRID // fog_size
    return ((fog - 1) % per_axis) * fog_size + 0.5, ((fog - 1) // per_axis) * fog_size + 0.5


def trail_map(trail: list[dict], title: str):
    """Map: areas of the visited Fogs + the cell-by-cell path (zoomed on the trail)."""
    points = [tuple(c) for v in trail for c in v["cells"]]
    xs, ys = [c[0] for c in points], [c[1] for c in points]
    margin = 4
    x0v, x1v = min(xs) - margin, max(xs) + margin
    y0v, y1v = min(ys) - margin, max(ys) + margin
    colors = plt.cm.tab10.colors
    fig, ax = plt.subplots(figsize=(7, 7))
    # each visit carries the area size of the Fog that produced it
    fogs = dict.fromkeys((v["fog"], v["fogSize"]) for v in trail)
    for i, (fog, fog_size) in enumerate(fogs):
        x0, y0 = _area(fog, fog_size)
        ax.add_patch(Rectangle((x0, y0), fog_size, fog_size, alpha=0.25, color=colors[i % 10]))
        # Fog name at the center of the visible part of the area (only if the area shows on the map)
        vx0, vx1 = max(x0, x0v), min(x0 + fog_size, x1v)
        vy0, vy1 = max(y0, y0v), min(y0 + fog_size, y1v)
        if vx0 < vx1 and vy0 < vy1:
            ax.text((vx0 + vx1) / 2, (vy0 + vy1) / 2, f"Fog {fog}", ha="center", va="center",
                    fontsize=11, alpha=0.6, weight="bold", clip_on=True)
    ax.plot(xs, ys, "-o", color="black", markersize=4, linewidth=1)
    ax.plot(xs[0], ys[0], "o", color="green", markersize=12, label=f"start {trail[0]['arrival']}")
    ax.plot(xs[-1], ys[-1], "o", color="red", markersize=12, label=f"end {trail[-1]['departure']}")
    ax.set_xlim(x0v, x1v)
    ax.set_ylim(y0v, y1v)
    ax.set_aspect("equal")
    ax.set_title(title)
    ax.set_xlabel("x (500 m cells)")
    ax.set_ylabel("y (500 m cells)")
    ax.legend(loc="best")
    fig.tight_layout()
    return fig


def timeline(trail: list[dict]):
    """Timeline: which Fog the user was in at each time of the day."""
    fogs = list(dict.fromkeys(v["fog"] for v in trail))
    colors = plt.cm.tab10.colors
    fig, ax = plt.subplots(figsize=(9, 0.5 * len(fogs) + 1.5))
    for v in trail:
        row = fogs.index(v["fog"])
        start = v["arrivalT"] / 2                 # timeslot -> hour
        duration = (v["departureT"] - v["arrivalT"] + 1) / 2
        ax.barh(row, duration, left=start, color=colors[row % 10], edgecolor="black")
    ax.set_yticks(range(len(fogs)), [f"Fog {f}" for f in fogs])
    ax.invert_yaxis()
    ax.set_xlim(0, 24)
    ax.set_xticks(range(0, 25, 2), [f"{h:02d}h" for h in range(0, 25, 2)])
    ax.set_xlabel("time of day")
    ax.grid(axis="x", alpha=0.3)
    ax.set_title("Time spent in each Fog node")
    fig.tight_layout()
    return fig


# ====================================================================== query by Fog node

def list_fogs(day: int, url: str = config.TMS_URL) -> list[dict]:
    """Fog nodes with visits on the day: [{fog, people, visits}]."""
    resp = requests.get(f"{url}/fogs", params={"day": day}, timeout=5)
    resp.raise_for_status()
    return resp.json()


def people_at_fog(fog: int, day: int, url: str = config.TMS_URL) -> list[dict]:
    """Who passed through the Fog on the day. For each pseudonym: the visits to this Fog and the day's trail.
    The platform only sees pseudonyms - without the salt and the uid there is no way to know who the person is."""
    resp = requests.get(f"{url}/visits", params={"fog": fog, "day": day}, timeout=5)
    resp.raise_for_status()
    at_fog: dict[str, list[dict]] = {}
    for v in resp.json():
        at_fog.setdefault(v["h"], []).append(v)
    people = []
    for i, (h, visits) in enumerate(at_fog.items(), 1):
        people.append({
            "label": f"Person {i} ({h[:8]}…)",
            "h": h,
            "at_fog": visits,
            "trail": fetch_trail(h, day, url),
        })
    return people


def fog_summary(people: list[dict]) -> dict:
    visits = [v for p in people for v in p["at_fog"]]
    return {
        "people": len(people),
        "visits": len(visits),
        "pings": sum(v["pings"] for v in visits),
        "start": min(v["arrival"] for v in visits),
        "end": max(v["departure"] for v in visits),
    }


def fog_table(people: list[dict], fog: int) -> list[dict]:
    """One row per visit to the Fog: where the person came from and where they went."""
    rows = []
    for p in people:
        trail = p["trail"]
        for v in p["at_fog"]:
            i = next(k for k, t in enumerate(trail) if t["arrivalT"] == v["arrivalT"] and t["fog"] == fog)
            rows.append({
                "person": p["label"],
                "arrival": v["arrival"],
                "departure": v["departure"],
                "pings": v["pings"],
                "came from": f"Fog {trail[i - 1]['fog']}" if i > 0 else "start of day",
                "went to": f"Fog {v['nextFog']}" if v.get("nextFog") else "end of day",
                "Fogs that day": len({t["fog"] for t in trail}),
            })
    return sorted(rows, key=lambda r: r["arrival"])


def fog_map(people: list[dict], fog: int, title: str):
    """Map: the queried Fog highlighted, the other visited Fogs and each person's trail."""
    colors = plt.cm.tab10.colors
    everything = [tuple(c) for p in people for v in p["trail"] for c in v["cells"]]
    xs, ys = [c[0] for c in everything], [c[1] for c in everything]
    fog_size = people[0]["at_fog"][0]["fogSize"]
    fx, fy = _area(fog, fog_size)
    margin = 4
    x0v, x1v = min(min(xs), fx) - margin, max(max(xs), fx + fog_size) + margin
    y0v, y1v = min(min(ys), fy) - margin, max(max(ys), fy + fog_size) + margin

    fig, ax = plt.subplots(figsize=(7, 7))
    visited = dict.fromkeys((v["fog"], v["fogSize"]) for p in people for v in p["trail"])
    for f, size in visited:
        x0, y0 = _area(f, size)
        highlight = f == fog
        ax.add_patch(Rectangle((x0, y0), size, size, facecolor="#f2c94c" if highlight else "#dddddd",
                               alpha=0.35 if highlight else 0.25, edgecolor="#b8860b" if highlight else "#999999",
                               linewidth=2 if highlight else 0.8))
        vx0, vx1 = max(x0, x0v), min(x0 + size, x1v)
        vy0, vy1 = max(y0, y0v), min(y0 + size, y1v)
        if vx0 < vx1 and vy0 < vy1:
            ax.text(vx0 + 1, vy1 - 1, f"Fog {f}", ha="left", va="top", fontsize=10,
                    alpha=0.8 if highlight else 0.5, weight="bold", clip_on=True)

    for i, p in enumerate(people):
        color = colors[i % 10]
        pts = [tuple(c) for v in p["trail"] for c in v["cells"]]
        px, py = [c[0] for c in pts], [c[1] for c in pts]
        ax.plot(px, py, "-o", color=color, markersize=3, linewidth=1.5, label=p["label"])
        ax.plot(px[0], py[0], "o", color=color, markersize=9, markeredgecolor="white")    # start
        ax.plot(px[-1], py[-1], "s", color=color, markersize=9, markeredgecolor="white")  # end

    ax.set_xlim(x0v, x1v)
    ax.set_ylim(y0v, y1v)
    ax.set_aspect("equal")
    ax.set_title(title)
    ax.set_xlabel("x (500 m cells)")
    ax.set_ylabel("y (500 m cells)")
    ax.legend(loc="best", fontsize=8, title="● start  ■ end", title_fontsize=8)
    fig.tight_layout()
    return fig


def fog_presence(people: list[dict], fog: int):
    """Timeline: when each person was at the queried Fog (colored) and in the rest of the day (gray)."""
    colors = plt.cm.tab10.colors
    fig, ax = plt.subplots(figsize=(9, 0.55 * len(people) + 1.6))
    for i, p in enumerate(people):
        for v in p["trail"]:
            start = v["arrivalT"] / 2
            duration = (v["departureT"] - v["arrivalT"] + 1) / 2
            here = v["fog"] == fog
            ax.barh(i, duration, left=start, height=0.6 if here else 0.3,
                    color=colors[i % 10] if here else "#cccccc", edgecolor="white", linewidth=1)
    ax.set_yticks(range(len(people)), [p["label"] for p in people])
    ax.invert_yaxis()
    ax.set_xlim(0, 24)
    ax.set_xticks(range(0, 25, 2), [f"{h:02d}h" for h in range(0, 25, 2)])
    ax.set_xlabel("time of day")
    ax.grid(axis="x", alpha=0.3)
    ax.set_title(f"Presence at Fog {fog} (colored) and at other Fogs (gray)")
    fig.tight_layout()
    return fig
