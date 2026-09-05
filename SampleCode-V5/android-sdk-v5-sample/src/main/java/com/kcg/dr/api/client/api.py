from typing import Callable, Any

import keyboard
import requests
import websockets
from config import ADDRESS, PORT

BASE_URL = f"http://{ADDRESS}:{PORT}"

def get(path):
    try:
        response = requests.get(BASE_URL + path, timeout=5)

        try:
            return response.status_code, response.json()
        except ValueError:
            return response.status_code, response.text

    except requests.RequestException as e:
        return None, str(e)


def post(path, data=None):
    try:
        response = requests.post(
            BASE_URL + path,
            json=data,
            timeout=10
        )

        try:
            return response.status_code, response.json()
        except ValueError:
            return response.status_code, response.text

    except requests.RequestException as e:
        return None, str(e)

async def ws_connect(
        path,
        on_msg:Callable[[str | bytes], Any],
        exit_key ='x',
        pre_recv: Callable[[], Any] = None,
        on_error: Callable[[Exception], Any] | None = None
):
        uri = (
                BASE_URL
                .replace("http://", "ws://")
                .replace("https://", "wss://")
                + path
        )

        try:
            async with websockets.connect(uri) as ws:
                # Initial message from server
                await ws.recv()

                while True:
                    if keyboard.is_pressed(exit_key):  # if exit key is pressed
                        break

                    if pre_recv: pre_recv()

                    message = await ws.recv()

                    try:
                        on_msg(message)
                    except RuntimeError as e:
                        if on_error: on_error(e)
        except websockets.ConnectionClosed:
            print(f"{uri}")
            print(f"connection closed.")

        except Exception as e:
            print(f"{uri}")
            print(f"error: {e}")
            if on_error: on_error(e)