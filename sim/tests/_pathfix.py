"""Shared bootstrap: put sim/ on sys.path so tests can `import simulation`
etc. without the project being installed as a package (script-shaped repo)."""
import os
import sys

SIM_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if SIM_DIR not in sys.path:
    sys.path.insert(0, SIM_DIR)
