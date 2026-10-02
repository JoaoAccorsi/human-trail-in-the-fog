"""Cloud Mobility Platform - rebuilds digital trails from the visits stored in the TMS.

    streamlit run mobility_platform.py

Two kinds of query (the data always comes from the TMS, which reads cloud/tms/data/visits.jsonl):
  - User trail: the authorized user provides the uid and the period (day and time range); the
    pseudonym h(s, e) is computed here, on the client, and only then is the TMS queried (the
    cloud never receives the uid).
  - People at a Fog node: who passed through Fog F on day D and the trail of each one.
    Here the platform only sees pseudonyms (city manager view).
"""

from datetime import time

import pandas as pd
import requests
import streamlit as st

import config
import trail as tr

st.set_page_config(page_title="HTF - Cloud Mobility Platform", layout="wide")
st.title("HTF · Cloud Mobility Platform")
st.caption("Rebuilds the digital trail from the visits sent by the Fog nodes to the TMS")

MIXED_VERSIONS_WARNING = (
    "**The TMS still has visits from an old version of the Fog** (areas of different "
    "sizes), so the query mixes different runs.\n\n"
    "To clean up: delete the file `cloud/tms/data/visits.jsonl` and run the Fog and the Edge again."
)
USER_TRAIL = "User trail"
PEOPLE_AT_FOG = "People at a Fog node"


def tms_error(url: str, e: Exception):
    st.error(f"Could not query the TMS at {url}: {e}")
    st.stop()


with st.sidebar:
    st.header("Query")
    mode = st.radio("Query type", [USER_TRAIL, PEOPLE_AT_FOG])
    day = st.number_input("Day", min_value=0, max_value=74, value=config.DEFAULT_DAY, step=1)
    url = st.text_input("TMS URL", value=config.TMS_URL)

    if mode == USER_TRAIL:
        user = st.number_input("User (uid)", min_value=0, value=config.DEFAULT_USER, step=1)
        salt = st.text_input("Salt (pseudonym secret)", value=config.SALT, type="password")
        slots = [time(h, m) for h in range(24) for m in (0, 30)]
        t_from, t_to = st.select_slider("Period", options=slots, value=(time(0, 0), time(23, 30)),
                                        format_func=lambda t: t.strftime("%H:%M"))
        start, end = t_from.strftime("%H:%M"), t_to.strftime("%H:%M")
    else:
        try:
            fogs_of_day = tr.list_fogs(int(day), url)
        except requests.RequestException:
            fogs_of_day = []
        if fogs_of_day:
            options = {f"Fog {f['fog']}  ·  {f['people']} person(s)": f["fog"] for f in fogs_of_day}
            fog = options[st.selectbox("Fog node", list(options))]
        else:
            st.caption("No visits on this day (or the TMS is down).")
            fog = st.number_input("Fog node", min_value=1, value=1, step=1)

    run = st.button("Rebuild trail" if mode == USER_TRAIL else "Query Fog node", type="primary")

if not run:
    st.info("Choose a query in the sidebar and click the button.")
    st.stop()

# ================================================================ user trail

if mode == USER_TRAIL:
    h = tr.pseudonym(int(user), salt)
    st.write("Queried pseudonym:")
    st.code(h, language=None)
    try:
        trail = tr.fetch_trail(h, int(day), url, start, end)
    except requests.RequestException as e:
        tms_error(url, e)
    if not trail:
        st.warning("No visits found. Run the Edge and the Fog for this user and day, "
                   "or widen the period (or check that the salt is the same as the Edge's).")
        st.stop()
    if len({tr.side_of(v) for v in trail}) > 1:
        st.warning(MIXED_VERSIONS_WARNING)

    s = tr.summary(trail)
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("Pings", s["pings"])
    c2.metric("Fog nodes visited", s["fogs"])
    c3.metric("Handovers", s["handovers"])
    c4.metric("Period", f"{s['start']} – {s['end']}")

    left, right = st.columns([1, 1])
    with left:
        st.pyplot(tr.trail_map(trail, f"Trail of user {user} on day {day}, {start}–{end}"))
    with right:
        st.pyplot(tr.timeline(trail))
        st.subheader("Visits to Fog nodes")
        st.dataframe(pd.DataFrame(tr.table(trail)), hide_index=True, use_container_width=True)

    with st.expander("GS1 EPCIS events received by the TMS"):
        try:
            events = tr.fetch_events(h, int(day), url, start, end)
        except requests.RequestException as e:
            events = []
            st.caption(f"Could not load the events: {e}")
        if events:
            st.dataframe(pd.DataFrame(tr.events_table(events)), hide_index=True, use_container_width=True)
            st.json(events, expanded=False)
        else:
            st.caption("No EPCIS events for this trail (visits recorded before EPCIS was added have none).")

# ================================================================ people at a Fog node

else:
    try:
        people = tr.people_at_fog(int(fog), int(day), url)
    except requests.RequestException as e:
        tms_error(url, e)
    if not people:
        st.warning(f"Nobody passed through Fog {fog} on day {day} (according to the visits in the TMS).")
        st.stop()
    if len({tr.side_of(v) for p in people for v in p["trail"]}) > 1:
        st.warning(MIXED_VERSIONS_WARNING)

    st.write(f"People who passed through **Fog {fog}** on day **{day}**.")
    s = tr.fog_summary(people)
    c1, c2, c3, c4 = st.columns(4)
    c1.metric("People", s["people"])
    c2.metric("Visits to the Fog", s["visits"])
    c3.metric("Pings at the Fog", s["pings"])
    c4.metric("Period", f"{s['start']} – {s['end']}")

    left, right = st.columns([1, 1])
    with left:
        st.pyplot(tr.fog_map(people, int(fog), f"Trails of people who passed through Fog {fog} on day {day}"))
    with right:
        st.pyplot(tr.fog_presence(people, int(fog)))
        st.subheader(f"Visits to Fog {fog}")
        st.dataframe(pd.DataFrame(tr.fog_table(people, int(fog))), hide_index=True, use_container_width=True)
