import asyncio
import json
from typing import Any

import questionary

import api
from flight import MISSIONS
from ui import clear, pause, Menu, printc


def print_response(code, response):
    if code: print(code)
    if response: print(json.dumps(response, indent=2))


def post_and_print(path: str, body: Any | None = None):
    code, response = api.post(path, data=body)
    print_response(code, response)
    pause()


# ============================================================
# Main menu actions
# ============================================================

def takeoff():
    clear()

    print("TAKEOFF")

    code, response = api.get("/takeoff")
    print_response(code, response)

    pause()


def land():
    printc("LAND")

    code, response = api.get("/land")
    print_response(code, response)

    pause()


def hello():
    printc("HELLO")

    code, response = api.get("/c/hello")
    print_response(code, response)

    pause()


def status():
    clear()

    print("=== STATUS ===")
    print()

    print("════════════════════════════════════")
    print("             DRONE STATUS")
    print("════════════════════════════════════")

    status_code, data = api.get("/status/")
    print_response(status_code, data)

    print("\n── Battery ─────────────────────────")

    status_code, data = api.get("/status/battery")
    print_response(status_code, data)

    print("\n── GPS ─────────────────────────────")

    status_code, data = api.get("/status/gps")
    print_response(status_code, data)

    print("\n── Signal ──────────────────────────")

    status_code, data = api.get("/status/signal")
    print_response(status_code, data)

    pause()


# ============================================================
# Telemetry
# ============================================================

async def telemetry_screen():
    def on_msg(message: str | bytes):
        clear()

        print("=== LIVE TELEMETRY ===")
        print()
        print("Press X to return")
        print()

        telemetry = json.loads(message)

        battery_percent = int(telemetry["battery"])
        b1, b0, bf = "█", "░", 0.2
        location = telemetry["location"]
        velocity = telemetry["velocity"]

        text = \
            f"""
              BATTERY
              {b1 * int(battery_percent * bf)}{b0 * int((100 - battery_percent) * bf)} {battery_percent}%
            
              POSITION
              Latitude       {location["latitude"]}
              Longitude      {location["longitude"]}
              Altitude       {round(location["altitude"], 2)} m
            
              VELOCITY
              X              {round(velocity['x'], 2)} m/s
              Y              {round(velocity['y'], 2)} m/s
              Z              {round(velocity['z'], 2)} m/s
            """

        print(text)
        # print(json.dumps(telemetry, indent=2))

    await api.ws_connect("/c/ws/telemetry", on_msg=on_msg, )

    pause()


def telemetry():
    asyncio.run(telemetry_screen())


# ============================================================
# Stream submenu
# ============================================================

def start_stream():
    clear()

    print("=== START RTMP STREAM ===")
    print()

    url = questionary.text(
        "RTMP URL:"
    ).ask()

    if url is None:
        return

    if not url.strip():
        print("No URL supplied.")
        pause()
        return

    # TODO:
    #
    # response = api.post(
    #     "/c/stream/start",
    #     {"rtmpUrl": url}
    # )
    #
    # show_response(response)

    print(f"\nStarting stream: {url}")

    pause()


def stop_stream():
    printc("Stopping stream...")
    post_and_print("/c/stream/stop")


def stream_status():
    printc("Stream status...")
    post_and_print("/c/stream/status")


stream_menu = Menu(
    "STREAM",
    {
        "Start RTMP": start_stream,
        "Stop": stop_stream,
        "Status": stream_status,
    })

# ============================================================
# Basic Flight submenu
# ============================================================


flight_menu = Menu(
    "FLIGHT",
    {
        'up': lambda: post_and_print("/c/fly/", {'type': 'fly_by', 'dz': 0.5}),
        'down': lambda: post_and_print("/c/fly/", {'type': 'fly_by', 'dz': -0.5}),
        'left': lambda: post_and_print("/c/fly/", {'type': 'fly_by', 'dy': -0.5}),
        'right': lambda: post_and_print("/c/fly/", {'type': 'fly_by', 'dy': 0.5}),
        'forward': lambda: post_and_print("/c/fly/", {'type': 'fly_by', 'dx': 0.5}),
        'backward': lambda: post_and_print("/c/fly/", {'type': 'fly_by', 'dx': -0.5}),
        'spin': lambda: post_and_print("/c/fly/", {'type': 'spin_by', 'degrees': 90.0}),
    }
)


# ============================================================
# Mission submenu
# ============================================================


def execute_mission(mission: dict):
    clear()

    print(f"=== {mission['name']} ===")
    print()

    print(json.dumps(mission["actions"], indent=2))
    print()

    confirm = questionary.confirm(
        "Execute this mission?",
        default=False
    ).ask()

    if not confirm:
        return

    fly_request = {
        "actions": list(mission["actions"])
    }
    code, response = api.post("/c/fly", data=fly_request)
    print_response(code, response)

    print("\nStarting mission...")

    pause()


mission_menu = Menu(
    "MISSIONS",
    {
        mission["name"]: lambda m=mission: execute_mission(m)
        for mission in MISSIONS
    }
)

# ============================================================
# Main menu
# ============================================================

main_menu = Menu(
    "DRONE API",
    {
        "Hello": hello,
        "Takeoff": takeoff,
        "Land": land,
        "Flight": flight_menu,
        "Status": status,
        "Telemetry": telemetry,
        "Stream": stream_menu,
        "Missions": mission_menu,
    }
)

# ============================================================
# Entry point
# ============================================================

if __name__ == "__main__":
    try:
        main_menu.run()

    except KeyboardInterrupt:
        clear()
        print("Exiting...")
