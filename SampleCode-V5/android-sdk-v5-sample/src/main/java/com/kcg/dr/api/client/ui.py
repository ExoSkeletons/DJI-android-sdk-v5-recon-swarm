import os
from typing import Callable, Self

import questionary


# ============================================================
# Generic helpers
# ============================================================

def clear():
    os.system("cls" if os.name == "nt" else "clear")


def printc(*args, **kwargs):
    clear()
    print(*args, **kwargs)


def pause():
    input("\nPress ENTER to continue...")


# ============================================================
# Generic menu
# ============================================================
class Menu:
    def __init__(self, title: str, options: dict[str, Callable | Self | None]):
        self.title = title
        self.options = options

    def run(self, prev=None, no_back: bool = False):
        options = dict(self.options)
        if not no_back:
            options.update({
                "Back" if prev is not None else "Quit":
                    None
            })

        while True:
            clear()

            print(f"=== {self.title} ===")
            print()

            choice = questionary.select(
                "Select an option:",
                choices=[label for label, v in options.items()]
            ).ask()

            # User pressed Ctrl+C / cancelled
            if choice is None:
                return

            # Get selection
            if not choice in options.keys():
                continue
            selection = options.get(choice)

            # None means "Back"
            if selection is None:
                return

            if isinstance(selection, Menu):
                menu: Menu = selection
                menu.run(prev=self)
                continue

            selection()
